package process

import cgroup.Cgroup
import cgroup.CgroupV2
import cgroup.DeviceCgroup
import channel.*
import channel.Message
import config.KontainerConfig
import config.saveKontainerConfig
import hook.runHooks
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import logger.Logger
import platform.posix.*
import rootfs.handleMountFdRequest
import seccomp.seccompUsesNotify
import seccomp.sendToSeccompListener
import seccomp.validateSeccompFlags
import spec.LinuxIdMapping
import spec.Spec
import state.ContainerStatus
import state.State
import state.createState
import state.deleteContainerDir
import state.deleteNotifySocket
import state.save
import syscall.Syscall
import utils.FileSystem

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
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
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
    stage1InCgroup: Boolean,
): Unit =
    memScoped {
        Logger.setContext("main")
        Logger.debug("started, stage-1 pid=$stage1Pid")

        // Resolve the OCI spec cgroupsPath (absolute → literal; relative or
        // unspecified → nested under our runtime's subtree) and stash the
        // resolved path so Delete can use it later. See
        // CgroupV2.resolveCgroupPath() for the rules.
        val resolvedCgroupPath = CgroupV2.resolveCgroupPath(spec.linux?.cgroupsPath, containerId)

        // The cgroup itself (directory, controllers, resource limits, device
        // eBPF program) was prepared by Create.kt BEFORE Stage-1 was spawned, so
        // that Stage-1 could be created directly inside it with
        // clone3(CLONE_INTO_CGROUP); see prepareContainerCgroup().

        // Handle UID/GID mapping only when CREATING a new user namespace.
        // When joining an existing user namespace (path is set), the bootstrap
        // doesn't send SYNC_USERMAP_PLS — the mapping already exists.
        val hasUserNamespace = spec.createsNamespace("user")
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
            val uidMap = buildIdMapping(spec.linux?.uidMappings, syscall.geteuid())
            val gidMap = buildIdMapping(spec.linux?.gidMappings, syscall.getegid())

            Logger.debug("constructed uidMap: ${uidMap.trim()}")
            Logger.debug("constructed gidMap: ${gidMap.trim()}")

            // Determine if we need to write to setgroups
            val isPrivileged = syscall.geteuid() == 0u
            Logger.debug("privileged mode: $isPrivileged (euid=${syscall.geteuid()})")

            if (!isPrivileged) {
                // Disable setgroups for unprivileged user namespaces (CVE-2014-8989)
                Logger.debug("disabling setgroups for pid $bootstrapPid")
                fs.writeTextFile("/proc/$bootstrapPid/setgroups", "deny\n")
            } else {
                Logger.debug("skipping setgroups write (running as root)")
            }

            Logger.debug("writing uid_map for pid $bootstrapPid")
            fs.writeTextFile("/proc/$bootstrapPid/uid_map", uidMap)

            Logger.debug("writing gid_map for pid $bootstrapPid")
            fs.writeTextFile("/proc/$bootstrapPid/gid_map", gidMap)

            Logger.debug("successfully wrote UID/GID mappings")

            // Send mapping ack to Stage-1
            // Note: enum sync_t is 4 bytes (int) in C
            val ackValue = 0x41 // SYNC_USERMAP_ACK = 0x41
            writeInt32(syncFd, ackValue, "Failed to send mapping ack to Stage-1")
            Logger.debug("sent mapping ack to Stage-1")
        }

        // Handle timens_offsets only when CREATING a new time namespace.
        // When joining (path is set), bootstrap doesn't unshare CLONE_NEWTIME
        // and doesn't send SYNC_TIMEOFFSETS_PLS.
        val hasTimeNamespace = spec.createsNamespace("time")
        val timeOffsets = spec.linux?.timeOffsets
        if (hasTimeNamespace && !timeOffsets.isNullOrEmpty()) {
            val request = readInt32(syncFd, "Failed to read timens request from Stage-1")
            if (request != 0x42) { // SYNC_TIMEOFFSETS_PLS
                throw Exception("Expected SYNC_TIMEOFFSETS_PLS (0x42), got 0x${request.toString(16)}")
            }
            val timensPid = readInt32(syncFd, "Failed to read PID for timens_offsets")
            Logger.debug("received timens_offsets request for pid $timensPid")

            // Each line: "<clock-id> <offset-secs> <offset-nanosecs>"
            // Clock names from the spec map to kernel identifiers.
            for ((clockName, offset) in timeOffsets) {
                val line = "$clockName ${offset.secs} ${offset.nanosecs}\n"
                Logger.debug("writing timens_offset: ${line.trim()}")
                fs.writeTextFile("/proc/$timensPid/timens_offsets", line)
            }

            val ackValue = 0x43 // SYNC_TIMEOFFSETS_ACK
            writeInt32(syncFd, ackValue, "Failed to send timens ack")
            Logger.debug("sent timens_offsets ack")
        } else if (hasTimeNamespace) {
            // Time namespace configured but no offsets — still need to
            // consume the sync messages to keep the protocol in sync.
            val request = readInt32(syncFd, "Failed to read timens request from Stage-1")
            if (request == 0x42) {
                val timensPid = readInt32(syncFd, "Failed to read PID for timens_offsets")
                Logger.debug("time namespace with no offsets, acking (pid=$timensPid)")
                val ackValue = 0x43 // SYNC_TIMEOFFSETS_ACK
                writeInt32(syncFd, ackValue, "Failed to send timens ack")
            }
        }

        // Wait for Stage-2 PID from bootstrap
        val stage2Pid = readInt32(syncFd, "Failed to read Stage-2 PID from sync pipe")
        Logger.debug("received Stage-2 PID from bootstrap: $stage2Pid")

        // Place Stage-2 (the long-lived init process) into the container cgroup.
        // Fast path: Stage-1 was created inside the cgroup with
        // clone3(CLONE_INTO_CGROUP) and Stage-2 inherited it at clone time, so
        // nothing to do. Fallback (old kernels / clone3 refused): migrate
        // Stage-2 by writing its PID to cgroup.procs. Stage-2 is guaranteed to
        // be alive here — it is blocked in bootstrap.c waiting for
        // SYNC_GRANDCHILD from Stage-1.
        if (stage1InCgroup) {
            Logger.debug("Stage-2 inherited cgroup $resolvedCgroupPath from Stage-1 (CLONE_INTO_CGROUP)")
        } else if (resolvedCgroupPath.isNotEmpty()) {
            cgroup.addProcess(stage2Pid, resolvedCgroupPath)
        }

        // Reset CPU affinity after cgroup assignment so the kernel clamps it
        // to cpuset.cpus.  Matches runc's tryResetCPUAffinity.
        resetCpuAffinity(stage2Pid)

        // Move host network devices into the container's network namespace.
        // Done from the host (main process) via RTM_SETLINK + IFLA_NET_NS_PID.
        // The init process renames them later from inside the namespace.
        if (spec.hasNamespace("network")) {
            spec.linux?.netDevices?.let { netDevices ->
                if (netDevices.isNotEmpty()) {
                    network.moveDevices(netDevices, stage2Pid)
                }
            }
        }

        // Signal Stage-1 that cgroup setup is complete. Stage-1 will then
        // send SYNC_GRANDCHILD to Stage-2, which will unshare(CLONE_NEWCGROUP)
        // AFTER being in the container's cgroup — so /proc/self/cgroup shows
        // "0::/" (the cgroup namespace root) instead of the host path.
        val syncCgroupDone = 0x46 // SYNC_CGROUP_DONE
        writeInt32(syncFd, syncCgroupDone, "Failed to send SYNC_CGROUP_DONE to Stage-1")
        Logger.debug("sent SYNC_CGROUP_DONE to Stage-1")
        close(syncFd)

        // Apply rlimits to Stage-2.  We target Stage-2 directly instead of
        // Stage-1 because Stage-1 may already have exited by the time we
        // reach this point.  prlimit64 works on any live process, and
        // Stage-2 has not yet started the user workload (it is still waiting
        // for the start signal), so the limits are in effect before any
        // container process runs.
        syscall.applyRlimits(stage2Pid, spec.process.rlimits)

        // Close senders and receivers that this process doesn't need
        // Keep mainReceiver and initSender - will be used to communicate with Stage-2
        mainSender.close()
        initReceiver.close()

        // Close notify listener in main process (only used by Stage-2)
        notifyListener.close()

        // Message loop: handle MountFdRequest (idmap), SeccompNotify,
        // and InitReady from the init process. Init sends these in order
        // as it progresses through rootfs setup → seccomp → ready, but
        // the loop handles them generically.
        Logger.debug("entering init message loop")
        var initDone = false
        while (!initDone) {
            val (msg, fd) = mainReceiver.receiveNextMessage()
            when (msg) {
                is Message.MountFdRequest -> {
                    Logger.debug("received mount fd request (implied=${msg.implied}, recursive=${msg.recursive})")
                    if (fd < 0) {
                        throw Exception("MountFdRequest received without tree fd")
                    }
                    handleMountFdRequest(
                        treeFd = fd,
                        uidMap = msg.uidMap,
                        gidMap = msg.gidMap,
                        recursive = msg.recursive,
                        implied = msg.implied,
                        stage2Pid = stage2Pid,
                    )
                    initSender.mountFdDone()
                    Logger.debug("sent mount fd done")
                }
                is Message.BindSourceRequest -> {
                    Logger.debug("received bind source request for ${msg.source} (isRbind=${msg.isRbind})")
                    // Clone the mount at the source path using open_tree(OPEN_TREE_CLONE).
                    // This creates a detached mount tree fd that the init process
                    // can install via move_mount(). The O_PATH + /proc/self/fd/N
                    // approach fails with EINVAL on kernel 6.17+ in user namespaces.
                    var openTreeFlags = platform.linux._OPEN_TREE_CLONE() or platform.linux._OPEN_TREE_CLOEXEC()
                    if (msg.isRbind) {
                        openTreeFlags = openTreeFlags or platform.linux._AT_RECURSIVE()
                    }
                    val treeFd = platform.linux._open_tree(-1, msg.source, openTreeFlags)
                    if (treeFd < 0) {
                        val errNum = errno
                        Logger.error("open_tree(${msg.source}) failed (errno=$errNum)")
                        throw Exception("open_tree failed for bind source ${msg.source} (errno=$errNum)")
                    }
                    initSender.bindSourceDone(treeFd)
                    close(treeFd)
                    Logger.debug("sent open_tree fd for ${msg.source}")
                }
                is Message.SeccompNotify -> {
                    Logger.debug("received seccomp notify FD: $fd")
                    if (fd < 0) {
                        throw Exception("SeccompNotify received without fd")
                    }
                    // Forward the seccomp notify FD to the listener if configured.
                    // If the listener connection fails, we must still notify init
                    // (otherwise it hangs waiting for the done signal), and then
                    // propagate the error.
                    var seccompError: Exception? = null
                    spec.linux?.seccomp?.listenerPath?.let { listenerPath ->
                        if (listenerPath.isNotEmpty()) {
                            Logger.debug("forwarding seccomp notify FD to listener: $listenerPath")
                            val containerState =
                                State(
                                    ociVersion = spec.ociVersion,
                                    id = containerId,
                                    status = ContainerStatus.CREATING,
                                    pid = stage2Pid,
                                    bundle = bundlePath,
                                    annotations = null,
                                    created = null,
                                )
                            try {
                                sendToSeccompListener(listenerPath, containerState, fd, spec.linux.seccomp.listenerMetadata)
                                Logger.debug("forwarded seccomp notify FD to listener")
                            } catch (e: Exception) {
                                seccompError = e
                                Logger.error("failed to forward seccomp notify FD: ${e.message}")
                            }
                        } else {
                            seccompError = Exception("seccomp listener path is empty")
                        }
                    } ?: run {
                        Logger.warn("seccomp notify FD received but no listenerPath specified")
                    }
                    // Close our copy of the seccomp notify FD. The seccomp
                    // agent now holds the only reference; when it dies, the
                    // kernel returns ENOSYS to notified syscalls.
                    close(fd)
                    initSender.seccompNotifyDone()
                    Logger.debug("sent seccomp notify done signal")
                    if (seccompError != null) {
                        throw seccompError!!
                    }
                }
                is Message.InitReady -> {
                    Logger.debug("init process is ready")
                    // Apply pids.max NOW — init has finished setup and is
                    // waiting for the start signal.  This avoids hitting
                    // pids.max=1 (from pids.limit=0) during init setup where
                    // Kotlin/Native runtime threads are still being created.
                    (cgroup as? CgroupV2)?.applyDeferredPids(resolvedCgroupPath, spec.linux?.resources)
                    initDone = true
                }
                else -> {
                    throw Exception("Unexpected message from init: $msg")
                }
            }
        }

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
                annotations = spec.annotations,
            )
        state.save(fs, rootPath)

        // Save internal configuration (independent of bundle). Store the
        // *resolved* cgroup path (relative to /sys/fs/cgroup, no leading
        // slash) so Delete.cleanup() removes the directory we actually
        // created, not whatever spec.linux.cgroupsPath was.
        Logger.debug("saving kontainer config")
        val kontainerConfig =
            KontainerConfig(
                cgroupPath = resolvedCgroupPath,
            )
        saveKontainerConfig(fs, kontainerConfig, rootPath, containerId)

        // Write PID to file if --pid-file was specified
        if (pidFile != null) {
            Logger.debug("writing PID to file: $pidFile")
            fs.writeTextFile(pidFile, "$stage2Pid")
            Logger.debug("successfully wrote PID $stage2Pid to $pidFile")
        }

        Logger.info("container $containerId created with init PID $stage2Pid")
        Logger.info("run 'kontainer-runtime start $containerId' to start the container")

        // Run prestart hooks AFTER state is saved (so state.json is valid for the
        // hook to read) and BEFORE start. The hook's stdin sees the State JSON
        // with status="created". Older runtime-tools tests still use this hook
        // point; createRuntime/createContainer are the modern equivalents.
        if (spec.hooks?.prestart != null) {
            if (!runHooks(spec.hooks.prestart, state, phase = "prestart")) {
                Logger.error("prestart hook failed; aborting container creation")
                cleanupContainer(syscall, fs, cgroup, rootPath, containerId, stage2Pid, resolvedCgroupPath)
                exit(1)
            }
        }
        // createRuntime is the modern equivalent of prestart and runs at the
        // same point in the lifecycle (after create, before start) from the
        // runtime's namespace. Many specs include both pointing at different
        // programs, so we run both lists in order.
        if (spec.hooks?.createRuntime != null) {
            if (!runHooks(spec.hooks.createRuntime, state, phase = "createRuntime")) {
                Logger.error("createRuntime hook failed; aborting container creation")
                cleanupContainer(syscall, fs, cgroup, rootPath, containerId, stage2Pid, resolvedCgroupPath)
                exit(1)
            }
        }

        // Return control to the caller. When invoked standalone
        // (create subcommand), main() returns and the process exits
        // normally.  When invoked from run(), the caller continues
        // with start + optional wait.
    }

/**
 * Clean up container resources on create failure: kill init, remove cgroup,
 * remove state directory. Best-effort — individual failures are logged but
 * do not prevent the rest of the cleanup from running.
 */
@OptIn(ExperimentalForeignApi::class)
private fun cleanupContainer(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    initPid: Int,
    cgroupPath: String,
) {
    // Kill init process
    try {
        syscall.killProcess(initPid, SIGKILL)
        waitpid(initPid, null, 0)
    } catch (_: Exception) {
    }

    // Remove cgroup
    try {
        cgroup.cleanup(cgroupPath)
    } catch (_: Exception) {
    }

    // Remove notify socket and state directory
    try {
        deleteNotifySocket(rootPath, containerId)
    } catch (_: Exception) {
    }
    try {
        deleteContainerDir(fs, rootPath, containerId)
    } catch (_: Exception) {
    }
}

/**
 * Entry point for main process
 * Handles errors and performs cleanup on failure
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainProcess(
    syscall: Syscall,
    fs: FileSystem,
    cgroup: Cgroup,
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
    stage1InCgroup: Boolean = false,
) {
    try {
        runMainProcessInternal(
            syscall,
            fs,
            cgroup,
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
            stage1InCgroup,
        )
    } catch (e: Exception) {
        val errMsg = e.message ?: "unknown"
        Logger.error("main process failed: $errMsg")

        // For runc bats compatibility, output init errors in runc's format.
        // runc hardcodes "runc run failed: unable to start container process:
        // error during container init: <error>" — compatible runtimes must
        // match this pattern so bats assertion globs pass.
        val initPrefix = "Error: Init process failed: "
        if (errMsg.startsWith(initPrefix)) {
            val innerError = errMsg.removePrefix(initPrefix)
            Logger.error(
                "runc run failed: unable to start container process: " +
                    "error during container init: $innerError",
            )
        }

        close(syncFd)
        notifyListener.close()
        // Best-effort cleanup of container state directory so a retry
        // with the same container ID does not get "already exists".
        try {
            deleteNotifySocket(rootPath, containerId)
        } catch (_: Exception) {
        }
        try {
            deleteContainerDir(fs, rootPath, containerId)
        } catch (_: Exception) {
        }
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
