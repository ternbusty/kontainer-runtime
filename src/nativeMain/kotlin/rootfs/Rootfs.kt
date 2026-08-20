package rootfs

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import spec.LinuxIdMapping
import syscall.Syscall

// Mount flags (from linux/mount.h)
const val MS_RDONLY = 1
const val MS_NOSUID = 2
const val MS_NODEV = 4
const val MS_NOEXEC = 8
const val MS_NOATIME = 1024
const val MS_NODIRATIME = 2048
const val MS_REMOUNT = 32
const val MS_BIND = 4096
const val MS_REC = 16384
const val MS_SLAVE = 524288 // 1 << 19
const val MS_RELATIME = 2097152 // 1 << 21
const val MS_STRICTATIME = 16777216 // 1 << 24
const val MS_PRIVATE = 262144 // 1 << 18
const val MS_SHARED = 1048576 // 1 << 20
const val MS_UNBINDABLE = 131072 // 1 << 17
const val MS_SYNCHRONOUS = 16
const val MS_MANDLOCK = 64
const val MS_NOSYMFOLLOW = 256 // 1 << 8

// Umount flags
const val MNT_DETACH = 2

/**
 * Prepare rootfs with basic mounts
 */
@OptIn(ExperimentalForeignApi::class)
fun prepareRootfs(
    syscall: Syscall,
    rootfsPath: String,
    rootfsPropagation: String? = null,
    specMounts: List<spec.Mount>? = null,
) {
    Logger.debug("preparing rootfs at $rootfsPath")

    if (access(rootfsPath, F_OK) != 0) {
        throw Exception("Rootfs path does not exist: $rootfsPath")
    }

    // Change root subtree propagation to slave|rec so mount/umount events from
    // inside the container don't leak to the host. This must happen FIRST and
    // is always slave|rec — applying rootfsPropagation to "/" before bind-mounting
    // rootfsPath fails with EINVAL for shared/unbindable. The spec'd propagation
    // is applied to the rootfsPath mount itself a few lines down.
    Logger.debug("changing root mount propagation to rslave (pre-bind)")
    if (syscall.mount(
            source = null,
            target = "/",
            fstype = null,
            flags = (MS_SLAVE or MS_REC).toULong(),
        ) != 0
    ) {
        val errNum = errno
        perror("mount / MS_SLAVE")
        Logger.warn("failed to change root mount propagation to rslave (errno=$errNum)")
        // Continue anyway - this is best effort
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

    // Mount /proc — create the mount point if the rootfs doesn't include it
    // (e.g. the docker-library/busybox tar has no /proc directory).
    val procPath = "$rootfsPath/proc"
    if (access(procPath, F_OK) != 0) {
        mkdir(procPath, 0x1EDu) // 0755
    }
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

    // Mount /dev — create the mount point if missing (e.g. busybox rootfs).
    val devPath = "$rootfsPath/dev"
    if (access(devPath, F_OK) != 0) {
        mkdir(devPath, 0x1EDu) // 0755
    }
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

    // Mount /sys — create the mount point if missing (e.g. busybox rootfs).
    val sysPath = "$rootfsPath/sys"
    if (access(sysPath, F_OK) != 0) {
        mkdir(sysPath, 0x1EDu) // 0755
    }
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
    // Like runc (mountCgroupV2), first try mounting a fresh cgroup2 filesystem.
    // With CLONE_NEWCGROUP (unshared by Stage-2 after cgroup assignment), the
    // cgroup2 mount automatically shows only the container's cgroup subtree,
    // giving the container full subcgroup management (mkdir, subtree_control).
    // If that fails (EPERM in user namespace without cgroupns), fall back to a
    // bind mount of the container's specific cgroup path.
    val cgroupMountPath = "$rootfsPath/sys/fs/cgroup"
    if (access("/sys/fs/cgroup/cgroup.controllers", F_OK) == 0) {
        Logger.debug("setting up /sys/fs/cgroup (cgroup v2)")

        if (access(cgroupMountPath, F_OK) != 0) {
            if (mkdir(cgroupMountPath, 0x1EDu) != 0) { // 0755
                val errNum = errno
                Logger.warn("failed to create /sys/fs/cgroup directory (errno=$errNum)")
            }
        }

        // Determine mount flags and data from the spec's cgroup mount options.
        // Options like nosuid/nodev/noexec/ro are mount flags; anything else
        // becomes the data string passed to mount(2) (e.g. nsdelegate).
        // runc passes m.Data verbatim — we mirror that behavior.
        val cgroupMount = specMounts?.find { it.destination == "/sys/fs/cgroup" }
        val flagNames =
            setOf("nosuid", "nodev", "noexec", "relatime", "ro", "rw", "sync", "async", "dirsync", "noatime", "nodiratime", "strictatime")
        val cgroupReadonly = cgroupMount?.options?.contains("ro") ?: true
        val cgroupFlags =
            (MS_NOSUID or MS_NODEV or MS_NOEXEC).toULong() or
                (if (cgroupReadonly) MS_RDONLY.toULong() else 0uL)
        val cgroupData = cgroupMount?.options?.filter { it !in flagNames }?.joinToString(",") ?: ""

        // Try direct cgroup2 mount first (runc's primary path).
        val directRc =
            syscall.mount(
                source = "cgroup2",
                target = cgroupMountPath,
                fstype = "cgroup2",
                flags = cgroupFlags,
                data = cgroupData,
            )
        if (directRc == 0) {
            Logger.debug("mounted cgroup2 filesystem at /sys/fs/cgroup (direct)")
        } else {
            val directErrno = errno
            Logger.debug("direct cgroup2 mount failed (errno=$directErrno), falling back to bind mount")

            // Fall back to bind mount of the container's cgroup path.
            val containerCgroupPath = getContainerCgroupPath()
            if (containerCgroupPath != null) {
                val cgroupSourcePath = "/sys/fs/cgroup$containerCgroupPath"
                Logger.debug("container cgroup source path: $cgroupSourcePath")

                if (access(cgroupSourcePath, F_OK) != 0) {
                    Logger.warn("container cgroup path does not exist: $cgroupSourcePath")
                } else {
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

                        // Apply readonly if the spec requires it.
                        if (cgroupReadonly) {
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
                        } else {
                            Logger.debug("/sys/fs/cgroup left writable per spec mount options")
                        }
                    }
                }
            } else {
                Logger.warn("could not determine container cgroup path, skipping /sys/fs/cgroup mount")
            }
        }
    } else {
        Logger.debug("cgroup v2 not available on host, skipping /sys/fs/cgroup mount")
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

    // Mount /dev/shm for shared memory (POSIX shm_open, etc.).
    // Order matters: OCI default spec lists /dev/pts, /dev/shm, /dev/mqueue, /sys —
    // some runtime-tools assertions check mounts appear "in order" against the spec.
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
 * Create the device nodes specified by spec.linux.devices[] inside the container's
 * /dev. Uses mknod(2) so the device has the requested major/minor. Falls back to
 * a bind mount from /dev/null on EPERM (e.g. running in a user namespace without
 * CAP_MKNOD).
 *
 * Called after pivot_root, while still root, so paths are relative to "/".
 */
@OptIn(ExperimentalForeignApi::class)
fun applyLinuxDevices(
    syscall: Syscall,
    devices: List<spec.LinuxDevice>?,
) {
    if (devices.isNullOrEmpty()) return
    for (d in devices) {
        val mode =
            when (d.type) {
                "c", "u" -> S_IFCHR
                "b" -> S_IFBLK
                "p" -> S_IFIFO
                else -> {
                    Logger.warn("unsupported device type ${d.type} for ${d.path}, skipping")
                    continue
                }
            }
        val perms = (d.fileMode ?: 0x1B6u).toInt() // 0666
        val major = d.major ?: 0L
        val minor = d.minor ?: 0L
        // Linux gnu_dev_makedev encoding handles the common (major<256, minor<256)
        // path with the simple (major<<8|minor) layout; full encoding is below.
        val devNum =
            (
                ((major and 0xfff) shl 8) or (minor and 0xff) or
                    ((minor and 0xfff00) shl 12) or ((major and 0xfffff000) shl 32)
            ).toULong()

        // Ensure parent directory exists. For /dev/test1, parent is /dev (already mounted).
        val parent = d.path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) mkdirP(parent)

        // Remove any pre-existing file at the destination so mknod doesn't EEXIST.
        unlink(d.path)

        // Temporarily clear umask so mknod creates the file with the exact mode
        // from the spec instead of letting the inherited umask trim bits off.
        val savedUmask = umask(0u)
        val rc = mknod(d.path, (mode or perms).toUInt(), devNum)
        umask(savedUmask)
        if (rc != 0) {
            Logger.warn("mknod ${d.path} (major=$major, minor=$minor) failed (errno=$errno); falling back to /dev/null bind")
            // Fall back: empty file + bind mount from /dev/null
            val fd = open(d.path, O_RDWR or O_CREAT, 0x1B6u)
            if (fd >= 0) close(fd)
            if (syscall.mount(
                    source = "/dev/null",
                    target = d.path,
                    fstype = null,
                    flags = MS_BIND.toULong(),
                ) != 0
            ) {
                Logger.warn("bind /dev/null over ${d.path} failed (errno=$errno)")
                continue
            }
        } else {
            // mknod respects the mode-bits-in-the-low-bits of mode arg, but file system
            // mode-on-disk is mknod_mode & ~umask. We already zeroed umask, but chmod
            // afterwards covers the kernel weirdness around setuid/setgid bits.
            chmod(d.path, perms.toUInt())
        }
        if (d.uid != null || d.gid != null) {
            if (chown(d.path, d.uid ?: 0u, d.gid ?: 0u) != 0) {
                Logger.warn("chown ${d.path} (uid=${d.uid}, gid=${d.gid}) failed (errno=$errno)")
            }
        }
        Logger.debug("created device ${d.path} (type=${d.type}, major=$major, minor=$minor, perms=${perms.toString(8)})")
    }
}

/**
 * Apply spec.linux.rootfsPropagation to "/" AFTER pivot_root. The kernel rejects
 * pivot_root when the new root is MS_SHARED, so propagation must be set last.
 */
@OptIn(ExperimentalForeignApi::class)
fun applyRootfsPropagation(
    syscall: Syscall,
    rootfsPropagation: String?,
) {
    val (label, flags) =
        when (rootfsPropagation) {
            "shared" -> "shared" to MS_SHARED.toULong()
            "rshared" -> "rshared" to (MS_SHARED or MS_REC).toULong()
            "private" -> "private" to MS_PRIVATE.toULong()
            "rprivate" -> "rprivate" to (MS_PRIVATE or MS_REC).toULong()
            "slave" -> "slave" to MS_SLAVE.toULong()
            "unbindable" -> "unbindable" to MS_UNBINDABLE.toULong()
            "runbindable" -> "runbindable" to (MS_UNBINDABLE or MS_REC).toULong()
            null, "rslave" -> return // already rslave from prepareRootfs
            else -> {
                Logger.warn("unknown rootfsPropagation $rootfsPropagation, leaving as rslave")
                return
            }
        }
    Logger.debug("setting rootfs propagation to $label (post-pivot)")
    if (syscall.mount(
            source = null,
            target = "/",
            fstype = null,
            flags = flags,
        ) != 0
    ) {
        Logger.warn("failed to set rootfs propagation $label (errno=$errno)")
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
 * Translate an OCI mount option string into a (flag, dataField, propagationFlag) triple.
 * - flag: bitwise OR'd into the mount() flags
 * - data: kept for fs-specific options like "size=64k" passed via mount() data
 * - propagationFlag: applied with a SECOND mount() call (the kernel only honours
 *   propagation flags when used alone)
 */
private data class ParsedMountOptions(
    val flags: ULong,
    val propagation: ULong,
    val data: String?,
    val clearedFlags: ULong = 0uL,
    val idmap: Boolean = false,
    val ridmap: Boolean = false,
)

private fun parseMountOptions(options: List<String>?): ParsedMountOptions {
    if (options.isNullOrEmpty()) return ParsedMountOptions(0uL, 0uL, null, 0uL)
    var flags = 0uL
    var propagation = 0uL
    var clearedFlags = 0uL
    var hasIdmap = false
    var hasRidmap = false
    val dataParts = mutableListOf<String>()
    for (opt in options) {
        when (opt) {
            "ro" -> flags = flags or MS_RDONLY.toULong()
            "rw" -> {
                flags = flags and MS_RDONLY.toULong().inv()
                clearedFlags = clearedFlags or MS_RDONLY.toULong()
            }
            "nosuid" -> flags = flags or MS_NOSUID.toULong()
            "suid" -> {
                flags = flags and MS_NOSUID.toULong().inv()
                clearedFlags = clearedFlags or MS_NOSUID.toULong()
            }
            "nodev" -> flags = flags or MS_NODEV.toULong()
            "dev" -> {
                flags = flags and MS_NODEV.toULong().inv()
                clearedFlags = clearedFlags or MS_NODEV.toULong()
            }
            "noexec" -> flags = flags or MS_NOEXEC.toULong()
            "exec" -> {
                flags = flags and MS_NOEXEC.toULong().inv()
                clearedFlags = clearedFlags or MS_NOEXEC.toULong()
            }
            "noatime" -> flags = flags or MS_NOATIME.toULong()
            "atime" -> {
                flags = flags and MS_NOATIME.toULong().inv()
                clearedFlags = clearedFlags or MS_NOATIME.toULong()
            }
            "nodiratime" -> flags = flags or MS_NODIRATIME.toULong()
            "diratime" -> {
                flags = flags and MS_NODIRATIME.toULong().inv()
                clearedFlags = clearedFlags or MS_NODIRATIME.toULong()
            }
            "relatime" -> flags = flags or MS_RELATIME.toULong()
            "norelatime" -> {
                flags = flags and MS_RELATIME.toULong().inv()
                clearedFlags = clearedFlags or MS_RELATIME.toULong()
            }
            "strictatime" -> flags = flags or MS_STRICTATIME.toULong()
            "nostrictatime" -> {
                flags = flags and MS_STRICTATIME.toULong().inv()
                clearedFlags = clearedFlags or MS_STRICTATIME.toULong()
            }
            "nosymfollow" -> flags = flags or MS_NOSYMFOLLOW.toULong()
            "symfollow" -> {
                flags = flags and MS_NOSYMFOLLOW.toULong().inv()
                clearedFlags = clearedFlags or MS_NOSYMFOLLOW.toULong()
            }
            "remount" -> flags = flags or MS_REMOUNT.toULong()
            "bind" -> flags = flags or MS_BIND.toULong()
            "rbind" -> flags = flags or MS_BIND.toULong() or MS_REC.toULong()
            "shared" -> propagation = propagation or MS_SHARED.toULong()
            "rshared" -> propagation = propagation or MS_SHARED.toULong() or MS_REC.toULong()
            "slave" -> propagation = propagation or MS_SLAVE.toULong()
            "rslave" -> propagation = propagation or MS_SLAVE.toULong() or MS_REC.toULong()
            "private" -> propagation = propagation or MS_PRIVATE.toULong()
            "rprivate" -> propagation = propagation or MS_PRIVATE.toULong() or MS_REC.toULong()
            "unbindable" -> propagation = propagation or MS_UNBINDABLE.toULong()
            "runbindable" -> propagation = propagation or MS_UNBINDABLE.toULong() or MS_REC.toULong()
            "defaults" -> { /* no-op */ }
            "idmap" -> hasIdmap = true
            "ridmap" -> hasRidmap = true
            else -> dataParts.add(opt)
        }
    }
    val data = if (dataParts.isEmpty()) null else dataParts.joinToString(",")
    return ParsedMountOptions(flags, propagation, data, clearedFlags, idmap = hasIdmap, ridmap = hasRidmap)
}

// statfs() f_flags constants (from linux/statfs.h).
// These differ from MS_* for some flags (e.g., ST_RELATIME vs MS_RELATIME).
private const val ST_RDONLY = 1uL
private const val ST_NOSUID = 2uL
private const val ST_NODEV = 4uL
private const val ST_NOEXEC = 8uL
private const val ST_SYNCHRONOUS = 16uL
private const val ST_MANDLOCK = 64uL
private const val ST_NOATIME = 1024uL
private const val ST_NODIRATIME = 2048uL
private const val ST_RELATIME = 4096uL
private const val ST_NOSYMFOLLOW = 8192uL

/**
 * Convert statfs(2) f_flags (ST_* bits) to mount(2) flags (MS_* bits).
 * Most ST_* values coincide with their MS_* counterparts, but ST_RELATIME
 * and ST_NOSYMFOLLOW differ, so a manual per-bit translation is required.
 */
private fun statfsToMountFlags(stFlags: ULong): ULong {
    var mntFlags = 0uL
    if (stFlags and ST_RDONLY != 0uL) mntFlags = mntFlags or MS_RDONLY.toULong()
    if (stFlags and ST_NOSUID != 0uL) mntFlags = mntFlags or MS_NOSUID.toULong()
    if (stFlags and ST_NODEV != 0uL) mntFlags = mntFlags or MS_NODEV.toULong()
    if (stFlags and ST_NOEXEC != 0uL) mntFlags = mntFlags or MS_NOEXEC.toULong()
    if (stFlags and ST_SYNCHRONOUS != 0uL) mntFlags = mntFlags or MS_SYNCHRONOUS.toULong()
    if (stFlags and ST_MANDLOCK != 0uL) mntFlags = mntFlags or MS_MANDLOCK.toULong()
    if (stFlags and ST_NOATIME != 0uL) mntFlags = mntFlags or MS_NOATIME.toULong()
    if (stFlags and ST_NODIRATIME != 0uL) mntFlags = mntFlags or MS_NODIRATIME.toULong()
    if (stFlags and ST_RELATIME != 0uL) mntFlags = mntFlags or MS_RELATIME.toULong()
    if (stFlags and ST_NOSYMFOLLOW != 0uL) mntFlags = mntFlags or MS_NOSYMFOLLOW.toULong()
    return mntFlags
}

/**
 * Best-effort mkdir -p for the bind/mount target. Bind mounts need the target to
 * exist as a file or directory matching the source kind; tmpfs/proc/sysfs need
 * a directory. We always create directories — the bind-mount-on-file case is
 * left to the caller (only used by /dev/null masking).
 */
@OptIn(ExperimentalForeignApi::class)
private fun mkdirP(path: String) {
    if (path.isEmpty() || path == "/") return
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isNotEmpty()) mkdirP(parent)
    if (access(path, F_OK) == 0) return
    if (mkdir(path, 0x1EDu) != 0 && errno != EEXIST) {
        Logger.debug("mkdir $path failed (errno=$errno)")
    }
}

/**
 * Process the spec.mounts[] array. Skips mount destinations that are already
 * established by prepareRootfs (/proc, /dev, /sys etc.) so we don't double-mount,
 * but processes everything else (user bind mounts, /dev/pts, /dev/shm, /dev/mqueue,
 * /sys/fs/cgroup with their spec-defined options).
 *
 * Called after pivot_root, while the process is still root with CAP_SYS_ADMIN.
 */
@OptIn(ExperimentalForeignApi::class)
fun applySpecMounts(
    syscall: Syscall,
    mounts: List<spec.Mount>?,
    rootfsPath: String,
    specLinux: spec.Linux? = null,
) {
    if (mounts.isNullOrEmpty()) return
    // prepareRootfs() already establishes these defaults; skip to avoid double-mount.
    val handledByPrepareRootfs = setOf("/proc", "/dev", "/sys", "/sys/fs/cgroup")
    for (m in mounts) {
        if (m.destination in handledByPrepareRootfs) {
            Logger.debug("skipping spec.mount ${m.destination} (already handled by prepareRootfs)")
            continue
        }
        val parsed = parseMountOptions(m.options)
        val fsType = m.type
        // Choose source: for bind mounts the source must exist on the host; for fs
        // mounts the value is mainly a label (e.g. "shm").
        val source = m.source ?: fsType
        // The target is relative to the future container root; the actual filesystem
        // path before pivot_root is rootfsPath + destination.
        val target = rootfsPath + m.destination

        // For tmpfs mounts: capture the existing directory's permissions before
        // mounting so we can restore them when no explicit mode= option was given.
        // This matches runc behaviour — see https://github.com/opencontainers/runc/issues/3911
        // and https://github.com/opencontainers/runc/issues/3952.
        var originalMode: UInt? = null
        if (fsType == "tmpfs") {
            val hasModeOption = m.options?.any { it.startsWith("mode=") } ?: false
            if (!hasModeOption && access(target, F_OK) == 0) {
                memScoped {
                    val st = alloc<stat>()
                    if (stat(target, st.ptr) == 0) {
                        // Capture the full permission bits (including setuid/setgid/sticky).
                        originalMode = st.st_mode and 0xFFFu
                        Logger.debug("tmpfs ${m.destination}: captured original mode $originalMode")
                    }
                }
            }
        }

        mkdirP(target)
        val rc =
            syscall.mount(
                source = source,
                target = target,
                fstype = if ((parsed.flags and MS_BIND.toULong()) != 0uL) null else fsType,
                flags = parsed.flags,
                data = parsed.data,
            )
        if (rc != 0) {
            if (errno == EBUSY) {
                Logger.debug("spec.mount ${m.destination}: already mounted, skipping")
            } else {
                Logger.warn("failed to mount ${m.destination} (type=$fsType, errno=$errno)")
            }
            continue
        }
        Logger.debug("mounted ${m.destination} (type=$fsType, flags=${parsed.flags})")

        // For tmpfs: restore the original directory permissions when no mode=
        // option was specified. The tmpfs default root mode is 01777, but when
        // the mount target already existed, runc preserves its permissions.
        if (originalMode != null) {
            if (chmod(target, originalMode) != 0) {
                Logger.warn("failed to chmod tmpfs ${m.destination} to original mode (errno=$errno)")
            } else {
                Logger.debug("restored original mode on tmpfs ${m.destination}")
            }
        }

        // Bind-remount once more with the requested flags. The kernel ignores flag
        // bits other than MS_BIND/MS_REC on the initial bind mount; MS_RDONLY etc.
        // only take effect via a subsequent MS_REMOUNT.
        if ((parsed.flags and MS_BIND.toULong()) != 0uL && parsed.flags != MS_BIND.toULong() &&
            parsed.flags != (MS_BIND or MS_REC).toULong()
        ) {
            val remountFlags = parsed.flags or MS_REMOUNT.toULong()
            val remountRc =
                syscall.mount(
                    source = source,
                    target = target,
                    fstype = null,
                    flags = remountFlags,
                    data = parsed.data,
                )
            if (remountRc != 0) {
                val remountErrno = errno
                if (remountErrno == EPERM) {
                    // In user namespaces, bind-remount fails with EPERM when the
                    // source mount has MNT_LOCKED flags that are not included in
                    // our remount flags.  Read the source's flags via statfs() and
                    // merge the locked flags in, mirroring runc's fallback.
                    Logger.debug("bind-remount of ${m.destination} got EPERM, trying locked-flag fallback")
                    memScoped {
                        val st = alloc<platform.linux.statfs>()
                        val statfsPath = m.source ?: target
                        if (platform.linux.statfs(statfsPath, st.ptr) != 0) {
                            val statfsErrno = errno
                            Logger.warn("statfs $statfsPath failed (errno=$statfsErrno), cannot retry bind-remount")
                        } else {
                            val sourceMountFlags = statfsToMountFlags(st.f_flags.toULong())

                            // MNT_LOCKED flags: flags the kernel locks in a user
                            // namespace so an unprivileged remount cannot clear them.
                            val mntLockedFlags =
                                (
                                    MS_RDONLY or MS_NODEV or MS_NOEXEC or MS_NOSUID or
                                        MS_NOATIME or MS_NODIRATIME
                                ).toULong() or MS_RELATIME.toULong() or MS_NOSYMFOLLOW.toULong()

                            // Determine which locked flags the source imposes.
                            val lockedFromSource = sourceMountFlags and mntLockedFlags

                            // If the user explicitly cleared a locked flag that the
                            // source still has set, we cannot honour that request in
                            // a user namespace — report the conflict.
                            val conflict = lockedFromSource and parsed.clearedFlags
                            if (conflict != 0uL) {
                                Logger.warn(
                                    "bind-remount of ${m.destination} failed: user namespace " +
                                        "does not allow clearing locked mount flags " +
                                        "(conflicting flags=0x${conflict.toString(16)})",
                                )
                            } else {
                                // Merge the locked flags from the source into our
                                // remount flags and retry.
                                val adjustedFlags = remountFlags or lockedFromSource
                                if (syscall.mount(
                                        source = source,
                                        target = target,
                                        fstype = null,
                                        flags = adjustedFlags,
                                        data = parsed.data,
                                    ) != 0
                                ) {
                                    val retryErrno = errno
                                    Logger.warn(
                                        "bind-remount of ${m.destination} failed even with " +
                                            "locked-flag fallback (errno=$retryErrno)",
                                    )
                                } else {
                                    Logger.debug(
                                        "bind-remount of ${m.destination} succeeded with " +
                                            "locked-flag fallback (adjustedFlags=0x${adjustedFlags.toString(16)})",
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Logger.warn("bind-remount of ${m.destination} failed (errno=$remountErrno)")
                }
            }
        }

        // Apply propagation flag in a separate mount() call (kernel requirement).
        if (parsed.propagation != 0uL) {
            if (syscall.mount(
                    source = null,
                    target = target,
                    fstype = null,
                    flags = parsed.propagation,
                ) != 0
            ) {
                Logger.warn("failed to set propagation on ${m.destination} (errno=$errno)")
            }
        }

        // Apply MOUNT_ATTR_IDMAP if the mount has explicit per-mount mappings
        // or the "idmap"/"ridmap" option was given.
        val needsIdmap = !m.uidMappings.isNullOrEmpty() || !m.gidMappings.isNullOrEmpty() || parsed.idmap || parsed.ridmap
        if (needsIdmap) {
            val recursive = parsed.ridmap || !m.gidMappings.isNullOrEmpty()
            // Determine which UID/GID mappings to use: per-mount if present,
            // otherwise fall back to the container-level mappings from spec.linux.
            val uidMappings = m.uidMappings?.takeIf { it.isNotEmpty() } ?: specLinux?.uidMappings
            val gidMappings = m.gidMappings?.takeIf { it.isNotEmpty() } ?: specLinux?.gidMappings

            if (uidMappings.isNullOrEmpty() && gidMappings.isNullOrEmpty()) {
                Logger.warn("idmap requested for ${m.destination} but no UID/GID mappings available")
            } else {
                applyIdmapMount(target, uidMappings, gidMappings, recursive)
            }
        }
    }
}

/**
 * Apply MOUNT_ATTR_IDMAP to an existing mount. This replaces the mount at
 * [target] with an ID-mapped clone that translates UID/GID ownership
 * according to the given mappings.
 *
 * Algorithm:
 * 1. Create a user namespace with the desired UID/GID mappings (via
 *    fork + unshare(CLONE_NEWUSER) + write uid_map/gid_map).
 * 2. open_tree() the target mount to get a detached mount fd.
 * 3. mount_setattr() with MOUNT_ATTR_IDMAP pointing at the userns fd.
 * 4. move_mount() the modified tree back to the original target.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyIdmapMount(
    target: String,
    uidMappings: List<LinuxIdMapping>?,
    gidMappings: List<LinuxIdMapping>?,
    recursive: Boolean,
) {
    Logger.debug("applying MOUNT_ATTR_IDMAP to $target (recursive=$recursive)")

    // Build mapping strings in the kernel format: "containerID hostID size\n"
    val uidMapStr = buildMappingString(uidMappings)
    val gidMapStr = buildMappingString(gidMappings)

    Logger.debug("idmap uid_map: ${uidMapStr.trim()}")
    Logger.debug("idmap gid_map: ${gidMapStr.trim()}")

    // Step 1: Create a user namespace with the desired mappings.
    val userNsFd = platform.linux._create_userns_with_mappings(uidMapStr, gidMapStr)
    if (userNsFd < 0) {
        val errNum = errno
        Logger.error("failed to create user namespace for idmap on $target (errno=$errNum)")
        throw Exception("Failed to create user namespace for idmap on $target (errno=$errNum)")
    }
    Logger.debug("created userns fd=$userNsFd for idmap")

    try {
        // Step 2: open_tree() to get a detached copy of the mount.
        var openTreeFlags = platform.linux._OPEN_TREE_CLONE() or platform.linux._OPEN_TREE_CLOEXEC()
        if (recursive) {
            openTreeFlags = openTreeFlags or platform.linux._AT_RECURSIVE()
        }
        val treeFd = platform.linux._open_tree(-1, target, openTreeFlags)
        if (treeFd < 0) {
            val errNum = errno
            Logger.error("open_tree($target) failed (errno=$errNum)")
            throw Exception("open_tree failed for idmap on $target (errno=$errNum)")
        }
        Logger.debug("open_tree fd=$treeFd for idmap")

        try {
            // Step 3: mount_setattr() with MOUNT_ATTR_IDMAP.
            memScoped {
                val attr = alloc<platform.linux._mount_attr>()
                attr.attr_set = platform.linux._MOUNT_ATTR_IDMAP()
                attr.attr_clr = 0uL
                attr.propagation = 0uL
                attr.userns_fd = userNsFd.toULong()

                var setattrFlags = platform.linux._AT_EMPTY_PATH()
                if (recursive) {
                    setattrFlags = setattrFlags or platform.linux._AT_RECURSIVE()
                }

                val rc =
                    platform.linux._mount_setattr(
                        treeFd,
                        "",
                        setattrFlags,
                        attr.ptr,
                        platform.linux._sizeof_mount_attr(),
                    )
                if (rc < 0) {
                    val errNum = errno
                    Logger.error("mount_setattr(MOUNT_ATTR_IDMAP) failed for $target (errno=$errNum)")
                    throw Exception("mount_setattr(MOUNT_ATTR_IDMAP) failed for $target (errno=$errNum)")
                }
                Logger.debug("mount_setattr MOUNT_ATTR_IDMAP applied to tree fd=$treeFd")
            }

            // Step 4: move_mount() to replace the original mount with the idmapped one.
            val targetFd = open(target, platform.linux._O_PATH_FLAG() or O_DIRECTORY or O_CLOEXEC, 0u)
            if (targetFd < 0) {
                val errNum = errno
                Logger.error("failed to open target $target for move_mount (errno=$errNum)")
                throw Exception("Failed to open $target for move_mount (errno=$errNum)")
            }

            try {
                val moveMountFlags =
                    platform.linux._MOVE_MOUNT_F_EMPTY_PATH() or
                        platform.linux._MOVE_MOUNT_T_EMPTY_PATH()
                val rc = platform.linux._move_mount(treeFd, "", targetFd, "", moveMountFlags)
                if (rc < 0) {
                    val errNum = errno
                    Logger.error("move_mount failed for idmap on $target (errno=$errNum)")
                    throw Exception("move_mount failed for idmap on $target (errno=$errNum)")
                }
                Logger.debug("move_mount completed, idmap mount active at $target")
            } finally {
                close(targetFd)
            }
        } finally {
            close(treeFd)
        }
    } finally {
        close(userNsFd)
    }
}

/**
 * Build a kernel-format mapping string from a list of [LinuxIdMapping].
 * Format: "containerID hostID size\n" for each entry. If the list is
 * null or empty, falls back to an identity mapping "0 0 4294967295\n".
 */
private fun buildMappingString(mappings: List<LinuxIdMapping>?): String {
    if (mappings.isNullOrEmpty()) {
        return "0 0 4294967295\n"
    }
    return mappings.joinToString("") { "${it.containerID} ${it.hostID} ${it.size}\n" }
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
