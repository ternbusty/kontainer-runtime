import channel.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
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
    Logger.setContext("main")

    if (args.isEmpty()) {
        Logger.error("Usage: kontainer-runtime <create|start> <container-id> [bundle-path]")
        exit(1)
    }

    val command = args[0]
    when (command) {
        "create" -> createContainer(args.drop(1).toTypedArray())
        "start" -> startContainer(args.drop(1).toTypedArray())
        else -> {
            Logger.error("unknown command: $command")
            Logger.error("available commands: create, start")
            exit(1)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun createContainer(args: Array<String>) {
    if (args.size < 2) {
        Logger.error("Usage: kontainer-runtime create <container-id> <bundle-path>")
        exit(1)
    }

    val containerId = args[0]
    val bundlePath = args[1]
    val configPath = "$bundlePath/config.json"

    Logger.info("creating container: $containerId")
    Logger.debug("loading spec from $configPath")

    val spec = try {
        loadSpec(configPath)
    } catch (e: Exception) {
        Logger.error("failed to load spec: ${e.message ?: "unknown error"}")
        exit(1)
        return
    }

    Logger.debug("loaded spec version ${spec.ociVersion}")

    // Get absolute path of rootfs
    val rootfsPath = if (spec.root?.path?.startsWith("/") == true) {
        spec.root.path
    } else {
        "$bundlePath/${spec.root?.path ?: "rootfs"}"
    }

    Logger.debug("rootfs path: $rootfsPath")
    Logger.debug("main: pid=${getpid()}")

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
        Logger.error("failed to create notify listener: ${e.message ?: "unknown"}")
        exit(1)
        return
    }

    // Fork intermediate process
    val intermediatePid = fork()
    when (intermediatePid) {
        -1 -> {
            perror("fork")
            Logger.error("Failed to fork intermediate process")
            notifyListener.close()
            exit(1)
        }

        0 -> {
            // Intermediate process
            // Close receivers and senders that this process doesn't need
            mainReceiver.close()
            interSender.close()
            initSender.close()
            // Keep initReceiver - will be passed to init process

            runIntermediateProcess(spec, rootfsPath, mainSender, interReceiver, initReceiver, notifyListener)
        }

        else -> {
            // Main process (parent process)
            // Close senders and receivers that this process doesn't need
            mainSender.close()
            interReceiver.close()
            initReceiver.close()
            // Keep initSender - will be used to send messages to init process

            // Close notify listener in main process (only used by init process)
            notifyListener.close()

            val initPid = runMainProcess(spec, containerId, bundlePath, intermediatePid, mainReceiver, interSender, initSender)

            // Save container state for start command
            Logger.info("container $containerId created with init PID $initPid")
            Logger.info("run 'kontainer-runtime start $containerId' to start the container")

            exit(0)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun startContainer(args: Array<String>) {
    if (args.isEmpty()) {
        Logger.error("Usage: kontainer-runtime start <container-id>")
        exit(1)
    }

    val containerId = args[0]
    val notifySocketPath = "/tmp/kontainer-$containerId.sock"

    Logger.info("starting container: $containerId")

    // Send start signal to notify socket
    val notifySocket = NotifySocket(notifySocketPath)
    try {
        notifySocket.notifyContainerStart()
        Logger.info("container $containerId started successfully")
    } catch (e: Exception) {
        Logger.error("failed to start container: ${e.message ?: "unknown"}")
        exit(1)
    }
}
