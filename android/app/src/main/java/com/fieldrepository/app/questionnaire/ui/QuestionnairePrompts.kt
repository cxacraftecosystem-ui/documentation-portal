package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.field

data class PromptButton(val label: String, val primary: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSheet(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
fun QuestionnairePrompt(title: String, body: String, buttons: List<PromptButton>, onDismiss: () -> Unit, dismissible: Boolean = true) {
    PromptSheet(onDismiss, dismissible) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title.isNotBlank()) Text(
                title, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.field.body)
        }
        PromptButtons(buttons)
    }
}

@Composable
fun PromptButtons(buttons: List<PromptButton>, modifier: Modifier = Modifier) {
    ActionRow(modifier = modifier.fillMaxWidth(), spacing = 12.dp) {
        buttons.forEach { b ->
            if (b.primary) {
                Button(
                    onClick = b.onClick,
                    modifier = Modifier.heightIn(min = 56.dp),
                    colors = if (b.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.background) else ButtonDefaults.buttonColors()
                ) { Text(b.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            } else {
                OutlinedButton(
                    onClick = b.onClick, modifier = Modifier.heightIn(min = 56.dp),
                    border = BorderStroke(1.dp, MaterialTheme.field.surface300)
                ) { Text(b.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable
fun RecoveredRecordingPrompt(minutes: Long, label: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("p13.title"),
        body = QuestionnaireCopy.p13Body(minutes, label),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p13.discard"), onClick = onDiscard),
            PromptButton(QuestionnaireCopy.s("p13.keep"), primary = true, onClick = onKeep)
        ),
        onDismiss = {},
        dismissible = false
    )
}

@Composable
fun StopCurrentPrompt(currentLabel: String, elapsedMs: Long, nextCode: String, onKeep: () -> Unit, onStopAndRecord: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.p3Title(currentLabel),
        body = QuestionnaireCopy.p3Body(elapsedMs),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p3.keep"), onClick = onKeep),
            PromptButton(QuestionnaireCopy.p3Stop(nextCode), primary = true, onClick = onStopAndRecord)
        ),
        onDismiss = onKeep
    )
}

@Composable
fun OpenMatchPrompt(body: String, onNew: () -> Unit, onJoin: () -> Unit, onDismiss: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("p1.title"),
        body = body,
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p1.new"), onClick = onNew),
            PromptButton(QuestionnaireCopy.s("p1.join"), primary = true, onClick = onJoin)
        ),
        onDismiss = onDismiss
    )
}

@Composable
fun MicOffPrompt(onUploadInstead: () -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    PromptSheet(onDismiss) {
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = QuestionnaireCopy.t("micOff") },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Filled.MicOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            Text(QuestionnaireCopy.t("micOffBody"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.field.body)
        }
        PromptButtons(listOf(
            PromptButton(QuestionnaireCopy.t("uploadInstead"), onClick = onUploadInstead),
            PromptButton(QuestionnaireCopy.t("openSettings"), primary = true, onClick = onOpenSettings)
        ))
    }
}

@Composable
fun RemoveTakePrompt(position: Int, label: String, onCancel: () -> Unit, onRemove: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.p7rTitle(position, label),
        body = QuestionnaireCopy.s("p7r.body"),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p7.cancel"), onClick = onCancel),
            PromptButton(QuestionnaireCopy.s("p7r.remove"), primary = true, destructive = true, onClick = onRemove)
        ),
        onDismiss = onCancel
    )
}

@Composable
fun RemoveParticipantPrompt(name: String, takes: Int, onCancel: () -> Unit, onRemove: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.p10Title(name),
        body = QuestionnaireCopy.p10Body(takes),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p7.cancel"), onClick = onCancel),
            PromptButton(QuestionnaireCopy.s("p10.remove"), primary = true, destructive = true, onClick = onRemove)
        ),
        onDismiss = onCancel
    )
}

@Composable
fun LeaveSessionPrompt(pending: Int, onStay: () -> Unit, failed: Int = 0, onLeave: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("leave.title"),
        body = QuestionnaireCopy.leaveBody(pending, failed),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("leave.stay"), onClick = onStay),
            PromptButton(QuestionnaireCopy.s("leave.leave"), primary = true, onClick = onLeave)
        ),
        onDismiss = onStay
    )
}

@Composable
fun UpdateBanner(onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.s("p14.banner"), style = MaterialTheme.typography.bodyMedium)
            PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p14.update"), primary = true, onClick = onUpdate)))
        }
    }
}
