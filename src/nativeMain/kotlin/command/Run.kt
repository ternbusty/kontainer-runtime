package command

import cgroup.Cgroup
import console.acceptConsoleMaster
import console.createConsoleSocketListener
import console.relayPtyIO
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import spec.loadSpec
import state.ContainerStatus
import state.loadState
import state.save
import state.withStatus
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
 * When spec.process.terminal is true and no --console-socket is given,
 * the runtime creates an internal console socket, accepts the PTY master
 * from the container, and relays I/O between the master and stdio.
 *
 * Create.kt uses a plain fork() for Stage-1, and bootstrap.c clones
 * Stage-2 with CLONE_PARENT.  This makes Stage-2 a child of the main
 * process (matching runc's architecture), so waitpid works for
 * exit-code forwarding.  Foreground mode uses waitpid to capture the
 * container exit code and exits with it.
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
    keep: Boolean = false,
) {
    // Determine if we need an internal console socket.
    // When terminal=true and no --console-socket was provided, we set one up
    // ourselves so the container can hand us its PTY master.
    var effectiveConsoleSocket = consoleSocket
    var internalConsoleFd = -1
    var internalSocketPath: String? = null

    if (consoleSocket == null && !detach) {
        val spec =
            try {
                loadSpec(fs, "$bundlePath/config.json")
            } catch (_: Exception) {
                null
            }
        if (spec != null && spec.process.terminal) {
            val tmpDir =
                memScoped {
                    val template = "/tmp/kontainer-console-XXXXXX"
                    val buf = allocArray<ByteVar>(template.length + 1)
                    val bytes = template.encodeToByteArray()
                    for (i in bytes.indices) buf[i] = bytes[i]
                    buf[bytes.size] = 0
                    val result = mkdtemp(buf)
                    result?.toKString()
                }
            if (tmpDir != null) {
                val pair = createConsoleSocketListener(tmpDir)
                if (pair != null) {
                    internalSocketPath = pair.first
                    internalConsoleFd = pair.second
                    effectiveConsoleSocket = internalSocketPath
                    Logger.debug("created internal console socket at $internalSocketPath")
                }
            }
        }
    }

    // Step 1: create the container (leaves it in CREATED state)
    create(syscall, fs, cgroup, rootPath, containerId, bundlePath, pidFile, effectiveConsoleSocket)

    // If we have an internal console socket, accept the PTY master now.
    // The container sends it during create, before the create call returns.
    var masterFd = -1
    if (internalConsoleFd >= 0) {
        masterFd = acceptConsoleMaster(internalConsoleFd)
        close(internalConsoleFd)
        internalConsoleFd = -1
        if (internalSocketPath != null) {
            unlink(internalSocketPath)
            // Try to rmdir the parent temp directory
            val parentDir = internalSocketPath.substringBeforeLast('/')
            rmdir(parentDir)
        }
        if (masterFd < 0) {
            Logger.warn("failed to receive PTY master from container")
        } else {
            Logger.debug("received PTY master fd=$masterFd from container")
        }
    }

    // Step 2: start (transitions to RUNNING)
    start(fs, rootPath, containerId)

    if (detach) {
        if (masterFd >= 0) close(masterFd)
        Logger.info("container $containerId started (detached)")
        return
    }

    // Foreground mode: relay PTY I/O or wait for process exit.
    Logger.debug("foreground mode: waiting for container $containerId to exit")

    if (masterFd >= 0) {
        // Relay I/O between the PTY master and our stdio until the
        // master side closes (container process exits / closes its pty).
        relayPtyIO(masterFd)
        close(masterFd)
    }

    // Snapshot the init PID before the state file is removed.
    val initPid =
        try {
            val st = loadState(fs, rootPath, containerId)
            st.pid ?: 0
        } catch (_: Exception) {
            0
        }

    // Wait for the container's init process to exit and capture its exit code.
    // Stage-2 (init) is a child of the main process: Create.kt fork()s
    // Stage-1 (child of main), then bootstrap.c clone_parent()s Stage-2
    // (parent = Stage-1's parent = main).  waitpid works here.
    val containerExitCode =
        if (initPid > 0) {
            waitForProcessExit(initPid)
        } else {
            0
        }

    if (keep) {
        // --keep: update state to stopped but leave the container around
        // for inspection / manual deletion.
        Logger.debug("container process exited, keeping state (--keep)")
        try {
            val st = loadState(fs, rootPath, containerId)
            val stoppedState = st.withStatus(ContainerStatus.STOPPED)
            stoppedState.save(fs, rootPath)
        } catch (e: Exception) {
            Logger.warn("failed to update state to stopped: ${e.message ?: "unknown"}")
        }
    } else {
        // Force-delete the container (process is gone, but state/cgroup may remain).
        Logger.debug("container process exited, cleaning up")
        try {
            delete(syscall, fs, cgroup, rootPath, containerId, force = true)
        } catch (e: Exception) {
            Logger.warn("cleanup after run failed: ${e.message ?: "unknown"}")
        }
    }

    Logger.info("container $containerId finished")
    exit(containerExitCode)
}

/**
 * Wait for the container init process to exit and return its exit code.
 *
 * Stage-2 (the container init) is a child of the main process: Create.kt
 * uses fork() for Stage-1, and bootstrap.c uses CLONE_PARENT for Stage-2,
 * so Stage-2's parent = Stage-1's parent = main process.  waitpid(2) works
 * here.  If it fails (ECHILD — process already reaped), we fall back to
 * polling with kill(pid, 0).
 *
 * @return the process's exit code, or 0 if it couldn't be determined
 */
@OptIn(ExperimentalForeignApi::class)
private fun waitForProcessExit(pid: Int): Int =
    memScoped {
        val status = alloc<IntVar>()

        // Try waitpid first — works if the init is our child (CLONE_PARENT).
        val rc = waitpid(pid, status.ptr, 0)
        if (rc > 0) {
            return@memScoped exitCodeFromWaitStatus(status.value)
        }

        // Fallback: poll until the process disappears. This handles
        // edge cases where waitpid fails (e.g., ECHILD if the kernel
        // reparented the process).
        Logger.debug("waitpid failed (errno=$errno), falling back to poll")
        var sleepUs: UInt = 10_000u
        while (true) {
            val probeRc = kill(pid, 0)
            if (probeRc != 0 && errno == ESRCH) {
                return@memScoped 0
            }
            usleep(sleepUs)
            if (sleepUs < 500_000u) {
                sleepUs = (sleepUs * 2u).coerceAtMost(500_000u)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        0
    }
