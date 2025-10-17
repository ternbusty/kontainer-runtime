package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.linux.__NR_mount
import platform.linux.__NR_pivot_root
import platform.linux.__NR_umount2
import platform.posix.syscall

/**
 * Low-level mount syscall wrappers
 *
 * Provides thin wrappers around mount-related system calls.
 * This layer should not contain business logic - just syscall invocation.
 */

/**
 * Mount a filesystem
 *
 * @param source Source device or filesystem (can be null for certain filesystem types)
 * @param target Mount point path
 * @param fstype Filesystem type (e.g., "ext4", "proc", "tmpfs")
 * @param flags Mount flags (MS_RDONLY, MS_BIND, etc.)
 * @param data Additional mount options as string
 * @return 0 on success, -1 on error (check errno)
 */
@OptIn(ExperimentalForeignApi::class)
fun mount(
    source: String?,
    target: String,
    fstype: String?,
    flags: ULong,
    data: String? = null
): Int {
    return memScoped {
        val src = source?.cstr?.ptr
        val tgt = target.cstr.ptr
        val fs = fstype?.cstr?.ptr
        val d = data?.cstr?.ptr

        syscall(__NR_mount.toLong(), src, tgt, fs, flags, d).toInt()
    }
}

/**
 * Unmount a filesystem
 *
 * @param target Mount point path to unmount
 * @param flags Unmount flags (MNT_DETACH, MNT_FORCE, etc.)
 * @return 0 on success, -1 on error (check errno)
 */
@OptIn(ExperimentalForeignApi::class)
fun umount2(target: String, flags: Int): Int {
    return memScoped {
        syscall(__NR_umount2.toLong(), target.cstr.ptr, flags).toInt()
    }
}

/**
 * Change the root filesystem
 *
 * pivot_root() changes the root filesystem of the calling process to newRoot,
 * and moves the old root filesystem to putOld.
 *
 * @param newRoot Path to the new root filesystem
 * @param putOld Path where the old root will be moved
 * @return 0 on success, -1 on error (check errno)
 */
@OptIn(ExperimentalForeignApi::class)
fun pivotRoot(newRoot: String, putOld: String): Int {
    return memScoped {
        syscall(__NR_pivot_root.toLong(), newRoot.cstr.ptr, putOld.cstr.ptr).toInt()
    }
}
