package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * C28 on the handset — AI-drafted answers from an interview's transcript, the wire half.
 * The card is `ui/AnswerDraftsCard.kt`; the rules are `backend/app/services/answer_drafts.py`.
 *
 * Hidden unless `GET /questionnaire/answer-drafts/status` says the feature is on. A draft is never an
 * answer: only "Accept section" writes answers, and every accepted answer is labelled
 * machine-suggested here and on the web. Online only — drafting needs the server's AI key.
 */
@Serializable
data class AnswerDraftsStatusDto(val enabled: Boolean = false)

@Serializable
data class AnswerDraftDto(
    val id: String,
    val questionId: String,
    val sectionId: String? = null,
    val draftText: String = "",
    val status: String = "PENDING",
)

@Serializable
data class TranscriptSourceDto(val id: String, val name: String = "")

@Serializable
data class AnswerDraftListDto(
    val items: List<AnswerDraftDto> = emptyList(),
    val transcripts: List<TranscriptSourceDto> = emptyList(),
    /** False for a reader who may not edit the interview: the card then draws nothing. */
    val mayDraft: Boolean = true,
)

@Serializable
data class DraftSectionRequest(val sectionId: String, val mediaId: String? = null, val transcript: String? = null)

@Serializable
data class DraftSectionDto(val drafted: Int = 0, val asked: Int = 0, val items: List<AnswerDraftDto> = emptyList())

@Serializable
data class AcceptedAnswerRequest(val questionId: String, val answerText: String)

@Serializable
data class AcceptSectionRequest(val sectionId: String, val answers: List<AcceptedAnswerRequest>)

@Serializable
data class AcceptSectionDto(val accepted: Int = 0, val edited: Int = 0, val skipped: Int = 0)

@Serializable
data class DismissSectionRequest(val sectionId: String)

interface AnswerDraftsApi {
    @GET("questionnaire/answer-drafts/status")
    suspend fun status(): AnswerDraftsStatusDto

    @GET("questionnaire/interviews/{id}/drafts")
    suspend fun list(@Path("id") interviewId: String): AnswerDraftListDto

    @POST("questionnaire/interviews/{id}/drafts")
    suspend fun draft(@Path("id") interviewId: String, @Body body: DraftSectionRequest): DraftSectionDto

    @POST("questionnaire/interviews/{id}/drafts/accept")
    suspend fun accept(@Path("id") interviewId: String, @Body body: AcceptSectionRequest): AcceptSectionDto

    @POST("questionnaire/interviews/{id}/drafts/dismiss")
    suspend fun dismiss(@Path("id") interviewId: String, @Body body: DismissSectionRequest): Map<String, Int>
}

class AnswerDraftsClient(val api: AnswerDraftsApi) {
    suspend fun enabled(): Boolean = runCatching { api.status().enabled }.getOrDefault(false)

    companion object {
        fun create(context: Context): AnswerDraftsClient =
            AnswerDraftsClient(ApiClient.retrofit(TokenStore(context.applicationContext)).create(AnswerDraftsApi::class.java))
    }
}

/** The pure rules and the words, shared with the web's `components/questionnaire/AnswerDraftsPanel.tsx`. */
object AnswerDrafts {
    const val ORIGIN_MACHINE = "MACHINE_SUGGESTED"
    const val ORIGIN_MACHINE_EDITED = "MACHINE_SUGGESTED_EDITED"

    const val LABEL = "Machine-suggested"
    const val DRAFT_FROM = "Draft from transcript"
    const val DRAFT = "Draft answers"
    const val ACCEPT = "Accept section"
    const val DISMISS = "Dismiss"
    const val PASTE = "Paste a transcript"
    const val ALL_ANSWERED = "Every question here is already answered."
    const val NOTHING_FOUND = "The transcript doesn't answer these questions."
    const val FAILED = "Drafting failed. Try again, or type the answers."

    fun isMachineSuggested(origin: String?): Boolean = origin == ORIGIN_MACHINE || origin == ORIGIN_MACHINE_EDITED

    /** The badge beside an answer: "Machine-suggested" or "Machine-suggested · edited". */
    fun badge(origin: String?): String? = when (origin) {
        ORIGIN_MACHINE -> LABEL
        ORIGIN_MACHINE_EDITED -> "$LABEL · edited"
        else -> null
    }

    /** The section's answers to send: each pending draft's edited text, or the draft as written. */
    fun answersToAccept(pending: List<AnswerDraftDto>, edits: Map<String, String>): List<AcceptedAnswerRequest> =
        pending.map { AcceptedAnswerRequest(it.questionId, edits[it.questionId] ?: it.draftText) }
}
