package command

import cgroup.getCgroupPids
import config.loadKontainerConfig
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import state.loadState
import utils.JsonCodec

/**
 * Ps command - List processes running in a container
 *
 * It reads the cgroup.procs file to get PIDs and outputs them in JSON or table format.
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @param format Output format ("json" or "table", default: "json")
 */
@OptIn(ExperimentalForeignApi::class)
fun ps(
    rootPath: String,
    containerId: String,
    format: String = "json",
) {
    Logger.info("listing processes for container: $containerId (format: $format)")

    // Validate format
    if (format != "json" && format != "table") {
        Logger.error("invalid format: $format (must be 'json' or 'table')")
        exit(1)
    }

    // Load container state to verify it exists
    val state =
        try {
            loadState(rootPath, containerId)
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
            loadKontainerConfig(rootPath, containerId)
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
            getCgroupPids(cgroupPath)
        } catch (e: Exception) {
            Logger.error("failed to get container PIDs: ${e.message ?: "unknown"}")
            exit(1)
            return
        }

    if (pids.isEmpty()) {
        Logger.warn("no processes found in container")
    }

    // Output according to format
    when (format) {
        "json" -> outputJson(pids)
        "table" -> outputTable(pids)
    }
}

/**
 * Output PIDs in JSON format (runc/youki compatible)
 *
 * Outputs a simple JSON array of PIDs: [123, 456, 789]
 */
private fun outputJson(pids: List<Int>) {
    val jsonString = JsonCodec.Compact.encode(pids)
    println(jsonString)
    Logger.debug("output JSON: $jsonString")
}

/**
 * Output PIDs in table format
 *
 * Executes 'ps -ef' and filters output to show only processes with matching PIDs.
 * This provides a human-readable table with process information.
 */
@OptIn(ExperimentalForeignApi::class)
private fun outputTable(pids: List<Int>) {
    if (pids.isEmpty()) {
        println("No processes found")
        return
    }

    // Execute 'ps -ef' to get all processes
    Logger.debug("executing 'ps -ef' to get process information")

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
                // Child process: execute ps -ef
                close(readFd)

                // Redirect stdout to pipe
                if (dup2(writeFd, STDOUT_FILENO) == -1) {
                    perror("dup2")
                    _exit(1)
                }
                close(writeFd)

                // Execute ps -ef
                val argv = allocArray<CPointerVar<ByteVar>>(3)
                argv[0] = "ps".cstr.ptr
                argv[1] = "-ef".cstr.ptr
                argv[2] = null

                execvp("ps", argv)

                // If we reach here, execvp failed
                perror("execvp")
                _exit(1)
            }

            else -> {
                // Parent process: read ps output and filter
                close(writeFd)

                // Read all output from ps
                val buffer = allocArray<ByteVar>(65536) // 64KB buffer
                val bytesRead = read(readFd, buffer, 65535u)
                close(readFd)

                if (bytesRead < 0) {
                    perror("read")
                    Logger.error("failed to read ps output")
                    exit(1)
                }

                buffer[bytesRead.toInt()] = 0 // Null terminate
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
 * @param psOutput Full output from 'ps -ef'
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
