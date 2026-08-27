package ioloop

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.linux.*
import platform.posix.*
import kotlin.coroutines.resume
import platform.posix.close as posixClose
import platform.posix.errno as posixErrno

private const val MAX_EVENTS = 8
private const val DRIVER_TIMEOUT_MS = 100

/**
 * Per-fd waiters. A pty relay has one coroutine reading an fd while another
 * writes to it, so read and write interest must be tracked separately and
 * combined into a single epoll registration.
 */
private class FdWaiters {
    var read: CancellableContinuation<Unit>? = null
    var write: CancellableContinuation<Unit>? = null

    val isEmpty get() = read == null && write == null
}

/**
 * Epoll-backed suspension for file descriptors — a miniature version of
 * Go's netpoller.
 *
 * The event loop runs on the same thread as the coroutines: a driver
 * coroutine calls [pollAndResume] which does epoll_wait, resumes waiters,
 * then yields so the resumed coroutines can execute.  This avoids the
 * overhead of cross-thread dispatch and mutex synchronisation that a
 * dedicated poller thread would require.
 *
 * Timer precision is bounded by [DRIVER_TIMEOUT_MS]: coroutine timers
 * (e.g. [withTimeoutOrNull]) fire on the next driver iteration after they
 * expire, so the worst-case delay is one timeout interval.  This is
 * acceptable for the coarse grace periods used in foreground supervision.
 */
@OptIn(ExperimentalForeignApi::class)
class IoLoop private constructor(
    private val epfd: Int,
) : AutoCloseable {
    private val waiters = mutableMapOf<Int, FdWaiters>()

    companion object {
        fun create(): IoLoop {
            val epfd = epoll_create1(EPOLL_CLOEXEC)
            check(epfd >= 0) { "epoll_create1 failed (errno=$posixErrno)" }
            return IoLoop(epfd)
        }
    }

    /** Suspend until [fd] is readable. */
    suspend fun awaitReadable(fd: Int) = awaitEvent(fd, wantWrite = false)

    /** Suspend until [fd] is writable (for resuming after EAGAIN on write). */
    suspend fun awaitWritable(fd: Int) = awaitEvent(fd, wantWrite = true)

    private suspend fun awaitEvent(
        fd: Int,
        wantWrite: Boolean,
    ) = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        val w = waiters.getOrPut(fd) { FdWaiters() }
        if (wantWrite) w.write = cont else w.read = cont
        val rc = arm(fd, w)
        if (rc != 0) {
            if (wantWrite) w.write = null else w.read = null
            if (w.isEmpty) waiters.remove(fd)
            if (rc == EPERM) {
                // The fd doesn't support epoll (e.g. a regular file, as when
                // stdio is redirected from/to a file). poll(2) reports such
                // fds as always ready, and their reads/writes never return
                // EAGAIN — resume immediately to match that semantic.
                cont.resume(Unit)
            } else {
                cont.cancel(IllegalStateException("epoll_ctl failed for fd=$fd (errno=$rc)"))
            }
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            val cur = waiters[fd] ?: return@invokeOnCancellation
            if (wantWrite) cur.write = null else cur.read = null
            if (cur.isEmpty) {
                waiters.remove(fd)
                epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null)
            } else {
                arm(fd, cur)
            }
        }
    }

    /**
     * (Re-)register [fd] with the interest set derived from its current
     * waiters.  EPOLLONESHOT disarms the fd after each event, so this is
     * called on every await and after every partial wakeup.
     *
     * @return 0 on success, errno on failure
     */
    private fun arm(
        fd: Int,
        w: FdWaiters,
    ): Int =
        memScoped {
            val ev = alloc<epoll_event>()
            var interest = EPOLLONESHOT
            if (w.read != null) interest = interest or EPOLLIN
            if (w.write != null) interest = interest or EPOLLOUT
            ev.events = interest
            ev.data.fd = fd
            if (epoll_ctl(epfd, EPOLL_CTL_ADD, fd, ev.ptr) == 0) return@memScoped 0
            if (posixErrno == EEXIST) {
                if (epoll_ctl(epfd, EPOLL_CTL_MOD, fd, ev.ptr) == 0) return@memScoped 0
            }
            posixErrno
        }

    /**
     * Wait for events (up to [timeoutMs]) and resume the matching waiters.
     */
    fun pollAndResume(timeoutMs: Int) {
        if (waiters.isEmpty()) return
        memScoped {
            val events = allocArray<epoll_event>(MAX_EVENTS)
            val n = epoll_wait(epfd, events, MAX_EVENTS, timeoutMs)
            if (n <= 0) return

            val toResume = ArrayList<CancellableContinuation<Unit>>(2)
            for (i in 0 until n) {
                val fd = events[i].data.fd
                val bits = events[i].events
                val w = waiters[fd] ?: continue
                val hupOrErr = (bits and (EPOLLHUP or EPOLLERR)) != 0u
                if ((bits and EPOLLIN) != 0u || hupOrErr) {
                    w.read?.let { toResume.add(it) }
                    w.read = null
                }
                if ((bits and EPOLLOUT) != 0u || hupOrErr) {
                    w.write?.let { toResume.add(it) }
                    w.write = null
                }
                if (w.isEmpty) {
                    waiters.remove(fd)
                } else {
                    arm(fd, w)
                }
            }
            for (cont in toResume) {
                cont.resume(Unit)
            }
        }
    }

    override fun close() {
        posixClose(epfd)
    }
}

fun setNonBlocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL)
    if (flags >= 0) fcntl(fd, F_SETFL, flags or O_NONBLOCK)
}

fun restoreBlocking(fd: Int) {
    val flags = fcntl(fd, F_GETFL)
    if (flags >= 0) fcntl(fd, F_SETFL, flags and O_NONBLOCK.inv())
}

fun <T> withIoLoop(block: suspend CoroutineScope.(IoLoop) -> T): T {
    val io = IoLoop.create()
    return try {
        runBlocking {
            val driver =
                launch {
                    while (isActive) {
                        io.pollAndResume(DRIVER_TIMEOUT_MS)
                        yield()
                    }
                }
            try {
                block(io)
            } finally {
                driver.cancel()
            }
        }
    } finally {
        io.close()
    }
}
