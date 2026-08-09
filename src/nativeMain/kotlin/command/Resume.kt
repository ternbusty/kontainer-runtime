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
 * Resume command — thaw all processes in a paused container.
 *
 * Writes "0" to `cgroup.freeze`, transitioning from PAUSED → RUNNING.
 */
fun resume(
    fs: FileSystem,
    rootPath: String,
    containerId: String,
) {
    val state = loadState(fs, rootPath, containerId).refreshStatus()

    if (!state.status.canResume()) {
        Logger.error(
            "cannot resume container $containerId: status is ${state.status.value}" +
                " (must be paused)",
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
        Logger.error("container $containerId has no cgroupsPath, cannot resume")
        exit(1)
        return
    }

    val normalizedPath = cgroupPath.removePrefix("/")
    val freezePath = "/sys/fs/cgroup/$normalizedPath/cgroup.freeze"

    try {
        fs.writeTextFile(freezePath, "0")
    } catch (e: Exception) {
        Logger.error("failed to thaw container $containerId: ${e.message}")
        exit(1)
        return
    }

    state.withStatus(ContainerStatus.RUNNING).save(fs, rootPath)
    Logger.info("resumed container $containerId")
}
