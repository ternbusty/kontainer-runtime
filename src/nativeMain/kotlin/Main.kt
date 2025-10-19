import command.create
import command.delete
import command.kill
import command.start
import command.state
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import logger.Logger
import platform.posix.exit

/**
 * Kontainer Runtime - Container runtime written in Kotlin/Native
 *
 * Minimal container runtime implementation compliant with OCI Runtime Specification
 * Uses a 3-process architecture with 3 channels and notify socket for container lifecycle management
 *
 * Commands:
 *   create [--bundle|-b <path>] [--pid-file <path>] <container-id>  - Create a container
 *   start <container-id>                                             - Start a created container
 *   state <container-id>                                             - Display container state
 *   kill <container-id> <signal>                                     - Send a signal to a container
 *   delete [--force|-f] <container-id>                               - Delete a container
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>): Unit =
    memScoped {
        Logger.setContext("main")

        if (args.isEmpty()) {
            Logger.error("Usage: kontainer-runtime [global-options] <command> [options] <container-id> [args...]")
            Logger.error("")
            Logger.error("Global options:")
            Logger.error("  --root <path>        Root directory for container state (default: /run/kontainer)")
            Logger.error("")
            Logger.error("Commands:")
            Logger.error("  create [--bundle|-b <path>] [--pid-file <path>] <container-id>    Create a new container")
            Logger.error("  start <container-id>                                               Start a created container")
            Logger.error("  state <container-id>                                               Display container state")
            Logger.error("  kill <container-id> <signal>                                       Send a signal to a container")
            Logger.error("  delete [--force|-f] <container-id>                                 Delete a container")
            exit(1)
        }

        // Parse global options
        var rootPath = "/run/kontainer" // Default root path
        var argIndex = 0

        while (argIndex < args.size) {
            when (args[argIndex]) {
                "--root" -> {
                    if (argIndex + 1 >= args.size) {
                        Logger.error("--root requires a path argument")
                        exit(1)
                    }
                    rootPath = args[argIndex + 1]
                    argIndex += 2
                }
                else -> {
                    // Not a global option, must be a command
                    break
                }
            }
        }

        if (argIndex >= args.size) {
            Logger.error("no command specified")
            Logger.error("Usage: kontainer-runtime [global-options] <command> [options] <container-id> [args...]")
            exit(1)
        }

        val command = args[argIndex]
        val commandArgs = args.drop(argIndex + 1)

        when (command) {
            "create" -> {
                val cmdArgs = commandArgs

                // Parse options
                var bundlePath = "." // Default to current directory (OCI standard)
                var pidFile: String? = null
                var containerId: String? = null
                var i = 0

                while (i < cmdArgs.size) {
                    when (cmdArgs[i]) {
                        "--bundle", "-b" -> {
                            if (i + 1 >= cmdArgs.size) {
                                Logger.error("--bundle requires a path argument")
                                exit(1)
                            }
                            bundlePath = cmdArgs[i + 1]
                            i += 2
                        }
                        "--pid-file" -> {
                            if (i + 1 >= cmdArgs.size) {
                                Logger.error("--pid-file requires a path argument")
                                exit(1)
                            }
                            pidFile = cmdArgs[i + 1]
                            i += 2
                        }
                        else -> {
                            // Assume this is the container ID (last positional argument)
                            if (cmdArgs[i].startsWith("-")) {
                                Logger.error("unknown option: ${cmdArgs[i]}")
                                Logger.error("Usage: kontainer-runtime create [--bundle|-b <path>] [--pid-file <path>] <container-id>")
                                exit(1)
                            }
                            containerId = cmdArgs[i]
                            i++
                        }
                    }
                }

                if (containerId == null) {
                    Logger.error("Usage: kontainer-runtime create [--bundle|-b <path>] [--pid-file <path>] <container-id>")
                    exit(1)
                }

                create(rootPath, containerId!!, bundlePath, pidFile)
            }

            "start" -> {
                if (commandArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime start <container-id>")
                    exit(1)
                }
                start(rootPath, commandArgs[0])
            }

            "state" -> {
                if (commandArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime state <container-id>")
                    exit(1)
                }
                state(rootPath, commandArgs[0])
            }

            "kill" -> {
                if (commandArgs.size < 2) {
                    Logger.error("Usage: kontainer-runtime kill <container-id> <signal>")
                    exit(1)
                }
                kill(rootPath, commandArgs[0], commandArgs[1])
            }

            "delete" -> {
                // Parse --force or -f flag
                val force = commandArgs.contains("--force") || commandArgs.contains("-f")
                val containerArgs = commandArgs.filter { it != "--force" && it != "-f" }

                if (containerArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime delete [--force|-f] <container-id>")
                    exit(1)
                }

                delete(rootPath, containerArgs[0], force)
            }

            else -> {
                Logger.error("unknown command: $command")
                Logger.error("available commands: create, start, state, kill, delete")
                exit(1)
            }
        }
    }
