package process

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.keyctl_describe_key
import platform.linux.keyctl_join_session_keyring
import platform.linux.keyctl_set_perm
import platform.posix.*

/**
 * Session keyring management for container processes.
 *
 * Each container gets a dedicated session keyring named `_ses.<containerID>`.
 * When SELinux is in use, the keyring is created with the process's SELinux
 * label so that keys stored in it inherit the container's security context.
 *
 * The init (create) path creates the keyring and restricts its permissions.
 * The exec path joins the existing keyring without modifying permissions.
 *
 * See: runc/libcontainer/standard_init_linux.go (create path)
 *      runc/libcontainer/setns_init_linux.go (exec path)
 *      runc/libcontainer/keys/keyctl.go (keyctl wrappers)
 */

/**
 * Set the SELinux label for newly created keys by writing to
 * `/proc/self/attr/keycreate`.  An empty [label] clears the attribute.
 *
 * Silently succeeds when SELinux is not available (ENOENT) or when
 * clearing the label is denied (EACCES with empty label) — matching
 * runc / go-selinux behaviour.
 */
@OptIn(ExperimentalForeignApi::class)
private fun setKeyLabel(label: String) =
    memScoped {
        val path = "/proc/self/attr/keycreate"
        val fd = open(path, O_WRONLY)
        if (fd < 0) {
            if (errno == ENOENT) return@memScoped // No SELinux
            throw Exception("failed to open $path for key label (errno=$errno)")
        }
        try {
            if (label.isEmpty()) {
                // Clear the attribute by writing zero bytes.
                val dummy = alloc<ByteVar>()
                if (write(fd, dummy.ptr, 0u) < 0 && errno != EACCES) {
                    throw Exception("failed to clear key label: errno=$errno")
                }
            } else {
                val bytes = label.encodeToByteArray()
                bytes.usePinned { p ->
                    if (write(fd, p.addressOf(0), bytes.size.toULong()).toInt() != bytes.size) {
                        throw Exception("failed to write key label '$label': errno=$errno")
                    }
                }
            }
        } finally {
            close(fd)
        }
    }

/**
 * Join or create a named session keyring.
 * Returns the key serial number on success.
 * Returns -1 and logs a warning on ENOSYS (keyrings not supported).
 * Throws on any other error.
 */
@OptIn(ExperimentalForeignApi::class)
private fun joinKeyring(name: String): Int {
    val result = keyctl_join_session_keyring(name)
    if (result < 0) {
        if (errno == ENOSYS) {
            Logger.warn("KeyctlJoinSessionKeyring: function not implemented (ENOSYS)")
            return -1
        }
        throw Exception("unable to join session keyring: errno=$errno")
    }
    return result.toInt()
}

/**
 * Modify keyring permissions by reading the current value via
 * KEYCTL_DESCRIBE, applying [mask], and OR-ing in [setbits].
 *
 * The keyring description format is: `type;uid;gid;perm_hex;description`
 */
@OptIn(ExperimentalForeignApi::class)
private fun modKeyringPerm(
    ringId: Int,
    mask: UInt,
    setbits: UInt,
) = memScoped {
    val bufSize = 256
    val buf = allocArray<ByteVar>(bufSize)
    val descLen = keyctl_describe_key(ringId, buf, bufSize.toULong())
    if (descLen < 0) {
        throw Exception("keyctl describe failed: errno=$errno")
    }

    // The description is NUL-terminated in the buffer; toKString() reads
    // up to the first NUL (or end of buffer).
    val desc = buf.toKString()
    // Format: "type;uid;gid;perm_hex;description"
    val parts = desc.split(";")
    if (parts.size < 5) {
        throw Exception("unexpected keyctl describe format: $desc")
    }
    val currentPerm =
        parts[3].toUIntOrNull(16)
            ?: throw Exception("failed to parse keyring permissions: ${parts[3]}")

    val newPerm = (currentPerm and mask) or setbits
    if (keyctl_set_perm(ringId, newPerm) < 0) {
        throw Exception("keyctl setperm failed: errno=$errno")
    }
}

/**
 * Set up the session keyring for a container process.
 *
 * Must be called BEFORE [applyProcessSecurity] so that the keyring is
 * created with the correct SELinux label (the label is written to
 * `/proc/self/attr/keycreate` before the keyring-creation syscall).
 *
 * @param containerId     container identifier — used as part of the keyring
 *                        name (`_ses.<containerId>`)
 * @param processLabel    SELinux process label, or null if SELinux is not used
 * @param hasUserNamespace whether the container has a user namespace
 * @param isExec          true for the exec/setns path (join only);
 *                        false for the init/create path (create + set perms)
 */
fun setupSessionKeyring(
    containerId: String,
    processLabel: String?,
    hasUserNamespace: Boolean,
    isExec: Boolean,
) {
    // Set SELinux key creation label before creating/joining the keyring
    if (!processLabel.isNullOrEmpty()) {
        setKeyLabel(processLabel)
    }

    try {
        val ringName = "_ses.$containerId"
        val keyId = joinKeyring(ringName)
        if (keyId < 0) {
            // ENOSYS — keyrings not supported; already warned
            return
        }
        Logger.debug("joined session keyring '$ringName' (id=$keyId)")

        if (!isExec) {
            // Init path: restrict keyring permissions.
            // With user namespace: set KEY_OTH_SEARCH (0x8) so processes
            // inside the user namespace can search the keyring.
            // Without user namespace: set KEY_USR_SEARCH (0x80000).
            val newperms: UInt = if (hasUserNamespace) 0x8u else 0x80000u
            modKeyringPerm(keyId, 0xffffffffu, newperms)
            Logger.debug("modified keyring permissions (newperms=0x${newperms.toString(16)})")
        }
    } finally {
        // Clear SELinux key creation label so subsequent key operations
        // do not inherit the container's label.
        if (!processLabel.isNullOrEmpty()) {
            try {
                setKeyLabel("")
            } catch (e: Exception) {
                // Clearing is best-effort (matches runc's defer behaviour)
                Logger.warn("failed to clear key label: ${e.message}")
            }
        }
    }
}
