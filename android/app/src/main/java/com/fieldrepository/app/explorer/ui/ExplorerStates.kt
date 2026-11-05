package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay

@Composable
internal fun StatusCard(status: ExplorerStatus, text: String, action: String? = null, tag: String? = null, onAction: (() -> Unit)? = null) {
    val tone = explorerTone(status)
    Row(
        Modifier.fillMaxWidth()
            .background(tone?.container ?: MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, tone?.border ?: MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = tone?.content ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = tone?.accent ?: MaterialTheme.colorScheme.primary, contentColor = tone?.onAccent ?: MaterialTheme.colorScheme.onPrimary),
                modifier = Modifier.heightIn(min = 40.dp),
            ) { Text(action) }
        }
    }
}

@Composable
private fun StateCard(
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    border: Color,
    content: Color,
    iconTint: Color,
    tag: String,
    footer: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth()
            .background(container, MaterialTheme.shapes.extraLarge)
            .border(1.dp, border, MaterialTheme.shapes.extraLarge)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = content, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = content, textAlign = TextAlign.Center)
        footer()
    }
}

@Composable
internal fun EmptyFolderCard(name: String, onRecord: (() -> Unit)?) {
    StateCard(
        icon = Icons.Filled.FolderOpen,
        title = ExplorerUiText.emptyTitle,
        body = ExplorerUiText.emptyBody(name),
        container = MaterialTheme.colorScheme.surface,
        border = MaterialTheme.field.hairline,
        content = MaterialTheme.colorScheme.onSurface,
        iconTint = MaterialTheme.colorScheme.primary,
        tag = "explorer.empty",
    ) {
        if (onRecord != null) Button(onClick = onRecord, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.empty.record")) { Text(ExplorerUiText.recordNow) }
    }
}

@Composable
internal fun OfflineCard(age: String?) {
    val tone = explorerWarnTone()
    StateCard(
        icon = Icons.Filled.CloudOff,
        title = ExplorerUiText.offlineTitle,
        body = ExplorerUiText.offlineBody(age),
        container = tone.container,
        border = tone.border,
        content = tone.content,
        iconTint = tone.muted,
        tag = "explorer.offline.card",
    ) {
        Text(ExplorerUiText.savedStillOpen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone.muted)
    }
}

@Composable
internal fun LoadFailedCard(key: Any, seconds: Int = 8, onRetry: () -> Unit) {
    val tone = explorerTone(ExplorerStatus.FAILED)!!
    var left by remember(key) { mutableIntStateOf(seconds) }
    LaunchedEffect(key) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        onRetry()
    }
    StateCard(
        icon = Icons.Filled.ErrorOutline,
        title = ExplorerUiText.failTitle,
        body = ExplorerUiText.retryIn(left),
        container = tone.container,
        border = tone.border,
        content = tone.content,
        iconTint = tone.accent,
        tag = "explorer.error",
    ) {
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = tone.accent, contentColor = tone.onAccent),
            modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.error.retry"),
        ) { Text(ExplorerUiText.tryNow) }
    }
}

@Composable
internal fun FolderSkeleton() {
    Column(Modifier.fillMaxWidth().testTag("explorer.loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBar(0.7f, 22)
            SkeletonBar(0.5f, 14)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(1f, 40, Modifier.weight(1f))
                SkeletonBar(1f, 40, Modifier.weight(1f))
            }
        }
        repeat(3) { i ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(MaterialTheme.field.surface100, MaterialTheme.shapes.small))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBar(1f, 12)
                    SkeletonBar(listOf(0.6f, 0.4f, 0.55f)[i], 10)
                }
            }
        }
    }
}
