package channel

import kotlinx.cinterop.*
import platform.linux.sockaddr_un
import platform.posix.*

/**
 * Notify Socket implementation for container start signaling
 */

const val NOTIFY_FILE = "notify.sock"

/**
 * NotifyListener (server side) - created during container creation
 * Waits for start signal from the main process
 */
@OptIn(ExperimentalForeignApi::class)
class NotifyListener(socketPath: String) {
    private val socket: Int

    init {
        fprintf(stderr, "NotifyListener: creating socket at %s\n", socketPath)

        memScoped {
            // Create Unix domain socket
            socket = socket(AF_UNIX, SOCK_STREAM, 0)
            if (socket == -1) {
                perror("socket")
                throw Exception("Failed to create socket")
            }

            // Unlink if exists
            unlink(socketPath)

            // Bind to socket path
            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            // Copy path to sun_path (limited to 108 bytes on Linux)
            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                throw Exception("Socket path too long (max 108 bytes)")
            }

            // Copy path into sun_path array
            for (i in pathBytes.indices) {
                addr.sun_path[i] = pathBytes[i]
            }
            addr.sun_path[pathBytes.size] = 0  // null terminate

            val addrPtr = addr.ptr.reinterpret<sockaddr>()
            val addrLen = sizeOf<sockaddr_un>().toUInt()

            if (bind(socket, addrPtr, addrLen) == -1) {
                perror("bind")
                close(socket)
                throw Exception("Failed to bind socket to $socketPath")
            }

            // Listen for connections
            if (listen(socket, 1) == -1) {
                perror("listen")
                close(socket)
                throw Exception("Failed to listen on socket")
            }

            fprintf(stderr, "NotifyListener: listening on %s (fd=%d)\n", socketPath, socket)
        }
    }

    /**
     * Wait for container start signal
     * Blocks until a connection is accepted and message is received
     */
    fun waitForContainerStart() {
        memScoped {
            fprintf(stderr, "NotifyListener: waiting for container start signal...\n")

            // Accept connection
            val clientSocket = accept(socket, null, null)
            if (clientSocket == -1) {
                perror("accept")
                throw Exception("Failed to accept connection")
            }

            // Read message
            val buffer = allocArray<ByteVar>(256)
            val n = recv(clientSocket, buffer, 255.toULong(), 0)
            if (n == -1L) {
                perror("recv")
                close(clientSocket)
                throw Exception("Failed to receive start signal")
            }

            buffer[n.toInt()] = 0
            val message = buffer.toKString()

            fprintf(stderr, "NotifyListener: received: %s\n", message)

            close(clientSocket)
        }
    }

    fun close() {
        close(socket)
    }
}

/**
 * NotifySocket (client side) - used by start command
 * Sends start signal to the init process
 */
@OptIn(ExperimentalForeignApi::class)
class NotifySocket(private val socketPath: String) {
    fun notifyContainerStart() {
        fprintf(stderr, "NotifySocket: connecting to %s\n", socketPath)

        memScoped {
            // Create Unix domain socket
            val sock = socket(AF_UNIX, SOCK_STREAM, 0)
            if (sock == -1) {
                perror("socket")
                throw Exception("Failed to create socket")
            }

            // Connect to socket path
            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            val pathBytes = socketPath.encodeToByteArray()
            if (pathBytes.size >= 108) {
                close(sock)
                throw Exception("Socket path too long (max 108 bytes)")
            }

            // Copy path into sun_path array
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

            // Send start message
            val message = "start container"
            val sent = send(sock, message.cstr.ptr, message.length.toULong(), 0)
            if (sent == -1L) {
                perror("send")
                close(sock)
                throw Exception("Failed to send start message")
            }

            fprintf(stderr, "NotifySocket: sent start signal\n")
            close(sock)
        }
    }
}
