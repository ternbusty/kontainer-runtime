package command

import config.loadKontainerConfig
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
    cpuQuota: Long? = null,
    cpuPeriod: Long? = null,
    cpuShares: Long? = null,
    pidsLimit: Long? = null,
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
                JsonCodec.loadFromFile<LinuxResources>(fs, resourcesPath)
            } catch (e: Exception) {
                Logger.error("failed to read resources file $resourcesPath: ${e.message}")
                exit(1)
                return
            }
        } else {
            LinuxResources()
        }

    // Apply CLI flag overrides
    if (memory != null) {
        resources = resources.copy(memory = (resources.memory ?: LinuxMemory()).copy(limit = memory))
    }
    if (cpuQuota != null || cpuPeriod != null || cpuShares != null) {
        val cpu = resources.cpu ?: LinuxCpu()
        resources =
            resources.copy(
                cpu =
                    cpu.copy(
                        quota = cpuQuota ?: cpu.quota,
                        period = cpuPeriod ?: cpu.period,
                        shares = cpuShares ?: cpu.shares,
                    ),
            )
    }
    if (pidsLimit != null) {
        resources = resources.copy(pids = (resources.pids ?: LinuxPids()).copy(limit = pidsLimit))
    }

    val normalizedPath = cgroupPath.removePrefix("/")
    val cgroupDir = "/sys/fs/cgroup/$normalizedPath"

    applyCgroupResources(fs, cgroupDir, resources)
    Logger.info("updated resources for $containerId")
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
        mem.swap?.let { swap ->
            mem.limit?.let { limit ->
                val value =
                    when {
                        swap == -1L || limit == -1L -> "max"
                        else -> (swap - limit).toString()
                    }
                writeCgroup(fs, "$cgroupDir/memory.swap.max", value, "memory.swap.max")
            }
        }
    }

    resources.cpu?.let { cpu ->
        cpu.shares?.let {
            if (it > 0) {
                val weight =
                    if (it == 0L) {
                        0L
                    } else {
                        val w = 1L + ((it - 2) * 9999 / 262142)
                        minOf(w, 10000L)
                    }
                if (weight != 0L) {
                    writeCgroup(fs, "$cgroupDir/cpu.weight", weight.toString(), "cpu.weight")
                }
            }
        }
        if (cpu.quota != null || cpu.period != null) {
            val q =
                when {
                    cpu.quota == null -> null
                    cpu.quota <= 0 -> "max"
                    else -> cpu.quota.toString()
                }
            val p = cpu.period?.toString()
            val value =
                when {
                    q != null && p != null -> "$q $p"
                    q != null -> q
                    p != null -> "max $p"
                    else -> null
                }
            value?.let { writeCgroup(fs, "$cgroupDir/cpu.max", it, "cpu.max") }
        }
    }

    resources.pids?.let { pids ->
        pids.limit?.let {
            val value = if (it <= 0) "max" else it.toString()
            writeCgroup(fs, "$cgroupDir/pids.max", value, "pids.max")
        }
    }

    resources.hugepageLimits?.forEach { hp ->
        val value = if (hp.limit <= 0) "max" else hp.limit.toString()
        writeCgroup(fs, "$cgroupDir/hugetlb.${hp.pageSize}.max", value, "hugetlb.${hp.pageSize}.max")
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
