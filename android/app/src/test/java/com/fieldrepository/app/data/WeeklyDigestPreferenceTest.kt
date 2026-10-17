package com.fieldrepository.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Monday summary's switch on the handset: the server's answer parses (with and without a latest
 * summary, and with fields this build does not know), the row is hidden for an account that gets no
 * summary, the copy follows mail, and a failed save puts the switch back.
 */
class WeeklyDigestPreferenceTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `the server's answer parses, latest included`() {
        val state = json.decodeFromString(
            WeeklyDigestPreferenceDto.serializer(),
            """{"eligible":true,"enabled":true,"emailAvailable":true,"schedule":"Mondays, 08:00 IST",
               "latest":{"id":"dg1","weekStart":"2026-10-19","waiting":4,"overdue":2,"newThisWeek":11,
                 "coverage":{"label":"Districts documented","value":41,"of":null,"percent":null,"scope":"x"}},
               "somethingNew":1}"""
        )
        assertTrue(state.eligible && state.enabled && state.emailAvailable)
        val latest = assertNotNullAndGet(state.latest)
        assertEquals("dg1", latest.id)
        assertEquals(listOf(4, 2, 11), listOf(latest.waiting, latest.overdue, latest.newThisWeek))
        assertEquals(41, latest.coverage?.value)
        assertNull(latest.coverage?.percent)
    }

    @Test
    fun `an account that gets no summary sees no switch`() {
        val state = json.decodeFromString(
            WeeklyDigestPreferenceDto.serializer(),
            """{"eligible":false,"enabled":false,"emailAvailable":true,"schedule":"Mondays, 08:00 IST","latest":null}"""
        )
        assertFalse(WeeklyDigestRules.showSwitch(state))
        assertFalse(WeeklyDigestRules.showSwitch(null))
        assertTrue(WeeklyDigestRules.showSwitch(state.copy(eligible = true)))
    }

    @Test
    fun `the copy follows mail`() {
        val base = WeeklyDigestPreferenceDto(eligible = true, enabled = true)
        assertTrue(WeeklyDigestRules.description(base.copy(emailAvailable = true)).endsWith("By e-mail and here."))
        assertTrue(WeeklyDigestRules.description(base).endsWith("Here, in your notifications."))
    }

    @Test
    fun `a failed save puts the switch back`() = runBlocking {
        val before = WeeklyDigestPreferenceDto(eligible = true, enabled = true)
        val shown = mutableListOf<Boolean>()
        val (left, failure) = WeeklyDigestRules.toggle(before, false, save = { error("offline") }, show = { shown += it.enabled })
        assertEquals(listOf(false, true), shown)
        assertTrue(left.enabled)
        assertNotNull(failure)

        shown.clear()
        val (saved, none) = WeeklyDigestRules.toggle(before, false, save = { before.copy(enabled = it) }, show = { shown += it.enabled })
        assertEquals(listOf(false, false), shown)
        assertFalse(saved.enabled)
        assertNull(none)
    }

    @Test
    fun `the body is just the switch, and a digest link opens nothing on the phone`() {
        assertEquals("""{"enabled":false}""", Json.encodeToString(WeeklyDigestPreferenceBody.serializer(), WeeklyDigestPreferenceBody(false)))
        val main = listOf(File("src/main/java/com/fieldrepository/app/MainActivity.kt"), File("app/src/main/java/com/fieldrepository/app/MainActivity.kt"))
            .first { it.exists() }.readText()
        assertTrue(main.contains("link.startsWith(\"/digest/\") -> Unit"))
        assertNull(notificationTarget("/digest/dg1"))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
