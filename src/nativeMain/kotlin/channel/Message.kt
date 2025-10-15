package channel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages exchanged between main, intermediate, and init processes
 */
@Serializable
sealed class Message {
    @Serializable
    data class IntermediateReady(val pid: Int) : Message()

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
    data class ExecFailed(val error: String) : Message()

    @Serializable
    data class OtherError(val error: String) : Message()
}

/**
 * JSON encoder/decoder for Message
 */
object MessageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(message: Message): String {
        return json.encodeToString(message)
    }

    fun decode(text: String): Message {
        return json.decodeFromString(text)
    }
}
