package config

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logger.Logger
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/**
 * Internal configuration for Kontainer Runtime
 *
 * Similar to youki's YoukiConfig, this stores runtime-specific configuration
 * that is independent of the OCI bundle. This allows operations like delete
 * to work even if the bundle has been moved or deleted.
 */

private const val KONTAINER_CONFIG_NAME = "kontainer_config.json"
private const val CONTAINER_ROOT = "/run/kontainer"

@Serializable
data class KontainerConfig(
    @SerialName("cgroup_path") val cgroupPath: String?
)

/**
 * Save configuration to the container directory
 *
 * @throws Exception if save fails
 */
@OptIn(ExperimentalForeignApi::class)
fun saveKontainerConfig(config: KontainerConfig, containerId: String) {
    val containerDir = "$CONTAINER_ROOT/$containerId"
    val configPath = "$containerDir/$KONTAINER_CONFIG_NAME"

    Logger.debug("saving kontainer config to $configPath")

    val json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    val jsonString = json.encodeToString(config)

    val file = fopen(configPath, "w")
        ?: throw Exception("Failed to open config file for writing: $configPath")

    try {
        // Write JSON string
        val bytes = jsonString.encodeToByteArray()
        val written = platform.posix.fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), file)
        if (written != bytes.size.toULong()) {
            throw Exception("Failed to write complete config file")
        }
        Logger.debug("saved kontainer config")
    } finally {
        fclose(file)
    }
}

/**
 * Load configuration from the container directory
 *
 * @param containerId Container ID
 * @return Loaded configuration
 * @throws Exception if load fails or config doesn't exist
 */
@OptIn(ExperimentalForeignApi::class)
fun loadKontainerConfig(containerId: String): KontainerConfig {
    val containerDir = "$CONTAINER_ROOT/$containerId"
    val configPath = "$containerDir/$KONTAINER_CONFIG_NAME"

    Logger.debug("loading kontainer config from $configPath")

    val file = fopen(configPath, "r")
        ?: throw Exception("Failed to open config file: $configPath")

    try {
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val bytesRead = fread(buffer, 1u, 4095u, file)
            if (bytesRead == 0UL) {
                throw Exception("Config file is empty: $configPath")
            }

            buffer[bytesRead.toInt()] = 0.toByte()
            val jsonString = buffer.toKString()

            val json = Json {
                ignoreUnknownKeys = true
            }

            return json.decodeFromString<KontainerConfig>(jsonString)
        }
    } finally {
        fclose(file)
    }
}
