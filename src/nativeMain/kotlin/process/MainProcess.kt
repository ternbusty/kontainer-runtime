package process

import channel.*
import kotlinx.cinterop.*
import platform.posix.*
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
    intermediatePid: Int,
    mainReceiver: MainReceiver,
    interSender: IntermediateSender
): Int = memScoped {
    fprintf(stderr, "main: started, intermediate pid=%d\n", intermediatePid)

    // Wait for UID/GID mapping request from intermediate process
    try {
        mainReceiver.waitForMappingRequest()
        fprintf(stderr, "main: received mapping request\n")
    } catch (e: Exception) {
        fprintf(stderr, "main: error waiting for mapping request: %s\n", e.message ?: "unknown")
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
        _exit(1)
    }

    fprintf(stderr, "main: writing uid_map for pid %d\n", intermediatePid)
    if (!writeText("/proc/$intermediatePid/uid_map", uidMap)) {
        fprintf(stderr, "main: failed to write uid_map\n")
        _exit(1)
    }

    fprintf(stderr, "main: writing gid_map for pid %d\n", intermediatePid)
    if (!writeText("/proc/$intermediatePid/gid_map", gidMap)) {
        fprintf(stderr, "main: failed to write gid_map\n")
        _exit(1)
    }

    // Notify intermediate process that mapping is written
    try {
        interSender.mappingWritten()
        fprintf(stderr, "main: sent mapping written ack\n")
    } catch (e: Exception) {
        fprintf(stderr, "main: error sending mapping ack: %s\n", e.message ?: "unknown")
        _exit(1)
    }

    // Close intermediate sender (no more messages to send)
    interSender.close()

    // Wait for intermediate process to send init PID
    val initPid: Int = try {
        mainReceiver.waitForIntermediateReady()
    } catch (e: Exception) {
        fprintf(stderr, "main: error waiting for init pid: %s\n", e.message ?: "unknown")
        _exit(1)
        -1  // Never reached, but satisfies type checker
    }
    fprintf(stderr, "main: received init pid=%d\n", initPid)

    // Wait for init process to be ready
    try {
        mainReceiver.waitForInitReady()
        fprintf(stderr, "main: init process is ready\n")
    } catch (e: Exception) {
        fprintf(stderr, "main: error waiting for init ready: %s\n", e.message ?: "unknown")
        _exit(1)
    }

    // Close main receiver
    mainReceiver.close()

    // Wait for intermediate process to exit
    val st = alloc<IntVar>()
    if (waitpid(intermediatePid, st.ptr, 0) == -1) {
        perror("waitpid(intermediate)")
        _exit(1)
    }

    fprintf(stderr, "main: intermediate process exited\n")
    fprintf(stderr, "main: container created with init pid=%d\n", initPid)

    initPid  // Return value
}
