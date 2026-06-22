package spec

import kotlinx.cinterop.*
import kotlinx.serialization.Serializable
import utils.FileSystem
import utils.JsonCodec

/**
 * OCI Runtime Specification (minimal implementation)
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md
 */
@Serializable
data class Spec(
    val ociVersion: String = "1.0.0",
    val root: Root,
    val process: Process = Process(args = emptyList()),
    val hostname: String? = null,
    val mounts: List<Mount>? = null,
    val annotations: Map<String, String>? = null,
    val hooks: Hooks? = null,
    val linux: Linux? = null,
) {
    /**
     * Check if a namespace type exists in the spec
     */
    fun hasNamespace(type: String): Boolean = linux?.namespaces?.any { it.type == type } ?: false
}

/**
 * One entry in spec.hooks.* — an external program to run at a lifecycle point.
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md#posix-platform-hooks
 */
@Serializable
data class Hook(
    val path: String,
    val args: List<String>? = null,
    val env: List<String>? = null,
    val timeout: Int? = null,
)

/**
 * The five hook points runtimes invoke. prestart/poststart/poststop are the
 * pre-1.0.2 names that the runtime-tools validation suite still exercises;
 * the createRuntime/createContainer/startContainer trio replaces them.
 */
@Serializable
data class Hooks(
    val prestart: List<Hook>? = null,
    val createRuntime: List<Hook>? = null,
    val createContainer: List<Hook>? = null,
    val startContainer: List<Hook>? = null,
    val poststart: List<Hook>? = null,
    val poststop: List<Hook>? = null,
)

/**
 * Mount entry from the OCI runtime-spec `mounts[]` array.
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md#mounts
 */
@Serializable
data class Mount(
    val destination: String,
    val type: String? = null,
    val source: String? = null,
    val options: List<String>? = null,
)

@Serializable
data class Root(
    val path: String,
    val readonly: Boolean = false,
)

@Serializable
data class Process(
    val args: List<String>,
    val env: List<String>? = null,
    val cwd: String = "/",
    val noNewPrivileges: Boolean? = null,
    val user: User = User(),
    val capabilities: LinuxCapabilities? = null,
    val rlimits: List<POSIXRlimit>? = null,
    val umask: UInt? = null,
    val apparmorProfile: String? = null,
    val selinuxLabel: String? = null,
    val terminal: Boolean = false,
    val consoleSize: ConsoleSize? = null,
)

/** Window size of the container's pseudo-terminal. */
@Serializable
data class ConsoleSize(
    val height: UInt,
    val width: UInt,
)

@Serializable
data class User(
    val uid: UInt = 0u,
    val gid: UInt = 0u,
    val additionalGids: List<UInt>? = null,
)

/**
 * POSIX resource limits (rlimit)
 * See https://man7.org/linux/man-pages/man2/getrlimit.2.html
 */
@Serializable
data class POSIXRlimit(
    val type: String, // e.g., "RLIMIT_NOFILE", "RLIMIT_NPROC"
    val hard: ULong,
    val soft: ULong,
)

/**
 * Linux capabilities configuration
 * See https://man7.org/linux/man-pages/man7/capabilities.7.html
 */
@Serializable
data class LinuxCapabilities(
    val bounding: List<String>? = null,
    val effective: List<String>? = null,
    val inheritable: List<String>? = null,
    val permitted: List<String>? = null,
    val ambient: List<String>? = null,
)

@Serializable
data class Namespace(
    val type: String,
    val path: String? = null,
)

@Serializable
data class LinuxIdMapping(
    val containerID: UInt,
    val hostID: UInt,
    val size: UInt,
)

@Serializable
data class LinuxMemory(
    val limit: Long? = null,
    val reservation: Long? = null,
    val swap: Long? = null,
)

@Serializable
data class LinuxCpu(
    val shares: Long? = null,
    val quota: Long? = null,
    val period: Long? = null,
)

@Serializable
data class LinuxPids(
    val limit: Long? = null,
)

@Serializable
data class LinuxHugepageLimit(
    val pageSize: String,
    val limit: Long,
)

/**
 * Linux resource limits
 */
@Serializable
data class LinuxResources(
    val pids: LinuxPids? = null,
    val hugepageLimits: List<LinuxHugepageLimit>? = null,
    val memory: LinuxMemory? = null,
    val cpu: LinuxCpu? = null,
)

/**
 * Seccomp argument comparison
 */
@Serializable
data class SeccompArg(
    val index: UInt,
    val value: ULong,
    val valueTwo: ULong? = null,
    val op: String,
)

/**
 * Filter for conditional seccomp rules
 */
@Serializable
data class Filter(
    val caps: List<String>? = null,
    val arches: List<String>? = null,
    val minKernel: String? = null,
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
    val comment: String? = null,
)

/**
 * Architecture mapping for seccomp
 */
@Serializable
data class ArchMap(
    val architecture: String,
    val subArchitectures: List<String>? = null,
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
    val listenerMetadata: String? = null,
)

@Serializable
data class Linux(
    val namespaces: List<Namespace>? = null,
    val uidMappings: List<LinuxIdMapping>? = null,
    val gidMappings: List<LinuxIdMapping>? = null,
    val resources: LinuxResources? = null,
    val cgroupsPath: String? = null,
    val seccomp: LinuxSeccomp? = null,
    val maskedPaths: List<String>? = null,
    val readonlyPaths: List<String>? = null,
    val sysctl: Map<String, String>? = null,
    val rootfsPropagation: String? = null,
    val devices: List<LinuxDevice>? = null,
    val mountLabel: String? = null,
)

/**
 * A device node entry from spec.linux.devices[]. type is one of "c", "b", "u", "p".
 * https://github.com/opencontainers/runtime-spec/blob/main/config-linux.md#devices
 */
@Serializable
data class LinuxDevice(
    val path: String,
    val type: String,
    val major: Long? = null,
    val minor: Long? = null,
    val fileMode: UInt? = null,
    val uid: UInt? = null,
    val gid: UInt? = null,
)

/**
 * Load OCI spec from config.json file
 */
@OptIn(ExperimentalForeignApi::class)
fun loadSpec(
    fs: FileSystem,
    configPath: String,
): Spec {
    // Read and parse JSON file
    val spec = JsonCodec.loadFromFile<Spec>(fs, configPath)

    // process.args may legitimately be empty: the spec allows omitting
    // spec.process entirely, in which case create/start should still succeed
    // (the container infrastructure is set up but the init process exec's
    // nothing and exits immediately). The OCI runtime-tools start.t test
    // exercises this path. Don't reject it here.

    // The OCI runtime-spec says the runtime MUST generate an error on invalid /
    // unsupported values. ociVersion is the canonical example: it has to be a
    // semver like "1.0.0", not free text. Reject anything that doesn't parse as
    // major.minor.patch.
    val versionRegex = Regex("^\\d+\\.\\d+\\.\\d+(?:-[\\w.-]+)?$")
    if (!versionRegex.matches(spec.ociVersion)) {
        throw Exception("Spec validation failed: ociVersion '${spec.ociVersion}' is not a valid semver")
    }

    return spec
}
