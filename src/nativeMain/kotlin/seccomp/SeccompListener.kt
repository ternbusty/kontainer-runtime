package seccomp

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import logger.Logger
import platform.linux.*
import platform.posix.*
import state.State
import utils.JsonCodec

/**
 * SeccompListener handles sending seccomp notify FD to external listener
 *
 * When SCMP_ACT_NOTIFY is used, the runtime sends the container process state
 * and the notify FD to an external listener via Unix socket specified by
 * listenerPath in the OCI spec.
 *
 * Protocol (matching runc):
 * 1. Connect to listener socket
 * 2. Send ContainerProcessState JSON as message data with the seccomp notify
 *    FD as SCM_RIGHTS ancillary data in a single sendmsg() call
 * 3. Close connection
 */

/** OCI runtime-spec constant for naming the seccomp notify FD. */
private const val SECCOMP_FD_NAME = "seccompFd"

/**
 * Container process state sent to the seccomp listener.
 *
 * Mirrors the runtime-spec `ContainerProcessState` struct so that standard
 * seccomp agents (such as runc's test `seccompagent`) can unmarshal it.
 */
@Serializable
data class ContainerProcessState(
    @SerialName("ociVersion")
    val ociVersion: String,
    @SerialName("fds")
    val fds: List<String>,
    @SerialName("pid")
    val pid: Int,
    @SerialName("metadata")
    val metadata: String = "",
    @SerialName("state")
    val state: State,
)

/**
 * Send seccomp notify FD to external listener
 *
 * Builds a ContainerProcessState (matching runc's wire format) and sends it
 * together with the seccomp notify FD in a single sendmsg() call.  The JSON
 * is the message payload and the FD is SCM_RIGHTS ancillary data.
 *
 * @param listenerPath Unix socket path to connect to
 * @param state Container state to embed in ContainerProcessState
 * @param notifyFd Seccomp notify file descriptor
 * @param metadata Opaque metadata string from the OCI seccomp config
 */
@OptIn(ExperimentalForeignApi::class)
fun sendToSeccompListener(
    listenerPath: String,
    state: State,
    notifyFd: Int,
    metadata: String? = null,
) {
    Logger.debug("sending seccomp notify FD to listener: $listenerPath")

    // Build the ContainerProcessState that the listener expects
    val processState =
        ContainerProcessState(
            ociVersion = state.ociVersion,
            fds = listOf(SECCOMP_FD_NAME),
            pid = state.pid ?: 0,
            metadata = metadata ?: "",
            state = state,
        )

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
            if (pathBytes.size >= 108) { // sizeof(sun_path) is typically 108
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

        // Send ContainerProcessState JSON + seccomp notify FD in a single sendmsg()
        val stateJson = JsonCodec.encode(processState)
        sendJsonWithFd(sock, stateJson, notifyFd)

        Logger.debug("sent seccomp notify FD to listener successfully")
    } finally {
        close(sock)
    }
}

/**
 * Send JSON data and a file descriptor in a single sendmsg() call.
 *
 * The JSON bytes are the message payload (iovec), and the FD is sent
 * as SCM_RIGHTS ancillary data (cmsg).  This matches the protocol that
 * runc's seccomp agent and other OCI-compliant listeners expect.
 */
@OptIn(ExperimentalForeignApi::class)
private fun sendJsonWithFd(
    sock: Int,
    json: String,
    fd: Int,
) {
    val jsonBytes = json.encodeToByteArray()

    memScoped {
        // Set up the iovec with the JSON data as the payload
        val iov = alloc<iovec>()
        iov.iov_base = jsonBytes.refTo(0).getPointer(this)
        iov.iov_len = jsonBytes.size.toULong()

        // Prepare control message buffer for SCM_RIGHTS
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

        // Set up control message header for FD transfer
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
            Logger.error("failed to send JSON+FD via sendmsg")
            throw Exception("Failed to send JSON+FD via sendmsg")
        }
    }
}
