package config

import kotlinx.cinterop.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import logger.Logger
import utils.JsonCodec

/**
 * Internal configuration for Kontainer Runtime
 *
 * This stores runtime-specific configuration
 * that is independent of the OCI bundle. This allows operations like delete
 * to work even if the bundle has been moved or deleted.
 */

private const val KONTAINER_CONFIG_NAME = "kontainer_config.json"

@Serializable
data class KontainerConfig(
    @SerialName("cgroup_path") val cgroupPath: String?,
)

/**
 * Save configuration to the container directory
 *
 * @param config Configuration to save
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @throws Exception if save fails
 */
@OptIn(ExperimentalForeignApi::class)
fun saveKontainerConfig(
    config: KontainerConfig,
    rootPath: String,
    containerId: String,
) {
    val containerDir = "$rootPath/$containerId"
    val configPath = "$containerDir/$KONTAINER_CONFIG_NAME"

    Logger.debug("saving kontainer config to $configPath")

    JsonCodec.writeToFile(configPath, config, prettyPrint = true)

    Logger.debug("saved kontainer config")
}

/**
 * Load configuration from the container directory
 *
 * @param rootPath Root directory for container state
 * @param containerId Container ID
 * @return Loaded configuration
 * @throws Exception if load fails or config doesn't exist
 */
@OptIn(ExperimentalForeignApi::class)
fun loadKontainerConfig(
    rootPath: String,
    containerId: String,
): KontainerConfig {
    val containerDir = "$rootPath/$containerId"
    val configPath = "$containerDir/$KONTAINER_CONFIG_NAME"

    Logger.debug("loading kontainer config from $configPath")

    return JsonCodec.loadFromFile<KontainerConfig>(configPath)
}
