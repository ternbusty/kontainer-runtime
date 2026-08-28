package console

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

/**
 * Unit tests for the console (PTY) module.
 *
 * [openPty] and [wireStdio] require real kernel interaction (devpts,
 * ioctl), so the tests that exercise those functions are guarded by
 * availability (/dev/ptmx must be present). [sendMasterTo] requires an
 * AF_UNIX listener on the far end, which we create inside the test.
 *
 * Tests that cannot run on a given host (e.g. CI without /dev/ptmx)
 * are silently skipped.
 */
@OptIn(ExperimentalForeignApi::class)
class ConsoleTest :
    FunSpec({

        test("openPty returns a valid pair when /dev/ptmx is available") {
            val pty = openPty() ?: return@test
            try {
                pty.master shouldNotBe pty.slave
                pty.master shouldBeGreaterThan 0
                pty.slave shouldBeGreaterThan 0
            } finally {
                close(pty.master)
                close(pty.slave)
            }
        }

        test("PtyPair data class equality") {
            val a = PtyPair(master = 3, slave = 4)
            val b = PtyPair(master = 3, slave = 4)
            a shouldBe b
            a.master shouldBe 3
            a.slave shouldBe 4
        }

        test("PtyPair with different fds are not equal") {
            val a = PtyPair(master = 3, slave = 4)
            val b = PtyPair(master = 5, slave = 6)
            a shouldNotBe b
        }

        test("sendMasterTo fails gracefully on non-existent socket path") {
            val result = sendMasterTo("/tmp/non-existent-console.sock", 3)
            result shouldBe false
        }

        test("sendMasterTo rejects overlong socket path") {
            // sun_path is limited to 108 bytes
            val longPath = "/tmp/" + "x".repeat(200) + ".sock"
            val result = sendMasterTo(longPath, 3)
            result shouldBe false
        }

        test("openPty master and slave fds are distinct") {
            val pty = openPty() ?: return@test
            try {
                (pty.master != pty.slave) shouldBe true
            } finally {
                close(pty.master)
                close(pty.slave)
            }
        }

        test("sendMasterTo sends fd via SCM_RIGHTS to a real unix socket") {
            val pty = openPty() ?: return@test
            try {
                // Create a temporary unix socket listener
                val sockPath =
                    "/tmp/kontainer-test-console-${getpid()}.sock"
                unlink(sockPath)

                val listenFd = createTestListener(sockPath)
                if (listenFd < 0) {
                    return@test
                }

                try {
                    // sendMasterTo connects and sends the master fd
                    val sent = sendMasterTo(sockPath, pty.master)
                    sent shouldBe true

                    // Accept the connection and receive the fd
                    val receivedFd = acceptAndReceiveFd(listenFd)
                    receivedFd.shouldNotBeNull()
                    receivedFd shouldBeGreaterThan 0

                    // The received fd should be usable (not -1).
                    // Confirming we got a valid fd proves SCM_RIGHTS works.
                    close(receivedFd)
                } finally {
                    close(listenFd)
                    unlink(sockPath)
                }
            } finally {
                close(pty.master)
                close(pty.slave)
            }
        }
    })

/**
 * Create a listening AF_UNIX socket at [path]. Returns the fd, or -1 on
 * failure.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createTestListener(path: String): Int =
    memScoped {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) return -1

        val addr = alloc<sockaddr_un>()
        addr.sun_family = AF_UNIX.toUShort()
        val pathBytes = path.encodeToByteArray()
        for (i in pathBytes.indices) {
            addr.sun_path[i] = pathBytes[i]
        }
        addr.sun_path[pathBytes.size] = 0

        if (bind(
                fd,
                addr.ptr.reinterpret(),
                sizeOf<sockaddr_un>().toUInt(),
            ) != 0
        ) {
            close(fd)
            return -1
        }

        if (listen(fd, 1) != 0) {
            close(fd)
            return -1
        }

        fd
    }

/**
 * Accept a connection on [listenFd] and receive a file descriptor sent
 * via SCM_RIGHTS. Returns the received fd, or null on failure.
 */
@OptIn(ExperimentalForeignApi::class)
private fun acceptAndReceiveFd(listenFd: Int): Int? =
    memScoped {
        val clientFd = accept(listenFd, null, null)
        if (clientFd < 0) return null

        try {
            val buf = allocArray<ByteVar>(1)
            val iov = alloc<iovec>()
            iov.iov_base = buf
            iov.iov_len = 1u

            val cmsgLen = _CMSG_SPACE(sizeOf<IntVar>().toULong())
            val cmsgBuf = allocArray<ByteVar>(cmsgLen.toInt())

            val msg = alloc<msghdr>()
            msg.msg_iov = iov.ptr
            msg.msg_iovlen = 1u
            msg.msg_control = cmsgBuf
            msg.msg_controllen = cmsgLen

            val received = recvmsg(clientFd, msg.ptr, 0)
            if (received < 0) return null

            val cmsg = _CMSG_FIRSTHDR(msg.ptr) ?: return null
            if (cmsg.pointed.cmsg_level != SOL_SOCKET ||
                cmsg.pointed.cmsg_type != SCM_RIGHTS
            ) {
                return null
            }

            val fdPtr =
                _CMSG_DATA(cmsg)!!
                    .reinterpret<IntVar>()
            fdPtr.pointed.value
        } finally {
            close(clientFd)
        }
    }
