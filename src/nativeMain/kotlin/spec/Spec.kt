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
    val user: User = User()
)

@Serializable
data class User(
    val uid: UInt = 0u,
    val gid: UInt = 0u
)

@Serializable
data class Linux(
    val namespaces: List<Namespace>? = null,
    val uidMappings: List<LinuxIdMapping>? = null,
    val gidMappings: List<LinuxIdMapping>? = null
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

/**
 * Load OCI spec from config.json file
 */
@OptIn(ExperimentalForeignApi::class)
fun loadSpec(configPath: String): Spec {
    val fp = fopen(configPath, "r") ?: throw Exception("Failed to open config file: $configPath")

    try {
        // Read entire file into memory
        memScoped {
            val buffer = StringBuilder()
            val chunk = allocArray<ByteVar>(1024)

            while (true) {
                val bytesRead = fread(chunk, 1.convert(), 1024.convert(), fp).toInt()
                if (bytesRead <= 0) break

                chunk[bytesRead] = 0
                buffer.append(chunk.toKString())
            }

            val json = buffer.toString()
            return Json { ignoreUnknownKeys = true }.decodeFromString(json)
        }
    } finally {
        fclose(fp)
    }
}
