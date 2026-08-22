@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import bootstrap.kontainer_is_init_process
import cgroup.CgroupV2
import channel.SocketInitReceiver
import channel.SocketMainSender
import channel.SocketNotifyListener
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import command.*
import config.BuildConfig
import exeseal.ensureSelfCloned
import exeseal.sealBinary
import kotlinx.cinterop.*
import logger.Logger
import platform.posix.*
import process.runInitProcess
import spec.loadSpec
import syscall.LinuxSyscall
import utils.RealFileSystem

/** Exec-option flags that consume the next argument as a value. */
private val EXEC_VALUE_FLAGS =
    setOf(
        "-p",
        "--process",
        "--pid-file",
        "--console-socket",
        "--pidfd-socket",
        "--cwd",
        "--user",
        "-u",
        "--additional-gids",
        "--preserve-fds",
        "--cgroup",
        "--env",
        "-e",
        "--cap",
    )

/** Exec-option flags that are boolean (no following value). */
private val EXEC_BOOL_FLAGS = setOf("-d", "--detach", "-t", "--tty")

/**
 * Exec-option flags that use `--flag=value` form (value attached with `=`).
 * These are recognized so `--preserve-fds=2` etc. are not mistaken for
 * positional arguments.
 */
private val EXEC_EQ_VALUE_PREFIXES =
    listOf(
        "--preserve-fds=",
        "--cwd=",
        "--user=",
        "--additional-gids=",
        "--cgroup=",
        "--env=",
        "--cap=",
        "--pid-file=",
        "--process=",
        "--console-socket=",
        "--pidfd-socket=",
    )

/**
 * Split trailing command arguments out of an `exec` invocation so that
 * the CLI parser never sees them.
 *
 * The OCI exec grammar is:
 *
 *     runtime [global-opts] exec [exec-opts] <container-id> [--] [cmd [arg…]]
 *
 * Everything after the container ID (the first positional arg inside the
 * exec subcommand) is the command to run inside the container.  A literal
 * `--` may precede the command to guard against arguments that look like
 * flags (e.g. `sh -c '…'`).
 *
 * Returns a new args array with the command args removed; the removed
 * args are appended to [commandArgsOut].
 */
internal fun preprocessExecArgs(
    args: Array<String>,
    commandArgsOut: MutableList<String>,
): Array<String> {
    val execIdx = args.indexOf("exec")
    if (execIdx < 0) return args

    // Everything up to and including "exec" passes through unchanged.
    val kept = args.slice(0..execIdx).toMutableList()

    var i = execIdx + 1
    var foundContainerId = false

    while (i < args.size) {
        if (foundContainerId) {
            commandArgsOut.add(args[i])
            i++
            continue
        }

        val a = args[i]

        if (a == "--") {
            // Explicit end-of-options; everything after is command args.
            foundContainerId = true
            i++
            continue
        }

        if (a in EXEC_VALUE_FLAGS) {
            kept.add(a)
            i++
            if (i < args.size) {
                kept.add(args[i])
                i++
            }
        } else if (a in EXEC_BOOL_FLAGS) {
            kept.add(a)
            i++
        } else if (EXEC_EQ_VALUE_PREFIXES.any { a.startsWith(it) }) {
            // --flag=value form (e.g. --preserve-fds=2)
            kept.add(a)
            i++
        } else if (a.startsWith("-")) {
            // Unknown flag (likely a global option like --debug placed
            // after the subcommand); pass through to the CLI parser.
            kept.add(a)
            i++
        } else {
            // First positional = container ID
            kept.add(a)
            foundContainerId = true
            i++
            // Skip an optional `--` separator between the container ID and
            // the command; everything after it is collected below.
            if (i < args.size && args[i] == "--") {
                i++
            }
        }
    }

    return kept.toTypedArray()
}

// ---------------------------------------------------------------------------
// Shared state passed from the root command to subcommands via clikt Context.
// ---------------------------------------------------------------------------

data class GlobalConfig(
    val rootPath: String,
    val syscall: LinuxSyscall,
    val fs: RealFileSystem,
    val cgroup: CgroupV2,
    val execCommandArgs: List<String>,
    /** True when the user explicitly passed `--root`. */
    val rootExplicit: Boolean = false,
)

// ---------------------------------------------------------------------------
// Root command — parses global options and stores config in Context.
// ---------------------------------------------------------------------------

class KontainerRuntime(
    private val originalArgs: Array<String>,
    private val execCommandArgs: List<String>,
) : CoreCliktCommand(name = "kontainer-runtime") {
    private val rootPathOpt by option("--root", help = "Root directory for container state")
    val rootPath get() = rootPathOpt ?: "/run/kontainer"
    val rootExplicit get() = rootPathOpt != null
    val logFile by option("--log", "-l", help = "Log file path")
    val logFormat by option("--log-format", help = "Log format (text or json)")
    val debug by option("--debug", help = "Enable debug logging").flag()
    val systemdCgroup by option("--systemd-cgroup", help = "Use systemd cgroup manager (accepted but not yet implemented)").flag()

    override fun run() {
        logFile?.let { Logger.setLogFile(it) }
        logFormat?.let { Logger.setLogFormat(it) }
        if (debug) {
            Logger.setLogLevel(Logger.Level.DEBUG)
        }
        Logger.debug("invoked with arguments: ${originalArgs.joinToString(" ") { "\"$it\"" }}")

        val syscall = LinuxSyscall()
        val fs = RealFileSystem()
        val cgroup = CgroupV2(fs)
        currentContext.obj = GlobalConfig(rootPath, syscall, fs, cgroup, execCommandArgs, rootExplicit)
    }
}

// ---------------------------------------------------------------------------
// Subcommands
// ---------------------------------------------------------------------------

class CreateCommand : CoreCliktCommand(name = "create") {
    override fun help(context: Context) = "Create a new container"

    val bundle by option("--bundle", "-b", help = "Bundle path").default(".")
    val pidFile by option("--pid-file", help = "PID file path")
    val consoleSocket by option(
        "--console-socket",
        help = "Path to AF_UNIX socket for PTY master fd handoff",
    )
    val pidfdSocket by option(
        "--pidfd-socket",
        help = "Path to AF_UNIX socket for pidfd handoff",
    )
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        create(
            config.syscall,
            config.fs,
            config.cgroup,
            config.rootPath,
            containerId,
            bundle,
            pidFile,
            consoleSocket,
            pidfdSocket,
        )
        // create() returns normally so run() can chain start().
        // Standalone create must use _exit to bypass the Kotlin/Native
        // runtime shutdown, which can deadlock after clone().
        _exit(0)
    }
}

class RunCommand : CoreCliktCommand(name = "run") {
    override fun help(context: Context) = "Create and start a container"

    val bundle by option("--bundle", "-b", help = "Bundle path").default(".")
    val pidFile by option("--pid-file", help = "PID file path")
    val consoleSocket by option(
        "--console-socket",
        help = "Path to AF_UNIX socket for PTY master fd handoff",
    )
    val pidfdSocket by option(
        "--pidfd-socket",
        help = "Path to AF_UNIX socket for pidfd handoff",
    )
    val detach by option(
        "--detach",
        "-d",
        help = "Detach from the container (do not wait for exit)",
    ).flag()
    val keep by option(
        "--keep",
        help = "Keep container state after it exits (don't delete automatically)",
    ).flag()
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        command.run(
            config.syscall,
            config.fs,
            config.cgroup,
            config.rootPath,
            containerId,
            bundle,
            pidFile,
            consoleSocket,
            detach,
            keep,
            pidfdSocket,
        )
        _exit(0)
    }
}

class StartCommand : CoreCliktCommand(name = "start") {
    override fun help(context: Context) = "Start a created container"

    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        start(config.fs, config.rootPath, containerId)
    }
}

class StateCommand : CoreCliktCommand(name = "state") {
    override fun help(context: Context) = "Display container state"

    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        state(config.fs, config.rootPath, containerId)
    }
}

class KillCommand : CoreCliktCommand(name = "kill") {
    override fun help(context: Context) = "Send a signal to a container"

    val all by option("--all", "-a", help = "Send the signal to all processes in the container").flag()
    val containerId by argument(help = "Container ID")
    val signal by argument(help = "Signal to send").default("SIGTERM")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        kill(config.syscall, config.fs, config.cgroup, config.rootPath, containerId, signal, all)
    }
}

class DeleteCommand : CoreCliktCommand(name = "delete") {
    override fun help(context: Context) = "Delete a container"

    val force by option("--force", "-f", help = "Force deletion").flag()
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        delete(config.syscall, config.fs, config.cgroup, config.rootPath, containerId, force)
    }
}

class ListCommand : CoreCliktCommand(name = "list") {
    override fun help(context: Context) = "List all containers"

    val format by option("--format", help = "Output format (table or json)").default("table")
    val quiet by option("--quiet", "-q", help = "Only display container IDs").flag()
    val config by requireObject<GlobalConfig>()

    override fun run() {
        list(config.fs, config.rootPath, format, quiet, config.rootExplicit)
    }
}

class PauseCommand : CoreCliktCommand(name = "pause") {
    override fun help(context: Context) = "Pause a running container"

    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        pause(config.fs, config.rootPath, containerId)
    }
}

class ResumeCommand : CoreCliktCommand(name = "resume") {
    override fun help(context: Context) = "Resume a paused container"

    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        resume(config.fs, config.rootPath, containerId)
    }
}

class UpdateCommand : CoreCliktCommand(name = "update") {
    override fun help(context: Context) = "Update container resource limits"

    val resourcesFile by option(
        "--resources",
        "-r",
        help = "Path to a JSON file with linux resources spec",
    )
    val memory by option("--memory", help = "Memory limit in bytes")
    val cpuQuota by option("--cpu-quota", help = "CPU quota in microseconds")
    val cpuPeriod by option("--cpu-period", help = "CPU period in microseconds")
    val cpuShares by option("--cpu-share", help = "CPU shares (relative weight)")
    val pidsLimit by option("--pids-limit", help = "PIDs limit")
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        update(
            config.fs,
            config.rootPath,
            containerId,
            resourcesPath = resourcesFile,
            memory = memory?.toLongOrNull(),
            cpuQuota = cpuQuota?.toLongOrNull(),
            cpuPeriod = cpuPeriod?.toLongOrNull(),
            cpuShares = cpuShares?.toLongOrNull(),
            pidsLimit = pidsLimit?.toLongOrNull(),
        )
    }
}

class EventsCommand : CoreCliktCommand(name = "events") {
    override fun help(context: Context) = "Stream container resource statistics"

    val stats by option("--stats", help = "Print a single snapshot and exit").flag()
    val interval by option(
        "--interval",
        help = "Interval between snapshots (Go duration, e.g. 5s, 100ms). Default 5s.",
    ).default("5s")
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        events(config.fs, config.rootPath, containerId, stats, parseDurationMs(interval))
    }
}

/**
 * Parse a Go-style duration string into milliseconds.
 * Supports: "100ms", "1s", "5s", "1m", "500us", bare number (seconds).
 */
private fun parseDurationMs(input: String): Long {
    val s = input.trim()

    // Try bare integer (seconds, for backward compat)
    s.toLongOrNull()?.let { return it * 1000 }

    // Go duration suffixes
    if (s.endsWith("ms")) {
        s.removeSuffix("ms").toLongOrNull()?.let { return it }
    }
    if (s.endsWith("us") || s.endsWith("µs")) {
        val v = s.removeSuffix("us").removeSuffix("µs").toLongOrNull()
        if (v != null) return maxOf(v / 1000, 1)
    }
    if (s.endsWith("ns")) {
        s.removeSuffix("ns").toLongOrNull()?.let { return maxOf(it / 1_000_000, 1) }
    }
    if (s.endsWith("m") && !s.endsWith("ms")) {
        s.removeSuffix("m").toLongOrNull()?.let { return it * 60_000 }
    }
    if (s.endsWith("h")) {
        s.removeSuffix("h").toLongOrNull()?.let { return it * 3_600_000 }
    }
    if (s.endsWith("s") && !s.endsWith("ms") && !s.endsWith("us") && !s.endsWith("ns") && !s.endsWith("µs")) {
        s.removeSuffix("s").toLongOrNull()?.let { return it * 1000 }
    }

    // Fallback: default 5 seconds
    return 5000
}

class PsCommand : CoreCliktCommand(name = "ps") {
    override fun help(context: Context) = "List processes in a container"

    val format by option("--format", "-f", help = "Output format (json or table)").default("json")
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        ps(config.fs, config.cgroup, config.rootPath, containerId, format)
    }
}

class ExecCommand : CoreCliktCommand(name = "exec") {
    override fun help(context: Context) = "Execute a process in a running container"

    val processSpec by option(
        "--process",
        "-p",
        help = "Path to a process.json file with the exec process spec",
    )
    val pidFile by option("--pid-file", help = "Path to write the exec'd process PID")
    val detach by option(
        "--detach",
        "-d",
        help = "Detach from the exec'd process (do not wait for exit)",
    ).flag()
    val tty by option(
        "--tty",
        "-t",
        help = "Allocate a pseudo-TTY",
    ).flag()
    val consoleSocketOpt by option(
        "--console-socket",
        help = "Path to AF_UNIX socket to receive the PTY master (detached terminal mode)",
    )
    val pidfdSocket by option(
        "--pidfd-socket",
        help = "Path to AF_UNIX socket for pidfd handoff",
    )
    val cwdOverride by option(
        "--cwd",
        help = "Override the working directory for the exec'd process",
    )
    val envOverrides by option(
        "--env",
        help = "Set an environment variable (KEY=VALUE, can be repeated)",
    ).multiple()
    val userOverride by option(
        "--user",
        "-u",
        help = "Override the user (UID[:GID])",
    )
    val additionalGids by option(
        "--additional-gids",
        help = "Additional group IDs (can be repeated)",
    ).multiple()
    val preserveFds by option(
        "--preserve-fds",
        help = "Number of additional file descriptors to preserve for the exec'd process",
    ).int().default(0)
    val cgroupOverride by option(
        "--cgroup",
        help = "Run the exec'd process in a sub-cgroup",
    ).multiple()
    val capOverride by option(
        "--cap",
        help = "Add a capability to the bounding set",
    ).multiple()
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        exec(
            config.syscall,
            config.fs,
            config.cgroup,
            config.rootPath,
            containerId,
            config.execCommandArgs,
            processSpec,
            pidFile,
            detach,
            tty = tty,
            consoleSocket = consoleSocketOpt,
            cwdOverride = cwdOverride,
            envOverrides = envOverrides,
            userOverride = userOverride,
            additionalGids = additionalGids,
            preserveFds = preserveFds,
            cgroupOverride = cgroupOverride,
            pidfdSocket = pidfdSocket,
        )
    }
}

class SpecCommand : CoreCliktCommand(name = "spec") {
    override fun help(context: Context) = "Create a new specification file"

    val bundle by option("--bundle", "-b", help = "Bundle path").default(".")

    override fun run() {
        spec(bundle)
    }
}

// ---------------------------------------------------------------------------
// Subcommand names (for help flag detection)
// ---------------------------------------------------------------------------
private val SUBCOMMAND_NAMES =
    setOf(
        "checkpoint",
        "create",
        "delete",
        "events",
        "exec",
        "kill",
        "list",
        "pause",
        "ps",
        "restore",
        "resume",
        "run",
        "spec",
        "start",
        "state",
        "update",
        "features",
    )

// ---------------------------------------------------------------------------
// runc-compatible help output (NAME: header format expected by help.bats)
// ---------------------------------------------------------------------------

private val SUBCOMMAND_DESCRIPTIONS =
    mapOf(
        "checkpoint" to "checkpoint a running container",
        "create" to "create a container",
        "delete" to "delete any resources held by the container often used with detached container",
        "events" to "display container events such as OOM notifications, cpu, memory, and IO usage statistics",
        "exec" to "execute new process inside the container",
        "features" to "show the enabled features",
        "kill" to "kill sends the specified signal (default: SIGTERM) to the container's init process",
        "list" to "lists containers started by runc with the given root",
        "pause" to "pause suspends all processes inside the container",
        "ps" to "ps displays the processes running inside a container",
        "restore" to "restore a container from a previous checkpoint",
        "resume" to "resumes all processes that have been previously paused",
        "run" to "create and run a container",
        "spec" to "create a new specification file",
        "start" to "executes the user defined process in a created container",
        "state" to "output the state of a container",
        "update" to "update container resource constraints",
    )

/** Get the runtime binary name — original name saved before exeseal, or fallback. */
private fun getRuntimeName(): String = getenv("_KONTAINER_BINARY_NAME")?.toKString() ?: "kontainer-runtime"

private fun printRuncHelp() {
    val name = getRuntimeName()
    println("NAME:")
    println("   $name - Open Container Initiative runtime")
    println()
    println("USAGE:")
    println("   $name [global options] command [command options] [arguments...]")
    println()
    println("COMMANDS:")
    for ((cmd, desc) in SUBCOMMAND_DESCRIPTIONS) {
        println("   ${cmd.padEnd(16)}$desc")
    }
    println()
    println("GLOBAL OPTIONS:")
    println("   --debug             enable debug logging")
    println("   --log value         set the log file to write runc logs to (default: /dev/stderr)")
    println("   --log-format value  set the log format (default: text)")
    println("   --root value        root directory for storage of container state (default: /run/kontainer)")
    println("   --help, -h          show help")
    println("   --version, -v       print the version")
}

private fun printSubcommandHelp(subcommand: String) {
    val name = getRuntimeName()
    val desc = SUBCOMMAND_DESCRIPTIONS[subcommand] ?: subcommand
    println("NAME:")
    println("   $name $subcommand - $desc")
    println()
    println("USAGE:")
    println("   $name $subcommand [command options] [arguments...]")
}

// ---------------------------------------------------------------------------
// Custom usage text (printed when invoked with no arguments)
// ---------------------------------------------------------------------------

private fun printUsage() {
    println("Usage: kontainer-runtime [global-options] <command> [options] <container-id> [args...]")
    println()
    println("Global options:")
    println("  --root <path>             Root directory for container state (default: /run/kontainer)")
    println("  --log <path>, -l <path>   Log file path (default: stderr)")
    println("  --log-format <text|json>  Log format (default: text)")
    println("  --debug                   Enable debug logging")
    println()
    println("Commands:")
    println("  create [--bundle|-b <path>] [--pid-file <path>] <container-id>    Create a new container")
    println("  run [--bundle|-b <path>] [-d] <container-id>                      Create and start a container")
    println("  start <container-id>                                               Start a created container")
    println("  state <container-id>                                               Display container state")
    println("  kill <container-id> <signal>                                       Send a signal to a container")
    println("  delete [--force|-f] <container-id>                                 Delete a container")
    println("  list [--format <table|json>] [-q]                                  List all containers")
    println("  pause <container-id>                                               Pause a running container")
    println("  resume <container-id>                                              Resume a paused container")
    println(
        "  update [-r <resources.json>] [--memory <bytes>] [--pids-limit <n>] <container-id>",
    )
    println(
        "                                                                         Update container resource limits",
    )
    println("  events [--stats] [--interval <s>] <container-id>                   Stream container resource stats")
    println("  ps [--format|-f <json|table>] <container-id>                       List processes in a container")
    println(
        "  exec [-p <process.json>] [--pid-file <path>] [-d] <container-id> [--] [command [args...]]",
    )
    println(
        "                                                                         Run a process in a running container",
    )
    println("  spec [--bundle|-b <path>]                                          Generate a default OCI config.json")
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Peek at the CLI args to identify the subcommand without full parsing.
 *
 * Skips known global flags (--root, --log, --log-format and their values,
 * plus boolean flags like --debug and --systemd-cgroup) and returns the first
 * positional token, which is the subcommand name.  Returns null if no
 * positional argument is found (e.g. bare `--help` invocation).
 */
private fun peekSubcommand(args: Array<String>): String? {
    val globalFlagsWithValue = setOf("--root", "--log", "-l", "--log-format")
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("-")) {
            val flagName = arg.split("=", limit = 2)[0]
            if (flagName in globalFlagsWithValue && !arg.contains("=")) {
                i += 2 // skip --flag <value>
            } else {
                i++ // boolean flag or --flag=value
            }
        } else {
            return arg
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Kontainer Runtime - Container runtime written in Kotlin/Native
 *
 * Minimal container runtime implementation compliant with OCI Runtime Specification
 */
fun main(args: Array<String>) {
    // Bootstrap constructor has already run before Kotlin runtime started
    // Check if this process is the init process (Stage-2, set by bootstrap.c)
    val isInit = kontainer_is_init_process()

    Logger.setContext("main")

    // CVE-2019-5736 mitigation: ensure processes that enter container
    // namespaces run from a sealed binary so /proc/self/exe cannot be used
    // to overwrite the host binary.
    //
    // Strategy depends on the subcommand:
    // - create/run: seal binary and store the fd; the fork+exec in Create.kt
    //   uses /proc/self/fd/<fd> so the bootstrap child is born from the sealed
    //   copy — one exec instead of two (no main-process re-exec needed).
    // - exec (and other commands that enter namespaces directly via setns in
    //   a fork'd child): re-exec the main process from the sealed copy so all
    //   fork'd children automatically have /proc/self/exe → sealed binary.
    // - init process (Stage-2): skip — it was already exec'd from the sealed
    //   binary by the parent.
    if (isInit == 0) {
        // Save the binary name before exeseal re-execs from a memfd — after
        // re-exec /proc/self/exe points to the memfd, not the original binary.
        // The help output and version strings need the original name.
        if (getenv("_KONTAINER_BINARY_NAME") == null) {
            memScoped {
                val buf = allocArray<ByteVar>(PATH_MAX)
                val len = readlink("/proc/self/exe", buf, (PATH_MAX - 1).convert())
                if (len > 0) {
                    buf[len.toInt()] = 0
                    val exePath = buf.toKString()
                    val baseName = exePath.substringAfterLast('/')
                    setenv("_KONTAINER_BINARY_NAME", baseName, 1)
                }
            }
        }

        val subcmd = peekSubcommand(args)
        if (subcmd == "create" || subcmd == "run") {
            sealBinary()
        } else {
            ensureSelfCloned(args)
        }
    }

    // If this is Stage-2 (init process) forked by bootstrap.c
    if (isInit != 0 || (args.size == 1 && args[0] == "__init__")) {
        Logger.debug("running as init process (Stage-2, forked by bootstrap.c)")

        val syscall = LinuxSyscall()
        val fs = RealFileSystem()

        // Restore channel FDs from environment variables
        val mainSenderFdStr = getenv("_KONTAINER_MAIN_SENDER_FD")?.toKString()
        val initReceiverFdStr = getenv("_KONTAINER_INIT_RECEIVER_FD")?.toKString()
        val notifyListenerFdStr = getenv("_KONTAINER_NOTIFY_LISTENER_FD")?.toKString()
        val bundlePath = getenv("_KONTAINER_BUNDLE_PATH")?.toKString()
        val rootfsPath = getenv("_KONTAINER_ROOTFS_PATH")?.toKString()

        if (mainSenderFdStr == null || initReceiverFdStr == null || notifyListenerFdStr == null ||
            bundlePath == null || rootfsPath == null
        ) {
            Logger.error("missing required environment variables for init process")
            exit(1)
            return
        }

        val mainSenderFd = mainSenderFdStr.toIntOrNull()
        val initReceiverFd = initReceiverFdStr.toIntOrNull()
        val notifyListenerFd = notifyListenerFdStr.toIntOrNull()

        if (mainSenderFd == null || initReceiverFd == null || notifyListenerFd == null) {
            Logger.error("invalid FD values in environment variables")
            exit(1)
            return
        }

        // Load spec from bundle
        Logger.debug("loading spec from $bundlePath/config.json")
        val spec =
            try {
                loadSpec(fs, "$bundlePath/config.json")
            } catch (e: Exception) {
                Logger.error("failed to load spec: ${e.message ?: "unknown error"}")
                exit(1)
                return
            }

        // Recreate channel objects from FDs (inherited from parent process)
        val mainSender = SocketMainSender(mainSenderFd)
        val initReceiver = SocketInitReceiver(initReceiverFd)
        val notifyListener = SocketNotifyListener(notifyListenerFd)

        val pid = getpid()
        Logger.info("init process (Stage-2, PID=$pid) started successfully via bootstrap.c")
        Logger.debug("bundle=$bundlePath, rootfs=$rootfsPath")
        Logger.debug(
            "restored FDs: main_sender=$mainSenderFd, " +
                "init_receiver=$initReceiverFd, notify_listener=$notifyListenerFd",
        )

        // Run init process logic (Stage-2 / PID 1)
        // This will eventually call execve() and replace this process
        // with the container process
        runInitProcess(syscall, spec, rootfsPath, mainSender, initReceiver, notifyListener)

        // Should not reach here (runInitProcess calls execve or _exit)
        Logger.error("runInitProcess returned unexpectedly")
        exit(1)
    }

    // Handle -v / --version before the CLI parser (runc compatibility)
    if (args.size == 1 && (args[0] == "-v" || args[0] == "--version")) {
        println("runc version ${BuildConfig.VERSION}")
        println("commit: ${BuildConfig.COMMIT}")
        println("spec: ${BuildConfig.OCI_SPEC_VERSION}")
        return
    }

    // Handle -h / --help before the CLI parser (runc compatibility).
    // CoreCliktCommand does not register --help; we produce runc-style output.
    if (args.isNotEmpty() && (args.last() == "-h" || args.last() == "--help")) {
        val subcommand = args.firstOrNull { !it.startsWith("-") }
        if (subcommand != null && subcommand in SUBCOMMAND_NAMES) {
            printSubcommandHelp(subcommand)
        } else if (subcommand != null && subcommand !in SUBCOMMAND_NAMES) {
            fprintf(stderr, "No help topic for '%s'\n", subcommand)
            exit(1)
            return
        } else {
            printRuncHelp()
        }
        return
    }

    if (args.isEmpty()) {
        printUsage()
        exit(1)
        return
    }

    // Exec needs special argv handling: everything after the container ID
    // is the command to run inside the container, but the CLI parser would
    // try to parse dash-prefixed args (e.g. `sh -c '...'`) as runtime flags.
    // We split the trailing command args out before the parser sees them.
    val execCommandArgs = mutableListOf<String>()
    val effectiveArgs = preprocessExecArgs(args, execCommandArgs)

    try {
        KontainerRuntime(args, execCommandArgs)
            .subcommands(
                CreateCommand(),
                RunCommand(),
                StartCommand(),
                StateCommand(),
                KillCommand(),
                DeleteCommand(),
                ListCommand(),
                PauseCommand(),
                ResumeCommand(),
                UpdateCommand(),
                EventsCommand(),
                PsCommand(),
                ExecCommand(),
                SpecCommand(),
            ).parse(effectiveArgs)
    } catch (e: UsageError) {
        // Clikt's main() on K/N prints usage errors to stdout with exit code 0,
        // which violates the OCI spec requirement that missing arguments produce
        // an error (non-zero exit + stderr).  Handle it ourselves.
        Logger.error(e.message ?: "missing required argument")
        exit(1)
    } catch (e: CliktError) {
        val msg = e.message
        Logger.error(msg ?: "CLI error")
        exit(1)
    }
}
