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
            Logger.error("failed to open $path for writing (errno=$errNum)")
            throw Exception("Failed to open $path for writing: errno=$errNum")
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
                    Logger.error("write error detected for $path (errno=$errNum)")
                    throw Exception("Write error for $path: errno=$errNum")
                }

                if (fflush(fp) != 0) {
                    val errNum = errno
                    Logger.error("failed to flush $path (errno=$errNum)")
                    throw Exception("Failed to flush $path: errno=$errNum")
                }
            }

            if (fclose(fp) != 0) {
                val errNum = errno
                Logger.error("failed to close $path (errno=$errNum)")
                throw Exception("Failed to close $path: errno=$errNum")
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
            if (fseek(fp, 0, SEEK_END) != 0) {
                val errNum = errno
                Logger.error("failed to seek to end of $path (errno=$errNum)")
                throw Exception("Failed to seek in $path: errno=$errNum")
            }

            val fileSize = ftell(fp)
            if (fileSize == -1L) {
                val errNum = errno
                Logger.error("failed to get size of $path (errno=$errNum)")
                throw Exception("Failed to get file size of $path: errno=$errNum")
            }

            if (fseek(fp, 0, SEEK_SET) != 0) {
                val errNum = errno
                Logger.error("failed to seek to start of $path (errno=$errNum)")
                throw Exception("Failed to seek in $path: errno=$errNum")
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
}
