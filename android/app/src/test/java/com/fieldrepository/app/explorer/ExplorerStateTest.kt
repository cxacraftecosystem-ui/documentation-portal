package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.ui.ExplorerState
import com.fieldrepository.app.explorer.ui.TranscriptLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ExplorerStateTest {
    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private fun state(api: FakeExplorerApi, cache: ExplorerCache? = null, online: Boolean = true, fallbackMs: Long = 200) = ExplorerState(
        api = api,
        scope = scope,
        cache = cache,
        rootName = "Repository",
        defaultStatus = null,
        statusParamOf = { it },
        online = { online },
        fallbackMs = fallbackMs,
    )

    private suspend fun until(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }

    private fun file(id: String) = ExplorerEntry(name = "$id.m4a", path = "w/$id", mediaId = id, mediaType = "AUDIO", transcriptAvailable = true)

    @QFeature("X8")
    @Test fun `pages of a long folder are appended and the cursor ends`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, cursor ->
            when (cursor) {
                null -> ExplorerTree(path = path, entries = (1..3).map { file("a$it") }, nextCursor = "p2", total = 5, truncated = true)
                else -> ExplorerTree(path = path, entries = (4..5).map { file("a$it") }, nextCursor = null, total = 5)
            }
        }
        val s = state(api)
        s.open("w")
        until { s.entries.size == 3 }
        assertEquals("p2", s.cursor)
        s.loadMore()
        until { s.entries.size == 5 }
        assertNull(s.cursor)
        assertEquals(listOf("tree:w::", "tree:w::p2"), api.calls)
    }

    @QFeature("X12")
    @Test fun `a transcript is fetched once when opened, never with the folder`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> ExplorerTree(path = path, entries = listOf(file("m1"))) }
        val s = state(api)
        s.open("w")
        until { s.entries.isNotEmpty() }
        val entry = s.files.first()
        s.toggleTranscript(entry)
        until { s.transcriptFor(entry) is TranscriptLoad.Loaded }
        s.toggleTranscript(entry)
        s.toggleTranscript(entry)
        delay(100)
        assertEquals(1, api.transcriptCalls.get())
        assertEquals("# Transcript m1", (s.transcriptFor(entry) as TranscriptLoad.Loaded).text)
        assertTrue(api.calls.none { it.startsWith("manifest") })
    }

    @Test fun `going back to a folder is instant from memory`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> delay(300); ExplorerTree(path = path, entries = listOf(file(path))) }
        val s = state(api)
        s.open("a")
        until { s.entries.isNotEmpty() && !s.loading }
        s.open("b")
        until { s.path == "b" && s.entries.firstOrNull()?.mediaId == "b" }
        s.open("a")
        assertEquals("a", s.entries.first().mediaId)
    }

    @Test fun `offline, the saved copy shows at once with a note`() = runBlocking {
        val cache = ExplorerCache(temp.newFolder(), "u1")
        cache.write(ExplorerCache.key("w", null), ExplorerTree(path = "w", entries = listOf(file("c1"))), now = 1_700_000_000_000)
        val api = FakeExplorerApi()
        api.trees = { _, _, _ -> throw IOException("no signal") }
        val s = state(api, cache, online = false)
        s.open("w")
        until { s.entries.isNotEmpty() }
        assertNotNull(s.cachedNote)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun `a slow connection falls back to the saved copy and then replaces it`() = runBlocking {
        val cache = ExplorerCache(temp.newFolder(), "u1")
        cache.write(ExplorerCache.key("w", null), ExplorerTree(path = "w", entries = listOf(file("old"))))
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> delay(800); ExplorerTree(path = path, entries = listOf(file("new"))) }
        val s = state(api, cache, fallbackMs = 100)
        s.open("w")
        until { s.entries.firstOrNull()?.mediaId == "old" }
        assertNotNull(s.cachedNote)
        until { s.entries.firstOrNull()?.mediaId == "new" }
        assertNull(s.cachedNote)
    }

    @Test fun `the root lands on the default grouping unless a place was saved`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ ->
            if (path.isEmpty()) ExplorerTree(taxonomies = listOf(ExplorerTaxonomy("a", "A", "by-a"), ExplorerTaxonomy("b", "B", "by-b", isDefault = true)))
            else ExplorerTree(path = path)
        }
        val fresh = state(api)
        fresh.start(null)
        until { fresh.path == "by-b" }
        val saved = state(api)
        saved.start("by-a/x")
        until { !saved.loading && saved.tree != null }
        assertEquals("by-a/x", saved.path)
    }

    @QFeature("X3")
    @Test fun `selection keeps folders and files across folders`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> ExplorerTree(path = path, entries = listOf(ExplorerEntry(name = "F", path = "$path/f", kind = "folder"), file("${path}m"), ExplorerEntry(name = "notes.txt", path = "$path/n", content = "x"))) }
        val s = state(api)
        s.open("a")
        until { s.entries.size == 3 }
        s.beginSelect(s.folders.first())
        s.toggleSelect(s.files.first())
        s.toggleSelect(s.files.last())
        s.open("b")
        until { s.path == "b" && s.entries.size == 3 }
        s.toggleSelect(s.files.first())
        val body = s.selectionBody()
        assertEquals(listOf("a/f"), body.paths)
        assertEquals(listOf("am", "bm"), body.mediaIds)
        assertTrue(body.stream)
        s.endSelect()
        assertTrue(s.selected.isEmpty())
    }

    @Test fun `locating a record opens its folder`() = runBlocking {
        val api = FakeExplorerApi()
        val s = state(api)
        s.locate("artisan", "r1", "Giriraj")
        until { s.path == "located/r1" }
    }

    @Test fun `a forbidden folder shows the restricted panel`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { _, _, _ -> throw FakeExplorerApi.httpError(403) }
        val s = state(api)
        s.open("w")
        until { s.restricted }
    }
}
