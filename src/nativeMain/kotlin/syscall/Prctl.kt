package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.linux.PR_GET_DUMPABLE
import platform.linux.PR_SET_DUMPABLE
import platform.linux.PR_SET_NO_NEW_PRIVS
import platform.linux.__NR_prctl
import platform.posix.errno
import platform.posix.perror
import platform.posix.syscall

/**
 * prctl(2) system call wrappers
 *
 * See https://man7.org/linux/man-pages/man2/prctl.2.html
 */

/**
 * Set or get the dumpable attribute for the calling process
 *
 * The dumpable attribute determines whether a core dump is produced for the calling
 * process upon delivery of a signal whose default behavior is to produce a core dump.
 *
 * More importantly for containers, the parent process can only write to
 * /proc/<pid>/uid_map and /proc/<pid>/gid_map if the child process is dumpable.
 *
 * This is typically set to true before requesting UID/GID mapping from the parent,
 * then set back to false after mapping is complete.
 *
 * @param dumpable true to make process dumpable, false to make it non-dumpable
 * @throws Exception if prctl fails
 */
@OptIn(ExperimentalForeignApi::class)
fun setDumpable(dumpable: Boolean) {
    Logger.debug("setting dumpable to $dumpable")

    val arg = if (dumpable) 1L else 0L
    val result =
        syscall(
            __NR_prctl.toLong(),
            PR_SET_DUMPABLE.toLong(),
            arg,
            0L,
            0L,
            0L,
        )

    if (result == -1L) {
        val errNum = errno
        perror("prctl(PR_SET_DUMPABLE)")
        throw Exception("Failed to set dumpable: errno=$errNum")
    }

    Logger.debug("successfully set dumpable to $dumpable")
}

/**
 * Get the dumpable attribute for the calling process
 *
 * @return true if process is dumpable, false otherwise
 */
@OptIn(ExperimentalForeignApi::class)
fun getDumpable(): Boolean {
    val result =
        syscall(
            __NR_prctl.toLong(),
            PR_GET_DUMPABLE.toLong(),
            0L,
            0L,
            0L,
            0L,
        )

    if (result == -1L) {
        val errNum = errno
        perror("prctl(PR_GET_DUMPABLE)")
        throw Exception("Failed to get dumpable: errno=$errNum")
    }

    return result == 1L
}

/**
 * Set the no_new_privs bit for the calling thread
 *
 * Once set, this bit cannot be unset. The setting of this bit is inherited by
 * children created with fork(2) and clone(2), and preserved across execve(2).
 *
 * With no_new_privs set, execve(2) promises not to grant privileges to do
 * anything that could not have been done without the execve(2) call (for
 * example, rendering the set-user-ID and set-group-ID mode bits, and file
 * capabilities non-functional).
 *
 * This is required for unprivileged processes to use seccomp filters.
 *
 * Note: This function does not throw an exception on failure. If the operation fails, a
 * warning is logged but execution continues.
 */
@OptIn(ExperimentalForeignApi::class)
fun setNoNewPrivileges() {
    Logger.debug("setting no_new_privileges")

    val result =
        syscall(
            __NR_prctl.toLong(),
            PR_SET_NO_NEW_PRIVS.toLong(),
            1L, // arg2: 1 to set no_new_privs
            0L, // arg3: unused
            0L, // arg4: unused
            0L, // arg5: unused
        )

    if (result == -1L) {
        val errNum = errno
        perror("prctl(PR_SET_NO_NEW_PRIVS)")
        Logger.warn("failed to set no_new_privileges (errno=$errNum)")
        // Do not throw exception
        // Seccomp can still work via the privileged path if needed
    } else {
        Logger.debug("successfully set no_new_privileges")
    }
}
