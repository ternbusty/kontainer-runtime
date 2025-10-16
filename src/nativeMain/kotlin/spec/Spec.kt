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
    val root: Root,
    val process: Process,
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
    val args: List<String>,
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
 */
@Serializable
data class LinuxResources(
    val memory: LinuxMemory? = null,
    val cpu: LinuxCpu? = null
)

/**
 * Seccomp argument comparison
 */
@Serializable
data class SeccompArg(
    val index: UInt,
    val value: ULong,
    val op: String
)

/**
 * Filter for conditional seccomp rules
 */
@Serializable
data class Filter(
    val caps: List<String>? = null,
    val arches: List<String>? = null,
    val minKernel: String? = null
)

/**
 * Seccomp syscall rule
 */
@Serializable
data class LinuxSyscall(
    val names: List<String>,
    val action: String,
    val args: List<SeccompArg>? = null,
    val errnoRet: UInt? = null,
    val includes: Filter? = null,
    val excludes: Filter? = null,
    val comment: String? = null
)

/**
 * Architecture mapping for seccomp
 */
@Serializable
data class ArchMap(
    val architecture: String,
    val subArchitectures: List<String>? = null
)

/**
 * Linux seccomp configuration
 */
@Serializable
data class LinuxSeccomp(
    val defaultAction: String,
    val defaultErrnoRet: UInt? = null,
    val architectures: List<String>? = null,
    val archMap: List<ArchMap>? = null,
    val syscalls: List<LinuxSyscall>? = null,
    val flags: List<String>? = null,
    val listenerPath: String? = null,
    val listenerMetadata: String? = null
)

@Serializable
data class Linux(
    val namespaces: List<Namespace>? = null,
    val uidMappings: List<LinuxIdMapping>? = null,
    val gidMappings: List<LinuxIdMapping>? = null,
    val resources: LinuxResources? = null,
    val cgroupsPath: String? = null,
    val seccomp: LinuxSeccomp? = null
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
        val spec = jsonParser.decodeFromString<Spec>(json)

        // Validate process.args is not empty (kotlinx.serialization doesn't check this)
        if (spec.process.args.isEmpty()) {
            throw Exception("Spec validation failed: process.args must not be empty")
        }

        return spec
    } finally {
        fclose(fp)
    }
}
