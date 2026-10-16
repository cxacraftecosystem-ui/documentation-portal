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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.reflect.KProperty

/** Whether a message reports something that went wrong or something that went right. */
enum class AppMessageTone { SUCCESS, ERROR }

/**
 * The app's one message: a sentence and its tone (UX audit 2026-10-16, DC-A03; the designer portal's file under the same names).
 *
 * It replaces a bare `String?` that every screen wrote into and that was drawn as one unstyled line
 * at the foot of a long page — below Save, with no colour, no icon and nothing for TalkBack. It is
 * still WRITTEN as a string (`message = "Craft saved"`), because it is a property delegate: plain
 * assignment is a success, and [showError] is the one door to the error treatment. So the dozens of
 * existing `message = …` sites keep compiling and keep meaning what they meant.
 */
class AppMessageState {
    var text by mutableStateOf<String?>(null)
        private set
    var tone by mutableStateOf(AppMessageTone.SUCCESS)
        private set
    /** Retry for the action that failed, when the failing screen offered one. */
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

/** How long a SUCCESS stays on screen. Errors stay until dismissed or the page changes. */
private const val SUCCESS_SHOWN_MILLIS = 5_000L

/**
 * Draws [state] as a banner: a coloured container, an icon AND a spoken word ("Done" / "Problem"), so the
 * tone never rests on colour alone; a polite live region so TalkBack reads it without being
 * interrupted; a Try again button when the failure has one; and a 48dp dismiss. Place it at the
 * bottom of the viewport, over the page, so it is seen on a long form rather than scrolled past.
 */
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
                fontSize = 14.sp,
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

/**
 * A load that FAILED, drawn as a failure: the reason and a Try again button (DC-A04 to DC-A07, DC-A17, DC-A18).
 * The alternative every one of those screens had was a spinner that never stopped or an empty state
 * that read as "nothing here" — a failure looking like success.
 */
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
            Text(message, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onRetry) {
                Text("Try again", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Where a section that was only handed an `onError` sends a SUCCESS note ("Audio recording added").
 * Those sections used to be given `onMessage = onError`, which was harmless while every message was
 * one plain line and would now paint a success as a problem. MainActivity provides it around the
 * screen switch; the default drops the note, which is the honest answer outside that host.
 */
val LocalAppNotice = staticCompositionLocalOf<(String) -> Unit> { {} }
