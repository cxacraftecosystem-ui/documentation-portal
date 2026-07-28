package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One artisan's questionnaire, gathered from every interview they sat in — the Android mirror of the
 * web's `/questionnaire/consolidated` (the picker) and `/questionnaire/consolidated/[artisanId]`
 * (the document).
 *
 * WHY IT EXISTS. The repository stores one interview per exact SET of artisans, which is the right
 * storage rule and is untouched by this screen. Its consequence is that an artisan's answers live
 * across every set they appear in, and on the live repository that is the normal case: thirteen of
 * sixteen artisans are in more than one interview and three are in four. Reading such a person today
 * means opening four entries.
 *
 * WHAT IT REFUSES TO DO, mirrored one-for-one with the backend and the web:
 *  - it never hides the source: every answer names the sitting, its date and how many people were in it;
 *  - it never resolves a disagreement: two different answers to one question are both shown, most
 *    recent first, flagged;
 *  - it never guesses a speaker. `QuestionnaireResponse` is unique on (interviewId, questionId) and
 *    has no artisan column — `answeredById` is the fieldworker — so in a group sitting the record
 *    simply does not say who answered, and this screen says so on the row.
 *
 * THE DTOs LIVE HERE, not in `data/ApiModels.kt`, and must stay here: `data/FieldRepositoryApi.kt` and
 * `data/FieldRepository.kt` both import [ConsolidatedQuestionnaireDto] from this package, so renaming,
 * moving or narrowing the visibility of these types breaks the data layer's compile. The private
 * Retrofit interface this file used to carry is gone — `FieldRepository.consolidatedQuestionnaire` is
 * the shared method now, and it is the one that also takes the workshop scope.
 */

/* ------------------------------------------------------------------------------------------------
 * Wire format — mirrors backend/app/services/questionnaire_consolidation.py
 * ---------------------------------------------------------------------------------------------- */

/** The sitting had one artisan: what was recorded in it is theirs. */
const val ATTRIBUTION_SOLE = "SOLE"

/** Several artisans were present and the data does not record which of them answered. */
const val ATTRIBUTION_GROUP = "GROUP"

@Serializable
data class ConsolidatedArtisanDto(
    val id: String,
    val name: String,
    val craftName: String? = null,
    val place: String? = null
)

@Serializable
data class ConsolidatedSourceDto(
    val id: String,
    val title: String,
    val date: String? = null,
    val dateBasis: String = "unknown",
    val status: String = "",
    val workshopTitle: String? = null,
    val artisanCount: Int = 0,
    val coParticipants: List<String> = emptyList(),
    val attribution: String = ATTRIBUTION_SOLE
)

@Serializable
data class ConsolidatedAnswerDto(
    val kind: String,
    val sourceId: String,
    val answerText: String? = null,
    val notes: String? = null,
    val recordedByName: String? = null,
    val mediaId: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val transcriptText: String? = null,
    val transcriptStatus: String? = null,
    val interviewId: String,
    val interviewTitle: String,
    val interviewDate: String? = null,
    val dateBasis: String = "unknown",
    val interviewStatus: String = "",
    val workshopTitle: String? = null,
    val artisanCount: Int = 0,
    val coParticipants: List<String> = emptyList(),
    val attribution: String = ATTRIBUTION_SOLE
)

@Serializable
data class ConsolidatedQuestionDto(
    val id: String,
    val prompt: String,
    val sortOrder: Int = 0,
    val conflict: Boolean = false,
    val answers: List<ConsolidatedAnswerDto> = emptyList()
)

@Serializable
data class ConsolidatedSectionDto(
    val id: String,
    val code: String,
    val title: String,
    val sortOrder: Int = 0,
    val questions: List<ConsolidatedQuestionDto> = emptyList(),
    val recordings: List<ConsolidatedAnswerDto> = emptyList()
)

@Serializable
data class ConsolidatedSummaryDto(
    val interviewCount: Int = 0,
    val groupSittingCount: Int = 0,
    val soleSittingCount: Int = 0,
    val answeredQuestionCount: Int = 0,
    val typedAnswerCount: Int = 0,
    val recordedAnswerCount: Int = 0,
    val unfiledRecordingCount: Int = 0,
    val conflictCount: Int = 0
)

@Serializable
data class ConsolidatedQuestionnaireDto(
    val artisan: ConsolidatedArtisanDto,
    @SerialName("generatedAt") val generatedAt: String? = null,
    val interviews: List<ConsolidatedSourceDto> = emptyList(),
    val sections: List<ConsolidatedSectionDto> = emptyList(),
    val unfiled: List<ConsolidatedAnswerDto> = emptyList(),
    val summary: ConsolidatedSummaryDto = ConsolidatedSummaryDto()
)

/* ------------------------------------------------------------------------------------------------
 * Screen
 * ---------------------------------------------------------------------------------------------- */

private val AmberSurface = Color(0x1FCD9200)
private val AmberInk = Color(0xFF8A5600)

/** dd MMM yyyy from the API's ISO-8601, without pulling in a formatter for four fields. */
private fun shortDate(value: String?): String {
    if (value.isNullOrBlank() || value.length < 10) return "-"
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val year = value.substring(0, 4)
    val month = value.substring(5, 7).toIntOrNull() ?: return value.substring(0, 10)
    val day = value.substring(8, 10)
    return "$day ${months.getOrElse(month - 1) { "?" }} $year"
}

/** Which artisan's document is open, and the name to head it with before the document arrives. */
private data class PickedArtisan(val id: String, val name: String)

/**
 * The whole feature, hosted in the shared chrome: a bare [Column] and nothing else, because HomeScreen
 * draws the scaffold, the app bar, the scroll and the back button. Nothing here may scroll itself — a
 * nested `verticalScroll` (or a `LazyColumn`, which this screen used to be) is measured with an
 * infinite height budget inside the parent's scroll and throws before it can draw.
 *
 * TWO VIEWS, ONE DESTINATION. The web gives the document its own URL, and that is the point there: the
 * document is the thing a researcher quotes, so it needs an address. Android has no addresses, so the
 * picker and the document are one screen with one piece of state — and the document therefore carries
 * its own way back to the list, because the chrome's back arrow leaves the destination entirely.
 */
@Composable
fun ConsolidatedQuestionnaireScreen(
    repository: FieldRepository,
    onError: (String) -> Unit
) {
    var opened by remember { mutableStateOf<PickedArtisan?>(null) }
    val picked = opened

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (picked == null) {
            ConsolidatedIndex(
                repository = repository,
                onError = onError,
                onOpen = { artisan -> opened = PickedArtisan(artisan.id, artisan.name) }
            )
        } else {
            ConsolidatedDocument(
                repository = repository,
                picked = picked,
                onError = onError,
                onBack = { opened = null }
            )
        }
    }
}

/* ------------------------------------------------------------------------------------------------
 * The picker — the web's /questionnaire/consolidated
 * ---------------------------------------------------------------------------------------------- */

/**
 * Picks the artisan whose consolidated questionnaire to read.
 *
 * The workshop scope here narrows WHO IS LISTED, defaulting to the most recent workshop like every
 * other screen that reads a workshop's output. It deliberately does NOT ride into the document — see
 * [ConsolidatedDocument] for why the document is read whole by default, and for the one sentence on
 * this screen that keeps the difference from being a surprise.
 */
@Composable
private fun ConsolidatedIndex(
    repository: FieldRepository,
    onError: (String) -> Unit,
    onOpen: (ArtisanDto) -> Unit
) {
    val scope = rememberWorkshopScope(repository = repository, onError = onError)
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(scope.settled, scope.requestKey) {
        // Held until the picker has settled, or the first request goes out unscoped and is replaced a
        // moment later — two requests, and a visible flash of the wrong list.
        if (!scope.settled) return@LaunchedEffect
        loading = true
        // Cleared BEFORE the request: a failed load followed by a scope change would otherwise keep
        // the old error card over a request already in flight, which reads as the retry not happening.
        error = null
        runCatching { repository.artisans(workshopIds = scope.workshopIds) }
            .onSuccess {
                artisans = it
                error = null
                loading = false
            }
            .onFailure { failure ->
                // Re-keying this effect cancels the call in flight and `runCatching` catches that
                // cancellation like any other Throwable. Rethrowing also skips `loading = false`, and
                // must: the pass that replaced this one already owns the flag.
                if (failure is CancellationException) throw failure
                val message = failure.apiErrorMessage("Unable to load artisans.")
                error = message
                onError(message)
                loading = false
            }
    }

    val filtered = remember(artisans, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) artisans
        else artisans.filter { artisan ->
            listOfNotNull(artisan.name, artisan.craft?.name, artisan.place)
                .joinToString(" ")
                .lowercase()
                .contains(needle)
        }
    }

    ConsolidatedCard(title = "Consolidated questionnaire", icon = Icons.Filled.Layers) {
        Text(
            "An interview is stored once per exact set of artisans, so most artisans' answers are " +
                "spread across several entries. Pick an artisan to read everything they contributed " +
                "as one document, with each answer still naming the interview it came from.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search artisans by name, craft or place") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        WorkshopScopeSelect(scope = scope, label = "Workshops to read")

        HorizontalDivider(color = MaterialTheme.field.hairline)

        // The one sentence that stops a narrowed list from being read as a narrowed document. On the
        // web the chosen scope rides the link into the document; here the document opens on every
        // interview and carries its own control, so this line names the difference rather than leaving
        // a reader to assume the two match.
        Text(
            "This list is the artisans in the chosen workshops. Each document opens on every " +
                "interview that artisan took part in — narrow it there to read one workshop's account.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
    }

    when {
        error != null -> ConsolidatedCard(title = "Could not load artisans") {
            Text(error.orEmpty(), color = MaterialTheme.field.body, fontSize = 13.sp)
        }

        loading -> ConsolidatedCard(title = "Loading artisans…") {
            Text(
                "Reading the artisans in scope.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        filtered.isEmpty() -> ConsolidatedCard(title = "No artisans match") {
            Text(
                if (!scope.isAllRecords) {
                    "No artisan in the chosen workshops matches. Widen the workshop scope, or " +
                        "choose All records."
                } else {
                    "Try a different name, craft or place."
                },
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
        }

        else -> ConsolidatedCard(title = "Artisans (${filtered.size})") {
            filtered.forEach { artisan ->
                ArtisanPickRow(artisan = artisan, onOpen = { onOpen(artisan) })
            }
        }
    }
}

@Composable
private fun ArtisanPickRow(artisan: ArtisanDto, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            // The label, not just the tap target: a row that reads "Ram Kumar, button" to TalkBack
            // says nothing about where it goes, and this one goes somewhere quite specific.
            .clickable(onClickLabel = "Read ${artisan.name}'s consolidated questionnaire") { onOpen() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artisan.name,
                display = true,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(artisan.craft?.name, artisan.place.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .ifBlank { "No craft recorded" },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/* ------------------------------------------------------------------------------------------------
 * The document — the web's /questionnaire/consolidated/[artisanId]
 * ---------------------------------------------------------------------------------------------- */

/**
 * One artisan read as a single document.
 *
 * THE SCOPE DEFAULTS TO EVERY WORKSHOP, and this is the one screen where that is right —
 * `defaultToMostRecent = false`. This is a document, the artifact a researcher cites, and its default
 * meaning has always been "everything this artisan has ever said". Narrowing it to one workshop
 * without being asked would change what a citation means.
 *
 * The whole document is recomputed server-side over the narrowed interviews, so the summary counts and
 * the divergence flags describe the scope rather than being filtered after the fact: a disagreement
 * between two workshops is not a disagreement within one of them. The heading sentence follows the
 * scope for the same reason — "all N interviews they took part in" is a claim about the whole corpus
 * and must not be repeated under a filter.
 *
 * THE SCOPE CONTROL IS RENDERED IN EVERY BRANCH, including both failures. Narrowing to a workshop this
 * artisan was not at is an ordinary thing to do by accident, and it produces an empty or failed
 * document — so the control that caused it has to still be on screen to undo it.
 */
@Composable
private fun ConsolidatedDocument(
    repository: FieldRepository,
    picked: PickedArtisan,
    onError: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberWorkshopScope(
        repository = repository,
        defaultToMostRecent = false,
        onError = onError
    )
    var data by remember(picked.id) { mutableStateOf<ConsolidatedQuestionnaireDto?>(null) }
    var error by remember(picked.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(picked.id, scope.settled, scope.requestKey) {
        if (!scope.settled) return@LaunchedEffect
        error = null
        runCatching {
            repository.consolidatedQuestionnaire(picked.id, workshopIds = scope.workshopIds)
        }
            .onSuccess { data = it; error = null }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                val message = failure.apiErrorMessage("Unable to load this questionnaire.")
                error = message
                onError(message)
            }
    }

    val current = data
    val artisan = current?.artisan
    val subtitle = listOfNotNull(artisan?.craftName, artisan?.place)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    ConsolidatedCard(
        title = artisan?.name?.ifBlank { picked.name } ?: picked.name,
        icon = Icons.Filled.Layers
    ) {
        // Not the chrome's back arrow, which leaves the whole destination: this steps from the document
        // to the picker, the move the web makes by following its own URL back.
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("All artisans", fontSize = 12.sp)
        }
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        if (current != null) {
            val count = current.summary.interviewCount
            val plural = if (count == 1) "" else "s"
            Text(
                if (!scope.isAllRecords) {
                    "Questionnaire answers from the $count interview$plural this artisan took part " +
                        "in within the chosen workshops."
                } else {
                    "Every questionnaire answer recorded for this artisan, gathered from all " +
                        "$count interview$plural they took part in."
                },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }
        WorkshopScopeSelect(scope = scope, label = "Workshops in this document")
    }

    when {
        error != null && current == null ->
            ConsolidatedCard(title = "This questionnaire could not be loaded") {
                Text(error.orEmpty(), color = MaterialTheme.field.body, fontSize = 13.sp)
            }

        current == null -> ConsolidatedCard(title = "Gathering answers from every interview…") {
            Text(
                "Reading every sitting this artisan took part in.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        else -> {
            ConsolidatedSummaryBlock(current)
            SourcesCard(current.interviews)
            current.sections.forEach { section -> SectionCard(section) }
            if (current.unfiled.isNotEmpty()) UnfiledCard(current.unfiled)
            if (current.sections.isEmpty() && current.unfiled.isEmpty()) {
                NothingRecordedCard(
                    artisanName = current.artisan.name,
                    interviewCount = current.summary.interviewCount,
                    scoped = !scope.isAllRecords
                )
            }
        }
    }
}

/**
 * A document with nothing in it, and the reason the two bodies differ: an artisan who sat in no
 * interview AT ALL is a gap in the fieldwork, while an artisan who sat in three and has no answers
 * filed is a gap in the filing. Naming the wrong one sends somebody to re-record a sitting that
 * already happened.
 */
@Composable
private fun NothingRecordedCard(artisanName: String, interviewCount: Int, scoped: Boolean) {
    ConsolidatedCard(
        title = if (interviewCount == 0) "Nothing in this scope" else "Nothing recorded yet"
    ) {
        Text(
            when {
                interviewCount == 0 && scoped ->
                    "$artisanName took part in no interviews at the chosen workshops. Widen the " +
                        "workshop scope, or choose All records."
                interviewCount == 0 -> "$artisanName has not taken part in any interview yet."
                else -> "$artisanName appears in $interviewCount interview" +
                    (if (interviewCount == 1) "" else "s") +
                    ", but no answers or recordings have been filed against them."
            },
            color = MaterialTheme.field.muted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ConsolidatedSummaryBlock(data: ConsolidatedQuestionnaireDto) {
    val summary = data.summary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Gathered from ${summary.interviewCount} interview${if (summary.interviewCount == 1) "" else "s"} " +
                "· ${summary.answeredQuestionCount} questions answered · ${summary.recordedAnswerCount} recordings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (summary.groupSittingCount > 0) {
            Notice(
                text = "${summary.groupSittingCount} of these were group sittings. Answers recorded in a group " +
                    "cannot be attributed to one artisan — the record does not store who spoke.",
                container = AmberSurface,
                ink = AmberInk
            )
        }
        if (summary.conflictCount > 0) {
            Notice(
                text = "${summary.conflictCount} question${if (summary.conflictCount == 1) "" else "s"} " +
                    "answered differently in different interviews. Every account is shown, most recent first.",
                container = MaterialTheme.colorScheme.primaryContainer,
                ink = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun Notice(text: String, container: Color, ink: Color) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(container).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = ink)
    }
}

@Composable
private fun SourcesCard(interviews: List<ConsolidatedSourceDto>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "SOURCES (${interviews.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            interviews.forEach { source ->
                Column {
                    Text(
                        source.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        shortDate(source.date) + if (source.dateBasis != "interviewDate") " (recorded)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    AttributionChip(source.attribution, source.artisanCount)
                    if (source.coParticipants.isNotEmpty()) {
                        Text(
                            "with " + source.coParticipants.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one control on this screen that must never be softened: it is the difference between "this
 * artisan said it" and "somebody in a room of five said it, and the record does not say who".
 */
@Composable
private fun AttributionChip(attribution: String, artisanCount: Int) {
    val group = attribution == ATTRIBUTION_GROUP
    val container = if (group) AmberSurface else MaterialTheme.colorScheme.surfaceVariant
    val ink = if (group) AmberInk else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(container).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (group) Icons.Filled.Group else Icons.Filled.Person,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.height(14.dp).width(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (group) "Group of $artisanCount — speaker not recorded" else "Spoke alone",
            style = MaterialTheme.typography.labelSmall,
            color = ink
        )
    }
}

@Composable
private fun SectionCard(section: ConsolidatedSectionDto) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        section.code,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            section.questions.forEach { question ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        question.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (question.conflict) {
                        Notice(
                            text = "Answered differently in ${question.answers.size} interviews. Both accounts are " +
                                "shown, most recent first. Neither has been chosen for you.",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            ink = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    question.answers.forEachIndexed { index, answer ->
                        AnswerRow(answer, ordinal = if (question.conflict) index + 1 else null)
                    }
                }
            }

            if (section.recordings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Recordings filed to this section",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Said plainly rather than pinned to a nearby question: the clip identifies the
                    // section and stops there, and inventing the rest is the error this screen avoids.
                    Text(
                        "These clips identify the section but not a specific question.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    section.recordings.forEach { AnswerRow(it, ordinal = null) }
                }
            }
        }
    }
}

@Composable
private fun AnswerRow(answer: ConsolidatedAnswerDto, ordinal: Int?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (ordinal != null) {
            Text(
                "ACCOUNT $ordinal",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val body = answer.answerText?.takeIf { it.isNotBlank() }
            ?: answer.transcriptText?.takeIf { it.isNotBlank() }
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        } else if (answer.kind == "RECORDED") {
            Text(
                answer.filename ?: "Recording",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (answer.transcriptStatus != null && answer.transcriptStatus != "COMPLETED") {
                    "Transcription did not complete for this clip — the audio is the record."
                } else {
                    "No transcript yet — the audio is the record."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        answer.notes?.takeIf { it.isNotBlank() }?.let {
            Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AttributionChip(answer.attribution, answer.artisanCount)
        Text(
            buildString {
                append(answer.interviewTitle)
                append(" · ")
                append(shortDate(answer.interviewDate))
                if (answer.dateBasis != "interviewDate") append(" (recorded)")
                answer.recordedByName?.let { append(" · noted by $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (answer.coParticipants.isNotEmpty()) {
            Text(
                "with " + answer.coParticipants.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnfiledCard(rows: List<ConsolidatedAnswerDto>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Recordings with no section (${rows.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Recorded against this artisan's interviews but carrying no section in their filename or " +
                    "metadata. Listed here rather than dropped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.forEach { AnswerRow(it, ordinal = null) }
        }
    }
}

/**
 * `RecordCard` is file-private in MainActivity.kt, so the card is restated here — the same precedent
 * as `AppearanceScreen`'s `PreferenceCard`, `SearchScreen`'s `SearchCard` and `MapScreen`'s `MapCard`.
 */
@Composable
private fun ConsolidatedCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    title,
                    display = true,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}
