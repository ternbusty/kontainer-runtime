@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package exeseal

import kotlinx.cinterop.*
import logger.Logger
import platform.linux._F_ADD_SEALS
import platform.linux._F_SEAL_GROW
import platform.linux._F_SEAL_SEAL
import platform.linux._F_SEAL_SHRINK
import platform.linux._F_SEAL_WRITE
import platform.linux._MFD_ALLOW_SEALING
import platform.linux._MFD_CLOEXEC
import platform.linux._O_TMPFILE
import platform.linux._copy_fd
import platform.linux._memfd_create
import platform.linux._try_sealed_overlayfs
import platform.posix.*

/**
 * CVE-2019-5736 mitigation — "cloned binary" approach.
 *
 * Before the runtime enters any container namespace (via `exec` → setns or
 * `create` → bootstrap clone), it must ensure that `/proc/self/exe` does NOT
 * point to the on-disk binary.  A malicious container process could otherwise
 * overwrite the host binary through that symlink.
 *
 * Fallback chain (matching runc):
 *   1. overlayfs  — zero-copy; creates a read-only overlay over the binary's
 *                   directory and opens the binary through it.  Requires
 *                   root and Linux 5.2+ (new mount API).
 *   2. memfd_create — copies the binary into a sealed memfd (Linux 3.17+)
 *   3. O_TMPFILE  — anonymous file on /tmp; no sealing but no on-disk name
 *   4. mkstemp+unlink — named temp file, immediately unlinked
 *
 * After obtaining a sealed fd, the process re-execs from it so
 * `/proc/self/exe` resolves to the sealed copy, not the on-disk binary.
 */

/** Environment variable set after a successful re-exec to avoid looping. */
private const val CLONED_ENV = "_KONTAINER_CLONED_BINARY"

/**
 * Sealed fd obtained by [sealBinary].  -1 if sealing was not attempted or
 * failed.  Used by Create.kt to exec the bootstrap child directly from the
 * sealed copy — eliminating the main-process re-exec that [ensureSelfCloned]
 * would otherwise perform.
 */
var sealedBinaryFd: Int = -1
    private set

/**
 * Seal the runtime binary and return the fd (>= 0) without re-execing.
 *
 * Call this for commands that fork+exec a child into container namespaces
 * (create, run).  The sealed fd is stored in [sealedBinaryFd] and the child
 * can exec from `/proc/self/fd/<fd>` so its `/proc/self/exe` points to the
 * sealed copy — protecting against CVE-2019-5736 in a single exec.
 *
 * Returns the sealed fd on success, -1 on failure (logs a warning).
 */
fun sealBinary(): Int {
    // Already sealed (e.g. outer layer or prior call).
    if (getenv(CLONED_ENV) != null || isMemfd()) {
        Logger.debug("exeseal: binary already sealed, sealBinary() no-op")
        return -1
    }

    val fd = cloneBinary()
    if (fd < 0) {
        Logger.warn("exeseal: failed to seal binary — CVE-2019-5736 mitigation inactive")
        return -1
    }

    sealedBinaryFd = fd
    Logger.debug("exeseal: sealed binary fd=$fd (no re-exec)")
    return fd
}

/**
 * Entry point for commands that need the main process itself to run from a
 * sealed copy (e.g. `exec`, where the forked child enters namespaces without
 * an intermediate exec).
 *
 * If the process is already running from a cloned binary (env marker set),
 * this is a no-op.  Otherwise it seals the binary and re-execs.
 * On success this function never returns (the process is replaced).
 * On failure it logs a warning and returns, allowing the runtime to proceed
 * unprotected — matching runc's behavior of not hard-failing when the
 * kernel is too old or no tmp space is available.
 */
fun ensureSelfCloned(args: Array<String>) {
    // Already cloned — nothing to do.
    if (getenv(CLONED_ENV) != null) {
        Logger.debug("exeseal: already running from cloned binary")
        return
    }

    // Check if /proc/self/exe already points to a memfd (e.g. if an outer
    // layer already performed the clone).
    if (isMemfd()) {
        Logger.debug("exeseal: /proc/self/exe is already a memfd")
        return
    }

    val clonedFd = cloneBinary()
    if (clonedFd < 0) {
        Logger.warn("exeseal: failed to clone binary — CVE-2019-5736 mitigation inactive")
        return
    }

    reexecFromFd(clonedFd, args)

    // reexecFromFd calls execv which replaces the process on success.
    // If we reach here, execv failed.
    close(clonedFd)
    Logger.warn("exeseal: re-exec from cloned fd failed — CVE-2019-5736 mitigation inactive")
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/** Returns true when /proc/self/exe already points to a memfd. */
private fun isMemfd(): Boolean =
    memScoped {
        val buf = allocArray<ByteVar>(PATH_MAX)
        val len = readlink("/proc/self/exe", buf, (PATH_MAX - 1).convert())
        if (len < 0) return false
        buf[len.toInt()] = 0
        val target = buf.toKString()
        target.startsWith("/memfd:") || target.contains("memfd:")
    }

/**
 * Obtain a sealed fd for the current binary.  Returns the fd (>= 0) on
 * success or -1 on failure.
 */
private fun cloneBinary(): Int {
    // Strategy 1: overlayfs (zero-copy, Linux 5.2+, requires root)
    val overlayFd = tryOverlayfs()
    if (overlayFd >= 0) return overlayFd

    // Strategy 2: memfd_create (copies binary, Linux 3.17+)
    val memfd = tryMemfd()
    if (memfd >= 0) return memfd

    // Strategy 3: O_TMPFILE (anonymous file, no name on disk)
    val tmpfd = tryOTmpfile()
    if (tmpfd >= 0) return tmpfd

    // Strategy 4: mkstemp + unlink
    return tryMkstemp()
}

/**
 * Try the overlayfs approach: create a read-only overlayfs over the
 * directory containing the binary, then open the binary through it.
 *
 * This is a zero-copy technique — no data is read or written.  The
 * overlayfs mount has two lowerdirs (the binary's directory and a dummy)
 * which puts it in "lower-only" mode where writes are completely blocked.
 * Unlike a bind-mount, overlayfs cannot be "unwrapped" by the container
 * to reach the underlying file.
 *
 * Requires: root, Linux 5.2+ (fsopen/fsconfig/fsmount), overlayfs support.
 * Returns fd >= 0 on success, -1 on failure.
 */
private fun tryOverlayfs(): Int {
    val fd = _try_sealed_overlayfs()
    if (fd >= 0) {
        Logger.debug("exeseal: sealed binary via overlayfs (fd=$fd)")
    } else {
        Logger.debug("exeseal: overlayfs failed: ${strerror(errno)?.toKString()}")
    }
    return fd
}

/**
 * Try cloning via memfd_create.  Returns sealed fd or -1.
 */
private fun tryMemfd(): Int {
    val flags = _MFD_CLOEXEC() or _MFD_ALLOW_SEALING()
    val fd = _memfd_create("kontainer_cloned", flags)
    if (fd < 0) {
        Logger.debug("exeseal: memfd_create failed: ${strerror(errno)?.toKString()}")
        return -1
    }

    if (!copyExeInto(fd)) {
        close(fd)
        return -1
    }

    // Seal: no further writes, grows, shrinks, or seal changes.
    val seals = _F_SEAL_SEAL() or _F_SEAL_SHRINK() or _F_SEAL_GROW() or _F_SEAL_WRITE()
    if (fcntl(fd, _F_ADD_SEALS(), seals) < 0) {
        Logger.debug("exeseal: F_ADD_SEALS failed: ${strerror(errno)?.toKString()}")
        close(fd)
        return -1
    }

    Logger.debug("exeseal: cloned binary into memfd (fd=$fd)")
    return fd
}

/**
 * Try cloning via O_TMPFILE (Linux 3.11+, needs a filesystem that supports it).
 * Returns fd or -1.
 */
private fun tryOTmpfile(): Int {
    val fd = open("/tmp", _O_TMPFILE() or O_RDWR or O_EXCL or O_CLOEXEC, S_IRWXU.toUInt())
    if (fd < 0) {
        Logger.debug("exeseal: O_TMPFILE failed: ${strerror(errno)?.toKString()}")
        return -1
    }

    if (!copyExeInto(fd)) {
        close(fd)
        return -1
    }

    Logger.debug("exeseal: cloned binary via O_TMPFILE (fd=$fd)")
    return fd
}

/**
 * Last resort: mkstemp + immediate unlink.  The file has no directory
 * entry after unlink but stays alive while the fd is open.  Returns fd
 * or -1.
 */
private fun tryMkstemp(): Int =
    memScoped {
        // mkstemp modifies the template in place — allocate a mutable C buffer.
        val tmpl = allocArray<ByteVar>(32)
        val src = "/tmp/kontainer-XXXXXX"
        src.forEachIndexed { i, c -> tmpl[i] = c.code.toByte() }
        tmpl[src.length] = 0

        val fd = mkstemp(tmpl)
        if (fd < 0) {
            Logger.debug("exeseal: mkstemp failed: ${strerror(errno)?.toKString()}")
            return -1
        }
        // Immediately unlink so there's no on-disk name to race.
        unlink(tmpl.toKString())

        if (!copyExeInto(fd)) {
            close(fd)
            return -1
        }

        // Make executable.
        fchmod(fd, S_IRWXU.toUInt())

        Logger.debug("exeseal: cloned binary via mkstemp+unlink (fd=$fd)")
        return fd
    }

/**
 * Copy /proc/self/exe contents into [dstFd].  Returns true on success.
 */
private fun copyExeInto(dstFd: Int): Boolean {
    val srcFd = open("/proc/self/exe", O_RDONLY)
    if (srcFd < 0) {
        Logger.debug("exeseal: open /proc/self/exe failed: ${strerror(errno)?.toKString()}")
        return false
    }
    val rc = _copy_fd(dstFd, srcFd)
    close(srcFd)
    if (rc < 0) {
        Logger.debug("exeseal: copy failed: ${strerror(errno)?.toKString()}")
        return false
    }
    // Seek back to start — not strictly required for exec but tidy.
    lseek(dstFd, 0, SEEK_SET)
    return true
}

/**
 * Re-exec the current process from `/proc/self/fd/<fd>`.
 * Sets the [CLONED_ENV] marker so the new process skips the clone.
 * On success this function does not return.
 */
private fun reexecFromFd(
    fd: Int,
    args: Array<String>,
) = memScoped {
    // Build the /proc/self/fd/<N> path.
    val fdPath = "/proc/self/fd/$fd"

    // Set marker to prevent infinite re-exec loop.
    setenv(CLONED_ENV, "1", 1)

    // Build argv: argv[0] = fdPath, argv[1..] = original args.
    // We use the fd path as argv[0] so /proc/self/exe of the new
    // process points to the memfd.
    val argv = allocArray<CPointerVar<ByteVar>>(args.size + 2)
    argv[0] = fdPath.cstr.ptr
    for (i in args.indices) {
        argv[i + 1] = args[i].cstr.ptr
    }
    argv[args.size + 1] = null

    Logger.debug("exeseal: re-execing from $fdPath")
    execv(fdPath, argv)

    // If we get here, execv failed.
    Logger.debug("exeseal: execv($fdPath) failed: ${strerror(errno)?.toKString()}")
}
