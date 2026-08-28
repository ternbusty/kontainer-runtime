package signals

import ioloop.IoLoop
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import platform.pty.pty_get_winsize
import platform.pty.pty_set_winsize

/**
 * Signal forwarding for foreground mode (run / exec without --detach),
 * mirroring runc's signals.go.
 *
 * A C signal handler cannot touch coroutine machinery (it may interrupt
 * malloc or the K/N runtime — only async-signal-safe calls are allowed),
 * and signalfd(2) is unusable here: the K/N GC threads start before main()
 * with an empty signal mask, so process-directed signals get delivered to
 * them and kill the process before signalfd ever sees them.  Instead we use
 * the classic self-pipe trick: a sigaction-installed handler (process-wide,
 * so it fires no matter which thread receives the signal) writes the signal
 * number as one byte to a pipe.  The pipe's read end goes on the [IoLoop]
 * like any other fd, turning signal handling into ordinary suspending I/O.
 */

/** Signals relayed to the container in foreground mode. */
private val FORWARDED_SIGNALS =
    listOf(SIGWINCH, SIGTERM, SIGINT, SIGQUIT, SIGHUP, SIGUSR1, SIGUSR2)

/** Write end of the self-pipe, read by the signal handler. */
private var sigPipeWriteFd: Int = -1

/**
 * Byte table where table[i] == i, allocated before handlers are installed.
 * Lets the handler write the signal number without allocating: write(2) on
 * a pre-existing pointer is async-signal-safe, malloc is not.  Never freed
 * (64 bytes, once per process) — freeing could race with a handler still
 * executing on another thread during uninstall.
 */
@OptIn(ExperimentalForeignApi::class)
private var sigByteTable: CPointer<ByteVar>? = null

@OptIn(ExperimentalForeignApi::class)
private val relaySignalHandler =
    staticCFunction<Int, Unit> { signo ->
        val fd = sigPipeWriteFd
        val table = sigByteTable
        if (fd >= 0 && table != null && signo in 0..63) {
            val saved = errno
            write(fd, table + signo, 1u)
            set_posix_errno(saved)
        }
    }

/**
 * Create the self-pipe and install the relay handler for all
 * [FORWARDED_SIGNALS].
 *
 * @return the pipe's read end (non-blocking), to be watched via
 *   [awaitAndForwardSignals], or -1 on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun installSignalRelay(): Int =
    memScoped {
        val fds = allocArray<IntVar>(2)
        if (pipe(fds) != 0) {
            Logger.warn("signal relay: pipe() failed (errno=$errno)")
            return -1
        }
        // Non-blocking on both ends: the handler must never block if the
        // pipe fills up (excess signals are dropped, like Go's buffered
        // signal channel), and the reader drains in a loop.
        for (i in 0..1) {
            val flags = fcntl(fds[i], F_GETFL)
            if (flags >= 0) fcntl(fds[i], F_SETFL, flags or O_NONBLOCK)
        }

        if (sigByteTable == null) {
            val table = nativeHeap.allocArray<ByteVar>(64)
            for (i in 0..63) table[i] = i.toByte()
            sigByteTable = table
        }
        sigPipeWriteFd = fds[1]

        for (sig in FORWARDED_SIGNALS) {
            signal(sig, relaySignalHandler)
        }
        Logger.debug("signal relay installed (pipe read fd=${fds[0]})")
        fds[0]
    }

/**
 * Restore default dispositions and release the self-pipe.
 */
@OptIn(ExperimentalForeignApi::class)
fun uninstallSignalRelay(readFd: Int) {
    for (sig in FORWARDED_SIGNALS) {
        signal(sig, SIG_DFL)
    }
    val writeFd = sigPipeWriteFd
    sigPipeWriteFd = -1
    if (writeFd >= 0) close(writeFd)
    if (readFd >= 0) close(readFd)
}

/**
 * Suspend on the self-pipe and dispatch signals as runc's forward() does:
 * SIGWINCH propagates the host terminal size to the PTY master, everything
 * else is forwarded to [targetPid].  Performs the initial resize before
 * entering the loop (errors ignored — stdin may not be a tty).
 *
 * Runs until cancelled; cancel it once the container process has exited.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun awaitAndForwardSignals(
    io: IoLoop,
    sigReadFd: Int,
    targetPid: Int,
    masterFd: Int,
) {
    if (masterFd >= 0) resizePty(masterFd)
    memScoped {
        val buf = allocArray<ByteVar>(64)
        while (true) {
            io.awaitReadable(sigReadFd)
            val n = read(sigReadFd, buf, 64u)
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue
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

/**
 * Copy the host terminal's window size (from stdin) onto the PTY master.
 * Errors are ignored, as in runc: stdin may not be a terminal, or stdout
 * may already be gone during shutdown races.
 */
@OptIn(ExperimentalForeignApi::class)
private fun resizePty(masterFd: Int) {
    memScoped {
        val rows = alloc<UShortVar>()
        val cols = alloc<UShortVar>()
        if (pty_get_winsize(STDIN_FILENO, rows.ptr, cols.ptr) != 0) return
        pty_set_winsize(masterFd, rows.value, cols.value)
    }
}
