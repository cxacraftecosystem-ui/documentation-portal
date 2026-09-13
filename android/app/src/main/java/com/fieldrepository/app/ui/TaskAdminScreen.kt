@file:OptIn(ExperimentalLayoutApi::class)

package com.fieldrepository.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.PageResponse
import com.fieldrepository.app.data.TaskArtisanDto
import com.fieldrepository.app.data.TaskBatchDto
import com.fieldrepository.app.data.TaskDto
import com.fieldrepository.app.data.TaskOptionsDto
import com.fieldrepository.app.data.TaskProgressAssigneeDto
import com.fieldrepository.app.data.TaskProgressReportDto
import com.fieldrepository.app.data.TaskSectionDto
import com.fieldrepository.app.data.TaskSummaryDto
import com.fieldrepository.app.data.TaskUserDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The task assignment board — hand work out, then hold it to account.
 *
 * A faithful Android port of the web `/settings/tasks` page (`AssignmentBuilder`,
 * `AccountabilityBoard`, `BatchList`). Three views over ONE scope: the workshop picker at the top
 * narrows the artisan list in the builder, the rollup in the accountability view and the assignment
 * list below it, because "who is behind" is a question about a fieldwork trip, not about the whole
 * archive.
 *
 * The accountability tab is the reason this screen exists. `progressCount` is what a researcher SAYS
 * they have done and `derivedCount` is what the repository can actually find them having produced;
 * the two are always drawn together and never merged into one "progress" number, because a task
 * marked done with nothing behind it is exactly the failure this board is meant to catch.
 *
 * Every route behind this screen is `require_admin` server-side — the caller is expected to show it
 * only to admins and the master admin, exactly as the web hides the route behind `isAdmin`.
 */

// =================================================================================================
// The scope vocabulary — a port of frontend/components/tasks/scope.ts, which is itself a port of
// the backend's scope_title()/_derived_target(). Kept identical so the title previewed here is the
// title the server actually stores when none is typed.
// =================================================================================================

/** The canonical order titles read in — matches `RECORD_TYPE_ORDER` on the backend. */
private val RECORD_TYPE_ORDER = listOf("artisan", "product", "process", "tool", "questionnaire", "media")

/** kind -> (singular, plural), matching `RECORD_TYPE_LABELS` on the backend. */
private val RECORD_TYPE_LABELS: Map<String, Pair<String, String>> = mapOf(
    "artisan" to ("artisan" to "artisans"),
    "product" to ("product" to "products"),
    "process" to ("process" to "processes"),
    "tool" to ("tool" to "tools"),
    "questionnaire" to ("questionnaire interview" to "questionnaire interviews"),
    "media" to ("media file" to "media files")
)

// =================================================================================================
// THE STATUS VOCABULARY — five states, one wording, and the review step that made it five.
//
// A researcher's "mark done" no longer finishes a task. It lands on SUBMITTED, and an admin's
// approval is what writes DONE. The owner's sentence for it was "tell them that it is currently
// under review", and everything in this block exists to make sure all three clients say that same
// sentence about the same row.
//
// WHY THE LABELS ARE NAMED CONSTANTS AND NOT STRING LITERALS AT THE CALL SITES. There is no codegen
// between the backend and this file — `ApiModels.kt`, `frontend/lib/types.ts` and the Pydantic
// schema are three hand-written copies of one contract — and Kotlin decodes with
// `ignoreUnknownKeys = true`, so a status this client has never heard of does not throw, does not
// warn, and does not log. It simply falls through whatever `else ->` branch is nearest. That is
// exactly how SUBMITTED would have rendered as "Open": a researcher who had already handed work in
// would be shown a to-do item and told, by omission, to do it again.
// =================================================================================================

const val TASK_STATUS_OPEN = "OPEN"
const val TASK_STATUS_IN_PROGRESS = "IN_PROGRESS"

/**
 * The assignee has declared the work finished and nobody with authority has agreed yet.
 *
 * NOT A FLAVOUR OF DONE, and the server's naming makes the same point: `DONE_PENDING_REVIEW` was
 * rejected because it puts the substring `DONE` inside a value that is emphatically not done, and
 * one careless `"DONE" in status` would then count unapproved work as finished everywhere at once.
 */
const val TASK_STATUS_SUBMITTED = "SUBMITTED"

/** APPROVED. After the review step this is a statement about a SECOND person's decision. */
const val TASK_STATUS_DONE = "DONE"
const val TASK_STATUS_CANCELLED = "CANCELLED"

/**
 * STILL ON THE ASSIGNEE'S SCREEN, by anybody's doing — the server's `OUTSTANDING_STATUSES`.
 *
 * Read the owner's "for it to not pop up next time" backwards and it IS the requirement: the task
 * must go on popping up until somebody approves it. Filter on this (or on the server's
 * `isOutstanding`), never on `status != "DONE"`, which quietly keeps withdrawn work on the list.
 */
val TASK_OUTSTANDING_STATUSES = setOf(TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED)

/**
 * The fallback wording, mirroring the server's `STATUS_LABELS` (tasks.py:129) word for word.
 *
 * THE SERVER'S `statusLabel` IS THE SOURCE AND THIS IS THE NET BENEATH IT, which is why the two
 * halves of the pair exist. Prefer [taskStatusText] everywhere a DTO is in hand; this table is only
 * reached for a status with no label attached — a payload from a server older than the review state,
 * or a bare status string like a filter chip's value, where there is no DTO to read a label off.
 *
 * "Approved" rather than "Done", and "To do" rather than "Open", because both words now carry the
 * new information: DONE means a second person agreed, and OPEN is the assignee's own queue.
 */
private val TASK_STATUS_LABELS = mapOf(
    TASK_STATUS_OPEN to "To do",
    TASK_STATUS_IN_PROGRESS to "In progress",
    TASK_STATUS_SUBMITTED to "Under review",
    TASK_STATUS_DONE to "Approved",
    TASK_STATUS_CANCELLED to "Cancelled"
)

/**
 * One status, worded the way every task surface in the product words it.
 *
 * AN UNKNOWN STATUS PRINTS AS ITSELF rather than being swallowed. The server may grow a sixth state
 * before this APK is rebuilt — it grew a fifth one — and a raw `ESCALATED` on screen is ugly and
 * honest where a fallback of "To do" would be neither: it would put a finished task back on
 * somebody's list, silently, which is the single defect this whole review feature was added to fix.
 */
fun taskStatusLabel(status: String): String = TASK_STATUS_LABELS[status] ?: status

/**
 * The label to actually render for a task: the SERVER'S word, with [taskStatusLabel] as the net.
 *
 * Using the server's string is not deference for its own sake. An admin reads this row on a laptop
 * and the researcher reads it on a phone; if each client owns its own copy of the wording, the two
 * drift the first time one is edited alone — and nobody notices, because no single person ever sees
 * both screens side by side.
 */
fun taskStatusText(task: TaskDto): String = task.statusLabel.ifBlank { taskStatusLabel(task.status) }

/**
 * Handed in, waiting on somebody with authority.
 *
 * DERIVED FROM [TaskDto.status] WHEN THE SERVER'S BOOLEAN IS ABSENT, rather than defaulting to
 * false. `isAwaitingReview` is nullable precisely so "this server does not send the field" and "this
 * task is not awaiting review" stay distinguishable; collapsing them would hand a submitted task
 * back its "Mark done" button and let a researcher re-submit work already sitting in the queue.
 */
fun taskAwaitingReview(task: TaskDto): Boolean =
    task.isAwaitingReview ?: (task.status == TASK_STATUS_SUBMITTED)

/**
 * Still on this person's screen — not yet approved, not withdrawn.
 *
 * Same fallback, worse failure if it were omitted: a `false` default against an older server would
 * empty the assignee's task list, and an empty to-do list is indistinguishable from a finished one.
 */
fun taskOutstanding(task: TaskDto): Boolean =
    task.isOutstanding ?: (task.status in TASK_OUTSTANDING_STATUSES)

// =================================================================================================
// THE ADMIN'S DECISION — approving somebody else's work, sending it back, or reopening an approval.
//
// WHY THIS IS A SEPARATE VOCABULARY FROM THE ASSIGNEE'S BUTTONS BELOW, when both write the same
// column through the same PATCH. They are not the same act. An assignee pressing "Mark done" is a
// REPORT: the person who did the work says it is finished. An admin pressing a button here is a
// DECLARATION ABOUT SOMEBODY ELSE, made by a person who did not do the work and possibly while that
// person is still doing it. The row afterwards is identical either way — the server writes `status`
// and `completedAt` and records nothing at all about who pressed what — so the client, before the
// press, is the ONLY place the difference can be made visible. Three things carry it: the wording
// ("for them", never a bare "Mark done"), a confirmation that names the assignee and states what the
// server will do, and the shield icon rather than the assignee card's tick. None of them is colour.
// =================================================================================================

/**
 * Which moves the board offers from a given status, in the order they should be drawn.
 *
 * SUBMITTED OFFERS BOTH ANSWERS AND OFFERS THEM IN THIS ORDER. Approving is the expected outcome and
 * sits first; sending back sits beside it because a reviewer who can only approve is not reviewing.
 * Sending back lands on IN_PROGRESS rather than OPEN deliberately: the work demonstrably started, and
 * "back to to-do" would tell the researcher — and every rollup counting `openCount` — that it never
 * did.
 *
 * DONE offers both ways back for the same reason it always did: "never started" and "half done" are
 * different answers about a person's week, and an undo that could only restore OPEN would quietly
 * erase that difference on every task it touched.
 *
 * CANCELLED OFFERS NOTHING, and the empty list is the feature. The server would accept a status
 * write here — `update_task`'s manager branch takes any value — so nothing but this function keeps
 * withdrawal off the board. Withdrawing an assignment already has a home on the Assignments tab,
 * where it is one action over the whole batch, behind a danger-tone confirm, and restricted to the
 * admin who sent it. Offering it here as a third small button would put the one irreversible act on
 * this screen in the same visual register as the reversible ones.
 */
fun taskOverrideTargets(status: String): List<String> = when (status) {
    TASK_STATUS_SUBMITTED -> listOf(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS)
    TASK_STATUS_DONE -> listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_OPEN)
    TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS -> listOf(TASK_STATUS_DONE)
    else -> emptyList()
}

/**
 * The button's wording — which depends on WHERE THE TASK IS NOW, not only on where it is going.
 *
 * Writing DONE from SUBMITTED and writing DONE from OPEN are the same database write and two
 * different human acts. From SUBMITTED the researcher has already said they finished and the admin
 * is agreeing, so the honest verb is "Approve". From OPEN nobody has claimed anything and the admin
 * is declaring the work finished over the assignee's head, which is what "Mark done for them" has
 * always been careful to admit. Collapsing the two into one label would let the second act borrow
 * the first one's innocence.
 *
 * THE WAYS BACK BORROW THE PILL'S OWN WORDS via [taskStatusLabel]. These buttons sit inches below a
 * status pill for the very task they act on, so a button reading "Reopen as not started" beside a
 * pill reading "To do" would put two names for one state on one row.
 */
fun taskOverrideActionLabel(current: String, next: String): String = when {
    next == TASK_STATUS_DONE && current == TASK_STATUS_SUBMITTED -> "Approve"
    next == TASK_STATUS_DONE -> "Mark done for them"
    current == TASK_STATUS_SUBMITTED -> "Send back"
    else -> "Back to ${taskStatusLabel(next).lowercase()}"
}

/** The confirmation an override passes through, as data rather than as JSX/Compose, so it can be
 *  asserted in a unit test instead of eyeballed on a phone nobody has in front of them. */
data class TaskOverrideCopy(
    val title: String,
    /** What is about to happen, in one sentence that names the person it happens to. */
    val body: String,
    /** The quieter second line: the consequence a reader must see BEFORE pressing, not after. */
    val note: String,
    val confirmLabel: String
)

/**
 * What the confirmation says, for each of the four moves this board can make.
 *
 * EVERY BODY NAMES THE ASSIGNEE. An override is the one action on this screen whose effect lands on
 * somebody who is not in the room, and a dialog reading "Mark this task done?" gives a tired admin
 * no way to notice they are looking at the wrong row. `whom` is the assignee's name, or a neutral
 * stand-in when the rollup could not resolve one — never blank, or the sentence loses its subject.
 *
 * THE NOTES STATE COSTS, NOT REASSURANCES. Approving is the only irreversible-feeling one and it is
 * not actually irreversible, so its note says what it really means (the task leaves their list) and
 * what it does not mean (it does not backdate anything). Sending back says the thing a reviewer is
 * most likely to get wrong: the work reappears as unfinished on the researcher's screen, which is
 * the point, but it is also how somebody in the field first learns their submission was refused.
 */
fun taskOverrideConfirm(current: String, next: String, assigneeName: String?): TaskOverrideCopy {
    val whom = assigneeName?.takeIf { it.isNotBlank() } ?: "this assignee"
    return when {
        next == TASK_STATUS_DONE && current == TASK_STATUS_SUBMITTED -> TaskOverrideCopy(
            title = "Approve this work?",
            body = "$whom handed this in and it is waiting on you. Approving records it as finished.",
            note = "It leaves their task list and stops appearing as outstanding. The completion date " +
                "stays the date they handed it in, not today — so approving late does not make them " +
                "look late.",
            confirmLabel = "Approve"
        )
        next == TASK_STATUS_DONE -> TaskOverrideCopy(
            title = "Mark this done for them?",
            body = "$whom has not handed this in. You would be recording it as finished on their behalf.",
            note = "The repository stores no trace of who pressed this — the row reads exactly as it " +
                "would if they had finished it themselves. If you only mean \"I think this is done\", " +
                "ask them first.",
            confirmLabel = "Mark done for them"
        )
        current == TASK_STATUS_SUBMITTED -> TaskOverrideCopy(
            title = "Send this back?",
            body = "$whom said this was finished. Sending it back refuses that and reopens the task.",
            note = "It returns to their list as unfinished work, and the app is where they will find " +
                "out. Nothing here carries a message, so tell them why yourself.",
            confirmLabel = "Send back"
        )
        else -> TaskOverrideCopy(
            title = "Reopen this task?",
            body = "This is approved. Reopening puts it back on ${whom}'s list as " +
                "${taskStatusLabel(next).lowercase()}.",
            note = "The completion date is cleared. Whatever they have already recorded stays in the " +
                "repository — only the task's state moves.",
            confirmLabel = "Back to ${taskStatusLabel(next).lowercase()}"
        )
    }
}

// =================================================================================================
// THE ASSIGNEE'S SIDE — the same five states read by the person who owes the work.
//
// These live here, beside the admin's vocabulary rather than beside the screen that renders them,
// for one reason: the two halves have to agree. "Under review" on the researcher's card and "Under
// review" on the admin's pill is one feature; two hand-written copies of that phrase in two files is
// two features that happen to match today. `MainActivity.MyTasksScreen` is the intended caller —
// see the HANDOFF note in this lane's report.
// =================================================================================================

/** The three sentences a handed-in task owes its author — the Kotlin twin of the web's
 *  `ReviewNoticeCopy`. */
data class TaskReviewNotice(val title: String, val body: String, val action: String)

/**
 * The review state, said to the person who submitted it.
 *
 * THE FAILURE MODE THIS GUARDS AGAINST IS PRECISE: a researcher presses "Mark done", the task does
 * NOT leave their list, and with no explanation on screen the only available reading is that the
 * press failed. So they press it again. Then they email somebody. The card therefore has to say
 * three things in the order a worried person asks them — it worked, it is with somebody else now,
 * and there is nothing further for you to do — and it must not look like a rejection while doing it.
 *
 * WORDED CHARACTER FOR CHARACTER WITH `TaskPrimitives.tsx:reviewNoticeCopy`. Not because duplication
 * is elegant — there is no codegen here and this is the third hand-written copy of the contract —
 * but because this exact sentence is what decides whether a researcher trusts the button, and the
 * one person who would discover a divergence is the one researcher who works a workshop on the
 * handset and writes it up on the laptop the same evening.
 *
 * @param assignerName names the admin who handed the work out where the row knows it. "Waiting on"
 *   rather than "not accepted": the delay belongs to the reviewer and the sentence has to put it
 *   there, and naming a person turns an anonymous wait into somebody who can actually be asked.
 * @return null for every state except SUBMITTED, so the caller draws nothing rather than an
 *   empty box.
 */
fun taskReviewNotice(task: TaskDto, assignerName: String? = null): TaskReviewNotice? {
    if (!taskAwaitingReview(task)) return null
    val who = assignerName?.trim().orEmpty()
    return TaskReviewNotice(
        title = "Handed in — under review",
        body = "You marked this done and it has been recorded. " +
            (if (who.isNotEmpty()) "$who or another admin" else "An admin or the master admin") +
            " has to approve it before it is finished, so it stays on your list until then.",
        // The third sentence exists because the second one, alone, reads as an instruction to wait
        // and do something. There is nothing to do, and saying so is the difference between a queue
        // and a problem.
        action = "Nothing more is needed from you unless it comes back."
    )
}

/**
 * The statuses an ASSIGNEE may move their own task to from where it is now.
 *
 * EVERY EMPTY OR MISSING ENTRY HERE MIRRORS A SERVER 403, and mirroring it is the whole job: the
 * server refuses, but a button that produces a red error banner has already told the researcher they
 * did something wrong when in fact they were offered something they were never allowed.
 *
 *  - DONE offers NOTHING. An approval is somebody else's decision; letting the assignee PATCH it back
 *    to IN_PROGRESS would erase the approval (and clear `completedAt` with it), which is what would
 *    make the review step decorative. The server answers this one with
 *    "Only the task creator or an admin can reopen an approved task".
 *  - CANCELLED offers nothing: withdrawal belongs to whoever handed the work out.
 *  - SUBMITTED offers the way BACK ONLY. Withdrawing your own submission is legitimate — you spotted
 *    a gap before the admin did — but there is no forward move from here: the next step is the
 *    reviewer's, and a second "Mark done" press would re-send something already in the queue.
 *
 * SUBMITTED, NOT DONE, IS WHAT "Mark done" WRITES. The server rewrites an assignee's DONE to
 * SUBMITTED rather than refusing it, so sending the old word would still work — but then the request
 * and the response disagree about what happened, and the next person reading a network capture has
 * to know about a rewrite to understand it. Saying the true word costs nothing.
 */
fun assigneeStatusChoices(status: String): List<String> = when (status) {
    TASK_STATUS_OPEN -> listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED)
    TASK_STATUS_IN_PROGRESS -> listOf(TASK_STATUS_OPEN, TASK_STATUS_SUBMITTED)
    TASK_STATUS_SUBMITTED -> listOf(TASK_STATUS_IN_PROGRESS)
    else -> emptyList()
}

/**
 * What the assignee's own buttons say.
 *
 * "Mark done" SURVIVES EVEN THOUGH IT NOW WRITES SUBMITTED, and the survival is deliberate. "Mark
 * done" is what the researcher means, it is the wording on the web card and on every build already
 * in the field, and renaming it to "Submit for approval" would make the app read as though a new
 * hurdle had been added to their job. The hurdle is real, but it is the ADMIN'S hurdle: the honest
 * place to explain it is [taskReviewNotice], on the card, after the press — not on the button, where
 * it reads as friction before the work is even claimed.
 *
 * "Withdraw" rather than "Back to in progress" for the way out of a submission, because that is the
 * act: taking back something you handed in, not editing a status field.
 */
fun assigneeStatusActionLabel(current: String, next: String): String = when {
    next == TASK_STATUS_SUBMITTED -> "Mark done"
    current == TASK_STATUS_SUBMITTED -> "Withdraw"
    next == TASK_STATUS_IN_PROGRESS -> "Start"
    else -> "Back to ${taskStatusLabel(next).lowercase()}"
}

/**
 * The confirmation after an assignee's own status write — what the app says it did.
 *
 * The SUBMITTED case is the one that matters and it is the reason this is not
 * `"Marked ${label.lowercase()}"`. That generic sentence would render as "Marked under review",
 * which reads like the app deciding something rather than like the researcher's own act having a
 * consequence, and says nothing about the task staying put.
 */
fun assigneeStatusConfirmation(next: String): String = when (next) {
    TASK_STATUS_SUBMITTED -> "Sent for approval. It stays on your list until an admin approves it."
    TASK_STATUS_OPEN -> "Moved back to your to-do list."
    TASK_STATUS_IN_PROGRESS -> "Marked in progress."
    else -> "Marked ${taskStatusLabel(next).lowercase()}."
}

// =================================================================================================
// THE ONE PROGRESS NUMBER — drawn from the server, captioned honestly, and absent when it must be.
// =================================================================================================

/**
 * The percentage to draw a bar from, or NULL MEANING "DRAW NO BAR".
 *
 * NULL IS NOT ZERO AND THE DISTINCTION IS THE POINT OF THIS FUNCTION EXISTING. "Food + collect TA
 * details" has no record types, no sections and no quota: there is nothing in the repository that
 * could ever count towards it. A bar at 0% on that row says "this person has produced nothing",
 * which is a claim about them; the truth is "there is nothing here to count", which is a claim about
 * the task. Callers MUST branch on null and render the state pill alone.
 *
 * FALLS BACK TO `percentComplete` ONLY ON A PRE-REVIEW PAYLOAD, detected by the absence of
 * `statusLabel` — every server that knows about `effectivePercent` also sends a label, and a blank
 * label is the only evidence available that a null percentage means "not sent" rather than "not
 * measurable". Reading `effectivePercent == null` as the signal instead would silently delete the
 * bar from every task on an older server.
 */
fun taskBarPercent(task: TaskDto): Int? =
    if (task.statusLabel.isBlank()) task.percentComplete else task.effectivePercent

/**
 * The line under the bar. The server words it ("6 of 24 artisan sections recorded") because it is
 * the server that knows which of the five scope dimensions actually produced the number.
 *
 * The fallback is reached only on a pre-review payload and says the smallest true thing it can from
 * the two figures that have always been on the wire.
 */
fun taskProgressCaption(task: TaskDto): String =
    task.progressLabel?.takeIf { it.isNotBlank() }
        ?: task.targetCount?.let { "${task.progressCount} of $it reported" }
        ?: "${task.progressCount} reported"

/**
 * Whether the number under the bar was COUNTED FROM THE REPOSITORY or merely typed in by the person
 * being measured.
 *
 * This is the owner's "should progress automatically as they record for more and more artisans",
 * turned into something a reader can verify at a glance. A bar that moved because somebody typed 10
 * into a box and a bar that moved because ten artisans were actually recorded look identical, and on
 * an accountability screen that is precisely the confusion worth spending a word to remove.
 */
fun taskProgressIsMeasured(task: TaskDto): Boolean = task.progressSource == "derived"

// =================================================================================================
// THE FULL-WIDTH CARD — "how much is left, and how far along am I", at the top of the task screen.
//
// The owner asked for the accountability board's headline numbers to exist on the ASSIGNEE'S screen
// too. The reason they were missing is worth stating: the assignee's screen is a LIST, and a list
// answers "what is this task" perfectly while answering "how much is left" not at all. A researcher
// with eleven tasks scrolls to find out, and scrolling to count is how three outstanding tasks
// become two in somebody's memory.
//
// EVERY NUMBER HERE COMES FROM `GET /tasks/summary`, NOT FROM THE LIST ON THE SCREEN BEHIND IT. The
// list is paged. A card counted from a page says "4 remaining" to somebody holding twenty-one tasks,
// and the direction of that error is the whole problem — it under-reports work, which is the one
// thing the card exists to stop happening.
//
// ⚑ THIS IS A PORT OF `frontend/components/tasks/AssigneeProgressCard.tsx`, NOT A SECOND DESIGN.
// `taskSummaryBar` mirrors its `summaryBar` and `taskDueSentence` its `dueSentence`, decision for
// decision and sentence for sentence. The same researcher reads this card on a phone in a workshop
// and on a laptop that evening; two cards that merely resembled each other would have them asking
// which one was right, and there is no reason on earth for the answer to be interesting.
// =================================================================================================

/** What to draw, and what to say about it — the Kotlin twin of the web's `SummaryBar`. */
data class TaskSummaryBar(
    /** NULL MEANS DRAW NO BAR. See [taskSummaryBar] for the rule and why it is not "draw 0%". */
    val percent: Int?,
    /** The sentence under the bar, or in its place when there is none. */
    val caption: String,
    /** How much of that percentage was counted from records rather than claimed: all / some / none. */
    val measured: String,
    /** Whole-card qualifications: a scan window that was hit, a derivation that was declined. */
    val caveats: List<String>
)

/**
 * THE HONESTY RULE, AS ONE FUNCTION.
 *
 * `percentComplete` arrives already averaged across the caller's tasks, and the server is explicit
 * about what it did with the unmeasurable ones: it stood a 0 (or a 100, for work handed in or
 * approved) in for them so the average has a number for every row. That substitution is correct for
 * an average and dangerous as a bar, because the two ways of arriving at 0% are indistinguishable
 * once drawn: "every task I can count is at zero" and "nothing you have is countable" are the same
 * empty rectangle, and only the first is a fact about the person.
 *
 * So the bar is drawn when — and only when — at least one figure behind it is real:
 *   - something was measured from the repository (`measuredCount > 0`), including a genuine 0%,
 *     which is worth drawing precisely because it says "this is being counted, and it is zero"; or
 *   - the average is above zero, which cannot happen without a reported figure, a submission or an
 *     approval underneath it.
 * Otherwise: no bar, and a sentence saying why — never an empty track passed off as a measurement.
 *
 * A withdrawn task is not work and the server already keeps CANCELLED out of the average; the same
 * subtraction happens here so the card's own arithmetic cannot disagree with the bar above it.
 */
fun taskSummaryBar(summary: TaskSummaryDto): TaskSummaryBar {
    val active = maxOf(0, summary.taskCount - summary.cancelledCount)
    val caveats = mutableListOf<String>()
    if (summary.truncated) {
        caveats += "You have more tasks than this summary can scan in one go, so these counts are a " +
            "floor rather than a total."
    }
    if (summary.derivationSkipped) {
        // The server declined to count records for a list this long. Not a failure and not hidden:
        // the alternative is a bar that looks measured and is not, which is the outcome ruled out
        // above.
        caveats += "You have too many tasks for the repository counts to be worked out in one go, " +
            "so nothing here was counted from records."
    }

    if (summary.taskCount == 0) {
        return TaskSummaryBar(null, "No tasks have been assigned to you yet.", "none", caveats)
    }
    if (active == 0) {
        return TaskSummaryBar(
            null,
            "Every task assigned to you has been withdrawn, so there is nothing left to measure.",
            "none",
            caveats
        )
    }

    val measured = when {
        summary.measuredCount == 0 -> "none"
        summary.measuredCount >= active -> "all"
        else -> "some"
    }

    if (summary.measuredCount == 0 && summary.percentComplete == 0) {
        return TaskSummaryBar(
            null,
            // Both halves are said because the card cannot tell which one is true from this payload,
            // and guessing would put one of two different explanations on screen as if it were
            // certain.
            "Nothing has been counted from the repository yet and nothing has been reported against " +
                "a target, so there is no honest bar to draw. The counts above are exact.",
            measured,
            caveats
        )
    }

    val caption = when (measured) {
        "all" -> "All $active of your tasks are counted from the repository — this bar moves on its " +
            "own as you record."
        "some" -> "${summary.measuredCount} of $active tasks counted from the repository; the rest " +
            "from what you reported or handed in."
        else -> "Based on what you have reported and handed in — none of these tasks could be " +
            "counted from records."
    }
    return TaskSummaryBar(summary.percentComplete.coerceIn(0, 100), caption, measured, caveats)
}

/**
 * The one sentence about deadlines, assembled so an empty one is genuinely empty rather than a
 * cheerful "0 overdue" nobody asked for. Overdue leads when it exists — it is the only part of this
 * card that is an emergency.
 */
fun taskDueSentence(summary: TaskSummaryDto): String? {
    val parts = mutableListOf<String>()
    if (summary.overdueCount > 0) {
        val isAre = if (summary.overdueCount == 1) "task is" else "tasks are"
        val its = if (summary.overdueCount == 1) "its" else "their"
        parts += "${summary.overdueCount} $isAre past $its due date"
    }
    if (summary.dueSoonCount > 0) parts += "${summary.dueSoonCount} due within the next two days"
    // `nextDueAt` already excludes anything overdue server-side, so this cannot repeat the first
    // clause with a date in the past.
    formatDate(summary.nextDueAt).takeIf { it != "-" }?.let { parts += "next due $it" }
    if (parts.isEmpty()) return null
    // Sentence case, one line, no bullet list: this is a status line, not a second list of tasks.
    return parts.joinToString(" · ") + "."
}

/**
 * The purple line under the tiles — THE SENTENCE THAT STOPS THE SECOND PRESS.
 *
 * Without it, `remainingCount` drops when work is handed in while the cards themselves stay on the
 * list, and the only available reading of that combination is that something went wrong. Worded
 * character for character with `AssigneeProgressCard.tsx`, singular and plural both, because this
 * is the sentence the owner actually asked for ("tell them that it is currently under review") and
 * a researcher who reads one phrasing on a phone and another on a laptop learns to trust neither.
 *
 * Returns null at zero rather than an empty string: nothing to say, so no box to draw.
 */
fun taskAwaitingReviewSentence(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "One task is under review. It stays on your list until an admin approves it — " +
        "there is nothing more for you to do on it."
    else -> "$count tasks are under review. They stay on your list until an admin approves them — " +
        "there is nothing more for you to do on them."
}

/** The six-tier ladder, highest first — the display order the web uses for role pickers. */
private val ROLES_BY_RANK = listOf(
    "MASTER_ADMIN", "ADMIN", "PROFESSOR", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"
)

private val ROLE_LABELS = mapOf(
    "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR" to "Field Contributor",
    "RESEARCHER" to "Researcher",
    "PROFESSOR" to "Professor",
    "ADMIN" to "Admin",
    "MASTER_ADMIN" to "Master Admin"
)

/**
 * The server's own 422 for a scope with no work in it, mirrored verbatim so the client rejection and
 * the server rejection say the same thing rather than two different things about one rule.
 */
private const val EMPTY_SCOPE_MESSAGE =
    "A task needs work in it: pass recordTypes " +
        "(any of ['artisan', 'product', 'process', 'tool', 'questionnaire', 'media']) and/or sectionIds."

/** The label the server gives this role, falling back to the local table for an unknown one. */
private fun roleLabelOf(role: String?): String = ROLE_LABELS[role] ?: role.orEmpty()

private fun TaskUserDto.displayRole(): String = roleLabel.ifBlank { roleLabelOf(role) }

/** Sort a set of record-type values into the canonical order, dropping anything unknown. */
private fun orderRecordTypes(values: Collection<String>): List<String> {
    val wanted = values.map { it.trim().lowercase() }.toSet()
    return RECORD_TYPE_ORDER.filter { it in wanted }
}

private fun recordTypeLabel(kind: String, plural: Boolean = true): String =
    RECORD_TYPE_LABELS[kind]?.let { if (plural) it.second else it.first } ?: kind

/** "a", "a and b", "a, b and c" — the backend's `_and_list`. */
private fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> "${items.dropLast(1).joinToString(", ")} and ${items.last()}"
}

private fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

/**
 * The default title the server derives when the admin does not type one — ported line for line from
 * `scope_title()` so the preview never promises a title the backend then writes differently.
 */
private fun scopeTitle(
    recordTypes: List<String>,
    sectionCodes: List<String>,
    artisanNames: List<String>,
    targetCount: Int?,
    workshopTitle: String?
): String {
    val parts = mutableListOf<String>()
    val ordered = orderRecordTypes(recordTypes)
    if (ordered.isNotEmpty()) {
        val isPlural = targetCount != 1
        val labels = ordered.map { recordTypeLabel(it, isPlural) }
        val count = if (targetCount != null && targetCount > 0) "$targetCount " else ""
        parts += "Record $count${andList(labels)}"
    }
    if (sectionCodes.isNotEmpty()) {
        val codes = sectionCodes.joinToString(", ")
        val noun = if (sectionCodes.size == 1) "section" else "sections"
        // Lower-cased when it trails a record-type half, so the whole reads as one instruction.
        val head = if (parts.isNotEmpty()) "questionnaire" else "Questionnaire"
        parts += "$head $noun $codes"
    }

    var title = if (parts.isEmpty()) "Field task" else parts.joinToString(" + ")
    when {
        artisanNames.size == 1 -> title += " for ${artisanNames[0]}"
        artisanNames.size == 2 -> title += " for ${artisanNames[0]} and ${artisanNames[1]}"
        artisanNames.isNotEmpty() -> title += " for ${artisanNames.size} artisans"
    }
    if (!workshopTitle.isNullOrBlank() && title.length + workshopTitle.length + 3 <= 300) {
        title += " ($workshopTitle)"
    }
    return title.take(300)
}

/**
 * The denominator `derivedCount` is read against — the backend's `_derived_target()`. Null means the
 * scope has no honest denominator (record types with no target count = "as many as apply").
 */
private fun derivedTargetFor(
    recordTypes: List<String>,
    sectionCount: Int,
    artisanCount: Int,
    targetCount: Int?
): Int? {
    var total = 0
    if (recordTypes.isNotEmpty()) {
        if (targetCount == null || targetCount <= 0) return null
        total += targetCount
    }
    if (sectionCount > 0) total += sectionCount * maxOf(1, artisanCount)
    return total.takeIf { it > 0 }
}

/** "for Gitaben Patel", "for 5 named artisans", "for every artisan at Test WS". */
private fun artisanPhrase(artisanNames: List<String>, workshopTitle: String?): String = when {
    artisanNames.size == 1 -> "for ${artisanNames[0]}"
    artisanNames.size == 2 -> "for ${artisanNames[0]} and ${artisanNames[1]}"
    artisanNames.isNotEmpty() -> "for ${artisanNames.size} named artisans"
    !workshopTitle.isNullOrBlank() -> "for every artisan at $workshopTitle"
    else -> "for every artisan in scope"
}

/** "record products and tools", "answer questionnaire sections C and D", or both joined. */
private fun workPhrase(
    recordTypes: List<String>,
    sectionCodes: List<String>,
    artisanNames: List<String>,
    targetCount: Int?,
    workshopTitle: String?
): String {
    val halves = mutableListOf<String>()
    val ordered = orderRecordTypes(recordTypes)
    if (ordered.isNotEmpty()) {
        val labels = ordered.map { recordTypeLabel(it, targetCount != 1) }
        val count = if (targetCount != null && targetCount > 0) "$targetCount " else ""
        halves += "record $count${andList(labels)}"
    }
    if (sectionCodes.isNotEmpty()) {
        val noun = if (sectionCodes.size == 1) "section" else "sections"
        halves += "answer questionnaire $noun ${andList(sectionCodes)}"
    }
    if (halves.isEmpty()) return "do nothing yet"
    return "${halves.joinToString(" and ")} ${artisanPhrase(artisanNames, workshopTitle)}"
}

/**
 * The headline the builder shows before anything is written:
 * "3 people × record products and tools for 5 artisans = 3 tasks".
 */
private fun assignmentPreview(
    assigneeCount: Int,
    recordTypes: List<String>,
    sectionCodes: List<String>,
    artisanNames: List<String>,
    targetCount: Int?,
    workshopTitle: String?
): String {
    val who = plural(assigneeCount, "person", "people")
    val rows = plural(assigneeCount, "task", "tasks")
    return "$who × ${workPhrase(recordTypes, sectionCodes, artisanNames, targetCount, workshopTitle)} = $rows"
}

/** How the assignee's own number compares with what the repository can see. */
private enum class GapTone { UNKNOWN, IDLE, MATCH, AHEAD, BEHIND }

private data class ProgressGap(val tone: GapTone, val label: String)

private fun progressGap(reported: Int, derived: Int?): ProgressGap {
    if (derived == null) return ProgressGap(GapTone.UNKNOWN, "Repository count unavailable")
    // Two zeroes agree, but agreeing about nothing is not an achievement: a green "matches" tick on
    // an untouched task would read as reassurance on exactly the row that deserves a chase.
    if (reported == 0 && derived == 0) return ProgressGap(GapTone.IDLE, "Nothing reported or recorded yet")
    val delta = reported - derived
    return when {
        delta > 0 -> ProgressGap(GapTone.BEHIND, "$delta more reported than the repository can find")
        delta < 0 -> ProgressGap(GapTone.AHEAD, "${-delta} more in the repository than reported")
        else -> ProgressGap(GapTone.MATCH, "Reported figure matches the repository")
    }
}

// --- dates -------------------------------------------------------------------------------------

private fun parseIsoDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

/** The web's `formatDate`: dd MMM yyyy, and "-" when there is nothing to show. */
private fun formatDate(value: String?): String =
    parseIsoDate(value)?.let {
        runCatching { it.format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }.getOrNull()
    } ?: "-"

private fun LocalDate.toDueInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

// =================================================================================================
// Screen
// =================================================================================================

private enum class TaskAdminTab(val label: String) {
    ASSIGN("Assign work"),
    PROGRESS("Accountability"),
    BATCHES("Assignments")
}

/**
 * Everything the builder holds between tab switches.
 *
 * Hoisted out of the builder composable on purpose: on a phone the three views are tabs in one
 * screen, and a half-filled assignment must survive a stray tap on "Accountability" — the web can
 * afford to unmount its form because a mis-click there is a mouse movement, not a thumb.
 */
private class AssignmentFormState {
    var roleFilter by mutableStateOf("")
    var assigneeIds by mutableStateOf<Set<String>>(emptySet())
    var recordTypes by mutableStateOf<Set<String>>(emptySet())
    var sectionIds by mutableStateOf<Set<String>>(emptySet())
    var artisanIds by mutableStateOf<Set<String>>(emptySet())
    var targetCount by mutableStateOf("")
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var dueDate by mutableStateOf<LocalDate?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun reset() {
        assigneeIds = emptySet()
        recordTypes = emptySet()
        sectionIds = emptySet()
        artisanIds = emptySet()
        targetCount = ""
        title = ""
        description = ""
        dueDate = null
    }
}

/**
 * The admin task assignment + accountability board.
 *
 * @param repository the shared API client.
 * @param onBack rendered as a back arrow in the header when non-null; pass null when the host chrome
 *   already provides its own back affordance.
 * @param onMessage a confirmation worth surfacing outside the screen (an assignment was sent).
 * @param onError a failure worth surfacing outside the screen; every load/write error is ALSO shown
 *   inline, so a host that ignores this loses nothing.
 */
@Composable
fun TaskAdminScreen(
    repository: FieldRepository,
    onBack: (() -> Unit)? = null,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(TaskAdminTab.ASSIGN) }
    var workshopId by remember { mutableStateOf("") }

    var options by remember { mutableStateOf<TaskOptionsDto?>(null) }
    var optionsLoading by remember { mutableStateOf(true) }
    var optionsError by remember { mutableStateOf<String?>(null) }

    var report by remember { mutableStateOf<TaskProgressReportDto?>(null) }
    var reportLoading by remember { mutableStateOf(false) }
    var reportError by remember { mutableStateOf<String?>(null) }

    var batches by remember { mutableStateOf<PageResponse<TaskBatchDto>?>(null) }
    var batchesLoading by remember { mutableStateOf(false) }
    var batchesError by remember { mutableStateOf<String?>(null) }
    var batchPage by remember { mutableIntStateOf(1) }

    // Bumped by Refresh and after a write, so the two data effects re-run without a scope change.
    var refreshToken by remember { mutableIntStateOf(0) }

    val form = remember { AssignmentFormState() }

    // The artisan picker narrows to the chosen workshop, so the options call re-runs on every change.
    LaunchedEffect(workshopId) {
        optionsLoading = true
        runCatching { repository.taskOptions(workshopId.ifBlank { null }) }
            .onSuccess { options = it; optionsError = null }
            .onFailure {
                val text = it.apiErrorMessage("Unable to load the assignment pickers")
                optionsError = text
                onError(text)
            }
        optionsLoading = false
    }

    LaunchedEffect(workshopId, refreshToken) {
        reportLoading = true
        runCatching { repository.taskProgress(workshopId.ifBlank { null }) }
            .onSuccess { report = it; reportError = null }
            .onFailure {
                val text = it.apiErrorMessage("Unable to load the accountability rollup")
                reportError = text
                onError(text)
            }
        reportLoading = false
    }

    LaunchedEffect(workshopId, batchPage, refreshToken) {
        batchesLoading = true
        runCatching {
            repository.taskBatches(workshopId = workshopId.ifBlank { null }, page = batchPage, pageSize = 10)
        }
            .onSuccess { batches = it; batchesError = null }
            .onFailure {
                val text = it.apiErrorMessage("Unable to load the assignments")
                batchesError = text
                onError(text)
            }
        batchesLoading = false
    }

    val workshops = options?.workshops.orEmpty()
    val workshopTitle = workshops.firstOrNull { it.id == workshopId }?.title

    fun refreshAll() {
        refreshToken += 1
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- header -------------------------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.Assignment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                "Task assignment",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            "Hand documentation work to the people below you, then watch what they report against " +
                "what the repository can actually find.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        optionsError?.let { ErrorBanner(it) }

        // --- step 1: the scope every view below inherits --------------------------------------
        PanelCard {
            StepHeader(number = 1, title = "Workshop")
            FieldLabel("Scope everything below to")
            SingleSelectField(
                value = workshopId,
                placeholder = if (optionsLoading) "Loading workshops..." else "All workshops",
                options = listOf("" to "All workshops") + workshops.map { workshop ->
                    workshop.id to (workshop.place?.takeIf { it.isNotBlank() }
                        ?.let { "${workshop.title} · $it" } ?: workshop.title)
                },
                onSelect = { value ->
                    workshopId = value
                    // A new workshop scope invalidates the page the batch list was sitting on.
                    batchPage = 1
                }
            )
            Text(
                if (workshopTitle != null) {
                    "Artisans, the rollup and the assignment list below are all limited to $workshopTitle."
                } else {
                    "Nothing is narrowed yet. Pick a workshop to scope the artisan picker, the rollup " +
                        "and the assignment list."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { refreshAll() },
                enabled = !reportLoading && !batchesLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
        }

        // --- tabs -----------------------------------------------------------------------------
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaskAdminTab.entries.forEach { entry ->
                val count = when (entry) {
                    TaskAdminTab.PROGRESS -> report?.assigneeCount
                    TaskAdminTab.BATCHES -> batches?.total
                    else -> null
                }
                TabPill(label = entry.label, count = count, selected = tab == entry) { tab = entry }
            }
        }

        when (tab) {
            TaskAdminTab.ASSIGN -> AssignWorkTab(
                form = form,
                options = options,
                loading = optionsLoading,
                workshopId = workshopId,
                workshopTitle = workshopTitle,
                onSubmit = { body ->
                    scope.launch {
                        form.busy = true
                        form.error = null
                        runCatching {
                            repository.createTaskBatch(
                                assigneeIds = body.assigneeIds,
                                workshopId = body.workshopId,
                                recordTypes = body.recordTypes,
                                artisanIds = body.artisanIds,
                                sectionIds = body.sectionIds,
                                targetCount = body.targetCount,
                                title = body.title,
                                description = body.description,
                                dueAt = body.dueAt
                            )
                        }
                            .onSuccess { result ->
                                form.reset()
                                onMessage(
                                    "Assigned to ${plural(result.created, "person", "people")}" +
                                        result.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                )
                                batchPage = 1
                                refreshAll()
                                tab = TaskAdminTab.BATCHES
                            }
                            .onFailure {
                                val text = it.apiErrorMessage("Unable to create the assignment")
                                form.error = text
                                onError(text)
                            }
                        form.busy = false
                    }
                }
            )

            TaskAdminTab.PROGRESS -> AccountabilityTab(
                report = report,
                loading = reportLoading,
                error = reportError,
                onReview = { task, nextStatus, onDone ->
                    scope.launch {
                        runCatching { repository.reviewTask(task.id, nextStatus) }
                            .onSuccess {
                                onDone(null)
                                // REFRESHED IN FULL RATHER THAN PATCHED IN PLACE, even though the
                                // response carries the updated row. An approval moves five numbers
                                // that are not on it: the assignee's awaitingReviewCount and
                                // outstandingCount, and the board's three headline tiles. Splicing
                                // the one task back into the tree would leave "Waiting on you: 3"
                                // above a queue with two rows in it, and the reader would have no
                                // way to know which of the two was lying.
                                refreshAll()
                                onMessage(
                                    if (nextStatus == TASK_STATUS_DONE) {
                                        "Approved. It has left their task list."
                                    } else {
                                        "Sent back. It is on their list again as " +
                                            "${taskStatusLabel(nextStatus).lowercase()}."
                                    }
                                )
                            }
                            .onFailure {
                                val text = it.apiErrorMessage("Unable to update this task")
                                onDone(text)
                                onError(text)
                            }
                    }
                }
            )

            TaskAdminTab.BATCHES -> AssignmentsTab(
                page = batches,
                loading = batchesLoading,
                error = batchesError,
                onPage = { batchPage = it },
                onDelete = { batch, onDone ->
                    scope.launch {
                        runCatching {
                            val batchId = batch.batchId
                            if (batchId != null) {
                                repository.deleteTaskBatch(batchId)
                            } else {
                                val taskId = batch.assignees.firstOrNull()?.taskId
                                requireNotNull(taskId) { "This assignment has no task to withdraw" }
                                repository.deleteTask(taskId)
                            }
                        }
                            .onSuccess {
                                onDone(null)
                                refreshAll()
                            }
                            .onFailure {
                                val text = it.apiErrorMessage("Unable to withdraw this assignment")
                                onDone(text)
                                onError(text)
                            }
                    }
                }
            )
        }
    }
}

// =================================================================================================
// Tab 1 — Assign work
// =================================================================================================

/** Exactly the fields POST /tasks/batch accepts, resolved from the form once the guard has passed. */
private data class AssignmentRequest(
    val assigneeIds: List<String>,
    val workshopId: String?,
    val recordTypes: List<String>,
    val artisanIds: List<String>,
    val sectionIds: List<String>,
    val targetCount: Int?,
    val title: String?,
    val description: String?,
    val dueAt: String?
)

/**
 * The assignment builder — one scope, many people, one POST.
 *
 * Every dimension the backend accepts is expressible here, and the panel at the bottom says the
 * combination back in a sentence before anything is written. That preview is not decoration: five
 * independent multi-selects produce combinations nobody can verify by reading the controls, and an
 * assignment sent to fifteen people is fifteen rows to unpick if it was wrong.
 */
@Composable
private fun AssignWorkTab(
    form: AssignmentFormState,
    options: TaskOptionsDto?,
    loading: Boolean,
    workshopId: String,
    workshopTitle: String?,
    onSubmit: (AssignmentRequest) -> Unit
) {
    val allAssignees = options?.assignees.orEmpty()
    val allArtisans = options?.artisans.orEmpty()
    val allSections = options?.sections.orEmpty()
    val allRecordTypes = options?.recordTypes.orEmpty()

    // Switching workshop reloads a narrower artisan list; anything picked from the previous workshop
    // has to go, or the batch would silently carry artisans who are not at this workshop at all.
    LaunchedEffect(allArtisans) {
        val available = allArtisans.map { it.id }.toSet()
        val next = form.artisanIds.filterTo(mutableSetOf()) { it in available }
        if (next.size != form.artisanIds.size) form.artisanIds = next
    }

    val selectedAssignees = allAssignees.filter { it.id in form.assigneeIds }
    val selectedSections = allSections.filter { it.id in form.sectionIds }.sortedBy { it.sortOrder }
    val selectedArtisans = allArtisans.filter { it.id in form.artisanIds }

    val orderedRecordTypes = orderRecordTypes(form.recordTypes)
    val sectionCodes = selectedSections.map { it.code }
    val artisanNames = selectedArtisans.map { it.name }
    val validTarget = form.targetCount.trim().toIntOrNull()?.takeIf { it > 0 }

    val generatedTitle = scopeTitle(orderedRecordTypes, sectionCodes, artisanNames, validTarget, workshopTitle)
    val derivedTarget = derivedTargetFor(
        recordTypes = orderedRecordTypes,
        sectionCount = selectedSections.size,
        artisanCount = selectedArtisans.size,
        targetCount = validTarget
    )

    val hasWork = orderedRecordTypes.isNotEmpty() || selectedSections.isNotEmpty()
    val hasPeople = form.assigneeIds.isNotEmpty()

    // The hierarchy filter: the roles that actually appear below this admin, highest tier first.
    val roleCounts = allAssignees.groupingBy { it.role }.eachCount()
    val roleOptions = listOf("" to "Everyone below me (${allAssignees.size})") +
        ROLES_BY_RANK.filter { roleCounts.containsKey(it) }.map { role ->
            val label = allAssignees.firstOrNull { it.role == role }?.displayRole() ?: roleLabelOf(role)
            role to "$label (${roleCounts[role]})"
        }
    val visibleAssignees =
        if (form.roleFilter.isBlank()) allAssignees else allAssignees.filter { it.role == form.roleFilter }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // --- step 2 ---------------------------------------------------------------------------
        PanelCard {
            StepHeader(
                number = 2,
                title = "Who does the work",
                hint = "Only people ranked below you can be given a task. Narrow by tier first if the " +
                    "list is long — one task row is created per person."
            )
            FieldLabel("Filter by tier")
            SingleSelectField(
                value = form.roleFilter,
                placeholder = "Everyone below me",
                options = roleOptions,
                onSelect = { form.roleFilter = it }
            )
            MultiSelectField(
                label = "Assignees",
                required = true,
                placeholder = if (loading) "Loading people..." else "Select people",
                emptyLabel = if (loading) "Loading people..." else "Nobody ranked below you",
                options = visibleAssignees.map { it.id to "${it.name} — ${it.displayRole()}" },
                selected = form.assigneeIds,
                searchable = visibleAssignees.size > 8,
                onToggle = { id -> form.assigneeIds = form.assigneeIds.toggle(id) },
                onSelectAll = { form.assigneeIds = form.assigneeIds + visibleAssignees.map { it.id } },
                onClear = { form.assigneeIds = emptySet() }
            )
            if (selectedAssignees.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedAssignees.forEach { user ->
                        PersonChip(user = user, onRemove = { form.assigneeIds = form.assigneeIds - user.id })
                    }
                }
                TextButton(onClick = { form.assigneeIds = emptySet() }) { Text("Clear all") }
            } else {
                Text(
                    "Nobody selected yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.placeholder
                )
                if (visibleAssignees.isNotEmpty()) {
                    TextButton(onClick = { form.assigneeIds = visibleAssignees.map { it.id }.toSet() }) {
                        Text("Select all ${visibleAssignees.size} shown")
                    }
                }
            }
        }

        // --- step 3 ---------------------------------------------------------------------------
        PanelCard {
            StepHeader(
                number = 3,
                title = "What they must produce",
                hint = "Record types and questionnaire sections can be combined. Leave the artisan " +
                    "list empty to mean every artisan in scope."
            )
            MultiSelectField(
                label = "Record types",
                placeholder = "Artisans, products, tools...",
                emptyLabel = "No record types available",
                options = allRecordTypes.map { kind ->
                    kind.value to kind.pluralLabel.replaceFirstChar { it.uppercase() }
                },
                selected = form.recordTypes,
                onToggle = { id -> form.recordTypes = form.recordTypes.toggle(id) },
                onClear = { form.recordTypes = emptySet() }
            )
            PickedHint(
                labels = allRecordTypes.filter { it.value in form.recordTypes }.map { it.pluralLabel },
                empty = "No record documentation asked for."
            )

            MultiSelectField(
                label = "Questionnaire sections",
                placeholder = "Sections to cover",
                emptyLabel = "No active questionnaire sections",
                options = allSections.sortedBy { it.sortOrder }.map { it.id to "${it.code} — ${it.title}" },
                selected = form.sectionIds,
                searchable = allSections.size > 8,
                onToggle = { id -> form.sectionIds = form.sectionIds.toggle(id) },
                onSelectAll = { form.sectionIds = allSections.map { it.id }.toSet() },
                onClear = { form.sectionIds = emptySet() }
            )
            PickedHint(
                labels = selectedSections.map { "Section ${it.code}" },
                empty = "No questionnaire coverage asked for."
            )

            MultiSelectField(
                label = "Artisan subset",
                placeholder = if (workshopId.isNotBlank()) "All artisans at this workshop" else "All artisans",
                emptyLabel = if (workshopId.isNotBlank()) "No artisans linked to this workshop" else "No artisans yet",
                options = allArtisans.map { artisan ->
                    artisan.id to (artisan.place?.takeIf { it.isNotBlank() }
                        ?.let { "${artisan.name} · $it" } ?: artisan.name)
                },
                selected = form.artisanIds,
                searchable = allArtisans.size > 8,
                onToggle = { id -> form.artisanIds = form.artisanIds.toggle(id) },
                onSelectAll = { form.artisanIds = allArtisans.map { it.id }.toSet() },
                onClear = { form.artisanIds = emptySet() }
            )
            PickedHint(
                labels = artisanNames,
                empty = if (workshopId.isNotBlank()) "Every artisan at this workshop." else "Every artisan in the repository."
            )

            FieldLabel("Target count")
            OutlinedTextField(
                value = form.targetCount,
                onValueChange = { text -> form.targetCount = text.filter { it.isDigit() }.take(6) },
                placeholder = { Text("e.g. 10", color = MaterialTheme.field.placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (validTarget != null) {
                    "Each person is asked for $validTarget record${if (validTarget == 1) "" else "s"}."
                } else {
                    "Optional. Without it, record work reads as “as many as apply” and has no percentage."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // --- step 4 ---------------------------------------------------------------------------
        PanelCard {
            StepHeader(
                number = 4,
                title = "Title, brief and deadline",
                hint = "Leave the title empty to use the one generated from the scope."
            )
            FieldLabel("Title")
            OutlinedTextField(
                value = form.title,
                onValueChange = { form.title = it.take(300) },
                placeholder = {
                    Text(
                        generatedTitle,
                        color = MaterialTheme.field.placeholder,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            FieldLabel("Description")
            /*
             * THE MICROPHONE, AND DELIBERATELY NOT THE RICH EDITOR.
             *
             * It is the biggest box on this screen and it is prose, so it clears the size bar the
             * user set for these controls — and dictation belongs on it, because an administrator
             * describing what good work looks like is composing four sentences of guidance, which is
             * faster said than typed.
             *
             * THE EDITOR STAYS OUT BECAUSE THIS COLUMN ALREADY HAS A FORMATTING PROMISE AND IT IS A
             * DIFFERENT ONE. The placeholder below says "Markdown is supported" — a promise nothing
             * in this app currently renders, which is a pre-existing inconsistency and not this
             * work's to resolve. Putting a rich toolbar over a column whose stated format is Markdown
             * would give one field two formatting models and leave whoever reads the assignment
             * looking at whichever one lost. Deciding between them is a product question about
             * `AssignedTask.description`; until it is answered this box gains the control that is
             * unambiguously an improvement and none of the one that is not.
             *
             * `label = null` because this screen puts the field's name ABOVE the box with
             * `FieldLabel`; a floating label here would print "Description" twice.
             */
            RecordProseField(
                label = null,
                value = form.description,
                onValueChange = { form.description = it },
                placeholder = "What good work looks like here. Markdown is supported.",
                minLines = 3,
                dictate = true,
            )
            FieldLabel("Due date")
            DueDateField(value = form.dueDate, onChange = { form.dueDate = it })
        }

        // --- the preview, then the send ---------------------------------------------------------
        PanelCard(container = MaterialTheme.field.surface50, outlined = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "This will create",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (hasPeople && hasWork) {
                    assignmentPreview(
                        assigneeCount = form.assigneeIds.size,
                        recordTypes = orderedRecordTypes,
                        sectionCodes = sectionCodes,
                        artisanNames = artisanNames,
                        targetCount = validTarget,
                        workshopTitle = workshopTitle
                    )
                } else {
                    "Pick the people and the work — the preview appears here."
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.field.hairline)
            SummaryRow("Task title", form.title.trim().ifBlank { generatedTitle })
            SummaryRow("Workshop", workshopTitle ?: "Not tied to a workshop")
            SummaryRow(
                "Repository counts against",
                if (derivedTarget != null) {
                    "$derivedTarget item${if (derivedTarget == 1) "" else "s"} per person"
                } else {
                    "No fixed denominator"
                }
            )
            SummaryRow("Due", form.dueDate?.let { formatDate(it.toDueInstant()) } ?: "No deadline")

            if (!hasWork && hasPeople) {
                WarningLine(
                    "Pick at least one record type or questionnaire section — a task with no work in " +
                        "it is rejected."
                )
            }
            if (hasWork && !hasPeople) {
                WarningLine("Pick at least one person to assign this to.")
            }
        }

        form.error?.let { ErrorBanner(it) }

        Button(
            onClick = {
                // Reject an empty scope HERE, with the server's own sentence, rather than spending a
                // round trip to be told the same thing.
                if (!hasWork) {
                    form.error = EMPTY_SCOPE_MESSAGE
                    return@Button
                }
                onSubmit(
                    AssignmentRequest(
                        assigneeIds = form.assigneeIds.toList(),
                        workshopId = workshopId.ifBlank { null },
                        recordTypes = orderedRecordTypes,
                        artisanIds = form.artisanIds.toList(),
                        sectionIds = form.sectionIds.toList(),
                        targetCount = validTarget,
                        title = form.title.trim().ifBlank { null },
                        description = form.description.trim().ifBlank { null },
                        // The picker yields a local date; send a full ISO instant at local midnight.
                        dueAt = form.dueDate?.toDueInstant()
                    )
                )
            },
            enabled = !form.busy && !loading && hasPeople,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (form.busy) {
                    "Assigning..."
                } else {
                    "Assign work" + if (hasPeople) " to ${plural(form.assigneeIds.size, "person", "people")}" else ""
                }
            )
        }
        OutlinedButton(
            onClick = { form.reset(); form.error = null },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset") }
    }
}

// =================================================================================================
// Tab 2 — Accountability
// =================================================================================================

/**
 * Who has what, and how far along they ACTUALLY are.
 *
 * Every line carries two numbers: `reportedTotal`, which the assignee typed in, and `derivedTotal`,
 * which is counted from the records that reached the repository. They are never merged and neither
 * is presented as the truth — the distance between them is the signal this view exists to surface.
 */
@Composable
private fun AccountabilityTab(
    report: TaskProgressReportDto?,
    loading: Boolean,
    error: String?,
    onReview: (TaskDto, String, (String?) -> Unit) -> Unit
) {
    when {
        error != null -> ErrorBanner(error)
        loading && report == null -> LoadingLine("Loading the rollup...")
        report == null -> Unit
        report.assignees.isEmpty() -> EmptyStateBlock(
            title = "Nobody has been given work here yet",
            body = "Assign work on the first tab and this becomes the accountability view: who has " +
                "what, what they say they have done, and what the repository can actually find."
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTileGrid(
                listOf(
                    StatTileSpec("People with work", report.assigneeCount),
                    StatTileSpec("Tasks in scope", report.taskCount),
                    StatTileSpec("Still outstanding", report.openCount),
                    // THE ONLY TILE ON THIS BOARD THAT IS ABOUT THE PERSON READING IT. Every other
                    // number here describes somebody else's week; this one is a queue of researchers
                    // blocked on a decision from whoever is holding the phone, and it is drawn in the
                    // warn tone for that reason rather than because anything has gone wrong. Without
                    // it the review step is invisible to the only people who can clear it, and work
                    // sits submitted for as long as nobody happens to expand the right person's card.
                    StatTileSpec(
                        "Waiting on you",
                        report.awaitingReviewCount,
                        if (report.awaitingReviewCount > 0) StatTone.WARN else StatTone.NEUTRAL
                    ),
                    StatTileSpec("Approved", report.doneCount, StatTone.GOOD),
                    StatTileSpec("Overdue", report.overdueCount, StatTone.WARN)
                )
            )

            if (report.truncated) {
                WarningBanner(
                    "This rollup hit its scan limit, so it is a partial picture. Pick a single " +
                        "workshop above to narrow it."
                )
            }

            Text(
                "Reported is what the person says they have done. In repository is what the database " +
                    "can find them having actually created inside the task's scope. Neither overwrites " +
                    "the other — a wide gap is the thing to ask about.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.awaitingReviewCount > 0) {
                Text(
                    "\"Waiting on you\" is work that has been handed in. It stays on the researcher's " +
                        "own list until somebody approves it, so nothing here clears itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            report.assignees.forEach { row -> AssigneeCard(row, onReview = onReview) }
        }
    }
}

@Composable
private fun AssigneeCard(
    row: TaskProgressAssigneeDto,
    onReview: (TaskDto, String, (String?) -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    PanelCard {
        PersonLine(row.user)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TaskChip(plural(row.taskCount, "task", "tasks"))
            TaskChip("${row.openCount} outstanding")
            // Drawn BEFORE the approved count and in the primary tone, because it is the only chip
            // on this row that asks the reader to do something. Counting submissions into
            // "outstanding" instead would have been cheaper and would have quietly blamed the
            // researcher for a delay that belongs to whoever is reading the board.
            if (row.awaitingReviewCount > 0) {
                TaskChip(
                    "${row.awaitingReviewCount} waiting on you",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = Icons.Filled.HourglassTop
                )
            }
            TaskChip(
                "${row.statusCounts[TASK_STATUS_DONE] ?: 0} approved",
                container = MaterialTheme.field.successContainer,
                content = MaterialTheme.field.onSuccessContainer
            )
            if (row.overdueCount > 0) {
                TaskChip(
                    "${row.overdueCount} overdue",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    icon = Icons.Filled.WarningAmber
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
                .padding(12.dp)
        ) {
            ProgressGapMeter(reported = row.reportedTotal, derived = row.derivedTotal, target = row.targetTotal)
        }

        TextButton(onClick = { open = !open }) {
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("${if (open) "Hide" else "Show"} the ${plural(row.taskCount, "task", "tasks")}")
        }

        if (open) {
            row.tasks.forEach { task ->
                AssigneeTaskCard(task, assigneeName = row.user?.name, onReview = onReview)
            }
        }
    }
}

@Composable
private fun AssigneeTaskCard(
    task: TaskDto,
    assigneeName: String?,
    onReview: (TaskDto, String, (String?) -> Unit) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            task.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TaskKindBadge(recordTypes = task.recordTypes, sections = task.sections)
            StatusPill(task)
        }
        DueLine(dueAt = task.dueAt, overdue = task.isOverdue)
        ScopeChipsRow(
            recordTypeLabels = task.recordTypeLabels,
            sections = task.sections,
            artisans = task.artisans,
            targetCount = task.targetCount,
            workshopTitle = task.workshopTitle
        )
        // The single bar the server says is authoritative for this row, drawn ABOVE the two-number
        // meter rather than instead of it. The meter answers "does the claim match the repository",
        // which is what the board is for; this answers "how far along is this", which is what an
        // admin deciding on a submission actually needs and would otherwise have to compute from
        // four numbers in their head.
        TaskEffectiveProgress(task)
        HorizontalDivider(color = MaterialTheme.field.hairline)
        ProgressGapMeter(
            reported = task.progressCount,
            derived = task.derivedCount,
            target = task.targetCount ?: task.derivedTarget
        )
        TaskReviewControls(task = task, assigneeName = assigneeName, onReview = onReview)
    }
}

/**
 * The one bar the server nominates for this row, with the caption that says where it came from.
 *
 * RENDERS NOTHING AT ALL when `effectivePercent` is null, and the nothing is the design. A task with
 * no record types, no sections and no quota — "food, and collect the TA's bank details" — has no
 * countable content, and a bar pinned at 0% beside somebody's name asserts that they have produced
 * nothing. The absent bar asserts only that there is nothing here to measure, which is true. The
 * caption is still drawn in that case, because "No measurable target" is genuinely useful to read.
 */
@Composable
private fun TaskEffectiveProgress(task: TaskDto) {
    val percent = taskBarPercent(task)
    val caption = taskProgressCaption(task)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (percent != null) {
            PercentBar(percent = percent, label = caption)
        } else {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.placeholder
            )
        }
        if (taskProgressIsMeasured(task)) {
            // The owner's "should progress automatically as they record for more and more artisans",
            // said out loud. Without it a bar that moved by itself is indistinguishable from a bar
            // somebody moved by typing a number into the box below it, and on this screen of all
            // screens that is the distinction worth a line of text.
            Text(
                "Counted from the repository — this moves on its own as records are filed" +
                    (task.derivedArtisanCount?.let { ", across $it in scope" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // A stuck 0% has two completely different causes and only one of them is the researcher's
        // fault. Answers recorded on an interview with nobody attached cannot raise an
        // (artisan, section) pair count, so the bar sits at zero while real work exists — and the
        // fix is a data link, not a chase. Saying so here is the difference between an admin
        // sending an unfair message and an admin fixing the interview.
        task.derivedBreakdown["unlinkedSections"]?.takeIf { it > 0 }?.let { orphans ->
            Text(
                "$orphans answered ${if (orphans == 1) "section" else "sections"} are on an interview " +
                    "with no artisan attached, so they cannot count towards this. Link the interview " +
                    "to an artisan and this moves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.onWarningContainer
            )
        }
    }
}

/**
 * THE ADMIN'S DECISION, on the accountability board — approve a submission, send it back, reopen an
 * approval, or record a task done on somebody's behalf.
 *
 * WHY THE CONTROL LIVES ON THIS BOARD AND NOT ON A SEPARATE "REVIEW QUEUE" SCREEN. The decision an
 * approver has to make is "does what they say match what the repository can find" — and that is
 * precisely the two-number meter directly above these buttons. Move the buttons to their own screen
 * and the reviewer approves from a list of titles, which is a rubber stamp with extra steps.
 *
 * EVERY PRESS GOES THROUGH A CONFIRMATION, including the approvals. That is not caution for its own
 * sake: the row this writes is byte-identical to the row the assignee would have written themselves,
 * the server records nothing about who pressed it, and an admin on a phone is one mis-tap away from
 * approving the wrong person's work with no way to tell afterwards that they did.
 */
@Composable
private fun TaskReviewControls(
    task: TaskDto,
    assigneeName: String?,
    onReview: (TaskDto, String, (String?) -> Unit) -> Unit
) {
    val targets = taskOverrideTargets(task.status)
    if (targets.isEmpty()) return

    var busy by remember(task.id) { mutableStateOf(false) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var confirming by remember(task.id) { mutableStateOf<String?>(null) }

    confirming?.let { next ->
        val copy = taskOverrideConfirm(task.status, next, assigneeName)
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(copy.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(copy.body)
                    Text(
                        copy.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    busy = true
                    error = null
                    onReview(task, next) { failure ->
                        busy = false
                        error = failure
                    }
                }) { Text(copy.confirmLabel) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }

    HorizontalDivider(color = MaterialTheme.field.hairline)
    if (taskAwaitingReview(task)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Filled.HourglassTop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Handed in" + (formatDate(task.completedAt).takeIf { it != "-" }?.let { " on $it" } ?: "") +
                    " and waiting on a decision.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    error?.let { ErrorBanner(it) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        targets.forEach { next ->
            val approving = next == TASK_STATUS_DONE && task.status == TASK_STATUS_SUBMITTED
            if (approving) {
                // The expected outcome of a review gets the filled button; everything else on this
                // row is an OutlinedButton. One emphasised action per row, and it is the one that
                // clears the queue.
                Button(enabled = !busy, onClick = { confirming = next }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Saving..." else taskOverrideActionLabel(task.status, next))
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = { confirming = next }) {
                    // The shield, not the assignee card's tick: this is an act performed ON somebody,
                    // and the icon is one of the three things carrying that distinction.
                    Icon(
                        Icons.Filled.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Saving..." else taskOverrideActionLabel(task.status, next))
                }
            }
        }
    }
}

// =================================================================================================
// Tab 3 — Assignments
// =================================================================================================

/**
 * Assignments grouped back into the action that created them.
 *
 * An admin thinks in the thing they did — "I gave the tool survey to five people" — not in the five
 * rows it became, so withdrawing it is one action too. Rows written before batching existed (and
 * single-assignee creates) come back with a null batchId; those are deleted one task at a time
 * through the task endpoint, which is why the delete below branches.
 */
@Composable
private fun AssignmentsTab(
    page: PageResponse<TaskBatchDto>?,
    loading: Boolean,
    error: String?,
    onPage: (Int) -> Unit,
    onDelete: (TaskBatchDto, (String?) -> Unit) -> Unit
) {
    var busyKey by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<TaskBatchDto?>(null) }

    val items = page?.items.orEmpty()

    confirming?.let { batch ->
        val isBatch = batch.batchId != null
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (isBatch) "Withdraw assignment" else "Delete task") },
            text = {
                Text(
                    if (isBatch) {
                        "Withdraw \"${batch.title}\" from all ${plural(batch.assigneeCount, "person", "people")}?"
                    } else {
                        "Delete the task \"${batch.title}\"?"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    busyKey = batch.key
                    deleteError = null
                    onDelete(batch) { failure ->
                        deleteError = failure
                        busyKey = null
                    }
                }) { Text(if (isBatch) "Withdraw" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }

    when {
        error != null -> ErrorBanner(error)
        loading && items.isEmpty() -> LoadingLine("Loading assignments...")
        items.isEmpty() -> EmptyStateBlock(
            title = "No assignments here yet",
            body = "Everything handed out from the assignment builder shows up here as one manageable " +
                "unit, with the whole group's progress and a single withdraw action."
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            deleteError?.let { ErrorBanner(it) }
            items.forEach { batch ->
                BatchCard(
                    batch = batch,
                    busy = busyKey == batch.key,
                    onRemove = { confirming = batch }
                )
            }
            val pages = page?.pages ?: 0
            if (pages > 1) {
                PaginationRow(
                    page = page?.page ?: 1,
                    pages = pages,
                    total = page?.total ?: 0,
                    onPage = onPage
                )
            }
        }
    }
}

@Composable
private fun BatchCard(batch: TaskBatchDto, busy: Boolean, onRemove: () -> Unit) {
    PanelCard {
        Text(
            batch.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "Sent by ${batch.createdBy?.name ?: "an administrator"} on ${formatDate(batch.createdAt)}" +
                if (batch.batchId == null) " · single task" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TaskChip("${batch.assigneeCount}", icon = Icons.Filled.Groups)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRemove, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (batch.batchId != null) "Withdraw" else "Delete")
            }
        }

        batch.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
        }

        ScopeChipsRow(
            recordTypeLabels = batch.recordTypeLabels,
            sections = batch.sections,
            artisans = batch.artisans,
            targetCount = batch.targetCount,
            workshopTitle = batch.workshopTitle
        )
        DueLine(dueAt = batch.dueAt, overdue = batch.overdueCount > 0)

        HorizontalDivider(color = MaterialTheme.field.hairline)

        PercentBar(percent = batch.percentComplete, label = "Group progress")
        // "APPROVED", NOT "FINISHED", AND THE SUBMISSIONS GET THEIR OWN CLAUSE RATHER THAN BEING
        // ADDED IN. The server counts only approved rows into `percentComplete` for exactly this
        // reason: a batch reading "5 of 5 finished" while nobody has looked at any of it is the
        // illusion the review state was added to remove, and folding `awaitingReviewCount` into the
        // same number here would re-create it one layer up.
        Text(
            "${batch.doneCount} of ${batch.assigneeCount} approved" +
                (if (batch.awaitingReviewCount > 0) " · ${batch.awaitingReviewCount} awaiting review" else "") +
                " · ${batch.openCount} outstanding" +
                if (batch.overdueCount > 0) " · ${batch.overdueCount} overdue" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GapChip(reported = batch.reportedTotal, derived = batch.derivedTotal)

        batch.assignees.forEach { assignee ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PersonLine(assignee.user)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "reported ${assignee.progressCount}" +
                            (batch.targetCount?.let { " / $it" } ?: "") +
                            " · in repository ${assignee.derivedCount?.toString() ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    // The server's own wording where it sent one. A batch row and the accountability
                    // row for the same task are two views of one thing and must not word it twice.
                    StatusPill(assignee.status, assignee.statusLabel.ifBlank { taskStatusLabel(assignee.status) })
                }
            }
        }
    }
}

// =================================================================================================
// Shared vocabulary — the chips, pills and meters every tab above draws from.
//
// Deliberate local copies rather than reaches into MainActivity: that file is owned elsewhere and
// its equivalents are private to it.
// =================================================================================================

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun PanelCard(
    container: Color = MaterialTheme.colorScheme.surface,
    outlined: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (outlined) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                    } else {
                        Modifier
                    }
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun StepHeader(number: Int, title: String, hint: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, required: Boolean = false) {
    Text(
        text + if (required) " *" else "",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** What a multi-select has actually got in it — the trigger only ever says "N selected". */
@Composable
private fun PickedHint(labels: List<String>, empty: String) {
    if (labels.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.placeholder)
        return
    }
    val shown = labels.take(6)
    val rest = labels.size - shown.size
    Text(
        shown.joinToString(", ") + if (rest > 0) " +$rest more" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SingleSelectField(
    value: String,
    placeholder: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selectedLabel ?: placeholder,
                color = if (selectedLabel != null) MaterialTheme.field.body else MaterialTheme.field.placeholder,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            options.forEach { (optionValue, label) ->
                val selected = optionValue == value
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = { onSelect(optionValue); expanded = false }
                )
            }
        }
    }
}

/**
 * A collapsed trigger that opens an inline checkbox list.
 *
 * A menu would be wrong here: these lists run to dozens of people or artisans, several are picked at
 * a time, and a dropdown that closes on every tick makes a second pick impossible.
 */
@Composable
private fun MultiSelectField(
    label: String,
    placeholder: String,
    emptyLabel: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    required: Boolean = false,
    searchable: Boolean = false,
    onToggle: (String) -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chosen = options.count { it.first in selected }
    val filtered =
        if (query.isBlank()) options else options.filter { it.second.contains(query.trim(), ignoreCase = true) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, required)
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (chosen > 0) "$chosen selected" else placeholder,
                color = if (chosen > 0) MaterialTheme.field.body else MaterialTheme.field.placeholder,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (options.isEmpty()) {
                    Text(
                        emptyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (searchable) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search", color = MaterialTheme.field.placeholder) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        filtered.forEach { (id, text) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(id) }
                            ) {
                                Checkbox(checked = id in selected, onCheckedChange = { onToggle(id) })
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.field.body
                                )
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                "Nothing matches \"${query.trim()}\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        onSelectAll?.let {
                            TextButton(onClick = it) { Text("Select all") }
                        }
                        onClear?.let {
                            TextButton(onClick = it) { Text("Clear") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { expanded = false }) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonChip(user: TaskUserDto, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            user.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            user.displayRole(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${user.name}",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * The task due date. Was an OutlinedButton opening the PLATFORM `android.app.DatePickerDialog`,
 * which is themed from res/values/styles.xml by the SYSTEM's night setting rather than by the app's
 * appearance preference — so a researcher running the app in Dark on a light phone got a white
 * calendar over a dark form. [FieldDateField] is the same Compose colour scheme as the screen and
 * lets the date be typed, which for a due date usually beats paging a calendar to next Friday.
 */
@Composable
private fun DueDateField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    FieldDateField(
        label = "Due date",
        value = value,
        onValueChange = onChange,
        placeholder = "No due date",
        clearable = true
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TabPill(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.small
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                MaterialTheme.shapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.field.body
        )
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskChip(
    text: String,
    container: Color = MaterialTheme.field.surface50,
    content: Color = MaterialTheme.field.body,
    border: Color = MaterialTheme.field.hairline,
    icon: ImageVector? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(container, CircleShape)
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One status, as a pill.
 *
 * THE `else ->` BRANCH IS WHY EVERY STATE MUST BE LISTED EXPLICITLY. It renders the neutral,
 * unremarkable chip that OPEN uses, and `ignoreUnknownKeys` means an unrecognised status arrives
 * here silently — so before SUBMITTED was given its own branch, a task the researcher had already
 * handed in drew the same grey chip as a task nobody had touched. Nothing would have logged, nothing
 * would have crashed, and the only symptom would have been an admin never noticing a review queue
 * existed. Adding a sixth status means editing this `when`, not just the label table.
 *
 * SUBMITTED IS DRAWN IN THE PRIMARY TONE, deliberately not in success-green and not in the neutral
 * grey. Green would say "finished", which is the exact claim the review step exists to withhold;
 * grey would say "nothing has happened here", which is false and is what leaves a submission
 * unnoticed at the bottom of a rollup. Primary says "this row wants a decision from you".
 */
@Composable
private fun StatusPill(status: String, label: String = taskStatusLabel(status)) {
    when (status) {
        TASK_STATUS_IN_PROGRESS -> TaskChip(
            label,
            container = MaterialTheme.field.warningContainer,
            content = MaterialTheme.field.onWarningContainer,
            border = MaterialTheme.field.warning
        )
        TASK_STATUS_SUBMITTED -> TaskChip(
            label,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = MaterialTheme.colorScheme.primary,
            icon = Icons.Filled.HourglassTop
        )
        TASK_STATUS_DONE -> TaskChip(
            label,
            container = MaterialTheme.field.successContainer,
            content = MaterialTheme.field.onSuccessContainer,
            border = MaterialTheme.field.success
        )
        TASK_STATUS_CANCELLED -> TaskChip(
            label,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            border = MaterialTheme.colorScheme.error
        )
        else -> TaskChip(label, content = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The pill for a task, preferring the server's own wording over this client's fallback table. */
@Composable
private fun StatusPill(task: TaskDto) = StatusPill(task.status, taskStatusText(task))

/**
 * Records vs questionnaire sections — the one distinction an assignee has to make at a glance,
 * because the two kinds of work happen in completely different parts of the app.
 */
@Composable
private fun TaskKindBadge(recordTypes: List<String>, sections: List<TaskSectionDto>) {
    val hasRecords = recordTypes.isNotEmpty()
    val hasSections = sections.isNotEmpty()
    if (!hasRecords && !hasSections) return
    val text = when {
        hasRecords && hasSections -> "Records + questionnaire"
        hasSections -> "Questionnaire sections"
        else -> "Record documentation"
    }
    TaskChip(
        text,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        border = MaterialTheme.colorScheme.primary,
        icon = if (hasSections && !hasRecords) Icons.AutoMirrored.Filled.ListAlt else Icons.Filled.Category
    )
}

@Composable
private fun DueLine(dueAt: String?, overdue: Boolean) {
    if (dueAt.isNullOrBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            Icons.Filled.Event,
            contentDescription = null,
            tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Text(
            "Due ${formatDate(dueAt)}" + if (overdue) " — overdue" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal,
            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Name + role, the way every hierarchy-aware screen in the app writes a person. */
@Composable
private fun PersonLine(user: TaskUserDto?) {
    if (user == null) {
        Text(
            "Unknown user",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            user.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            user.displayRole(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Every dimension of a scope, spelled out. Artisans collapse past four names — a task handed out for
 * twenty artisans is about the count, not the roster.
 */
@Composable
private fun ScopeChipsRow(
    recordTypeLabels: List<String>,
    sections: List<TaskSectionDto>,
    artisans: List<TaskArtisanDto>,
    targetCount: Int?,
    workshopTitle: String?,
    maxArtisans: Int = 4
) {
    val shownArtisans = artisans.take(maxArtisans)
    val hiddenArtisans = artisans.size - shownArtisans.size
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        workshopTitle?.takeIf { it.isNotBlank() }?.let { TaskChip(it) }
        recordTypeLabels.forEach { label ->
            TaskChip(
                label.replaceFirstChar { it.uppercase() },
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                border = MaterialTheme.colorScheme.primary
            )
        }
        if (targetCount != null && targetCount > 0) {
            TaskChip(
                "Target $targetCount",
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                border = MaterialTheme.colorScheme.primary
            )
        }
        sections.sortedBy { it.sortOrder }.forEach { section ->
            TaskChip(
                "Section ${section.code}",
                container = MaterialTheme.colorScheme.surface,
                icon = Icons.AutoMirrored.Filled.ListAlt
            )
        }
        shownArtisans.forEach { artisan ->
            TaskChip(artisan.name, container = MaterialTheme.colorScheme.surface)
        }
        if (hiddenArtisans > 0) {
            TaskChip(
                "+$hiddenArtisans more artisans",
                container = MaterialTheme.colorScheme.surface,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (artisans.isEmpty()) {
            TaskChip(
                "All artisans in scope",
                container = MaterialTheme.colorScheme.surface,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The reported-vs-derived verdict as a labelled, icon-bearing chip. Every tone carries an icon and a
 * sentence, so the judgement survives colour-blindness and greyscale printing.
 */
@Composable
private fun GapChip(reported: Int, derived: Int?) {
    val gap = progressGap(reported, derived)
    when (gap.tone) {
        GapTone.MATCH, GapTone.AHEAD -> TaskChip(
            gap.label,
            container = MaterialTheme.field.successContainer,
            content = MaterialTheme.field.onSuccessContainer,
            border = MaterialTheme.field.success,
            icon = Icons.Filled.CheckCircle
        )
        GapTone.BEHIND -> TaskChip(
            gap.label,
            container = MaterialTheme.field.warningContainer,
            content = MaterialTheme.field.onWarningContainer,
            border = MaterialTheme.field.warning,
            icon = Icons.Filled.WarningAmber
        )
        GapTone.IDLE -> TaskChip(
            gap.label,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Filled.RemoveCircleOutline
        )
        GapTone.UNKNOWN -> TaskChip(
            gap.label,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.AutoMirrored.Filled.HelpOutline
        )
    }
}

@Composable
private fun MeterBar(value: Int, denominator: Int, color: Color) {
    val fraction = if (denominator > 0) (value.toFloat() / denominator).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.field.surface200, CircleShape)
            .border(1.dp, MaterialTheme.field.hairline, CircleShape)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/**
 * The gap made legible: what the assignee says, directly above what the repository can actually see,
 * on ONE shared scale so the two bars are comparable by length.
 *
 * With no target count the shared denominator is the larger of the two figures, which keeps the
 * comparison honest without inventing a quota that was never set.
 */
@Composable
private fun ProgressGapMeter(reported: Int, derived: Int?, target: Int?) {
    val hasTarget = target != null && target > 0
    val denominator = if (hasTarget) target else maxOf(reported, derived ?: 0, 1)
    val suffix = if (hasTarget) " / $target" else ""
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Reported",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                MeterBar(value = reported, denominator = denominator, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "$reported$suffix",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "In repository",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (derived == null) {
                    Text(
                        "not counted for this page",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.field.placeholder
                    )
                } else {
                    MeterBar(value = derived, denominator = denominator, color = MaterialTheme.field.muted)
                }
            }
            Text(
                if (derived == null) "—" else "$derived$suffix",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        GapChip(reported = reported, derived = derived)
        if (!hasTarget) {
            Text(
                "No target count on this task — the bars compare the two figures to each other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.placeholder
            )
        }
    }
}

/** Slim single-value bar used where the two-number meter would be overkill (batch rollups). */
@Composable
private fun PercentBar(percent: Int, label: String) {
    val clamped = percent.coerceIn(0, 100)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$clamped%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        MeterBar(value = clamped, denominator = 100, color = MaterialTheme.colorScheme.primary)
    }
}

private enum class StatTone { NEUTRAL, WARN, GOOD }

private data class StatTileSpec(
    val label: String,
    val value: Int,
    val tone: StatTone = StatTone.NEUTRAL,
    /** The web's tile hint ("still to do", "waiting on an admin") — what the number MEANS, in two
     *  or three words, for the reader who cannot tell "Remaining" from "Under review" at a glance. */
    val hint: String? = null
)

/** Two tiles per row: five headline numbers do not fit across a phone in one line and never will. */
@Composable
private fun StatTileGrid(tiles: List<StatTileSpec>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> StatTile(tile, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(spec: StatTileSpec, modifier: Modifier = Modifier) {
    val valueColor = when {
        spec.tone == StatTone.WARN && spec.value != 0 -> MaterialTheme.colorScheme.error
        spec.tone == StatTone.GOOD && spec.value != 0 -> MaterialTheme.field.success
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("${spec.value}", style = MaterialTheme.typography.headlineSmall, color = valueColor)
        Text(
            spec.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        spec.hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.placeholder)
        }
    }
}

@Composable
private fun PaginationRow(page: Int, pages: Int, total: Int, onPage: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Page ${if (pages > 0) page else 0} of $pages · $total records",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.body
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onPage(page - 1) },
                enabled = page > 1,
                modifier = Modifier.weight(1f)
            ) { Text("Previous") }
            OutlinedButton(
                onClick = { onPage(page + 1) },
                enabled = page < pages,
                modifier = Modifier.weight(1f)
            ) { Text("Next") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun WarningBanner(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.field.onWarningContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.warning, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun WarningLine(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.warning)
}

@Composable
private fun LoadingLine(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyStateBlock(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Assignment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// =================================================================================================
// THE ASSIGNEE'S FULL-WIDTH PROGRESS CARD
//
// The owner asked for the board's headline numbers to exist on the RESEARCHER'S screen as well, and
// this is that card — a port of `frontend/components/tasks/AssigneeProgressCard.tsx`, tile for tile.
//
// IT IS PUBLIC AND IT LIVES IN THIS FILE rather than beside the task list it belongs above, because
// that list is inside `MainActivity.kt` — a file this lane does not own. See the HANDOFF note in the
// lane report for the one call site it needs; until that lands, this composable has no caller. It is
// here rather than unwritten because the alternative is the wording being invented a second time by
// whoever wires it, which is exactly the drift the ported copy above exists to prevent.
//
// WHY FOUR TILES AND NOT A SENTENCE. The web's four are Remaining / Under review / Overdue /
// Approved, and the ORDER is the argument: Remaining leads because it is the owner's "remaining
// tasks" and the thing a researcher opens this screen to find out; Under review is second because
// it is the new state and the one that would otherwise be read as "still to do" — it is work that
// has LEFT them; Overdue is third and loud only when non-zero (`StatTile` keeps a zero in ink rather
// than in red, so an on-time list is not decorated with a warning colour); Approved is last, because
// it is the only one that is over.
// =================================================================================================

/**
 * "Where you stand" — drawn from `GET /tasks/summary`.
 *
 * @param summary the caller's own rollup. Null with [loading] renders a height rather than a hole;
 *   null without it renders nothing at all.
 * @param onRefresh optional; when null the refresh affordance is omitted rather than drawn dead.
 */
@Composable
fun AssigneeTaskSummaryCard(
    summary: TaskSummaryDto?,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    if (summary == null) {
        // FIRST PAINT IS A HEIGHT, NOT A HOLE — the list below renders as soon as its own call
        // returns, so a card occupying no space until its fetch landed would shove the whole screen
        // down under the reader's thumb a moment after they started reading it.
        if (loading) LoadingLine("Loading your progress...")
        return
    }
    // Nothing assigned at all: the list below already says so in more useful words, and a card of
    // four zeroes above it would be the same non-news twice.
    if (summary.taskCount == 0) return

    val bar = taskSummaryBar(summary)
    val due = taskDueSentence(summary)

    PanelCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Assignment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                "Where you stand",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onRefresh != null) {
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh your progress",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        StatTileGrid(
            listOf(
                StatTileSpec("Remaining", summary.remainingCount, hint = "still to do"),
                StatTileSpec("Under review", summary.awaitingReviewCount, hint = "waiting on an admin"),
                StatTileSpec("Overdue", summary.overdueCount, StatTone.WARN, hint = "past the due date"),
                StatTileSpec("Approved", summary.approvedCount, StatTone.GOOD, hint = "signed off")
            )
        )

        // THE SENTENCE THAT STOPS THE SECOND PRESS. `remainingCount` drops the moment work is handed
        // in while the cards themselves stay on the list, and without this the only available reading
        // of that combination is that something went wrong.
        taskAwaitingReviewSentence(summary.awaitingReviewCount)?.let { sentence ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Filled.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        due?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.overdueCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (summary.overdueCount > 0) FontWeight.SemiBold else null
            )
        }

        // NO BAR WHEN THERE IS NO HONEST BAR TO DRAW, only the sentence explaining that. An empty
        // track and "every task I can count is at zero" are the same rectangle, and only one of them
        // is a fact about the person reading it.
        if (bar.percent != null) {
            PercentBar(percent = bar.percent, label = "Overall progress")
        } else {
            Text(
                "Overall progress",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            bar.caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.placeholder
        )
        bar.caveats.forEach { WarningBanner(it) }
    }
}

/**
 * The assignee's own status buttons and the "under review" notice for ONE task — the card body that
 * belongs above the reported-progress box on `MainActivity`'s task card.
 *
 * PUBLIC AND UNCALLED FOR THE SAME REASON AS THE CARD ABOVE. Putting the wording here rather than
 * leaving it to the screen that owns the list is what stops "Under review" being written twice in
 * two files and drifting the first time one of them is edited alone.
 *
 * @param task the row, as the server returned it.
 * @param editable false on somebody else's row (the "assigned by me" view), where the API would
 *   refuse the write anyway — the controls are hidden rather than shown and then rejected.
 * @param assignerName the admin who handed the work out, when the row knows it; it turns an
 *   anonymous wait into somebody the researcher can go and ask.
 */
@Composable
fun AssigneeTaskActions(
    task: TaskDto,
    editable: Boolean,
    busy: Boolean,
    onStatus: (String) -> Unit,
    assignerName: String? = null
) {
    taskReviewNotice(task, assignerName)?.let { notice ->
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    notice.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                notice.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                notice.action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }

    if (!editable) return
    val choices = assigneeStatusChoices(task.status)
    if (choices.isEmpty()) {
        // APPROVED OR WITHDRAWN — NO CONTROLS, AND A SENTENCE SAYING WHY. The server answers an
        // assignee touching an approved task with a 403 ("Only the task creator or an admin can
        // reopen an approved task"), and a button that produces a red banner has already told
        // somebody they did something wrong when in fact they were offered something they were
        // never allowed to press.
        if (task.status == TASK_STATUS_DONE) {
            Text(
                "Approved — this one is finished. Only an admin or the person who assigned it can " +
                    "reopen it now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { next ->
            val label = assigneeStatusActionLabel(task.status, next)
            if (next == TASK_STATUS_SUBMITTED) {
                Button(enabled = !busy, onClick = { onStatus(next) }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Saving..." else label)
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = { onStatus(next) }) {
                    Text(if (busy) "Saving..." else label)
                }
            }
        }
    }
    if (choices.contains(TASK_STATUS_SUBMITTED)) {
        // Said BEFORE the press, not only after it. The web's `MyTaskCard` carries the same line
        // under the same button: "Mark done" no longer finishes a task, and a researcher who learns
        // that only from the card not disappearing has already decided the app is broken.
        Text(
            "Marking this done sends it to an admin to approve. It stays on your list, marked under " +
                "review, until they do.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
