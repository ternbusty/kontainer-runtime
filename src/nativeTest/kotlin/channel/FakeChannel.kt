package channel

/**
 * In-memory channel implementations for tests.
 *
 * Each Fake* records every call. Receivers consume from preseeded queues so
 * tests can stage the messages an init/main process would observe.
 *
 * Receivers throw IllegalStateException when their queue is empty — tests
 * either preseed enough messages or assert the receive was never called.
 */

class FakeMainSender : MainSender {
    val calls: MutableList<String> = mutableListOf()

    override fun fd(): Int {
        calls += "fd()"
        return -1
    }

    override fun identifierMappingRequest() {
        calls += "identifierMappingRequest()"
    }

    override fun initReady() {
        calls += "initReady()"
    }

    override fun seccompNotifyRequest(fd: Int) {
        calls += "seccompNotifyRequest(fd=$fd)"
    }

    override fun execFailed(error: String) {
        calls += "execFailed(error=$error)"
    }

    override fun sendError(error: String) {
        calls += "sendError(error=$error)"
    }

    override fun close() {
        calls += "close()"
    }
}

class FakeMainReceiver : MainReceiver {
    val calls: MutableList<String> = mutableListOf()
    val mappingRequests: ArrayDeque<Message.WriteMapping> = ArrayDeque()
    val initReadySignals: ArrayDeque<Unit> = ArrayDeque()
    val seccompRequestFds: ArrayDeque<Int> = ArrayDeque()

    override fun fd(): Int {
        calls += "fd()"
        return -1
    }

    override fun waitForMappingRequest(): Message.WriteMapping {
        calls += "waitForMappingRequest()"
        return mappingRequests.removeFirstOrNull()
            ?: error("no mapping request preseeded")
    }

    override fun waitForInitReady() {
        calls += "waitForInitReady()"
        initReadySignals.removeFirstOrNull()
            ?: error("no init-ready signal preseeded")
    }

    override fun waitForSeccompRequest(): Int {
        calls += "waitForSeccompRequest()"
        return seccompRequestFds.removeFirstOrNull()
            ?: error("no seccomp request fd preseeded")
    }

    override fun close() {
        calls += "close()"
    }
}

class FakeInitSender : InitSender {
    val calls: MutableList<String> = mutableListOf()

    override fun fd(): Int {
        calls += "fd()"
        return -1
    }

    override fun mappingWritten() {
        calls += "mappingWritten()"
    }

    override fun seccompNotifyDone() {
        calls += "seccompNotifyDone()"
    }

    override fun close() {
        calls += "close()"
    }
}

class FakeInitReceiver : InitReceiver {
    val calls: MutableList<String> = mutableListOf()
    val mappingAcks: ArrayDeque<Unit> = ArrayDeque()
    val seccompDoneSignals: ArrayDeque<Unit> = ArrayDeque()

    override fun fd(): Int {
        calls += "fd()"
        return -1
    }

    override fun waitForMappingAck() {
        calls += "waitForMappingAck()"
        mappingAcks.removeFirstOrNull()
            ?: error("no mapping ack preseeded")
    }

    override fun waitForSeccompRequestDone() {
        calls += "waitForSeccompRequestDone()"
        seccompDoneSignals.removeFirstOrNull()
            ?: error("no seccomp done signal preseeded")
    }

    override fun close() {
        calls += "close()"
    }
}

class FakeNotifyListener : NotifyListener {
    val calls: MutableList<String> = mutableListOf()
    val startSignals: ArrayDeque<Unit> = ArrayDeque()

    override fun fd(): Int {
        calls += "fd()"
        return -1
    }

    override fun waitForContainerStart() {
        calls += "waitForContainerStart()"
        startSignals.removeFirstOrNull()
            ?: error("no start signal preseeded")
    }

    override fun close() {
        calls += "close()"
    }
}

class FakeNotifySocket : NotifySocket {
    val calls: MutableList<String> = mutableListOf()

    override fun notifyContainerStart() {
        calls += "notifyContainerStart()"
    }
}
