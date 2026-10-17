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
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.LatestReviewDto
import com.fieldrepository.app.data.ReturnedRecordDto

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

    fun bulkReceipt(approved: Int, failed: Int): String =
        if (failed == 0) "$approved record${if (approved == 1) "" else "s"} approved."
        else "$approved approved, $failed failed (still ticked)."
}

/** The reviewer's note above a returned record (A1). Draws nothing when there is none, or offline. */
@Composable
fun ReviewNoteBanner(repository: FieldRepository, recordType: String, recordId: String) {
    var review by remember(recordType, recordId) { mutableStateOf<LatestReviewDto?>(null) }
    var status by remember(recordType, recordId) { mutableStateOf<String?>(null) }
    LaunchedEffect(recordType, recordId) {
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
