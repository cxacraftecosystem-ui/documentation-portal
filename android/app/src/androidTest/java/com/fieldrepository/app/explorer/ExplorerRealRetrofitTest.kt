package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.fieldrepository.app.explorer.ui.ExplorerLinkRequest
import com.fieldrepository.app.explorer.ui.ExplorerScreen
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExplorerRealRetrofitTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val server = MockWebServer()
    private val trees = AtomicInteger(0)
    private val languages = AtomicInteger(0)
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val errors = Collections.synchronizedList(mutableListOf<String>())

    private val taxonomies = listOf(
        ExplorerTaxonomy("workshop", "Workshop", "by-workshop", "Workshop › artisan › record.", isDefault = true),
        ExplorerTaxonomy("craft", "Craft", "by-craft", "Craft › artisan."),
    )
    private val folder = ExplorerEntry(name = "Bagru workshop", path = "w/bagru", kind = "folder", recordType = "workshop", count = 1)
    private val photo = ExplorerEntry(name = "Block.jpg", path = "w/bagru/p1", mediaId = "p1", mediaType = "IMAGE", sizeBytes = 2_700_000)

    private fun treeFor(path: String): ExplorerTree = when (path) {
        "" -> ExplorerTree(path = "", taxonomies = taxonomies)
        "by-workshop" -> ExplorerTree(path = path, crumbs = listOf(ExplorerCrumb("Workshop", "by-workshop")), entries = listOf(folder), total = 1, taxonomies = taxonomies, taxonomy = "workshop")
        "w/bagru" -> ExplorerTree(
            path = path,
            crumbs = listOf(ExplorerCrumb("Workshop", "by-workshop"), ExplorerCrumb("Bagru workshop", "w/bagru")),
            entries = listOf(photo),
            total = 1,
            taxonomies = taxonomies,
            taxonomy = "workshop",
        )
        "c/indigo" -> ExplorerTree(
            path = path,
            crumbs = listOf(ExplorerCrumb("Craft", "by-craft"), ExplorerCrumb("Indigo", "c/indigo")),
            entries = listOf(folder),
            total = 1,
            taxonomies = taxonomies,
            taxonomy = "craft",
        )
        else -> ExplorerTree(path = path, taxonomies = taxonomies, taxonomy = "workshop")
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private lateinit var api: ExplorerApi
    private lateinit var more: ExplorerMore

    @Before fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                seen += url.encodedPath
                return when (url.encodedPath) {
                    "/data/tree" -> {
                        trees.incrementAndGet()
                        json(ExplorerJson.encodeToString(ExplorerTree.serializer(), treeFor(url.queryParameter("path").orEmpty())))
                    }
                    "/data/transcripts/languages" -> {
                        languages.incrementAndGet()
                        json("{\"languages\":[]}")
                    }
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
        ExplorerPlace.write(app, ExplorerPortal.userId(app), "")
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
    }

    @After fun stop() {
        ExplorerEnvironment.apiOverride = null
        ExplorerMoreApi.override = null
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
        server.shutdown()
    }

    private fun show(width: Int? = null) {
        compose.setContent {
            MaterialTheme {
                if (width != null) {
                    Box(Modifier.requiredWidth(width.dp)) { ExplorerScreen(onBack = {}, onError = { errors += it }) }
                } else {
                    ExplorerScreen(onBack = {}, onError = { errors += it })
                }
            }
        }
    }

    private fun appears(tag: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun reach(tag: String) {
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("explorer.tree").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun settled(): Int {
        compose.waitForIdle()
        val before = trees.get()
        Thread.sleep(2_000)
        compose.waitForIdle()
        assertEquals("the screen kept asking for folders after it settled: $seen", before, trees.get())
        return before
    }

    @Test fun dataOpensOnFoldersAndSettlesWithARealRetrofitClient() {
        show()
        reach("explorer.folder.w/bagru")
        val landed = settled()
        assertTrue("landing asked for the tree $landed times: $seen", landed <= 3)
        assertTrue("languages were asked ${languages.get()} times", languages.get() <= 1)

        reach("explorer.folder.w/bagru")
        compose.onNodeWithTag("explorer.folder.w/bagru").performClick()
        reach("explorer.file.p1")
        val opened = settled() - landed
        assertTrue("opening one folder asked for the tree $opened times: $seen", opened in 1..2)
        assertTrue(errors.isEmpty())
    }

    @Test fun aTabletListsTheLandingFoldersInTheContentPane() {
        show(width = 900)
        appears("explorer.content.folder.w/bagru")
        val landed = settled()
        assertTrue("landing asked for the tree $landed times: $seen", landed <= 3)
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("explorer.pane.tree").performScrollToNode(hasTestTag("explorer.folder.w/bagru")) }.isSuccess
        }
        compose.onNodeWithTag("explorer.folder.w/bagru").assertExists()
    }

    @Test fun backReturnsToTheFolderViewedBeforeNotTheParent() {
        show()
        reach("explorer.folder.w/bagru")
        settled()
        ExplorerViewLinks.request(ExplorerLinkRequest(viewId = null, path = "c/indigo"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Indigo")).fetchSemanticsNodes().isNotEmpty() }
        reach("explorer.folder.w/bagru")
        compose.onNodeWithTag("explorer.folder.w/bagru").performClick()
        reach("explorer.file.p1")
        Espresso.pressBack()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("Indigo")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("explorer.file.p1")).fetchSemanticsNodes().isEmpty()
        }
        Espresso.pressBack()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Indigo")).fetchSemanticsNodes().isEmpty() }
        reach("explorer.folder.w/bagru")
        assertTrue(errors.isEmpty())
    }

    @Test fun aSavedViewThatIsGoneSaysSoAndTheLinkIsUsedOnce() {
        show()
        reach("explorer.folder.w/bagru")
        settled()
        ExplorerViewLinks.request(ExplorerLinkRequest(viewId = "gone", path = null))
        compose.waitUntil(20_000) { errors.contains(ExplorerUiText.viewMissing) }
        compose.waitForIdle()
        assertNull(ExplorerViewLinks.requests.value)
        assertEquals(1, seen.count { it == "/data/views/gone" })
    }
}
