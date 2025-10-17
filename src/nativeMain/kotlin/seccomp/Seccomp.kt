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
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateAction(action: String, errno: UInt?): UInt {
    return when (action) {
        "SCMP_ACT_KILL" -> SCMP_ACT_KILL_THREAD
        "SCMP_ACT_KILL_PROCESS" -> SCMP_ACT_KILL_PROCESS
        "SCMP_ACT_KILL_THREAD" -> SCMP_ACT_KILL_THREAD
        "SCMP_ACT_TRAP" -> SCMP_ACT_TRAP
        "SCMP_ACT_ERRNO" -> {
            val errnoVal = errno ?: 1u
            _SCMP_ACT_ERRNO(errnoVal)
        }

        "SCMP_ACT_TRACE" -> {
            val traceVal = errno ?: 1u
            _SCMP_ACT_TRACE(traceVal)
        }

        "SCMP_ACT_ALLOW" -> SCMP_ACT_ALLOW
        "SCMP_ACT_LOG" -> SCMP_ACT_LOG
        "SCMP_ACT_NOTIFY" -> SCMP_ACT_NOTIFY
        else -> {
            Logger.error("Unknown seccomp action: $action")
            throw Exception("Unknown seccomp action: $action")
        }
    }
}

/**
 * Translate OCI spec operator string to libseccomp operator constant
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateOp(op: String): scmp_compare {
    return when (op) {
        "SCMP_CMP_NE" -> SCMP_CMP_NE
        "SCMP_CMP_LT" -> SCMP_CMP_LT
        "SCMP_CMP_LE" -> SCMP_CMP_LE
        "SCMP_CMP_EQ" -> SCMP_CMP_EQ
        "SCMP_CMP_GE" -> SCMP_CMP_GE
        "SCMP_CMP_GT" -> SCMP_CMP_GT
        "SCMP_CMP_MASKED_EQ" -> SCMP_CMP_MASKED_EQ
        else -> {
            Logger.error("Unknown seccomp operator: $op")
            throw Exception("Unknown seccomp operator: $op")
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
        if (seccomp_attr_set(ctx, SCMP_FLTATR_CTL_NNP, 0u) < 0) {
            perror("seccomp_attr_set(SCMP_FLTATR_CTL_NNP)")
            Logger.warn("failed to set SCMP_FLTATR_CTL_NNP")
        }

        // Architecture constants are defined in linux/audit.h and referenced by seccomp.h
        // Architecture support is complex and requires audit.h constants
        // For now, only native arch is supported
        if (seccomp.architectures != null && seccomp.architectures.isNotEmpty()) {
            Logger.error("explicit architecture specification is not yet supported")
            throw Exception("Explicit architecture specification in seccomp is not yet supported. Only native architecture is supported.")
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
        Logger.error("SCMP_ACT_NOTIFY cannot be used for write syscall")
        throw Exception("SCMP_ACT_NOTIFY cannot be used for write syscall")
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
                Logger.error("failed to add rule for syscall $name")
                throw Exception("Failed to add seccomp rule for syscall: $name")
            }
        } else {
            // Add conditional rules
            // Note: libseccomp requires one rule per argument comparison
            syscall.args.forEach { arg ->
                if (addSyscallArgRule(ctx, action, syscallNum, arg) < 0) {
                    Logger.error("failed to add conditional rule for syscall $name")
                    throw Exception("Failed to add conditional seccomp rule for syscall: $name")
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
