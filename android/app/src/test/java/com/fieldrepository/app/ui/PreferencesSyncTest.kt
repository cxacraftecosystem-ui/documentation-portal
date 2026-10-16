package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * An appearance change made with no signal is queued on the device and, at the next sync, wins over
 * the server's row only when it is the later write.
 */
class PreferencesSyncTest {
    private val serverRowAt = "2026-10-16T09:00:00Z"
    private val serverMillis = Instant.parse(serverRowAt).toEpochMilli()

    @Test
    fun `nothing queued never overrides the account`() {
        assertFalse(localPreferencesWin(null, serverRowAt))
        assertFalse(localPreferencesWin(null, null))
    }

    @Test
    fun `the later write wins`() {
        assertTrue(localPreferencesWin(serverMillis + 60_000, serverRowAt))
        assertFalse(localPreferencesWin(serverMillis - 60_000, serverRowAt))
    }

    @Test
    fun `a row with no readable time loses to a queued change`() {
        assertTrue(localPreferencesWin(serverMillis, null))
        assertTrue(localPreferencesWin(serverMillis, "not a time"))
    }
}
