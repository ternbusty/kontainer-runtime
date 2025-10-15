import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import platform.posix.*
import process.runIntermediateProcess
import process.runMainProcess
import spec.loadSpec

/**
 * Kontainer Runtime - Container runtime written in Kotlin/Native
 *
 * Minimal container runtime implementation compliant with OCI Runtime Specification
 * Uses a 3-process architecture for namespace isolation and rootfs preparation
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>): Unit = memScoped {
    // Load OCI spec from bundle
    val bundlePath = if (args.isNotEmpty()) args[0] else "."
    val configPath = "$bundlePath/config.json"

    fprintf(stderr, "Loading spec from %s\n", configPath)
    val spec = try {
        loadSpec(configPath)
    } catch (e: Exception) {
        fprintf(stderr, "Failed to load spec: %s\n", e.message ?: "unknown error")
        exit(1)
        return
    }

    fprintf(stderr, "Loaded spec version %s\n", spec.ociVersion)

    // Get absolute path of rootfs
    val rootfsPath = if (spec.root?.path?.startsWith("/") == true) {
        spec.root.path
    } else {
        "$bundlePath/${spec.root?.path ?: "rootfs"}"
    }

    fprintf(stderr, "Rootfs path: %s\n", rootfsPath)
    fprintf(stderr, "parent getpid=%d getppid=%d\n", getpid(), getppid())

    // Create socketpair for inter-process communication (main <-> intermediate)
    val sv = IntArray(2)
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv.refTo(0)) != 0) {
        perror("socketpair")
        exit(1)
    }

    // Fork intermediate process
    val pid = fork()
    when (pid) {
        -1 -> {
            perror("fork")
            exit(1)
        }

        0 -> {
            // Intermediate process
            runIntermediateProcess(spec, rootfsPath, sv[0])
        }

        else -> {
            // Main process (parent process)
            close(sv[1])
            runMainProcess(spec, pid, sv[0])
        }
    }
}
