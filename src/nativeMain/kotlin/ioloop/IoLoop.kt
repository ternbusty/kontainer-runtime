package ioloop

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import logger.Logger
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
 * epoll_wait(2) normally runs on a dedicated thread (like Go's netpoller
 * thread), so the coroutine thread's event loop stays free to fire timers
 * on schedule; readiness events resume coroutines across threads, which
 * the event loop supports natively.
 *
 * Thread creation is impossible in one caller: exec's forked child has
 * done setns(CLONE_NEWPID), after which clone(CLONE_THREAD) fails with
 * EINVAL (a thread must live in the creator's PID namespace, but the
 * namespace for children has been changed). When the poller thread cannot
 * be started, [withIoLoop] falls back to driving epoll from a coroutine
 * on the shared thread; that mode delays timers by up to
 * [DRIVER_TIMEOUT_MS], which is acceptable there (the exec child's only
 * timers are coarse grace periods).
 *
 * [waiters] is accessed from both the poller thread and coroutine threads,
 * so every access goes through [lock].
 */
@OptIn(ExperimentalForeignApi::class)
class IoLoop private constructor(
    private val epfd: Int,
    private val wakeReadFd: Int,
    private val wakeWriteFd: Int,
) : AutoCloseable {
    private val lock = SynchronizedObject()
    private val waiters = mutableMapOf<Int, FdWaiters>()

    /** True when the dedicated poller thread is running. */
    var usesPollerThread = false
        private set

    companion object {
        fun create(): IoLoop =
            memScoped {
                val epfd = epoll_create1(EPOLL_CLOEXEC)
                check(epfd >= 0) { "epoll_create1 failed (errno=$posixErrno)" }

                // Self-pipe so close() can wake the poller out of its
                // indefinite epoll_wait.
                val fds = allocArray<IntVar>(2)
                check(pipe(fds) == 0) { "pipe failed (errno=$posixErrno)" }
                val ev = alloc<epoll_event>()
                ev.events = EPOLLIN
                ev.data.fd = fds[0]
                epoll_ctl(epfd, EPOLL_CTL_ADD, fds[0], ev.ptr)

                val io = IoLoop(epfd, fds[0], fds[1])
                io.usesPollerThread = io.startPollerThread()
                io
            }
    }

    private fun startPollerThread(): Boolean =
        memScoped {
            val ref = StableRef.create(this@IoLoop)
            val thread = alloc<pthread_tVar>()
            val rc =
                pthread_create(
                    thread.ptr,
                    null,
                    staticCFunction { arg: COpaquePointer? ->
                        val r = arg!!.asStableRef<IoLoop>()
                        val loop = r.get()
                        loop.pollLoop()
                        r.dispose()
                        null as COpaquePointer?
                    },
                    ref.asCPointer(),
                )
            if (rc != 0) {
                // Expected in exec's forked child after setns(CLONE_NEWPID).
                Logger.debug("ioloop: pthread_create failed (rc=$rc), falling back to driver coroutine")
                ref.dispose()
                return@memScoped false
            }
            pthread_detach(thread.value)
            true
        }

    /** Suspend until [fd] is readable. */
    suspend fun awaitReadable(fd: Int) = awaitEvent(fd, wantWrite = false)

    /** Suspend until [fd] is writable (for resuming after EAGAIN on write). */
    suspend fun awaitWritable(fd: Int) = awaitEvent(fd, wantWrite = true)

    private suspend fun awaitEvent(
        fd: Int,
        wantWrite: Boolean,
    ) = suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        val rc =
            synchronized(lock) {
                val w = waiters.getOrPut(fd) { FdWaiters() }
                if (wantWrite) w.write = cont else w.read = cont
                val armRc = arm(fd, w)
                if (armRc != 0) {
                    if (wantWrite) w.write = null else w.read = null
                    if (w.isEmpty) waiters.remove(fd)
                }
                armRc
            }
        if (rc != 0) {
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
            synchronized(lock) {
                val cur = waiters[fd] ?: return@synchronized
                if (wantWrite) cur.write = null else cur.read = null
                if (cur.isEmpty) {
                    waiters.remove(fd)
                    epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null)
                } else {
                    arm(fd, cur)
                }
            }
        }
    }

    /**
     * (Re-)register [fd] with the interest set derived from its current
     * waiters. EPOLLONESHOT disarms the fd after each event, so this is
     * called on every await and after every partial wakeup. Caller must
     * hold [lock].
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
     *
     * @return true when the wake pipe fired (close() was called)
     */
    private fun pollOnce(timeoutMs: Int): Boolean =
        memScoped {
            val events = allocArray<epoll_event>(MAX_EVENTS)
            val n = epoll_wait(epfd, events, MAX_EVENTS, timeoutMs)
            if (n < 0) return@memScoped false
            var shutdown = false
            // Collect continuations under the lock, resume outside it —
            // resume dispatches into a coroutine event loop and must not
            // run under our mutex.
            val toResume = ArrayList<CancellableContinuation<Unit>>(2)
            synchronized(lock) {
                for (i in 0 until n) {
                    val fd = events[i].data.fd
                    if (fd == wakeReadFd) {
                        shutdown = true
                        continue
                    }
                    val bits = events[i].events
                    val w = waiters[fd] ?: continue
                    // HUP/ERR wake both directions: reads observe EOF/EIO
                    // and writes observe EPIPE at the syscall.
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
                        // ONESHOT already disarmed the fd; leave it
                        // registered so the next await re-arms it via
                        // EEXIST → MOD.
                        waiters.remove(fd)
                    } else {
                        arm(fd, w)
                    }
                }
            }
            for (cont in toResume) {
                cont.resume(Unit)
            }
            shutdown
        }

    /** Driver-mode entry: poll once with a bounded wait. */
    fun pollAndResume(timeoutMs: Int) {
        pollOnce(timeoutMs)
    }

    /** Poller thread body: epoll_wait until close() writes the wake pipe. */
    private fun pollLoop() {
        while (!pollOnce(-1)) {
            // keep polling
        }
        posixClose(wakeReadFd)
        posixClose(epfd)
    }

    override fun close() {
        if (usesPollerThread) {
            // Wake the poller; it closes the epoll fd and wake pipe on exit.
            memScoped {
                val b = alloc<ByteVar>()
                b.value = 1
                write(wakeWriteFd, b.ptr, 1u)
            }
            posixClose(wakeWriteFd)
        } else {
            posixClose(wakeWriteFd)
            posixClose(wakeReadFd)
            posixClose(epfd)
        }
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
                if (io.usesPollerThread) {
                    null
                } else {
                    launch {
                        while (isActive) {
                            io.pollAndResume(DRIVER_TIMEOUT_MS)
                            yield()
                        }
                    }
                }
            try {
                block(io)
            } finally {
                driver?.cancel()
            }
        }
    } finally {
        io.close()
    }
}
