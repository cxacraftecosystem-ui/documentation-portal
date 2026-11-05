package com.fieldrepository.app.explorer.shots

import android.app.Notification
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerNotifications
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.portal.ExplorerPortal

private class Shown(val title: String, val text: String?, val detail: String?, val fraction: Float?, val actions: List<String>)

private fun shown(n: Notification): Shown {
    val extras = n.extras
    val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.split("\n")
    val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
    val at = extras.getInt(Notification.EXTRA_PROGRESS)
    return Shown(
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        detail = big?.getOrNull(1),
        fraction = if (max > 0) at.toFloat() / max else null,
        actions = n.actions?.map { it.title.toString() }.orEmpty(),
    )
}

@Composable
private fun Card(kind: String, mark: String, markBg: Color, markFg: Color, bg: Color, line: Color?, head: Color, body: Color, action: Color, item: Shown) {
    Column(
        Modifier.fillMaxWidth()
            .background(bg, RoundedCornerShape(20.dp))
            .let { if (line != null) it.border(1.dp, line, RoundedCornerShape(20.dp)) else it }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(18.dp).background(markBg, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Text(mark, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = markFg)
            }
            Text("${ExplorerPortal.PRODUCT} · $kind", fontSize = 12.sp, color = head)
        }
        Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF2F0F9), maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.text?.let { Text(it, fontSize = 13.sp, color = body) }
        item.detail?.let { Text(it, fontSize = 12.sp, color = Color(0xFFA7A3BC), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item.fraction?.let { f ->
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF4C3B63), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(f).height(6.dp).background(Color(0xFFC090F5), RoundedCornerShape(3.dp)))
            }
        }
        if (item.actions.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                item.actions.forEach { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = action) }
            }
        }
    }
}

@Composable
internal fun NotificationPreviews(running: ExplorerJob, saved: ExplorerJob, failed: ExplorerJob) {
    val context = LocalContext.current
    val progress = remember(running) { shown(ExplorerNotifications.progress(context, running, com.fieldrepository.app.explorer.data.ExplorerCancelReceiver.intent(context, running.id))) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notifications", fontSize = 12.sp, color = Color(0xFFCFCBE0), modifier = Modifier.padding(horizontal = 4.dp))
        Card(ExplorerText.downloads, "", Color(0xFF762CB1), Color.White, Color(0xFF2A2640), null, Color(0xFFCFCBE0), Color(0xFFD0CBDF), Color(0xFFC090F5), progress)
        Card(ExplorerText.savedShort, "✓", Color(0xFF22C55E), Color(0xFF052E16), Color(0xFF123B25), Color(0xFF15803D), Color(0xFFB7E4C7), Color(0xFFC9EED6), Color(0xFF86EFAC), Shown(ExplorerNotifications.finishedTitle(saved), ExplorerNotifications.finishedText(saved), null, null, listOf(ExplorerText.open, ExplorerText.share)))
        Card(ExplorerText.failedShort, "!", Color(0xFFEF4444), Color(0xFF450A0A), Color(0xFF4A1717), Color(0xFFB91C1C), Color(0xFFF5B5B5), Color(0xFFF5B5B5), Color(0xFFFCA5A5), Shown(ExplorerNotifications.finishedText(failed), null, null, null, listOf(ExplorerText.retry, ExplorerText.details)))
    }
}
