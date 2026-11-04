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

private val RECORD_TYPE_ORDER = listOf("artisan", "product", "process", "tool", "questionnaire", "media")

private val RECORD_TYPE_LABELS: Map<String, Pair<String, String>> = mapOf(
    "artisan" to ("artisan" to "artisans"),
    "product" to ("product" to "products"),
    "process" to ("process" to "processes"),
    "tool" to ("tool" to "tools"),
    "questionnaire" to ("questionnaire interview" to "questionnaire interviews"),
    "media" to ("media file" to "media files")
)

const val TASK_STATUS_OPEN = "OPEN"
const val TASK_STATUS_IN_PROGRESS = "IN_PROGRESS"

const val TASK_STATUS_SUBMITTED = "SUBMITTED"

const val TASK_STATUS_DONE = "DONE"
const val TASK_STATUS_CANCELLED = "CANCELLED"

val TASK_OUTSTANDING_STATUSES = setOf(TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED)

private val TASK_STATUS_LABELS = mapOf(
    TASK_STATUS_OPEN to "To do",
    TASK_STATUS_IN_PROGRESS to "In progress",
    TASK_STATUS_SUBMITTED to "Under review",
    TASK_STATUS_DONE to "Approved",
    TASK_STATUS_CANCELLED to "Cancelled"
)

fun taskStatusLabel(status: String): String = TASK_STATUS_LABELS[status] ?: status

fun taskStatusText(task: TaskDto): String = task.statusLabel.ifBlank { taskStatusLabel(task.status) }

fun taskAwaitingReview(task: TaskDto): Boolean =
    task.isAwaitingReview ?: (task.status == TASK_STATUS_SUBMITTED)

fun taskOutstanding(task: TaskDto): Boolean =
    task.isOutstanding ?: (task.status in TASK_OUTSTANDING_STATUSES)

fun taskOverrideTargets(status: String): List<String> = when (status) {
    TASK_STATUS_SUBMITTED -> listOf(TASK_STATUS_DONE, TASK_STATUS_IN_PROGRESS)
    TASK_STATUS_DONE -> listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_OPEN)
    TASK_STATUS_OPEN, TASK_STATUS_IN_PROGRESS -> listOf(TASK_STATUS_DONE)
    else -> emptyList()
}

fun taskOverrideActionLabel(current: String, next: String): String = when {
    next == TASK_STATUS_DONE && current == TASK_STATUS_SUBMITTED -> "Approve"
    next == TASK_STATUS_DONE -> "Mark done for them"
    current == TASK_STATUS_SUBMITTED -> "Send back"
    else -> "Back to ${taskStatusLabel(next).lowercase()}"
}

data class TaskOverrideCopy(
    val title: String,
    val body: String,
    val note: String,
    val confirmLabel: String
)

fun taskOverrideConfirm(current: String, next: String, assigneeName: String?): TaskOverrideCopy {
    val whom = assigneeName?.takeIf { it.isNotBlank() } ?: "this assignee"
    return when {
        next == TASK_STATUS_DONE && current == TASK_STATUS_SUBMITTED -> TaskOverrideCopy(
            title = "Approve this work?",
            body = "$whom handed this in. Approve to mark it finished.",
            note = "It leaves their list. Completion stays dated the day they handed it in, not today.",
            confirmLabel = "Approve"
        )
        next == TASK_STATUS_DONE -> TaskOverrideCopy(
            title = "Mark this done for them?",
            body = "$whom hasn't handed this in. You'd be marking it finished for them.",
            note = "If unsure, ask them first.",
            confirmLabel = "Mark done for them"
        )
        current == TASK_STATUS_SUBMITTED -> TaskOverrideCopy(
            title = "Send this back?",
            body = "$whom marked this finished. Sending it back reopens it.",
            note = "Let them know why.",
            confirmLabel = "Send back"
        )
        else -> TaskOverrideCopy(
            title = "Reopen this task?",
            body = "This is approved. Reopening puts it back on ${whom}'s list as " +
                "${taskStatusLabel(next).lowercase()}.",
            note = "The completion date is cleared. Their records are kept.",
            confirmLabel = "Back to ${taskStatusLabel(next).lowercase()}"
        )
    }
}

data class TaskReviewNotice(val title: String, val body: String, val action: String)

fun taskReviewNotice(task: TaskDto, assignerName: String? = null): TaskReviewNotice? {
    if (!taskAwaitingReview(task)) return null
    val who = assignerName?.trim().orEmpty()
    return TaskReviewNotice(
        title = "Handed in — under review",
        body = "You marked this done. " +
            (if (who.isNotEmpty()) "$who or another admin" else "An admin or the master admin") +
            " will approve it; it stays on your list until then.",
        action = "Nothing more to do unless it comes back."
    )
}

fun assigneeStatusChoices(status: String): List<String> = when (status) {
    TASK_STATUS_OPEN -> listOf(TASK_STATUS_IN_PROGRESS, TASK_STATUS_SUBMITTED)
    TASK_STATUS_IN_PROGRESS -> listOf(TASK_STATUS_OPEN, TASK_STATUS_SUBMITTED)
    TASK_STATUS_SUBMITTED -> listOf(TASK_STATUS_IN_PROGRESS)
    else -> emptyList()
}

fun assigneeStatusActionLabel(current: String, next: String): String = when {
    next == TASK_STATUS_SUBMITTED -> "Mark done"
    current == TASK_STATUS_SUBMITTED -> "Withdraw"
    next == TASK_STATUS_IN_PROGRESS -> "Start"
    else -> "Back to ${taskStatusLabel(next).lowercase()}"
}

fun assigneeStatusConfirmation(next: String): String = when (next) {
    TASK_STATUS_SUBMITTED -> "Sent for approval. It stays on your list until approved."
    TASK_STATUS_OPEN -> "Moved back to your to-do list."
    TASK_STATUS_IN_PROGRESS -> "Marked in progress."
    else -> "Marked ${taskStatusLabel(next).lowercase()}."
}

fun taskBarPercent(task: TaskDto): Int? =
    if (task.statusLabel.isBlank()) task.percentComplete else task.effectivePercent

fun taskProgressCaption(task: TaskDto): String =
    task.progressLabel?.takeIf { it.isNotBlank() }
        ?: task.targetCount?.let { "${task.progressCount} of $it reported" }
        ?: "${task.progressCount} reported"

fun taskProgressIsMeasured(task: TaskDto): Boolean = task.progressSource == "derived"

data class TaskSummaryBar(
    val percent: Int?,
    val caption: String,
    val measured: String,
    val caveats: List<String>
)

fun taskSummaryBar(summary: TaskSummaryDto): TaskSummaryBar {
    val active = maxOf(0, summary.taskCount - summary.cancelledCount)
    val caveats = mutableListOf<String>()
    if (summary.truncated) {
        caveats += "Counts may be incomplete."
    }
    if (summary.derivationSkipped) {
        caveats += "Too many tasks to count from records."
    }

    if (summary.taskCount == 0) {
        return TaskSummaryBar(null, "No tasks have been assigned to you yet.", "none", caveats)
    }
    if (active == 0) {
        return TaskSummaryBar(
            null,
            "All your tasks were withdrawn.",
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
            "No progress yet.",
            measured,
            caveats
        )
    }

    val caption = when (measured) {
        "all" -> "All $active counted from your records — updates as you record."
        "some" -> "${summary.measuredCount} of $active counted from records; the rest from your reports."
        else -> "Based on what you've reported."
    }
    return TaskSummaryBar(summary.percentComplete.coerceIn(0, 100), caption, measured, caveats)
}

fun taskDueSentence(summary: TaskSummaryDto): String? {
    val parts = mutableListOf<String>()
    if (summary.overdueCount > 0) {
        val isAre = if (summary.overdueCount == 1) "task is" else "tasks are"
        val its = if (summary.overdueCount == 1) "its" else "their"
        parts += "${summary.overdueCount} $isAre past $its due date"
    }
    if (summary.dueSoonCount > 0) parts += "${summary.dueSoonCount} due within the next two days"
    formatDate(summary.nextDueAt).takeIf { it != "-" }?.let { parts += "next due $it" }
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ") + "."
}

fun taskAwaitingReviewSentence(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 task awaiting approval."
    else -> "$count tasks awaiting approval."
}

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

private const val EMPTY_SCOPE_MESSAGE =
    "A task needs work in it: pass recordTypes " +
        "(any of ['artisan', 'product', 'process', 'tool', 'questionnaire', 'media']) and/or sectionIds."

private fun roleLabelOf(role: String?): String = ROLE_LABELS[role] ?: role.orEmpty()

private fun TaskUserDto.displayRole(): String = roleLabel.ifBlank { roleLabelOf(role) }

private fun orderRecordTypes(values: Collection<String>): List<String> {
    val wanted = values.map { it.trim().lowercase() }.toSet()
    return RECORD_TYPE_ORDER.filter { it in wanted }
}

private fun recordTypeLabel(kind: String, plural: Boolean = true): String =
    RECORD_TYPE_LABELS[kind]?.let { if (plural) it.second else it.first } ?: kind

private fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> "${items.dropLast(1).joinToString(", ")} and ${items.last()}"
}

private fun plural(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

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

private fun artisanPhrase(artisanNames: List<String>, workshopTitle: String?): String = when {
    artisanNames.size == 1 -> "for ${artisanNames[0]}"
    artisanNames.size == 2 -> "for ${artisanNames[0]} and ${artisanNames[1]}"
    artisanNames.isNotEmpty() -> "for ${artisanNames.size} named artisans"
    !workshopTitle.isNullOrBlank() -> "for every artisan at $workshopTitle"
    else -> "for every artisan in scope"
}

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

private enum class GapTone { UNKNOWN, IDLE, MATCH, AHEAD, BEHIND }

private data class ProgressGap(val tone: GapTone, val label: String)

private fun progressGap(reported: Int, derived: Int?): ProgressGap {
    if (derived == null) return ProgressGap(GapTone.UNKNOWN, "Count unavailable")
    if (reported == 0 && derived == 0) return ProgressGap(GapTone.IDLE, "Nothing reported or recorded yet")
    val delta = reported - derived
    return when {
        delta > 0 -> ProgressGap(GapTone.BEHIND, "$delta more reported than recorded")
        delta < 0 -> ProgressGap(GapTone.AHEAD, "${-delta} more recorded than reported")
        else -> ProgressGap(GapTone.MATCH, "Reported figure matches what is recorded")
    }
}

private fun parseIsoDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

private fun formatDate(value: String?): String =
    parseIsoDate(value)?.let {
        runCatching { it.format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }.getOrNull()
    } ?: "-"

private fun LocalDate.toDueInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private enum class TaskAdminTab(val label: String) {
    ASSIGN("Assign work"),
    PROGRESS("Accountability"),
    BATCHES("Assignments")
}

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

    var refreshToken by remember { mutableIntStateOf(0) }

    val form = remember { AssignmentFormState() }

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
            "Assign work and track it against what's recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        optionsError?.let { ErrorBanner(it) }

        PanelCard {
            StepHeader(number = 1, title = "Workshop")
            SingleSelectField(
                label = "Scope everything below to",
                value = workshopId,
                placeholder = if (optionsLoading) "Loading workshops..." else "All workshops",
                options = listOf("" to "All workshops") + workshops.map { workshop ->
                    workshop.id to (workshop.place?.takeIf { it.isNotBlank() }
                        ?.let { "${workshop.title} · $it" } ?: workshop.title)
                },
                onSelect = { value ->
                    workshopId = value
                    batchPage = 1
                }
            )
            Text(
                if (workshopTitle != null) {
                    "Showing $workshopTitle only."
                } else {
                    "Pick a workshop to narrow this page."
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
                                refreshAll()
                                onMessage(
                                    if (nextStatus == TASK_STATUS_DONE) {
                                        "Approved."
                                    } else {
                                        "Sent back as ${taskStatusLabel(nextStatus).lowercase()}."
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

    val roleCounts = allAssignees.groupingBy { it.role }.eachCount()
    val roleOptions = listOf("" to "Everyone below me (${allAssignees.size})") +
        ROLES_BY_RANK.filter { roleCounts.containsKey(it) }.map { role ->
            val label = allAssignees.firstOrNull { it.role == role }?.displayRole() ?: roleLabelOf(role)
            role to "$label (${roleCounts[role]})"
        }
    val visibleAssignees =
        if (form.roleFilter.isBlank()) allAssignees else allAssignees.filter { it.role == form.roleFilter }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        PanelCard {
            StepHeader(
                number = 2,
                title = "Who does the work",
                hint = "People ranked below you. Each gets their own task."
            )
            SingleSelectField(
                label = "Filter by tier",
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
                onSelectedChange = { next ->
                    form.assigneeIds = (form.assigneeIds - visibleAssignees.map { it.id }.toSet()) + next
                }
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

        PanelCard {
            StepHeader(
                number = 3,
                title = "What they must produce",
                hint = "Combine record types and sections. No artisans means all of them."
            )
            MultiSelectField(
                label = "Record types",
                placeholder = "Artisans, products, tools...",
                emptyLabel = "No record types available",
                options = allRecordTypes.map { kind ->
                    kind.value to kind.pluralLabel.replaceFirstChar { it.uppercase() }
                },
                selected = form.recordTypes,
                onSelectedChange = { form.recordTypes = it }
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
                onSelectedChange = { form.sectionIds = it }
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
                onSelectedChange = { form.artisanIds = it }
            )
            PickedHint(
                labels = artisanNames,
                empty = if (workshopId.isNotBlank()) "Every artisan at this workshop." else "Every artisan."
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
                    "Optional."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PanelCard {
            StepHeader(
                number = 4,
                title = "Title, brief and deadline",
                hint = "Leave empty to generate one."
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
            RecordProseField(
                label = null,
                value = form.description,
                onValueChange = { form.description = it },
                placeholder = "What good work looks like. Markdown supported.",
                minLines = 3,
                dictate = true,
            )
            FieldLabel("Due date")
            DueDateField(value = form.dueDate, onChange = { form.dueDate = it })
        }

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
                    "Pick people and work to preview."
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.field.hairline)
            SummaryRow("Task title", form.title.trim().ifBlank { generatedTitle })
            SummaryRow("Workshop", workshopTitle ?: "Not tied to a workshop")
            SummaryRow(
                "Counted against",
                if (derivedTarget != null) {
                    "$derivedTarget item${if (derivedTarget == 1) "" else "s"} per person"
                } else {
                    "No fixed denominator"
                }
            )
            SummaryRow("Due", form.dueDate?.let { formatDate(it.toDueInstant()) } ?: "No deadline")

            if (!hasWork && hasPeople) {
                WarningLine(
                    "Pick at least one record type or section."
                )
            }
            if (hasWork && !hasPeople) {
                WarningLine("Pick at least one person.")
            }
        }

        form.error?.let { ErrorBanner(it) }

        Button(
            onClick = {
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
            body = "Assigned work and progress will show here."
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatTileGrid(
                listOf(
                    StatTileSpec("People with work", report.assigneeCount),
                    StatTileSpec("Tasks in scope", report.taskCount),
                    StatTileSpec("Still outstanding", report.openCount),
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
                    "Partial — pick one workshop to see all."
                )
            }

            Text(
                "Reported is what they say; recorded is what they saved. Ask about wide gaps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.awaitingReviewCount > 0) {
                Text(
                    "\"Waiting on you\" is handed-in work to approve.",
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
            style = MaterialTheme.typography.titleMedium,
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
            Text(
                "Counted automatically" +
                    (task.derivedArtisanCount?.let { ", across $it in scope" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        task.derivedBreakdown["unlinkedSections"]?.takeIf { it > 0 }?.let { orphans ->
            Text(
                "$orphans answered ${if (orphans == 1) "section isn't" else "sections aren't"} counted — link the interview to an artisan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.onWarningContainer
            )
        }
    }
}

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
    ActionRow {
        targets.forEach { next ->
            val approving = next == TASK_STATUS_DONE && task.status == TASK_STATUS_SUBMITTED
            if (approving) {
                Button(enabled = !busy, onClick = { confirming = next }) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Saving..." else taskOverrideActionLabel(task.status, next))
                }
            } else {
                OutlinedButton(enabled = !busy, onClick = { confirming = next }) {
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
            body = "Each assignment appears here with its group progress."
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
                            " · recorded ${assignee.derivedCount?.toString() ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    StatusPill(assignee.status, assignee.statusLabel.ifBlank { taskStatusLabel(assignee.status) })
                }
            }
        }
    }
}

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
    label: String,
    value: String,
    placeholder: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedValue = value,
        placeholder = placeholder,
        includeNone = false,
        onSelect = onSelect
    )
}

@Composable
private fun MultiSelectField(
    label: String,
    placeholder: String,
    emptyLabel: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    required: Boolean = false,
    onSelectedChange: (Set<String>) -> Unit
) {
    SearchableMultiSelectField(
        label = label + if (required) " *" else "",
        options = remember(options) { options.asSelectOptions() },
        selected = selected,
        placeholder = placeholder,
        emptyMessage = emptyLabel,
        showChips = false,
        onSelectedChange = onSelectedChange
    )
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
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove ${user.name}",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun DueDateField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    CalendarField(
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

@Composable
private fun StatusPill(task: TaskDto) = StatusPill(task.status, taskStatusText(task))

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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            user.displayRole(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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
            .background(FieldProgressDefaults.trackColor, CircleShape)
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
                "Recorded",
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
                "No target set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.placeholder
            )
        }
    }
}

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
    val hint: String? = null
)

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
        ActionRow {
            OutlinedButton(
                onClick = { onPage(page - 1) },
                enabled = page > 1,
            ) { Text("Previous") }
            OutlinedButton(
                onClick = { onPage(page + 1) },
                enabled = page < pages,
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
        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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

@Composable
fun AssigneeTaskSummaryCard(
    summary: TaskSummaryDto?,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    if (summary == null) {
        if (loading) LoadingLine("Loading your progress...")
        return
    }
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
        if (task.status == TASK_STATUS_DONE) {
            Text(
                "Approved and finished.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    ActionRow {
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
        Text(
            "An admin approves it next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
