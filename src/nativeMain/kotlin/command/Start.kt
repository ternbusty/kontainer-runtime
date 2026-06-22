package command

import channel.SocketNotifySocket
import hook.runHooks
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.exit
import spec.loadSpec
import state.*
import utils.FileSystem

/**
 * Start command - Starts a created container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 */
@OptIn(ExperimentalForeignApi::class)
fun start(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
) {
    Logger.info("starting container: $containerId")

    // Load container state to verify it exists
    var state =
        try {
            loadState(fs, rootPath, containerId)
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
    val notifySocket = SocketNotifySocket(notifySocketPath)
    try {
        notifySocket.notifyContainerStart()
        Logger.debug("sent start signal to container")

        // Update state to "running" and save
        val updatedState = state.withStatus(ContainerStatus.RUNNING)
        updatedState.save(fs, rootPath)
        Logger.debug("updated container state to 'running'")

        Logger.info("container $containerId started successfully")

        // Run poststart hooks AFTER the container is running. The hook stdin sees
        // the State JSON with status="running". A failing hook here is logged but
        // not fatal — the container is already up and tearing it down would be
        // worse than the hook's intent.
        val spec =
            try {
                loadSpec(fs, "${state.bundle}/config.json")
            } catch (e: Exception) {
                null
            }
        if (spec?.hooks?.poststart != null) {
            runHooks(spec.hooks.poststart, updatedState)
        }
    } catch (e: Exception) {
        Logger.error("failed to start container: ${e.message ?: "unknown"}")
        exit(1)
    }
}
