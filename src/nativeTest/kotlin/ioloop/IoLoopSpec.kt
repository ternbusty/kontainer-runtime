package ioloop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
private fun now(): Long =
    memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC, ts.ptr)
        ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
    }

@OptIn(ExperimentalForeignApi::class)
class IoLoopSpec :
    FunSpec({
        test("timers stay precise while the poller idles on epoll_wait") {
            val delta =
                withIoLoop {
                    val t0 = now()
                    delay(100)
                    now() - t0
                }
            // The poller runs on its own thread, so delay() must not inherit
            // epoll_wait latency. (The old in-thread driver added up to 100ms.)
            delta shouldBeInRange 90L..160L
        }

        test("awaitReadable resumes when the fd becomes readable") {
            memScoped {
                val fds = allocArray<IntVar>(2)
                pipe(fds) shouldBe 0
                try {
                    withIoLoop { io ->
                        launch {
                            delay(50)
                            val b = alloc<ByteVar>()
                            b.value = 7
                            write(fds[1], b.ptr, 1u)
                        }
                        val t0 = now()
                        io.awaitReadable(fds[0])
                        val delta = now() - t0
                        val buf = alloc<ByteVar>()
                        read(fds[0], buf.ptr, 1u) shouldBe 1L
                        buf.value shouldBe 7.toByte()
                        delta shouldBeInRange 40L..150L
                    }
                } finally {
                    close(fds[0])
                    close(fds[1])
                }
            }
        }

        test("awaitWritable resumes after a full pipe drains") {
            memScoped {
                val fds = allocArray<IntVar>(2)
                pipe(fds) shouldBe 0
                setNonBlocking(fds[1])
                try {
                    // Fill the pipe until EAGAIN.
                    val junk = allocArray<ByteVar>(4096)
                    while (write(fds[1], junk, 4096u) > 0) { }
                    errno shouldBe EAGAIN

                    withIoLoop { io ->
                        launch {
                            delay(50)
                            // Drain enough for the writer to make progress.
                            val buf = allocArray<ByteVar>(65536)
                            read(fds[0], buf, 65536u)
                        }
                        val t0 = now()
                        io.awaitWritable(fds[1])
                        val delta = now() - t0
                        val b = alloc<ByteVar>()
                        b.value = 1
                        (write(fds[1], b.ptr, 1u) > 0) shouldBe true
                        delta shouldBeInRange 40L..150L
                    }
                } finally {
                    close(fds[0])
                    close(fds[1])
                }
            }
        }
    })
