package com.fieldrepository.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.UserDto
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
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.ui.markWalkthroughSeen
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DataMessageActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val server = MockWebServer()
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private var scenario: ActivityScenario<MainActivity>? = null

    private val user = UserDto(
        id = "u-data-banner",
        email = "data.banner@example.org",
        name = "Data Banner",
        role = "RESEARCHER",
        canDownloadDataset = true,
    )
    private val taxonomies = listOf(ExplorerTaxonomy("workshop", "Workshop", "by-workshop", "Workshop › artisan › record.", isDefault = true))
    private val folder = ExplorerEntry(name = "Bagru workshop", path = "w/bagru", kind = "folder", recordType = "workshop", count = 1)

    private fun tree(path: String): ExplorerTree = when (path) {
        "" -> ExplorerTree(path = "", taxonomies = taxonomies)
        else -> ExplorerTree(
            path = path,
            crumbs = listOf(ExplorerCrumb("Workshop", "by-workshop")),
            entries = listOf(folder),
            total = 1,
            taxonomies = taxonomies,
            taxonomy = "workshop",
        )
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val path = url.encodedPath
                seen += path
                return when {
                    path == "/api/me" -> json(ApiClient.json.encodeToString(UserDto.serializer(), user))
                    path.startsWith("/api/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    path == "/data/tree" -> json(ExplorerJson.encodeToString(ExplorerTree.serializer(), tree(url.queryParameter("path").orEmpty())))
                    path == "/data/views/unreachable" -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    else -> MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{\"detail\":\"Not found\"}")
                }
            }
        }
        server.start()
        val explorer = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build())
            .addConverterFactory(ExplorerJson.asConverterFactory("application/json".toMediaType()))
            .build()
        ExplorerEnvironment.apiOverride = explorer.create(ExplorerApi::class.java)
        ExplorerMoreApi.override = explorer.create(ExplorerMore::class.java)
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
        val tokens = TokenStore(app)
        tokens.setToken("device-test-token")
        tokens.setUser(user)
        ExplorerPlace.write(app, ExplorerPortal.userId(app), "")
        markWalkthroughSeen(app)
        val base = server.url("/api/")
        MainRepositorySeam.factory = { context ->
            val store = TokenStore(context)
            val api = ApiClient.retrofit(store).newBuilder().baseUrl(base).build().create(FieldRepositoryApi::class.java)
            FieldRepository(api, store)
        }
    }

    @After fun stop() {
        scenario?.close()
        MainRepositorySeam.factory = null
        ExplorerEnvironment.apiOverride = null
        ExplorerMoreApi.override = null
        ExplorerViewLinks.requests.value?.let { ExplorerViewLinks.consume(it) }
        TokenStore(app).clear()
        server.shutdown()
    }

    private fun openLink(viewId: String) {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("${ExplorerPortal.WEB_BASE}/data?viewId=$viewId"))
            .setClass(app, MainActivity::class.java)
        scenario = ActivityScenario.launch(link)
    }

    private fun banner(tone: String, text: String): SemanticsMatcher = hasTestTag(tone) and hasAnyDescendant(hasText(text))

    private fun showsOnData(tone: String, text: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("explorer.tree")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(banner(tone, text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(banner(tone, text), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTag("app-message-success"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aSavedViewThatIsGoneShowsItsErrorBannerOnTheDataScreen() {
        openLink("bogus")
        showsOnData("app-message-error", ExplorerUiText.viewMissing)
        assertTrue(seen.contains("/data/views/bogus"))
    }

    @Test fun aSavedViewTheServerCannotBeReachedForSaysSoInsteadOfMissing() {
        openLink("unreachable")
        showsOnData("app-message-error", ExplorerUiText.viewOffline)
        assertTrue(compose.onAllNodes(hasText(ExplorerUiText.viewMissing), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
