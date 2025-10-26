package process

import channel.InitReceiver
import channel.MainSender
import channel.NotifyListener
import kotlinx.cinterop.*
import logger.Logger
import namespace.hasNamespace
import platform.posix.*
import rootfs.pivotRoot
import rootfs.prepareRootfs
import seccomp.initializeSeccomp
import spec.Spec
import syscall.closeRange
import syscall.setAdditionalGroups
import syscall.setNoNewPrivileges

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
 *
 * Responsibilities:
 * - Setup cgroup before user namespace
 * - Request UID/GID mapping from main process (if user namespace is configured)
 * - Set hostname
 * - Prepare rootfs and pivot root
 * - Apply seccomp filters
 * - Execute the container process via execve()
 */
@OptIn(ExperimentalForeignApi::class)
private fun initProcessInternal(
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
        // before the Kotlin runtime starts.
        //
        // Stage-0 (bootstrap-parent): Forks Stage-1 and handles UID/GID mapping protocol
        // Stage-1: Unshares user namespace → requests mapping → waits for ack → becomes root in user NS
        //          → unshares other namespaces → forks Stage-2 → exits
        // Stage-2: Starts Kotlin runtime (this process) → becomes container init via execve
        //
        // This design ensures:
        // - User namespace is created FIRST (before other namespaces) to avoid SELinux bugs
        // - UID/GID mapping is completed in Stage-1 (before other namespaces)
        // - All namespace unshare operations happen in single-threaded Stage-1
        // - Kotlin/Native GC threads (created after Stage-2 starts) don't interfere
        // - No multithreading issues (Linux kernel restrictions on unshare)
        // - This process runs as PID 1 in the new PID namespace
        Logger.debug("all namespaces already unshared by Stage-1, UID/GID mapping already done")

        // Cgroup setup is already done in Create.kt for intermediate process (Stage-0)
        // - Stage-0 → Stage-1 → Stage-2 are all automatically included in the cgroup
        // - Parent has necessary privileges (runs in host namespace)
        // - Prevents race conditions and cgroup escape

        // User namespace mapping is already done by Stage-1
        // Stage-1 performed the following steps:
        // 1. unshare(CLONE_NEWUSER)
        // 2. prctl(PR_SET_DUMPABLE, 1)
        // 3. Sent mapping request to Stage-0
        // 4. Stage-0 forwarded to Create.kt
        // 5. Create.kt wrote uid_map/gid_map for Stage-1 process
        // 6. Create.kt sent ack to Stage-0
        // 7. Stage-0 forwarded ack to Stage-1
        // 8. prctl(PR_SET_DUMPABLE, 0)
        // 9. setuid(0) and setgid(0) in Stage-1
        // 10. unshare other namespaces
        //
        // At this point, we are already root (UID 0, GID 0) in the user namespace
        // We inherited this from Stage-1 when it forked Stage-2
        Logger.debug("user namespace mapping already done by Stage-1, we are root in user NS")

        // Session creation (setsid) is already done by bootstrap.c Stage-2
        // This ensures proper session handling before Kotlin runtime starts
        Logger.debug("session already created by bootstrap.c (sid=${getsid(0)})")

        // TODO: Setup network interfaces (setupNetwork)
        // Call setupNetwork() here to bring up loopback interface
        // This is needed for localhost (127.0.0.1) to work in the container
        // See: runc/libcontainer/standard_init_linux.go:80

        // Prepare rootfs
        if (hasNamespace(spec.linux?.namespaces, "mount")) {
            prepareRootfs(rootfsPath)
            pivotRoot(rootfsPath)
        } else {
            Logger.debug("no mount namespace, skipping rootfs preparation")
        }

        // Change to working directory
        val cwd = spec.process.cwd
        if (chdir(cwd) != 0) {
            perror("chdir")
            Logger.warn("failed to chdir to $cwd")
        } else {
            Logger.debug("changed directory to $cwd")
        }

        // Set hostname and domainname (within UTS namespace)
        // This must be done BEFORE dropping privileges (setuid/setgid) because
        // sethostname() requires CAP_SYS_ADMIN capability
        // See: runc/libcontainer/standard_init_linux.go:120-129
        spec.hostname?.let { hostname ->
            if (sethostname(hostname, hostname.length.toULong()) != 0) {
                perror("sethostname")
                Logger.warn("failed to set hostname to $hostname")
            } else {
                Logger.debug("set hostname to $hostname")
            }
        }

        // Prepare environment and FD handling
        val processArgs = spec.process.args
        val processEnv = spec.process.env?.toMutableList() ?: mutableListOf()

        // Handle LISTEN_FDS for systemd socket activation
        // See https://www.freedesktop.org/software/systemd/man/sd_listen_fds.html
        val listenFds = getenv("LISTEN_FDS")?.toKString()?.toIntOrNull() ?: 0
        val preserveFds =
            if (listenFds > 0) {
                // LISTEN_FDS will be passed to container init process
                // LISTEN_PID will be set to PID 1 (init process in container)
                processEnv.add("LISTEN_FDS=$listenFds")
                processEnv.add("LISTEN_PID=1")
                Logger.debug("preserving $listenFds FDs for systemd socket activation")
                listenFds
            } else {
                0
            }

        // Set no_new_privileges if specified in the spec
        // This prevents the process from gaining new privileges through execve
        // (e.g., via setuid/setgid binaries or file capabilities)
        // Must be set before applying capabilities
        if (spec.process.noNewPrivileges == true) {
            setNoNewPrivileges()
        }

        // Apply capability restrictions
        // 1. Apply bounding set (root privilege required)
        // 2. Set PR_SET_KEEPCAPS to preserve capabilities across setuid
        // 3. setgroups/setgid/setuid
        // 4. Clear PR_SET_KEEPCAPS
        // 5. Apply effective/permitted/inheritable/ambient capabilities (as non-root user)
        spec.process.capabilities?.let { capabilities ->
            // Step 1: Apply bounding set before changing user (root privilege required)
            Logger.debug("applying bounding set capabilities")
            capability.applyBoundingSet(capabilities)

            // Step 2: Set PR_SET_KEEPCAPS to preserve capabilities while we change users
            Logger.debug("setting PR_SET_KEEPCAPS")
            capability.setKeepCaps()
        }

        // Set additional groups (supplementary groups) before dropping privileges
        // This must be done before setgid/setuid
        // Note: /proc/self/setgroups may be "deny" in unprivileged user namespace (Linux 3.19+)
        spec.process.user.additionalGids?.let { additionalGids ->
            if (additionalGids.isNotEmpty()) {
                Logger.debug("setting ${additionalGids.size} additional groups")
                setAdditionalGroups(additionalGids)
            }
        }

        // Set UID/GID to spec.process.user values before executing container process
        // Note: setgid must be called before setuid (once we drop to non-root, we can't setgid)
        val targetUid = spec.process.user.uid
        val targetGid = spec.process.user.gid

        if (setgid(targetGid) != 0) {
            perror("setgid")
            Logger.error("Failed to set GID to $targetGid")
            throw Exception("Failed to set GID to $targetGid")
        }
        if (setuid(targetUid) != 0) {
            perror("setuid")
            Logger.error("Failed to set UID to $targetUid")
            throw Exception("Failed to set UID to $targetUid")
        }
        Logger.debug("set UID=$targetUid GID=$targetGid for container process")

        // Apply remaining capabilities after setuid
        spec.process.capabilities?.let { capabilities ->
            // Step 4: Clear PR_SET_KEEPCAPS
            Logger.debug("clearing PR_SET_KEEPCAPS")
            capability.clearKeepCaps()

            // Step 5: Apply effective/permitted/inheritable/ambient capabilities
            Logger.debug("applying effective/permitted/inheritable/ambient capabilities")
            capability.applyCapabilities(capabilities)
        }

        // TODO: Finalize rootfs (finalizeRootfs)
        // 1. Remount tmpfs and /dev as readonly (if spec specifies MS_RDONLY)
        // 2. Set rootfs (/) as readonly if spec.root.readonly == true
        // 3. Set umask (default 0o022 or spec.process.umask)
        // This is critical for readonly container security

        // TODO: Apply AppArmor profile and SELinux label
        // See: runc/libcontainer/standard_init_linux.go:114-124

        // Initialize seccomp filter
        // This must be done after capabilities, but before closing channels
        // With no_new_privileges, seccomp is unprivileged; without it, requires CAP_SYS_ADMIN
        spec.linux?.seccomp?.let { seccomp ->
            Logger.debug("initializing seccomp filter")
            val notifyFd = initializeSeccomp(seccomp)
            Logger.info("seccomp filter initialized successfully")
            syncSeccompNotifyFd(notifyFd, mainSender, initReceiver)
        }

        // Send init ready signal to main process
        mainSender.initReady()
        Logger.debug("sent init ready signal")

        // Close channels (no more messages to send/receive)
        mainSender.close()
        initReceiver.close()

        // Cleanup extra file descriptors to prevent FD leaks (CVE-2024-21626)
        // This sets FD_CLOEXEC on all FDs >= 3 + preserveFds, so they will be
        // automatically closed when execve is called. We do this late (after
        // sending init_ready) to avoid closing the channel pipes prematurely.
        closeRange(preserveFds)

        // Wait for start signal from notify socket
        Logger.debug("waiting for start signal...")
        notifyListener.waitForContainerStart()
        Logger.debug("received start signal, executing container process")

        // Close notify listener
        notifyListener.close()

        Logger.info("Executing: ${processArgs.joinToString(" ")}")

        // Clear all host environment variables
        // This ensures the container process starts with a clean environment
        clearenv()
        Logger.debug("cleared all host environment variables")

        // Set environment variables from spec.process.env
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

        // Convert args to C array (null-terminated)
        val argv = allocArray<CPointerVar<ByteVar>>(processArgs.size + 1)
        processArgs.forEachIndexed { i, arg ->
            argv[i] = arg.cstr.ptr
        }
        argv[processArgs.size] = null

        // Execute the process (replaces current process, doesn't return on success)
        // Use execvp instead of execve to support PATH lookup for relative paths
        // execvp searches PATH environment variable and uses the environment we set above
        execvp(processArgs[0], argv)

        // If we reach here, execvp failed
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
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
) {
    try {
        initProcessInternal(spec, rootfsPath, mainSender, initReceiver, notifyListener)
    } catch (e: Exception) {
        Logger.error("init process failed: ${e.message ?: "unknown"}")

        // Try to send error to main process (best effort)
        try {
            mainSender.sendError("Init process failed: ${e.message}")
        } catch (sendErr: Exception) {
            Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
        }

        _exit(1)
    }
}

/**
 * Synchronize seccomp notify FD with main process
 *
 * If a notify FD is provided:
 * - Send it to main process via seccompNotifyRequest()
 * - Wait for main process to handle it
 *
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
