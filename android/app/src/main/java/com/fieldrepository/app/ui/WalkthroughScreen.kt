package com.fieldrepository.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.data.UserDto

private const val WALKTHROUGH_PREFS = "fieldrepo_prefs"
private const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen"

internal fun walkthroughSeen(context: Context): Boolean =
    context.getSharedPreferences(WALKTHROUGH_PREFS, Context.MODE_PRIVATE)
        .getBoolean(PREF_WALKTHROUGH_SEEN, false)

internal fun markWalkthroughSeen(context: Context) {
    context.getSharedPreferences(WALKTHROUGH_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_WALKTHROUGH_SEEN, true)
        .apply()
}

@Composable
private fun walkthroughReduceMotion(): Boolean {
    val context = LocalContext.current
    val osScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }
    return LocalAppPreferences.current.reducedMotion || osScale == 0f
}

private fun walkthroughOpenLabel(destination: NavDestination): String? =
    FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }?.label

@Composable
internal fun WalkthroughDialog(
    user: UserDto?,
    onFinish: () -> Unit,
    onOpen: (NavDestination) -> Unit,
) {
    var deck by remember(user?.role) { mutableStateOf(walkthroughDeckFor(user)) }

    val reduceMotion = walkthroughReduceMotion()

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            WalkthroughJourney(
                deck = deck,
                decks = WALKTHROUGH_DECKS,
                onChooseDeck = { chosen -> deck = chosen },
                reduceMotion = reduceMotion,
                onOpen = onOpen,
                onFinish = onFinish,
            )
        }
    }
}

@Composable
internal fun WalkthroughOpenButton(
    destination: NavDestination,
    onOpen: (NavDestination) -> Unit,
) {
    val label = walkthroughOpenLabel(destination)
    TextButton(
        onClick = { onOpen(destination) },
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (label != null) "Open $label" else "Open the screen this step teaches",
            style = FieldTextStyles.Link,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
