package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

@Composable
fun ConsentQuestion(artisanName: String, onAnswer: (state: String?) -> Unit, modifier: Modifier = Modifier, refused: Boolean = false) {
    Surface(color = MaterialTheme.field.surface50, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.consentNotice(QuestionnaireCopy.firstName(artisanName), refused), style = MaterialTheme.typography.bodyMedium)
            PromptButtons(
                if (refused) listOf(
                    PromptButton(QuestionnaireCopy.s("consent.ok")) { onAnswer(null) },
                    PromptButton(QuestionnaireCopy.s("consent.switchOn"), primary = true) { onAnswer("GRANTED") }
                ) else listOf(
                    PromptButton(QuestionnaireCopy.s("consent.switchOff")) { onAnswer("REFUSED") },
                    PromptButton(QuestionnaireCopy.s("consent.ok"), primary = true) { onAnswer(null) }
                )
            )
        }
    }
}

@Composable
fun TranscriptStatusLine(line: TranscriptLine?, onRetry: (() -> Unit)? = null, onRecordConsent: (() -> Unit)? = null) {
    line ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (line.tone == TranscriptTone.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.field.muted
        )
        if (line.text == QuestionnaireCopy.s("transcript.refused") && onRecordConsent != null) {
            TextButton(onClick = onRecordConsent) { Text(QuestionnaireCopy.s("consent.record")) }
        }
        if (onRetry != null && (line.retryEnabled || line.retryReason != null)) {
            TextButton(onClick = onRetry, enabled = line.retryEnabled) { Text(QuestionnaireCopy.s("transcript.retry")) }
            line.retryReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
        }
    }
}
