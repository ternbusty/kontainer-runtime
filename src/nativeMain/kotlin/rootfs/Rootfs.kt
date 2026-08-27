package rootfs

import channel.InitReceiver
import channel.MainSender
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

const val MS_MOVE = 8192 // 1 << 13

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

    // Open newroot directory so we can fchdir into it before pivot_root.
    // After pivot_root(".", "."), "." refers to put_old (the old root),
    // and "/" refers to the new root. This matches runc's approach.
    val newrootFd = open(newRoot, O_DIRECTORY or O_RDONLY, 0u)
    if (newrootFd < 0) {
        val errNum = errno
        perror("open newroot")
        Logger.error("failed to open $newRoot (errno=$errNum)")
        throw Exception("Failed to open $newRoot (errno=$errNum)")
    }

    // Change into the rootfs directory before pivot_root so that after
    // pivot_root(".", "."), "." refers to the old root (put_old).
    if (fchdir(newrootFd) != 0) {
        val errNum = errno
        perror("fchdir")
        close(newrootFd)
        Logger.error("failed to fchdir to newroot (errno=$errNum)")
        throw Exception("Failed to fchdir to newroot (errno=$errNum)")
    }
    close(newrootFd)

    // pivot_root(".", ".") — the new root is "." (which is newRoot), and
    // the old root is put at "." (which will be resolved after pivot to
    // the old root directory). See runc's pivotRoot for this pattern.
    if (syscall.pivotRoot(".", ".") == -1) {
        val errNum = errno
        perror("pivot_root")
        Logger.error("failed to pivot_root (errno=$errNum)")
        throw Exception("Failed to pivot_root (errno=$errNum)")
    }
    Logger.debug("pivot_root syscall completed")

    // After pivot_root(".", "."), "." is the old root. Make it slave so
    // umount events don't propagate to the host. This targets ONLY the
    // old root, not the new root — preserving any propagation settings
    // (e.g. "shared") set on individual mounts.
    if (syscall.mount(
            source = null,
            target = ".",
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

    // Lazy unmount of the old root.
    if (syscall.umount2(".", MNT_DETACH) != 0) {
        val errNum = errno
        perror("umount2 old root")
        Logger.warn("failed to unmount old root (errno=$errNum)")
    } else {
        Logger.debug("unmounted old root")
    }

    // Change to the new root "/" (which is now the container rootfs).
    if (syscall.chdir("/") != 0) {
        val errNum = errno
        perror("chdir /")
        Logger.error("failed to chdir to / (errno=$errNum)")
        throw Exception("Failed to chdir to / (errno=$errNum)")
    }

    Logger.debug("successfully pivoted root")
}

/**
 * Alternative to pivot_root for --no-pivot mode.
 *
 * Masks host procfs/sysfs mounts that are not under rootfs, then uses
 * MS_MOVE + chroot to switch the root. This prevents the container from
 * re-mounting procfs in writable mode (which would expose bare /proc).
 *
 * See runc/libcontainer/rootfs_linux.go:msMoveRoot().
 */
@OptIn(ExperimentalForeignApi::class)
fun msMoveRoot(
    syscall: Syscall,
    newRoot: String,
) {
    Logger.debug("msMoveRoot: switching root to $newRoot (no-pivot mode)")

    // Canonicalize newRoot so prefix matching works correctly.
    val canonRoot =
        memScoped {
            val buf = allocArray<ByteVar>(4096)
            if (realpath(newRoot, buf) != null) buf.toKString() else newRoot
        }

    // Parse /proc/self/mountinfo and collect all "full" mounts (root="/")
    // of procfs and sysfs that are NOT inside the container rootfs.
    // These must be masked before MS_MOVE to prevent the container from
    // accessing the host's /proc or /sys.
    val mountsToMask = mutableListOf<String>()
    val mountinfo =
        memScoped {
            val fd = open("/proc/self/mountinfo", O_RDONLY, 0u)
            if (fd < 0) {
                Logger.warn("msMoveRoot: cannot read /proc/self/mountinfo")
                return@memScoped ""
            }
            val buf = StringBuilder()
            val chunk = allocArray<ByteVar>(4096)
            while (true) {
                val n = read(fd, chunk, 4096u).toInt()
                if (n <= 0) break
                buf.append(chunk.toKString().take(n))
            }
            close(fd)
            buf.toString()
        }
    for (line in mountinfo.lines()) {
        if (line.isBlank()) continue
        // mountinfo format: id parent major:minor root mount-point options ... - fstype source super-options
        val parts = line.split(' ')
        if (parts.size < 7) continue
        val root = parts[3]
        val mountPoint = parts[4]
        // Find the separator " - "
        val sepIdx = parts.indexOf("-")
        if (sepIdx < 0 || sepIdx + 1 >= parts.size) continue
        val fstype = parts[sepIdx + 1]

        // Only mask full mounts (root="/") of proc or sysfs
        if (root != "/") continue
        if (fstype != "proc" && fstype != "sysfs") continue
        // Don't mask if it's under the container rootfs
        if (mountPoint.startsWith("$canonRoot/") || mountPoint == canonRoot) continue

        mountsToMask.add(mountPoint)
    }

    // Mask each host procfs/sysfs mount: make slave, then lazy unmount.
    // If unmount fails, cover with a tmpfs so it can't be read.
    for (mp in mountsToMask) {
        Logger.debug("msMoveRoot: masking host mount $mp")
        syscall.mount(
            source = null,
            target = mp,
            fstype = null,
            flags = (MS_SLAVE or MS_REC).toULong(),
        )
        if (syscall.umount2(mp, MNT_DETACH) != 0) {
            Logger.debug("msMoveRoot: umount failed for $mp, covering with tmpfs")
            syscall.mount(
                source = "tmpfs",
                target = mp,
                fstype = "tmpfs",
                flags = 0u,
            )
        }
    }

    // Open an fd to newRoot for fchdir after the MS_MOVE.
    val newrootFd = open(canonRoot, O_DIRECTORY or O_RDONLY, 0u)
    if (newrootFd < 0) {
        val errNum = errno
        Logger.error("msMoveRoot: failed to open $canonRoot (errno=$errNum)")
        throw Exception("msMoveRoot: failed to open $canonRoot (errno=$errNum)")
    }

    // MS_MOVE the rootfs mount onto "/"
    if (syscall.mount(
            source = canonRoot,
            target = "/",
            fstype = null,
            flags = MS_MOVE.toULong(),
        ) != 0
    ) {
        val errNum = errno
        close(newrootFd)
        Logger.error("msMoveRoot: MS_MOVE failed (errno=$errNum)")
        throw Exception("msMoveRoot: MS_MOVE failed (errno=$errNum)")
    }

    // fchdir into the new root, then chroot + chdir
    if (fchdir(newrootFd) != 0) {
        val errNum = errno
        close(newrootFd)
        Logger.error("msMoveRoot: fchdir failed (errno=$errNum)")
        throw Exception("msMoveRoot: fchdir failed (errno=$errNum)")
    }
    close(newrootFd)

    if (syscall.chroot(".") != 0) {
        val errNum = errno
        Logger.error("msMoveRoot: chroot failed (errno=$errNum)")
        throw Exception("msMoveRoot: chroot failed (errno=$errNum)")
    }

    if (syscall.chdir("/") != 0) {
        val errNum = errno
        Logger.error("msMoveRoot: chdir / failed (errno=$errNum)")
        throw Exception("msMoveRoot: chdir / failed (errno=$errNum)")
    }

    Logger.debug("msMoveRoot: successfully switched root (no-pivot mode)")
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
 *
 * Private/slave variants are already covered by the rslave applied in
 * prepareRootfs — applying them again here (especially rprivate) would
 * recursively reset ALL mounts to private, undoing per-mount propagation
 * flags (e.g. "shared") set during applySpecMounts.  This matches runc's
 * post-pivot logic which skips MS_PRIVATE|MS_SLAVE.
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
            "unbindable" -> "unbindable" to MS_UNBINDABLE.toULong()
            "runbindable" -> "runbindable" to (MS_UNBINDABLE or MS_REC).toULong()
            // private/slave variants (including recursive) are already covered
            // by the rslave set in prepareRootfs.  Reapplying rprivate post-pivot
            // would clobber per-mount propagation flags set during applySpecMounts.
            null, "rslave", "slave", "private", "rprivate" -> return
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
// The atime "enum" flags (mutually exclusive) and combined atime flags.
// These match runc's mntAtimeEnumFlags / mntAtimeFlags definitions.
private val MNT_ATIME_ENUM_FLAGS = (MS_NOATIME or MS_RELATIME or MS_STRICTATIME).toULong()
private val MNT_ATIME_FLAGS = MNT_ATIME_ENUM_FLAGS or MS_NODIRATIME.toULong()

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
    // MS_STRICTATIME is a "fake" MS_* flag — it isn't stored in mnt->mnt_flags,
    // so it doesn't appear in statfs(2). If none of the other atime enum flags
    // are present, the mount is MS_STRICTATIME. (Matches runc's statfsToMountFlags.)
    if (mntFlags and MNT_ATIME_ENUM_FLAGS == 0uL) {
        mntFlags = mntFlags or MS_STRICTATIME.toULong()
    }
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
 * Called before pivot_root. Returns a list of (destination, propagation_flags)
 * pairs for mounts whose propagation must be applied AFTER pivot_root. This
 * is needed because open_tree + move_mount for idmap mounts creates mounts
 * whose propagation changes are lost during pivot_root's cleanup of the old
 * root mount tree.
 */
@OptIn(ExperimentalForeignApi::class)
fun applySpecMounts(
    syscall: Syscall,
    mounts: List<spec.Mount>?,
    rootfsPath: String,
    specLinux: spec.Linux? = null,
    mainSender: MainSender? = null,
    initReceiver: InitReceiver? = null,
): List<Pair<String, ULong>> {
    if (mounts.isNullOrEmpty()) return emptyList()
    val deferredPropagation = mutableListOf<Pair<String, ULong>>()
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
        // path before pivot_root is rootfsPath + destination. Destination may be
        // relative (no leading '/') per OCI spec, so ensure a separator.
        val target =
            if (m.destination.startsWith("/")) {
                rootfsPath + m.destination
            } else {
                "$rootfsPath/${m.destination}"
            }

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
        //
        // We need a remount whenever the user specified any option beyond plain
        // "bind"/"rbind", even if that option only CLEARS a flag (e.g. "dev"
        // clears MS_NODEV). In runc's semantics, specifying ANY mount option
        // on a bind mount means "I want exactly these flags, clear everything
        // else" — which differs from mount(8)'s default inherit-all behavior.
        val isBindMount = (parsed.flags and MS_BIND.toULong()) != 0uL
        val justBind = parsed.flags == MS_BIND.toULong() || parsed.flags == (MS_BIND or MS_REC).toULong()
        val hasExtraOptions = !justBind || parsed.clearedFlags != 0uL
        if (isBindMount && hasExtraOptions) {
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
                            Logger.error("statfs $statfsPath failed (errno=$statfsErrno), cannot retry bind-remount")
                            throw Exception(
                                "error mounting \"${m.source ?: ""}\" to \"${m.destination}\" " +
                                    "mount destination: statfs failed (errno=$statfsErrno): operation not permitted",
                            )
                        } else {
                            val sourceMountFlags = statfsToMountFlags(st.f_flags.toULong())

                            // MNT_LOCKED flags: flags the kernel locks in a user
                            // namespace so an unprivileged remount cannot clear them.
                            // NOTE: MS_NOSYMFOLLOW is NOT locked (runc confirms this).
                            // Includes all atime flags (MS_STRICTATIME is "fake" but still locked).
                            val mntLockedFlags =
                                (
                                    MS_RDONLY or MS_NODEV or MS_NOEXEC or MS_NOSUID
                                ).toULong() or MNT_ATIME_FLAGS

                            // Determine which locked flags the source imposes.
                            val lockedFromSource = sourceMountFlags and mntLockedFlags

                            // If the user explicitly cleared a locked flag that the
                            // source still has set, we cannot honour that request in
                            // a user namespace — this is a hard error matching runc.
                            val conflict = lockedFromSource and parsed.clearedFlags
                            if (conflict != 0uL) {
                                Logger.error(
                                    "bind-remount of ${m.destination} failed: user namespace " +
                                        "does not allow clearing locked mount flags " +
                                        "(conflicting flags=0x${conflict.toString(16)})",
                                )
                                throw Exception(
                                    "error mounting \"${m.source ?: ""}\" to \"${m.destination}\" " +
                                        "mount destination: operation not permitted",
                                )
                            }

                            // If an atime flag was requested, it must match the source.
                            // This catches two kernel bugs where the kernel silently
                            // ignores conflicting atime flags:
                            // - MS_RELATIME is ignored when MS_NOATIME is set
                            // - MS_STRICTATIME causes MS_RELATIME/MS_NOATIME to be ignored
                            // We must error out to avoid producing mounts that don't
                            // match the user's request. (Matches runc's remount logic.)
                            val requestedAtime = parsed.flags and MNT_ATIME_FLAGS
                            val sourceAtime = sourceMountFlags and MNT_ATIME_FLAGS
                            if (requestedAtime != 0uL && requestedAtime != sourceAtime) {
                                Logger.error(
                                    "bind-remount of ${m.destination} failed: cannot change " +
                                        "locked atime flags (requested=0x${requestedAtime.toString(16)}, " +
                                        "source=0x${sourceAtime.toString(16)})",
                                )
                                throw Exception(
                                    "error mounting \"${m.source ?: ""}\" to \"${m.destination}\" " +
                                        "mount destination: operation not permitted",
                                )
                            }

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
                } else {
                    Logger.warn("bind-remount of ${m.destination} failed (errno=$remountErrno)")
                }
            }
        }

        // Apply MOUNT_ATTR_IDMAP if the mount has explicit per-mount mappings
        // or the "idmap"/"ridmap" option was given.
        // NOTE: idmap is processed BEFORE propagation because the idmap step
        // does open_tree + move_mount which replaces the mount tree. Any
        // propagation set before that would be lost on the replacement mount.
        val needsIdmap = !m.uidMappings.isNullOrEmpty() || !m.gidMappings.isNullOrEmpty() || parsed.idmap || parsed.ridmap
        if (needsIdmap) {
            // open_tree needs AT_RECURSIVE when the mount has submounts
            // (MS_REC / rbind) so the entire mount tree is cloned.
            // mount_setattr needs AT_RECURSIVE only for "ridmap" — for
            // "idmap" the id mapping is applied only to the top mount.
            // runc separates these two decisions the same way (see
            // libcontainer/mount_linux.go mountFd()).
            val openTreeRecursive = (parsed.flags and MS_REC.toULong()) != 0uL
            val setattrRecursive = parsed.ridmap

            // Determine which UID/GID mappings to use: per-mount if present,
            // otherwise fall back to the container-level mappings from spec.linux.
            val uidMappings = m.uidMappings?.takeIf { it.isNotEmpty() } ?: specLinux?.uidMappings
            val gidMappings = m.gidMappings?.takeIf { it.isNotEmpty() } ?: specLinux?.gidMappings

            requireNotNull(mainSender) { "mainSender required for idmap mounts" }
            requireNotNull(initReceiver) { "initReceiver required for idmap mounts" }

            if (uidMappings.isNullOrEmpty() && gidMappings.isNullOrEmpty()) {
                // Implied mapping: when "idmap"/"ridmap" option is specified
                // without explicit UID/GID mappings, use the current user
                // namespace's mapping (like runc's "implied mapping" behavior).
                applyIdmapMountImplied(syscall, target, openTreeRecursive, setattrRecursive, mainSender, initReceiver)
            } else {
                // OCI mappings use host-relative IDs. The main process runs
                // in the host user namespace, so the mappings can be passed
                // directly — no translation needed.
                applyIdmapMount(syscall, target, uidMappings, gidMappings, openTreeRecursive, setattrRecursive, mainSender, initReceiver)
            }
        }

        // Apply propagation flag in a separate mount() call (kernel requirement).
        // This must happen AFTER idmap processing because the idmap step does
        // open_tree + move_mount which replaces the mount — propagation set
        // before that would be lost on the replacement mount.
        //
        // For ALL mounts with propagation flags, defer the propagation to
        // after pivot_root. Propagation set before pivot_root is lost during
        // pivot_root's cleanup (MS_SLAVE|MS_REC on old root + umount). The
        // deferred entries use the post-pivot path (m.destination, not target)
        // since after pivot_root rootfsPath becomes "/".
        if (parsed.propagation != 0uL) {
            deferredPropagation.add(m.destination to parsed.propagation)
        }
    }
    return deferredPropagation
}

/**
 * Apply deferred mount propagation flags AFTER pivot_root.
 *
 * Some propagation changes (especially on idmap mounts created via
 * open_tree + move_mount) are lost during pivot_root. This function
 * applies them after pivot_root when the mount tree is stable.
 *
 * @param entries list of (destination, propagation_flags) pairs
 */
@OptIn(ExperimentalForeignApi::class)
fun applyDeferredPropagation(entries: List<Pair<String, ULong>>) {
    for ((destination, flags) in entries) {
        if (platform.linux._mount(null, destination, null, flags, null) != 0) {
            Logger.warn("failed to set deferred propagation on $destination (errno=$errno)")
        } else {
            Logger.debug("set deferred propagation on $destination (flags=0x${flags.toString(16)})")
        }
    }
}

/**
 * Apply MOUNT_ATTR_IDMAP to an existing mount via fd-passing to the
 * main process (which runs in the host user namespace).
 *
 * mount_setattr(MOUNT_ATTR_IDMAP) requires the caller to be in the
 * init (host) user namespace. The init process is in the container's
 * user namespace, so it cannot call mount_setattr directly. Instead:
 *
 * 1. Init: open_tree(target) → detached mount tree fd
 * 2. Init: send tree fd + mappings to main via MountFdRequest/SCM_RIGHTS
 * 3. Main: create userns with OCI mappings, call mount_setattr on tree fd
 * 4. Main: send MountFdDone
 * 5. Init: move_mount(tree fd → target) to install the idmapped mount
 *
 * The tree fd references the same kernel mount object in both processes,
 * so mount_setattr applied by main is visible when init calls move_mount.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyIdmapMount(
    syscall: Syscall,
    target: String,
    uidMappings: List<LinuxIdMapping>?,
    gidMappings: List<LinuxIdMapping>?,
    openTreeRecursive: Boolean,
    setattrRecursive: Boolean,
    mainSender: MainSender,
    initReceiver: InitReceiver,
) {
    Logger.debug(
        "applying MOUNT_ATTR_IDMAP to $target via main process (openTreeRecursive=$openTreeRecursive, setattrRecursive=$setattrRecursive)",
    )

    // Build mapping strings in the kernel format: "containerID hostID size\n"
    val uidMapStr = buildMappingString(uidMappings)
    val gidMapStr = buildMappingString(gidMappings)

    Logger.debug("idmap uid_map: ${uidMapStr.trim()}")
    Logger.debug("idmap gid_map: ${gidMapStr.trim()}")

    // Step 1: open_tree() to get a detached copy of the mount.
    // AT_RECURSIVE on open_tree clones the entire mount tree (submounts).
    // This is needed for rbind mounts so submounts survive the move_mount.
    // AT_RECURSIVE on mount_setattr (separate flag) controls whether idmap
    // applies to all submounts or just the top mount.
    var openTreeFlags = platform.linux._OPEN_TREE_CLONE() or platform.linux._OPEN_TREE_CLOEXEC()
    if (openTreeRecursive) {
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
        // Step 2: Send tree fd to main process for mount_setattr.
        // setattrRecursive controls AT_RECURSIVE on mount_setattr.
        mainSender.mountFdRequest(treeFd, uidMapStr, gidMapStr, setattrRecursive, implied = false)

        // Step 3: Wait for main to apply mount_setattr.
        initReceiver.waitForMountFdDone()
        Logger.debug("main process applied mount_setattr for $target")

        // Step 4: Remove the original mount before move_mount so we don't
        // stack mounts. The clone from open_tree is a separate detached copy
        // that is unaffected by this unmount.
        syscall.umount2(target, MNT_DETACH)

        // Step 5: move_mount() to install the idmapped mount at the target.
        val rc =
            platform.linux._move_mount(
                treeFd,
                "",
                -100,
                target, // AT_FDCWD
                platform.linux._MOVE_MOUNT_F_EMPTY_PATH(),
            )
        if (rc < 0) {
            val errNum = errno
            Logger.error("move_mount failed for idmap on $target (errno=$errNum)")
            throw Exception("move_mount failed for idmap on $target (errno=$errNum)")
        }
        Logger.debug("move_mount completed, idmap mount active at $target")
    } finally {
        close(treeFd)
    }
}

/**
 * Apply MOUNT_ATTR_IDMAP using the container's own user namespace
 * (implied mapping) via fd-passing to the main process.
 *
 * Same architecture as [applyIdmapMount] but sets implied=true so the
 * main process uses the container's userns (via /proc/$initPid/ns/user)
 * instead of creating a new one. Matches runc's "implied mapping" behavior.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyIdmapMountImplied(
    syscall: Syscall,
    target: String,
    openTreeRecursive: Boolean,
    setattrRecursive: Boolean,
    mainSender: MainSender,
    initReceiver: InitReceiver,
) {
    Logger.debug(
        "applying MOUNT_ATTR_IDMAP (implied mapping) to $target via main process (openTreeRecursive=$openTreeRecursive, setattrRecursive=$setattrRecursive)",
    )

    // open_tree() to get a detached copy of the mount.
    var openTreeFlags = platform.linux._OPEN_TREE_CLONE() or platform.linux._OPEN_TREE_CLOEXEC()
    if (openTreeRecursive) {
        openTreeFlags = openTreeFlags or platform.linux._AT_RECURSIVE()
    }
    val treeFd = platform.linux._open_tree(-1, target, openTreeFlags)
    if (treeFd < 0) {
        val errNum = errno
        Logger.error("open_tree($target) failed (errno=$errNum)")
        throw Exception("open_tree failed for implied idmap on $target (errno=$errNum)")
    }

    try {
        // Send tree fd to main with implied=true (no UID/GID mappings needed).
        mainSender.mountFdRequest(treeFd, null, null, setattrRecursive, implied = true)

        // Wait for main to apply mount_setattr.
        initReceiver.waitForMountFdDone()
        Logger.debug("main process applied implied mount_setattr for $target")

        // Remove the original mount before move_mount (see applyIdmapMount).
        syscall.umount2(target, MNT_DETACH)

        // move_mount() to install the idmapped mount.
        val rc =
            platform.linux._move_mount(
                treeFd,
                "",
                -100,
                target, // AT_FDCWD
                platform.linux._MOVE_MOUNT_F_EMPTY_PATH(),
            )
        if (rc < 0) {
            val errNum = errno
            Logger.error("move_mount failed for implied idmap on $target (errno=$errNum)")
            throw Exception("move_mount failed for implied idmap on $target (errno=$errNum)")
        }
        Logger.debug("move_mount completed, implied idmap mount active at $target")
    } finally {
        close(treeFd)
    }
}

/**
 * Handle a [Message.MountFdRequest] in the main process (host user namespace).
 *
 * Called by the main process message loop when the init process sends a
 * mount tree fd for MOUNT_ATTR_IDMAP application. The main process can
 * call mount_setattr because it runs in the init (host) user namespace.
 *
 * For explicit mappings: creates a new user namespace with the given
 * UID/GID mappings and passes it to mount_setattr.
 *
 * For implied mappings: opens the container's user namespace from
 * /proc/[stage2Pid]/ns/user and passes it to mount_setattr.
 *
 * @param treeFd   detached mount tree fd received via SCM_RIGHTS
 * @param uidMap   kernel-format uid_map string (null for implied)
 * @param gidMap   kernel-format gid_map string (null for implied)
 * @param recursive whether to pass AT_RECURSIVE to mount_setattr
 * @param implied  if true, use the container's own userns
 * @param stage2Pid PID of the init process (for /proc access)
 */
@OptIn(ExperimentalForeignApi::class)
fun handleMountFdRequest(
    treeFd: Int,
    uidMap: String?,
    gidMap: String?,
    recursive: Boolean,
    implied: Boolean,
    stage2Pid: Int,
) {
    Logger.debug("handling mount fd request (implied=$implied, recursive=$recursive)")

    val userNsFd: Int
    if (implied) {
        // Open the container's user namespace from the host.
        userNsFd = open("/proc/$stage2Pid/ns/user", O_RDONLY or O_CLOEXEC)
        if (userNsFd < 0) {
            val errNum = errno
            close(treeFd)
            throw Exception("failed to open /proc/$stage2Pid/ns/user for implied idmap (errno=$errNum)")
        }
        Logger.debug("opened container userns fd=$userNsFd for implied idmap")
    } else {
        // Create a new user namespace with the specified OCI mappings.
        // The main process is in the host userns, so OCI host-relative
        // IDs are used directly — no translation needed.
        userNsFd = platform.linux._create_userns_with_mappings(uidMap!!, gidMap!!)
        if (userNsFd < 0) {
            val errNum = errno
            close(treeFd)
            throw Exception("failed to create userns for idmap (errno=$errNum)")
        }
        Logger.debug("created userns fd=$userNsFd for idmap")
    }

    try {
        // Apply MOUNT_ATTR_IDMAP to the detached mount tree.
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
                Logger.error("mount_setattr(MOUNT_ATTR_IDMAP) failed (errno=$errNum)")
                throw Exception("mount_setattr(MOUNT_ATTR_IDMAP) failed (errno=$errNum)")
            }
            Logger.debug("mount_setattr MOUNT_ATTR_IDMAP applied to tree fd=$treeFd")
        }
    } finally {
        close(userNsFd)
    }
    // Close our copy of the tree fd. Init's copy is still valid and now
    // carries the idmap attributes — init will use it for move_mount.
    close(treeFd)
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
