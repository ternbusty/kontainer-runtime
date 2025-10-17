package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Signal numbers (Linux)
 * https://man7.org/linux/man-pages/man7/signal.7.html
 */
const val SIGHUP = 1     // Hangup detected on controlling terminal or death of controlling process
const val SIGINT = 2     // Interrupt from keyboard
const val SIGQUIT = 3    // Quit from keyboard
const val SIGILL = 4     // Illegal Instruction
const val SIGABRT = 6    // Abort signal from abort(3)
const val SIGFPE = 8     // Floating-point exception
const val SIGKILL = 9    // Kill signal (cannot be caught or ignored)
const val SIGSEGV = 11   // Invalid memory reference
const val SIGPIPE = 13   // Broken pipe: write to pipe with no readers
const val SIGALRM = 14   // Timer signal from alarm(2)
const val SIGTERM = 15   // Termination signal
const val SIGUSR1 = 10   // User-defined signal 1
const val SIGUSR2 = 12   // User-defined signal 2
const val SIGCHLD = 17   // Child stopped or terminated
const val SIGCONT = 18   // Continue if stopped
const val SIGSTOP = 19   // Stop process (cannot be caught or ignored)
const val SIGTSTP = 20   // Stop typed at terminal
const val SIGTTIN = 21   // Terminal input for background process
const val SIGTTOU = 22   // Terminal output for background process

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
