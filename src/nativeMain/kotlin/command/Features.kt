package command

import config.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Features command — output a JSON document describing the runtime's capabilities.
 * Matches runc's `features` subcommand output format.
 */
fun features() {
    val features =
        RuntimeFeatures(
            ociVersionMin = "1.0.0",
            ociVersionMax = BuildConfig.OCI_SPEC_VERSION,
            hooks =
                listOf(
                    "prestart",
                    "createRuntime",
                    "createContainer",
                    "startContainer",
                    "poststart",
                    "poststop",
                ),
            mountOptions =
                listOf(
                    "async",
                    "atime",
                    "bind",
                    "defaults",
                    "dev",
                    "diratime",
                    "dirsync",
                    "exec",
                    "iversion",
                    "lazytime",
                    "loud",
                    "mand",
                    "noatime",
                    "nodev",
                    "nodiratime",
                    "noexec",
                    "noiversion",
                    "nolazytime",
                    "nomand",
                    "norelatime",
                    "nosuid",
                    "nosymfollow",
                    "rbind",
                    "rdev",
                    "rdiratime",
                    "relatime",
                    "remount",
                    "rexec",
                    "rnoatime",
                    "rnodev",
                    "rnodiratime",
                    "rnoexec",
                    "rnorelatime",
                    "rnosuid",
                    "rnosymfollow",
                    "rnostrictatime",
                    "ro",
                    "rprivate",
                    "rrelatime",
                    "rro",
                    "rrw",
                    "rshared",
                    "rslave",
                    "rstrictatime",
                    "rsuid",
                    "rsymfollow",
                    "runbindable",
                    "rw",
                    "shared",
                    "silent",
                    "slave",
                    "strictatime",
                    "suid",
                    "symfollow",
                    "sync",
                    "unbindable",
                    "idmap",
                    "ridmap",
                ),
            linux =
                LinuxFeatures(
                    namespaces =
                        listOf(
                            "cgroup",
                            "ipc",
                            "mount",
                            "network",
                            "pid",
                            "user",
                            "uts",
                            "time",
                        ),
                    capabilities =
                        listOf(
                            "CAP_CHOWN",
                            "CAP_DAC_OVERRIDE",
                            "CAP_DAC_READ_SEARCH",
                            "CAP_FOWNER",
                            "CAP_FSETID",
                            "CAP_KILL",
                            "CAP_SETGID",
                            "CAP_SETUID",
                            "CAP_SETPCAP",
                            "CAP_LINUX_IMMUTABLE",
                            "CAP_NET_BIND_SERVICE",
                            "CAP_NET_BROADCAST",
                            "CAP_NET_ADMIN",
                            "CAP_NET_RAW",
                            "CAP_IPC_LOCK",
                            "CAP_IPC_OWNER",
                            "CAP_SYS_MODULE",
                            "CAP_SYS_RAWIO",
                            "CAP_SYS_CHROOT",
                            "CAP_SYS_PTRACE",
                            "CAP_SYS_PACCT",
                            "CAP_SYS_ADMIN",
                            "CAP_SYS_BOOT",
                            "CAP_SYS_NICE",
                            "CAP_SYS_RESOURCE",
                            "CAP_SYS_TIME",
                            "CAP_SYS_TTY_CONFIG",
                            "CAP_MKNOD",
                            "CAP_LEASE",
                            "CAP_AUDIT_WRITE",
                            "CAP_AUDIT_CONTROL",
                            "CAP_SETFCAP",
                            "CAP_MAC_OVERRIDE",
                            "CAP_MAC_ADMIN",
                            "CAP_SYSLOG",
                            "CAP_WAKE_ALARM",
                            "CAP_BLOCK_SUSPEND",
                            "CAP_AUDIT_READ",
                            "CAP_PERFMON",
                            "CAP_BPF",
                            "CAP_CHECKPOINT_RESTORE",
                        ),
                    cgroup = CgroupFeatures(v2 = true),
                    seccomp =
                        SeccompFeatures(
                            enabled = true,
                            actions =
                                listOf(
                                    "SCMP_ACT_ALLOW",
                                    "SCMP_ACT_ERRNO",
                                    "SCMP_ACT_KILL",
                                    "SCMP_ACT_KILL_PROCESS",
                                    "SCMP_ACT_KILL_THREAD",
                                    "SCMP_ACT_LOG",
                                    "SCMP_ACT_NOTIFY",
                                    "SCMP_ACT_TRACE",
                                    "SCMP_ACT_TRAP",
                                ),
                            operators =
                                listOf(
                                    "SCMP_CMP_EQ",
                                    "SCMP_CMP_GE",
                                    "SCMP_CMP_GT",
                                    "SCMP_CMP_LE",
                                    "SCMP_CMP_LT",
                                    "SCMP_CMP_MASKED_EQ",
                                    "SCMP_CMP_NE",
                                ),
                            supportedFlags =
                                listOf(
                                    "SECCOMP_FILTER_FLAG_TSYNC",
                                    "SECCOMP_FILTER_FLAG_LOG",
                                    "SECCOMP_FILTER_FLAG_SPEC_ALLOW",
                                ),
                            knownFlags =
                                listOf(
                                    "SECCOMP_FILTER_FLAG_TSYNC",
                                    "SECCOMP_FILTER_FLAG_LOG",
                                    "SECCOMP_FILTER_FLAG_SPEC_ALLOW",
                                    "SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV",
                                ),
                        ),
                    apparmor = ApparmorFeatures(enabled = false),
                    selinux = SelinuxFeatures(enabled = false),
                    mountExtensions = MountExtensions(idmap = IdmapFeatures(enabled = true)),
                ),
            annotations =
                mapOf(
                    "io.github.seccomp.libseccomp.version" to getLibseccompVersion(),
                ),
        )

    val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }
    println(json.encodeToString(features))
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun getLibseccompVersion(): String {
    val major = libseccomp._seccomp_version_major()
    val minor = libseccomp._seccomp_version_minor()
    val micro = libseccomp._seccomp_version_micro()
    return "$major.$minor.$micro"
}

@Serializable
data class RuntimeFeatures(
    val ociVersionMin: String,
    val ociVersionMax: String,
    val hooks: List<String>,
    val mountOptions: List<String>,
    val linux: LinuxFeatures,
    val annotations: Map<String, String>,
)

@Serializable
data class LinuxFeatures(
    val namespaces: List<String>,
    val capabilities: List<String>,
    val cgroup: CgroupFeatures,
    val seccomp: SeccompFeatures,
    val apparmor: ApparmorFeatures,
    val selinux: SelinuxFeatures,
    val mountExtensions: MountExtensions,
)

@Serializable
data class CgroupFeatures(
    val v2: Boolean,
)

@Serializable
data class SeccompFeatures(
    val enabled: Boolean,
    val actions: List<String>,
    val operators: List<String>,
    val supportedFlags: List<String>,
    val knownFlags: List<String>,
)

@Serializable
data class ApparmorFeatures(
    val enabled: Boolean,
)

@Serializable
data class SelinuxFeatures(
    val enabled: Boolean,
)

@Serializable
data class MountExtensions(
    val idmap: IdmapFeatures,
)

@Serializable
data class IdmapFeatures(
    val enabled: Boolean,
)
