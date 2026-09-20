package com.fieldrepository.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * THE F/D ARTISAN-SET COLLISION, END TO END ON THE HANDSET — the refusal read for its holder, and
 * the fold that answers it.
 *
 * ── THE REPORT ──────────────────────────────────────────────────────────────────────────────────
 *
 * Two researchers recorded one artisan set as two sittings, each titled by the sections it covered —
 * "D Black Pottery" and an "F" one. The F sitting had missed an artisan. Ticking that artisan makes
 * F's set key equal D's, `@@unique([questionnaireId, artisanSetKey])` refuses the PATCH, and until
 * `POST …/merge-into/{targetId}` landed there was nothing the handset could do about it. Worse than
 * nothing, in fact: this client passed `it.message` to the error line, and Retrofit's message for a
 * refused request is the bare status line — so what the researcher read was "HTTP 409 Conflict"
 * while the server's own explanation sat unread in the response body.
 *
 * ── WHY IT IS ASSERTED THROUGH A REAL RETROFIT CLIENT ──────────────────────────────────────────
 *
 * The same argument `QuestionnaireScopeTest` makes about the workshop scope, and it applies harder
 * here because TWO artefacts are under test and neither is visible from a Kotlin signature:
 *
 *  * THE URL. `merge-into` is a path segment with a hyphen sitting between two `@Path` parameters.
 *    A `@POST` spelled `merge_into`, a parameter named `targetId` in the annotation but `target` in
 *    the signature, a leading slash that resets the base path — every one of those compiles and
 *    every one of them 404s at a researcher who has just agreed to fold their afternoon's work.
 *  * THE 409 BODY. `apiFailure` unpacks `detail` out of a real `HttpException`, whose error body
 *    Retrofit buffers and CONSUMES on the first read. A test that hand-built an `ApiFailure` would
 *    assert the parsing and skip the one-read constraint that shaped the whole type.
 *
 * So an OkHttp interceptor short-circuits every call: it records the URL Retrofit assembled and
 * answers with a canned body and status, with no socket opened and no new test dependency (this
 * module's JVM suite carries `junit:junit` alone). The base URL is a `.invalid` host so a broken
 * short-circuit fails with a DNS error rather than quietly reaching something real.
 */
class QuestionnaireMergeTest {

    private val sent = mutableListOf<Request>()

    /** A Retrofit client whose calls never leave the process, answering [status] with [body]. */
    private fun api(status: Int, body: String): FieldRepositoryApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                sent += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 409) "Conflict" else "OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        return Retrofit.Builder()
            .baseUrl("https://field-repository.invalid/api/")
            .client(client)
            .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FieldRepositoryApi::class.java)
    }

    /** See [unopenedTokenStore]: a plain JVM suite has no `Context` to build a real store with. */
    private fun repository(api: FieldRepositoryApi): FieldRepository =
        FieldRepository(api, unopenedTokenStore())

    private fun onlyRequest(): Request {
        assertEquals("expected exactly one request, got ${sent.map { it.url }}", 1, sent.size)
        return sent.first()
    }

    /** The throwable a refused call handed back — the real `HttpException`, body and all. */
    private fun refusal(status: Int, body: String, call: suspend (FieldRepository) -> Unit): Throwable {
        val repository = repository(api(status, body))
        return runBlocking { runCatching { call(repository) } }.exceptionOrNull()
            ?: throw AssertionError("the canned $status did not fail the call at all")
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  1. The fold reaches the route the backend actually declares
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the merge posts to the route named by both interviews`() {
        runBlocking { repository(api(200, SURVIVOR)).mergeQuestionnaireInterviewInto("iv-f", "iv-d") }

        val request = onlyRequest()
        assertEquals(
            "the path is `POST /questionnaire/interviews/{id}/merge-into/{targetId}` — the source " +
                "interview first, the survivor second. Reversed, this deletes the wrong sitting.",
            "/api/questionnaire/interviews/iv-f/merge-into/iv-d",
            request.url.encodedPath,
        )
        assertEquals("POST", request.method)
        assertTrue(
            "no query parameters: both interviews are named in the path and there is nothing else " +
                "to choose — ${request.url}",
            request.url.querySize == 0,
        )
        assertEquals(
            "no request body, which is what the route (path parameters and the session, no Pydantic " +
                "model) expects — Retrofit sends an empty one for a `@POST` with no `@Body`.",
            0L,
            request.body?.contentLength() ?: 0L,
        )
    }

    @Test
    fun `the survivor comes back hydrated, so the caller need not guess what the move produced`() {
        val survivor = runBlocking {
            repository(api(200, SURVIVOR)).mergeQuestionnaireInterviewInto("iv-f", "iv-d")
        }
        assertEquals("iv-d", survivor.id)
        assertEquals("D Black Pottery", survivor.title)
        assertEquals(
            "the survivor's artisans must decode — the point of the fold is that ONE row now covers " +
                "the whole set",
            listOf("art-1", "art-2"),
            survivor.artisans.map { it.artisanId },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  2. The refused save, read for the holder
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE ASSERTION THAT WOULD HAVE FAILED BEFORE THIS CHANGE, twice over: the sentence the
     * researcher reads was Retrofit's status line, and there was no way at all to learn WHICH
     * interview holds the set.
     */
    @Test
    fun `the artisan-set 409 yields its code, its sentence and the interview that holds the set`() {
        val failure = refusal(409, DUPLICATE_SET_409) {
            it.updateQuestionnaireInterview("iv-f", QuestionnaireInterviewUpdateRequest(title = "F"))
        }.apiFailure("Unable to save questionnaire")

        assertEquals(DUPLICATE_ARTISAN_SET_CODE, failure.code)
        assertTrue(
            "the server's own sentence must reach the screen. `HttpException.message` is \"HTTP 409 " +
                "Conflict\" and explains nothing: ${failure.message}",
            failure.message.startsWith("An interview already exists for this exact set of artisans"),
        )
        val holder = failure.artisanSetHolder()
        assertNotNull("the holder is what makes the offer possible at all", holder)
        assertEquals("iv-d", holder!!.id)
        assertEquals("D Black Pottery", holder.title)
    }

    /**
     * ONE READ, THREE ANSWERS. Retrofit buffers the error body and reading it CONSUMES the buffer,
     * so the code, the sentence and the holder cannot be fetched by three separate readers — whoever
     * ran second would silently see an empty body. That constraint is why [ApiFailure] carries the
     * parsed `detail` rather than exposing a second parser, and this pins it.
     */
    @Test
    fun `one read of the failure answers all three questions`() {
        val failure = refusal(409, DUPLICATE_SET_409) {
            it.updateQuestionnaireInterview("iv-f", QuestionnaireInterviewUpdateRequest(title = "F"))
        }.apiFailure("Unable to save questionnaire")

        assertTrue(
            "code, message and holder must all come out of the SAME ApiFailure — a second reader " +
                "over the same throwable gets an empty body and silently finds nothing.",
            failure.code != null && failure.message.isNotBlank() && failure.artisanSetHolder() != null,
        )
    }

    /**
     * A NULL HOLDER IS A REAL ANSWER and not a malformed body: the route sends it when the holder
     * cannot be identified (a concurrent delete, or a null set key, which is not deduped). The
     * sentence still has to render — that is what every screen did before the offer existed.
     */
    @Test
    fun `a holderless refusal still carries its sentence and simply offers no move`() {
        val failure = refusal(409, DUPLICATE_SET_409_NO_HOLDER) {
            it.updateQuestionnaireInterview("iv-f", QuestionnaireInterviewUpdateRequest(title = "F"))
        }.apiFailure("Unable to save questionnaire")

        assertEquals(DUPLICATE_ARTISAN_SET_CODE, failure.code)
        assertTrue(failure.message.startsWith("An interview already exists"))
        assertNull(
            "`holder: null` must not be read as an interview called \"null\" — there is nothing to " +
                "offer to move into, and the sentence is the whole answer",
            failure.artisanSetHolder(),
        )
    }

    /**
     * THE CODE IS THE DISCRIMINATOR, NOT THE SHAPE. Another 409 that happens to carry a `holder` key
     * must not be read as this one — the artisan identity conflict one table over uses the same
     * three-part body, and offering to fold an interview into an ARTISAN would be the worst kind of
     * confident nonsense.
     */
    @Test
    fun `a differently-coded 409 offers no fold however similar its body looks`() {
        val failure = refusal(409, OTHER_409) {
            it.updateQuestionnaireInterview("iv-f", QuestionnaireInterviewUpdateRequest(title = "F"))
        }.apiFailure("Unable to save questionnaire")

        assertEquals("some_other_conflict", failure.code)
        assertNull(failure.artisanSetHolder())
        assertTrue(
            "and its own sentence still reaches the screen",
            failure.message.contains("Something else was refused"),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  3. The refused fold, read for the questions that disagree
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE ROUTE REFUSES RATHER THAN PICKING A WINNER when both rows answer one question differently,
     * and it names every such question. Nothing is written, so this is recoverable — but only if the
     * client says WHICH questions. "3 answers disagree" sends a researcher back through twenty-two
     * sections by hand.
     *
     * ORDER IS THE SERVER'S (section code, then prompt): that is the order a questionnaire is read
     * in, and re-sorting here would put the list out of step with the paper it is checked against.
     */
    @Test
    fun `a refused fold names every question the two interviews answer differently`() {
        val failure = refusal(409, MERGE_CONFLICT_409) {
            it.mergeQuestionnaireInterviewInto("iv-f", "iv-d")
        }.apiFailure("Unable to move this interview")

        assertEquals(MERGE_ANSWER_CONFLICT_CODE, failure.code)
        assertTrue(
            "the server's sentence explains that NOTHING was moved, which is the fact the screen " +
                "most needs to carry: ${failure.message}",
            failure.message.contains("Nothing was moved"),
        )
        val conflicts = failure.mergeAnswerConflicts()
        assertEquals(
            "every named question survives the trip, in the order the server sorted them",
            listOf("q-d1", "q-f3"),
            conflicts.map { it.questionId },
        )
        assertEquals(listOf("D", "F"), conflicts.map { it.sectionCode })
        assertEquals(
            listOf("Which clay is used?", "How many looms are in the workshop?"),
            conflicts.map { it.prompt },
        )
    }

    /**
     * EVERY OTHER MERGE FAILURE YIELDS AN EMPTY LIST, so a caller can treat "empty" as "not this
     * kind of refusal" and fall back to the sentence. Nothing was moved in any of these cases
     * either, which is why falling back to a sentence is safe.
     */
    @Test
    fun `a merge refused for any other reason yields no question list`() {
        val failure = refusal(422, CROSS_INSTRUMENT_422) {
            it.mergeQuestionnaireInterviewInto("iv-f", "iv-other")
        }.apiFailure("Unable to move this interview")

        assertNull("a plain-string detail carries no code", failure.code)
        assertTrue(
            "the sentence is the answer here: ${failure.message}",
            failure.message.startsWith("These two interviews were taken on different questionnaires"),
        )
        assertTrue(
            "and there is no question list to draw: ${failure.mergeAnswerConflicts()}",
            failure.mergeAnswerConflicts().isEmpty(),
        )
    }

    private companion object {
        /** `public_encode` of the surviving interview, trimmed to what this suite reads. */
        const val SURVIVOR = """
            {"id":"iv-d","title":"D Black Pottery","status":"APPROVED",
             "artisans":[{"artisanId":"art-1"},{"artisanId":"art-2"}],
             "responses":[{"questionId":"q-d1","answerText":"Local black clay."}],
             "questionnaireId":"instrument-3"}
        """

        /** `duplicate_set_conflict`, holder and all. */
        const val DUPLICATE_SET_409 = """
            {"detail":{"code":"artisan_set_taken",
             "message":"An interview already exists for this exact set of artisans. There is a single shared entry per artisan set — open it to add or view answers instead of creating another.",
             "holder":{"id":"iv-d","title":"D Black Pottery"}}}
        """

        /** The same refusal when the holder could not be identified — a documented, real state. */
        const val DUPLICATE_SET_409_NO_HOLDER = """
            {"detail":{"code":"artisan_set_taken",
             "message":"An interview already exists for this exact set of artisans.",
             "holder":null}}
        """

        /** Same status, same three-part shape, different code. */
        const val OTHER_409 = """
            {"detail":{"code":"some_other_conflict","message":"Something else was refused.",
             "holder":{"id":"art-9","title":"Ram Kumar"}}}
        """

        /** `_MERGE_CONFLICT_CODE`, with the questions the route names. */
        const val MERGE_CONFLICT_409 = """
            {"detail":{"code":"merge_answer_conflict",
             "message":"Both interviews answer 2 question(s) differently. Nothing was moved. Reconcile these answers first — whichever wording is right has to be chosen by somebody who was there, not by the server.",
             "questions":[
               {"questionId":"q-d1","sectionCode":"D","prompt":"Which clay is used?","fields":["answerText"]},
               {"questionId":"q-f3","sectionCode":"F","prompt":"How many looms are in the workshop?","fields":["answerText","notes"]}]}}
        """

        /** A plain-string detail, which is what the cross-instrument refusal actually sends. */
        const val CROSS_INSTRUMENT_422 = """
            {"detail":"These two interviews were taken on different questionnaires. Answers cannot move between instruments — they would land under questions the sitting was never asked."}
        """
    }
}
