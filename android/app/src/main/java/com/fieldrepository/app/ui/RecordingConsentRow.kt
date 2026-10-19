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
import com.fieldrepository.app.data.RecordingConsentClient
import com.fieldrepository.app.data.RecordingConsentCopy
import com.fieldrepository.app.data.QConsentGet
import kotlinx.coroutines.launch

/**
 * The consent rule — "Recordings may be transcribed" on the artisan edit form. Twin of the web's
 * `RecordingConsentToggle.tsx`. A SETTING, so an [AppToggle], saved when pressed, ON only once the
 * server says so.
 */
@Composable
fun RecordingConsentRow(artisanId: String) {
    val context = LocalContext.current
    val client = remember { RecordingConsentClient.create(context) }
    val scope = rememberCoroutineScope()
    var consent by remember(artisanId) { mutableStateOf<QConsentGet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId) {
        consent = runCatching { client.consent(artisanId) }.getOrNull()
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
                    runCatching { client.setConsent(artisanId, next) }
                        .onSuccess { consent = it }
                        .onFailure { error = RecordingConsentCopy.SAVE_FAILED }
                    busy = false
                }
            },
        )
        Text(
            RecordingConsentCopy.status(current, current.recordedAt?.take(10)),
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
