package com.fieldrepository.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom island's pure rules: which slots the bar shows, what goes behind "More", and when
 * "More" is lit. Shared verbatim, package line aside, by the designer and documentation portals' Android apps.
 */
class IslandBottomNavTest {

    // ── splitIsland, on plain values ───────────────────────────────────────────────────────────

    @Test
    fun `five or fewer are all shown and there is no More`() {
        for (n in 0..5) {
            val items = (1..n).map { "d$it" }
            val split = splitIsland(items)
            assertEquals(items, split.shown)
            assertTrue(split.overflow.isEmpty())
            assertFalse("no More with $n items", split.hasMore)
        }
    }

    @Test
    fun `six shows the first four in order and puts two behind More`() {
        val split = splitIsland(listOf("a", "b", "c", "d", "e", "f"))
        assertEquals(listOf("a", "b", "c", "d"), split.shown)
        assertEquals(listOf("e", "f"), split.overflow)
        assertTrue(split.hasMore)
    }

    @Test
    fun `the bar never draws more than five slots, More included`() {
        for (n in 6..30) {
            val split = splitIsland((1..n).map { it })
            assertEquals("four shown for $n", 4, split.shown.size)
            assertEquals("the rest behind More for $n", n - 4, split.overflow.size)
            // Nothing lost, nothing duplicated, order kept.
            assertEquals((1..n).toList(), split.shown + split.overflow)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cap below two is refused`() {
        splitIsland(listOf(1, 2, 3), maxVisible = 1)
    }

    // ── islandSlots and the active rules ───────────────────────────────────────────────────────

    private fun entry(label: String) = IslandEntry(label, Icons.Filled.Star) {}

    private val roots = listOf(entry("Dashboard"), entry("Walkthrough"))
    private val groups = listOf(
        IslandGroup("Record", listOf(entry("Record artisan"))),
        IslandGroup("Browse", listOf(entry("Map"), entry("Tasks"))),
        IslandGroup("Admin", listOf(entry("Settings hub"))),
        IslandGroup("Account", listOf(entry("Settings"), entry("Open the web portal")))
    )

    @Test
    fun `slots are the roots then the non-empty groups, in order`() {
        val slots = islandSlots(roots, groups + IslandGroup("Empty", emptyList()))
        assertEquals(
            listOf("Dashboard", "Walkthrough", "Record", "Browse", "Admin", "Account"),
            slots.map { it.label }
        )
    }

    @Test
    fun `an empty group never pushes a real one behind More`() {
        val withoutAdmin = groups.map { if (it.label == "Admin") IslandGroup("Admin", emptyList()) else it }
        val split = splitIsland(islandSlots(roots, withoutAdmin))
        assertFalse(split.hasMore)
        assertEquals(listOf("Dashboard", "Walkthrough", "Record", "Browse", "Account"), split.shown.map { it.label })
    }

    @Test
    fun `More is lit only when the current destination is behind it`() {
        val split = splitIsland(islandSlots(roots, groups))
        assertEquals(listOf("Admin", "Account"), split.overflow.map { it.label })

        assertTrue(isIslandMoreActive(split, "Settings hub"))
        assertTrue(isIslandMoreActive(split, "Settings"))
        assertFalse(isIslandMoreActive(split, "Dashboard"))
        assertFalse(isIslandMoreActive(split, "Map"))
        assertFalse(isIslandMoreActive(split, null))
        assertFalse(isIslandMoreActive(split, "Nowhere"))
    }

    @Test
    fun `a slot is active for its own label or for an entry of its group`() {
        val slots = islandSlots(roots, groups)
        val dashboard = slots.first { it.label == "Dashboard" }
        val browse = slots.first { it.label == "Browse" }
        assertTrue(isIslandSlotActive(dashboard, "Dashboard"))
        assertFalse(isIslandSlotActive(dashboard, "Map"))
        assertTrue(isIslandSlotActive(browse, "Tasks"))
        // A group's own label is not a destination, so it never lights the group.
        assertFalse(isIslandSlotActive(browse, "Browse"))
        assertFalse(isIslandSlotActive(browse, null))
    }

    @Test
    fun `More with nothing hidden is never lit`() {
        val split = splitIsland(islandSlots(roots, groups.take(2)))
        assertFalse(split.hasMore)
        assertFalse(isIslandMoreActive(split, "Dashboard"))
    }

    // ── against the real menu model, role by role ──────────────────────────────────────────────

    private fun user(role: String) = UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    /** The slots the host builds for [role]: the same projection of [visibleNavItems] it renders. */
    private fun slotsFor(role: String, adminMode: Boolean): List<IslandSlot> {
        val items = visibleNavItems(user(role), adminMode)
        val rootEntries = items.filter { it.group == null }.map { entry(it.label) }
        val groupEntries = NavGroup.entries.map { group ->
            IslandGroup(group.label, items.filter { it.group == group }.map { entry(it.label) })
        }
        return islandSlots(rootEntries, groupEntries)
    }

    @Test
    fun `a volunteer has five slots and no More`() {
        val split = splitIsland(slotsFor("CROWDSOURCE_VOLUNTEER", adminMode = false))
        assertFalse(split.hasMore)
        assertEquals(listOf("Dashboard", "Walkthrough", "Record", "Browse", "Account"), split.shown.map { it.label })
    }

    @Test
    fun `an admin with admin view on gets four slots and More holding Admin and Account`() {
        val split = splitIsland(slotsFor("MASTER_ADMIN", adminMode = true))
        assertEquals(listOf("Dashboard", "Walkthrough", "Record", "Browse"), split.shown.map { it.label })
        assertEquals(listOf("Admin", "Account"), split.overflow.map { it.label })
        assertTrue(isIslandMoreActive(split, "Settings hub"))
        assertFalse(isIslandMoreActive(split, "Dashboard"))
    }

    @Test
    fun `an admin with admin view off is back to five slots`() {
        val split = splitIsland(slotsFor("ADMIN", adminMode = false))
        assertFalse(split.hasMore)
        assertFalse(split.shown.any { it.label == NavGroup.ADMIN.label })
    }

    @Test
    fun `role filtering decides what is behind More, never the other way round`() {
        // Every role, both admin-view states: the split is a partition of exactly the visible slots.
        listOf(
            "CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "INSPECTOR",
            "PROFESSOR", "ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"
        ).forEach { role ->
            listOf(true, false).forEach { adminMode ->
                val slots = slotsFor(role, adminMode)
                val split = splitIsland(slots)
                assertEquals("$role/$adminMode", slots.map { it.label }, (split.shown + split.overflow).map { it.label })
                assertTrue("$role/$adminMode draws at most five", split.shown.size + (if (split.hasMore) 1 else 0) <= ISLAND_MAX_VISIBLE)
                // Every entry behind More is one this role may see — the sheet renders nothing else.
                val behindMore = split.overflow.flatMap { slot ->
                    when (slot) {
                        is IslandSlot.Root -> listOf(slot.label)
                        is IslandSlot.Group -> slot.group.entries.map { it.label }
                    }
                }
                val visibleLabels = visibleNavItems(user(role), adminMode).map { it.label }.toSet()
                assertTrue("$role/$adminMode", visibleLabels.containsAll(behindMore))
            }
        }
    }
}
