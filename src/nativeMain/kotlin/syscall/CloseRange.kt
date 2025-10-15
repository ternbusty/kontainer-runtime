package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.perror
import platform.posix.syscall

/**
 * FD cleanup implementation using close_range(2) syscall
 */

// System call number for close_range (x86_64 Linux)
private const val SYS_close_range = 436L

// Flag to set FD_CLOEXEC on file descriptors instead of closing them immediately
// FDs will be automatically closed on execve
private const val CLOSE_RANGE_CLOEXEC = 4

/**
 * Close (or mark as CLOEXEC) all file descriptors >= 3 + preserveFds
 *
 * This sets FD_CLOEXEC flag on file descriptors, so they will be automatically
 * closed when execve is called. This approach allows us to keep the channel pipes
 * open until we receive the start signal, then clean them up during exec.
 *
 * @param preserveFds Number of additional FDs to preserve (beyond stdin/stdout/stderr)
 *                    Used for systemd socket activation (LISTEN_FDS)
 */
@OptIn(ExperimentalForeignApi::class)
fun closeRange(preserveFds: Int = 0) {
    val minFd = 3 + preserveFds  // stdin=0, stdout=1, stderr=2, then preserve_fds
    val maxFd = Int.MAX_VALUE

    Logger.debug("setting CLOEXEC on FDs >= $minFd")

    val result = syscall(
        SYS_close_range,
        minFd.toLong(),
        maxFd.toLong(),
        CLOSE_RANGE_CLOEXEC.toLong()
    )

    if (result == -1L) {
        perror("close_range")
        Logger.warn("close_range syscall failed, FDs may leak")
        // Don't throw exception - this is a security enhancement, not critical for basic operation
        // Older kernels (< 5.11) don't support CLOSE_RANGE_CLOEXEC flag
    } else {
        Logger.debug("successfully set CLOEXEC on FDs >= $minFd")
    }
}
