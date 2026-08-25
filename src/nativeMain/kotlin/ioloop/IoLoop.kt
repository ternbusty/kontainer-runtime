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

@OptIn(ExperimentalForeignApi::class)
class IoLoop private constructor(
    private val epfd: Int,
) : AutoCloseable {
    private val waiters = mutableMapOf<Int, CancellableContinuation<Unit>>()

    companion object {
        fun create(): IoLoop {
            val fd = epoll_create1(EPOLL_CLOEXEC)
            check(fd >= 0) { "epoll_create1 failed (errno=$posixErrno)" }
            return IoLoop(fd)
        }
    }

    suspend fun awaitReadable(fd: Int) =
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            memScoped {
                val ev = alloc<epoll_event>()
                ev.events = (EPOLLIN or EPOLLONESHOT)
                ev.data.fd = fd
                val rc = epoll_ctl(epfd, EPOLL_CTL_ADD, fd, ev.ptr)
                if (rc != 0 && posixErrno == EPERM) {
                    // The fd doesn't support epoll (e.g. a regular file, as
                    // when stdin is redirected from a file). poll(2) reports
                    // such fds as always ready, and their reads never return
                    // EAGAIN — resume immediately to match that semantic.
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                if (rc != 0 && posixErrno != EEXIST) {
                    cont.cancel(IllegalStateException("epoll_ctl ADD failed (errno=$posixErrno)"))
                    return@suspendCancellableCoroutine
                }
                if (rc != 0) {
                    epoll_ctl(epfd, EPOLL_CTL_MOD, fd, ev.ptr)
                }
            }
            waiters[fd] = cont
            cont.invokeOnCancellation {
                waiters.remove(fd)
                epoll_ctl(epfd, EPOLL_CTL_DEL, fd, null)
            }
        }

    fun pollAndResume(timeoutMs: Int): Int =
        memScoped {
            val events = allocArray<epoll_event>(MAX_EVENTS)
            val n = epoll_wait(epfd, events, MAX_EVENTS, timeoutMs)
            if (n < 0) {
                if (posixErrno == EINTR) return@memScoped 0
                return@memScoped -1
            }
            for (i in 0 until n) {
                val fd = events[i].data.fd
                val cont = waiters.remove(fd) ?: continue
                cont.resume(Unit)
            }
            n
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
