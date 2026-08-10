package command

import logger.Logger
import spec.loadSpec
import state.State
import state.loadState
import state.refreshStatus
import utils.FileSystem
import utils.JsonCodec

/**
 * List all containers managed by this runtime.
 *
 * Walks `{rootPath}/` for child directories, attempts to load each one's
 * `state.json`, refreshes the status from `/proc`, and prints a summary.
 *
 * Three output formats (matching runc):
 * - **table** (default): `ID  PID  STATUS  BUNDLE  CREATED`
 * - **json**: a JSON array of OCI state objects
 * - **quiet**: one container ID per line
 *
 * Containers whose state.json is missing or corrupt are silently skipped
 * (a warning is logged).
 */
fun list(
    fs: FileSystem,
    rootPath: String,
    format: String,
    quiet: Boolean,
) {
    print(formatContainerList(fs, rootPath, format, quiet))
}

/**
 * Build the formatted output for the list command.
 *
 * Separated from [list] so unit tests can assert on the returned string
 * without capturing stdout (Kotlin/Native has no System.setOut).
 */
internal fun formatContainerList(
    fs: FileSystem,
    rootPath: String,
    format: String,
    quiet: Boolean,
): String {
    val dirs = fs.listDirectories(rootPath)
    val states = mutableListOf<State>()

    for (dir in dirs) {
        try {
            val state = loadState(fs, rootPath, dir).refreshStatus()
            states.add(state)
        } catch (e: Exception) {
            Logger.warn("list: skipping $dir: ${e.message}")
        }
    }

    // Sort by container ID (runc uses alphabetical order).
    states.sortBy { it.id }

    return when {
        quiet -> states.joinToString("") { "${it.id}\n" }
        format == "json" -> {
            // Build list entries with rootfs field for runc compatibility
            val entries =
                states.map { s ->
                    val rootfs =
                        try {
                            loadSpec(fs, "${s.bundle}/config.json").root.path.let { p ->
                                if (p.startsWith("/")) p else "${s.bundle}/$p"
                            }
                        } catch (_: Exception) {
                            ""
                        }
                    ListEntry(s.ociVersion, s.id, s.pid, s.status, s.bundle, rootfs, s.created)
                }
            JsonCodec.encode(entries, prettyPrint = false) + "\n"
        }
        else -> formatTable(states)
    }
}

/**
 * Build a padded table with header: ID  PID  STATUS  BUNDLE  CREATED
 */
private fun formatTable(states: List<State>): String {
    val headers = listOf("ID", "PID", "STATUS", "BUNDLE", "CREATED")
    val rows =
        states.map { s ->
            listOf(
                s.id,
                s.pid?.toString() ?: "",
                s.status.value,
                s.bundle,
                s.created ?: "",
            )
        }

    // Column widths = max of header width and all row values
    val widths =
        headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOfOrNull { it[col].length } ?: 0,
            )
        }

    return buildString {
        appendLine(headers.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString("  "))
        for (row in rows) {
            appendLine(row.mapIndexed { i, v -> v.padEnd(widths[i]) }.joinToString("  "))
        }
    }
}

@kotlinx.serialization.Serializable
private data class ListEntry(
    val ociVersion: String,
    val id: String,
    val pid: Int?,
    @kotlinx.serialization.Serializable(with = state.ContainerStatusSerializer::class)
    val status: state.ContainerStatus,
    val bundle: String,
    val rootfs: String,
    val created: String?,
)
