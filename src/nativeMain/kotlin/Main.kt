import cgroup.cleanupCgroup
import channel.*
import config.KontainerConfig
import config.loadKontainerConfig
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
import state.containerExists
import state.createState
import state.deleteContainerDir
import state.deleteNotifySocket
import state.loadState
import state.refreshStatus
import state.save
import state.withStatus
import syscall.killProcess

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

    when (val command = args[0]) {
        "create" -> createContainer(args.drop(1).toTypedArray())
        "start" -> startContainer(args.drop(1).toTypedArray())
        "state" -> stateContainer(args.drop(1).toTypedArray())
        "delete" -> {
            // Parse --force or -f flag
            val remainingArgs = args.drop(1).toList()
            val force = remainingArgs.contains("--force") || remainingArgs.contains("-f")
            val containerArgs = remainingArgs.filter { it != "--force" && it != "-f" }.toTypedArray()
            deleteContainer(containerArgs, force)
        }
        else -> {
            Logger.error("unknown command: $command")
            Logger.error("available commands: create, start, state, delete")
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

    // Check if container already exists (youki compatibility)
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

            val initPid =
                runMainProcess(spec, containerId, bundlePath, intermediatePid, mainReceiver, interSender, initSender)

            // Save container state for start command
            Logger.debug("saving container state")
            try {
                val state = createState(
                    ociVersion = spec.ociVersion,
                    containerId = containerId,
                    status = "created",
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

            Logger.info("container $containerId created with init PID $initPid")
            Logger.info("run 'kontainer-runtime start $containerId' to start the container")

            exit(0)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun stateContainer(args: Array<String>) {
    if (args.isEmpty()) {
        Logger.error("Usage: kontainer-runtime state <container-id>")
        exit(1)
    }

    val containerId = args[0]

    // Load container state
    var state = try {
        loadState(containerId)
    } catch (e: Exception) {
        Logger.error("failed to load container state: ${e.message ?: "unknown"}")
        Logger.error("container may not exist or state file is corrupted")
        exit(1)
        return
    }

    // Refresh status to reflect actual process state
    // This checks /proc/{pid} and updates status accordingly
    state = state.refreshStatus()

    // Output state as JSON to stdout (OCI Runtime Spec requirement)
    try {
        val json = kotlinx.serialization.json.Json {
            prettyPrint = true
            encodeDefaults = false
        }
        println(json.encodeToString(state))
    } catch (e: Exception) {
        Logger.error("failed to serialize state: ${e.message ?: "unknown"}")
        exit(1)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun startContainer(args: Array<String>) {
    if (args.isEmpty()) {
        Logger.error("Usage: kontainer-runtime start <container-id>")
        exit(1)
    }

    val containerId = args[0]

    Logger.info("starting container: $containerId")

    // Load container state to verify it exists
    var state = try {
        loadState(containerId)
    } catch (e: Exception) {
        Logger.error("failed to load container state: ${e.message ?: "unknown"}")
        Logger.error("container may not exist or state file is corrupted")
        exit(1)
        return
    }

    // Refresh status to check actual process state
    state = state.refreshStatus()

    // Verify container is in 'created' state
    if (state.status != "created") {
        Logger.error("container is in '${state.status}' state, expected 'created'")
        exit(1)
    }

    Logger.debug("container ${state.id} has init PID ${state.pid}")

    val notifySocketPath = "/tmp/kontainer-$containerId.sock"

    // Send start signal to notify socket
    val notifySocket = NotifySocket(notifySocketPath)
    try {
        notifySocket.notifyContainerStart()
        Logger.debug("sent start signal to container")

        // Update state to "running" and save
        val updatedState = state.withStatus("running")
        updatedState.save()
        Logger.debug("updated container state to 'running'")

        Logger.info("container $containerId started successfully")
    } catch (e: Exception) {
        Logger.error("failed to start container: ${e.message ?: "unknown"}")
        exit(1)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun deleteContainer(args: Array<String>, force: Boolean = false) {
    if (args.isEmpty()) {
        Logger.error("Usage: kontainer-runtime delete [--force|-f] <container-id>")
        exit(1)
    }

    val containerId = args[0]

    Logger.info("deleting container: $containerId${if (force) " (force)" else ""}")

    // Check if container exists
    if (!containerExists(containerId)) {
        if (force) {
            // With force flag, non-existent container is not an error
            Logger.debug("container $containerId does not exist, but force flag is set")
            Logger.info("container $containerId deleted successfully")
            exit(0)
        }
        Logger.error("container $containerId does not exist")
        exit(1)
    }

    // Load container state and refresh to get actual status
    var state = try {
        loadState(containerId)
    } catch (e: Exception) {
        Logger.error("failed to load container state: ${e.message ?: "unknown"}")
        exit(1)
        return
    }

    // Refresh status to check actual process state
    state = state.refreshStatus()
    Logger.debug("container status: ${state.status}")

    // Check if container can be deleted
    // Following youki/runc behavior: allow deletion of 'stopped' and 'created' states
    // With force flag, allow deletion of any state
    when (state.status) {
        "stopped" -> {
            // Can delete stopped containers
            Logger.debug("container is stopped, proceeding with deletion")
        }
        "created" -> {
            // For created containers, kill the process first
            // Note: youki/runc allow deleting 'created' without force flag
            Logger.debug("container is created, killing process before deletion")
            state.pid?.let { pid ->
                try {
                    killProcess(pid)
                    Logger.debug("killed process $pid")
                } catch (e: Exception) {
                    Logger.warn("failed to kill process $pid: ${e.message ?: "unknown"}")
                    // Continue with deletion even if kill fails
                }
            }
        }
        "running", "paused", "creating" -> {
            // Cannot delete running/paused/creating containers without force flag
            if (force) {
                Logger.debug("container is in '${state.status}' state, but force flag is set")
                Logger.debug("killing process before deletion")
                state.pid?.let { pid ->
                    try {
                        killProcess(pid)
                        Logger.debug("killed process $pid")
                    } catch (e: Exception) {
                        Logger.warn("failed to kill process $pid: ${e.message ?: "unknown"}")
                        // Continue with deletion even if kill fails
                    }
                }
            } else {
                Logger.error("cannot delete container in '${state.status}' state")
                Logger.error("use --force flag to force deletion, or stop the container first")
                exit(1)
            }
        }
        else -> {
            // Unknown status
            Logger.warn("unknown container status: ${state.status}")
            if (!force) {
                Logger.error("cannot delete container in unknown state without force flag")
                exit(1)
            }
        }
    }

    // Load internal config to get cgroup path
    // This is independent of bundle, so works even if bundle was moved/deleted
    val config = try {
        loadKontainerConfig(containerId)
    } catch (e: Exception) {
        Logger.warn("failed to load kontainer config: ${e.message ?: "unknown"}")
        Logger.warn("will skip cgroup cleanup")
        null
    }

    // Cleanup cgroup
    config?.cgroupPath?.let { cgroupPath ->
        try {
            cleanupCgroup(cgroupPath)
        } catch (e: Exception) {
            Logger.warn("failed to cleanup cgroup: ${e.message ?: "unknown"}")
            // Continue with deletion even if cgroup cleanup fails
        }
    }

    // Delete notify socket
    try {
        deleteNotifySocket(containerId)
    } catch (e: Exception) {
        Logger.warn("failed to delete notify socket: ${e.message ?: "unknown"}")
        // Continue with deletion
    }

    // Delete container directory
    try {
        deleteContainerDir(containerId)
        Logger.info("container $containerId deleted successfully")
    } catch (e: Exception) {
        Logger.error("failed to delete container directory: ${e.message ?: "unknown"}")
        exit(1)
    }
}
