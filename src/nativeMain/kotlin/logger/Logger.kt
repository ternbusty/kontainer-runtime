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

    // Current log level threshold
    private var currentLevel: Level = detectLogLevel()

    // Process context (main/intermediate/init)
    private var processContext: String = "main"

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
            fprintf(
                stderr,
                "[%s] [%s] [%s] %s\n",
                timestamp,
                level.label,
                processContext,
                message,
            )
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
