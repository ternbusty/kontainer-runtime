package process

import channel.InitSender
import channel.IntermediateSender
import channel.MainReceiver
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import seccomp.sendToSeccompListener
import spec.Spec
import state.State
import utils.writeText

/**
 * Wait status check macros (from sys/wait.h)
 * These are C macros, so we need to implement them manually in Kotlin
 */
private fun WIFEXITED(status: Int): Boolean = ((status and 0x7f) == 0)
private fun WEXITSTATUS(status: Int): Int = ((status and 0xff00) shr 8)
private fun WIFSIGNALED(status: Int): Boolean = ((((status and 0x7f) + 1) shr 1) > 0)
private fun WTERMSIG(status: Int): Int = (status and 0x7f)

/**
 * Main process - Parent process
 *
 * Responsibilities:
 * - Wait for UID/GID mapping request from intermediate process
 * - Write mappings to /proc/<pid>/uid_map and /proc/<pid>/gid_map
 * - Wait for intermediate process to send init PID
 * - Wait for init process to be ready
 * - Wait for intermediate process to exit
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainProcess(
    spec: Spec,
    containerId: String,
    bundlePath: String,
    intermediatePid: Int,
    mainReceiver: MainReceiver,
    interSender: IntermediateSender,
    initSender: InitSender
): Int = memScoped {
    Logger.setContext("main")
    Logger.debug("started, intermediate pid=$intermediatePid")

    // Wait for UID/GID mapping request from intermediate process
    try {
        mainReceiver.waitForMappingRequest()
        Logger.debug("received mapping request")
    } catch (e: Exception) {
        Logger.error("error waiting for mapping request: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Get UID/GID mappings from spec
    val uidMappings = spec.linux?.uidMappings
    val gidMappings = spec.linux?.gidMappings

    // Build uid_map content
    val uidMap = if (!uidMappings.isNullOrEmpty()) {
        uidMappings.joinToString("\n") { mapping ->
            "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
        } + "\n"
    } else {
        // Fallback: map container root to current effective UID
        val hostUid = geteuid()
        "0 $hostUid 1\n"
    }

    // Build gid_map content
    val gidMap = if (!gidMappings.isNullOrEmpty()) {
        gidMappings.joinToString("\n") { mapping ->
            "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
        } + "\n"
    } else {
        // Fallback: map container root to current effective GID
        val hostGid = getegid()
        "0 $hostGid 1\n"
    }

    // Kernel requires disabling setgroups before writing gid_map (CVE-2014-8989)
    try {
        Logger.debug("disabling setgroups for pid $intermediatePid")
        writeText("/proc/$intermediatePid/setgroups", "deny\n")

        Logger.debug("writing uid_map for pid $intermediatePid")
        writeText("/proc/$intermediatePid/uid_map", uidMap)

        Logger.debug("writing gid_map for pid $intermediatePid")
        writeText("/proc/$intermediatePid/gid_map", gidMap)

        Logger.debug("successfully wrote UID/GID mappings")
    } catch (e: Exception) {
        Logger.error("failed to write UID/GID mappings: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Notify intermediate process that mapping is written
    try {
        interSender.mappingWritten()
        Logger.debug("sent mapping written ack")
    } catch (e: Exception) {
        Logger.error("error sending mapping ack: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Close intermediate sender (no more messages to send)
    interSender.close()

    // Wait for intermediate process to send init PID
    val initPid: Int = try {
        mainReceiver.waitForIntermediateReady()
    } catch (e: Exception) {
        Logger.error("error waiting for init pid: ${e.message ?: "unknown"}")
        _exit(1)
        -1  // Never reached, but satisfies type checker
    }
    Logger.debug("received init pid=$initPid")

    // Check if seccomp notify is used in the spec
    val hasSeccompNotify = spec.linux?.seccomp?.syscalls?.any { it.action == "SCMP_ACT_NOTIFY" } ?: false
    if (hasSeccompNotify) {
        Logger.debug("seccomp notify is enabled, waiting for notify FD")
        try {
            val notifyFd = mainReceiver.waitForSeccompRequest()
            Logger.debug("received seccomp notify FD: $notifyFd")

            // If listenerPath is specified, forward the FD to the listener
            spec.linux.seccomp.listenerPath?.let { listenerPath ->
                Logger.debug("forwarding seccomp notify FD to listener: $listenerPath")
                try {
                    val containerState = State(
                        ociVersion = spec.ociVersion,
                        id = containerId,
                        status = "creating",
                        pid = initPid,
                        bundle = bundlePath,
                        annotations = null,
                        created = null  // Not set yet during creating phase
                    )
                    sendToSeccompListener(listenerPath, containerState, notifyFd)
                    Logger.debug("forwarded seccomp notify FD to listener")
                } catch (e: Exception) {
                    Logger.error("failed to forward seccomp notify FD: ${e.message}")
                    _exit(1)
                }
            } ?: run {
                Logger.warn("seccomp notify FD received but no listenerPath specified")
            }

            // Send completion signal to init process
            initSender.seccompNotifyDone()
            Logger.debug("sent seccomp notify done signal")
        } catch (e: Exception) {
            Logger.error("error handling seccomp notify: ${e.message ?: "unknown"}")
            _exit(1)
        }
    }

    // Wait for init process to be ready
    try {
        mainReceiver.waitForInitReady()
        Logger.debug("init process is ready")
    } catch (e: Exception) {
        Logger.error("error waiting for init ready: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Close channels
    mainReceiver.close()
    initSender.close()

    // Wait for intermediate process to exit
    // By this point, the intermediate process should already have exited successfully.
    // If intermediate process errors out, the `init_ready` will not be sent.
    // We check the exit status but only log warnings,
    // because the process status has already been validated through piping.
    val st = alloc<IntVar>()
    if (waitpid(intermediatePid, st.ptr, 0) == -1) {
        val errNum = errno
        if (errNum == ECHILD) {
            // This is safe because intermediate_process and main_process check if the process is
            // finished by piping instead of exit code.
            Logger.warn("intermediate process already reaped")
        } else {
            perror("waitpid(intermediate)")
            Logger.error("Failed to wait for intermediate process (errno=$errNum)")
            _exit(1)
        }
    } else {
        // Check exit status
        val status = st.value
        if (WIFEXITED(status)) {
            val exitCode = WEXITSTATUS(status)
            if (exitCode != 0) {
                Logger.warn("intermediate process failed with exit status: $exitCode")
            } else {
                Logger.debug("intermediate process exited successfully")
            }
        } else if (WIFSIGNALED(status)) {
            val signal = WTERMSIG(status)
            Logger.warn("intermediate process killed by signal: $signal")
        } else {
            Logger.warn("intermediate process exited abnormally (status=$status)")
        }
    }
    Logger.info("container created with init pid=$initPid")

    initPid
}
