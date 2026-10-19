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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

/**
 * The consent question (DESIGN §3.11), inline under the artisan field and on the artisan record. It
 * never blocks Start session or recording; it only decides whether audio may later go to an outside
 * service. Buttons on one line, Not now · Yes, recorded; No sits under More.
 */
@Composable
fun ConsentQuestion(artisanName: String, onAnswer: (state: String?) -> Unit, modifier: Modifier = Modifier) {
    var more by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.field.surface50, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.consentQuestion(QuestionnaireCopy.firstName(artisanName)), style = MaterialTheme.typography.bodyMedium)
            PromptButtons(
                listOf(
                    PromptButton(QuestionnaireCopy.s("consent.notNow")) { onAnswer(null) },
                    PromptButton(QuestionnaireCopy.s("consent.yes"), primary = true) { onAnswer("GRANTED") }
                )
            )
            TextButton(onClick = { more = !more }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(QuestionnaireCopy.s("consent.more")) }
            if (more) {
                TextButton(onClick = { onAnswer("REFUSED") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(QuestionnaireCopy.s("consent.no")) }
            }
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
        if (line.text == QuestionnaireCopy.s("consent.waiting") && onRecordConsent != null) {
            TextButton(onClick = onRecordConsent) { Text(QuestionnaireCopy.s("consent.record")) }
        }
        if (onRetry != null && (line.retryEnabled || line.retryReason != null)) {
            TextButton(onClick = onRetry, enabled = line.retryEnabled) { Text(QuestionnaireCopy.s("transcript.retry")) }
            line.retryReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted) }
        }
    }
}
