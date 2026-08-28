@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package command

import config.loadKontainerConfig
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import logger.Logger
import platform.posix.exit
import spec.LinuxCpu
import spec.LinuxMemory
import spec.LinuxPids
import spec.LinuxResources
import utils.FileSystem
import utils.JsonCodec

/**
 * Update command — modify resource limits of a running (or paused) container.
 *
 * Re-applies cgroup resource limits to an existing container's cgroup
 * directory without restarting it. Accepts individual CLI flags
 * (`--memory`, `--cpu-quota`, etc.) and/or a `--resources` JSON file
 * that matches the spec.linux.resources schema.
 *
 * CLI flags override fields from the JSON file.
 */
fun update(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
    resourcesPath: String? = null,
    memory: Long? = null,
    memorySwap: Long? = null,
    memoryReservation: Long? = null,
    cpuQuota: Long? = null,
    cpuPeriod: Long? = null,
    cpuShares: Long? = null,
    cpuBurst: Long? = null,
    cpuIdle: Long? = null,
    cpusetCpus: String? = null,
    cpusetMems: String? = null,
    pidsLimit: Long? = null,
    blkioWeight: Long? = null,
) {
    val config =
        try {
            loadKontainerConfig(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("no configuration recorded for $containerId: ${e.message}")
            exit(1)
            return
        }

    val cgroupPath = config.cgroupPath
    if (cgroupPath == null) {
        Logger.error("container $containerId has no cgroupsPath, cannot update")
        exit(1)
        return
    }

    // Start with the resources file (if provided), then overlay CLI flags.
    var resources =
        if (resourcesPath != null) {
            try {
                if (resourcesPath == "-") {
                    // Read from stdin
                    val input =
                        buildString {
                            val buf = ByteArray(4096)
                            while (true) {
                                val n = readStdin(buf)
                                if (n <= 0) break
                                append(buf.decodeToString(0, n))
                            }
                        }
                    JsonCodec.decode<LinuxResources>(input)
                } else {
                    JsonCodec.loadFromFile<LinuxResources>(fs, resourcesPath)
                }
            } catch (e: Exception) {
                Logger.error("failed to read resources file $resourcesPath: ${e.message}")
                exit(1)
                return
            }
        } else {
            LinuxResources()
        }

    // Apply CLI flag overrides — these take precedence over the JSON file.
    if (memory != null || memorySwap != null || memoryReservation != null) {
        val mem = resources.memory ?: LinuxMemory()
        resources =
            resources.copy(
                memory =
                    mem.copy(
                        limit = memory ?: mem.limit,
                        swap = memorySwap ?: mem.swap,
                        reservation = memoryReservation ?: mem.reservation,
                    ),
            )
    }
    if (cpuQuota != null || cpuPeriod != null || cpuShares != null ||
        cpuBurst != null || cpuIdle != null
    ) {
        val cpu = resources.cpu ?: LinuxCpu()
        resources =
            resources.copy(
                cpu =
                    cpu.copy(
                        quota = cpuQuota ?: cpu.quota,
                        period = cpuPeriod ?: cpu.period,
                        shares = cpuShares ?: cpu.shares,
                        burst = cpuBurst ?: cpu.burst,
                        idle = cpuIdle ?: cpu.idle,
                    ),
            )
    }
    if (cpusetCpus != null || cpusetMems != null) {
        val cpu = resources.cpu ?: LinuxCpu()
        resources =
            resources.copy(
                cpu =
                    cpu.copy(
                        cpus = cpusetCpus ?: cpu.cpus,
                        mems = cpusetMems ?: cpu.mems,
                    ),
            )
    }
    if (pidsLimit != null) {
        resources = resources.copy(pids = (resources.pids ?: LinuxPids()).copy(limit = pidsLimit))
    }

    // Validate cpu.idle (must be 0 or 1)
    resources.cpu?.idle?.let { idle ->
        if (idle !in 0L..1L) {
            Logger.error("invalid value for cpu.idle: $idle (must be 0 or 1)")
            exit(1)
            return
        }
    }

    // Validate cpu period (kernel requires 1000 <= period <= 1000000)
    resources.cpu?.period?.let { period ->
        if (period < 1000 || period > 1000000) {
            Logger.error("invalid cpu.cfs_period_us value $period: must be between 1000 and 1000000")
            exit(1)
            return
        }
    }

    // checkBeforeUpdate — validate memory limits against current usage
    resources.memory?.let { mem ->
        if (mem.checkBeforeUpdate == true) {
            val normalizedPath = cgroupPath.removePrefix("/")
            val cgroupDir = "/sys/fs/cgroup/$normalizedPath"
            val currentUsage = readCgroupLong(fs, "$cgroupDir/memory.current")
            // Check memory+swap FIRST — when both are given the combined check
            // is the more specific rejection (runc compat).
            if (mem.swap != null && mem.swap > 0) {
                val swapUsage = readCgroupLong(fs, "$cgroupDir/memory.swap.current") ?: 0L
                val totalUsage = currentUsage?.let { it + swapUsage } ?: 0L
                if (mem.swap < totalUsage) {
                    Logger.error("rejecting memory+swap limit ${"${mem.swap}"} (current usage: $totalUsage)")
                    exit(1)
                    return
                }
            }
            if (mem.limit != null && mem.limit > 0 && currentUsage != null && mem.limit < currentUsage) {
                Logger.error("rejecting memory limit ${"${mem.limit}"} (current usage: $currentUsage)")
                exit(1)
                return
            }
        }
    }

    val normalizedPath = cgroupPath.removePrefix("/")
    val cgroupDir = "/sys/fs/cgroup/$normalizedPath"

    applyCgroupResources(fs, cgroupDir, resources)
    Logger.info("updated resources for $containerId")
}

/**
 * Read a cgroup value file as Long, returning null on failure.
 */
private fun readCgroupLong(
    fs: FileSystem,
    path: String,
): Long? =
    try {
        fs.readTextFile(path).trim().toLongOrNull()
    } catch (_: Exception) {
        null
    }

/**
 * Read from stdin into a byte array. Returns the number of bytes read.
 */
private fun readStdin(buf: ByteArray): Int {
    buf.usePinned { pinned ->
        val n = platform.posix.read(platform.posix.STDIN_FILENO, pinned.addressOf(0), buf.size.toULong())
        return n.toInt()
    }
}

/**
 * Write cgroup resource values directly to cgroupfs files.
 *
 * Reuses the same value-conversion logic as CgroupV2.applyResources
 * but operates on an already-existing cgroup directory. Best-effort:
 * failures are logged, not thrown.
 */
internal fun applyCgroupResources(
    fs: FileSystem,
    cgroupDir: String,
    resources: LinuxResources,
) {
    resources.memory?.let { mem ->
        mem.limit?.let { writeLimit(fs, "$cgroupDir/memory.max", it, "memory.max") }
        mem.reservation?.let { writeLimit(fs, "$cgroupDir/memory.low", it, "memory.low") }
        val swap = mem.swap
        if (swap != null) {
            // cgroup v2: memory.swap.max is swap-only (not memory+swap like v1).
            // If the caller gave us a v1-style combined limit, subtract memory.
            val memLimit = mem.limit
            val value =
                when {
                    swap == -1L -> "max"
                    memLimit != null && memLimit > 0 -> {
                        val delta = swap - memLimit
                        if (delta < 0) "0" else delta.toString()
                    }
                    else -> swap.toString()
                }
            writeCgroup(fs, "$cgroupDir/memory.swap.max", value, "memory.swap.max")
        } else if (mem.limit == -1L) {
            // When memory is set to unlimited and swap is not explicitly set,
            // also make swap unlimited (runc compat).
            writeCgroup(fs, "$cgroupDir/memory.swap.max", "max", "memory.swap.max")
        }
    }

    resources.cpu?.let { cpu ->
        cpu.shares?.let {
            val weight = cgroup.convertCpuSharesV2(it)
            if (weight != 0L) {
                writeCgroup(fs, "$cgroupDir/cpu.weight", weight.toString(), "cpu.weight")
            }
        }
        if (cpu.quota != null || cpu.period != null) {
            // Read current cpu.max to preserve the other half when only one is updated.
            // Format is "quota period" (e.g. "500000 1000000" or "max 100000").
            val current =
                try {
                    fs.readTextFile("$cgroupDir/cpu.max").trim().split(" ", limit = 2)
                } catch (_: Exception) {
                    listOf("max", "100000")
                }
            val currentQuota = current.getOrNull(0) ?: "max"
            val currentPeriod = current.getOrNull(1) ?: "100000"

            val q =
                when {
                    cpu.quota == null -> currentQuota
                    cpu.quota <= 0 -> "max"
                    else -> cpu.quota.toString()
                }
            val p = cpu.period?.toString() ?: currentPeriod
            writeCgroup(fs, "$cgroupDir/cpu.max", "$q $p", "cpu.max")
        }
        cpu.burst?.let { burst ->
            if (burst >= 0) {
                writeCgroup(fs, "$cgroupDir/cpu.max.burst", burst.toString(), "cpu.max.burst")
            }
        }
        cpu.idle?.let { idle ->
            writeCgroup(fs, "$cgroupDir/cpu.idle", idle.toString(), "cpu.idle")
        }
        cpu.cpus?.let { cpus ->
            writeCgroup(fs, "$cgroupDir/cpuset.cpus", cpus, "cpuset.cpus")
        }
        cpu.mems?.let { mems ->
            writeCgroup(fs, "$cgroupDir/cpuset.mems", mems, "cpuset.mems")
        }
    }

    resources.pids?.let { pids ->
        pids.limit?.let {
            val value =
                when {
                    it < 0 -> "max"
                    it == 0L -> "1" // runc compat: 0 → 1 (TasksMax=0 invalid)
                    else -> it.toString()
                }
            writeCgroup(fs, "$cgroupDir/pids.max", value, "pids.max")
        }
    }

    resources.hugepageLimits?.forEach { hp ->
        val value = if (hp.limit <= 0) "max" else hp.limit.toString()
        writeCgroup(fs, "$cgroupDir/hugetlb.${hp.pageSize}.max", value, "hugetlb.${hp.pageSize}.max")
    }

    // cgroup v2 unified map: write arbitrary cgroup files verbatim.
    resources.unified?.forEach { (key, value) ->
        // Multi-line values: write each line separately (kernel cgroup
        // interface processes one line per write() call).
        val lines = value.split('\n').filter { it.isNotBlank() }
        for (line in lines) {
            writeCgroup(fs, "$cgroupDir/$key", line, key)
        }
    }

    // Block IO (v1 blockIO → v2 io.max conversion)
    resources.blockIO?.let { bio ->
        bio.weight?.let { w ->
            if (w > 0) {
                writeCgroup(fs, "$cgroupDir/io.weight", w.toString(), "io.weight")
            }
        }
        // Merge throttle device entries into per-device io.max lines
        val deviceMap = mutableMapOf<String, MutableMap<String, String>>()
        bio.throttleReadBpsDevice?.forEach { d ->
            val key = "${d.major}:${d.minor}"
            deviceMap.getOrPut(key) { mutableMapOf() }["rbps"] = d.rate.toString()
        }
        bio.throttleWriteBpsDevice?.forEach { d ->
            val key = "${d.major}:${d.minor}"
            deviceMap.getOrPut(key) { mutableMapOf() }["wbps"] = d.rate.toString()
        }
        bio.throttleReadIOPSDevice?.forEach { d ->
            val key = "${d.major}:${d.minor}"
            deviceMap.getOrPut(key) { mutableMapOf() }["riops"] = d.rate.toString()
        }
        bio.throttleWriteIOPSDevice?.forEach { d ->
            val key = "${d.major}:${d.minor}"
            deviceMap.getOrPut(key) { mutableMapOf() }["wiops"] = d.rate.toString()
        }
        for ((dev, limits) in deviceMap) {
            val parts = limits.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            writeCgroup(fs, "$cgroupDir/io.max", "$dev $parts", "io.max")
        }
    }
}

private fun writeLimit(
    fs: FileSystem,
    path: String,
    value: Long,
    name: String,
) {
    val v = if (value == -1L) "max" else value.toString()
    writeCgroup(fs, path, v, name)
}

private fun writeCgroup(
    fs: FileSystem,
    path: String,
    value: String,
    name: String,
) {
    try {
        fs.writeTextFile(path, value)
        Logger.debug("update: set $name = $value")
    } catch (e: Exception) {
        Logger.warn("update: failed to write $name: ${e.message}")
    }
}
