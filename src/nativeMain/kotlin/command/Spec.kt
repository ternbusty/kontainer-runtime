package command

import utils.JsonCodec
import utils.RealFileSystem

/**
 * Default OCI runtime spec compatible with runc's `runc spec` output.
 *
 * Generates config.json in the specified bundle directory.
 * This is the minimum viable spec that runc's integration test
 * harness (setup_busybox / runc_spec) expects to be able to produce.
 */
fun spec(bundlePath: String) {
    val configPath = "$bundlePath/config.json"
    val fs = RealFileSystem()

    // The default spec mirrors what `runc spec` generates.
    val defaultSpec =
        spec.Spec(
            ociVersion = "1.0.0",
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
                            ambient = DEFAULT_CAPS,
                        ),
                    rlimits =
                        listOf(
                            spec.POSIXRlimit(type = "RLIMIT_NOFILE", hard = 1024u, soft = 1024u),
                        ),
                ),
            hostname = "kontainer",
            mounts =
                listOf(
                    spec.Mount(
                        destination = "/proc",
                        type = "proc",
                        source = "proc",
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
                    namespaces =
                        listOf(
                            spec.Namespace(type = "pid"),
                            spec.Namespace(type = "network"),
                            spec.Namespace(type = "ipc"),
                            spec.Namespace(type = "uts"),
                            spec.Namespace(type = "mount"),
                            spec.Namespace(type = "cgroup"),
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

    JsonCodec.writeToFile(fs, configPath, defaultSpec, prettyPrint = true)
}

private val DEFAULT_CAPS =
    listOf(
        "CAP_AUDIT_WRITE",
        "CAP_KILL",
        "CAP_NET_BIND_SERVICE",
    )
