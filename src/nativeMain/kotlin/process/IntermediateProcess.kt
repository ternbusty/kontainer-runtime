package process

import cgroup.setupCgroup
import channel.InitReceiver
import channel.IntermediateReceiver
import channel.MainSender
import channel.NotifyListener
import kotlinx.cinterop.*
import logger.Logger
import namespace.hasNamespace
import namespace.unshareNamespace
import platform.posix.*
import spec.Spec

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
    notifyListener: NotifyListener
): Unit = memScoped {
    Logger.setContext("intermediate")
    Logger.debug("started, pid=${getpid()}")

    // Setup cgroup BEFORE entering user namespace
    // At this point, intermediate process still has host root privileges (inherited from parent)
    // This allows creation of cgroup directories in /sys/fs/cgroup/
    setupCgroup(getpid(), spec.linux?.cgroupsPath, spec.linux?.resources)

    // First, unshare user namespace
    if (hasNamespace(spec.linux?.namespaces, "user")) {
        unshareNamespace("user")
    }

    // Send mapping request to main process
    mainSender.identifierMappingRequest()
    Logger.debug("sent mapping request")

    // Wait for mapping completion from main process
    interReceiver.waitForMappingAck()
    Logger.debug("received mapping ack")

    // Set UID/GID to 0 (root within user namespace)
    if (setuid(0u) != 0 || setgid(0u) != 0) {
        perror("setuid/setgid")
        throw Exception("Failed to setuid/setgid")
    }
    Logger.debug("set UID/GID to 0 in user namespace")

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

    // Fork init process
    val initPid = fork()
    if (initPid == -1) {
        perror("fork(init)")
        throw Exception("Failed to fork init process")
    }

    if (initPid == 0) {
        // Init process
        // Close interReceiver in init process (not needed)
        interReceiver.close()

        try {
            runInitProcess(spec, rootfsPath, mainSender, initReceiver, notifyListener)
            _exit(0)
        } catch (e: Exception) {
            Logger.error("init process failed: ${e.message ?: "unknown"}")

            // Try to send error to main process (best effort)
            try {
                mainSender.sendError("Init process failed: ${e.message}")
            } catch (sendErr: Exception) {
                Logger.warn("failed to send error to main process: ${sendErr.message ?: "unknown"}")
            }

            _exit(1)
        }
    } else {
        // Intermediate process: send init PID to main process
        mainSender.intermediateReady(initPid)
        Logger.debug("sent init pid=$initPid to main")

        // Close channels (no more communication needed)
        mainSender.close()
        interReceiver.close()
        initReceiver.close()

        // Wait for init process to exit
        val st = alloc<IntVar>()
        if (waitpid(initPid, st.ptr, 0) == -1) {
            perror("waitpid(init)")
            throw Exception("Failed to wait for init process")
        }
        Logger.debug("init process exited")
        _exit(0)
    }
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
    notifyListener: NotifyListener
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
