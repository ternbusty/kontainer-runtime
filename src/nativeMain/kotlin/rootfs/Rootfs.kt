package rootfs

import kotlinx.cinterop.ExperimentalForeignApi
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
const val MS_BIND = 4096
const val MS_REC = 16384
const val MS_SLAVE = 524288  // 1 << 19

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
    data: String? = null
): Int {
    return mount(source, target, fstype, flags, data)
}

/**
 * Umount filesystem using syscall layer
 */
@OptIn(ExperimentalForeignApi::class)
fun umountFs(target: String, flags: Int): Int {
    return umount2(target, flags)
}

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

    // Mount /proc if it exists in rootfs
    val procPath = "$rootfsPath/proc"
    if (access(procPath, F_OK) == 0) {
        if (mountFs(
                source = "proc",
                target = procPath,
                fstype = "proc",
                flags = (MS_NOSUID or MS_NODEV or MS_NOEXEC).toULong()
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
                data = "mode=755"
            ) != 0
        ) {
            val errNum = errno
            perror("mount /dev")
            Logger.error("failed to mount /dev (errno=$errNum)")
            throw Exception("Failed to mount /dev (errno=$errNum)")
        }
        Logger.debug("mounted /dev")

        // Create essential device nodes
        createDeviceNodes(devPath)
    }

    // Mount /sys if it exists in rootfs
    val sysPath = "$rootfsPath/sys"
    if (access(sysPath, F_OK) == 0) {
        if (mountFs(
                source = "sysfs",
                target = sysPath,
                fstype = "sysfs",
                flags = (MS_NOSUID or MS_NODEV or MS_NOEXEC or MS_RDONLY).toULong()
            ) != 0
        ) {
            val errNum = errno
            perror("mount /sys")
            Logger.error("failed to mount /sys (errno=$errNum)")
            throw Exception("Failed to mount /sys (errno=$errNum)")
        }
        Logger.debug("mounted /sys")
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
private fun createDeviceNode(path: String, name: String) {
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
            flags = MS_BIND.toULong()
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
}

/**
 * Perform pivot_root to change root filesystem
 */
@OptIn(ExperimentalForeignApi::class)
fun pivotRoot(newRoot: String) {
    Logger.debug("pivoting root to $newRoot")

    // First, bind mount the rootfs to itself to make it a mount point
    // This is required for pivot_root to work
    if (mountFs(
            source = newRoot,
            target = newRoot,
            fstype = null,
            flags = (MS_BIND or MS_REC).toULong()
        ) != 0
    ) {
        perror("bind mount rootfs")
        Logger.warn("failed to bind mount rootfs, trying chroot instead")
        chrootInto(newRoot)
        return
    }

    // Open newroot directory to get a file descriptor before pivot_root
    // This allows us to fchdir back to it after pivot_root
    val newrootFd = open(newRoot, O_DIRECTORY or O_RDONLY, 0u)
    if (newrootFd < 0) {
        perror("open newroot")
        throw Exception("Failed to open $newRoot")
    }

    // Perform pivot_root syscall using newroot for both arguments
    // This puts the old root at the same location as new root
    if (pivotRoot(newRoot, newRoot) == -1) {
        perror("pivot_root")
        close(newrootFd)
        throw Exception("Failed to pivot_root")
    }

    // Change to the new root using file descriptor
    if (fchdir(newrootFd) != 0) {
        perror("fchdir")
        close(newrootFd)
        throw Exception("Failed to fchdir to newroot")
    }
    close(newrootFd)

    // Make the old root (which is now at /) a slave mount
    // This prevents umount events from propagating to the host
    if (mountFs(
            source = null,
            target = "/",
            fstype = null,
            flags = (MS_SLAVE or MS_REC).toULong()
        ) != 0
    ) {
        perror("make old root slave")
        Logger.warn("failed to make old root slave")
    }

    // Unmount the old root with lazy unmount
    // Since we used pivot_root(newroot, newroot), old root is at /
    if (umountFs("/", MNT_DETACH) != 0) {
        perror("umount2 old root")
        Logger.warn("failed to unmount old root")
    } else {
        Logger.debug("unmounted old root")
    }

    // Change to root directory of new rootfs
    if (chdir("/") != 0) {
        perror("chdir /")
        throw Exception("Failed to chdir to /")
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
