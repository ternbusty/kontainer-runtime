package syscall

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Set additional groups (supplementary groups) for the current process
 *
 * This function checks /proc/self/setgroups to determine if setgroups is allowed
 * (it may be "deny" in unprivileged user namespace since Linux 3.19).
 *
 * Following runc's design, this is called before setgid/setuid.
 *
 * @param gids List of additional group IDs
 */
@OptIn(ExperimentalForeignApi::class)
fun setAdditionalGroups(gids: List<UInt>) {
    if (gids.isEmpty()) {
        Logger.debug("no additional groups to set")
        return
    }

    // Check if setgroups is allowed
    // /proc/self/setgroups may contain "deny" in unprivileged user namespace
    val setgroupsPath = "/proc/self/setgroups"
    memScoped {
        val fp = fopen(setgroupsPath, "r")
        if (fp == null) {
            // File doesn't exist, assume setgroups is allowed
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

    // Convert UInt list to gid_t array
    memScoped {
        val gidArray = allocArray<gid_tVar>(gids.size)
        gids.forEachIndexed { i, gid ->
            gidArray[i] = gid
        }

        if (setgroups(gids.size.toULong(), gidArray) != 0) {
            val errorMsg = strerror(errno)?.toKString() ?: "unknown error"
            Logger.warn("failed to set additional groups: $errorMsg (errno=$errno)")
            // Don't throw exception, just log warning like runc does
            // This can fail in unprivileged user namespace
        } else {
            Logger.debug("set ${gids.size} additional groups successfully")
        }
    }
}
