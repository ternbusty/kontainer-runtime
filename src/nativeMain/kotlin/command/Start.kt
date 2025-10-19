package command

import channel.NotifySocket
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.exit
import state.*

/**
 * Start command - Starts a created container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 */
@OptIn(ExperimentalForeignApi::class)
fun start(
    rootPath: String,
    containerId: String,
) {
    Logger.info("starting container: $containerId")

    // Load container state to verify it exists
    var state =
        try {
            loadState(rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("failed to load container state: ${e.message ?: "unknown"}")
            Logger.error("container may not exist or state file is corrupted")
            exit(1)
            return
        }

    // Refresh status to check actual process state
    state = state.refreshStatus()

    // Verify container is in 'created' state
    if (!state.status.canStart()) {
        Logger.error("container is in '${state.status.value}' state, expected 'created'")
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
        val updatedState = state.withStatus(ContainerStatus.RUNNING)
        updatedState.save(rootPath)
        Logger.debug("updated container state to 'running'")

        Logger.info("container $containerId started successfully")
    } catch (e: Exception) {
        Logger.error("failed to start container: ${e.message ?: "unknown"}")
        exit(1)
    }
}
