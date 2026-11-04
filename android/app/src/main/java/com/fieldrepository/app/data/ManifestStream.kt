package com.fieldrepository.app.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.Reader

internal object ManifestStream {

    const val NDJSON_MEDIA_TYPE = "application/x-ndjson"

    const val TOTAL_HEADER = "X-Dataset-Total"
    const val MEDIA_HEADER = "X-Dataset-Media"
    const val TRUNCATED_HEADER = "X-Dataset-Truncated"

    fun isNdjson(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase() == NDJSON_MEDIA_TYPE

    fun count(header: String?): Int = header?.trim()?.toIntOrNull() ?: -1

    fun flag(header: String?): Boolean = header?.trim().equals("true", ignoreCase = true)
}

internal class ManifestLines<T>(
    private val reader: Reader,
    private val json: Json,
    private val deserializer: DeserializationStrategy<T>
) {
    var unreadable: Int = 0
        private set

    fun entries(): Sequence<T> = reader.buffered().lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val entry = runCatching { json.decodeFromString(deserializer, line) }.getOrNull()
        if (entry == null) unreadable++
        entry
    }
}

internal data class ManifestOutcome(
    val total: Int,
    val truncated: Boolean,
    val unreadable: Int
)
