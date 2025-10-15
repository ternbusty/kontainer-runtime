package rootfs

import kotlinx.cinterop.*
import platform.linux.__NR_pivot_root
import platform.posix.*

// Mount syscall number
private const val __NR_mount = 165L
private const val __NR_umount2 = 166L

// Mount flags (from linux/mount.h)
const val MS_RDONLY = 1
const val MS_NOSUID = 2
const val MS_NODEV = 4
const val MS_NOEXEC = 8
const val MS_BIND = 4096
const val MS_REC = 16384

// Umount flags
const val MNT_DETACH = 2

/**
 * Mount a filesystem using syscall
 */
@OptIn(ExperimentalForeignApi::class)
fun mountFs(
    source: String?,
    target: String,
    fstype: String?,
    flags: ULong,
    data: String? = null
): Int {
    memScoped {
        val src = source?.cstr?.ptr
        val tgt = target.cstr.ptr
        val fs = fstype?.cstr?.ptr
        val d = data?.cstr?.ptr

        return syscall(__NR_mount, src, tgt, fs, flags, d).toInt()
    }
}

/**
 * Umount filesystem using syscall
 */
@OptIn(ExperimentalForeignApi::class)
fun umount2(target: String, flags: Int): Int {
    memScoped {
        return syscall(__NR_umount2, target.cstr.ptr, flags).toInt()
    }
}

/**
 * Create device number (major, minor)
 */
fun makedev(major: UInt, minor: UInt): ULong {
    return ((major.toULong() shl 8) or minor.toULong())
}

/**
 * Prepare rootfs with basic mounts
 */
@OptIn(ExperimentalForeignApi::class)
fun prepareRootfs(rootfsPath: String) {
    fprintf(stderr, "Preparing rootfs at %s\n", rootfsPath)

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
            perror("mount /proc")
            fprintf(stderr, "Warning: failed to mount /proc\n")
        } else {
            fprintf(stderr, "Mounted /proc\n")
        }
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
            perror("mount /dev")
            fprintf(stderr, "Warning: failed to mount /dev\n")
        } else {
            fprintf(stderr, "Mounted /dev\n")

            // Create essential device nodes
            createDeviceNodes(devPath)
        }
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
            perror("mount /sys")
            fprintf(stderr, "Warning: failed to mount /sys\n")
        } else {
            fprintf(stderr, "Mounted /sys\n")
        }
    }
}

/**
 * Create essential device nodes in /dev
 */
@OptIn(ExperimentalForeignApi::class)
fun createDeviceNodes(devPath: String) {
    // Create /dev/null (character device 1,3 with mode 0666)
    val nullPath = "$devPath/null"
    mknod(nullPath, (S_IFCHR.toInt() or 0x1B6).toUInt(), makedev(1u, 3u))

    // Create /dev/zero (character device 1,5 with mode 0666)
    val zeroPath = "$devPath/zero"
    mknod(zeroPath, (S_IFCHR.toInt() or 0x1B6).toUInt(), makedev(1u, 5u))

    // Create /dev/random (character device 1,8 with mode 0666)
    val randomPath = "$devPath/random"
    mknod(randomPath, (S_IFCHR.toInt() or 0x1B6).toUInt(), makedev(1u, 8u))

    // Create /dev/urandom (character device 1,9 with mode 0666)
    val urandomPath = "$devPath/urandom"
    mknod(urandomPath, (S_IFCHR.toInt() or 0x1B6).toUInt(), makedev(1u, 9u))

    fprintf(stderr, "Created device nodes in %s\n", devPath)
}

/**
 * Perform pivot_root to change root filesystem
 */
@OptIn(ExperimentalForeignApi::class)
fun pivotRoot(newRoot: String) {
    fprintf(stderr, "Pivoting root to %s\n", newRoot)

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
        fprintf(stderr, "Warning: failed to bind mount rootfs, trying chroot instead\n")
        chrootInto(newRoot)
        return
    }

    // Change to new root directory
    if (chdir(newRoot) != 0) {
        perror("chdir to newRoot")
        throw Exception("Failed to chdir to $newRoot")
    }

    // Create a directory for old root (must be within new root)
    val oldRoot = ".old_root"
    if (access(oldRoot, F_OK) != 0) {
        if (mkdir(oldRoot, 0x1C0u) != 0) {  // 0x1C0 = 0700 octal
            perror("mkdir old_root")
            throw Exception("Failed to create old_root directory")
        }
    }

    // Perform pivot_root syscall
    memScoped {
        if (syscall(__NR_pivot_root.toLong(), ".".cstr.ptr, oldRoot.cstr.ptr) == -1L) {
            perror("pivot_root")
            throw Exception("Failed to pivot_root")
        }
    }

    // Change to new root
    if (chdir("/") != 0) {
        perror("chdir /")
        throw Exception("Failed to chdir to /")
    }

    // Unmount old root
    if (umount2(oldRoot, MNT_DETACH) != 0) {
        perror("umount2 old_root")
        fprintf(stderr, "Warning: failed to unmount old root\n")
    }

    // Remove old root directory
    rmdir(oldRoot)

    fprintf(stderr, "Successfully pivoted root\n")
}

/**
 * Simple chroot as fallback when not using mount namespace
 */
@OptIn(ExperimentalForeignApi::class)
fun chrootInto(newRoot: String) {
    fprintf(stderr, "Chrooting to %s\n", newRoot)

    if (chroot(newRoot) != 0) {
        perror("chroot")
        throw Exception("Failed to chroot to $newRoot")
    }

    if (chdir("/") != 0) {
        perror("chdir /")
        throw Exception("Failed to chdir to / after chroot")
    }

    fprintf(stderr, "Successfully chrooted\n")
}
