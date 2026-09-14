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

/**
 * THE TWO-LEVEL RECORD PICKER'S RULES, AND THE WEB'S COPY OF THEM.
 *
 * ── WHY THESE ARE FUNCTIONS AND WHY THIS SUITE COMPOSES NOTHING ────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot put
 * a picker on a screen and look at it. That is the constraint `RecordPickersTest`, `AccessRosterTest`
 * and `WorkshopOptionsTest` already worked around the same way — by lifting the ruling out of the
 * composable — and the rulings here need it more than most, because three of the four states
 * [recordListMessage] decides between cannot be produced at a desk on purpose:
 *
 *   - a workshop holding more than a hundred records of one type;
 *   - a request still in flight at the instant somebody reads the screen;
 *   - a handset with no signal — the state this product exists for, and the state nobody has while
 *     they are testing it.
 *
 * ── AND WHY HALF OF IT READS A `.tsx` FILE OFF DISK ────────────────────────────────────────────
 *
 * "The handset and the browser offer one control" is a claim about TWO clients, and the failure mode
 * of one requirement implemented twice is that the two agree on the day they are written and drift
 * on the next edit — which nobody notices, because nobody re-reads a `.tsx` while editing Kotlin.
 * This feature starts from evidence that it happens: `RecordPickerScreen` in `MainActivity.kt` and
 * the web's pickers were already spelling the same five option labels by hand, identically, with
 * nothing anywhere asserting it. Hand-spelled agreement is agreement right up until somebody edits
 * one of them.
 *
 * So the strings are checked from both ends. The Kotlin half below computes what this client would
 * actually print; the web half asserts that the browser's source still contains the same sentence.
 * Change a rule on one side and this suite goes red, which is the entire point of it.
 */
class RecordSwitcherTest {

    // ── The labels ─────────────────────────────────────────────────────────────────────────────

    private fun artisan(name: String, place: String) =
        ArtisanDto(id = "a1", name = name, place = place, status = "APPROVED")

    @Test
    fun `an artisan reads as name then place`() {
        assertEquals("Ram Kumar · Bagru", recordOptionLabel(artisan("Ram Kumar", "Bagru")))
    }

    /**
     * THE HAND-TYPED MIDDLE DOT THAT USED TO PRINT WITH NOTHING AFTER IT.
     *
     * `RecordPickerScreen` built this label as `"${it.name} · ${it.place}"`, which on a row with no
     * place renders "Ram Kumar · " — a separator promising a second half that is not coming. Two of
     * the five templates had the defect (the artisan's `place` and the product's `artisanName` are
     * both blank-able) and the other three had a `let` guard, so the same list printed two different
     * shapes depending on which record type it held.
     */
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

    /**
     * A row with nothing to say for itself is still a row, and still has to be pickable — it is a
     * real record and the researcher may well be opening it BECAUSE its name is empty. A blank option
     * is one the eye slides straight past and the finger cannot aim at.
     */
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

    // ── The sentence under the dropdown ────────────────────────────────────────────────────────

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

    /**
     * THE ARM THAT MATTERS MOST. "No artisans in this workshop yet" is a claim about the repository,
     * and the researcher's reasonable response to it is to go and file one — so it must never be
     * printed off rows that have not arrived. It is the same defect `recordPickers.ts` was written
     * after, one dropdown over, where "No artisans are linked to this craft yet" was printed off a
     * list that had simply not loaded.
     */
    @Test
    fun `an empty workshop is only ever announced once its rows have arrived`() {
        assertEquals(
            "No artisans in this workshop yet. Pick another workshop above, or file the first one.",
            message(RecordListState.LOADED, loadedFor = "w1", shown = 0)
        )
        // Not yet asked.
        assertEquals(
            "Loading this workshop's records…",
            message(RecordListState.PENDING, loadedFor = null, shown = 0)
        )
    }

    /**
     * THE FRAME IN WHICH THE ROWS BELONG TO THE PREVIOUS WORKSHOP.
     *
     * `state` is LOADED and `shown` is non-zero, so every boolean a shorter implementation would have
     * reached for says "we have an answer" — and the answer is about workshop w1 while the dropdown
     * now reads w2. This is why the loaded-for workshop is carried as an ID and not as a flag.
     */
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

    /**
     * A FAILED REQUEST IS NOT EVIDENCE ABOUT A WORKSHOP, and the sentence says so as well as saying
     * the thing the reader actually wants to know at that moment. The commonest way to reach this
     * arm is a researcher in a courtyard with no signal, and the question in their head is whether
     * their work is still there.
     */
    @Test
    fun `a failed load is named as a failed load and reassures about the open record`() {
        assertEquals(
            "These artisans could not be loaded. The record open below is unaffected.",
            message(RecordListState.UNAVAILABLE, loadedFor = null, shown = 0)
        )
        // The bare plural, NOT "artisans in this workshop": the failure is not evidence about the
        // workshop, and attaching it to one would be the same overclaim in the other direction.
        assertEquals(
            "These processes could not be loaded. The record open below is unaffected.",
            message(RecordListState.UNAVAILABLE, null, shown = 0, kind = RecordSwitchKind.PROCESS)
        )
    }

    // ── When a keystroke is allowed to cost a request ──────────────────────────────────────────

    /**
     * THE ORDINARY CASE COSTS NOTHING, and that is the whole design. One workshop's records fit
     * inside the 100-row page in practice, the sheet's own filter is then the complete answer, it is
     * instant, and it works with no signal — which is the half that matters for this product.
     */
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

    // ── Merging, and what it protects ──────────────────────────────────────────────────────────

    @Test
    fun `a search result adds rows and never removes one`() {
        val page = listOf(SelectOption("a1", "One"), SelectOption("a2", "Two"))
        val found = listOf(SelectOption("a2", "Two"), SelectOption("a9", "Nine"))
        assertEquals(listOf("a1", "a2", "a9"), mergeRecordOptions(page, found).map { it.value })
    }

    /**
     * THE RECORD BEING EDITED MUST NOT VANISH FROM ITS OWN PICKER because somebody typed. Replacing
     * the options with a narrower answer is what would do it, and the control would then be unable
     * to draw its own current value — which `recordPickers.useRecordOffPage` argues at length is
     * worse than a list that is merely short.
     */
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

    // ── The cut sentence ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a whole list says nothing about being cut`() {
        assertNull(listCutNotice(40, 40, "artisans in this workshop", ListCutReach.SEARCH))
    }

    /**
     * The SEARCH arm is the only one that does not end by admitting a limit, because there is not one
     * to admit: the term goes to the server, so every row counted in `total` is reachable by typing.
     * Printing the arithmetic anyway is still worth the line — a reader looking at 100 rows and no
     * sentence cannot tell whether that is the workshop or the ceiling.
     */
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
            "Showing 100 of 749 artisans — the other 649 are not on this list, and typing here " +
                "searches only the 100 shown.",
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

    // ── The web's copy of all of it ────────────────────────────────────────────────────────────

    /**
     * WHY THE WEB SENTENCES ARE CHECKED AS FRAGMENTS AND NOT AS WHOLE LITERALS.
     *
     * Both sides build these strings by interpolation and the two languages spell that differently —
     * `${kind.plural}` here, `${recordPlural(kind)}` there — so the whole literal can never match
     * character for character and asserting that it does would mean asserting on the interpolation
     * syntax instead of on the English. What IS comparable is every run of text OUTSIDE the holes,
     * and those are what a researcher reads. The Kotlin half above already pins the assembled
     * sentence, so between the two halves the whole string is covered.
     */
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

    /**
     * THE FIVE KINDS, IN ORDER, BUILT FROM THIS ENUM.
     *
     * Derived from `RecordSwitchKind.values()` rather than typed out, so adding a sixth kind here
     * fails this test until the web declares it too — which is the direction the drift actually runs,
     * because a Kotlin edit is the one a `.tsx` reader will not see. Order is asserted along with
     * membership: these two lists are compared elsewhere by index, and a set comparison would pass a
     * reordering that quietly changes which kind a positional read resolves to.
     */
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

    /**
     * The cut sentences live in a THIRD file on the web (`components/data/cappedList.ts`) because
     * several screens print them, so they are read from there rather than from the switcher.
     */
    @Test
    fun `the web's capped-list sentences still match this client's`() {
        val web = repoSource(WEB_CAPPED_LIST, "../$WEB_CAPPED_LIST")
        for (fragment in listOf(
            " — type to search all ",
            " are not on this list, and typing here searches only the ",
            " — use the pager to reach the rest, which are not searched by the box above.",
            " could be listed here — this is not an empty repository.",
        )) {
            assertTrue("$WEB_CAPPED_LIST no longer contains: $fragment", web.contains(fragment))
        }
    }

    /**
     * THE CONTROL IS ACTUALLY MOUNTED, on this client and on all five of the web's update surfaces.
     *
     * Every assertion above is about a rule, and a rule nothing draws is a rule that ships as nothing
     * at all — which is the defect `record-parity-fields-unit.spec.ts` was written after, where six
     * columns landed in the database, the schemas and both clients' types while NOTHING RENDERED ANY
     * OF THEM. A picker that exists and is on no page is the same shape of defect, and it is exactly
     * the kind an edit to a page file removes silently: no compiler, type check or lint has an
     * opinion about a `<section>` that is one component shorter than it was.
     */
    @Test
    fun `this client mounts the switcher on its edit screen`() {
        val main = repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertTrue("EditScreen must compose RecordSwitcher", main.contains("RecordSwitcher("))
        assertTrue("EditScreen must be handed a way to open another record", main.contains("onOpenRecord = "))
        // And the picker's destination must remain the one every other route into editing uses. A
        // switcher that grew its own loading path would be a second, looser way in — see the header
        // of `ui/RecordSwitcher.kt`.
        assertTrue(main.contains("Screen.Edit(s.mode, picked)"))
    }

    /**
     * THE ELEMENT NAME IS MATCHED WITH A DELIMITER AFTER IT, and that is not pedantry — it is what
     * this assertion failed a negative control over. A bare `contains("<RecordSwitcher")` is also
     * satisfied by `<RecordSwitcherX`, so renaming the component on one page and leaving a stale
     * reference behind would have read as "still mounted". Requiring whitespace or a closing bracket
     * makes the match the element rather than a prefix of one.
     *
     * The KIND is asserted alongside it for the same reason in the other direction: five pages each
     * mounting this control is worth nothing if two of them mount it for the same record type, which
     * is the copy-paste every one of these five pages was created by.
     */
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

        /**
         * EVERY WEB SURFACE THAT UPDATES A RECORD — the whole list, and the reason it is a list.
         *
         * Three of the five record types have a route of their own; crafts and processes are edited
         * by an inline form on their list page and reached by `?edit=<id>`. Naming all five here is
         * what makes "on the update page for EVERY record type" a checked claim rather than a
         * sentence in a commit message: the two inline ones are the easy pair to forget, precisely
         * because they do not look like edit pages from the route table.
         */
        val WEB_UPDATE_SURFACES = listOf(
            "frontend/app/(protected)/artisans/[id]/edit/page.tsx" to RecordSwitchKind.ARTISAN,
            "frontend/app/(protected)/products/[id]/edit/page.tsx" to RecordSwitchKind.PRODUCT,
            "frontend/app/(protected)/tools/[id]/edit/page.tsx" to RecordSwitchKind.TOOL,
            "frontend/app/(protected)/crafts/page.tsx" to RecordSwitchKind.CRAFT,
            "frontend/app/(protected)/processes/page.tsx" to RecordSwitchKind.PROCESS,
        )
    }
}
