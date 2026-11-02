package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.EnglishNotice
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch

/**
 * WHY THE ENGLISH IS MISSING, AND THE WAY TO ADD IT (contract 3, D38), the same on both portals.
 *
 * Gemini sometimes declines a recording outright, and asking again gets the same answer. The server
 * says so in `englishNotice`: DECLINED to whoever may add the English (the edit rule), UNAVAILABLE,
 * naming no provider, to every other reader. An editor edits any turn's English in place, or pastes
 * the whole translation here: paragraphs that line up with the turns land on them, anything else is
 * kept as one English text under the turns ([ManualEnglishBlock]).
 */
@Composable
internal fun EnglishNoticeCard(view: Transcript, host: TranscriptHost) {
    var writing by remember { mutableStateOf(false) }
    when (view.englishNotice) {
        EnglishNotice.UNAVAILABLE -> Text(
            TranscriptStrings.englishUnavailable,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.field.body,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag("transcript.english.unavailable")
        )
        EnglishNotice.DECLINED -> Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(14.dp)
                .testTag("transcript.english.declined"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(TranscriptStrings.englishDeclined, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (view.permissions.addEnglish) {
                        Text(TranscriptStrings.englishDeclinedHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
                    }
                }
            }
            // One action, centred (the design bar's single-button rule).
            if (view.permissions.addEnglish) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { writing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("transcript.english.add")) {
                    Text(TranscriptStrings.addEnglish)
                }
            }
        }
        else -> Unit
    }
    if (writing) AddEnglishDialog(view, host, onDismiss = { writing = false })
}

@Composable
private fun AddEnglishDialog(view: Transcript, host: TranscriptHost, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val plan = TranscriptFormat.englishPlan(view.turns, text)
    val planLine = when {
        plan.paragraphs == 0 -> TranscriptStrings.addEnglishHint
        plan.aligned -> TranscriptStrings.addEnglishAligned(plan.paragraphs)
        else -> TranscriptStrings.addEnglishWhole(plan.paragraphs, plan.turns)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(TranscriptStrings.addEnglishTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text,
                    { text = it.take(200_000); problem = null },
                    placeholder = { Text(TranscriptStrings.addEnglishPlaceholder) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("transcript.english.text")
                )
                Text(planLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, modifier = Modifier.testTag("transcript.english.plan"))
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val refusal = host.addEnglish(text)
                        saving = false
                        if (refusal == null) onDismiss() else problem = refusal
                    }
                },
                modifier = Modifier.testTag("transcript.english.save")
            ) { Text(TranscriptStrings.addEnglishSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(TranscriptStrings.cancel) } }
    )
}

/** The whole transcript's English as a person pasted it (contract 3), under the turns for every reader. */
@Composable
internal fun ManualEnglishBlock(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.field.surface200, RoundedCornerShape(12.dp))
            .padding(14.dp)
            .testTag("transcript.english.manual"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(TranscriptStrings.manualEnglishTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Pill(TranscriptStrings.manualEnglishSource, tone = PillTone.QUIET)
        }
        TranscriptFormat.paragraphs(text).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
        }
    }
}
