package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A8: every create offers what usually comes next, and "Save and add another" is on the forms. */
class NextStepsTest {

    private fun modes(saved: String, hasProduct: Boolean = true) = nextStepChoices(saved, hasProduct).map { it.modeName }

    @Test
    fun `a saved product leads to how it is made, another product, and a tool`() {
        assertEquals(listOf("PROCESS", "PRODUCT", "TOOL"), modes("PRODUCT"))
        assertEquals("Document how it's made", nextStepChoices("PRODUCT").first().label)
    }

    @Test
    fun `a product queued offline has no id, so the process option is withheld`() {
        assertEquals(listOf("PRODUCT", "TOOL"), modes("PRODUCT", hasProduct = false))
    }

    @Test
    fun `a saved craft leads to an artisan first`() {
        assertEquals("ARTISAN", modes("CRAFT").first())
        assertEquals("Add an artisan", nextStepChoices("CRAFT").first().label)
    }

    @Test
    fun `the artisan panel keeps its three shortcuts`() {
        assertEquals(listOf("PRODUCT", "TOOL", "QUESTIONNAIRE"), modes("ARTISAN"))
    }

    @Test
    fun `tools and processes offer another of the same kind`() {
        assertEquals("TOOL", modes("TOOL").first())
        assertEquals("PROCESS", modes("PROCESS").first())
    }

    @Test
    fun `the heading names the record when it has a name`() {
        assertEquals("Saved Block-printed scarf", nextStepHeading("Product", "Block-printed scarf"))
        assertEquals("Product saved", nextStepHeading("Product", "  "))
        assertEquals("Product saved", nextStepHeading("Product", null))
    }

    @Test
    fun `the five create forms draw Save and add another`() {
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertEquals(5, Regex("""onSaveAnother = if \(!isEdit""").findAll(source).count())
        assertTrue("the next-step panel replaces the artisan-only panel", source.contains("NextStepPanel("))
        // The re-opened form must be a new composition, or it would keep the first record's values.
        assertTrue(source.contains("is Screen.Create -> key(s) {"))
    }
}
