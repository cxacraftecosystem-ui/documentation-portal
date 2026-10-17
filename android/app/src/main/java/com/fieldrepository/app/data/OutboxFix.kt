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

/**
 * "FIX AND SEND" FOR A SAVE THE SERVER REFUSED (B1, 2026-10-17).
 *
 * A refused outbox entry used to have one door, "Try again", which sent the identical bytes to the
 * identical refusal. The researcher's only way out was to throw the entry away and type the record
 * again from memory — twenty fields and the photographs, a day after the artisan had gone home. Now
 * the entry opens in an editor with the field the server named highlighted, and is sent again with
 * the correction.
 *
 * WHY AN EDITOR OF THE QUEUED BODY AND NOT THE RECORD'S OWN FORM. One or two keys in the body are
 * wrong; every other answer in it was given by somebody standing in front of the artisan. Re-opening
 * a two-hundred-field form would re-derive a dozen of them from today's state (the workshop default,
 * the carried artisan, a fresh `recordedAt`) and send a record that is not the one that was taken.
 * The editor changes the keys a person changes and re-serialises the rest exactly as queued.
 *
 * Everything here is pure so a JVM test pins it; the dialog is `ui/OutboxTray.kt`.
 */

/** One editable value in a queued body. [path] is dotted (`answers.3.answerText`). */
data class PayloadField(
    val path: String,
    val label: String,
    val value: String,
    /** "string", "number" or "boolean" — what the edited text is written back as. */
    val kind: String,
    /** Named by the server's refusal: drawn first, marked, and opened ready to edit. */
    val highlighted: Boolean
)

private val payloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Keys a person must never edit by hand in a queued body: ids, provenance and machine fields. */
private val NOT_EDITABLE = setOf(
    "id", "recordedAt", "formContract", "status", "workshopId", "craftId", "artisanId", "productId",
    "toolId", "questionnaireId", "measurementMethods", "location", "createdById", "idempotencyKey"
)

/** Plain words for the keys a researcher meets most often; anything else is split from camelCase. */
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

/** "aadhaarNumber" → "Aadhaar number"; "answers.3.answerText" → "Answer 4". */
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

/**
 * The field paths a refusal names. A 422 carries Pydantic's `loc` (`["body", "aadhaarNumber"]`); an
 * object refusal carries `field` (the artisan identity 409). The leading `body` is the request, not
 * a field, and a refusal of the whole body names nothing.
 */
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

/**
 * What the editor shows for [payload]: every field the refusal named (when it points at a value),
 * then every other plain top-level value a person could have typed. Lists and nested objects other
 * than the named paths stay as queued.
 */
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
        // A box that held nothing and still holds nothing stays absent rather than becoming "".
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

/**
 * [payload] with [edits] (path → typed text) written in, each as the type the field already had.
 * Keys nobody edited are carried through exactly as queued.
 */
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

/** May this refused entry be opened in the editor? Only while its body has not reached the server. */
fun PendingEntry.isFixable(): Boolean = when {
    failure == null || needsReview -> false
    createdId != null -> false
    kind == OutboxKind.SEQUENCE -> steps.getOrNull(completedSteps)?.payloadJson?.isNotBlank() == true
    kind == OutboxKind.ATTACH -> false
    else -> payloadJson.isNotBlank()
}

/** The body the editor works on: the refused step of a sequence, otherwise the entry's own. */
fun PendingEntry.fixablePayload(): String =
    if (kind == OutboxKind.SEQUENCE) steps.getOrNull(completedSteps)?.payloadJson.orEmpty() else payloadJson

/** "Use existing artisan" applies to a refused create that clashed with a named artisan. */
fun PendingEntry.offersExistingRecord(): Boolean =
    failure != null && createdId == null && kind == OutboxKind.CREATE && !existingRecordId.isNullOrBlank()

/**
 * The confirmation before Discard. It names what goes — the copy on this phone and its files —
 * and, on a clash, that the record already on the server is not touched.
 */
fun outboxDiscardSentence(label: String, files: Int, clash: Boolean): String {
    val filesPart = when (files) {
        0 -> ""
        1 -> " and its 1 photo or recording"
        else -> " and its $files photos and recordings"
    }
    val clashPart = if (clash) " The record already on the server is not touched." else ""
    return "“$label”$filesPart will be deleted from this phone. This can't be undone.$clashPart"
}

/** What "Use existing artisan" says it did. */
fun useExistingSentence(label: String, existing: String?, files: Int): String {
    val who = existing?.takeIf { it.isNotBlank() } ?: "the existing artisan"
    return if (files > 0) {
        "This copy of “$label” was dropped. Its ${if (files == 1) "file goes" else "$files files go"} to $who."
    } else {
        "This copy of “$label” was dropped. $who stays as recorded."
    }
}
