package com.fieldrepository.app.recording

import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.writeFileAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a take is, written to disk BEFORE the recorder starts (DESIGN §6.1.2), so a process kill
 * mid-take still knows which session, targets and roster the partial ADTS file belongs to.
 *
 * The files live in app-private storage (`questionnaire-recordings/`), which NAMING §1.7 exempts:
 * the name the researcher ever sees is the one the server mints at presign.
 */
@Serializable
data class RecordingDraft(
    val clientClipKey: String,
    /** The session's outbox clientKey. */
    val sessionKey: String,
    val questionnaireId: String,
    val adtsPath: String,
    val deviceCapturedAt: String,
    val clockOffsetMs: Long = 0,
    /** When [clockOffsetMs] was measured (ISO-8601), frozen with the take. */
    val clockOffsetMeasuredAt: String? = null,
    val capturedAtSource: String = "DEVICE",
    val targets: List<QTargetRef> = emptyList(),
    /** The roster at Record press: everyone ANSWERED until the "Answered by" chips say otherwise. */
    val subjects: List<QCompleteSubject> = emptyList(),
    /** "Section D", "D3"; copy only. */
    val label: String,
    val contentType: String = "audio/mp4",
    val extension: String = "m4a",
    val continuationOfId: String? = null,
    val replacesRecordingId: String? = null,
    val ackedRecordingIds: List<String> = emptyList(),
    val finished: Boolean = false,
    val m4aPath: String? = null,
    val durationMs: Long = 0,
    val sha256: String? = null,
    val sizeBytes: Long = 0,
    /** Set once the take is in the session outbox; the draft is then only a record. */
    val queued: Boolean = false
)

object RecordingDrafts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun dir(filesDir: File): File = File(filesDir, "questionnaire-recordings").apply { mkdirs() }

    fun write(filesDir: File, draft: RecordingDraft) {
        writeFileAtomically(File(dir(filesDir), "${draft.clientClipKey}.json"), json.encodeToString(RecordingDraft.serializer(), draft).toByteArray())
    }

    fun all(filesDir: File): List<RecordingDraft> =
        dir(filesDir).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString(RecordingDraft.serializer(), it.readText()) }.getOrNull() }

    fun read(filesDir: File, clientClipKey: String): RecordingDraft? =
        File(dir(filesDir), "$clientClipKey.json").takeIf { it.isFile }?.let {
            runCatching { json.decodeFromString(RecordingDraft.serializer(), it.readText()) }.getOrNull()
        }

    /** Removes the draft record and, unless [keepAudio], its audio files too. */
    fun delete(filesDir: File, draft: RecordingDraft, keepAudio: Boolean) {
        if (!keepAudio) {
            File(draft.adtsPath).delete()
            draft.m4aPath?.let { File(it).delete() }
        }
        File(dir(filesDir), "${draft.clientClipKey}.json").delete()
    }
}
