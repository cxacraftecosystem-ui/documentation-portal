package com.fieldrepository.app.data

import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.orderedChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArtisansHeadFirstTest {
    private fun artisan(id: String, name: String) = ArtisanDto(id = id, name = name, place = "Moradabad", status = "APPROVED")

    private val fahim = artisan("a-fahim", "Fahim")
    private val moin = artisan("a-moin", "Moin Khan")

    private val grinder = ToolDetailDto(
        id = "cmwekvo2d03omcbtag53r4g2x",
        toolkitName = "Grinder",
        artisanId = "a-fahim",
        artisanName = "Fahim",
        place = "Moradabad",
        artisanLinks = listOf(ToolArtisanLinkDto("a-moin", moin), ToolArtisanLinkDto("a-fahim", fahim)),
    )

    @Test
    fun aToolWithTwoLinkedArtisansNamesBothHeadFirstOnce() {
        assertEquals(listOf("Fahim", "Moin Khan"), grinder.toolArtisanList())
        assertEquals("Fahim, Moin Khan", grinder.toolArtisanNames())
        assertEquals("Grinder · Fahim, Moin Khan", grinder.toolRowLabel())
        assertEquals("Artisans", grinder.toolArtisanLabel())
        assertEquals(listOf("a-fahim", "a-moin"), grinder.toolArtisanIds())
        assertEquals("Moin Khan", grinder.toolArtisanName("a-moin"))
        assertEquals("Fahim", grinder.toolArtisanName("a-fahim"))
        assertNull(grinder.toolArtisanName("a-other"))
    }

    @Test
    fun aHeadOutsideTheLinksLeadsAndABlankHeadNameIsTakenFromItsLink() {
        val rafiq = grinder.copy(artisanId = "a-rafiq", artisanName = "Rafiq")
        assertEquals(listOf("Rafiq", "Moin Khan", "Fahim"), rafiq.toolArtisanList())
        assertEquals(listOf("a-rafiq", "a-moin", "a-fahim"), rafiq.toolArtisanIds())
        val blank = grinder.copy(artisanName = "")
        assertEquals(listOf("Fahim", "Moin Khan"), blank.toolArtisanList())
        val solo = ToolDetailDto(id = "t2", toolkitName = "Mallet", artisanId = "a1", artisanName = "Kamla Devi")
        assertEquals("Artisan", solo.toolArtisanLabel())
        assertEquals("Mallet · Kamla Devi", solo.toolRowLabel())
    }

    @Test
    fun theAssignedListPutsTheHeadFirstEvenWhenItHasNoLinkRow() {
        val linkedOnly = listOf(moin)
        val headless = grinder.copy(artisanLinks = listOf(ToolArtisanLinkDto("a-moin", moin)))
        assertEquals(listOf("a-fahim", "a-moin"), toolArtisansHeadFirst(headless, linkedOnly).map { it.id })
        assertEquals("Fahim", toolArtisansHeadFirst(headless, linkedOnly).first().name)
        assertEquals(listOf("a-fahim", "a-moin"), toolArtisansHeadFirst(grinder, listOf(moin, fahim)).map { it.id })
        assertEquals(listOf("a-moin"), toolArtisansHeadFirst(null, listOf(moin, moin)).map { it.id })
    }

    @Test
    fun chosenChipsFollowTheRecordOrder() {
        val options = listOf(SelectOption("a-moin", "Moin Khan"), SelectOption("a-fahim", "Fahim"), SelectOption("a-x", "Xavier"))
        assertEquals(listOf("a-fahim", "a-moin", "a-x"), orderedChips(options, grinder.toolArtisanIds()).map { it.value })
        assertEquals(listOf("a-moin", "a-fahim", "a-x"), orderedChips(options, null).map { it.value })
    }
}
