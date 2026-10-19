package com.fieldrepository.app.ui.questionnaire

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
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

/**
 * The consent notice (DESIGN §3.11), inline under the artisan field and on the artisan record. Consent
 * is assumed by default and can be revoked (owner decision, 19 Oct 2026): with no answer on record it
 * says recordings may be sent, Switch off · OK; after a refusal it says so, OK · Switch back on. It
 * never blocks Start session or recording. `onAnswer(null)` is OK; otherwise the state to record.
 */
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

/** The take row's transcript line. Paused is neutral; Retry is disabled with its reason while paused. */
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
