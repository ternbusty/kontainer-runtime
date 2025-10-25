package process

import cgroup.setupCgroup
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
import syscall.setDumpable
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
        // Stage-0 (bootstrap-parent): Forks Stage-1 and forwards Stage-2 PID to Create.kt
        // Stage-1: Unshares ALL namespaces (single-threaded) → forks Stage-2 → exits
        // Stage-2: Starts Kotlin runtime (this process) → becomes container init via execve
        //
        // This design ensures:
        // - All namespace unshare operations happen in single-threaded Stage-1
        // - Kotlin/Native GC threads (created after Stage-2 starts) don't interfere
        // - No multithreading issues (Linux kernel restrictions on unshare)
        // - This process runs as PID 1 in the new PID namespace
        Logger.debug("all namespaces already unshared by Stage-1, running as PID 1")

        // Setup cgroup BEFORE entering user namespace
        // At this point, we still have host root privileges (inherited from parent)
        // This allows creation of cgroup directories in /sys/fs/cgroup/
        setupCgroup(getpid(), spec.linux?.cgroupsPath, spec.linux?.resources)

        // Handle user namespace (created by bootstrap.c before Kotlin runtime started)
        val hasUserNamespace = hasNamespace(spec.linux?.namespaces, "user")
        Logger.debug("hasUserNamespace: $hasUserNamespace")
        if (hasUserNamespace) {
            // User namespace was already created by bootstrap.c (before Kotlin runtime started)
            // This avoids the multithreading issue (Kotlin GC creates 3 threads)
            Logger.debug("user namespace already created by bootstrap.c")

            // Make process dumpable so parent can write to uid_map/gid_map
            // See: https://man7.org/linux/man-pages/man7/user_namespaces.7.html
            // "The parent process can write to the /proc/PID/uid_map and /proc/PID/gid_map
            // files only if the child process has the PR_SET_DUMPABLE attribute set"
            setDumpable(true)

            // Send mapping request to main process
            mainSender.identifierMappingRequest()
            Logger.debug("sent mapping request")

            // Wait for mapping completion from main process
            initReceiver.waitForMappingAck()
            Logger.debug("received mapping ack")

            // Restore non-dumpable state after mapping is complete
            setDumpable(false)

            // Set UID/GID to 0 (root within user namespace)
            if (setuid(0u) != 0 || setgid(0u) != 0) {
                perror("setuid/setgid")
                throw Exception("Failed to setuid/setgid")
            }
            Logger.debug("set UID/GID to 0 in user namespace")
        } else {
            Logger.debug("skipping user namespace setup (no user namespace configured)")
        }

        // Session creation (setsid) is already done by bootstrap.c Stage-2
        // This ensures proper session handling before Kotlin runtime starts
        Logger.debug("session already created by bootstrap.c (sid=${getsid(0)})")

        // Set no_new_privileges if specified in the spec
        // This prevents the process from gaining new privileges through execve
        // (e.g., via setuid/setgid binaries or file capabilities)
        // Note: Failure is not fatal
        if (spec.process.noNewPrivileges == true) {
            setNoNewPrivileges()
        }

        // Initialize seccomp filter early if no_new_privileges is NOT set
        // Without no_new_privileges, seccomp is a privileged operation (requires CAP_SYS_ADMIN).
        // We must do this before dropping capabilities/UID/GID.
        if (spec.process.noNewPrivileges != true) {
            spec.linux?.seccomp?.let { seccomp ->
                Logger.debug("initializing seccomp filter (privileged path)")
                val notifyFd = initializeSeccomp(seccomp)
                Logger.info("seccomp filter initialized successfully")
                syncSeccompNotifyFd(notifyFd, mainSender, initReceiver)
            }
        }

        // Set hostname (within UTS namespace)
        spec.hostname?.let { hostname ->
            if (sethostname(hostname, hostname.length.toULong()) != 0) {
                perror("sethostname")
                Logger.warn("failed to set hostname")
            } else {
                Logger.debug("set hostname to $hostname")
            }
        }

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

        // Verify container operation
        Logger.info("=== Container is ready ===")
        Logger.debug("PID: ${getpid()}")
        Logger.debug("CWD: $cwd")

        // Execute container process
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

        // Apply capability restrictions before dropping privileges
        // This must be done before setuid/setgid because some capability operations require root
        spec.process.capabilities?.let { capabilities ->
            Logger.debug("applying capability restrictions")
            capability.dropPrivileges(capabilities)
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

        // Initialize seccomp filter if no_new_privileges IS set
        // With no_new_privileges, seccomp becomes unprivileged operation.
        // We do this after dropping privileges but before closing channels
        // so we can send the notify FD to main process if needed.
        if (spec.process.noNewPrivileges == true) {
            spec.linux?.seccomp?.let { seccomp ->
                Logger.debug("initializing seccomp filter (unprivileged path)")
                val notifyFd = initializeSeccomp(seccomp)
                Logger.info("seccomp filter initialized successfully")
                syncSeccompNotifyFd(notifyFd, mainSender, initReceiver)
            }
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
