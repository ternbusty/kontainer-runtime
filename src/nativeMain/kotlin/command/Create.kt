package command

import cgroup.setupCgroup
import channel.NotifyListener
import channel.initChannel
import channel.mainChannel
import config.KontainerConfig
import config.saveKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import namespace.calculateCloneFlags
import namespace.hasNamespace
import platform.linux.PR_SET_CHILD_SUBREAPER
import platform.linux.prctl
import platform.posix.*
import process.runMainProcess
import spec.loadSpec
import state.ContainerStatus
import state.containerExists
import state.createState
import state.save
import syscall.applyRlimits
import utils.writeText

/**
 * Create command - Creates a new container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param bundlePath Path to OCI bundle directory (default: current directory)
 * @param pidFile Optional path to write the Stage-2 (init process) PID
 */
@OptIn(ExperimentalForeignApi::class)
fun create(
    rootPath: String,
    containerId: String,
    bundlePath: String = ".",
    pidFile: String? = null,
): Unit =
    memScoped {
        if (containerExists(rootPath, containerId)) {
            Logger.error("container $containerId already exists")
            exit(1)
        }

        val configPath = "$bundlePath/config.json"

        Logger.info("creating container: $containerId")
        Logger.debug("loading spec from $configPath")

        val spec =
            try {
                loadSpec(configPath)
            } catch (e: Exception) {
                Logger.error("failed to load spec: ${e.message ?: "unknown error"}")
                exit(1)
                return
            }

        Logger.debug("loaded spec version ${spec.ociVersion}")

        // Get absolute path of rootfs
        val rootfsPath =
            if (spec.root.path.startsWith("/")) {
                spec.root.path
            } else {
                "$bundlePath/${spec.root.path}"
            }

        Logger.debug("rootfs path: $rootfsPath")
        Logger.debug("main: pid=${getpid()}")

        // Create 2 channels for inter-process communication
        // Main ↔ Stage-2 (init / PID 1)
        val (mainSender, mainReceiver) = mainChannel()
        val (initSender, initReceiver) = initChannel()

        // Create notify socket path
        val notifySocketPath = "/tmp/kontainer-$containerId.sock"

        // Create NotifyListener before forking (will be inherited by child processes)
        val notifyListener =
            try {
                NotifyListener(notifySocketPath)
            } catch (e: Exception) {
                Logger.error("failed to create notify listener: ${e.message ?: "unknown"}")
                exit(1)
                return
            }

        // Fork and exec to trigger bootstrap constructor
        // The bootstrap constructor (in C) will:
        //   - Unshare namespaces (Stage-1)
        //   - Fork Stage-2 (init process / PID 1)
        //   - Exit Stage-1 immediately
        // This ensures all forks happen before Kotlin runtime initialization, avoiding GC deadlock

        // Create sync socketpair for parent-child synchronization
        val syncFds = IntArray(2)
        syncFds.usePinned { pinned ->
            if (socketpair(AF_UNIX, SOCK_STREAM, 0, pinned.addressOf(0)) < 0) {
                perror("socketpair")
                Logger.error("Failed to create sync socketpair")
                notifyListener.close()
                exit(1)
            }
        }
        Logger.debug("created sync socketpair: parent_fd=${syncFds[0]}, child_fd=${syncFds[1]}")

        // Create init pipe (used as a flag to trigger bootstrap mode)
        // bootstrap.c checks _KONTAINER_INITPIPE environment variable to determine if it should run
        val initPipe = IntArray(2)
        initPipe.usePinned { pinned ->
            if (pipe(pinned.addressOf(0)) < 0) {
                perror("pipe")
                Logger.error("Failed to create init pipe")
                close(syncFds[0])
                close(syncFds[1])
                notifyListener.close()
                exit(1)
            }
        }
        Logger.debug("created init pipe: read=${initPipe[0]}, write=${initPipe[1]}")

        // Calculate clone flags from OCI spec namespaces
        // These flags will be passed to bootstrap.c via environment variable
        val cloneFlags = calculateCloneFlags(spec.linux?.namespaces)
        Logger.debug("calculated clone_flags: 0x${cloneFlags.toString(16)}")

        // Close write end of init pipe (no longer used for netlink messages)
        // Init pipe is kept only as a flag to trigger bootstrap mode
        close(initPipe[1])

        // Get current executable path (in parent process, before fork)
        val exePathBuf = allocArray<ByteVar>(4096)
        val exePathLen = readlink("/proc/self/exe", exePathBuf, 4095u)
        if (exePathLen < 0) {
            perror("readlink")
            Logger.error("Failed to read executable path")
            close(initPipe[0])
            close(syncFds[0])
            close(syncFds[1])
            notifyListener.close()
            exit(1)
        }
        exePathBuf[exePathLen.toInt()] = 0.toByte() // null terminate
        Logger.debug("executable path: ${exePathBuf.toKString()}")

        // Set this process as a subreaper
        // This ensures that when Stage-1 exits, Stage-2 reparents to this process
        // instead of init (PID 1), allowing us to wait for the container process
        Logger.debug("setting PR_SET_CHILD_SUBREAPER")
        if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) {
            perror("prctl(PR_SET_CHILD_SUBREAPER)")
            Logger.warn("Failed to set child subreaper, container may not be properly tracked")
        }

        // Fork and exec to trigger bootstrap constructor
        when (val stage1Pid = fork()) {
            -1 -> {
                perror("fork")
                Logger.error("Failed to fork")
                close(initPipe[0])
                close(syncFds[0])
                close(syncFds[1])
                notifyListener.close()
                exit(1)
            }

            0 -> {
                // Child process (will become Stage-1): exec ourselves with environment variables set
                // After exec, bootstrap constructor runs and creates Stage-2 before Kotlin runtime starts

                // Close parent side of sync socketpair
                close(syncFds[0])

                // Set environment variables
                val initPipeStr = initPipe[0].toString()
                val syncPipeStr = syncFds[1].toString()

                // Pass channel FDs to init process (Stage-2)
                val mainSenderFd = mainSender.fd().toString()
                val initReceiverFd = initReceiver.fd().toString()
                val notifyListenerFd = notifyListener.fd().toString()

                // Set clone flags as hex string (e.g., "10000000" for CLONE_NEWUSER)
                val cloneFlagsHex = cloneFlags.toUInt().toString(16)
                setenv("_KONTAINER_CLONE_FLAGS", cloneFlagsHex, 1)
                setenv("_KONTAINER_INITPIPE", initPipeStr, 1)
                setenv("_KONTAINER_SYNCPIPE", syncPipeStr, 1)
                setenv("_KONTAINER_MAIN_SENDER_FD", mainSenderFd, 1)
                setenv("_KONTAINER_INIT_RECEIVER_FD", initReceiverFd, 1)
                setenv("_KONTAINER_NOTIFY_LISTENER_FD", notifyListenerFd, 1)
                setenv("_KONTAINER_BUNDLE_PATH", bundlePath, 1)
                setenv("_KONTAINER_ROOTFS_PATH", rootfsPath, 1)
                setenv("_KONTAINER_NOTIFY_SOCKET", notifySocketPath, 1)

                // Prepare arguments
                val argv = allocArray<CPointerVar<ByteVar>>(3)
                argv[0] = exePathBuf
                argv[1] = "__init__".cstr.ptr
                argv[2] = null

                // Exec ourselves
                val exePath = exePathBuf.toKString()
                execv(exePath, argv)

                // If exec fails, we reach here
                perror("execv")
                _exit(1)
            }

            else -> {
                // Parent process (Create.kt / Main Process):
                // Wait for Stage-1 to complete bootstrap and receive Stage-2 PID

                // Close child side of sync socketpair
                close(syncFds[1])
                close(initPipe[0])

                Logger.debug("forked Stage-1, PID=$stage1Pid, waiting for bootstrap to complete")

                // Setup cgroup for Stage-1 BEFORE syncing with child
                // Stage-1 → Stage-2 are both included in the cgroup (inherited through fork)
                try {
                    setupCgroup(stage1Pid, spec.linux?.cgroupsPath, spec.linux?.resources)
                } catch (e: Exception) {
                    Logger.error("failed to setup cgroup: ${e.message ?: "unknown"}")
                    close(syncFds[0])
                    notifyListener.close()
                    exit(1)
                }

                // Apply rlimits to Stage-1 BEFORE entering user namespace
                // Rlimits are inherited: Stage-1 → Stage-2
                applyRlimits(stage1Pid, spec.process.rlimits)

                // Handle UID/GID mapping if user namespace is configured
                // This must be done BEFORE receiving Stage-2 PID, as Stage-1 waits for mapping completion
                // before forking Stage-2
                val hasUserNamespace = hasNamespace(spec.linux?.namespaces, "user")
                if (hasUserNamespace) {
                    Logger.debug("user namespace configured, handling UID/GID mapping")

                    // Wait for mapping request from Stage-1
                    // Note: enum sync_t is 4 bytes (int) in C
                    val requestBytes = ByteArray(4)
                    requestBytes.usePinned { pinned ->
                        val n = read(syncFds[0], pinned.addressOf(0), 4u)
                        if (n != 4L) {
                            perror("read")
                            Logger.error("Failed to read mapping request from Stage-1 (received $n bytes)")
                            close(syncFds[0])
                            notifyListener.close()
                            exit(1)
                        }
                        val requestValue =
                            (requestBytes[0].toInt() and 0xFF) or
                                ((requestBytes[1].toInt() and 0xFF) shl 8) or
                                ((requestBytes[2].toInt() and 0xFF) shl 16) or
                                ((requestBytes[3].toInt() and 0xFF) shl 24)
                        if (requestValue != 0x40) { // SYNC_USERMAP_PLS = 0x40
                            Logger.error("Invalid mapping request: expected 0x40, got 0x${requestValue.toString(16)}")
                            close(syncFds[0])
                            notifyListener.close()
                            exit(1)
                        }
                    }
                    Logger.debug("received mapping request from Stage-1")

                    // UID/GID mapping protocol:
                    // 1. Stage-1 sends SYNC_USERMAP_PLS (mapping request)
                    // 2. Stage-1 sends its own PID (4 bytes, int32)
                    // 3. Create.kt writes to /proc/<stage1_pid>/uid_map and gid_map
                    // 4. Create.kt sends SYNC_USERMAP_ACK (mapping acknowledgment)
                    // (see bootstrap.c:147-172 for the Stage-1 side)

                    // Read Stage-1 PID from bootstrap
                    val bootstrapPidBytes = ByteArray(4)
                    bootstrapPidBytes.usePinned { pinned ->
                        val n = read(syncFds[0], pinned.addressOf(0), 4u)
                        if (n != 4L) {
                            perror("read")
                            Logger.error("Failed to read bootstrap PID from Stage-1 (received $n bytes)")
                            close(syncFds[0])
                            notifyListener.close()
                            exit(1)
                        }
                    }
                    val bootstrapPid =
                        (bootstrapPidBytes[0].toInt() and 0xFF) or
                            ((bootstrapPidBytes[1].toInt() and 0xFF) shl 8) or
                            ((bootstrapPidBytes[2].toInt() and 0xFF) shl 16) or
                            ((bootstrapPidBytes[3].toInt() and 0xFF) shl 24)
                    Logger.debug("received bootstrap PID from Stage-1: $bootstrapPid")

                    // Get UID/GID mappings from spec
                    val uidMappings = spec.linux?.uidMappings
                    val gidMappings = spec.linux?.gidMappings

                    // Build uid_map content
                    val uidMap =
                        if (!uidMappings.isNullOrEmpty()) {
                            uidMappings.joinToString("\n") { mapping ->
                                "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
                            } + "\n"
                        } else {
                            // Fallback: map container root to current effective UID
                            val hostUid = geteuid()
                            "0 $hostUid 1\n"
                        }

                    // Build gid_map content
                    val gidMap =
                        if (!gidMappings.isNullOrEmpty()) {
                            gidMappings.joinToString("\n") { mapping ->
                                "${mapping.containerID} ${mapping.hostID} ${mapping.size}"
                            } + "\n"
                        } else {
                            // Fallback: map container root to current effective GID
                            val hostGid = getegid()
                            "0 $hostGid 1\n"
                        }

                    Logger.debug("constructed uidMap: ${uidMap.trim()}")
                    Logger.debug("constructed gidMap: ${gidMap.trim()}")

                    // Determine if we need to write to setgroups
                    val isPrivileged = geteuid() == 0u
                    Logger.debug("privileged mode: $isPrivileged (euid=${geteuid()})")

                    try {
                        if (!isPrivileged) {
                            // Disable setgroups for unprivileged user namespaces (CVE-2014-8989)
                            Logger.debug("disabling setgroups for pid $bootstrapPid")
                            writeText("/proc/$bootstrapPid/setgroups", "deny\n")
                        } else {
                            Logger.debug("skipping setgroups write (running as root)")
                        }

                        Logger.debug("writing uid_map for pid $bootstrapPid")
                        writeText("/proc/$bootstrapPid/uid_map", uidMap)

                        Logger.debug("writing gid_map for pid $bootstrapPid")
                        writeText("/proc/$bootstrapPid/gid_map", gidMap)

                        Logger.debug("successfully wrote UID/GID mappings")
                    } catch (e: Exception) {
                        Logger.error("failed to write UID/GID mappings: ${e.message ?: "unknown"}")
                        close(syncFds[0])
                        notifyListener.close()
                        exit(1)
                    }

                    // Send mapping ack to Stage-1
                    // Note: enum sync_t is 4 bytes (int) in C
                    val ackValue = 0x41 // SYNC_USERMAP_ACK = 0x41
                    val ackBytes =
                        byteArrayOf(
                            (ackValue and 0xFF).toByte(),
                            ((ackValue shr 8) and 0xFF).toByte(),
                            ((ackValue shr 16) and 0xFF).toByte(),
                            ((ackValue shr 24) and 0xFF).toByte(),
                        )
                    ackBytes.usePinned { pinned ->
                        val n = write(syncFds[0], pinned.addressOf(0), 4u)
                        if (n != 4L) {
                            perror("write")
                            Logger.error("Failed to send mapping ack to Stage-1")
                            close(syncFds[0])
                            notifyListener.close()
                            exit(1)
                        }
                    }
                    Logger.debug("sent mapping ack to Stage-1")
                }

                // Wait for Stage-2 PID from bootstrap
                val receivedPidBytes = ByteArray(4)
                receivedPidBytes.usePinned { pinned ->
                    val n = read(syncFds[0], pinned.addressOf(0), 4u)
                    if (n < 0) {
                        perror("read")
                        Logger.error("Failed to read PID from sync pipe")
                        close(syncFds[0])
                        notifyListener.close()
                        exit(1)
                    }
                    if (n.toLong() != 4L) {
                        Logger.error("Incomplete read from sync pipe: got $n bytes")
                        close(syncFds[0])
                        notifyListener.close()
                        exit(1)
                    }
                }

                // Decode PID from bytes (little-endian)
                val stage2Pid =
                    (receivedPidBytes[0].toInt() and 0xFF) or
                        ((receivedPidBytes[1].toInt() and 0xFF) shl 8) or
                        ((receivedPidBytes[2].toInt() and 0xFF) shl 16) or
                        ((receivedPidBytes[3].toInt() and 0xFF) shl 24)

                Logger.debug("received Stage-2 PID from bootstrap: $stage2Pid")

                close(syncFds[0])

                // Close senders and receivers that this process doesn't need
                // Keep mainReceiver and initSender - will be used to communicate with Stage-2
                mainSender.close()
                initReceiver.close()

                // Close notify listener in main process (only used by Stage-2)
                notifyListener.close()

                val initPid =
                    runMainProcess(
                        spec,
                        containerId,
                        bundlePath,
                        stage2Pid,
                        mainReceiver,
                        initSender,
                    )

                // Save container state for start command
                Logger.debug("saving container state")
                try {
                    val state =
                        createState(
                            ociVersion = spec.ociVersion,
                            containerId = containerId,
                            status = ContainerStatus.CREATED,
                            pid = initPid,
                            bundle = bundlePath,
                            annotations = null,
                        )
                    state.save(rootPath)
                } catch (e: Exception) {
                    Logger.error("failed to save container state: ${e.message ?: "unknown"}")
                    exit(1)
                }

                // Save internal configuration (independent of bundle)
                Logger.debug("saving kontainer config")
                try {
                    val kontainerConfig =
                        KontainerConfig(
                            cgroupPath = spec.linux?.cgroupsPath,
                        )
                    saveKontainerConfig(kontainerConfig, rootPath, containerId)
                } catch (e: Exception) {
                    Logger.error("failed to save kontainer config: ${e.message ?: "unknown"}")
                    exit(1)
                }

                // Write PID to file if --pid-file was specified
                if (pidFile != null) {
                    Logger.debug("writing PID to file: $pidFile")
                    try {
                        writeText(pidFile, "$initPid")
                        Logger.debug("successfully wrote PID $initPid to $pidFile")
                    } catch (e: Exception) {
                        Logger.error("failed to write PID file: ${e.message ?: "unknown"}")
                        exit(1)
                    }
                }

                Logger.info("container $containerId created with init PID $initPid")
                Logger.info("run 'kontainer-runtime start $containerId' to start the container")

                exit(0)
            }
        }
    }
