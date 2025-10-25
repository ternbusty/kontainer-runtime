package namespace

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.*
import spec.Namespace

/**
 * Calculate clone flags from OCI spec namespaces
 *
 * Converts a list of namespace specifications into the corresponding
 * CLONE_NEW* flags that can be passed to C code for unshare operations.
 *
 * @param namespaces List of namespace specifications from OCI config
 * @return Combined clone flags as UInt (bitwise OR of all CLONE_NEW* flags)
 */
@OptIn(ExperimentalForeignApi::class)
fun calculateCloneFlags(namespaces: List<Namespace>?): UInt {
    if (namespaces == null) {
        return 0u
    }

    var flags = 0u

    for (ns in namespaces) {
        val flag: UInt =
            when (ns.type) {
                "mount" -> _CLONE_NEWNS().toUInt()
                "network" -> _CLONE_NEWNET().toUInt()
                "uts" -> _CLONE_NEWUTS().toUInt()
                "ipc" -> _CLONE_NEWIPC().toUInt()
                "pid" -> _CLONE_NEWPID().toUInt()
                "user" -> _CLONE_NEWUSER().toUInt()
                else -> {
                    // Skip unknown namespace types (for forward compatibility)
                    0u
                }
            }
        flags = flags or flag
    }

    return flags
}
