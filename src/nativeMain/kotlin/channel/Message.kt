package channel

import kotlinx.serialization.Serializable

/**
 * Messages exchanged between main and init processes
 *
 * There are only two processes that communicate:
 * - Main process (parent)
 * - Init process (PID 1 in container, Stage-2 from bootstrap.c)
 */
@Serializable
sealed class Message {
    @Serializable
    object InitReady : Message()

    @Serializable
    object WriteMapping : Message()

    @Serializable
    object MappingWritten : Message()

    @Serializable
    object SeccompNotify : Message()

    @Serializable
    object SeccompNotifyDone : Message()

    @Serializable
    data class ExecFailed(
        val error: String,
    ) : Message()

    @Serializable
    data class OtherError(
        val error: String,
    ) : Message()
}
