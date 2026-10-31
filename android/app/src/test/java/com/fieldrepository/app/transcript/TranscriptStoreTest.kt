package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.example
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DownloadedFile
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMapping
import com.fieldrepository.app.transcript.data.SpeakerMappingBody
import com.fieldrepository.app.transcript.data.SpeakerRole
import com.fieldrepository.app.transcript.data.SpeakerSuggestion
import com.fieldrepository.app.transcript.data.TextSource
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptEdit
import com.fieldrepository.app.transcript.data.TranscriptRepository
import com.fieldrepository.app.transcript.data.TranscriptStore
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUnavailable
import com.fieldrepository.app.transcript.data.TranslationStatus
import com.fieldrepository.app.transcript.data.TurnEditBody
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.nio.file.Files

/** A server in memory: answers like the contract, or fails as told. Nothing leaves the JVM. */
class FakeTranscriptServer(var view: Transcript = example) : TranscriptBackend {
    var offline = false
    var refuseWith: Int? = null
    var refusalBody = """{"detail":{"code":"STALE_REVISION","message":"Changed on another device."}}"""
    val edits = mutableListOf<Pair<String, TurnEditBody>>()
    val speakerCalls = mutableListOf<SpeakerMappingBody>()

    private fun gate() {
        if (offline) throw IOException("offline")
        refuseWith?.let { throw HttpException(Response.error<Any>(it, refusalBody.toResponseBody())) }
    }

    override suspend fun get(mediaId: String): Transcript { gate(); return view }
    override suspend fun batch(ids: List<String>) = ids.map { TranscriptSummary(it, view.status) }
    override suspend fun mapSpeakers(mediaId: String, body: SpeakerMappingBody): Transcript {
        gate(); speakerCalls += body
        view = TranscriptRepository.applyLocally(view, TranscriptEdit(mediaId = mediaId, type = TranscriptEdit.SPEAKERS, mappings = body.mappings)).copy(revision = view.revision + 1)
        return view
    }
    override suspend fun speakerSuggestions(mediaId: String) = emptyList<SpeakerSuggestion>()
    override suspend fun editTurn(mediaId: String, turnId: String, body: TurnEditBody): TranscriptTurn {
        gate(); edits += turnId to body
        view = TranscriptRepository.applyLocally(view, TranscriptEdit(mediaId = mediaId, type = TranscriptEdit.TURN, turnId = turnId, text = body.text, english = body.english))
        return view.turns.first { it.id == turnId }.let { it.copy(revision = it.revision + 1) }
    }
    override suspend fun turnHistory(mediaId: String, turnId: String) = emptyList<TurnHistoryEntry>()
    override suspend fun retranslate(mediaId: String, turnId: String): TranscriptTurn {
        gate()
        return view.turns.first { it.id == turnId }.let { it.copy(translation = it.translation.copy(text = "Fresh English.", status = TranslationStatus.TRANSLATED)) }
    }
    override suspend fun retry(mediaId: String): Transcript { gate(); return view }
    override suspend fun transcribeNow(mediaId: String): Transcript { gate(); return view }
    override suspend fun download(mediaId: String, format: String, track: CopyTrack) = DownloadedFile(null, ByteArray(0))
    override suspend fun ask(mediaId: String, question: String) = AskAnswer()
    override suspend fun autodraft(mediaId: String) = AutoDraftResult()
    override suspend fun quoteCard(mediaId: String, turnIds: List<String>) = QuoteCard()
    override suspend fun glossary(craftId: String?, q: String?) = emptyList<GlossaryTerm>()
    override suspend fun budget() = BudgetMeter()
}

class TranscriptStoreTest {
    private val root = Files.createTempDirectory("transcripts").toFile()
    private val server = FakeTranscriptServer()
    private fun repo(user: String = "u_1") = TranscriptRepository(server, TranscriptStore(root, user, json), json)

    @Test fun `a ready transcript opens with no connection, for the same account only`() = runBlocking {
        val r = repo()
        assertFalse(r.load("cm_media_1").offline)
        server.offline = true
        val offline = r.load("cm_media_1")
        assertTrue(offline.offline)
        assertEquals(example.turns, offline.view.turns)
        try {
            repo("someone_else").load("cm_media_1"); fail("another account read this account's copy")
        } catch (_: IOException) {
        }
    }

    @Test fun `a transcript still working is not kept`() = runBlocking {
        server.view = example.copy(status = PipelineStatus.TRANSLATING)
        repo().load("cm_media_1")
        assertNull(repo().cached("cm_media_1"))
    }

    @Test fun `the switch off reads as unavailable, never as the old copy`() = runBlocking {
        val r = repo()
        r.load("cm_media_1")
        server.refuseWith = 404
        server.refusalBody = """{"detail":{"code":"NOT_FOUND","message":"Not found."}}"""
        try {
            r.load("cm_media_1"); fail("a 404 showed a cached transcript")
        } catch (_: TranscriptUnavailable) {
        }
    }

    @Test fun `an offline edit shows at once, survives a reload, and lands in order`() = runBlocking {
        val r = repo()
        val loaded = r.load("cm_media_1").view
        server.offline = true
        val a = loaded.turns.first { it.id == "turn_a" }
        var shown = r.queue(loaded, TranscriptEdit(mediaId = loaded.mediaId, type = TranscriptEdit.TURN, turnId = a.id, text = "आप ये रंग कैसे बनाते हो?", baseRevision = a.revision))
        val edited = shown.turns.first { it.id == "turn_a" }
        assertEquals(TextSource.EDITED, edited.textSource)
        assertEquals(TranslationStatus.STALE, edited.translation.status)
        assertTrue("word timings belong to the machine's words", edited.words.isEmpty())
        shown = r.queue(shown, TranscriptEdit(mediaId = loaded.mediaId, type = TranscriptEdit.SPEAKERS, baseRevision = shown.revision,
            mappings = listOf(SpeakerMapping("speaker_0", "Meera", SpeakerRole.RESEARCHER), SpeakerMapping("speaker_1", "Giriraj", SpeakerRole.ARTISAN))))
        assertEquals("Meera (researcher)", shown.turns.first { it.id == "turn_a" }.speakerName)

        val reopened = r.load("cm_media_1")
        assertTrue(reopened.offline)
        assertEquals(2, reopened.waitingEdits)
        assertEquals("Meera (researcher)", reopened.view.turns[0].speakerName)

        assertEquals(2, r.drain())
        server.offline = false
        assertEquals(0, r.drain())
        assertEquals(listOf("turn_a"), server.edits.map { it.first })
        assertEquals(1, server.edits[0].second.baseRevision)
        assertEquals(1, server.speakerCalls.size)
        assertEquals("Meera (researcher)", r.load("cm_media_1").view.turns[0].speakerName)
    }

    @Test fun `a refused edit is kept with the contract's sentence and does not block the rest`() = runBlocking {
        val r = repo()
        val loaded = r.load("cm_media_1").view
        r.queue(loaded, TranscriptEdit(mediaId = loaded.mediaId, type = TranscriptEdit.TURN, turnId = "turn_b", english = "Edited English.", baseRevision = 0))
        server.refuseWith = 409
        assertEquals(0, r.drain())
        assertEquals(listOf(TranscriptStrings.changedElsewhere), r.failed("cm_media_1").map { it.failure })
    }

    @Test fun `re-translate lands the new English in the phone's copy`() = runBlocking {
        val r = repo()
        val loaded = r.load("cm_media_1").view
        r.queue(loaded, TranscriptEdit(mediaId = loaded.mediaId, type = TranscriptEdit.RETRANSLATE, turnId = "turn_a"))
        assertEquals(0, r.drain())
        assertEquals("Fresh English.", r.cached("cm_media_1")!!.turns.first { it.id == "turn_a" }.translation.text)
    }
}
