package com.fieldrepository.app.transcript.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * TRANSCRIPTS IN "VIEW DATA" AND "DOWNLOAD DATA" (0.0.6, transcript contract `dataBrowser`).
 *
 * `GET /data/transcripts` pages recordings with everything the pipeline added: turns with speakers
 * (mapped names), times and English, the summary, quotes, glossary terms and the recording's chips.
 * `GET /data/transcripts/{kind}.{fmt}` streams every matching row. Consent refused means no words
 * at all ([DataRecording.withheld]); cost and model names reach administrators only.
 */
@Serializable
data class DataTurn(
    val index: Int = 0,
    val speakerKey: String? = null,
    val speaker: String = "",
    val colorKey: String = TranscriptPreview.NEUTRAL,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val start: String? = null,
    val end: String? = null,
    val language: String? = null,
    val text: String = "",
    val english: String? = null,
    val translationStatus: String? = null,
    val edited: Boolean = false,
)

@Serializable
data class DataSpeaker(val speakerKey: String? = null, val label: String = "", val colorKey: String = TranscriptPreview.NEUTRAL, val role: String? = null)

@Serializable
data class DataQuote(val speaker: String? = null, val original: String = "", val english: String? = null)

@Serializable
data class DataGlossaryTerm(val term: String = "", val gloss: String? = null, val craft: String? = null)

@Serializable
data class DataModels(val stt: String? = null, val translation: String? = null)

@Serializable
data class DataRecording(
    val mediaId: String,
    val file: String? = null,
    val mediaType: String = "",
    val uploadedBy: String? = null,
    val uploadedAt: String? = null,
    val transcriptStatus: String? = null,
    /** `CONSENT_REFUSED`, or null. */
    val withheld: String? = null,
    /** `TURNS`, or `TEXT` for a recording transcribed before the turn pipeline (turns carry no times). */
    val source: String? = null,
    val version: Int? = null,
    val languages: List<TranscriptLanguage> = emptyList(),
    val durationMs: Long? = null,
    val speakers: List<DataSpeaker> = emptyList(),
    val turns: List<DataTurn> = emptyList(),
    val timed: Boolean = false,
    val summary: List<String> = emptyList(),
    val quotes: List<DataQuote> = emptyList(),
    val glossary: List<DataGlossaryTerm> = emptyList(),
    val chips: JsonObject? = null,
    /** Administrators only. */
    val models: DataModels? = null,
    /** Administrators only. */
    val costUsd: Double? = null,
)

@Serializable
data class DataTranscriptPage(val items: List<DataRecording> = emptyList(), val nextCursor: String? = null)

/** One per-recording transcript file on a data-browser file entry (`transcriptFiles`). */
@Serializable
data class DataTranscriptFile(val name: String = "", val format: String = "", val track: String = "both", val timestamps: Boolean = true, val url: String? = null)

/**
 * The recording filters of `/data/transcripts` (contract `dataBrowser.filters`), as query values.
 * Blank fields are left out; [extra] carries any questionnaire filter the host already holds.
 */
data class DataTranscriptFilters(
    val q: String = "",
    val speaker: String = "",
    val language: String = "",
    val status: String = "",
    val since: String = "",
    val until: String = "",
    /** null: either; true: only with English; false: only without. */
    val hasEnglish: Boolean? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    fun toQuery(): Map<String, String> = buildMap {
        putAll(extra.filterValues { it.isNotBlank() })
        q.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
        speaker.trim().takeIf { it.isNotEmpty() }?.let { put("speaker", it) }
        language.trim().takeIf { it.isNotEmpty() }?.let { put("language", it) }
        status.trim().takeIf { it.isNotEmpty() }?.let { put("status", it) }
        since.trim().takeIf { it.isNotEmpty() }?.let { put("since", it) }
        until.trim().takeIf { it.isNotEmpty() }?.let { put("until", it) }
        hasEnglish?.let { put("hasEnglish", it.toString()) }
    }

    val active: Boolean get() = toQuery().isNotEmpty()

    companion object {
        /** The three streamed datasets and their two formats. */
        val KINDS = listOf("recordings", "turns", "glossary")
        val FORMATS = listOf("csv", "ndjson")

        /** `track` of a file entry as the download route takes it. */
        fun trackOf(file: DataTranscriptFile): CopyTrack = runCatching { CopyTrack.of(file.track) }.getOrDefault(CopyTrack.BOTH)
    }
}
