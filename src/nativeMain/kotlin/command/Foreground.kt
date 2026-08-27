package command

import console.relayPtyIO
import ioloop.IoLoop
import ioloop.withIoLoop
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logger.Logger
import platform.linux._NR_pidfd_open
import platform.posix.*
import signals.awaitAndForwardSignals
import signals.installSignalRelay
import signals.uninstallSignalRelay

/**
 * Foreground supervision for run/exec, mirroring runc's concurrent
 * architecture: while waiting for the container process to exit, the PTY
 * I/O relay and the signal forwarder (terminal resize + signal delivery to
 * the container) run as concurrent coroutines on one epoll-backed loop —
 * the coroutine equivalent of runc's goroutines in tty.go / signals.go.
 *
 * @param masterFd PTY master to relay to our stdio, or -1 when the
 *   container inherits stdio directly (terminal=false)
 * @param targetPid the container process to supervise and forward signals to
 * @return the container process's exit code (0 if it couldn't be determined)
 */
@OptIn(ExperimentalForeignApi::class)
internal fun superviseForeground(
    masterFd: Int,
    targetPid: Int,
): Int {
    val sigReadFd = installSignalRelay()
    return try {
        withIoLoop { io ->
            val relayJob =
                if (masterFd >= 0) {
                    launch(start = CoroutineStart.UNDISPATCHED) { relayPtyIO(io, masterFd) }
                } else {
                    null
                }
            val sigJob =
                if (sigReadFd >= 0) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        awaitAndForwardSignals(io, sigReadFd, targetPid, masterFd)
                    }
                } else {
                    null
                }

            val exitCode = awaitProcessExit(io, targetPid)

            sigJob?.cancel()
            if (relayJob != null) {
                // Let the relay drain buffered output: once the container's
                // pty slave closes, the master read hits EOF and the relay
                // ends on its own. If something inside the container still
                // holds the slave open, don't hang — cancel after a grace
                // period (runc equivalently force-closes the master).
                withTimeoutOrNull(200) { relayJob.join() }
                relayJob.cancel()
            }
            exitCode
        }
    } finally {
        uninstallSignalRelay(sigReadFd)
    }
}

/**
 * Suspend until [pid] exits and return its exit code.
 *
 * Uses pidfd_open(2) so the wait is a plain readable-fd event on the
 * [IoLoop] — unlike SIGCHLD-based reaping (runc's approach) this also works
 * when the process is not our child (e.g. reparented init).  Falls back to
 * a WNOHANG/kill(0) poll loop on kernels without pidfd (< 5.3).
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitProcessExit(
    io: IoLoop,
    pid: Int,
): Int {
    val pidfd = syscall(_NR_pidfd_open(), pid, 0).toInt()
    if (pidfd >= 0) {
        try {
            io.awaitReadable(pidfd)
        } finally {
            close(pidfd)
        }
        // The process is dead (zombie at worst), so this does not block:
        // reap it if it is our child, otherwise settle for exit code 0.
        return memScoped {
            val status = alloc<IntVar>()
            if (waitpid(pid, status.ptr, 0) == pid) {
                exitCodeFromWaitStatus(status.value)
            } else {
                0
            }
        }
    }

    Logger.debug("pidfd_open failed (errno=$errno), falling back to WNOHANG polling")
    while (true) {
        memScoped {
            val status = alloc<IntVar>()
            val rc = waitpid(pid, status.ptr, WNOHANG)
            if (rc == pid) return exitCodeFromWaitStatus(status.value)
            if (rc < 0 && kill(pid, 0) != 0 && errno == ESRCH) return 0
        }
        delay(100)
    }
}
