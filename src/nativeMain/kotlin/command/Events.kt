package command

import config.loadKontainerConfig
import ioloop.restoreBlocking
import ioloop.setNonBlocking
import ioloop.withIoLoop
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import logger.Logger
import platform.linux.IN_CLOEXEC
import platform.linux.IN_DELETE_SELF
import platform.linux.IN_MODIFY
import platform.linux.inotify_add_watch
import platform.linux.inotify_event
import platform.linux.inotify_init1
import platform.posix.*
import state.containerExists
import state.getContainerDir
import utils.FileSystem
import utils.JsonCodec

/**
 * Events command — stream resource-usage statistics from a container's cgroup.
 *
 * Reads memory, CPU, and PID stats from the cgroup v2 filesystem and
 * prints one JSON object per snapshot. With `--stats` (one-shot) it
 * prints a single snapshot and exits; otherwise it loops at [intervalMs].
 *
 * Output format (JSON Lines):
 *
 *     {"id":"ct1","type":"stats","data":{"memory":{...},"cpu":{...},"pids":{...}}}
 */
@OptIn(ExperimentalForeignApi::class)
fun events(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
    stats: Boolean,
    intervalMs: Long,
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
        Logger.error("container $containerId has no cgroupsPath, cannot read events")
        exit(1)
        return
    }

    val cgDir = "/sys/fs/cgroup/${cgroupPath.removePrefix("/")}"

    // Set up inotify to watch:
    //   1. Container state file for deletion → exit cleanly
    //   2. memory.events for modification → real-time OOM detection
    val statePath = "${getContainerDir(rootPath, containerId)}/state.json"
    val memEventsPath = "$cgDir/memory.events"
    val ifd = inotify_init1(IN_CLOEXEC)
    var stateWd = -1
    var memEventsWd = -1
    if (ifd >= 0) {
        stateWd = inotify_add_watch(ifd, statePath, IN_DELETE_SELF.toUInt())
        memEventsWd = inotify_add_watch(ifd, memEventsPath, IN_MODIFY.toUInt())
    }

    try {
        eventsLoop(fs, rootPath, containerId, cgDir, stats, intervalMs, ifd, stateWd, memEventsWd)
    } finally {
        if (ifd >= 0) close(ifd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun eventsLoop(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
    cgDir: String,
    stats: Boolean,
    intervalMs: Long,
    ifd: Int,
    stateWd: Int,
    @Suppress("UNUSED_PARAMETER") memEventsWd: Int,
) {
    if (ifd >= 0) setNonBlocking(ifd)
    try {
        eventsLoopCoroutine(fs, rootPath, containerId, cgDir, stats, intervalMs, ifd, stateWd)
    } finally {
        if (ifd >= 0) restoreBlocking(ifd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun eventsLoopCoroutine(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
    cgDir: String,
    stats: Boolean,
    intervalMs: Long,
    ifd: Int,
    stateWd: Int,
) = withIoLoop { io ->
    var lastOomCount = readOomCount(fs, cgDir)

    while (true) {
        if (!containerExists(fs, rootPath, containerId)) {
            break
        }

        val currentOomCount = readOomCount(fs, cgDir)
        if (currentOomCount != null && lastOomCount != null && currentOomCount > lastOomCount) {
            println("""{"type":"oom","id":"$containerId"}""")
            fflush(stdout)
        }
        lastOomCount = currentOomCount

        val snapshot = buildSnapshot(fs, cgDir, containerId)
        println(JsonCodec.encode(snapshot))
        fflush(stdout)

        if (stats) break

        if (ifd >= 0) {
            val ready =
                withTimeoutOrNull(intervalMs) {
                    io.awaitReadable(ifd)
                }
            if (ready != null) {
                memScoped {
                    val buf = allocArray<ByteVar>(4096)
                    val n = read(ifd, buf, 4096u)
                    if (n > 0) {
                        var offset = 0
                        while (offset < n.toInt()) {
                            val event = (buf + offset)!!.reinterpret<inotify_event>().pointed
                            if (event.wd == stateWd) {
                                val finalOomCount = readOomCount(fs, cgDir)
                                if (finalOomCount != null && lastOomCount != null && finalOomCount > lastOomCount) {
                                    println("""{"type":"oom","id":"$containerId"}""")
                                    fflush(stdout)
                                }
                                return@withIoLoop
                            }
                            val nameLen = event.len.toInt()
                            offset += sizeOf<inotify_event>().toInt() + nameLen
                        }
                    }
                }
            }
        } else {
            delay(intervalMs)
        }
    }
}

/**
 * Build the stats snapshot for tests — returns the serializable event.
 */
internal fun buildSnapshot(
    fs: FileSystem,
    cgroupDir: String,
    containerId: String,
): EventSnapshot {
    val memCurrent = readLong(fs, "$cgroupDir/memory.current")
    val memMax = readCgroupString(fs, "$cgroupDir/memory.max")

    val cpuStat = readKvFile(fs, "$cgroupDir/cpu.stat")

    val pidsCurrent = readLong(fs, "$cgroupDir/pids.current")
    val pidsMax = readCgroupString(fs, "$cgroupDir/pids.max")

    // Hugetlb stats: discover page sizes from /sys/kernel/mm/hugepages/ then
    // read rsvd.current and events from the cgroup.
    val hugetlb = readHugetlbStats(fs, cgroupDir)

    return EventSnapshot(
        id = containerId,
        type = "stats",
        data =
            EventData(
                memory =
                    MemoryStats(
                        usage = memCurrent,
                        limit = memMax,
                    ),
                cpu = CpuStats(stat = cpuStat),
                pids = PidsStats(current = pidsCurrent, limit = pidsMax),
                hugetlb = hugetlb.ifEmpty { null },
            ),
    )
}

// ---- Data model ----

@Serializable
internal data class EventSnapshot(
    val type: String,
    val id: String,
    val data: EventData,
)

@Serializable
internal data class EventData(
    val memory: MemoryStats,
    val cpu: CpuStats,
    val pids: PidsStats,
    val hugetlb: Map<String, HugetlbStats>? = null,
)

@Serializable
internal data class MemoryStats(
    val usage: Long? = null,
    val limit: String? = null,
)

@Serializable
internal data class CpuStats(
    val stat: Map<String, Long>? = null,
)

@Serializable
internal data class PidsStats(
    val current: Long? = null,
    val limit: String? = null,
)

@Serializable
internal data class HugetlbStats(
    val usage: Long = 0,
    val max: Long = 0,
    val failcnt: Long = 0,
)

// ---- Hugetlb stats ----

/**
 * Read hugetlb stats for all discovered page sizes.
 * Discovers sizes from /sys/kernel/mm/hugepages/ (e.g. hugepages-2048kB → "2MB").
 * Reads cgroup v2 files: hugetlb.<size>.current, hugetlb.<size>.max, hugetlb.<size>.events.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readHugetlbStats(
    fs: FileSystem,
    cgroupDir: String,
): Map<String, HugetlbStats> {
    val result = linkedMapOf<String, HugetlbStats>()
    // Discover hugepage sizes from sysfs
    val pageSizes = discoverHugepageSizes()
    for (cgroupSize in pageSizes) {
        // cgroup v2 uses rsvd variant: hugetlb.<size>.rsvd.current
        val usage =
            readLong(fs, "$cgroupDir/hugetlb.$cgroupSize.rsvd.current")
                ?: readLong(fs, "$cgroupDir/hugetlb.$cgroupSize.current")
                ?: continue
        val max =
            readLong(fs, "$cgroupDir/hugetlb.$cgroupSize.rsvd.max")
                ?: readLong(fs, "$cgroupDir/hugetlb.$cgroupSize.max")
                ?: 0
        // failcnt from events file: hugetlb.<size>.events has "max <N>"
        val events = readKvFile(fs, "$cgroupDir/hugetlb.$cgroupSize.events")
        val failcnt = events?.get("max") ?: 0
        result[cgroupSize] = HugetlbStats(usage = usage, max = max, failcnt = failcnt)
    }
    return result
}

/**
 * Discover hugepage sizes from /sys/kernel/mm/hugepages/.
 * Each entry is like "hugepages-2048kB" → convert to cgroup format "2MB".
 */
@OptIn(ExperimentalForeignApi::class)
private fun discoverHugepageSizes(): List<String> {
    val sizes = mutableListOf<String>()
    val dir = opendir("/sys/kernel/mm/hugepages") ?: return sizes
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (!name.startsWith("hugepages-")) continue
            // "hugepages-2048kB" → "2048kB" → convert to "2MB"
            val sizeStr = name.removePrefix("hugepages-")
            val cgroupSize = convertHugepageSize(sizeStr)
            if (cgroupSize != null) sizes.add(cgroupSize)
        }
    } finally {
        closedir(dir)
    }
    return sizes
}

/**
 * Convert kernel hugepage size to cgroup naming convention.
 * "2048kB" → "2MB", "1048576kB" → "1GB"
 */
private fun convertHugepageSize(sizeStr: String): String? {
    val kb = sizeStr.removeSuffix("kB").toLongOrNull() ?: return null
    return when {
        kb >= 1048576 && kb % 1048576 == 0L -> "${kb / 1048576}GB"
        kb >= 1024 && kb % 1024 == 0L -> "${kb / 1024}MB"
        else -> "${kb}KB"
    }
}

// ---- OOM detection ----

/**
 * Read the "oom_kill" counter from memory.events in the given cgroup directory.
 * Uses "oom_kill" (actual process kills) rather than "oom" (limit-hit events),
 * matching runc's behavior with `notifyOnOOMV2`.
 * Returns null if the file is absent or unreadable.
 */
private fun readOomCount(
    fs: FileSystem,
    cgroupDir: String,
): Long? = readKvFile(fs, "$cgroupDir/memory.events")?.get("oom_kill")

// ---- Cgroup file readers ----

private fun readLong(
    fs: FileSystem,
    path: String,
): Long? =
    try {
        fs.readProcFile(path).trim().toLongOrNull()
    } catch (_: Exception) {
        null
    }

private fun readCgroupString(
    fs: FileSystem,
    path: String,
): String? =
    try {
        fs.readProcFile(path).trim()
    } catch (_: Exception) {
        null
    }

private fun readKvFile(
    fs: FileSystem,
    path: String,
): Map<String, Long>? =
    try {
        val content = fs.readProcFile(path).trim()
        if (content.isEmpty()) {
            null
        } else {
            val map = linkedMapOf<String, Long>()
            for (line in content.lines()) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size == 2) {
                    parts[1].toLongOrNull()?.let { map[parts[0]] = it }
                }
            }
            map.ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }
