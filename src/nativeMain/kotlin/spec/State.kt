package spec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Container state as defined by OCI Runtime Specification
 *
 * The state of the container includes the following properties:
 * - ociVersion: version of the Open Container Initiative Runtime Specification
 * - id: container's ID (unique across all containers on the host)
 * - status: runtime state of the container (creating, created, running, stopped)
 * - pid: ID of the container process (on the host)
 * - bundle: absolute path to the container's bundle directory
 * - annotations: annotations associated with the container
 */
@Serializable
data class ContainerState(
    val ociVersion: String,
    val id: String,
    val status: String,
    val pid: Int? = null,
    val bundle: String,
    val annotations: Map<String, String>? = null
)

/**
 * JSON encoder for ContainerState
 */
object StateCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = false
    }

    fun encode(state: ContainerState): String {
        return json.encodeToString(ContainerState.serializer(), state)
    }

    fun decode(text: String): ContainerState {
        return json.decodeFromString(ContainerState.serializer(), text)
    }
}
