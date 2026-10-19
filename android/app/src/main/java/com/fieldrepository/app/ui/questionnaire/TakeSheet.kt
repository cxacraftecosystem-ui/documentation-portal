package com.fieldrepository.app.ui.questionnaire

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.AudioPlayer
import com.fieldrepository.app.ui.field

/** One take as the sheet lists it: "Take 1  14:12 min  Nandini  7 Jul 10:42 / Group of 3 · Transcribed". */
data class TakeRow(
    val recordingId: String,
    val position: Int,
    val first: String,
    val second: String,
    val transcript: TranscriptLine?,
    val local: Boolean,
    val removable: Boolean
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
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            takes.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.first, style = MaterialTheme.typography.bodyMedium)
                        Text(t.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                        TranscriptStatusLine(t.transcript)
                    }
                    IconButton(onClick = { onPlay(t) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                }
                if (t.removable && !t.local) TextButton(onClick = { onRemove(t) }) { Text(QuestionnaireCopy.s("p7r.remove")) }
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
