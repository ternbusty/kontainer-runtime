import channel.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import platform.posix.*
import process.runIntermediateProcess
import process.runMainProcess
import spec.loadSpec

/**
 * Kontainer Runtime - Container runtime written in Kotlin/Native
 *
 * Minimal container runtime implementation compliant with OCI Runtime Specification
 * Uses a 3-process architecture with 3 channels and notify socket for container lifecycle management
 *
 * Commands:
 *   create <container-id> <bundle-path>  - Create a container
 *   start <container-id>                  - Start a created container
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>): Unit = memScoped {
    if (args.isEmpty()) {
        fprintf(stderr, "Usage: kontainer-runtime <create|start> <container-id> [bundle-path]\n")
        exit(1)
    }

    val command = args[0]
    when (command) {
        "create" -> createContainer(args.drop(1).toTypedArray())
        "start" -> startContainer(args.drop(1).toTypedArray())
        else -> {
            fprintf(stderr, "Unknown command: %s\n", command)
            fprintf(stderr, "Available commands: create, start\n")
            exit(1)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun createContainer(args: Array<String>) {
    if (args.size < 2) {
        fprintf(stderr, "Usage: kontainer-runtime create <container-id> <bundle-path>\n")
        exit(1)
    }

    val containerId = args[0]
    val bundlePath = args[1]
    val configPath = "$bundlePath/config.json"

    fprintf(stderr, "Creating container: %s\n", containerId)
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
    fprintf(stderr, "main: pid=%d\n", getpid())

    // Create 3 channels for inter-process communication
    val (mainSender, mainReceiver) = mainChannel()
    val (interSender, interReceiver) = intermediateChannel()
    val (initSender, initReceiver) = initChannel()

    // Create notify socket path
    val notifySocketPath = "/tmp/kontainer-$containerId.sock"

    // Create NotifyListener before forking (will be inherited by child processes)
    val notifyListener = try {
        NotifyListener(notifySocketPath)
    } catch (e: Exception) {
        fprintf(stderr, "Failed to create notify listener: %s\n", e.message ?: "unknown")
        exit(1)
        return
    }

    // Fork intermediate process
    val intermediatePid = fork()
    when (intermediatePid) {
        -1 -> {
            perror("fork")
            notifyListener.close()
            exit(1)
        }

        0 -> {
            // Intermediate process
            // Close receivers and senders that this process doesn't need
            mainReceiver.close()
            interSender.close()
            initSender.close()
            initReceiver.close()

            runIntermediateProcess(spec, rootfsPath, mainSender, interReceiver, notifyListener)
        }

        else -> {
            // Main process (parent process)
            // Close senders that this process doesn't need
            mainSender.close()
            interReceiver.close()
            initSender.close()
            initReceiver.close()

            // Close notify listener in main process (only used by init process)
            notifyListener.close()

            val initPid = runMainProcess(spec, intermediatePid, mainReceiver, interSender)

            // Save container state for start command
            fprintf(stderr, "Container %s created with init PID %d\n", containerId, initPid)
            fprintf(stderr, "Run 'kontainer-runtime start %s' to start the container\n", containerId)

            exit(0)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun startContainer(args: Array<String>) {
    if (args.isEmpty()) {
        fprintf(stderr, "Usage: kontainer-runtime start <container-id>\n")
        exit(1)
    }

    val containerId = args[0]
    val notifySocketPath = "/tmp/kontainer-$containerId.sock"

    fprintf(stderr, "Starting container: %s\n", containerId)

    // Send start signal to notify socket
    val notifySocket = NotifySocket(notifySocketPath)
    try {
        notifySocket.notifyContainerStart()
        fprintf(stderr, "Container %s started successfully\n", containerId)
    } catch (e: Exception) {
        fprintf(stderr, "Failed to start container: %s\n", e.message ?: "unknown")
        exit(1)
    }
}
