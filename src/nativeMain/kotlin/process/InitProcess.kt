package process

import kotlinx.cinterop.*
import platform.posix.*
import spec.Spec
import namespace.hasNamespace
import rootfs.*

/**
 * Init process - Init process inside container (PID 1)
 *
 * Responsibilities:
 * - Set hostname
 * - Prepare rootfs (mount /proc, /dev, /sys)
 * - Switch root with pivot_root
 * - Change to working directory
 * - Execute container process (currently sleep)
 */
@OptIn(ExperimentalForeignApi::class)
fun runInitProcess(spec: Spec, rootfsPath: String) {
    fprintf(stderr, "init getpid=%d getppid=%d\n", getpid(), getppid())

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

        // For now, use sleep for verification instead of executing actual process
        // TODO: Execute execve() using spec.process?.args
        fprintf(stderr, "Sleeping for 30 seconds...\n")
        sleep(30u)

        fprintf(stderr, "Container exiting\n")
    } catch (e: Exception) {
        fprintf(stderr, "init: error: %s\n", e.message ?: "unknown")
        _exit(1)
    }
}
