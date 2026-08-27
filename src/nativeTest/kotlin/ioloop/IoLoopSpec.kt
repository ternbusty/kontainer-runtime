package ioloop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.*
import kotlinx.coroutines.launch
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
class IoLoopSpec :
    FunSpec({
        test("awaitReadable resumes when the fd becomes readable") {
            memScoped {
                val fds = allocArray<IntVar>(2)
                pipe(fds) shouldBe 0
                try {
                    withIoLoop { io ->
                        launch {
                            val b = alloc<ByteVar>()
                            b.value = 7
                            write(fds[1], b.ptr, 1u)
                        }
                        io.awaitReadable(fds[0])
                        val buf = alloc<ByteVar>()
                        read(fds[0], buf.ptr, 1u) shouldBe 1L
                        buf.value shouldBe 7.toByte()
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
                    val junk = allocArray<ByteVar>(4096)
                    while (write(fds[1], junk, 4096u) > 0) { }
                    errno shouldBe EAGAIN

                    withIoLoop { io ->
                        launch {
                            val buf = allocArray<ByteVar>(65536)
                            read(fds[0], buf, 65536u)
                        }
                        io.awaitWritable(fds[1])
                        val b = alloc<ByteVar>()
                        b.value = 1
                        (write(fds[1], b.ptr, 1u) > 0) shouldBe true
                    }
                } finally {
                    close(fds[0])
                    close(fds[1])
                }
            }
        }

        test("awaitReadable on a regular file resumes immediately") {
            memScoped {
                val path = "/dev/null"
                val fd = open(path, O_RDONLY)
                check(fd >= 0) { "open($path) failed" }
                try {
                    withIoLoop { io ->
                        io.awaitReadable(fd)
                    }
                } finally {
                    close(fd)
                }
            }
        }
    })
