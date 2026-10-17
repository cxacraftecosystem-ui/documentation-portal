package com.fieldrepository.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.AcceptSectionRequest
import com.fieldrepository.app.data.AnswerDraftDto
import com.fieldrepository.app.data.AnswerDraftListDto
import com.fieldrepository.app.data.AnswerDrafts
import com.fieldrepository.app.data.AnswerDraftsClient
import com.fieldrepository.app.data.DismissSectionRequest
import com.fieldrepository.app.data.DraftSectionRequest
import kotlinx.coroutines.launch

/**
 * C28 — draft one section's unanswered questions from the interview's transcript, then accept or
 * edit them together. Twin of the web's `AnswerDraftsPanel.tsx`. [onAccepted] hands the kept words
 * (questionId → text) back so the form's own answer boxes show them at once.
 */
@Composable
fun AnswerDraftsCard(
    interviewId: String,
    sectionId: String,
    questions: List<Pair<String, String>>,
    onAccepted: (Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    val client = remember { AnswerDraftsClient.create(context) }
    val scope = rememberCoroutineScope()
    var list by remember(interviewId) { mutableStateOf<AnswerDraftListDto?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    val edits = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(interviewId) {
        if (client.enabled()) {
            list = runCatching { client.api.list(interviewId) }.getOrNull()
            source = list?.transcripts?.firstOrNull()?.id
        }
    }
    val drafts = list ?: return
    if (!drafts.mayDraft) return
    val prompts = questions.toMap()
    val pending: List<AnswerDraftDto> =
        drafts.items.filter { it.sectionId == sectionId && it.status == "PENDING" && prompts.containsKey(it.questionId) }

    fun run(action: suspend () -> Unit) {
        busy = true
        note = null
        scope.launch {
            runCatching { action() }.onFailure { note = AnswerDrafts.FAILED }
            busy = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (pending.isEmpty()) {
            Text(AnswerDrafts.DRAFT_FROM, fontWeight = FontWeight.SemiBold)
            val options = drafts.transcripts.map { it.id to it.name } + ("" to AnswerDrafts.PASTE)
            SearchableSelectField(
                label = "Transcript",
                options = remember(options) { options.asSelectOptions() },
                selectedValue = source ?: "",
                includeNone = false,
                onSelect = { choice -> source = choice.ifBlank { null } },
            )
            if (source == null) {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(AnswerDrafts.PASTE) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ActionRow {
                OutlinedButton(
                    enabled = !busy && (source != null || pasted.isNotBlank()),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        run {
                            val answer = client.api.draft(
                                interviewId,
                                DraftSectionRequest(sectionId, mediaId = source, transcript = if (source == null) pasted else null),
                            )
                            list = drafts.copy(
                                items = drafts.items.filterNot { d -> answer.items.any { it.questionId == d.questionId } } + answer.items
                            )
                            edits.clear()
                            note = when {
                                answer.asked == 0 -> AnswerDrafts.ALL_ANSWERED
                                answer.drafted == 0 -> AnswerDrafts.NOTHING_FOUND
                                else -> null
                            }
                        }
                    },
                ) { Text(if (busy) "Drafting…" else AnswerDrafts.DRAFT) }
            }
        } else {
            pending.forEach { d ->
                Text("${prompts[d.questionId]} · ${AnswerDrafts.LABEL}", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = edits[d.questionId] ?: d.draftText,
                    onValueChange = { edits[d.questionId] = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ActionRow {
                Button(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        run {
                            val answers = AnswerDrafts.answersToAccept(pending, edits)
                            client.api.accept(interviewId, AcceptSectionRequest(sectionId, answers))
                            onAccepted(answers.filter { it.answerText.isNotBlank() }.associate { it.questionId to it.answerText.trim() })
                            list = drafts.copy(items = drafts.items.map { if (pending.any { p -> p.id == it.id }) it.copy(status = "ACCEPTED") else it })
                        }
                    },
                ) { Text(AnswerDrafts.ACCEPT) }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        run {
                            client.api.dismiss(interviewId, DismissSectionRequest(sectionId))
                            list = drafts.copy(items = drafts.items.map { if (pending.any { p -> p.id == it.id }) it.copy(status = "DISMISSED") else it })
                        }
                    },
                ) { Text(AnswerDrafts.DISMISS) }
            }
        }
        note?.let {
            Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
