package com.fieldrepository.app.ui.questionnaire

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QArtisanRecord
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SegmentedChoice
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

/**
 * Artisan → Questionnaire (DESIGN §4.6): summary, section chips, Continue with…, Timeline | By
 * question. Replaces the consolidated screen and the old answer panel when v2 is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtisanQuestionnaireRecord(
    v2: QuestionnaireV2,
    artisanId: String,
    artisanName: String,
    onContinueWith: (artisanId: String) -> Unit,
    onStartSession: (artisanId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<QArtisanRecord?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf("T") }
    var playing by remember { mutableStateOf<Uri?>(null) }
    var consent by remember { mutableStateOf<String?>("…") }

    LaunchedEffect(artisanId, attempt) {
        loading = true; failed = false
        runCatching { v2.api.artisanRecord(artisanId) }.onSuccess { record = it }.onFailure { failed = true }
        consent = v2.consentState(artisanId)
        loading = false
    }

    ElevatedCard(colors = CardDefaults.elevatedCardColors(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
            if (consent == null || consent == "NOT_RECORDED") {
                ConsentQuestion(artisanName, onAnswer = { state ->
                    if (state == null) consent = "SKIPPED" else { consent = state; scope.launch { v2.recordConsent(artisanId, state) } }
                })
            }
            when {
                failed -> {
                    Text(QuestionnaireCopy.s("record.error"), style = MaterialTheme.typography.bodyMedium)
                    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.tryAgain"), primary = true) { attempt++ }))
                }
                !loading && (record?.instruments.isNullOrEmpty() || record!!.instruments.all { it.sessions.isEmpty() }) -> {
                    Text(QuestionnaireCopy.s("record.empty"), style = MaterialTheme.typography.bodyMedium)
                    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.startSession"), primary = true) { onStartSession(artisanId) }))
                }
                record != null -> record!!.instruments.forEach { instrument ->
                    Text(instrument.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        QuestionnaireCopy.recordSummary(instrument.summary.sectionsCovered, instrument.summary.sectionsTotal, instrument.summary.sessions, instrument.summary.repeats),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted
                    )
                    if (instrument.summary.open.isNotEmpty()) Text(QuestionnaireCopy.openSections(instrument.summary.open), style = MaterialTheme.typography.bodySmall)
                    PromptButtons(listOf(PromptButton(QuestionnaireCopy.continueWith(QuestionnaireCopy.firstName(artisanName)), primary = true) { onContinueWith(artisanId) }))
                    SegmentedChoice(
                        label = "",
                        options = listOf(SelectOption("T", QuestionnaireCopy.s("record.timeline")), SelectOption("Q", QuestionnaireCopy.s("record.byQuestion"))),
                        selectedValue = view
                    ) { view = it }
                    if (view == "T") {
                        ArtisanTimeline.rows(record!!, instrument.questionnaireId, artisanId).forEach { row ->
                            when (row) {
                                is TimelineRow.SessionHead -> Text(row.text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                                TimelineRow.Uncertain -> Text(QuestionnaireCopy.s("record.uncertain"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted)
                                is TimelineRow.Take -> Row(Modifier.fillMaxWidth().clickable {
                                    scope.launch { playing = v2.playbackUrl(row.recordingId)?.let(Uri::parse) }
                                }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(row.time ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(row.text, style = MaterialTheme.typography.bodySmall)
                                        if (row.badges.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            row.badges.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
                                        }
                                    }
                                    Text("▶", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        instrument.document.forEach { section ->
                            Text("Section ${section.sectionCode}", style = MaterialTheme.typography.labelLarge)
                            section.questions.forEach { q ->
                                val badge = when {
                                    q.answer?.attribution == "GROUP_SHARED" -> QuestionnaireCopy.s("record.groupAnswer")
                                    q.answer?.sourceRecordingId != null -> QuestionnaireCopy.s("record.fromRecording")
                                    else -> null
                                }
                                Text(listOfNotNull(q.displayCode, q.answer?.text ?: "—", badge).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                if (q.otherRecordingIds.isNotEmpty()) Text(
                                    QuestionnaireCopy.moreTakes(q.otherRecordingIds.size) + if (q.accountsDiffer) " · ${QuestionnaireCopy.s("record.accountsDiffer")}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted
                                )
                                if (q.recordedUnderEarlierWording) Text(QuestionnaireCopy.s("record.earlierWording"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                            }
                        }
                        if (instrument.retired.isNotEmpty()) Text(QuestionnaireCopy.s("record.retired"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            playing?.let { AudioPlayer(it) }
        }
    }
}
