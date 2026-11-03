package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.reflect.KProperty

enum class AppMessageTone { SUCCESS, ERROR }

class AppMessageState {
    var text by mutableStateOf<String?>(null)
        private set
    var tone by mutableStateOf(AppMessageTone.SUCCESS)
        private set
    var retry by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun show(text: String?, tone: AppMessageTone, retry: (() -> Unit)? = null) {
        this.text = text?.takeIf { it.isNotBlank() }
        this.tone = tone
        this.retry = retry
    }

    fun showError(text: String?, retry: (() -> Unit)? = null) = show(text, AppMessageTone.ERROR, retry)

    fun clear() = show(null, AppMessageTone.SUCCESS)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): String? = text

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) =
        show(value, AppMessageTone.SUCCESS)
}

private const val SUCCESS_SHOWN_MILLIS = 5_000L

@Composable
fun AppMessageBanner(state: AppMessageState, modifier: Modifier = Modifier) {
    val text = state.text ?: return
    val error = state.tone == AppMessageTone.ERROR
    if (!error) {
        LaunchedEffect(text) {
            delay(SUCCESS_SHOWN_MILLIS)
            if (state.text == text) state.clear()
        }
    }
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.field.successContainer
    val onContainer = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.field.onSuccessContainer
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (error) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = if (error) "Problem" else "Done",
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            state.retry?.let { retry ->
                TextButton(onClick = { state.clear(); retry() }) {
                    Text("Try again", color = onContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = { state.clear() }) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message")
            }
        }
    }
}

@Composable
fun LoadFailedCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onRetry) {
                Text("Try again", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

val LocalAppNotice = staticCompositionLocalOf<(String) -> Unit> { {} }
