package command

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.exit
import state.loadState
import state.refreshStatus
import syscall.killProcess
import syscall.parseSignal

/**
 * Kill command - Send a signal to a container
 *
 * Sends the specified signal to the container's init process.
 * Only works on containers in "created" or "running" states.
 *
 * @param containerId Container ID
 * @param signalStr Signal to send (name like "SIGTERM" or number like "15")
 */
@OptIn(ExperimentalForeignApi::class)
fun kill(containerId: String, signalStr: String) {
    Logger.info("killing container: $containerId with signal: $signalStr")

    // Load container state
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
    Logger.debug("container status: ${state.status}")

    // Validate status - only created or running containers can be killed
    when (state.status) {
        "created", "running" -> {
            // Valid states for kill
            Logger.debug("container is in valid state for kill: ${state.status}")
        }
        else -> {
            Logger.error("cannot kill container in '${state.status}' state")
            Logger.error("kill can only be used on containers in 'created' or 'running' states")
            exit(1)
        }
    }

    // Parse signal
    val signal = try {
        parseSignal(signalStr)
    } catch (e: IllegalArgumentException) {
        Logger.error("invalid signal: ${e.message ?: "unknown"}")
        exit(1)
        return
    }

    Logger.debug("parsed signal: $signalStr -> $signal")

    // Get PID from state
    val pid = state.pid
    if (pid == null) {
        Logger.error("container has no PID in state")
        exit(1)
        return
    }

    Logger.debug("sending signal $signal to PID $pid")

    // Send signal to init process
    try {
        killProcess(pid, signal)
        Logger.info("successfully sent signal $signalStr to container $containerId (PID $pid)")
    } catch (e: Exception) {
        Logger.error("failed to kill container: ${e.message ?: "unknown"}")
        exit(1)
    }
}
