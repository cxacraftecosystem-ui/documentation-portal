package com.fieldrepository.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenRestoreTest {
    @Test
    fun everyTopLevelScreenSurvivesProcessDeath() {
        val samples = restorableScreenSamples()
        assertEquals(samples.size, samples.toSet().size)
        samples.forEach { token -> assertEquals(token, restoredScreenToken(token)) }
        samples.drop(1).forEach { token -> assertNotEquals("home", token) }
    }

    @Test
    fun settingsNotificationsAndRecordsAreRemembered() {
        val samples = restorableScreenSamples()
        listOf("Notifications", "Appearance", "DataBrowser").forEach { name ->
            val token = packScreenTokens(listOf("object", name))
            assertEquals(token, restoredScreenToken(token))
        }
        val record = packScreenTokens(listOf("view", "ARTISAN", "a1", null, "home"))
        assertEquals(record, restoredScreenToken(record))
        assertEquals(true, samples.any { it.startsWith(packScreenTokens(listOf("view"))) })
    }

    @Test
    fun anUnreadableTokenFallsBackToTheDashboard() {
        assertEquals("home", restoredScreenToken("garbage"))
        assertEquals("home", restoredScreenToken(packScreenTokens(listOf("view", "NOT_A_MODE", "x"))))
        assertEquals("home", restoredScreenToken(packScreenTokens(listOf("object", "Nowhere"))))
        assertEquals(packScreenTokens(listOf("object", "DataBrowser")), restoredScreenToken("data"))
    }

    @Test
    fun tokensPackNullsAndSeparators() {
        val tokens = listOf("a:b", null, "", "n;", "12:x")
        assertEquals(tokens, unpackScreenTokens(packScreenTokens(tokens)))
    }
}
