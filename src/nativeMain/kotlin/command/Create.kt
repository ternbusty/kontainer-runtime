package command

import channel.NotifyListener
import channel.initChannel
import channel.intermediateChannel
import channel.mainChannel
import config.KontainerConfig
import config.saveKontainerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import platform.posix.exit
import platform.posix.fork
import platform.posix.getpid
import platform.posix.perror
import process.runIntermediateProcess
import process.runMainProcess
import spec.loadSpec
import state.ContainerStatus
import state.containerExists
import state.createState
import state.save
import utils.writeText

/**
 * Create command - Creates a new container
 *
 * @param containerId Container ID
 * @param bundlePath Path to OCI bundle directory (default: current directory)
 * @param pidFile Optional path to write the container's init process PID
 */
@OptIn(ExperimentalForeignApi::class)
fun create(containerId: String, bundlePath: String = ".", pidFile: String? = null): Unit = memScoped {
    if (containerExists(containerId)) {
        Logger.error("container $containerId already exists")
        exit(1)
    }

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
    val rootfsPath = if (spec.root.path.startsWith("/")) {
        spec.root.path
    } else {
        "$bundlePath/${spec.root.path}"
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
    when (val intermediatePid = fork()) {
        -1 -> {
            perror("fork")
            Logger.error("Failed to fork intermediate process")
            notifyListener.close()
            exit(1)
        }

        0 -> {
            // Intermediate process
            // Close receivers and senders that this process doesn't need
            // Keep initReceiver - will be passed to init process
            mainReceiver.close()
            interSender.close()
            initSender.close()

            runIntermediateProcess(spec, rootfsPath, mainSender, interReceiver, initReceiver, notifyListener)
        }

        else -> {
            // Main process (parent process)
            // Close senders and receivers that this process doesn't need
            // Keep initSender - will be used to send messages to init process
            mainSender.close()
            interReceiver.close()
            initReceiver.close()

            // Close notify listener in main process (only used by init process)
            notifyListener.close()

            val initPid =
                runMainProcess(spec, containerId, bundlePath, intermediatePid, mainReceiver, interSender, initSender)

            // Save container state for start command
            Logger.debug("saving container state")
            try {
                val state = createState(
                    ociVersion = spec.ociVersion,
                    containerId = containerId,
                    status = ContainerStatus.CREATED,
                    pid = initPid,
                    bundle = bundlePath,
                    annotations = null
                )
                state.save()
            } catch (e: Exception) {
                Logger.error("failed to save container state: ${e.message ?: "unknown"}")
                exit(1)
            }

            // Save internal configuration (independent of bundle)
            Logger.debug("saving kontainer config")
            try {
                val kontainerConfig = KontainerConfig(
                    cgroupPath = spec.linux?.cgroupsPath
                )
                saveKontainerConfig(kontainerConfig, containerId)
            } catch (e: Exception) {
                Logger.error("failed to save kontainer config: ${e.message ?: "unknown"}")
                exit(1)
            }

            // Write PID to file if --pid-file was specified
            if (pidFile != null) {
                Logger.debug("writing PID to file: $pidFile")
                try {
                    writeText(pidFile, "$initPid\n")
                    Logger.debug("successfully wrote PID $initPid to $pidFile")
                } catch (e: Exception) {
                    Logger.error("failed to write PID file: ${e.message ?: "unknown"}")
                    exit(1)
                }
            }

            Logger.info("container $containerId created with init PID $initPid")
            Logger.info("run 'kontainer-runtime start $containerId' to start the container")

            exit(0)
        }
    }
}
