package command

import config.loadKontainerConfig
import logger.Logger
import platform.posix.exit
import state.ContainerStatus
import state.loadState
import state.refreshStatus
import state.save
import state.withStatus
import utils.FileSystem

/**
 * Pause command — freeze all processes in a running container.
 *
 * Uses the cgroup v2 freezer: writing "1" to `cgroup.freeze` atomically
 * stops every process in the cgroup and all descendant cgroups. The
 * container transitions from RUNNING → PAUSED.
 */
fun pause(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
) {
    val state = loadState(fs, rootPath, containerId).refreshStatus()

    if (!state.status.canPause()) {
        Logger.error(
            "cannot pause container $containerId: status is ${state.status.value}" +
                " (must be running)",
        )
        exit(1)
    }

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
        Logger.error("container $containerId has no cgroupsPath, cannot pause")
        exit(1)
        return
    }

    val normalizedPath = cgroupPath.removePrefix("/")
    val freezePath = "/sys/fs/cgroup/$normalizedPath/cgroup.freeze"

    try {
        fs.writeTextFile(freezePath, "1")
    } catch (e: Exception) {
        Logger.error("failed to freeze container $containerId: ${e.message}")
        exit(1)
        return
    }

    state.withStatus(ContainerStatus.PAUSED).save(fs, rootPath)
    Logger.info("paused container $containerId")
}
