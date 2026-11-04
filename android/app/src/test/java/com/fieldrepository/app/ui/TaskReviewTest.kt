package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.TaskDto
import com.fieldrepository.app.data.TaskSummaryDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReviewTest {

    private fun task(
        status: String = TASK_STATUS_OPEN,
        statusLabel: String = taskStatusLabel(status),
        isAwaitingReview: Boolean? = (status == TASK_STATUS_SUBMITTED),
        isOutstanding: Boolean? = (status in TASK_OUTSTANDING_STATUSES),
        effectivePercent: Int? = null,
        progressSource: String? = null,
        progressLabel: String? = null,
        progressCount: Int = 0,
        targetCount: Int? = null,
        derivedArtisanCount: Int? = null,
        derivedBreakdown: Map<String, Int> = emptyMap(),
        completedAt: String? = null
    ) = TaskDto(
        id = "task-1",
        title = "Record tools for the Bhuj artisans",
        status = status,
        statusLabel = statusLabel,
        isAwaitingReview = isAwaitingReview,
        isOutstanding = isOutstanding,
        effectivePercent = effectivePercent,
        progressSource = progressSource,
        progressLabel = progressLabel,
        progressCount = progressCount,
        targetCount = targetCount,
        derivedArtisanCount = derivedArtisanCount,
        derivedBreakdown = derivedBreakdown,
        completedAt = completedAt
    )

    private fun summary(
        taskCount: Int = 0,
        remainingCount: Int = 0,
        awaitingReviewCount: Int = 0,
        approvedCount: Int = 0,
        cancelledCount: Int = 0,
        overdueCount: Int = 0,
        dueSoonCount: Int = 0,
        nextDueAt: String? = null,
        percentComplete: Int = 0,
        measuredCount: Int = 0,
        derivationSkipped: Boolean = false,
        truncated: Boolean = false
    ) = TaskSummaryDto(
        taskCount = taskCount,
        remainingCount = remainingCount,
        awaitingReviewCount = awaitingReviewCount,
        outstandingCount = remainingCount + awaitingReviewCount,
        approvedCount = approvedCount,
        cancelledCount = cancelledCount,
        overdueCount = overdueCount,
        dueSoonCount = dueSoonCount,
        nextDueAt = nextDueAt,
        percentComplete = percentComplete,
        measuredCount = measuredCount,
        derivationSkipped = derivationSkipped,
        truncated = truncated
    )

    @Test
    fun `every status has the server's wording`() {
        assertEquals("To do", taskStatusLabel("OPEN"))
        assertEquals("In progress", taskStatusLabel("IN_PROGRESS"))
        assertEquals("Under review", taskStatusLabel("SUBMITTED"))
        assertEquals("Approved", taskStatusLabel("DONE"))
        assertEquals("Cancelled", taskStatusLabel("CANCELLED"))
    }

    @Test
    fun `the owner's phrase is the wording a researcher actually sees`() {
        assertEquals("Under review", taskStatusLabel(TASK_STATUS_SUBMITTED))
    }

    @Test
    fun `an unknown status is shown as itself rather than swallowed`() {
        assertEquals("ESCALATED", taskStatusLabel("ESCALATED"))
    }

    @Test
    fun `the server's label wins over this client's table`() {
        assertEquals("Awaiting sign-off", taskStatusText(task(statusLabel = "Awaiting sign-off")))
    }

    @Test
    fun `a payload with no label falls back rather than rendering blank`() {
        assertEquals("Under review", taskStatusText(task(status = TASK_STATUS_SUBMITTED, statusLabel = "")))
    }

    @Test
    fun `awaiting review is derived from the status when the server did not send the flag`() {
        assertTrue(taskAwaitingReview(task(status = TASK_STATUS_SUBMITTED, isAwaitingReview = null)))
        assertFalse(taskAwaitingReview(task(status = TASK_STATUS_IN_PROGRESS, isAwaitingReview = null)))
    }

    @Test
    fun `outstanding keeps submitted work on the list and drops withdrawn work`() {
        assertTrue(taskOutstanding(task(status = TASK_STATUS_OPEN, isOutstanding = null)))
        assertTrue(taskOutstanding(task(status = TASK_STATUS_IN_PROGRESS, isOutstanding = null)))
        assertTrue(taskOutstanding(task(status = TASK_STATUS_SUBMITTED, isOutstanding = null)))
        assertFalse(taskOutstanding(task(status = TASK_STATUS_DONE, isOutstanding = null)))
        assertFalse(taskOutstanding(task(status = TASK_STATUS_CANCELLED, isOutstanding = null)))
    }

    @Test
    fun `the server's booleans are preferred over the local derivation`() {
        assertTrue(taskAwaitingReview(task(status = "ESCALATED", isAwaitingReview = true)))
        assertFalse(taskOutstanding(task(status = TASK_STATUS_OPEN, isOutstanding = false)))
    }

    @Test
    fun `a submission offers approval first and sending back beside it`() {
        assertEquals(
            listOf(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS),
            taskOverrideTargets(TASK_STATUS_SUBMITTED)
        )
    }

    @Test
    fun `sending back lands on in progress rather than on to-do`() {
        assertEquals(TASK_STATUS_IN_PROGRESS, taskOverrideTargets(TASK_STATUS_SUBMITTED).last())
        assertFalse(taskOverrideTargets(TASK_STATUS_SUBMITTED).contains(TASK_STATUS_OPEN))
    }

    @Test
    fun `an approved task offers both ways back`() {
        assertEquals(
            listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_OPEN),
            taskOverrideTargets(TASK_STATUS_DONE)
        )
    }

    @Test
    fun `a withdrawn task offers nothing at all`() {
        assertEquals(emptyList<String>(), taskOverrideTargets(TASK_STATUS_CANCELLED))
        assertEquals(emptyList<String>(), taskOverrideTargets("ESCALATED"))
    }

    @Test
    fun `approving and marking done for them are worded differently`() {
        assertEquals("Approve", taskOverrideActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE))
        assertEquals("Mark done for them", taskOverrideActionLabel(TASK_STATUS_OPEN, TASK_STATUS_DONE))
        assertEquals("Mark done for them", taskOverrideActionLabel(TASK_STATUS_IN_PROGRESS, TASK_STATUS_DONE))
    }

    @Test
    fun `refusing a submission is called sending back, not rejecting`() {
        assertEquals("Send back", taskOverrideActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the ways back borrow the pill's own words`() {
        assertEquals(
            "Back to ${taskStatusLabel(TASK_STATUS_OPEN).lowercase()}",
            taskOverrideActionLabel(TASK_STATUS_DONE, TASK_STATUS_OPEN)
        )
        assertEquals("Back to in progress", taskOverrideActionLabel(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `every confirmation names the person it lands on`() {
        val moves = listOf(
            TASK_STATUS_SUBMITTED to TASK_STATUS_DONE,
            TASK_STATUS_SUBMITTED to TASK_STATUS_IN_PROGRESS,
            TASK_STATUS_OPEN to TASK_STATUS_DONE,
            TASK_STATUS_DONE to TASK_STATUS_OPEN
        )
        moves.forEach { (from, next) ->
            assertTrue(
                "$from -> $next did not name the assignee",
                taskOverrideConfirm(from, next, "Meera Joshi").body.contains("Meera Joshi")
            )
        }
    }

    @Test
    fun `a nameless assignee gets a stand-in rather than a sentence with no subject`() {
        val copy = taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE, null)
        assertTrue(copy.body.contains("this assignee"))
        assertFalse(copy.body.contains("  "))
        assertEquals(copy.body, taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE, "   ").body)
    }

    @Test
    fun `the approval note promises that approving late does not make them look late`() {
        val copy = taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE, "Meera Joshi")
        assertTrue(copy.note.contains("not today"))
        assertEquals("Approve", copy.confirmLabel)
    }

    @Test
    fun `the send-back note asks the admin to tell them why`() {
        val copy = taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS, "Meera Joshi")
        assertTrue(copy.note.contains("Let them know why"))
        assertEquals("Send back", copy.confirmLabel)
    }

    @Test
    fun `marking done for somebody who never claimed it says to ask them first`() {
        val copy = taskOverrideConfirm(TASK_STATUS_OPEN, TASK_STATUS_DONE, "Meera Joshi")
        assertTrue(copy.note.contains("ask them first"))
        assertEquals("Mark done for them", copy.confirmLabel)
    }

    @Test
    fun `the confirm button repeats the button that opened it`() {
        listOf(
            TASK_STATUS_SUBMITTED to TASK_STATUS_DONE,
            TASK_STATUS_SUBMITTED to TASK_STATUS_IN_PROGRESS,
            TASK_STATUS_OPEN to TASK_STATUS_DONE,
            TASK_STATUS_DONE to TASK_STATUS_IN_PROGRESS
        ).forEach { (from, next) ->
            assertEquals(
                taskOverrideActionLabel(from, next),
                taskOverrideConfirm(from, next, "Meera Joshi").confirmLabel
            )
        }
    }

    @Test
    fun `marking done writes SUBMITTED, never DONE`() {
        assertEquals(listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED), assigneeStatusChoices(TASK_STATUS_OPEN))
        assertEquals(listOf(TASK_STATUS_OPEN, TASK_STATUS_SUBMITTED), assigneeStatusChoices(TASK_STATUS_IN_PROGRESS))
        assertFalse(assigneeStatusChoices(TASK_STATUS_OPEN).contains(TASK_STATUS_DONE))
        assertFalse(assigneeStatusChoices(TASK_STATUS_IN_PROGRESS).contains(TASK_STATUS_DONE))
    }

    @Test
    fun `an approved task offers the assignee nothing`() {
        assertEquals(emptyList<String>(), assigneeStatusChoices(TASK_STATUS_DONE))
        assertEquals(emptyList<String>(), assigneeStatusChoices(TASK_STATUS_CANCELLED))
    }

    @Test
    fun `a submitted task offers the way back but no second way forward`() {
        assertEquals(listOf(TASK_STATUS_IN_PROGRESS), assigneeStatusChoices(TASK_STATUS_SUBMITTED))
        assertEquals("Withdraw", assigneeStatusActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the assignee's button still says mark done`() {
        assertEquals("Mark done", assigneeStatusActionLabel(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED))
        assertEquals("Start", assigneeStatusActionLabel(TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the confirmation after marking done explains why the task stayed put`() {
        val message = assigneeStatusConfirmation(TASK_STATUS_SUBMITTED)
        assertTrue(message.contains("approval"))
        assertTrue(message.contains("stays on your list"))
        assertFalse(message.contains("under review"))
    }

    @Test
    fun `the under-review notice appears only on work that has been handed in`() {
        assertNull(taskReviewNotice(task(status = TASK_STATUS_OPEN)))
        assertNull(taskReviewNotice(task(status = TASK_STATUS_IN_PROGRESS)))
        assertNull(taskReviewNotice(task(status = TASK_STATUS_DONE)))
        assertNull(taskReviewNotice(task(status = TASK_STATUS_CANCELLED)))
        assertNotNull(taskReviewNotice(task(status = TASK_STATUS_SUBMITTED)))
    }

    @Test
    fun `the notice answers all three questions a stranded card raises`() {
        val notice = taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!
        assertEquals("Handed in — under review", notice.title)
        assertTrue(notice.body.contains("You marked this done"))
        assertTrue(notice.body.contains("will approve it"))
        assertTrue(notice.body.contains("stays on your list until then"))
        assertEquals("Nothing more to do unless it comes back.", notice.action)
    }

    @Test
    fun `the notice is worded exactly as the web words it`() {
        assertEquals(
            "You marked this done. An admin or the master admin will approve it; it stays on your list until then.",
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!.body
        )
    }

    @Test
    fun `naming the admin turns an anonymous wait into somebody who can be asked`() {
        val named = taskReviewNotice(task(status = TASK_STATUS_SUBMITTED), "Dr Iyer")!!
        assertTrue(named.body.contains("Dr Iyer or another admin"))
        assertEquals(
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!.body,
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED), "   ")!!.body
        )
    }

    @Test
    fun `a task with nothing measurable draws no bar at all`() {
        val unmeasurable = task(
            status = TASK_STATUS_IN_PROGRESS,
            effectivePercent = null,
            progressSource = null,
            progressLabel = "No measurable target"
        )
        assertNull(taskBarPercent(unmeasurable))
        assertEquals("No measurable target", taskProgressCaption(unmeasurable))
    }

    @Test
    fun `zero percent and no percent are not the same answer`() {
        assertEquals(0, taskBarPercent(task(status = TASK_STATUS_CANCELLED, effectivePercent = 0)))
        assertNull(taskBarPercent(task(status = TASK_STATUS_IN_PROGRESS, effectivePercent = null)))
    }

    @Test
    fun `a pre-review payload keeps its old bar instead of losing it`() {
        val legacy = TaskDto(id = "t", status = "IN_PROGRESS", percentComplete = 40)
        assertEquals(40, taskBarPercent(legacy))
        assertEquals("0 reported", taskProgressCaption(legacy))
    }

    @Test
    fun `the caption is the server's sentence, because only it knows which scope produced the number`() {
        val measured = task(
            status = TASK_STATUS_IN_PROGRESS,
            effectivePercent = 25,
            progressSource = "derived",
            progressLabel = "6 of 24 artisan sections recorded"
        )
        assertEquals("6 of 24 artisan sections recorded", taskProgressCaption(measured))
        assertEquals(25, taskBarPercent(measured))
        assertTrue(taskProgressIsMeasured(measured))
    }

    @Test
    fun `a typed-in figure is never presented as a measured one`() {
        assertFalse(taskProgressIsMeasured(task(progressSource = "reported", effectivePercent = 80)))
        assertFalse(taskProgressIsMeasured(task(progressSource = "status", effectivePercent = 100)))
        assertFalse(taskProgressIsMeasured(task(progressSource = null)))
    }

    @Test
    fun `the caption falls back to the smallest true thing when the server sent none`() {
        assertEquals("4 of 10 reported", taskProgressCaption(task(progressCount = 4, targetCount = 10)))
        assertEquals("4 reported", taskProgressCaption(task(progressCount = 4, targetCount = null)))
    }

    @Test
    fun `a bar is refused outright when nothing behind it is real`() {
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 4, measuredCount = 0, percentComplete = 0))
        assertNull(bar.percent)
        assertTrue(bar.caption.contains("No progress yet"))
    }

    @Test
    fun `a measured zero IS drawn, because it says the counting is happening`() {
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 4, measuredCount = 4, percentComplete = 0))
        assertEquals(0, bar.percent)
        assertEquals("all", bar.measured)
    }

    @Test
    fun `an average above zero is drawn even with nothing measured`() {
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 2, awaitingReviewCount = 2, percentComplete = 50))
        assertEquals(50, bar.percent)
        assertEquals("none", bar.measured)
        assertTrue(bar.caption.contains("Based on what you've reported"))
    }

    @Test
    fun `a fully measured card says the bar moves on its own`() {
        val bar = taskSummaryBar(summary(taskCount = 6, remainingCount = 6, measuredCount = 6, percentComplete = 25))
        assertEquals("all", bar.measured)
        assertTrue(bar.caption.contains("All 6 counted from your records"))
        assertTrue(bar.caption.contains("updates as you record"))
    }

    @Test
    fun `a partly measured card says how much of itself was counted`() {
        val bar = taskSummaryBar(summary(taskCount = 6, remainingCount = 6, measuredCount = 2, percentComplete = 30))
        assertEquals("some", bar.measured)
        assertTrue(bar.caption.contains("2 of 6 counted from records"))
        assertTrue(bar.caption.contains("the rest from your reports"))
    }

    @Test
    fun `withdrawn tasks are out of the denominator, so the card cannot disagree with its own bar`() {
        val bar = taskSummaryBar(summary(taskCount = 6, cancelledCount = 3, measuredCount = 3, percentComplete = 40))
        assertEquals("all", bar.measured)
        assertTrue(bar.caption.contains("All 3 counted"))
    }

    @Test
    fun `a list that is entirely withdrawn says so instead of showing zero percent`() {
        val bar = taskSummaryBar(summary(taskCount = 3, cancelledCount = 3))
        assertNull(bar.percent)
        assertTrue(bar.caption.contains("were withdrawn"))
    }

    @Test
    fun `an empty list says nothing has been assigned rather than nothing has been done`() {
        val bar = taskSummaryBar(summary())
        assertNull(bar.percent)
        assertEquals("No tasks have been assigned to you yet.", bar.caption)
    }

    @Test
    fun `a declined derivation is a caveat, not a silently reported bar`() {
        val bar = taskSummaryBar(
            summary(taskCount = 400, remainingCount = 400, percentComplete = 20, derivationSkipped = true)
        )
        assertTrue(bar.caveats.any { it.contains("Too many tasks to count from records") })
    }

    @Test
    fun `a truncated scan is published as a floor, not as a total`() {
        assertTrue(taskSummaryBar(summary(taskCount = 5, remainingCount = 5, percentComplete = 10)).caveats.isEmpty())
        val bar = taskSummaryBar(
            summary(taskCount = 2000, remainingCount = 900, percentComplete = 10, truncated = true)
        )
        assertTrue(bar.caveats.any { it.contains("may be incomplete") })
    }

    @Test
    fun `the bar is clamped rather than trusted`() {
        assertEquals(100, taskSummaryBar(summary(taskCount = 1, remainingCount = 1, percentComplete = 140)).percent)
    }

    @Test
    fun `a list with no deadlines gets no cheerful zero`() {
        assertNull(taskDueSentence(summary(taskCount = 3, remainingCount = 3)))
    }

    @Test
    fun `lateness leads, because it is the only emergency on the card`() {
        val sentence = taskDueSentence(
            summary(
                taskCount = 9,
                remainingCount = 3,
                overdueCount = 2,
                dueSoonCount = 1,
                nextDueAt = "2026-09-20T00:00:00Z"
            )
        )!!
        assertTrue(sentence.startsWith("2 tasks are past their due date"))
        assertTrue(sentence.indexOf("past their due date") < sentence.indexOf("due within the next two days"))
        assertTrue(sentence.contains("next due 20 Sep"))
        assertTrue(sentence.endsWith("."))
    }

    @Test
    fun `one overdue task is described in the singular, pronoun included`() {
        assertEquals("1 task is past its due date.", taskDueSentence(summary(taskCount = 1, overdueCount = 1)))
    }

    @Test
    fun `the under-review sentence is worded exactly as the web words it`() {
        assertEquals(
            "1 task awaiting approval.",
            taskAwaitingReviewSentence(1)
        )
        assertEquals(
            "3 tasks awaiting approval.",
            taskAwaitingReviewSentence(3)
        )
    }

    @Test
    fun `there is no sentence when there is nothing waiting`() {
        assertNull(taskAwaitingReviewSentence(0))
        assertNull(taskAwaitingReviewSentence(-1))
    }

    private val json: Json = ApiClient.json

    @Test
    fun `a submitted task decodes with every review field intact`() {
        val payload = """
            {
              "id": "task-9",
              "title": "Questionnaire sections C and D",
              "status": "SUBMITTED",
              "statusLabel": "Under review",
              "isAwaitingReview": true,
              "isOutstanding": true,
              "effectivePercent": 25,
              "progressSource": "derived",
              "progressLabel": "6 of 24 artisan sections recorded",
              "percentComplete": 100,
              "derivedCount": 6,
              "derivedTarget": 24,
              "derivedPercent": 25,
              "derivedArtisanCount": 12,
              "derivedBreakdown": {"pairs": 6, "unlinkedSections": 3},
              "progressCount": 0,
              "completedAt": "2026-09-10T08:15:00Z"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(TaskDto.serializer(), payload)

        assertEquals("SUBMITTED", decoded.status)
        assertEquals("Under review", decoded.statusLabel)
        assertEquals(true, decoded.isAwaitingReview)
        assertEquals(true, decoded.isOutstanding)
        assertEquals(25, decoded.effectivePercent)
        assertEquals("derived", decoded.progressSource)
        assertEquals("6 of 24 artisan sections recorded", decoded.progressLabel)
        assertEquals(25, decoded.derivedPercent)
        assertEquals(12, decoded.derivedArtisanCount)
        assertEquals(3, decoded.derivedBreakdown["unlinkedSections"])

        assertEquals("Under review", taskStatusText(decoded))
        assertTrue(taskAwaitingReview(decoded))
        assertTrue(taskOutstanding(decoded))
        assertEquals(25, taskBarPercent(decoded))
        assertTrue(taskProgressIsMeasured(decoded))
        assertNotNull(taskReviewNotice(decoded))
    }

    @Test
    fun `a server that has never heard of the review state still decodes`() {
        val payload = """
            {"id":"task-1","title":"Record tools","status":"IN_PROGRESS","progressCount":4,
             "targetCount":10,"percentComplete":40}
        """.trimIndent()
        val decoded = json.decodeFromString(TaskDto.serializer(), payload)

        assertEquals("", decoded.statusLabel)
        assertNull(decoded.isAwaitingReview)
        assertNull(decoded.isOutstanding)
        assertTrue(taskOutstanding(decoded))
        assertFalse(taskAwaitingReview(decoded))
        assertEquals("In progress", taskStatusText(decoded))
        assertEquals(40, taskBarPercent(decoded))
    }

    @Test
    fun `the summary card decodes from the endpoint's own shape`() {
        val payload = """
            {
              "assignee": {"id":"u1","name":"Meera Joshi","role":"RESEARCHER","roleLabel":"Researcher"},
              "workshopId": "w1",
              "taskCount": 7,
              "statusCounts": {"OPEN":2,"IN_PROGRESS":1,"SUBMITTED":3,"DONE":1,"CANCELLED":0},
              "remainingCount": 3,
              "awaitingReviewCount": 3,
              "outstandingCount": 6,
              "approvedCount": 1,
              "cancelledCount": 0,
              "overdueCount": 1,
              "dueSoonCount": 2,
              "nextDueAt": "2026-09-20T00:00:00Z",
              "percentComplete": 48,
              "measuredCount": 4,
              "derivationSkipped": false,
              "truncated": false
            }
        """.trimIndent()
        val decoded = json.decodeFromString(TaskSummaryDto.serializer(), payload)

        assertEquals(3, decoded.remainingCount)
        assertEquals(3, decoded.awaitingReviewCount)
        assertEquals(6, decoded.outstandingCount)
        assertEquals(3, decoded.statusCounts["SUBMITTED"])
        assertEquals("Meera Joshi", decoded.assignee?.name)

        val bar = taskSummaryBar(decoded)
        assertEquals(48, bar.percent)
        assertEquals("some", bar.measured)
        assertTrue(bar.caption.contains("4 of 7 counted from records"))
        assertTrue(bar.caveats.isEmpty())
        val due = taskDueSentence(decoded)!!
        assertTrue(due.startsWith("1 task is past its due date · 2 due within the next two days · next due 20 Sep"))
        assertTrue(due.endsWith("2026."))
        assertTrue(taskAwaitingReviewSentence(decoded.awaitingReviewCount)!!.startsWith("3 tasks awaiting approval"))
    }

    @Test
    fun `a batch that nobody has submitted in still answers the SUBMITTED question`() {
        val payload = """
            {"assignee":null,"taskCount":2,
             "statusCounts":{"OPEN":2,"IN_PROGRESS":0,"SUBMITTED":0,"DONE":0,"CANCELLED":0},
             "remainingCount":2,"outstandingCount":2}
        """.trimIndent()
        val decoded = json.decodeFromString(TaskSummaryDto.serializer(), payload)
        assertEquals(0, decoded.statusCounts["SUBMITTED"])
        assertEquals(0, decoded.awaitingReviewCount)
        assertNull(taskAwaitingReviewSentence(decoded.awaitingReviewCount))
    }
}
