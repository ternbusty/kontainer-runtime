@file:OptIn(ExperimentalForeignApi::class)

package command

import kotlinx.cinterop.*
import logger.Logger
import platform.linux._NR_pidfd_open
import platform.posix.*
import platform.pty.pty_get_winsize
import platform.pty.pty_set_winsize
import signals.installSignalRelay
import signals.uninstallSignalRelay

/**
 * Foreground supervision using a single poll(2) call that multiplexes
 * all fds: PTY master, stdin, signal self-pipe, and pidfd. No threads,
 * no coroutines, no epoll — the simplest possible event loop.
 */
internal fun superviseForeground(
    masterFd: Int,
    targetPid: Int,
): Int {
    val sigReadFd = installSignalRelay()

    if (masterFd >= 0) resizePty(masterFd)

    val pidfd = syscall(_NR_pidfd_open(), targetPid, 0).toInt()

    return try {
        doPollLoop(masterFd, sigReadFd, pidfd, targetPid)
    } finally {
        if (pidfd >= 0) close(pidfd)
        uninstallSignalRelay(sigReadFd)
    }
}

private fun doPollLoop(
    masterFd: Int,
    sigReadFd: Int,
    pidfd: Int,
    targetPid: Int,
): Int =
    memScoped {
        val buf = allocArray<ByteVar>(65536)

        val nfds = 4
        val pfds = allocArray<pollfd>(nfds)

        pfds[0].fd = masterFd
        pfds[0].events = if (masterFd >= 0) POLLIN.toShort() else 0
        pfds[1].fd = STDIN_FILENO
        pfds[1].events = if (masterFd >= 0) POLLIN.toShort() else 0
        pfds[2].fd = sigReadFd
        pfds[2].events = if (sigReadFd >= 0) POLLIN.toShort() else 0
        pfds[3].fd = pidfd
        pfds[3].events = if (pidfd >= 0) POLLIN.toShort() else 0

        var masterOpen = masterFd >= 0
        var exitCode = 0
        var exited = false

        while (true) {
            val rc = poll(pfds, nfds.toULong(), if (exited) 0 else -1)
            if (rc < 0) {
                if (errno == EINTR) continue
                break
            }

            if (masterOpen && pfds[0].revents.toInt() and POLLIN != 0) {
                val n = read(masterFd, buf, 65536u)
                if (n > 0) {
                    write(STDOUT_FILENO, buf, n.toULong())
                } else {
                    masterOpen = false
                    pfds[0].events = 0
                }
            }
            if (pfds[0].revents.toInt() and (POLLHUP or POLLERR) != 0) {
                val n = read(masterFd, buf, 65536u)
                if (n > 0) {
                    write(STDOUT_FILENO, buf, n.toULong())
                }
                masterOpen = false
                pfds[0].events = 0
            }

            if (pfds[1].revents.toInt() and POLLIN != 0 && masterOpen) {
                val n = read(STDIN_FILENO, buf, 65536u)
                if (n > 0) {
                    write(masterFd, buf, n.toULong())
                } else {
                    pfds[1].events = 0
                }
            }

            if (pfds[2].revents.toInt() and POLLIN != 0) {
                val sigBuf = allocArray<ByteVar>(64)
                val n = read(sigReadFd, sigBuf, 64u)
                if (n > 0) {
                    for (i in 0 until n.toInt()) {
                        when (val sig = sigBuf[i].toInt()) {
                            SIGWINCH -> if (masterFd >= 0) resizePty(masterFd)
                            else -> {
                                Logger.debug("forwarding signal $sig to $targetPid")
                                kill(targetPid, sig)
                            }
                        }
                    }
                }
            }

            if (pfds[3].revents.toInt() and POLLIN != 0) {
                val status = alloc<IntVar>()
                if (waitpid(targetPid, status.ptr, WNOHANG) > 0) {
                    exitCode = exitCodeFromWaitStatus(status.value)
                }
                exited = true
                pfds[3].events = 0
            }

            if (exited && !masterOpen) break
            if (exited && rc == 0) break
        }

        exitCode
    }

private fun resizePty(masterFd: Int) {
    memScoped {
        val rows = alloc<UShortVar>()
        val cols = alloc<UShortVar>()
        if (pty_get_winsize(STDIN_FILENO, rows.ptr, cols.ptr) != 0) return
        pty_set_winsize(masterFd, rows.value, cols.value)
    }
}
