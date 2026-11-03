package com.fieldrepository.app.data

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

internal fun statusSentence(code: Int): String? = when (code) {
    401 -> "Session expired. Sign in again."
    403 -> "You don't have access to this. Ask an administrator."
    404 -> "Not found — it may have been deleted."
    408, 429 -> "Busy right now. Try again in a moment."
    in 500..599 -> "Something went wrong. Try again in a moment."
    else -> null
}

private val BARE_REASON_PHRASES = setOf(
    "not found", "method not allowed", "internal server error", "bad gateway", "service unavailable",
    "gateway timeout", "gateway time-out", "forbidden", "unauthorized", "bad request", "too many requests",
    "conflict",
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
