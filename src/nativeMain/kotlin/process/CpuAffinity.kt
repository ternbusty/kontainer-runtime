package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*

/**
 * CPU affinity helpers using sched_setaffinity(2).
 *
 * Used in two contexts:
 * 1. Container create: after adding the init PID to the cgroup, reset its
 *    affinity to all CPUs so the kernel clamps it to cpuset.cpus.
 * 2. Container exec: apply initial/final affinity from process.execCPUAffinity.
 */

/** Size of the CPU mask in bytes — 128 bytes = 1024 CPUs. */
private const val CPU_MASK_SIZE = 128

/**
 * Reset the CPU affinity of [pid] to all CPUs (all-ones mask).
 * The kernel automatically clamps this to the process's cgroup cpuset.cpus,
 * so the effective result is "inherit the cgroup's cpuset".
 *
 * Matches runc's tryResetCPUAffinity.
 */
@OptIn(ExperimentalForeignApi::class)
fun resetCpuAffinity(pid: Int) {
    memScoped {
        val mask = allocArray<ByteVar>(CPU_MASK_SIZE)
        // Fill with all-ones
        for (i in 0 until CPU_MASK_SIZE) {
            mask[i] = 0xFF.toByte()
        }
        val rc =
            syscall(
                _NR_sched_setaffinity(),
                pid.toLong(),
                CPU_MASK_SIZE.toLong(),
                mask.toLong(),
            )
        if (rc != 0L) {
            // Best-effort: log but don't throw (matches runc behavior)
            val err = strerror(errno)?.toKString() ?: "unknown"
            Logger.warn("sched_setaffinity (reset) failed for pid=$pid: $err")
        } else {
            Logger.debug("reset CPU affinity for pid=$pid to cgroup cpuset")
        }
    }
}

/**
 * Set the CPU affinity of [pid] to the specific CPUs listed in [cpuList].
 * The list format is a Linux CPU list string like "0-3,7".
 */
@OptIn(ExperimentalForeignApi::class)
fun setCpuAffinity(
    pid: Int,
    cpuList: String,
) {
    val mask = parseCpuList(cpuList)
    memScoped {
        val seg = allocArray<ByteVar>(CPU_MASK_SIZE)
        for (i in 0 until CPU_MASK_SIZE) seg[i] = 0
        // Write the mask as little-endian longs
        for (i in mask.indices) {
            val longOffset = i * 8
            for (b in 0 until 8) {
                if (longOffset + b < CPU_MASK_SIZE) {
                    seg[longOffset + b] = ((mask[i] ushr (b * 8)) and 0xFF).toByte()
                }
            }
        }
        val rc =
            syscall(
                _NR_sched_setaffinity(),
                pid.toLong(),
                CPU_MASK_SIZE.toLong(),
                seg.toLong(),
            )
        if (rc != 0L) {
            val err = strerror(errno)?.toKString() ?: "unknown"
            Logger.warn("sched_setaffinity failed for pid=$pid cpuList=$cpuList: $err")
        } else {
            Logger.debug("set CPU affinity for pid=$pid to $cpuList")
        }
    }
}

/**
 * Parse a Linux CPU list string (e.g. "0-3,5,7") into a bitmask
 * represented as a LongArray. Each Long covers 64 CPUs.
 */
internal fun parseCpuList(list: String): LongArray {
    val mask = LongArray(CPU_MASK_SIZE / 8) // 16 longs = 1024 bits
    for (part in list.split(",")) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        val dash = trimmed.indexOf('-')
        if (dash >= 0) {
            val lo = trimmed.substring(0, dash).trim().toIntOrNull() ?: continue
            val hi = trimmed.substring(dash + 1).trim().toIntOrNull() ?: continue
            for (i in lo..minOf(hi, mask.size * 64 - 1)) {
                mask[i / 64] = mask[i / 64] or (1L shl (i % 64))
            }
        } else {
            val cpu = trimmed.toIntOrNull() ?: continue
            if (cpu < mask.size * 64) {
                mask[cpu / 64] = mask[cpu / 64] or (1L shl (cpu % 64))
            }
        }
    }
    return mask
}
