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

@OptIn(ExperimentalForeignApi::class)
fun runInitProcess(
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    initReceiver: channel.InitReceiver,
    notifyListener: NotifyListener
) {
    Logger.setContext("init")
    Logger.debug("started, pid=${getpid()} ppid=${getppid()}")

    // Create new session and become session leader (detach from controlling terminal)
    if (setsid() == -1) {
        perror("setsid")
        Logger.error("Failed to create new session")
        throw Exception("Failed to create new session")
    }
    Logger.debug("created new session (sid=${getsid(0)})")

    // Set no_new_privileges if specified in the spec
    // This prevents the process from gaining new privileges through execve
    // (e.g., via setuid/setgid binaries or file capabilities)
    // Note: Failure is not fatal
    if (spec.process?.noNewPrivileges == true) {
        setNoNewPrivileges()
    }

    // Initialize seccomp filter early if no_new_privileges is NOT set
    // Without no_new_privileges, seccomp is a privileged operation (requires CAP_SYS_ADMIN).
    // We must do this before dropping capabilities/UID/GID.
    if (spec.process?.noNewPrivileges != true) {
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
        throw Exception("No process args specified")
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
}

/**
 * Synchronize seccomp notify FD with main process
 * 
 * If a notify FD is provided:
 * - Send it to main process via seccompNotifyRequest()
 * - Wait for main process to handle it
 * 
 * This follows the same pattern as youki's sync_seccomp()
 */
@OptIn(ExperimentalForeignApi::class)
private fun syncSeccompNotifyFd(
    notifyFd: Int?,
    mainSender: MainSender,
    initReceiver: channel.InitReceiver
) {
    if (notifyFd != null) {
        Logger.debug("sending seccomp notify FD to main process")
        mainSender.seccompNotifyRequest(notifyFd)
        initReceiver.waitForSeccompRequestDone()
        Logger.debug("seccomp notify FD handled by main process")
    }
}
