package com.fieldrepository.app.language

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.RetrofitTranscriptBackend
import com.fieldrepository.app.transcript.data.SpokenLanguageSuggested
import com.fieldrepository.app.transcript.data.SpokenLanguages
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptApi
import com.fieldrepository.app.transcript.data.TranscriptAudio
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptLanguage
import com.fieldrepository.app.transcript.data.TranscriptPermissions
import com.fieldrepository.app.transcript.data.TranscriptSpokenLanguage
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.UploadPolicy
import com.fieldrepository.app.transcript.ui.ManualPlayback
import com.fieldrepository.app.transcript.ui.SpokenLanguageChoice
import com.fieldrepository.app.transcript.ui.SpokenLanguageField
import com.fieldrepository.app.transcript.ui.SpokenLanguagePicker
import com.fieldrepository.app.transcript.ui.TranscribeChoiceItem
import com.fieldrepository.app.transcript.ui.TranscribeChoiceRows
import com.fieldrepository.app.transcript.ui.TranscriptHost
import com.fieldrepository.app.transcript.ui.TranscriptViewer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@RunWith(AndroidJUnit4::class)
class SpokenLanguageUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val view = Transcript(
        mediaId = "cm_media_1",
        title = "Interview · Odia clip",
        status = PipelineStatus.READY,
        version = 1,
        languages = listOf(TranscriptLanguage("hi", "Hindi")),
        spokenLanguage = TranscriptSpokenLanguage(
            chosen = SpokenLanguages.AUTO,
            requested = SpokenLanguages.AUTO,
            detected = "hi",
            detectedLabel = "Hindi",
            probability = 0.42,
            uncertain = true,
            estimateMinutes = 2.5,
            estimateUsd = 0.02,
        ),
        turns = listOf(TranscriptTurn(id = "turn_a", index = 0, speakerName = "Speaker 1", startMs = 0, endMs = 4000, language = "hi", text = "नमस्कार")),
        audio = TranscriptAudio(null, 69_000),
        permissions = TranscriptPermissions(edit = true, retranscribe = true),
        revision = 1,
    )

    private val sent = mutableListOf<Pair<String, String>>()

    private fun fakeBackend(): TranscriptBackend {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                synchronized(sent) { sent += request.url.encodedPath to buffer.readUtf8() }
                val answer = view.copy(spokenLanguage = view.spokenLanguage!!.copy(chosen = "or", retranscribing = true))
                val body = ApiClient.json.encodeToString(Transcript.serializer(), answer)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(202)
                    .message("Accepted")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TranscriptApi::class.java)
        return RetrofitTranscriptBackend(api)
    }

    private class BackendHost(private val backend: TranscriptBackend, private val onView: (Transcript) -> Unit) : TranscriptHost() {
        override suspend fun retranscribe(spokenLanguage: String): String? =
            runCatching { onView(backend.retranscribe("cm_media_1", spokenLanguage)) }.exceptionOrNull()?.let { TranscriptStrings.retranscribeFailed }
    }

    @Test fun thePickerStartsOnAutomaticOffersMixedAndSearchesByName() {
        var value by mutableStateOf(SpokenLanguages.AUTO)
        compose.setContent { MaterialTheme { SpokenLanguagePicker(value = value, onChange = { value = it }) } }
        compose.onNodeWithTag("spokenLanguage.field").assertTextContains(TranscriptStrings.spokenLanguageAuto)
        compose.onNodeWithTag("spokenLanguage.field").performClick()
        compose.onNodeWithTag("spokenLanguage.option.mixed").assertTextContains(TranscriptStrings.spokenLanguageMixed)
        compose.onNodeWithTag("spokenLanguage.search").performTextInput("odi")
        compose.onNodeWithTag("spokenLanguage.option.or").assertIsDisplayed()
        compose.onAllNodesWithTag("spokenLanguage.option.hi").assertCountEquals(0)
        compose.onNodeWithTag("spokenLanguage.search").performTextClearance()
        compose.onNodeWithTag("spokenLanguage.search").performTextInput("zzzz")
        compose.onNodeWithTag("spokenLanguage.empty").assertIsDisplayed()
        compose.onNodeWithTag("spokenLanguage.search").performTextClearance()
        compose.onNodeWithTag("spokenLanguage.search").performTextInput("beng")
        compose.onNodeWithTag("spokenLanguage.option.bn").performClick()
        compose.waitForIdle()
        assertEquals("bn", value)
        compose.onNodeWithTag("spokenLanguage.field").assertTextContains("Bengali")
    }

    @Test fun theArtisansLastLanguageIsShownAsTheDefaultAndStaysEditable() {
        val choice = SpokenLanguageChoice().apply { offer(SpokenLanguageSuggested("or", "Odia", SpokenLanguages.SOURCE_ARTISAN)) }
        compose.setContent { MaterialTheme { SpokenLanguageField(choice) } }
        compose.onNodeWithTag("spokenLanguage.field").assertTextContains("Odia")
        compose.onNodeWithTag("spokenLanguage.hint").assertTextEquals(TranscriptStrings.spokenLanguageFromArtisan)
        compose.onNodeWithTag("spokenLanguage.field").performClick()
        compose.onNodeWithTag("spokenLanguage.option.auto").performClick()
        compose.waitForIdle()
        assertEquals(SpokenLanguages.AUTO, choice.value)
        compose.onAllNodesWithTag("spokenLanguage.hint").assertCountEquals(0)
    }

    @Test fun anUploadToBeTranscribedAsksForTheSpokenLanguage() {
        val chosen = mutableMapOf<String, String>()
        val batch = listOf(TranscribeChoiceItem("u-audio", "Artisan.m4a", "audio/mp4"))
        compose.setContent {
            MaterialTheme {
                TranscribeChoiceRows(
                    batch,
                    policy = UploadPolicy(autoTranscribeAudio = true),
                    spokenLanguageOf = { chosen[it] },
                    onSpokenLanguage = { key, code -> chosen[key] = code },
                )
            }
        }
        compose.onNodeWithTag("upload.spokenLanguage").assertIsDisplayed()
        compose.onNodeWithTag("spokenLanguage.field").performClick()
        compose.onNodeWithTag("spokenLanguage.search").performTextInput("odia")
        compose.onNodeWithTag("spokenLanguage.option.or").performClick()
        compose.waitUntil(5_000) { chosen["u-audio"] == "or" }
    }

    @Test fun wrongLanguageReTranscribesThroughTheApiAndKeepsTheOldVersion() {
        var current by mutableStateOf(view)
        val host = BackendHost(fakeBackend()) { current = it }
        compose.setContent { MaterialTheme { TranscriptViewer(current, ManualPlayback(available = false), host) } }
        compose.onNodeWithTag("transcript.spokenLanguage").assertTextEquals(TranscriptStrings.detectedLanguage("Hindi"))
        compose.onNodeWithTag("transcript.languageUncertain").assertTextEquals(TranscriptStrings.languageUncertain)
        compose.onNodeWithTag("transcript.wrongLanguage").performClick()
        compose.onNodeWithTag("transcript.retranscribeDialog").assertIsDisplayed()
        compose.onNodeWithTag("transcript.retranscribeMinutes").assertTextEquals(TranscriptStrings.retranscribeMinutes("2.5"))
        compose.onNodeWithTag("transcript.retranscribeCost").assertTextEquals(TranscriptStrings.retranscribeCost("0.02"))
        compose.onNodeWithTag("spokenLanguage.field").performClick()
        compose.onNodeWithTag("spokenLanguage.search").performTextInput("odi")
        compose.onNodeWithTag("spokenLanguage.option.or").performClick()
        compose.onNodeWithTag("transcript.retranscribeConfirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("transcript.retranscribing").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("transcript.retranscribeDialog").assertCountEquals(0)
        compose.onAllNodesWithTag("transcript.wrongLanguage").assertCountEquals(0)
        val call = synchronized(sent) { sent.toList() }.single()
        assertEquals("/api/transcripts/cm_media_1/retranscribe", call.first)
        assertTrue(call.second.contains("\"spokenLanguage\":\"or\""))
        assertEquals(1, current.version)
    }
}
