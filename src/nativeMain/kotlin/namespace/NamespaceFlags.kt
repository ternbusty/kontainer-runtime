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
        // A namespace entry with a non-null `path` means "join an existing namespace
        // at this path", not "create a new one" — don't add it to the unshare set.
        if (ns.path != null) continue
        val flag: UInt =
            when (ns.type) {
                "mount" -> _CLONE_NEWNS().toUInt()
                "network" -> _CLONE_NEWNET().toUInt()
                "uts" -> _CLONE_NEWUTS().toUInt()
                "ipc" -> _CLONE_NEWIPC().toUInt()
                "pid" -> _CLONE_NEWPID().toUInt()
                "user" -> _CLONE_NEWUSER().toUInt()
                "cgroup" -> 0x02000000u // CLONE_NEWCGROUP (not yet in K/N's platform.linux on older sysroots)
                else -> {
                    // Skip unknown namespace types (for forward compatibility)
                    0u
                }
            }
        flags = flags or flag
    }

    return flags
}
