package utils

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Write text to a file with proper error checking
 *
 * This function is critical for writing UID/GID mappings and other
 * container configuration. Proper error detection is essential.
 *
 * @param path Path to file to write
 * @param content Text content to write
 * @throws Exception if write fails for any reason
 */
@OptIn(ExperimentalForeignApi::class)
fun writeText(path: String, content: String) {
    val fp = fopen(path, "w")
    if (fp == null) {
        val errNum = errno
        Logger.error("failed to open $path for writing (errno=$errNum)")
        throw Exception("Failed to open $path for writing: errno=$errNum")
    }

    try {
        memScoped {
            val cs = content.cstr
            val bytesToWrite = content.length.convert<size_t>()

            // Write content
            val bytesWritten = fwrite(cs.ptr, 1.convert(), bytesToWrite, fp)

            // Check for partial write
            if (bytesWritten < bytesToWrite) {
                Logger.error("partial write to $path: wrote $bytesWritten of $bytesToWrite bytes")
                throw Exception("Partial write to $path: wrote $bytesWritten of $bytesToWrite bytes")
            }

            // Check for write errors using ferror
            if (ferror(fp) != 0) {
                val errNum = errno
                Logger.error("write error detected for $path (errno=$errNum)")
                throw Exception("Write error for $path: errno=$errNum")
            }

            // Flush to ensure data is written
            if (fflush(fp) != 0) {
                val errNum = errno
                Logger.error("failed to flush $path (errno=$errNum)")
                throw Exception("Failed to flush $path: errno=$errNum")
            }
        }

        // Close and check for errors
        if (fclose(fp) != 0) {
            val errNum = errno
            Logger.error("failed to close $path (errno=$errNum)")
            throw Exception("Failed to close $path: errno=$errNum")
        }

        Logger.debug("successfully wrote $path")
    } catch (e: Exception) {
        // Attempt to close on exception
        fclose(fp)
        // Re-throw the exception
        throw e
    }
}
