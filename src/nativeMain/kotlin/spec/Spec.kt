package spec

import config.BuildConfig
import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import utils.FileSystem
import utils.JsonCodec

/**
 * OCI Runtime Specification (minimal implementation)
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md
 */
@Serializable
data class Spec(
    val ociVersion: String = BuildConfig.OCI_SPEC_VERSION,
    val root: Root,
    val process: Process = Process(args = emptyList()),
    val hostname: String? = null,
    val domainname: String? = null,
    val mounts: List<Mount>? = null,
    val annotations: Map<String, String>? = null,
    val hooks: Hooks? = null,
    val linux: Linux? = null,
) {
    /**
     * Check if a namespace type exists in the spec (either creating or joining).
     */
    fun hasNamespace(type: String): Boolean = linux?.namespaces?.any { it.type == type } ?: false

    /**
     * Check if the spec creates (unshares) a new namespace of the given type.
     * Returns false when the namespace is being *joined* (has a path).
     */
    fun createsNamespace(type: String): Boolean = linux?.namespaces?.any { it.type == type && it.path.isNullOrEmpty() } ?: false
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
    val uidMappings: List<LinuxIdMapping>? = null,
    val gidMappings: List<LinuxIdMapping>? = null,
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
    val oomScoreAdj: Int? = null,
    val apparmorProfile: String? = null,
    val selinuxLabel: String? = null,
    val terminal: Boolean = false,
    val consoleSize: ConsoleSize? = null,
    val ioPriority: LinuxIOPriority? = null,
    val scheduler: LinuxScheduler? = null,
    val execCPUAffinity: ExecCPUAffinity? = null,
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
    val umask: UInt? = null,
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
    val checkBeforeUpdate: Boolean? = null,
)

@Serializable
data class LinuxCpu(
    val shares: Long? = null,
    val quota: Long? = null,
    val period: Long? = null,
    val cpus: String? = null,
    val mems: String? = null,
    val burst: Long? = null,
    val idle: Long? = null,
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
 * A device-access rule from spec.linux.resources.devices[].
 * The cgroup v2 device controller uses eBPF to enforce these.
 * https://github.com/opencontainers/runtime-spec/blob/main/config-linux.md#devices-1
 */
@Serializable
data class LinuxDeviceCgroup(
    val allow: Boolean,
    val type: String? = null,
    val major: Long? = null,
    val minor: Long? = null,
    val access: String? = null,
)

/**
 * A per-device throttle entry for block I/O.
 */
@Serializable
data class LinuxThrottleDevice(
    val major: Long,
    val minor: Long,
    val rate: Long,
)

/**
 * Block I/O resource limits (OCI v1-era structure; the runtime
 * translates these to cgroup v2 io.max / io.weight).
 */
@Serializable
data class LinuxBlockIO(
    val weight: Long? = null,
    val leafWeight: Long? = null,
    val throttleReadBpsDevice: List<LinuxThrottleDevice>? = null,
    val throttleWriteBpsDevice: List<LinuxThrottleDevice>? = null,
    val throttleReadIOPSDevice: List<LinuxThrottleDevice>? = null,
    val throttleWriteIOPSDevice: List<LinuxThrottleDevice>? = null,
)

/**
 * Linux resource limits
 */
@Serializable
data class LinuxResources(
    val devices: List<LinuxDeviceCgroup>? = null,
    val pids: LinuxPids? = null,
    val hugepageLimits: List<LinuxHugepageLimit>? = null,
    val memory: LinuxMemory? = null,
    val cpu: LinuxCpu? = null,
    val unified: Map<String, String>? = null,
    val blockIO: LinuxBlockIO? = null,
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
    val timeOffsets: Map<String, LinuxTimeOffset>? = null,
    val memoryPolicy: LinuxMemoryPolicy? = null,
    val netDevices: Map<String, LinuxNetDevice>? = null,
)

/**
 * Time offset for a POSIX clock, used with CLONE_NEWTIME.
 * Written to /proc/<pid>/timens_offsets before the process enters the new
 * time namespace. See time_namespaces(7).
 */
@Serializable
data class LinuxTimeOffset(
    val secs: Long = 0,
    val nanosecs: Long = 0,
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
 * I/O priority for the container process (process.ioPriority).
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md#io-priority
 */
@Serializable
data class LinuxIOPriority(
    @SerialName("class") val clazz: String? = null,
    val priority: Int = 0,
) {
    /** Map the OCI class string to the kernel IOPRIO_CLASS_* value. */
    fun classValue(): Int =
        when (clazz) {
            "IOPRIO_CLASS_RT" -> 1
            "IOPRIO_CLASS_BE" -> 2
            "IOPRIO_CLASS_IDLE" -> 3
            else -> 2 // default: best-effort
        }
}

/**
 * Scheduler policy for the container process (process.scheduler).
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md#scheduler
 */
@Serializable
data class LinuxScheduler(
    val policy: String? = null,
    val nice: Int? = null,
    val priority: Int? = null,
    val flags: List<String>? = null,
    val runtime: Long? = null,
    val deadline: Long? = null,
    val period: Long? = null,
) {
    fun policyValue(): Int =
        when (policy) {
            "SCHED_OTHER" -> 0
            "SCHED_FIFO" -> 1
            "SCHED_RR" -> 2
            "SCHED_BATCH" -> 3
            "SCHED_ISO" -> 4
            "SCHED_IDLE" -> 5
            "SCHED_DEADLINE" -> 6
            else -> 0
        }

    fun flagBits(): Long {
        if (flags.isNullOrEmpty()) return 0
        var bits = 0L
        for (f in flags) {
            bits = bits or
                when (f) {
                    "SCHED_FLAG_RESET_ON_FORK" -> 0x01L
                    "SCHED_FLAG_RECLAIM" -> 0x02L
                    "SCHED_FLAG_DL_OVERRUN" -> 0x04L
                    "SCHED_FLAG_KEEP_POLICY" -> 0x08L
                    "SCHED_FLAG_KEEP_PARAMS" -> 0x10L
                    "SCHED_FLAG_UTIL_CLAMP_MIN" -> 0x20L
                    "SCHED_FLAG_UTIL_CLAMP_MAX" -> 0x40L
                    else -> 0L
                }
        }
        return bits
    }
}

/**
 * Per-process CPU affinity for exec (process.execCPUAffinity).
 * https://github.com/opencontainers/runtime-spec/blob/main/config.md#exec-cpu-affinity
 */
@Serializable
data class ExecCPUAffinity(
    val initial: String? = null,
    @SerialName("final") val fin: String? = null,
)

/**
 * NUMA memory policy (linux.memoryPolicy).
 * https://github.com/opencontainers/runtime-spec/blob/main/config-linux.md#memory-policy
 */
@Serializable
data class LinuxMemoryPolicy(
    val mode: String? = null,
    val nodes: String? = null,
    val flags: List<String>? = null,
)

/**
 * Network device to move into the container namespace (linux.netDevices).
 * The map key in the spec is the host interface name.
 */
@Serializable
data class LinuxNetDevice(
    val name: String? = null,
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
