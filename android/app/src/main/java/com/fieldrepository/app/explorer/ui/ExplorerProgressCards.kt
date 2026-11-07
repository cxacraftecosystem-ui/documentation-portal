package com.fieldrepository.app.explorer.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerKind
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field

internal fun jobStatus(job: ExplorerJob): ExplorerStatus = when (job.state) {
    ExplorerJobState.DONE -> {
        val failed = job.result?.failedCount ?: 0
        when {
            failed > 0 && (job.result?.saved ?: 0) == 0 -> ExplorerStatus.FAILED
            failed > 0 -> ExplorerStatus.PARTIAL
            else -> ExplorerStatus.SAVED
        }
    }
    ExplorerJobState.FAILED -> ExplorerStatus.FAILED
    else -> ExplorerStatus.NEUTRAL
}

internal fun jobSummary(job: ExplorerJob): String {
    val include = job.request.sources.firstOrNull()?.include
    val what = if (include.isNullOrBlank()) ExplorerText.everything else include.split(',').map { ExplorerKind.ofToken(it.trim()).label }.joinToString(", ")
    return listOfNotNull(
        what.takeIf { job.request.kind == ExplorerJobKind.FOLDER },
        job.totalFiles.takeIf { it > 1 }?.let { ExplorerBytes.count(it, "file") },
        ExplorerBytes.format(job.totalBytes.takeIf { it > 0 }),
    ).joinToString(" · ")
}

internal fun jobPercent(job: ExplorerJob): Int? = com.fieldrepository.app.explorer.data.ExplorerProgress.percent(job)

internal fun jobSavedLine(job: ExplorerJob): String {
    val result = job.result
    val bytes = result?.bytes?.takeIf { it > 0 } ?: job.totalBytes.takeIf { it > 0 }
    return listOfNotNull(
        result?.takeIf { it.total > 1 }?.let { ExplorerBytes.count(it.saved, "file") },
        ExplorerBytes.format(bytes),
        result?.location?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }?.let { "$it/" },
    ).joinToString(" · ").ifBlank { ExplorerText.doneLine(result) }
}

@Composable
internal fun ProgressCard(
    job: ExplorerJob,
    network: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    large: Boolean = false,
) {
    val percent = jobPercent(job)
    val paused = job.state == ExplorerJobState.PAUSED
    val border = if (MaterialTheme.field.isDark) com.fieldrepository.app.ui.FieldPalette.Purple800 else com.fieldrepository.app.ui.FieldPalette.Purple300
    Column(
        Modifier.fillMaxWidth()
            .shadow(if (MaterialTheme.field.isDark) 0.dp else 6.dp, ExplorerShapes.card, ambientColor = MaterialTheme.field.shadow.copy(alpha = 0.10f), spotColor = MaterialTheme.field.shadow.copy(alpha = 0.10f))
            .explorerCard(line = border)
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("explorer.progress"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (paused) "${job.request.title} · ${ExplorerText.paused}" else if (large) ExplorerUiText.downloading else ExplorerUiText.downloading(job.request.title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val summary = jobSummary(job)
                if (summary.isNotBlank() && !large) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            percent?.let { Text(ExplorerUiText.percent(it), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("explorer.progress.percent")) }
        }
        ExplorerBar(job.fraction, height = 10.dp)
        busyOrWaiting(job)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (job.resumedFrom > 0 && job.active) Text(ExplorerUiText.pickingUp(job.resumedFrom + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.progress.resumed"))
        val bytes = if (job.totalBytes > 0) ExplorerBytes.progress(job.doneBytes, job.totalBytes) else "—"
        val files = if (job.totalFiles > 0) ExplorerText.filesOf(job.doneFiles, job.totalFiles) else "—"
        val speed = job.speedBps?.takeIf { it > 0 && !paused }?.let { ExplorerText.speed(it) }
        val speedLine = listOfNotNull(speed ?: "—", network?.takeIf { speed != null }).joinToString(" · ")
        val left = job.secondsLeft()?.takeIf { !paused }?.let { ExplorerText.aboutLeft(it) }
        if (large) {
            Text(bytes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(job.totalFiles.takeIf { it > 0 }?.let { ExplorerUiText.fileOf(job.doneFiles, it) }, job.secondsLeft()?.takeIf { !paused }?.let { ExplorerText.timeLeft(it) }).joinToString(" · ").ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (speed != null) Text(speedLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(ExplorerUiText.downloaded, bytes, Modifier.weight(1f))
                    Fact(ExplorerUiText.filesLabel, files, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(ExplorerUiText.speed, speedLine, Modifier.weight(1f))
                    Fact(ExplorerUiText.timeLeft, left ?: "—", Modifier.weight(1f))
                }
            }
        }
        val current = job.current?.takeIf { it != ExplorerText.resuming && it != ExplorerText.waitingForNetwork }
        if (current != null && !paused) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.field.surface50, ExplorerShapes.control)
                    .border(1.dp, MaterialTheme.field.hairline, ExplorerShapes.control)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("explorer.progress.now"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(ExplorerUiText.now, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = if (large) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                ExplorerBar(job.currentFraction, height = 6.dp, fill = if (MaterialTheme.field.isDark) MaterialTheme.colorScheme.primary else com.fieldrepository.app.ui.FieldPalette.Purple600)
                if (job.currentTotal > 0) Text(ExplorerBytes.progress(job.currentBytes, job.currentTotal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val warn = explorerWarnTone()
        Text(
            listOfNotNull(job.retrying.takeIf { it > 0 }?.let { ExplorerText.retryNote(it) }, ExplorerText.keepsGoing).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = if (job.retrying > 0) warn.muted else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
                .let { if (job.retrying > 0) it.background(warn.container, ExplorerShapes.tile).padding(horizontal = 10.dp, vertical = 8.dp) else it }
                .testTag("explorer.progress.note"),
        )
        val failed = explorerTone(ExplorerStatus.FAILED)!!
        val pause: @Composable (Modifier) -> Unit = { m ->
            ExplorerSecondaryButton(if (paused) ExplorerText.resume else ExplorerText.pause, onClick = if (paused) onResume else onPause, modifier = m.testTag("explorer.progress.pause"))
        }
        val cancel: @Composable (Modifier) -> Unit = { m ->
            ExplorerSecondaryButton(
                ExplorerText.cancel,
                onClick = onCancel,
                line = if (MaterialTheme.field.isDark) failed.border else com.fieldrepository.app.ui.FieldPalette.Error200,
                content = failed.accent,
                modifier = m.testTag("explorer.progress.cancel"),
            )
        }
        val canPause = job.pausable || paused
        if (large) {
            if (canPause) pause(Modifier.fillMaxWidth())
            cancel(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canPause) pause(Modifier.weight(1f))
                cancel(Modifier.weight(1f))
            }
        }
    }
}

private fun busyOrWaiting(job: ExplorerJob): String? = when {
    job.busySeconds != null -> ExplorerText.busy(job.busySeconds)
    job.current == ExplorerText.waitingForNetwork || job.current == ExplorerText.resuming -> job.current
    job.state == ExplorerJobState.QUEUED -> if (job.request.wifiOnly) ExplorerText.waitingForWifi else ExplorerText.queued
    else -> null
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun RecentFilesCard(job: ExplorerJob, onAll: (() -> Unit)? = null) {
    val recent = job.recent
    if (recent.isEmpty()) return
    val shown = recent.reversed()
    Column(
        Modifier.fillMaxWidth()
            .clip(ExplorerShapes.card)
            .explorerCard()
            .testTag("explorer.recent"),
    ) {
        val line = MaterialTheme.field.hairline
        Row(
            Modifier.fillMaxWidth()
                .drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                .heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ExplorerUiText.recentFiles, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (onAll != null && job.totalFiles > 0) {
                ExplorerLinkButton(ExplorerUiText.allN(job.totalFiles), onClick = onAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.recent.all"))
            } else if (job.totalFiles > 0) {
                Text(ExplorerText.filesOf(job.doneFiles, job.totalFiles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        shown.forEachIndexed { index, file -> RecentRow(file, job.active, last = index == shown.lastIndex) }
    }
}

@Composable
internal fun RecentRow(file: ExplorerRecent, active: Boolean, last: Boolean = false) {
    val status = when (file.status) {
        ExplorerRecentStatus.SAVED -> ExplorerStatus.SAVED
        ExplorerRecentStatus.FAILED, ExplorerRecentStatus.RETRYING -> ExplorerStatus.FAILED
        ExplorerRecentStatus.RUNNING -> ExplorerStatus.NEUTRAL
    }
    val tone = explorerTone(status)
    val divider = tone?.border?.copy(alpha = 0.6f) ?: MaterialTheme.field.hairline
    Row(
        Modifier.fillMaxWidth()
            .background(tone?.container ?: Color.Transparent)
            .drawBehind { if (!last) drawLine(divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("explorer.recent.${file.status.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val mark = when (file.status) {
            ExplorerRecentStatus.SAVED -> ExplorerMarkKind.SAVED
            ExplorerRecentStatus.FAILED, ExplorerRecentStatus.RETRYING -> ExplorerMarkKind.FAILED
            ExplorerRecentStatus.RUNNING -> ExplorerMarkKind.RUNNING
        }
        val (markBg, markFg) = explorerMark()
        ExplorerMarkBadge(mark, if (tone != null) MaterialTheme.colorScheme.surface else markBg, tone?.accent ?: markFg)
        Text(file.name.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, color = tone?.content ?: MaterialTheme.colorScheme.onSurface, maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val end = when (file.status) {
            ExplorerRecentStatus.SAVED -> ExplorerBytes.format(file.sizeBytes)
            ExplorerRecentStatus.RETRYING -> if (active) ExplorerUiText.retrying else ExplorerUiText.failed
            ExplorerRecentStatus.FAILED -> ExplorerUiText.failed
            ExplorerRecentStatus.RUNNING -> file.fraction?.let { ExplorerUiText.percent((it * 100).toInt()) }
        }
        end?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = when (status) { ExplorerStatus.FAILED -> tone!!.accent; ExplorerStatus.SAVED -> tone!!.muted; else -> MaterialTheme.colorScheme.onSurfaceVariant }) }
    }
}

@Composable
internal fun ResultCard(job: ExplorerJob, onOpen: () -> Unit, onRetry: () -> Unit) {
    val status = jobStatus(job)
    val tone = explorerTone(status) ?: return
    val partial = status == ExplorerStatus.PARTIAL
    val saved = status == ExplorerStatus.SAVED || partial
    val canOpen = job.result?.uri != null || job.result?.location != null
    Column(
        Modifier.fillMaxWidth()
            .explorerCard(tone.container, tone.border)
            .padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("explorer.result"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val count = job.result?.saved ?: 0
            if (saved) ExplorerMarkIcon(if (partial) ExplorerMarkKind.FAILED else ExplorerMarkKind.SAVED, tone.content, 16.dp)
            Text(
                if (saved) (if (count > 1 && job.request.kind != ExplorerJobKind.FOLDER) ExplorerText.savedFiles(count) else "${ExplorerText.savedShort} ${job.request.title}") else job.request.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = tone.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (saved && canOpen) {
                ExplorerLinkButton(ExplorerUiText.open, onClick = onOpen, color = tone.accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.result.open"))
            } else if (!saved) {
                ExplorerLinkButton(ExplorerUiText.retry, onClick = onRetry, color = tone.accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.result.retry"))
            }
        }
        val detail = if (saved) {
            listOfNotNull(jobSavedLine(job), job.result?.failedCount?.takeIf { it > 0 }?.let { ExplorerText.failedFiles(it) }).joinToString(" · ")
        } else {
            "${ExplorerUiText.failed} · ${job.error ?: ExplorerText.fileFailed}"
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = tone.muted)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AllFilesSheet(job: ExplorerJob, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var files by androidx.compose.runtime.remember(job.id) { androidx.compose.runtime.mutableStateOf<List<ExplorerRecent>?>(null) }
    androidx.compose.runtime.LaunchedEffect(job.id, job.doneFiles, job.state) {
        files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.fieldrepository.app.explorer.data.ExplorerDownloads.files(context, job.id) }.getOrDefault(emptyList())
        }
    }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ExplorerShapes.sheet, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { ExplorerSheetHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("explorer.allfiles")) {
            Text(ExplorerUiText.allFiles, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(job.request.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = files
            when {
                list == null -> LoadingLine(ExplorerText.preparing)
                list.isEmpty() -> HintLine(ExplorerUiText.noFilesYet)
                else -> {
                    val shown = list.asReversed()
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
                        items(shown.size) { i -> RecentRow(shown[i], job.active, last = i == shown.lastIndex) }
                    }
                }
            }
        }
    }
}
