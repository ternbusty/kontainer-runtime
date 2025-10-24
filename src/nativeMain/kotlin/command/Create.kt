package command

import channel.NotifyListener
import channel.initChannel
import channel.intermediateChannel
import channel.mainChannel
import config.KontainerConfig
import config.saveKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import netlink.*
import platform.posix.*
import process.runMainProcess
import spec.loadSpec
import state.ContainerStatus
import state.containerExists
import state.createState
import state.save
import utils.writeText

/**
 * Create command - Creates a new container
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param bundlePath Path to OCI bundle directory (default: current directory)
 * @param pidFile Optional path to write the container's init process PID
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

        // Create 3 channels for inter-process communication
        val (mainSender, mainReceiver) = mainChannel()
        val (interSender, interReceiver) = intermediateChannel()
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

        // Fork intermediate process using bootstrap constructor
        // This ensures fork happens before Kotlin runtime, avoiding GC deadlock

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

        // Create init pipe for passing netlink message
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

        // Prepare netlink message with configuration
        val netlinkMsg = NetlinkMessage(INIT_MSG)
        // For now, just send minimal data (clone flags will be ignored by intermediate process)
        netlinkMsg.addInt32(CLONE_FLAGS_ATTR, 0u)
        netlinkMsg.addString(CONTAINER_ID_ATTR, containerId)
        netlinkMsg.addString(BUNDLE_PATH_ATTR, bundlePath)
        netlinkMsg.addString(ROOTFS_PATH_ATTR, rootfsPath)

        // Check if user namespace is configured in spec
        val hasUserNamespace = spec.linux?.namespaces?.any { it.type == "user" } ?: false
        netlinkMsg.addInt32(USER_NS_ATTR, if (hasUserNamespace) 1u else 0u)

        val msgBytes = netlinkMsg.serialize()
        Logger.debug("prepared netlink message: ${msgBytes.size} bytes")

        // Write netlink message to init pipe
        msgBytes.usePinned { pinned ->
            val written = write(initPipe[1], pinned.addressOf(0), msgBytes.size.convert())
            if (written < 0) {
                perror("write")
                Logger.error("Failed to write netlink message to init pipe")
                close(initPipe[0])
                close(initPipe[1])
                close(syncFds[0])
                close(syncFds[1])
                notifyListener.close()
                exit(1)
            }
            Logger.debug("wrote $written bytes to init pipe")
        }
        close(initPipe[1]) // Close write end

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

        // Fork and exec to trigger bootstrap constructor
        when (val intermediatePid = fork()) {
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
                // Child process: exec ourselves with environment variables set
                // This will trigger bootstrap constructor which will fork intermediate process

                // Close parent side of sync socketpair
                close(syncFds[0])

                // Set environment variables
                val initPipeStr = initPipe[0].toString()
                val syncPipeStr = syncFds[1].toString()

                // Pass channel FDs to intermediate process
                val mainSenderFd = mainSender.fd().toString()
                val interReceiverFd = interReceiver.fd().toString()
                val initReceiverFd = initReceiver.fd().toString()

                setenv("_KONTAINER_INITPIPE", initPipeStr, 1)
                setenv("_KONTAINER_SYNCPIPE", syncPipeStr, 1)
                setenv("_KONTAINER_MAIN_SENDER_FD", mainSenderFd, 1)
                setenv("_KONTAINER_INTER_RECEIVER_FD", interReceiverFd, 1)
                setenv("_KONTAINER_INIT_RECEIVER_FD", initReceiverFd, 1)
                setenv("_KONTAINER_BUNDLE_PATH", bundlePath, 1)
                setenv("_KONTAINER_ROOTFS_PATH", rootfsPath, 1)
                setenv("_KONTAINER_NOTIFY_SOCKET", notifySocketPath, 1)

                // Prepare arguments
                val argv = allocArray<CPointerVar<ByteVar>>(3)
                argv[0] = exePathBuf
                argv[1] = "__intermediate__".cstr.ptr
                argv[2] = null

                // Exec ourselves
                val exePath = exePathBuf.toKString()
                execv(exePath, argv)

                // If exec fails, we reach here
                perror("execv")
                _exit(1)
            }

            else -> {
                // Parent process: wait for child to exec and bootstrap to fork

                // Close child side of sync socketpair
                close(syncFds[1])
                close(initPipe[0])

                Logger.debug("forked child process, PID=$intermediatePid, waiting for bootstrap to complete")

                // Wait for intermediate process PID from bootstrap
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
                val actualIntermediatePid =
                    (receivedPidBytes[0].toInt() and 0xFF) or
                        ((receivedPidBytes[1].toInt() and 0xFF) shl 8) or
                        ((receivedPidBytes[2].toInt() and 0xFF) shl 16) or
                        ((receivedPidBytes[3].toInt() and 0xFF) shl 24)

                Logger.debug("received intermediate PID from bootstrap: $actualIntermediatePid")

                close(syncFds[0])

                // Now actualIntermediatePid is the real intermediate process
                // We need to wait for it to send us the init PID

                // Close senders and receivers that this process doesn't need
                // Keep initSender - will be used to send messages to init process
                mainSender.close()
                interReceiver.close()
                initReceiver.close()

                // Close notify listener in main process (only used by init process)
                notifyListener.close()

                val initPid =
                    runMainProcess(
                        spec,
                        containerId,
                        bundlePath,
                        actualIntermediatePid,
                        mainReceiver,
                        interSender,
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
