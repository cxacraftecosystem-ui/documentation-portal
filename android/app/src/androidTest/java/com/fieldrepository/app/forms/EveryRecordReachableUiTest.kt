package com.fieldrepository.app.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.MY_RECORDS_PAGE_BUDGET
import com.fieldrepository.app.data.PageResponse
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.myRecordsWalk
import com.fieldrepository.app.ui.AccessRosterScreen
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.MyActivityOlder
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(AndroidJUnit4::class)
class EveryRecordReachableUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun rosterRepository(pending: Int): FieldRepository {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url
                val waiting = url.queryParameter("status") == "PENDING"
                val total = if (waiting) pending else 0
                val page = url.queryParameter("page")?.toInt() ?: 1
                val size = url.queryParameter("pageSize")?.toInt() ?: 50
                val from = (page - 1) * size + 1
                val to = minOf(total, page * size)
                val items = if (from > to) "" else (from..to).joinToString(",") {
                    """{"id":"q$it","email":"waiting$it@example.org","status":"PENDING","requestCount":1}"""
                }
                val pages = (total + size - 1) / size
                val body = """{"items":[$items],"total":$total,"page":$page,"pageSize":$size,"pages":$pages}"""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
        return FieldRepository(api, TokenStore(context))
    }

    @Test
    fun theAccessQueueReachesTheLastWaitingRequest() {
        val repository = rosterRepository(pending = 62)
        val admin = UserDto(id = "admin", email = "admin@example.org", name = "Admin", role = "MASTER_ADMIN")
        compose.setContent {
            FieldRepositoryTheme(darkTheme = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccessRosterScreen(repository = repository, user = admin)
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Showing 25 of 62.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("access-queue-more").performScrollTo().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Showing 50 of 62.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show 12 more").performScrollTo().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("waiting62@example.org").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("waiting62@example.org").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("access-queue-more").assertDoesNotExist()
    }

    @Test
    fun myActivityLoadsOlderRecordsUntilTheLastOne() {
        val total = MY_RECORDS_PAGE_BUDGET * 100 + 40
        val fetch: suspend (Int) -> PageResponse<String> = { page ->
            val from = (page - 1) * 100 + 1
            val to = minOf(total, page * 100)
            PageResponse(if (from > to) emptyList() else (from..to).map { "Record $it" }, total = total, page = page, pageSize = 100, pages = (total + 99) / 100)
        }
        compose.setContent {
            FieldRepositoryTheme(darkTheme = false) {
                val scope = rememberCoroutineScope()
                var rows by remember { mutableStateOf<List<String>>(emptyList()) }
                var next by remember { mutableStateOf<Int?>(1) }
                var loading by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val first = myRecordsWalk(1, fetch)
                    rows = first.items
                    next = first.nextPage
                }
                LazyColumn(Modifier.testTag("activity-list")) {
                    items(rows, key = { it }) { MaterialText(it) }
                    val from = next
                    if (from != null && rows.isNotEmpty()) {
                        item("older") {
                            MyActivityOlder(shown = rows.size, loading = loading, failed = false) {
                                scope.launch {
                                    loading = true
                                    val more = myRecordsWalk(from, fetch)
                                    rows = rows + more.items
                                    next = more.nextPage
                                    loading = false
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Newest ${MY_RECORDS_PAGE_BUDGET * 100} shown.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("activity-list").performScrollToNode(hasTestTag("my-activity-more"))
        compose.onNodeWithTag("my-activity-more").performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Record $total").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Record $total"))
        compose.onNodeWithText("Record $total").assertIsDisplayed()
        compose.onNodeWithTag("my-activity-more").assertDoesNotExist()
    }
}
