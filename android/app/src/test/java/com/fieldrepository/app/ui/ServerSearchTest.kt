package com.fieldrepository.app.ui

import com.fieldrepository.app.data.PageResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * THE SERVER-SEARCHED PICKER (DC-A09) AND THE LAZY PAGE (DC-A34), as pure rules.
 *
 * `ui/ServerSearch.kt` decides what a picker sheet lists for a query, whether there is another page,
 * and what it says when the server cannot answer. `ui/LazyPage.kt` decides how a lazy page reports its
 * scroll to the quick-return header and when a Back restore can land. None of it needs a renderer.
 */
class ServerSearchTest {

    private data class Row(val id: String, val name: String)

    private fun rows(vararg names: String) = names.map { Row(it.lowercase(), it) }

    private fun page(items: List<Row>, total: Int, page: Int = 1) =
        PageResponse(items, total, page, SERVER_SEARCH_PAGE_SIZE, (total + SERVER_SEARCH_PAGE_SIZE - 1) / SERVER_SEARCH_PAGE_SIZE)

    private fun visible(
        query: String,
        browse: List<Row> = emptyList(),
        browseLoaded: Boolean = true,
        cached: List<Row> = emptyList(),
        resultsFor: String? = null,
        results: List<Row> = emptyList(),
        seen: List<Row> = emptyList()
    ) = serverSearchRows(query, browse, browseLoaded, cached, resultsFor, results, seen, { it.id }, { it.name })

    // ── what the sheet lists ────────────────────────────────────────────────────────────────────

    @Test
    fun `a blank query lists the scope's own page once it has arrived, and the cache before that`() {
        assertEquals(rows("Asha", "Bhanu"), visible("", browse = rows("Asha", "Bhanu"), cached = rows("Old")))
        assertEquals(rows("Old"), visible("  ", browseLoaded = false, cached = rows("Old")))
    }

    @Test
    fun `a query the server answered lists exactly that answer, unfiltered`() {
        // The server matched "kumar" in a field the label does not show (notes): the sheet must not
        // filter it away again.
        val answer = rows("Ram Lal")
        assertEquals(answer, visible(" kumar ", browse = rows("Asha"), resultsFor = "kumar", results = answer))
    }

    @Test
    fun `while a query is in flight or offline, every row on the device is filtered locally`() {
        val shown = visible(
            "ram bhuj",
            browse = rows("Ram Kumar Bhuj", "Asha"),
            cached = rows("Ram Lal Bhuj"),
            resultsFor = "ra",
            seen = rows("Ramesh Bhuj", "Ram Kumar Bhuj")
        )
        assertEquals(listOf("Ram Kumar Bhuj", "Ram Lal Bhuj", "Ramesh Bhuj"), shown.map { it.name })
    }

    @Test
    fun `there is another page only of what is listed, and never of a local fallback`() {
        assertTrue(serverSearchHasMore("", true, 100, 2000, null, 0, 0))
        assertFalse(serverSearchHasMore("", false, 0, 2000, null, 0, 0))
        assertFalse(serverSearchHasMore("", true, 2000, 2000, null, 0, 0))
        assertTrue(serverSearchHasMore("ram", true, 100, 2000, "ram", 100, 140))
        assertFalse(serverSearchHasMore("ram", true, 100, 2000, "ram", 40, 40))
        // Typed past the last answer: the list is the local filter, which has no next page.
        assertFalse(serverSearchHasMore("rame", true, 100, 2000, "ram", 100, 140))
    }

    @Test
    fun `rows are only ever added, first writer wins`() {
        val before = rows("Asha", "Bhanu")
        val merged = mergeRowsById(before, listOf(Row("bhanu", "Bhanu (renamed)"), Row("chitra", "Chitra")), { it.id })
        assertEquals(listOf("Asha", "Bhanu", "Chitra"), merged.map { it.name })
        assertTrue(mergeRowsById(before, emptyList(), { it.id }) === before)
    }

    @Test
    fun `local matching is every space-split term, case-insensitive`() {
        assertTrue(serverSearchMatches("Ram Kumar · Bagru", "ram BAGRU"))
        assertFalse(serverSearchMatches("Ram Kumar · Bagru", "ram jaipur"))
        assertTrue(serverSearchMatches("anything", "   "))
    }

    // ── the state, against a fake list route ────────────────────────────────────────────────────

    private class FakeRoute(val all: List<Row>) {
        val calls = mutableListOf<Pair<String?, Int>>()
        var failWith: Throwable? = null
        suspend fun fetch(search: String?, page: Int): PageResponse<Row> {
            calls += search to page
            failWith?.let { throw it }
            val matching = if (search == null) all else all.filter { serverSearchMatches(it.name, search) }
            val from = (page - 1) * SERVER_SEARCH_PAGE_SIZE
            val items = matching.drop(from).take(SERVER_SEARCH_PAGE_SIZE)
            return PageResponse(items, matching.size, page, SERVER_SEARCH_PAGE_SIZE, (matching.size + 99) / 100)
        }
    }

    private fun state(route: FakeRoute) = ServerSearchState<Row>({ it.id }, { it.name }).also { s ->
        s.fetch = { search, p -> route.fetch(search, p) }
        s.scope = CoroutineScope(Dispatchers.Unconfined)
    }

    private val table = (1..2000).map { Row("a$it", "Artisan $it") }

    @Test
    fun `a seeded picker costs no request until somebody asks for more`() {
        val route = FakeRoute(table)
        val s = state(route)
        s.seed(page(table.take(100), 2000))
        assertEquals(100, s.rows.size)
        assertEquals(2000, s.total)
        assertTrue(s.hasMore)
        assertTrue(route.calls.isEmpty())

        s.loadMore()
        assertEquals(listOf<Pair<String?, Int>>(null to 2), route.calls)
        assertEquals(200, s.rows.size)
        assertEquals("a200", s.rows.last().id)
    }

    @Test
    fun `typing asks the server, and load more pages the matches rather than the plain list`() = runBlocking {
        val route = FakeRoute(table)
        val s = state(route)
        s.seed(page(table.take(100), 2000))
        s.updateQuery("Artisan 1")
        s.search("Artisan 1")
        // Every term must match, so "Artisan 1" is every artisan whose number has a 1 in it.
        assertEquals(table.count { serverSearchMatches(it.name, "Artisan 1") }, s.total)
        assertEquals(100, s.rows.size)
        s.loadMore()
        assertEquals(listOf<Pair<String?, Int>>("Artisan 1" to 1, "Artisan 1" to 2), route.calls)
        assertEquals(200, s.rows.size)
        // A row found only by searching stays known after the box is cleared.
        val deep = s.rows.last()
        s.updateQuery("")
        assertTrue(s.known.any { it.id == deep.id })
        assertTrue(s.rows.none { it.id == deep.id })
    }

    @Test
    fun `offline, a search falls back to the saved rows and says so in one line`() = runBlocking {
        val route = FakeRoute(table)
        val s = state(route)
        s.cached = rows("Ram Lal", "Asha")
        route.failWith = IOException("no route to host")
        s.loadBrowse()
        assertEquals(SERVER_SEARCH_OFFLINE_NOTE, s.note)
        assertFalse(s.browseLoaded)
        assertEquals(rows("Ram Lal", "Asha"), s.rows)

        s.updateQuery("ram")
        s.search("ram")
        assertEquals(rows("Ram Lal"), s.rows)
        assertFalse(s.hasMore)
        assertEquals(SERVER_SEARCH_OFFLINE_NOTE, s.note)

        route.failWith = null
        s.retry()
        assertTrue(s.browseLoaded)
        assertNull(s.note)
    }

    @Test
    fun `a server refusal is not called offline`() = runBlocking {
        val route = FakeRoute(table)
        val s = state(route)
        route.failWith = IllegalStateException("500")
        s.loadBrowse()
        assertEquals(SERVER_SEARCH_FAILED_NOTE, s.note)
    }

    @Test
    fun `the copy never admits a partial list`() {
        // DC-A09: nothing is out of reach, so no sentence may say a list is short.
        for (note in listOf(SERVER_SEARCH_OFFLINE_NOTE, SERVER_SEARCH_FAILED_NOTE)) {
            assertFalse(note, note.contains("of ") && note.contains("Showing"))
            assertTrue(note.length <= 48)
        }
    }

    // ── carry scope ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `only a whole list may vouch that a carried record is gone`() {
        assertEquals(CarryScopeState.LOADED, carryScopeStateFor(page(table.take(40), 40), page(table.take(3), 3)))
        assertEquals(CarryScopeState.UNAVAILABLE, carryScopeStateFor(page(table.take(100), 2000)))
        assertEquals(CarryScopeState.UNAVAILABLE, carryScopeStateFor(null))
    }

    // ── the lazy page ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a lazy page reports zero only at the very top, and far once the first item is gone`() {
        assertEquals(0, lazyPageScrollOffset(0, 0))
        assertEquals(37, lazyPageScrollOffset(0, 37))
        assertTrue(lazyPageScrollOffset(3, 0) > 100_000)
    }

    @Test
    fun `a Back restore waits until the list holds the item it names`() {
        assertFalse(lazyRestoreReady(LazyScrollMark(index = 40, offset = 12), totalItems = 5))
        assertTrue(lazyRestoreReady(LazyScrollMark(index = 40, offset = 12), totalItems = 103))
    }
}
