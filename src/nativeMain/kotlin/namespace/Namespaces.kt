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
 * Unshare specified namespaces
 */
@OptIn(ExperimentalForeignApi::class)
fun unshareNamespaces(namespaces: List<Namespace>) {
    for (ns in namespaces) {
        val flag = when (ns.type) {
            "mount" -> CLONE_NEWNS
            "network" -> CLONE_NEWNET
            "uts" -> CLONE_NEWUTS
            "ipc" -> CLONE_NEWIPC
            "pid" -> CLONE_NEWPID
            "user" -> CLONE_NEWUSER
            else -> {
                Logger.warn("unknown namespace type: ${ns.type}")
                continue
            }
        }

        if (syscall(__NR_unshare.toLong(), flag) == -1L) {
            perror("unshare(${ns.type})")
            throw Exception("Failed to unshare ${ns.type} namespace")
        }

        Logger.debug("unshared ${ns.type} namespace")
    }
}

/**
 * Check if a namespace type exists in the list
 */
fun hasNamespace(namespaces: List<Namespace>?, type: String): Boolean {
    return namespaces?.any { it.type == type } ?: false
}
