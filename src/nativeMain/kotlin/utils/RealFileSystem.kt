package utils

import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*

/**
 * Production [FileSystem] implementation backed by libc stdio (fopen/fread/fwrite/...).
 */
@OptIn(ExperimentalForeignApi::class)
class RealFileSystem : FileSystem {
    override fun writeTextFile(
        path: String,
        content: String,
    ) {
        val fp = fopen(path, "w")
        if (fp == null) {
            val errNum = errno
            val errMsg = strerror(errNum)?.toKString()?.lowercase() ?: "unknown error"
            Logger.error("failed to open $path for writing ($errMsg)")
            throw Exception("Failed to open $path for writing: $errMsg")
        }

        try {
            memScoped {
                val cs = content.cstr
                val bytesToWrite = content.length.convert<size_t>()

                val bytesWritten = fwrite(cs.ptr, 1.convert(), bytesToWrite, fp)

                if (bytesWritten < bytesToWrite) {
                    Logger.error("partial write to $path: wrote $bytesWritten of $bytesToWrite bytes")
                    throw Exception("Partial write to $path: wrote $bytesWritten of $bytesToWrite bytes")
                }

                if (ferror(fp) != 0) {
                    val errNum = errno
                    val errMsg = strerror(errNum)?.toKString()?.lowercase() ?: "unknown error"
                    Logger.error("write error detected for $path ($errMsg)")
                    throw Exception("Write error for $path: $errMsg")
                }

                if (fflush(fp) != 0) {
                    val errNum = errno
                    val errMsg = strerror(errNum)?.toKString()?.lowercase() ?: "unknown error"
                    Logger.error("failed to flush $path ($errMsg)")
                    throw Exception("Failed to flush $path: $errMsg")
                }
            }

            if (fclose(fp) != 0) {
                val errNum = errno
                val errMsg = strerror(errNum)?.toKString()?.lowercase() ?: "unknown error"
                Logger.error("failed to close $path ($errMsg)")
                throw Exception("Failed to close $path: $errMsg")
            }

            Logger.debug("successfully wrote $path")
        } catch (e: Exception) {
            fclose(fp)
            throw e
        }
    }

    override fun readTextFile(path: String): String {
        val fp = fopen(path, "r")
        if (fp == null) {
            val errNum = errno
            Logger.error("failed to open $path for reading (errno=$errNum)")
            throw Exception("Failed to open $path for reading: errno=$errNum")
        }

        try {
            // Try seek-based reading first (works for regular files).
            // If seek fails (ESPIPE — e.g. pipe fds like /dev/fd/63),
            // fall back to streaming reads.
            if (fseek(fp, 0, SEEK_END) != 0) {
                // Non-seekable fd — read in chunks until EOF.
                return readStreamToString(fp, path)
            }

            val fileSize = ftell(fp)
            if (fileSize <= 0L) {
                // fileSize == -1: ftell failed
                // fileSize == 0:  either genuinely empty OR a pseudo-file
                //   (sysfs/cgroupfs/procfs report size 0 via stat/seek but
                //   still return content on read). Fall back to streaming
                //   read which handles both cases correctly.
                if (fseek(fp, 0, SEEK_SET) != 0) {
                    return readStreamToString(fp, path)
                }
                return readStreamToString(fp, path)
            }

            if (fseek(fp, 0, SEEK_SET) != 0) {
                return readStreamToString(fp, path)
            }

            val content =
                memScoped {
                    val buffer = allocArray<ByteVar>(fileSize.toInt() + 1)
                    val bytesRead = fread(buffer, 1u, fileSize.toULong(), fp)

                    if (bytesRead.toLong() != fileSize) {
                        val errNum = errno
                        Logger.error("partial read from $path: read $bytesRead of $fileSize bytes (errno=$errNum)")
                        throw Exception("Partial read from $path: read $bytesRead of $fileSize bytes")
                    }

                    buffer[fileSize.toInt()] = 0
                    buffer.toKString()
                }

            if (fclose(fp) != 0) {
                val errNum = errno
                Logger.error("failed to close $path (errno=$errNum)")
                throw Exception("Failed to close $path: errno=$errNum")
            }

            Logger.debug("successfully read $path ($fileSize bytes)")
            return content
        } catch (e: Exception) {
            fclose(fp)
            throw e
        }
    }

    /**
     * Read all content from a non-seekable FILE* (e.g. pipe fd) into a String.
     * Reads in chunks and closes the stream when done.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun readStreamToString(
        fp: CPointer<FILE>,
        path: String,
    ): String =
        memScoped {
            val chunkSize = 4096
            val buffer = allocArray<ByteVar>(chunkSize + 1)
            val parts = mutableListOf<String>()
            var totalBytes = 0L

            while (true) {
                val n = fread(buffer, 1u, chunkSize.toULong(), fp)
                if (n == 0UL) break
                buffer[n.toInt()] = 0
                parts.add(buffer.toKString())
                totalBytes += n.toLong()
            }

            fclose(fp)
            Logger.debug("successfully read $path ($totalBytes bytes, streaming)")
            parts.joinToString("")
        }

    override fun readProcFile(path: String): String {
        val fp = fopen(path, "r")
        if (fp == null) {
            val errNum = errno
            throw Exception("Failed to open $path for reading: errno=$errNum")
        }

        try {
            memScoped {
                val bufferSize = 4096
                val buffer = allocArray<ByteVar>(bufferSize)
                val bytesRead = fread(buffer, 1u, (bufferSize - 1).toULong(), fp)

                if (bytesRead == 0UL) {
                    fclose(fp)
                    return ""
                }

                buffer[bytesRead.toInt()] = 0
                val content = buffer.toKString()

                fclose(fp)
                return content
            }
        } catch (e: Exception) {
            fclose(fp)
            throw e
        }
    }

    override fun createDirectories(
        path: String,
        mode: UInt,
    ) {
        Logger.debug("creating directories: $path")

        val components = path.trim('/').split('/')
        var currentPath = if (path.startsWith("/")) "/" else ""

        for (component in components) {
            if (component.isEmpty()) continue

            currentPath += if (currentPath == "/") component else "/$component"

            if (mkdir(currentPath, mode) != 0) {
                val errNum = errno
                if (errNum != EEXIST) {
                    Logger.error("failed to create directory $currentPath (errno=$errNum)")
                    perror("mkdir($currentPath)")
                    throw Exception("Failed to create directory $currentPath: errno=$errNum")
                }
                Logger.debug("directory already exists: $currentPath")
            } else {
                Logger.debug("created directory: $currentPath")
            }
        }
    }

    override fun fileExists(path: String): Boolean {
        val fp = fopen(path, "r")
        if (fp != null) {
            fclose(fp)
            Logger.debug("file exists: $path")
            return true
        }

        Logger.debug("file does not exist: $path")
        return false
    }

    override fun renameFile(
        oldPath: String,
        newPath: String,
    ) {
        if (rename(oldPath, newPath) != 0) {
            val errNum = errno
            throw Exception("Failed to rename $oldPath to $newPath: errno=$errNum")
        }
    }

    override fun removeDirectory(path: String): Boolean {
        if (access(path, F_OK) != 0) {
            Logger.debug("directory $path does not exist, nothing to remove")
            return false
        }

        if (rmdir(path) != 0) {
            val errNum = errno
            // Best-effort: cleanup callers (cgroup, container dir) tolerate non-empty
            // directories or permission errors silently.
            Logger.warn("failed to remove directory $path: errno=$errNum")
            return false
        }

        Logger.debug("removed directory: $path")
        return true
    }

    override fun listDirectories(path: String): List<String> {
        val dir = opendir(path) ?: return emptyList()
        val result = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                // Only include entries that are directories (DT_DIR) or that
                // we cannot classify from the dirent (DT_UNKNOWN — some
                // filesystems don't fill d_type); the latter is rare in
                // practice (state lives on tmpfs/ext4, both DT_DIR-aware).
                val dtype = entry.pointed.d_type.toInt()
                if (dtype == DT_DIR.toInt() || dtype == DT_UNKNOWN.toInt()) {
                    result.add(name)
                }
            }
        } finally {
            closedir(dir)
        }
        return result
    }
}
