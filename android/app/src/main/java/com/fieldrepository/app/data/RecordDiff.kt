package com.fieldrepository.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val RECORD_EDIT_ALWAYS_SENT: Set<String> = setOf("formContract")

val RECORD_EDIT_NEVER_DIFFED: Set<String> = setOf(
    "recordedTimezone",
    "recordedAt",
    "clientKey",
    "duplicateAcknowledged",
)

private val DEVICE_ONLY_KEYS: Set<String> = setOf("capturedAt", "altitude", "accuracy")

fun recordEditChangedKeys(request: JsonObject, record: JsonObject): Set<String> =
    request.keys.filterTo(LinkedHashSet()) { key ->
        if (key in RECORD_EDIT_NEVER_DIFFED) return@filterTo false
        val mine = request[key]
        if (key !in record) return@filterTo !isBlankRecordValue(mine)
        !sameRecordValue(key, mine, record[key])
    }

private val DIFF_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val WIRE_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

val ARTISAN_CLEARABLE_COLUMNS: Set<String> = setOf(
    "localName", "gender", "phone", "email", "address", "notes", "dateOfBirth", "craftStartDate",
    "experienceYears", "experienceMonths", "dos", "donts",
)

fun <T> recordBase(serializer: KSerializer<T>, record: T?): JsonObject? =
    record?.let { runCatching { DIFF_JSON.encodeToJsonElement(serializer, it) as JsonObject }.getOrNull() }

fun <T> editChangedKeys(serializer: KSerializer<T>, request: T, base: JsonObject?): Set<String>? {
    if (base == null) return null
    val typed = DIFF_JSON.encodeToJsonElement(serializer, request) as? JsonObject ?: return null
    return recordEditChangedKeys(typed, base)
}

fun <T> editPatchBody(
    serializer: KSerializer<T>,
    request: T,
    changed: Set<String>?,
    clearable: Set<String> = emptySet(),
): JsonObject {
    val wire = (WIRE_JSON.encodeToJsonElement(serializer, request) as JsonObject).toMutableMap()
    if (changed == null) return JsonObject(wire)
    for (key in clearable) if (key in changed && key !in wire) wire[key] = JsonNull
    return restrictEditBody(JsonObject(wire), changed)
}

fun restrictEditBody(body: JsonObject, changed: Set<String>?): JsonObject {
    if (changed == null) return body
    return JsonObject(body.filterKeys { it in changed || it in RECORD_EDIT_ALWAYS_SENT })
}

fun isBlankRecordValue(value: JsonElement?): Boolean = when (value) {
    null, JsonNull -> true
    is JsonPrimitive -> value.isString && value.content.isBlank()
    is JsonArray -> value.isEmpty()
    else -> false
}

fun sameRecordValue(key: String, a: JsonElement?, b: JsonElement?): Boolean {
    if (isBlankRecordValue(a) && isBlankRecordValue(b)) return true
    if (isBlankRecordValue(a) != isBlankRecordValue(b)) return false
    if (a is JsonPrimitive && b is JsonPrimitive) return samePrimitive(key, a, b)
    if (a is JsonObject && b is JsonObject) {
        return a.all { (k, v) -> k in DEVICE_ONLY_KEYS || isBlankRecordValue(v) || sameRecordValue(k, v, b[k]) }
    }
    if (a is JsonArray && b is JsonArray) {
        if (a.size != b.size) return false
        if (a.all { it is JsonPrimitive } && b.all { it is JsonPrimitive }) {
            return a.map { (it as JsonPrimitive).content.trim() }.sorted() ==
                b.map { (it as JsonPrimitive).content.trim() }.sorted()
        }
        return a.indices.all { sameRecordValue(key, a[it], b[it]) }
    }
    return a == b
}

private fun samePrimitive(key: String, a: JsonPrimitive, b: JsonPrimitive): Boolean {
    val x = a.content.trim()
    val y = b.content.trim()
    if (x == y) return true
    val nx = x.toDoubleOrNull()
    val ny = y.toDoubleOrNull()
    if (nx != null && ny != null && (!a.isString || !b.isString)) return nx == ny
    if (key.contains("phone", ignoreCase = true) && samePhone(x, y)) return true
    if (sameMoment(x, y)) return true
    return a.isString && b.isString && sameText(x, y)
}

private fun sameText(a: String, b: String): Boolean = textLines(a) == textLines(b)

private fun textLines(value: String): List<String> =
    value.lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }

fun samePhone(a: String, b: String): Boolean {
    val x = a.filter { it.isDigit() }
    val y = b.filter { it.isDigit() }
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    val (long, short) = if (x.length >= y.length) x to y else y to x
    return short.length >= 10 && long.length - short.length <= 3 && long.endsWith(short)
}

private val PLAIN_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun sameMoment(a: String, b: String): Boolean {
    val aDate = PLAIN_DATE.matches(a)
    val bDate = PLAIN_DATE.matches(b)
    if (aDate && bDate) return a == b
    if (aDate) return instantDate(b)?.let { it == parseDate(a) } ?: false
    if (bDate) return instantDate(a)?.let { it == parseDate(b) } ?: false
    val x = parseInstant(a) ?: return false
    val y = parseInstant(b) ?: return false
    return x == y
}

private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

private fun parseInstant(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()

private fun instantDate(value: String): LocalDate? =
    runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate() }.getOrNull()

fun JsonObject.withLinkIds(vararg lists: Pair<String, List<String>>): JsonObject =
    JsonObject(this + lists.associate { (key, ids) -> key to JsonArray(ids.map { JsonPrimitive(it) }) })

fun narrowInterviewUpdate(
    request: QuestionnaireInterviewUpdateRequest,
    record: QuestionnaireInterviewDetailDto,
): QuestionnaireInterviewUpdateRequest {
    fun keep(key: String, mine: String?, theirs: String?): String? {
        if (mine == null) return null
        val stored: JsonElement = if (theirs == null) JsonNull else JsonPrimitive(theirs)
        return if (sameRecordValue(key, JsonPrimitive(mine), stored)) null else mine
    }
    return request.copy(
        title = keep("title", request.title, record.title),
        place = keep("place", request.place, record.place),
        language = keep("language", request.language, record.language),
        notes = keep("notes", request.notes, record.notes),
        status = keep("status", request.status, record.status),
        workshopId = keep("workshopId", request.workshopId, record.workshopId),
    )
}
