package namespace

import kotlinx.cinterop.ExperimentalForeignApi
import logger.Logger
import platform.linux.*
import spec.Namespace
import syscall.unshare

/**
 * Unshare a single namespace by type
 *
 * @param type Namespace type (user, mount, network, uts, ipc, pid)
 * @throws Exception if unshare fails
 */
@OptIn(ExperimentalForeignApi::class)
fun unshareNamespace(type: String) {
    val flag = when (type) {
        "mount" -> _CLONE_NEWNS()
        "network" -> _CLONE_NEWNET()
        "uts" -> _CLONE_NEWUTS()
        "ipc" -> _CLONE_NEWIPC()
        "pid" -> _CLONE_NEWPID()
        "user" -> _CLONE_NEWUSER()
        else -> throw IllegalArgumentException("Unknown namespace type: $type")
    }

    unshare(flag)
    Logger.debug("unshared $type namespace")
}

/**
 * Check if a namespace type exists in the list
 */
fun hasNamespace(namespaces: List<Namespace>?, type: String): Boolean {
    return namespaces?.any { it.type == type } ?: false
}
