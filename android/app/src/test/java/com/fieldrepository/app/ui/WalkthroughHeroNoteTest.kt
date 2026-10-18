package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening card's switcher, and the note over it, match the designer portal's walkthrough: the
 * each other deck sits in its own [ActionRow], and the note describes THIS app's decks rather than the
 * designer portal's inspector deck it was carried over from.
 */
class WalkthroughHeroNoteTest {
    private val path = "android/app/src/main/java/com/fieldrepository/app/ui/WalkthroughJourney.kt"
    private val file get() = repoFile(path, "../$path").readText().replace("\r\n", "\n")
    private val heroNote get() = file.substringAfter(" * The opening band.").substringBefore("private fun WalkthroughHeroCard(")
    private val heroCard get() = file.substringAfter("private fun WalkthroughHeroCard(").substringBefore(" * The closing card")

    @Test
    fun `the note names this app's decks`() {
        assertTrue(heroNote.contains("SINCE THERE ARE THREE DECKS, THE SWITCHER"))
        assertFalse(heroNote.contains("inspector"))
        assertEquals(3, WALKTHROUGH_DECKS.size)
    }

    @Test
    fun `each other deck gets its own ActionRow`() {
        val switcher = heroCard.substringAfter("val others = decks.filter")
        assertTrue(switcher.contains("others.forEach { other ->\n                    ActionRow {"))
        assertFalse(switcher.contains("ActionRow {\n                    others.forEach"))
        assertTrue(heroNote.contains("EACH OTHER DECK GETS ITS OWN [ActionRow]"))
        assertFalse(heroCard.contains("Inspecting a"))
    }
}
