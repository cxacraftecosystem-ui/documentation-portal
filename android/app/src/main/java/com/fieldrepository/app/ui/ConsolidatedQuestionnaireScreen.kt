package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.ApiClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * One artisan's questionnaire, gathered from every interview they sat in — the Android mirror of the
 * web's `/questionnaire/consolidated/[artisanId]`.
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
 * NEW FILE ON PURPOSE. `MainActivity.kt`, `AppNavigation.kt`, `ApiModels.kt` and `FieldRepository.kt`
 * were all being edited concurrently, so everything this feature needs — DTOs, service, screen —
 * is here. See [ConsolidatedQuestionnaireApi] for the one shared file this does touch, and the
 * accompanying report for the exact call sites to add.
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

/**
 * Declared here rather than added to `FieldRepositoryApi` only because that file was mid-edit by
 * other work. It is built from [ApiClient.retrofit], so it rides the SAME OkHttp stack as every other
 * call — the gateway-504 retry, the auth header and the lenient JSON — instead of a private client
 * that would quietly miss all three.
 */
interface ConsolidatedQuestionnaireApi {
    @GET("questionnaire/artisans/{id}/consolidated")
    suspend fun consolidated(@Path("id") artisanId: String): ConsolidatedQuestionnaireDto
}

fun consolidatedQuestionnaireApi(tokenStore: TokenStore): ConsolidatedQuestionnaireApi =
    ApiClient.retrofit(tokenStore).create(ConsolidatedQuestionnaireApi::class.java)

fun consolidatedQuestionnaireApi(retrofit: Retrofit): ConsolidatedQuestionnaireApi =
    retrofit.create(ConsolidatedQuestionnaireApi::class.java)

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

@Composable
fun ConsolidatedQuestionnaireScreen(
    artisanId: String,
    tokenStore: TokenStore,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {}
) {
    val api = remember(tokenStore) { consolidatedQuestionnaireApi(tokenStore) }
    var data by remember(artisanId) { mutableStateOf<ConsolidatedQuestionnaireDto?>(null) }
    var error by remember(artisanId) { mutableStateOf<String?>(null) }

    LaunchedEffect(artisanId) {
        runCatching { api.consolidated(artisanId) }
            .onSuccess { data = it; error = null }
            .onFailure {
                val text = it.message ?: "Unable to load this questionnaire"
                error = text
                onError(text)
            }
    }

    val current = data
    if (error != null && current == null) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    if (current == null) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ConsolidatedHeader(current) }
        item { SourcesCard(current.interviews) }

        items(current.sections, key = { it.id }) { section -> SectionCard(section) }

        if (current.unfiled.isNotEmpty()) {
            item { UnfiledCard(current.unfiled) }
        }
        if (current.sections.isEmpty() && current.unfiled.isEmpty()) {
            item {
                Text(
                    "No answers or recordings have been filed against ${current.artisan.name} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConsolidatedHeader(data: ConsolidatedQuestionnaireDto) {
    val summary = data.summary
    Column {
        Text(
            data.artisan.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        val subtitle = listOfNotNull(data.artisan.craftName, data.artisan.place).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Gathered from ${summary.interviewCount} interview${if (summary.interviewCount == 1) "" else "s"} " +
                "· ${summary.answeredQuestionCount} questions answered · ${summary.recordedAnswerCount} recordings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (summary.groupSittingCount > 0) {
            Spacer(Modifier.height(8.dp))
            Notice(
                text = "${summary.groupSittingCount} of these were group sittings. Answers recorded in a group " +
                    "cannot be attributed to one artisan — the record does not store who spoke.",
                container = AmberSurface,
                ink = AmberInk
            )
        }
        if (summary.conflictCount > 0) {
            Spacer(Modifier.height(8.dp))
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
