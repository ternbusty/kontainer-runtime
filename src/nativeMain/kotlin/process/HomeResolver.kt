package process

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Resolve a user's home directory from `/etc/passwd`.
 *
 * At the point this is called, the process is already inside the container's
 * mount namespace (post-`pivotRoot` for init, post-`setns(CLONE_NEWNS)` for
 * exec), so `/etc/passwd` refers to the container's file.
 *
 * If no matching UID is found, or `/etc/passwd` cannot be read, returns `"/"`.
 * This matches runc's behaviour in `libcontainer/init_linux.go:setupUser()`.
 */
@OptIn(ExperimentalForeignApi::class)
fun resolveHomeDir(uid: UInt): String {
    val fp = fopen("/etc/passwd", "r") ?: return "/"
    try {
        val buf = ByteArray(4096)
        buf.usePinned { pinned ->
            while (fgets(pinned.addressOf(0), buf.size, fp) != null) {
                val line = pinned.addressOf(0).toKString().trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                // Format: name:password:uid:gid:gecos:home:shell
                val fields = line.split(":")
                if (fields.size >= 6) {
                    val passwdUid = fields[2].toUIntOrNull()
                    if (passwdUid == uid) {
                        val home = fields[5]
                        Logger.debug("resolved HOME for uid=$uid: $home")
                        return home
                    }
                }
            }
        }
    } finally {
        fclose(fp)
    }
    Logger.debug("no /etc/passwd entry for uid=$uid, defaulting HOME=/")
    return "/"
}

/**
 * Ensure the environment list contains a non-empty HOME variable.
 *
 * runc behaviour (env.go prepareEnv): if HOME is empty or absent after dedup,
 * look up the user's home in /etc/passwd and set it. Non-empty HOME is kept
 * as-is.
 */
fun ensureHomeEnv(
    env: MutableList<String>,
    uid: UInt,
) {
    val idx = env.indexOfLast { it.startsWith("HOME=") }
    if (idx >= 0) {
        val value = env[idx].substringAfter("HOME=")
        if (value.isNotEmpty()) return
        // HOME="" — override with resolved home
        val home = resolveHomeDir(uid)
        env[idx] = "HOME=$home"
    } else {
        val home = resolveHomeDir(uid)
        env.add("HOME=$home")
    }
}
