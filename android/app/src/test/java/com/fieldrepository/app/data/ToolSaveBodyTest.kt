package com.fieldrepository.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSaveBodyTest {

    private val agraInlay = "craft-agra-marble-inlay"

    private val fahim = ArtisanDto(id = "art-fahim", name = "Fahim", place = "Agra", status = "APPROVED", craftId = agraInlay)

    private val moinKhan = ArtisanDto(id = "art-moin", name = "Moin Khan", place = "Agra", status = "APPROVED", craftId = agraInlay)

    private val coldCatalogue = emptyList<CraftDto>()

    private fun carriedPrefillCraftName(): String? = null

    private fun incidentForm() = ToolFormFields(
        toolkitName = "Chisel set",
        craftName = carriedPrefillCraftName().orEmpty(),
        artisanName = "Fahim",
        place = "Agra",
        craftIds = listOf(agraInlay),
        artisanIds = listOf(fahim.id, moinKhan.id),
    )

    private fun String?.isBlankName(): Boolean = this != null && this.isBlank()

    @Test
    fun `a tool saved after save-and-add-another with a cold craft catalogue never sends a blank craft name`() {
        val body = toolCreateBody(incidentForm(), crafts = coldCatalogue, artisans = listOf(fahim, moinKhan))

        assertFalse(
            "0.0.2 sent craftName = craftName.trim(), which was \"\" here, and the server answered 422 five times",
            body.craftName.isBlankName(),
        )
        assertFalse(body.artisanName.isBlankName())
        assertFalse(body.place.isBlankName())
        assertEquals(listOf(agraInlay), body.craftIds)
        assertEquals(listOf(fahim.id, moinKhan.id), body.artisanIds)
        assertEquals(agraInlay, body.craftId)
    }

    @Test
    fun `the incident body on the wire carries no empty craft name, artisan name or place`() {
        val body = toolCreateBody(incidentForm(), crafts = coldCatalogue, artisans = listOf(fahim, moinKhan))
        val wire = ApiClient.json.encodeToString(ToolCreateRequest.serializer(), body)

        assertFalse(wire, wire.contains("\"craftName\":\"\""))
        assertFalse(wire, wire.contains("\"artisanName\":\"\""))
        assertFalse(wire, wire.contains("\"place\":\"\""))
        assertTrue(wire, wire.contains("\"craftIds\""))
    }

    @Test
    fun `with nothing to name the craft the body leaves it out so the server names it from the id`() {
        val body = toolCreateBody(incidentForm(), crafts = coldCatalogue, artisans = listOf(fahim, moinKhan))
        assertNull(body.craftName)
    }

    @Test
    fun `a picker row that knows its craft names the craft even while the catalogue is cold`() {
        val inlay = CraftDto(id = agraInlay, name = "Agra Marble Inlay")
        val body = toolCreateBody(
            incidentForm(),
            crafts = coldCatalogue,
            artisans = listOf(fahim.copy(craft = inlay), moinKhan),
        )
        assertEquals("Agra Marble Inlay", body.craftName)
    }

    @Test
    fun `a blank artisan name and place fall back to the first picked artisan`() {
        val body = toolCreateBody(
            incidentForm().copy(artisanName = "  ", place = ""),
            crafts = coldCatalogue,
            artisans = listOf(fahim, moinKhan),
        )
        assertEquals("Fahim", body.artisanName)
        assertEquals("Agra", body.place)
    }

    @Test
    fun `an artisan the picker never loaded leaves the names out instead of sending them blank`() {
        val body = toolCreateBody(
            incidentForm().copy(artisanName = "", place = ""),
            crafts = coldCatalogue,
            artisans = emptyList(),
        )
        assertNull(body.artisanName)
        assertNull(body.place)
    }

    @Test
    fun `the craft carried to the next form comes from the catalogue, then the artisan rows, then what was typed`() {
        val inlay = CraftDto(id = agraInlay, name = "Agra Marble Inlay")
        assertEquals("Agra Marble Inlay", linkedCraftName(listOf(agraInlay), "", listOf(inlay)))
        assertEquals("Agra Marble Inlay", linkedCraftName(listOf(agraInlay), "", coldCatalogue, listOf(fahim.copy(craft = inlay))))
        assertEquals("Pietra dura", linkedCraftName(listOf(agraInlay), "Pietra dura", coldCatalogue))
        assertNull(linkedCraftName(listOf(agraInlay), " ", coldCatalogue, listOf(fahim)))
    }

    @Test
    fun `optional tool text is left out when blank, never sent as an empty string`() {
        val body = toolCreateBody(
            incidentForm().copy(localName = " ", englishName = "", material = "  ", remarks = "", suggestions = " "),
            crafts = coldCatalogue,
            artisans = listOf(fahim, moinKhan),
        )
        assertNull(body.localName)
        assertNull(body.englishName)
        assertNull(body.material)
        assertNull(body.remarks)
        assertNull(body.suggestionsForToolImprovement)
        assertEquals("Chisel set", body.toolkitName)
    }

    @Test
    fun `a product carried in with only a craft id never sends a blank craft name`() {
        val body = productCreateBody(
            ProductFormFields(
                productName = "Inlay coaster",
                craftName = "",
                artisanName = "Fahim",
                place = "Agra",
                craftId = agraInlay,
                artisanId = fahim.id,
            ),
            crafts = coldCatalogue,
            artisans = listOf(fahim),
        )
        assertFalse(body.craftName.isBlankName())
        assertFalse(body.artisanName.isBlankName())
        assertFalse(body.place.isBlankName())
        assertEquals(agraInlay, body.craftId)
        assertEquals(fahim.id, body.artisanId)
    }

    @Test
    fun `a product with a blank craft id sends no craft id`() {
        val body = productCreateBody(
            ProductFormFields(productName = "Inlay coaster", craftName = "Agra Marble Inlay", artisanName = "Fahim", place = "Agra"),
            crafts = coldCatalogue,
            artisans = emptyList(),
        )
        assertNull(body.craftId)
        assertNull(body.artisanId)
        assertEquals("Agra Marble Inlay", body.craftName)
    }
}
