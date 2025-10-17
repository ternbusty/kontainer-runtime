package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Signal numbers (Linux)
 */
const val SIGKILL = 9
const val SIGTERM = 15

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
