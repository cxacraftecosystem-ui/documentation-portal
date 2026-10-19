package com.fieldrepository.app.ui.questionnaire

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.field

/**
 * The prompts of DESIGN §4.5, worded from [QuestionnaireCopy]. Button rows follow the design bar:
 * one button centred, two on one line ([ActionRow] does both).
 */
data class PromptButton(val label: String, val primary: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun QuestionnairePrompt(title: String, body: String, buttons: List<PromptButton>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body) },
        confirmButton = { PromptButtons(buttons) }
    )
}

@Composable
fun PromptButtons(buttons: List<PromptButton>, modifier: Modifier = Modifier) {
    ActionRow(modifier = modifier.fillMaxWidth()) {
        buttons.forEach { b ->
            if (b.primary) {
                Button(
                    onClick = b.onClick,
                    colors = if (b.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) { Text(b.label, textAlign = TextAlign.Center) }
            } else {
                OutlinedButton(onClick = b.onClick) { Text(b.label, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** P13: a take saved before the app closed. */
@Composable
fun RecoveredRecordingPrompt(minutes: Long, label: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("p13.title"),
        body = QuestionnaireCopy.p13Body(minutes, label),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("p13.discard"), onClick = onDiscard),
            PromptButton(QuestionnaireCopy.s("p13.keep"), primary = true, onClick = onKeep)
        ),
        onDismiss = {}
    )
}

/** P3: a second Record while one take is running. */
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

/** P1: a matching session is already open. */
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

/** P5 when the identical file is elsewhere and nothing can be joined: OK, centred. */
@Composable
fun AlreadyUploadedPrompt(body: String, onOk: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("p5.title"),
        body = body,
        buttons = listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = onOk)),
        onDismiss = onOk
    )
}

/** P7r: remove a take (soft; restorable). */
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

/** P10: remove a participant who has takes. */
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
fun LeaveSessionPrompt(pending: Int, onStay: () -> Unit, onLeave: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("leave.title"),
        body = QuestionnaireCopy.leaveBody(pending),
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("leave.stay"), onClick = onStay),
            PromptButton(QuestionnaireCopy.s("leave.leave"), primary = true, onClick = onLeave)
        ),
        onDismiss = onStay
    )
}

@Composable
fun FinishSessionPrompt(sections: Int, artisans: Int, uploading: Int, onDismiss: () -> Unit, onFinish: () -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("finish.title"),
        body = QuestionnaireCopy.finishBody(sections, artisans, uploading),
        buttons = listOf(PromptButton(QuestionnaireCopy.s("finish.button"), primary = true, onClick = onFinish)),
        onDismiss = onDismiss
    )
}

/** P14: an old build (the server says creates need a newer app). */
@Composable
fun UpdateBanner(onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.s("p14.banner"), style = MaterialTheme.typography.bodyMedium)
            PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p14.update"), primary = true, onClick = onUpdate)))
        }
    }
}
