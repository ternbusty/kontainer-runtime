package command

import config.BuildConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import platform.posix.getegid
import platform.posix.geteuid
import utils.RealFileSystem

/**
 * Default OCI runtime spec compatible with runc's `runc spec` output.
 *
 * Generates config.json in the specified bundle directory.
 * This is the minimum viable spec that runc's integration test
 * harness (setup_busybox / runc_spec) expects to be able to produce.
 */
fun spec(
    bundlePath: String,
    rootless: Boolean = false,
) {
    val configPath = "$bundlePath/config.json"
    val fs = RealFileSystem()

    // The default spec mirrors what `runc spec` generates.
    val defaultSpec =
        spec.Spec(
            ociVersion = BuildConfig.OCI_SPEC_VERSION,
            root = spec.Root(path = "rootfs", readonly = true),
            process =
                spec.Process(
                    terminal = true,
                    args = listOf("sh"),
                    env =
                        listOf(
                            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                            "TERM=xterm",
                        ),
                    cwd = "/",
                    noNewPrivileges = true,
                    user = spec.User(uid = 0u, gid = 0u),
                    capabilities =
                        spec.LinuxCapabilities(
                            bounding = DEFAULT_CAPS,
                            effective = DEFAULT_CAPS,
                            permitted = DEFAULT_CAPS,
                        ),
                    rlimits =
                        listOf(
                            spec.POSIXRlimit(type = spec.RlimitType.NOFILE, hard = 1024u, soft = 1024u),
                        ),
                ),
            hostname = "kontainer",
            mounts =
                listOf(
                    spec.Mount(
                        destination = "/proc",
                        type = "proc",
                        source = "proc",
                        options = emptyList(),
                    ),
                    spec.Mount(
                        destination = "/dev",
                        type = "tmpfs",
                        source = "tmpfs",
                        options = listOf("nosuid", "strictatime", "mode=755", "size=65536k"),
                    ),
                    spec.Mount(
                        destination = "/dev/pts",
                        type = "devpts",
                        source = "devpts",
                        options =
                            listOf(
                                "nosuid",
                                "noexec",
                                "newinstance",
                                "ptmxmode=0666",
                                "mode=0620",
                                "gid=5",
                            ),
                    ),
                    spec.Mount(
                        destination = "/dev/shm",
                        type = "tmpfs",
                        source = "shm",
                        options = listOf("nosuid", "noexec", "nodev", "mode=1777", "size=65536k"),
                    ),
                    spec.Mount(
                        destination = "/dev/mqueue",
                        type = "mqueue",
                        source = "mqueue",
                        options = listOf("nosuid", "noexec", "nodev"),
                    ),
                    spec.Mount(
                        destination = "/sys",
                        type = "sysfs",
                        source = "sysfs",
                        options = listOf("nosuid", "noexec", "nodev", "ro"),
                    ),
                    spec.Mount(
                        destination = "/sys/fs/cgroup",
                        type = "cgroup",
                        source = "cgroup",
                        options = listOf("nosuid", "noexec", "nodev", "relatime", "ro"),
                    ),
                ),
            linux =
                spec.Linux(
                    resources =
                        spec.LinuxResources(
                            devices =
                                listOf(
                                    spec.LinuxDeviceCgroup(
                                        allow = false,
                                        access = "rwm",
                                    ),
                                ),
                        ),
                    namespaces =
                        listOf(
                            spec.Namespace(type = spec.NamespaceType.PID),
                            spec.Namespace(type = spec.NamespaceType.NETWORK),
                            spec.Namespace(type = spec.NamespaceType.IPC),
                            spec.Namespace(type = spec.NamespaceType.UTS),
                            spec.Namespace(type = spec.NamespaceType.MOUNT),
                            spec.Namespace(type = spec.NamespaceType.CGROUP),
                        ),
                    maskedPaths =
                        listOf(
                            "/proc/acpi",
                            "/proc/asound",
                            "/proc/kcore",
                            "/proc/keys",
                            "/proc/latency_stats",
                            "/proc/timer_list",
                            "/proc/timer_stats",
                            "/proc/sched_debug",
                            "/sys/firmware",
                            "/proc/scsi",
                        ),
                    readonlyPaths =
                        listOf(
                            "/proc/bus",
                            "/proc/fs",
                            "/proc/irq",
                            "/proc/sys",
                            "/proc/sysrq-trigger",
                        ),
                ),
        )

    // Use explicitNulls = false so the OCI JSON schema validator doesn't
    // reject null-typed fields (e.g. hooks, mount options).  The schema
    // expects absent, not null.
    val specJson =
        Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }
    val outputSpec = if (rootless) toRootless(defaultSpec) else defaultSpec
    fs.writeTextFile(configPath, specJson.encodeToString(serializer(), outputSpec))
}

/**
 * Turn [s] into a spec an unprivileged user can run, like runc's
 * `runc spec --rootless` (libcontainer/specconv ToRootless): add a user
 * namespace that maps only the caller's uid/gid to root, drop the network
 * namespace (it needs a network setup the user cannot do), bind-mount /sys
 * instead of mounting sysfs, drop uid=/gid= mount options that would refer to
 * unmapped ids, and remove cgroup resources.
 */
@OptIn(ExperimentalForeignApi::class)
private fun toRootless(s: spec.Spec): spec.Spec {
    val linux = s.linux ?: spec.Linux()
    val namespaces =
        linux.namespaces.orEmpty().filter {
            it.type != spec.NamespaceType.NETWORK && it.type != spec.NamespaceType.USER
        } + spec.Namespace(type = spec.NamespaceType.USER)
    val mounts =
        s.mounts?.map { m ->
            if (m.destination.trimEnd('/') == "/sys") {
                spec.Mount(
                    destination = "/sys",
                    type = "none",
                    source = "/sys",
                    options = listOf("rbind", "nosuid", "noexec", "nodev", "ro"),
                )
            } else {
                m.copy(options = m.options?.filterNot { it.startsWith("uid=") || it.startsWith("gid=") })
            }
        }
    return s.copy(
        mounts = mounts,
        linux =
            linux.copy(
                namespaces = namespaces,
                uidMappings = listOf(spec.LinuxIdMapping(containerID = 0u, hostID = geteuid(), size = 1u)),
                gidMappings = listOf(spec.LinuxIdMapping(containerID = 0u, hostID = getegid(), size = 1u)),
                resources = null,
            ),
    )
}

private val DEFAULT_CAPS =
    listOf(
        "CAP_AUDIT_WRITE",
        "CAP_KILL",
        "CAP_NET_BIND_SERVICE",
    )
