package com.fieldrepository.app.ui

import com.fieldrepository.app.data.BulkApproveResponse
import com.fieldrepository.app.data.DashboardStatsMine
import com.fieldrepository.app.data.LatestReviewAnswerDto
import com.fieldrepository.app.data.PendingReviewDto
import com.fieldrepository.app.data.ReturnedListDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE REVIEW FEEDBACK LOOP ON THE HANDSET (A1, A2, A3, B9): the copy the web's `lib/review.ts`
 * prints, word for word (`e2e/review-feedback-loop-unit.spec.ts` holds the same sentences), the wire
 * shapes, and source checks that the screens draw them.
 */
class ReviewFeedbackTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private fun source(path: String) = repoFile(path, "../$path").readText()

    @Test
    fun `the note's wording matches the web`() {
        assertEquals("Sent back by Dr Rao", ReviewFeedbackCopy.heading("NEEDS_REVISION", "Dr Rao"))
        assertEquals("Not accepted by A reviewer", ReviewFeedbackCopy.heading("REJECTED", " "))
        assertTrue(ReviewFeedbackCopy.nextStep("REJECTED").contains("reopen"))
        assertEquals("Sent back to you (3)", ReviewFeedbackCopy.title(3))
        assertEquals("2 records to fix · 1 not accepted", ReviewFeedbackCopy.note(2, 1))
        assertEquals("1 record to fix", ReviewFeedbackCopy.note(1, 0))
        assertEquals("3 records approved.", ReviewFeedbackCopy.bulkReceipt(3, 0))
        assertEquals("2 approved, 1 failed (still ticked).", ReviewFeedbackCopy.bulkReceipt(2, 1))
    }

    @Test
    fun `only reviewed kinds ask for a note`() {
        assertEquals("questionnaire", ReviewFeedbackCopy.recordTypeFor("QUESTIONNAIRE"))
        assertEquals("artisan", ReviewFeedbackCopy.recordTypeFor("ARTISAN"))
        assertNull(ReviewFeedbackCopy.recordTypeFor("MEDIA"))
        assertTrue(ReviewFeedbackCopy.isReturned("NEEDS_REVISION"))
        assertFalse(ReviewFeedbackCopy.isReturned("PENDING"))
    }

    @Test
    fun `the new answers decode, and an older server's answers still do`() {
        val latest = json.decodeFromString<LatestReviewAnswerDto>(
            """{"status":"NEEDS_REVISION","canReview":false,"needsAdminApproval":false,
               "review":{"status":"NEEDS_REVISION","notes":"Add the village","reasonLabel":"Missing details","reviewerName":"Dr Rao"}}"""
        )
        assertEquals("Add the village", latest.review?.notes)
        val mine = json.decodeFromString<ReturnedListDto>(
            """{"items":[{"recordType":"artisan","id":"a1","label":"Ramesh","status":"REJECTED"}],"total":1,
               "counts":{"NEEDS_REVISION":0,"REJECTED":1}}"""
        )
        assertEquals(1, mine.counts.rejected)
        val row = json.decodeFromString<PendingReviewDto>(
            """{"recordType":"artisan","id":"a1","label":"Ramesh","resubmitted":true,"previousReview":{"notes":"Fix it"}}"""
        )
        assertTrue(row.resubmitted)
        assertEquals("Fix it", row.previousReview?.notes)
        val old = json.decodeFromString<PendingReviewDto>("""{"recordType":"artisan","id":"a1","label":"Ramesh"}""")
        assertFalse(old.resubmitted)
        assertEquals(0, json.decodeFromString<DashboardStatsMine>("""{"totalArtisans":2}""").sentBack)
        val bulk = json.decodeFromString<BulkApproveResponse>(
            """{"results":[{"recordType":"artisan","id":"a1","outcome":"APPROVED"}],"approved":1,"failed":0}"""
        )
        assertEquals(1, bulk.approved)
    }

    @Test
    fun `the screens draw the note, the card and the bulk approval`() {
        val main = source("android/app/src/main/java/com/fieldrepository/app/MainActivity.kt")
        assertTrue(main.contains("ReviewNoteBanner(repository = repository, recordType = type, recordId = recordId)"))
        assertTrue(main.contains("SentBackCard("))
        assertTrue(main.contains("repository.bulkApprove(rows)"))
        assertTrue(main.contains("if (item.resubmitted)"))
        val api = source("android/app/src/main/java/com/fieldrepository/app/data/FieldRepositoryApi.kt")
        assertTrue(api.contains("@POST(\"review/bulk-approve\")"))
        assertTrue(api.contains("@GET(\"review/mine\")"))
    }
}
