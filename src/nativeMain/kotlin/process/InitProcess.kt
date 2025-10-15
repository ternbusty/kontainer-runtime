package process

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
import syscall.setNoNewPrivileges

/**
 * Init process - Init process inside container (PID 1)
 *
 * Responsibilities:
 * - Set hostname
 * - Prepare rootfs (mount /proc, /dev, /sys)
 * - Switch root with pivot_root
 * - Change to working directory
 * - Send init ready signal to main process
 * - Wait for start signal from notify socket
 * - Execute container process
 */
@OptIn(ExperimentalForeignApi::class)
fun runInitProcess(
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    notifyListener: NotifyListener
) {
    Logger.setContext("init")
    Logger.debug("started, pid=${getpid()} ppid=${getppid()}")

    // Create new session and become session leader (detach from controlling terminal)
    if (setsid() == -1) {
        perror("setsid")
        Logger.error("Failed to create new session")
        mainSender.sendError("Failed to create new session")
        _exit(1)
    }
    Logger.debug("created new session (sid=${getsid(0)})")

    // Set no_new_privileges if specified in the spec
    // This prevents the process from gaining new privileges through execve
    // (e.g., via setuid/setgid binaries or file capabilities)
    if (spec.process?.noNewPrivileges == true) {
        setNoNewPrivileges()
    }

    // Initialize seccomp filter early if no_new_privileges is NOT set
    // Without no_new_privileges, seccomp is a privileged operation (requires CAP_SYS_ADMIN).
    // We must do this before dropping capabilities/UID/GID.
    if (spec.process?.noNewPrivileges != true) {
        spec.linux?.seccomp?.let { seccomp ->
            Logger.debug("initializing seccomp filter (privileged path)")
            if (initializeSeccomp(seccomp) < 0) {
                Logger.error("Failed to initialize seccomp")
                mainSender.sendError("Failed to initialize seccomp")
                _exit(1)
            }
            Logger.info("seccomp filter initialized successfully")
        }
    }

    try {
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
        val cwd = spec.process?.cwd ?: "/"
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
        if (spec.process?.args.isNullOrEmpty()) {
            Logger.error("no process args specified")
            _exit(1)
        }
        // After null check, we can safely use !! since we exited above if null/empty
        val processArgs = spec.process!!.args!!
        val processEnv = spec.process.env?.toMutableList() ?: mutableListOf()

        // Handle LISTEN_FDS for systemd socket activation
        // See https://www.freedesktop.org/software/systemd/man/sd_listen_fds.html
        val listenFds = getenv("LISTEN_FDS")?.toKString()?.toIntOrNull() ?: 0
        val preserveFds = if (listenFds > 0) {
            // LISTEN_FDS will be passed to container init process
            // LISTEN_PID will be set to PID 1 (init process in container)
            processEnv.add("LISTEN_FDS=$listenFds")
            processEnv.add("LISTEN_PID=1")
            Logger.debug("preserving $listenFds FDs for systemd socket activation")
            listenFds
        } else {
            0
        }

        // Set UID/GID to spec.process.user values before executing container process
        // Note: setgid must be called before setuid (once we drop to non-root, we can't setgid)
        val targetUid = spec.process.user.uid
        val targetGid = spec.process.user.gid

        if (setgid(targetGid) != 0) {
            perror("setgid")
            Logger.error("Failed to set GID to $targetGid")
            _exit(1)
        }
        if (setuid(targetUid) != 0) {
            perror("setuid")
            Logger.error("Failed to set UID to $targetUid")
            _exit(1)
        }
        Logger.debug("set UID=$targetUid GID=$targetGid for container process")

        // Send init ready signal to main process
        try {
            mainSender.initReady()
            Logger.debug("sent init ready signal")
        } catch (e: Exception) {
            Logger.error("failed to send init ready: ${e.message ?: "unknown"}")
            _exit(1)
        }

        // Close main sender (no more messages to send)
        mainSender.close()

        // Cleanup extra file descriptors to prevent FD leaks (CVE-2024-21626)
        // This sets FD_CLOEXEC on all FDs >= 3 + preserveFds, so they will be
        // automatically closed when execve is called. We do this late (after
        // sending init_ready) to avoid closing the channel pipes prematurely.
        closeRange(preserveFds)

        // Wait for start signal from notify socket
        Logger.debug("waiting for start signal...")
        try {
            notifyListener.waitForContainerStart()
            Logger.debug("received start signal, executing container process")
        } catch (e: Exception) {
            Logger.error("failed to wait for start signal: ${e.message ?: "unknown"}")
            _exit(1)
        }

        // Close notify listener
        notifyListener.close()

        // Initialize seccomp filter late if no_new_privileges IS set
        // With no_new_privileges, seccomp becomes unprivileged operation.
        // We do this as late as possible (right before exec) to minimize
        // the number of syscalls that happen after the filter is applied.
        if (spec.process.noNewPrivileges == true) {
            spec.linux?.seccomp?.let { seccomp ->
                Logger.debug("initializing seccomp filter (unprivileged path, close to exec)")
                if (initializeSeccomp(seccomp) < 0) {
                    Logger.error("Failed to initialize seccomp")
                    _exit(1)
                }
                Logger.info("seccomp filter initialized successfully")
            }
        }

        Logger.info("Executing: ${processArgs.joinToString(" ")}")

        memScoped {
            // Convert args to C array (null-terminated)
            val argv = allocArray<CPointerVar<ByteVar>>(processArgs.size + 1)
            processArgs.forEachIndexed { i, arg ->
                argv[i] = arg.cstr.ptr
            }
            argv[processArgs.size] = null

            // Convert env to C array (null-terminated)
            val envp = allocArray<CPointerVar<ByteVar>>(processEnv.size + 1)
            processEnv.forEachIndexed { i, e ->
                envp[i] = e.cstr.ptr
            }
            envp[processEnv.size] = null

            // Execute the process (replaces current process, doesn't return on success)
            execve(processArgs[0], argv, envp)

            // If we reach here, execve failed
            perror("execve")
            Logger.error("Failed to execute ${processArgs[0]}")
            _exit(127)
        }
    } catch (e: Exception) {
        Logger.error("error: ${e.message ?: "unknown"}")
        _exit(1)
    }
}
