package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** The users screen's "Signed in" line. */
class UserSignInStatusTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `the most recent sign-in wins`() {
        assertEquals("Last on 17 Oct 2026", signInStatusLabel("2026-10-17T04:00:00Z", "2026-09-01T04:00:00Z", zone = india))
    }

    @Test
    fun `the first sign-in is shown when the last is not known yet`() {
        assertEquals("First on 01 Sept 2026".replace("Sept", signInMonth()), signInStatusLabel(null, "2026-09-01T04:00:00+00:00", zone = india))
    }

    @Test
    fun `an account that never signed in says not yet`() {
        assertEquals("Not yet", signInStatusLabel(null, null, zone = india))
        assertEquals("Not yet", signInStatusLabel(null, "garbage", zone = india))
    }

    @Test
    fun `the day is the viewer's day, not the server's`() {
        assertEquals("Last on 18 Oct 2026", signInStatusLabel("2026-10-17T20:00:00Z", null, zone = india))
    }

    @Test
    fun `a first sign-in the server filled from the creation date is not shown as a date`() {
        assertEquals(
            FIRST_SIGN_IN_UNRECORDED,
            signInStatusLabel(null, "2026-03-01T10:00:00Z", "2026-03-01T10:00:00.000+00:00", india)
        )
        assertEquals(
            "First on 04 Mar 2026",
            signInStatusLabel(null, "2026-03-04T10:00:00Z", "2026-03-01T10:00:00Z", india)
        )
    }

    @Test
    fun `a server that does not send the fields gets no line, one that sends null gets not yet`() {
        val old = json.decodeFromString<UserDto>("""{"id":"u","email":"a@x","name":"A","role":"ADMIN"}""")
        assertNull(signInStatusLabel(old.lastLoginAt, old.firstLoginAt, old.createdAt, india))
        val current = json.decodeFromString<UserDto>(
            """{"id":"u","email":"a@x","name":"A","role":"ADMIN","firstLoginAt":null,"lastLoginAt":null}"""
        )
        assertEquals("Not yet", signInStatusLabel(current.lastLoginAt, current.firstLoginAt, current.createdAt, india))
    }

    /** en-IN's short September differs between JDKs ("Sep" or "Sept"); read it from the formatter. */
    private fun signInMonth(): String =
        java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale("en", "IN")).format(java.time.LocalDate.of(2026, 9, 1))
}
