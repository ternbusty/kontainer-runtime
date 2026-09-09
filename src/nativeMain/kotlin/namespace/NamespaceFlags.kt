package namespace

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.*
import spec.Namespace
import spec.NamespaceType

/**
 * One namespace the exec command must join via setns(2).
 *
 * @param ociType OCI spec namespace type ("mount", "network", ...)
 * @param procName file name under /proc/<pid>/ns/ ("mnt", "net", ...)
 * @param cloneFlag CLONE_NEW* constant, passed as the nstype guard to setns
 */

/** CLONE_NEWTIME — may not be defined in older sysroot headers. */
const val CLONE_NEWTIME = 0x00000080

/** CLONE_NEWCGROUP — may not be defined in older sysroot headers. */
const val CLONE_NEWCGROUP = 0x02000000

data class NsJoin(
    val ociType: NamespaceType,
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
        NsJoin(NamespaceType.USER, "user", _CLONE_NEWUSER()),
        NsJoin(NamespaceType.IPC, "ipc", _CLONE_NEWIPC()),
        NsJoin(NamespaceType.UTS, "uts", _CLONE_NEWUTS()),
        NsJoin(NamespaceType.NETWORK, "net", _CLONE_NEWNET()),
        NsJoin(NamespaceType.MOUNT, "mnt", _CLONE_NEWNS()),
        NsJoin(NamespaceType.CGROUP, "cgroup", CLONE_NEWCGROUP),
        NsJoin(NamespaceType.TIME, "time", CLONE_NEWTIME),
        NsJoin(NamespaceType.PID, "pid", _CLONE_NEWPID()),
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
        // A namespace entry with a non-empty `path` means "join an existing
        // namespace at this path", not "create a new one" — don't add it to
        // the unshare set.  An empty string is treated the same as absent
        // (OCI: path must be a valid filesystem path to join).
        if (!ns.path.isNullOrEmpty()) continue
        val flag: UInt =
            when (ns.type) {
                NamespaceType.MOUNT -> _CLONE_NEWNS().toUInt()
                NamespaceType.NETWORK -> _CLONE_NEWNET().toUInt()
                NamespaceType.UTS -> _CLONE_NEWUTS().toUInt()
                NamespaceType.IPC -> _CLONE_NEWIPC().toUInt()
                NamespaceType.PID -> _CLONE_NEWPID().toUInt()
                NamespaceType.USER -> _CLONE_NEWUSER().toUInt()
                NamespaceType.CGROUP -> CLONE_NEWCGROUP.toUInt()
                NamespaceType.TIME -> CLONE_NEWTIME.toUInt()
            }
        flags = flags or flag
    }

    return flags
}
