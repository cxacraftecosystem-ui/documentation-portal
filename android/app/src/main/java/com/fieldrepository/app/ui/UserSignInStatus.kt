package com.fieldrepository.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SIGN_IN_DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("en", "IN"))

/**
 * The users screen's "Signed in" line: the most recent sign-in, else the first one, else "Not yet".
 * en-IN, pinned, like every other date this app prints in a sentence.
 */
fun signInStatusLabel(lastLoginAt: String?, firstLoginAt: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    signInDay(lastLoginAt, zone)?.let { return "Last on ${it.format(SIGN_IN_DAY)}" }
    signInDay(firstLoginAt, zone)?.let { return "First on ${it.format(SIGN_IN_DAY)}" }
    return "Not yet"
}

private fun signInDay(value: String?, zone: ZoneId): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).atZone(zone).toLocalDate() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}
