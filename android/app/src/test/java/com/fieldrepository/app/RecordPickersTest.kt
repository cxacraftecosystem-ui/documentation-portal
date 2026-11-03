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

    @Test
    fun `an artisan the picker cannot see keeps their link`() {
        val loadedPage = listOf(artisan("someone-else", craftId = "pottery"))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "off-page", artisans = loadedPage))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "blockprinting", artisanId = "off-page", artisans = loadedPage))
    }

    @Test
    fun `an artisan with no craft recorded keeps their link only when the craft differs`() {
        val artisans = listOf(artisan("a1", craftId = null))
        assertTrue(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "a1", artisans = artisans))
    }

    @Test
    fun `unlinking the craft never touches the artisan`() {
        val artisans = listOf(artisan("a1", craftId = "weaving"))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "", artisanId = "a1", artisans = artisans))
        assertFalse(craftChangeClearsArtisan(nextCraftId = "pottery", artisanId = "", artisans = artisans))
    }

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

    @Test
    fun `the same two gestures in either order remove the same people`() {
        val artisans = listOf(artisan("potter", craftId = "pottery"), artisan("weaver", craftId = "weaving"))
        val ids = listOf("potter", "weaver")
        val unticked = craftsChangeClearsArtisans(emptyList(), listOf("pottery"), ids, artisans)
        val thenTicked = craftsChangeClearsArtisans(
            listOf("weaving"), emptyList(), ids.filterNot { it in unticked }, artisans
        )
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

    @Test
    fun `the hydrated craft wins, then the ticked craft, then nothing`() {
        val ticked = listOf(craft("c1", "Bandhani"))
        assertEquals(
            "Block Printing",
            craftNameFor(named("a1", "Asha", craftId = "c1", craft = craft("c9", "Block Printing")), ticked)
        )
        assertEquals("Bandhani", craftNameFor(named("a2", "Bina", craftId = "c1"), ticked))
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
        assertEquals(
            listOf("a3", "a1", "a4", "a2"),
            artisansByCraftThenName(rows, ticked).map { it.id }
        )
    }

    @Test
    fun `an unknown craft sorts last rather than first`() {
        val ticked = listOf(craft("c1", "Weaving"))
        val rows = listOf(
            named("a1", "Asha", craftId = null),
            named("a2", "Bina", craftId = "c1"),
        )
        assertEquals(listOf("a2", "a1"), artisansByCraftThenName(rows, ticked).map { it.id })
    }

    @Test
    fun `the ordering is case insensitive and total`() {
        val ticked = listOf(craft("c1", "bandhani"), craft("c2", "Ajrakh"))
        val rows = listOf(
            named("z", "asha", craftId = "c1"),
            named("a", "Asha", craftId = "c1"),
            named("m", "BINA", craftId = "c2"),
        )
        assertEquals(listOf("m", "a", "z"), artisansByCraftThenName(rows, ticked).map { it.id })
    }

    @Test
    fun `a complete list says nothing`() {
        assertNull(listCutNotice(loaded = 42, total = 42, noun = "artisans"))
        assertNull(listCutNotice(loaded = 100, total = 0, noun = "artisans"))
    }

    @Test
    fun `a cut list prints both numbers and points at Search instead of admitting a gap`() {
        val notice = listCutNotice(loaded = 100, total = 749, noun = "artisans")
        assertEquals(
            "Showing 100 of 749 artisans. To find another, use Browse records from the menu.",
            notice
        )
    }

    @Test
    fun `nothing loaded over a non-empty repository gets its own words`() {
        assertEquals(
            "None of the 749 artisans could be loaded. Try again.",
            listCutNotice(loaded = 0, total = 749, noun = "artisans")
        )
    }

    @Test
    fun `merging adds rows and never removes one`() {
        val page = listOf(artisan("a1", "weaving"), artisan("a2", "pottery"))
        val roster = listOf(artisan("a2", "pottery"), artisan("a3", "pottery"))
        val merged = mergeArtisansById(page, roster)
        assertEquals(listOf("a1", "a2", "a3"), merged.map { it.id })
    }

    @Test
    fun `a narrower answer never shortens the options`() {
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
        assertEquals("Artisan a1", merged.first().name)
    }
}
