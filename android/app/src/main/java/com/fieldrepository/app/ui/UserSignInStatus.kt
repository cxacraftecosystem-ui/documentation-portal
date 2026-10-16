package com.fieldrepository.app.ui

import com.fieldrepository.app.data.FIELD_NOT_SENT
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SIGN_IN_DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("en", "IN"))

/** For an account in use from before first sign-ins were recorded. The web says the same. */
const val FIRST_SIGN_IN_UNRECORDED = "Yes, date not recorded"

/**
 * The users screen's "Signed in" line: the most recent sign-in, else the first one, else "Not yet".
 * en-IN, pinned, like every other date this app prints in a sentence.
 *
 * NULL — DRAW NO LINE — when the server did not send [lastLoginAt] at all ([FIELD_NOT_SENT]): a
 * server older than the sign-in columns sends neither field, and "Not yet" for every account there
 * would be a false statement on an admin screen.
 *
 * A [firstLoginAt] equal to [createdAt] is not a date: for accounts that predate the column the
 * server filled it with the creation time to mark them as in use, and a real sign-in is always later
 * than the account it signs in to.
 */
fun signInStatusLabel(
    lastLoginAt: String?,
    firstLoginAt: String?,
    createdAt: String? = null,
    zone: ZoneId = ZoneId.systemDefault()
): String? {
    if (lastLoginAt == FIELD_NOT_SENT) return null
    signInDay(lastLoginAt, zone)?.let { return "Last on ${it.format(SIGN_IN_DAY)}" }
    val first = instantOf(firstLoginAt)
    if (first != null && first == instantOf(createdAt)) return FIRST_SIGN_IN_UNRECORDED
    signInDay(firstLoginAt, zone)?.let { return "First on ${it.format(SIGN_IN_DAY)}" }
    return "Not yet"
}

private fun instantOf(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

private fun signInDay(value: String?, zone: ZoneId): LocalDate? {
    if (value.isNullOrBlank()) return null
    instantOf(value)?.let { return it.atZone(zone).toLocalDate() }
    return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}
