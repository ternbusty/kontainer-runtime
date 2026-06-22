package cgroup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import spec.LinuxResources
import utils.FileSystem

/**
 * cgroup v2 implementation backed by cgroupfs writes via [FileSystem].
 */
@OptIn(ExperimentalForeignApi::class)
class CgroupV2(
    private val fs: FileSystem,
) : Cgroup {
    override fun setup(
        pid: Int,
        cgroupPath: String?,
        resources: LinuxResources?,
    ) {
        if (cgroupPath == null && resources == null) {
            return
        }

        memScoped {
            // cgroupPath is expected to be the FINAL relative-to-cgroup-root
            // path the runtime has already resolved (see resolveCgroupPath()
            // in this file). If the caller passes null we fall back to a PID-
            // suffixed leaf under our runtime's subtree — this is mainly for
            // tests that don't go through MainProcess's resolver.
            val normalizedPath = cgroupPath?.removePrefix("/") ?: "kontainer-runtime/kontainer-$pid"
            val fullPath = "$CGROUP_ROOT/$normalizedPath"

            Logger.debug("setting up cgroup at $fullPath")

            fs.createDirectories(fullPath, 0x1EDu) // 0o755
            Logger.debug("created cgroup directory: $fullPath")

            // Enable controllers at every ancestor of the leaf cgroup.
            // In cgroup v2 a controller is only available in a child cgroup if its
            // parent's cgroup.subtree_control contains +<controller>. For a nested
            // path like "default/test-verify" we must enable controllers in both
            // /sys/fs/cgroup/cgroup.subtree_control and
            // /sys/fs/cgroup/default/cgroup.subtree_control, otherwise opening
            // memory.max etc. in the leaf fails with EACCES.
            val requiredControllers = getRequiredControllers(resources)
            if (requiredControllers.isNotEmpty()) {
                val segments = normalizedPath.split("/").filter { it.isNotEmpty() }
                val ancestorPaths = mutableListOf(CGROUP_ROOT)
                for (i in 0 until segments.size - 1) {
                    ancestorPaths.add("${ancestorPaths.last()}/${segments[i]}")
                }

                for (ancestorPath in ancestorPaths) {
                    val subtreeControlPath = "$ancestorPath/$CGROUP_SUBTREE_CONTROL"
                    for (controller in requiredControllers) {
                        try {
                            fs.writeTextFile(subtreeControlPath, "+$controller")
                            Logger.debug("enabled $controller controller in $ancestorPath")
                        } catch (e: Exception) {
                            Logger.error("failed to enable $controller controller in $ancestorPath: ${e.message}")
                            throw Exception("Failed to enable required cgroup controller: $controller in $ancestorPath", e)
                        }
                    }
                }
            }

            val procsPath = "$fullPath/$CGROUP_PROCS"
            try {
                fs.writeTextFile(procsPath, pid.toString())
                Logger.debug("added PID $pid to cgroup")
            } catch (e: Exception) {
                Logger.error("failed to add PID to cgroup: ${e.message}")
                throw Exception("Failed to add PID to cgroup", e)
            }

            if (resources != null) {
                applyResources(fullPath, resources)
            }
        }
    }

    override fun cleanup(cgroupPath: String?) {
        if (cgroupPath == null) {
            Logger.debug("no cgroup path specified, skipping cleanup")
            return
        }

        val normalizedPath = cgroupPath.removePrefix("/")
        val fullPath = "$CGROUP_ROOT/$normalizedPath"
        Logger.debug("cleaning up cgroup at $fullPath")

        // removeDirectory returns false on missing directory or error; in either
        // case cleanup is best-effort and we already log inside the impl.
        fs.removeDirectory(fullPath)
    }

    override fun getPids(cgroupPath: String): List<Int> {
        val normalizedPath = cgroupPath.removePrefix("/")
        val fullPath = "$CGROUP_ROOT/$normalizedPath"
        val procsPath = "$fullPath/$CGROUP_PROCS"

        Logger.debug("reading PIDs from $procsPath")

        val content =
            try {
                fs.readProcFile(procsPath)
            } catch (e: Exception) {
                Logger.error("failed to read cgroup.procs: ${e.message}")
                throw Exception("Failed to read cgroup.procs file: ${e.message}")
            }

        val pids =
            content
                .trim()
                .split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    line.trim().toIntOrNull()?.also {
                        Logger.debug("found PID: $it")
                    }
                }

        Logger.debug("found ${pids.size} PIDs in cgroup")
        return pids
    }

    private fun applyResources(
        cgroupPath: String,
        resources: LinuxResources,
    ) {
        resources.memory?.let { memory ->
            applyMemoryLimits(cgroupPath, memory.limit, memory.reservation, memory.swap)
        }
        resources.cpu?.let { cpu ->
            applyCpuLimits(cgroupPath, cpu.shares, cpu.quota, cpu.period)
        }
    }

    private fun applyMemoryLimits(
        cgroupPath: String,
        limit: Long?,
        reservation: Long?,
        swap: Long?,
    ) {
        limit?.let {
            val memoryMaxPath = "$cgroupPath/$MEMORY_MAX"
            val value = if (it == -1L) "max" else it.toString()
            writeCgroupFile(memoryMaxPath, value, "memory.max")
        }

        reservation?.let {
            val memoryLowPath = "$cgroupPath/$MEMORY_LOW"
            val value = if (it == -1L) "max" else it.toString()
            writeCgroupFile(memoryLowPath, value, "memory.low")
        }

        // In cgroup v2 swap is separate from memory (unlike v1 where swap was
        // memory+swap), so swap.max receives swap minus the limit.
        swap?.let { swapValue ->
            limit?.let { limitValue ->
                val memorySwapPath = "$cgroupPath/$MEMORY_SWAP_MAX"
                val value =
                    when {
                        swapValue == -1L || limitValue == -1L -> "max"
                        else -> (swapValue - limitValue).toString()
                    }
                writeCgroupFile(memorySwapPath, value, "memory.swap.max")
            }
        }
    }

    private fun applyCpuLimits(
        cgroupPath: String,
        shares: Long?,
        quota: Long?,
        period: Long?,
    ) {
        // Convert cgroup v1 shares to cgroup v2 weight
        // Formula: weight = 1 + ((shares - 2) * 9999) / 262142
        shares?.let {
            if (it > 0) {
                val weight =
                    if (it == 0L) {
                        0L
                    } else {
                        val w = 1L + ((it - 2) * 9999 / 262142)
                        minOf(w, 10000L) // MAX_CPU_WEIGHT
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
            val quotaStr =
                when {
                    quota == null -> null
                    quota <= 0 -> "max"
                    else -> quota.toString()
                }
            val periodStr = period?.toString()

            val value =
                when {
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

    private fun writeCgroupFile(
        path: String,
        value: String,
        name: String,
    ) {
        try {
            fs.writeTextFile(path, value)
            Logger.debug("set $name = $value")
        } catch (e: Exception) {
            // Warn-only because resource limits are best-effort
            Logger.warn("failed to write $name: ${e.message}")
        }
    }

    private fun getRequiredControllers(resources: LinuxResources?): List<String> {
        if (resources == null) {
            return emptyList()
        }
        val controllers = mutableListOf<String>()
        if (resources.memory != null) controllers.add("memory")
        if (resources.cpu != null) controllers.add("cpu")
        return controllers
    }

    companion object {
        /** Subtree under /sys/fs/cgroup where this runtime nests its containers. */
        const val RUNTIME_CGROUP_PREFIX = "kontainer-runtime"

        /**
         * Resolve spec.linux.cgroupsPath into the final relative-to-cgroup-root
         * path the runtime will create, per OCI runtime-spec
         * config-linux.md#cgroupsPath:
         *   - absolute (leading `/`): MUST be relative to the cgroup mount; we
         *     strip the leading slash and use as-is.
         *   - relative (no leading `/`): the runtime MAY interpret it relative
         *     to a runtime-determined location, so we nest it under
         *     `kontainer-runtime/`.
         *   - null: runtime default — `kontainer-runtime/<container-id>`.
         */
        fun resolveCgroupPath(
            specPath: String?,
            containerId: String,
        ): String =
            when {
                specPath == null -> "$RUNTIME_CGROUP_PREFIX/$containerId"
                specPath.startsWith("/") -> specPath.removePrefix("/")
                else -> "$RUNTIME_CGROUP_PREFIX/$specPath"
            }

        private const val CGROUP_ROOT = "/sys/fs/cgroup"
        private const val CGROUP_PROCS = "cgroup.procs"
        private const val CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control"
        private const val MEMORY_MAX = "memory.max"
        private const val MEMORY_LOW = "memory.low"
        private const val MEMORY_SWAP_MAX = "memory.swap.max"
        private const val CPU_WEIGHT = "cpu.weight"
        private const val CPU_MAX = "cpu.max"
    }
}
