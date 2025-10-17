package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Parse signal string to signal number
 *
 * Accepts both signal names (e.g., "SIGKILL", "KILL") and numbers (e.g., "9").
 *
 * @param signalStr Signal string to parse
 * @return Signal number
 * @throws IllegalArgumentException if signal is invalid
 */
fun parseSignal(signalStr: String): Int {
    // Try parsing as number first
    signalStr.toIntOrNull()?.let { return it }

    // Parse as signal name (with or without SIG prefix)
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

/**
 * Send a signal to a process
 *
 * Wrapper around kill(2) system call.
 * ESRCH errors (process doesn't exist) are intentionally ignored,
 * as this is the desired state when terminating processes.
 *
 * @param pid Process ID to send signal to
 * @param signal Signal number to send (default: SIGKILL)
 * @throws Exception if kill fails with error other than ESRCH
 */
@OptIn(ExperimentalForeignApi::class)
fun killProcess(pid: Int, signal: Int = SIGKILL) {
    Logger.debug("sending signal $signal to process $pid")

    val result = kill(pid, signal)

    when {
        result == 0 -> {
            Logger.debug("successfully sent signal $signal to process $pid")
        }
        result == -1 -> {
            val errNum = errno
            if (errNum == ESRCH) {
                // Process doesn't exist - this is OK (race condition)
                Logger.debug("process $pid does not exist (ESRCH), already terminated")
            } else {
                // Other error
                perror("kill")
                Logger.error("failed to send signal $signal to process $pid: errno=$errNum")
                throw Exception("Failed to kill process $pid: errno=$errNum")
            }
        }
        else -> {
            Logger.error("kill returned unexpected value: $result")
            throw Exception("kill returned unexpected value: $result")
        }
    }
}
