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
    ExplorerJobState.DONE -> if ((job.result?.failedCount ?: 0) > 0 && (job.result?.saved ?: 0) == 0) ExplorerStatus.FAILED else ExplorerStatus.SAVED
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
) {
    val percent = jobPercent(job)
    val paused = job.state == ExplorerJobState.PAUSED
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.shapes.large)
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("explorer.progress"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (paused) "${job.request.title} · ${ExplorerText.paused}" else ExplorerUiText.downloading(job.request.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = jobSummary(job)
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            percent?.let { Text(ExplorerUiText.percent(it), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("explorer.progress.percent")) }
        }
        val fraction = job.fraction
        if (fraction != null) {
            FieldLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
        } else {
            FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp))
        }
        busyOrWaiting(job)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (job.resumedFrom > 0 && job.active) Text(ExplorerUiText.pickingUp(job.resumedFrom + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.progress.resumed"))
        Row(Modifier.fillMaxWidth()) {
            Fact(ExplorerUiText.downloaded, if (job.totalBytes > 0) ExplorerBytes.progress(job.doneBytes, job.totalBytes) else "—", Modifier.weight(1f))
            Fact(ExplorerUiText.filesLabel, if (job.totalFiles > 0) ExplorerText.filesOf(job.doneFiles, job.totalFiles) else "—", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            val speed = job.speedBps?.takeIf { it > 0 && !paused }?.let { ExplorerText.speed(it) }
            Fact(ExplorerUiText.speed, listOfNotNull(speed ?: "—", network?.takeIf { speed != null }).joinToString(" · "), Modifier.weight(1f))
            Fact(ExplorerUiText.timeLeft, job.secondsLeft()?.takeIf { !paused }?.let { ExplorerText.aboutLeft(it) } ?: "—", Modifier.weight(1f))
        }
        val current = job.current?.takeIf { it != ExplorerText.resuming && it != ExplorerText.waitingForNetwork }
        if (current != null && !paused) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("explorer.progress.now"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(ExplorerUiText.now, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val own = job.currentFraction
                if (own != null) {
                    FieldLinearProgressIndicator(progress = { own }, modifier = Modifier.fillMaxWidth().height(6.dp))
                } else {
                    FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                }
                if (job.currentTotal > 0) Text(ExplorerBytes.progress(job.currentBytes, job.currentTotal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val warn = explorerWarnTone()
        Text(
            listOfNotNull(job.retrying.takeIf { it > 0 }?.let { ExplorerText.retryNote(it) }, ExplorerText.keepsGoing).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = if (job.retrying > 0) warn.muted else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
                .let { if (job.retrying > 0) it.background(warn.container, MaterialTheme.shapes.medium).padding(horizontal = 10.dp, vertical = 8.dp) else it }
                .testTag("explorer.progress.note"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.pausable || paused) {
                OutlinedButton(onClick = if (paused) onResume else onPause, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.progress.pause")) {
                    Text(if (paused) ExplorerText.resume else ExplorerText.pause)
                }
            }
            val failed = explorerTone(ExplorerStatus.FAILED)!!
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = failed.accent),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.progress.cancel"),
            ) { Text(ExplorerText.cancel) }
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
    Column(modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun RecentFilesCard(job: ExplorerJob, onAll: (() -> Unit)? = null) {
    val recent = job.recent
    if (recent.isEmpty()) return
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .testTag("explorer.recent"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ExplorerUiText.recentFiles, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onAll != null && job.totalFiles > 0) {
                androidx.compose.material3.TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.recent.all")) {
                    Text(ExplorerUiText.allN(job.totalFiles))
                }
            } else if (job.totalFiles > 0) {
                Text(ExplorerText.filesOf(job.doneFiles, job.totalFiles), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        recent.reversed().forEach { RecentRow(it, job.active) }
    }
}

@Composable
internal fun RecentRow(file: ExplorerRecent, active: Boolean) {
    val status = when (file.status) {
        ExplorerRecentStatus.SAVED -> ExplorerStatus.SAVED
        ExplorerRecentStatus.FAILED, ExplorerRecentStatus.RETRYING -> ExplorerStatus.FAILED
        ExplorerRecentStatus.RUNNING -> ExplorerStatus.NEUTRAL
    }
    val tone = explorerTone(status)
    Row(
        Modifier.fillMaxWidth()
            .background(tone?.container ?: Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("explorer.recent.${file.status.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val mark = when (file.status) {
            ExplorerRecentStatus.SAVED -> "✓"
            ExplorerRecentStatus.FAILED, ExplorerRecentStatus.RETRYING -> "!"
            ExplorerRecentStatus.RUNNING -> "↓"
        }
        Box(
            Modifier.size(22.dp).background(if (tone != null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = tone?.accent ?: MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(file.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = tone?.content ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val end = when (file.status) {
            ExplorerRecentStatus.SAVED -> ExplorerBytes.format(file.sizeBytes)
            ExplorerRecentStatus.RETRYING -> if (active) ExplorerUiText.retrying else ExplorerUiText.failed
            ExplorerRecentStatus.FAILED -> ExplorerUiText.failed
            ExplorerRecentStatus.RUNNING -> file.fraction?.let { ExplorerUiText.percent((it * 100).toInt()) }
        }
        end?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tone?.muted ?: MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ResultCard(job: ExplorerJob, onOpen: () -> Unit, onShare: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val status = jobStatus(job)
    val tone = explorerTone(status) ?: return
    Column(
        Modifier.fillMaxWidth()
            .background(tone.container, MaterialTheme.shapes.large)
            .border(1.dp, tone.border, MaterialTheme.shapes.large)
            .padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("explorer.result"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status == ExplorerStatus.SAVED) {
            val saved = job.result?.saved ?: 0
            Text(
                if (saved > 1) "✓ ${ExplorerText.savedFiles(saved)}" else "✓ ${ExplorerText.savedShort} ${job.request.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tone.content,
            )
            Text(jobSavedLine(job), style = MaterialTheme.typography.bodySmall, color = tone.muted)
            val failed = job.result?.failedCount ?: 0
            if (failed > 0) Text(ExplorerText.failedFiles(failed), style = MaterialTheme.typography.bodySmall, color = tone.muted)
            if (job.result?.uri != null || job.result?.location != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShare, colors = ButtonDefaults.outlinedButtonColors(contentColor = tone.content), modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(ExplorerUiText.share) }
                    Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = tone.accent, contentColor = tone.onAccent), modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.result.open")) { Text(ExplorerUiText.open) }
                }
            } else {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) { Text(ExplorerUiText.dismiss) }
            }
        } else {
            Text(job.request.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tone.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ExplorerUiText.failed} · ${job.error ?: ExplorerText.fileFailed}", style = MaterialTheme.typography.bodySmall, color = tone.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, colors = ButtonDefaults.outlinedButtonColors(contentColor = tone.content), modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text(ExplorerUiText.dismiss) }
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = tone.accent, contentColor = tone.onAccent), modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("explorer.result.retry")) { Text(ExplorerUiText.retry) }
            }
        }
        if (status == ExplorerStatus.SAVED) {
            job.result?.location?.let { Text(ExplorerText.savedTo(it), style = MaterialTheme.typography.labelSmall, color = tone.muted) }
        }
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
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
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
                        items(shown.size) { i -> RecentRow(shown[i], job.active) }
                    }
                }
            }
        }
    }
}
