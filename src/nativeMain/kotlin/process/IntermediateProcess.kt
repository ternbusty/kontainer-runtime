package process

import cgroup.setupCgroup
import channel.InitReceiver
import channel.IntermediateReceiver
import channel.MainSender
import channel.NotifyListener
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import namespace.hasNamespace
import namespace.unshareNamespace
import platform.posix.*
import spec.Spec
import syscall.cloneSibling
import syscall.setDumpable

/**
 * Intermediate process
 *
 * Responsibilities:
 * - Setup cgroup before user namespace
 * - Unshare user namespace
 * - Request UID/GID mapping from main process
 * - Unshare other namespaces (mount, network, uts, ipc)
 * - Unshare PID namespace
 * - Fork and start init process
 * - Send init PID to main process
 */

/**
 * Internal implementation of intermediate process
 * Throws exceptions on errors - caller handles error reporting
 */
@OptIn(ExperimentalForeignApi::class)
private fun intermediateProcessInternal(
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    interReceiver: IntermediateReceiver,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
): Unit =
    memScoped {
        Logger.setContext("intermediate")
        Logger.debug("started, pid=${getpid()}")

        // Log namespace configuration from spec
        val namespaces = spec.linux?.namespaces
        Logger.debug("namespaces from spec: ${namespaces?.size ?: 0} entries")
        namespaces?.forEach { ns ->
            Logger.debug("  namespace: type=${ns.type}")
        }

        // Setup cgroup BEFORE entering user namespace
        // At this point, intermediate process still has host root privileges (inherited from parent)
        // This allows creation of cgroup directories in /sys/fs/cgroup/
        setupCgroup(getpid(), spec.linux?.cgroupsPath, spec.linux?.resources)

        // Handle user namespace (created by bootstrap.c before Kotlin runtime started)
        val hasUserNamespace = hasNamespace(spec.linux?.namespaces, "user")
        Logger.debug("hasUserNamespace: $hasUserNamespace")
        if (hasUserNamespace) {
            // User namespace was already created by bootstrap.c (before Kotlin runtime started)
            // This avoids the multithreading issue (Kotlin GC creates 3 threads)
            Logger.debug("user namespace already created by bootstrap.c")

            // Make process dumpable so parent can write to uid_map/gid_map
            // See: https://man7.org/linux/man-pages/man7/user_namespaces.7.html
            // "The parent process can write to the /proc/PID/uid_map and /proc/PID/gid_map
            // files only if the child process has the PR_SET_DUMPABLE attribute set"
            setDumpable(true)

            // Send mapping request to main process
            mainSender.identifierMappingRequest()
            Logger.debug("sent mapping request")

            // Wait for mapping completion from main process
            interReceiver.waitForMappingAck()
            Logger.debug("received mapping ack")

            // Restore non-dumpable state after mapping is complete
            setDumpable(false)

            // Set UID/GID to 0 (root within user namespace)
            if (setuid(0u) != 0 || setgid(0u) != 0) {
                perror("setuid/setgid")
                throw Exception("Failed to setuid/setgid")
            }
            Logger.debug("set UID/GID to 0 in user namespace")
        } else {
            Logger.debug("skipping user namespace setup (no user namespace configured)")
        }

        // Unshare additional namespaces (excluding user and pid)
        spec.linux?.namespaces?.let { namespaces ->
            // Unshare mount, network, UTS, IPC namespaces
            for (ns in namespaces) {
                when (ns.type) {
                    "mount", "network", "uts", "ipc" -> {
                        unshareNamespace(ns.type)
                    }
                }
            }
        }

        // Unshare PID namespace
        if (hasNamespace(spec.linux?.namespaces, "pid")) {
            unshareNamespace("pid")
        }

        // Clone init process as sibling (using CLONE_PARENT)
        // This ensures that when intermediate process exits, init process remains
        // a child of the main process, not re-parented to PID 1
        val initPid =
            cloneSibling {
                // Init process
                // Close interReceiver in init process (not needed)
                interReceiver.close()

                try {
                    runInitProcess(spec, rootfsPath, mainSender, initReceiver, notifyListener)
                    // runInitProcess calls execve and never returns
                    // If we reach here, something went wrong
                    Logger.error("runInitProcess returned unexpectedly")
                } catch (e: Exception) {
                    Logger.error("init process failed: ${e.message ?: "unknown"}")

                    // Try to send error to main process (best effort)
                    try {
                        mainSender.sendError("Init process failed: ${e.message}")
                    } catch (sendErr: Exception) {
                        Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
                    }
                }

                // If we reach here, something went wrong
                _exit(1)
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }

        // Intermediate process: send init PID to main process
        mainSender.intermediateReady(initPid)
        Logger.debug("sent init pid=$initPid to main")

        // Close channels (no more communication needed)
        mainSender.close()
        interReceiver.close()
        initReceiver.close()

        // With CLONE_PARENT, init process is a sibling (not a child) of intermediate process.
        // Therefore, intermediate process cannot wait for init process (would get ECHILD).
        // The main process will manage the init process lifecycle.
        Logger.debug("intermediate process finished, exiting")
        _exit(0)
    }

/**
 * Entry point for intermediate process
 * Handles errors and communicates them to main process
 */
@OptIn(ExperimentalForeignApi::class)
fun runIntermediateProcess(
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    interReceiver: IntermediateReceiver,
    initReceiver: InitReceiver,
    notifyListener: NotifyListener,
) {
    try {
        intermediateProcessInternal(spec, rootfsPath, mainSender, interReceiver, initReceiver, notifyListener)
    } catch (e: Exception) {
        Logger.error("intermediate process failed: ${e.message ?: "unknown"}")

        // Try to send error to main process (best effort)
        try {
            mainSender.sendError("Intermediate process failed: ${e.message}")
        } catch (sendErr: Exception) {
            Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
        }

        _exit(1)
    }
}
