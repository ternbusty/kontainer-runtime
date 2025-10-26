package capability

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.errno
import platform.posix.perror
import platform.posix.strerror
import platform.posix.syscall
import spec.LinuxCapabilities

/**
 * Linux capability enumeration
 * See https://man7.org/linux/man-pages/man7/capabilities.7.html
 */
@OptIn(ExperimentalForeignApi::class)
enum class Capability(
    val value: Int,
    val capName: String,
) {
    CHOWN(CAP_CHOWN, "CAP_CHOWN"),
    DAC_OVERRIDE(CAP_DAC_OVERRIDE, "CAP_DAC_OVERRIDE"),
    DAC_READ_SEARCH(CAP_DAC_READ_SEARCH, "CAP_DAC_READ_SEARCH"),
    FOWNER(CAP_FOWNER, "CAP_FOWNER"),
    FSETID(CAP_FSETID, "CAP_FSETID"),
    KILL(CAP_KILL, "CAP_KILL"),
    SETGID(CAP_SETGID, "CAP_SETGID"),
    SETUID(CAP_SETUID, "CAP_SETUID"),
    SETPCAP(CAP_SETPCAP, "CAP_SETPCAP"),
    LINUX_IMMUTABLE(CAP_LINUX_IMMUTABLE, "CAP_LINUX_IMMUTABLE"),
    NET_BIND_SERVICE(CAP_NET_BIND_SERVICE, "CAP_NET_BIND_SERVICE"),
    NET_BROADCAST(CAP_NET_BROADCAST, "CAP_NET_BROADCAST"),
    NET_ADMIN(CAP_NET_ADMIN, "CAP_NET_ADMIN"),
    NET_RAW(CAP_NET_RAW, "CAP_NET_RAW"),
    IPC_LOCK(CAP_IPC_LOCK, "CAP_IPC_LOCK"),
    IPC_OWNER(CAP_IPC_OWNER, "CAP_IPC_OWNER"),
    SYS_MODULE(CAP_SYS_MODULE, "CAP_SYS_MODULE"),
    SYS_RAWIO(CAP_SYS_RAWIO, "CAP_SYS_RAWIO"),
    SYS_CHROOT(CAP_SYS_CHROOT, "CAP_SYS_CHROOT"),
    SYS_PTRACE(CAP_SYS_PTRACE, "CAP_SYS_PTRACE"),
    SYS_PACCT(CAP_SYS_PACCT, "CAP_SYS_PACCT"),
    SYS_ADMIN(CAP_SYS_ADMIN, "CAP_SYS_ADMIN"),
    SYS_BOOT(CAP_SYS_BOOT, "CAP_SYS_BOOT"),
    SYS_NICE(CAP_SYS_NICE, "CAP_SYS_NICE"),
    SYS_RESOURCE(CAP_SYS_RESOURCE, "CAP_SYS_RESOURCE"),
    SYS_TIME(CAP_SYS_TIME, "CAP_SYS_TIME"),
    SYS_TTY_CONFIG(CAP_SYS_TTY_CONFIG, "CAP_SYS_TTY_CONFIG"),
    MKNOD(CAP_MKNOD, "CAP_MKNOD"),
    LEASE(CAP_LEASE, "CAP_LEASE"),
    AUDIT_WRITE(CAP_AUDIT_WRITE, "CAP_AUDIT_WRITE"),
    AUDIT_CONTROL(CAP_AUDIT_CONTROL, "CAP_AUDIT_CONTROL"),
    SETFCAP(CAP_SETFCAP, "CAP_SETFCAP"),
    MAC_OVERRIDE(CAP_MAC_OVERRIDE, "CAP_MAC_OVERRIDE"),
    MAC_ADMIN(CAP_MAC_ADMIN, "CAP_MAC_ADMIN"),
    SYSLOG(CAP_SYSLOG, "CAP_SYSLOG"),
    WAKE_ALARM(CAP_WAKE_ALARM, "CAP_WAKE_ALARM"),
    BLOCK_SUSPEND(CAP_BLOCK_SUSPEND, "CAP_BLOCK_SUSPEND"),
    AUDIT_READ(CAP_AUDIT_READ, "CAP_AUDIT_READ"),
    PERFMON(CAP_PERFMON, "CAP_PERFMON"),
    BPF(CAP_BPF, "CAP_BPF"),
    CHECKPOINT_RESTORE(CAP_CHECKPOINT_RESTORE, "CAP_CHECKPOINT_RESTORE"),
    ;

    companion object {
        /**
         * Convert OCI capability name string to Capability enum
         * Example: "CAP_NET_BIND_SERVICE" -> Capability.CAP_NET_BIND_SERVICE
         */
        fun fromString(name: String): Capability? = entries.find { it.capName == name }
    }
}

/**
 * Get current process capabilities using syscall
 */
@OptIn(ExperimentalForeignApi::class)
private fun getCapabilities(): Triple<UInt, UInt, UInt> =
    memScoped {
        val header = alloc<__user_cap_header_struct>()
        header.version = _LINUX_CAPABILITY_VERSION_3.toUInt()
        header.pid = 0 // current process

        val data = allocArray<__user_cap_data_struct>(2)

        if (syscall(__NR_capget.toLong(), header.ptr, data) != 0L) {
            perror("capget")
            throw Exception("Failed to get capabilities: ${strerror(errno)?.toKString()}")
        }

        // Combine the two u32 values for each set (for capabilities > 31)
        val effective = data[0].effective or (data[1].effective.toLong() shl 32).toUInt()
        val permitted = data[0].permitted or (data[1].permitted.toLong() shl 32).toUInt()
        val inheritable = data[0].inheritable or (data[1].inheritable.toLong() shl 32).toUInt()

        Triple(effective, permitted, inheritable)
    }

/**
 * Set process capabilities using syscall
 */
@OptIn(ExperimentalForeignApi::class)
private fun setCapabilities(
    effective: UInt,
    permitted: UInt,
    inheritable: UInt,
) {
    memScoped {
        val header = alloc<__user_cap_header_struct>()
        header.version = _LINUX_CAPABILITY_VERSION_3.toUInt()
        header.pid = 0 // current process

        val data = allocArray<__user_cap_data_struct>(2)

        // Split each capability set into two u32 values
        data[0].effective = effective and 0xFFFFFFFFu
        data[0].permitted = permitted and 0xFFFFFFFFu
        data[0].inheritable = inheritable and 0xFFFFFFFFu

        data[1].effective = (effective shr 32) and 0xFFFFFFFFu
        data[1].permitted = (permitted shr 32) and 0xFFFFFFFFu
        data[1].inheritable = (inheritable shr 32) and 0xFFFFFFFFu

        if (syscall(__NR_capset.toLong(), header.ptr, data) != 0L) {
            perror("capset")
            throw Exception("Failed to set capabilities: ${strerror(errno)?.toKString()}")
        }
    }
}

/**
 * Convert capability set to bitmask
 */
private fun capabilitiesToMask(caps: Set<Capability>): ULong {
    var mask = 0UL
    for (cap in caps) {
        mask = mask or (1UL shl cap.value)
    }
    return mask
}

/**
 * Convert OCI capability name list to Capability set
 */
fun parseCapabilities(capNames: List<String>?): Set<Capability> {
    if (capNames == null) return emptySet()

    val caps = mutableSetOf<Capability>()
    for (name in capNames) {
        val cap = Capability.fromString(name)
        if (cap != null) {
            caps.add(cap)
        } else {
            Logger.warn("Unknown capability: $name")
        }
    }
    return caps
}

/**
 * Reset effective capabilities to match permitted capabilities
 * This is typically done after privilege operations to restore capabilities
 */
@OptIn(ExperimentalForeignApi::class)
fun resetEffective() {
    Logger.debug("resetting effective capabilities to match permitted")
    val (_, permitted, inheritable) = getCapabilities()
    setCapabilities(permitted, permitted, inheritable)
}

/**
 * Drop bounding set capabilities
 * Bounding set limits the capabilities that can be acquired
 */
@OptIn(ExperimentalForeignApi::class)
private fun dropBoundingCapabilities(caps: Set<Capability>) {
    // Get all possible capabilities
    val allCaps = (0..CAP_LAST_CAP).toSet()

    // Find capabilities to drop (all capabilities not in the desired set)
    val capsToDrop =
        allCaps.filter { capValue ->
            caps.none { it.value == capValue }
        }

    // Drop each capability from bounding set
    for (capValue in capsToDrop) {
        if (prctl(PR_CAPBSET_DROP, capValue, 0, 0, 0) != 0) {
            val cap = Capability.entries.find { it.value == capValue }
            Logger.warn("Failed to drop bounding capability ${cap?.capName ?: capValue}: ${strerror(errno)?.toKString()}")
        }
    }
}

/**
 * Set ambient capabilities
 * Ambient capabilities are preserved across execve of non-privileged programs
 */
@OptIn(ExperimentalForeignApi::class)
private fun setAmbientCapabilities(caps: Set<Capability>) {
    // First, clear all ambient capabilities
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0) != 0) {
        Logger.warn("Failed to clear ambient capabilities: ${strerror(errno)?.toKString()}")
    }

    // Then raise each requested capability
    for (cap in caps) {
        if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, cap.value, 0, 0) != 0) {
            Logger.warn("Failed to raise ambient capability ${cap.capName}: ${strerror(errno)?.toKString()}")
        }
    }
}

/**
 * Set PR_SET_KEEPCAPS to preserve capabilities across setuid
 * This must be called BEFORE setuid/setgid
 */
@OptIn(ExperimentalForeignApi::class)
fun setKeepCaps() {
    Logger.debug("setting PR_SET_KEEPCAPS")
    if (prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0) != 0) {
        perror("prctl(PR_SET_KEEPCAPS, 1)")
        throw Exception("Failed to set PR_SET_KEEPCAPS: ${strerror(errno)?.toKString()}")
    }
}

/**
 * Clear PR_SET_KEEPCAPS after setuid
 * This should be called AFTER setuid/setgid
 */
@OptIn(ExperimentalForeignApi::class)
fun clearKeepCaps() {
    Logger.debug("clearing PR_SET_KEEPCAPS")
    if (prctl(PR_SET_KEEPCAPS, 0, 0, 0, 0) != 0) {
        perror("prctl(PR_SET_KEEPCAPS, 0)")
        throw Exception("Failed to clear PR_SET_KEEPCAPS: ${strerror(errno)?.toKString()}")
    }
}

/**
 * Apply bounding set capabilities
 * This must be called BEFORE setuid/setgid (while still root)
 * The bounding set limits which capabilities can be acquired
 */
@OptIn(ExperimentalForeignApi::class)
fun applyBoundingSet(capabilities: LinuxCapabilities) {
    Logger.debug("applying bounding set capabilities")

    // Parse bounding set from OCI spec
    val boundingCaps = parseCapabilities(capabilities.bounding)

    // Drop capabilities in bounding set before changing user
    if (capabilities.bounding != null) {
        Logger.debug("setting bounding capabilities: ${boundingCaps.map { it.capName }}")
        dropBoundingCapabilities(boundingCaps)
    }

    Logger.debug("bounding set applied successfully")
}

/**
 * Apply effective, permitted, inheritable, and ambient capabilities
 * This must be called AFTER setuid/setgid (as non-root user)
 * Requires PR_SET_KEEPCAPS to be set before setuid
 */
@OptIn(ExperimentalForeignApi::class)
fun applyCapabilities(capabilities: LinuxCapabilities) {
    Logger.debug("applying capability sets")

    // Parse capability sets from OCI spec
    val effectiveCaps = parseCapabilities(capabilities.effective)
    val inheritableCaps = parseCapabilities(capabilities.inheritable)
    val permittedCaps = parseCapabilities(capabilities.permitted)
    val ambientCaps = parseCapabilities(capabilities.ambient)

    // Set effective, permitted, and inheritable capabilities
    val effectiveMask = capabilitiesToMask(effectiveCaps).toUInt()
    val permittedMask = capabilitiesToMask(permittedCaps).toUInt()
    val inheritableMask = capabilitiesToMask(inheritableCaps).toUInt()

    Logger.debug("setting effective capabilities: ${effectiveCaps.map { it.capName }}")
    Logger.debug("setting permitted capabilities: ${permittedCaps.map { it.capName }}")
    Logger.debug("setting inheritable capabilities: ${inheritableCaps.map { it.capName }}")

    setCapabilities(effectiveMask, permittedMask, inheritableMask)

    // Set ambient capabilities (if specified)
    if (capabilities.ambient != null) {
        Logger.debug("setting ambient capabilities: ${ambientCaps.map { it.capName }}")
        setAmbientCapabilities(ambientCaps)
    }

    Logger.info("capabilities applied successfully")
}
