package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerDownloadsTap
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerInfoField
import com.fieldrepository.app.explorer.data.ExplorerLanguage
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerPeaks
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerReportOptionsBody
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTranscriptList
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import com.fieldrepository.app.explorer.data.ExplorerView
import com.fieldrepository.app.explorer.data.ExplorerViewBody
import com.fieldrepository.app.explorer.data.ExplorerViews
import com.fieldrepository.app.explorer.data.ExplorerVisit
import com.fieldrepository.app.explorer.data.ExplorerVisitBody
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.transcript.data.DataTranscriptFile
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExplorerScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class More : ExplorerMore {
        val visits = mutableListOf<String>()
        override suspend fun reportOptions() = ExplorerReportOptionsBody(null)
        override suspend fun saveReportOptions(body: ExplorerReportOptionsBody) = body
        override suspend fun visit(body: ExplorerVisitBody): ExplorerVisit {
            visits += body.path
            return ExplorerVisit(path = body.path, previousVisitAt = "2026-11-01T00:00:00Z", newFiles = 3, newTranscripts = 1)
        }
        var overview: ExplorerOverview? = null
        override suspend fun overview(path: String, status: String?) = overview ?: ExplorerOverview(path = path)
        var saved: ExplorerView? = null
        override suspend fun view(id: String): ExplorerView = saved ?: throw java.io.IOException("missing")
        var peaks = ExplorerPeaks()
        override suspend fun peaks(id: String) = peaks
        override suspend fun views() = ExplorerViews()
        override suspend fun saveView(body: ExplorerViewBody) = ExplorerView(id = "v1", name = body.name, path = body.path)
        override suspend fun transcripts(path: String, q: String?, status: String?) = ExplorerTranscriptList()
    }

    private class Fake : ExplorerApi {
        val transcriptCalls = AtomicInteger(0)
        val calls = mutableListOf<String>()
        val folder = ExplorerEntry(name = "Bagru workshop", path = "w/bagru", kind = "folder", recordType = "workshop")
        val video = ExplorerEntry(name = "Dabu.mp4", path = "w/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 701L * 1024 * 1024, previewPath = "/data/media/v1/preview", transcriptAvailable = false)
        val photo = ExplorerEntry(name = "Block.jpg", path = "w/p1", mediaId = "p1", mediaType = "IMAGE", sizeBytes = 2_700_000, thumbnailPath = "/data/media/p1/thumbnail")
        val audio = ExplorerEntry(
            name = "Interview.m4a", path = "w/a1", mediaId = "a1", mediaType = "AUDIO", sizeBytes = 9_000_000, transcriptAvailable = true,
            transcriptFiles = listOf(DataTranscriptFile("a.md", "md", "both", true)),
        )
        val crumbs = listOf(ExplorerCrumb("All workshops", "by-workshop"), ExplorerCrumb("Giriraj Prasad Chhipa", "w"))
        val info = ExplorerInfo(
            title = "Giriraj Prasad Chhipa",
            fields = listOf(
                ExplorerInfoField("Craft", "Dabu printing"), ExplorerInfoField("Location", "Bagru, Rajasthan"),
                ExplorerInfoField("Recorded fields", "34 of 40"), ExplorerInfoField("Review", "Approved"), ExplorerInfoField("Phone", "98"),
            ),
        )
        val taxonomies = listOf(
            ExplorerTaxonomy("workshop", "Workshop", "by-workshop", "Workshop › artisan › record.", isDefault = true),
            ExplorerTaxonomy("craft", "Craft", "by-craft", "Craft › artisan."),
        )

        override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int): ExplorerTree {
            calls += "tree:$path:${cursor.orEmpty()}:$slim"
            return if (cursor == null) {
                ExplorerTree(path = path, crumbs = crumbs, info = info, entries = listOf(folder, video, photo, audio), nextCursor = "next", total = 5, taxonomies = taxonomies, taxonomy = "workshop")
            } else {
                ExplorerTree(path = path, crumbs = crumbs, entries = listOf(ExplorerEntry(name = "Late.jpg", path = "w/p2", mediaId = "p2", mediaType = "IMAGE")), total = 5)
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

    private val more = More()

    @Before fun fakes() {
        com.fieldrepository.app.explorer.ui.ExplorerTablesMemory.clear()
        ExplorerMoreApi.override = more
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
    }

    private val sections = mutableListOf<String>()

    private fun show(fake: Fake, fontScale: Float? = null, width: Int? = null, start: String = "w") {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    val body: @androidx.compose.runtime.Composable () -> Unit = {
                        ExplorerScreen(
                            onBack = {},
                            api = fake,
                            more = more,
                            startPath = start,
                            onOpenSection = { artisan, key -> sections += "$artisan:$key" },
                            search = { Text("search-slot", modifier = Modifier.testTag("explorer.search.slot")) },
                        )
                    }
                    if (width != null) Box(Modifier.requiredWidth(width.dp)) { body() } else body()
                }
            }
        }
        compose.waitUntil(5_000) { fake.calls.any { it.startsWith("tree:$start") } }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag))
    }

    @QFeature("X1")
    @Test fun theFolderShowsItsRecordAtAGlanceWithOneDownloadCard() {
        val fake = Fake()
        show(fake)
        compose.onNodeWithTag("explorer.toolbar").assertIsDisplayed()
        compose.onNodeWithTag("explorer.search.slot", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("explorer.organise").assertIsDisplayed()
        compose.onNodeWithText("Workshop › artisan › record.").assertExists()
        scrollTo("explorer.folder.card")
        compose.onNodeWithText("Dabu printing").assertExists()
        compose.onNodeWithText("Show all 5 fields").assertExists()
        compose.onNodeWithTag("explorer.newsince", useUnmergedTree = true).assertExists()
        scrollTo("explorer.download.card")
        compose.onNodeWithTag("explorer.download").assertExists()
        compose.onNodeWithTag("explorer.report").assertExists()
        scrollTo("explorer.folder.w/bagru")
        compose.onNodeWithTag("explorer.folder.w/bagru").assertIsDisplayed()
        compose.onNodeWithTag("explorer.downloads").assertIsDisplayed()
        assertTrue(fake.calls.first().endsWith(":1"))
        assertEquals(listOf("w"), more.visits)
    }

    @QFeature("X2")
    @Test fun theIncludeChipsCarryTheirSizesBeforeDownloading() {
        val fake = Fake()
        show(fake)
        compose.waitUntil(5_000) { fake.calls.contains("stats:w") }
        compose.waitForIdle()
        compose.onAllNodesWithText("926 files · 6.7 GB").onFirst().assertExists()
        scrollTo("explorer.include")
        compose.onNodeWithText("✓ Videos 3.4 GB").assertExists()
        compose.onNodeWithText("✓ Images 3.3 GB").assertExists()
        compose.onNodeWithText("6.7 GB selected").assertExists()
        compose.onNodeWithTag("explorer.include.videos").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Videos 3.4 GB").assertExists()
        compose.onNodeWithText("3.3 GB selected").assertExists()
    }

    @QFeature("X3")
    @Test fun aLongPressStartsSelectionWithSizeAndBreakdown() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.folder.w/bagru")
        compose.onNodeWithTag("explorer.folder.w/bagru").performTouchInput { longClick() }
        compose.waitForIdle()
        scrollTo("explorer.check.v1")
        compose.onNodeWithTag("explorer.check.v1").performClick()
        compose.waitUntil(5_000) { fake.calls.any { it.startsWith("stats-selection") } }
        compose.waitForIdle()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("explorer.select.breakdown").assertIsDisplayed()
        compose.onNodeWithText("1 folder · 1 video", substring = true).assertExists()
        compose.onNodeWithTag("explorer.select.download").assertIsEnabled()
        compose.onNodeWithTag("explorer.select.share").assertIsDisplayed()
        compose.onNodeWithTag("explorer.selectall").assertIsDisplayed()
        assertEquals("stats-selection:[w/bagru]:[v1]", fake.calls.first { it.startsWith("stats-selection") })
    }

    @QFeature("X3")
    @Test fun whileSelectingTypePillsFilterTheFiles() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.file.v1")
        compose.onNodeWithTag("explorer.file.v1").performTouchInput { longClick() }
        compose.waitForIdle()
        scrollTo("explorer.kind.videos")
        compose.onNodeWithTag("explorer.kind.videos").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Block.jpg").assertCountEquals(0)
    }

    @QFeature("X4")
    @Test fun imagesShowAThumbnailNotTheOriginal() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.file.p1")
        compose.onNodeWithTag("explorer.thumb.p1", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithContentDescription("Block.jpg", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Block.jpg", useUnmergedTree = true).assertCountEquals(1)
    }

    @QFeature("X5")
    @Test fun aVideoPreviewOpensWithTranscriptEnglishAndDetailsTabs() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.preview.v1")
        compose.onNodeWithTag("explorer.preview.v1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.preview.media").assertExists()
        compose.onNodeWithTag("explorer.preview.tab.details").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.preview.details").assertExists()
        compose.onNodeWithTag("explorer.preview.next").assertExists()
    }

    @QFeature("X7")
    @Test fun aTranscribedRecordingOffersAllFormatsInOneZip() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.allformats.a1")
        compose.onNodeWithTag("explorer.allformats.a1").assertExists()
    }

    @QFeature("X12")
    @Test fun aTranscriptLoadsOnlyWhenOpenedAndOnlyOnce() {
        val fake = Fake()
        show(fake)
        assertEquals(0, fake.transcriptCalls.get())
        scrollTo("explorer.transcript.a1")
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
        scrollTo("explorer.more")
        compose.onNodeWithTag("explorer.more").performClick()
        compose.waitUntil(5_000) { fake.calls.any { it == "tree:w:next:1" } }
        compose.waitForIdle()
        scrollTo("explorer.file.p2")
        compose.onNodeWithTag("explorer.file.p2").assertExists()
    }

    @QFeature("X6")
    @Test fun tableSearchAsksTheServerForEveryRowAndShowsRowsAsCards() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.tables.toggle")
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        compose.waitUntil(5_000) { fake.reportQueries.isNotEmpty() }
        compose.waitForIdle()
        scrollTo("explorer.tables.sheet")
        compose.onNodeWithTag("explorer.tables.sheet").assertExists()
        scrollTo("explorer.tables.search")
        compose.onNodeWithTag("explorer.tables.search").performTextInput("meena")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { fake.reportQueries.contains("meena") }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.tables.count", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1 of 2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("explorer.csv.Artisans").assertExists()
        compose.onNodeWithTag("explorer.tables.report").assertExists()
        assertEquals(listOf(null, "meena"), fake.reportQueries)
    }

    @QFeature("X6")
    @Test fun theTablesStayOpenWhileScrollingAndAskOnce() {
        val fake = Fake()
        show(fake)
        scrollTo("explorer.tables.toggle")
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        compose.waitUntil(5_000) { fake.reportQueries.isNotEmpty() }
        compose.waitForIdle()
        scrollTo("explorer.toolbar")
        compose.waitForIdle()
        scrollTo("explorer.tables")
        compose.onNodeWithTag("explorer.tables").assertExists()
        assertEquals(1, fake.reportQueries.size)
    }

    @QFeature("X9")
    @Test fun theTranscriptLanguageFilterIsFilledFromTheServer() {
        val fake = Fake()
        show(fake)
        scrollTo("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").performClick()
        compose.waitForIdle()
        scrollTo("explorer.transcripts.pill.language")
        compose.onNodeWithTag("explorer.transcripts.pill.language").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("transcript.data.language.raj").assertExists()
        compose.onNodeWithText("Rajasthani").assertExists()
    }

    @QFeature("X10")
    @Test fun aNotificationTapOpensTheDownloadsSheet() {
        val fake = Fake()
        show(fake)
        ExplorerDownloadsTap.request()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("explorer.sheet.downloads")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer.sheet.downloads").assertExists()
    }

    @QFeature("X1")
    @Test fun atDoubleTextSizeTheDownloadButtonsStackFullWidth() {
        val fake = Fake()
        show(fake, fontScale = 2f)
        scrollTo("explorer.download.card")
        compose.onNodeWithTag("explorer.download").assertExists()
        scrollTo("explorer.report")
        val download = compose.onNodeWithTag("explorer.download").getBoundsInRoot()
        val report = compose.onNodeWithTag("explorer.report").getBoundsInRoot()
        assertTrue("buttons stack at fontScale 2", report.top >= download.bottom)
        assertEquals(download.left, report.left)
        compose.onNodeWithText("Download folder").assertExists()
        compose.onNodeWithText("Report (.xlsx)").assertExists()
    }

    @QFeature("X1")
    @Test fun aTabletWidthShowsTheTreeBesideTheFolder() {
        val fake = Fake()
        show(fake, width = 900)
        compose.onNodeWithTag("explorer.tablet").assertExists()
        compose.onNodeWithTag("explorer.pane.tree").performScrollToNode(hasTestTag("explorer.folder.w/bagru"))
        compose.onNodeWithTag("explorer.folder.w/bagru").assertExists()
    }

    @QFeature("X1")
    @Test fun aSavedViewLinkRestoresTheFolderTabAndSearch() {
        val fake = Fake()
        more.saved = ExplorerView(id = "v1", name = "Meena", path = "w", state = com.fieldrepository.app.explorer.data.ExplorerViewState(view = "tables", q = "meena"))
        show(fake)
        com.fieldrepository.app.explorer.ui.ExplorerViewLinks.request(com.fieldrepository.app.explorer.ui.ExplorerLinkRequest("v1", null))
        compose.waitUntil(5_000) { fake.reportQueries.contains("meena") }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.tables").assertExists()
    }

    @QFeature("X1")
    @Test fun aWorkshopOverviewSquareOpensThatSection() {
        val fake = Fake()
        more.overview = ExplorerOverview(
            path = "by-workshop/w1",
            title = "3rd Craft Toolkit",
            sections = listOf(com.fieldrepository.app.explorer.data.ExplorerSectionHead("B", "Materials")),
            artisans = listOf(
                com.fieldrepository.app.explorer.data.ExplorerOverviewArtisan(
                    id = "a1", name = "Giriraj", path = "by-workshop/w1/crafts/c1/artisans/a1",
                    sections = listOf(com.fieldrepository.app.explorer.data.ExplorerSectionState("B", "partial")),
                ),
            ),
        )
        show(fake, start = "by-workshop/w1")
        scrollTo("explorer.overview")
        compose.onNodeWithTag("explorer.overview.square.a1.B").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a1:B"), sections)
    }

    @QFeature("X5")
    @Test fun clearVoicesShowsOnlyWhenAnIsolatedCopyExists() {
        val audio = ExplorerEntry(name = "Section-D.m4a", path = "w/a9", mediaId = "a9", mediaType = "AUDIO", sizeBytes = 14_000_000)
        more.peaks = ExplorerPeaks(peaks = List(40) { 0.5f }, durationMs = 60_000, isolated = true)
        compose.setContent {
            MaterialTheme {
                com.fieldrepository.app.explorer.ui.ExplorerMediaDialog(entries = listOf(audio), start = 0, onSave = null, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("explorer.audio.clear")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("explorer.audio.waveform").assertExists()
        compose.runOnUiThread { com.fieldrepository.app.explorer.data.ExplorerAudio.stop() }
    }

    @QFeature("X5")
    @Test fun clearVoicesIsAbsentWithoutAnIsolatedCopy() {
        val audio = ExplorerEntry(name = "Section-D.m4a", path = "w/a8", mediaId = "a8", mediaType = "AUDIO", sizeBytes = 14_000_000)
        more.peaks = ExplorerPeaks(peaks = List(40) { 0.5f }, durationMs = 60_000, isolated = false)
        compose.setContent {
            MaterialTheme {
                com.fieldrepository.app.explorer.ui.ExplorerMediaDialog(entries = listOf(audio), start = 0, onSave = null, onDismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("explorer.audio.waveform")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("explorer.audio.clear")).assertCountEquals(0)
        compose.runOnUiThread { com.fieldrepository.app.explorer.data.ExplorerAudio.stop() }
    }
}
