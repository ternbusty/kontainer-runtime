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
fun writeTextFile(
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

/**
 * Read entire text file contents
 *
 * This function opens a file, determines its size, reads the entire contents
 * into memory, and returns it as a String.
 *
 * @param path Path to file to read
 * @return File contents as String
 * @throws Exception if read fails for any reason
 */
@OptIn(ExperimentalForeignApi::class)
fun readTextFile(path: String): String {
    val fp = fopen(path, "r")
    if (fp == null) {
        val errNum = errno
        Logger.error("failed to open $path for reading (errno=$errNum)")
        throw Exception("Failed to open $path for reading: errno=$errNum")
    }

    try {
        // Get file size using fseek/ftell
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

        // Read entire file
        val content =
            memScoped {
                val buffer = allocArray<ByteVar>(fileSize.toInt() + 1)
                val bytesRead = fread(buffer, 1u, fileSize.toULong(), fp)

                if (bytesRead.toLong() != fileSize) {
                    val errNum = errno
                    Logger.error("partial read from $path: read $bytesRead of $fileSize bytes (errno=$errNum)")
                    throw Exception("Partial read from $path: read $bytesRead of $fileSize bytes")
                }

                // Null terminate and convert to String
                buffer[fileSize.toInt()] = 0
                buffer.toKString()
            }

        // Close file
        if (fclose(fp) != 0) {
            val errNum = errno
            Logger.error("failed to close $path (errno=$errNum)")
            throw Exception("Failed to close $path: errno=$errNum")
        }

        Logger.debug("successfully read $path ($fileSize bytes)")
        return content
    } catch (e: Exception) {
        // Attempt to close on exception
        fclose(fp)
        throw e
    }
}

/**
 * Read /proc file contents
 *
 * /proc files cannot be seeked and report size as 0, so we read them
 * using a fixed-size buffer until EOF.
 *
 * @param path Path to /proc file to read
 * @return File contents as String
 * @throws Exception if read fails
 */
@OptIn(ExperimentalForeignApi::class)
fun readProcFile(path: String): String {
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

            // Null terminate and convert to String
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

/**
 * Create directories recursively
 *
 * Creates all parent directories as needed, similar to `mkdir -p`.
 * Does not fail if directories already exist.
 *
 * @param path Directory path to create
 * @param mode Permissions mode (default: 0755)
 * @throws Exception if directory creation fails (except for EEXIST)
 */
@OptIn(ExperimentalForeignApi::class)
fun createDirectories(
    path: String,
    mode: UInt = 0x1EDu,
) { // 0x1ED = 0755 octal
    Logger.debug("creating directories: $path")

    // Split path into components
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
            // EEXIST is OK - directory already exists
            Logger.debug("directory already exists: $currentPath")
        } else {
            Logger.debug("created directory: $currentPath")
        }
    }
}

/**
 * Check if a file or directory exists
 *
 * @param path Path to check
 * @return true if file/directory exists, false otherwise
 */
@OptIn(ExperimentalForeignApi::class)
fun fileExists(path: String): Boolean {
    val fp = fopen(path, "r")
    if (fp != null) {
        fclose(fp)
        Logger.debug("file exists: $path")
        return true
    }

    Logger.debug("file does not exist: $path")
    return false
}
