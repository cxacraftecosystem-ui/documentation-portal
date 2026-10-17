package com.fieldrepository.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CONNECTION_FAILED_SENTENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five problems the 2026-10-17 emulator smoke test found with the wifi off and on the update
 * screens: banners hiding the end of long forms, a connection error raised under the offline banner,
 * the record switcher opening on an unrelated workshop, unreadable actions on the "files ready"
 * tile, and an admin-view switch that forgot itself.
 */
class SmokeFixesTest {

    private val main: String by lazy {
        repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
    }

    // ── 1. The banner stack's footprint ─────────────────────────────────────────────────────

    @Test
    fun `no banner, no reserve`() {
        assertEquals(0.dp, bannerClearance(120.dp, shown = false))
        assertEquals(0.dp, bannerClearance(0.dp, shown = true))
    }

    @Test
    fun `a showing stack reserves its measured height plus the gap`() {
        assertEquals(120.dp + BANNER_STACK_GAP, bannerClearance(120.dp, shown = true))
    }

    @Test
    fun `the page clears the island and the banners together`() {
        assertEquals(96.dp, pageBottomReserve(64.dp, 32.dp))
        assertEquals(64.dp, pageBottomReserve(64.dp, 0.dp))
    }

    @Test
    fun `the page and the message surface both pad by the combined reserve`() {
        assertTrue(main.contains(".padding(bottom = pageReserve),"))
        val banner = main.indexOf("AppMessageBanner(\n            state = appMessage,")
        assertTrue("AppMessageBanner must still be mounted on the page", banner >= 0)
        assertTrue(
            "the message surface must sit above the banners, never under them",
            main.substring(banner, banner + 220).contains(".padding(bottom = pageReserve)")
        )
        assertTrue("the root must measure the banner stack", main.contains("bannerStackHeight = with(rootDensity)"))
    }

    // ── 2. No "Could not connect" under the offline banner ──────────────────────────────────

    @Test
    fun `the connection error is swallowed only while the banner already says it`() {
        assertTrue(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.ERROR, bannerShowing = true))
        assertFalse(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.ERROR, bannerShowing = false))
        assertFalse(bannerAlreadySays("Name is required.", AppMessageTone.ERROR, bannerShowing = true))
        assertFalse(bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.SUCCESS, bannerShowing = true))
    }

    @Test
    fun `every message passes the offline check`() {
        val start = main.indexOf("fun showMessage(")
        assertTrue(start >= 0)
        assertTrue(main.substring(start, start + 900).contains("bannerAlreadySays("))
    }

    @Test
    fun `upload media queues when offline instead of trying each file`() {
        val start = main.indexOf("private fun AndroidMediaForm(")
        val body = main.substring(start, main.indexOf("\nprivate fun ", start + 1))
        assertTrue(body.contains("if (!repository.isOnline(context) && queueBatch(uris))"))
        assertEquals(
            "1 file saved on this device. It uploads when you're back online.",
            com.fieldrepository.app.queuedMediaSentence(1)
        )
        assertEquals(
            "3 files saved on this device. They upload when you're back online.",
            com.fieldrepository.app.queuedMediaSentence(3)
        )
    }

    // ── 3. The switcher opens on All workshops, then the record's own ───────────────────────

    @Test
    fun `all workshops is the first row and is the blank id`() {
        val rows = recordSwitchWorkshopOptions(listOf(SelectOption("w1", "Lacquerware"), SelectOption("w2", "Toolkit")))
        assertEquals(SelectOption("", ALL_WORKSHOPS_LABEL), rows.first())
        assertEquals(listOf("", "w1", "w2"), rows.map { it.value })
    }

    @Test
    fun `the record's own workshop is taken once known, and a choice is never overridden`() {
        val known = listOf("w1", "w2")
        assertEquals("w2", recordSwitchWorkshopFor("", "w2", known))
        assertEquals("", recordSwitchWorkshopFor("", null, known))
        assertEquals("", recordSwitchWorkshopFor("", "gone", known))
        assertEquals("w1", recordSwitchWorkshopFor("w1", "w2", known))
    }

    @Test
    fun `under all workshops nothing is said about a workshop`() {
        val kind = RecordSwitchKind.ARTISAN
        assertEquals("Loading artisans…", recordListMessage(kind, RecordListState.PENDING, null, "", 0))
        assertEquals("No artisans yet.", recordListMessage(kind, RecordListState.LOADED, "", "", 0))
        assertNull(recordListMessage(kind, RecordListState.LOADED, "", "", 4))
        assertEquals("artisans", kind.nounFor(""))
        assertEquals("artisans in this workshop", kind.nounFor("w1"))
    }

    @Test
    fun `neither switcher runs the most-recent-workshop default`() {
        assertFalse(main.contains("val switchWorkshop = rememberWorkshopPicker(repository, isEdit = false"))
        assertEquals(
            2,
            Regex("""val switchWorkshop = rememberWorkshopPicker\(repository, isEdit = true, initialId = null\)""")
                .findAll(main).count()
        )
    }

    // ── 4. Actions on the brand tile ────────────────────────────────────────────────────────

    @Test
    fun `tile ink clears AA on both tiles, and its outline is visible`() {
        for (tile in listOf(FieldPalette.Purple800, FieldPalette.Purple900)) {
            assertTrue("label on $tile", ratio(FieldPalette.Purple50, tile) >= 4.5)
            val outline = FieldPalette.Purple50.copy(alpha = TILE_OUTLINE_ALPHA).compositeOver(tile)
            assertTrue("outline on $tile", ratio(outline, tile) >= 3.0)
        }
    }

    @Test
    fun `the files-ready tile draws its actions in tile ink`() {
        val start = main.indexOf("file(s) ready")
        val tile = main.substring(start, start + 2600)
        assertTrue(tile.contains("onTile = true"))
        assertTrue(tile.contains("Text(\"Clear batch\", color = tileInk"))
        assertFalse("the bare primary-coloured TextButton is what vanished", tile.contains("TextButton(onClick = { selectedUris = emptyList() })"))
    }

    // ── 5. Admin view remembered per account ────────────────────────────────────────────────

    @Test
    fun `the stored choice wins, else the role default`() {
        assertTrue(restoredAdminView(null, isMasterAdmin = true))
        assertFalse(restoredAdminView(null, isMasterAdmin = false))
        assertTrue(restoredAdminView(true, isMasterAdmin = false))
        assertFalse(restoredAdminView(false, isMasterAdmin = true))
    }

    @Test
    fun `each account has its own key`() {
        assertTrue(adminViewKey("a") != adminViewKey("b"))
    }

    @Test
    fun `every flip of the switch is written`() {
        assertFalse(Regex("""adminViewRequested = (true|!adminView)""").containsMatchIn(main))
        assertTrue(main.contains("writeAdminView(adminViewContext, user.id, on)"))
    }

    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
