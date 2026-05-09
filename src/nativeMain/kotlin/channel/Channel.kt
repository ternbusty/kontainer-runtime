package channel

/*
 * Each of the main and init processes holds one receiver and shares the
 * matching sender across processes via the FDs inherited at exec time.
 *
 * The interfaces below are the testability seam; concrete socket-backed
 * implementations live in SocketChannel.kt. Tests inject fakes that record
 * sent messages and let tests preseed received ones.
 */

/**
 * Sender side of the main channel (init -> main).
 */
interface MainSender {
    fun fd(): Int

    fun identifierMappingRequest()

    fun initReady()

    fun seccompNotifyRequest(fd: Int)

    fun execFailed(error: String)

    fun sendError(error: String)

    fun close()
}

/**
 * Receiver side of the main channel (init -> main).
 */
interface MainReceiver {
    fun fd(): Int

    fun waitForMappingRequest(): Message.WriteMapping

    fun waitForInitReady()

    fun waitForSeccompRequest(): Int

    fun close()
}

/**
 * Sender side of the init channel (main -> init).
 */
interface InitSender {
    fun fd(): Int

    fun mappingWritten()

    fun seccompNotifyDone()

    fun close()
}

/**
 * Receiver side of the init channel (main -> init).
 */
interface InitReceiver {
    fun fd(): Int

    fun waitForMappingAck()

    fun waitForSeccompRequestDone()

    fun close()
}
