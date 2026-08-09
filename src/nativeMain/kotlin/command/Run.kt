package command

import cgroup.Cgroup
import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.posix.*
import state.*
import syscall.Syscall
import utils.FileSystem

/**
 * Run command — OCI "create + start" lifecycle in one invocation.
 *
 * With `--detach` the runtime returns immediately after the container
 * reaches RUNNING (equivalent to `create` then `start`).
 *
 * Without `--detach` (foreground mode) the runtime additionally polls
 * the container process until it exits, then force-deletes the
 * container so the caller need not clean up manually.
 *
 * Note: because our bootstrap.c clones Stage-2 with CLONE_PARENT,
 * Stage-2 is a *sibling* of this process rather than a child, so we
 * cannot use waitpid to obtain its exit code. Foreground mode polls
 * process liveness via `kill(pid, 0)` and always returns exit code 0.
 * A future bootstrap.c change (conditional CLONE_PARENT) would allow
 * true waitpid-based exit-code forwarding.
 */
@OptIn(ExperimentalForeignApi::class)
fun run(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    bundlePath: String = ".",
    pidFile: String? = null,
    consoleSocket: String? = null,
    detach: Boolean = false,
) {
    // Step 1: create the container (leaves it in CREATED state)
    create(syscall, fs, cgroup, rootPath, containerId, bundlePath, pidFile, consoleSocket)

    // Step 2: start (transitions to RUNNING)
    start(fs, rootPath, containerId)

    if (detach) {
        Logger.info("container $containerId started (detached)")
        return
    }

    // Foreground mode: wait for the container process to exit, then clean up.
    Logger.debug("foreground mode: waiting for container $containerId to exit")

    // Snapshot the init PID before the state file is removed.
    val initPid =
        try {
            val st = loadState(fs, rootPath, containerId)
            st.pid ?: 0
        } catch (_: Exception) {
            0
        }

    if (initPid > 0) {
        waitForProcessExit(initPid)
    }

    // Force-delete the container (process is gone, but state/cgroup may remain).
    Logger.debug("container process exited, cleaning up")
    try {
        delete(syscall, fs, cgroup, rootPath, containerId, force = true)
    } catch (e: Exception) {
        Logger.warn("cleanup after run failed: ${e.message ?: "unknown"}")
    }

    Logger.info("container $containerId finished")
}

/**
 * Poll until the process identified by [pid] no longer exists.
 *
 * Uses `kill(pid, 0)` (signal 0 — existence check) with
 * exponential-ish back-off: 10 ms → 20 → 40 → … → capped at 500 ms.
 */
@OptIn(ExperimentalForeignApi::class)
private fun waitForProcessExit(pid: Int) {
    var sleepUs: UInt = 10_000u // start at 10 ms

    while (true) {
        val rc = kill(pid, 0)
        if (rc != 0 && errno == ESRCH) {
            // Process no longer exists.
            return
        }
        usleep(sleepUs)
        if (sleepUs < 500_000u) {
            sleepUs = (sleepUs * 2u).coerceAtMost(500_000u)
        }
    }
}
