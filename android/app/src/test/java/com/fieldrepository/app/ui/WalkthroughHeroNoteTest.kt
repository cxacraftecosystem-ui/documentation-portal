package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughHeroNoteTest {
    private val path = "android/app/src/main/java/com/fieldrepository/app/ui/WalkthroughJourney.kt"
    private val file get() = repoFile(path, "../$path").readText().replace("\r\n", "\n")
    private val heroCard get() = file.substringAfter("private fun WalkthroughHeroCard(").substringBefore("private fun WalkthroughOutroCard(")

    @Test
    fun `the hero card switches between this app's decks`() {
        assertEquals(3, WALKTHROUGH_DECKS.size)
        assertTrue(heroCard.contains("val others = decks.filter { it.id != deck.id }"))
        assertFalse(heroCard.contains("inspector", ignoreCase = true))
    }

    @Test
    fun `each other deck gets its own ActionRow`() {
        val switcher = heroCard.substringAfter("val others = decks.filter")
        assertTrue(switcher.contains("others.forEach { other ->\n                    ActionRow {"))
        assertFalse(switcher.contains("ActionRow {\n                    others.forEach"))
        assertFalse(heroCard.contains("Inspecting a"))
    }
}
