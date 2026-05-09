package utils

/**
 * In-memory [FileSystem] for unit tests.
 *
 * Files live in [files] keyed by absolute path. Directories live in [directories].
 * Reads/writes go through the in-memory map. /proc files (read by [readProcFile])
 * resolve from the same map; tests can preseed them.
 *
 * Throws when reading a missing file (mirroring the real implementation), but
 * does not enforce parent-directory existence on write (tests rarely care).
 */
class FakeFileSystem : FileSystem {
    val files: MutableMap<String, String> = mutableMapOf()
    val directories: MutableSet<String> = mutableSetOf()

    /** Every successful operation, in order, as a string. Useful for asserting call order. */
    val calls: MutableList<String> = mutableListOf()

    override fun writeTextFile(
        path: String,
        content: String,
    ) {
        files[path] = content
        calls += "writeTextFile($path, $content)"
    }

    override fun readTextFile(path: String): String {
        calls += "readTextFile($path)"
        return files[path] ?: throw Exception("Failed to open $path for reading: errno=2")
    }

    override fun readProcFile(path: String): String {
        calls += "readProcFile($path)"
        return files[path] ?: throw Exception("Failed to open $path for reading: errno=2")
    }

    override fun createDirectories(
        path: String,
        mode: UInt,
    ) {
        // Mirror mkdir -p: register this path and every ancestor segment.
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        val prefix = if (path.startsWith("/")) "/" else ""
        for (i in segments.indices) {
            directories += prefix + segments.subList(0, i + 1).joinToString("/")
        }
        calls += "createDirectories($path, mode=$mode)"
    }

    override fun fileExists(path: String): Boolean {
        calls += "fileExists($path)"
        return path in files || path in directories
    }

    override fun removeDirectory(path: String): Boolean {
        calls += "removeDirectory($path)"
        return directories.remove(path)
    }
}
