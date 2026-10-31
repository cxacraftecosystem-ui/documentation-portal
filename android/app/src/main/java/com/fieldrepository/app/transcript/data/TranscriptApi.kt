package com.fieldrepository.app.transcript.data

import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** The routes of `shared/transcript-contract.json`, under the apps' `/api/` base. */
interface TranscriptApi {
    @GET("transcripts/{mediaId}")
    suspend fun get(@Path("mediaId") mediaId: String): Transcript

    @GET("transcripts/batch")
    suspend fun batch(@Query("ids") ids: String): TranscriptSummaryList

    @PUT("transcripts/{mediaId}/speakers")
    suspend fun mapSpeakers(@Path("mediaId") mediaId: String, @Body body: SpeakerMappingBody): Transcript

    @GET("transcripts/{mediaId}/speakers/suggestions")
    suspend fun speakerSuggestions(@Path("mediaId") mediaId: String): SpeakerSuggestionList

    @PATCH("transcripts/{mediaId}/turns/{turnId}")
    suspend fun editTurn(@Path("mediaId") mediaId: String, @Path("turnId") turnId: String, @Body body: TurnEditBody): TranscriptTurn

    @GET("transcripts/{mediaId}/turns/{turnId}/history")
    suspend fun turnHistory(@Path("mediaId") mediaId: String, @Path("turnId") turnId: String): TurnHistoryList

    @POST("transcripts/{mediaId}/turns/{turnId}/retranslate")
    suspend fun retranslate(@Path("mediaId") mediaId: String, @Path("turnId") turnId: String): TranscriptTurn

    @POST("transcripts/{mediaId}/retry")
    suspend fun retry(@Path("mediaId") mediaId: String): Transcript

    @POST("transcripts/{mediaId}/transcribe-now")
    suspend fun transcribeNow(@Path("mediaId") mediaId: String): Transcript

    @Streaming
    @GET("transcripts/{mediaId}/download")
    suspend fun download(@Path("mediaId") mediaId: String, @Query("format") format: String, @Query("track") track: String): Response<ResponseBody>

    @POST("transcripts/{mediaId}/ask")
    suspend fun ask(@Path("mediaId") mediaId: String, @Body body: AskBody): AskAnswer

    @POST("transcripts/{mediaId}/autodraft")
    suspend fun autodraft(@Path("mediaId") mediaId: String): AutoDraftResult

    @POST("transcripts/{mediaId}/quote-card")
    suspend fun quoteCard(@Path("mediaId") mediaId: String, @Body body: QuoteCardBody): QuoteCard

    @GET("transcripts/glossary")
    suspend fun glossary(
        @Query("craftId") craftId: String?,
        @Query("q") q: String?,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): GlossaryPage

    @GET("transcripts/budget")
    suspend fun budget(): BudgetMeter
}

/**
 * Everything the viewer asks of a server, as one seam: [RetrofitTranscriptBackend] in the app, a
 * fake in the JVM and Compose tests (no test ever reaches a real transcription or model provider).
 */
interface TranscriptBackend {
    suspend fun get(mediaId: String): Transcript
    suspend fun batch(ids: List<String>): List<TranscriptSummary>
    suspend fun mapSpeakers(mediaId: String, body: SpeakerMappingBody): Transcript
    suspend fun speakerSuggestions(mediaId: String): List<SpeakerSuggestion>
    suspend fun editTurn(mediaId: String, turnId: String, body: TurnEditBody): TranscriptTurn
    suspend fun turnHistory(mediaId: String, turnId: String): List<TurnHistoryEntry>
    suspend fun retranslate(mediaId: String, turnId: String): TranscriptTurn
    suspend fun retry(mediaId: String): Transcript
    suspend fun transcribeNow(mediaId: String): Transcript
    /** The file; `srt`/`vtt` take `original` or `english` only. */
    suspend fun download(mediaId: String, format: String, track: CopyTrack): DownloadedFile
    suspend fun ask(mediaId: String, question: String): AskAnswer
    suspend fun autodraft(mediaId: String): AutoDraftResult
    suspend fun quoteCard(mediaId: String, turnIds: List<String>): QuoteCard
    suspend fun glossary(craftId: String?, q: String?): List<GlossaryTerm>
    suspend fun budget(): BudgetMeter
}

class RetrofitTranscriptBackend(private val api: TranscriptApi) : TranscriptBackend {
    override suspend fun get(mediaId: String) = api.get(mediaId)
    override suspend fun batch(ids: List<String>) = if (ids.isEmpty()) emptyList() else api.batch(ids.joinToString(",")).items
    override suspend fun mapSpeakers(mediaId: String, body: SpeakerMappingBody) = api.mapSpeakers(mediaId, body)
    override suspend fun speakerSuggestions(mediaId: String) = api.speakerSuggestions(mediaId).items
    override suspend fun editTurn(mediaId: String, turnId: String, body: TurnEditBody) = api.editTurn(mediaId, turnId, body)
    override suspend fun turnHistory(mediaId: String, turnId: String) = api.turnHistory(mediaId, turnId).items
    override suspend fun retranslate(mediaId: String, turnId: String) = api.retranslate(mediaId, turnId)
    override suspend fun retry(mediaId: String) = api.retry(mediaId)
    override suspend fun transcribeNow(mediaId: String) = api.transcribeNow(mediaId)
    override suspend fun download(mediaId: String, format: String, track: CopyTrack): DownloadedFile {
        val response = api.download(mediaId, format, track.wire)
        val body = response.body()
        if (!response.isSuccessful || body == null) throw HttpException(response)
        return DownloadedFile(contentDispositionName(response.headers()["Content-Disposition"]), body.use { it.bytes() })
    }
    override suspend fun ask(mediaId: String, question: String) = api.ask(mediaId, AskBody(question))
    override suspend fun autodraft(mediaId: String) = api.autodraft(mediaId)
    override suspend fun quoteCard(mediaId: String, turnIds: List<String>) = api.quoteCard(mediaId, QuoteCardBody(turnIds))
    override suspend fun glossary(craftId: String?, q: String?) = api.glossary(craftId, q).items
    override suspend fun budget() = api.budget()
}

/** The `{detail: {code, message}}` of a refusal, or null when the body is something else. */
fun HttpException.transcriptError(json: Json): TranscriptErrorDetail? = runCatching {
    val body = response()?.errorBody()?.string() ?: return null
    json.decodeFromString(TranscriptError.serializer(), body).detail.takeIf { it.code.isNotBlank() }
}.getOrNull()

/** A downloaded file: the server's name for it (naming skill), when it sent one, and its bytes. */
class DownloadedFile(val name: String?, val bytes: ByteArray)

/** The ASCII `filename=` of a Content-Disposition header (the naming convention keeps it ASCII). */
fun contentDispositionName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val match = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header) ?: return null
    return match.groupValues[1].trim().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
}
