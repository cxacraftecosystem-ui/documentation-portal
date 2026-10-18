package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B5: the product and tool forms pick the artisan first, and the artisan sets craft and place. */
class ArtisanFirstPickerTest {

    private fun artisan(craft: String?, place: String = "Bagru") = ArtisanDto(
        id = "a1",
        name = "Ramesh Chhipa",
        place = place,
        status = "APPROVED",
        craftId = craft?.let { "c1" },
        craft = craft?.let { CraftDto(id = "c1", name = it) },
    )

    @Test
    fun `a row names the artisan, their craft and their place`() {
        assertEquals("Ramesh Chhipa · Block printing · Bagru", artisanFirstOption(artisan("Block printing")).label)
    }

    @Test
    fun `a missing craft or place is dropped rather than printed as an empty slot`() {
        assertEquals("Ramesh Chhipa · Bagru", artisanFirstOption(artisan(null)).label)
        assertEquals("Ramesh Chhipa · Block printing", artisanFirstOption(artisan("Block printing", place = " ")).label)
    }

    @Test
    fun `neither form gates the artisan on a craft any more`() {
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertFalse(
            "the dead stop is gone",
            Regex("placeholder = [^\\n]*Select a linked craft first").containsMatchIn(source)
        )
        assertEquals(
            "product and tool each offer Artisan not on record",
            2,
            Regex("""label = "Artisan not on record"""").findAll(source).count(),
        )
        assertTrue(source.contains("server = picker.search.asServerSearch(::artisanFirstOption)"))
    }
}
