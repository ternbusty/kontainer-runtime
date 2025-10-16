package namespace

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.__NR_unshare
import platform.posix.*
import spec.Namespace

// Namespace clone flags
const val CLONE_NEWNS: Int = 0x00020000      // Mount namespace
const val CLONE_NEWUTS: Int = 0x04000000     // UTS namespace
const val CLONE_NEWIPC: Int = 0x08000000     // IPC namespace
const val CLONE_NEWUSER: Int = 0x10000000    // User namespace
const val CLONE_NEWPID: Int = 0x20000000     // PID namespace
const val CLONE_NEWNET: Int = 0x40000000     // Network namespace

/**
 * Unshare a single namespace by type
 *
 * @param type Namespace type (user, mount, network, uts, ipc, pid)
 * @throws Exception if unshare fails
 */
@OptIn(ExperimentalForeignApi::class)
fun unshareNamespace(type: String) {
    val flag = when (type) {
        "mount" -> CLONE_NEWNS
        "network" -> CLONE_NEWNET
        "uts" -> CLONE_NEWUTS
        "ipc" -> CLONE_NEWIPC
        "pid" -> CLONE_NEWPID
        "user" -> CLONE_NEWUSER
        else -> throw IllegalArgumentException("Unknown namespace type: $type")
    }

    if (syscall(__NR_unshare.toLong(), flag) == -1L) {
        perror("unshare($type)")
        throw Exception("Failed to unshare $type namespace")
    }

    Logger.debug("unshared $type namespace")
}

/**
 * Check if a namespace type exists in the list
 */
fun hasNamespace(namespaces: List<Namespace>?, type: String): Boolean {
    return namespaces?.any { it.type == type } ?: false
}
