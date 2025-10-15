package process

import cgroup.setupCgroup
import channel.*
import kotlinx.cinterop.*
import logger.Logger
import platform.linux.__NR_unshare
import platform.posix.*
import spec.Spec
import namespace.*

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
@OptIn(ExperimentalForeignApi::class)
fun runIntermediateProcess(
    spec: Spec,
    rootfsPath: String,
    mainSender: MainSender,
    interReceiver: IntermediateReceiver,
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
        if (syscall(__NR_unshare.toLong(), CLONE_NEWUSER) == -1L) {
            perror("unshare(CLONE_NEWUSER)")
            Logger.error("Failed to unshare user namespace")
            try {
                mainSender.sendError("Failed to unshare user namespace")
            } catch (e: Exception) {
                // Ignore error sending
            }
            _exit(1)
        }
        Logger.debug("unshared user namespace")
    }

    // Send mapping request to main process
    try {
        mainSender.identifierMappingRequest()
        Logger.debug("sent mapping request")
    } catch (e: Exception) {
        Logger.error("failed to send mapping request: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Wait for mapping completion from main process
    try {
        interReceiver.waitForMappingAck()
        Logger.debug("received mapping ack")
    } catch (e: Exception) {
        Logger.error("failed to receive mapping ack: ${e.message ?: "unknown"}")
        _exit(1)
    }

    // Set UID/GID to 0 (root within user namespace)
    if (setuid(0u) != 0 || setgid(0u) != 0) {
        perror("setuid/setgid")
        try {
            mainSender.sendError("Failed to setuid/setgid")
        } catch (e: Exception) {
            // Ignore
        }
        _exit(1)
    }

    // Unshare additional namespaces (excluding user and pid)
    spec.linux?.namespaces?.let { namespaces ->
        try {
            // Unshare mount, network, UTS, IPC namespaces
            for (ns in namespaces) {
                when (ns.type) {
                    "mount", "network", "uts", "ipc" -> {
                        val flag = when (ns.type) {
                            "mount" -> CLONE_NEWNS
                            "network" -> CLONE_NEWNET
                            "uts" -> CLONE_NEWUTS
                            "ipc" -> CLONE_NEWIPC
                            else -> continue
                        }
                        if (syscall(__NR_unshare.toLong(), flag) == -1L) {
                            perror("unshare(${ns.type})")
                            Logger.error("Failed to unshare ${ns.type} namespace")
                            try {
                                mainSender.sendError("Failed to unshare ${ns.type} namespace")
                            } catch (e: Exception) {
                                // Ignore
                            }
                            _exit(1)
                        }
                        Logger.debug("unshared ${ns.type} namespace")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error("failed to unshare namespaces: ${e.message ?: "unknown"}")
            try {
                mainSender.sendError("Failed to unshare namespaces: ${e.message}")
            } catch (sendErr: Exception) {
                // Ignore
            }
            _exit(1)
        }
    }

    // Unshare PID namespace
    if (hasNamespace(spec.linux?.namespaces, "pid")) {
        if (syscall(__NR_unshare.toLong(), CLONE_NEWPID) == -1L) {
            perror("unshare(CLONE_NEWPID)")
            Logger.error("Failed to unshare PID namespace")
            try {
                mainSender.sendError("Failed to unshare PID namespace")
            } catch (e: Exception) {
                // Ignore
            }
            _exit(1)
        }
        Logger.debug("unshared pid namespace")
    }

    // Fork init process
    val initPid = fork()
    if (initPid == -1) {
        perror("fork(init)")
        Logger.error("Failed to fork init process")
        try {
            mainSender.sendError("Failed to fork init process")
        } catch (e: Exception) {
            // Ignore
        }
        _exit(1)
    }

    if (initPid == 0) {
        // Init process
        runInitProcess(spec, rootfsPath, mainSender, notifyListener)
        _exit(0)
    } else {
        // Intermediate process: send init PID to main process
        try {
            mainSender.intermediateReady(initPid)
            Logger.debug("sent init pid=$initPid to main")
        } catch (e: Exception) {
            Logger.error("failed to send init pid: ${e.message ?: "unknown"}")
            _exit(1)
        }

        // Close channels (no more communication needed)
        mainSender.close()
        interReceiver.close()

        // Wait for init process to exit
        val st = alloc<IntVar>()
        if (waitpid(initPid, st.ptr, 0) == -1) {
            perror("waitpid(init)")
            Logger.error("Failed to wait for init process")
            _exit(1)
        }
        Logger.debug("init process exited")
        _exit(0)
    }
}
