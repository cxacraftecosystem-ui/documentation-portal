package com.fieldrepository.app.explorer.ui

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
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
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.ui.FieldBodyFontFamily
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field

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
        entry.mediaType != null -> entry.mediaType.lowercase().replaceFirstChar { it.uppercase() }
        entry.transcriptPath != null -> ExplorerText.transcript
        entry.content != null -> "Text"
        else -> "File"
    },
    ExplorerBytes.format(entry.sizeBytes),
).joinToString(" · ")

class ExplorerOffline(val file: java.io.File?, val keep: (coil3.Image) -> Unit)

@Composable
internal fun ExplorerThumb(entry: ExplorerEntry, size: Dp = 44.dp) {
    val context = LocalContext.current
    val offline = if (entry.mediaType == "IMAGE") ExplorerPortal.rememberOffline(entry.mediaId) else null
    val thumb = entry.thumbnailPath
    val source = when {
        thumb != null -> ExplorerPaths.resolve(ExplorerPortal.baseUrl(), ExplorerPaths.withQuery(thumb, "size", "256"))
        entry.mediaType == "IMAGE" && !entry.url.isNullOrBlank() -> entry.url
        else -> null
    }
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size).background(MaterialTheme.field.surface100, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fileIcon(entry), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
    val stored: @Composable () -> Unit = {
        val file = offline?.file
        if (file != null) {
            coil3.compose.AsyncImage(model = file, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(MaterialTheme.shapes.small))
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
        contentDescription = entry.name,
        contentScale = ContentScale.Crop,
        loading = { stored() },
        error = { stored() },
        onSuccess = { state -> offline?.keep?.invoke(state.result.image) },
        modifier = Modifier.size(size).clip(MaterialTheme.shapes.small).testTag("explorer.thumb.${entry.mediaId ?: entry.path}"),
    )
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
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("explorer.folder.${folder.path}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (selecting) ExplorerText.select else ExplorerText.open,
                    onLongClickLabel = ExplorerText.select,
                    onClick = { if (selecting) onToggle() else onOpen() },
                    onLongClick = onLongPress,
                )
                .semantics { if (selecting) stateDescription = if (selected) "Selected" else "Not selected" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.testTag("explorer.check.${folder.path}"))
            Box(
                Modifier.size(36.dp).background(MaterialTheme.field.brandTile, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(recordIcon(folder.recordType), contentDescription = null, tint = MaterialTheme.field.onBrandTile, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                folder.recordType?.takeIf { it.isNotBlank() }?.let { type ->
                    Text(type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!selecting) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
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
) {
    val previewable = entry.mediaType in ExplorerPaths.PREVIEWABLE && (entry.mediaId != null || !entry.url.isNullOrBlank())
    val textual = entry.content != null || (entry.transcriptPath != null && entry.mediaType == null)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("explorer.file.${entry.mediaId ?: entry.path}"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .combinedClickable(
                        role = Role.Button,
                        onClickLabel = if (selecting) ExplorerText.select else ExplorerText.open,
                        onLongClickLabel = ExplorerText.select,
                        onClick = { if (selecting) onToggle() else onOpen() },
                        onLongClick = onLongPress,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selecting) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle() },
                        enabled = ExplorerState.selectable(entry),
                        modifier = Modifier.testTag("explorer.check.${entry.mediaId ?: entry.path}"),
                    )
                }
                ExplorerThumb(entry)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(fileMeta(entry), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!selecting) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (textual) TextButton(onClick = onText, modifier = Modifier.heightIn(min = 44.dp)) { Text(if (textOpen) ExplorerText.hide else ExplorerText.view) }
                    if (entry.transcriptAvailable && entry.mediaType != null) {
                        TextButton(onClick = onTranscript, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.transcript.${entry.mediaId ?: entry.path}")) {
                            Text(if (transcriptOpen) ExplorerText.hide else ExplorerText.transcript)
                        }
                    }
                    if (previewable) TextButton(onClick = onPreview, enabled = online, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.preview.${entry.mediaId ?: entry.path}")) { Text(ExplorerText.preview) }
                    if (entry.mediaId != null || entry.content != null) {
                        if (saveJob != null) {
                            TextButton(onClick = onCancelSave, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.save.cancel.${entry.mediaId ?: entry.path}")) {
                                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(ExplorerText.cancel)
                            }
                        } else {
                            TextButton(onClick = onSave, enabled = online || entry.mediaId == null, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.save.${entry.mediaId ?: entry.path}")) { Text(ExplorerText.save) }
                        }
                    }
                }
                saveJob?.let { job ->
                    val fraction = job.fraction
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (fraction != null) {
                            FieldLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp))
                        } else {
                            FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        Text(jobLine(job), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                extra()
                if (textOpen && textual) {
                    val body = entry.content ?: (transcript as? TranscriptLoad.Loaded)?.text
                    when {
                        body != null -> TextPanel { PlainTextBody(body) }
                        transcript is TranscriptLoad.Loading -> LoadingLine(ExplorerText.preparing)
                        else -> HintLine(ExplorerText.transcriptFailed)
                    }
                }
                if (transcriptOpen) {
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
}

internal fun jobLine(job: ExplorerJob): String = when {
    job.busySeconds != null -> ExplorerText.busy(job.busySeconds)
    job.current == ExplorerText.waitingForNetwork || job.current == ExplorerText.resuming -> job.current.orEmpty()
    job.state == com.fieldrepository.app.explorer.data.ExplorerJobState.QUEUED ->
        if (job.request.wifiOnly) ExplorerText.waitingForWifi else ExplorerText.queued
    job.totalBytes > 0 -> ExplorerBytes.progress(job.doneBytes, job.totalBytes) + (ExplorerText.eta(job)?.let { " · $it" } ?: "")
    job.totalFiles > 1 -> ExplorerText.fetching(job.doneFiles, job.totalFiles)
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
internal fun PlainTextBody(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body, lineHeight = 18.sp)
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
                    Text(inlineMarkdown(line.trimStart().drop(2)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body, lineHeight = 18.sp)
                }
                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body, lineHeight = 18.sp)
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelWidth = (maxWidth * 0.34f).coerceIn(88.dp, 132.dp)
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small),
            ) {
                fields.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.field.hairline)
                    InfoRow(field.label, field.value, labelWidth)
                }
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
                lineHeight = 20.sp,
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
