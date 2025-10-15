package process

import channel.*
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import seccomp.sendToSeccompListener
import spec.ContainerState
import spec.Spec
import utils.writeText

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
        val hostUid = geteuid().toUInt()
        "0 $hostUid 1\n"
    }

    // Build gid_map content
    val gidMap = if (!gidMappings.isNullOrEmpty()) {
        gidMappings.joinToString("\n") { mapping ->
            "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
        } + "\n"
    } else {
        // Fallback: map container root to current effective GID
        val hostGid = getegid().toUInt()
        "0 $hostGid 1\n"
    }

    // Kernel requires disabling setgroups before writing gid_map (CVE-2014-8989)
    if (!writeText("/proc/$intermediatePid/setgroups", "deny\n")) {
        perror("write setgroups")
        Logger.error("Failed to write setgroups")
        _exit(1)
    }

    Logger.debug("writing uid_map for pid $intermediatePid")
    if (!writeText("/proc/$intermediatePid/uid_map", uidMap)) {
        Logger.error("failed to write uid_map")
        _exit(1)
    }

    Logger.debug("writing gid_map for pid $intermediatePid")
    if (!writeText("/proc/$intermediatePid/gid_map", gidMap)) {
        Logger.error("failed to write gid_map")
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
            spec.linux.seccomp?.listenerPath?.let { listenerPath ->
                Logger.debug("forwarding seccomp notify FD to listener: $listenerPath")
                try {
                    val containerState = ContainerState(
                        ociVersion = spec.ociVersion,
                        id = containerId,
                        status = "creating",
                        pid = initPid,
                        bundle = bundlePath,
                        annotations = null
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
    val st = alloc<IntVar>()
    if (waitpid(intermediatePid, st.ptr, 0) == -1) {
        perror("waitpid(intermediate)")
        Logger.error("Failed to wait for intermediate process")
        _exit(1)
    }

    Logger.debug("intermediate process exited")
    Logger.info("container created with init pid=$initPid")

    initPid  // Return value
}
