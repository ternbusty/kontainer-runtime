package command

import cgroup.Cgroup
import config.loadKontainerConfig
import hook.runHooks
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.SIGKILL
import platform.posix.exit
import spec.loadSpec
import state.*
import syscall.Syscall
import utils.FileSystem

/**
 * Delete command - Deletes a container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param force If true, force deletion even if container is running
 */
@OptIn(ExperimentalForeignApi::class)
fun delete(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    force: Boolean = false,
) {
    Logger.info("deleting container: $containerId${if (force) " (force)" else ""}")

    // Check if container exists
    if (!containerExists(fs, rootPath, containerId)) {
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
            loadState(fs, rootPath, containerId)
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
            // STOPPED status: can delete without killing process.
            // In host-pidns scenarios the init process may be gone but child
            // processes remain in the cgroup — kill them before cleanup.
            Logger.debug("container is stopped, proceeding with deletion")
            val cgPath =
                try {
                    loadKontainerConfig(fs, rootPath, containerId).cgroupPath
                } catch (_: Exception) {
                    null
                }
            if (cgPath != null) {
                try {
                    val pids = cgroup.getPids(cgPath)
                    for (p in pids) {
                        try {
                            syscall.killProcess(p, SIGKILL)
                            Logger.debug("killed remaining PID $p in cgroup")
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        force -> {
            // Force flag set: kill all processes in the container before deletion.
            // In host-pidns scenarios the init process may be gone but child
            // processes remain — we must kill everything in the cgroup.
            Logger.debug("container is in '${state.status.value}' state, but force flag is set")
            Logger.debug("killing all container processes before deletion")

            // Try cgroup-based kill first (covers host-pidns)
            val cgPath =
                try {
                    loadKontainerConfig(fs, rootPath, containerId).cgroupPath
                } catch (_: Exception) {
                    null
                }

            // If the container is paused (frozen), thaw it before sending
            // SIGKILL — the kernel holds signals pending on frozen processes.
            if (state.status == ContainerStatus.PAUSED && cgPath != null) {
                val normalizedPath = cgPath.removePrefix("/")
                val freezePath = "/sys/fs/cgroup/$normalizedPath/cgroup.freeze"
                try {
                    fs.writeTextFile(freezePath, "0")
                    Logger.debug("thawed paused container before kill")
                } catch (_: Exception) {
                    Logger.debug("could not thaw container (may not be frozen)")
                }
            }

            if (cgPath != null) {
                try {
                    val pids = cgroup.getPids(cgPath)
                    for (p in pids) {
                        try {
                            syscall.killProcess(p, SIGKILL)
                            Logger.debug("killed PID $p")
                        } catch (_: Exception) {
                            // Process may have already exited
                        }
                    }
                } catch (_: Exception) {
                    // Fall back to init PID
                }
            }

            // Also try the init PID directly (belt & suspenders)
            state.pid?.let { pid ->
                try {
                    syscall.killProcess(pid, SIGKILL)
                    Logger.debug("killed init process $pid")
                } catch (_: Exception) {
                    // Process may have already exited
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
            loadKontainerConfig(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.warn("failed to load kontainer config: ${e.message ?: "unknown"}")
            Logger.warn("will skip cgroup cleanup")
            null
        }

    // Cleanup cgroup
    config?.cgroupPath?.let { cgroupPath ->
        try {
            cgroup.cleanup(cgroupPath)
        } catch (e: Exception) {
            Logger.warn("failed to cleanup cgroup: ${e.message ?: "unknown"}")
            // Continue with deletion even if cgroup cleanup fails
        }
    }

    // Run poststop hooks BEFORE we tear down the notify socket / container dir so
    // the hook can still read state.json and the runtime layout. State at this
    // point shows status="stopped". Hook failures are logged but non-fatal.
    val poststopSpec =
        try {
            loadSpec(fs, "${state.bundle}/config.json")
        } catch (e: Exception) {
            null
        }
    if (poststopSpec?.hooks?.poststop != null) {
        runHooks(poststopSpec.hooks.poststop, state.withStatus(ContainerStatus.STOPPED))
    }

    // Delete notify socket
    try {
        deleteNotifySocket(rootPath, containerId)
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
