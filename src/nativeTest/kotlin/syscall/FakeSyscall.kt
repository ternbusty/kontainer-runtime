package syscall

import spec.POSIXRlimit

/**
 * In-memory [Syscall] for unit tests.
 *
 * Every call is appended to [calls] as a string for assertion. Methods returning
 * Int default to 0 (success); override [returnValues] for a specific method name
 * to make it return something else. Methods that throw real exceptions in the
 * Linux implementation (e.g. capget failure) are not modeled here — tests that
 * exercise that path should call [shouldThrowOn].
 */
class FakeSyscall : Syscall {
    val calls: MutableList<String> = mutableListOf()
    var capabilities: CapabilitySets = CapabilitySets(0u, 0u, 0u)
    var euid: UInt = 0u
    var egid: UInt = 0u

    override fun mount(
        source: String?,
        target: String,
        fstype: String?,
        flags: ULong,
        data: String?,
    ): Int {
        calls += "mount(source=$source, target=$target, fstype=$fstype, flags=$flags, data=$data)"
        return 0
    }

    override fun umount2(
        target: String,
        flags: Int,
    ): Int {
        calls += "umount2(target=$target, flags=$flags)"
        return 0
    }

    override fun pivotRoot(
        newRoot: String,
        putOld: String,
    ): Int {
        calls += "pivotRoot(newRoot=$newRoot, putOld=$putOld)"
        return 0
    }

    override fun chroot(path: String): Int {
        calls += "chroot(path=$path)"
        return 0
    }

    override fun chdir(path: String): Int {
        calls += "chdir(path=$path)"
        return 0
    }

    override fun setuid(uid: UInt): Int {
        calls += "setuid(uid=$uid)"
        return 0
    }

    override fun setgid(gid: UInt): Int {
        calls += "setgid(gid=$gid)"
        return 0
    }

    override fun geteuid(): UInt {
        calls += "geteuid()"
        return euid
    }

    override fun getegid(): UInt {
        calls += "getegid()"
        return egid
    }

    override fun sethostname(name: String): Int {
        calls += "sethostname(name=$name)"
        return 0
    }

    override fun umask(mask: UInt): UInt {
        calls += "umask(mask=$mask)"
        return 0u
    }

    override fun prctl(
        option: Int,
        arg2: ULong,
        arg3: ULong,
        arg4: ULong,
        arg5: ULong,
    ): Int {
        calls += "prctl(option=$option, arg2=$arg2, arg3=$arg3, arg4=$arg4, arg5=$arg5)"
        return 0
    }

    override fun getCapabilities(): CapabilitySets {
        calls += "getCapabilities()"
        return capabilities
    }

    override fun setCapabilities(caps: CapabilitySets) {
        calls += "setCapabilities(effective=${caps.effective}, permitted=${caps.permitted}, inheritable=${caps.inheritable})"
        capabilities = caps
    }

    override fun applyRlimits(
        pid: Int,
        rlimits: List<POSIXRlimit>?,
    ) {
        calls += "applyRlimits(pid=$pid, rlimits=${rlimits?.map { it.type }})"
    }

    override fun setNoNewPrivileges() {
        calls += "setNoNewPrivileges()"
    }

    override fun closeRange(preserveFds: Int) {
        calls += "closeRange(preserveFds=$preserveFds)"
    }

    override fun setAdditionalGroups(gids: List<UInt>) {
        calls += "setAdditionalGroups(gids=$gids)"
    }

    override fun killProcess(
        pid: Int,
        signal: Int,
    ) {
        calls += "killProcess(pid=$pid, signal=$signal)"
    }
}
