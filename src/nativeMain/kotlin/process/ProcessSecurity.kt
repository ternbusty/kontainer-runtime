package process

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import seccomp.initializeSeccomp
import spec.LinuxSeccomp
import spec.Process
import syscall.Syscall

/*
 * Shared OCI spec.process security setup, used by both the create path
 * (InitProcess) and the exec command so that a process entering a container
 * is restricted by exactly the same code regardless of how it got there.
 */

/**
 * Apply the spec.process security profile to the CURRENT process, in the
 * mandatory order:
 *
 * 0. oom_score_adj — lowering it below the inherited value requires
 *    CAP_SYS_RESOURCE, so it must precede setuid
 * 1. umask
 * 2. no_new_privileges — must precede capability changes, and lets seccomp
 *    load without CAP_SYS_ADMIN
 * 3. seccomp — BEFORE dropping capabilities: seccomp(2) needs CAP_SYS_ADMIN
 *    unless PR_SET_NO_NEW_PRIVS is set, and an OCI default spec specifies
 *    seccomp without noNewPrivileges (so we cannot rely on NNP). The filter
 *    is inherited across the later capset / setuid / execve.
 * 4. capability bounding set (root privilege required) + PR_SET_KEEPCAPS to
 *    preserve capabilities across setuid
 * 5. additional (supplementary) groups — setgroups(2) needs CAP_SETGID
 * 6. setgid then setuid — once non-root, we can't setgid
 * 7. clear PR_SET_KEEPCAPS + apply effective/permitted/inheritable/ambient
 *    capabilities (as the target user)
 * 8. AppArmor profile / SELinux exec context — staged labels that take
 *    effect on the next execve
 *
 * rlimits are deliberately NOT applied here: a low RLIMIT_AS applied this
 * early could abort the runtime itself before execve. Callers apply them
 * dead-last, right before execvp (see the call sites).
 *
 * @param applySeccomp seam for unit tests; production uses [initializeSeccomp]
 * @param onSeccompNotifyFd invoked with the seccomp notify FD when the
 *   profile uses SCMP_ACT_NOTIFY; the callback must synchronize with
 *   whichever process forwards the FD to the OCI seccomp listener
 * @throws Exception on fatal failure (setgid/setuid, capability errors, ...)
 */
@OptIn(ExperimentalForeignApi::class)
fun applyProcessSecurity(
    syscall: Syscall,
    process: Process,
    seccomp: LinuxSeccomp?,
    applySeccomp: (LinuxSeccomp) -> Int? = ::initializeSeccomp,
    onSeccompNotifyFd: (Int) -> Unit,
) {
    process.oomScoreAdj?.let { adj ->
        val f =
            fopen("/proc/self/oom_score_adj", "w")
                ?: throw Exception("Failed to open /proc/self/oom_score_adj (errno=$errno)")
        try {
            if (fputs(adj.toString(), f) < 0) {
                throw Exception("Failed to write oom_score_adj=$adj (errno=$errno)")
            }
        } finally {
            fclose(f)
        }
        Logger.debug("set oom_score_adj to $adj")
    }

    // Set umask (default 0o022)
    val umaskValue = process.user.umask ?: 0x12u // 0x12 = 0o022 (octal)
    syscall.umask(umaskValue)
    Logger.debug("set umask to ${umaskValue.toString(8)}")

    // Set no_new_privileges if specified.
    // Prevents the process from gaining new privileges through execve. Must be set
    // before applying capabilities.
    if (process.noNewPrivileges == true) {
        syscall.setNoNewPrivileges()
    }

    seccomp?.let { profile ->
        val notifyFd = applySeccomp(profile)
        if (notifyFd != null) {
            onSeccompNotifyFd(notifyFd)
        }
    }

    // Capability ordering:
    // 1. Apply bounding set (root privilege required)
    // 2. Set PR_SET_KEEPCAPS to preserve capabilities across setuid
    // 3. setgroups/setgid/setuid
    // 4. Clear PR_SET_KEEPCAPS
    // 5. Apply effective/permitted/inheritable/ambient capabilities (as non-root user)
    process.capabilities?.let { capabilities ->
        capability.applyBoundingSet(syscall, capabilities)
        capability.setKeepCaps(syscall)
    }

    // Set additional groups (supplementary groups) before dropping privileges.
    // Note: /proc/self/setgroups may be "deny" in unprivileged user namespaces (Linux 3.19+).
    process.user.additionalGids?.let { additionalGids ->
        if (additionalGids.isNotEmpty()) {
            Logger.debug("setting ${additionalGids.size} additional groups")
            syscall.setAdditionalGroups(additionalGids)
        }
    }

    // setgid must be called before setuid (once non-root, we can't setgid).
    val targetUid = process.user.uid
    val targetGid = process.user.gid

    if (syscall.setgid(targetGid) != 0) {
        perror("setgid")
        Logger.error("Failed to set GID to $targetGid")
        throw Exception("Failed to set GID to $targetGid")
    }
    if (syscall.setuid(targetUid) != 0) {
        perror("setuid")
        Logger.error("Failed to set UID to $targetUid")
        throw Exception("Failed to set UID to $targetUid")
    }
    Logger.debug("set UID=$targetUid GID=$targetGid for container process")

    // Apply remaining capabilities after setuid
    process.capabilities?.let { capabilities ->
        capability.clearKeepCaps(syscall)
        capability.applyCapabilities(syscall, capabilities)
    }

    // Apply AppArmor profile / SELinux exec context. Both are written to
    // /proc/self/attr/* files and take effect on the next execve.
    // - AppArmor: /proc/self/attr/apparmor/exec (newer) or .../exec (older);
    //   format is "exec <profile>" or "changeprofile <profile>".
    // - SELinux: /proc/self/attr/exec; format is the raw context label.
    // Failure is logged but non-fatal — the LSM may not be loaded.
    process.apparmorProfile?.let { profile ->
        val payload = "exec $profile"
        val written =
            listOf(
                "/proc/self/attr/apparmor/exec",
                "/proc/self/attr/exec",
            ).any { path ->
                try {
                    val fd = open(path, O_WRONLY)
                    if (fd < 0) return@any false
                    val bytes = payload.encodeToByteArray()
                    val ok =
                        bytes.usePinned { p ->
                            write(fd, p.addressOf(0), bytes.size.toULong()).toInt() == bytes.size
                        }
                    close(fd)
                    ok
                } catch (_: Throwable) {
                    false
                }
            }
        if (!written) {
            Logger.warn("failed to set apparmor profile '$profile'")
        } else {
            Logger.debug("staged apparmor exec profile: $profile")
        }
    }
    process.selinuxLabel?.let { label ->
        try {
            val fd = open("/proc/self/attr/exec", O_WRONLY)
            if (fd < 0) {
                Logger.warn("failed to open /proc/self/attr/exec for SELinux label (errno=$errno)")
            } else {
                val bytes = label.encodeToByteArray()
                bytes.usePinned { p ->
                    if (write(fd, p.addressOf(0), bytes.size.toULong()).toInt() != bytes.size) {
                        Logger.warn("short write of SELinux label '$label'")
                    }
                }
                close(fd)
                Logger.debug("staged SELinux exec label: $label")
            }
        } catch (e: Throwable) {
            Logger.warn("failed to set SELinux label: ${e.message}")
        }
    }
}

/**
 * Replace the current environment with the spec.process.env entries.
 * Clears every inherited (host) variable first so nothing leaks into the
 * container process.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyProcessEnv(env: List<String>) {
    clearenv()
    Logger.debug("cleared all host environment variables")

    env.forEach { envEntry ->
        val parts = envEntry.split("=", limit = 2)
        if (parts.size == 2) {
            val key = parts[0]
            val value = parts[1]
            if (setenv(key, value, 1) != 0) {
                perror("setenv")
                Logger.warn("failed to set environment variable: $key=$value")
            }
        } else {
            Logger.warn("invalid environment variable format: $envEntry")
        }
    }
    Logger.debug("set ${env.size} environment variables")
}
