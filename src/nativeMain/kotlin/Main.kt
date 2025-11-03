import bootstrap.kontainer_is_init_process
import channel.InitReceiver
import channel.MainSender
import channel.NotifyListener
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
                    loadSpec("$bundlePath/config.json")
                } catch (e: Exception) {
                    Logger.error("failed to load spec: ${e.message ?: "unknown error"}")
                    exit(1)
                    return
                }

            // Recreate channel objects from FDs (inherited from parent process)
            val mainSender = MainSender(mainSenderFd)
            val initReceiver = InitReceiver(initReceiverFd)
            val notifyListener = NotifyListener(notifyListenerFd)

            val pid = getpid()
            Logger.info("init process (Stage-2, PID=$pid) started successfully via bootstrap.c")
            Logger.debug("bundle=$bundlePath, rootfs=$rootfsPath")
            Logger.debug("restored FDs: main_sender=$mainSenderFd, init_receiver=$initReceiverFd, notify_listener=$notifyListenerFd")

            // Run init process logic (Stage-2 / PID 1)
            // This will eventually call execve() and replace this process with the container process
            runInitProcess(spec, rootfsPath, mainSender, initReceiver, notifyListener)

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

            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                create(rootPath, containerId, bundle, pidFile)
            }
        }

        class StartCommand : Subcommand("start", "Start a created container") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                start(rootPath, containerId)
            }
        }

        class StateCommand : Subcommand("state", "Display container state") {
            val containerId by argument(
                ArgType.String,
                description = "Container ID",
            )

            override fun execute() {
                state(rootPath, containerId)
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
                kill(rootPath, containerId, signal)
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
                delete(rootPath, containerId, force)
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
                ps(rootPath, containerId, format)
            }
        }

        parser.subcommands(
            CreateCommand(),
            StartCommand(),
            StateCommand(),
            KillCommand(),
            DeleteCommand(),
            PsCommand(),
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
            println("  ps [--format|-f <json|table>] <container-id>                       List processes in a container")
            exit(1)
        }

        Logger.info("kontainer-runtime invoked with ${args.size} arguments. arguments: ${args.joinToString(" ") { "\"$it\"" }}")

        try {
            parser.parse(args)
        } catch (e: IllegalStateException) {
            Logger.error("error: ${e.message}")
            exit(1)
        }

        // Apply global options after parsing
        logFile?.let { Logger.setLogFile(it) }
        logFormat?.let { Logger.setLogFormat(it) }
        if (debug) {
            Logger.setLogLevel(logger.Logger.Level.DEBUG)
        }
    }
