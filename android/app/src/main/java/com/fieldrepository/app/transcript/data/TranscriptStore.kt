package com.fieldrepository.app.transcript.data

import com.fieldrepository.app.questionnaire.data.writeFileAtomically
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class TranscriptStore(root: File, userId: String, private val json: Json) {
    val dir: File = File(root, "transcripts/" + accountKey(userId))
    private val outboxDir = File(dir, "outbox")
    private val mutex = Mutex()

    private fun file(mediaId: String) = File(dir, safe(mediaId) + ".json")

    suspend fun cached(mediaId: String): Transcript? = mutex.withLock {
        val f = file(mediaId)
        if (!f.isFile) return@withLock null
        runCatching { json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun keep(view: Transcript) = mutex.withLock {
        val f = file(view.mediaId)
        if (view.status == PipelineStatus.READY) {
            writeFileAtomically(f, json.encodeToString(Transcript.serializer(), view).toByteArray())
        } else if (f.isFile) {
            f.delete()
        }
        Unit
    }

    suspend fun cachedIds(): List<String> = mutex.withLock {
        dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString(Transcript.serializer(), f.readText()).mediaId }.getOrNull()
        }
    }

    suspend fun pending(): List<TranscriptEdit> = mutex.withLock { readPending() }

    private fun readPending(): List<TranscriptEdit> =
        outboxDir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString(TranscriptEdit.serializer(), f.readText()) }.getOrNull()
        }.sortedWith(compareBy({ it.seq }, { it.createdAt }))

    suspend fun enqueue(edit: TranscriptEdit): TranscriptEdit = mutex.withLock {
        val stored = edit.copy(seq = (readPending().maxOfOrNull { it.seq } ?: 0) + 1)
        write(stored)
        stored
    }

    suspend fun update(edit: TranscriptEdit) = mutex.withLock { write(edit) }

    suspend fun remove(edit: TranscriptEdit) = mutex.withLock { File(outboxDir, edit.id + ".json").delete(); Unit }

    private fun write(edit: TranscriptEdit) =
        writeFileAtomically(File(outboxDir, edit.id + ".json"), json.encodeToString(TranscriptEdit.serializer(), edit).toByteArray())

    companion object {
        fun accountKey(userId: String): String =
            MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun safe(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }
    }
}

@Serializable
data class TranscriptEdit(
    val id: String = UUID.randomUUID().toString(),
    val mediaId: String,
    val type: String,
    val turnId: String? = null,
    val text: String? = null,
    val english: String? = null,
    val mappings: List<SpeakerMapping> = emptyList(),
    val baseRevision: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val seq: Int = 0,
    val failure: String? = null,
) {
    companion object {
        const val TURN = "turn"
        const val RETRANSLATE = "retranslate"
        const val SPEAKERS = "speakers"
    }
}

data class LoadedTranscript(val view: Transcript, val offline: Boolean, val waitingEdits: Int)

class TranscriptUnavailable : Exception("transcript unavailable")

class TranscriptRepository(
    private val backend: TranscriptBackend,
    private val store: TranscriptStore,
    private val json: Json,
) {
    val server: TranscriptBackend get() = backend

    suspend fun load(mediaId: String): LoadedTranscript {
        val fresh = try {
            backend.get(mediaId)
        } catch (e: IOException) {
            null
        } catch (e: HttpException) {
            if (e.code() == 404) {
                throw TranscriptUnavailable()
            }
            if (e.code() < 500) throw e
            null
        }
        val waiting = store.pending().filter { it.mediaId == mediaId && it.failure == null }
        if (fresh != null) {
            store.keep(fresh)
            return LoadedTranscript(waiting.fold(fresh) { acc, e -> applyLocally(acc, e) }, offline = false, waitingEdits = waiting.size)
        }
        val cached = store.cached(mediaId) ?: throw IOException(TranscriptStrings.offlineCached)
        return LoadedTranscript(waiting.fold(cached) { acc, e -> applyLocally(acc, e) }, offline = true, waitingEdits = waiting.size)
    }

    suspend fun cached(mediaId: String): Transcript? = store.cached(mediaId)

    suspend fun queue(current: Transcript, edit: TranscriptEdit): Transcript {
        store.enqueue(edit)
        val updated = applyLocally(current, edit)
        store.keep(updated)
        return updated
    }

    suspend fun accept(view: Transcript) = store.keep(view)

    suspend fun acceptTurn(mediaId: String, turn: TranscriptTurn): Transcript? {
        val cached = store.cached(mediaId) ?: return null
        val updated = cached.copy(turns = cached.turns.map { if (it.id == turn.id) turn else it })
        store.keep(updated)
        return updated
    }

    suspend fun drain(): Int {
        for (edit in store.pending().filter { it.failure == null }) {
            try {
                when (edit.type) {
                    TranscriptEdit.TURN -> acceptTurn(edit.mediaId, backend.editTurn(edit.mediaId, edit.turnId!!, TurnEditBody(edit.baseRevision, edit.text, edit.english)))
                    TranscriptEdit.RETRANSLATE -> acceptTurn(edit.mediaId, backend.retranslate(edit.mediaId, edit.turnId!!))
                    TranscriptEdit.SPEAKERS -> store.keep(backend.mapSpeakers(edit.mediaId, SpeakerMappingBody(edit.baseRevision, edit.mappings)))
                }
                store.remove(edit)
            } catch (e: IOException) {
                break
            } catch (e: HttpException) {
                if (e.code() >= 500 || e.code() == 429) break
                store.update(edit.copy(failure = refusalSentence(e.code(), e.transcriptError(json))))
            }
        }
        return store.pending().count { it.failure == null }
    }

    suspend fun failed(mediaId: String): List<TranscriptEdit> = store.pending().filter { it.mediaId == mediaId && it.failure != null }

    suspend fun discard(edit: TranscriptEdit) = store.remove(edit)

    companion object {
        fun refusalSentence(code: Int, detail: TranscriptErrorDetail?): String = when {
            detail?.code == "STALE_REVISION" || code == 409 && detail == null -> TranscriptStrings.changedElsewhere
            detail != null && detail.message.isNotBlank() -> detail.message
            else -> TranscriptStrings.changedElsewhere
        }

        fun applyLocally(t: Transcript, edit: TranscriptEdit): Transcript = when (edit.type) {
            TranscriptEdit.TURN -> t.copy(turns = t.turns.map { turn ->
                if (turn.id != edit.turnId) turn else {
                    val textChanged = edit.text != null && edit.text != turn.text
                    val englishChanged = edit.english != null && edit.english != turn.translation.text
                    turn.copy(
                        text = edit.text ?: turn.text,
                        textSource = if (textChanged) TextSource.EDITED else turn.textSource,
                        words = if (textChanged) emptyList() else turn.words,
                        lowConfidence = if (textChanged) emptyList() else turn.lowConfidence,
                        translation = when {
                            englishChanged -> turn.translation.copy(text = edit.english, status = TranslationStatus.TRANSLATED, source = TextSource.EDITED)
                            textChanged && turn.translation.status != TranslationStatus.ALREADY_ENGLISH -> turn.translation.copy(status = TranslationStatus.STALE)
                            else -> turn.translation
                        },
                    )
                }
            })
            TranscriptEdit.SPEAKERS -> {
                val byKey = edit.mappings.associateBy { it.speakerKey }
                val speakers = t.speakers.mapIndexed { i, s ->
                    val m = byKey[s.speakerKey] ?: return@mapIndexed s
                    s.copy(name = m.name, role = m.role, personId = m.personId, suggested = false, label = TranscriptFormat.speakerLabel(m.name, m.role, i + 1))
                }
                val labels = speakers.associate { it.speakerKey to it.label }
                t.copy(speakers = speakers, turns = t.turns.map { turn -> turn.copy(speakerName = labels[turn.speakerKey] ?: turn.speakerName) })
            }
            TranscriptEdit.RETRANSLATE -> t.copy(turns = t.turns.map { turn ->
                if (turn.id == edit.turnId) turn.copy(translation = turn.translation.copy(status = TranslationStatus.PENDING)) else turn
            })
            else -> t
        }
    }
}
