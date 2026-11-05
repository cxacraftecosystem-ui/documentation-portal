package com.fieldrepository.app.forms

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.ui.AppSearchPanel
import com.fieldrepository.app.ui.DataRecordSearch
import com.fieldrepository.app.ui.DataUnifiedSearch
import com.fieldrepository.app.ui.appSearchAllLine
import java.util.Collections
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataSearchReachUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val artisans = 31

    private val products = 3

    private var unifiedOff = false

    private fun unifiedItem(type: String, id: String, title: String) =
        """{"portal":"designer","local":true,"type":"$type","id":"$id","title":"$title","subtitle":null,"snippet":"","highlights":[],"place":null,"craftCodes":[],"recordDate":null,"href":"/$type/$id","openUrl":null,"score":0.016393}"""

    private fun unifiedGroup(type: String, label: String, total: Int, prefix: String, title: String, offset: Int, limit: Int): String? {
        if (total == 0) return null
        val items = (offset until minOf(total, offset + limit)).joinToString(",") { unifiedItem(type, "$prefix${it + 1}", "$title ${it + 1}") }
        return """{"type":"$type","label":"$label","total":$total,"localTotal":$total,"remoteTotal":0,"items":[$items]}"""
    }

    private fun unified(q: String, type: String?, page: Int, limit: Int): String {
        val offset = (page - 1) * limit
        val groups = listOfNotNull(
            unifiedGroup("artisans", "Artisans", artisans, "a", "Artisan", offset, limit).takeIf { type == null || type == "artisans" },
            unifiedGroup("products", "Products", products, "p", "Product", offset, limit).takeIf { type == null || type == "products" },
        ).joinToString(",")
        val typed = if (type != null) ""","type":"$type"""" else ""
        return """{"query":"$q","suggestion":null,"mode":"text","groups":[$groups],"remote":{"portal":"fieldRepository","label":"Field Repository","status":"off"},"typesRefused":[],"page":$page,"limit":$limit$typed}"""
    }

    private fun records(types: List<String>?, page: Int, pageSize: Int): String {
        val offset = (page - 1) * pageSize
        val wanted = types == null || "artisans" in types
        val rows = if (!wanted) "" else (offset until minOf(artisans, offset + pageSize)).joinToString(",") {
            """{"id":"a${it + 1}","name":"Artisan ${it + 1}","place":"Kutch","status":"APPROVED","createdAt":"2026-10-01T10:00:00Z"}"""
        }
        val total = if (wanted) artisans else 0
        return """{"query":null,"page":$page,"pageSize":$pageSize,"types":["artisans"],"artisans":[$rows],"workshops":[],"products":[],"tools":[],"media":[],"totals":{"artisans":$total,"workshops":0,"products":0,"tools":0,"media":0},"total":$total,"pageCount":${maxOf(1, (total + pageSize - 1) / pageSize)}}"""
    }

    private fun repository(): FieldRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TokenStore(context)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            asked += url.encodedPath + "?" + (url.encodedQuery ?: "")
            val path = url.encodedPath.trimEnd('/')
            val (code, body) = when {
                path.endsWith("/search/unified") && unifiedOff -> 404 to """{"detail":"Not Found"}"""
                path.endsWith("/search/unified") -> 200 to unified(
                    url.queryParameter("q").orEmpty(),
                    url.queryParameter("type"),
                    url.queryParameter("page")?.toIntOrNull() ?: 1,
                    url.queryParameter("limit")?.toIntOrNull() ?: 5,
                )
                path.endsWith("/search") -> 200 to records(
                    url.queryParameter("types")?.split(',')?.filter { it.isNotBlank() },
                    url.queryParameter("page")?.toIntOrNull() ?: 1,
                    url.queryParameter("pageSize")?.toIntOrNull() ?: 10,
                )
                else -> 404 to """{"detail":"Not Found"}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = ApiClient.retrofit(store).newBuilder().client(client).build().create(FieldRepositoryApi::class.java)
        return FieldRepository(api, store)
    }

    private fun waitFor(tag: String, text: String? = null, root: SemanticsNodeInteractionsProvider = compose) {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag).and(hasText(text))
        compose.waitUntil(30_000) { root.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun gone(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun showUnified(opened: MutableList<String> = mutableListOf()) {
        val repo = repository()
        compose.setContent {
            MaterialTheme {
                DataUnifiedSearch(
                    repository = repo,
                    initial = "indigo",
                    onOpenInData = { type, id, _ -> opened += "$type:$id" },
                    onPlay = { _, _, _, _ -> },
                    onOpenRemote = null,
                    onClose = {},
                )
            }
        }
    }

    private fun showQuick(opened: MutableList<String>) {
        val repo = repository()
        compose.setContent {
            MaterialTheme {
                DataRecordSearch(repo) { type, id, _ -> opened += "$type:$id" }
            }
        }
    }

    private fun tapUntilListed(chip: String) {
        repeat(3) {
            compose.onNodeWithText(chip).performSemanticsAction(SemanticsActions.OnClick)
            val listed = runCatching {
                compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("explorer.search.summary"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (listed) return
            compose.onRoot(useUnmergedTree = true).printToLog("quick-search")
        }
        waitFor("explorer.search.summary")
    }

    private fun reach(list: String, more: String, line: String, done: String) {
        compose.waitUntil(60_000) {
            if (compose.onAllNodes(hasTestTag(line).and(hasText(done)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                true
            } else {
                runCatching { compose.onNodeWithTag(list, useUnmergedTree = true).performScrollToNode(hasTestTag(more)) }
                false
            }
        }
    }

    private fun scrollResultsTo(list: String, tag: String) {
        compose.onNodeWithTag(list, useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun theAllViewShowsResultsFromEveryGroup() {
        showUnified()
        waitFor("data.search.shown", "8 of 34 shown")
        waitFor("data.search.item.artisans.a1")
        compose.onNodeWithTag("data.search.group.artisans", useUnmergedTree = true).assertExists()
        scrollResultsTo("data.search.results", "data.search.item.products.p3")
        compose.onNodeWithTag("data.search.item.products.p3", useUnmergedTree = true).assertExists()
        assertTrue(asked.any { it.contains("search/unified") && !it.contains("type=") })
    }

    private fun requested(fragment: String) {
        compose.waitUntil(30_000) { asked.any { it.contains("search/unified") && fragment.split('&').all { part -> it.contains(part) } } }
    }

    @Test
    fun showAllArtisansReachesTheThirtyFirst() {
        val opened = mutableListOf<String>()
        showUnified(opened)
        waitFor("data.search.shown", "8 of 34 shown")
        scrollResultsTo("data.search.results", "data.search.all.artisans")
        compose.onNodeWithText("Show all 31 artisans").assertExists()
        compose.onNodeWithTag("data.search.all.artisans", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        requested("type=artisans&page=1")
        reach("data.search.results", "data.search.more", "data.search.shown", "All 31 shown")
        assertTrue(gone("data.search.item.products.p1"))
        scrollResultsTo("data.search.results", "data.search.item.artisans.a31")
        compose.onNodeWithTag("data.search.item.artisans.a31", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("artisan:a31"), opened)
        assertTrue(gone("data.search.more"))
    }

    @Test
    fun loadMoreInTheAllViewPagesEveryGroupToItsEnd() {
        showUnified()
        waitFor("data.search.shown", "8 of 34 shown")
        reach("data.search.results", "data.search.more", "data.search.shown", "All 34 shown")
        waitFor("data.search.shown", "All 34 shown")
        scrollResultsTo("data.search.results", "data.search.item.artisans.a31")
        compose.onNodeWithTag("data.search.item.artisans.a31", useUnmergedTree = true).assertExists()
        assertTrue(gone("data.search.more"))
        assertTrue(asked.any { it.contains("search/unified") && it.contains("page=7") && !it.contains("type=") })
    }

    @Test
    fun everythingWithNothingTypedLoadsAndShowAllReachesEveryHit() {
        val opened = mutableListOf<String>()
        showQuick(opened)
        compose.waitForIdle()
        assertTrue(gone("explorer.search.summary"))
        tapUntilListed("Everything")
        compose.onNodeWithText("Showing 8 of 31").assertExists()
        compose.onNodeWithTag("explorer.search.all", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("search.all.shown")
        reach("search.all.list", "search.all.more.artisan", "search.all.shown", "All 31 shown")
        scrollResultsTo("search.all.list", "search.all.row.artisan.a31")
        compose.onNode(hasClickAction().and(hasAnyAncestor(hasTestTag("search.all.row.artisan.a31"))), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("artisan:a31"), opened)
        assertTrue(gone("search.all.more.artisan"))
    }

    @Test
    fun aFilterOnlyQuickSearchReachesEveryHit() {
        val opened = mutableListOf<String>()
        showQuick(opened)
        tapUntilListed("Artisans")
        compose.onNodeWithText("Show all 31").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("search.all.shown")
        reach("search.all.list", "search.all.more.artisan", "search.all.shown", "All 31 shown")
        scrollResultsTo("search.all.list", "search.all.row.artisan.a31")
        compose.onNodeWithTag("search.all.row.artisan.a31", useUnmergedTree = true).assertExists()
        assertTrue(asked.any { it.contains("/search?") && it.contains("types=artisans") && !it.contains("q=") })
    }

    @Test
    fun theLegacyFallbackPagesToTheLastRecordToo() {
        unifiedOff = true
        showUnified()
        waitFor("search.all.shown")
        reach("search.all.list", "search.all.more.artisan", "search.all.shown", "All 31 shown")
        scrollResultsTo("search.all.list", "search.all.row.artisan.a31")
        compose.onNodeWithTag("search.all.row.artisan.a31", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theTopSearchLeadsToEveryRecord() {
        val repo = repository()
        compose.setContent {
            MaterialTheme {
                AppSearchPanel(
                    appName = "the app",
                    logo = {},
                    localItems = emptyList(),
                    remoteSearch = { emptyList() },
                    onClose = {},
                    fullSearch = { q, back ->
                        DataUnifiedSearch(repository = repo, initial = q, onOpenInData = { _, _, _ -> }, onPlay = { _, _, _, _ -> }, onOpenRemote = null, onClose = back)
                    },
                )
            }
        }
        compose.onNodeWithContentDescription("Search the app").performTextInput("indigo")
        waitFor("app.search.all")
        compose.onNodeWithText(appSearchAllLine("indigo")).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("data.search.shown", "8 of 34 shown")
        scrollResultsTo("data.search.results", "data.search.all.artisans")
        compose.onNodeWithTag("data.search.all.artisans", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        requested("type=artisans&page=1")
        reach("data.search.results", "data.search.more", "data.search.shown", "All 31 shown")
    }
}
