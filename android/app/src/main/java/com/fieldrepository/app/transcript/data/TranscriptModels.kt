package com.fieldrepository.app.transcript.data

import kotlinx.serialization.Serializable

/**
 * THE TRANSCRIPT AS THE SERVER STORES IT: TURNS, NOT ONE MARKDOWN BLOB (DESIGN_2026-10-31 §2, §3).
 *
 * Every type mirrors `types` in `shared/transcript-contract.json` (contractVersion 1), field for
 * field; `TranscriptContractTest` decodes the contract's own examples through these classes. The
 * same Kotlin in designer-portal and documentation-portal (`scripts/transcript_android.py`).
 * Every recording in both portals is addressed by its `MediaFile.id` (the contract's `mediaId`).
 */

@Serializable
data class TranscriptLanguage(val code: String, val label: String = "")

@Serializable
data class TranscriptBadge(val kind: String, val provider: String = "", val model: String = "", val label: String = "")

@Serializable
data class TranscriptSpeaker(
    val speakerKey: String,
    /** The mapped name, else "Speaker 1". */
    val label: String = "",
    val name: String? = null,
    /** `speakerRole`: RESEARCHER, ARTISAN, OTHER. */
    val role: String? = null,
    val personId: String? = null,
    /** The label comes from a suggestion nobody has confirmed. */
    val suggested: Boolean = false,
)

/** One word with its timing and its character [offset] in the turn's text (karaoke). */
@Serializable
data class TranscriptWord(val text: String, val startMs: Long, val endMs: Long, val offset: Int)

@Serializable
data class TurnTranslation(
    val text: String? = null,
    /** `translationStatus`: TRANSLATED, ALREADY_ENGLISH, STALE, PENDING, FAILED. */
    val status: String = TranslationStatus.PENDING,
    /** `textSource`: MACHINE or EDITED. */
    val source: String = TextSource.MACHINE,
    val model: String? = null,
)

@Serializable
data class TranscriptTurn(
    val id: String,
    val index: Int = 0,
    val speakerKey: String? = null,
    /** As [TranscriptSpeaker.label]; the copy uses this. */
    val speakerName: String = "",
    val startMs: Long,
    val endMs: Long,
    val language: String = "",
    /** The current original: edited if edited, else the machine's. */
    val text: String,
    val textSource: String = TextSource.MACHINE,
    /** `[start, end)` code-point offsets into [text] where Scribe was unsure; empty once edited. */
    val lowConfidence: List<List<Int>> = emptyList(),
    val words: List<TranscriptWord> = emptyList(),
    val translation: TurnTranslation = TurnTranslation(),
    val revision: Int = 0,
)

@Serializable
data class TranscriptQuote(val turnId: String? = null, val original: String = "", val english: String = "", val speaker: String? = null)

@Serializable
data class TranscriptCost(val sttUsd: Double = 0.0, val translationUsd: Double = 0.0, val totalUsd: Double = 0.0)

@Serializable
data class TranscriptAudio(val url: String? = null, val durationMs: Long? = null)

@Serializable
data class TranscriptPermissions(
    val edit: Boolean = false,
    val retry: Boolean = false,
    val transcribeNow: Boolean = false,
    val ask: Boolean = false,
    val autodraft: Boolean = false,
    val quoteCard: Boolean = false,
    val mapSpeakers: Boolean = false,
)

/** `TranscriptView.readOnly`: `NOT_EDITOR` or `HELD_POST`, with the portal's own read-only sentence. */
@Serializable
data class TranscriptReadOnly(val code: String = "", val message: String = "")

/** `TranscriptView`: GET /api/transcripts/{mediaId}. */
@Serializable
data class Transcript(
    val mediaId: String,
    /** "Interview · Giriraj Prasad Chhipa · 24 Jun 2026" */
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
    /** Admins only, else null. */
    val cost: TranscriptCost? = null,
    val audio: TranscriptAudio = TranscriptAudio(),
    val permissions: TranscriptPermissions = TranscriptPermissions(),
    /** Why this reader may read but not change it; null when they may (the write rule's own answer). */
    val readOnly: TranscriptReadOnly? = null,
    /** Bumps on every edit or speaker change; sent back as `baseRevision`. */
    val revision: Int = 0,
    val updatedAt: String? = null,
)

/** `TranscriptSummary`: the header badges a list draws, from GET /api/transcripts/batch. */
@Serializable
data class TranscriptSummary(
    val mediaId: String,
    val status: String,
    val languages: List<TranscriptLanguage> = emptyList(),
    val durationMs: Long? = null,
    val speakerCount: Int = 0,
    val badges: List<TranscriptBadge> = emptyList(),
)

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

// ── Speakers ───────────────────────────────────────────────────────────────────────────────────

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

// ── Turn edits ─────────────────────────────────────────────────────────────────────────────────

@Serializable
data class TurnEditBody(val baseRevision: Int, val text: String? = null, val english: String? = null)

@Serializable
data class TurnHistoryEntry(val at: String, val field: String, val source: String = TextSource.MACHINE, val text: String = "", val byName: String? = null)

@Serializable
data class TurnHistoryList(val items: List<TurnHistoryEntry> = emptyList())

// ── Copy (server side) ─────────────────────────────────────────────────────────────────────────

@Serializable
data class CopyBody(val track: String, val turnIds: List<String>? = null, val single: Boolean = false)

@Serializable
data class CopyText(val text: String)

// ── Ask this interview (§10.2) ─────────────────────────────────────────────────────────────────

@Serializable
data class AskBody(val question: String)

@Serializable
data class Citation(val turnId: String, val startMs: Long = 0, val quote: String? = null)

@Serializable
data class AskAnswer(
    /** False: the transcript does not cover the question, and the answer says so. */
    val covered: Boolean = false,
    val answer: String = "",
    val citations: List<Citation> = emptyList(),
    val model: String? = null,
)

// ── Fill the questionnaire from the recording (§10.3) ──────────────────────────────────────────

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

// ── Quote card (§10.5) ─────────────────────────────────────────────────────────────────────────

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

// ── Craft glossary (§10.4) ─────────────────────────────────────────────────────────────────────

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

// ── Budget (§4.7) ──────────────────────────────────────────────────────────────────────────────

@Serializable
data class BudgetMeter(
    val month: String = "",
    val monthlyUsd: Double? = null,
    val spentUsd: Double = 0.0,
    val sttUsd: Double = 0.0,
    val translationUsd: Double = 0.0,
    val percent: Double? = null,
    /** `budgetState`: OK, ALERT, PAUSED, UNSET. */
    val state: String = "UNSET",
    val alertAt: Double = 0.8,
    val pauseAt: Double = 1.0,
    val pausedByBudget: Boolean = false,
)

/** `{detail: {code, message}}`, every error's body. */
@Serializable
data class TranscriptErrorDetail(val code: String = "", val message: String = "")

@Serializable
data class TranscriptError(val detail: TranscriptErrorDetail = TranscriptErrorDetail())
