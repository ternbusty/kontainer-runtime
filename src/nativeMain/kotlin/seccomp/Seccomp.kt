package seccomp

import kotlinx.cinterop.*
import libseccomp.*
import logger.Logger
import platform.posix.*
import spec.LinuxSeccomp
import spec.LinuxSyscall
import spec.SeccompArg

/**
 * Seccomp implementation using libseccomp
 */

/**
 * Translate OCI architecture name to libseccomp architecture name
 *
 * OCI spec uses names like "SCMP_ARCH_X86_64" while libseccomp's
 * seccomp_arch_resolve_name() expects names like "x86_64"
 */
private fun translateArchName(ociArchName: String): String =
    when (ociArchName) {
        "SCMP_ARCH_NATIVE" -> "native"
        "SCMP_ARCH_X86" -> "x86"
        "SCMP_ARCH_X86_64" -> "x86_64"
        "SCMP_ARCH_X32" -> "x32"
        "SCMP_ARCH_ARM" -> "arm"
        "SCMP_ARCH_AARCH64" -> "aarch64"
        "SCMP_ARCH_MIPS" -> "mips"
        "SCMP_ARCH_MIPS64" -> "mips64"
        "SCMP_ARCH_MIPS64N32" -> "mips64n32"
        "SCMP_ARCH_MIPSEL" -> "mipsel"
        "SCMP_ARCH_MIPSEL64" -> "mipsel64"
        "SCMP_ARCH_MIPSEL64N32" -> "mipsel64n32"
        "SCMP_ARCH_PPC" -> "ppc"
        "SCMP_ARCH_PPC64" -> "ppc64"
        "SCMP_ARCH_PPC64LE" -> "ppc64le"
        "SCMP_ARCH_S390" -> "s390"
        "SCMP_ARCH_S390X" -> "s390x"
        "SCMP_ARCH_PARISC" -> "parisc"
        "SCMP_ARCH_PARISC64" -> "parisc64"
        "SCMP_ARCH_RISCV64" -> "riscv64"
        else -> {
            Logger.error("Unknown OCI architecture name: $ociArchName")
            throw Exception("Unknown OCI architecture name: $ociArchName")
        }
    }

/**
 * Translate OCI spec action string to libseccomp action constant
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateAction(
    action: String,
    errno: UInt?,
): UInt =
    when (action) {
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

/**
 * Translate OCI spec operator string to libseccomp operator constant
 */
@OptIn(ExperimentalForeignApi::class)
private fun translateOp(op: String): scmp_compare =
    when (op) {
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

/**
 * Check if seccomp config uses SCMP_ACT_NOTIFY action.
 * Public so callers (MainProcess, exec) can decide up front whether a notify
 * FD will need forwarding to the OCI seccomp listener.
 */
fun seccompUsesNotify(seccomp: LinuxSeccomp): Boolean = seccomp.syscalls?.any { it.action == "SCMP_ACT_NOTIFY" } ?: false

/**
 * Validate that all seccomp flags in the spec are supported by the
 * linked libseccomp.  Called from the parent process *before* forking
 * so that error messages are visible in the runtime's output (the
 * child's unhandled-exception output is lost after fork).
 *
 * @throws Exception when an unsupported flag is requested
 */
@OptIn(ExperimentalForeignApi::class)
fun validateSeccompFlags(seccomp: LinuxSeccomp) {
    seccomp.flags?.forEach { flag ->
        when (flag) {
            "SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV" -> {
                if (!libseccompVersionAtLeast(2, 6, 0)) {
                    throw Exception(
                        "error adding WaitKill flag to seccomp filter: " +
                            "SetWaitKill requires libseccomp >= 2.6.0 " +
                            "(have ${_seccomp_version_major()}.${_seccomp_version_minor()}.${_seccomp_version_micro()})",
                    )
                }
            }
            "SECCOMP_FILTER_FLAG_TSYNC",
            "SECCOMP_FILTER_FLAG_LOG",
            "SECCOMP_FILTER_FLAG_SPEC_ALLOW",
            -> {} // always supported
            else -> {} // unknown flags are warned at apply time
        }
    }

    // If SCMP_ACT_NOTIFY is used, the listenerPath must be non-empty.
    // An empty or missing listenerPath means nobody is listening for the
    // seccomp notifications and the container will hang on the first
    // notified syscall.
    if (seccompUsesNotify(seccomp)) {
        val listenerPath = seccomp.listenerPath
        if (listenerPath.isNullOrEmpty()) {
            throw Exception(
                "seccomp SCMP_ACT_NOTIFY action is used but no listener path is configured; " +
                    "set linux.seccomp.listenerPath to the Unix socket path of the seccomp agent",
            )
        }
    }
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
    val ctx =
        seccomp_init(defaultAction) ?: run {
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

        // Process seccomp flags from the OCI spec.
        // When no flags are specified (null or absent), default to SPEC_ALLOW
        // if supported (matching runc behavior).
        val effectiveFlags = seccomp.flags
        if (effectiveFlags == null) {
            // No flags field: auto-add SPEC_ALLOW
            try {
                applySeccompFlag(ctx, "SECCOMP_FILTER_FLAG_SPEC_ALLOW")
            } catch (_: Exception) {
                Logger.debug("SECCOMP_FILTER_FLAG_SPEC_ALLOW not supported, skipping default")
            }
        } else {
            effectiveFlags.forEach { flag ->
                applySeccompFlag(ctx, flag)
            }
        }

        // Compute the combined numeric value of the applied flags and log it
        // (runc debug output: "seccomp filter flags: N")
        val flagsValue = computeSeccompFlagsValue(effectiveFlags)
        Logger.debug("seccomp filter flags: $flagsValue")

        // Handle architecture specification and Add specified architectures
        if (seccomp.architectures != null && seccomp.architectures.isNotEmpty()) {
            Logger.debug("processing ${seccomp.architectures.size} architecture(s)")

            // Remove native architecture (added by default)
            if (seccomp_arch_remove(ctx, SCMP_ARCH_NATIVE.toUInt()) < 0) {
                perror("seccomp_arch_remove(SCMP_ARCH_NATIVE)")
                Logger.warn("failed to remove native architecture")
            } else {
                Logger.debug("removed default native architecture")
            }

            seccomp.architectures.forEach { ociArchName ->
                // Translate OCI arch name to libseccomp arch name
                val libseccompArchName = translateArchName(ociArchName)

                val archToken = seccomp_arch_resolve_name(libseccompArchName)
                if (archToken == 0u) {
                    Logger.error("unknown architecture: $ociArchName (libseccomp name: $libseccompArchName)")
                    throw Exception("Unknown seccomp architecture: $ociArchName")
                }

                Logger.debug("adding architecture: $ociArchName -> $libseccompArchName (token=$archToken)")
                if (seccomp_arch_add(ctx, archToken) < 0) {
                    perror("seccomp_arch_add")
                    Logger.error("failed to add architecture: $ociArchName")
                    throw Exception("Failed to add seccomp architecture: $ociArchName")
                }
            }
            Logger.debug("all architectures added successfully")
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

        // runc compat (patchbpf): install a second BPF filter that returns
        // ENOSYS for syscall numbers beyond the native architecture's known
        // range. Without this stub, unknown/future syscalls hit the libseccomp
        // default action (typically ERRNO+EPERM) instead of ENOSYS. Since
        // the kernel picks the most restrictive result across multiple filters,
        // and ERRNO(ENOSYS) beats ALLOW, this correctly upgrades unknown
        // syscalls to ENOSYS without affecting explicit rules.
        installEnosysStub(seccomp.syscalls, computeSeccompFlagsValue(seccomp.flags))

        // If SCMP_ACT_NOTIFY is used, get the notify FD
        val notifyFd =
            if (seccompUsesNotify(seccomp)) {
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

        Logger.info("seccomp filter initialized successfully")
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
private fun addSyscallRule(
    ctx: COpaquePointer?,
    syscall: LinuxSyscall,
    defaultAction: UInt,
) {
    val action = translateAction(syscall.action, syscall.errnoRet)

    // Skip if action is the same as default (redundant rule)
    if (action == defaultAction) {
        Logger.debug("skipping redundant seccomp rule with default action")
        return
    }

    // Validation: SCMP_ACT_NOTIFY cannot be used for write syscall.
    // After seccomp init, we need to write the seccomp fd on the sync pipe
    // to the parent; if write is notified, we deadlock.
    if (syscall.action == "SCMP_ACT_NOTIFY" && syscall.names.contains("write")) {
        Logger.error("SCMP_ACT_NOTIFY cannot be used for the write syscall")
        throw Exception("SCMP_ACT_NOTIFY cannot be used for the write syscall")
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
            // Add conditional rules with proper AND/OR logic
            // Count how many conditions exist for each argument index
            val argCounts = mutableMapOf<UInt, Int>()
            syscall.args.forEach { arg ->
                argCounts[arg.index] = (argCounts[arg.index] ?: 0) + 1
            }

            // Check if multiple conditions share the same argument index
            val hasMultipleArgs = argCounts.values.any { it > 1 }

            if (hasMultipleArgs) {
                // Multiple conditions on the same argument index
                // Add each condition as a separate rule (OR behavior)
                Logger.debug("syscall $name has multiple conditions on same arg, using OR logic")
                syscall.args.forEach { arg ->
                    if (addSyscallArgRule(ctx, action, syscallNum, arg) < 0) {
                        Logger.error("failed to add conditional rule for syscall $name")
                        throw Exception("Failed to add conditional seccomp rule for syscall: $name")
                    }
                }
            } else {
                // Each condition is on a different argument index
                // Add all conditions as a single rule (AND behavior)
                Logger.debug("syscall $name has conditions on different args, using AND logic")
                memScoped {
                    val cmpArray = allocArray<scmp_arg_cmp>(syscall.args.size)
                    syscall.args.forEachIndexed { i, arg ->
                        cmpArray[i].arg = arg.index
                        cmpArray[i].op = translateOp(arg.op)
                        cmpArray[i].datum_a = arg.value
                        cmpArray[i].datum_b = arg.valueTwo ?: 0u
                    }

                    if (seccomp_rule_add_array(ctx, action, syscallNum, syscall.args.size.toUInt(), cmpArray) < 0) {
                        perror("seccomp_rule_add_array")
                        Logger.error("failed to add conditional rule for syscall $name")
                        throw Exception("Failed to add conditional seccomp rule for syscall: $name")
                    }
                }
            }
        }
    }
}

/**
 * Check if the linked libseccomp version is at least major.minor.micro.
 */
@OptIn(ExperimentalForeignApi::class)
private fun libseccompVersionAtLeast(
    major: Int,
    minor: Int,
    micro: Int,
): Boolean {
    val curMajor = _seccomp_version_major()
    val curMinor = _seccomp_version_minor()
    val curMicro = _seccomp_version_micro()
    if (curMajor < 0) return false // seccomp_version() failed
    return curMajor > major ||
        (curMajor == major && curMinor > minor) ||
        (curMajor == major && curMinor == minor && curMicro >= micro)
}

/**
 * Apply a single OCI seccomp flag to the filter context.
 *
 * Maps OCI spec flag names (SECCOMP_FILTER_FLAG_*) to the corresponding
 * libseccomp filter attributes.  Some flags require a minimum libseccomp
 * version — when the linked library is too old, we throw so that callers
 * (and bats tests) get a clear error rather than silently ignoring the flag.
 */
@OptIn(ExperimentalForeignApi::class)
private fun applySeccompFlag(
    ctx: COpaquePointer?,
    flag: String,
) {
    when (flag) {
        "SECCOMP_FILTER_FLAG_TSYNC" -> {
            if (seccomp_attr_set(ctx, SCMP_FLTATR_CTL_TSYNC, 1u) < 0) {
                perror("seccomp_attr_set(SCMP_FLTATR_CTL_TSYNC)")
                throw Exception("Failed to set seccomp flag: $flag")
            }
        }
        "SECCOMP_FILTER_FLAG_LOG" -> {
            if (seccomp_attr_set(ctx, SCMP_FLTATR_CTL_LOG, 1u) < 0) {
                perror("seccomp_attr_set(SCMP_FLTATR_CTL_LOG)")
                throw Exception("Failed to set seccomp flag: $flag")
            }
        }
        "SECCOMP_FILTER_FLAG_SPEC_ALLOW" -> {
            if (seccomp_attr_set(ctx, SCMP_FLTATR_CTL_SSB, 1u) < 0) {
                perror("seccomp_attr_set(SCMP_FLTATR_CTL_SSB)")
                throw Exception("Failed to set seccomp flag: $flag")
            }
        }
        "SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV" -> {
            if (!libseccompVersionAtLeast(2, 6, 0)) {
                // Match runc's error format so the bats test can detect and skip gracefully.
                throw Exception(
                    "error adding WaitKill flag to seccomp filter: " +
                        "SetWaitKill requires libseccomp >= 2.6.0 " +
                        "(have ${_seccomp_version_major()}.${_seccomp_version_minor()}.${_seccomp_version_micro()})",
                )
            }
            if (seccomp_attr_set(ctx, _SCMP_FLTATR_CTL_WAITKILL(), 1u) < 0) {
                perror("seccomp_attr_set(SCMP_FLTATR_CTL_WAITKILL)")
                throw Exception(
                    "error adding WaitKill flag to seccomp filter: " +
                        "SetWaitKill requires libseccomp >= 2.6.0",
                )
            }
        }
        else -> {
            Logger.warn("unknown seccomp flag: $flag (ignored)")
        }
    }
    Logger.debug("applied seccomp flag: $flag")
}

/**
 * Compute the combined numeric kernel flags value from OCI flag names.
 * When [flags] is null (field absent), runc defaults to SPEC_ALLOW (4)
 * if the kernel supports it.
 */
private fun computeSeccompFlagsValue(flags: List<String>?): Int {
    if (flags == null) {
        // No flags field: runc defaults to SPEC_ALLOW
        return 4 // SECCOMP_FILTER_FLAG_SPEC_ALLOW
    }
    var sum = 0
    for (flag in flags) {
        sum +=
            when (flag) {
                "SECCOMP_FILTER_FLAG_TSYNC" -> 0
                "SECCOMP_FILTER_FLAG_LOG" -> 2
                "SECCOMP_FILTER_FLAG_SPEC_ALLOW" -> 4
                "SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV" -> 32
                else -> 0
            }
    }
    return sum
}

/**
 * Add a syscall rule with argument comparison
 */
@OptIn(ExperimentalForeignApi::class)
private fun addSyscallArgRule(
    ctx: COpaquePointer?,
    action: UInt,
    syscallNum: Int,
    arg: SeccompArg,
): Int =
    memScoped {
        val cmp = alloc<scmp_arg_cmp>()
        cmp.arg = arg.index
        cmp.op = translateOp(arg.op)
        cmp.datum_a = arg.value
        cmp.datum_b = arg.valueTwo ?: 0u // For SCMP_CMP_MASKED_EQ

        seccomp_rule_add_array(ctx, action, syscallNum, 1u, cmp.ptr)
    }

/**
 * Install a raw BPF filter that returns ENOSYS for syscall numbers beyond the
 * native architecture's known range. Installed as a second seccomp filter after
 * the libseccomp one. Since the kernel picks the most restrictive result across
 * filters, and ERRNO(ENOSYS) beats ALLOW, this correctly upgrades "unknown
 * syscall gets ALLOW" to ENOSYS without affecting rules that already return
 * something more restrictive (KILL, TRAP).
 *
 * BPF program (6 instructions):
 *   [0] LD_ABS  arch           (seccomp_data.arch at offset 4)
 *   [1] JEQ    native_arch 0 3 (if not native arch, skip to ALLOW)
 *   [2] LD_ABS  nr             (seccomp_data.nr at offset 0)
 *   [3] JGE    threshold 0 1   (if nr < threshold, skip to ALLOW)
 *   [4] RET    ERRNO|ENOSYS
 *   [5] RET    ALLOW
 */
@Suppress("NOTHING_TO_INLINE")
@OptIn(ExperimentalForeignApi::class)
private fun installEnosysStub(
    syscalls: List<LinuxSyscall>?,
    filterFlagsValue: Int,
) {
    val threshold = findMaxSyscallNr(syscalls) + 1
    if (threshold <= 1) {
        Logger.debug("patchbpf: no syscalls resolved, skipping ENOSYS stub")
        return
    }
    Logger.debug("patchbpf: ENOSYS stub for nr >= $threshold")

    // AUDIT_ARCH_* = EM_<arch> | __AUDIT_ARCH_64BIT | __AUDIT_ARCH_LE
    // aarch64: 0xC00000B7 (EM_AARCH64=183), x86_64: 0xC000003E (EM_X86_64=62)
    val auditArch: UInt =
        if (platform.posix.sysconf(platform.posix._SC_PAGESIZE) > 0) {
            // Detect architecture at runtime via uname
            memScoped {
                val uts = alloc<platform.posix.utsname>()
                platform.posix.uname(uts.ptr)
                val machine = uts.machine.toKString()
                when {
                    machine.contains("aarch64") -> 0xC00000B7u
                    else -> 0xC000003Eu // x86_64
                }
            }
        } else {
            0xC000003Eu
        }

    val retEnosys = 0x00050000u or 38u // SECCOMP_RET_ERRNO | ENOSYS
    val retAllow = 0x7FFF0000u // SECCOMP_RET_ALLOW

    memScoped {
        // struct sock_filter = { __u16 code; __u8 jt; __u8 jf; __u32 k; } = 8 bytes
        val instCount = 6
        val filter = allocArray<ByteVar>(instCount * 8)

        fun writeBpfInst(
            idx: Int,
            code: UShort,
            jt: UByte,
            jf: UByte,
            k: UInt,
        ) {
            val off = idx * 8
            filter[off + 0] = (code.toInt() and 0xFF).toByte()
            filter[off + 1] = ((code.toInt() shr 8) and 0xFF).toByte()
            filter[off + 2] = jt.toByte()
            filter[off + 3] = jf.toByte()
            filter[off + 4] = (k.toInt() and 0xFF).toByte()
            filter[off + 5] = ((k.toInt() shr 8) and 0xFF).toByte()
            filter[off + 6] = ((k.toInt() shr 16) and 0xFF).toByte()
            filter[off + 7] = ((k.toInt() shr 24) and 0xFF).toByte()
        }

        writeBpfInst(0, 0x20u, 0u, 0u, 4u) // LD_ABS arch
        writeBpfInst(1, 0x15u, 0u, 3u, auditArch) // JEQ native_arch, skip 0, else skip 3
        writeBpfInst(2, 0x20u, 0u, 0u, 0u) // LD_ABS nr
        writeBpfInst(3, 0x35u, 0u, 1u, threshold.toUInt()) // JGE threshold, skip 0, else skip 1
        writeBpfInst(4, 0x06u, 0u, 0u, retEnosys) // RET ERRNO|ENOSYS
        writeBpfInst(5, 0x06u, 0u, 0u, retAllow) // RET ALLOW

        // struct sock_fprog { unsigned short len; struct sock_filter *filter; }
        // On 64-bit: 2 bytes len + 6 bytes pad + 8 bytes pointer = 16 bytes
        val prog = allocArray<ByteVar>(16)
        prog[0] = (instCount and 0xFF).toByte()
        prog[1] = ((instCount shr 8) and 0xFF).toByte()
        // bytes 2-7: padding (zeroed by allocArray)
        // bytes 8-15: pointer to filter
        val filterPtr = filter.rawValue.toLong()
        for (i in 0..7) {
            prog[8 + i] = ((filterPtr shr (i * 8)) and 0xFF).toByte()
        }

        // seccomp(SECCOMP_SET_MODE_FILTER=1, flags, &prog)
        // Strip NEW_LISTENER (0x08) and WAIT_KILLABLE_RECV (0x20) flags
        // — those only apply when the filter uses NOTIFY.
        val stubFlags = (filterFlagsValue and (0x08 or 0x20).inv()).toLong()
        // __NR_seccomp: 317 on x86_64, 277 on aarch64
        val nrSeccomp = if (auditArch == 0xC00000B7u) 277L else 317L
        val rc = syscall(nrSeccomp, 1L, stubFlags, prog)
        if (rc != 0L) {
            Logger.warn("patchbpf: seccomp(SET_MODE_FILTER) failed (errno=${platform.posix.errno})")
        } else {
            Logger.debug("patchbpf: ENOSYS stub installed")
        }
    }
}

/**
 * Find the highest native syscall number by probing libseccomp with
 * names from the spec and a list of recently-added syscalls.
 */
@OptIn(ExperimentalForeignApi::class)
private fun findMaxSyscallNr(syscalls: List<LinuxSyscall>?): Int {
    var max = 0

    // Probe spec-referenced syscalls.
    syscalls?.forEach { sc ->
        sc.names.forEach { name ->
            val nr = seccomp_syscall_resolve_name(name)
            if (nr > 0 && nr > max) max = nr
        }
    }

    // Probe well-known high-numbered syscalls to find the native arch's max.
    val probes =
        listOf(
            "removexattrat",
            "listxattrat",
            "getxattrat",
            "setxattrat",
            "mseal",
            "lsm_list_modules",
            "lsm_set_self_attr",
            "lsm_get_self_attr",
            "listmount",
            "statmount",
            "futex_requeue",
            "futex_wait",
            "futex_wake",
            "fchmodat2",
            "cachestat",
            "set_mempolicy_home_node",
            "process_mrelease",
            "futex_waitv",
            "epoll_pwait2",
            "mount_setattr",
            "openat2",
            "pidfd_getfd",
            "close_range",
            "io_uring_setup",
            "pidfd_send_signal",
            "io_uring_enter",
            "rseq",
            "pkey_free",
            "pkey_alloc",
            "pkey_mprotect",
            "statx",
            "copy_file_range",
            "preadv2",
            "memfd_create",
            "getrandom",
            "membarrier",
            "execveat",
            "userfaultfd",
            "seccomp",
            "sched_setattr",
            "renameat2",
            "kcmp",
            "finit_module",
            "process_vm_writev",
            "process_vm_readv",
        )
    for (name in probes) {
        val nr = seccomp_syscall_resolve_name(name)
        if (nr > 0 && nr > max) max = nr
    }

    if (max < 100) max = 450 // safe fallback
    return max
}
