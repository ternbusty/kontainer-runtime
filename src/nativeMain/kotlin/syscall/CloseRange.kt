package syscall

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import logger.Logger
import platform.posix.*

/**
 * FD cleanup implementation using close_range(2) syscall with fallback
 */

// System call number for close_range (x86_64 Linux)
private const val SYS_close_range = 436L

// Flag to set FD_CLOEXEC on file descriptors instead of closing them immediately
// FDs will be automatically closed on execve
private const val CLOSE_RANGE_CLOEXEC = 4

// fcntl operations
private const val F_GETFD = 1
private const val F_SETFD = 2
private const val FD_CLOEXEC = 1

/**
 * Get list of open file descriptors by reading /proc/self/fd
 * Returns list of FD numbers, or empty list on error
 */
@OptIn(ExperimentalForeignApi::class)
private fun getOpenFds(): List<Int> {
    val procFdPath = "/proc/self/fd"
    val fds = mutableListOf<Int>()

    // Open /proc/self/fd directory
    val dir = opendir(procFdPath)
    if (dir == null) {
        Logger.warn("failed to open $procFdPath for FD enumeration")
        return emptyList()
    }

    try {
        // Read directory entries
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()

            // Skip "." and ".."
            if (name == "." || name == "..") {
                continue
            }

            // Parse FD number from filename
            name.toIntOrNull()?.let { fd ->
                fds.add(fd)
            }
        }
    } finally {
        closedir(dir)
    }

    return fds
}

/**
 * Emulate close_range by setting FD_CLOEXEC on all open FDs
 * This is a fallback for kernels that don't support close_range(2) or CLOSE_RANGE_CLOEXEC
 *
 * @param preserveFds Number of additional FDs to preserve (beyond stdin/stdout/stderr)
 */
@OptIn(ExperimentalForeignApi::class)
private fun emulateCloseRange(preserveFds: Int = 0) {
    val minFd = 3 + preserveFds

    Logger.debug("emulating close_range by setting CLOEXEC on FDs >= $minFd")

    val openFds = getOpenFds()
    if (openFds.isEmpty()) {
        Logger.warn("failed to enumerate open FDs, cannot emulate close_range")
        return
    }

    val fdsToClose = openFds.filter { it >= minFd }
    var successCount = 0
    var failCount = 0

    for (fd in fdsToClose) {
        // Get current flags
        val currentFlags = fcntl(fd, F_GETFD)
        if (currentFlags == -1) {
            // FD might have been closed already, or doesn't support fcntl
            failCount++
            continue
        }

        // Set FD_CLOEXEC flag
        if (fcntl(fd, F_SETFD, currentFlags or FD_CLOEXEC) == -1) {
            Logger.warn("failed to set CLOEXEC on FD $fd")
            failCount++
        } else {
            successCount++
        }
    }

    Logger.debug("emulated close_range: set CLOEXEC on $successCount FDs, $failCount failed")
}

/**
 * Close (or mark as CLOEXEC) all file descriptors >= 3 + preserveFds
 *
 * This sets FD_CLOEXEC flag on file descriptors, so they will be automatically
 * closed when execve is called. This approach allows us to keep the channel pipes
 * open until we receive the start signal, then clean them up during exec.
 *
 * Falls back to manual FD enumeration on kernels that don't support close_range(2).
 * This is important for security (CVE-2024-21626 mitigation).
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
        val errNum = errno
        // ENOSYS = syscall not available (old kernel < 5.9)
        // EINVAL = flag not supported (old kernel < 5.11 doesn't support CLOSE_RANGE_CLOEXEC)
        if (errNum == ENOSYS || errNum == EINVAL) {
            Logger.debug("close_range not supported (errno=$errNum), using fallback")
            emulateCloseRange(preserveFds)
        } else {
            // Other errors
            perror("close_range")
            Logger.warn("close_range failed with errno=$errNum, trying fallback")
            emulateCloseRange(preserveFds)
        }
    } else {
        Logger.debug("successfully set CLOEXEC on FDs >= $minFd using close_range")
    }
}
