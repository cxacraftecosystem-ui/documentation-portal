package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search panel's pure rules — ranking, grouping, de-duplication, recents — and the role filter
 * on the screens it offers. Shared verbatim, package line aside, by both portals' Android apps.
 */
class AppSearchTest {

    private fun item(group: AppSearchGroup, key: String, title: String, keywords: List<String> = emptyList()) =
        AppSearchItem(group, key, title, Icons.Filled.Star, keywords = keywords) {}

    // ── appSearchScore ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `scores exact, prefix, word prefix, substring and other in that order`() {
        assertEquals(0, appSearchScore("Bagru", "bagru"))
        assertEquals(1, appSearchScore("Bagru prints", "bag"))
        assertEquals(2, appSearchScore("Hand block, Bagru", "bag"))
        assertEquals(3, appSearchScore("Sambagru", "bag"))
        assertEquals(4, appSearchScore("Indigo", "bag"))
    }

    @Test
    fun `scoring ignores case and surrounding space, and a blank query matches nothing well`() {
        assertEquals(0, appSearchScore("  Map ", " MAP"))
        assertEquals(4, appSearchScore("Map", "   "))
    }

    // ── groupAppSearchResults ──────────────────────────────────────────────────────────────────

    @Test
    fun `groups come out in the fixed order and empty groups are left out`() {
        val sections = groupAppSearchResults(
            listOf(
                item(AppSearchGroup.MEDIA, "m1", "bag.jpg"),
                item(AppSearchGroup.ARTISANS, "a1", "Bagru"),
                item(AppSearchGroup.SCREENS, "s1", "Browse records")
            ),
            "b"
        )
        assertEquals(
            listOf(AppSearchGroup.SCREENS, AppSearchGroup.ARTISANS, AppSearchGroup.MEDIA),
            sections.map { it.group }
        )
    }

    @Test
    fun `within a group better matches rise and ties keep their arrival order`() {
        val sections = groupAppSearchResults(
            listOf(
                item(AppSearchGroup.ARTISANS, "1", "Old Kota weaver"),
                item(AppSearchGroup.ARTISANS, "2", "Kotak"),
                item(AppSearchGroup.ARTISANS, "3", "Kota"),
                item(AppSearchGroup.ARTISANS, "4", "Mukota"),
                item(AppSearchGroup.ARTISANS, "5", "Kotah")
            ),
            "kota"
        )
        assertEquals(listOf("3", "2", "5", "1", "4"), sections.single().items.map { it.key })
    }

    @Test
    fun `each group is capped and still reports how many matched`() {
        val many = (1..9).map { item(AppSearchGroup.TOOLS, "t$it", "Chisel $it") }
        val section = groupAppSearchResults(many, "chisel").single()
        assertEquals(APP_SEARCH_GROUP_CAP, section.items.size)
        assertEquals(9, section.total)
    }

    @Test
    fun `the same record from the server and from the phone is shown once, server first`() {
        val server = item(AppSearchGroup.ARTISANS, "a1", "Ramesh (server)")
        val cached = item(AppSearchGroup.ARTISANS, "a1", "Ramesh (cached)")
        val other = item(AppSearchGroup.CRAFTS, "a1", "Same id, other group")
        val sections = groupAppSearchResults(listOf(server, cached, other), "ramesh")
        assertEquals(listOf("Ramesh (server)"), sections.first { it.group == AppSearchGroup.ARTISANS }.items.map { it.title })
        assertEquals(1, sections.first { it.group == AppSearchGroup.CRAFTS }.items.size)
    }

    // ── appSearchLocalMatch ────────────────────────────────────────────────────────────────────

    @Test
    fun `local items match on title, subtitle or keyword, never on a blank query`() {
        val theme = item(AppSearchGroup.SETTINGS, "theme", "Theme", keywords = listOf("dark mode"))
        assertTrue(appSearchLocalMatch(theme, "them"))
        assertTrue(appSearchLocalMatch(theme, "DARK"))
        assertFalse(appSearchLocalMatch(theme, "font"))
        assertFalse(appSearchLocalMatch(theme, " "))
    }

    // ── recents ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a recent search goes to the front, once, and the list is capped`() {
        var recents = emptyList<String>()
        (1..10).forEach { recents = pushRecentSearch(recents, "q$it") }
        assertEquals(APP_SEARCH_RECENT_MAX, recents.size)
        assertEquals("q10", recents.first())
        recents = pushRecentSearch(recents, "  Q5 ")
        assertEquals("Q5", recents.first())
        assertEquals(1, recents.count { it.equals("q5", ignoreCase = true) })
        assertEquals(recents, pushRecentSearch(recents, "   "))
    }

    @Test
    fun `removing a recent search ignores case`() {
        assertEquals(listOf("indigo"), removeRecentSearch(listOf("Bagru", "indigo"), "bagru"))
    }

    // ── quick return ───────────────────────────────────────────────────────────────────────────

    private val header = 150
    private val hideAfter = 60f

    private fun step(s: QuickReturnStep, dy: Float, offset: Int, pinned: Boolean = false) =
        quickReturnStep(s, dy, offset, header, hideAfter, pinned)

    @Test
    fun `scrolling down hides the header only after the travel threshold and past its height`() {
        var s = QuickReturnStep(shown = true)
        s = step(s, -30f, 400)
        assertTrue("30px down is below the threshold", s.shown)
        s = step(s, -40f, 440)
        assertFalse("70px down, page past the header: hidden", s.shown)
    }

    @Test
    fun `it never hides while the page has not moved past the header's own height`() {
        var s = QuickReturnStep(shown = true)
        s = step(s, -100f, 100)
        assertTrue(s.shown)
        s = step(s, -40f, 140)
        assertTrue("still within the header's height", s.shown)
        s = step(s, -20f, 160)
        assertFalse(s.shown)
    }

    @Test
    fun `any upward scroll brings it back at once, even mid-page`() {
        val hidden = QuickReturnStep(shown = false, travelPx = 0f)
        assertTrue(step(hidden, 1f, 2000).shown)
        assertEquals("the travel restarts", 0f, step(hidden, 1f, 2000).travelPx)
    }

    @Test
    fun `at the very top it is shown, whatever the direction`() {
        val hidden = QuickReturnStep(shown = false)
        assertTrue(step(hidden, -50f, 0).shown)
        assertTrue(step(hidden, 0f, 0).shown)
    }

    @Test
    fun `pinned by the open search field it stays shown while scrolling down`() {
        var s = QuickReturnStep(shown = true)
        repeat(10) { s = step(s, -100f, 500 + it * 100, pinned = true) }
        assertTrue(s.shown)
    }

    @Test
    fun `no movement changes nothing, and a hidden header stays hidden going further down`() {
        val hidden = QuickReturnStep(shown = false, travelPx = 12f)
        assertEquals(hidden, step(hidden, 0f, 800))
        assertFalse(step(hidden, -500f, 1300).shown)
    }

    @Test
    fun `reduced motion snaps instead of sliding`() {
        assertEquals(0, quickReturnSlideMillis(reducedMotion = true))
        assertEquals(QUICK_RETURN_SLIDE_MS, quickReturnSlideMillis(reducedMotion = false))
        assertTrue(QUICK_RETURN_SLIDE_MS > 0)
    }

    // ── screens follow the menu's role filter ──────────────────────────────────────────────────

    private fun user(role: String) = UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    // Every tier this app's FieldPermissions ladder knows (six on the documentation portal).
    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR", "ADMIN", "MASTER_ADMIN"
    )

    @Test
    fun `the screens a search offers are exactly the menu this role sees`() {
        everyRole.forEach { role ->
            listOf(true, false).forEach { adminMode ->
                val visible = visibleNavItems(user(role), adminMode)
                val screens = appSearchScreenItems(visible) {}
                assertEquals("$role/$adminMode", visible.map { it.label }, screens.map { it.title })
                assertTrue(screens.all { it.group == AppSearchGroup.SCREENS })
            }
        }
        val volunteer = appSearchScreenItems(visibleNavItems(user("CROWDSOURCE_VOLUNTEER"), false)) {}
        assertFalse("a volunteer is never offered admin screens", volunteer.any { it.title == "Manage users" })
    }

    @Test
    fun `every setting is in the settings group and opens something`() {
        var opened = 0
        val settings = appSearchSettingItems { opened++ }
        assertTrue(settings.isNotEmpty())
        assertTrue(settings.all { it.group == AppSearchGroup.SETTINGS })
        settings.forEach { it.onOpen() }
        assertEquals(settings.size, opened)
        assertTrue(settings.any { appSearchLocalMatch(it, "dark mode") })
    }
}
