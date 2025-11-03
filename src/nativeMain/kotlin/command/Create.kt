package command

import channel.NotifyListener
import channel.initChannel
import channel.mainChannel
import kotlinx.cinterop.*
import logger.Logger
import namespace.calculateCloneFlags
import platform.linux.PR_SET_CHILD_SUBREAPER
import platform.linux.prctl
import platform.posix.*
import process.runMainProcess
import spec.loadSpec
import state.containerExists

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

        // Calculate clone flags from OCI spec namespaces
        // These flags will be passed to bootstrap.c via environment variable
        val cloneFlags = calculateCloneFlags(spec.linux?.namespaces)
        Logger.debug("calculated clone_flags: 0x${cloneFlags.toString(16)}")

        // Get current executable path (in parent process, before fork)
        val exePathBuf = allocArray<ByteVar>(4096)
        val exePathLen = readlink("/proc/self/exe", exePathBuf, 4095u)
        if (exePathLen < 0) {
            perror("readlink")
            Logger.error("Failed to read executable path")
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
                val syncPipeStr = syncFds[1].toString()

                // Pass channel FDs to init process (Stage-2)
                val mainSenderFd = mainSender.fd().toString()
                val initReceiverFd = initReceiver.fd().toString()
                val notifyListenerFd = notifyListener.fd().toString()

                // Set clone flags as hex string (e.g., "10000000" for CLONE_NEWUSER)
                val cloneFlagsHex = cloneFlags.toUInt().toString(16)
                setenv("_KONTAINER_CLONE_FLAGS", cloneFlagsHex, 1)
                // Enable bootstrap mode
                setenv("_KONTAINER_IS_BOOTSTRAP", "1", 1)
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

                Logger.debug("forked Stage-1, PID=$stage1Pid, waiting for bootstrap to complete")

                runMainProcess(
                    stage1Pid = stage1Pid,
                    syncFd = syncFds[0],
                    spec = spec,
                    containerId = containerId,
                    bundlePath = bundlePath,
                    rootPath = rootPath,
                    pidFile = pidFile,
                    notifyListener = notifyListener,
                    mainSender = mainSender,
                    mainReceiver = mainReceiver,
                    initSender = initSender,
                    initReceiver = initReceiver,
                )
            }
        }
    }
