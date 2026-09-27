package utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.geteuid

/**
 * True when the runtime runs without root privileges, i.e. it can only
 * create rootless containers. Mirrors runc's `os.Geteuid() != 0` check
 * (runc additionally treats euid 0 inside a user namespace as rootless for
 * cgroups; we do not special-case that yet).
 */
fun isRootless(): Boolean = geteuid() != 0u

/**
 * Default state directory for `--root`.
 *
 * Like runc, a rootless runtime uses `$XDG_RUNTIME_DIR/kontainer` (a
 * per-user tmpfs the user can write to) when the variable is set; otherwise,
 * and always for root, `/run/kontainer`.
 */
@OptIn(ExperimentalForeignApi::class)
fun defaultRootPath(): String {
    val xdgRuntimeDir = getenv("XDG_RUNTIME_DIR")?.toKString()
    return if (isRootless() && !xdgRuntimeDir.isNullOrEmpty()) {
        "$xdgRuntimeDir/kontainer"
    } else {
        "/run/kontainer"
    }
}
