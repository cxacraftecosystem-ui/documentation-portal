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
}
