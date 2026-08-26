@file:OptIn(ExperimentalForeignApi::class)

package command

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import logger.Logger
import platform.linux._NR_pidfd_open
import platform.posix.*
import platform.pty.pty_get_winsize
import platform.pty.pty_set_winsize
import signals.installSignalRelay
import signals.uninstallSignalRelay

/**
 * Foreground supervision using coroutines + Dispatchers.IO.
 * Each blocking I/O operation (relay read/write, signal read, process wait)
 * runs as a coroutine on the IO dispatcher's thread pool.
 */
internal fun superviseForeground(
    masterFd: Int,
    targetPid: Int,
): Int {
    val sigReadFd = installSignalRelay()

    if (masterFd >= 0) resizePty(masterFd)

    return try {
        runBlocking {
            val masterToStdout =
                if (masterFd >= 0) {
                    launch(Dispatchers.IO) {
                        memScoped {
                            val buf = allocArray<ByteVar>(65536)
                            while (true) {
                                val n = read(masterFd, buf, 65536u)
                                if (n <= 0) break
                                write(STDOUT_FILENO, buf, n.toULong())
                            }
                        }
                    }
                } else {
                    null
                }

            val stdinToMaster =
                if (masterFd >= 0) {
                    launch(Dispatchers.IO) {
                        memScoped {
                            val buf = allocArray<ByteVar>(65536)
                            while (true) {
                                val n = read(STDIN_FILENO, buf, 65536u)
                                if (n <= 0) break
                                write(masterFd, buf, n.toULong())
                            }
                        }
                    }
                } else {
                    null
                }

            val sigJob =
                if (sigReadFd >= 0) {
                    launch(Dispatchers.IO) {
                        memScoped {
                            val buf = allocArray<ByteVar>(64)
                            while (true) {
                                val n = read(sigReadFd, buf, 64u)
                                if (n <= 0) break
                                for (i in 0 until n.toInt()) {
                                    when (val sig = buf[i].toInt()) {
                                        SIGWINCH -> if (masterFd >= 0) resizePty(masterFd)
                                        else -> {
                                            Logger.debug("forwarding signal $sig to $targetPid")
                                            kill(targetPid, sig)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                }

            val exitCode =
                withContext(Dispatchers.IO) {
                    awaitProcessExitBlocking(targetPid)
                }

            sigJob?.cancel()
            masterToStdout?.cancel()
            stdinToMaster?.cancel()
            if (masterFd >= 0) delay(200)

            exitCode
        }
    } finally {
        uninstallSignalRelay(sigReadFd)
    }
}

private fun awaitProcessExitBlocking(pid: Int): Int =
    memScoped {
        val status = alloc<IntVar>()
        val rc = waitpid(pid, status.ptr, 0)
        if (rc > 0) return@memScoped exitCodeFromWaitStatus(status.value)

        val pidfd = syscall(_NR_pidfd_open(), pid, 0).toInt()
        if (pidfd >= 0) {
            val pfd = alloc<pollfd>()
            pfd.fd = pidfd
            pfd.events = POLLIN.toShort()
            poll(pfd.ptr, 1u, -1)
            close(pidfd)
            return@memScoped 0
        }

        while (true) {
            if (kill(pid, 0) != 0 && errno == ESRCH) return@memScoped 0
            usleep(100_000u)
        }
        @Suppress("UNREACHABLE_CODE")
        0
    }

private fun resizePty(masterFd: Int) {
    memScoped {
        val rows = alloc<UShortVar>()
        val cols = alloc<UShortVar>()
        if (pty_get_winsize(STDIN_FILENO, rows.ptr, cols.ptr) != 0) return
        pty_set_winsize(masterFd, rows.value, cols.value)
    }
}
