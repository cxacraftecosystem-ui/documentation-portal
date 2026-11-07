package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerSort
import com.fieldrepository.app.explorer.data.ExplorerSortMap
import com.fieldrepository.app.explorer.data.ExplorerSortMemory
import com.fieldrepository.app.explorer.data.ExplorerSorts
import com.fieldrepository.app.explorer.data.ExplorerTranscriptList
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.data.ExplorerSortApplied
import com.fieldrepository.app.explorer.ui.ExplorerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ExplorerSortTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    private val files = listOf(
        ExplorerEntry(name = "Cotton.jpg", path = "w/c", mediaId = "c", mediaType = "IMAGE", capturedAt = "2026-09-03T10:00:00Z"),
        ExplorerEntry(name = "Alum.jpg", path = "w/a", mediaId = "a", mediaType = "IMAGE", capturedAt = "2026-09-01T10:00:00Z"),
        ExplorerEntry(name = "Dabu.mp4", path = "w/d", mediaId = "d", mediaType = "VIDEO", capturedAt = "2026-09-04T10:00:00Z"),
        ExplorerEntry(name = "Block.jpg", path = "w/b", mediaId = "b", mediaType = "IMAGE", capturedAt = "2026-09-02T10:00:00Z"),
    )

    private fun ordered(sort: String?): List<ExplorerEntry> {
        val applied = ExplorerSort.parse(sort) ?: ExplorerSorts.TREE
        val by = when (applied.key) {
            "recorded" -> files.sortedBy { it.capturedAt }
            else -> files.sortedBy { it.name.lowercase() }
        }
        return if (applied.dir == ExplorerSort.DESC) by.reversed() else by
    }

    private fun api(offered: List<String> = listOf("name", "recorded", "added", "type")): FakeExplorerApi = FakeExplorerApi().apply {
        sortedTrees = { path, _, cursor, sort ->
            val all = ordered(sort)
            val from = cursor?.toInt() ?: 0
            val until = minOf(from + 2, all.size)
            val applied = ExplorerSort.parse(sort) ?: ExplorerSorts.TREE
            ExplorerTree(
                path = path,
                entries = all.subList(from, until),
                nextCursor = if (until < all.size) "$until" else null,
                total = all.size,
                sort = ExplorerSortApplied(applied.key, applied.dir),
                sorts = offered,
            )
        }
    }

    private fun state(api: FakeExplorerApi, store: ExplorerSortMap = ExplorerSortMap()) = ExplorerState(
        api = api,
        scope = scope,
        cache = null,
        rootName = "Repository",
        defaultStatus = null,
        statusParamOf = { it },
        sortStore = store,
    )

    private fun names(s: ExplorerState) = s.entries.map { it.name }

    @QFeature("X1")
    @Test fun `a choice is written key colon dir and anything else is refused`() {
        assertEquals(ExplorerSort("name", "asc"), ExplorerSort.parse("name:asc"))
        assertEquals(ExplorerSort("recorded", "desc"), ExplorerSort.parse(" Recorded:DESC "))
        assertEquals("size:asc", ExplorerSort("size", "asc").param)
        assertNull(ExplorerSort.parse("relevance:asc"))
        assertNull(ExplorerSort.parse("colour:asc"))
        assertNull(ExplorerSort.parse("name:up"))
        assertNull(ExplorerSort.parse("name"))
        assertNull(ExplorerSort.parse(":asc"))
        assertNull(ExplorerSort.parse("name:"))
        assertNull(ExplorerSort.parse(null))
    }

    @QFeature("X1")
    @Test fun `labels and direction words are the contract's`() {
        assertEquals("Name · A–Z", ExplorerSorts.short(ExplorerSort("name", "asc")))
        assertEquals("Name · Z–A", ExplorerSorts.short(ExplorerSort("name", "desc")))
        assertEquals("Sort: Date recorded, Newest", ExplorerSorts.spoken(ExplorerSort("recorded", "desc")))
        assertEquals("Oldest", ExplorerSorts.word("added", "asc"))
        assertEquals("Last modified", ExplorerSorts.label("modified"))
        assertEquals("Largest", ExplorerSorts.word("size", "desc"))
        assertEquals("Shortest", ExplorerSorts.word("duration", "asc"))
        assertEquals("Best first", ExplorerSorts.word("relevance", "desc"))
        assertEquals("Review status", ExplorerSorts.label("status"))
        assertEquals("Z–A", ExplorerSorts.word("folder", "desc"))
        assertEquals(listOf("desc"), ExplorerSorts.directions("relevance"))
        assertEquals(listOf("desc", "asc"), ExplorerSorts.directions("recorded"))
        assertEquals(listOf("asc", "desc"), ExplorerSorts.directions("artisan"))
    }

    @QFeature("X1")
    @Test fun `picking a key starts in its natural direction`() {
        listOf("name", "type", "workshop", "artisan", "craft", "status", "folder").forEach { assertEquals(it, "asc", ExplorerSorts.pick(it).dir) }
        listOf("recorded", "added", "modified", "size", "duration", "relevance").forEach { assertEquals(it, "desc", ExplorerSorts.pick(it).dir) }
    }

    @QFeature("X1")
    @Test fun `the default is never sent`() {
        assertNull(ExplorerSorts.param(null, ExplorerSorts.TREE))
        assertNull(ExplorerSorts.param(ExplorerSort("name", "asc"), ExplorerSorts.TREE))
        assertEquals("name:desc", ExplorerSorts.param(ExplorerSort("name", "desc"), ExplorerSorts.TREE))
        assertNull(ExplorerSorts.param(ExplorerSort("folder", "asc"), ExplorerSorts.TRANSCRIPTS))
        assertNull(ExplorerSorts.param(ExplorerSort("added", "desc"), ExplorerSorts.BROWSE))
        assertNull(ExplorerSorts.param(ExplorerSort("relevance", "desc"), ExplorerSorts.SEARCH))
        assertEquals("recorded:desc", ExplorerSorts.param(ExplorerSort("recorded", "desc"), ExplorerSorts.SEARCH))
    }

    @QFeature("X1")
    @Test fun `each list level has its own memory key`() {
        assertEquals("tree:root:0", ExplorerSorts.treeLevel(""))
        assertEquals("tree:by-workshop:1", ExplorerSorts.treeLevel("by-workshop"))
        assertEquals("tree:by-workshop:3", ExplorerSorts.treeLevel("by-workshop/w1/a2"))
        assertEquals("tree:a:2", ExplorerSorts.treeLevel("/a//b/"))
        assertEquals("transcripts", ExplorerSorts.TRANSCRIPTS_LEVEL)
        assertEquals("browse:workshop", ExplorerSorts.browseLevel("workshop"))
        assertEquals("browse-media:artisan", ExplorerSorts.browseMediaLevel("artisan"))
        assertEquals("search:all", ExplorerSorts.searchLevel(null))
        assertEquals("search:tools", ExplorerSorts.searchLevel("tools"))
        assertEquals("dataExplorer.sort.u1.tree:root:0", ExplorerSortMemory.key("u1", "tree:root:0"))
        assertEquals("dataExplorer.sort.guest.transcripts", ExplorerSortMemory.key(null, "transcripts"))
    }

    @QFeature("X1")
    @Test fun `a remembered key the list does not offer falls back to the default`() {
        val remembered = ExplorerSort("duration", "asc")
        assertEquals(ExplorerSorts.TREE, ExplorerSorts.resolve(remembered, listOf("name", "added"), ExplorerSorts.TREE))
        assertEquals(remembered, ExplorerSorts.resolve(remembered, listOf("name", "duration"), ExplorerSorts.TREE))
        assertEquals(ExplorerSorts.TREE, ExplorerSorts.resolve(remembered, emptyList(), ExplorerSorts.TREE))
        assertEquals(ExplorerSorts.SEARCH, ExplorerSorts.resolve(null, listOf("relevance", "name"), ExplorerSorts.SEARCH))
        assertEquals(listOf("name", "recorded"), ExplorerSorts.offered(listOf("name", "colour", "Recorded", "name")))
    }

    @QFeature("X1")
    @Test fun `responses carry the applied sort and the offered keys, and older servers parse without them`() {
        val fresh = ExplorerJson.decodeFromString(ExplorerTree.serializer(), """{"path":"w","entries":[],"sort":{"key":"recorded","dir":"asc"},"sorts":["name","recorded"]}""")
        assertEquals(ExplorerSort("recorded", "asc"), fresh.sort?.toSort())
        assertEquals(listOf("name", "recorded"), fresh.sorts)
        val old = ExplorerJson.decodeFromString(ExplorerTree.serializer(), """{"path":"w","entries":[]}""")
        assertNull(old.sort)
        assertTrue(old.sorts.isEmpty())
        val list = ExplorerJson.decodeFromString(ExplorerTranscriptList.serializer(), """{"items":[],"sort":{"key":"folder","dir":"asc"},"sorts":["folder","name"]}""")
        assertEquals(ExplorerSorts.TRANSCRIPTS, list.sort?.toSort())
    }

    @QFeature("X1")
    @Test fun `a folder opens with the remembered sort and every page asks for it`() = runBlocking {
        val api = api()
        val s = state(api, ExplorerSortMap(mapOf("tree:w:1" to ExplorerSort("recorded", "asc"))))
        s.open("w")
        until { s.entries.size == 2 }
        assertEquals(listOf("Alum.jpg", "Block.jpg"), names(s))
        assertEquals(ExplorerSort("recorded", "asc"), s.sort)
        s.loadMore()
        until { s.entries.size == 4 }
        assertEquals(listOf("Alum.jpg", "Block.jpg", "Cotton.jpg", "Dabu.mp4"), names(s))
        assertEquals(listOf("tree:w:::sort=recorded:asc", "tree:w::2:sort=recorded:asc"), api.calls)
    }

    @QFeature("X1")
    @Test fun `changing the sort drops the loaded pages and reloads from the first`() = runBlocking {
        val api = api()
        val store = ExplorerSortMap()
        val s = state(api, store)
        s.open("w")
        until { s.entries.size == 2 }
        s.loadMore()
        until { s.entries.size == 4 }
        assertEquals(ExplorerSorts.TREE, s.sort)
        assertEquals(listOf("name", "recorded", "added", "type"), s.sortKeys)
        s.changeSort(ExplorerSort("recorded", "desc"))
        until { api.calls.size == 3 && s.entries.size == 2 && !s.loading }
        assertEquals("tree:w:::sort=recorded:desc", api.calls[2])
        assertEquals(listOf("Dabu.mp4", "Cotton.jpg"), names(s))
        assertEquals("2", s.cursor)
        assertEquals(ExplorerSort("recorded", "desc"), store.saved["tree:w:1"])
        s.loadMore()
        until { s.entries.size == 4 }
        assertEquals(listOf("Dabu.mp4", "Cotton.jpg", "Block.jpg", "Alum.jpg"), names(s))
        assertEquals("tree:w::2:sort=recorded:desc", api.calls[3])
    }

    @QFeature("X1")
    @Test fun `going back to the default sends no sort`() = runBlocking {
        val api = api()
        val s = state(api, ExplorerSortMap(mapOf("tree:w:1" to ExplorerSort("name", "desc"))))
        s.open("w")
        until { s.entries.size == 2 }
        assertEquals(listOf("Dabu.mp4", "Cotton.jpg"), names(s))
        s.changeSort(ExplorerSorts.TREE)
        until { api.calls.size == 2 && !s.loading }
        assertEquals(listOf("tree:w:::sort=name:desc", "tree:w::"), api.calls)
        assertEquals(listOf("Alum.jpg", "Block.jpg"), names(s))
    }

    @QFeature("X1")
    @Test fun `a remembered key this folder does not offer is dropped for the default`() = runBlocking {
        val api = api(offered = listOf("name", "added"))
        val s = state(api, ExplorerSortMap(mapOf("tree:w:1" to ExplorerSort("duration", "desc"))))
        s.open("w")
        until { api.calls.size == 2 && !s.loading }
        assertEquals(listOf("tree:w:::sort=duration:desc", "tree:w::"), api.calls)
        assertEquals(ExplorerSorts.TREE, s.sort)
        s.loadMore()
        until { s.entries.size == 4 }
        assertEquals("tree:w::2", api.calls[2])
    }

    @QFeature("X1")
    @Test fun `a sort the server refuses is retried without it`() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String?>())
        val got = ExplorerSorts.fetch(ExplorerSort("size", "asc"), ExplorerSorts.TREE, { t: ExplorerTree -> t.sorts }) { sort ->
            asked += sort
            if (sort != null) throw FakeExplorerApi.httpError(422)
            ExplorerTree(path = "w")
        }
        assertEquals(listOf("size:asc", null), asked)
        assertNull(got.sent)
    }

    @QFeature("X1")
    @Test fun `each level keeps its own choice`() = runBlocking {
        val api = api()
        val store = ExplorerSortMap()
        val s = state(api, store)
        s.open("w")
        until { s.entries.size == 2 }
        s.changeSort(ExplorerSort("type", "desc"))
        until { api.calls.size == 2 && !s.loading }
        s.open("w/deeper")
        until { api.calls.size == 3 && !s.loading }
        assertEquals("tree:w/deeper::", api.calls[2])
        assertEquals(ExplorerSorts.TREE, s.sort)
        assertEquals(mapOf("tree:w:1" to ExplorerSort("type", "desc")), store.saved.toMap())
    }
}
