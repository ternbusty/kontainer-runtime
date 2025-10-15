package utils

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Simple file writing utility
 */
@OptIn(ExperimentalForeignApi::class)
fun writeText(path: String, content: String): Boolean {
    val fp = fopen(path, "w")
    if (fp == null) return false
    memScoped {
        val cs = content.cstr
        if (fwrite(cs.ptr, 1.convert(), content.length.convert(), fp) < content.length.convert()) {
            fclose(fp)
            return false
        }
    }
    return fclose(fp) == 0
}
