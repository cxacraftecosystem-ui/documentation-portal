package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerKind
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import androidx.compose.foundation.border
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading

enum class DownloadChoice { EVERYTHING, TRANSCRIPTS, REPORT }

sealed interface SizeLoad {
    data object Loading : SizeLoad
    data class Known(val size: ExplorerSize) : SizeLoad
    data object Unknown : SizeLoad
}

@Composable
internal fun SheetButtons(primary: String, onPrimary: () -> Unit, enabled: Boolean = true, secondary: String? = null, onSecondary: (() -> Unit)? = null, primaryTag: String = "explorer.sheet.primary") {
    if (secondary == null || onSecondary == null) {
        Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(primaryTag)) { Text(primary) }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onSecondary, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.sheet.secondary")) {
            Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(primaryTag)) {
            Text(primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSheet(
    title: String,
    selection: Boolean,
    size: SizeLoad,
    metered: Boolean,
    online: Boolean,
    freeBytes: Long?,
    onStart: (choice: DownloadChoice, wifiOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var choice by remember { mutableStateOf(DownloadChoice.EVERYTHING) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().testTag("explorer.sheet.download"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(ExplorerText.download, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(Icons.Filled.FolderZip, ExplorerText.everything + " (.zip)", sizeLine(size), choice == DownloadChoice.EVERYTHING, "explorer.choice.everything") { choice = DownloadChoice.EVERYTHING }
                if (!selection) {
                    ChoiceRow(Icons.Filled.Description, ExplorerText.transcriptsZip + " (.zip)", ExplorerText.transcriptsZipNote, choice == DownloadChoice.TRANSCRIPTS, "explorer.choice.transcripts") { choice = DownloadChoice.TRANSCRIPTS }
                    ChoiceRow(Icons.Filled.TableChart, ExplorerText.report, ExplorerText.reportNote, choice == DownloadChoice.REPORT, "explorer.choice.report") { choice = DownloadChoice.REPORT }
                }
            }
            if (choice == DownloadChoice.EVERYTHING) SizeBreakdown(size)
            val known = (size as? SizeLoad.Known)?.size
            val needed = known?.let { ExplorerBytes.spaceNeeded(it.bytes, it.largest, com.fieldrepository.app.explorer.data.ExplorerEngine.PARALLEL) }
            val noRoom = choice == DownloadChoice.EVERYTHING && known != null && needed != null && freeBytes != null && known.bytes > 0 && freeBytes < needed
            when {
                !online -> Notice(ExplorerText.offline, warn = true)
                noRoom -> Notice(ExplorerText.noSpace(ExplorerBytes.format(needed), ExplorerBytes.format(freeBytes)), warn = true, tag = "explorer.sheet.space")
                metered && choice == DownloadChoice.EVERYTHING -> Notice(
                    known?.let { k -> ExplorerBytes.format(k.bytes)?.let { ExplorerText.meteredSize(it) } } ?: ExplorerText.metered,
                    warn = true,
                    tag = "explorer.sheet.metered",
                )
                else -> Text(ExplorerText.keepsGoing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (metered && choice == DownloadChoice.EVERYTHING && online && !noRoom) {
                SheetButtons(
                    primary = ExplorerText.useMobileData,
                    onPrimary = { onStart(choice, false) },
                    secondary = ExplorerText.waitForWifi,
                    onSecondary = { onStart(choice, true) },
                )
            } else {
                SheetButtons(primary = ExplorerText.start, onPrimary = { onStart(choice, false) }, enabled = online && !noRoom)
            }
        }
    }
}

private fun sizeLine(size: SizeLoad): String = when (size) {
    SizeLoad.Loading -> ExplorerText.counting
    SizeLoad.Unknown -> ExplorerText.sizeUnknown
    is SizeLoad.Known -> ExplorerBytes.line(size.size)
}

@Composable
private fun ChoiceRow(icon: ImageVector, label: String, detail: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (selected) MaterialTheme.field.surface100 else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun SizeBreakdown(size: SizeLoad) {
    val known = (size as? SizeLoad.Known)?.size ?: return
    val rows = ExplorerKind.entries.mapNotNull { kind -> known.byKind[kind]?.takeIf { it.files > 0 }?.let { kind to it } }
    if (rows.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium).padding(12.dp).testTag("explorer.sheet.sizes"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(ExplorerBytes.line(known), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("explorer.sheet.total"))
        rows.forEach { (kind, stats) ->
            Row(Modifier.fillMaxWidth()) {
                Text(kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body, modifier = Modifier.weight(1f))
                Text(ExplorerBytes.line(stats.files, stats.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (known.truncated) Text(ExplorerText.partOfArchive, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.onWarningContainer)
    }
}

@Composable
internal fun Notice(text: String, warn: Boolean = false, tag: String? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.field.onWarningContainer else MaterialTheme.field.onSuccessContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (warn) MaterialTheme.field.warningContainer else MaterialTheme.field.successContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .let { if (tag != null) it.testTag(tag) else it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsSheet(
    jobs: List<ExplorerJob>,
    onCancel: (ExplorerJob) -> Unit,
    onRetry: (ExplorerJob) -> Unit,
    onOpen: (ExplorerJob) -> Unit,
    onShare: (ExplorerJob) -> Unit,
    onDismissJob: (ExplorerJob) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
    onPause: (ExplorerJob) -> Unit = {},
    onResume: (ExplorerJob) -> Unit = {},
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ExplorerShapes.sheet, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { ExplorerSheetHandle() }) {
        DownloadsSheetBody(jobs, onCancel, onRetry, onOpen, onShare, onDismissJob, onClearFinished, onPause, onResume, Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun DownloadsSheetBody(
    jobs: List<ExplorerJob>,
    onCancel: (ExplorerJob) -> Unit,
    onRetry: (ExplorerJob) -> Unit,
    onOpen: (ExplorerJob) -> Unit,
    onShare: (ExplorerJob) -> Unit,
    onDismissJob: (ExplorerJob) -> Unit,
    onClearFinished: () -> Unit,
    onPause: (ExplorerJob) -> Unit = {},
    onResume: (ExplorerJob) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).testTag("explorer.sheet.downloads")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ExplorerText.downloads,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (jobs.count { !it.active && it.state != ExplorerJobState.PAUSED } > 1) {
                ExplorerLinkButton(ExplorerText.clearFinished, onClick = onClearFinished, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.sheet.clear"))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (jobs.isEmpty()) {
            Text(ExplorerText.noDownloads, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job,
                        onCancel = { onCancel(job) },
                        onRetry = { onRetry(job) },
                        onOpen = { onOpen(job) },
                        onShare = { onShare(job) },
                        onDismiss = { onDismissJob(job) },
                        onPause = { onPause(job) },
                        onResume = { onResume(job) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun JobAction(text: String, onClick: () -> Unit, tag: String, color: Color = MaterialTheme.colorScheme.primary) {
    ExplorerLinkButton(text, onClick = onClick, color = color, modifier = Modifier.heightIn(min = 40.dp).testTag(tag))
}

@Composable
internal fun JobCard(
    job: ExplorerJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    var showFailed by remember(job.id) { mutableStateOf(false) }
    val tone = explorerTone(jobStatus(job))
    val content = tone?.content ?: MaterialTheme.colorScheme.onSurface
    val muted = tone?.muted ?: MaterialTheme.colorScheme.onSurfaceVariant
    val large = LocalDensity.current.fontScale >= 1.5f
    val compact = job.request.single || job.request.kind == ExplorerJobKind.FILE || job.request.kind == ExplorerJobKind.REPORT || job.request.kind == ExplorerJobKind.TRANSCRIPTS
    val canOpen = job.result?.uri != null || job.result?.location != null
    Column(
        Modifier.fillMaxWidth()
            .explorerCard(tone?.container ?: MaterialTheme.colorScheme.surface, tone?.border ?: MaterialTheme.field.hairline, ExplorerShapes.inner)
            .padding(12.dp)
            .testTag("explorer.job.${job.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val title: @Composable (String, Modifier) -> Unit = { text, m ->
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = content, maxLines = if (large) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = m)
        }
        val accent = tone?.accent ?: MaterialTheme.colorScheme.primary
        val openButton: @Composable (Modifier) -> Unit = { m ->
            ExplorerPrimaryButton(ExplorerText.open, onClick = onOpen, container = accent, content = tone?.onAccent ?: MaterialTheme.colorScheme.onPrimary, minHeight = 44.dp, style = MaterialTheme.typography.bodyMedium, modifier = m.testTag("explorer.job.open"))
        }
        when (job.state) {
            ExplorerJobState.QUEUED, ExplorerJobState.RUNNING, ExplorerJobState.PAUSED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    title(job.request.title, Modifier.weight(1f))
                    val end = if (job.state == ExplorerJobState.PAUSED) ExplorerText.paused else jobPercent(job)?.let { ExplorerUiText.percent(it) }
                    end?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }
                ExplorerBar(if (job.state == ExplorerJobState.QUEUED) null else job.fraction, height = 6.dp, modifier = Modifier.testTag("explorer.job.progress"))
                Text(
                    jobLine(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("explorer.job.status"),
                )
                if (job.retrying > 0) Text(ExplorerText.retryNote(job.retrying), style = MaterialTheme.typography.bodySmall, color = explorerWarnTone().muted)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    when {
                        job.state == ExplorerJobState.PAUSED -> JobAction(ExplorerText.resume, onResume, "explorer.job.resume")
                        job.pausable -> JobAction(ExplorerText.pause, onPause, "explorer.sheet.secondary")
                    }
                    JobAction(ExplorerText.cancel, onCancel, "explorer.job.cancel", explorerTone(ExplorerStatus.FAILED)!!.accent)
                }
            }
            ExplorerJobState.DONE -> {
                val result = job.result
                if (compact && canOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            title("✓ ${job.request.title}", Modifier)
                            Text(jobSavedLine(job), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("explorer.job.done"))
                        }
                        openButton(Modifier)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        title(job.request.title, Modifier.weight(1f))
                        Text(ExplorerUiText.saved, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = accent)
                    }
                    Text(jobSavedLine(job), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("explorer.job.done"))
                    if (result != null && result.failedCount > 0) {
                        ExplorerLinkButton("${ExplorerText.failedFiles(result.failedCount)} · ${if (showFailed) ExplorerText.hide else ExplorerText.show}", onClick = { showFailed = !showFailed }, color = content)
                        if (showFailed) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("explorer.job.failed")) {
                                result.failed.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
                                if (result.failedCount > result.failed.size) Text("…", style = MaterialTheme.typography.labelSmall, color = muted)
                            }
                        }
                    }
                    if (result?.truncated == true) Notice(ExplorerText.partOfArchive, warn = true)
                    if (canOpen) {
                        Spacer(Modifier.height(2.dp))
                        val share: @Composable (Modifier) -> Unit = { m ->
                            ExplorerSecondaryButton(ExplorerText.share, onClick = onShare, line = tone?.border ?: explorerControlLine(), content = content, minHeight = 44.dp, style = MaterialTheme.typography.bodyMedium, modifier = m.testTag("explorer.job.share"))
                        }
                        if (large) {
                            openButton(Modifier.fillMaxWidth())
                            share(Modifier.fillMaxWidth())
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                share(Modifier.weight(1f))
                                openButton(Modifier.weight(1f))
                            }
                        }
                    } else {
                        SheetButtons(primary = ExplorerText.dismiss, onPrimary = onDismiss)
                    }
                }
            }
            ExplorerJobState.FAILED, ExplorerJobState.CANCELLED -> {
                val failed = job.state == ExplorerJobState.FAILED
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(job.request.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = content, maxLines = if (large) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (failed) "${ExplorerUiText.failed} · ${job.error ?: ExplorerText.fileFailed}" else ExplorerText.cancelled,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            modifier = Modifier.testTag(if (failed) "explorer.job.error" else "explorer.job.cancelled"),
                        )
                    }
                    if (failed) {
                        ExplorerPrimaryButton(ExplorerText.retry, onClick = onRetry, container = accent, content = tone?.onAccent ?: MaterialTheme.colorScheme.onPrimary, minHeight = 44.dp, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("explorer.job.retry"))
                    } else {
                        ExplorerSecondaryButton(ExplorerText.retry, onClick = onRetry, minHeight = 44.dp, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("explorer.job.retry"))
                    }
                }
            }
        }
    }
}
