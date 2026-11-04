package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.ProcessDetailDto
import com.fieldrepository.app.data.ProductDetailDto
import com.fieldrepository.app.data.ToolDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSwitcherTest {

    private fun artisan(name: String, place: String) =
        ArtisanDto(id = "a1", name = name, place = place, status = "APPROVED")

    @Test
    fun `an artisan reads as name then place`() {
        assertEquals("Ram Kumar · Bagru", recordOptionLabel(artisan("Ram Kumar", "Bagru")))
    }

    @Test
    fun `a missing second half takes the separator with it`() {
        assertEquals("Ram Kumar", recordOptionLabel(artisan("Ram Kumar", "")))
        assertEquals("Ram Kumar", recordOptionLabel(artisan("Ram Kumar", "   ")))
    }

    @Test
    fun `a craft with no place is just its name`() {
        assertEquals("Block printing", recordOptionLabel(CraftDto(id = "c1", name = "Block printing")))
        assertEquals(
            "Block printing · Bagru",
            recordOptionLabel(CraftDto(id = "c1", name = "Block printing", place = "Bagru"))
        )
    }

    @Test
    fun `a process reads as its name then its parent product`() {
        val bare = ProcessDetailDto(id = "p1", name = "Dyeing")
        assertEquals("Dyeing", recordOptionLabel(bare))
        assertEquals(
            "Dyeing · Cotton scarf",
            recordOptionLabel(bare.copy(product = ProductDetailDto(id = "pr1", productName = "Cotton scarf")))
        )
    }

    @Test
    fun `a product and a tool read as their own name then the artisan`() {
        assertEquals(
            "Cotton scarf · Ram Kumar",
            recordOptionLabel(ProductDetailDto(id = "p1", productName = "Cotton scarf", artisanName = "Ram Kumar"))
        )
        assertEquals(
            "Bamboo comb · Ram Kumar",
            recordOptionLabel(ToolDetailDto(id = "t1", toolkitName = "Bamboo comb", artisanName = "Ram Kumar"))
        )
    }

    @Test
    fun `a record with no name at all is named by its type`() {
        assertEquals("Untitled artisan", recordOptionLabel(artisan("", "")))
        assertEquals("Untitled product", recordOptionLabel(ProductDetailDto(id = "p1")))
        assertEquals("Untitled tool", recordOptionLabel(ToolDetailDto(id = "t1")))
    }

    @Test
    fun `the separator is the spaced middle dot`() {
        assertEquals(" · ", LABEL_SEPARATOR)
    }

    private fun message(
        state: RecordListState,
        loadedFor: String?,
        selected: String = "w1",
        shown: Int = 3,
        kind: RecordSwitchKind = RecordSwitchKind.ARTISAN
    ) = recordListMessage(kind, state, loadedFor, selected, shown)

    @Test
    fun `a settled list with rows in it says nothing`() {
        assertNull(message(RecordListState.LOADED, loadedFor = "w1"))
    }

    @Test
    fun `an empty workshop is only ever announced once its rows have arrived`() {
        assertEquals(
            "No artisans in this workshop yet. Pick another workshop above, or file the first one.",
            message(RecordListState.LOADED, loadedFor = "w1", shown = 0)
        )
        assertEquals(
            "Loading this workshop's records…",
            message(RecordListState.PENDING, loadedFor = null, shown = 0)
        )
    }

    @Test
    fun `rows belonging to the workshop just left are not described as this one's`() {
        assertEquals(
            "Loading this workshop's records…",
            message(RecordListState.LOADED, loadedFor = "w1", selected = "w2", shown = 12)
        )
        assertFalse(recordsAreForWorkshop("w1", "w2"))
        assertFalse(recordsAreForWorkshop(null, "w2"))
        assertTrue(recordsAreForWorkshop("w2", "w2"))
    }

    @Test
    fun `a failed load is named as a failed load and reassures about the open record`() {
        assertEquals(
            "These artisans could not be loaded. The record open below is unaffected.",
            message(RecordListState.UNAVAILABLE, loadedFor = null, shown = 0)
        )
        assertEquals(
            "These processes could not be loaded. The record open below is unaffected.",
            message(RecordListState.UNAVAILABLE, null, shown = 0, kind = RecordSwitchKind.PROCESS)
        )
    }

    @Test
    fun `a whole list never sends a keystroke anywhere`() {
        assertFalse(shouldSearchServer(loaded = 40, total = 40, query = "ram"))
        assertFalse(shouldSearchServer(loaded = 0, total = 0, query = "ram"))
    }

    @Test
    fun `a cut list searches the server, and a blank box is not a search`() {
        assertTrue(shouldSearchServer(loaded = 100, total = 240, query = "ram"))
        assertFalse(shouldSearchServer(loaded = 100, total = 240, query = ""))
        assertFalse(shouldSearchServer(loaded = 100, total = 240, query = "   "))
    }

    @Test
    fun `the debounce is the 350ms every list page on the web already uses`() {
        assertEquals(350L, SEARCH_DEBOUNCE_MS)
    }

    @Test
    fun `a search result adds rows and never removes one`() {
        val page = listOf(SelectOption("a1", "One"), SelectOption("a2", "Two"))
        val found = listOf(SelectOption("a2", "Two"), SelectOption("a9", "Nine"))
        assertEquals(listOf("a1", "a2", "a9"), mergeRecordOptions(page, found).map { it.value })
    }

    @Test
    fun `a narrower answer never shortens the options`() {
        val open = listOf(SelectOption("editing-this-one", "The one on screen"))
        assertEquals(open, mergeRecordOptions(open, emptyList()))
        assertTrue(mergeRecordOptions(open, listOf(SelectOption("other", "Other"))).any { it.value == "editing-this-one" })
    }

    @Test
    fun `first writer wins on a duplicate id`() {
        val merged = mergeRecordOptions(listOf(SelectOption("a1", "Original")), listOf(SelectOption("a1", "Renamed")))
        assertEquals(1, merged.size)
        assertEquals("Original", merged.first().label)
    }

    @Test
    fun `a whole list says nothing about being cut`() {
        assertNull(listCutNotice(40, 40, "artisans in this workshop", ListCutReach.SEARCH))
    }

    @Test
    fun `a searchable cut invites typing rather than warning about a limit`() {
        assertEquals(
            "Showing 100 of 240 artisans in this workshop — type to search all 240.",
            listCutNotice(100, 240, "artisans in this workshop", ListCutReach.SEARCH)
        )
    }

    @Test
    fun `the other two reaches keep the wording they had`() {
        assertEquals(
            "Showing 100 of 749 artisans. To find another, use Browse records from the menu.",
            listCutNotice(100, 749, "artisans")
        )
        assertEquals(
            "Showing 100 of 749 artisans — use the pager to reach the rest, which are not searched " +
                "by the box above.",
            listCutNotice(100, 749, "artisans", ListCutReach.PAGER)
        )
    }

    @Test
    fun `the title names the record type`() {
        assertEquals("Open a different artisan", recordSwitcherTitle(RecordSwitchKind.ARTISAN))
        assertEquals("Open a different process", recordSwitcherTitle(RecordSwitchKind.PROCESS))
    }

    private fun webSwitcher(): String = repoSource(WEB_SWITCHER, "../$WEB_SWITCHER")

    @Test
    fun `the web prints the same four sentences`() {
        val web = webSwitcher()
        for (fragment in listOf(
            " could not be loaded. The record open below is unaffected.",
            "Loading this workshop's records…",
            " yet. Pick another workshop above, or file the first one.",
            "Open a different ",
        )) {
            assertTrue("$WEB_SWITCHER no longer contains: $fragment", web.contains(fragment))
        }
    }

    @Test
    fun `the web opens on the same All workshops row and says the same two sentences`() {
        val web = webSwitcher()
        assertTrue(web.contains("ALL_WORKSHOPS_LABEL = \"$ALL_WORKSHOPS_LABEL\""))
        assertTrue(web.contains("`Loading \${recordPlural(kind)}…`"))
        assertTrue(web.contains("`No \${recordPlural(kind)} yet.`"))
    }

    @Test
    fun `the web declares the same record kinds in the same order`() {
        val expected = RecordSwitchKind.values().joinToString(", ") { "\"${it.singular}\"" }
        assertTrue(
            "$WEB_SWITCHER must declare RECORD_KINDS as [$expected]",
            webSwitcher().contains("RECORD_KINDS = [$expected] as const")
        )
    }

    @Test
    fun `the web agrees about the separator and the debounce`() {
        val web = webSwitcher()
        assertTrue(web.contains("LABEL_SEPARATOR = \"$LABEL_SEPARATOR\""))
        assertTrue(web.contains("SEARCH_DEBOUNCE_MS = $SEARCH_DEBOUNCE_MS;"))
    }

    @Test
    fun `the web's capped-list sentences still match this client's`() {
        val web = repoSource(WEB_CAPPED_LIST, "../$WEB_CAPPED_LIST")
        for (fragment in listOf(
            " — type to search all ",
            ". To find another, use Browse records from the menu.",
            " — use the pager to reach the rest, which are not searched by the box above.",
            " could be loaded. Try again.",
        )) {
            assertTrue("$WEB_CAPPED_LIST no longer contains: $fragment", web.contains(fragment))
        }
    }

    @Test
    fun `the update-existing picker draws the switcher, not just the edit screen`() {
        val main = repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        for (screen in listOf("RecordPickerScreen", "EditScreen")) {
            val start = main.indexOf("private fun $screen(")
            assertTrue("$screen must exist in MainActivity.kt", start >= 0)
            val next = main.indexOf("\nprivate fun ", start + 1)
            val body = if (next > start) main.substring(start, next) else main.substring(start)
            assertTrue(
                "$screen must compose RecordSwitcher. A control mounted on only one of these two " +
                    "screens is the 0.0.3 defect: the update page showed the old unfiltered dropdown " +
                    "while the file-wide assertion passed on the other screen's mount.",
                body.contains("RecordSwitcher(")
            )
            assertTrue(
                "$screen must resolve the kind through recordSwitchKindFor so the five switchable " +
                    "kinds stay one list rather than two that can disagree.",
                body.contains("recordSwitchKindFor(")
            )
        }
    }

    @Test
    fun `the update-existing picker keeps its unfiltered list underneath`() {
        val main = repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        val start = main.indexOf("private fun RecordPickerScreen(")
        val next = main.indexOf("\nprivate fun ", start + 1)
        val body = if (next > start) main.substring(start, next) else main.substring(start)
        assertTrue(
            "RecordPickerScreen must keep the unfiltered dropdown: it is the only way to a record " +
                "whose workshopId is null, which the workshop filter has no parameter to express.",
            body.contains("Update existing ") && body.contains("Or tap a recent record")
        )
    }

    @Test
    fun `this client mounts the switcher on its edit screen`() {
        val main = repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertTrue("EditScreen must compose RecordSwitcher", main.contains("RecordSwitcher("))
        assertTrue("EditScreen must be handed a way to open another record", main.contains("onOpenRecord = "))
        assertTrue(main.contains("Screen.Edit(s.mode, picked)"))
    }

    @Test
    fun `the web mounts it on every update surface it has`() {
        for ((page, kind) in WEB_UPDATE_SURFACES) {
            val source = repoSource(page, "../$page")
            assertTrue(
                "$page must mount the <RecordSwitcher> element",
                Regex("""<RecordSwitcher[\s/>]""").containsMatchIn(source)
            )
            assertTrue("$page must mount it for kind=\"${kind.singular}\"", source.contains("kind=\"${kind.singular}\""))
        }
    }

    private companion object {
        const val WEB_SWITCHER = "frontend/components/forms/RecordSwitcher.tsx"
        const val WEB_CAPPED_LIST = "frontend/components/data/cappedList.ts"

        val WEB_UPDATE_SURFACES = listOf(
            "frontend/app/(protected)/artisans/[id]/edit/page.tsx" to RecordSwitchKind.ARTISAN,
            "frontend/app/(protected)/products/[id]/edit/page.tsx" to RecordSwitchKind.PRODUCT,
            "frontend/app/(protected)/tools/[id]/edit/page.tsx" to RecordSwitchKind.TOOL,
            "frontend/app/(protected)/crafts/page.tsx" to RecordSwitchKind.CRAFT,
            "frontend/app/(protected)/processes/page.tsx" to RecordSwitchKind.PROCESS,
        )
    }
}
