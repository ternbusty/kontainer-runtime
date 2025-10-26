package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.errno
import platform.posix.strerror
import spec.POSIXRlimit

/**
 * Map OCI spec rlimit type to POSIX resource constant
 */
@OptIn(ExperimentalForeignApi::class)
private fun rlimitTypeToResource(type: String): Int? =
    when (type) {
        "RLIMIT_AS" -> RLIMIT_AS
        "RLIMIT_CORE" -> RLIMIT_CORE
        "RLIMIT_CPU" -> RLIMIT_CPU
        "RLIMIT_DATA" -> RLIMIT_DATA
        "RLIMIT_FSIZE" -> RLIMIT_FSIZE
        "RLIMIT_LOCKS" -> RLIMIT_LOCKS
        "RLIMIT_MEMLOCK" -> RLIMIT_MEMLOCK
        "RLIMIT_MSGQUEUE" -> RLIMIT_MSGQUEUE
        "RLIMIT_NICE" -> RLIMIT_NICE
        "RLIMIT_NOFILE" -> RLIMIT_NOFILE
        "RLIMIT_NPROC" -> RLIMIT_NPROC
        "RLIMIT_RSS" -> RLIMIT_RSS
        "RLIMIT_RTPRIO" -> RLIMIT_RTPRIO
        "RLIMIT_RTTIME" -> RLIMIT_RTTIME
        "RLIMIT_SIGPENDING" -> RLIMIT_SIGPENDING
        "RLIMIT_STACK" -> RLIMIT_STACK
        else -> {
            Logger.warn("unknown rlimit type: $type")
            null
        }
    }

/**
 * Apply rlimits to a process using prlimit()
 *
 * This function sets resource limits for the specified process.
 * Following runc's design, this is called by the parent process after receiving
 * the procReady signal from the init process, because we lose permissions
 * to raise limits once we enter a user namespace.
 *
 * @param pid Process ID to apply rlimits to
 * @param rlimits List of rlimits from OCI spec
 */
@OptIn(ExperimentalForeignApi::class)
fun applyRlimits(
    pid: Int,
    rlimits: List<POSIXRlimit>?,
) {
    if (rlimits.isNullOrEmpty()) {
        Logger.debug("no rlimits to apply")
        return
    }

    Logger.debug("applying ${rlimits.size} rlimits to PID $pid")

    memScoped {
        for (rlimit in rlimits) {
            val resource = rlimitTypeToResource(rlimit.type)
            if (resource == null) {
                Logger.warn("skipping unknown rlimit type: ${rlimit.type}")
                continue
            }

            val newLimit = alloc<rlimit>()
            newLimit.rlim_cur = rlimit.soft
            newLimit.rlim_max = rlimit.hard

            if (prlimit_wrapper(pid, resource, newLimit.ptr, null) != 0) {
                val errorMsg = strerror(errno)?.toKString() ?: "unknown error"
                Logger.warn("failed to set rlimit ${rlimit.type} (resource=$resource) for PID $pid: $errorMsg")
                // Continue applying other rlimits instead of failing
            } else {
                Logger.debug("set rlimit ${rlimit.type}: soft=${rlimit.soft}, hard=${rlimit.hard}")
            }
        }
    }

    Logger.info("rlimits applied successfully")
}
