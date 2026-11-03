package com.fieldrepository.app.transcript

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.repoRoot
import com.fieldrepository.app.transcript.data.AudioIsolationEnvelope
import com.fieldrepository.app.transcript.data.BackfillView
import com.fieldrepository.app.transcript.data.TranscribeChoices
import com.fieldrepository.app.transcript.data.TranscriptAudio
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptionSwitchBody
import com.fieldrepository.app.transcript.data.UploadPolicy
import com.fieldrepository.app.transcript.data.UploadTranscribe
import com.fieldrepository.app.transcript.data.switches
import com.fieldrepository.app.transcript.ui.backfillEstimateText
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadTranscribeChoiceTest {
    private val pkg = listOf("com/designprototype/workshop", "com/fieldrepository/app").first {
        File(repoRoot(), "android/app/src/main/java/$it").isDirectory
    }

    @After fun forgetChoices() = TranscribeChoices.clear()

    @QFeature("F2")
    @Test fun `every audio and video file starts unticked and only a ticked one asks for transcription`() {
        assertEquals(emptyList<String>(), TranscribeChoices.processingFor("content://a/1", "video/mp4"))
        assertEquals(emptyList<String>(), TranscribeChoices.processingFor("content://a/2", "AUDIO"))
        TranscribeChoices.set("content://a/1", true)
        assertEquals(listOf("TRANSCRIPTION"), TranscribeChoices.processingFor("content://a/1", "video/mp4"))
        assertEquals(emptyList<String>(), TranscribeChoices.processingFor("content://a/2", "AUDIO"))
        TranscribeChoices.set("content://a/3", true)
        assertEquals(emptyList<String>(), TranscribeChoices.processingFor("content://a/3", "image/jpeg"))
        assertFalse(UploadTranscribe.eligible("application/pdf"))
        assertTrue(UploadTranscribe.eligible("VIDEO") && UploadTranscribe.eligible("audio/mp4"))
    }

    @QFeature("F3")
    @Test fun `transcribe all ticks every file and each box can still be changed after it`() {
        val keys = listOf("u1", "u2", "u3")
        TranscribeChoices.setAll(keys, true)
        assertTrue(keys.all(TranscribeChoices::isOn))
        TranscribeChoices.set("u2", false)
        assertEquals(listOf(true, false, true), keys.map(TranscribeChoices::isOn))
        TranscribeChoices.setAll(keys, false)
        assertTrue(keys.none(TranscribeChoices::isOn))
    }

    @QFeature("F4")
    @Test fun `an admin auto switch locks the box on and says so`() {
        val videos = UploadPolicy(autoTranscribeVideo = true)
        assertTrue(UploadTranscribe.locked("video/mp4", videos))
        assertFalse(UploadTranscribe.locked("audio/mp4", videos))
        assertEquals(listOf("TRANSCRIPTION"), UploadTranscribe.processing("video/mp4", chosen = false, policy = videos))
        assertEquals(TranscriptStrings.uploadAutoVideo, UploadTranscribe.note(listOf("video/mp4", "audio/mp4"), videos))
        assertEquals(TranscriptStrings.uploadAutoBoth, UploadTranscribe.note(listOf("video/mp4", "audio/mp4"), UploadPolicy(true, true)))
        assertNull(UploadTranscribe.note(listOf("audio/mp4"), videos))
        val envelope = json.decodeFromString(
            AudioIsolationEnvelope.serializer(),
            """{"autoTranscribe":{"video":true,"audio":false},"backfill":{"enabled":false,"queued":0,"done":0,"waiting":0}}""",
        )
        assertTrue(envelope.switches().autoTranscribe.video)
        assertFalse(envelope.switches().autoTranscribe.audio)
    }

    @QFeature("F5")
    @Test fun `the backfill ships off and is only switched on with the estimate confirmed`() {
        assertFalse(AudioIsolationEnvelope().switches().backfill.enabled)
        val on = json.parseToJsonElement(
            json.encodeToString(TranscriptionSwitchBody.serializer(), TranscriptionSwitchBody(transcriptBackfill = true, confirmBackfill = true))
        ).jsonObject
        assertEquals("true", on["confirmBackfill"].toString())
        assertEquals("true", on["transcriptBackfill"].toString())
        assertFalse("autoTranscribeVideo" in on)
        val view = json.decodeFromString(
            BackfillView.serializer(),
            """{"estimate":{"recordings":12,"hours":3.25,"costUsd":0.81},"progress":{"enabled":false}}""",
        )
        assertEquals("12 files, about 3.3 h, about \$0.81", backfillEstimateText(view.estimate!!.recordings, view.estimate!!.hours, view.estimate!!.costUsd))
    }

    @QFeature("F6")
    @Test fun `a queued file keeps its choice and an older queued file keeps the old rule`() {
        assertEquals(emptyList<String>(), UploadTranscribe.processingFor(false, "AUDIO"))
        assertEquals(listOf("TRANSCRIPTION"), UploadTranscribe.processingFor(true, "VIDEO"))
        assertEquals(listOf("TRANSCRIPTION"), UploadTranscribe.processingFor(null, "AUDIO"))
        assertEquals(emptyList<String>(), UploadTranscribe.processingFor(null, "VIDEO"))
        val offline = File(repoRoot(), "android/app/src/main/java/$pkg/data/Offline.kt").readText()
        assertTrue(offline.contains("processing ?: TranscribeChoices.processingFor(uri.toString(), inferMediaType(mimeType))"))
    }

    @QFeature("F1")
    @Test fun `a video recording says so in the transcript view`() {
        assertTrue(json.decodeFromString(TranscriptAudio.serializer(), """{"url":"https://x/v.mp4","video":true}""").video)
        assertFalse(json.decodeFromString(TranscriptAudio.serializer(), """{"url":"https://x/a.m4a"}""").video)
        val viewer = File(repoRoot(), "android/app/src/main/java/$pkg/transcript/ui/TranscriptViewer.kt").readText()
        assertTrue(viewer.contains("if (view.audio.video && playback.available)"))
    }

    @QFeature("F7")
    @Test fun `a questionnaire take never shows the box and is always queued`() {
        val tree = File(repoRoot(), "android/app/src/main/java/$pkg/questionnaire")
        val offenders = tree.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("TranscribeChoiceRows") || it.readText().contains("TranscribeChoices") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
