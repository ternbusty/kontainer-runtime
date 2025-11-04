package state

import kotlinx.cinterop.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import logger.Logger
import platform.posix.*
import utils.JsonCodec
import utils.createDirectories
import utils.fileExists

/**
 * Container status enum
 *
 * Represents the runtime status of a container.
 * Compatible with OCI Runtime Specification.
 */
enum class ContainerStatus(
    val value: String,
) {
    CREATING("creating"),
    CREATED("created"),
    RUNNING("running"),
    STOPPED("stopped"),
    ;

    /**
     * Check if container can be started
     * Only CREATED containers can be started
     */
    fun canStart(): Boolean = this == CREATED

    /**
     * Check if container can receive signals
     * CREATED and RUNNING containers can be killed
     */
    fun canKill(): Boolean = this in setOf(CREATED, RUNNING)

    /**
     * Check if container can be deleted
     * Only STOPPED containers can be deleted (without force flag)
     */
    fun canDelete(): Boolean = this == STOPPED

    companion object {
        /**
         * Parse status string to ContainerStatus
         *
         * @param value Status string ("creating", "created", etc.)
         * @return ContainerStatus enum value
         * @throws IllegalArgumentException if status string is unknown
         */
        fun fromString(value: String): ContainerStatus =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown container status: $value")
    }
}

/**
 * Custom serializer for ContainerStatus enum
 *
 * Serializes enum as its string value for JSON compatibility
 */
object ContainerStatusSerializer : KSerializer<ContainerStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ContainerStatus", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ContainerStatus,
    ) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): ContainerStatus {
        val str = decoder.decodeString()
        return ContainerStatus.fromString(str)
    }
}

/**
 * Container state information
 *
 * Represents the runtime state of a container, saved to disk for
 * persistence across create/start/stop/delete commands.
 *
 * Compliant with OCI Runtime Specification:
 * https://github.com/opencontainers/runtime-spec/blob/main/runtime.md#state
 *
 * Saved to: /run/kontainer/{container-id}/state.json
 */
@Serializable
data class State(
    @SerialName("ociVersion")
    val ociVersion: String,
    @SerialName("id")
    val id: String,
    @SerialName("status")
    @Serializable(with = ContainerStatusSerializer::class)
    val status: ContainerStatus,
    @SerialName("pid")
    val pid: Int? = null, // Required on Linux when status is "created" or "running"
    @SerialName("bundle")
    val bundle: String,
    @SerialName("annotations")
    val annotations: Map<String, String>? = null,
    @SerialName("created")
    val created: String? = null, // ISO 8601 timestamp (extension, not in OCI spec)
)

private const val STATE_FILE_NAME = "state.json"

/**
 * Get the directory path for a container's state
 *
 * @param rootPath Root directory for container state (e.g., /run/kontainer)
 * @param containerId Container ID
 * @return Path to container's state directory
 */
private fun getContainerDir(
    rootPath: String,
    containerId: String,
): String = "$rootPath/$containerId"

/**
 * Get the full path to the state file
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @return Path to state.json file
 */
private fun getStatePath(
    rootPath: String,
    containerId: String,
): String = "${getContainerDir(rootPath, containerId)}/$STATE_FILE_NAME"

/**
 * Get current timestamp in ISO 8601 format
 */
@OptIn(ExperimentalForeignApi::class)
private fun getCurrentTimestamp(): String =
    memScoped {
        val now = alloc<time_tVar>()
        time(now.ptr)

        val timeinfo =
            localtime(now.ptr) ?: run {
                Logger.warn("localtime failed, using fallback timestamp")
                return "1970-01-01T00:00:00Z"
            }

        // Format: YYYY-MM-DDTHH:MM:SSZ
        val buffer = allocArray<ByteVar>(32)
        strftime(buffer, 32.toULong(), "%Y-%m-%dT%H:%M:%SZ", timeinfo)
        return buffer.toKString()
    }

/**
 * Save container state to disk
 *
 * Creates {rootPath}/{container-id}/ directory if it doesn't exist
 * and writes state.json file.
 *
 * @param rootPath Root directory for container state (e.g., /run/kontainer)
 * @throws Exception if directory creation or file write fails
 */
@OptIn(ExperimentalForeignApi::class)
fun State.save(rootPath: String) {
    val containerDir = getContainerDir(rootPath, this.id)
    val statePath = getStatePath(rootPath, this.id)

    Logger.debug("saving state to $statePath")

    // Create container directory (and parent directories if needed)
    createDirectories(containerDir)

    // Serialize state to JSON and write to file
    try {
        JsonCodec.writeToFile(statePath, this, prettyPrint = true)
    } catch (e: Exception) {
        Logger.error("failed to save state: ${e.message ?: "unknown"}")
        throw Exception("Failed to save state: ${e.message}")
    }

    Logger.info("saved state for container ${this.id}")
}

/**
 * Check if a container with the given ID already exists
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID to check
 * @return true if container exists, false otherwise
 */
@OptIn(ExperimentalForeignApi::class)
fun containerExists(
    rootPath: String,
    containerId: String,
): Boolean {
    val statePath = getStatePath(rootPath, containerId)
    val exists = fileExists(statePath)

    if (exists) {
        Logger.debug("container $containerId exists at $statePath")
    } else {
        Logger.debug("container $containerId does not exist")
    }

    return exists
}

/**
 * Load container state from disk
 *
 * Reads state.json from {rootPath}/{container-id}/
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID to load
 * @return State object
 * @throws Exception if file doesn't exist or parsing fails
 */
@OptIn(ExperimentalForeignApi::class)
fun loadState(
    rootPath: String,
    containerId: String,
): State {
    val statePath = getStatePath(rootPath, containerId)

    Logger.debug("loading state from $statePath")

    val state =
        try {
            JsonCodec.loadFromFile<State>(statePath)
        } catch (e: Exception) {
            Logger.error("failed to load state: ${e.message ?: "unknown"}")
            throw Exception("Failed to load state file (container may not exist): ${e.message}")
        }

    Logger.info("loaded state for container ${state.id}")
    return state
}

/**
 * Create a new State object with current timestamp
 */
fun createState(
    ociVersion: String,
    containerId: String,
    status: ContainerStatus,
    pid: Int?,
    bundle: String,
    annotations: Map<String, String>? = null,
): State =
    State(
        ociVersion = ociVersion,
        id = containerId,
        status = status,
        pid = pid,
        bundle = bundle,
        annotations = annotations,
        created = getCurrentTimestamp(),
    )

/**
 * Create a new State with updated status
 */
fun State.withStatus(newStatus: ContainerStatus): State = this.copy(status = newStatus)

/**
 * Delete notify socket for a container
 *
 * Removes /tmp/kontainer-{container-id}.sock
 * Errors are ignored (socket may not exist)
 *
 * @param containerId Container ID
 */
@OptIn(ExperimentalForeignApi::class)
fun deleteNotifySocket(containerId: String) {
    val notifySocketPath = "/tmp/kontainer-$containerId.sock"

    Logger.debug("deleting notify socket: $notifySocketPath")

    if (unlink(notifySocketPath) != 0) {
        val errNum = errno
        if (errNum != ENOENT) {
            // File doesn't exist - OK, already deleted
            Logger.warn("failed to delete notify socket $notifySocketPath: errno=$errNum")
        }
    } else {
        Logger.debug("deleted notify socket: $notifySocketPath")
    }
}

/**
 * Delete container directory and all its contents
 *
 * Recursively removes {rootPath}/{container-id}/
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @throws Exception if directory deletion fails
 */
@OptIn(ExperimentalForeignApi::class)
fun deleteContainerDir(
    rootPath: String,
    containerId: String,
) {
    val containerDir = getContainerDir(rootPath, containerId)

    Logger.debug("deleting container directory: $containerDir")

    // Check if directory exists
    val dir = opendir(containerDir)
    if (dir == null) {
        val errNum = errno
        if (errNum == ENOENT) {
            // Directory doesn't exist - already deleted
            Logger.debug("container directory $containerDir does not exist")
            return
        }
        perror("opendir")
        Logger.error("failed to open container directory $containerDir: errno=$errNum")
        throw Exception("Failed to open container directory: errno=$errNum")
    }
    closedir(dir)

    // Use rm -rf to recursively delete
    // This is simpler than implementing recursive deletion in Kotlin
    val result = system("rm -rf $containerDir")
    if (result != 0) {
        Logger.error("failed to delete container directory $containerDir: exit code=$result")
        throw Exception("Failed to delete container directory: exit code=$result")
    }

    Logger.info("deleted container directory: $containerDir")
}

/**
 * Check if a process is alive by reading /proc/{pid}/stat
 *
 * @param pid Process ID to check
 * @return true if process exists and is not zombie/dead, false otherwise
 */
@OptIn(ExperimentalForeignApi::class)
private fun isProcessAlive(pid: Int): Boolean {
    val statPath = "/proc/$pid/stat"

    // Try to open /proc/{pid}/stat
    val file = fopen(statPath, "r")
    if (file == null) {
        // Process doesn't exist
        Logger.debug("process $pid does not exist (/proc/$pid/stat not found)")
        return false
    }

    try {
        // Read the stat file
        // Format: pid (comm) state ppid ...
        // We need to parse the state field (3rd field)
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val bytesRead = fread(buffer, 1u, 4095u, file)
            if (bytesRead == 0UL) {
                Logger.warn("failed to read /proc/$pid/stat")
                return false
            }

            buffer[bytesRead.toInt()] = 0 // Null terminate
            val statContent = buffer.toKString()

            // Parse state: skip "pid (comm) " and get the state character
            // comm can contain spaces and parentheses, so we need to find the last ')'
            val lastParen = statContent.lastIndexOf(')')
            if (lastParen == -1 || lastParen + 2 >= statContent.length) {
                Logger.warn("failed to parse /proc/$pid/stat")
                return false
            }

            // State is the character after ") "
            val state = statContent[lastParen + 2]

            Logger.debug("process $pid state: $state")

            // Check if process is zombie (Z) or dead (X)
            return when (state) {
                'Z' -> {
                    Logger.debug("process $pid is zombie")
                    false
                }

                'X' -> {
                    Logger.debug("process $pid is dead")
                    false
                }

                else -> {
                    // Process is alive (R, S, D, T, etc.)
                    true
                }
            }
        }
    } finally {
        fclose(file)
    }
}

/**
 * Refresh container status based on actual process state
 *
 * Checks /proc/{pid} to determine if the container process is still running.
 * Updates status to STOPPED if:
 * - PID is null
 * - Process doesn't exist in /proc
 * - Process is zombie (Z) or dead (X)
 *
 * @return New State with updated status, or original State if no change needed
 */
fun State.refreshStatus(): State {
    val newStatus =
        when {
            // No PID means container is stopped
            this.pid == null -> {
                Logger.debug("container ${this.id} has no PID, status: stopped")
                ContainerStatus.STOPPED
            }

            // Check if process is actually alive
            !isProcessAlive(this.pid) -> {
                Logger.debug("container ${this.id} process ${this.pid} is not alive, status: stopped")
                ContainerStatus.STOPPED
            }

            // Process exists and is alive
            else -> {
                // Keep current status if it's a valid non-running state
                when (this.status) {
                    ContainerStatus.CREATING, ContainerStatus.CREATED -> {
                        Logger.debug("container ${this.id} process ${this.pid} is alive, keeping status: ${this.status.value}")
                        this.status
                    }

                    ContainerStatus.STOPPED -> {
                        // Process is alive but status is stopped - inconsistent state
                        // This shouldn't happen, but if it does, update to running
                        Logger.warn("container ${this.id} status is 'stopped' but process ${this.pid} is alive, updating to 'running'")
                        ContainerStatus.RUNNING
                    }

                    ContainerStatus.RUNNING -> {
                        // Process is alive and running
                        Logger.debug("container ${this.id} process ${this.pid} is alive, status: running")
                        ContainerStatus.RUNNING
                    }
                }
            }
        }

    // Return updated state if status changed
    return if (newStatus != this.status) {
        Logger.info("container ${this.id} status changed: ${this.status.value} -> ${newStatus.value}")
        this.copy(status = newStatus)
    } else {
        this
    }
}
