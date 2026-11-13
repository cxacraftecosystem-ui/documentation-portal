package com.fieldrepository.app.legacyrecording

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fieldrepository.app.AudioClipRecorder
import com.fieldrepository.app.MediaCaptureState
import com.fieldrepository.app.MediaStagingEffect
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.DraftStore
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.StagedObjects
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.recording.MicLock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LegacyClipsDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val server = MockWebServer()
    private val presigned = AtomicInteger()
    private val deletes = Collections.synchronizedList(mutableListOf<String>())
    private val completed = Collections.synchronizedList(mutableListOf<String>())
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var repository: FieldRepository

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun serve() {
        StagedObjects.reset()
        LegacyTake.reset()
        MicLock.reset()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val path = url.encodedPath
                return when {
                    request.method == "POST" && path == "/api/media/presign" -> {
                        val n = presigned.incrementAndGet()
                        json("""{"uploadUrl":"${server.url("/put/$n")}","objectKey":"media/u-device/clip-$n/staged-${System.currentTimeMillis()}-$n.m4a","bucket":"b","headers":{}}""")
                    }
                    request.method == "PUT" && path.startsWith("/put/") -> MockResponse().setResponseCode(200)
                    request.method == "DELETE" && path == "/api/media/object" -> {
                        deletes += url.queryParameter("objectKey").orEmpty()
                        MockResponse().setResponseCode(204)
                    }
                    request.method == "POST" && path == "/api/media/complete" -> {
                        val key = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["objectKey"]?.jsonPrimitive?.content.orEmpty()
                        completed += key
                        json("""{"id":"m-${completed.size}","originalFilename":"clip.m4a","mediaType":"AUDIO"}""")
                    }
                    else -> MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{\"detail\":\"Not found\"}")
                }
            }
        }
        server.start()
        val store = TokenStore(app)
        store.setToken("device-test-token")
        val api = ApiClient.retrofit(store).newBuilder().baseUrl(server.url("/api/")).build().create(FieldRepositoryApi::class.java)
        repository = FieldRepository(api, store)
    }

    @After fun stop() {
        runCatching { LegacyTake.finaliseForLater() }
        LegacyTake.reset()
        MicLock.reset()
        StagedObjects.reset()
        TokenStore(app).clear()
        server.shutdown()
    }

    private fun waitUntil(ms: Long = 20_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(what, condition())
    }

    private class Screen {
        var shown by mutableStateOf(true)
        var draft: List<Uri> = emptyList()
        var media: MediaCaptureState? = null
    }

    private fun showForm(screen: Screen) {
        compose.setContent {
            MaterialTheme {
                if (screen.shown) {
                    val media = remember { MediaCaptureState().also { it.uris = screen.draft } }
                    screen.media = media
                    LaunchedEffect(media.uris) { screen.draft = media.uris }
                    MediaStagingEffect(repository = repository, media = media)
                    Column {
                        AudioClipRecorder(
                            clips = media.uris,
                            onAddClip = { media.uris = media.uris + it },
                            onRemoveLast = { media.uris = media.uris.dropLast(1) },
                            onError = { errors += it }
                        )
                    }
                }
            }
        }
    }

    private fun recordTake(start: String) {
        compose.onNodeWithText(start).performClick()
        waitUntil(what = "the take started") { LegacyTake.active }
        Thread.sleep(1_500)
        compose.onNodeWithText("Stop").performClick()
        waitUntil(what = "the take stopped") { !LegacyTake.active }
    }

    private fun staged(screen: Screen, count: Int) {
        waitUntil(what = "$count clips are uploaded") { screen.media?.let { m -> m.uris.size == count && m.uris.all { it in m.staged } } == true }
    }

    private fun leaveAndReturn(screen: Screen) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { screen.shown = false }
        compose.waitForIdle()
        Thread.sleep(2_000)
        compose.runOnUiThread { screen.shown = true }
        compose.waitForIdle()
    }

    private fun save(screen: Screen, interviewId: String) = runBlocking {
        val media = screen.media!!
        media.uris.forEachIndexed { index, uri ->
            val staged = media.stagedDeferred[uri]?.await() ?: media.staged[uri]
            assertTrue("clip ${index + 1} has its upload", staged != null)
            repository.completeStaged(
                staged = staged!!, linkedRecordType = "questionnaire", linkedRecordId = interviewId,
                recordName = "Interview", caption = "Question audio: A1", location = null,
                batchIndex = index + 1, processingRequests = listOf("TRANSCRIPTION")
            )
        }
    }

    @Test fun twoTakesSurviveBackgroundAndLeavingAndBothUploadWithNoDelete() {
        val screen = Screen()
        showForm(screen)
        recordTake("Record ●")
        recordTake("Record another")
        staged(screen, 2)
        val keys = screen.media!!.uris.map { screen.media!!.staged.getValue(it).objectKey }
        leaveAndReturn(screen)
        staged(screen, 2)
        Thread.sleep(1_000)
        assertEquals("no staged object was deleted", emptyList<String>(), deletes.toList())
        save(screen, "int-new")
        assertEquals(keys.toSet(), completed.toSet())
        assertEquals("each take uploaded once", 2, presigned.get())
        assertEquals(emptyList<String>(), deletes.toList())
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test fun reopenedSessionUpdateRecordsAndAddsAFileThenSaves() {
        val screen = Screen()
        showForm(screen)
        recordTake("Record ●")
        val picked = File(DraftStore.capturesDir(app), "picked-${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val pickedUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", picked)
        compose.runOnUiThread { screen.media!!.uris = screen.media!!.uris + pickedUri }
        staged(screen, 2)
        leaveAndReturn(screen)
        staged(screen, 2)
        save(screen, "int-saved")
        assertEquals(2, completed.size)
        assertEquals(2, presigned.get())
        assertEquals(emptyList<String>(), deletes.toList())
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test fun twoConsecutiveTakesStartWithoutFailure() {
        val screen = Screen()
        showForm(screen)
        recordTake("Record ●")
        recordTake("Record another")
        compose.onNodeWithText("Re-record").performClick()
        waitUntil(what = "the re-record started") { LegacyTake.active }
        Thread.sleep(1_500)
        compose.onNodeWithText("Stop").performClick()
        waitUntil(what = "the re-record stopped") { !LegacyTake.active }
        assertEquals(emptyList<String>(), errors.toList())
        assertEquals(2, screen.media!!.uris.size)
        assertTrue(!MicLock.held)
    }

    @Test fun startingASecondRecorderFinalisesTheFirstInsteadOfFailing() {
        val first = mutableStateOf<List<Uri>>(emptyList())
        val second = mutableStateOf<List<Uri>>(emptyList())
        compose.setContent {
            MaterialTheme {
                Column {
                    AudioClipRecorder(clips = first.value, onAddClip = { first.value = first.value + it }, onRemoveLast = {}, onError = { errors += it }, idleLabel = "Record A")
                    AudioClipRecorder(clips = second.value, onAddClip = { second.value = second.value + it }, onRemoveLast = {}, onError = { errors += it }, idleLabel = "Record B")
                }
            }
        }
        compose.onNodeWithText("Record A").performClick()
        waitUntil(what = "A started") { LegacyTake.active }
        Thread.sleep(1_500)
        compose.onNodeWithText("Record B").performClick()
        waitUntil(what = "A's take was kept") { first.value.size == 1 }
        assertTrue("B holds the microphone", LegacyTake.active)
        Thread.sleep(1_500)
        compose.onNodeWithText("Stop").performClick()
        waitUntil(what = "B's take was kept") { second.value.size == 1 }
        assertEquals(emptyList<String>(), errors.toList())
        assertTrue(!MicLock.held)
    }
}
