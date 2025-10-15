package process

import channel.*
import kotlinx.cinterop.*
import namespace.hasNamespace
import platform.posix.*
import rootfs.pivotRoot
import rootfs.prepareRootfs
import spec.Spec

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
    fprintf(stderr, "init: started, pid=%d ppid=%d\n", getpid(), getppid())

    try {
        // Set hostname (within UTS namespace)
        spec.hostname?.let { hostname ->
            if (sethostname(hostname, hostname.length.toULong()) != 0) {
                perror("sethostname")
                fprintf(stderr, "Warning: failed to set hostname\n")
            } else {
                fprintf(stderr, "Set hostname to %s\n", hostname)
            }
        }

        // Prepare rootfs
        if (hasNamespace(spec.linux?.namespaces, "mount")) {
            prepareRootfs(rootfsPath)
            pivotRoot(rootfsPath)
        } else {
            fprintf(stderr, "No mount namespace, skipping rootfs preparation\n")
        }

        // Change to working directory
        val cwd = spec.process?.cwd ?: "/"
        if (chdir(cwd) != 0) {
            perror("chdir")
            fprintf(stderr, "Warning: failed to chdir to %s\n", cwd)
        } else {
            fprintf(stderr, "Changed directory to %s\n", cwd)
        }

        // Verify container operation
        fprintf(stderr, "=== Container is ready ===\n")
        fprintf(stderr, "PID: %d\n", getpid())
        fprintf(stderr, "CWD: %s\n", cwd)

        // Execute container process
        if (spec.process?.args.isNullOrEmpty()) {
            fprintf(stderr, "Error: no process args specified\n")
            _exit(1)
        }
        // After null check, we can safely use !! since we exited above if null/empty
        val processArgs = spec.process!!.args!!
        val processEnv = spec.process.env ?: emptyList()

        // Set UID/GID to spec.process.user values before executing container process
        // Note: setgid must be called before setuid (once we drop to non-root, we can't setgid)
        val targetUid = spec.process.user.uid
        val targetGid = spec.process.user.gid

        if (setgid(targetGid) != 0) {
            perror("setgid")
            fprintf(stderr, "Failed to set GID to %u\n", targetGid)
            _exit(1)
        }
        if (setuid(targetUid) != 0) {
            perror("setuid")
            fprintf(stderr, "Failed to set UID to %u\n", targetUid)
            _exit(1)
        }
        fprintf(stderr, "Set UID=%u GID=%u for container process\n", targetUid, targetGid)

        // Send init ready signal to main process
        try {
            mainSender.initReady()
            fprintf(stderr, "init: sent init ready signal\n")
        } catch (e: Exception) {
            fprintf(stderr, "init: failed to send init ready: %s\n", e.message ?: "unknown")
            _exit(1)
        }

        // Close main sender (no more messages to send)
        mainSender.close()

        // Wait for start signal from notify socket
        fprintf(stderr, "init: waiting for start signal...\n")
        try {
            notifyListener.waitForContainerStart()
            fprintf(stderr, "init: received start signal, executing container process\n")
        } catch (e: Exception) {
            fprintf(stderr, "init: failed to wait for start signal: %s\n", e.message ?: "unknown")
            _exit(1)
        }

        // Close notify listener
        notifyListener.close()

        fprintf(stderr, "Executing: %s\n", processArgs.joinToString(" "))

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
            fprintf(stderr, "Failed to execute %s\n", processArgs[0])
            _exit(127)
        }
    } catch (e: Exception) {
        fprintf(stderr, "init: error: %s\n", e.message ?: "unknown")
        _exit(1)
    }
}
