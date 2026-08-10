package namespace

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.*
import spec.Namespace

/**
 * One namespace the exec command must join via setns(2).
 *
 * @param ociType OCI spec namespace type ("mount", "network", ...)
 * @param procName file name under /proc/<pid>/ns/ ("mnt", "net", ...)
 * @param cloneFlag CLONE_NEW* constant, passed as the nstype guard to setns
 */

/** CLONE_NEWTIME — may not be defined in older sysroot headers. */
const val CLONE_NEWTIME = 0x00000080

data class NsJoin(
    val ociType: String,
    val procName: String,
    val cloneFlag: Int,
)

/**
 * Namespaces exec should join for a container, derived from the SPEC — not
 * from what exists under /proc/<pid>/ns/, where an entry exists for every
 * type whether or not the container has a private one. Joining the caller's
 * own user namespace fails with EINVAL (user_namespaces(7)), so a
 * /proc-derived list breaks exec for containers without a user namespace.
 *
 * Returned in mandatory join order regardless of spec order: user first
 * (joining it grants the capabilities in the container's userns that
 * authorize the remaining joins), pid last (it only affects children,
 * which is why exec forks once more after joining).
 */
@OptIn(ExperimentalForeignApi::class)
fun nsJoinList(namespaces: List<Namespace>?): List<NsJoin> {
    if (namespaces == null) return emptyList()
    val specTypes = namespaces.map { it.type }.toSet()
    return listOf(
        NsJoin("user", "user", _CLONE_NEWUSER()),
        NsJoin("ipc", "ipc", _CLONE_NEWIPC()),
        NsJoin("uts", "uts", _CLONE_NEWUTS()),
        NsJoin("network", "net", _CLONE_NEWNET()),
        NsJoin("mount", "mnt", _CLONE_NEWNS()),
        NsJoin("cgroup", "cgroup", 0x02000000), // CLONE_NEWCGROUP (not yet in K/N's platform.linux on older sysroots)
        NsJoin("time", "time", CLONE_NEWTIME),
        NsJoin("pid", "pid", _CLONE_NEWPID()),
    ).filter { it.ociType in specTypes }
}

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
                "time" -> CLONE_NEWTIME.toUInt()
                else -> {
                    // Skip unknown namespace types (for forward compatibility)
                    0u
                }
            }
        flags = flags or flag
    }

    return flags
}
