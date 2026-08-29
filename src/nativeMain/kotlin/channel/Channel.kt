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

    /** Send a mount tree fd with an idmap request to the main process. */
    fun mountFdRequest(
        treeFd: Int,
        uidMap: String?,
        gidMap: String?,
        recursive: Boolean,
        implied: Boolean,
    )

    /** Request the main process to clone a bind-mount source via open_tree (no fd attached). */
    fun bindSourceRequest(
        source: String,
        isRbind: Boolean = false,
    )

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

    /**
     * Receive the next message from init, along with an optional fd
     * carried via SCM_RIGHTS (-1 when no fd is attached). Used by
     * the main process message loop to handle MountFdRequest,
     * SeccompNotify, and InitReady in any order.
     */
    fun receiveNextMessage(): Pair<Message, Int>

    fun close()
}

/**
 * Sender side of the init channel (main -> init).
 */
interface InitSender {
    fun fd(): Int

    fun mappingWritten()

    fun seccompNotifyDone()

    /** Notify init that mount_setattr has been applied to the tree fd. */
    fun mountFdDone()

    /** Send an O_PATH fd for a bind-mount source to init via SCM_RIGHTS. */
    fun bindSourceDone(fd: Int)

    fun close()
}

/**
 * Receiver side of the init channel (main -> init).
 */
interface InitReceiver {
    fun fd(): Int

    fun waitForMappingAck()

    fun waitForSeccompRequestDone()

    /** Block until main confirms mount_setattr is done. */
    fun waitForMountFdDone()

    /** Block until main sends a bind-source fd via SCM_RIGHTS. Returns the fd. */
    fun waitForBindSourceFd(): Int

    fun close()
}
