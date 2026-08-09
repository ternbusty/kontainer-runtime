import bootstrap.kontainer_is_init_process
import cgroup.CgroupV2
import channel.SocketInitReceiver
import channel.SocketMainSender
import channel.SocketNotifyListener
import command.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cli.*
import logger.Logger
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
 * kotlinx-cli never sees them.
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
            // after the subcommand); pass through to kotlinx-cli.
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

/**
 * Kontainer Runtime - Container runtime written in Kotlin/Native
 *
 * Minimal container runtime implementation compliant with OCI Runtime Specification
 *
 * Commands:
 *   create [--bundle|-b <path>] [--pid-file <path>] <container-id>  - Create a container
 *   start <container-id>                                             - Start a created container
 *   state <container-id>                                             - Display container state
 *   kill <container-id> <signal>                                     - Send a signal to a container
 *   delete [--force|-f] <container-id>                               - Delete a container
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCli::class)
fun main(args: Array<String>): Unit =
    memScoped {
        // Bootstrap constructor has already run before Kotlin runtime started
        // Check if this process is the init process (Stage-2, set by bootstrap.c)
        val isInit = kontainer_is_init_process()

        Logger.setContext("main")

        val syscall = LinuxSyscall()
        val fs = RealFileSystem()
        val cgroup = CgroupV2(fs)

        // If this is Stage-2 (init process) forked by bootstrap.c
        if (isInit != 0 || (args.size == 1 && args[0] == "__init__")) {
            Logger.debug("running as init process (Stage-2, forked by bootstrap.c)")

            // Note: bootstrap.c Stage-1 has already sent our PID to Main Process
            // We don't need to sync with bootstrap parent here

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
            Logger.debug("restored FDs: main_sender=$mainSenderFd, init_receiver=$initReceiverFd, notify_listener=$notifyListenerFd")

            // Run init process logic (Stage-2 / PID 1)
            // This will eventually call execve() and replace this process with the container process
            runInitProcess(syscall, spec, rootfsPath, mainSender, initReceiver, notifyListener)

            // Should not reach here (runInitProcess calls execve or _exit)
            Logger.error("runInitProcess returned unexpectedly")
            exit(1)
        }

        val parser = ArgParser("kontainer-runtime")

        // Global options
        val rootPath by parser
            .option(
                ArgType.String,
                fullName = "root",
                description = "Root directory for container state",
            ).default("/run/kontainer")

        val logFile by parser.option(
            ArgType.String,
            shortName = "l",
            fullName = "log",
            description = "Log file path",
        )

        val logFormat by parser.option(
            ArgType.String,
            fullName = "log-format",
            description = "Log format (text or json)",
        )

        val debug by parser
            .option(
                ArgType.Boolean,
                fullName = "debug",
                description = "Enable debug logging",
            ).default(false)

        // kotlinx-cli runs Subcommand.execute() inside parser.parse(), so global
        // options must be applied at the start of each execute() — code placed
        // after parse() returns would run only after the command has finished
        // (or never, if the command exits).
        var globalOptionsApplied = false

        fun applyGlobalOptions() {
            if (globalOptionsApplied) return
            globalOptionsApplied = true
            logFile?.let { Logger.setLogFile(it) }
            logFormat?.let { Logger.setLogFormat(it) }
            if (debug) {
                Logger.setLogLevel(Logger.Level.DEBUG)
            }
            Logger.debug("invoked with arguments: ${args.joinToString(" ") { "\"$it\"" }}")
        }

        class CreateCommand : Subcommand("create", "Create a new container") {
            val bundle by option(
                ArgType.String,
                shortName = "b",
                fullName = "bundle",
                description = "Bundle path",
            ).default(".")

            val pidFile by option(
                ArgType.String,
                fullName = "pid-file",
                description = "PID file path",
            )

            val consoleSocket by option(
                ArgType.String,
                fullName = "console-socket",
                description = "Path to AF_UNIX socket for PTY master fd handoff",
            )

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                create(syscall, fs, cgroup, rootPath, containerId, bundle, pidFile, consoleSocket)
            }
        }

        class StartCommand : Subcommand("start", "Start a created container") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                start(fs, rootPath, containerId)
            }
        }

        class StateCommand : Subcommand("state", "Display container state") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                state(fs, rootPath, containerId)
            }
        }

        class KillCommand : Subcommand("kill", "Send a signal to a container") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            val signal by argument(
                ArgType.String,
                description = "Signal to send",
            )

            override fun execute() {
                applyGlobalOptions()
                kill(syscall, fs, rootPath, containerId, signal)
            }
        }

        class DeleteCommand : Subcommand("delete", "Delete a container") {
            val force by option(
                ArgType.Boolean,
                shortName = "f",
                fullName = "force",
                description = "Force deletion",
            ).default(false)

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                delete(syscall, fs, cgroup, rootPath, containerId, force)
            }
        }

        class ListCommand : Subcommand("list", "List all containers") {
            val format by option(
                ArgType.String,
                fullName = "format",
                description = "Output format (table or json)",
            ).default("table")

            val quiet by option(
                ArgType.Boolean,
                shortName = "q",
                fullName = "quiet",
                description = "Only display container IDs",
            ).default(false)

            override fun execute() {
                applyGlobalOptions()
                list(fs, rootPath, format, quiet)
            }
        }

        class PauseCommand : Subcommand("pause", "Pause a running container") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                pause(fs, rootPath, containerId)
            }
        }

        class ResumeCommand : Subcommand("resume", "Resume a paused container") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                resume(fs, rootPath, containerId)
            }
        }

        class UpdateCommand : Subcommand("update", "Update container resource limits") {
            val resourcesFile by option(
                ArgType.String,
                shortName = "r",
                fullName = "resources",
                description = "Path to a JSON file with linux resources spec",
            )

            val memory by option(
                ArgType.String,
                fullName = "memory",
                description = "Memory limit in bytes",
            )

            val cpuQuota by option(
                ArgType.String,
                fullName = "cpu-quota",
                description = "CPU quota in microseconds",
            )

            val cpuPeriod by option(
                ArgType.String,
                fullName = "cpu-period",
                description = "CPU period in microseconds",
            )

            val cpuShares by option(
                ArgType.String,
                fullName = "cpu-share",
                description = "CPU shares (relative weight)",
            )

            val pidsLimit by option(
                ArgType.String,
                fullName = "pids-limit",
                description = "PIDs limit",
            )

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                update(
                    fs,
                    rootPath,
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

        class EventsCommand : Subcommand("events", "Stream container resource statistics") {
            val stats by option(
                ArgType.Boolean,
                fullName = "stats",
                description = "Print a single snapshot and exit",
            ).default(false)

            val interval by option(
                ArgType.Int,
                fullName = "interval",
                description = "Seconds between snapshots (default 5)",
            ).default(5)

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                events(fs, rootPath, containerId, stats, interval.toUInt())
            }
        }

        class PsCommand : Subcommand("ps", "List processes in a container") {
            val format by option(
                ArgType.String,
                shortName = "f",
                fullName = "format",
                description = "Output format (json or table)",
            ).default("json")

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                ps(fs, cgroup, rootPath, containerId, format)
            }
        }

        // Exec needs special argv handling: everything after the container ID
        // is the command to run inside the container, but kotlinx-cli would try
        // to parse dash-prefixed args (e.g. `sh -c '...'`) as runtime flags.
        // We split the trailing command args out before kotlinx-cli sees them.
        val execCommandArgs = mutableListOf<String>()

        class ExecCommand : Subcommand("exec", "Execute a process in a running container") {
            val processSpec by option(
                ArgType.String,
                shortName = "p",
                fullName = "process",
                description = "Path to a process.json file with the exec process spec",
            )

            val pidFile by option(
                ArgType.String,
                fullName = "pid-file",
                description = "Path to write the exec'd process PID",
            )

            val detach by option(
                ArgType.Boolean,
                shortName = "d",
                fullName = "detach",
                description = "Detach from the exec'd process (do not wait for exit)",
            ).default(false)

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                applyGlobalOptions()
                exec(syscall, fs, cgroup, rootPath, containerId, execCommandArgs, processSpec, pidFile, detach)
            }
        }

        parser.subcommands(
            CreateCommand(),
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
        )

        if (args.isEmpty()) {
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
            println("  start <container-id>                                               Start a created container")
            println("  state <container-id>                                               Display container state")
            println("  kill <container-id> <signal>                                       Send a signal to a container")
            println("  delete [--force|-f] <container-id>                                 Delete a container")
            println("  list [--format <table|json>] [-q]                                  List all containers")
            println("  pause <container-id>                                               Pause a running container")
            println("  resume <container-id>                                              Resume a paused container")
            println("  update [-r <resources.json>] [--memory <bytes>] [--pids-limit <n>] <container-id>")
            println("                                                                         Update container resource limits")
            println("  events [--stats] [--interval <s>] <container-id>                   Stream container resource stats")
            println("  ps [--format|-f <json|table>] <container-id>                       List processes in a container")
            println("  exec [-p <process.json>] [--pid-file <path>] [-d] <container-id> [--] [command [args...]]")
            println("                                                                         Run a process in a running container")
            exit(1)
        }

        val effectiveArgs = preprocessExecArgs(args, execCommandArgs)

        try {
            parser.parse(effectiveArgs)
        } catch (e: IllegalStateException) {
            Logger.error("error: ${e.message}")
            exit(1)
        }

        // Covers invocations without a subcommand (execute() never ran)
        applyGlobalOptions()
    }
