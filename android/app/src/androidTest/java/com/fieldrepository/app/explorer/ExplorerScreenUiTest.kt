package com.fieldrepository.app.explorer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerLanguage
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.transcript.data.DataTranscriptFile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExplorerScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class Fake : ExplorerApi {
        val transcriptCalls = AtomicInteger(0)
        val calls = mutableListOf<String>()
        val folder = ExplorerEntry(name = "Bagru workshop", path = "w/bagru", kind = "folder", recordType = "workshop")
        val video = ExplorerEntry(name = "Dabu.mp4", path = "w/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 701L * 1024 * 1024, previewPath = "/data/media/v1/preview")
        val photo = ExplorerEntry(name = "Block.jpg", path = "w/p1", mediaId = "p1", mediaType = "IMAGE", sizeBytes = 2_700_000, thumbnailPath = "/data/media/p1/thumbnail")
        val audio = ExplorerEntry(
            name = "Interview.m4a", path = "w/a1", mediaId = "a1", mediaType = "AUDIO", sizeBytes = 9_000_000, transcriptAvailable = true,
            transcriptFiles = listOf(DataTranscriptFile("a.md", "md", "both", true)),
        )

        override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int): ExplorerTree {
            calls += "tree:$path:${cursor.orEmpty()}:$slim"
            return if (cursor == null) {
                ExplorerTree(path = path, entries = listOf(folder, video, photo, audio), nextCursor = "next", total = 5)
            } else {
                ExplorerTree(path = path, entries = listOf(ExplorerEntry(name = "Late.jpg", path = "w/p2", mediaId = "p2", mediaType = "IMAGE")), total = 5)
            }
        }

        override suspend fun locate(type: String, id: String): JsonElement = JsonObject(mapOf("path" to JsonPrimitive("w")))
        override suspend fun transcript(id: String): ExplorerTranscript {
            transcriptCalls.incrementAndGet()
            return ExplorerTranscript(mediaId = id, content = "**Giriraj** Clay and gum.")
        }
        override suspend fun stats(path: String, include: String?): ExplorerStats {
            calls += "stats:$path"
            return ExplorerStats(files = 926, bytes = 7_150_000_000, byType = mapOf("videos" to ExplorerTypeStats(57, 3_650_000_000), "images" to ExplorerTypeStats(869, 3_500_000_000)))
        }
        override suspend fun selectionStats(body: ExplorerStatsBody): ExplorerStats {
            calls += "stats-selection:${body.paths}:${body.mediaIds}"
            return ExplorerStats(files = 12, bytes = 40_000_000)
        }
        val reportQueries = mutableListOf<String?>()
        override suspend fun reportJson(path: String, q: String?, format: String): ExplorerReport {
            reportQueries += q
            val rows = listOf(listOf(JsonPrimitive("Giriraj"), JsonPrimitive("Bagru")), listOf(JsonPrimitive("Meena"), JsonPrimitive("Sanganer")))
            val matched = if (q == null) rows else rows.filter { r -> r.any { it.content.contains(q, ignoreCase = true) } }
            return ExplorerReport(listOf(ExplorerSheet(name = "Artisans", columns = listOf(JsonPrimitive("Name"), JsonPrimitive("Place")), rows = matched, totalRows = 2, matchedRows = matched.size)))
        }
        override suspend fun languages() = ExplorerLanguages(listOf(ExplorerLanguage("hi", "Hindi", 3), ExplorerLanguage("raj", "Rajasthani", 1)))
        override suspend fun manifestStream(path: String, include: String?, stream: Int): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun selectionManifestStream(body: ExplorerSelectionBody): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun media(id: String, format: String?, fallback: String?, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun path(path: String, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
    }

    private fun show(fake: Fake) {
        compose.setContent {
            MaterialTheme {
                ExplorerScreen(
                    onBack = {},
                    api = fake,
                    startPath = "w",
                    search = { Text("search-slot", modifier = Modifier.testTag("explorer.search.slot")) },
                )
            }
        }
        compose.waitUntil(5_000) { fake.calls.any { it.startsWith("tree:w") } }
        compose.waitForIdle()
    }

    @QFeature("X1")
    @Test fun theTreeComesFirstWithSearchAsItsToolbarAndOneDownloadButton() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.toolbar").assertIsDisplayed()
        compose.onNodeWithTag("explorer.search.slot", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("explorer.folder.w/bagru").assertIsDisplayed()
        compose.onNodeWithTag("explorer.download").assertIsDisplayed()
        compose.onNodeWithTag("explorer.downloads").assertIsDisplayed()
        assertTrue(fake.calls.first().endsWith(":1"))
    }

    @QFeature("X2")
    @Test fun theDownloadSheetShowsCountAndSizePerTypeBeforeStarting() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.download").performClick()
        compose.waitUntil(5_000) { fake.calls.contains("stats:w") }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.sheet.download").assertIsDisplayed()
        compose.onNodeWithTag("explorer.sheet.total").assertIsDisplayed()
        compose.onAllNodesWithText("926 files · 6.7 GB").onFirst().assertIsDisplayed()
        compose.onNodeWithText("57 files · 3.4 GB").assertIsDisplayed()
        compose.onNodeWithTag("explorer.choice.transcripts").assertIsDisplayed()
        compose.onNodeWithTag("explorer.choice.report").assertIsDisplayed()
    }

    @QFeature("X3")
    @Test fun aLongPressStartsSelectionAndTheBarDownloadsTheSelection() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.folder.w/bagru").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.check.v1").performClick()
        compose.onNodeWithText("Download 2 selected").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("explorer.download").performClick()
        compose.waitUntil(5_000) { fake.calls.any { it.startsWith("stats-selection") } }
        assertEquals("stats-selection:[w/bagru]:[v1]", fake.calls.first { it.startsWith("stats-selection") })
    }

    @QFeature("X4")
    @Test fun imagesShowAThumbnailNotTheOriginal() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.thumb.p1", useUnmergedTree = true).assertExists()
    }

    @QFeature("X5")
    @Test fun videosAndImagesOfferAPreview() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.preview.v1").assertIsDisplayed()
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.preview.p1"))
        compose.onNodeWithTag("explorer.preview.p1").assertIsDisplayed()
    }

    @QFeature("X7")
    @Test fun aTranscribedRecordingOffersAllFormatsInOneZip() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.allformats.a1"))
        compose.onNodeWithTag("explorer.allformats.a1").assertIsDisplayed()
    }

    @QFeature("X12")
    @Test fun aTranscriptLoadsOnlyWhenOpenedAndOnlyOnce() {
        val fake = Fake()
        show(fake)
        assertEquals(0, fake.transcriptCalls.get())
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.transcript.a1"))
        compose.onNodeWithTag("explorer.transcript.a1").performClick()
        compose.waitUntil(5_000) { fake.transcriptCalls.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Clay and gum.", substring = true).assertExists()
        compose.onNodeWithTag("explorer.transcript.a1").performClick()
        compose.onNodeWithTag("explorer.transcript.a1").performClick()
        compose.waitForIdle()
        assertEquals(1, fake.transcriptCalls.get())
    }

    @QFeature("X8")
    @Test fun aLongFolderPagesWithLoadMore() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithText("Showing 4 of 5", substring = true).assertExists()
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.more"))
        compose.onNodeWithTag("explorer.more").performClick()
        compose.waitUntil(5_000) { fake.calls.any { it == "tree:w:next:1" } }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.file.p2"))
        compose.onNodeWithTag("explorer.file.p2").assertExists()
    }

    @QFeature("X6")
    @Test fun tableSearchAsksTheServerForEveryRowAndEachSheetDownloadsAsCsv() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.tables.toggle"))
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        compose.waitUntil(5_000) { fake.reportQueries.isNotEmpty() }
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("explorer.tables.search"))
        compose.onNodeWithTag("explorer.tables.search").performTextInput("meena")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { fake.reportQueries.contains("meena") }
        compose.waitForIdle()
        compose.onNodeWithText("1 of 2 rows match").assertExists()
        compose.onNodeWithTag("explorer.csv.Artisans").assertExists()
    }

    @QFeature("X9")
    @Test fun theTranscriptLanguageFilterIsFilledFromTheServer() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("data.transcripts.toggle"))
        compose.onNodeWithTag("data.transcripts.toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag("transcript.data.language.raj"))
        compose.onNodeWithTag("transcript.data.language.raj").assertExists()
        compose.onNodeWithText("Rajasthani").assertExists()
    }
}
