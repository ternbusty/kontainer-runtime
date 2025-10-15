package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.*

/**
 * prctl(2) system call wrappers
 *
 * See https://man7.org/linux/man-pages/man2/prctl.2.html
 */

// System call number for prctl (all architectures)
private const val SYS_prctl = 157L

// prctl operations
private const val PR_SET_NO_NEW_PRIVS = 38

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
 */
@OptIn(ExperimentalForeignApi::class)
fun setNoNewPrivileges() {
    Logger.debug("setting no_new_privileges")

    val result = syscall(
        SYS_prctl,
        PR_SET_NO_NEW_PRIVS.toLong(),
        1L,  // arg2: 1 to set no_new_privs
        0L,  // arg3: unused
        0L,  // arg4: unused
        0L   // arg5: unused
    )

    if (result == -1L) {
        perror("prctl(PR_SET_NO_NEW_PRIVS)")
        Logger.warn("failed to set no_new_privileges")
        // Don't throw exception - this is a security enhancement
    } else {
        Logger.debug("successfully set no_new_privileges")
    }
}
