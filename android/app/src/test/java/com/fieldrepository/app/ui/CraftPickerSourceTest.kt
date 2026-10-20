package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No form on the handset types a craft (REQUIREMENTS_2026-10-20 §2.4): every place a person picks one
 * goes through `ui/CraftPicker.kt`, whose only options are the craft list.
 */
class CraftPickerSourceTest {
    private val main = repoSource(
        "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
    )
    private val search = repoSource(
        "app/src/main/java/com/fieldrepository/app/ui/SearchScreen.kt",
        "android/app/src/main/java/com/fieldrepository/app/ui/SearchScreen.kt",
    )

    @Test
    fun `the free-text craft boxes are gone`() {
        assertFalse("the artisan form still offers \"Or new craft name\"", main.contains("\"Or new craft name\""))
        assertFalse("a form still types a craft name", main.contains("RequiredInput(\"Craft name\", craftName"))
        assertFalse("a form still links a craft by the old label", main.contains("\"Linked craft (fills craft name)\""))
        assertFalse("a form still keeps a typed new craft", main.contains("newCraftName"))
        assertFalse("the craft form still types its category", main.contains("TextInput(\"Category\""))
    }

    @Test
    fun `every craft picker is the craft-list picker`() {
        // Artisan, product, tool, workshop "Crafts covered" and tool assignment; and the search filter.
        assertTrue(Regex("CraftPickerField\\(").findAll(main).count() >= 5)
        assertTrue(Regex("multiple = true").findAll(main).count() >= 3)
        assertTrue(search.contains("CraftPickerField("))
        assertFalse("crafts are no longer paged from the list route", main.contains("repository.craftsSearchPage(term, page)"))
    }
}
