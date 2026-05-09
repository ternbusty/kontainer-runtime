package command

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.*
import state.loadState
import state.refreshStatus
import syscall.Syscall

/**
 * Kill command - Send a signal to a container
 *
 * Sends the specified signal to the container's init process.
 * Only works on containers in "created" or "running" states.
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param signalStr Signal to send (name like "SIGTERM" or number like "15")
 */
@OptIn(ExperimentalForeignApi::class)
fun kill(
    syscall: Syscall,
    rootPath: String,
    containerId: String,
    signalStr: String,
) {
    Logger.info("killing container: $containerId with signal: $signalStr")

    // Load container state
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
    Logger.debug("container status: ${state.status.value}")

    // Validate status - only created or running containers can be killed
    if (!state.status.canKill()) {
        Logger.error("cannot kill container in '${state.status.value}' state")
        Logger.error("kill can only be used on containers in 'created' or 'running' states")
        exit(1)
    }

    Logger.debug("container is in valid state for kill: ${state.status.value}")

    // Parse signal
    val signal =
        try {
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
        syscall.killProcess(pid, signal)
        Logger.info("successfully sent signal $signalStr to container $containerId (PID $pid)")
    } catch (e: Exception) {
        Logger.error("failed to kill container: ${e.message ?: "unknown"}")
        exit(1)
    }
}

/**
 * Parse a signal name (e.g. "SIGKILL", "KILL") or number (e.g. "9") to its signal number.
 */
internal fun parseSignal(signalStr: String): Int {
    signalStr.toIntOrNull()?.let { return it }

    val normalized = if (signalStr.startsWith("SIG")) signalStr else "SIG$signalStr"

    return when (normalized.uppercase()) {
        "SIGHUP" -> SIGHUP
        "SIGINT" -> SIGINT
        "SIGQUIT" -> SIGQUIT
        "SIGILL" -> SIGILL
        "SIGABRT" -> SIGABRT
        "SIGFPE" -> SIGFPE
        "SIGKILL" -> SIGKILL
        "SIGSEGV" -> SIGSEGV
        "SIGPIPE" -> SIGPIPE
        "SIGALRM" -> SIGALRM
        "SIGTERM" -> SIGTERM
        "SIGUSR1" -> SIGUSR1
        "SIGUSR2" -> SIGUSR2
        "SIGCHLD" -> SIGCHLD
        "SIGCONT" -> SIGCONT
        "SIGSTOP" -> SIGSTOP
        "SIGTSTP" -> SIGTSTP
        "SIGTTIN" -> SIGTTIN
        "SIGTTOU" -> SIGTTOU
        else -> throw IllegalArgumentException("Unknown signal: $signalStr")
    }
}
