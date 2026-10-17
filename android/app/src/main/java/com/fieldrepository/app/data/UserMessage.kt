package com.fieldrepository.app.data

import retrofit2.HttpException

/**
 * THE ONE PLACE A FAILURE BECOMES WORDS ON SCREEN.
 *
 * Every screen used to write `it.message ?: "Unable to …"`, which hands the reader whatever the
 * throwable happened to carry: Retrofit's "HTTP 403 Forbidden", the platform's "Unable to resolve
 * host api.example.org", or an engine's own words. None of those tells a researcher in a village
 * what to do next (UX audit 2026-10-16, DC-A02).
 *
 * Built on [apiFailure], so a sentence the SERVER wrote for people (an Aadhaar clash, a late
 * submission) still reaches them verbatim. What changes is everything else:
 *
 * - no answer at all (no signal, DNS, timeout) → [CONNECTION_FAILED_SENTENCE];
 * - an answer with nothing readable in it, or only a bare reason phrase ("Not Found") → a plain
 *   sentence chosen by the status code;
 * - an exception thrown on the device → its message only when it already reads as a sentence
 *   written for a person, otherwise the caller's [fallback].
 *
 * The designer portal carries this file under the same name, built on its `apiRefusal`; keep the
 * two copyable. [apiErrorMessage] routes here, so the older call sites share the one mapper.
 * Reads the buffered error body, like [apiFailure]: call it once per failure.
 * `UserMessageSourceTest` refuses `.message ?: "` in the UI sources so a new screen cannot go back.
 */
fun Throwable.userMessage(fallback: String): String {
    val said = apiFailure(fallback).message
    if (this is HttpException) {
        if (said == fallback || said.isBareReasonPhrase()) return statusSentence(code()) ?: fallback
        return said
    }
    if (said == fallback || said == CONNECTION_FAILED_SENTENCE) return said
    return if (said.readsAsSentenceForPeople()) said else fallback
}

/** A plain sentence for a status code whose answer carried nothing readable, or null to keep the caller's. */
internal fun statusSentence(code: Int): String? = when (code) {
    401 -> "Session expired. Sign in again."
    403 -> "You don't have access to this. Ask an administrator."
    404 -> "Not found — it may have been deleted."
    408, 429 -> "Busy right now. Try again in a moment."
    in 500..599 -> "Something went wrong. Try again in a moment."
    else -> null
}

/** HTTP's own reason phrases, which FastAPI echoes as `detail` for its built-in 404 and 405. */
private val BARE_REASON_PHRASES = setOf(
    "not found", "method not allowed", "internal server error", "bad gateway", "service unavailable",
    "gateway timeout", "gateway time-out", "forbidden", "unauthorized", "bad request", "too many requests",
    "conflict",
)

internal fun String.isBareReasonPhrase(): Boolean =
    trim().trimEnd('.').lowercase() in BARE_REASON_PHRASES || trim().startsWith("HTTP ")

/** Words that only ever appear in text written for a developer, never in a sentence for a researcher. */
private val INTERNAL_MARKERS = listOf(
    "Exception", "exception", "java.", "kotlin.", "android.", "okhttp", "NaN", "null", "=", "://",
    "errno", "EACCES", "ENOENT", "HTTP ", "resolve host", "failed to connect", "Failed to connect",
)

/**
 * Does this exception message already read as a sentence for a person? The app throws
 * `IllegalStateException("That file is larger than …")` on purpose and those should reach the
 * reader; the platform throws "start failed." and "Permission Denial: …" and those should not.
 */
internal fun String.readsAsSentenceForPeople(): Boolean {
    val text = trim()
    if (text.length < 8 || !text.first().isUpperCase() || ' ' !in text) return false
    if (text.startsWith("Permission Denial")) return false
    return INTERNAL_MARKERS.none { it in text }
}
