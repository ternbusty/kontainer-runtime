package channel

import kotlinx.cinterop.*
import platform.linux.*
import platform.posix.*

/*
 * Each of the main, intermediate, and init process will have a uni-directional
 * channel (a sender and a receiver). Each process will hold the receiver and
 * listen message on it. Each sender is shared between processes to send
 * message to the corresponding receiver.
 */

/**
 * Base channel implementation using socketpair
 */
@OptIn(ExperimentalForeignApi::class)
private fun createSocketPair(): Pair<Int, Int> {
    val sv = IntArray(2)
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv.refTo(0)) != 0) {
        perror("socketpair")
        throw Exception("Failed to create socketpair")
    }
    return Pair(sv[0], sv[1])
}

/**
 * Send a message through a socket
 */
@OptIn(ExperimentalForeignApi::class)
private fun sendMessage(
    socket: Int,
    message: Message,
) {
    val json = MessageCodec.encode(message)
    val bytes = json.encodeToByteArray()

    memScoped {
        val sent = send(socket, bytes.refTo(0), bytes.size.toULong(), 0)
        if (sent == -1L) {
            perror("send")
            throw Exception("Failed to send message")
        }
    }
}

/**
 * Receive a message from a socket
 */
@OptIn(ExperimentalForeignApi::class)
private fun receiveMessage(socket: Int): Message {
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        val received = recv(socket, buffer, 4095.toULong(), 0)

        if (received == -1L) {
            perror("recv")
            throw Exception("Failed to receive message")
        }
        if (received == 0L) {
            throw Exception("Connection closed")
        }

        buffer[received.toInt()] = 0
        val json = buffer.toKString()
        return MessageCodec.decode(json)
    }
}

/**
 * Send a message with a file descriptor using SCM_RIGHTS
 */
@OptIn(ExperimentalForeignApi::class)
private fun sendMessageWithFd(
    socket: Int,
    message: Message,
    fd: Int,
) {
    val json = MessageCodec.encode(message)
    val bytes = json.encodeToByteArray()

    memScoped {
        // Prepare iovec for message data
        val iov = alloc<iovec>()
        iov.iov_base = bytes.refTo(0).getPointer(this)
        iov.iov_len = bytes.size.toULong()

        // Prepare control message (cmsg) for file descriptor
        val cmsgSpace = _CMSG_SPACE(sizeOf<IntVar>().toULong())
        val cmsgBuf = allocArray<ByteVar>(cmsgSpace.toInt())

        // Prepare msghdr
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

            // Copy file descriptor into control message data
            val dataPtr = _CMSG_DATA(cmsg)
            if (dataPtr != null) {
                dataPtr.reinterpret<IntVar>().pointed.value = fd
            }
        }

        val sent = sendmsg(socket, msg.ptr, 0)
        if (sent == -1L) {
            perror("sendmsg")
            throw Exception("Failed to send message with FD")
        }
    }
}

/**
 * Receive a message with a file descriptor using SCM_RIGHTS
 */
@OptIn(ExperimentalForeignApi::class)
private fun receiveMessageWithFd(socket: Int): Pair<Message, Int> {
    memScoped {
        // Prepare buffer for message data
        val buffer = allocArray<ByteVar>(4096)
        val iov = alloc<iovec>()
        iov.iov_base = buffer
        iov.iov_len = 4095u

        // Prepare buffer for control message
        val cmsgSpace = _CMSG_SPACE(sizeOf<IntVar>().toULong())
        val cmsgBuf = allocArray<ByteVar>(cmsgSpace.toInt())

        // Prepare msghdr
        val msg = alloc<msghdr>()
        msg.msg_name = null
        msg.msg_namelen = 0u
        msg.msg_iov = iov.ptr
        msg.msg_iovlen = 1u
        msg.msg_control = cmsgBuf
        msg.msg_controllen = cmsgSpace
        msg.msg_flags = 0

        val received = recvmsg(socket, msg.ptr, 0)
        if (received == -1L) {
            perror("recvmsg")
            throw Exception("Failed to receive message with FD")
        }
        if (received == 0L) {
            throw Exception("Connection closed")
        }

        // Extract message data
        buffer[received.toInt()] = 0
        val json = buffer.toKString()
        val message = MessageCodec.decode(json)

        // Extract file descriptor from control message
        var receivedFd = -1
        val cmsg = _CMSG_FIRSTHDR(msg.ptr)

        if (cmsg != null && cmsg.pointed.cmsg_level == SOL_SOCKET && cmsg.pointed.cmsg_type == SCM_RIGHTS) {
            val dataPtr = _CMSG_DATA(cmsg)
            if (dataPtr != null) {
                receivedFd = dataPtr.reinterpret<IntVar>().pointed.value
            }
        }

        if (receivedFd == -1) {
            throw Exception("Failed to extract FD from control message")
        }

        return Pair(message, receivedFd)
    }
}

/**
 * Main Channel - for communication from intermediate/init to main process
 */
class MainSender(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun identifierMappingRequest() {
        sendMessage(socket, Message.WriteMapping)
    }

    fun intermediateReady(pid: Int) {
        sendMessage(socket, Message.IntermediateReady(pid))
    }

    fun initReady() {
        sendMessage(socket, Message.InitReady)
    }

    fun seccompNotifyRequest(fd: Int) {
        sendMessageWithFd(socket, Message.SeccompNotify, fd)
    }

    fun execFailed(error: String) {
        sendMessage(socket, Message.ExecFailed(error))
    }

    fun sendError(error: String) {
        sendMessage(socket, Message.OtherError(error))
    }

    fun close() {
        close(socket)
    }
}

class MainReceiver(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun waitForMappingRequest(): Message.WriteMapping =
        when (val msg = receiveMessage(socket)) {
            is Message.WriteMapping -> msg
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected WriteMapping")
        }

    fun waitForIntermediateReady(): Int =
        when (val msg = receiveMessage(socket)) {
            is Message.IntermediateReady -> msg.pid
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected IntermediateReady")
        }

    fun waitForInitReady() {
        when (val msg = receiveMessage(socket)) {
            is Message.InitReady -> return
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected InitReady")
        }
    }

    fun waitForSeccompRequest(): Int {
        val (msg, fd) = receiveMessageWithFd(socket)
        return when (msg) {
            is Message.SeccompNotify -> fd
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected SeccompNotify")
        }
    }

    fun close() {
        close(socket)
    }
}

fun mainChannel(): Pair<MainSender, MainReceiver> {
    val (sender, receiver) = createSocketPair()
    return Pair(MainSender(sender), MainReceiver(receiver))
}

/**
 * Intermediate Channel - for communication from main to intermediate process
 */
class IntermediateSender(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun mappingWritten() {
        sendMessage(socket, Message.MappingWritten)
    }

    fun close() {
        close(socket)
    }
}

class IntermediateReceiver(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun waitForMappingAck() {
        when (val msg = receiveMessage(socket)) {
            is Message.MappingWritten -> return
            else -> throw Exception("Unexpected message: $msg, expected MappingWritten")
        }
    }

    fun close() {
        close(socket)
    }
}

fun intermediateChannel(): Pair<IntermediateSender, IntermediateReceiver> {
    val (sender, receiver) = createSocketPair()
    return Pair(IntermediateSender(sender), IntermediateReceiver(receiver))
}

/**
 * Init Channel - for communication from main to init process (reserved for future use like seccomp)
 */
class InitSender(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun seccompNotifyDone() {
        sendMessage(socket, Message.SeccompNotifyDone)
    }

    fun close() {
        close(socket)
    }
}

class InitReceiver(
    private val socket: Int,
) {
    fun fd(): Int = socket

    fun waitForSeccompRequestDone() {
        when (val msg = receiveMessage(socket)) {
            is Message.SeccompNotifyDone -> return
            else -> throw Exception("Unexpected message: $msg, expected SeccompNotifyDone")
        }
    }

    fun close() {
        close(socket)
    }
}

fun initChannel(): Pair<InitSender, InitReceiver> {
    val (sender, receiver) = createSocketPair()
    return Pair(InitSender(sender), InitReceiver(receiver))
}
