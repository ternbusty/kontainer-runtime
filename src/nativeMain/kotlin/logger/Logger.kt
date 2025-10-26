package logger

import config.BuildConfig
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Simple structured logger for kontainer-runtime
 *
 * Features:
 * - Log levels: TRACE, DEBUG, INFO, WARN, ERROR
 * - Environment variable control: KONTAINER_LOG_LEVEL
 * - Process context tracking (main/intermediate/init)
 * - Timestamp formatting
 *
 * Usage:
 *   Logger.setContext("init")
 *   Logger.debug("started, pid=${getpid()}")
 *   Logger.error("Failed to initialize seccomp")
 */
@OptIn(ExperimentalForeignApi::class)
object Logger {
    /**
     * Log levels in ascending order of severity
     */
    enum class Level(
        val value: Int,
        val label: String,
    ) {
        TRACE(0, "TRACE"),
        DEBUG(1, "DEBUG"),
        INFO(2, "INFO"),
        WARN(3, "WARN"),
        ERROR(4, "ERROR"),
        ;

        companion object {
            fun fromString(s: String): Level? =
                when (s.uppercase()) {
                    "TRACE" -> TRACE
                    "DEBUG" -> DEBUG
                    "INFO" -> INFO
                    "WARN" -> WARN
                    "ERROR" -> ERROR
                    else -> null
                }
        }
    }

    /**
     * Log format types
     */
    enum class Format {
        TEXT,
        JSON,
    }

    // Current log level threshold
    private var currentLevel: Level = detectLogLevel()

    // Process context (main/intermediate/init)
    private var processContext: String = "main"

    // Log file handle (null means stderr only)
    private var logFile: CPointer<FILE>? = null

    // Log format (text or json)
    private var logFormat: Format = Format.TEXT

    // Debug log file handle (always writes to /tmp for troubleshooting)
    private var debugLogFile: CPointer<FILE>? = null

    init {
        // Open debug log file in /tmp for persistent logging
        // This helps troubleshoot issues where containerd cleans up log files

        // Temporarily set umask to 0 to ensure the log file is created with 0666 permissions
        // This allows both root and regular users to write to the file
        // Note: fchmod() fails with EPERM in user namespaces, so we use umask instead
        val oldUmask = umask(0u)
        debugLogFile = fopen("/tmp/kontainer-runtime-debug.log", "a")
        umask(oldUmask) // Restore original umask

        if (debugLogFile != null) {
            // Write a separator to mark new execution
            val timestamp = getCurrentTimestamp()
            fprintf(debugLogFile, "\n=== New execution at %s ===\n", timestamp)
            fflush(debugLogFile)
        } else {
            // Log fopen() failure to stderr for debugging
            val errNum = errno
            val uid = getuid()
            val gid = getgid()
            fprintf(
                stderr,
                "WARNING: Failed to open /tmp/kontainer-runtime-debug.log for appending (errno=%d, uid=%u, gid=%u)\n",
                errNum,
                uid,
                gid,
            )
            fflush(stderr)
        }
    }

    /**
     * Detect log level from environment variable KONTAINER_LOG_LEVEL
     * Falls back to build-time default (DEBUG for debug builds, INFO for release builds)
     */
    private fun detectLogLevel(): Level {
        val envVar = getenv("KONTAINER_LOG_LEVEL")?.toKString()

        if (envVar != null) {
            Level.fromString(envVar)?.let { return it }
        }

        // Default log level from build configuration
        return Level.fromString(BuildConfig.DEFAULT_LOG_LEVEL) ?: Level.INFO
    }

    /**
     * Set the process context for log messages
     * Typically: "main", "intermediate", or "init"
     */
    fun setContext(context: String) {
        processContext = context
    }

    /**
     * Set the log file path
     * Opens the file in append mode
     *
     * Creates parent directories if they don't exist
     *
     * @param path Path to log file (e.g., "/var/log/kontainer.log")
     */
    fun setLogFile(path: String) {
        // Close existing log file if open
        logFile?.let {
            fclose(it)
            logFile = null
        }

        // Extract parent directory from log file path and create it if needed
        // We use direct POSIX calls here to avoid Logger.debug() calls that would go to stderr
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash > 0) {
            val parentDir = path.substring(0, lastSlash)

            // Split path into components and create directories recursively
            val components = parentDir.trim('/').split('/')
            var currentPath = if (parentDir.startsWith("/")) "/" else ""

            for (component in components) {
                if (component.isEmpty()) continue

                currentPath += if (currentPath == "/") component else "/$component"

                if (mkdir(currentPath, 0x1EDu) != 0) { // 0x1ED = 0755 octal
                    val errNum = errno
                    if (errNum != EEXIST) {
                        // Directory creation failed (but not because it exists)
                        // Don't use Logger here to avoid circular dependency
                        // Continue anyway, fopen will fail if directory doesn't exist
                    }
                }
            }
        }

        // Open new log file in append mode
        val file = fopen(path, "a")
        if (file == null) {
            fprintf(stderr, "[ERROR] Failed to open log file: %s\n", path)
            perror("fopen")
            return
        }

        logFile = file
        // Don't log to stderr - it pollutes stdout when used with containerd
    }

    /**
     * Set the log format
     *
     * @param format "text" or "json"
     */
    fun setLogFormat(format: String) {
        logFormat =
            when (format.lowercase()) {
                "json" -> Format.JSON
                "text" -> Format.TEXT
                else -> {
                    fprintf(stderr, "[WARN] Unknown log format '%s', using 'text'\n", format)
                    Format.TEXT
                }
            }
    }

    /**
     * Set the log level programmatically
     *
     * @param level Log level to set
     */
    fun setLogLevel(level: Level) {
        currentLevel = level
    }

    /**
     * Get current timestamp in a readable format
     * Format: YYYY-MM-DD HH:MM:SS
     */
    private fun getCurrentTimestamp(): String {
        return memScoped {
            val now = alloc<time_tVar>()
            time(now.ptr)

            val timeinfo = localtime(now.ptr) ?: return "0000-00-00 00:00:00"

            val buffer = allocArray<ByteVar>(32)
            strftime(buffer, 32.convert(), "%Y-%m-%d %H:%M:%S", timeinfo)

            buffer.toKString()
        }
    }

    /**
     * Internal log function
     */
    private fun log(
        level: Level,
        message: String,
    ) {
        if (level.value >= currentLevel.value) {
            val timestamp = getCurrentTimestamp()

            when (logFormat) {
                Format.TEXT -> {
                    val formattedMessage = "[%s] [%s] [%s] %s\n"

                    // Log to stderr only if no log file is configured
                    // This prevents polluting stdout when used with containerd (--log option)
                    if (logFile == null) {
                        fprintf(
                            stderr,
                            formattedMessage,
                            timestamp,
                            level.label,
                            processContext,
                            message,
                        )
                    }

                    // Log to file if configured
                    logFile?.let { file ->
                        fprintf(
                            file,
                            formattedMessage,
                            timestamp,
                            level.label,
                            processContext,
                            message,
                        )
                        fflush(file) // Ensure immediate write
                    }

                    // Always log to debug file for troubleshooting
                    debugLogFile?.let { file ->
                        fprintf(
                            file,
                            formattedMessage,
                            timestamp,
                            level.label,
                            processContext,
                            message,
                        )
                        fflush(file) // Ensure immediate write
                    }
                }

                Format.JSON -> {
                    // Escape quotes in message for JSON
                    val escapedMessage = message.replace("\"", "\\\"").replace("\n", "\\n")
                    val jsonMessage =
                        "{\"timestamp\":\"$timestamp\",\"level\":\"${level.label}\"," +
                            "\"context\":\"$processContext\",\"message\":\"$escapedMessage\"}\n"

                    // Log to stderr only if no log file is configured
                    // This prevents polluting stdout when used with containerd (--log option)
                    if (logFile == null) {
                        fprintf(stderr, "%s", jsonMessage)
                    }

                    // Log to file if configured
                    logFile?.let { file ->
                        fprintf(file, "%s", jsonMessage)
                        fflush(file)
                    }

                    // Always log to debug file for troubleshooting
                    debugLogFile?.let { file ->
                        fprintf(file, "%s", jsonMessage)
                        fflush(file)
                    }
                }
            }
        }
    }

    /**
     * Log at TRACE level (most verbose)
     */
    fun trace(message: String) {
        log(Level.TRACE, message)
    }

    /**
     * Log at DEBUG level
     * Use for detailed diagnostic information
     */
    fun debug(message: String) {
        log(Level.DEBUG, message)
    }

    /**
     * Log at INFO level
     * Use for general informational messages
     */
    fun info(message: String) {
        log(Level.INFO, message)
    }

    /**
     * Log at WARN level
     * Use for warning messages (non-fatal issues)
     */
    fun warn(message: String) {
        log(Level.WARN, message)
    }

    /**
     * Log at ERROR level
     * Use for error messages (failures)
     */
    fun error(message: String) {
        log(Level.ERROR, message)
    }
}
