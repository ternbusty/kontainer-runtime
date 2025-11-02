package utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Common JSON codecs for different use cases
 */
object JsonCodec {
    object Spec {
        internal val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }

        internal inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)

        internal inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)
    }

    object PrettyPrint {
        internal val json =
            Json {
                prettyPrint = true
                encodeDefaults = false
                ignoreUnknownKeys = true
            }

        internal inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)

        internal inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)
    }

    object Compact {
        internal val json =
            Json {
                prettyPrint = false
                encodeDefaults = false
                ignoreUnknownKeys = true
            }

        internal inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)

        internal inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)
    }

    object Message {
        internal val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        internal inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)

        internal inline fun <reified T> decode(text: String): T = json.decodeFromString(serializer(), text)
    }
}
