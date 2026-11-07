package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.ui.ExplorerPaneWidth
import com.fieldrepository.app.explorer.ui.ExplorerResume
import com.fieldrepository.app.explorer.ui.ExplorerState
import com.fieldrepository.app.explorer.ui.ExplorerTab
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.explorer.ui.videoFrame
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
import org.junit.Test

class ExplorerNavTest {
    private val web = "https://portal.example.org"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    @QFeature("X1")
    @Test fun `a web link keeps its tab, sheet, query, hit and kind`() {
        val got = ExplorerViewLinks.parse("$web/data?path=w%2Fbagru&view=tables&sheet=Tools&q=clay&hit=mud&kind=image&preview=m1", web)
        assertEquals("w/bagru", got?.path)
        assertEquals("tables", got?.view)
        assertEquals("Tools", got?.sheet)
        assertEquals("clay", got?.q)
        assertEquals("mud", got?.hit)
        assertEquals("IMAGE", got?.kind)
        assertEquals("m1", got?.preview)
    }

    @QFeature("X1")
    @Test fun `an unknown view is dropped rather than guessed`() {
        val got = ExplorerViewLinks.parse("$web/data?path=w&view=gallery", web)
        assertNull(got?.view)
        assertEquals("w", got?.path)
    }

    @QFeature("X1")
    @Test fun `the pane width stays between its floor and sixty percent of the screen`() {
        assertEquals(280f, ExplorerPaneWidth.clamp(100f, 1200f))
        assertEquals(720f, ExplorerPaneWidth.clamp(2000f, 1200f))
        assertEquals(400f, ExplorerPaneWidth.clamp(400f, 1200f))
        assertEquals(280f, ExplorerPaneWidth.clamp(500f, 300f))
    }

    @QFeature("X1")
    @Test fun `a video frame keeps the video's shape and fits inside the stage`() {
        val (w1, h1) = videoFrame(400f, 300f, 16f / 9f)
        assertEquals(400f, w1, 0.01f)
        assertEquals(225f, h1, 0.01f)
        val (w2, h2) = videoFrame(800f, 360f, 16f / 9f)
        assertEquals(360f, h2, 0.01f)
        assertEquals(640f, w2, 0.01f)
        val (w3, h3) = videoFrame(400f, 360f, 9f / 16f)
        assertEquals(360f, h3, 0.01f)
        assertEquals(202.5f, w3, 0.01f)
        val (w4, h4) = videoFrame(400f, 360f, null)
        assertEquals(400f, w4, 0.01f)
        assertEquals(225f, h4, 0.01f)
    }

    @QFeature("X1")
    @Test fun `a resumed state reopens its folder, tab and status`() = runBlocking {
        val api = FakeExplorerApi()
        val taxonomies = listOf(ExplorerTaxonomy("workshop", "Workshop", "by-workshop", "", isDefault = true))
        api.trees = { path, _, _ ->
            ExplorerTree(
                path = path,
                crumbs = listOf(ExplorerCrumb("Workshop", "by-workshop"), ExplorerCrumb("Bagru", "w/bagru")),
                entries = listOf(ExplorerEntry(name = "a.m4a", path = "w/bagru/a", mediaId = "a", mediaType = "AUDIO")),
                taxonomies = taxonomies,
                taxonomy = "workshop",
            )
        }
        val state = ExplorerState(api = api, scope = scope, cache = null, rootName = "Repository", defaultStatus = "approved", statusParamOf = { it })
        state.start("by-workshop", ExplorerResume("w/bagru", ExplorerTab.TRANSCRIPTS, null))
        withTimeout(5_000) { while (state.tree == null) delay(10) }
        assertEquals("w/bagru", state.path)
        assertEquals(ExplorerTab.TRANSCRIPTS, state.tab)
        assertNull(state.status)
        assertEquals("tree:w/bagru::", api.calls.first())
    }
}
