package command

import config.loadKontainerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import logger.Logger
import platform.posix.*
import state.containerExists
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

    while (true) {
        // Check if the container still exists. Once deleted, exit cleanly.
        if (!containerExists(fs, rootPath, containerId)) {
            break
        }

        val snapshot = buildSnapshot(fs, cgDir, containerId)
        println(JsonCodec.encode(snapshot))
        fflush(stdout)

        if (stats) break

        // Sleep for the interval. Use usleep for sub-second precision.
        if (intervalMs >= 1000) {
            sleep((intervalMs / 1000).toUInt())
        } else {
            usleep((intervalMs * 1000).toUInt())
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
    val memEvents = readKvFile(fs, "$cgroupDir/memory.events")

    val cpuStat = readKvFile(fs, "$cgroupDir/cpu.stat")

    val pidsCurrent = readLong(fs, "$cgroupDir/pids.current")
    val pidsMax = readCgroupString(fs, "$cgroupDir/pids.max")

    return EventSnapshot(
        id = containerId,
        type = "stats",
        data =
            EventData(
                memory =
                    MemoryStats(
                        usage = memCurrent,
                        limit = memMax,
                        events = memEvents,
                    ),
                cpu = CpuStats(stat = cpuStat),
                pids = PidsStats(current = pidsCurrent, limit = pidsMax),
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
)

@Serializable
internal data class MemoryStats(
    val usage: Long? = null,
    val limit: String? = null,
    val events: Map<String, Long>? = null,
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
