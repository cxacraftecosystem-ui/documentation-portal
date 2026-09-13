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

/**
 * The review state, the assignee's progress card, and the admin's approve/send-back control —
 * asserted rather than eyeballed.
 *
 * WHY THIS FILE EXISTS, AND WHY IT IS MOSTLY ABOUT WORDS. Reproducing any of this by hand means two
 * accounts, a real assignment, a real submission and then reading a phone screen carefully — and
 * every failure mode below is SILENT. That is not a figure of speech here: Kotlin decodes this API
 * with `ignoreUnknownKeys = true`, so a status this client has never heard of does not throw, does
 * not warn and does not log. It falls through the nearest `else ->` branch and renders as whatever
 * lives there. When `SUBMITTED` was added server-side, the branch it would have fallen through to
 * was the one OPEN uses — so a researcher who had already handed work in would have been shown a
 * to-do item and told, by omission, to do it all again. Nothing anywhere would have gone red.
 *
 * So the decisions were lifted out of the composables into pure functions, where one assertion can
 * hold them. If somebody folds `taskOverrideTargets` back into an `if` inside a composable, these
 * tests stop compiling — which is the point.
 *
 * THE WEB TWIN OF EVERY STRING BELOW IS `frontend/components/tasks/` (`TaskPrimitives.tsx`,
 * `AssigneeProgressCard.tsx`, `MyTaskCard.tsx`), and the SOURCE of the status wording is
 * `backend/app/api/routes/tasks.py:129` (`STATUS_LABELS`). Change one, change all three: a
 * researcher told "Under review" on a phone and "Submitted" on a laptop is looking at two features,
 * not one.
 */
class TaskReviewTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * One task as the server actually serialises it, with every field of the review contract
     * present. Defaults describe an ordinary open task; each test overrides only what it is about.
     */
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

    // -----------------------------------------------------------------------
    // The status vocabulary — the contract with the server and with the web
    // -----------------------------------------------------------------------

    @Test
    fun `every status has the server's wording`() {
        // Mirrors backend STATUS_LABELS exactly. "Approved" and not "Done", because after the review
        // step DONE carries new information: a SECOND person agreed. "To do" and not "Open" for the
        // matching reason — OPEN is now specifically the assignee's own queue.
        assertEquals("To do", taskStatusLabel("OPEN"))
        assertEquals("In progress", taskStatusLabel("IN_PROGRESS"))
        assertEquals("Under review", taskStatusLabel("SUBMITTED"))
        assertEquals("Approved", taskStatusLabel("DONE"))
        assertEquals("Cancelled", taskStatusLabel("CANCELLED"))
    }

    @Test
    fun `the owner's phrase is the wording a researcher actually sees`() {
        // The owner asked for "tell them that it is currently under review". The server carries the
        // phrase so three hand-written clients cannot each invent their own; this pins the Android
        // end of that.
        assertEquals("Under review", taskStatusLabel(TASK_STATUS_SUBMITTED))
    }

    @Test
    fun `an unknown status is shown as itself rather than swallowed`() {
        // The server grew a fifth state; it may grow a sixth before this APK is rebuilt. A raw
        // "ESCALATED" on screen is ugly and honest. A fallback of "To do" would be neither — it
        // would put finished work back on somebody's list without a trace.
        assertEquals("ESCALATED", taskStatusLabel("ESCALATED"))
    }

    @Test
    fun `the server's label wins over this client's table`() {
        // If the server rewords a state, the phone follows without an APK. The local table is the
        // net, not the source.
        assertEquals("Awaiting sign-off", taskStatusText(task(statusLabel = "Awaiting sign-off")))
    }

    @Test
    fun `a payload with no label falls back rather than rendering blank`() {
        assertEquals("Under review", taskStatusText(task(status = TASK_STATUS_SUBMITTED, statusLabel = "")))
    }

    // -----------------------------------------------------------------------
    // The two booleans, and why they are nullable
    // -----------------------------------------------------------------------

    @Test
    fun `awaiting review is derived from the status when the server did not send the flag`() {
        // A non-null `false` default would make "this server is older than the review state"
        // indistinguishable from "this task is not awaiting review" — and would hand a submitted
        // task its "Mark done" button back, letting a researcher re-submit work already in a queue.
        assertTrue(taskAwaitingReview(task(status = TASK_STATUS_SUBMITTED, isAwaitingReview = null)))
        assertFalse(taskAwaitingReview(task(status = TASK_STATUS_IN_PROGRESS, isAwaitingReview = null)))
    }

    @Test
    fun `outstanding keeps submitted work on the list and drops withdrawn work`() {
        // The owner's "for it to not pop up next time", read backwards, IS the requirement: it must
        // go on popping up until somebody approves it.
        assertTrue(taskOutstanding(task(status = TASK_STATUS_OPEN, isOutstanding = null)))
        assertTrue(taskOutstanding(task(status = TASK_STATUS_IN_PROGRESS, isOutstanding = null)))
        assertTrue(taskOutstanding(task(status = TASK_STATUS_SUBMITTED, isOutstanding = null)))
        assertFalse(taskOutstanding(task(status = TASK_STATUS_DONE, isOutstanding = null)))
        // The reason `status != "DONE"` is the wrong filter: it keeps this one.
        assertFalse(taskOutstanding(task(status = TASK_STATUS_CANCELLED, isOutstanding = null)))
    }

    @Test
    fun `the server's booleans are preferred over the local derivation`() {
        assertTrue(taskAwaitingReview(task(status = "ESCALATED", isAwaitingReview = true)))
        assertFalse(taskOutstanding(task(status = TASK_STATUS_OPEN, isOutstanding = false)))
    }

    // -----------------------------------------------------------------------
    // The admin's decision
    // -----------------------------------------------------------------------

    @Test
    fun `a submission offers approval first and sending back beside it`() {
        // Both answers, in this order: approving is the expected outcome, and a reviewer who can
        // only approve is not reviewing. Matches the web's `overrideTargets`.
        assertEquals(
            listOf(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS),
            taskOverrideTargets(TASK_STATUS_SUBMITTED)
        )
    }

    @Test
    fun `sending back lands on in progress rather than on to-do`() {
        // The work demonstrably started — they handed it in — so restoring "not started" would be
        // the board contradicting a fact it is looking at, and would tell every rollup counting
        // openCount the same untruth.
        assertEquals(TASK_STATUS_IN_PROGRESS, taskOverrideTargets(TASK_STATUS_SUBMITTED).last())
        assertFalse(taskOverrideTargets(TASK_STATUS_SUBMITTED).contains(TASK_STATUS_OPEN))
    }

    @Test
    fun `an approved task offers both ways back`() {
        // Here "never started" and "half done" ARE genuinely different answers about somebody's
        // week, and an undo that could only restore OPEN would erase that difference on every task
        // it touched.
        assertEquals(
            listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_OPEN),
            taskOverrideTargets(TASK_STATUS_DONE)
        )
    }

    @Test
    fun `a withdrawn task offers nothing at all`() {
        // The server WOULD accept a status write here — update_task's manager branch takes any
        // value — so nothing but this empty list keeps withdrawal off the accountability board,
        // where it would sit as a third small button beside two reversible ones.
        assertEquals(emptyList<String>(), taskOverrideTargets(TASK_STATUS_CANCELLED))
        assertEquals(emptyList<String>(), taskOverrideTargets("ESCALATED"))
    }

    @Test
    fun `approving and marking done for them are worded differently`() {
        // The same database write, two different human acts. From SUBMITTED the researcher has
        // already claimed it and the admin is AGREEING. From OPEN nobody has claimed anything and
        // the admin is declaring it finished over their head — which is what "for them" admits.
        // One label for both would tell an approver they are overruling the researcher at the exact
        // moment they are backing them up.
        assertEquals("Approve", taskOverrideActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE))
        assertEquals("Mark done for them", taskOverrideActionLabel(TASK_STATUS_OPEN, TASK_STATUS_DONE))
        assertEquals("Mark done for them", taskOverrideActionLabel(TASK_STATUS_IN_PROGRESS, TASK_STATUS_DONE))
    }

    @Test
    fun `refusing a submission is called sending back, not rejecting`() {
        // "Send back", not "Reject": the work is not refused, it is returned for more of it, and the
        // row it writes is plain IN_PROGRESS. Nor "Send for revision", which is this app's REVIEW
        // LADDER's wording and carries a promise this screen cannot keep — that one takes mandatory
        // comments, and an assigned task has no column to put them in.
        assertEquals("Send back", taskOverrideActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the ways back borrow the pill's own words`() {
        // These buttons sit inches below the status pill for the very task they act on. A button
        // reading "Reopen as not started" beside a pill reading "To do" is two names for one state
        // on one row — so the label is DERIVED from the pill's rather than written beside it.
        assertEquals(
            "Back to ${taskStatusLabel(TASK_STATUS_OPEN).lowercase()}",
            taskOverrideActionLabel(TASK_STATUS_DONE, TASK_STATUS_OPEN)
        )
        assertEquals("Back to in progress", taskOverrideActionLabel(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `every confirmation names the person it lands on`() {
        // An override is the one act on this board whose effect reaches somebody not in the room.
        // "Mark this task done?" gives a tired admin no way to notice they are on the wrong row.
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
        // The server stamps completedAt at the SUBMISSION and never re-stamps it on approval, so
        // `completedAt > dueAt` stays a fact about the doer. An approver has to be told that, or a
        // conscientious one delays clicking to avoid "marking them late", which is the opposite of
        // what the delay does.
        val copy = taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_DONE, "Meera Joshi")
        assertTrue(copy.note.contains("not today"))
        assertEquals("Approve", copy.confirmLabel)
    }

    @Test
    fun `the send-back note says the researcher finds out from the app and nowhere else`() {
        // Nothing on the wire carries a message with a send-back. An admin who assumes one is sent
        // leaves somebody in the field re-reading a task they thought was finished.
        val copy = taskOverrideConfirm(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS, "Meera Joshi")
        assertTrue(copy.note.contains("tell them why yourself"))
        assertEquals("Send back", copy.confirmLabel)
    }

    @Test
    fun `marking done for somebody who never claimed it says the row keeps no trace`() {
        // `test_an_override_leaves_no_trace_of_who_performed_it` pins this server-side: the row is
        // identical to one the assignee wrote themselves. The only place that can be said is here,
        // before the press.
        val copy = taskOverrideConfirm(TASK_STATUS_OPEN, TASK_STATUS_DONE, "Meera Joshi")
        assertTrue(copy.note.contains("no trace"))
        assertEquals("Mark done for them", copy.confirmLabel)
    }

    @Test
    fun `the confirm button repeats the button that opened it`() {
        // A dialog whose confirm reads differently from the button pressed to reach it makes a
        // reader wonder whether they opened the wrong one.
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

    // -----------------------------------------------------------------------
    // The assignee's side
    // -----------------------------------------------------------------------

    @Test
    fun `marking done writes SUBMITTED, never DONE`() {
        // The owner's rule: an admin has to approve it. The server rewrites an assignee's DONE to
        // SUBMITTED rather than refusing it (so older field builds keep working), but sending the
        // true word means the request and the response agree about what happened.
        assertEquals(listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED), assigneeStatusChoices(TASK_STATUS_OPEN))
        assertEquals(listOf(TASK_STATUS_OPEN, TASK_STATUS_SUBMITTED), assigneeStatusChoices(TASK_STATUS_IN_PROGRESS))
        assertFalse(assigneeStatusChoices(TASK_STATUS_OPEN).contains(TASK_STATUS_DONE))
        assertFalse(assigneeStatusChoices(TASK_STATUS_IN_PROGRESS).contains(TASK_STATUS_DONE))
    }

    @Test
    fun `an approved task offers the assignee nothing`() {
        // The server answers this with 403 "Only the task creator or an admin can reopen an approved
        // task". Without that clause the person whose work was approved could PATCH the approval
        // away, which would make the whole review step decorative — and a button that produces a red
        // banner has already told somebody they did something wrong when they were merely offered
        // something they were never allowed.
        assertEquals(emptyList<String>(), assigneeStatusChoices(TASK_STATUS_DONE))
        assertEquals(emptyList<String>(), assigneeStatusChoices(TASK_STATUS_CANCELLED))
    }

    @Test
    fun `a submitted task offers the way back but no second way forward`() {
        // Withdrawing your own submission is legitimate — you spotted a gap before the admin did.
        // A second "Mark done" would re-send something already in the queue.
        assertEquals(listOf(TASK_STATUS_IN_PROGRESS), assigneeStatusChoices(TASK_STATUS_SUBMITTED))
        assertEquals("Withdraw", assigneeStatusActionLabel(TASK_STATUS_SUBMITTED, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the assignee's button still says mark done`() {
        // It writes SUBMITTED, and it still reads "Mark done". That is what the researcher means, it
        // is the web's wording and the wording on every build already in the field. The review step
        // is the ADMIN'S hurdle and belongs in the notice beside the button, not on it, where it
        // would read as friction before the work is even claimed.
        assertEquals("Mark done", assigneeStatusActionLabel(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED))
        assertEquals("Start", assigneeStatusActionLabel(TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS))
    }

    @Test
    fun `the confirmation after marking done explains why the task stayed put`() {
        // The generic "Marked ${label}" would render as "Marked under review", which reads like the
        // app deciding something rather than like the researcher's own act having a consequence —
        // and says nothing about the card still being there afterwards.
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
        // "Did it work?" (yes, recorded), "whose problem is it now?" (an admin's), "is there
        // anything left for me to do?" (no). Without all three, the honest reading of a task that
        // stayed put after being marked done is "the app did not save it" — and the reasonable
        // response to that is to press the button again, then do the work again.
        val notice = taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!
        assertEquals("Handed in — under review", notice.title)
        assertTrue(notice.body.contains("it has been recorded"))
        assertTrue(notice.body.contains("has to approve it"))
        assertTrue(notice.body.contains("stays on your list until then"))
        assertEquals("Nothing more is needed from you unless it comes back.", notice.action)
    }

    @Test
    fun `the notice is worded exactly as the web words it`() {
        // `frontend/components/tasks/TaskPrimitives.tsx:reviewNoticeCopy`. This is the sentence that
        // decides whether a researcher trusts the button, and the person who would find a divergence
        // is the one who works a workshop on the handset and writes it up on the laptop that evening.
        assertEquals(
            "You marked this done and it has been recorded. An admin or the master admin has to " +
                "approve it before it is finished, so it stays on your list until then.",
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!.body
        )
    }

    @Test
    fun `naming the admin turns an anonymous wait into somebody who can be asked`() {
        val named = taskReviewNotice(task(status = TASK_STATUS_SUBMITTED), "Dr Iyer")!!
        assertTrue(named.body.contains("Dr Iyer or another admin"))
        // Blank is not a name. A stray space would otherwise produce " or another admin".
        assertEquals(
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED))!!.body,
            taskReviewNotice(task(status = TASK_STATUS_SUBMITTED), "   ")!!.body
        )
    }

    // -----------------------------------------------------------------------
    // The one progress number
    // -----------------------------------------------------------------------

    @Test
    fun `a task with nothing measurable draws no bar at all`() {
        // "Food, and collect the TA's bank details" has no record types, no sections and no quota.
        // A bar at 0% beside somebody's name asserts they have produced nothing, which is a claim
        // about them; the absent bar asserts only that there is nothing here to count.
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
        // Detected by the ABSENT LABEL, not by the absent percentage: every server that sends
        // effectivePercent also sends statusLabel, so a blank label is the only available evidence
        // that a null percentage means "not sent" rather than "not measurable". Reading the null
        // percentage as the signal would delete the bar from every task on an older server.
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
        // This is the owner's "should progress automatically as they record for more and more
        // artisans", made checkable. A bar that moved because somebody typed 10 into a box and a bar
        // that moved because ten artisans were recorded look identical on screen.
        assertFalse(taskProgressIsMeasured(task(progressSource = "reported", effectivePercent = 80)))
        assertFalse(taskProgressIsMeasured(task(progressSource = "status", effectivePercent = 100)))
        assertFalse(taskProgressIsMeasured(task(progressSource = null)))
    }

    @Test
    fun `the caption falls back to the smallest true thing when the server sent none`() {
        assertEquals("4 of 10 reported", taskProgressCaption(task(progressCount = 4, targetCount = 10)))
        assertEquals("4 reported", taskProgressCaption(task(progressCount = 4, targetCount = null)))
    }

    // -----------------------------------------------------------------------
    // The full-width card — a port of the web's AssigneeProgressCard, so these
    // assertions are simultaneously a spec and a parity check.
    // -----------------------------------------------------------------------

    @Test
    fun `a bar is refused outright when nothing behind it is real`() {
        // THE HONESTY RULE. `percentComplete` arrives already averaged, with 0 stood in for every
        // task the server could not measure. "Every task I can count is at zero" and "nothing you
        // have is countable" are the same empty rectangle once drawn, and only the first is a fact
        // about the person. So: no bar, and a sentence saying why.
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 4, measuredCount = 0, percentComplete = 0))
        assertNull(bar.percent)
        assertTrue(bar.caption.contains("no honest bar to draw"))
        assertTrue(bar.caption.contains("The counts above are exact"))
    }

    @Test
    fun `a measured zero IS drawn, because it says the counting is happening`() {
        // The distinction the rule above turns on: measuredCount > 0 means the repository looked and
        // found nothing, which is a real measurement and worth a real empty bar.
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 4, measuredCount = 4, percentComplete = 0))
        assertEquals(0, bar.percent)
        assertEquals("all", bar.measured)
    }

    @Test
    fun `an average above zero is drawn even with nothing measured`() {
        // It cannot be above zero without a reported figure, a submission or an approval under it.
        val bar = taskSummaryBar(summary(taskCount = 4, remainingCount = 2, awaitingReviewCount = 2, percentComplete = 50))
        assertEquals(50, bar.percent)
        assertEquals("none", bar.measured)
        assertTrue(bar.caption.contains("Based on what you have reported and handed in"))
    }

    @Test
    fun `a fully measured card says the bar moves on its own`() {
        // The owner's "should progress automatically as they record for more and more artisans",
        // said to the person it is a promise to.
        val bar = taskSummaryBar(summary(taskCount = 6, remainingCount = 6, measuredCount = 6, percentComplete = 25))
        assertEquals("all", bar.measured)
        assertTrue(bar.caption.contains("All 6 of your tasks are counted from the repository"))
        assertTrue(bar.caption.contains("moves on its own as you record"))
    }

    @Test
    fun `a partly measured card says how much of itself was counted`() {
        val bar = taskSummaryBar(summary(taskCount = 6, remainingCount = 6, measuredCount = 2, percentComplete = 30))
        assertEquals("some", bar.measured)
        assertTrue(bar.caption.contains("2 of 6 tasks counted from the repository"))
        assertTrue(bar.caption.contains("the rest from what you reported or handed in"))
    }

    @Test
    fun `withdrawn tasks are out of the denominator, so the card cannot disagree with its own bar`() {
        // The server already keeps CANCELLED out of the average. Counting them here would put
        // "3 of 6 counted" beside a bar averaged over three.
        val bar = taskSummaryBar(summary(taskCount = 6, cancelledCount = 3, measuredCount = 3, percentComplete = 40))
        assertEquals("all", bar.measured)
        assertTrue(bar.caption.contains("All 3 of your tasks"))
    }

    @Test
    fun `a list that is entirely withdrawn says so instead of showing zero percent`() {
        val bar = taskSummaryBar(summary(taskCount = 3, cancelledCount = 3))
        assertNull(bar.percent)
        assertTrue(bar.caption.contains("has been withdrawn"))
    }

    @Test
    fun `an empty list says nothing has been assigned rather than nothing has been done`() {
        val bar = taskSummaryBar(summary())
        assertNull(bar.percent)
        assertEquals("No tasks have been assigned to you yet.", bar.caption)
    }

    @Test
    fun `a declined derivation is a caveat, not a silently reported bar`() {
        // The server refused to count records for a list this long. Not a failure and not hidden:
        // the alternative is a bar that looks measured and is not.
        val bar = taskSummaryBar(
            summary(taskCount = 400, remainingCount = 400, percentComplete = 20, derivationSkipped = true)
        )
        assertTrue(bar.caveats.any { it.contains("nothing here was counted from records") })
    }

    @Test
    fun `a truncated scan is published as a floor, not as a total`() {
        assertTrue(taskSummaryBar(summary(taskCount = 5, remainingCount = 5, percentComplete = 10)).caveats.isEmpty())
        val bar = taskSummaryBar(
            summary(taskCount = 2000, remainingCount = 900, percentComplete = 10, truncated = true)
        )
        assertTrue(bar.caveats.any { it.contains("a floor rather than a total") })
    }

    @Test
    fun `the bar is clamped rather than trusted`() {
        assertEquals(100, taskSummaryBar(summary(taskCount = 1, remainingCount = 1, percentComplete = 140)).percent)
    }

    // -----------------------------------------------------------------------
    // The deadline line
    // -----------------------------------------------------------------------

    @Test
    fun `a list with no deadlines gets no cheerful zero`() {
        // "0 overdue" is not news. An empty sentence must be genuinely empty so the caller draws no
        // line at all rather than an empty one with its own spacing.
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
        // Month abbreviation only, not the full formatted date: `formatDate` renders through the
        // JVM's default locale, which spells September "Sept" on a modern CLDR and "Sep" on an
        // older one. Pinning the whole string here would make this test fail on somebody's machine
        // for a reason that has nothing to do with tasks.
        assertTrue(sentence.contains("next due 20 Sep"))
        assertTrue(sentence.endsWith("."))
    }

    @Test
    fun `one overdue task is described in the singular, pronoun included`() {
        // "1 tasks are past their due date" is the kind of sentence that makes a reader stop
        // trusting the number in front of it.
        assertEquals("1 task is past its due date.", taskDueSentence(summary(taskCount = 1, overdueCount = 1)))
    }

    // -----------------------------------------------------------------------
    // The sentence that stops the second press
    // -----------------------------------------------------------------------

    @Test
    fun `the under-review sentence is worded exactly as the web words it`() {
        // `frontend/components/tasks/AssigneeProgressCard.tsx`, singular and plural both.
        assertEquals(
            "One task is under review. It stays on your list until an admin approves it — there is " +
                "nothing more for you to do on it.",
            taskAwaitingReviewSentence(1)
        )
        assertEquals(
            "3 tasks are under review. They stay on your list until an admin approves them — there " +
                "is nothing more for you to do on them.",
            taskAwaitingReviewSentence(3)
        )
    }

    @Test
    fun `there is no sentence when there is nothing waiting`() {
        assertNull(taskAwaitingReviewSentence(0))
        assertNull(taskAwaitingReviewSentence(-1))
    }

    // -----------------------------------------------------------------------
    // THE WIRE — the silent-failure class this whole feature is exposed to
    // -----------------------------------------------------------------------

    /** THE decoder this app actually uses. Borrowed rather than re-declared: a second `Json { }`
     *  built here with the same settings would stop tracking the real one the day it changes. */
    private val json: Json = ApiClient.json

    @Test
    fun `a submitted task decodes with every review field intact`() {
        // THE TEST THE WHOLE FILE IS FOR. `ignoreUnknownKeys = true` means a field this DTO forgot
        // to declare is dropped in total silence — no throw, no warning, no log line. Nothing else
        // in the build would have caught `statusLabel` being misspelled here.
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
        // Explains a stuck 0%: answers filed against an interview with no artisan attached cannot
        // raise an (artisan, section) pair count, and the fix is a data link, not a chase.
        assertEquals(3, decoded.derivedBreakdown["unlinkedSections"])

        // And the whole point of decoding it: it renders as review work, not as a to-do item.
        assertEquals("Under review", taskStatusText(decoded))
        assertTrue(taskAwaitingReview(decoded))
        assertTrue(taskOutstanding(decoded))
        assertEquals(25, taskBarPercent(decoded))
        assertTrue(taskProgressIsMeasured(decoded))
        assertNotNull(taskReviewNotice(decoded))
    }

    @Test
    fun `a server that has never heard of the review state still decodes`() {
        // The other direction of the skew, and the one that must degrade rather than break: an older
        // backend sends four statuses and none of the new fields.
        val payload = """
            {"id":"task-1","title":"Record tools","status":"IN_PROGRESS","progressCount":4,
             "targetCount":10,"percentComplete":40}
        """.trimIndent()
        val decoded = json.decodeFromString(TaskDto.serializer(), payload)

        assertEquals("", decoded.statusLabel)
        assertNull(decoded.isAwaitingReview)
        assertNull(decoded.isOutstanding)
        // Derived from the status rather than defaulted to false — a `false` here would empty the
        // researcher's task list, and an empty to-do list is indistinguishable from a finished one.
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
        assertTrue(bar.caption.contains("4 of 7 tasks counted from the repository"))
        assertTrue(bar.caveats.isEmpty())
        val due = taskDueSentence(decoded)!!
        assertTrue(due.startsWith("1 task is past its due date · 2 due within the next two days · next due 20 Sep"))
        assertTrue(due.endsWith("2026."))
        assertTrue(taskAwaitingReviewSentence(decoded.awaitingReviewCount)!!.startsWith("3 tasks are under review"))
    }

    @Test
    fun `a batch that nobody has submitted in still answers the SUBMITTED question`() {
        // The server builds statusCounts from a fixed key list precisely so a client reading
        // counts["SUBMITTED"] does not fault on the happy path. Pinned here because the failure
        // would only ever show up on a batch where everything is going well.
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
