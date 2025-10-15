package process

import cgroup.setupCgroup
import channel.*
import kotlinx.cinterop.*
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
    fprintf(stderr, "intermediate: started, pid=%d\n", getpid())

    // Setup cgroup BEFORE entering user namespace
    // At this point, intermediate process still has host root privileges (inherited from parent)
    // This allows creation of cgroup directories in /sys/fs/cgroup/
    setupCgroup(getpid(), spec.linux?.cgroupsPath, spec.linux?.resources)

    // First, unshare user namespace
    if (hasNamespace(spec.linux?.namespaces, "user")) {
        if (syscall(__NR_unshare.toLong(), CLONE_NEWUSER) == -1L) {
            perror("unshare(CLONE_NEWUSER)")
            try {
                mainSender.sendError("Failed to unshare user namespace")
            } catch (e: Exception) {
                // Ignore error sending
            }
            _exit(1)
        }
        fprintf(stderr, "intermediate: unshared user namespace\n")
    }

    // Send mapping request to main process
    try {
        mainSender.identifierMappingRequest()
        fprintf(stderr, "intermediate: sent mapping request\n")
    } catch (e: Exception) {
        fprintf(stderr, "intermediate: failed to send mapping request: %s\n", e.message ?: "unknown")
        _exit(1)
    }

    // Wait for mapping completion from main process
    try {
        interReceiver.waitForMappingAck()
        fprintf(stderr, "intermediate: received mapping ack\n")
    } catch (e: Exception) {
        fprintf(stderr, "intermediate: failed to receive mapping ack: %s\n", e.message ?: "unknown")
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
                            try {
                                mainSender.sendError("Failed to unshare ${ns.type} namespace")
                            } catch (e: Exception) {
                                // Ignore
                            }
                            _exit(1)
                        }
                        fprintf(stderr, "intermediate: unshared %s namespace\n", ns.type)
                    }
                }
            }
        } catch (e: Exception) {
            fprintf(stderr, "intermediate: failed to unshare namespaces: %s\n", e.message ?: "unknown")
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
            try {
                mainSender.sendError("Failed to unshare PID namespace")
            } catch (e: Exception) {
                // Ignore
            }
            _exit(1)
        }
        fprintf(stderr, "intermediate: unshared pid namespace\n")
    }

    // Fork init process
    val initPid = fork()
    if (initPid == -1) {
        perror("fork(init)")
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
            fprintf(stderr, "intermediate: sent init pid=%d to main\n", initPid)
        } catch (e: Exception) {
            fprintf(stderr, "intermediate: failed to send init pid: %s\n", e.message ?: "unknown")
            _exit(1)
        }

        // Close channels (no more communication needed)
        mainSender.close()
        interReceiver.close()

        // Wait for init process to exit
        val st = alloc<IntVar>()
        if (waitpid(initPid, st.ptr, 0) == -1) {
            perror("waitpid(init)")
            _exit(1)
        }
        fprintf(stderr, "intermediate: init process exited\n")
        _exit(0)
    }
}
