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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.ReviewSend
import com.fieldrepository.app.data.LatestReviewDto
import com.fieldrepository.app.data.RecordMediaReviewDto
import com.fieldrepository.app.data.ReturnedRecordDto
import com.fieldrepository.app.data.ReviewReasonDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

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

    fun recordTypeFor(entryName: String): String? = when (entryName) {
        "ARTISAN" -> "artisan"
        "PRODUCT" -> "product"
        "PROCESS" -> "process"
        "TOOL" -> "tool"
        "QUESTIONNAIRE" -> "questionnaire"
        "WORKSHOP" -> "workshop"
        else -> null
    }

    fun approveLabel(count: Int): String = "Approve $count"

    fun bulkReceipt(approved: Int, failed: Int, fileCount: Int = 0): String {
        val withFiles = if (fileCount > 0) ", with ${files(fileCount)}" else ""
        return if (failed == 0) "$approved record${if (approved == 1) "" else "s"} approved$withFiles."
        else "$approved approved${withFiles.removePrefix(",")}, $failed failed (still ticked)."
    }

    fun refusalOutcome(reason: ReviewReasonDto?): String = if (reason?.fixable == true) "NEEDS_REVISION" else "REJECTED"

    fun refusalButtonLabel(reason: ReviewReasonDto?): String =
        if (refusalOutcome(reason) == "NEEDS_REVISION") "Send for revision" else "Reject record"

    fun refusalReady(reason: ReviewReasonDto?, note: String): Boolean =
        reason != null && (!reason.needsNote || note.isNotBlank())

    fun reviseReasons(reasons: List<ReviewReasonDto>): List<ReviewReasonDto> = reasons.filter { it.fixable }

    fun bannerNeedsFetch(status: String?): Boolean = isReturned(status)

    fun barNeedsFetch(status: String?, mayReview: Boolean): Boolean = mayReview && status == "PENDING"

    private fun files(count: Int): String = "$count file${if (count == 1) "" else "s"}"

    fun alsoApproves(count: Int): String? = if (count > 0) "Also approves ${files(count)}." else null

    fun approvedReceipt(label: String?, mediaApproved: Int): String {
        val what = label?.let { "Approved $it" } ?: "Approved"
        return if (mediaApproved > 0) "$what, with ${files(mediaApproved)}." else "$what."
    }

    fun approvalOutcome(label: String?, sent: ReviewSend): String = when (sent) {
        is ReviewSend.Queued -> QUEUED
        is ReviewSend.Sent -> approvedReceipt(label, sent.mediaApproved)
    }

    const val QUEUED = "Saved on this phone. It sends when you reconnect."

    fun fileRefusalLabel(reason: ReviewReasonDto?): String =
        if (refusalOutcome(reason) == "NEEDS_REVISION") "Send for revision" else "Reject file"
}

data class MediaReviewScope(
    val repository: FieldRepository,
    val rejectable: Set<String>,
    val reasons: List<ReviewReasonDto>,
    val onRejected: (String) -> Unit
)

val LocalMediaReview = staticCompositionLocalOf<MediaReviewScope?> { null }

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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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

object DataStatusChoice {
    const val ALL = "ALL"
    const val DEFAULT = ALL

    val options: List<SelectOption> = listOf(
        SelectOption(ALL, "All"),
        SelectOption("APPROVED", "Approved"),
        SelectOption("PENDING", "Pending"),
        SelectOption("NEEDS_REVISION", "Needs revision"),
        SelectOption("REJECTED", "Rejected")
    )

    fun param(choice: String): String? = choice.takeIf { it.isNotBlank() && it != ALL }
}

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
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            current.reasonLabel?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            current.notes?.takeIf { it.isNotBlank() && it != current.reasonLabel }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            Text(ReviewFeedbackCopy.nextStep(state), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(ReviewFeedbackCopy.note(sentBack, rejected), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (open) "Hide" else "Show", color = Coral, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (open) {
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                val list = items
                if (list == null && failed == null) {
                    Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                        Text(item.label, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                ReviewFeedbackCopy.heading(item.status, item.reviewerName),
                                item.reasonLabel?.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        item.reviewNotes?.takeIf { it.isNotBlank() && it != item.reasonLabel }?.let {
                            Text(it, color = Body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (list != null && list.isEmpty()) {
                    Text("Nothing sent back.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun RecordReviewBar(
    repository: FieldRepository,
    recordType: String,
    recordId: String,
    knownStatus: String?,
    mayReview: Boolean,
    mediaReview: Deferred<RecordMediaReviewDto?>? = null,
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
    var approvesWith by remember(recordType, recordId) { mutableStateOf(0) }
    var confirmApprove by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sharedMediaReview by rememberUpdatedState(mediaReview)
    LaunchedEffect(recordType, recordId, knownStatus, mayReview) {
        if (!ReviewFeedbackCopy.barNeedsFetch(knownStatus, mayReview)) {
            canReview = false
            return@LaunchedEffect
        }
        runCatching { repository.latestReview(recordType, recordId) }
            .onSuccess { canReview = it.canReview && it.status == "PENDING"; late = it.needsAdminApproval }
            .onFailure { canReview = false }
        runCatching { repository.reviewReasons() }.onSuccess { reasons = it }
        val shared = sharedMediaReview?.await()
        if (shared != null) {
            approvesWith = shared.approvesWith
        } else {
            runCatching { repository.recordMediaReview(recordType, recordId) }.onSuccess { approvesWith = it.approvesWith }
        }
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
                Text("Waiting for your review", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            if (late) Text("Submitted outside workshop dates — admins only can approve.", color = Coral, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
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
        Text("This sends it back to be fixed.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
