package process

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import seccomp.initializeSeccomp
import spec.Spec
import syscall.Syscall

/**
 * Privilege-drop sequence shared by the init process and `exec`.
 *
 * Ordering constraints:
 * - rlimits first, while still root, so limits can be raised if requested
 * - no_new_privileges before capabilities
 * - seccomp before dropping capabilities: seccomp(2) needs CAP_SYS_ADMIN
 *   unless NNP is set, and the spec may omit noNewPrivileges
 * - bounding set before setuid (root required); effective/permitted/
 *   inheritable/ambient after setuid, bridged by PR_SET_KEEPCAPS
 * - LSM exec labels staged last; they take effect on the next execve
 *
 * [onSeccompNotifyFd] receives the notify FD when the filter uses
 * SCMP_ACT_NOTIFY. Callers without a consumer for it must reject such
 * specs up front; a filter whose notify FD nobody services blocks the
 * notified syscalls forever.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyProcessSecurity(
    syscall: Syscall,
    spec: Spec,
    onSeccompNotifyFd: ((Int) -> Unit)? = null,
) {
    syscall.applyRlimits(0, spec.process.rlimits)

    if (spec.process.noNewPrivileges == true) {
        syscall.setNoNewPrivileges()
    }

    spec.linux?.seccomp?.let { seccomp ->
        val notifyFd = initializeSeccomp(seccomp)
        if (notifyFd != null) {
            if (onSeccompNotifyFd == null) {
                throw Exception("seccomp filter uses SCMP_ACT_NOTIFY but no notify FD consumer is available")
            }
            onSeccompNotifyFd(notifyFd)
        }
    }

    spec.process.capabilities?.let { capabilities ->
        capability.applyBoundingSet(syscall, capabilities)
        capability.setKeepCaps(syscall)
    }

    // Set additional groups (supplementary groups) before dropping privileges.
    // Note: /proc/self/setgroups may be "deny" in unprivileged user namespaces (Linux 3.19+).
    spec.process.user.additionalGids?.let { additionalGids ->
        if (additionalGids.isNotEmpty()) {
            Logger.debug("setting ${additionalGids.size} additional groups")
            syscall.setAdditionalGroups(additionalGids)
        }
    }

    // setgid must be called before setuid (once non-root, we can't setgid).
    val targetUid = spec.process.user.uid
    val targetGid = spec.process.user.gid

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

    spec.process.capabilities?.let { capabilities ->
        capability.clearKeepCaps(syscall)
        capability.applyCapabilities(syscall, capabilities)
    }

    // Apply AppArmor profile / SELinux exec context. Both are written to
    // /proc/self/attr/* files and take effect on the next execve.
    // - AppArmor: /proc/self/attr/apparmor/exec (newer) or .../exec (older);
    //   format is "exec <profile>" or "changeprofile <profile>".
    // - SELinux: /proc/self/attr/exec; format is the raw context label.
    // Failure is logged but non-fatal — the LSM may not be loaded.
    spec.process.apparmorProfile?.let { profile ->
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
    spec.process.selinuxLabel?.let { label ->
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
