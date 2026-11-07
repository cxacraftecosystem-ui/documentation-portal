package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerEntry

object ExplorerAudioExpandText {
    const val expand = "Expand"
}

data class ExplorerAudioHandoff(val positionMs: Long, val playing: Boolean)

object ExplorerAudioExpand {
    fun entry(mediaId: String, name: String): ExplorerEntry =
        ExplorerEntry(name = name.ifBlank { mediaId }, path = mediaId, mediaId = mediaId, mediaType = "AUDIO", transcriptAvailable = true)

    fun handBack(mediaId: String, startMs: Long, wasPlaying: Boolean): ExplorerAudioHandoff {
        val now = ExplorerAudio.state.value
        val handoff = if (now.item?.mediaId == mediaId) ExplorerAudioHandoff(now.positionMs, now.playing) else ExplorerAudioHandoff(startMs, wasPlaying)
        ExplorerAudio.stop()
        return handoff
    }
}

@Composable
fun ExplorerAudioExpandButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    IconButton(onClick = onClick, modifier = modifier.testTag("audio.expand")) {
        Icon(Icons.Filled.OpenInFull, contentDescription = ExplorerAudioExpandText.expand, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ExplorerAudioExpanded(mediaId: String, name: String, startMs: Long, wasPlaying: Boolean, onClose: (ExplorerAudioHandoff) -> Unit) {
    val entry = remember(mediaId, name) { ExplorerAudioExpand.entry(mediaId, name) }
    ExplorerMediaDialog(
        entries = listOf(entry),
        start = 0,
        startSeconds = startMs.coerceAtLeast(0L) / 1000.0,
        onSave = null,
        onDismiss = { onClose(ExplorerAudioExpand.handBack(mediaId, startMs, wasPlaying)) },
    )
}
