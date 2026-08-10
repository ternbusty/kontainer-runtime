package command

import cgroup.Cgroup
import config.loadKontainerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.*
import state.loadState
import state.refreshStatus
import syscall.Syscall
import utils.FileSystem

/**
 * Kill command - Send a signal to a container
 *
 * Sends the specified signal to all processes in the container's cgroup.
 * This handles the host-pidns case where killing just the init process
 * does not terminate child processes (no PID namespace to propagate the death).
 * Only works on containers in "created" or "running" states.
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param signalStr Signal to send (name like "SIGTERM" or number like "15")
 */
@OptIn(ExperimentalForeignApi::class)
fun kill(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    signalStr: String,
    all: Boolean = false,
) {
    Logger.info("killing container: $containerId with signal: $signalStr")

    // Load container state
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
    Logger.debug("container status: ${state.status.value}")

    // Validate status - only created or running containers can be killed
    if (!state.status.canKill()) {
        if (all) {
            // --all with a stopped container: succeed silently (runc compat)
            exit(0)
        }
        // In host-pidns scenarios the init process may be gone (container
        // shows as "stopped") but other processes remain in the cgroup.
        // Check for remaining cgroup processes before rejecting.
        val hasRemainingProcs =
            try {
                val cgPath = loadKontainerConfig(fs, rootPath, containerId).cgroupPath
                cgPath != null && cgroup.getPids(cgPath).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        if (!hasRemainingProcs) {
            fprintf(stderr, "container not running\n")
            exit(1)
        }
        // Fall through to cgroup-based kill below.
        Logger.debug("container init is gone but cgroup still has processes, proceeding with cgroup kill")
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

    // Try to send the signal to ALL processes in the container's cgroup.
    // This handles the host-pidns case where killing just init doesn't
    // propagate to child processes (there's no PID namespace).
    val cgroupPath =
        try {
            loadKontainerConfig(fs, rootPath, containerId).cgroupPath
        } catch (_: Exception) {
            null
        }

    if (cgroupPath != null) {
        Logger.debug("killing all processes in cgroup $cgroupPath")
        try {
            val pids = cgroup.getPids(cgroupPath)
            for (p in pids) {
                Logger.debug("sending signal $signal to PID $p")
                try {
                    syscall.killProcess(p, signal)
                } catch (_: Exception) {
                    // Process may have already exited; ignore ESRCH.
                }
            }
            Logger.info("successfully sent signal $signalStr to ${pids.size} process(es) in container $containerId")
        } catch (e: Exception) {
            // Fall back to sending to init PID only.
            Logger.debug("failed to read cgroup PIDs, falling back to init PID: ${e.message}")
            sendToInitPid(syscall, pid, signal, signalStr, containerId)
        }
    } else {
        sendToInitPid(syscall, pid, signal, signalStr, containerId)
    }
}

/** Send signal to the init PID (fallback when cgroup is not available). */
private fun sendToInitPid(
    syscall: Syscall,
    pid: Int,
    signal: Int,
    signalStr: String,
    containerId: String,
) {
    Logger.debug("sending signal $signal to PID $pid")
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
