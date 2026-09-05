package utils

/**
 * Abstraction over the file operations the runtime invokes.
 *
 * Domain code should call methods on a [FileSystem] instance rather than
 * invoking platform.posix file operations directly. Tests inject a fake
 * implementation that operates on an in-memory map.
 *
 * [RealFileSystem] is the production implementation backed by libc's stdio.
 */
interface FileSystem {
    /**
     * Write [content] to [path], overwriting any existing content.
     * @throws Exception if the write fails for any reason
     */
    fun writeTextFile(
        path: String,
        content: String,
    )

    /**
     * Read the entire contents of [path] as a String.
     * @throws Exception if the read fails for any reason
     */
    fun readTextFile(path: String): String

    /**
     * Read a /proc file. /proc files cannot be seeked and report size as 0,
     * so the read uses a fixed-size buffer until EOF.
     * @throws Exception if the read fails
     */
    fun readProcFile(path: String): String

    /**
     * Create [path] and any missing parent directories (mkdir -p).
     * Does not fail if the directory already exists.
     * @throws Exception if directory creation fails for reasons other than EEXIST
     */
    fun createDirectories(
        path: String,
        mode: UInt = 0x1EDu, // 0o755
    )

    /**
     * Returns true if [path] exists and can be opened.
     */
    fun fileExists(path: String): Boolean

    /**
     * Atomically rename [oldPath] to [newPath], replacing any existing file.
     * Both paths must be on the same filesystem (rename(2) semantics).
     * @throws Exception if the rename fails
     */
    fun renameFile(
        oldPath: String,
        newPath: String,
    )

    /**
     * Remove an empty directory. Returns true if removed, false if it did not exist.
     * Other errors (e.g. directory not empty, permission denied) are logged as a
     * warning by the implementation but not thrown.
     */
    fun removeDirectory(path: String): Boolean

    /**
     * List names of direct child directories under [path], excluding `.` and `..`.
     * Returns an empty list if [path] does not exist or is not a directory.
     */
    fun listDirectories(path: String): List<String>

    /**
     * Remove [path] and everything below it without spawning a shell (rm -rf semantics).
     * Symbolic links are removed, never followed. A missing [path] is not an error.
     * @return true if something was removed, false if [path] did not exist
     * @throws Exception if any entry cannot be removed
     */
    fun removeDirectoryRecursively(path: String): Boolean
}
