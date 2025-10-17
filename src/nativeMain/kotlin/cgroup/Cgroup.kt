package cgroup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import platform.posix.F_OK
import platform.posix.access
import platform.posix.mkdir
import platform.posix.perror
import spec.LinuxResources
import utils.writeText

/**
 * Cgroup v2 management
 *
 * Provides basic cgroup v2 support for resource limitation
 */

private const val CGROUP_ROOT = "/sys/fs/cgroup"
private const val CGROUP_PROCS = "cgroup.procs"
private const val CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control"
private const val MEMORY_MAX = "memory.max"
private const val MEMORY_LOW = "memory.low"
private const val MEMORY_SWAP_MAX = "memory.swap.max"
private const val CPU_WEIGHT = "cpu.weight"
private const val CPU_MAX = "cpu.max"

/**
 * Setup cgroup for a process
 *
 * Creates cgroup directory structure, enables controllers, and applies resource limits
 *
 * @param pid Process ID to add to cgroup
 * @param cgroupPath Relative path from /sys/fs/cgroup (e.g., "mycontainer")
 * @param resources Resource limits to apply
 */
@OptIn(ExperimentalForeignApi::class)
fun setupCgroup(pid: Int, cgroupPath: String?, resources: LinuxResources?) {
    // Skip if no cgroup path or resources specified
    if (cgroupPath == null && resources == null) {
        return
    }

    memScoped {
        val path = cgroupPath ?: "kontainer-${pid}"
        val fullPath = "$CGROUP_ROOT/$path"

        Logger.debug("setting up cgroup at $fullPath")

        // Create cgroup directory
        if (access(fullPath, F_OK) != 0) {
            if (mkdir(fullPath, 0x1EDu) != 0) {  // 0x1ED = 0755 octal
                perror("mkdir cgroup")
                Logger.warn("failed to create cgroup directory")
                return@memScoped
            }
            Logger.debug("created cgroup directory: $fullPath")
        }

        // Enable controllers in subtree_control
        // Only enable controllers that are actually needed based on resources
        val requiredControllers = getRequiredControllers(resources)
        if (requiredControllers.isNotEmpty()) {
            val subtreeControlPath = "$CGROUP_ROOT/$CGROUP_SUBTREE_CONTROL"

            for (controller in requiredControllers) {
                try {
                    writeText(subtreeControlPath, "+$controller")
                    Logger.debug("enabled $controller controller")
                } catch (e: Exception) {
                    Logger.error("failed to enable $controller controller: ${e.message}")
                    throw Exception("Failed to enable required cgroup controller: $controller", e)
                }
            }
        }

        // Add process to cgroup
        val procsPath = "$fullPath/$CGROUP_PROCS"
        try {
            writeText(procsPath, pid.toString())
            Logger.debug("added PID $pid to cgroup")
        } catch (e: Exception) {
            Logger.error("failed to add PID to cgroup: ${e.message}")
            throw Exception("Failed to add PID to cgroup", e)
        }

        // Apply resource limits if specified
        if (resources != null) {
            applyResources(fullPath, resources)
        }
    }
}

/**
 * Apply resource limits to cgroup
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyResources(cgroupPath: String, resources: LinuxResources) {
    // Apply memory limits
    resources.memory?.let { memory ->
        applyMemoryLimits(cgroupPath, memory.limit, memory.reservation, memory.swap)
    }

    // Apply CPU limits
    resources.cpu?.let { cpu ->
        applyCpuLimits(cgroupPath, cpu.shares, cpu.quota, cpu.period)
    }
}

/**
 * Apply memory resource limits
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyMemoryLimits(
    cgroupPath: String,
    limit: Long?,
    reservation: Long?,
    swap: Long?
) {
    // Set memory.max (memory limit)
    limit?.let {
        val memoryMaxPath = "$cgroupPath/$MEMORY_MAX"
        val value = if (it == -1L) "max" else it.toString()
        writeCgroupFile(memoryMaxPath, value, "memory.max")
    }

    // Set memory.low (memory reservation / soft limit)
    reservation?.let {
        val memoryLowPath = "$cgroupPath/$MEMORY_LOW"
        val value = if (it == -1L) "max" else it.toString()
        writeCgroupFile(memoryLowPath, value, "memory.low")
    }

    // Set memory.swap.max (swap limit)
    swap?.let { swapValue ->
        limit?.let { limitValue ->
            val memorySwapPath = "$cgroupPath/$MEMORY_SWAP_MAX"
            // In cgroup v2, swap is separate from memory (unlike v1 where swap was memory+swap)
            // So if swap=2048 and limit=1024, we write 2048-1024=1024 to memory.swap.max
            val value = when {
                swapValue == -1L || limitValue == -1L -> "max"
                else -> (swapValue - limitValue).toString()
            }
            writeCgroupFile(memorySwapPath, value, "memory.swap.max")
        }
    }
}

/**
 * Apply CPU resource limits
 */
@OptIn(ExperimentalForeignApi::class)
private fun applyCpuLimits(
    cgroupPath: String,
    shares: Long?,
    quota: Long?,
    period: Long?
) {
    // Convert shares to cpu.weight (cgroup v1 shares -> cgroup v2 weight conversion)
    // Formula: weight = 1 + ((shares - 2) * 9999) / 262142
    shares?.let {
        if (it > 0) {
            val weight = if (it == 0L) {
                0L
            } else {
                val w = 1L + ((it - 2) * 9999 / 262142)
                minOf(w, 10000L)  // MAX_CPU_WEIGHT = 10000
            }
            if (weight != 0L) {
                val cpuWeightPath = "$cgroupPath/$CPU_WEIGHT"
                writeCgroupFile(cpuWeightPath, weight.toString(), "cpu.weight")
            }
        }
    }

    // Set cpu.max (format: "quota period")
    if (quota != null || period != null) {
        val cpuMaxPath = "$cgroupPath/$CPU_MAX"
        val quotaStr = when {
            quota == null -> null
            quota <= 0 -> "max"
            else -> quota.toString()
        }
        val periodStr = period?.toString()

        val value = when {
            quotaStr != null && periodStr != null -> "$quotaStr $periodStr"
            quotaStr != null -> quotaStr
            periodStr != null -> "max $periodStr"
            else -> null
        }

        value?.let {
            writeCgroupFile(cpuMaxPath, it, "cpu.max")
        }
    }
}

/**
 * Write value to a cgroup file
 */
private fun writeCgroupFile(path: String, value: String, name: String) {
    try {
        writeText(path, value)
        Logger.debug("set $name = $value")
    } catch (e: Exception) {
        Logger.warn("failed to write $name: ${e.message}")
        // Note: This is warn-only for resource limits (Priority 2 issue)
    }
}

/**
 * Determine which controllers are required based on resource configuration
 */
private fun getRequiredControllers(resources: LinuxResources?): List<String> {
    if (resources == null) {
        return emptyList()
    }

    val controllers = mutableListOf<String>()

    // Memory controller needed if any memory limit is specified
    if (resources.memory != null) {
        controllers.add("memory")
    }

    // CPU controller needed if any CPU limit is specified
    if (resources.cpu != null) {
        controllers.add("cpu")
    }

    return controllers
}

/**
 * Cleanup cgroup for a container
 *
 * Removes the cgroup directory for the container.
 * Errors are logged as warnings and do not fail the operation.
 *
 * @param cgroupPath Relative path from /sys/fs/cgroup
 */
@OptIn(ExperimentalForeignApi::class)
fun cleanupCgroup(cgroupPath: String?) {
    if (cgroupPath == null) {
        Logger.debug("no cgroup path specified, skipping cleanup")
        return
    }

    val fullPath = "$CGROUP_ROOT/$cgroupPath"
    Logger.debug("cleaning up cgroup at $fullPath")

    // Check if directory exists
    if (access(fullPath, F_OK) != 0) {
        Logger.debug("cgroup directory $fullPath does not exist, already cleaned up")
        return
    }

    // Remove cgroup directory using rmdir
    // Note: rmdir only works if the cgroup is empty (no processes)
    val result = platform.posix.rmdir(fullPath)
    if (result != 0) {
        val errNum = platform.posix.errno
        // Warn but don't fail - cgroup cleanup is best-effort
        Logger.warn("failed to remove cgroup directory $fullPath: errno=$errNum")
    } else {
        Logger.debug("removed cgroup directory: $fullPath")
    }
}
