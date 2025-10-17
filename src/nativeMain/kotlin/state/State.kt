package state

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logger.Logger
import platform.posix.*

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
    val status: String,  // "creating", "created", "running", "stopped", "paused"

    @SerialName("pid")
    val pid: Int? = null,  // Required on Linux when status is "created" or "running"

    @SerialName("bundle")
    val bundle: String,

    @SerialName("annotations")
    val annotations: Map<String, String>? = null,

    @SerialName("created")
    val created: String? = null  // ISO 8601 timestamp (extension, not in OCI spec)
)

private const val STATE_ROOT = "/run/kontainer"
private const val STATE_FILE_NAME = "state.json"

/**
 * Get the directory path for a container's state
 */
private fun getContainerDir(containerId: String): String {
    return "$STATE_ROOT/$containerId"
}

/**
 * Get the full path to the state file
 */
private fun getStatePath(containerId: String): String {
    return "${getContainerDir(containerId)}/$STATE_FILE_NAME"
}

/**
 * Get current timestamp in ISO 8601 format
 */
@OptIn(ExperimentalForeignApi::class)
private fun getCurrentTimestamp(): String = memScoped {
    val now = alloc<time_tVar>()
    time(now.ptr)

    val timeinfo = localtime(now.ptr) ?: run {
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
 * Creates /run/kontainer/{container-id}/ directory if it doesn't exist
 * and writes state.json file.
 *
 * @throws Exception if directory creation or file write fails
 */
@OptIn(ExperimentalForeignApi::class)
fun State.save() {
    val containerDir = getContainerDir(this.id)
    val statePath = getStatePath(this.id)

    Logger.debug("saving state to $statePath")

    // Create container directory (recursive)
    // First create STATE_ROOT if needed
    if (mkdir(STATE_ROOT, 0x1EDu) != 0) {  // 0x1ED = 0755 octal
        val errNum = errno
        if (errNum != EEXIST) {
            perror("mkdir($STATE_ROOT)")
            throw Exception("Failed to create state root directory: errno=$errNum")
        }
    }

    // Then create container directory
    if (mkdir(containerDir, 0x1EDu) != 0) {  // 0x1ED = 0755 octal
        val errNum = errno
        if (errNum != EEXIST) {
            perror("mkdir($containerDir)")
            throw Exception("Failed to create container directory: errno=$errNum")
        }
    }

    // Serialize state to JSON
    val json = try {
        Json.encodeToString(this)
    } catch (e: Exception) {
        Logger.error("failed to serialize state: ${e.message ?: "unknown"}")
        throw Exception("Failed to serialize state: ${e.message}")
    }

    Logger.debug("serialized state: $json")

    // Write to file
    val file = fopen(statePath, "w")
    if (file == null) {
        perror("fopen($statePath)")
        throw Exception("Failed to open state file for writing")
    }

    try {
        val written = fputs(json, file)
        if (written == EOF) {
            perror("fputs")
            throw Exception("Failed to write state file")
        }

        // Ensure data is flushed to disk
        if (fflush(file) != 0) {
            perror("fflush")
            throw Exception("Failed to flush state file")
        }

        Logger.info("saved state for container ${this.id}")
    } finally {
        fclose(file)
    }
}

/**
 * Check if a container with the given ID already exists
 *
 * @param containerId Container ID to check
 * @return true if container exists, false otherwise
 */
@OptIn(ExperimentalForeignApi::class)
fun containerExists(containerId: String): Boolean {
    val statePath = getStatePath(containerId)

    // Try to open state file for reading
    val file = fopen(statePath, "r")
    if (file != null) {
        fclose(file)
        Logger.debug("container $containerId exists at $statePath")
        return true
    }

    Logger.debug("container $containerId does not exist")
    return false
}

/**
 * Load container state from disk
 *
 * Reads state.json from /run/kontainer/{container-id}/
 *
 * @param containerId Container ID to load
 * @return State object
 * @throws Exception if file doesn't exist or parsing fails
 */
@OptIn(ExperimentalForeignApi::class)
fun loadState(containerId: String): State {
    val statePath = getStatePath(containerId)

    Logger.debug("loading state from $statePath")

    // Open file for reading
    val file = fopen(statePath, "r")
    if (file == null) {
        perror("fopen($statePath)")
        throw Exception("Failed to open state file (container may not exist)")
    }

    try {
        // Read entire file into buffer
        // Seek to end to get file size
        if (fseek(file, 0, SEEK_END) != 0) {
            perror("fseek")
            throw Exception("Failed to seek in state file")
        }

        val fileSize = ftell(file)
        if (fileSize == -1L) {
            perror("ftell")
            throw Exception("Failed to get state file size")
        }

        // Seek back to beginning
        if (fseek(file, 0, SEEK_SET) != 0) {
            perror("fseek")
            throw Exception("Failed to seek in state file")
        }

        // Read file content
        memScoped {
            val buffer = allocArray<ByteVar>(fileSize.toInt() + 1)
            val bytesRead = fread(buffer, 1u, fileSize.toULong(), file)
            if (bytesRead.toLong() != fileSize) {
                perror("fread")
                throw Exception("Failed to read state file")
            }

            buffer[fileSize.toInt()] = 0  // Null terminate
            val json = buffer.toKString()

            Logger.debug("loaded state json: $json")

            // Parse JSON
            try {
                val state = Json.decodeFromString<State>(json)
                Logger.info("loaded state for container ${state.id}")
                return state
            } catch (e: Exception) {
                Logger.error("failed to parse state: ${e.message ?: "unknown"}")
                throw Exception("Failed to parse state file: ${e.message}")
            }
        }
    } finally {
        fclose(file)
    }
}

/**
 * Create a new State object with current timestamp
 */
fun createState(
    ociVersion: String,
    containerId: String,
    status: String,
    pid: Int?,
    bundle: String,
    annotations: Map<String, String>? = null
): State {
    return State(
        ociVersion = ociVersion,
        id = containerId,
        status = status,
        pid = pid,
        bundle = bundle,
        annotations = annotations,
        created = getCurrentTimestamp()
    )
}

/**
 * Create a new State with updated status
 *
 * This is a convenience function for state transitions (e.g., "created" -> "running")
 */
fun State.withStatus(newStatus: String): State {
    return this.copy(status = newStatus)
}

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
 * Recursively removes /run/kontainer/{container-id}/
 *
 * @param containerId Container ID
 * @throws Exception if directory deletion fails
 */
@OptIn(ExperimentalForeignApi::class)
fun deleteContainerDir(containerId: String) {
    val containerDir = getContainerDir(containerId)

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

            buffer[bytesRead.toInt()] = 0  // Null terminate
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
 * Updates status to "stopped" if:
 * - PID is null
 * - Process doesn't exist in /proc
 * - Process is zombie (Z) or dead (X)
 *
 * @return New State with updated status, or original State if no change needed
 */
fun State.refreshStatus(): State {
    val newStatus = when {
        // No PID means container is stopped
        this.pid == null -> {
            Logger.debug("container ${this.id} has no PID, status: stopped")
            "stopped"
        }

        // Check if process is actually alive
        !isProcessAlive(this.pid) -> {
            Logger.debug("container ${this.id} process ${this.pid} is not alive, status: stopped")
            "stopped"
        }

        // Process exists and is alive
        else -> {
            // Keep current status if it's a valid non-running state
            when (this.status) {
                "creating", "created", "paused" -> {
                    Logger.debug("container ${this.id} process ${this.pid} is alive, keeping status: ${this.status}")
                    this.status
                }

                "stopped" -> {
                    // Process is alive but status is stopped - inconsistent state
                    // This shouldn't happen, but if it does, update to running
                    Logger.warn("container ${this.id} status is 'stopped' but process ${this.pid} is alive, updating to 'running'")
                    "running"
                }

                else -> {
                    // Default to running if process is alive
                    Logger.debug("container ${this.id} process ${this.pid} is alive, status: running")
                    "running"
                }
            }
        }
    }

    // Return updated state if status changed
    return if (newStatus != this.status) {
        Logger.info("container ${this.id} status changed: ${this.status} -> $newStatus")
        this.copy(status = newStatus)
    } else {
        this
    }
}
