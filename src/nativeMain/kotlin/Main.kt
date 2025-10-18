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
            Logger.error("Usage: kontainer-runtime <command> [options] <container-id> [args...]")
            Logger.error("")
            Logger.error("Commands:")
            Logger.error("  create [--bundle|-b <path>] [--pid-file <path>] <container-id>    Create a new container")
            Logger.error("  start <container-id>                                               Start a created container")
            Logger.error("  state <container-id>                                               Display container state")
            Logger.error("  kill <container-id> <signal>                                       Send a signal to a container")
            Logger.error("  delete [--force|-f] <container-id>                                 Delete a container")
            exit(1)
        }

        when (val command = args[0]) {
            "create" -> {
                val cmdArgs = args.drop(1)

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

                create(containerId!!, bundlePath, pidFile)
            }

            "start" -> {
                val cmdArgs = args.drop(1)
                if (cmdArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime start <container-id>")
                    exit(1)
                }
                start(cmdArgs[0])
            }

            "state" -> {
                val cmdArgs = args.drop(1)
                if (cmdArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime state <container-id>")
                    exit(1)
                }
                state(cmdArgs[0])
            }

            "kill" -> {
                val cmdArgs = args.drop(1)
                if (cmdArgs.size < 2) {
                    Logger.error("Usage: kontainer-runtime kill <container-id> <signal>")
                    exit(1)
                }
                kill(cmdArgs[0], cmdArgs[1])
            }

            "delete" -> {
                // Parse --force or -f flag
                val remainingArgs = args.drop(1).toList()
                val force = remainingArgs.contains("--force") || remainingArgs.contains("-f")
                val containerArgs = remainingArgs.filter { it != "--force" && it != "-f" }

                if (containerArgs.isEmpty()) {
                    Logger.error("Usage: kontainer-runtime delete [--force|-f] <container-id>")
                    exit(1)
                }

                delete(containerArgs[0], force)
            }

            else -> {
                Logger.error("unknown command: $command")
                Logger.error("available commands: create, start, state, kill, delete")
                exit(1)
            }
        }
    }
