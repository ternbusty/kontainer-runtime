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
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import command.*
import config.BuildConfig
import kotlinx.cinterop.toKString
import logger.Logger
import platform.posix._exit
import platform.posix.exit
import platform.posix.getenv
import platform.posix.getpid
import process.runInitProcess
import spec.loadSpec
import syscall.LinuxSyscall
import utils.RealFileSystem

/** Exec-option flags that consume the next argument as a value. */
private val EXEC_VALUE_FLAGS = setOf("-p", "--process", "--pid-file")

/** Exec-option flags that are boolean (no following value). */
private val EXEC_BOOL_FLAGS = setOf("-d", "--detach")

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
)

// ---------------------------------------------------------------------------
// Root command — parses global options and stores config in Context.
// ---------------------------------------------------------------------------

class KontainerRuntime(
    private val originalArgs: Array<String>,
    private val execCommandArgs: List<String>,
) : CoreCliktCommand(name = "kontainer-runtime") {
    val rootPath by option("--root", help = "Root directory for container state")
        .default("/run/kontainer")
    val logFile by option("--log", "-l", help = "Log file path")
    val logFormat by option("--log-format", help = "Log format (text or json)")
    val debug by option("--debug", help = "Enable debug logging").flag()

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
        currentContext.obj = GlobalConfig(rootPath, syscall, fs, cgroup, execCommandArgs)
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
    val detach by option(
        "--detach",
        "-d",
        help = "Detach from the container (do not wait for exit)",
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

    val containerId by argument(help = "Container ID")
    val signal by argument(help = "Signal to send")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        kill(config.syscall, config.fs, config.rootPath, containerId, signal)
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
        list(config.fs, config.rootPath, format, quiet)
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
        help = "Seconds between snapshots (default 5)",
    ).int().default(5)
    val containerId by argument(help = "Container ID")
    val config by requireObject<GlobalConfig>()

    override fun run() {
        events(config.fs, config.rootPath, containerId, stats, interval.toUInt())
    }
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
        println("kontainer-runtime version ${BuildConfig.VERSION}")
        println("commit: ${BuildConfig.COMMIT}")
        println("spec: ${BuildConfig.OCI_SPEC_VERSION}")
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
