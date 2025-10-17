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
 * @param containerId Container ID
 * @param force If true, force deletion even if container is running
 */
@OptIn(ExperimentalForeignApi::class)
fun delete(containerId: String, force: Boolean = false) {
    Logger.info("deleting container: $containerId${if (force) " (force)" else ""}")

    // Check if container exists
    if (!containerExists(containerId)) {
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
    var state = try {
        loadState(containerId)
    } catch (e: Exception) {
        Logger.error("failed to load container state: ${e.message ?: "unknown"}")
        exit(1)
        return
    }

    // Refresh status to check actual process state
    state = state.refreshStatus()
    Logger.debug("container status: ${state.status}")

    // Check if container can be deleted
    // Allow deletion of 'stopped' and 'created' states
    // With force flag, allow deletion of any state
    when (state.status) {
        "stopped" -> {
            // Can delete stopped containers
            Logger.debug("container is stopped, proceeding with deletion")
        }

        "created" -> {
            // For created containers, kill the process first
            Logger.debug("container is created, killing process before deletion")
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

        "running", "paused", "creating" -> {
            // Cannot delete running/paused/creating containers without force flag
            if (force) {
                Logger.debug("container is in '${state.status}' state, but force flag is set")
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
            } else {
                Logger.error("cannot delete container in '${state.status}' state")
                Logger.error("use --force flag to force deletion, or stop the container first")
                exit(1)
            }
        }

        else -> {
            // Unknown status
            Logger.warn("unknown container status: ${state.status}")
            if (!force) {
                Logger.error("cannot delete container in unknown state without force flag")
                exit(1)
            }
        }
    }

    // Load internal config to get cgroup path
    // This is independent of bundle, so works even if bundle was moved/deleted
    val config = try {
        loadKontainerConfig(containerId)
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
        deleteContainerDir(containerId)
        Logger.info("container $containerId deleted successfully")
    } catch (e: Exception) {
        Logger.error("failed to delete container directory: ${e.message ?: "unknown"}")
        exit(1)
    }
}
