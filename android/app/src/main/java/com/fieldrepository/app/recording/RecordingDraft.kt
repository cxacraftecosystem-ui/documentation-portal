package com.fieldrepository.app.recording

import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.writeFileAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RecordingDraft(
    val clientClipKey: String,
    val sessionKey: String,
    val questionnaireId: String,
    val adtsPath: String,
    val deviceCapturedAt: String,
    val clockOffsetMs: Long = 0,
    val clockOffsetMeasuredAt: String? = null,
    val capturedAtSource: String = "DEVICE",
    val targets: List<QTargetRef> = emptyList(),
    val subjects: List<QCompleteSubject> = emptyList(),
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

    fun delete(filesDir: File, draft: RecordingDraft, keepAudio: Boolean) {
        if (!keepAudio) {
            File(draft.adtsPath).delete()
            draft.m4aPath?.let { File(it).delete() }
        }
        File(dir(filesDir), "${draft.clientClipKey}.json").delete()
    }
}
