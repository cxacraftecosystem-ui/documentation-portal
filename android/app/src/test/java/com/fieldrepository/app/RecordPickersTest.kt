package com.fieldrepository.app

import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.ui.artisansByCraftThenName
import com.fieldrepository.app.ui.craftChangeClearsArtisan
import com.fieldrepository.app.ui.craftNameFor
import com.fieldrepository.app.ui.craftsChangeClearsArtisans
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

    private fun named(id: String, name: String, craftId: String?, craft: CraftDto? = null) =
        ArtisanDto(
            id = id,
            name = name,
            place = "Place",
            status = "APPROVED",
            craftId = craftId,
            craft = craft
        )

    private fun craft(id: String, name: String) = CraftDto(id = id, name = name)

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
    // craftsChangeClearsArtisans — the destructive one, in its multi-select form
    //
    // EVERY CASE BELOW HAS A TWIN in `frontend/e2e/record-pickers-unit.spec.ts`, against
    // `frontend/components/forms/recordPickers.ts`. If you change a rule there and these still pass
    // unchanged, you have just created the divergence both files are written about.
    // -----------------------------------------------------------------------

    @Test
    fun `deselecting one craft drops only that craft's people`() {
        val artisans = listOf(artisan("weaver", craftId = "weaving"), artisan("potter", craftId = "pottery"))
        assertEquals(
            listOf("weaver"),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("pottery"),
                removedCraftIds = listOf("weaving"),
                artisanIds = listOf("weaver", "potter"),
                artisans = artisans
            )
        )
    }

    @Test
    fun `an artisan of a craft that is still ticked is kept`() {
        val artisans = listOf(artisan("potter", craftId = "pottery"))
        assertEquals(
            emptyList<String>(),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("pottery", "weaving"),
                removedCraftIds = listOf("blockprinting"),
                artisanIds = listOf("potter"),
                artisans = artisans
            )
        )
    }

    /**
     * THE HEADLINE DEFECT, AND THE ONE CASE THE OLD RULE COULD NOT GET RIGHT. Mohan is a potter; this
     * tool was linked to him through "Assign tools to artisans", and Pottery has never been ticked on
     * this form. Unticking Block printing has nothing to do with him. The old rule read his craft's
     * absence from the next list as a reason to drop him, the save carried the shortened list, and
     * `_replace_artisan_links` deleted his `ToolArtisan` row under a 200.
     */
    @Test
    fun `an artisan whose craft was never ticked is not touched by unticking another`() {
        val artisans = listOf(artisan("mohan", craftId = "pottery"), artisan("printer", craftId = "blockprinting"))
        assertEquals(
            listOf("printer"),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("bandhani"),
                removedCraftIds = listOf("blockprinting"),
                artisanIds = listOf("mohan", "printer"),
                artisans = artisans
            )
        )
    }

    /**
     * THE REGRESSION, in the plural. Same shape as the singular's: the artisan is simply not on the
     * loaded page, which says nothing whatever about their craft, and reading that silence as "wrong
     * craft" is what destroyed stored links under a 200.
     */
    @Test
    fun `an artisan the picker cannot see keeps their link across a craft deselection`() {
        val loadedPage = listOf(artisan("someone-else", craftId = "pottery"))
        assertEquals(
            emptyList<String>(),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("pottery"),
                removedCraftIds = listOf("weaving"),
                artisanIds = listOf("off-page"),
                artisans = loadedPage
            )
        )
        assertEquals(
            emptyList<String>(),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("blockprinting"),
                removedCraftIds = listOf("pottery"),
                artisanIds = listOf("off-page"),
                artisans = loadedPage
            )
        )
    }

    /**
     * AND HERE THE PLURAL DELIBERATELY DIFFERS FROM THE SINGULAR ABOVE.
     *
     * `craftChangeClearsArtisan` CLEARS an artisan whose own craft column is blank, because its
     * question is "is this artisan of THE craft" and a blank column is a knowable no. The plural's
     * question is "did the craft that just went away account for this artisan", which a blank column
     * cannot answer at all — so the link is kept. The browser's twin makes the same choice, in the
     * same words.
     */
    @Test
    fun `an artisan with no craft recorded is never dropped by a craft change`() {
        val artisans = listOf(artisan("unfiled", craftId = null))
        assertTrue(craftChangeClearsArtisan("pottery", "unfiled", artisans))
        assertEquals(
            emptyList<String>(),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("pottery"),
                removedCraftIds = listOf("weaving"),
                artisanIds = listOf("unfiled"),
                artisans = artisans
            )
        )
    }

    /**
     * UNTICKING THE LAST CRAFT IS NOT A SPECIAL CASE, AND THE CASE THAT SAID IT WAS IS QUOTED.
     *
     * This was named "unticking every craft never touches the artisans" and asserted
     * `emptyList()` for an empty `nextCraftIds`, under a comment headed "UNTICKING THE LAST CRAFT
     * DROPS NOBODY" which argued that without the arm "the save would send `artisanIds: []`, every
     * `ToolArtisan` row for the tool would be deleted, and the trigger would have been an edit about
     * a CRAFT", and that the answer was otherwise order-dependent. The browser asserted the OPPOSITE
     * for the same input, so the two clients disagreed on every gesture that empties the list.
     *
     * Both sides now run ONE rule with no empty-list arm, and `removedCraftIds` is what makes that
     * safe rather than merely consistent: the craft just unticked is in `removedCraftIds`, so ITS
     * artisans go and nobody else's — which is what unticking a craft means whether or not it was the
     * last. The harm the arm was protecting against is no longer caused by the general rule.
     */
    @Test
    fun `unticking the last craft drops that craft's people and nobody else's`() {
        val artisans = listOf(
            artisan("potter", craftId = "pottery"),
            artisan("weaver", craftId = "weaving"),
            artisan("unfiled", craftId = null),
        )
        assertEquals(
            listOf("potter"),
            craftsChangeClearsArtisans(
                nextCraftIds = emptyList(),
                removedCraftIds = listOf("pottery"),
                artisanIds = listOf("potter", "weaver", "unfiled", "off-page"),
                artisans = artisans
            )
        )
    }

    /**
     * THE ORDER-DEPENDENCE THE OLD ARM WAS ALSO ARGUING ABOUT, ASSERTED RATHER THAN CLAIMED. Both
     * gestures remove exactly Pottery, so both end at the same record — which under the old rule they
     * did not, and a researcher had no way to know which of the two they had performed.
     */
    @Test
    fun `the same two gestures in either order remove the same people`() {
        val artisans = listOf(artisan("potter", craftId = "pottery"), artisan("weaver", craftId = "weaving"))
        val ids = listOf("potter", "weaver")
        // Untick Pottery first (the list empties), then tick Weaving.
        val unticked = craftsChangeClearsArtisans(emptyList(), listOf("pottery"), ids, artisans)
        val thenTicked = craftsChangeClearsArtisans(
            listOf("weaving"), emptyList(), ids.filterNot { it in unticked }, artisans
        )
        // Tick Weaving first, then untick Pottery.
        val ticked = craftsChangeClearsArtisans(listOf("pottery", "weaving"), emptyList(), ids, artisans)
        val thenUnticked = craftsChangeClearsArtisans(
            listOf("weaving"), listOf("pottery"), ids.filterNot { it in ticked }, artisans
        )
        assertEquals(listOf("potter"), unticked + thenTicked)
        assertEquals(listOf("potter"), ticked + thenUnticked)
    }

    @Test
    fun `a blank artisan id is never returned as something to drop`() {
        val artisans = listOf(artisan("a1", craftId = "weaving"))
        assertEquals(
            emptyList<String>(),
            craftsChangeClearsArtisans(
                nextCraftIds = listOf("pottery"),
                removedCraftIds = listOf("weaving"),
                artisanIds = listOf(""),
                artisans = artisans
            )
        )
    }

    // -----------------------------------------------------------------------
    // craftNameFor / artisansByCraftThenName — the canonical picker ordering
    // -----------------------------------------------------------------------

    @Test
    fun `the hydrated craft wins, then the ticked craft, then nothing`() {
        val ticked = listOf(craft("c1", "Bandhani"))
        assertEquals(
            "Block Printing",
            craftNameFor(named("a1", "Asha", craftId = "c1", craft = craft("c9", "Block Printing")), ticked)
        )
        assertEquals("Bandhani", craftNameFor(named("a2", "Bina", craftId = "c1"), ticked))
        // An id no ticked craft carries, and an artisan with no craft at all, both answer "".
        assertEquals("", craftNameFor(named("a3", "Chandni", craftId = "c2"), ticked))
        assertEquals("", craftNameFor(named("a4", "Devi", craftId = null), ticked))
    }

    @Test
    fun `artisans sort by craft name then by artisan name`() {
        val ticked = listOf(craft("c1", "Weaving"), craft("c2", "Bandhani"))
        val rows = listOf(
            named("a1", "Zoya", craftId = "c2"),
            named("a2", "Asha", craftId = "c1"),
            named("a3", "Bina", craftId = "c2"),
            named("a4", "Anil", craftId = "c1"),
        )
        // Bandhani before Weaving, whatever order the crafts were ticked in; then by name inside each.
        assertEquals(
            listOf("a3", "a1", "a4", "a2"),
            artisansByCraftThenName(rows, ticked).map { it.id }
        )
    }

    /**
     * An artisan whose craft this form cannot name sorts LAST, never first.
     *
     * A blank sort key would otherwise sort before every real craft name and put the unknowns at the
     * top of the sheet, which reads as the list being broken rather than as the crafts being unknown.
     */
    @Test
    fun `an unknown craft sorts last rather than first`() {
        val ticked = listOf(craft("c1", "Weaving"))
        val rows = listOf(
            named("a1", "Asha", craftId = null),
            named("a2", "Bina", craftId = "c1"),
        )
        assertEquals(listOf("a2", "a1"), artisansByCraftThenName(rows, ticked).map { it.id })
    }

    /**
     * Case folding is ROOT-lowercase on both the craft and the artisan name, and the tuple ends in
     * the id so the order is TOTAL — nothing here depends on `sortedWith` being stable.
     */
    @Test
    fun `the ordering is case insensitive and total`() {
        val ticked = listOf(craft("c1", "bandhani"), craft("c2", "Ajrakh"))
        val rows = listOf(
            named("z", "asha", craftId = "c1"),
            named("a", "Asha", craftId = "c1"),
            named("m", "BINA", craftId = "c2"),
        )
        // Ajrakh first despite its capital; then the two Ashas, whose fold is identical, split by the
        // raw name ("Asha" before "asha" in UTF-16 code-unit order) and then by id.
        assertEquals(listOf("m", "a", "z"), artisansByCraftThenName(rows, ticked).map { it.id })
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
