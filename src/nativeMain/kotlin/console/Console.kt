package console

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import platform.pty._TIOCSCTTY
import platform.pty._TIOCSWINSZ
import platform.pty.pty_grantpt
import platform.pty.pty_openpt
import platform.pty.pty_ptsname_r
import platform.pty.pty_unlockpt

/**
 * Pseudo-terminal pair: the master end (sent to the caller via the console
 * socket) and the slave end (dup'd onto the container process's stdio).
 */
data class PtyPair(
    val master: Int,
    val slave: Int,
)

/**
 * Allocate a new pseudo-terminal pair.
 *
 * Calls posix_openpt → grantpt → unlockpt → ptsname_r → open(slave).
 * The master must be shipped to the caller via [sendMasterTo]; the slave
 * must be wired to stdin/stdout/stderr via [wireStdio].
 *
 * @return the pair, or null on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun openPty(): PtyPair? {
    val master = pty_openpt(O_RDWR or O_NOCTTY)
    if (master < 0) {
        Logger.warn("posix_openpt failed (errno=$errno)")
        return null
    }

    if (pty_grantpt(master) != 0) {
        Logger.warn("grantpt failed (errno=$errno)")
        close(master)
        return null
    }

    if (pty_unlockpt(master) != 0) {
        Logger.warn("unlockpt failed (errno=$errno)")
        close(master)
        return null
    }

    val slavePath =
        memScoped {
            val buf = allocArray<ByteVar>(256)
            if (pty_ptsname_r(master, buf, 256) != 0) {
                Logger.warn("ptsname_r failed (errno=$errno)")
                close(master)
                return null
            }
            buf.toKString()
        }

    val slave = open(slavePath, O_RDWR)
    if (slave < 0) {
        Logger.warn("open slave $slavePath failed (errno=$errno)")
        close(master)
        return null
    }

    Logger.debug("opened pty pair: master=$master, slave=$slave ($slavePath)")
    return PtyPair(master, slave)
}

/**
 * Send the PTY master fd to the external console socket at [socketPath].
 *
 * The console socket is created by the caller (e.g. containerd-shim) and
 * bound to a path. We connect to it and ship the fd via SCM_RIGHTS.
 *
 * @return true on success
 */
@OptIn(ExperimentalForeignApi::class)
fun sendMasterTo(
    socketPath: String,
    masterFd: Int,
): Boolean =
    memScoped {
        val sock = socket(AF_UNIX, SOCK_STREAM, 0)
        if (sock < 0) {
            Logger.warn("console socket: socket() failed (errno=$errno)")
            return false
        }

        try {
            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            // Copy path (must fit in sun_path[108])
            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                Logger.warn("console socket path too long: ${pathBytes.size}")
                return false
            }
            for (i in pathBytes.indices) {
                addr.sun_path[i] = pathBytes[i]
            }
            addr.sun_path[pathBytes.size] = 0

            if (connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_un>().toUInt()) != 0) {
                Logger.warn("console socket: connect to $socketPath failed (errno=$errno)")
                return false
            }

            // Send the master fd via SCM_RIGHTS
            return sendFdOverSocket(sock, masterFd)
        } finally {
            close(sock)
        }
    }

/**
 * Replace stdin/stdout/stderr with the PTY slave and acquire it as the
 * controlling terminal. Must be called after setsid().
 *
 * @param slaveFd the PTY slave file descriptor
 * @param height optional window height from spec.process.consoleSize
 * @param width optional window width from spec.process.consoleSize
 */
@OptIn(ExperimentalForeignApi::class)
fun wireStdio(
    slaveFd: Int,
    height: UInt? = null,
    width: UInt? = null,
) {
    // Set controlling terminal
    if (platform.posix.ioctl(slaveFd, _TIOCSCTTY().toULong(), 0) != 0) {
        Logger.warn("TIOCSCTTY failed (errno=$errno)")
    }

    // Set window size if specified
    if (height != null && width != null) {
        memScoped {
            val ws = alloc<winsize>()
            ws.ws_row = height.toUShort()
            ws.ws_col = width.toUShort()
            ws.ws_xpixel = 0u
            ws.ws_ypixel = 0u
            if (platform.posix.ioctl(slaveFd, _TIOCSWINSZ().toULong(), ws.ptr) != 0) {
                Logger.warn("TIOCSWINSZ failed (errno=$errno)")
            }
        }
    }

    // Dup slave onto stdio
    dup2(slaveFd, STDIN_FILENO)
    dup2(slaveFd, STDOUT_FILENO)
    dup2(slaveFd, STDERR_FILENO)

    if (slaveFd > STDERR_FILENO) {
        close(slaveFd)
    }

    Logger.debug("wired pty slave to stdio")
}

/**
 * Send a file descriptor over a unix socket using SCM_RIGHTS.
 * Sends a single zero byte as the payload (required by the OCI console protocol).
 */
@OptIn(ExperimentalForeignApi::class)
private fun sendFdOverSocket(
    sock: Int,
    fd: Int,
): Boolean =
    memScoped {
        val buf = allocArray<ByteVar>(1)
        buf[0] = 0

        val iov = alloc<iovec>()
        iov.iov_base = buf
        iov.iov_len = 1u

        // SCM_RIGHTS needs a cmsg carrying one int (the fd)
        val cmsgLen = _CMSG_SPACE(sizeOf<IntVar>().toULong())
        val cmsgBuf = allocArray<ByteVar>(cmsgLen.toInt())

        val msg = alloc<msghdr>()
        msg.msg_iov = iov.ptr
        msg.msg_iovlen = 1u
        msg.msg_control = cmsgBuf
        msg.msg_controllen = cmsgLen.toULong()

        val cmsg = _CMSG_FIRSTHDR(msg.ptr) ?: return false
        cmsg.pointed.cmsg_level = SOL_SOCKET
        cmsg.pointed.cmsg_type = SCM_RIGHTS
        cmsg.pointed.cmsg_len = _CMSG_LEN(sizeOf<IntVar>().toULong())

        // Write the fd into the cmsg data
        val fdPtr = _CMSG_DATA(cmsg)!!.reinterpret<IntVar>()
        fdPtr.pointed.value = fd

        val sent = sendmsg(sock, msg.ptr, 0)
        if (sent < 0) {
            Logger.warn("sendmsg SCM_RIGHTS failed (errno=$errno)")
            return false
        }

        Logger.debug("sent fd $fd via SCM_RIGHTS")
        true
    }
