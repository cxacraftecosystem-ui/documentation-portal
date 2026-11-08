package com.fieldrepository.app.explorer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSort
import com.fieldrepository.app.explorer.data.ExplorerSortApplied
import com.fieldrepository.app.explorer.data.ExplorerSortMemory
import com.fieldrepository.app.explorer.data.ExplorerSorts
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerState
import com.fieldrepository.app.explorer.ui.rememberExplorerState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class ExplorerSortUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private class Sorted : ExplorerApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val files = listOf(
            ExplorerEntry(name = "Alum.jpg", path = "$PATH/s1", mediaId = "s1", mediaType = "IMAGE", capturedAt = "2026-09-04T10:00:00Z"),
            ExplorerEntry(name = "Block.jpg", path = "$PATH/s2", mediaId = "s2", mediaType = "IMAGE", capturedAt = "2026-09-02T10:00:00Z"),
            ExplorerEntry(name = "Cotton.jpg", path = "$PATH/s3", mediaId = "s3", mediaType = "IMAGE", capturedAt = "2026-09-05T10:00:00Z"),
            ExplorerEntry(name = "Dabu.mp4", path = "$PATH/s4", mediaId = "s4", mediaType = "VIDEO", capturedAt = "2026-09-01T10:00:00Z"),
            ExplorerEntry(name = "Ember.jpg", path = "$PATH/s5", mediaId = "s5", mediaType = "IMAGE", capturedAt = "2026-09-03T10:00:00Z"),
        )

        fun ordered(sort: String?): List<ExplorerEntry> {
            val applied = ExplorerSort.parse(sort) ?: ExplorerSorts.TREE
            val by = if (applied.key == "recorded") files.sortedBy { it.capturedAt } else files.sortedBy { it.name.lowercase() }
            return if (applied.dir == ExplorerSort.DESC) by.reversed() else by
        }

        override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int, sort: String?): ExplorerTree {
            calls += "tree:$path:${cursor.orEmpty()}:${sort.orEmpty()}"
            val all = ordered(sort)
            val from = cursor?.toIntOrNull() ?: 0
            val until = minOf(from + PAGE, all.size)
            val applied = ExplorerSort.parse(sort) ?: ExplorerSorts.TREE
            return ExplorerTree(
                path = path,
                crumbs = listOf(ExplorerCrumb("Sorted", "sorted"), ExplorerCrumb("Workshop", PATH)),
                entries = all.subList(from, until),
                nextCursor = if (until < all.size) "$until" else null,
                total = all.size,
                sort = ExplorerSortApplied(applied.key, applied.dir),
                sorts = OFFERED,
            )
        }

        override suspend fun locate(type: String, id: String): JsonElement = JsonObject(mapOf("path" to JsonPrimitive(PATH)))
        override suspend fun transcript(id: String) = ExplorerTranscript(mediaId = id)
        override suspend fun stats(path: String, include: String?) = ExplorerStats()
        override suspend fun selectionStats(body: ExplorerStatsBody) = ExplorerStats()
        override suspend fun reportJson(path: String, q: String?, format: String, sheet: String?, offset: Int?) = ExplorerReport()
        override suspend fun languages() = ExplorerLanguages()
        override suspend fun manifestStream(path: String, include: String?, stream: Int): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun selectionManifestStream(body: ExplorerSelectionBody): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun media(id: String, format: String?, fallback: String?, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun path(path: String, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
    }

    private val more = DesignBoards.More()

    @Before fun fakes() {
        ExplorerMoreApi.override = more
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerSortMemory.clear(app)
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerNetwork.override = null
        ExplorerSortMemory.clear(app)
    }

    private fun show(fake: Sorted): () -> ExplorerState {
        var held: ExplorerState? = null
        compose.setContent {
            MaterialTheme {
                val state = rememberExplorerState(fake)
                held = state
                ExplorerScreen(onBack = {}, api = fake, more = more, startPath = PATH, held = state)
            }
        }
        compose.waitUntil(10_000) { fake.calls.any { it.startsWith("tree:$PATH:") } }
        compose.waitForIdle()
        return { held!! }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun asked(call: String) {
        compose.waitUntil(10_000) { fake.calls.contains(call) }
        compose.waitForIdle()
    }

    private val fake = Sorted()

    @QFeature("X1")
    @Test fun pickingDateRecordedThenOldestReloadsEveryPageInTheServersOrder() {
        val state = show(fake)
        compose.waitUntil(10_000) { state().sortKeys.isNotEmpty() }
        scrollTo("explorer.sort")
        compose.onNodeWithTag("explorer.sort.label", useUnmergedTree = true).assert(hasText("Name · A–Z"))
        compose.onNodeWithTag("explorer.sort").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.sort.sheet").assertExists()
        compose.onNodeWithText("Sort by").assertExists()
        OFFERED.forEach { compose.onNodeWithTag("explorer.sort.key.$it").assertExists() }
        compose.onNodeWithTag("explorer.sort.key.duration").assertDoesNotExist()
        compose.onNodeWithTag("explorer.sort.key.name").assertIsSelected()

        compose.onNodeWithTag("explorer.sort.key.recorded").performClick()
        asked("tree:$PATH::recorded:desc")
        compose.onNodeWithTag("explorer.sort.key.recorded").assertIsSelected()
        compose.onNodeWithTag("explorer.sort.dir.desc").assert(hasText("Newest"))
        compose.onNodeWithTag("explorer.sort.dir.asc").assert(hasText("Oldest"))

        compose.onNodeWithTag("explorer.sort.dir.asc").performClick()
        asked("tree:$PATH::recorded:asc")
        compose.onNodeWithTag("explorer.sort.dir.asc").assertIsSelected()
        compose.onNodeWithTag("explorer.sort.done").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("explorer.sort.sheet").assertDoesNotExist() }.isSuccess }

        val expected = fake.ordered("recorded:asc").map { it.mediaId!! }
        assertEquals(listOf("s4", "s2", "s5", "s1", "s3"), expected)
        compose.waitUntil(15_000) {
            runCatching { scrollTo("explorer.file.${expected.last()}") }.isSuccess || run {
                runCatching { scrollTo("explorer.more") }
                false
            }
        }
        asked("tree:$PATH:$PAGE:recorded:asc")
        compose.waitUntil(10_000) { state().entries.size == expected.size }
        assertEquals(expected, state().entries.map { it.mediaId })

        expected.zipWithNext().forEach { (upper, lower) ->
            scrollTo("explorer.file.$lower")
            val top = compose.onNodeWithTag("explorer.file.$upper").getBoundsInRoot().top
            val below = compose.onNodeWithTag("explorer.file.$lower").getBoundsInRoot().top
            assertTrue("$upper is above $lower", top < below)
        }

        scrollTo("explorer.sort")
        compose.onNodeWithTag("explorer.sort.label", useUnmergedTree = true).assert(hasText("Date recorded · Oldest"))
        assertEquals(ExplorerSort("recorded", "asc"), ExplorerSortMemory(app, ExplorerPortal.userId(app)).read(ExplorerSorts.treeLevel(PATH)))
    }

    @QFeature("X1")
    @Test fun aRememberedChoiceIsSentWhenTheFolderOpens() {
        ExplorerSortMemory(app, ExplorerPortal.userId(app)).write(ExplorerSorts.treeLevel(PATH), ExplorerSort("name", "desc"))
        val state = show(fake)
        asked("tree:$PATH::name:desc")
        compose.waitUntil(10_000) { state().entries.isNotEmpty() }
        assertEquals(listOf("s5", "s4", "s3"), state().entries.take(PAGE).map { it.mediaId })
        assertTrue(fake.calls.none { it == "tree:$PATH::" })
        scrollTo("explorer.sort")
        compose.onNodeWithTag("explorer.sort.label", useUnmergedTree = true).assert(hasText("Name · Z–A"))
    }

    companion object {
        const val PATH = "sorted/workshop"
        const val PAGE = 3
        val OFFERED = listOf("name", "recorded", "added", "size", "type")
    }
}
