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
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.CrossPortalSharingClient
import com.fieldrepository.app.data.SharingConsentCopy
import com.fieldrepository.app.data.SharingConsentDto
import kotlinx.coroutines.launch

/**
 * C27 — "Share with the Designer Portal" on the artisan edit form. Twin of the web's
 * `SharingConsentToggle.tsx`. A SETTING, so an [AppToggle]; drawn only while the server says the
 * cross-portal feature is on, saved when pressed, ON only once the server says so.
 */
@Composable
fun SharingConsentRow(artisanId: String) {
    val context = LocalContext.current
    val client = remember { CrossPortalSharingClient.create(context) }
    val scope = rememberCoroutineScope()
    var consent by remember(artisanId) { mutableStateOf<SharingConsentDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId) {
        if (client.enabled()) consent = runCatching { client.consent(artisanId) }.getOrNull()
    }
    val current = consent ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppToggle(
            label = SharingConsentCopy.LABEL,
            description = SharingConsentCopy.DESCRIPTION,
            checked = current.granted,
            busy = busy,
            onCheckedChange = { next ->
                busy = true
                error = null
                scope.launch {
                    runCatching { client.setConsent(artisanId, next) }
                        .onSuccess { consent = it }
                        .onFailure { error = SharingConsentCopy.SAVE_FAILED }
                    busy = false
                }
            },
        )
        Text(
            SharingConsentCopy.status(current, current.recordedAt?.take(10)),
            color = Muted,
            fontSize = 12.sp,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
