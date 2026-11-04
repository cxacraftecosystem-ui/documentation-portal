package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class PayloadField(
    val path: String,
    val label: String,
    val value: String,
    val kind: String,
    val highlighted: Boolean
)

private val payloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val NOT_EDITABLE = setOf(
    "id", "recordedAt", "formContract", "status", "workshopId", "craftId", "artisanId", "productId",
    "toolId", "questionnaireId", "measurementMethods", "location", "createdById", "idempotencyKey"
)

private val KEY_WORDS = mapOf(
    "aadhaarNumber" to "Aadhaar number",
    "pehchanCardNumber" to "Pehchan card number",
    "pehchanCardAvailable" to "Has a Pehchan card",
    "aadhaarNotShared" to "Aadhaar not shared by artisan",
    "dateOfBirth" to "Date of birth",
    "experienceYears" to "Experience (years)",
    "toolkitName" to "Toolkit name",
    "productName" to "Product name",
    "craftName" to "Craft name",
    "artisanName" to "Artisan name",
    "lengthInches" to "Length (inches)",
    "breadthInches" to "Breadth (inches)",
    "heightInches" to "Height (inches)",
    "answerText" to "Answer",
)

fun payloadFieldLabel(path: String): String {
    val parts = path.split('.')
    val key = parts.last()
    val words = KEY_WORDS[key] ?: key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .lowercase()
        .replaceFirstChar { it.uppercase() }
    val index = parts.dropLast(1).lastOrNull()?.toIntOrNull()
    return if (index != null) "$words ${index + 1}" else words
}

fun refusalFieldPaths(detail: JsonElement?): List<String> = when (detail) {
    is JsonArray -> detail.mapNotNull { entry ->
        val loc = (entry as? JsonObject)?.get("loc") as? JsonArray ?: return@mapNotNull null
        loc.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .dropWhile { it == "body" }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(".")
    }.distinct()
    is JsonObject -> listOfNotNull((detail["field"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() })
    else -> emptyList()
}

private fun JsonElement.at(path: List<String>): JsonElement? {
    var node: JsonElement? = this
    for (part in path) {
        node = when (val current = node) {
            is JsonObject -> current[part]
            is JsonArray -> part.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        }
        if (node == null) return null
    }
    return node
}

private fun kindOf(value: JsonElement?): String? = when {
    value == null || value is JsonNull -> "string"
    value !is JsonPrimitive -> null
    value.isString -> "string"
    value.booleanOrNull != null -> "boolean"
    value.longOrNull != null || value.doubleOrNull != null -> "number"
    else -> "string"
}

private fun textOf(value: JsonElement?): String =
    if (value == null || value is JsonNull) "" else (value as? JsonPrimitive)?.contentOrNull.orEmpty()

fun editablePayloadFields(payload: String, highlighted: List<String>): List<PayloadField> {
    val root = runCatching { payloadJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return emptyList()
    val named = highlighted.filter { it.split('.').last() !in NOT_EDITABLE }.mapNotNull { path ->
        val value = root.at(path.split('.'))
        val kind = kindOf(value) ?: return@mapNotNull null
        PayloadField(path, payloadFieldLabel(path), textOf(value), kind, highlighted = true)
    }
    val rest = root.entries
        .filter { (key, value) -> key !in NOT_EDITABLE && named.none { it.path == key } && (value is JsonPrimitive || value is JsonNull) }
        .mapNotNull { (key, value) ->
            val kind = kindOf(value) ?: return@mapNotNull null
            PayloadField(key, payloadFieldLabel(key), textOf(value), kind, highlighted = false)
        }
    return named + rest
}

private fun encodeEdit(kind: String, text: String, original: JsonElement?): JsonElement {
    val trimmed = text.trim()
    return when (kind) {
        "boolean" -> JsonPrimitive(trimmed.equals("true", ignoreCase = true) || trimmed.equals("yes", ignoreCase = true))
        "number" -> trimmed.toLongOrNull()?.let { JsonPrimitive(it) }
            ?: trimmed.toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: if (trimmed.isEmpty()) JsonNull else JsonPrimitive(trimmed)
        else -> if (trimmed.isEmpty() && (original == null || original is JsonNull)) JsonNull else JsonPrimitive(text)
    }
}

private fun JsonElement.with(path: List<String>, value: JsonElement): JsonElement {
    if (path.isEmpty()) return value
    val head = path.first()
    return when (this) {
        is JsonObject -> JsonObject(LinkedHashMap(this).apply { put(head, (this@with[head] ?: JsonNull).with(path.drop(1), value)) })
        is JsonArray -> {
            val index = head.toIntOrNull() ?: return this
            if (index !in indices) return this
            JsonArray(mapIndexed { i, element -> if (i == index) element.with(path.drop(1), value) else element })
        }
        else -> this
    }
}

fun applyPayloadEdits(payload: String, fields: List<PayloadField>, edits: Map<String, String>): String {
    var root: JsonElement = payloadJson.parseToJsonElement(payload)
    for (field in fields) {
        val text = edits[field.path] ?: continue
        if (text == field.value) continue
        val path = field.path.split('.')
        root = root.with(path, encodeEdit(field.kind, text, root.at(path)))
    }
    return payloadJson.encodeToString(JsonElement.serializer(), root)
}

fun PendingEntry.isFixable(): Boolean = when {
    failure == null || needsReview -> false
    createdId != null -> false
    kind == OutboxKind.SEQUENCE -> steps.getOrNull(completedSteps)?.payloadJson?.isNotBlank() == true
    kind == OutboxKind.ATTACH -> false
    else -> payloadJson.isNotBlank()
}

fun PendingEntry.fixablePayload(): String =
    if (kind == OutboxKind.SEQUENCE) steps.getOrNull(completedSteps)?.payloadJson.orEmpty() else payloadJson

fun PendingEntry.offersExistingRecord(): Boolean =
    failure != null && createdId == null && kind == OutboxKind.CREATE && !existingRecordId.isNullOrBlank()

fun outboxDiscardSentence(label: String, files: Int, clash: Boolean): String {
    val filesPart = when (files) {
        0 -> ""
        1 -> " and its 1 photo or recording"
        else -> " and its $files photos and recordings"
    }
    val clashPart = if (clash) " The record already on the server is not touched." else ""
    return "“$label”$filesPart will be deleted from this phone. This can't be undone.$clashPart"
}

fun useExistingSentence(label: String, existing: String?, files: Int): String {
    val who = existing?.takeIf { it.isNotBlank() } ?: "the existing artisan"
    return if (files > 0) {
        "This copy of “$label” was dropped. Its ${if (files == 1) "file goes" else "$files files go"} to $who."
    } else {
        "This copy of “$label” was dropped. $who stays as recorded."
    }
}
