package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
import com.fieldrepository.app.explorer.ui.ExplorerTablesState
import com.fieldrepository.app.explorer.ui.TablesLoad
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class ExplorerTablesStateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun forget() = ExplorerTablesMemory.clear()

    @After fun stop() = scope.cancel()

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }

    private fun report(path: String, q: String?) = ExplorerReport(
        sheets = listOf(ExplorerSheet(name = "Tools", columns = listOf(JsonPrimitive("Name")), rows = listOf(listOf(JsonPrimitive("$path:${q.orEmpty()}"))), totalRows = 120, matchedRows = if (q == null) null else 3)),
        query = q,
    )

    private fun tables(api: FakeExplorerApi) = ExplorerTablesState(api, scope, owner = "u1", debounceMs = 60, busyTickMs = 1)

    private fun reports(api: FakeExplorerApi) = api.calls.filter { it.startsWith("report:") }

    @QFeature("X6")
    @Test fun `showing the same folder twice asks once and never drops back to loading`() = runBlocking {
        val api = FakeExplorerApi()
        api.report = { path, q -> report(path, q) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        repeat(5) { t.show("w/a") }
        delay(100)
        assertEquals(1, reports(api).size)
        assertTrue(t.load is TablesLoad.Ready)
    }

    @QFeature("X6")
    @Test fun `typing in the table search is debounced to one server search and keeps the rows on screen`() = runBlocking {
        val api = FakeExplorerApi()
        val gate = CompletableDeferred<Unit>()
        api.report = { path, q -> if (q != null) gate.await(); report(path, q) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        t.search("i")
        t.search("in")
        t.search("indigo")
        until { reports(api).size == 2 }
        val during = Collections.synchronizedList(mutableListOf<TablesLoad>())
        during += t.load
        assertTrue(t.refreshing)
        assertTrue(during.all { it is TablesLoad.Ready && it.path == "w/a" })
        gate.complete(Unit)
        until { (t.load as? TablesLoad.Ready)?.query == "indigo" }
        assertEquals(listOf("report:w/a::json", "report:w/a:indigo:json"), reports(api))
        assertFalse(t.refreshing)
    }

    @QFeature("X6")
    @Test fun `a new folder clears the search and never shows the old folder's rows`() = runBlocking {
        val api = FakeExplorerApi()
        val gate = CompletableDeferred<Unit>()
        api.report = { path, q -> if (path == "w/b") gate.await(); report(path, q) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        t.search("lime")
        until { (t.load as? TablesLoad.Ready)?.query == "lime" }
        t.show("w/b")
        assertEquals("", t.query)
        assertEquals(TablesLoad.Loading, t.load)
        gate.complete(Unit)
        until { (t.load as? TablesLoad.Ready)?.path == "w/b" }
    }

    @QFeature("X6")
    @Test fun `going back to a folder uses the remembered tables without asking again`() = runBlocking {
        val api = FakeExplorerApi()
        api.report = { path, q -> report(path, q) }
        val t = tables(api)
        t.show("w/a")
        until { (t.load as? TablesLoad.Ready)?.path == "w/a" }
        t.show("w/b")
        until { (t.load as? TablesLoad.Ready)?.path == "w/b" }
        t.show("w/a")
        assertEquals("w/a", (t.load as TablesLoad.Ready).path)
        assertEquals(2, reports(api).size)
    }

    @QFeature("X6")
    @Test fun `a busy report is retried with backoff and the wait is shown`() = runBlocking {
        val api = FakeExplorerApi()
        var tries = 0
        val waits = Collections.synchronizedList(mutableListOf<Int>())
        api.report = { path, q -> tries++; if (tries < 3) throw FakeExplorerApi.httpError(503) else report(path, q) }
        val t = tables(api)
        val watcher = scope.launch {
            while (true) {
                t.busySeconds?.let { waits += it }
                delay(1)
            }
        }
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        watcher.cancel()
        assertEquals(3, tries)
        assertNull(t.busySeconds)
        assertTrue(waits.isNotEmpty())
    }

    @QFeature("X6")
    @Test fun `a failed refresh keeps the rows and offers a retry`() = runBlocking {
        val api = FakeExplorerApi()
        api.report = { path, q -> if (q != null) throw java.io.IOException("down") else report(path, q) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        t.search("kiln")
        until { t.refreshFailed }
        assertEquals("w/a", (t.load as TablesLoad.Ready).path)
        assertEquals("", (t.load as TablesLoad.Ready).query)
    }

    @QFeature("X6")
    @Test fun `the picked sheet prefers one with matches while searching`() {
        val r = ExplorerReport(
            sheets = listOf(
                ExplorerSheet(name = "Artisans", rows = emptyList(), totalRows = 4, matchedRows = 0),
                ExplorerSheet(name = "Tools", rows = listOf(listOf(JsonPrimitive("Indigo vat"))), totalRows = 120, matchedRows = 3),
            ),
        )
        assertEquals("Tools", ExplorerTablesState.picked(r, null, searching = true)?.name)
        assertEquals("Artisans", ExplorerTablesState.picked(r, "Artisans", searching = true)?.name)
        assertEquals(3, ExplorerTablesState.count(r.sheets[1], searching = true))
        assertEquals(120, ExplorerTablesState.count(r.sheets[1], searching = false))
    }

    private fun tablePage(offset: Int, total: Int = 450, size: Int = 200): ExplorerSheet {
        val numbers = ((offset + 1)..minOf(offset + size, total)).toList()
        return ExplorerSheet(
            name = "Tools",
            columns = listOf(JsonPrimitive("Name")),
            rows = numbers.map { listOf(JsonPrimitive("r$it")) },
            totalRows = total,
            rowNumbers = numbers,
            preview = offset + size < total,
            offset = offset,
        )
    }

    @QFeature("X6")
    @Test fun `show more rows walks the loaded rows and then the server to the last row`() = runBlocking {
        val api = FakeExplorerApi()
        api.report = { _, _ -> ExplorerReport(listOf(tablePage(0))) }
        api.reportPage = { _, _, _, offset -> ExplorerReport(listOf(tablePage(offset))) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        assertEquals(20, ExplorerTablesState.window(t.ready!!.report.sheets.first(), false, t.shown).size)
        var taps = 0
        while (ExplorerTablesState.canShowMore(t.ready!!.report.sheets.first(), false, t.shown)) {
            val before = t.shown
            t.more(t.ready!!.report.sheets.first())
            until { t.shown > before }
            taps++
            assertTrue(taps < 50)
        }
        val window = ExplorerTablesState.window(t.ready!!.report.sheets.first(), false, t.shown)
        assertEquals(450, window.size)
        assertEquals((1..450).toList(), window.map { it.second })
        assertEquals("r450", (window.last().first.first() as JsonPrimitive).content)
        assertEquals(listOf("report:w/a::json", "report:w/a::json:Tools:200", "report:w/a::json:Tools:400"), reports(api))
        assertFalse(t.rowsFailed)
    }

    @QFeature("X6")
    @Test fun `a search pages its matches past the first two hundred`() = runBlocking {
        val api = FakeExplorerApi()
        fun matches(offset: Int) = tablePage(offset, total = 260, size = 200).copy(totalRows = 900, matchedRows = 260)
        api.report = { _, q -> ExplorerReport(listOf(if (q == null) tablePage(0) else matches(0)), q) }
        api.reportPage = { _, _, _, offset -> ExplorerReport(listOf(matches(offset)), "loom") }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        t.search("loom")
        until { (t.load as? TablesLoad.Ready)?.query == "loom" }
        while (ExplorerTablesState.canShowMore(t.ready!!.report.sheets.first(), true, t.shown)) {
            val before = t.shown
            t.more(t.ready!!.report.sheets.first())
            until { t.shown > before }
        }
        val window = ExplorerTablesState.window(t.ready!!.report.sheets.first(), true, t.shown)
        assertEquals(260, window.size)
        assertEquals(260, window.last().second)
        assertTrue(reports(api).contains("report:w/a:loom:json:Tools:200"))
    }

    @Test fun `a cap note waits for the last page`() {
        val note = listOf(JsonPrimitive("Note: capped"))
        fun rows(range: IntRange) = range.map { listOf(JsonPrimitive("r$it")) }
        val first = ExplorerSheet(name = "T", rows = rows(1..3) + listOf(note), rowNumbers = listOf(1, 2, 3, 0), totalRows = 5)
        assertEquals(listOf<Int?>(1, 2, 3), ExplorerTablesState.window(first, false, 10).map { it.second })
        val last = ExplorerSheet(name = "T", rows = rows(4..5) + listOf(note), rowNumbers = listOf(4, 5, 0), totalRows = 5, offset = 3)
        val joined = ExplorerTablesState.joined(first, last)
        assertEquals(listOf(1, 2, 3, 4, 5, 0), joined.rowNumbers)
        assertEquals(listOf<Int?>(1, 2, 3, 4, 5, 0), ExplorerTablesState.window(joined, false, 10).map { it.second })
        assertFalse(ExplorerTablesState.canShowMore(joined, false, 10))
    }

    @Test fun `a server that ignores the offset is not merged twice`() = runBlocking {
        val api = FakeExplorerApi()
        api.report = { _, _ -> ExplorerReport(listOf(tablePage(0))) }
        api.reportPage = { _, _, _, _ -> ExplorerReport(listOf(tablePage(0))) }
        val t = tables(api)
        t.show("w/a")
        until { t.load is TablesLoad.Ready }
        repeat(4) { t.more(t.ready!!.report.sheets.first()) }
        t.more(t.ready!!.report.sheets.first())
        until { t.rowsFailed }
        assertEquals(200, ExplorerTablesState.dataRows(t.ready!!.report.sheets.first()))
    }
}
