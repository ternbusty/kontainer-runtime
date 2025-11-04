package process

import cgroup.setupCgroup
import channel.*
import config.KontainerConfig
import config.saveKontainerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import logger.Logger
import platform.posix.*
import seccomp.sendToSeccompListener
import spec.LinuxIdMapping
import spec.Spec
import state.ContainerStatus
import state.State
import state.createState
import state.save
import syscall.applyRlimits
import utils.writeTextFile

/**
 * Main process - Parent process (internal implementation)
 *
 * This process manages the init process (Stage-2/PID 1).
 *
 * Responsibilities:
 * - Setup cgroup for Stage-1 (inherited by Stage-2)
 * - Apply rlimits to Stage-1 (inherited by Stage-2)
 * - Handle UID/GID mapping protocol with Stage-1
 * - Receive Stage-2 PID from bootstrap
 * - Handle seccomp notify FD if configured
 * - Wait for init process to be ready
 * - Save container state and config
 * - Write PID to file if specified
 */
@OptIn(ExperimentalForeignApi::class)
private fun runMainProcessInternal(
    stage1Pid: Int,
    syncFd: Int,
    spec: Spec,
    containerId: String,
    bundlePath: String,
    rootPath: String,
    pidFile: String?,
    notifyListener: NotifyListener,
    mainSender: MainSender,
    mainReceiver: MainReceiver,
    initSender: InitSender,
    initReceiver: InitReceiver,
): Unit =
    memScoped {
        Logger.setContext("main")
        Logger.debug("started, stage-1 pid=$stage1Pid")

        // Setup cgroup for Stage-1 BEFORE syncing with child
        // Stage-1 → Stage-2 are both included in the cgroup (inherited through fork)
        setupCgroup(stage1Pid, spec.linux?.cgroupsPath, spec.linux?.resources)

        // Apply rlimits to Stage-1 BEFORE entering user namespace
        // Rlimits are inherited: Stage-1 → Stage-2
        applyRlimits(stage1Pid, spec.process.rlimits)

        // Handle UID/GID mapping if user namespace is configured
        // This must be done BEFORE receiving Stage-2 PID, as Stage-1 waits for mapping completion
        // before forking Stage-2
        val hasUserNamespace = spec.hasNamespace("user")
        if (hasUserNamespace) {
            Logger.debug("user namespace configured, handling UID/GID mapping")

            // Wait for mapping request from Stage-1
            // Note: enum sync_t is 4 bytes (int) in C
            val requestValue = readInt32(syncFd, "Failed to read mapping request from Stage-1")
            if (requestValue != 0x40) { // SYNC_USERMAP_PLS = 0x40
                throw Exception("Invalid mapping request: expected 0x40, got 0x${requestValue.toString(16)}")
            }
            Logger.debug("received mapping request from Stage-1")

            // UID/GID mapping protocol:
            // 1. Stage-1 sends SYNC_USERMAP_PLS (mapping request)
            // 2. Stage-1 sends its own PID (4 bytes, int32)
            // 3. Main Process writes to /proc/<stage1_pid>/uid_map and gid_map
            // 4. Main Process sends SYNC_USERMAP_ACK (mapping acknowledgment)
            // (see bootstrap.c:141-167 for the Stage-1 side)

            // Read Stage-1 PID from bootstrap
            val bootstrapPid = readInt32(syncFd, "Failed to read bootstrap PID from Stage-1")
            Logger.debug("received bootstrap PID from Stage-1: $bootstrapPid")

            // Build uid_map and gid_map content
            val uidMap = buildIdMapping(spec.linux?.uidMappings, geteuid())
            val gidMap = buildIdMapping(spec.linux?.gidMappings, getegid())

            Logger.debug("constructed uidMap: ${uidMap.trim()}")
            Logger.debug("constructed gidMap: ${gidMap.trim()}")

            // Determine if we need to write to setgroups
            val isPrivileged = geteuid() == 0u
            Logger.debug("privileged mode: $isPrivileged (euid=${geteuid()})")

            if (!isPrivileged) {
                // Disable setgroups for unprivileged user namespaces (CVE-2014-8989)
                Logger.debug("disabling setgroups for pid $bootstrapPid")
                writeTextFile("/proc/$bootstrapPid/setgroups", "deny\n")
            } else {
                Logger.debug("skipping setgroups write (running as root)")
            }

            Logger.debug("writing uid_map for pid $bootstrapPid")
            writeTextFile("/proc/$bootstrapPid/uid_map", uidMap)

            Logger.debug("writing gid_map for pid $bootstrapPid")
            writeTextFile("/proc/$bootstrapPid/gid_map", gidMap)

            Logger.debug("successfully wrote UID/GID mappings")

            // Send mapping ack to Stage-1
            // Note: enum sync_t is 4 bytes (int) in C
            val ackValue = 0x41 // SYNC_USERMAP_ACK = 0x41
            writeInt32(syncFd, ackValue, "Failed to send mapping ack to Stage-1")
            Logger.debug("sent mapping ack to Stage-1")
        }

        // Wait for Stage-2 PID from bootstrap
        val stage2Pid = readInt32(syncFd, "Failed to read Stage-2 PID from sync pipe")
        Logger.debug("received Stage-2 PID from bootstrap: $stage2Pid")

        close(syncFd)

        // Close senders and receivers that this process doesn't need
        // Keep mainReceiver and initSender - will be used to communicate with Stage-2
        mainSender.close()
        initReceiver.close()

        // Close notify listener in main process (only used by Stage-2)
        notifyListener.close()

        // Check if seccomp notify is used in the spec
        val hasSeccompNotify =
            spec.linux
                ?.seccomp
                ?.syscalls
                ?.any { it.action == "SCMP_ACT_NOTIFY" } ?: false
        if (hasSeccompNotify) {
            Logger.debug("seccomp notify is enabled, waiting for notify FD")
            val notifyFd = mainReceiver.waitForSeccompRequest()
            Logger.debug("received seccomp notify FD: $notifyFd")

            // If listenerPath is specified, forward the FD to the listener
            spec.linux.seccomp.listenerPath?.let { listenerPath ->
                Logger.debug("forwarding seccomp notify FD to listener: $listenerPath")
                val containerState =
                    State(
                        ociVersion = spec.ociVersion,
                        id = containerId,
                        status = ContainerStatus.CREATING,
                        pid = stage2Pid,
                        bundle = bundlePath,
                        annotations = null,
                        created = null, // Not set yet during creating phase
                    )
                sendToSeccompListener(listenerPath, containerState, notifyFd)
                Logger.debug("forwarded seccomp notify FD to listener")
            } ?: run {
                Logger.warn("seccomp notify FD received but no listenerPath specified")
            }

            initSender.seccompNotifyDone()
            Logger.debug("sent seccomp notify done signal")
        }

        mainReceiver.waitForInitReady()
        Logger.debug("init process is ready")

        mainReceiver.close()
        initSender.close()

        Logger.info("container created with init pid=$stage2Pid")

        // Save container state for start command
        Logger.debug("saving container state")
        val state =
            createState(
                ociVersion = spec.ociVersion,
                containerId = containerId,
                status = ContainerStatus.CREATED,
                pid = stage2Pid,
                bundle = bundlePath,
                annotations = null,
            )
        state.save(rootPath)

        // Save internal configuration (independent of bundle)
        Logger.debug("saving kontainer config")
        val kontainerConfig =
            KontainerConfig(
                cgroupPath = spec.linux?.cgroupsPath,
            )
        saveKontainerConfig(kontainerConfig, rootPath, containerId)

        // Write PID to file if --pid-file was specified
        if (pidFile != null) {
            Logger.debug("writing PID to file: $pidFile")
            writeTextFile(pidFile, "$stage2Pid")
            Logger.debug("successfully wrote PID $stage2Pid to $pidFile")
        }

        Logger.info("container $containerId created with init PID $stage2Pid")
        Logger.info("run 'kontainer-runtime start $containerId' to start the container")

        exit(0)
    }

/**
 * Entry point for main process
 * Handles errors and performs cleanup on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainProcess(
    stage1Pid: Int,
    syncFd: Int,
    spec: Spec,
    containerId: String,
    bundlePath: String,
    rootPath: String,
    pidFile: String?,
    notifyListener: NotifyListener,
    mainSender: MainSender,
    mainReceiver: MainReceiver,
    initSender: InitSender,
    initReceiver: InitReceiver,
) {
    try {
        runMainProcessInternal(
            stage1Pid,
            syncFd,
            spec,
            containerId,
            bundlePath,
            rootPath,
            pidFile,
            notifyListener,
            mainSender,
            mainReceiver,
            initSender,
            initReceiver,
        )
    } catch (e: Exception) {
        Logger.error("main process failed: ${e.message ?: "unknown"}")
        close(syncFd)
        notifyListener.close()
        _exit(1)
    }
}

/**
 * Read a 4-byte integer from a file descriptor (little-endian)
 * @param fd File descriptor to read from
 * @param errorMessage Error message prefix
 * @return Decoded Int32 value
 * @throws Exception if read fails or doesn't return 4 bytes
 */
@OptIn(ExperimentalForeignApi::class)
fun readInt32(
    fd: Int,
    errorMessage: String,
): Int {
    val bytes = ByteArray(4)
    bytes.usePinned { pinned ->
        val n = read(fd, pinned.addressOf(0), 4u)
        if (n != 4L) {
            perror("read")
            Logger.error("$errorMessage (received $n bytes)")
            throw Exception("$errorMessage (received $n bytes)")
        }
    }
    // Decode little-endian
    return (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16) or
        ((bytes[3].toInt() and 0xFF) shl 24)
}

/**
 * Write a 4-byte integer to a file descriptor (little-endian)
 * @param fd File descriptor to write to
 * @param value Integer value to write
 * @param errorMessage Error message prefix
 * @throws Exception if write fails or doesn't write 4 bytes
 */
@OptIn(ExperimentalForeignApi::class)
fun writeInt32(
    fd: Int,
    value: Int,
    errorMessage: String,
) {
    val bytes =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    bytes.usePinned { pinned ->
        val n = write(fd, pinned.addressOf(0), 4u)
        if (n != 4L) {
            perror("write")
            Logger.error(errorMessage)
            throw Exception(errorMessage)
        }
    }
}

/**
 * Build ID mapping string from OCI spec mappings
 * @param mappings List of ID mappings from OCI spec (can be null)
 * @param fallbackId Fallback ID to use if mappings is null/empty
 * @return Formatted mapping string for uid_map/gid_map
 */
fun buildIdMapping(
    mappings: List<LinuxIdMapping>?,
    fallbackId: UInt,
): String =
    if (!mappings.isNullOrEmpty()) {
        mappings.joinToString("\n") { mapping ->
            "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
        } + "\n"
    } else {
        "0 $fallbackId 1\n"
    }
