package command

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.exit
import state.StateCodec
import state.loadState
import state.refreshStatus

/**
 * State command - Displays the state of a container
 *
 * @param containerId Container ID
 */
@OptIn(ExperimentalForeignApi::class)
fun state(containerId: String) {
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
        println(StateCodec.encode(state))
    } catch (e: Exception) {
        Logger.error("failed to serialize state: ${e.message ?: "unknown"}")
        exit(1)
    }
}
