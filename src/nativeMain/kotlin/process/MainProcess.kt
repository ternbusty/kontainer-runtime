package process

import channel.InitSender
import channel.MainReceiver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import platform.posix._exit
import seccomp.sendToSeccompListener
import spec.Spec
import state.ContainerStatus
import state.State

/**
 * Main process - Parent process
 *
 * This process manages the init process (Stage-2/PID 1).
 *
 * Note: User namespace mapping and cgroup setup are now handled in Create.kt
 * before this process starts. This process handles:
 * - Waiting for init process to be ready
 * - Handling seccomp notify FD if configured
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainProcess(
    spec: Spec,
    containerId: String,
    bundlePath: String,
    initPid: Int,
    mainReceiver: MainReceiver,
    initSender: InitSender,
): Int =
    memScoped {
        Logger.setContext("main")
        Logger.debug("started, init pid=$initPid")

        // Note: User namespace mapping and cgroup setup are already done in Create.kt
        // - UID/GID mapping for Stage-1 process
        // - Cgroup setup for intermediate process (Stage-0)
        //   → Stage-0 → Stage-1 → Stage-2 are all automatically included in the cgroup

        // Check if seccomp notify is used in the spec
        val hasSeccompNotify =
            spec.linux
                ?.seccomp
                ?.syscalls
                ?.any { it.action == "SCMP_ACT_NOTIFY" } ?: false
        if (hasSeccompNotify) {
            Logger.debug("seccomp notify is enabled, waiting for notify FD")
            try {
                val notifyFd = mainReceiver.waitForSeccompRequest()
                Logger.debug("received seccomp notify FD: $notifyFd")

                // If listenerPath is specified, forward the FD to the listener
                spec.linux.seccomp.listenerPath?.let { listenerPath ->
                    Logger.debug("forwarding seccomp notify FD to listener: $listenerPath")
                    try {
                        val containerState =
                            State(
                                ociVersion = spec.ociVersion,
                                id = containerId,
                                status = ContainerStatus.CREATING,
                                pid = initPid,
                                bundle = bundlePath,
                                annotations = null,
                                created = null, // Not set yet during creating phase
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

                initSender.seccompNotifyDone()
                Logger.debug("sent seccomp notify done signal")
            } catch (e: Exception) {
                Logger.error("error handling seccomp notify: ${e.message ?: "unknown"}")
                _exit(1)
            }
        }

        try {
            mainReceiver.waitForInitReady()
            Logger.debug("init process is ready")
        } catch (e: Exception) {
            Logger.error("error waiting for init ready: ${e.message ?: "unknown"}")
            _exit(1)
        }
        mainReceiver.close()
        initSender.close()

        Logger.info("container created with init pid=$initPid")

        initPid
    }
