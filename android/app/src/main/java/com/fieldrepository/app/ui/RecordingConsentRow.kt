package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QConsentGet
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.data.RecordingConsentCopy
import com.fieldrepository.app.data.consentDay
import kotlinx.coroutines.launch

@Composable
fun RecordingConsentRow(artisanId: String) {
    val context = LocalContext.current
    val v2 = remember { QuestionnaireV2.get(context) }
    val scope = rememberCoroutineScope()
    var consent by remember(artisanId) { mutableStateOf<QConsentGet?>(null) }
    var waiting by remember(artisanId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId) {
        waiting = v2.consentWaiting(artisanId)
        val queued = v2.knownConsent[artisanId]?.takeIf { waiting }
        consent = if (queued != null) QConsentGet(artisanId = artisanId, state = queued)
        else runCatching { v2.api.consent(artisanId) }.getOrNull()
    }
    val current = consent ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppToggle(
            label = RecordingConsentCopy.LABEL,
            description = RecordingConsentCopy.DESCRIPTION,
            checked = RecordingConsentCopy.granted(current),
            busy = busy,
            onCheckedChange = { next ->
                busy = true
                error = null
                scope.launch {
                    val state = if (next) "GRANTED" else "REFUSED"
                    if (!v2.recordConsent(artisanId, state)) {
                        error = RecordingConsentCopy.SAVE_FAILED
                    } else {
                        waiting = v2.consentWaiting(artisanId)
                        consent = (if (waiting) null else runCatching { v2.api.consent(artisanId) }.getOrNull())
                            ?: QConsentGet(artisanId = artisanId, state = state)
                    }
                    busy = false
                }
            },
        )
        Text(
            RecordingConsentCopy.status(current, consentDay(current.recordedAt)) +
                (if (waiting) " · " + QuestionnaireCopy.s("upload.waiting") else ""),
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
