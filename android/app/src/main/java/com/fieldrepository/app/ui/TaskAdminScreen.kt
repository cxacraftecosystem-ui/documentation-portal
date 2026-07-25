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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.material3.Text
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

private val TASK_STATUS_LABELS = mapOf(
    "OPEN" to "Open",
    "IN_PROGRESS" to "In progress",
    "DONE" to "Done",
    "CANCELLED" to "Cancelled"
)

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
                error = reportError
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
            OutlinedTextField(
                value = form.description,
                onValueChange = { form.description = it },
                placeholder = {
                    Text(
                        "What good work looks like here. Markdown is supported.",
                        color = MaterialTheme.field.placeholder
                    )
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
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
    error: String?
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
                    StatTileSpec("Finished", report.doneCount, StatTone.GOOD),
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

            report.assignees.forEach { row -> AssigneeCard(row) }
        }
    }
}

@Composable
private fun AssigneeCard(row: TaskProgressAssigneeDto) {
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
            TaskChip(
                "${row.statusCounts["DONE"] ?: 0} done",
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
            row.tasks.forEach { task -> AssigneeTaskCard(task) }
        }
    }
}

@Composable
private fun AssigneeTaskCard(task: TaskDto) {
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
            StatusPill(task.status)
        }
        DueLine(dueAt = task.dueAt, overdue = task.isOverdue)
        ScopeChipsRow(
            recordTypeLabels = task.recordTypeLabels,
            sections = task.sections,
            artisans = task.artisans,
            targetCount = task.targetCount,
            workshopTitle = task.workshopTitle
        )
        HorizontalDivider(color = MaterialTheme.field.hairline)
        ProgressGapMeter(
            reported = task.progressCount,
            derived = task.derivedCount,
            target = task.targetCount ?: task.derivedTarget
        )
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
        Text(
            "${batch.doneCount} of ${batch.assigneeCount} finished · ${batch.openCount} outstanding" +
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
                    StatusPill(assignee.status)
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

@Composable
private fun DueDateField(value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = {
                val initial = value ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day)) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth
                ).show()
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(value?.let { formatDate(it.toDueInstant()) } ?: "Pick a date")
        }
        if (value != null) {
            TextButton(onClick = { onChange(null) }) { Text("Clear") }
        }
    }
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
private fun StatusPill(status: String) {
    val label = TASK_STATUS_LABELS[status] ?: status
    when (status) {
        "IN_PROGRESS" -> TaskChip(
            label,
            container = MaterialTheme.field.warningContainer,
            content = MaterialTheme.field.onWarningContainer,
            border = MaterialTheme.field.warning
        )
        "DONE" -> TaskChip(
            label,
            container = MaterialTheme.field.successContainer,
            content = MaterialTheme.field.onSuccessContainer,
            border = MaterialTheme.field.success
        )
        "CANCELLED" -> TaskChip(
            label,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            border = MaterialTheme.colorScheme.error
        )
        else -> TaskChip(label, content = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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

private data class StatTileSpec(val label: String, val value: Int, val tone: StatTone = StatTone.NEUTRAL)

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
