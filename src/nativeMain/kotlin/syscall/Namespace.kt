package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.__NR_unshare
import platform.posix.errno
import platform.posix.perror
import platform.posix.syscall

/**
 * Low-level namespace syscall wrappers
 *
 * Provides thin wrappers around namespace-related system calls.
 * This layer should not contain business logic - just syscall invocation.
 */

/**
 * Unshare namespace(s)
 *
 * Disassociates parts of the process execution context that are currently
 * being shared with other processes.
 *
 * @param flags Namespace flags (CLONE_NEWNS, CLONE_NEWNET, etc.)
 * @throws Exception if unshare fails
 */
@OptIn(ExperimentalForeignApi::class)
fun unshare(flags: Int) {
    if (syscall(__NR_unshare.toLong(), flags) == -1L) {
        val errNum = errno
        perror("unshare")
        throw Exception("Failed to unshare namespace (flags=0x${flags.toString(16)}, errno=$errNum)")
    }
}

/**
 * Reassociate thread with a namespace
 *
 * Allows the calling thread to join an existing namespace.
 *
 * @param fd File descriptor referring to a namespace (typically opened from /proc/[pid]/ns/)
 * @param nstype Namespace type (CLONE_NEWNS, CLONE_NEWNET, etc.), or 0 to allow any type
 * @throws Exception if setns fails
 */
@OptIn(ExperimentalForeignApi::class)
fun setns(fd: Int, nstype: Int) {
    // setns syscall number is 308 on x86_64
    val __NR_setns = 308L

    if (syscall(__NR_setns, fd, nstype) == -1L) {
        val errNum = errno
        perror("setns")
        throw Exception("Failed to setns (fd=$fd, nstype=0x${nstype.toString(16)}, errno=$errNum)")
    }
}
