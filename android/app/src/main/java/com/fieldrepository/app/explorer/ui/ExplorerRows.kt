package com.fieldrepository.app.explorer.ui

import com.fieldrepository.app.ui.FieldCheckbox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerKeyValues
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.ui.FieldBodyFontFamily
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.fieldrepository.app.explorer.data.ExplorerJobState

internal fun recordIcon(recordType: String?): ImageVector = when (recordType) {
    "workshop" -> Icons.Filled.Groups
    "craft" -> Icons.Filled.Brush
    "artisan" -> Icons.Filled.Person
    "product" -> Icons.Filled.Inventory2
    "tool" -> Icons.Filled.Build
    "process" -> Icons.Filled.AccountTree
    "interview" -> Icons.AutoMirrored.Filled.Assignment
    "user" -> Icons.Filled.AccountCircle
    "taxonomy" -> Icons.Filled.Layers
    else -> Icons.Filled.Folder
}

internal fun fileIcon(entry: ExplorerEntry): ImageVector = when (entry.mediaType) {
    "IMAGE" -> Icons.Filled.Image
    "VIDEO" -> Icons.Filled.Movie
    "AUDIO" -> Icons.Filled.Audiotrack
    "PDF" -> Icons.Filled.PictureAsPdf
    "DOCUMENT" -> Icons.Filled.Description
    else -> if (entry.content != null || entry.transcriptPath != null) Icons.Filled.Description else Icons.Filled.Attachment
}

internal fun fileMeta(entry: ExplorerEntry): String = listOfNotNull(
    when {
        entry.mediaType == "PDF" || entry.mediaType == "DOCUMENT" -> "Document"
        entry.mediaType != null -> entry.mediaType.lowercase().replaceFirstChar { it.uppercase() }
        entry.transcriptPath != null -> ExplorerText.transcript
        entry.content != null -> "Text"
        else -> "File"
    },
    ExplorerBytes.format(entry.sizeBytes),
    entry.pixels?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" },
    entry.pages?.takeIf { it > 0 }?.let { ExplorerBytes.count(it, "page") },
    "transcript".takeIf { entry.transcriptAvailable && entry.mediaType != null },
    capturedDay(entry.capturedAt),
).joinToString(" · ")

internal fun capturedDay(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let { value ->
    runCatching {
        val instant = runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrElse { java.time.Instant.parse(value) }
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(instant.atZone(java.time.ZoneId.systemDefault()))
    }.getOrNull()
}

internal fun folderMeta(folder: ExplorerEntry): String? {
    val kind = folder.recordType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    val n = folder.count
    val size = listOfNotNull(
        n?.let { ExplorerBytes.count(it, "file") },
        folder.bytes?.takeIf { it > 0 && (n ?: 0) > 0 }?.let { ExplorerBytes.format(it) },
    ).joinToString(" · ").ifBlank { null }
    return listOfNotNull(kind, size).joinToString(" · ").ifBlank { null }
}

class ExplorerOffline(val file: java.io.File?, val keep: (coil3.Image) -> Unit)

internal fun displayName(entry: ExplorerEntry): String =
    if (entry.mediaType in setOf("VIDEO", "AUDIO", "IMAGE") && entry.name.contains('.')) entry.name.substringBeforeLast('.').ifBlank { entry.name } else entry.name

@Composable
internal fun ExplorerThumb(entry: ExplorerEntry, size: Dp = 48.dp) {
    val context = LocalContext.current
    val offline = if (entry.mediaType == "IMAGE") ExplorerPortal.rememberOffline(entry.mediaId) else null
    val thumb = entry.thumbnailPath
    val source = when {
        thumb != null -> ExplorerPaths.resolve(ExplorerPortal.baseUrl(), ExplorerPaths.withQuery(thumb, "size", "256"))
        entry.mediaType == "IMAGE" && !entry.url.isNullOrBlank() -> entry.url
        else -> null
    }
    val fallback: @Composable () -> Unit = { ExplorerTypeTile(entry, size) }
    val stored: @Composable () -> Unit = {
        val file = offline?.file
        if (file != null) {
            ExplorerThumbFrame(size) { coil3.compose.AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
        } else {
            fallback()
        }
    }
    if (source == null) {
        stored()
        return
    }
    val request = remember(source) {
        val builder = ImageRequest.Builder(context).data(source)
        if (thumb != null) {
            ExplorerPortal.token(context)?.let { token ->
                builder.httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $token").build())
            }
            entry.mediaId?.let { id -> builder.memoryCacheKey("explorer-thumb:$id").diskCacheKey("explorer-thumb:$id") }
        }
        builder.build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        loading = { stored() },
        error = { stored() },
        success = {
            val image = this
            ExplorerThumbFrame(size) { image.SubcomposeAsyncImageContent(modifier = Modifier.matchParentSize()) }
        },
        onSuccess = { state -> offline?.keep?.invoke(state.result.image) },
        modifier = Modifier.size(size).clip(ExplorerShapes.tile).testTag("explorer.thumb.${entry.mediaId ?: entry.path}"),
    )
}

@Composable
internal fun ExplorerThumbFrame(size: Dp, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(ExplorerShapes.tile).background(MaterialTheme.colorScheme.surfaceVariant).testTag("explorer.thumb.frame"),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun ExplorerCheck(checked: Boolean, onToggle: () -> Unit, tag: String, enabled: Boolean = true) {
    Box(
        Modifier.size(40.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        FieldCheckbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: ExplorerEntry,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    first: Boolean = true,
    last: Boolean = true,
    tag: String = "explorer.folder.${folder.path}",
) {
    val container = if (selecting && selected) explorerSelectedRow() else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .explorerCell(first, last, container)
            .heightIn(min = 68.dp)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (selecting) ExplorerText.select else ExplorerText.open,
                onLongClickLabel = ExplorerText.select,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = onLongPress,
            )
            .semantics { if (selecting) stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(start = if (selecting) 6.dp else 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) ExplorerCheck(selected, onToggle, "explorer.check.${folder.path}")
        Box(Modifier.size(if (selecting) 44.dp else 48.dp).background(MaterialTheme.colorScheme.primaryContainer, ExplorerShapes.tile), contentAlignment = Alignment.Center) {
            Icon(recordIcon(folder.recordType), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            folderMeta(folder)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.folder.meta.${folder.path}"))
            }
        }
        if (!selecting) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun FileRow(
    entry: ExplorerEntry,
    selecting: Boolean,
    selected: Boolean,
    textOpen: Boolean,
    transcriptOpen: Boolean,
    transcript: TranscriptLoad?,
    saveJob: ExplorerJob?,
    online: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
    onText: () -> Unit,
    onTranscript: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onCancelSave: () -> Unit,
    extra: @Composable () -> Unit = {},
    savedJob: ExplorerJob? = null,
    onOpenSaved: () -> Unit = {},
    first: Boolean = true,
    last: Boolean = true,
    cell: Boolean = true,
    playing: String? = null,
) {
    val previewable = entry.mediaType in ExplorerPaths.PREVIEWABLE && (entry.mediaId != null || !entry.url.isNullOrBlank())
    val textual = entry.content != null || (entry.transcriptPath != null && entry.mediaType == null)
    val hasTranscript = entry.transcriptAvailable && entry.mediaType != null
    val finished = savedJob?.takeIf { saveJob == null }
    val status = when {
        selecting && selected -> ExplorerStatus.NEUTRAL
        finished == null -> ExplorerStatus.NEUTRAL
        else -> jobStatus(finished)
    }
    val tone = explorerTone(status)
    val markFg = explorerMark().second
    val large = LocalDensity.current.fontScale >= 1.5f
    val savable = entry.mediaId != null || entry.content != null
    val id = entry.mediaId ?: entry.path
    val container = when {
        tone != null -> tone.container
        selecting && selected -> explorerSelectedRow()
        playing != null -> explorerSelectedRow()
        else -> MaterialTheme.colorScheme.surface
    }
    var menu by remember { mutableStateOf(false) }
    val actions = !selecting && (textual || hasTranscript || previewable)
    Column(
        Modifier.fillMaxWidth()
            .let { if (cell) it.explorerCell(first, last, container, tone?.border ?: MaterialTheme.field.hairline) else it.background(container) }
            .testTag("explorer.file.$id"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (selecting) ExplorerText.select else ExplorerText.open,
                    onLongClickLabel = ExplorerText.select,
                    onClick = { if (selecting) onToggle() else onOpen() },
                    onLongClick = onLongPress,
                )
                .semantics { if (selecting) stateDescription = if (selected) "Selected" else "Not selected" }
                .padding(start = if (selecting) 6.dp else 14.dp, end = if (actions && !large) 4.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) ExplorerCheck(selected, onToggle, "explorer.check.$id", enabled = ExplorerState.selectable(entry))
            ExplorerThumb(entry, size = if (selecting) 44.dp else 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    displayName(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tone?.content ?: if (playing != null) markFg else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (large) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playing ?: fileMeta(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = tone?.muted ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (playing != null) Modifier.testTag("explorer.playing.$id") else Modifier,
                )
            }
            if (!large && !selecting && savable) SaveAction(entry, saveJob, finished, tone, online, onSave, onCancelSave, onOpenSaved, Modifier)
            if (!large && actions) RowMenuButton(id, menu, { menu = it }, previewable, hasTranscript, textual, transcriptOpen, textOpen, online, onPreview, onTranscript, onText)
        }
        if (large && !selecting && (savable || actions)) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (savable) SaveAction(entry, saveJob, finished, tone, online, onSave, onCancelSave, onOpenSaved, Modifier.weight(1f))
                if (actions) RowMenuButton(id, menu, { menu = it }, previewable, hasTranscript, textual, transcriptOpen, textOpen, online, onPreview, onTranscript, onText)
            }
        }
        if (!selecting) {
            saveJob?.let { job ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExplorerBar(job.fraction, height = 4.dp)
                    Text(
                        listOfNotNull(jobPercent(job)?.let { ExplorerUiText.percent(it) }, jobLine(job)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("explorer.save.progress.$id"),
                    )
                }
            }
            if (textOpen && textual) {
                val body = entry.content ?: (transcript as? TranscriptLoad.Loaded)?.text
                when {
                    body != null -> TextBody(body)
                    transcript is TranscriptLoad.Loading -> LoadingLine(ExplorerText.preparing)
                    else -> HintLine(ExplorerText.transcriptFailed)
                }
            }
            if (transcriptOpen) {
                extra()
                when (transcript) {
                    is TranscriptLoad.Loaded -> TextPanel { TranscriptBody(transcript.text) }
                    TranscriptLoad.None -> HintLine(ExplorerText.transcriptNone)
                    TranscriptLoad.Failed -> HintLine(ExplorerText.transcriptFailed)
                    else -> LoadingLine(ExplorerText.preparing)
                }
            }
        }
    }
}

@Composable
private fun RowMenuButton(
    id: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    previewable: Boolean,
    hasTranscript: Boolean,
    textual: Boolean,
    transcriptOpen: Boolean,
    textOpen: Boolean,
    online: Boolean,
    onPreview: () -> Unit,
    onTranscript: () -> Unit,
    onText: () -> Unit,
) {
    Box {
        IconButton(onClick = { onOpenChange(true) }, modifier = Modifier.size(40.dp).testTag("explorer.actions.$id")) {
            Icon(Icons.Filled.MoreVert, contentDescription = ExplorerUiText.moreActions, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            if (previewable) DropdownMenuItem(text = { Text(ExplorerText.preview) }, enabled = online, onClick = { onOpenChange(false); onPreview() }, modifier = Modifier.testTag("explorer.preview.$id"))
            if (hasTranscript) DropdownMenuItem(text = { Text(if (transcriptOpen) ExplorerUiText.hideTranscript else ExplorerText.transcript) }, onClick = { onOpenChange(false); onTranscript() }, modifier = Modifier.testTag("explorer.transcript.$id"))
            if (textual) DropdownMenuItem(text = { Text(if (textOpen) ExplorerText.hide else ExplorerText.view) }, onClick = { onOpenChange(false); onText() }, modifier = Modifier.testTag("explorer.text.$id"))
        }
    }
}

@Composable
private fun SaveAction(
    entry: ExplorerEntry,
    saveJob: ExplorerJob?,
    finished: ExplorerJob?,
    tone: ExplorerTone?,
    online: Boolean,
    onSave: () -> Unit,
    onCancelSave: () -> Unit,
    onOpenSaved: () -> Unit,
    modifier: Modifier,
) {
    val id = entry.mediaId ?: entry.path
    val shape = ExplorerShapes.tile
    val pad = PaddingValues(horizontal = 12.dp)
    when {
        saveJob != null -> OutlinedButton(onClick = onCancelSave, shape = shape, border = BorderStroke(1.dp, explorerControlLine()), contentPadding = pad, modifier = modifier.heightIn(min = 40.dp).testTag("explorer.save.cancel.$id")) {
            FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(ExplorerText.cancel, style = MaterialTheme.typography.bodyMedium)
        }
        finished != null && finished.state == ExplorerJobState.DONE -> Button(
            onClick = onOpenSaved,
            shape = shape,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = tone?.accent ?: MaterialTheme.colorScheme.primary, contentColor = tone?.onAccent ?: MaterialTheme.colorScheme.onPrimary),
            modifier = modifier.heightIn(min = 40.dp).testTag("explorer.save.open.$id"),
        ) { Text(ExplorerText.open, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        finished != null && finished.state == ExplorerJobState.FAILED -> Button(
            onClick = onSave,
            shape = shape,
            enabled = online || entry.mediaId == null,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = tone?.accent ?: MaterialTheme.colorScheme.error, contentColor = tone?.onAccent ?: MaterialTheme.colorScheme.onError),
            modifier = modifier.heightIn(min = 40.dp).testTag("explorer.save.retry.$id"),
        ) { Text(ExplorerText.retry, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        else -> OutlinedButton(
            onClick = onSave,
            shape = shape,
            enabled = online || entry.mediaId == null,
            border = BorderStroke(1.dp, explorerControlLine()),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = pad,
            modifier = modifier.heightIn(min = 40.dp).testTag("explorer.save.$id"),
        ) { Text(ExplorerText.save, style = MaterialTheme.typography.bodyMedium) }
    }
}

internal fun jobLine(job: ExplorerJob): String = when {
    job.busySeconds != null -> ExplorerText.busy(job.busySeconds)
    job.current == ExplorerText.waitingForNetwork || job.current == ExplorerText.resuming -> job.current.orEmpty()
    job.state == ExplorerJobState.PAUSED -> listOfNotNull(ExplorerText.paused, job.totalBytes.takeIf { it > 0 }?.let { ExplorerBytes.progress(job.doneBytes, it) }).joinToString(" · ")
    job.state == ExplorerJobState.QUEUED -> if (job.request.wifiOnly) ExplorerText.waitingForWifi else ExplorerText.queued
    job.totalBytes > 0 || job.totalFiles > 0 -> ExplorerText.progressLine(job)
    else -> ExplorerText.preparing
}

@Composable
internal fun LoadingLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HintLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
internal fun TextPanel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .heightIn(max = 320.dp)
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer { content() }
    }
}

@Composable
internal fun TextBody(text: String) {
    val parsed = remember(text) { ExplorerKeyValues.parse(text) }
    if (!parsed.tabular) {
        TextPanel { Paragraphs(parsed.paragraphs) }
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp).testTag("explorer.text.table"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        parsed.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
        RecordInfoTable(ExplorerInfo(title = parsed.title.orEmpty(), fields = parsed.fields))
        if (parsed.paragraphs.isNotEmpty()) SelectionContainer { Paragraphs(parsed.paragraphs) }
    }
}

@Composable
internal fun Paragraphs(paragraphs: List<String>) {
    Column(Modifier.testTag("explorer.text.paragraphs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
    }
}

@Composable
internal fun TranscriptBody(markdown: String) {
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.trim().length >= 3 && line.trim().all { it == '-' } -> HorizontalDivider(color = MaterialTheme.field.hairline)
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        inlineMarkdown(line.drop(level).trim()),
                        style = if (level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                    Text(inlineMarkdown(line.trimStart().drop(2)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
                }
                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
            }
        }
    }
}

private val INLINE_MARKDOWN = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")

internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE_MARKDOWN.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        val italic = match.groupValues[2]
        val code = match.groupValues[3]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FieldBodyFontFamily)) { append(code) }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private const val INFO_VALUE_COLLAPSED_LINES = 8

@Composable
internal fun RecordInfoTable(info: ExplorerInfo) {
    val fields = info.fields.filter { it.value.isNotBlank() }
    if (fields.isEmpty()) return
    val labelWidth = 120.dp
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ExplorerShapes.control)
                .border(1.dp, MaterialTheme.field.hairline, ExplorerShapes.control),
        ) {
            fields.forEachIndexed { index, field ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.field.hairline)
                InfoRow(field.label, field.value, labelWidth)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, labelWidth: Dp) {
    var expanded by remember(label, value) { mutableStateOf(false) }
    var foldable by remember(label, value) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(labelWidth))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.body,
                maxLines = if (expanded) Int.MAX_VALUE else INFO_VALUE_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> if (!expanded && layout.hasVisualOverflow) foldable = true },
            )
            if (foldable) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
            }
        }
    }
}

@Composable
internal fun AllFormatsChip(mediaId: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled && !busy,
        label = { Text(if (busy) ExplorerText.preparing else ExplorerText.allFormats) },
        modifier = Modifier.semantics { contentDescription = "${ExplorerText.allFormats} (.zip)" }.testTag("explorer.allformats.$mediaId"),
    )
}

@Composable
internal fun CachedNote(note: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("explorer.cached"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
