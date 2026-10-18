package com.fieldrepository.app.ui

import com.fieldrepository.app.data.BulkApproveResponse
import com.fieldrepository.app.data.PendingReviewDto
import com.fieldrepository.app.data.QueuedReviewDecision
import com.fieldrepository.app.data.RecordMediaReviewDto
import com.fieldrepository.app.data.ReviewReasonDto
import com.fieldrepository.app.data.ReviewSend
import com.fieldrepository.app.data.isUnanswered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * FILES APPROVED WITH THEIR RECORD (owner decision 2026-10-18), on the handset.
 *
 * Every approval says how many files go with it — the web's words (`e2e/review-media-cascade-unit.
 * spec.ts` holds the same sentences) — every file card on a record's page keeps its own Reject, and
 * an approval or a file's rejection made with no signal waits in the outbox and replays under the
 * Idempotency-Key it was first sent with.
 */
class ReviewMediaCascadeTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private fun source(path: String) = repoFile(path, "../$path").readText()
    private val main get() = source("android/app/src/main/java/com/fieldrepository/app/MainActivity.kt")
    private val repo get() = source("android/app/src/main/java/com/fieldrepository/app/data/FieldRepository.kt")

    @Test
    fun `the count reads like the web's, and says nothing when there are no files`() {
        assertEquals("Also approves 4 files.", ReviewFeedbackCopy.alsoApproves(4))
        assertEquals("Also approves 1 file.", ReviewFeedbackCopy.alsoApproves(1))
        assertNull(ReviewFeedbackCopy.alsoApproves(0))
        assertEquals("Approved, with 3 files.", ReviewFeedbackCopy.approvedReceipt(null, 3))
        assertEquals("Approved.", ReviewFeedbackCopy.approvedReceipt(null, 0))
        assertEquals("Approved Ramesh, with 1 file.", ReviewFeedbackCopy.approvedReceipt("Ramesh", 1))
        assertEquals(ReviewFeedbackCopy.QUEUED, ReviewFeedbackCopy.approvalOutcome("Ramesh", ReviewSend.Queued))
        assertEquals("Approved.", ReviewFeedbackCopy.approvalOutcome(null, ReviewSend.Sent(mediaApproved = 0)))
    }

    @Test
    fun `a file's Reject is labelled for a file and a fixable reason sends it back`() {
        val fixable = ReviewReasonDto("MISSING_DETAILS", "Missing details", fixable = true)
        assertEquals("Reject file", ReviewFeedbackCopy.fileRefusalLabel(null))
        assertEquals("Send for revision", ReviewFeedbackCopy.fileRefusalLabel(fixable))
    }

    @Test
    fun `the wire carries the counts, and an older server's answer still decodes`() {
        val row = json.decodeFromString<PendingReviewDto>("""{"recordType":"artisan","id":"a1","label":"Ramesh","pendingMediaCount":4}""")
        assertEquals(4, row.pendingMediaCount)
        assertEquals(0, json.decodeFromString<PendingReviewDto>("""{"recordType":"artisan","id":"a1","label":"R"}""").pendingMediaCount)
        assertEquals(5, json.decodeFromString<BulkApproveResponse>("""{"approved":2,"mediaApproved":5}""").mediaApproved)
        val media = json.decodeFromString<RecordMediaReviewDto>("""{"approvesWith":3,"rejectable":["m1","m2"]}""")
        assertEquals(3, media.approvesWith)
        assertEquals(listOf("m1", "m2"), media.rejectable)
    }

    @Test
    fun `a queued decision survives the outbox's round trip`() {
        val decision = QueuedReviewDecision("media", "m1", notes = "Blurred", reason = "DUPLICATE")
        assertEquals(decision, json.decodeFromString<QueuedReviewDecision>(json.encodeToString(decision)))
    }

    @Test
    fun `only a decision that got no answer waits in the outbox`() {
        fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))
        assertTrue(isUnanswered(java.net.UnknownHostException("no signal")))
        assertTrue(isUnanswered(java.net.SocketTimeoutException("timeout")))
        assertTrue(isUnanswered(http(504)))
        assertFalse(isUnanswered(http(403)))
        assertFalse(isUnanswered(http(422)))
    }

    @Test
    fun `approving and a file's rejection replay through the outbox with their key`() {
        assertTrue(repo.contains("\"review-approve\", \"review-reject\" -> {"))
        assertTrue(repo.contains("postReviewDecision(entry.type, decision, entry.idempotencyKey)"))
        assertTrue(repo.contains("kind = OutboxKind.ACTION, targetId = decision.recordId, idempotencyKey = key"))
        val api = source("android/app/src/main/java/com/fieldrepository/app/data/FieldRepositoryApi.kt")
        assertTrue(api.contains("@GET(\"review/{type}/{id}/media\")"))
        assertEquals(2, Regex("""@Header\("Idempotency-Key"\) idempotencyKey: String\? = null""").findAll(api).count())
    }

    @Test
    fun `the queue, the bulk bar and the record page ask before approving files`() {
        assertTrue(main.contains("if (item.pendingMediaCount > 0) confirmApprove = true else approve()"))
        assertTrue(main.contains("if (selected.values.sumOf { it.pendingMediaCount } > 0) confirmBulk = true else approveTicked()"))
        val feedback = source("android/app/src/main/java/com/fieldrepository/app/ui/ReviewFeedback.kt")
        assertTrue(feedback.contains("if (approvesWith > 0) confirmApprove = true else decide(\"approve\")"))
    }

    @Test
    fun `every file card on a record's page carries its own Reject`() {
        val card = main.substringAfter("private fun MediaWithTranscript(").substringBefore("val scope = rememberCoroutineScope()")
        assertTrue(card.contains("MediaRejectAction(mediaId = media.id"))
        assertTrue(main.contains("CompositionLocalProvider(LocalMediaReview provides mediaReview)"))
    }
}
