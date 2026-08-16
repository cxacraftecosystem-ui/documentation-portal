package com.fieldrepository.app

import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.ui.craftChangeClearsArtisan
import com.fieldrepository.app.ui.listCutNotice
import com.fieldrepository.app.ui.mergeArtisansById
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record pickers' rules, asserted rather than eyeballed.
 *
 * WHY THESE THREE FUNCTIONS ARE PURE AND WHY THIS FILE EXISTS. Reproducing the defect they close
 * needs a repository holding more than 100 artisans AND a record old enough to sort off the first
 * page of `GET /artisans` — nobody arranges that before a release, and the failure mode is silent
 * (a link disappears; the save returns 200). So the decision was lifted out of the composables into
 * `ui/RecordPickers.kt`, where a test can reach it. If someone later folds `craftChangeClearsArtisan`
 * back into an `if` inside `MainActivity`, these tests stop compiling — which is the point.
 *
 * The web twin of every rule below lives in `frontend/components/forms/recordPickers.ts` and
 * `frontend/components/data/cappedList.ts`. Change one, change both.
 */
class RecordPickersTest {

    private fun artisan(id: String, craftId: String?) =
        ArtisanDto(id = id, name = "Artisan $id", place = "Place", status = "APPROVED", craftId = craftId)

    // -----------------------------------------------------------------------
    // craftChangeClearsArtisan — the destructive one
    // -----------------------------------------------------------------------

    @Test
    fun `an artisan known to practise another craft is unlinked`() {
        val artisans = listOf(artisan("a1", craftId = "weaving"))
        assertTrue(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "a1", artisans = artisans))
    }

    @Test
    fun `an artisan known to practise the chosen craft is kept`() {
        val artisans = listOf(artisan("a1", craftId = "pottery"))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "a1", artisans = artisans))
    }

    /**
     * THE REGRESSION. This is the case the old `artisans.none { it.id == artisanId && it.craftId == id }`
     * got wrong: the artisan is simply not on the loaded page, which says nothing whatever about
     * their craft. The old expression read that silence as "wrong craft" and blanked the field, and
     * because `artisanId` is in the backend's CLEARABLE_KEYS the blank was saved as a real unlink.
     * Keeping the link is the safe direction — a wrong link is visible and one tap from a fix.
     */
    @Test
    fun `an artisan the picker cannot see keeps their link`() {
        val loadedPage = listOf(artisan("someone-else", craftId = "pottery"))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "off-page", artisans = loadedPage))
        // And the same when the new craft is one no loaded artisan practises, which is the shape the
        // 100-row page produces most often.
        assertFalse(craftChangeClearsArtisan(nextCraftId = "blockprinting", artisanId = "off-page", artisans = loadedPage))
    }

    @Test
    fun `an artisan with no craft recorded keeps their link only when the craft differs`() {
        val artisans = listOf(artisan("a1", craftId = null))
        // The record says "no craft"; the form is being pointed at one. That IS a known difference,
        // so it clears — the artisan is on the page and their column disagrees.
        assertTrue(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "a1", artisans = artisans))
    }

    @Test
    fun `unlinking the craft never touches the artisan`() {
        val artisans = listOf(artisan("a1", craftId = "weaving"))
        // "Unlinked / type below" is a blank craft id. Clearing the artisan too would destroy a
        // second link the researcher never touched.
        assertFalse(craftChangeClearsArtisan(nextCraftId = "", artisanId = "a1", artisans = artisans))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "", artisans = artisans))
    }

    // -----------------------------------------------------------------------
    // listCutNotice — the sentence under a capped list
    // -----------------------------------------------------------------------

    @Test
    fun `a complete list says nothing`() {
        assertNull(listCutNotice(loaded = 42, total = 42, noun = "artisans"))
        // A server that under-reports `total` must also produce silence rather than negative
        // arithmetic on screen.
        assertNull(listCutNotice(loaded = 100, total = 0, noun = "artisans"))
    }

    @Test
    fun `a cut list prints both numbers and says the search is local`() {
        val notice = listCutNotice(loaded = 100, total = 749, noun = "artisans")
        assertEquals(
            "Showing 100 of 749 artisans — the other 649 are not on this list, " +
                "and typing here searches only the 100 shown.",
            notice
        )
    }

    /**
     * The arm no live database produces from page one, and the reason this is a function rather
     * than an `if` in a composable: nothing renders, so nothing can be screenshotted, so the only
     * way this wording is ever checked is here. It must NOT tell the reader to search or page —
     * neither would help — and it must deny the reading the empty control invites.
     */
    @Test
    fun `nothing loaded over a non-empty repository gets its own words`() {
        assertEquals(
            "None of the 749 artisans could be listed here — this is not an empty repository.",
            listCutNotice(loaded = 0, total = 749, noun = "artisans")
        )
    }

    // -----------------------------------------------------------------------
    // mergeArtisansById — additive, first writer wins
    // -----------------------------------------------------------------------

    @Test
    fun `merging adds rows and never removes one`() {
        val page = listOf(artisan("a1", "weaving"), artisan("a2", "pottery"))
        val roster = listOf(artisan("a2", "pottery"), artisan("a3", "pottery"))
        val merged = mergeArtisansById(page, roster)
        assertEquals(listOf("a1", "a2", "a3"), merged.map { it.id })
    }

    @Test
    fun `a narrower answer never shortens the options`() {
        // The craft-scoped roster arriving must not make the form forget the artisan it is editing.
        val page = listOf(artisan("editing-this-one", "weaving"))
        assertEquals(page, mergeArtisansById(page, emptyList()))
        val merged = mergeArtisansById(page, listOf(artisan("other", "pottery")))
        assertTrue(merged.any { it.id == "editing-this-one" })
    }

    @Test
    fun `the first writer wins on a duplicate id`() {
        val first = artisan("a1", "weaving")
        val second = ArtisanDto(id = "a1", name = "Renamed", place = "Elsewhere", status = "PENDING", craftId = "pottery")
        val merged = mergeArtisansById(listOf(first), listOf(second))
        assertEquals(1, merged.size)
        // The row already on screen is not swapped for a differently-shaped one mid-interaction.
        assertEquals("Artisan a1", merged.first().name)
    }
}
