package com.fieldrepository.app.transcript.data

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptLanguage(val code: String, val label: String = "")

@Serializable
data class TranscriptBadge(val kind: String, val provider: String = "", val model: String = "", val label: String = "")

@Serializable
data class TranscriptSpeaker(
    val speakerKey: String,
    val label: String = "",
    val name: String? = null,
    val role: String? = null,
    val personId: String? = null,
    val suggested: Boolean = false,
    val sample: TranscriptClip? = null,
    val colorKey: String? = null,
)

@Serializable
data class AudioIsolationSetting(val enabled: Boolean = false, val keyConfigured: Boolean = false)

@Serializable
data class AudioIsolationEnvelope(
    val audioIsolation: AudioIsolationSetting? = null,
    val autoTranscribe: AutoTranscribeSetting? = null,
    val backfill: BackfillProgress? = null,
)

@Serializable
data class AutoTranscribeSetting(val video: Boolean = false, val audio: Boolean = false)

@Serializable
data class BackfillProgress(
    val enabled: Boolean = false,
    val queued: Int = 0,
    val done: Int = 0,
    val waiting: Int = 0,
    val lastRunAt: String? = null,
)

@Serializable
data class BackfillEstimate(
    val recordings: Int = 0,
    val queued: Int = 0,
    val failed: Int = 0,
    val untranscribed: Int = 0,
    val leftOutForConsent: Int = 0,
    val minutes: Double = 0.0,
    val hours: Double = 0.0,
    val costUsd: Double = 0.0,
    val computedAt: String? = null,
)

@Serializable
data class BackfillView(val estimate: BackfillEstimate? = null, val progress: BackfillProgress? = null)

@Serializable
data class TranscriptionSwitchBody(
    val autoTranscribeVideo: Boolean? = null,
    val autoTranscribeAudio: Boolean? = null,
    val transcriptBackfill: Boolean? = null,
    val confirmBackfill: Boolean = false,
)

data class TranscriptionSwitches(
    val autoTranscribe: AutoTranscribeSetting = AutoTranscribeSetting(),
    val backfill: BackfillProgress = BackfillProgress(),
)

@Serializable
data class AudioIsolationBody(val audioIsolation: Boolean)

@Serializable
data class TranscriptClip(val startMs: Long = 0, val endMs: Long = 0)

@Serializable
data class TranscriptWord(val text: String, val startMs: Long, val endMs: Long, val offset: Int)

@Serializable
data class TurnTranslation(
    val text: String? = null,
    val status: String = TranslationStatus.PENDING,
    val source: String = TextSource.MACHINE,
    val model: String? = null,
)

@Serializable
data class TranscriptTurn(
    val id: String,
    val index: Int = 0,
    val speakerKey: String? = null,
    val speakerName: String = "",
    val startMs: Long,
    val endMs: Long,
    val language: String = "",
    val text: String,
    val textSource: String = TextSource.MACHINE,
    val lowConfidence: List<List<Int>> = emptyList(),
    val words: List<TranscriptWord> = emptyList(),
    val translation: TurnTranslation = TurnTranslation(),
    val revision: Int = 0,
    val colorKey: String? = null,
)

@Serializable
data class TranscriptQuote(val turnId: String? = null, val original: String = "", val english: String = "", val speaker: String? = null)

@Serializable
data class TranscriptCost(val sttUsd: Double = 0.0, val translationUsd: Double = 0.0, val totalUsd: Double = 0.0)

@Serializable
data class TranscriptAudio(
    val url: String? = null,
    val durationMs: Long? = null,
    val cleaned: Boolean = false,
    val originalUrl: String? = null,
    val video: Boolean = false,
)

@Serializable
data class TranscriptPermissions(
    val edit: Boolean = false,
    val retry: Boolean = false,
    val transcribeNow: Boolean = false,
    val ask: Boolean = false,
    val autodraft: Boolean = false,
    val quoteCard: Boolean = false,
    val mapSpeakers: Boolean = false,
    val addEnglish: Boolean = false,
)

object EnglishNotice {
    const val DECLINED = "DECLINED"
    const val UNAVAILABLE = "UNAVAILABLE"
}

@Serializable
data class EnglishBody(val baseRevision: Int, val text: String)

@Serializable
data class TranscriptReadOnly(val code: String = "", val message: String = "")

@Serializable
data class Transcript(
    val mediaId: String,
    val title: String = "",
    val status: String,
    val queueReason: String? = null,
    val statusMessage: String? = null,
    val version: Int? = null,
    val languages: List<TranscriptLanguage> = emptyList(),
    val durationMs: Long? = null,
    val speakerCount: Int = 0,
    val badges: List<TranscriptBadge> = emptyList(),
    val speakers: List<TranscriptSpeaker> = emptyList(),
    val turns: List<TranscriptTurn> = emptyList(),
    val summary: List<String> = emptyList(),
    val quotes: List<TranscriptQuote> = emptyList(),
    val cost: TranscriptCost? = null,
    val audio: TranscriptAudio = TranscriptAudio(),
    val permissions: TranscriptPermissions = TranscriptPermissions(),
    val readOnly: TranscriptReadOnly? = null,
    val englishNotice: String? = null,
    val manualEnglish: String? = null,
    val revision: Int = 0,
    val updatedAt: String? = null,
)

@Serializable
data class TranscriptSummary(
    val mediaId: String,
    val status: String,
    val languages: List<TranscriptLanguage> = emptyList(),
    val durationMs: Long? = null,
    val speakerCount: Int = 0,
    val badges: List<TranscriptBadge> = emptyList(),
    val translation: TranscriptTranslationProgress? = null,
    val statusMessage: String? = null,
)

@Serializable
data class TranscriptTranslationProgress(val done: Int = 0, val total: Int = 0, val percent: Int? = null)

@Serializable
data class TranscriptSummaryList(val items: List<TranscriptSummary> = emptyList())

object PipelineStatus {
    const val WAITING_FOR_CONSENT = "WAITING_FOR_CONSENT"
    const val QUEUED = "QUEUED"
    const val TRANSCRIBING = "TRANSCRIBING"
    const val TRANSLATING = "TRANSLATING"
    const val READY = "READY"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"

    fun working(status: String) = status == QUEUED || status == TRANSCRIBING || status == TRANSLATING
}

object TranslationStatus {
    const val TRANSLATED = "TRANSLATED"
    const val ALREADY_ENGLISH = "ALREADY_ENGLISH"
    const val STALE = "STALE"
    const val PENDING = "PENDING"
    const val FAILED = "FAILED"
}

object TextSource {
    const val MACHINE = "MACHINE"
    const val EDITED = "EDITED"
}

object SpeakerRole {
    const val RESEARCHER = "RESEARCHER"
    const val ARTISAN = "ARTISAN"
    const val OTHER = "OTHER"
    val all = listOf(RESEARCHER, ARTISAN, OTHER)
}

@Serializable
data class SpeakerMapping(val speakerKey: String, val name: String? = null, val role: String? = null, val personId: String? = null)

@Serializable
data class SpeakerMappingBody(val baseRevision: Int, val mappings: List<SpeakerMapping>)

@Serializable
data class SpeakerCandidate(val name: String, val role: String? = null, val personId: String? = null, val reason: String? = null)

@Serializable
data class SpeakerSuggestion(val speakerKey: String, val candidates: List<SpeakerCandidate> = emptyList())

@Serializable
data class SpeakerSuggestionList(val items: List<SpeakerSuggestion> = emptyList())

@Serializable
data class TurnEditBody(val baseRevision: Int, val text: String? = null, val english: String? = null)

@Serializable
data class TurnHistoryEntry(val at: String, val field: String, val source: String = TextSource.MACHINE, val text: String = "", val byName: String? = null)

@Serializable
data class TurnHistoryList(val items: List<TurnHistoryEntry> = emptyList())

@Serializable
data class CopyBody(val track: String, val turnIds: List<String>? = null, val single: Boolean = false)

@Serializable
data class CopyText(val text: String)

@Serializable
data class AskBody(val question: String)

@Serializable
data class Citation(val turnId: String, val startMs: Long = 0, val quote: String? = null)

@Serializable
data class AskAnswer(
    val covered: Boolean = false,
    val answer: String = "",
    val citations: List<Citation> = emptyList(),
    val model: String? = null,
)

@Serializable
data class AutoDraft(
    val questionId: String,
    val answerOriginal: String = "",
    val answerEnglish: String = "",
    val citations: List<Citation> = emptyList(),
)

@Serializable
data class AutoDraftSkip(val questionId: String, val reason: String)

@Serializable
data class AutoDraftResult(
    val drafted: List<AutoDraft> = emptyList(),
    val skipped: List<AutoDraftSkip> = emptyList(),
    val draftsUrl: String? = null,
)

@Serializable
data class QuoteCardBody(val turnIds: List<String>)

@Serializable
data class QuoteClip(val startMs: Long = 0, val endMs: Long = 0, val url: String? = null)

@Serializable
data class QuoteCard(
    val turnIds: List<String> = emptyList(),
    val original: String = "",
    val english: String = "",
    val speakerName: String = "",
    val artisanName: String? = null,
    val craft: String? = null,
    val date: String = "",
    val language: TranscriptLanguage? = null,
    val clip: QuoteClip = QuoteClip(),
    val markdown: String = "",
)

@Serializable
data class GlossarySighting(val mediaId: String, val turnId: String? = null, val startMs: Long? = null)

@Serializable
data class GlossaryTerm(
    val id: String,
    val term: String,
    val gloss: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    val occurrences: Int = 0,
    val sightings: List<GlossarySighting> = emptyList(),
)

@Serializable
data class GlossaryPage(val items: List<GlossaryTerm> = emptyList(), val nextCursor: String? = null)

@Serializable
data class BudgetMeter(
    val month: String = "",
    val monthlyUsd: Double? = null,
    val spentUsd: Double = 0.0,
    val sttUsd: Double = 0.0,
    val translationUsd: Double = 0.0,
    val percent: Double? = null,
    val state: String = "UNSET",
    val alertAt: Double = 0.8,
    val pauseAt: Double = 1.0,
    val pausedByBudget: Boolean = false,
)

@Serializable
data class TranscriptErrorDetail(val code: String = "", val message: String = "")

@Serializable
data class TranscriptError(val detail: TranscriptErrorDetail = TranscriptErrorDetail())
