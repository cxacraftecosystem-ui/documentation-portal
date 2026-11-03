package com.fieldrepository.app.questionnaire.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.ui.field

data class TakeRow(
    val recordingId: String,
    val position: Int,
    val first: String,
    val second: String,
    val transcript: TranscriptLine?,
    val local: Boolean,
    val removable: Boolean,
    val targetKey: String? = null,
    val chips: com.fieldrepository.app.questionnaire.data.QChips? = null,
    val canMakePrimary: Boolean = false,
    val removed: Boolean = false,
    val canPropose: Boolean = false,
    val mediaFileId: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeSheet(
    heading: String,
    takes: List<TakeRow>,
    locked: Boolean,
    playing: Uri?,
    onPlay: (TakeRow) -> Unit,
    onRemove: (TakeRow) -> Unit,
    onRecord: () -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: (TakeRow) -> Unit = {},
    onRecordConsent: (TakeRow) -> Unit = {},
    onRestore: (TakeRow) -> Unit = {},
    onMakePrimary: (TakeRow) -> Unit = {},
    onPropose: (TakeRow) -> Unit = {},
    onHistory: (TakeRow) -> Unit = {},
    onTranscript: (TakeRow) -> Unit = {},
    note: String? = null
) {
    var showRemoved by remember { mutableStateOf(false) }
    val live = takes.filterNot { it.removed }
    val removed = takes.filter { it.removed }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
            live.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.first, style = MaterialTheme.typography.bodyMedium)
                        Text(t.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                        RecordingChips(t.chips, onChip = QuestionnaireV2Nav::openRecordings)
                        TranscriptStatusLine(
                            t.transcript,
                            onRetry = if (t.local) null else ({ onRetry(t) }),
                            onRecordConsent = if (t.local) null else ({ onRecordConsent(t) })
                        )
                    }
                    IconButton(onClick = { onPlay(t) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                }
                if (!t.local) {
                    val actions = buildList {
                        if (t.mediaFileId != null) add(PromptButton(TranscriptUiText.open) { onTranscript(t) })
                        if (t.canPropose) add(PromptButton(QuestionnaireCopy.s("take.propose")) { onPropose(t) })
                        if (t.canMakePrimary) add(PromptButton(QuestionnaireCopy.s("take.makePrimary")) { onMakePrimary(t) })
                        add(PromptButton(QuestionnaireCopy.s("take.history")) { onHistory(t) })
                        if (t.removable && !locked) add(PromptButton(QuestionnaireCopy.s("p7r.remove"), destructive = true) { onRemove(t) })
                    }
                    if (actions.isNotEmpty()) PromptButtons(actions)
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
            if (removed.isNotEmpty()) {
                TextButton(onClick = { showRemoved = !showRemoved }) { Text(QuestionnaireCopy.showRemoved(removed.size)) }
                if (showRemoved) removed.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
                            Text(QuestionnaireCopy.s("take.removed"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                        }
                        TextButton(onClick = { onRestore(t) }) { Text(QuestionnaireCopy.s("take.restore")) }
                    }
                }
            }
            playing?.let { AudioPlayer(it) }
            if (!locked) {
                PromptButtons(
                    listOf(
                        PromptButton(QuestionnaireCopy.s("workspace.recordTake"), primary = true, onClick = onRecord),
                        PromptButton(QuestionnaireCopy.s("workspace.uploadFile"), onClick = onUpload)
                    )
                )
            }
        }
    }
}
