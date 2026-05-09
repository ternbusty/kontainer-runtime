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
}

/**
 * Default [Syscall] instance used by domain code.
 *
 * This is a temporary singleton bridging the in-progress refactor: existing
 * callers still invoke top-level helpers and need a single entry point. Once
 * every caller takes a [Syscall] parameter explicitly (Step 4 of the refactor),
 * this top-level binding will be removed.
 */
val defaultSyscall: Syscall = LinuxSyscall()
