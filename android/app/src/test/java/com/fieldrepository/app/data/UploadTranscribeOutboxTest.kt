package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.transcript.data.TranscribeChoices
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadTranscribeOutboxTest {
    @After fun forgetChoices() = TranscribeChoices.clear()

    private val json = Json { ignoreUnknownKeys = true }

    private fun source(name: String) = File("src/main/java/com/fieldrepository/app/$name").readText()

    @QFeature("F6")
    @Test fun `the choice survives the outbox round trip`() {
        val original = PendingMedia(
            localPath = "/data/user/0/app/files/outbox/abc",
            originalFilename = "clip.mp4",
            mimeType = "video/mp4",
            mediaType = "VIDEO",
            processing = listOf("TRANSCRIPTION"),
        )
        val back = json.decodeFromString(PendingMedia.serializer(), json.encodeToString(PendingMedia.serializer(), original))
        assertEquals(listOf("TRANSCRIPTION"), back.processing)
    }

    @QFeature("F6")
    @Test fun `a file is queued with the box it had and an older queued file keeps the old rule`() {
        assertTrue(source("data/Offline.kt").contains("processing ?: TranscribeChoices.processingFor(uri.toString(), inferMediaType(mimeType))"))
        assertTrue(source("data/FieldRepository.kt").contains("pm.processing ?: UploadTranscribe.legacy(pm.mediaType)"))
    }

    @QFeature("F2")
    @Test fun `no upload path assumes audio is to be transcribed any more`() {
        val repository = source("data/FieldRepository.kt")
        assertEquals(0, Regex("""if \((staged\.)?mediaType == "AUDIO"\) listOf\("TRANSCRIPTION"\)""").findAll(repository).count())
        assertEquals(listOf("TRANSCRIPTION"), TranscribeChoices.also { it.set("content://x/1", true) }.processingFor("content://x/1", "video/mp4"))
    }

    @QFeature("F7")
    @Test fun `an interview's question recordings are always queued with no box`() {
        val main = source("MainActivity.kt")
        assertTrue(main.contains("processing = listOf(UploadTranscribe.TRANSCRIPTION)))"))
        assertEquals(2, Regex("""processingRequests = listOf\(UploadTranscribe\.TRANSCRIPTION\),""").findAll(main).count())
    }
}
