package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import spec.LinuxIOPriority

/**
 * I/O priority constants from linux/ioprio.h.
 *
 * IOPRIO_PRIO_VALUE(class, data) = (class << 13) | data
 */
private const val IOPRIO_WHO_PROCESS = 1

/**
 * Apply the OCI process.ioPriority to the current process.
 * Called before privilege drop (needs CAP_SYS_ADMIN for RT class).
 */
@OptIn(ExperimentalForeignApi::class)
fun applyIOPriority(ioPriority: LinuxIOPriority?) {
    if (ioPriority == null) return

    val value = (ioPriority.classValue() shl 13) or ioPriority.priority
    val rc = syscall(_NR_ioprio_set(), IOPRIO_WHO_PROCESS.toLong(), 0L, value.toLong())
    if (rc != 0L) {
        val err = strerror(errno)?.toKString() ?: "unknown"
        Logger.warn("ioprio_set failed: $err")
    } else {
        Logger.debug("ioprio_set class=${ioPriority.clazz} priority=${ioPriority.priority}")
    }
}
