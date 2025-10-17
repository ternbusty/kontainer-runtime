package config

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logger.Logger
import utils.readJsonFile
import utils.writeJsonFile

/**
 * Internal configuration for Kontainer Runtime
 *
 * This stores runtime-specific configuration
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

    val jsonString = ConfigCodec.encode(config)
    writeJsonFile(configPath, jsonString)

    Logger.debug("saved kontainer config")
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

    return readJsonFile(configPath, ConfigCodec::decode)
}

/**
 * JSON codec for KontainerConfig serialization/deserialization
 *
 * Provides centralized JSON configuration for encoding and decoding internal config.
 */
object ConfigCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /**
     * Encode config to JSON string
     *
     * @param config KontainerConfig to encode
     * @return JSON string
     */
    fun encode(config: KontainerConfig): String {
        return json.encodeToString(config)
    }

    /**
     * Decode JSON string to KontainerConfig
     *
     * @param text JSON string
     * @return KontainerConfig object
     */
    fun decode(text: String): KontainerConfig {
        return json.decodeFromString(text)
    }
}
