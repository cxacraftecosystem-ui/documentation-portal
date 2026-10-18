package com.fieldrepository.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.ReviewSend
import com.fieldrepository.app.data.LatestReviewDto
import com.fieldrepository.app.data.ReturnedRecordDto
import com.fieldrepository.app.data.ReviewReasonDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch

/**
 * THE REVIEW FEEDBACK LOOP ON THE HANDSET (A1, A2) — the web's `lib/review.ts` and
 * `components/review/ReviewNoteBanner.tsx` / `SentBackCard.tsx`, word for word.
 *
 * The reviewer's note used to be required, saved, and shown on no screen of either app. Now it sits
 * above the record's edit form, and the home screen carries "Sent back to you (N)" whenever a
 * reviewer has returned something of this account's.
 */
object ReviewFeedbackCopy {
    fun isReturned(status: String?): Boolean = status == "NEEDS_REVISION" || status == "REJECTED"

    fun heading(status: String, reviewerName: String?): String {
        val who = reviewerName?.trim()?.takeIf { it.isNotEmpty() } ?: "A reviewer"
        return if (status == "REJECTED") "Not accepted by $who" else "Sent back by $who"
    }

    fun nextStep(status: String): String =
        if (status == "REJECTED") "Fix it and save to reopen it for review."
        else "Fix it and save to send it back for review."

    fun title(count: Int): String = "Sent back to you ($count)"

    fun note(sentBack: Int, rejected: Int): String = buildList {
        if (sentBack > 0) add("$sentBack record${if (sentBack == 1) "" else "s"} to fix")
        if (rejected > 0) add("$rejected not accepted")
    }.joinToString(" · ")

    /** The API's record type for an edit screen's kind, or null for a kind that is never reviewed. */
    fun recordTypeFor(entryName: String): String? = when (entryName) {
        "ARTISAN" -> "artisan"
        "PRODUCT" -> "product"
        "PROCESS" -> "process"
        "TOOL" -> "tool"
        "QUESTIONNAIRE" -> "questionnaire"
        "WORKSHOP" -> "workshop"
        else -> null
    }

    /** "Approve 3", the bulk bar's button. */
    fun approveLabel(count: Int): String = "Approve $count"

    fun bulkReceipt(approved: Int, failed: Int, fileCount: Int = 0): String {
        val withFiles = if (fileCount > 0) ", with ${files(fileCount)}" else ""
        return if (failed == 0) "$approved record${if (approved == 1) "" else "s"} approved$withFiles."
        else "$approved approved${withFiles.removePrefix(",")}, $failed failed (still ticked)."
    }

    // --- The reason a reviewer picks (C19), the web's `lib/review.ts` word for word ---

    /** What "Reject" with this reason really does: a FIXABLE reason sends the record back instead. */
    fun refusalOutcome(reason: ReviewReasonDto?): String = if (reason?.fixable == true) "NEEDS_REVISION" else "REJECTED"

    /** The confirm button's label for the picked reason. */
    fun refusalButtonLabel(reason: ReviewReasonDto?): String =
        if (refusalOutcome(reason) == "NEEDS_REVISION") "Send for revision" else "Reject record"

    /** A reason is picked, and "Other" carries words. */
    fun refusalReady(reason: ReviewReasonDto?, note: String): Boolean =
        reason != null && (!reason.needsNote || note.isNotBlank())

    /** The reasons offered on "Send for revision": the fixable ones, picking one optional. */
    fun reviseReasons(reasons: List<ReviewReasonDto>): List<ReviewReasonDto> = reasons.filter { it.fixable }

    /** The banner needs the server only for a record that was sent back or not accepted. */
    fun bannerNeedsFetch(status: String?): Boolean = isReturned(status)

    /** The record page's review bar asks the server only for a PENDING record and a reviewer. */
    fun barNeedsFetch(status: String?, mayReview: Boolean): Boolean = mayReview && status == "PENDING"

    // --- Files approved with their record (owner decision 2026-10-18), the web's words ---

    private fun files(count: Int): String = "$count file${if (count == 1) "" else "s"}"

    /** The approval's confirmation line, or null when no file goes with it (and nothing is asked). */
    fun alsoApproves(count: Int): String? = if (count > 0) "Also approves ${files(count)}." else null

    /** The receipt after approving one record; [label] is null where the page already names it. */
    fun approvedReceipt(label: String?, mediaApproved: Int): String {
        val what = label?.let { "Approved $it" } ?: "Approved"
        return if (mediaApproved > 0) "$what, with ${files(mediaApproved)}." else "$what."
    }

    /** What a sent decision says: the receipt, or that it waits on this phone. */
    fun approvalOutcome(label: String?, sent: ReviewSend): String = when (sent) {
        is ReviewSend.Queued -> QUEUED
        is ReviewSend.Sent -> approvedReceipt(label, sent.mediaApproved)
    }

    /** A decision made with no signal: it waits in the outbox and sends itself. */
    const val QUEUED = "Saved on this phone. It sends when you reconnect."

    /** One file's Reject panel: the confirm button for the picked reason. */
    fun fileRefusalLabel(reason: ReviewReasonDto?): String =
        if (refusalOutcome(reason) == "NEEDS_REVISION") "Send for revision" else "Reject file"
}

/**
 * The reviewer's one decision on a single file, handed to the file cards of a record's page.
 * Approving a record approves its pending files with it, so a reviewer who disagrees about ONE
 * photograph rejects that file here — before the record is decided or after it was approved.
 * Null (every screen but the record view) draws nothing on the cards.
 */
data class MediaReviewScope(
    val repository: FieldRepository,
    val rejectable: Set<String>,
    val reasons: List<ReviewReasonDto>,
    val onRejected: (String) -> Unit
)

val LocalMediaReview = staticCompositionLocalOf<MediaReviewScope?> { null }

/** Reject on one file card, with the reasons a record is rejected with; queued with no signal. */
@Composable
fun MediaRejectAction(mediaId: String, label: String) {
    val review = LocalMediaReview.current ?: return
    if (mediaId !in review.rejectable) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(mediaId) { mutableStateOf(false) }
    var reasonCode by remember(mediaId) { mutableStateOf("") }
    var note by remember(mediaId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val reason = review.reasons.firstOrNull { it.code == reasonCode }

    fun send() {
        scope.launch {
            busy = true
            error = null
            runCatching { review.repository.rejectMedia(context, mediaId, label, reason?.code, note) }
                .onSuccess { sent ->
                    open = false
                    review.onRejected(
                        when {
                            sent is ReviewSend.Queued -> ReviewFeedbackCopy.QUEUED
                            (sent as ReviewSend.Sent).status == "NEEDS_REVISION" -> "File sent back for revision."
                            else -> "File rejected."
                        }
                    )
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    error = it.apiErrorMessage("That file wasn't rejected.")
                }
            busy = false
        }
    }

    if (!open) {
        ActionRow {
            OutlinedButton(
                onClick = { reasonCode = ""; open = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Reject", maxLines = 1) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ReviewReasonPicker(panel = "reject", reasons = review.reasons, reasonCode = reasonCode) { reasonCode = it }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(if (reason?.needsNote == true) "Note for the uploader (required)" else "Note for the uploader") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        val ready = review.reasons.isEmpty() || ReviewFeedbackCopy.refusalReady(reason, note)
        ActionRow {
            Button(
                onClick = { if (ReviewFeedbackCopy.refusalOutcome(reason) == "REJECTED") confirm = true else send() },
                enabled = !busy && ready,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(if (busy) "Saving…" else ReviewFeedbackCopy.fileRefusalLabel(reason), maxLines = 1) }
            OutlinedButton(onClick = { open = false }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel", maxLines = 1)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reject this file?") },
            text = { Text("$label. Its record keeps its own decision.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; send() }) {
                    Text("Reject file", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

/**
 * The data browser's review-status choice (C21), the web's `DATA_STATUS_OPTIONS`: an export means
 * approved, so Approved is the default and "All statuses" is the escape.
 */
object DataStatusChoice {
    const val DEFAULT = "APPROVED"
    const val ALL = "ALL"

    val options: List<SelectOption> = listOf(
        SelectOption("APPROVED", "Approved"),
        SelectOption("PENDING", "Pending review"),
        SelectOption(ALL, "All statuses")
    )

    /** The `status` query parameter for a choice; every status sends none. */
    fun param(choice: String): String? = choice.takeIf { it.isNotBlank() && it != ALL }
}

/**
 * The reviewer's note above a returned record (A1). Draws nothing when there is none, or offline.
 *
 * [knownStatus] is the record's status as its screen loaded it; the server is asked only when that
 * says the record was sent back or not accepted, so a pending or approved record, or one created
 * offline, costs no request (and no 404).
 */
@Composable
fun ReviewNoteBanner(repository: FieldRepository, recordType: String, recordId: String, knownStatus: String?) {
    var review by remember(recordType, recordId) { mutableStateOf<LatestReviewDto?>(null) }
    var status by remember(recordType, recordId) { mutableStateOf<String?>(null) }
    LaunchedEffect(recordType, recordId, knownStatus) {
        if (!ReviewFeedbackCopy.bannerNeedsFetch(knownStatus)) {
            review = null
            status = null
            return@LaunchedEffect
        }
        runCatching { repository.latestReview(recordType, recordId) }
            .onSuccess { answer ->
                status = answer.status
                review = answer.review
            }
    }
    val current = review
    val state = status
    if (current == null || state == null || !ReviewFeedbackCopy.isReturned(state)) return
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RateReview, contentDescription = null, tint = Coral, modifier = Modifier.size(18.dp))
                Text(
                    ReviewFeedbackCopy.heading(state, current.reviewerName),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
            current.reasonLabel?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            current.notes?.takeIf { it.isNotBlank() && it != current.reasonLabel }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            Text(ReviewFeedbackCopy.nextStep(state), color = Muted, fontSize = 12.sp)
        }
    }
}

/**
 * "Sent back to you (N)" on the home screen (A2). Tapping it lists the records with each note; a row
 * opens the record, whose edit form carries the note again above it.
 */
@Composable
fun SentBackCard(
    repository: FieldRepository,
    sentBack: Int,
    rejected: Int,
    onOpen: (recordType: String, recordId: String) -> Unit
) {
    if (sentBack + rejected <= 0) return
    var open by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ReturnedRecordDto>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (!open || items != null) return@LaunchedEffect
        runCatching { repository.myReturnedRecords() }
            .onSuccess { items = it.items; failed = null }
            .onFailure { failed = "Couldn't load what was sent back." }
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
            ) {
                Icon(Icons.Filled.RateReview, contentDescription = null, tint = Coral, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        ReviewFeedbackCopy.title(sentBack + rejected),
                        display = true,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(ReviewFeedbackCopy.note(sentBack, rejected), color = Muted, fontSize = 12.sp)
                }
                Text(if (open) "Hide" else "Show", color = Coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (open) {
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                val list = items
                if (list == null && failed == null) {
                    Text("Loading…", color = Muted, fontSize = 12.sp)
                }
                list?.forEach { item ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = "Open ${item.label}") { onOpen(item.recordType, item.id) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(item.label, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            listOfNotNull(
                                ReviewFeedbackCopy.heading(item.status, item.reviewerName),
                                item.reasonLabel?.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            color = Muted,
                            fontSize = 12.sp
                        )
                        item.reviewNotes?.takeIf { it.isNotBlank() && it != item.reasonLabel }?.let {
                            Text(it, color = Body, fontSize = 13.sp)
                        }
                    }
                }
                if (list != null && list.isEmpty()) {
                    Text("Nothing sent back.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Approve, send back or reject a PENDING record from its own read-only page (A13), the web's
 * `components/review/RecordReviewBar.tsx`. Drawn only when the server says this reader may review
 * this record, so it never offers a decision the API refuses. [mayReview] is the account's review
 * access; without it nothing is asked.
 */
@Composable
fun RecordReviewBar(
    repository: FieldRepository,
    recordType: String,
    recordId: String,
    knownStatus: String?,
    mayReview: Boolean,
    onDecided: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var canReview by remember(recordType, recordId) { mutableStateOf(false) }
    var late by remember(recordType, recordId) { mutableStateOf(false) }
    var reasons by remember { mutableStateOf<List<ReviewReasonDto>>(emptyList()) }
    var panel by remember(recordType, recordId) { mutableStateOf<String?>(null) }
    var note by remember(recordType, recordId) { mutableStateOf("") }
    var reasonCode by remember(recordType, recordId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReject by remember { mutableStateOf(false) }
    var decided by remember(recordType, recordId) { mutableStateOf(false) }
    /** Files approving this record approves with it; with any, Approve asks first and says how many. */
    var approvesWith by remember(recordType, recordId) { mutableStateOf(0) }
    var confirmApprove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(recordType, recordId, knownStatus, mayReview) {
        if (!ReviewFeedbackCopy.barNeedsFetch(knownStatus, mayReview)) {
            canReview = false
            return@LaunchedEffect
        }
        runCatching { repository.latestReview(recordType, recordId) }
            .onSuccess { canReview = it.canReview && it.status == "PENDING"; late = it.needsAdminApproval }
            .onFailure { canReview = false }
        runCatching { repository.reviewReasons() }.onSuccess { reasons = it }
        runCatching { repository.recordMediaReview(recordType, recordId) }.onSuccess { approvesWith = it.approvesWith }
    }
    if (!canReview || decided) return
    val reason = reasons.firstOrNull { it.code == reasonCode }

    fun decide(action: String) {
        scope.launch {
            busy = true
            error = null
            runCatching {
                when (action) {
                    "approve" -> ReviewFeedbackCopy.approvalOutcome(
                        null, repository.approveRecord(context, recordType, recordId, "Approval")
                    )
                    "revise" -> {
                        repository.reviseRecord(recordType, recordId, note.trim(), reason?.takeIf { it.fixable }?.code)
                        "Sent back for revision."
                    }
                    else -> {
                        val outcome = repository.rejectRecord(recordType, recordId, reason?.code, note)
                        if (outcome == "NEEDS_REVISION") "Sent back for revision." else "Rejected."
                    }
                }
            }.onSuccess { message ->
                decided = true
                onDecided(message)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                error = it.apiErrorMessage("That review action didn't go through.")
            }
            busy = false
        }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RateReview, contentDescription = null, tint = Coral, modifier = Modifier.size(18.dp))
                Text("Waiting for your review", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            if (late) Text("Submitted outside workshop dates — admins only can approve.", color = Coral, fontSize = 12.sp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            ActionRow(
                destructive = {
                    OutlinedButton(
                        onClick = { reasonCode = ""; panel = if (panel == "reject") null else "reject" },
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reject", maxLines = 1) }
                }
            ) {
                Button(
                    onClick = { if (approvesWith > 0) confirmApprove = true else decide("approve") },
                    enabled = !busy
                ) { Text(if (busy && panel == null) "Saving…" else "Approve", maxLines = 1) }
                OutlinedButton(
                    onClick = { reasonCode = ""; panel = if (panel == "revise") null else "revise" },
                    enabled = !busy
                ) { Text("Send for revision", maxLines = 1) }
            }
            if (panel != null) {
                ReviewReasonPicker(panel = panel!!, reasons = reasons, reasonCode = reasonCode) { reasonCode = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = {
                        Text(
                            if (panel == "revise") "What needs to change?"
                            else if (reason?.needsNote == true) "Note for the contributor (required)"
                            else "Note for the contributor (optional)"
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                val ready = if (panel == "revise") note.isNotBlank() else reasons.isEmpty() || ReviewFeedbackCopy.refusalReady(reason, note)
                Button(
                    onClick = {
                        if (panel == "reject" && ReviewFeedbackCopy.refusalOutcome(reason) == "REJECTED") confirmReject = true
                        else decide(panel!!)
                    },
                    enabled = !busy && ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        when {
                            busy -> "Saving…"
                            panel == "revise" -> "Send for revision"
                            else -> ReviewFeedbackCopy.refusalButtonLabel(reason)
                        }
                    )
                }
            }
        }
    }
    if (confirmApprove) {
        AlertDialog(
            onDismissRequest = { confirmApprove = false },
            title = { Text("Approve this record?") },
            text = { Text(ReviewFeedbackCopy.alsoApproves(approvesWith).orEmpty()) },
            confirmButton = { TextButton(onClick = { confirmApprove = false; decide("approve") }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { confirmApprove = false }) { Text("Cancel") } }
        )
    }
    if (confirmReject) {
        AlertDialog(
            onDismissRequest = { confirmReject = false },
            title = { Text("Reject this record?") },
            text = { Text("It shows as rejected to the contributor until they fix it.") },
            confirmButton = {
                TextButton(onClick = { confirmReject = false; decide("reject") }) {
                    Text("Reject record", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReject = false }) { Text("Cancel") } }
        )
    }
}

/**
 * The reason picker on a decision panel: on Reject, the whole short list (required when the server
 * offers one); on Send for revision, only the fixable reasons, optional. Draws nothing when there is
 * nothing to offer.
 */
@Composable
fun ReviewReasonPicker(panel: String, reasons: List<ReviewReasonDto>, reasonCode: String, onPick: (String) -> Unit) {
    val offered = if (panel == "revise") ReviewFeedbackCopy.reviseReasons(reasons) else reasons
    if (offered.isEmpty()) return
    SearchableSelectField(
        label = if (panel == "revise") "Reason (optional)" else "Reason",
        options = offered.map { SelectOption(it.code, it.label) },
        selectedValue = reasonCode,
        placeholder = "Pick a reason",
        includeNone = false,
        clearable = panel == "revise",
        onSelect = onPick
    )
    if (panel == "reject" && offered.firstOrNull { it.code == reasonCode }?.fixable == true) {
        Text("This sends it back to be fixed.", color = Muted, fontSize = 12.sp)
    }
}
