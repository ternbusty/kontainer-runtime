package utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object JsonCodec {
    /**
     * Base JSON configuration optimized for robustness and compatibility
     *
     * - ignoreUnknownKeys: Allows forward compatibility with newer JSON schemas
     * - isLenient: Tolerates relaxed JSON syntax (e.g., unquoted keys)
     * - coerceInputValues: Handles type mismatches gracefully
     */
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /**
     * Encode a value to JSON string
     *
     * @param value Value to encode
     * @param prettyPrint If true, format output for human readability (default: false)
     * @return JSON string representation
     */
    internal inline fun <reified T> encode(
        value: T,
        prettyPrint: Boolean = false,
    ): String {
        val encoder =
            if (prettyPrint) {
                Json(json) { this.prettyPrint = true }
            } else {
                json
            }
        return encoder.encodeToString(serializer(), value)
    }

    /**
     * Decode a JSON string to typed value
     *
     * @param text JSON string to decode
     * @return Decoded value of type T
     */
    internal inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)

    /**
     * Write a value to a JSON file
     *
     * @param path File path to write to
     * @param value Value to serialize and write
     * @param prettyPrint If true, format output for human readability (default: false)
     * @throws Exception if file write fails
     */
    internal inline fun <reified T> writeToFile(
        path: String,
        value: T,
        prettyPrint: Boolean = false,
    ) {
        writeTextFile(path, encode(value, prettyPrint))
    }

    /**
     * Load a value from a JSON file
     *
     * @param path File path to read from
     * @return Decoded value of type T
     * @throws Exception if file read or JSON parsing fails
     */
    internal inline fun <reified T> loadFromFile(path: String): T {
        val jsonContent = readTextFile(path)
        return decode<T>(jsonContent)
    }
}
