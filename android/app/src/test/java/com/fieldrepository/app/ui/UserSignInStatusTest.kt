package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** The users screen's "Signed in" line. */
class UserSignInStatusTest {
    private val india = ZoneId.of("Asia/Kolkata")

    @Test
    fun `the most recent sign-in wins`() {
        assertEquals("Last on 17 Oct 2026", signInStatusLabel("2026-10-17T04:00:00Z", "2026-09-01T04:00:00Z", india))
    }

    @Test
    fun `the first sign-in is shown when the last is not known yet`() {
        assertEquals("First on 01 Sept 2026".replace("Sept", signInMonth()), signInStatusLabel(null, "2026-09-01T04:00:00+00:00", india))
    }

    @Test
    fun `an account that never signed in says not yet`() {
        assertEquals("Not yet", signInStatusLabel(null, null, india))
        assertEquals("Not yet", signInStatusLabel("", "garbage", india))
    }

    @Test
    fun `the day is the viewer's day, not the server's`() {
        assertEquals("Last on 18 Oct 2026", signInStatusLabel("2026-10-17T20:00:00Z", null, india))
    }

    /** en-IN's short September differs between JDKs ("Sep" or "Sept"); read it from the formatter. */
    private fun signInMonth(): String =
        java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale("en", "IN")).format(java.time.LocalDate.of(2026, 9, 1))
}
