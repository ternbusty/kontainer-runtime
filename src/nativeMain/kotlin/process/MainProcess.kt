package process

import kotlinx.cinterop.*
import platform.posix.*
import spec.Spec
import utils.writeText

/**
 * Main process - Parent process
 *
 * Responsibilities:
 * - Receive UID/GID mapping request from intermediate process
 * - Write mappings to /proc/<pid>/uid_map and /proc/<pid>/gid_map
 * - Wait for intermediate process to exit
 */
@OptIn(ExperimentalForeignApi::class)
fun runMainProcess(
    spec: Spec,
    pid: Int,
    socket: Int
): Unit = memScoped {
    // Receive MAP request from intermediate process
    val buf = allocArray<ByteVar>(128)
    val n = recv(socket, buf, 127.toULong(), 0)
    if (n == -1L) {
        perror("recv(map request)")
        close(socket)
        exit(1)
    }
    buf[n.toInt()] = 0
    val s = buf.toKString().trim()

    // Format: MAP <pid>
    val parts = s.split(" ")
    if (parts.size != 2 || parts[0] != "MAP") {
        fprintf(stderr, "parent: bad request: %s\n", s)
        close(socket)
        exit(1)
    }
    val targetPid = parts[1].toInt()
    fprintf(stderr, "parent: mapping for pid=%d\n", targetPid)

    // Map effective UID/GID to 0..0
    val hostUid = geteuid().toUInt()
    val hostGid = getegid().toUInt()

    // Kernel requires disabling setgroups before writing gid_map
    if (!writeText("/proc/$targetPid/setgroups", "deny\n")) {
        perror("write setgroups")
        close(socket)
        exit(1)
    }

    // Map a single ID range
    val uidMap = "${0} ${hostUid} ${1}\n"
    val gidMap = "${0} ${hostGid} ${1}\n"

    if (!writeText("/proc/$targetPid/uid_map", uidMap)) {
        fprintf(stderr, "parent: failed uid_map host=%u\n", hostUid)
        close(socket)
        exit(1)
    }
    if (!writeText("/proc/$targetPid/gid_map", gidMap)) {
        fprintf(stderr, "parent: failed gid_map host=%u\n", hostGid)
        close(socket)
        exit(1)
    }

    // Respond with mapping completion
    val ack = "MAPPED\n"
    if (send(socket, ack.cstr.ptr, ack.length.toULong(), 0) == -1L) {
        perror("send(ack)")
        close(socket)
        exit(1)
    }

    // Wait for intermediate process to exit
    val st = alloc<IntVar>()
    if (waitpid(pid, st.ptr, 0) == -1) {
        perror("waitpid(intermediate)")
        close(socket)
        exit(1)
    }

    close(socket)
    fprintf(stderr, "parent: done\n")
}
