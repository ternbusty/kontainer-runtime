package syscall

import spec.POSIXRlimit

/**
 * Abstraction over kernel and libc operations the runtime invokes.
 *
 * This is the seam used to mock kernel-facing behavior in unit tests.
 * Domain code should call methods on a [Syscall] instance rather than
 * invoking platform.posix.* / platform.linux.* directly.
 *
 * [LinuxSyscall] is the production implementation. Tests inject a fake
 * implementation that records calls.
 */
interface Syscall {
    // Mount-family
    fun mount(
        source: String?,
        target: String,
        fstype: String?,
        flags: ULong,
        data: String? = null,
    ): Int

    fun umount2(
        target: String,
        flags: Int,
    ): Int

    fun pivotRoot(
        newRoot: String,
        putOld: String,
    ): Int

    fun chroot(path: String): Int

    fun chdir(path: String): Int

    // Identity / process
    fun setuid(uid: UInt): Int

    fun setgid(gid: UInt): Int

    fun geteuid(): UInt

    fun getegid(): UInt

    fun sethostname(name: String): Int

    fun umask(mask: UInt): UInt

    // prctl primitive (used by capabilities and similar callers)
    fun prctl(
        option: Int,
        arg2: ULong,
        arg3: ULong,
        arg4: ULong,
        arg5: ULong,
    ): Int

    // Capabilities (capget/capset wrapped in logical form so the cinterop
    // structs don't leak through the interface)
    fun getCapabilities(): CapabilitySets

    fun setCapabilities(caps: CapabilitySets)

    // Resource / signal / fd helpers preserved from the existing wrappers
    fun applyRlimits(
        pid: Int,
        rlimits: List<POSIXRlimit>?,
    )

    fun setNoNewPrivileges()

    fun closeRange(preserveFds: Int = 0)

    fun setAdditionalGroups(gids: List<UInt>)

    fun killProcess(
        pid: Int,
        signal: Int,
    )

    /**
     * Join the namespace referenced by the given file descriptor.
     * [nstype] is one of CLONE_NEWNS / CLONE_NEWNET / ... and acts as a guard:
     * the kernel returns EINVAL if the fd is not of that type (0 disables the check).
     */
    fun setns(
        fd: Int,
        nstype: Int,
    ): Int
}

/**
 * The three capability bitmasks read/written together by capget(2)/capset(2).
 * Each bit position corresponds to a [capability.Capability.value].
 */
data class CapabilitySets(
    val effective: ULong,
    val permitted: ULong,
    val inheritable: ULong,
)
