package channel

import kotlinx.cinterop.*
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
private fun sendMessage(socket: Int, message: Message) {
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
 * Main Channel - for communication from intermediate/init to main process
 */
class MainSender(private val socket: Int) {
    fun identifierMappingRequest() {
        sendMessage(socket, Message.WriteMapping)
    }

    fun intermediateReady(pid: Int) {
        sendMessage(socket, Message.IntermediateReady(pid))
    }

    fun initReady() {
        sendMessage(socket, Message.InitReady)
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

class MainReceiver(private val socket: Int) {
    fun waitForMappingRequest(): Message.WriteMapping {
        return when (val msg = receiveMessage(socket)) {
            is Message.WriteMapping -> msg
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected WriteMapping")
        }
    }

    fun waitForIntermediateReady(): Int {
        return when (val msg = receiveMessage(socket)) {
            is Message.IntermediateReady -> msg.pid
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected IntermediateReady")
        }
    }

    fun waitForInitReady() {
        when (val msg = receiveMessage(socket)) {
            is Message.InitReady -> return
            is Message.ExecFailed -> throw Exception("Exec failed: ${msg.error}")
            is Message.OtherError -> throw Exception("Error: ${msg.error}")
            else -> throw Exception("Unexpected message: $msg, expected InitReady")
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
class IntermediateSender(private val socket: Int) {
    fun mappingWritten() {
        sendMessage(socket, Message.MappingWritten)
    }

    fun close() {
        close(socket)
    }
}

class IntermediateReceiver(private val socket: Int) {
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
class InitSender(private val socket: Int) {
    fun close() {
        close(socket)
    }
}

class InitReceiver(private val socket: Int) {
    fun close() {
        close(socket)
    }
}

fun initChannel(): Pair<InitSender, InitReceiver> {
    val (sender, receiver) = createSocketPair()
    return Pair(InitSender(sender), InitReceiver(receiver))
}
