package seccomp

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import spec.ContainerState
import spec.StateCodec

/**
 * SeccompListener handles sending seccomp notify FD to external listener
 *
 * When SCMP_ACT_NOTIFY is used, the runtime can send the notify FD to an
 * external listener via Unix socket specified by listenerPath in the OCI spec.
 *
 * Protocol:
 * 1. Connect to listener socket
 * 2. Send container state as JSON + newline
 * 3. Send seccomp notify FD using SCM_RIGHTS
 * 4. Close connection
 */

/**
 * Send seccomp notify FD to external listener
 *
 * @param listenerPath Unix socket path to connect to
 * @param state Container state to send
 * @param notifyFd Seccomp notify file descriptor
 */
@OptIn(ExperimentalForeignApi::class)
fun sendToSeccompListener(listenerPath: String, state: ContainerState, notifyFd: Int) {
    Logger.debug("sending seccomp notify FD to listener: $listenerPath")

    // Create Unix socket
    val sock = socket(AF_UNIX, SOCK_STREAM, 0)
    if (sock == -1) {
        perror("socket")
        Logger.error("failed to create socket for seccomp listener")
        throw Exception("Failed to create socket for seccomp listener")
    }

    try {
        // Connect to listener socket
        memScoped {
            val addr = alloc<sockaddr_un>()
            addr.sun_family = AF_UNIX.toUShort()

            // Copy path to sun_path
            val pathBytes = listenerPath.encodeToByteArray()
            if (pathBytes.size >= 108) {  // sizeof(sun_path) is typically 108
                Logger.error("listener path too long: $listenerPath")
                throw Exception("Listener path too long")
            }

            pathBytes.forEachIndexed { index, byte ->
                addr.sun_path[index] = byte
            }
            addr.sun_path[pathBytes.size] = 0

            val addrSize = sizeOf<sockaddr_un>().toUInt()
            if (connect(sock, addr.ptr.reinterpret(), addrSize) == -1) {
                perror("connect")
                Logger.error("failed to connect to seccomp listener: $listenerPath")
                throw Exception("Failed to connect to seccomp listener")
            }
        }

        Logger.debug("connected to seccomp listener")

        // Send container state as JSON
        val stateJson = StateCodec.encode(state) + "\n"
        val stateBytes = stateJson.encodeToByteArray()

        memScoped {
            val sent = send(sock, stateBytes.refTo(0), stateBytes.size.toULong(), 0)
            if (sent == -1L) {
                perror("send")
                Logger.error("failed to send container state to listener")
                throw Exception("Failed to send container state to listener")
            }
        }

        Logger.debug("sent container state to listener")

        // Send notify FD using SCM_RIGHTS
        sendFdToListener(sock, notifyFd)

        Logger.debug("sent seccomp notify FD to listener successfully")
    } finally {
        close(sock)
    }
}

/**
 * Send file descriptor using SCM_RIGHTS
 */
@OptIn(ExperimentalForeignApi::class)
private fun sendFdToListener(sock: Int, fd: Int) {
    memScoped {
        // Dummy data to send (required by sendmsg)
        val dummy = byteArrayOf(0)
        val iov = alloc<iovec>()
        iov.iov_base = dummy.refTo(0).getPointer(this)
        iov.iov_len = 1u

        // Prepare control message for FD
        val cmsgSpace = _CMSG_SPACE(sizeOf<IntVar>().toULong())
        val cmsgBuf = allocArray<ByteVar>(cmsgSpace.toInt())

        val msg = alloc<msghdr>()
        msg.msg_name = null
        msg.msg_namelen = 0u
        msg.msg_iov = iov.ptr
        msg.msg_iovlen = 1u
        msg.msg_control = cmsgBuf
        msg.msg_controllen = cmsgSpace
        msg.msg_flags = 0

        // Set up control message header
        val cmsg = _CMSG_FIRSTHDR(msg.ptr)
        if (cmsg != null) {
            cmsg.pointed.cmsg_level = SOL_SOCKET
            cmsg.pointed.cmsg_type = SCM_RIGHTS
            cmsg.pointed.cmsg_len = _CMSG_LEN(sizeOf<IntVar>().toULong())

            val dataPtr = _CMSG_DATA(cmsg)
            if (dataPtr != null) {
                dataPtr.reinterpret<IntVar>().pointed.value = fd
            }
        }

        val sent = sendmsg(sock, msg.ptr, 0)
        if (sent == -1L) {
            perror("sendmsg")
            Logger.error("failed to send FD via SCM_RIGHTS")
            throw Exception("Failed to send FD via SCM_RIGHTS")
        }
    }
}
