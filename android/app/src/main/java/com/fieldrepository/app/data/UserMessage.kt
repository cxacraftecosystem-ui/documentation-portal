package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

fun Throwable.userMessage(fallback: String): String {
    val said = apiFailure(fallback).message
    if (this is HttpException) {
        if (said == fallback || said.isBareReasonPhrase()) return statusSentence(code()) ?: fallback
        return said
    }
    if (said == fallback || said == CONNECTION_FAILED_SENTENCE) return said
    return if (said.readsAsSentenceForPeople()) said else fallback
}

internal const val TOO_LARGE_SENTENCE = "This is too large to upload. Choose a smaller file or fewer photos."

internal fun statusSentence(code: Int): String? = when (code) {
    400 -> "This couldn't be saved. Check the details and try again."
    401 -> "Session expired. Sign in again."
    403 -> "You don't have access to this. Ask an administrator."
    404 -> "Not found — it may have been deleted."
    408, 429 -> "Busy right now. Try again in a moment."
    409 -> "This clashes with something already saved. Refresh and try again."
    413 -> TOO_LARGE_SENTENCE
    422 -> "Some details weren't accepted. Check the form and try again."
    in 500..599 -> "Something went wrong. Try again in a moment."
    else -> null
}

private val errorSentenceJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun errorBodyObject(raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    return runCatching { errorSentenceJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
}

fun serverRefusalSentence(body: JsonObject?): String? {
    if (body == null) return null
    val top = (body["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    if (top != null) return top
    return body["detail"]?.let { detailSentence(it) }
}

fun detailSentence(detail: JsonElement): String? = when (detail) {
    is JsonPrimitive -> detail.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    is JsonArray -> detail
        .mapNotNull { entry -> (entry as? JsonObject)?.validationLine() }
        .distinct()
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
    else -> null
}

fun httpRefusalSentence(code: Int, raw: String?, fallback: String): String =
    serverRefusalSentence(errorBodyObject(raw))?.takeUnless { it.isBareReasonPhrase() }
        ?: statusSentence(code)
        ?: fallback

private const val VALUE_ERROR_PREFIX = "Value error, "

private fun JsonObject.validationLine(): String? {
    val raw = (this["msg"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val said = raw.removePrefix(VALUE_ERROR_PREFIX).trim().takeIf { it.isNotEmpty() } ?: return null
    if (containsKey("label") || raw.startsWith(VALUE_ERROR_PREFIX)) return said
    val field = fieldLabelOf(this["loc"]) ?: return said
    val sentence = if (said.last() in ".!?") said else "$said."
    return "$field: $sentence"
}

private val LOC_ROOTS = setOf("body", "query", "path", "header", "cookie")

internal fun fieldLabelOf(loc: JsonElement?): String? {
    val parts = (loc as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
    val path = parts.dropWhile { it in LOC_ROOTS }
    val keyAt = path.indexOfLast { it.toIntOrNull() == null }
    if (keyAt < 0) return null
    val words = path[keyAt]
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .trim()
        .lowercase()
        .replaceFirstChar { it.uppercase() }
        .takeIf { it.isNotEmpty() }
        ?: return null
    val index = path.getOrNull(keyAt + 1)?.toIntOrNull()
    return if (index != null) "$words ${index + 1}" else words
}

private val BARE_REASON_PHRASES = setOf(
    "not found", "method not allowed", "internal server error", "bad gateway", "service unavailable",
    "gateway timeout", "gateway time-out", "forbidden", "unauthorized", "bad request", "too many requests",
    "conflict", "payload too large", "request entity too large", "content too large", "unprocessable entity",
    "unprocessable content",
)

internal fun String.isBareReasonPhrase(): Boolean =
    trim().trimEnd('.').lowercase() in BARE_REASON_PHRASES || trim().startsWith("HTTP ")

private val INTERNAL_MARKERS = listOf(
    "Exception", "exception", "java.", "kotlin.", "android.", "okhttp", "NaN", "null", "=", "://",
    "errno", "EACCES", "ENOENT", "HTTP ", "resolve host", "failed to connect", "Failed to connect",
)

internal fun String.readsAsSentenceForPeople(): Boolean {
    val text = trim()
    if (text.length < 8 || !text.first().isUpperCase() || ' ' !in text) return false
    if (text.startsWith("Permission Denial")) return false
    return INTERNAL_MARKERS.none { it in text }
}
