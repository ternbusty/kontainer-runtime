package spec

import kotlinx.cinterop.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * OCI Runtime Specification (minimal implementation)
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md
 */
@Serializable
data class Spec(
    val ociVersion: String = "1.0.0",
    val root: Root? = null,
    val process: Process? = null,
    val hostname: String? = null,
    val linux: Linux? = null
)

@Serializable
data class Root(
    val path: String,
    val readonly: Boolean = false
)

@Serializable
data class Process(
    val args: List<String>? = null,
    val env: List<String>? = null,
    val cwd: String = "/",
    val noNewPrivileges: Boolean? = null,
    val user: User = User()
)

@Serializable
data class User(
    val uid: UInt = 0u,
    val gid: UInt = 0u
)

@Serializable
data class Namespace(
    val type: String
)

@Serializable
data class LinuxIdMapping(
    val containerID: UInt,
    val hostID: UInt,
    val size: UInt
)

@Serializable
data class LinuxMemory(
    val limit: Long? = null,
    val reservation: Long? = null,
    val swap: Long? = null
)

@Serializable
data class LinuxCpu(
    val shares: Long? = null,
    val quota: Long? = null,
    val period: Long? = null
)

/**
 * Linux resource limits
 *
 * KNOWN ISSUE (Kotlin/Native 2.1.0 + kotlinx.serialization 1.8.0):
 * Deserializing a JSON object with BOTH memory and cpu fields simultaneously
 * causes "double free or corruption" error. Each field works correctly when
 * deserialized alone.
 *
 * Workaround: Use either memory OR cpu, not both at the same time.
 * This appears to be a bug in Kotlin/Native's serialization implementation
 * for nested nullable data classes.
 */
@Serializable
data class LinuxResources(
    val memory: LinuxMemory? = null,
    val cpu: LinuxCpu? = null
)

@Serializable
data class Linux(
    val namespaces: List<Namespace>? = null,
    val uidMappings: List<LinuxIdMapping>? = null,
    val gidMappings: List<LinuxIdMapping>? = null,
    val resources: LinuxResources? = null,
    val cgroupsPath: String? = null
)

/**
 * Load OCI spec from config.json file
 */
@OptIn(ExperimentalForeignApi::class)
fun loadSpec(configPath: String): Spec {
    val fp = fopen(configPath, "r") ?: throw Exception("Failed to open config file: $configPath")

    try {
        // Read entire file into memory
        val json = memScoped {
            val buffer = StringBuilder()
            // Allocate 1025 bytes: 1024 for data + 1 for null terminator
            val chunk = allocArray<ByteVar>(1025)

            while (true) {
                val bytesRead = fread(chunk, 1.convert(), 1024.convert(), fp).toInt()
                if (bytesRead <= 0) break

                // Null terminate the chunk
                chunk[bytesRead] = 0
                buffer.append(chunk.toKString())
            }

            buffer.toString()
        }

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        return jsonParser.decodeFromString<Spec>(json)
    } finally {
        fclose(fp)
    }
}
