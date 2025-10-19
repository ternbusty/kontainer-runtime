package command

import cgroup.cleanupCgroup
import config.loadKontainerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.exit
import state.*
import syscall.killProcess

/**
 * Delete command - Deletes a container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param force If true, force deletion even if container is running
 */
@OptIn(ExperimentalForeignApi::class)
fun delete(
    rootPath: String,
    containerId: String,
    force: Boolean = false,
) {
    Logger.info("deleting container: $containerId${if (force) " (force)" else ""}")

    // Check if container exists
    if (!containerExists(rootPath, containerId)) {
        if (force) {
            // With force flag, non-existent container is not an error
            Logger.debug("container $containerId does not exist, but force flag is set")
            Logger.info("container $containerId deleted successfully")
            exit(0)
        }
        Logger.error("container $containerId does not exist")
        exit(1)
    }

    // Load container state and refresh to get actual status
    var state =
        try {
            loadState(rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("failed to load container state: ${e.message ?: "unknown"}")
            exit(1)
            return
        }

    // Refresh status to check actual process state
    state = state.refreshStatus()
    Logger.debug("container status: ${state.status.value}")

    // Check if container can be deleted
    // Allow deletion of 'stopped' state without force
    // With force flag, allow deletion of any state
    when {
        state.status.canDelete() -> {
            // STOPPED status: can delete without killing process
            Logger.debug("container is stopped, proceeding with deletion")
        }

        force -> {
            // Force flag set: kill process before deletion
            Logger.debug("container is in '${state.status.value}' state, but force flag is set")
            Logger.debug("killing process before deletion")
            state.pid?.let { pid ->
                try {
                    killProcess(pid)
                    Logger.debug("killed process $pid")
                } catch (e: Exception) {
                    Logger.warn("failed to kill process $pid: ${e.message ?: "unknown"}")
                    // Continue with deletion even if kill fails
                }
            }
        }

        else -> {
            // Cannot delete without force flag
            Logger.error("cannot delete container in '${state.status.value}' state")
            Logger.error("use --force flag to force deletion, or stop the container first")
            exit(1)
        }
    }

    // Load internal config to get cgroup path
    // This is independent of bundle, so works even if bundle was moved/deleted
    val config =
        try {
            loadKontainerConfig(rootPath, containerId)
        } catch (e: Exception) {
            Logger.warn("failed to load kontainer config: ${e.message ?: "unknown"}")
            Logger.warn("will skip cgroup cleanup")
            null
        }

    // Cleanup cgroup
    config?.cgroupPath?.let { cgroupPath ->
        try {
            cleanupCgroup(cgroupPath)
        } catch (e: Exception) {
            Logger.warn("failed to cleanup cgroup: ${e.message ?: "unknown"}")
            // Continue with deletion even if cgroup cleanup fails
        }
    }

    // Delete notify socket
    try {
        deleteNotifySocket(containerId)
    } catch (e: Exception) {
        Logger.warn("failed to delete notify socket: ${e.message ?: "unknown"}")
        // Continue with deletion
    }

    // Delete container directory
    try {
        deleteContainerDir(rootPath, containerId)
        Logger.info("container $containerId deleted successfully")
    } catch (e: Exception) {
        Logger.error("failed to delete container directory: ${e.message ?: "unknown"}")
        exit(1)
    }
}
