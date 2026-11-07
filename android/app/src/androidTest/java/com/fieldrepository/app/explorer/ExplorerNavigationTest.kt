package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerEnvironment
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.data.ExplorerPlace
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.ui.ExplorerPaneWidth
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.explorer.ui.rememberDataExplorerState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ExplorerNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val server = MockWebServer()
    private val trees = AtomicInteger(0)
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val opened = Collections.synchronizedList(mutableListOf<String>())
    private var backs = 0
    @Volatile private var nextPage = CountDownLatch(0)

    private val taxonomies = listOf(
        ExplorerTaxonomy("workshop", "Workshop", "by-workshop", "Workshop › artisan › record.", isDefault = true),
        ExplorerTaxonomy("craft", "Craft", "by-craft", "Craft › artisan."),
    )
    private val folder = ExplorerEntry(name = "Bagru workshop", path = "w/bagru", kind = "folder", recordType = "workshop", count = 3)
    private val photo = ExplorerEntry(name = "Block.jpg", path = "w/bagru/p1", mediaId = "p1", mediaType = "IMAGE", sizeBytes = 2_700_000)
    private val clip = ExplorerEntry(name = "Dabu.mp4", path = "w/bagru/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 9_000_000)
    private val late = ExplorerEntry(name = "Kiln.jpg", path = "w/bagru/p3", mediaId = "p3", mediaType = "IMAGE", sizeBytes = 1_000_000)
    private val bagruCrumbs = listOf(ExplorerCrumb("Workshop", "by-workshop"), ExplorerCrumb("Bagru workshop", "w/bagru"))

    private fun treeFor(path: String, cursor: String?): ExplorerTree = when (path) {
        "" -> ExplorerTree(path = "", taxonomies = taxonomies)
        "by-workshop" -> ExplorerTree(path = path, crumbs = listOf(ExplorerCrumb("Workshop", "by-workshop")), entries = listOf(folder), total = 1, taxonomies = taxonomies, taxonomy = "workshop")
        "w/bagru" -> if (cursor == null) {
            ExplorerTree(path = path, crumbs = bagruCrumbs, entries = listOf(photo, clip), nextCursor = "2", total = 3, taxonomies = taxonomies, taxonomy = "workshop")
        } else {
            ExplorerTree(path = path, crumbs = bagruCrumbs, entries = listOf(late), total = 3, taxonomies = taxonomies, taxonomy = "workshop")
        }
        else -> ExplorerTree(path = path, taxonomies = taxonomies, taxonomy = "workshop")
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private lateinit var api: ExplorerApi
    private lateinit var more: ExplorerMore

    @Before fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                seen += url.encodedPath + "?" + url.encodedQuery.orEmpty()
                return when (url.encodedPath) {
                    "/data/tree" -> {
                        trees.incrementAndGet()
                        if (url.queryParameter("cursor") != null) nextPage.await(15, TimeUnit.SECONDS)
                        json(ExplorerJson.encodeToString(ExplorerTree.serializer(), treeFor(url.queryParameter("path").orEmpty(), url.queryParameter("cursor"))))
                    }
                    "/data/transcripts/languages" -> json("{\"languages\":[]}")
                    else -> MockResponse().setResponseCode(404).setBody("{\"detail\":\"Not found\"}")
                }
            }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(ExplorerJson.asConverterFactory("application/json".toMediaType()))
            .build()
        api = retrofit.create(ExplorerApi::class.java)
        more = retrofit.create(ExplorerMore::class.java)
        ExplorerEnvironment.apiOverride = api
        ExplorerMoreApi.override = more
        ExplorerPlace.write(app, ExplorerPortal.userId(app), "w/bagru")
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
    }

    @After fun stop() {
        nextPage.countDown()
        ExplorerEnvironment.apiOverride = null
        ExplorerMoreApi.override = null
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
        ExplorerPaneWidth.write(app, ExplorerPaneWidth.DEFAULT)
        server.shutdown()
    }

    private fun screen(width: Int? = null) {
        val body: @androidx.compose.runtime.Composable () -> Unit = {
            ExplorerScreen(onBack = { backs++ }, onOpenMedia = { opened += it })
        }
        compose.setContent { MaterialTheme { if (width != null) Box(Modifier.requiredWidth(width.dp)) { body() } else body() } }
    }

    private fun appears(tag: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun gone(tag: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
    }

    private fun reach(tag: String) {
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun paneWidthDp(): Float {
        val bounds = compose.onNodeWithTag("explorer.pane.tree").getBoundsInRoot()
        return (bounds.right - bounds.left).value
    }

    @QFeature("X1")
    @Test fun aFileTapPreviewsInDataAndOpenRecordIsItsOwnAction() {
        screen()
        reach("explorer.file.p1")
        compose.onNodeWithTag("explorer.file.p1").performClick()
        appears("explorer.preview.media")
        assertTrue("a tap left Data for the record page: $opened", opened.isEmpty())
        compose.onNodeWithTag("explorer.preview.record").performClick()
        gone("explorer.preview.media")
        assertEquals(listOf("p1"), opened.toList())
    }

    @QFeature("X1")
    @Test fun returningFromARecordKeepsTheFolderTabAndTree() {
        var inData by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                val held = rememberDataExplorerState()
                if (inData) {
                    holder.SaveableStateProvider("data") { ExplorerScreen(onBack = {}, held = held, onOpenMedia = { opened += it; inData = false }) }
                } else {
                    Text("record")
                }
            }
        }
        reach("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").performClick()
        compose.waitForIdle()
        val before = trees.get()
        inData = false
        compose.waitForIdle()
        inData = true
        appears("explorer.screen")
        compose.waitForIdle()
        compose.onNodeWithTag("data.transcripts.toggle").assertIsSelected()
        compose.onNodeWithTag("explorer.folder.card").assertExists()
        assertEquals("coming back asked for the folder again: $seen", before, trees.get())
    }

    @QFeature("X1")
    @Test fun aRestoredScreenComesBackOnTheSameFolderAndTab() {
        val tester = StateRestorationTester(compose)
        tester.setContent { MaterialTheme { ExplorerScreen(onBack = {}) } }
        reach("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").performClick()
        compose.waitForIdle()
        ExplorerPlace.write(app, ExplorerPortal.userId(app), "by-workshop")
        tester.emulateSavedInstanceStateRestore()
        reach("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").assertIsSelected()
        compose.onNodeWithTag("explorer.crumbs").assertExists()
    }

    @QFeature("X1")
    @Test fun backStepsUpTheFoldersBeforeLeavingData() {
        screen()
        reach("explorer.file.p1")
        Espresso.pressBack()
        reach("explorer.folder.w/bagru")
        assertEquals(0, backs)
        Espresso.pressBack()
        compose.waitUntil(5_000) { backs == 1 }
    }

    @QFeature("X1")
    @Test fun theShowingRowLoadsUpToTheLastRecord() {
        nextPage = CountDownLatch(1)
        screen()
        appears("explorer.showing")
        compose.onNodeWithTag("explorer.showing").assertExists()
        compose.onNodeWithTag("explorer.showing.more").performClick()
        nextPage.countDown()
        reach("explorer.file.p3")
        gone("explorer.showing")
        assertTrue(seen.any { it.startsWith("/data/tree?") && it.contains("cursor=2") })
    }

    @QFeature("X1")
    @Test fun theTabletPaneDragsWithinItsLimitsAndIsRemembered() {
        screen(width = 1200)
        appears("explorer.pane.divider")
        assertTrue(abs(paneWidthDp() - ExplorerPaneWidth.DEFAULT) < 2f)
        compose.onNodeWithTag("explorer.pane.divider").performTouchInput {
            down(center)
            moveBy(Offset(150.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        val wider = paneWidthDp()
        assertTrue("the pane is $wider dp after a drag", wider > ExplorerPaneWidth.DEFAULT + 100f)
        assertTrue(abs((ExplorerPaneWidth.read(app) ?: 0f) - wider) < 2f)
        compose.onNodeWithTag("explorer.pane.divider").performTouchInput {
            down(center)
            moveBy(Offset(-2_000.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue(abs(paneWidthDp() - ExplorerPaneWidth.MIN) < 2f)
        compose.onNodeWithTag("explorer.pane.divider").performTouchInput {
            down(center)
            moveBy(Offset(4_000.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue(abs(paneWidthDp() - 1200f * ExplorerPaneWidth.MAX_SHARE) < 2f)
        compose.onNodeWithTag("explorer.pane.divider").performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
        compose.waitForIdle()
        assertTrue(abs(paneWidthDp() - 400f) < 2f)
        assertTrue(abs((ExplorerPaneWidth.read(app) ?: 0f) - 400f) < 2f)
    }

    @QFeature("X1")
    @Test fun theTabletHeaderBackStepsUpThenLeaves() {
        screen(width = 1200)
        appears("explorer.file.p1")
        compose.onNodeWithTag("explorer.pane.back").performClick()
        appears("explorer.content.folder.w/bagru")
        assertEquals(0, backs)
        compose.onNodeWithTag("explorer.pane.back").performClick()
        compose.waitUntil(5_000) { backs == 1 }
    }

    @QFeature("X1")
    @Test fun aWebLinkOpensItsTabAndPreview() {
        screen()
        reach("explorer.file.p1")
        ExplorerViewLinks.request(ExplorerViewLinks.parse(ExplorerPortal.WEB_BASE + "/data?path=w%2Fbagru&view=transcripts&q=clay")!!)
        compose.waitUntil(20_000) { ExplorerViewLinks.requests.value == null }
        reach("data.transcripts.toggle")
        compose.onNodeWithTag("data.transcripts.toggle").assertIsSelected()
        ExplorerViewLinks.request(ExplorerViewLinks.parse(ExplorerPortal.WEB_BASE + "/data?path=w%2Fbagru&preview=x9&kind=IMAGE&hit=mud")!!)
        appears("explorer.preview.media")
        compose.waitUntil(20_000) { ExplorerViewLinks.requests.value == null }
        assertNull(ExplorerViewLinks.requests.value)
    }
}
