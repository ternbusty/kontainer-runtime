package rootfs

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import syscall.mount
import syscall.pivotRoot
import syscall.umount2

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
 * Mount a filesystem using syscall layer
 */
@OptIn(ExperimentalForeignApi::class)
fun mountFs(
    source: String?,
    target: String,
    fstype: String?,
    flags: ULong,
    data: String? = null,
): Int = mount(source, target, fstype, flags, data)

/**
 * Umount filesystem using syscall layer
 */
@OptIn(ExperimentalForeignApi::class)
fun umountFs(
    target: String,
    flags: Int,
): Int = umount2(target, flags)

/**
 * Prepare rootfs with basic mounts
 */
@OptIn(ExperimentalForeignApi::class)
fun prepareRootfs(rootfsPath: String) {
    Logger.debug("preparing rootfs at $rootfsPath")

    // Ensure rootfs directory exists
    if (access(rootfsPath, F_OK) != 0) {
        throw Exception("Rootfs path does not exist: $rootfsPath")
    }

    // Change root mount propagation to slave
    // This prevents mount/umount events from propagating to/from the host
    // and is required for pivot_root to work correctly
    Logger.debug("changing root mount propagation to slave")
    if (mountFs(
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

    // Bind mount rootfs to itself to make it a mount point
    // This is required for pivot_root to work properly
    Logger.debug("bind mounting rootfs to itself")
    if (mountFs(
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
        if (mountFs(
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
        if (mountFs(
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

        // Create essential device nodes and mount /dev/shm
        createDeviceNodes(devPath)
    }

    // Mount /sys if it exists in rootfs
    val sysPath = "$rootfsPath/sys"
    if (access(sysPath, F_OK) == 0) {
        if (mountFs(
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

        // Mount /sys/fs/cgroup if cgroup v2 is available
        // This allows the container to read its cgroup information
        // We bind mount the host's /sys/fs/cgroup instead of creating a new one
        // because user namespaces don't have permission to create new cgroup filesystems
        val cgroupPath = "$rootfsPath/sys/fs/cgroup"
        if (access("/sys/fs/cgroup/cgroup.controllers", F_OK) == 0) {
            // Host has cgroup v2
            Logger.debug("bind mounting /sys/fs/cgroup (cgroup v2)")

            // Create directory if it doesn't exist
            if (access(cgroupPath, F_OK) != 0) {
                if (mkdir(cgroupPath, 0x1EDu) != 0) { // 0755
                    val errNum = errno
                    Logger.warn("failed to create /sys/fs/cgroup directory (errno=$errNum)")
                }
            }

            // Step 1: Bind mount host's /sys/fs/cgroup
            if (mountFs(
                    source = "/sys/fs/cgroup",
                    target = cgroupPath,
                    fstype = null,
                    flags = (MS_BIND or MS_REC).toULong(),
                ) != 0
            ) {
                val errNum = errno
                perror("bind mount /sys/fs/cgroup")
                Logger.warn("failed to bind mount /sys/fs/cgroup (errno=$errNum)")
                // Continue anyway - cgroup is not critical for container execution
            } else {
                Logger.debug("bind mounted /sys/fs/cgroup")

                // Step 2: Remount as readonly
                if (mountFs(
                        source = null,
                        target = cgroupPath,
                        fstype = null,
                        flags = (MS_BIND or MS_REMOUNT or MS_RDONLY or MS_NOSUID or MS_NODEV or MS_NOEXEC).toULong(),
                    ) != 0
                ) {
                    val errNum = errno
                    perror("remount /sys/fs/cgroup readonly")
                    Logger.warn("failed to remount /sys/fs/cgroup readonly (errno=$errNum)")
                    // Continue anyway - readonly remount is best effort
                } else {
                    Logger.debug("remounted /sys/fs/cgroup as readonly")
                }
            }
        } else {
            Logger.debug("cgroup v2 not available on host, skipping /sys/fs/cgroup mount")
        }
    }
}

/**
 * Create a single device node using bind mount approach
 *
 * This approach works in user namespaces where mknod() would fail with EPERM.
 * Instead of using mknod(), we:
 * 1. Create an empty file
 * 2. Bind mount the host device onto that file
 *
 * @param path Full path to the device node in container
 * @param name Device name for logging and locating host device
 * @throws Exception if device creation fails
 */
@OptIn(ExperimentalForeignApi::class)
private fun createDeviceNode(
    path: String,
    name: String,
) {
    // Create empty file first
    // Mode 0666 for device files
    val fd = open(path, O_RDWR or O_CREAT, 0x1B6u)
    if (fd == -1) {
        val errNum = errno
        if (errNum == EEXIST) {
            // File already exists - this is OK, we'll try to mount over it
            Logger.debug("file for device $name already exists at $path")
        } else {
            // Other errors are failures
            perror("open $name")
            Logger.error("failed to create file for device $name at $path (errno=$errNum)")
            throw Exception("Failed to create file for device node: $name")
        }
    } else {
        // Successfully created file, close it
        close(fd)
        Logger.debug("created file for device $name at $path")
    }

    // Bind mount host device onto the file
    val hostDevPath = "/dev/$name"
    if (mountFs(
            source = hostDevPath,
            target = path,
            fstype = null,
            flags = MS_BIND.toULong(),
        ) != 0
    ) {
        val errNum = errno
        // EBUSY means device is already mounted - this is OK
        if (errNum == EBUSY) {
            Logger.debug("device $name already mounted at $path")
        } else {
            // Other errors are failures
            perror("bind mount $name")
            Logger.error("failed to bind mount $hostDevPath to $path (errno=$errNum)")
            throw Exception("Failed to bind mount device: $name")
        }
    } else {
        Logger.debug("bind mounted $hostDevPath to $path")
    }
}

/**
 * Create essential device nodes in /dev
 */
@OptIn(ExperimentalForeignApi::class)
fun createDeviceNodes(devPath: String) {
    createDeviceNode("$devPath/null", "null")
    createDeviceNode("$devPath/zero", "zero")
    createDeviceNode("$devPath/random", "random")
    createDeviceNode("$devPath/urandom", "urandom")
    Logger.debug("finished creating device nodes in $devPath")

    // Mount /dev/shm for shared memory (POSIX shm_open, etc.)
    // See: runc/libcontainer/SPEC.md
    val shmPath = "$devPath/shm"
    if (access(shmPath, F_OK) != 0) {
        // Create /dev/shm directory if it doesn't exist
        if (mkdir(shmPath, 0x1FFu) != 0) { // 0x1FF = 0777 octal
            val errNum = errno
            Logger.warn("failed to create /dev/shm directory (errno=$errNum)")
        }
    }
    if (mountFs(
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
        // Continue anyway - shm is not critical for all containers
    } else {
        Logger.debug("mounted /dev/shm")
    }
}

/**
 * Perform pivot_root to change root filesystem
 */
@OptIn(ExperimentalForeignApi::class)
fun pivotRoot(newRoot: String) {
    Logger.debug("pivoting root to $newRoot")

    // Open newroot directory to get a file descriptor before pivot_root
    // This allows us to fchdir back to it after pivot_root
    val newrootFd = open(newRoot, O_DIRECTORY or O_RDONLY, 0u)
    if (newrootFd < 0) {
        val errNum = errno
        perror("open newroot")
        Logger.error("failed to open $newRoot (errno=$errNum)")
        throw Exception("Failed to open $newRoot (errno=$errNum)")
    }

    // Perform pivot_root syscall using newroot for both arguments
    // This puts the old root at the same location as new root
    if (pivotRoot(newRoot, newRoot) == -1) {
        val errNum = errno
        perror("pivot_root")
        close(newrootFd)
        Logger.error("failed to pivot_root (errno=$errNum)")
        throw Exception("Failed to pivot_root (errno=$errNum)")
    }
    Logger.debug("pivot_root syscall completed")

    // Make the old root (which is now at /) a slave mount
    // This prevents umount events from propagating to the host
    // IMPORTANT: This must be done BEFORE fchdir to the new root
    if (mountFs(
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

    // Unmount the old root with lazy unmount
    // Since we used pivot_root(newroot, newroot), old root is at /
    // IMPORTANT: This must be done BEFORE fchdir to the new root
    if (umountFs("/", MNT_DETACH) != 0) {
        val errNum = errno
        perror("umount2 old root")
        Logger.warn("failed to unmount old root (errno=$errNum)")
    } else {
        Logger.debug("unmounted old root")
    }

    // Change to the new root using file descriptor
    if (fchdir(newrootFd) != 0) {
        val errNum = errno
        perror("fchdir")
        close(newrootFd)
        Logger.error("failed to fchdir to newroot (errno=$errNum)")
        throw Exception("Failed to fchdir to newroot (errno=$errNum)")
    }
    Logger.debug("changed to new root")

    close(newrootFd)

    // Change to root directory of new rootfs
    if (chdir("/") != 0) {
        val errNum = errno
        perror("chdir /")
        Logger.error("failed to chdir to / (errno=$errNum)")
        throw Exception("Failed to chdir to / (errno=$errNum)")
    }

    Logger.debug("successfully pivoted root")
}

/**
 * Simple chroot as fallback when not using mount namespace
 */
@OptIn(ExperimentalForeignApi::class)
fun chrootInto(newRoot: String) {
    Logger.debug("chrooting to $newRoot")

    if (chroot(newRoot) != 0) {
        perror("chroot")
        throw Exception("Failed to chroot to $newRoot")
    }

    if (chdir("/") != 0) {
        perror("chdir /")
        throw Exception("Failed to chdir to / after chroot")
    }

    Logger.debug("successfully chrooted")
}

/**
 * Set root filesystem as readonly
 * This is called after pivot_root to make the container's root readonly
 * See: runc/libcontainer/rootfs_linux.go:setReadonly()
 */
@OptIn(ExperimentalForeignApi::class)
fun setRootfsReadonly() {
    Logger.debug("setting rootfs as readonly")

    // Try MS_BIND | MS_REMOUNT | MS_RDONLY
    var flags = (MS_BIND or MS_REMOUNT or MS_RDONLY).toULong()

    if (mountFs(
            source = null,
            target = "/",
            fstype = null,
            flags = flags,
        ) == 0
    ) {
        Logger.debug("rootfs set as readonly")
        return
    }

    // If failed, get current mount flags and retry
    // This is necessary because some filesystems require their existing flags
    // to be preserved during remount
    memScoped {
        val st = alloc<platform.linux.statfs>()
        if (platform.linux.statfs("/", st.ptr) != 0) {
            val errNum = errno
            perror("statfs /")
            Logger.error("failed to statfs / (errno=$errNum)")
            throw Exception("Failed to statfs / (errno=$errNum)")
        }

        // Add existing flags from statfs
        flags = flags or st.f_flags.toULong()

        if (mountFs(
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
