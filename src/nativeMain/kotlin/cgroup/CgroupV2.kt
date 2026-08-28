package cgroup

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import spec.LinuxResources
import utils.FileSystem
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * Convert cgroup v1 CPU shares [2..262144] to cgroup v2 CPU weight [1..10000]
 * using a non-linear quadratic mapping that fits min (2→1), default (1024→100),
 * and max (262144→10000). Matches runc's ConvertCPUSharesToCgroupV2Value.
 */
fun convertCpuSharesV2(shares: Long): Long {
    if (shares == 0L) return 0L
    if (shares <= 2L) return 1L
    if (shares >= 262144L) return 10000L
    val l = ln(shares.toDouble()) / ln(2.0)
    val exponent = (l * l + 125.0 * l) / 612.0 - 7.0 / 34.0
    return ceil(10.0.pow(exponent)).toLong()
}

/**
 * cgroup v2 implementation backed by cgroupfs writes via [FileSystem].
 */
@OptIn(ExperimentalForeignApi::class)
class CgroupV2(
    private val fs: FileSystem,
) : Cgroup {
    override fun setup(
        pid: Int?,
        cgroupPath: String?,
        resources: LinuxResources?,
        deferPids: Boolean,
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
            val normalizedPath = cgroupPath?.removePrefix("/") ?: "kontainer-runtime/kontainer-${pid ?: 0}"
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
            //
            // Even when the spec sets no explicit resource limits, controllers must
            // be propagated so that per-controller knobs (pids.max, memory.max, …)
            // exist in the leaf cgroup — the OCI integration tests verify their
            // presence. We read the available controllers from each ancestor's own
            // cgroup.controllers and enable all of them in its subtree_control.
            // Controllers explicitly required by spec resources are added on top.
            val requiredControllers = getRequiredControllers(resources)
            val segments = normalizedPath.split("/").filter { it.isNotEmpty() }
            if (segments.isNotEmpty()) {
                val ancestorPaths = mutableListOf(CGROUP_ROOT)
                for (i in 0 until segments.size - 1) {
                    ancestorPaths.add("${ancestorPaths.last()}/${segments[i]}")
                }

                for (ancestorPath in ancestorPaths) {
                    val controllersToEnable = getAvailableControllers(ancestorPath, requiredControllers)
                    val subtreeControlPath = "$ancestorPath/$CGROUP_SUBTREE_CONTROL"
                    for (controller in controllersToEnable) {
                        try {
                            fs.writeTextFile(subtreeControlPath, "+$controller")
                            Logger.debug("enabled $controller controller in $ancestorPath")
                        } catch (e: Exception) {
                            if (controller in requiredControllers) {
                                Logger.error("failed to enable $controller controller in $ancestorPath: ${e.message}")
                                throw Exception("Failed to enable required cgroup controller: $controller in $ancestorPath", e)
                            }
                            Logger.debug("could not enable optional controller $controller in $ancestorPath: ${e.message}")
                        }
                    }
                }
            }

            // Reject if the cgroup already has processes (runc compat: error for
            // a non-empty cgroup to avoid resource conflicts).
            try {
                val procs = fs.readTextFile("$fullPath/$CGROUP_PROCS").trim()
                if (procs.isNotEmpty()) {
                    throw Exception("container's cgroup is not empty: $fullPath")
                }
            } catch (e: Exception) {
                if (e.message?.contains("not empty") == true) throw e
                // File might not exist yet (freshly created) — OK.
            }

            // Reject if the cgroup is frozen (runc compat: refuse pre-existing
            // frozen cgroup to avoid containers silently starting in a frozen
            // state).
            try {
                val freeze = fs.readTextFile("$fullPath/cgroup.freeze").trim()
                if (freeze == "1") {
                    throw Exception("container's cgroup unexpectedly frozen")
                }
            } catch (e: Exception) {
                if (e.message?.contains("unexpectedly frozen") == true) throw e
                // cgroup.freeze may not exist (no freezer controller) — OK.
            }

            if (pid != null) {
                val procsPath = "$fullPath/$CGROUP_PROCS"
                try {
                    fs.writeTextFile(procsPath, pid.toString())
                    Logger.debug("added PID $pid to cgroup")
                } catch (e: Exception) {
                    Logger.error("failed to add PID to cgroup: ${e.message}")
                    throw Exception("Failed to add PID to cgroup", e)
                }
            }

            if (resources != null) {
                applyResources(fullPath, resources, deferPids = deferPids)
            }
        }
    }

    override fun addProcess(
        pid: Int,
        cgroupPath: String,
    ) {
        val normalizedPath = cgroupPath.removePrefix("/")
        val procsPath = "$CGROUP_ROOT/$normalizedPath/$CGROUP_PROCS"

        try {
            fs.writeTextFile(procsPath, pid.toString())
        } catch (e: Exception) {
            // Include the root-cause text (e.g. "no such file or directory") so
            // that callers printing only `message` get the full picture.
            val reason = e.message?.substringAfterLast(": ") ?: "unknown error"
            throw Exception("adding pid $pid to $procsPath: $reason", e)
        }
        Logger.debug("added PID $pid to cgroup $normalizedPath")
    }

    /**
     * Apply the pids.max limit that was deferred during [setup] with
     * `deferPids = true`. Call AFTER the init process has completed its
     * setup (received InitReady) so that Kotlin/Native runtime threads
     * created during init don't hit the limit.
     */
    fun applyDeferredPids(
        cgroupPath: String,
        resources: LinuxResources?,
    ) {
        resources?.pids?.let { pids ->
            val normalizedPath = cgroupPath.removePrefix("/")
            val fullPath = "$CGROUP_ROOT/$normalizedPath"
            applyPidsLimit(fullPath, pids.limit)
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

        // Kill any remaining processes in the cgroup tree before removing it.
        // In cgroup v2, processes in subcgroups are NOT visible in the parent's
        // cgroup.procs — they only appear in the subcgroup's own cgroup.procs.
        // We must walk all subcgroups and kill their processes too.
        killProcessesRecursive(fullPath)

        // Recursively remove subcgroups (bottom-up), then the leaf.
        // cgroupfs only allows rmdir on empty cgroups, so children first.
        removeCgroupRecursive(fullPath)
    }

    /**
     * Kill all processes in a cgroup directory and all of its sub-cgroups,
     * walking depth-first. Uses cgroup.procs for domain cgroups and
     * cgroup.threads for threaded cgroups.
     */
    private fun killProcessesRecursive(path: String) {
        // Kill processes in subcgroups first (depth-first)
        val children = fs.listDirectories(path)
        for (child in children) {
            killProcessesRecursive("$path/$child")
        }

        // Kill processes/threads in this cgroup
        for (procsFile in listOf(CGROUP_PROCS, "cgroup.threads")) {
            try {
                val content = fs.readProcFile("$path/$procsFile").trim()
                if (content.isNotEmpty()) {
                    for (line in content.lines()) {
                        val pid = line.trim().toIntOrNull() ?: continue
                        platform.posix.kill(pid, platform.posix.SIGKILL)
                    }
                }
            } catch (_: Exception) {
                // Best-effort: file may not exist (e.g. cgroup.threads
                // in a non-threaded cgroup) or may already be empty.
            }
        }
    }

    /**
     * Recursively remove a cgroup directory and all its sub-cgroups.
     * Walks depth-first (children before parent) because cgroupfs rejects
     * rmdir on a non-empty cgroup.
     *
     * After children are removed, retries rmdir with exponential backoff
     * because the kernel may still be cleaning up SIGKILL'd processes —
     * rmdir returns EBUSY until every process has fully exited.  This
     * matches runc's `cgroups/utils.go` `rmdir(path, retry=true)` pattern:
     * 1 ms initial delay, doubling each attempt, up to 10 retries (~1 s).
     */
    private fun removeCgroupRecursive(path: String) {
        val children = fs.listDirectories(path)
        for (child in children) {
            removeCgroupRecursive("$path/$child")
        }

        if (fs.removeDirectory(path)) return

        // Retry with exponential backoff on failure (typically EBUSY).
        var delayUs: UInt = 1_000u // 1 ms in microseconds
        for (attempt in 0 until 10) {
            usleep(delayUs)
            if (fs.removeDirectory(path)) return
            delayUs = delayUs * 2u
        }
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
        deferPids: Boolean = false,
    ) {
        resources.memory?.let { memory ->
            applyMemoryLimits(cgroupPath, memory.limit, memory.reservation, memory.swap)
        }
        resources.cpu?.let { cpu ->
            applyCpuLimits(cgroupPath, cpu.shares, cpu.quota, cpu.period)
            cpu.burst?.let { burst ->
                if (burst >= 0) {
                    writeCgroupFile("$cgroupPath/cpu.max.burst", burst.toString(), "cpu.max.burst")
                }
            }
            cpu.idle?.let { idle ->
                writeCgroupFile("$cgroupPath/cpu.idle", idle.toString(), "cpu.idle")
            }
        }
        if (!deferPids) {
            resources.pids?.let { pids ->
                applyPidsLimit(cgroupPath, pids.limit)
            }
        }
        resources.hugepageLimits?.forEach { hp ->
            applyHugepageLimit(cgroupPath, hp.pageSize, hp.limit)
        }
        resources.cpu?.let { cpu ->
            cpu.cpus?.let { cpus ->
                writeCgroupFile("$cgroupPath/cpuset.cpus", cpus, "cpuset.cpus")
            }
            cpu.mems?.let { mems ->
                writeCgroupFile("$cgroupPath/cpuset.mems", mems, "cpuset.mems")
            }
        }
        resources.unified?.forEach { (key, value) ->
            // Multi-line values: write each line separately (kernel cgroup
            // interface processes one line per write() call).
            val lines = value.split('\n').filter { it.isNotBlank() }
            for (line in lines) {
                writeCgroupFile("$cgroupPath/$key", line, key)
            }
        }
        // Block IO (v1 blockIO → v2 io.max conversion)
        resources.blockIO?.let { bio ->
            bio.weight?.let { w ->
                if (w > 0) {
                    writeCgroupFile("$cgroupPath/io.weight", w.toString(), "io.weight")
                }
            }
            val deviceMap = mutableMapOf<String, MutableMap<String, String>>()
            bio.throttleReadBpsDevice?.forEach { d ->
                val k = "${d.major}:${d.minor}"
                deviceMap.getOrPut(k) { mutableMapOf() }["rbps"] = d.rate.toString()
            }
            bio.throttleWriteBpsDevice?.forEach { d ->
                val k = "${d.major}:${d.minor}"
                deviceMap.getOrPut(k) { mutableMapOf() }["wbps"] = d.rate.toString()
            }
            bio.throttleReadIOPSDevice?.forEach { d ->
                val k = "${d.major}:${d.minor}"
                deviceMap.getOrPut(k) { mutableMapOf() }["riops"] = d.rate.toString()
            }
            bio.throttleWriteIOPSDevice?.forEach { d ->
                val k = "${d.major}:${d.minor}"
                deviceMap.getOrPut(k) { mutableMapOf() }["wiops"] = d.rate.toString()
            }
            for ((dev, limits) in deviceMap) {
                val parts = limits.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
                writeCgroupFile("$cgroupPath/io.max", "$dev $parts", "io.max")
            }
        }
    }

    private fun applyPidsLimit(
        cgroupPath: String,
        limit: Long?,
    ) {
        if (limit == null) return
        val value =
            when {
                limit < 0 -> "max" // -1 = unlimited
                limit == 0L -> "1" // runc compat: 0 → 1 (TasksMax=0 invalid)
                else -> limit.toString()
            }
        writeCgroupFile("$cgroupPath/$PIDS_MAX", value, "pids.max")
    }

    private fun applyHugepageLimit(
        cgroupPath: String,
        pageSize: String,
        limit: Long,
    ) {
        // cgroup v2 file name: hugetlb.<size>.max (e.g. hugetlb.2MB.max).
        val fileName = "hugetlb.$pageSize.max"
        val value = if (limit <= 0) "max" else limit.toString()
        writeCgroupFile("$cgroupPath/$fileName", value, fileName)
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
        // Convert cgroup v1 shares to cgroup v2 weight using a quadratic
        // function that maps [2..262144] → [1..10000], matching the
        // opencontainers/cgroups ConvertCPUSharesToCgroupV2Value.
        shares?.let {
            val weight = convertCpuSharesV2(it)
            if (weight != 0L) {
                val cpuWeightPath = "$cgroupPath/$CPU_WEIGHT"
                writeCgroupFile(cpuWeightPath, weight.toString(), "cpu.weight")
            }
        }

        // Validate CPU period. The kernel requires 1000 <= period <= 1000000.
        // Reject out-of-range values early (matching runc) rather than letting
        // the kernel EINVAL propagate as a cryptic cgroup write failure.
        if (period != null && (period < 1000 || period > 1000000)) {
            throw IllegalArgumentException(
                "invalid cpu.cfs_period_us value $period: must be between 1000 and 1000000",
            )
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

    /**
     * Read the controllers available to [cgroupPath] by parsing its
     * `cgroup.controllers` file and merging with [requiredControllers].
     * Returns a de-duplicated list of controllers to enable in
     * `cgroup.subtree_control` of this directory. If the controllers file
     * cannot be read (e.g. the directory was just created by us and the
     * parent doesn't delegate), falls back to [requiredControllers] only.
     */
    private fun getAvailableControllers(
        cgroupPath: String,
        requiredControllers: List<String>,
    ): List<String> {
        val available =
            try {
                val content = fs.readProcFile("$cgroupPath/$CGROUP_CONTROLLERS").trim()
                if (content.isNotEmpty()) {
                    content.split(" ").filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

        // Merge available with required, de-duplicate, preserving order.
        val merged = LinkedHashSet<String>()
        merged.addAll(available)
        merged.addAll(requiredControllers)
        return merged.toList()
    }

    private fun getRequiredControllers(resources: LinuxResources?): List<String> {
        if (resources == null) {
            return emptyList()
        }
        val controllers = mutableListOf<String>()
        if (resources.memory != null) controllers.add("memory")
        if (resources.cpu != null) controllers.add("cpu")
        if (resources.pids != null) controllers.add("pids")
        if (!resources.hugepageLimits.isNullOrEmpty()) controllers.add("hugetlb")
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
        private const val CGROUP_CONTROLLERS = "cgroup.controllers"
        private const val CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control"
        private const val MEMORY_MAX = "memory.max"
        private const val MEMORY_LOW = "memory.low"
        private const val MEMORY_SWAP_MAX = "memory.swap.max"
        private const val CPU_WEIGHT = "cpu.weight"
        private const val CPU_MAX = "cpu.max"
        private const val PIDS_MAX = "pids.max"
    }
}
