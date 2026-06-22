package process

import channel.InitReceiver
import channel.MainSender
import channel.NotifyListener
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import rootfs.applyLinuxDevices
import rootfs.applyMaskedPaths
import rootfs.applyReadonlyPaths
import rootfs.applyRootfsPropagation
import rootfs.applySpecMounts
import rootfs.applySysctls
import rootfs.pivotRoot
import rootfs.prepareRootfs
import rootfs.setRootfsReadonly
import seccomp.initializeSeccomp
import spec.Spec
import syscall.Syscall

/**
 * Init process (Stage-2 / PID 1 in container)
 *
 * This process runs as PID 1 in the new PID namespace (if configured).
 * It is created by bootstrap.c Stage-1 using CLONE_PARENT.
 *
 * This process:
 * - Runs as PID 1 in the container
 * - Sets up cgroup, user namespace mappings, rootfs, and seccomp
 * - Eventually calls execve() to become the container process
 * - Does NOT fork any additional processes
 */
@OptIn(ExperimentalForeignApi::class)
private fun initProcessInternal(
    syscall: Syscall,
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
): Unit =
    memScoped {
        Logger.setContext("init")
        Logger.debug("started, pid=${getpid()} ppid=${getppid()}")

        // All namespaces (user, mount, network, uts, ipc, pid) are unshared by bootstrap.c Stage-1
        // before the Kotlin runtime starts. UID/GID mapping was also completed by Stage-1.
        // See bootstrap.c for the full 2-stage protocol.
        Logger.debug("all namespaces already unshared by Stage-1, UID/GID mapping already done")
        Logger.debug("user namespace mapping already done by Stage-1, we are root in user NS")
        Logger.debug("session already created by bootstrap.c (sid=${getsid(0)})")

        // Bring up the loopback interface inside the container's network
        // namespace (when one is configured). Without this, the container has
        // no working network at all — `ping 127.0.0.1` fails, `bind(...)` to
        // 127.0.0.1 fails with EADDRNOTAVAIL, etc.
        if (spec.hasNamespace("network")) {
            if (platform.linux.set_loopback_up() != 0) {
                Logger.warn("failed to bring up loopback interface (errno=$errno)")
            } else {
                Logger.debug("brought up loopback interface")
            }
        }

        // Prepare rootfs
        if (spec.hasNamespace("mount")) {
            prepareRootfs(syscall, rootfsPath, spec.linux?.rootfsPropagation)
            // Process spec.mounts BEFORE pivot_root so bind-mount source paths from
            // the host are still reachable. Targets are inside rootfsPath.
            applySpecMounts(syscall, spec.mounts, rootfsPath)
            pivotRoot(syscall, rootfsPath)
            // Apply rootfsPropagation only AFTER pivot_root; the kernel forbids
            // pivot_root into a MS_SHARED subtree.
            applyRootfsPropagation(syscall, spec.linux?.rootfsPropagation)
        } else {
            Logger.debug("no mount namespace, skipping rootfs preparation")
        }

        // Change to working directory
        val cwd = spec.process.cwd
        if (syscall.chdir(cwd) != 0) {
            perror("chdir")
            Logger.warn("failed to chdir to $cwd")
        } else {
            Logger.debug("changed directory to $cwd")
        }

        // Set hostname (within UTS namespace).
        // Must be done BEFORE dropping privileges (setuid/setgid) because
        // sethostname() requires CAP_SYS_ADMIN.
        // See: runc/libcontainer/standard_init_linux.go:120-129
        spec.hostname?.let { hostname ->
            if (syscall.sethostname(hostname) != 0) {
                perror("sethostname")
                Logger.warn("failed to set hostname to $hostname")
            } else {
                Logger.debug("set hostname to $hostname")
            }
        }

        // Create spec.linux.devices[] device nodes inside the container's /dev.
        applyLinuxDevices(syscall, spec.linux?.devices)

        // Apply spec.linux.sysctl entries via /proc/sys/*. /proc is mounted by
        // prepareRootfs; we must do this while still root (writing /proc/sys
        // generally needs CAP_SYS_ADMIN or similar).
        applySysctls(spec.linux?.sysctl)

        // Mask and remount-readonly paths inside the container. Done after
        // prepareRootfs+pivotRoot (so the target paths exist inside the new
        // root) and before dropping caps (mount/remount need CAP_SYS_ADMIN).
        applyMaskedPaths(syscall, spec.linux?.maskedPaths)
        applyReadonlyPaths(syscall, spec.linux?.readonlyPaths)

        // Finalize rootfs (set readonly, umask).
        // Must be done BEFORE dropping privileges (setuid/setgid) because remounting
        // requires CAP_SYS_ADMIN.
        // See: runc/libcontainer/standard_init_linux.go:114-118
        finalizeRootfs(syscall, spec)

        // Prepare environment and FD handling
        val processArgs = spec.process.args
        val processEnv = spec.process.env?.toMutableList() ?: mutableListOf()

        // Handle LISTEN_FDS for systemd socket activation.
        // See https://www.freedesktop.org/software/systemd/man/sd_listen_fds.html
        val listenFds = getenv("LISTEN_FDS")?.toKString()?.toIntOrNull() ?: 0
        val preserveFds =
            if (listenFds > 0) {
                processEnv.add("LISTEN_FDS=$listenFds")
                processEnv.add("LISTEN_PID=1")
                Logger.debug("preserving $listenFds FDs for systemd socket activation")
                listenFds
            } else {
                0
            }

        // Apply rlimits to self. The main process also tries this against stage-1's
        // PID, but stage-1 may have already cloned stage-2 by then, so stage-2 would
        // inherit the host's defaults instead. Setting them here, on the init process
        // itself, guarantees the container sees the spec'd values. Done while still
        // root so RLIMIT_NICE / RLIMIT_NOFILE etc. can be raised if requested.
        syscall.applyRlimits(0, spec.process.rlimits)

        // Set no_new_privileges if specified.
        // Prevents the process from gaining new privileges through execve. Must be set
        // before applying capabilities.
        if (spec.process.noNewPrivileges == true) {
            syscall.setNoNewPrivileges()
        }

        // Load seccomp filter BEFORE dropping capabilities. seccomp(2) needs
        // CAP_SYS_ADMIN unless PR_SET_NO_NEW_PRIVS is set, and an OCI default
        // spec specifies seccomp without noNewPrivileges (so we cannot rely on
        // NNP). Installing the filter here, while we still hold CAP_SYS_ADMIN,
        // avoids the EPERM. The filter is inherited across the later capset /
        // setuid / execve, so the container process runs under it.
        spec.linux?.seccomp?.let { seccomp ->
            val notifyFd = initializeSeccomp(seccomp)
            syncSeccompNotifyFd(notifyFd, mainSender, initReceiver)
        }

        // Capability ordering:
        // 1. Apply bounding set (root privilege required)
        // 2. Set PR_SET_KEEPCAPS to preserve capabilities across setuid
        // 3. setgroups/setgid/setuid
        // 4. Clear PR_SET_KEEPCAPS
        // 5. Apply effective/permitted/inheritable/ambient capabilities (as non-root user)
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

        // Apply remaining capabilities after setuid
        spec.process.capabilities?.let { capabilities ->
            capability.clearKeepCaps(syscall)
            capability.applyCapabilities(syscall, capabilities)
        }

        // TODO: Apply AppArmor profile and SELinux label
        // See: runc/libcontainer/standard_init_linux.go:114-124

        mainSender.initReady()
        Logger.debug("sent init ready signal")

        mainSender.close()
        initReceiver.close()

        // Cleanup extra file descriptors to prevent FD leaks (CVE-2024-21626).
        // This sets FD_CLOEXEC on all FDs >= 3 + preserveFds so they're auto-closed at
        // execve. Done late (after init_ready) to avoid closing channel pipes early.
        syscall.closeRange(preserveFds)

        Logger.debug("waiting for start signal...")
        notifyListener.waitForContainerStart()
        Logger.debug("received start signal, executing container process")

        notifyListener.close()

        // An empty args list means the spec omitted spec.process entirely. The
        // start operation must still succeed: exit cleanly so the container
        // transitions to "stopped" without trying to exec nothing.
        if (processArgs.isEmpty()) {
            Logger.info("spec.process omitted; init exiting with status 0")
            _exit(0)
        }

        Logger.info("Executing: ${processArgs.joinToString(" ")}")

        // Clear host environment variables so container starts clean
        clearenv()
        Logger.debug("cleared all host environment variables")

        processEnv.forEach { envEntry ->
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
        Logger.debug("set ${processEnv.size} environment variables")

        val argv = allocArray<CPointerVar<ByteVar>>(processArgs.size + 1)
        processArgs.forEachIndexed { i, arg ->
            argv[i] = arg.cstr.ptr
        }
        argv[processArgs.size] = null

        // execvp uses PATH lookup and the environment we set above
        execvp(processArgs[0], argv)

        perror("execvp")
        Logger.error("Failed to execute ${processArgs[0]}")
        _exit(127)
    }

/**
 * Entry point for init process
 * Handles errors and communicates them to main process
 */
@OptIn(ExperimentalForeignApi::class)
fun runInitProcess(
    syscall: Syscall,
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
) {
    try {
        initProcessInternal(syscall, spec, rootfsPath, mainSender, initReceiver, notifyListener)
    } catch (e: Exception) {
        Logger.error("init process failed: ${e.message ?: "unknown"}")

        try {
            mainSender.sendError("Init process failed: ${e.message}")
        } catch (sendErr: Exception) {
            Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
        }

        _exit(1)
    }
}

/**
 * Synchronize seccomp notify FD with main process.
 *
 * If a notify FD is provided, send it to main process via seccompNotifyRequest()
 * and wait for main process to handle it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun syncSeccompNotifyFd(
    notifyFd: Int?,
    mainSender: MainSender,
    initReceiver: InitReceiver,
) {
    if (notifyFd != null) {
        Logger.debug("sending seccomp notify FD to main process")
        mainSender.seccompNotifyRequest(notifyFd)
        initReceiver.waitForSeccompRequestDone()
        Logger.debug("seccomp notify FD handled by main process")
    }
}

/**
 * Finalize rootfs setup
 * - Set rootfs as readonly if specified
 * - Set umask
 * See: runc/libcontainer/rootfs_linux.go:finalizeRootfs()
 */
@OptIn(ExperimentalForeignApi::class)
private fun finalizeRootfs(
    syscall: Syscall,
    spec: Spec,
) {
    if (spec.root.readonly) {
        Logger.debug("finalizing rootfs as readonly")
        setRootfsReadonly(syscall)
    }

    // Set umask (default 0o022)
    val umaskValue = spec.process.umask ?: 0x12u // 0x12 = 0o022 (octal)
    syscall.umask(umaskValue)
    Logger.debug("set umask to ${umaskValue.toString(8)}")
}
