package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.linux.*
import platform.posix.*
import spec.POSIXRlimit

/**
 * Production [Syscall] implementation invoking real kernel and libc routines.
 *
 * Each method is a thin wrapper around the corresponding `platform.posix.*` or
 * `platform.linux.*` call. The few methods that contain higher-level logic
 * (looping over rlimits, falling back when close_range is unavailable, reading
 * /proc/self/setgroups) live here too so callers see a single seam.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxSyscall : Syscall {
    override fun mount(
        source: String?,
        target: String,
        fstype: String?,
        flags: ULong,
        data: String?,
    ): Int =
        memScoped {
            val src = source?.cstr?.ptr
            val tgt = target.cstr.ptr
            val fs = fstype?.cstr?.ptr
            val d = data?.cstr?.ptr

            syscall(__NR_mount.toLong(), src, tgt, fs, flags, d).toInt()
        }

    override fun umount2(
        target: String,
        flags: Int,
    ): Int =
        memScoped {
            syscall(__NR_umount2.toLong(), target.cstr.ptr, flags).toInt()
        }

    override fun pivotRoot(
        newRoot: String,
        putOld: String,
    ): Int =
        memScoped {
            syscall(__NR_pivot_root.toLong(), newRoot.cstr.ptr, putOld.cstr.ptr).toInt()
        }

    override fun applyRlimits(
        pid: Int,
        rlimits: List<POSIXRlimit>?,
    ) {
        if (rlimits.isNullOrEmpty()) {
            Logger.debug("no rlimits to apply")
            return
        }

        Logger.debug("applying ${rlimits.size} rlimits to PID $pid")

        memScoped {
            for (rlimit in rlimits) {
                val resource = rlimitTypeToResource(rlimit.type)
                if (resource == null) {
                    Logger.warn("skipping unknown rlimit type: ${rlimit.type}")
                    continue
                }

                val newLimit = alloc<rlimit>()
                newLimit.rlim_cur = rlimit.soft
                newLimit.rlim_max = rlimit.hard

                if (prlimit_wrapper(pid, resource, newLimit.ptr, null) != 0) {
                    val errorMsg = strerror(errno)?.toKString() ?: "unknown error"
                    Logger.warn("failed to set rlimit ${rlimit.type} (resource=$resource) for PID $pid: $errorMsg")
                    // Continue applying other rlimits instead of failing
                } else {
                    Logger.debug("set rlimit ${rlimit.type}: soft=${rlimit.soft}, hard=${rlimit.hard}")
                }
            }
        }

        Logger.info("rlimits applied successfully")
    }

    override fun setNoNewPrivileges() {
        Logger.debug("setting no_new_privileges")

        val result =
            syscall(
                __NR_prctl.toLong(),
                PR_SET_NO_NEW_PRIVS.toLong(),
                1L, // arg2: 1 to set no_new_privs
                0L, // arg3: unused
                0L, // arg4: unused
                0L, // arg5: unused
            )

        if (result == -1L) {
            val errNum = errno
            perror("prctl(PR_SET_NO_NEW_PRIVS)")
            Logger.warn("failed to set no_new_privileges (errno=$errNum)")
            // Do not throw exception
            // Seccomp can still work via the privileged path if needed
        } else {
            Logger.debug("successfully set no_new_privileges")
        }
    }

    override fun closeRange(preserveFds: Int) {
        val minFd = 3 + preserveFds // stdin=0, stdout=1, stderr=2, then preserve_fds
        val maxFd = Int.MAX_VALUE

        Logger.debug("setting CLOEXEC on FDs >= $minFd")

        val result =
            syscall(
                _SYS_close_range(),
                minFd.toLong(),
                maxFd.toLong(),
                _CLOSE_RANGE_CLOEXEC().toLong(),
            )

        if (result == -1L) {
            val errNum = errno
            // ENOSYS = syscall not available (old kernel < 5.9)
            // EINVAL = flag not supported (old kernel < 5.11 doesn't support CLOSE_RANGE_CLOEXEC)
            if (errNum == ENOSYS || errNum == EINVAL) {
                Logger.debug("close_range not supported (errno=$errNum), using fallback")
                emulateCloseRange(preserveFds)
            } else {
                perror("close_range")
                Logger.warn("close_range failed with errno=$errNum, trying fallback")
                emulateCloseRange(preserveFds)
            }
        } else {
            Logger.debug("successfully set CLOEXEC on FDs >= $minFd using close_range")
        }
    }

    override fun setAdditionalGroups(gids: List<UInt>) {
        if (gids.isEmpty()) {
            Logger.debug("no additional groups to set")
            return
        }

        // /proc/self/setgroups may contain "deny" in unprivileged user namespace (Linux 3.19+)
        val setgroupsPath = "/proc/self/setgroups"
        memScoped {
            val fp = fopen(setgroupsPath, "r")
            if (fp == null) {
                Logger.debug("/proc/self/setgroups does not exist, proceeding with setgroups")
            } else {
                val buffer = allocArray<ByteVar>(32)
                val result = fgets(buffer, 32, fp)
                fclose(fp)

                if (result != null) {
                    val content = result.toKString().trim()
                    if (content == "deny") {
                        Logger.warn("setgroups is denied in this user namespace, skipping")
                        return
                    }
                }
            }
        }

        memScoped {
            val gidArray = allocArray<gid_tVar>(gids.size)
            gids.forEachIndexed { i, gid ->
                gidArray[i] = gid
            }

            if (setgroups(gids.size.toULong(), gidArray) != 0) {
                val errorMsg = strerror(errno)?.toKString() ?: "unknown error"
                Logger.warn("failed to set additional groups: $errorMsg (errno=$errno)")
                // Don't throw: this can fail in unprivileged user namespace
            } else {
                Logger.debug("set ${gids.size} additional groups successfully")
            }
        }
    }

    override fun killProcess(
        pid: Int,
        signal: Int,
    ) {
        Logger.debug("sending signal $signal to process $pid")

        val result = kill(pid, signal)

        when {
            result == 0 -> {
                Logger.debug("successfully sent signal $signal to process $pid")
            }
            result == -1 -> {
                val errNum = errno
                if (errNum == ESRCH) {
                    // Process doesn't exist - this is OK (race condition)
                    Logger.debug("process $pid does not exist (ESRCH), already terminated")
                } else {
                    perror("kill")
                    Logger.error("failed to send signal $signal to process $pid: errno=$errNum")
                    throw Exception("Failed to kill process $pid: errno=$errNum")
                }
            }
            else -> {
                Logger.error("kill returned unexpected value: $result")
                throw Exception("kill returned unexpected value: $result")
            }
        }
    }

    private fun rlimitTypeToResource(type: String): Int? =
        when (type) {
            "RLIMIT_AS" -> RLIMIT_AS
            "RLIMIT_CORE" -> RLIMIT_CORE
            "RLIMIT_CPU" -> RLIMIT_CPU
            "RLIMIT_DATA" -> RLIMIT_DATA
            "RLIMIT_FSIZE" -> RLIMIT_FSIZE
            "RLIMIT_LOCKS" -> RLIMIT_LOCKS
            "RLIMIT_MEMLOCK" -> RLIMIT_MEMLOCK
            "RLIMIT_MSGQUEUE" -> RLIMIT_MSGQUEUE
            "RLIMIT_NICE" -> RLIMIT_NICE
            "RLIMIT_NOFILE" -> RLIMIT_NOFILE
            "RLIMIT_NPROC" -> RLIMIT_NPROC
            "RLIMIT_RSS" -> RLIMIT_RSS
            "RLIMIT_RTPRIO" -> RLIMIT_RTPRIO
            "RLIMIT_RTTIME" -> RLIMIT_RTTIME
            "RLIMIT_SIGPENDING" -> RLIMIT_SIGPENDING
            "RLIMIT_STACK" -> RLIMIT_STACK
            else -> {
                Logger.warn("unknown rlimit type: $type")
                null
            }
        }

    /**
     * Read the open file descriptors of this process by listing /proc/self/fd.
     * Used as a fallback when close_range(2) is unavailable.
     */
    private fun getOpenFds(): List<Int> {
        val procFdPath = "/proc/self/fd"
        val fds = mutableListOf<Int>()

        val dir = opendir(procFdPath)
        if (dir == null) {
            Logger.warn("failed to open $procFdPath for FD enumeration")
            return emptyList()
        }

        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()

                if (name == "." || name == "..") {
                    continue
                }

                name.toIntOrNull()?.let { fd ->
                    fds.add(fd)
                }
            }
        } finally {
            closedir(dir)
        }

        return fds
    }

    /**
     * Emulate close_range by setting FD_CLOEXEC on all open FDs.
     * Fallback for kernels that don't support close_range(2) or CLOSE_RANGE_CLOEXEC.
     */
    private fun emulateCloseRange(preserveFds: Int) {
        val minFd = 3 + preserveFds

        Logger.debug("emulating close_range by setting CLOEXEC on FDs >= $minFd")

        val openFds = getOpenFds()
        if (openFds.isEmpty()) {
            Logger.error("failed to enumerate open FDs, cannot emulate close_range")
            throw Exception("Failed to enumerate open FDs for close_range emulation (CVE-2024-21626 mitigation)")
        }

        val fdsToClose = openFds.filter { it >= minFd }

        for (fd in fdsToClose) {
            val currentFlags = fcntl(fd, F_GETFD)
            if (currentFlags == -1) {
                // FD might have been closed already (race condition), ignore
                continue
            }

            // Intentionally ignore errors here -- failures here typically mean the
            // FD was already closed by another thread (race condition).
            fcntl(fd, F_SETFD, currentFlags or FD_CLOEXEC)
        }

        Logger.debug("emulated close_range: set CLOEXEC on ${fdsToClose.size} FDs")
    }
}
