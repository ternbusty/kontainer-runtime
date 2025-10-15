package seccomp

import kotlinx.cinterop.*
import libseccomp.*
import logger.Logger
import platform.posix.perror
import spec.LinuxSeccomp
import spec.LinuxSyscall
import spec.SeccompArg

/**
 * Seccomp implementation using libseccomp
 */

/**
 * Translate OCI spec action string to libseccomp action constant
 *
 * libseccomp action constants:
 * - SCMP_ACT_KILL_PROCESS = 0x80000000U
 * - SCMP_ACT_KILL_THREAD  = 0x00000000U
 * - SCMP_ACT_TRAP         = 0x00030000U
 * - SCMP_ACT_ERRNO(x)     = 0x00050000U | (x & 0x0000ffffU)
 * - SCMP_ACT_TRACE(x)     = 0x7ff00000U | (x & 0x0000ffffU)
 * - SCMP_ACT_LOG          = 0x7ffc0000U
 * - SCMP_ACT_ALLOW        = 0x7fff0000U
 * - SCMP_ACT_NOTIFY       = 0x7fc00000U
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateAction(action: String, errno: UInt?): UInt {
    return when (action) {
        "SCMP_ACT_KILL" -> 0x00000000u  // SCMP_ACT_KILL_THREAD
        "SCMP_ACT_KILL_PROCESS" -> 0x80000000u
        "SCMP_ACT_KILL_THREAD" -> 0x00000000u
        "SCMP_ACT_TRAP" -> 0x00030000u
        "SCMP_ACT_ERRNO" -> {
            val errnoVal = (errno ?: 1u) and 0x0000ffffu
            0x00050000u or errnoVal
        }

        "SCMP_ACT_TRACE" -> {
            val traceVal = (errno ?: 1u) and 0x0000ffffu
            0x7ff00000u or traceVal
        }

        "SCMP_ACT_ALLOW" -> 0x7fff0000u
        "SCMP_ACT_LOG" -> 0x7ffc0000u
        "SCMP_ACT_NOTIFY" -> 0x7fc00000u
        else -> {
            Logger.warn("Unknown seccomp action: $action, defaulting to SCMP_ACT_KILL")
            0x00000000u  // SCMP_ACT_KILL_THREAD
        }
    }
}

/**
 * Translate OCI spec operator string to libseccomp operator constant
 *
 * enum scmp_compare values:
 * - SCMP_CMP_NE        = 1  (not equal)
 * - SCMP_CMP_LT        = 2  (less than)
 * - SCMP_CMP_LE        = 3  (less than or equal)
 * - SCMP_CMP_EQ        = 4  (equal)
 * - SCMP_CMP_GE        = 5  (greater than or equal)
 * - SCMP_CMP_GT        = 6  (greater than)
 * - SCMP_CMP_MASKED_EQ = 7  (masked equality)
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateOp(op: String): UInt {
    return when (op) {
        "SCMP_CMP_NE" -> 1u
        "SCMP_CMP_LT" -> 2u
        "SCMP_CMP_LE" -> 3u
        "SCMP_CMP_EQ" -> 4u
        "SCMP_CMP_GE" -> 5u
        "SCMP_CMP_GT" -> 6u
        "SCMP_CMP_MASKED_EQ" -> 7u
        else -> {
            Logger.warn("Unknown seccomp operator: $op, defaulting to SCMP_CMP_EQ")
            4u  // SCMP_CMP_EQ
        }
    }
}

/**
 * Check if seccomp config uses SCMP_ACT_NOTIFY action
 */
private fun hasNotifyAction(seccomp: LinuxSeccomp): Boolean {
    return seccomp.syscalls?.any { it.action == "SCMP_ACT_NOTIFY" } ?: false
}

/**
 * Initialize and load seccomp filter based on OCI spec
 *
 * @return notify FD if SCMP_ACT_NOTIFY is used, null otherwise, or throws on error
 */
@OptIn(ExperimentalForeignApi::class)
fun initializeSeccomp(seccomp: LinuxSeccomp): Int? {
    Logger.debug("initializing seccomp filter")

    // Validation: SCMP_ACT_NOTIFY cannot be used as default action
    if (seccomp.defaultAction == "SCMP_ACT_NOTIFY") {
        Logger.error("SCMP_ACT_NOTIFY cannot be used as default action")
        throw Exception("SCMP_ACT_NOTIFY cannot be used as default action")
    }

    // Create filter context with default action
    val defaultAction = translateAction(seccomp.defaultAction, seccomp.defaultErrnoRet)
    val ctx = seccomp_init(defaultAction) ?: run {
        perror("seccomp_init")
        Logger.error("Failed to initialize seccomp context")
        throw Exception("Failed to initialize seccomp context")
    }

    try {
        // Set CTL_NNP to false (don't automatically set no_new_privs)
        // We handle no_new_privs separately based on the OCI spec
        // SCMP_FLTATR_CTL_NNP = 3
        if (seccomp_attr_set(ctx, 3u, 0u) < 0) {
            perror("seccomp_attr_set(SCMP_FLTATR_CTL_NNP)")
            Logger.warn("failed to set SCMP_FLTATR_CTL_NNP")
        }

        // Architecture constants are defined in linux/audit.h and referenced by seccomp.h
        // For simplicity, we'll use the raw values or rely on native arch
        // Most containers will use SCMP_ARCH_NATIVE (0) which matches the host arch

        // Note: Architecture support is complex and requires audit.h constants
        // For now, we skip explicit architecture handling and rely on native arch
        if (seccomp.architectures != null && seccomp.architectures.isNotEmpty()) {
            Logger.debug("explicit architecture handling not fully implemented, using native arch")
        }

        // Add syscall rules
        seccomp.syscalls?.forEach { syscall ->
            addSyscallRule(ctx, syscall, defaultAction)
        }

        // Load the filter into the kernel
        Logger.debug("loading seccomp filter into kernel")
        if (seccomp_load(ctx) < 0) {
            perror("seccomp_load")
            Logger.error("Failed to load seccomp filter")
            throw Exception("Failed to load seccomp filter")
        }

        Logger.debug("seccomp filter loaded successfully")

        // If SCMP_ACT_NOTIFY is used, get the notify FD
        val notifyFd = if (hasNotifyAction(seccomp)) {
            val fd = seccomp_notify_fd(ctx)
            if (fd < 0) {
                perror("seccomp_notify_fd")
                Logger.error("Failed to get seccomp notify FD")
                throw Exception("Failed to get seccomp notify FD")
            }
            Logger.debug("obtained seccomp notify FD: $fd")
            fd
        } else {
            null
        }

        return notifyFd
    } finally {
        // Note: Don't release ctx yet if we're returning a notify FD,
        // as the FD is associated with the context. However, seccomp_load
        // already committed the filter to the kernel, so it's safe to release.
        seccomp_release(ctx)
    }
}

/**
 * Add a syscall rule to the seccomp filter
 */
@OptIn(ExperimentalForeignApi::class)
private fun addSyscallRule(ctx: COpaquePointer?, syscall: LinuxSyscall, defaultAction: UInt) {
    val action = translateAction(syscall.action, syscall.errnoRet)

    // Skip if action is the same as default (redundant rule)
    if (action == defaultAction) {
        Logger.debug("skipping redundant seccomp rule with default action")
        return
    }

    // Validation: SCMP_ACT_NOTIFY cannot be used for write syscall
    if (syscall.action == "SCMP_ACT_NOTIFY" && syscall.names.contains("write")) {
        Logger.warn("SCMP_ACT_NOTIFY cannot be used for write syscall, skipping")
        return
    }

    syscall.names.forEach { name ->
        // Get syscall number by name
        val syscallNum = seccomp_syscall_resolve_name(name)
        if (syscallNum == __NR_SCMP_ERROR) {
            // Syscall not supported by this kernel/arch, skip it
            Logger.debug("syscall $name not supported, skipping")
            return@forEach
        }

        if (syscall.args.isNullOrEmpty()) {
            // No argument filters, add simple rule
            if (seccomp_rule_add(ctx, action, syscallNum, 0u) < 0) {
                perror("seccomp_rule_add")
                Logger.warn("failed to add rule for syscall $name")
            }
        } else {
            // Add conditional rules
            // Note: libseccomp requires one rule per argument comparison
            syscall.args.forEach { arg ->
                if (addSyscallArgRule(ctx, action, syscallNum, name, arg) < 0) {
                    Logger.warn("failed to add conditional rule for syscall $name")
                }
            }
        }
    }
}

/**
 * Add a syscall rule with argument comparison
 */
@OptIn(ExperimentalForeignApi::class)
private fun addSyscallArgRule(
    ctx: COpaquePointer?,
    action: UInt,
    syscallNum: Int,
    name: String,
    arg: SeccompArg
): Int {
    return memScoped {
        val cmp = alloc<scmp_arg_cmp>()
        cmp.arg = arg.index
        cmp.op = translateOp(arg.op)
        cmp.datum_a = arg.value
        cmp.datum_b = 0u  // Used for SCMP_CMP_MASKED_EQ

        seccomp_rule_add_array(ctx, action, syscallNum, 1u, cmp.ptr)
    }
}
