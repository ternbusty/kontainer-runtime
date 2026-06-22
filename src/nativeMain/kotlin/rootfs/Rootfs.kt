package rootfs

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import syscall.Syscall

// Mount flags (from linux/mount.h)
const val MS_RDONLY = 1
const val MS_NOSUID = 2
const val MS_NODEV = 4
const val MS_NOEXEC = 8
const val MS_REMOUNT = 32
const val MS_BIND = 4096
const val MS_REC = 16384
const val MS_SLAVE = 524288 // 1 << 19

// Umount flags
const val MNT_DETACH = 2

/**
 * Prepare rootfs with basic mounts
 */
@OptIn(ExperimentalForeignApi::class)
fun prepareRootfs(
    syscall: Syscall,
    rootfsPath: String,
) {
    Logger.debug("preparing rootfs at $rootfsPath")

    if (access(rootfsPath, F_OK) != 0) {
        throw Exception("Rootfs path does not exist: $rootfsPath")
    }

    // Change root mount propagation to slave so mount/umount events don't propagate
    // to/from the host. Required for pivot_root to work correctly.
    Logger.debug("changing root mount propagation to slave")
    if (syscall.mount(
            source = null,
            target = "/",
            fstype = null,
            flags = (MS_SLAVE or MS_REC).toULong(),
        ) != 0
    ) {
        val errNum = errno
        perror("mount / MS_SLAVE")
        Logger.warn("failed to change root mount propagation to slave (errno=$errNum)")
        // Continue anyway - this is best effort
    } else {
        Logger.debug("root mount propagation changed to slave")
    }

    // Bind mount rootfs to itself to make it a mount point (required for pivot_root)
    Logger.debug("bind mounting rootfs to itself")
    if (syscall.mount(
            source = rootfsPath,
            target = rootfsPath,
            fstype = null,
            flags = (MS_BIND or MS_REC).toULong(),
        ) != 0
    ) {
        val errNum = errno
        perror("bind mount rootfs")
        Logger.error("failed to bind mount rootfs to itself (errno=$errNum)")
        throw Exception("Failed to bind mount rootfs (errno=$errNum)")
    }
    Logger.debug("rootfs bind mounted successfully")

    // Mount /proc if it exists in rootfs
    val procPath = "$rootfsPath/proc"
    if (access(procPath, F_OK) == 0) {
        if (syscall.mount(
                source = "proc",
                target = procPath,
                fstype = "proc",
                flags = (MS_NOSUID or MS_NODEV or MS_NOEXEC).toULong(),
            ) != 0
        ) {
            val errNum = errno
            perror("mount /proc")
            Logger.error("failed to mount /proc (errno=$errNum)")
            throw Exception("Failed to mount /proc (errno=$errNum)")
        }
        Logger.debug("mounted /proc")
    }

    // Mount /dev if it exists in rootfs
    val devPath = "$rootfsPath/dev"
    if (access(devPath, F_OK) == 0) {
        if (syscall.mount(
                source = "tmpfs",
                target = devPath,
                fstype = "tmpfs",
                flags = (MS_NOSUID or MS_NOEXEC).toULong(),
                data = "mode=755",
            ) != 0
        ) {
            val errNum = errno
            perror("mount /dev")
            Logger.error("failed to mount /dev (errno=$errNum)")
            throw Exception("Failed to mount /dev (errno=$errNum)")
        }
        Logger.debug("mounted /dev")

        createDeviceNodes(syscall, devPath)
    }

    // Mount /sys if it exists in rootfs
    val sysPath = "$rootfsPath/sys"
    if (access(sysPath, F_OK) == 0) {
        if (syscall.mount(
                source = "sysfs",
                target = sysPath,
                fstype = "sysfs",
                flags = (MS_NOSUID or MS_NODEV or MS_NOEXEC or MS_RDONLY).toULong(),
            ) != 0
        ) {
            val errNum = errno
            perror("mount /sys")
            Logger.error("failed to mount /sys (errno=$errNum)")
            throw Exception("Failed to mount /sys (errno=$errNum)")
        }
        Logger.debug("mounted /sys")

        // Mount /sys/fs/cgroup if cgroup v2 is available.
        // We bind mount the container's specific cgroup path instead of the whole
        // /sys/fs/cgroup so the container only sees its own cgroup. See
        // runc/libcontainer/rootfs_linux.go:mountCgroupV2().
        val cgroupMountPath = "$rootfsPath/sys/fs/cgroup"
        if (access("/sys/fs/cgroup/cgroup.controllers", F_OK) == 0) {
            Logger.debug("setting up /sys/fs/cgroup (cgroup v2)")

            val containerCgroupPath = getContainerCgroupPath()
            if (containerCgroupPath != null) {
                val cgroupSourcePath = "/sys/fs/cgroup$containerCgroupPath"
                Logger.debug("container cgroup source path: $cgroupSourcePath")

                if (access(cgroupSourcePath, F_OK) != 0) {
                    Logger.warn("container cgroup path does not exist: $cgroupSourcePath")
                } else {
                    if (access(cgroupMountPath, F_OK) != 0) {
                        if (mkdir(cgroupMountPath, 0x1EDu) != 0) { // 0755
                            val errNum = errno
                            Logger.warn("failed to create /sys/fs/cgroup directory (errno=$errNum)")
                        }
                    }

                    // Bind mount the container's cgroup path to /sys/fs/cgroup so the
                    // container sees its own cgroup as the root of the hierarchy.
                    if (syscall.mount(
                            source = cgroupSourcePath,
                            target = cgroupMountPath,
                            fstype = null,
                            flags = (MS_BIND or MS_REC).toULong(),
                        ) != 0
                    ) {
                        val errNum = errno
                        perror("bind mount $cgroupSourcePath")
                        Logger.warn("failed to bind mount container cgroup (errno=$errNum)")
                    } else {
                        Logger.debug("bind mounted container cgroup to /sys/fs/cgroup")

                        // Remount as readonly
                        if (syscall.mount(
                                source = null,
                                target = cgroupMountPath,
                                fstype = null,
                                flags = (MS_BIND or MS_REMOUNT or MS_RDONLY or MS_NOSUID or MS_NODEV or MS_NOEXEC).toULong(),
                            ) != 0
                        ) {
                            val errNum = errno
                            perror("remount /sys/fs/cgroup readonly")
                            Logger.warn("failed to remount /sys/fs/cgroup readonly (errno=$errNum)")
                        } else {
                            Logger.debug("remounted /sys/fs/cgroup as readonly")
                        }
                    }
                }
            } else {
                Logger.warn("could not determine container cgroup path, skipping /sys/fs/cgroup mount")
            }
        } else {
            Logger.debug("cgroup v2 not available on host, skipping /sys/fs/cgroup mount")
        }
    }
}

/**
 * Create a single device node using bind mount.
 *
 * mknod(2) fails with EPERM in user namespaces, so instead we:
 * 1. Create an empty file
 * 2. Bind mount the host device onto that file
 */
@OptIn(ExperimentalForeignApi::class)
private fun createDeviceNode(
    syscall: Syscall,
    path: String,
    name: String,
) {
    // Mode 0666 for device files
    val fd = open(path, O_RDWR or O_CREAT, 0x1B6u)
    if (fd == -1) {
        val errNum = errno
        if (errNum == EEXIST) {
            Logger.debug("file for device $name already exists at $path")
        } else {
            perror("open $name")
            Logger.error("failed to create file for device $name at $path (errno=$errNum)")
            throw Exception("Failed to create file for device node: $name")
        }
    } else {
        close(fd)
        Logger.debug("created file for device $name at $path")
    }

    val hostDevPath = "/dev/$name"
    if (syscall.mount(
            source = hostDevPath,
            target = path,
            fstype = null,
            flags = MS_BIND.toULong(),
        ) != 0
    ) {
        val errNum = errno
        if (errNum == EBUSY) {
            Logger.debug("device $name already mounted at $path")
        } else {
            perror("bind mount $name")
            Logger.error("failed to bind mount $hostDevPath to $path (errno=$errNum)")
            throw Exception("Failed to bind mount device: $name")
        }
    } else {
        Logger.debug("bind mounted $hostDevPath to $path")
    }
}

/**
 * Create one of the default /dev symlinks (e.g. /dev/stdin -> /proc/self/fd/0).
 * Failure is non-fatal so a missing target on the host doesn't kill the container.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createDevSymlink(
    linkPath: String,
    target: String,
) {
    if (symlink(target, linkPath) != 0) {
        val errNum = errno
        if (errNum == EEXIST) {
            Logger.debug("symlink $linkPath -> $target already exists")
        } else {
            Logger.warn("failed to symlink $linkPath -> $target (errno=$errNum)")
        }
    } else {
        Logger.debug("created symlink $linkPath -> $target")
    }
}

/**
 * Create the default /dev contents required by the OCI runtime-spec:
 *   - device nodes (null/zero/full/random/urandom/tty)
 *   - /dev/pts (devpts) and /dev/mqueue (mqueue) submounts
 *   - /dev/shm (tmpfs)
 *   - symlinks (stdin/stdout/stderr/fd -> /proc/self/fd entries, ptmx -> pts/ptmx)
 */
@OptIn(ExperimentalForeignApi::class)
private fun createDeviceNodes(
    syscall: Syscall,
    devPath: String,
) {
    createDeviceNode(syscall, "$devPath/null", "null")
    createDeviceNode(syscall, "$devPath/zero", "zero")
    createDeviceNode(syscall, "$devPath/full", "full")
    createDeviceNode(syscall, "$devPath/random", "random")
    createDeviceNode(syscall, "$devPath/urandom", "urandom")
    createDeviceNode(syscall, "$devPath/tty", "tty")
    Logger.debug("finished creating device nodes in $devPath")

    // Mount /dev/pts (devpts) so /dev/ptmx -> pts/ptmx and pseudoterminal allocation work.
    val ptsPath = "$devPath/pts"
    if (access(ptsPath, F_OK) != 0) {
        if (mkdir(ptsPath, 0x1EDu) != 0) { // 0755
            Logger.warn("failed to create $ptsPath directory (errno=$errno)")
        }
    }
    if (syscall.mount(
            source = "devpts",
            target = ptsPath,
            fstype = "devpts",
            flags = (MS_NOSUID or MS_NOEXEC).toULong(),
            data = "newinstance,ptmxmode=0666,mode=0620",
        ) != 0
    ) {
        Logger.warn("failed to mount /dev/pts (errno=$errno)")
    } else {
        Logger.debug("mounted /dev/pts")
    }

    // Mount /dev/mqueue (mqueue) for POSIX message queues.
    val mqueuePath = "$devPath/mqueue"
    if (access(mqueuePath, F_OK) != 0) {
        if (mkdir(mqueuePath, 0x1EDu) != 0) {
            Logger.warn("failed to create $mqueuePath directory (errno=$errno)")
        }
    }
    if (syscall.mount(
            source = "mqueue",
            target = mqueuePath,
            fstype = "mqueue",
            flags = (MS_NOSUID or MS_NOEXEC or MS_NODEV).toULong(),
        ) != 0
    ) {
        Logger.warn("failed to mount /dev/mqueue (errno=$errno)")
    } else {
        Logger.debug("mounted /dev/mqueue")
    }

    // Mount /dev/shm for shared memory (POSIX shm_open, etc.)
    val shmPath = "$devPath/shm"
    if (access(shmPath, F_OK) != 0) {
        if (mkdir(shmPath, 0x1FFu) != 0) { // 0x1FF = 0777 octal
            val errNum = errno
            Logger.warn("failed to create /dev/shm directory (errno=$errNum)")
        }
    }
    if (syscall.mount(
            source = "shm",
            target = shmPath,
            fstype = "tmpfs",
            flags = (MS_NOSUID or MS_NOEXEC or MS_NODEV).toULong(),
            data = "mode=1777,size=65536k",
        ) != 0
    ) {
        val errNum = errno
        perror("mount /dev/shm")
        Logger.warn("failed to mount /dev/shm (errno=$errNum)")
    } else {
        Logger.debug("mounted /dev/shm")
    }

    // Default symlinks required by the OCI spec (and used by util-linux, GNU coreutils, ...).
    createDevSymlink("$devPath/stdin", "/proc/self/fd/0")
    createDevSymlink("$devPath/stdout", "/proc/self/fd/1")
    createDevSymlink("$devPath/stderr", "/proc/self/fd/2")
    createDevSymlink("$devPath/fd", "/proc/self/fd")
    createDevSymlink("$devPath/ptmx", "pts/ptmx")
    createDevSymlink("$devPath/core", "/proc/kcore")
}

/**
 * Perform pivot_root to change root filesystem.
 */
@OptIn(ExperimentalForeignApi::class)
fun pivotRoot(
    syscall: Syscall,
    newRoot: String,
) {
    Logger.debug("pivoting root to $newRoot")

    // Open newroot directory before pivot_root so we can fchdir back to it after.
    val newrootFd = open(newRoot, O_DIRECTORY or O_RDONLY, 0u)
    if (newrootFd < 0) {
        val errNum = errno
        perror("open newroot")
        Logger.error("failed to open $newRoot (errno=$errNum)")
        throw Exception("Failed to open $newRoot (errno=$errNum)")
    }

    // Use newroot for both arguments so the old root ends up at the same location.
    if (syscall.pivotRoot(newRoot, newRoot) == -1) {
        val errNum = errno
        perror("pivot_root")
        close(newrootFd)
        Logger.error("failed to pivot_root (errno=$errNum)")
        throw Exception("Failed to pivot_root (errno=$errNum)")
    }
    Logger.debug("pivot_root syscall completed")

    // Make the old root (now at /) a slave mount BEFORE fchdir so umount events
    // don't propagate to the host.
    if (syscall.mount(
            source = null,
            target = "/",
            fstype = null,
            flags = (MS_SLAVE or MS_REC).toULong(),
        ) != 0
    ) {
        val errNum = errno
        perror("make old root slave")
        Logger.warn("failed to make old root slave (errno=$errNum)")
    } else {
        Logger.debug("made old root slave")
    }

    // Lazy unmount of the old root, also BEFORE fchdir.
    if (syscall.umount2("/", MNT_DETACH) != 0) {
        val errNum = errno
        perror("umount2 old root")
        Logger.warn("failed to unmount old root (errno=$errNum)")
    } else {
        Logger.debug("unmounted old root")
    }

    if (fchdir(newrootFd) != 0) {
        val errNum = errno
        perror("fchdir")
        close(newrootFd)
        Logger.error("failed to fchdir to newroot (errno=$errNum)")
        throw Exception("Failed to fchdir to newroot (errno=$errNum)")
    }
    Logger.debug("changed to new root")

    close(newrootFd)

    if (syscall.chdir("/") != 0) {
        val errNum = errno
        perror("chdir /")
        Logger.error("failed to chdir to / (errno=$errNum)")
        throw Exception("Failed to chdir to / (errno=$errNum)")
    }

    Logger.debug("successfully pivoted root")
}

/**
 * Set root filesystem as readonly.
 * Called after pivot_root to make the container's root readonly.
 * See runc/libcontainer/rootfs_linux.go:setReadonly().
 */
@OptIn(ExperimentalForeignApi::class)
fun setRootfsReadonly(syscall: Syscall) {
    Logger.debug("setting rootfs as readonly")

    var flags = (MS_BIND or MS_REMOUNT or MS_RDONLY).toULong()

    if (syscall.mount(
            source = null,
            target = "/",
            fstype = null,
            flags = flags,
        ) == 0
    ) {
        Logger.debug("rootfs set as readonly")
        return
    }

    // Some filesystems require their existing flags to be preserved during remount,
    // so retry with statfs() flags merged in.
    memScoped {
        val st = alloc<platform.linux.statfs>()
        if (platform.linux.statfs("/", st.ptr) != 0) {
            val errNum = errno
            perror("statfs /")
            Logger.error("failed to statfs / (errno=$errNum)")
            throw Exception("Failed to statfs / (errno=$errNum)")
        }

        flags = flags or st.f_flags.toULong()

        if (syscall.mount(
                source = null,
                target = "/",
                fstype = null,
                flags = flags,
            ) != 0
        ) {
            val errNum = errno
            perror("remount / readonly")
            Logger.error("failed to remount / as readonly (errno=$errNum)")
            throw Exception("Failed to remount / as readonly (errno=$errNum)")
        }

        Logger.debug("rootfs set as readonly (with existing flags)")
    }
}

/**
 * Mask a path inside the container by bind-mounting /dev/null over a regular file
 * or an empty tmpfs over a directory. Used to implement spec.linux.maskedPaths.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyMaskedPaths(
    syscall: Syscall,
    paths: List<String>?,
) {
    if (paths.isNullOrEmpty()) return
    for (path in paths) {
        if (access(path, F_OK) != 0) {
            Logger.debug("masked path $path does not exist, skipping")
            continue
        }
        memScoped {
            val st = alloc<stat>()
            if (stat(path, st.ptr) != 0) {
                Logger.warn("failed to stat masked path $path (errno=$errno)")
                return@memScoped
            }
            val isDir = (st.st_mode.toInt() and S_IFMT) == S_IFDIR
            val rc =
                if (isDir) {
                    syscall.mount(
                        source = "tmpfs",
                        target = path,
                        fstype = "tmpfs",
                        flags = (MS_RDONLY or MS_NOSUID or MS_NODEV).toULong(),
                        data = "size=0k",
                    )
                } else {
                    syscall.mount(
                        source = "/dev/null",
                        target = path,
                        fstype = null,
                        flags = MS_BIND.toULong(),
                    )
                }
            if (rc != 0) {
                Logger.warn("failed to mask path $path (errno=$errno)")
            } else {
                Logger.debug("masked path $path (dir=$isDir)")
            }
        }
    }
}

/**
 * Remount a list of paths as read-only by bind-remounting them with MS_RDONLY.
 * Used to implement spec.linux.readonlyPaths.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyReadonlyPaths(
    syscall: Syscall,
    paths: List<String>?,
) {
    if (paths.isNullOrEmpty()) return
    for (path in paths) {
        if (access(path, F_OK) != 0) {
            Logger.debug("readonly path $path does not exist, skipping")
            continue
        }
        // Bind the path to itself first so MS_REMOUNT below operates on a mount we own,
        // not on whatever filesystem the path happens to live in.
        if (syscall.mount(
                source = path,
                target = path,
                fstype = null,
                flags = (MS_BIND or MS_REC).toULong(),
            ) != 0
        ) {
            Logger.warn("failed to bind $path for readonly remount (errno=$errno)")
            continue
        }
        if (syscall.mount(
                source = path,
                target = path,
                fstype = null,
                flags = (MS_BIND or MS_REC or MS_REMOUNT or MS_RDONLY).toULong(),
            ) != 0
        ) {
            Logger.warn("failed to remount $path as readonly (errno=$errno)")
        } else {
            Logger.debug("remounted $path as readonly")
        }
    }
}

/**
 * Write entries from spec.linux.sysctl to /proc/sys/<key-with-slashes>.
 * Requires /proc to be mounted (done in prepareRootfs).
 */
@OptIn(ExperimentalForeignApi::class)
fun applySysctls(sysctls: Map<String, String>?) {
    if (sysctls.isNullOrEmpty()) return
    for ((key, value) in sysctls) {
        val sysctlPath = "/proc/sys/" + key.replace('.', '/')
        val fd = fopen(sysctlPath, "w")
        if (fd == null) {
            Logger.warn("failed to open $sysctlPath for sysctl $key (errno=$errno)")
            continue
        }
        try {
            if (fputs(value, fd) < 0) {
                Logger.warn("failed to write sysctl $key=$value to $sysctlPath (errno=$errno)")
            } else {
                Logger.debug("set sysctl $key=$value")
            }
        } finally {
            fclose(fd)
        }
    }
}

/**
 * Get container's cgroup v2 path from /proc/self/cgroup.
 * Returns the cgroup path (e.g., "/default/test-container") or null if not found.
 * See runc/libcontainer/cgroups/utils.go.
 */
@OptIn(ExperimentalForeignApi::class)
fun getContainerCgroupPath(): String? {
    val fd = fopen("/proc/self/cgroup", "r")
    if (fd == null) {
        Logger.warn("failed to open /proc/self/cgroup")
        return null
    }

    try {
        memScoped {
            val buffer = allocArray<ByteVar>(512)
            while (fgets(buffer, 512, fd) != null) {
                val line = buffer.toKString().trim()

                // cgroup v2 format: "0::/path/to/cgroup"
                if (line.startsWith("0::")) {
                    val cgroupPath = line.substring(3)
                    if (cgroupPath.isNotEmpty()) {
                        Logger.debug("found container cgroup path: $cgroupPath")
                        return cgroupPath
                    }
                }
            }
        }
    } finally {
        fclose(fd)
    }

    Logger.warn("cgroup v2 path not found in /proc/self/cgroup")
    return null
}
