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

/**
 * READY TRANSCRIPTS, KEPT ON THE PHONE PER ACCOUNT, AND THE EDITS WAITING TO BE SENT (DESIGN §4.9).
 *
 * `<files>/transcripts/<account>/` holds one JSON file per Ready transcript, for reading and copying
 * with no connection, and `outbox/` the turn edits, speaker names and re-translations made
 * offline, one file per change in the order they were made — the questionnaire queue's pattern
 * (`questionnaire/data/SessionOutbox.kt`): a file per unit, written atomically, drained in order by
 * the app's background pass and replayed until the server answers. The account folder is a digest
 * of the user id, so one person's transcripts never open under another's sign-in.
 */
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

    /** Keeps a Ready transcript; any other state is not worth reading offline and is dropped. */
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

    // ── The edit queue ─────────────────────────────────────────────────────────────────────

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

/** One change made on the phone, waiting to reach the server. */
@Serializable
data class TranscriptEdit(
    val id: String = UUID.randomUUID().toString(),
    val mediaId: String,
    /** [TURN], [RETRANSLATE] or [SPEAKERS]. */
    val type: String,
    val turnId: String? = null,
    val text: String? = null,
    val english: String? = null,
    val mappings: List<SpeakerMapping> = emptyList(),
    /** The turn's revision (a turn edit) or the transcript's (speakers) the change was made on. */
    val baseRevision: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val seq: Int = 0,
    /** The server refused it (not a network failure): kept for a person to see, never resent alone. */
    val failure: String? = null,
) {
    companion object {
        const val TURN = "turn"
        const val RETRANSLATE = "retranslate"
        const val SPEAKERS = "speakers"
    }
}

/** What loading gave the viewer: the transcript, whether it is the phone's copy, and what waits. */
data class LoadedTranscript(val view: Transcript, val offline: Boolean, val waitingEdits: Int)

/** The transcript route answered 404: the pipeline is off (or the reader may not see it). */
class TranscriptUnavailable : Exception("transcript unavailable")

/**
 * The viewer's one door to transcripts: the server when there is one, the phone's copy when there
 * is not, and every edit applied to the phone's copy at once and queued for the server.
 */
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
                // The pipeline is off, or this reader may not see it: never show a stale copy then.
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

    /** The phone's copy, if there is one (an entry row with no connection). */
    suspend fun cached(mediaId: String): Transcript? = store.cached(mediaId)

    /** Applies a change to the phone's copy at once and queues it; the caller asks for a sync pass. */
    suspend fun queue(current: Transcript, edit: TranscriptEdit): Transcript {
        store.enqueue(edit)
        val updated = applyLocally(current, edit)
        store.keep(updated)
        return updated
    }

    /** Takes a server answer into the phone's copy: a whole view, or one turn of it. */
    suspend fun accept(view: Transcript) = store.keep(view)

    suspend fun acceptTurn(mediaId: String, turn: TranscriptTurn): Transcript? {
        val cached = store.cached(mediaId) ?: return null
        val updated = cached.copy(turns = cached.turns.map { if (it.id == turn.id) turn else it })
        store.keep(updated)
        return updated
    }

    /**
     * Sends every waiting change in order. Stops at the first network failure (the rest wait for
     * the next pass); a refusal is kept with its reason and the queue moves on. Returns how many wait.
     */
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

        /** The change as the phone shows it before the server has answered. */
        fun applyLocally(t: Transcript, edit: TranscriptEdit): Transcript = when (edit.type) {
            TranscriptEdit.TURN -> t.copy(turns = t.turns.map { turn ->
                if (turn.id != edit.turnId) turn else {
                    val textChanged = edit.text != null && edit.text != turn.text
                    val englishChanged = edit.english != null && edit.english != turn.translation.text
                    turn.copy(
                        text = edit.text ?: turn.text,
                        textSource = if (textChanged) TextSource.EDITED else turn.textSource,
                        // Word timings and doubts belong to the machine's words, not to edited ones.
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
