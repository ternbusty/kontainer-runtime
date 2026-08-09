package rootfs

import spec.Namespace

/**
 * Validate that every sysctl key in the spec is on the OCI/runc allowlist.
 *
 * The kernel allows writing per-namespace sysctls only when the process
 * owns a NEW instance of the governing namespace. Shared-namespace
 * sysctls would modify the host and must be rejected.
 *
 * Allowlist (mirrors runc `libcontainer/configs/validate/validator.go`):
 *
 * | sysctl prefix / key                        | Requires new namespace |
 * |---------------------------------------------|------------------------|
 * | `net.*`                                     | network                |
 * | `fs.mqueue.*`                               | IPC                    |
 * | `kernel.msgmax`, `.msgmnb`, `.msgmni`,      | IPC                    |
 * |   `.sem`, `.shmall`, `.shmmax`, `.shmmni`,  |                        |
 * |   `.shm_rmid_forced`                        |                        |
 * | `kernel.domainname`                         | UTS                    |
 *
 * `kernel.hostname` is deliberately NOT allowed (runc parity): the spec
 * has a dedicated `hostname` field; setting it via sysctl would bypass
 * the runtime's validation and ordering.
 *
 * @return list of human-readable error strings; empty if everything is valid
 */
fun validateSysctls(
    sysctls: Map<String, String>?,
    namespaces: List<Namespace>?,
): List<String> {
    if (sysctls.isNullOrEmpty()) return emptyList()

    // A new namespace = a spec entry without a path (path means "join existing")
    val newNsTypes =
        namespaces
            ?.filter { it.path == null }
            ?.map { it.type }
            ?.toSet() ?: emptySet()

    val errors = mutableListOf<String>()

    for (key in sysctls.keys) {
        val err = validateSysctlKey(key, newNsTypes)
        if (err != null) errors.add(err)
    }

    return errors
}

/**
 * IPC-related kernel sysctls that runc allows when a new IPC namespace
 * is present. Listed individually because they do not share a common
 * prefix with other kernel.* keys.
 */
private val IPC_KERNEL_SYSCTLS =
    setOf(
        "kernel.msgmax",
        "kernel.msgmnb",
        "kernel.msgmni",
        "kernel.sem",
        "kernel.shmall",
        "kernel.shmmax",
        "kernel.shmmni",
        "kernel.shm_rmid_forced",
    )

private fun validateSysctlKey(
    key: String,
    newNsTypes: Set<String>,
): String? =
    when {
        key.startsWith("net.") -> {
            if ("network" !in newNsTypes) {
                "sysctl \"$key\" requires a new network namespace"
            } else {
                null
            }
        }

        key.startsWith("fs.mqueue.") -> {
            if ("ipc" !in newNsTypes) {
                "sysctl \"$key\" requires a new IPC namespace"
            } else {
                null
            }
        }

        key in IPC_KERNEL_SYSCTLS -> {
            if ("ipc" !in newNsTypes) {
                "sysctl \"$key\" requires a new IPC namespace"
            } else {
                null
            }
        }

        key == "kernel.domainname" -> {
            if ("uts" !in newNsTypes) {
                "sysctl \"$key\" requires a new UTS namespace"
            } else {
                null
            }
        }

        // hostname is NOT on the allowlist — use spec.hostname instead
        else -> "sysctl \"$key\" is not in the allowed list"
    }
