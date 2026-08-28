package command

import cgroup.Cgroup
import config.loadKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import state.loadState
import utils.FileSystem
import utils.JsonCodec

/**
 * Ps command - List processes running in a container
 *
 * Reads the cgroup.procs file to get PIDs and runs the host's `ps` command
 * filtered to those PIDs.  With `--format json` outputs a JSON PID array;
 * otherwise passes any extra arguments through to `ps` (defaulting to
 * `ps -ef` when none are given).  This matches runc's behaviour.
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param format Output format ("json" forces JSON array; anything else is table)
 * @param psArgs Extra arguments forwarded to the host `ps` command
 */
@OptIn(ExperimentalForeignApi::class)
fun ps(
    fs: FileSystem,
    cgroup: Cgroup,
    rootPath: String,
    containerId: String,
    format: String = "table",
    psArgs: List<String> = emptyList(),
) {
    Logger.info("listing processes for container: $containerId (format: $format)")

    // Load container state to verify it exists
    val state =
        try {
            loadState(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("failed to load container state: ${e.message ?: "unknown"}")
            Logger.error("container may not exist")
            exit(1)
            return
        }

    Logger.debug("container status: ${state.status.value}")

    // Load kontainer config to get cgroup path
    val config =
        try {
            loadKontainerConfig(fs, rootPath, containerId)
        } catch (e: Exception) {
            Logger.error("failed to load kontainer config: ${e.message ?: "unknown"}")
            exit(1)
            return
        }

    val cgroupPath = config.cgroupPath
    if (cgroupPath == null) {
        Logger.error("no cgroup path found in container config")
        Logger.error("container may have been created without cgroup support")
        exit(1)
        return
    }

    Logger.debug("cgroup path: $cgroupPath")

    // Get PIDs from cgroup
    val pids =
        try {
            cgroup.getPids(cgroupPath)
        } catch (e: Exception) {
            Logger.error("failed to get container PIDs: ${e.message ?: "unknown"}")
            exit(1)
            return
        }

    if (pids.isEmpty()) {
        Logger.warn("no processes found in container")
    }

    // Output according to format
    if (format == "json") {
        outputJson(pids)
    } else {
        outputTable(pids, psArgs)
    }
}

/**
 * Output PIDs in JSON format (runc/youki compatible)
 *
 * Outputs a simple JSON array of PIDs: [123, 456, 789]
 */
private fun outputJson(pids: List<Int>) {
    val jsonString = JsonCodec.encode(pids)
    println(jsonString)
    Logger.debug("output JSON: $jsonString")
}

/**
 * Output process information in table format using the host's `ps` command.
 *
 * Runs `ps <psArgs>` (defaulting to `ps -ef`) and filters the output to
 * show only processes whose PIDs belong to the container.  When no extra
 * args are given runc uses the SysV-style `ps -ef`; when additional args
 * are present (e.g. `-e -x`) they are forwarded verbatim, which may
 * produce BSD-style output where the PID column is in a different position.
 */
@OptIn(ExperimentalForeignApi::class)
private fun outputTable(
    pids: List<Int>,
    psArgs: List<String>,
) {
    if (pids.isEmpty()) {
        println("No processes found")
        return
    }

    // Determine the full argv for ps.
    // runc default is `ps -ef`; when the caller supplies extra args those
    // replace `-ef`.
    val args: List<String> = if (psArgs.isEmpty()) listOf("ps", "-ef") else listOf("ps") + psArgs

    Logger.debug("executing '${args.joinToString(" ")}' to get process information")

    memScoped {
        // Create pipe for reading ps output
        val pipeFds = allocArray<IntVar>(2)
        if (pipe(pipeFds) != 0) {
            perror("pipe")
            Logger.error("failed to create pipe for ps command")
            exit(1)
        }

        val readFd = pipeFds[0]
        val writeFd = pipeFds[1]

        // Fork to execute ps command
        when (val pid = fork()) {
            -1 -> {
                perror("fork")
                Logger.error("failed to fork for ps command")
                close(readFd)
                close(writeFd)
                exit(1)
            }

            0 -> {
                // Child process: execute ps with the determined args.
                close(readFd)

                // Redirect stdout to pipe
                if (dup2(writeFd, STDOUT_FILENO) == -1) {
                    perror("dup2")
                    _exit(1)
                }
                close(writeFd)

                val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
                args.forEachIndexed { i, a -> argv[i] = a.cstr.ptr }
                argv[args.size] = null

                execvp("ps", argv)

                // If we reach here, execvp failed
                perror("execvp")
                _exit(1)
            }

            else -> {
                // Parent process: read ps output and filter
                close(writeFd)

                // Read all output from ps (loop until EOF).
                // ps -e -x on a busy host can exceed 64KB easily, so
                // use a 512KB buffer.
                val bufSize = 524288
                val buffer = allocArray<ByteVar>(bufSize + 1)
                var totalRead = 0
                while (totalRead < bufSize) {
                    val n = read(readFd, buffer + totalRead, (bufSize - totalRead).toULong())
                    if (n <= 0) break
                    totalRead += n.toInt()
                }
                close(readFd)

                if (totalRead == 0) {
                    Logger.error("no output from ps command")
                    exit(1)
                }

                buffer[totalRead] = 0 // Null terminate
                val psOutput = buffer.toKString()

                // Wait for child process
                val statusVar = alloc<IntVar>()
                waitpid(pid, statusVar.ptr, 0)

                // Parse and filter ps output
                filterPsOutput(psOutput, pids)
            }
        }
    }
}

/**
 * Filter ps output to show only processes with matching PIDs
 *
 * @param psOutput Full output from `ps`
 * @param pids List of PIDs to include
 */
private fun filterPsOutput(
    psOutput: String,
    pids: List<Int>,
) {
    val lines = psOutput.split('\n')

    if (lines.isEmpty()) {
        println("No output from ps command")
        return
    }

    // Print header (first line)
    val header = lines[0]
    println(header)

    // Find PID column index
    val pidIndex = findPidColumn(header)
    if (pidIndex == -1) {
        Logger.warn("could not find PID column in ps output")
        // Print all lines as fallback
        lines.drop(1).forEach { println(it) }
        return
    }

    Logger.debug("PID column index: $pidIndex")

    // Filter and print matching lines
    var matchCount = 0
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue

        val fields = line.trim().split(Regex("\\s+"))
        if (pidIndex < fields.size) {
            val pid = fields[pidIndex].toIntOrNull()
            if (pid != null && pids.contains(pid)) {
                println(line)
                matchCount++
            }
        }
    }

    Logger.debug("filtered ps output: $matchCount matching processes")
}

/**
 * Find the column index of the PID field in ps output header
 *
 * @param header Header line from ps output (e.g., "UID PID PPID C STIME TTY TIME CMD")
 * @return Column index of PID field, or -1 if not found
 */
private fun findPidColumn(header: String): Int {
    val fields = header.trim().split(Regex("\\s+"))
    return fields.indexOfFirst { it == "PID" }
}
