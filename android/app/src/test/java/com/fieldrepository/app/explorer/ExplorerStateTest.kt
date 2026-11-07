package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.ui.ExplorerLinkRequest
import com.fieldrepository.app.explorer.ui.ExplorerState
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.explorer.ui.TranscriptLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        until { s.entries.isNotEmpty() && s.cachedNote != null }
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
        until { s.entries.firstOrNull()?.mediaId == "old" && s.cachedNote != null }
        until { s.entries.firstOrNull()?.mediaId == "new" && s.cachedNote == null }
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

    @QFeature("X12")
    @Test fun `a transcript file reads its own route and is never selected as the recording`() = runBlocking {
        val api = FakeExplorerApi()
        val text = ExplorerEntry(name = "Interview_Transcript.md", path = "w/t1", mediaId = "m9", transcriptPath = "/data/media/m9/transcript")
        api.trees = { path, _, _ -> ExplorerTree(path = path, entries = listOf(text)) }
        api.paths = { path, _ ->
            assertEquals("/api/data/media/m9/transcript", path)
            FakeExplorerApi.bytes("{\"mediaId\":\"m9\",\"content\":\"hello transcript\"}".toByteArray())
        }
        val s = state(api)
        s.open("w")
        until { s.entries.isNotEmpty() }
        val entry = s.files.first()
        assertTrue(entry.isTranscriptFile)
        assertEquals(false, ExplorerState.selectable(entry))
        assertEquals("hello transcript", s.transcriptText(entry))
        assertEquals("hello transcript", s.transcriptText(entry))
        assertEquals(1, api.calls.count { it.startsWith("path:") })
    }

    @QFeature("X1")
    @Test fun `starting twice loads the folder once`() = runBlocking {
        val api = FakeExplorerApi()
        val s = state(api)
        s.start("w")
        s.start("w")
        until { s.tree != null }
        delay(100)
        assertEquals(1, api.calls.count { it.startsWith("tree:w:") })
    }

    @QFeature("X1")
    @Test fun `opening the folder that is already loading does not ask again`() = runBlocking {
        val api = FakeExplorerApi()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.trees = { path, _, _ -> gate.await(); ExplorerTree(path = path) }
        val s = state(api)
        s.open("w")
        s.open("w")
        s.open("w")
        gate.complete(Unit)
        until { s.tree != null }
        assertEquals(1, api.calls.count { it.startsWith("tree:w:") })
    }

    @QFeature("X6")
    @Test fun `the tab goes back to files only when the folder changes`() = runBlocking {
        val api = FakeExplorerApi()
        val s = state(api)
        s.open("w")
        until { s.tree != null }
        s.tab = com.fieldrepository.app.explorer.ui.ExplorerTab.TABLES
        s.open("w")
        assertEquals(com.fieldrepository.app.explorer.ui.ExplorerTab.TABLES, s.tab)
        s.open("w/b")
        assertEquals(com.fieldrepository.app.explorer.ui.ExplorerTab.FILES, s.tab)
    }

    @QFeature("X3")
    @Test fun `the selection adds up the sizes of the picked files`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> ExplorerTree(path = path, entries = listOf(file("a").copy(sizeBytes = 700), file("b").copy(sizeBytes = 18))) }
        val s = state(api)
        s.open("w")
        until { s.entries.size == 2 }
        s.beginSelect(s.files[0])
        s.toggleSelect(s.files[1])
        assertEquals(718L, s.selectedBytes)
        assertEquals(0, s.selectedFolders)
    }
    private fun placed(api: FakeExplorerApi, places: MutableList<String>) = ExplorerState(
        api = api,
        scope = scope,
        cache = null,
        rootName = "Repository",
        defaultStatus = null,
        statusParamOf = { it },
        onPlace = { places += it },
    )

    private val groupings = listOf(ExplorerTaxonomy("w", "Workshop", "by-workshop", isDefault = true), ExplorerTaxonomy("c", "Craft", "by-craft"))

    private fun grouped(path: String) = when {
        path.isEmpty() -> ExplorerTree(taxonomies = groupings)
        else -> ExplorerTree(
            path = path,
            crumbs = listOf(ExplorerCrumb(path.substringBefore('/'), path.substringBefore('/'))) + if ('/' in path) listOf(ExplorerCrumb("Leaf", path)) else emptyList(),
            entries = listOf(ExplorerEntry(name = "F", path = "$path/f", kind = "folder")),
            taxonomies = groupings,
            taxonomy = if (path.startsWith("by-craft")) "c" else "w",
        )
    }

    @Test fun `a forgotten state stops its folder load and opens nothing more`() = runBlocking {
        val api = FakeExplorerApi()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.trees = { path, _, _ -> started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val s = state(api)
        s.open("w")
        started.await()
        s.onForgotten()
        until { !s.loading || s.closed }
        assertTrue(s.closed)
        s.open("x")
        delay(100)
        assertEquals(1, api.calls.size)
        assertEquals("w", s.path)
    }

    @Test fun `the place is written only when the folder changes`() = runBlocking {
        val api = FakeExplorerApi()
        val places = mutableListOf<String>()
        val s = placed(api, places)
        s.open("w")
        until { s.tree != null }
        s.open("w")
        s.reload()
        s.changeStatus("approved")
        s.open("w/b")
        until { s.path == "w/b" && s.tree != null }
        assertEquals(listOf("w", "w/b"), places)
    }

    @Test fun `a grouping root is the top, so back leaves Data instead of opening a bare root`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> grouped(path) }
        val s = state(api)
        s.start(null)
        until { s.path == "by-workshop" && s.tree?.path == "by-workshop" }
        assertTrue(s.atTop)
        assertEquals(false, s.canGoUp)
        assertEquals(false, s.goUp())
        assertEquals("by-workshop", s.path)
        s.open("by-workshop/x")
        until { s.tree?.path == "by-workshop/x" }
        assertTrue(s.canGoUp)
        assertTrue(s.goUp())
        until { s.path == "by-workshop" }
    }

    private fun deep(path: String): ExplorerTree {
        if (path.isEmpty()) return ExplorerTree(taxonomies = groupings)
        val segments = path.split('/')
        val crumbs = segments.indices.map { i -> ExplorerCrumb(segments[i], segments.take(i + 1).joinToString("/")) }
        return ExplorerTree(
            path = path,
            crumbs = crumbs,
            entries = listOf(ExplorerEntry(name = "F", path = "$path/f", kind = "folder")),
            taxonomies = groupings,
            taxonomy = if (path.startsWith("by-craft")) "c" else "w",
        )
    }

    @Test fun `back returns to the folder viewed before, not to the parent`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> deep(path) }
        val s = state(api)
        s.start(null)
        until { s.path == "by-workshop" && s.tree?.path == "by-workshop" }
        s.open("by-craft/k1")
        until { s.tree?.path == "by-craft/k1" }
        s.open("by-workshop/w1/crafts/c1/artisans/a1")
        until { s.tree?.path == "by-workshop/w1/crafts/c1/artisans/a1" }
        assertTrue(s.canGoUp)
        assertTrue(s.goUp())
        until { s.path == "by-craft/k1" }
        assertTrue(s.goUp())
        until { s.path == "by-workshop" && s.tree?.path == "by-workshop" }
        assertEquals(false, s.canGoUp)
        assertEquals(false, s.goUp())
    }

    @Test fun `back skips grouping folders the user never opened`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> deep(path) }
        val s = state(api)
        s.start("by-workshop/w1/crafts/c1/artisans/a1")
        until { s.tree?.path == "by-workshop/w1/crafts/c1/artisans/a1" }
        assertTrue(s.goUp())
        until { s.tree?.path == "by-workshop/w1/crafts/c1" }
        assertTrue(s.goUp())
        until { s.tree?.path == "by-workshop/w1" }
        assertTrue(s.goUp())
        until { s.path == "by-workshop" }
        assertTrue(api.calls.none { it.startsWith("tree:by-workshop/w1/crafts:") || it.startsWith("tree:by-workshop/w1/crafts/c1/artisans:") })
    }

    @Test fun `only listing levels count as grouping folders`() {
        assertTrue(ExplorerState.isGrouping("by-workshop/w1/crafts"))
        assertTrue(ExplorerState.isGrouping("by-workshop/w1/crafts/c1/artisans"))
        assertTrue(ExplorerState.isGrouping("by-design-workshop/d1/stages"))
        assertEquals(false, ExplorerState.isGrouping("by-workshop"))
        assertEquals(false, ExplorerState.isGrouping("by-workshop/w1"))
        assertEquals(false, ExplorerState.isGrouping("by-workshop/w1/_misc"))
        assertEquals(false, ExplorerState.isGrouping("by-type/images"))
    }

    @Test fun `a link that falls back to locate is used up only once the folder is found`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = object : FakeExplorerApi() {
            override suspend fun locate(type: String, id: String): kotlinx.serialization.json.JsonElement {
                gate.await()
                return super.locate(type, id)
            }
        }
        val s = state(api)
        val link = ExplorerLinkRequest(viewId = null, path = null, go = listOf("open", "artisan", "r1"))
        ExplorerViewLinks.request(link)
        try {
            val serving = launch(Dispatchers.Default) {
                ExplorerViewLinks.serve(link, onFailure = { throw AssertionError(it) }) { s.locate("artisan", "r1", "").join() }
            }
            delay(150)
            assertEquals(link, ExplorerViewLinks.requests.value)
            gate.complete(Unit)
            serving.join()
            assertNull(ExplorerViewLinks.requests.value)
            assertEquals("located/r1", s.path)
        } finally {
            ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
        }
    }

    @Test fun `opening the root once groupings are known stays in the current grouping`() = runBlocking {
        val api = FakeExplorerApi()
        api.trees = { path, _, _ -> grouped(path) }
        val s = state(api)
        s.start("by-craft/x")
        until { s.tree?.path == "by-craft/x" }
        s.open("")
        assertEquals("by-craft", s.path)
        until { s.tree?.path == "by-craft" }
        assertTrue(api.calls.none { it == "tree:::" })
    }
}
