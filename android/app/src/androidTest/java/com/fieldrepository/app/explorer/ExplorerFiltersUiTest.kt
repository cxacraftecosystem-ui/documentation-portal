package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerActiveFilter
import com.fieldrepository.app.explorer.data.ExplorerActiveValue
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerAppliedFilters
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerFacet
import com.fieldrepository.app.explorer.data.ExplorerFacetOption
import com.fieldrepository.app.explorer.data.ExplorerFacets
import com.fieldrepository.app.explorer.data.ExplorerFilterApi
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerNetwork
import com.fieldrepository.app.explorer.data.ExplorerPeerPage
import com.fieldrepository.app.explorer.data.ExplorerPeerRow
import com.fieldrepository.app.explorer.data.ExplorerPortalChoices
import com.fieldrepository.app.explorer.data.ExplorerPortalOption
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerReportSheets
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTranscriptList
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.ExplorerPaneWidth
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerTableWidths
import com.fieldrepository.app.explorer.ui.ExplorerTablesMemory
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
class ExplorerFiltersUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    class Tree : ExplorerApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val reports: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

        private val folders = listOf(
            ExplorerEntry(name = "Bamboo", path = "$ROOT/c1", kind = "folder", recordType = "craft", count = 4, bytes = 4_000_000),
            ExplorerEntry(name = "Clay", path = "$ROOT/c2", kind = "folder", recordType = "craft", count = 2, bytes = 2_000_000),
        )
        private val details = ExplorerEntry(name = "details.txt", path = "$ROOT/details.txt", content = DETAILS)
        private val video = ExplorerEntry(name = "Dyeing.mp4", path = "$ROOT/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 2_000_000)

        override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int, sort: String?, filters: Map<String, String>): ExplorerTree {
            calls += "tree:$path:" + filters.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
            val crafts = filters["craftIds"]?.split(',').orEmpty()
            val nothing = filters["mediaTypes"] == "PDF"
            val shown = when {
                nothing -> emptyList()
                crafts.isEmpty() -> folders + details + video
                else -> folders.filter { it.path.substringAfterLast('/') in crafts }.map { it.copy(count = 1) } + details
            }
            val active = buildList {
                if (crafts.isNotEmpty()) add(ExplorerActiveFilter("craftIds", "craft", "Craft", crafts.map { ExplorerActiveValue(it, if (it == "c1") "Bamboo" else "Clay") }))
                if (nothing) add(ExplorerActiveFilter("mediaTypes", "mediaType", "Media type", listOf(ExplorerActiveValue("PDF", "PDF"))))
            }
            return ExplorerTree(
                path = path,
                crumbs = listOf(ExplorerCrumb("By workshop", "by-workshop"), ExplorerCrumb("Bagru", ROOT)),
                entries = shown,
                total = shown.size,
                taxonomies = listOf(ExplorerTaxonomy("by-workshop", "By workshop", "by-workshop", isDefault = true), ExplorerTaxonomy("by-type", "By media type", "by-type")),
                taxonomy = "by-workshop",
                filters = ExplorerAppliedFilters(active, active.size),
            )
        }

        override suspend fun locate(type: String, id: String): JsonElement = JsonObject(mapOf("path" to JsonPrimitive(ROOT)))
        override suspend fun transcript(id: String) = ExplorerTranscript(mediaId = id)
        override suspend fun stats(path: String, include: String?, filters: Map<String, String>): ExplorerStats {
            calls += "stats:$path:" + filters.keys.sorted().joinToString(",")
            return ExplorerStats()
        }
        override suspend fun selectionStats(body: ExplorerStatsBody) = ExplorerStats()
        override suspend fun reportJson(path: String, q: String?, format: String, sheet: String?, offset: Int?, filters: Map<String, String>): ExplorerReport {
            reports += filters
            val rows = listOf(
                listOf(JsonPrimitive("Bagru 2026"), JsonPrimitive(SCHEME), JsonPrimitive("Bagru, Rajasthan")),
                listOf(JsonPrimitive("Kutch 2026"), JsonPrimitive("$SCHEME $SCHEME"), JsonPrimitive("Bhuj")),
            )
            return ExplorerReport(listOf(ExplorerSheet(name = "Schemes", columns = listOf(JsonPrimitive("Workshop"), JsonPrimitive("Scheme"), JsonPrimitive("Place")), rows = rows, totalRows = 2, rowNumbers = listOf(1, 2))))
        }
        override suspend fun languages() = ExplorerLanguages()
        override suspend fun manifestStream(path: String, include: String?, stream: Int): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun selectionManifestStream(body: ExplorerSelectionBody): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun media(id: String, format: String?, fallback: String?, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun path(path: String, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
    }

    class Facets : ExplorerFilterApi {
        val peers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun filters(path: String?, filters: Map<String, String>) = FACETS

        override suspend fun peer(type: String?, q: String?, page: Int, pageSize: Int, filters: Map<String, String>): ExplorerPeerPage {
            peers += "peer:${type.orEmpty()}:${q.orEmpty()}:$page:" + filters.keys.sorted().joinToString(",")
            return ExplorerPeerPage(
                portal = "fieldRepository",
                portalLabel = "Field Repository",
                items = listOf(ExplorerPeerRow(portal = "fieldRepository", portalLabel = "Field Repository", type = "artisans", id = "a9", title = "Meena Devi", subtitle = "Block printing", place = "Sanganer", href = "/remote/artisans/a9")),
                total = 1,
                page = 1,
                pageSize = 20,
                pages = 1,
            )
        }
    }

    class Spy(private val base: ExplorerMore = DesignBoards.More()) : ExplorerMore by base {
        val transcripts: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        val sheets: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())

        override suspend fun transcripts(path: String, q: String?, status: String?, offset: Int?, sort: String?, filters: Map<String, String>): ExplorerTranscriptList {
            transcripts += filters
            return ExplorerTranscriptList()
        }

        override suspend fun reportSheets(path: String, status: String?, q: String?, filters: Map<String, String>): ExplorerReportSheets {
            sheets += filters
            return ExplorerReportSheets(path = path)
        }
    }

    private val tree = Tree()
    private val facets = Facets()
    private val more = Spy()
    private val remote = Collections.synchronizedList(mutableListOf<String>())

    @Before fun fakes() {
        ExplorerTablesMemory.clear()
        ExplorerMoreApi.override = more
        ExplorerNetwork.override = ExplorerLink(online = true, metered = false)
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerNetwork.override = null
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
    }

    private var width by mutableStateOf<Int?>(null)

    private fun show(at: Int? = null) {
        width = at
        compose.setContent {
            MaterialTheme {
                val body: @androidx.compose.runtime.Composable () -> Unit = {
                    ExplorerScreen(onBack = {}, api = tree, more = more, filterApi = facets, startPath = ROOT, onOpenRemote = { t, id -> remote += "$t/$id" })
                }
                val w = width
                if (w != null) Box(Modifier.requiredWidth(w.dp)) { body() } else body()
            }
        }
        compose.waitUntil(10_000) { tree.calls.any { it.startsWith("tree:$ROOT:") } }
        compose.waitForIdle()
    }

    private fun appears(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(tag: String) {
        compose.waitUntil(10_000) {
            listOf("explorer.tree", "explorer.pane.tree").any { list ->
                compose.onAllNodesWithTag(list).fetchSemanticsNodes().isNotEmpty() &&
                    runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }.isSuccess
            }
        }
        compose.waitForIdle()
    }

    private fun pick(facet: String, vararg ids: String) {
        scrollTo("explorer.filters.open")
        compose.onNodeWithTag("explorer.filters.open").performClick()
        appears("explorer.filters.sheet")
        compose.onNodeWithTag("explorer.filter.$facet").performScrollTo().performClick()
        appears("explorer.filter.$facet.panel")
        ids.forEach { id ->
            appears("explorer.filter.$facet.option.$id")
            compose.onNodeWithTag("explorer.filter.$facet.option.$id").performClick()
        }
        compose.onNodeWithTag("explorer.filter.$facet.apply").performClick()
        compose.onNodeWithTag("explorer.filters.done").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @QFeature("X13")
    @Test fun aMultiSelectIsSearchableWithStaticCheckboxesAndNarrowsTheTreeAndTheFolder() {
        show()
        scrollTo("explorer.filters.open")
        compose.onNodeWithTag("explorer.filters.open").performClick()
        appears("explorer.filters.sheet")
        compose.onNodeWithTag("explorer.filter.craft").performScrollTo().performClick()
        appears("explorer.filter.craft.option.c1")
        compose.onNodeWithTag("explorer.filter.craft.search").performTextInput("bam")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("explorer.filter.craft.option.c2").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("explorer.filter.craft.option.c1").assertIsOff().performClick()
        compose.onNodeWithTag("explorer.filter.craft.option.c1").assertIsOn()
        compose.onNodeWithTag("explorer.filter.craft.apply").performClick()
        compose.onNodeWithTag("explorer.filters.done").performScrollTo().performClick()
        compose.waitUntil(10_000) { tree.calls.contains("tree:$ROOT:craftIds=c1") }
        compose.waitForIdle()
        appears("explorer.filters.chip.CRAFT.c1")
        compose.onNodeWithText("Craft: Bamboo").assertExists()
        scrollTo("explorer.folder.$ROOT/c1")
        assertTrue(compose.onAllNodesWithTag("explorer.folder.$ROOT/c2").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("explorer.file.v1").fetchSemanticsNodes().isEmpty())
        compose.waitUntil(10_000) { tree.calls.any { it == "stats:$ROOT:craftIds" } }
        compose.waitUntil(10_000) { more.sheets.any { it["craftIds"] == "c1" } }
    }

    @QFeature("X13")
    @Test fun anEmptyFilteredFolderNamesItsFiltersAndClears() {
        show()
        pick("mediaType", "PDF")
        compose.waitUntil(10_000) { tree.calls.contains("tree:$ROOT:mediaTypes=PDF") }
        scrollTo("explorer.filtered.empty")
        compose.onNodeWithText("Nothing here matches Media type: PDF.").assertExists()
        compose.onNodeWithTag("explorer.filtered.clear").performClick()
        compose.waitUntil(10_000) { tree.calls.count { it == "tree:$ROOT:" } >= 2 }
        scrollTo("explorer.file.v1")
    }

    @QFeature("X13")
    @Test fun theDesignWorkshopFilterIsAMultiSelect() {
        show()
        pick("designWorkshop", "d1", "d2")
        compose.waitUntil(10_000) { tree.calls.contains("tree:$ROOT:designWorkshopIds=d1,d2") }
        appears("explorer.filters.chip.DESIGN_WORKSHOP.d1")
        appears("explorer.filters.chip.DESIGN_WORKSHOP.d2")
    }

    @QFeature("X13")
    @Test fun theDateRangeOpensACalendar() {
        show()
        scrollTo("explorer.filters.open")
        compose.onNodeWithTag("explorer.filters.open").performClick()
        appears("explorer.filters.sheet")
        compose.onNodeWithTag("explorer.filter.dates").performScrollTo().performClick()
        appears("explorer.filter.dates.dialog")
        compose.onNodeWithTag("explorer.filter.dates.clear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.filters.sheet").assertExists()
    }

    @QFeature("X13")
    @Test fun filtersReachTheTablesAndTheTranscripts() {
        show(1200)
        pick("craft", "c1", "c2")
        compose.waitUntil(10_000) { tree.calls.contains("tree:$ROOT:craftIds=c1,c2") }
        scrollTo("explorer.tables.toggle")
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        compose.waitUntil(10_000) { tree.reports.any { it["craftIds"] == "c1,c2" } }
        scrollTo("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").performClick()
        compose.waitUntil(10_000) { more.transcripts.any { it["craftIds"] == "c1,c2" } }
    }

    @QFeature("X13")
    @Test fun otherPortalResultsCarryAChipAndOpenThroughTheCrossPortalLink() {
        show()
        pick("portal", "other", "this")
        scrollTo("explorer.filtered.local")
        scrollTo("explorer.peer.row.artisans.a9")
        compose.onNodeWithTag("explorer.peer.chip.a9", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Field Repository").assertExists()
        assertTrue(compose.onAllNodesWithTag("explorer.file.v1").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("explorer.peer.row.artisans.a9").performClick()
        compose.waitUntil(5_000) { remote.contains("artisans/a9") }
    }

    @QFeature("X13")
    @Test fun bothPortalsListLocalRowsAndPeerRows() {
        show()
        pick("portal", "other")
        scrollTo("explorer.peer.row.artisans.a9")
        scrollTo("explorer.file.v1")
        assertTrue(facets.peers.isNotEmpty())
    }

    @QFeature("X1")
    @Test fun theTabletPaneNeverOverlapsTheContentAtAnyDragPosition() {
        show(840)
        listOf(840, 1024, 1280, 1440).forEach { at ->
            width = at
            compose.waitForIdle()
            appears("explorer.pane.divider")
            listOf(-4_000f, 4_000f, -150f).forEach { by ->
                compose.onNodeWithTag("explorer.pane.divider").performTouchInput {
                    down(center)
                    moveBy(Offset(by.dp.toPx(), 0f))
                    up()
                }
                compose.waitForIdle()
                val pane = compose.onNodeWithTag("explorer.pane.tree").getBoundsInRoot()
                val divider = compose.onNodeWithTag("explorer.pane.divider").getBoundsInRoot()
                val content = compose.onNodeWithTag("explorer.content").getBoundsInRoot()
                assertTrue("at $at the pane ends at ${pane.right} past the divider ${divider.left}", pane.right.value <= divider.left.value + 0.5f)
                assertTrue("at $at the content starts at ${content.left} inside the divider ${divider.right}", content.left.value >= divider.right.value - 0.5f)
                assertTrue("at $at the content is ${content.right - content.left} wide", (content.right - content.left).value >= ExplorerPaneWidth.CONTENT_MIN - 1f)
            }
        }
    }

    @QFeature("X6")
    @Test fun theTablesGridWrapsLongBengaliWithoutCellScrollsOrOverlap() {
        show()
        scrollTo("explorer.tables.toggle")
        compose.onNodeWithTag("explorer.tables.toggle").performClick()
        appears("explorer.tables.grid")
        compose.waitForIdle()
        val scrolling = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange).and(hasAnyAncestor(hasTestTag("explorer.tables.grid")))
        assertEquals(0, compose.onAllNodes(scrolling, useUnmergedTree = true).fetchSemanticsNodes().size)
        val nested = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange).and(hasAnyAncestor(hasTestTag("explorer.tables.grid")))
        assertEquals(0, compose.onAllNodes(nested, useUnmergedTree = true).fetchSemanticsNodes().size)
        listOf(0, 1).forEach { row ->
            val cells = (0..2).map { box("explorer.tables.cell.Schemes.$row.$it") }
            cells.forEach { c -> assertTrue("a cell is ${c.width} dp wide", c.width >= ExplorerTableWidths.MIN - 1 && c.width <= ExplorerTableWidths.MAX + 1) }
            cells.zipWithNext().forEach { (a, b) -> assertTrue("cells overlap: ${a.right} > ${b.left}", a.right <= b.left + 0.5f) }
            val heights = cells.map { it.height }
            assertTrue("cells of one row share a height: $heights", heights.max() - heights.min() < 1f)
        }
        val first = box("explorer.tables.row.Schemes.0")
        val second = box("explorer.tables.row.Schemes.1")
        assertTrue("rows overlap: ${first.bottom} > ${second.top}", first.bottom <= second.top + 0.5f)
        assertTrue("the long Bengali row grew: ${second.height}", second.height > 80f)
    }

    private class Bounds(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    private fun box(tag: String): Bounds {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val d = compose.density.density
        return Bounds(node.positionInRoot.x / d, node.positionInRoot.y / d, node.size.width / d, node.size.height / d)
    }

    @QFeature("X1")
    @Test fun detailsTextRendersAsAFieldValueTable() {
        show()
        scrollTo("explorer.file.$ROOT/details.txt")
        compose.onNodeWithTag("explorer.file.$ROOT/details.txt").performClick()
        appears("explorer.text.table")
        compose.onNodeWithText("Workshop id").assertExists()
        compose.onNodeWithText("Scheme").assertExists()
    }

    companion object {
        const val ROOT = "by-workshop/w1"
        const val SCHEME = "প্রধানমন্ত্রী বিশ্বকর্মা যোজনা, হস্তশিল্প উন্নয়ন কর্মসূচির অধীনে কারিগর প্রশিক্ষণ ও সরঞ্জাম সহায়তা"
        const val DETAILS = "Bagru block printing workshop\nWorkshop id: 6a1f0c2e\nScheme: $SCHEME\nPlace: Bagru, Rajasthan\nDates: 2026-01-10 to 2026-01-14"

        val FACETS = ExplorerFacets(
            facets = listOf(
                ExplorerFacet("workshop", "workshopIds", "Workshop", listOf(ExplorerFacetOption("w1", "Bagru", count = 6))),
                ExplorerFacet("designWorkshop", "designWorkshopIds", "Design workshop", listOf(ExplorerFacetOption("d1", "Kutch design week", count = 3), ExplorerFacetOption("d2", "Bagru studio", count = 2))),
                ExplorerFacet("craft", "craftIds", "Craft", listOf(ExplorerFacetOption("c1", "Bamboo", count = 4), ExplorerFacetOption("c2", "Clay", count = 2))),
                ExplorerFacet("artisan", "artisanIds", "Artisan", listOf(ExplorerFacetOption("a1", "Giriraj", count = 5))),
                ExplorerFacet("mediaType", "mediaTypes", "Media type", listOf(ExplorerFacetOption("VIDEO", "Video", count = 1), ExplorerFacetOption("PDF", "PDF", count = 0))),
                ExplorerFacet("recordedBy", "recordedByIds", "Recorded by", listOf(ExplorerFacetOption("u1", "Asha", count = 6))),
                ExplorerFacet("language", "languages", "Language", listOf(ExplorerFacetOption("ben", "Bengali", count = 1))),
            ),
            portal = ExplorerPortalChoices(listOf(ExplorerPortalOption("this", "This portal", true, "ok"), ExplorerPortalOption("other", "Field Repository", true, "ok"))),
        )
    }
}
