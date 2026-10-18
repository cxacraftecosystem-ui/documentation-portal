package com.fieldrepository.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.AppNotificationDto
import com.fieldrepository.app.data.LocalAlerts
import com.fieldrepository.app.data.NotificationApi
import com.fieldrepository.app.data.NotificationInboxRules
import com.fieldrepository.app.data.NotificationState
import com.fieldrepository.app.data.NotificationStore
import com.fieldrepository.app.data.NotificationSync
import com.fieldrepository.app.data.notificationInstant
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Notifications" — the in-app activity list (C25), kept on this phone so it opens with no signal.
 * The web's /notifications, the same list from the same routes.
 *
 * The alerts switch for Android 13+ is asked for HERE, in context — the one screen where a person
 * is thinking about being told things — and never at launch.
 */
@Composable
fun NotificationsScreen(
    api: NotificationApi,
    userId: String,
    onOpenLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inbox by NotificationState.inbox.collectAsState()
    var syncing by remember { mutableStateOf(true) }
    var reachable by remember { mutableStateOf(true) }
    var alertsAllowed by remember { mutableStateOf(LocalAlerts.permitted(context)) }
    val askForAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alertsAllowed = granted && LocalAlerts.permitted(context)
    }

    LaunchedEffect(userId) {
        NotificationState.publish(NotificationStore.load(context, userId))
        reachable = NotificationSync.sync(context, api, userId) != null
        syncing = false
    }

    val mine = inbox?.takeIf { it.userId == userId }
    val items = mine?.items.orEmpty()
    val unread = mine?.let { NotificationInboxRules.unread(it) } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (unread > 0) "$unread unread" else "You're all caught up.",
            color = Muted,
            fontSize = 14.sp,
        )

        if (LocalAlerts.permissionNeeded() && !alertsAllowed) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Get an alert on this phone when something new arrives.", fontSize = 14.sp)
                    ActionRow {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    askForAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Turn on alerts") }
                    }
                }
            }
        }

        if (unread > 0) {
            ActionRow {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            NotificationSync.markAllRead(context, userId)
                            NotificationSync.sync(context, api, userId)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Mark all as read") }
            }
        }

        if (!reachable && !syncing) {
            Text("Offline — showing what's on this phone.", color = Muted, fontSize = 13.sp)
        }

        when {
            items.isEmpty() && syncing -> Text("Loading…", color = Muted, fontSize = 13.sp)
            items.isEmpty() -> Text("No notifications yet", color = Muted, fontSize = 14.sp)
            else -> items.forEach { item ->
                NotificationRow(item) {
                    scope.launch {
                        if (!item.read) {
                            NotificationSync.markRead(context, userId, listOf(item.id))
                            launch { NotificationSync.sync(context, api, userId) }
                        }
                        item.link?.let(onOpenLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(item: AppNotificationDto, onOpen: () -> Unit) {
    val spoken = (if (item.read) "" else "Unread. ") + item.title
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .background(if (item.read) Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = 15.sp,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                item.body?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Muted, maxLines = 2) }
                Text(notificationWhen(item.createdAt), fontSize = 12.sp, color = Muted)
            }
        }
    }
}

/** "Just now", "5 min ago", "3 h ago", "Yesterday", then the date — the web's `whenWords`. */
internal fun notificationWhen(createdAt: String, now: Instant = Instant.now()): String {
    val moment = notificationInstant(createdAt) ?: return ""
    val seconds = Duration.between(moment, now).seconds
    return when {
        seconds < 60 -> "Just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 172_800 -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(moment)
    }
}
