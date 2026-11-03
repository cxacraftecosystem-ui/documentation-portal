package com.fieldrepository.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.InsightQuoteDto
import com.fieldrepository.app.data.TranscriptInsightActions
import com.fieldrepository.app.data.TranscriptInsightEditing
import com.fieldrepository.app.data.TranscriptInsightViewDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TranscriptInsightControls(
    view: TranscriptInsightViewDto,
    actions: TranscriptInsightActions,
    onEdit: () -> Unit,
    onReplaced: (TranscriptInsightViewDto) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember(view.mediaId) { mutableStateOf(false) }
    var error by remember(view.mediaId) { mutableStateOf<String?>(null) }
    var askReplace by remember(view.mediaId) { mutableStateOf(false) }
    val mayEdit = TranscriptInsightEditing.mayEdit(view, editable = true)
    val mayRegenerate = TranscriptInsightEditing.mayRegenerate(view, editable = true)

    fun start(discardEdits: Boolean) {
        askReplace = false
        busy = true
        error = null
        scope.launch {
            try {
                onReplaced(actions.regenerate(view.mediaId, discardEdits))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                error = failure.apiErrorMessage(TranscriptInsightEditing.START_FAILED)
            } finally {
                busy = false
            }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (mayEdit || mayRegenerate) {
        ActionRow {
            if (mayEdit) {
                OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("insight-edit")) { Text("Edit") }
            }
            if (mayRegenerate) {
                OutlinedButton(
                    onClick = { if (TranscriptInsightEditing.needsRegenerateConfirm(view)) askReplace = true else start(false) },
                    enabled = !busy,
                    modifier = Modifier.testTag("insight-regenerate")
                ) { Text(TranscriptInsightEditing.regenerateLabel(view)) }
            }
        }
    }
    if (askReplace) {
        AlertDialog(
            onDismissRequest = { askReplace = false },
            title = { Text(TranscriptInsightEditing.REPLACE_EDITS) },
            confirmButton = { TextButton(onClick = { start(true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { askReplace = false }) { Text("Keep") } }
        )
    }
}

@Composable
internal fun TranscriptInsightEditor(
    view: TranscriptInsightViewDto,
    actions: TranscriptInsightActions,
    loadTranslation: suspend () -> String?,
    onCancel: () -> Unit,
    onSaved: (TranscriptInsightViewDto) -> Unit
) {
    val insight = view.insight ?: return
    val scope = rememberCoroutineScope()
    val lines = remember(insight.id, insight.version) {
        mutableStateListOf<String>().apply { addAll(TranscriptInsightEditing.editRows(insight.summaryLines)) }
    }
    val quotes = remember(insight.id, insight.version) {
        mutableStateListOf<InsightQuoteDto>().apply { addAll(insight.quotes) }
    }
    var translation by remember(insight.id, insight.version) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Summary", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        lines.forEachIndexed { index, line ->
            OutlinedTextField(
                value = line,
                onValueChange = { lines[index] = it.take(TranscriptInsightEditing.SUMMARY_LINE_MAX) },
                label = { Text("Line ${index + 1}") },
                modifier = Modifier.fillMaxWidth().testTag("insight-line-$index")
            )
        }
        Text("Key quotes", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        quotes.forEachIndexed { index, quote ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Hairline, shape = RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                OutlinedTextField(
                    value = quote.original,
                    onValueChange = { quotes[index] = quote.copy(original = it) },
                    label = { Text("Original words") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = quote.english,
                    onValueChange = { quotes[index] = quote.copy(english = it) },
                    label = { Text("English") },
                    modifier = Modifier.fillMaxWidth()
                )
                ActionRow {
                    OutlinedButton(onClick = { quotes.removeAt(index) }) { Text("Remove") }
                }
            }
        }
        if (quotes.size < TranscriptInsightEditing.QUOTES_MAX) {
            ActionRow {
                OutlinedButton(onClick = { quotes.add(InsightQuoteDto()) }) { Text("Add quote") }
            }
        }
        if (TranscriptInsightEditing.translatable(insight)) {
            Text("English translation", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            val text = translation
            if (text == null) {
                ActionRow {
                    OutlinedButton(onClick = { scope.launch { translation = loadTranslation().orEmpty() } }) {
                        Text("Edit translation")
                    }
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { translation = it },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("insight-translation")
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        ActionRow {
            OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
            Button(
                onClick = {
                    val body = TranscriptInsightEditing.patch(insight, lines.toList(), quotes.toList(), translation)
                    if (body == null) {
                        error = TranscriptInsightEditing.NEED_A_LINE
                    } else {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                onSaved(actions.save(view.mediaId, body))
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (failure: Throwable) {
                                error = failure.apiErrorMessage(TranscriptInsightEditing.SAVE_FAILED)
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.testTag("insight-save")
            ) { Text(if (saving) "Saving…" else "Save") }
        }
    }
}
