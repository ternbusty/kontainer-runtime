package channel

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.sockaddr_un
import platform.posix.*

/**
 * Notify socket abstractions for container start signaling.
 *
 * The runtime uses a Unix domain socket. The init process (Stage-2) listens on
 * it via [NotifyListener] and blocks in waitForContainerStart() until the
 * `start` command connects via [NotifySocket] and sends a message.
 *
 * Implementations live in the same file because they're tightly coupled (they
 * have to agree on the socket protocol).
 */

const val NOTIFY_FILE = "notify.sock"

/**
 * Server side of the notify socket. Owned by the init process (and inherited
 * across exec via [fd]).
 */
interface NotifyListener {
    /** FD of the listening socket; passed across exec via env vars. */
    fun fd(): Int

    /** Block until a client connects and sends a start message. */
    fun waitForContainerStart()

    fun close()
}

/**
 * Client side of the notify socket. Used by the `start` command.
 */
interface NotifySocket {
    /** Connect and send the start message. */
    fun notifyContainerStart()
}

/**
 * Unix-domain-socket-backed [NotifyListener].
 *
 * Two construction paths: from a path (creates socket, binds, listens) used by
 * Create, and from an inherited FD used by the init process after fork/exec.
 */
@OptIn(ExperimentalForeignApi::class)
class SocketNotifyListener : NotifyListener {
    private val socket: Int

    constructor(socketPath: String) {
        Logger.debug("NotifyListener: creating socket at $socketPath")

        memScoped {
            socket = socket(AF_UNIX, SOCK_STREAM, 0)
            if (socket == -1) {
                perror("socket")
                throw Exception("Failed to create socket")
            }

            unlink(socketPath)

            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            // sun_path is limited to 108 bytes on Linux
            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                throw Exception("Socket path too long (max 108 bytes)")
            }

            for (i in pathBytes.indices) {
                addr.sun_path[i] = pathBytes[i]
            }
            addr.sun_path[pathBytes.size] = 0 // null terminate

            val addrPtr = addr.ptr.reinterpret<sockaddr>()
            val addrLen = sizeOf<sockaddr_un>().toUInt()

            if (bind(socket, addrPtr, addrLen) == -1) {
                perror("bind")
                close(socket)
                throw Exception("Failed to bind socket to $socketPath")
            }

            if (listen(socket, 1) == -1) {
                perror("listen")
                close(socket)
                throw Exception("Failed to listen on socket")
            }

            Logger.debug("NotifyListener: listening on $socketPath (fd=$socket)")
        }
    }

    constructor(fd: Int) {
        Logger.debug("NotifyListener: reusing existing socket fd=$fd")
        socket = fd
    }

    override fun fd(): Int = socket

    override fun waitForContainerStart() {
        memScoped {
            Logger.debug("NotifyListener: waiting for container start signal...")

            val clientSocket = accept(socket, null, null)
            if (clientSocket == -1) {
                perror("accept")
                throw Exception("Failed to accept connection")
            }

            val buffer = allocArray<ByteVar>(256)
            val n = recv(clientSocket, buffer, 255.toULong(), 0)
            if (n == -1L) {
                perror("recv")
                close(clientSocket)
                throw Exception("Failed to receive start signal")
            }

            buffer[n.toInt()] = 0
            val message = buffer.toKString()

            Logger.debug("NotifyListener: received: $message")

            close(clientSocket)
        }
    }

    override fun close() {
        close(socket)
    }
}

/**
 * Unix-domain-socket-backed [NotifySocket].
 */
@OptIn(ExperimentalForeignApi::class)
class SocketNotifySocket(
    private val socketPath: String,
) : NotifySocket {
    override fun notifyContainerStart() {
        Logger.debug("NotifySocket: connecting to $socketPath")

        memScoped {
            val sock = socket(AF_UNIX, SOCK_STREAM, 0)
            if (sock == -1) {
                perror("socket")
                throw Exception("Failed to create socket")
            }

            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                close(sock)
                throw Exception("Socket path too long (max 108 bytes)")
            }

            for (i in pathBytes.indices) {
                addr.sun_path[i] = pathBytes[i]
            }
            addr.sun_path[pathBytes.size] = 0

            val addrPtr = addr.ptr.reinterpret<sockaddr>()
            val addrLen = sizeOf<sockaddr_un>().toUInt()

            if (connect(sock, addrPtr, addrLen) == -1) {
                perror("connect")
                close(sock)
                throw Exception("Failed to connect to socket: $socketPath")
            }

            val message = "start container"
            val sent = send(sock, message.cstr.ptr, message.length.toULong(), 0)
            if (sent == -1L) {
                perror("send")
                close(sock)
                throw Exception("Failed to send start message")
            }

            Logger.debug("NotifySocket: sent start signal")
            close(sock)
        }
    }
}
