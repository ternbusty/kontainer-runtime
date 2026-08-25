package console

import ioloop.IoLoop
import ioloop.restoreBlocking
import ioloop.setNonBlocking
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import platform.pty._TIOCSCTTY
import platform.pty._TIOCSWINSZ
import platform.pty.pty_disable_onlcr
import platform.pty.pty_grantpt
import platform.pty.pty_openpt
import platform.pty.pty_ptsname_r
import platform.pty.pty_set_winsize
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

    // Disable ONLCR so the slave doesn't add \r before \n. Without this,
    // callers that capture output (bats, containerd shim) see spurious \r
    // characters that break string comparisons.
    pty_disable_onlcr(slave)

    Logger.debug("opened pty pair: master=$master, slave=$slave ($slavePath)")
    return PtyPair(master, slave)
}

/**
 * Allocate a new pseudo-terminal pair from a specific devpts mount point.
 *
 * Unlike [openPty] which uses posix_openpt (always opens the host's
 * /dev/ptmx), this function opens the ptmx device from the specified
 * devpts directory.  This is required when the container has its own
 * devpts mounted with `newinstance` — the slave must belong to the
 * container's devpts so /dev/pts/N paths resolve correctly after
 * pivot_root.
 *
 * @param devptsDir path to the devpts mount point (e.g. "$rootfs/dev/pts"
 *   before pivot_root, or "/dev/pts" inside the container after setns)
 * @return the pair, or null on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun openPtyFromDevpts(devptsDir: String): PtyPair? {
    val master = open("$devptsDir/ptmx", O_RDWR or O_NOCTTY or O_CLOEXEC)
    if (master < 0) {
        Logger.warn("open $devptsDir/ptmx failed (errno=$errno)")
        return null
    }

    if (pty_grantpt(master) != 0) {
        Logger.warn("grantpt failed on $devptsDir/ptmx (errno=$errno)")
        close(master)
        return null
    }

    if (pty_unlockpt(master) != 0) {
        Logger.warn("unlockpt failed on $devptsDir/ptmx (errno=$errno)")
        close(master)
        return null
    }

    // ptsname_r returns something like "/dev/pts/0".  Extract the index
    // (the part after the last slash) and open the slave from the target
    // devpts directory rather than the default /dev/pts.
    val slaveIndex =
        memScoped {
            val buf = allocArray<ByteVar>(256)
            if (pty_ptsname_r(master, buf, 256) != 0) {
                Logger.warn("ptsname_r failed (errno=$errno)")
                close(master)
                return null
            }
            buf.toKString().substringAfterLast('/')
        }

    val slavePath = "$devptsDir/$slaveIndex"
    val slave = open(slavePath, O_RDWR)
    if (slave < 0) {
        Logger.warn("open slave $slavePath failed (errno=$errno)")
        close(master)
        return null
    }

    // Disable ONLCR so the slave doesn't add \r before \n.
    pty_disable_onlcr(slave)

    Logger.debug("opened pty pair from $devptsDir: master=$master, slave=$slave ($slavePath)")
    return PtyPair(master, slave)
}

/**
 * Connect to a console socket at [socketPath] and return the connected fd.
 * The caller can later use [sendMasterViaFd] to ship a PTY master over it.
 *
 * @return the connected socket fd, or -1 on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun connectConsoleSocket(socketPath: String): Int =
    memScoped {
        val sock = socket(AF_UNIX, SOCK_STREAM, 0)
        if (sock < 0) {
            Logger.warn("connectConsoleSocket: socket() failed (errno=$errno)")
            return -1
        }

        val addr = alloc<sockaddr_un>()
        addr.sun_family = AF_UNIX.toUShort()
        val pathBytes = socketPath.encodeToByteArray()
        if (pathBytes.size >= 108) {
            Logger.warn("console socket path too long: ${pathBytes.size}")
            close(sock)
            return -1
        }
        for (i in pathBytes.indices) {
            addr.sun_path[i] = pathBytes[i]
        }
        addr.sun_path[pathBytes.size] = 0

        if (connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_un>().toUInt()) != 0) {
            Logger.warn("connectConsoleSocket: connect to $socketPath failed (errno=$errno)")
            close(sock)
            return -1
        }

        Logger.debug("connected to console socket at $socketPath (fd=$sock)")
        sock
    }

/**
 * Send a PTY master fd over an already-connected console socket.
 *
 * @return true on success
 */
fun sendMasterViaFd(
    connectedSockFd: Int,
    masterFd: Int,
): Boolean = sendFdOverSocket(connectedSockFd, masterFd)

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
    if (platform.posix.ioctl(slaveFd, _TIOCSCTTY(), 0) != 0) {
        Logger.warn("TIOCSCTTY failed (errno=$errno)")
    }

    // Set window size if specified
    if (height != null && width != null) {
        if (pty_set_winsize(slaveFd, height.toUShort(), width.toUShort()) != 0) {
            Logger.warn("TIOCSWINSZ failed (errno=$errno)")
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
 * Create a temporary AF_UNIX socket, bind it, listen, and return the path
 * and the listening fd. Used by Run when terminal=true but no --console-socket
 * was provided — the runtime creates its own internal listener.
 *
 * @return pair of (socket path, server fd) or null on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun createConsoleSocketListener(tmpDir: String): Pair<String, Int>? =
    memScoped {
        val socketPath = "$tmpDir/console.sock"

        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (fd < 0) {
            Logger.warn("createConsoleSocketListener: socket() failed (errno=$errno)")
            return null
        }

        val addr = alloc<sockaddr_un>()
        addr.sun_family = AF_UNIX.toUShort()
        val pathBytes = socketPath.encodeToByteArray()
        if (pathBytes.size >= 108) {
            close(fd)
            return null
        }
        for (i in pathBytes.indices) {
            addr.sun_path[i] = pathBytes[i]
        }
        addr.sun_path[pathBytes.size] = 0

        if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_un>().toUInt()) != 0) {
            Logger.warn("createConsoleSocketListener: bind failed (errno=$errno)")
            close(fd)
            return null
        }

        if (listen(fd, 1) != 0) {
            Logger.warn("createConsoleSocketListener: listen failed (errno=$errno)")
            close(fd)
            unlink(socketPath)
            return null
        }

        Logger.debug("console socket listener at $socketPath (fd=$fd)")
        Pair(socketPath, fd)
    }

/**
 * Accept a connection on a console socket server and receive the PTY master
 * fd via SCM_RIGHTS.
 *
 * @return the received master fd, or -1 on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun acceptConsoleMaster(serverFd: Int): Int =
    memScoped {
        val client = accept(serverFd, null, null)
        if (client < 0) {
            Logger.warn("acceptConsoleMaster: accept failed (errno=$errno)")
            return -1
        }

        val masterFd = receiveFdFromSocket(client)
        close(client)
        masterFd
    }

/**
 * Receive a file descriptor from a unix socket via SCM_RIGHTS.
 *
 * @return the received fd, or -1 on failure
 */
@OptIn(ExperimentalForeignApi::class)
private fun receiveFdFromSocket(sock: Int): Int =
    memScoped {
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

        val n = recvmsg(sock, msg.ptr, 0)
        if (n < 0) {
            Logger.warn("recvmsg SCM_RIGHTS failed (errno=$errno)")
            return -1
        }

        val cmsg = _CMSG_FIRSTHDR(msg.ptr)
        if (cmsg == null ||
            cmsg.pointed.cmsg_level != SOL_SOCKET ||
            cmsg.pointed.cmsg_type != SCM_RIGHTS
        ) {
            Logger.warn("receiveFdFromSocket: unexpected cmsg (no SCM_RIGHTS)")
            return -1
        }

        val fdPtr = _CMSG_DATA(cmsg)!!.reinterpret<IntVar>()
        val fd = fdPtr.pointed.value
        Logger.debug("received fd $fd via SCM_RIGHTS")
        fd
    }

/**
 * Relay I/O between a PTY master fd and the current process's stdin/stdout.
 * Runs until the master closes (or the calling coroutine is cancelled).
 * Each direction (master→stdout, stdin→master) is a coroutine that suspends
 * on [io] until its fd is readable, so callers can run other coroutines
 * (signal forwarding, process supervision) concurrently on the same loop.
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun relayPtyIO(
    io: IoLoop,
    masterFd: Int,
) {
    setNonBlocking(masterFd)
    setNonBlocking(STDIN_FILENO)
    try {
        coroutineScope {
            val stdinJob =
                launch {
                    memScoped {
                        val buf = allocArray<ByteVar>(4096)
                        while (isActive) {
                            io.awaitReadable(STDIN_FILENO)
                            val n = read(STDIN_FILENO, buf, 4096u)
                            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue
                            if (n <= 0) break
                            write(masterFd, buf, n.toULong())
                        }
                    }
                }
            launch {
                memScoped {
                    val buf = allocArray<ByteVar>(4096)
                    try {
                        while (isActive) {
                            io.awaitReadable(masterFd)
                            val n = read(masterFd, buf, 4096u)
                            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue
                            if (n <= 0) break
                            write(STDOUT_FILENO, buf, n.toULong())
                        }
                    } finally {
                        stdinJob.cancel()
                    }
                }
            }
        }
    } finally {
        restoreBlocking(masterFd)
        restoreBlocking(STDIN_FILENO)
    }
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
        msg.msg_controllen = cmsgLen

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
