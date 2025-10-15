package process

import cgroup.setupCgroup
import kotlinx.cinterop.*
import platform.linux.__NR_unshare
import platform.posix.*
import spec.Spec
import namespace.*

/**
 * Intermediate process
 *
 * Responsibilities:
 * - Unshare user namespace
 * - Request UID/GID mapping from main process
 * - Unshare other namespaces (mount, network, uts, ipc)
 * - Unshare PID namespace
 * - Fork and start init process
 */
@OptIn(ExperimentalForeignApi::class)
fun runIntermediateProcess(
    spec: Spec,
    rootfsPath: String,
    socket: Int
): Unit = memScoped {
    close(socket)  // Close socket used by parent

    // Setup cgroup BEFORE entering user namespace
    // At this point, intermediate process still has host root privileges (inherited from parent)
    // This allows creation of cgroup directories in /sys/fs/cgroup/
    setupCgroup(getpid(), spec.linux?.cgroupsPath, spec.linux?.resources)

    // First, unshare user namespace
    if (hasNamespace(spec.linux?.namespaces, "user")) {
        if (syscall(__NR_unshare.toLong(), CLONE_NEWUSER) == -1L) {
            perror("unshare(CLONE_NEWUSER)")
            _exit(1)
        }
        fprintf(stderr, "intermediate: unshared user namespace\n")
    }

    fprintf(stderr, "intermediate getpid=%d getppid=%d\n", getpid(), getppid())

    // Send mapping request to parent
    val childSocket = socket + 1  // The other end of socketpair
    val req = "MAP ${getpid()}\n"
    fprintf(stderr, "intermediate: requesting mapping pid=%d\n", getpid())
    if (send(childSocket, req.cstr.ptr, req.length.toULong(), 0) == -1L) {
        perror("send(map request)")
        _exit(1)
    }

    // Wait for completion response from parent
    val ackBuf = allocArray<ByteVar>(64)
    val rn = recv(childSocket, ackBuf, 63.toULong(), 0)
    if (rn == -1L) {
        perror("recv(ack)")
        _exit(1)
    } else {
        ackBuf[rn.toInt()] = 0
        val ack = ackBuf.toKString()
        if (!ack.startsWith("MAPPED")) {
            fprintf(stderr, "intermediate: mapping failed ack=%s\n", ack)
            _exit(1)
        }
    }

    // Set UID/GID to 0 (root within user namespace)
    if (setuid(0u) != 0 || setgid(0u) != 0) {
        perror("setuid/setgid")
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
                            _exit(1)
                        }
                        fprintf(stderr, "intermediate: unshared %s namespace\n", ns.type)
                    }
                }
            }
        } catch (e: Exception) {
            fprintf(stderr, "intermediate: failed to unshare namespaces: %s\n", e.message ?: "unknown")
            _exit(1)
        }
    }

    // Unshare PID namespace
    if (hasNamespace(spec.linux?.namespaces, "pid")) {
        if (syscall(__NR_unshare.toLong(), CLONE_NEWPID) == -1L) {
            perror("unshare(CLONE_NEWPID)")
            _exit(1)
        }
        fprintf(stderr, "intermediate: unshared pid namespace\n")
    }

    // Fork init process
    val cpid = fork()
    if (cpid == -1) {
        perror("fork(init)")
        _exit(1)
    }

    if (cpid == 0) {
        // Init process
        runInitProcess(spec, rootfsPath)
        _exit(0)
    } else {
        // Intermediate process waits for init to exit
        val st = alloc<IntVar>()
        if (waitpid(cpid, st.ptr, 0) == -1) {
            perror("waitpid(init)")
            _exit(1)
        }
        _exit(0)
    }
}
