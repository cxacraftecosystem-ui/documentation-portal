package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

val MEASUREMENT_DIMENSIONS: Set<String> = setOf("lengthInches", "breadthInches", "heightInches")

const val TECHNIQUE_SCALE = "SCALE"

const val TECHNIQUE_RECTIFIED = "RECTIFIED"

private const val METHOD_PHOTO_GEOMETRY = "PHOTO_GEOMETRY"
private const val METHOD_VISION_MODEL = "VISION_MODEL"

fun geometryMarker(technique: String?): JsonObject = buildJsonObject {
    put("method", JsonPrimitive(METHOD_PHOTO_GEOMETRY))
    if (technique == TECHNIQUE_SCALE || technique == TECHNIQUE_RECTIFIED) {
        put("technique", JsonPrimitive(technique))
    }
}

fun visionMarker(fromServer: JsonObject?): JsonObject =
    fromServer ?: buildJsonObject { put("method", JsonPrimitive(METHOD_VISION_MODEL)) }

data class MeasurementReading(
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val valueInches: Double? = null,
    val marker: JsonObject? = null,
)

private fun storableNumber(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val parsed = trimmed.toDoubleOrNull() ?: return false
    return parsed.isFinite()
}

private data class AcceptedMeasurement(val acceptedText: String, val marker: JsonObject)

class MeasurementMarkers {
    private val accepted = mutableMapOf<String, AcceptedMeasurement>()

    fun accept(column: String, text: String, marker: JsonObject) {
        if (column !in MEASUREMENT_DIMENSIONS) return
        if (!storableNumber(text)) return
        accepted[column] = AcceptedMeasurement(text, marker)
    }

    fun forget(column: String) {
        accepted.remove(column)
    }

    fun body(current: Map<String, String>): JsonObject? {
        val live = accepted.filter { (column, entry) ->
            val text = current[column].orEmpty()
            storableNumber(text) && text == entry.acceptedText
        }
        if (live.isEmpty()) return null
        return buildJsonObject { live.forEach { (column, entry) -> put(column, entry.marker) } }
    }
}
