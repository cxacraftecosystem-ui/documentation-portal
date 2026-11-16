package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.ui.ExplorerAudioExpand
import com.fieldrepository.app.explorer.ui.ExplorerMediaContent
import com.fieldrepository.app.explorer.ui.ExplorerMediaDialog
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.explorer.ui.ExplorerUiText
import com.fieldrepository.app.questionnaire.data.ClipPayload
import com.fieldrepository.app.questionnaire.data.OverlayTake
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant

data class InPlaceTake(
    val key: String,
    val mediaKey: String,
    val recordingId: String?,
    val title: String,
    val subtitle: String?,
    val durationMs: Long,
    val localPath: String? = null,
    val mediaType: String = "AUDIO",
    val retryStep: String? = null,
    val openable: Boolean = true
) {
    val local: Boolean get() = recordingId == null
}

data class LocalClip(
    val take: OverlayTake,
    val line: String,
    val path: String?,
    val contentType: String? = null,
    val stepId: String? = null,
    val failed: Boolean = false,
    val landed: Boolean = false
)

object LocalTakes {
    fun line(clip: ClipPayload, failed: Boolean, online: Boolean): String = when {
        failed -> QuestionnaireCopy.t("uploadFailed")
        clip.uploadedPercent > 0 || online -> QuestionnaireCopy.uploading(clip.uploadedPercent)
        else -> QuestionnaireCopy.s("upload.waiting")
    }

    fun of(
        unit: SessionUnit?,
        tracked: Set<String> = emptySet(),
        online: Boolean = false,
        isFile: (String) -> Boolean = { File(it).isFile }
    ): List<LocalClip> {
        val steps = unit?.steps.orEmpty().filter { it.kind == StepKind.CLIP && it.clip != null }
        val sessionKey = unit?.let { it.sessionId ?: it.clientKey }.orEmpty()
        return steps.flatMap { step ->
            val clip = step.clip!!
            val failed = step.state == StepState.FAILED
            val landed = step.state == StepState.DONE
            if (landed && clip.clientClipKey !in tracked) return@flatMap emptyList()
            val targets = clip.recording.targets.map { it.targetKey }.ifEmpty { listOf(InPlaceTakes.WHOLE) }
            targets.map { target ->
                LocalClip(
                    OverlayTake(
                        clip.clientClipKey, sessionKey, "", target, clip.recording.deviceCapturedAt, clip.recording.durationMs,
                        clip.recording.rosterCount, uploading = clip.uploaded || clip.partsDone.isNotEmpty()
                    ),
                    if (landed) QuestionnaireCopy.s("upload.uploaded") else line(clip, failed, online),
                    if (landed) null else clip.localPath.takeIf(isFile), clip.contentType, step.id, failed, landed
                )
            }
        }
    }

    fun arriving(files: List<Pair<String, PickedFile>>, targetKey: String, online: Boolean, fallbackAt: Instant = Instant.now()): List<LocalClip> = files.map { (key, file) ->
        LocalClip(
            OverlayTake(key, "", "", targetKey, (file.recordedAt ?: fallbackAt).toString(), file.durationMs, 1, uploading = false),
            if (online) QuestionnaireCopy.uploading(0) else QuestionnaireCopy.s("upload.waiting"), file.path, file.mime
        )
    }

    fun merge(own: List<LocalClip>, arriving: List<LocalClip>): List<LocalClip> {
        val queued = own.map { it.take.clientClipKey }.toSet()
        return own + arriving.filter { it.take.clientClipKey !in queued }
    }
}

object InPlaceTakes {
    private const val LOCAL = "local:"
    const val WHOLE = "W:session"

    fun localKey(clientClipKey: String): String = LOCAL + clientClipKey

    fun mediaTypeOf(contentType: String?): String = if (contentType?.lowercase()?.startsWith("video/") == true) "VIDEO" else "AUDIO"

    fun whenLine(capturedAt: String?): String? = capturedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let {
        QuestionnaireCopy.day(it.atZone(Naming.IST).toLocalDate()) + " · " + QuestionnaireCopy.clock(it)
    }

    private fun live(recordings: List<QRecording>, targetKey: String): List<QRecording> =
        recordings.filter { r ->
            r.removedAt == null && r.mediaFileId != null && if (targetKey == WHOLE) r.targets.isEmpty() else r.targets.any { it.targetKey == targetKey }
        }

    private class Found(val at: String, val recording: QRecording?, val clip: LocalClip?)

    private fun found(targetKey: String, recordings: List<QRecording>, clips: List<LocalClip>): List<Found> {
        val server = live(recordings, targetKey)
        val uploaded = server.mapNotNull { it.clientClipKey }.toSet()
        val local = clips.filter { (it.path != null || it.landed) && it.take.targetKey == targetKey && it.take.clientClipKey !in uploaded }.distinctBy { it.take.clientClipKey }
        return (server.map { Found(it.capturedAt.orEmpty(), it, null) } + local.map { Found(it.take.capturedAt, null, it) }).sortedBy { it.at }
    }

    private fun build(f: Found, title: String, subtitle: String?): InPlaceTake {
        val r = f.recording
        if (r != null) return InPlaceTake(r.id, r.mediaFileId!!, r.id, title, subtitle, r.durationMs, mediaType = r.mediaType)
        val clip = f.clip!!
        val key = localKey(clip.take.clientClipKey)
        return InPlaceTake(
            key, key, null, title, subtitle, clip.take.durationMs, clip.path, mediaTypeOf(clip.contentType),
            retryStep = clip.stepId?.takeIf { clip.failed }, openable = clip.path != null
        )
    }

    private fun duration(f: Found): Long = f.recording?.durationMs ?: f.clip?.take?.durationMs ?: 0L

    private fun state(f: Found): String? = f.clip?.line ?: whenLine(f.recording?.capturedAt)

    fun section(sectionId: String, recordings: List<QRecording>, clips: List<LocalClip>): List<InPlaceTake> =
        found("S:$sectionId", recordings, clips).mapIndexed { i, f ->
            val word = when {
                i == 0 -> QuestionnaireCopy.t("sectionTake")
                f.recording?.continuationOfId != null -> QuestionnaireCopy.t("takePart", "n" to i + 1)
                else -> QuestionnaireCopy.t("takeN", "n" to i + 1)
            }
            build(f, word + " · " + QuestionnaireCopy.elapsed(duration(f)), state(f))
        }

    fun whole(recordings: List<QRecording>, clips: List<LocalClip>): List<InPlaceTake> =
        found(WHOLE, recordings, clips).mapIndexed { i, f ->
            val word = if (f.recording?.continuationOfId != null) QuestionnaireCopy.t("takePart", "n" to i + 1) else QuestionnaireCopy.t("takeN", "n" to i + 1)
            build(f, word + " · " + QuestionnaireCopy.elapsed(duration(f)), state(f))
        }

    fun scopeOf(recording: QRecording): String = recording.targets.firstOrNull()?.targetKey ?: WHOLE

    fun question(lineageId: String, recordings: List<QRecording>, clips: List<LocalClip>): List<InPlaceTake> {
        val all = found("Q:$lineageId", recordings, clips)
        return all.map { f ->
            val line = state(f).orEmpty()
            if (all.size == 1) build(f, line, null) else build(f, QuestionnaireCopy.t("questionTake", "duration" to QuestionnaireCopy.elapsed(duration(f))), line)
        }
    }

    fun entry(take: InPlaceTake, name: String): ExplorerEntry =
        if (!take.local) ExplorerAudioExpand.entry(take.mediaKey, name).copy(mediaType = take.mediaType)
        else ExplorerEntry(name = name, path = take.mediaKey, mediaId = take.mediaKey, mediaType = take.mediaType, previewPath = take.localPath?.let { File(it).toURI().toString() })

    fun openKey(scope: String, take: InPlaceTake): String = "$scope|${take.key}"

    fun isOpen(open: String?, scope: String, take: InPlaceTake): Boolean = open == openKey(scope, take)

    fun openIn(open: String?, scope: String): Boolean = open?.startsWith("$scope|") == true

    fun mediaOf(open: String?, takes: List<InPlaceTake>): String? = open?.substringAfter('|')?.let { key -> takes.firstOrNull { it.key == key }?.mediaKey }

    fun openMedia(open: String?, recordings: List<QRecording>, clips: List<LocalClip>): String? {
        val scope = open?.substringBefore('|', "")?.ifEmpty { null } ?: return null
        return mediaOf(open, found(scope, recordings, clips).map { build(it, "", null) })
    }
}

private class LocalTakeLoader(private val uri: String?) : ExplorerMediaLoader {
    override suspend fun source(entry: ExplorerEntry): String? = uri
    override suspend fun transcript(mediaId: String): Transcript? = null
}

@Composable
internal fun StopTakesWhenClosed(media: String?) {
    val opened = remember { mutableSetOf<String>() }
    media?.let { opened += it }
    LaunchedEffect(media) {
        val now = ExplorerAudio.state.value.item?.mediaId ?: return@LaunchedEffect
        if (now in opened && now != media) ExplorerAudio.stop()
    }
    DisposableEffect(Unit) {
        onDispose {
            val now = ExplorerAudio.state.value.item?.mediaId
            if (now != null && now in opened) ExplorerAudio.stop()
        }
    }
}

@Composable
internal fun OpenTakeGuard(open: String?, media: String?, hidden: Boolean, close: () -> Unit) {
    StopTakesWhenClosed(media)
    LaunchedEffect(open, media, hidden) { if (open != null && (media == null || hidden)) close() }
}

@Composable
internal fun takePreviewHeight(): Dp = (LocalConfiguration.current.screenHeightDp * 0.62f).coerceIn(380f, 560f).dp

@Composable
internal fun OpenFullViewButton(onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).testTag("questionnaire.take.full"),
        contentAlignment = Alignment.Center
    ) { Text(QuestionnaireCopy.t("openFullView"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TakePreview(
    take: InPlaceTake,
    name: String,
    full: Boolean,
    onFull: (Boolean) -> Unit,
    loader: ExplorerMediaLoader? = null,
    fullButton: Boolean = false
) {
    val entry = remember(take, name) { InPlaceTakes.entry(take, name) }
    val bring = remember { BringIntoViewRequester() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fullButton && !take.local) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OpenFullViewButton { onFull(true) } }
        Box(Modifier.fillMaxWidth().height(takePreviewHeight()).clip(RoundedCornerShape(16.dp)).testTag("questionnaire.take.preview")) {
            ExplorerMediaContent(
                entries = listOf(entry),
                start = 0,
                onSave = null,
                onDismiss = {},
                loader = loader ?: if (take.local) LocalTakeLoader(entry.previewPath) else null,
                knownDurationMs = take.durationMs.takeIf { it > 0 },
                positioned = false,
                embedded = true,
                noTranscript = if (take.local) QuestionnaireCopy.t("transcriptAfterUpload") else ExplorerUiText.noTranscript
            )
        }
    }
    LaunchedEffect(take.key) { runCatching { bring.bringIntoView() } }
    if (full && !take.local) ExplorerMediaDialog(
        entries = listOf(entry),
        start = 0,
        onSave = null,
        onDismiss = { onFull(false) },
        knownDurationMs = take.durationMs.takeIf { it > 0 },
        positioned = false
    )
}

data class RevealClearance(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TakeInPlaceRow(
    take: InPlaceTake,
    name: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    loader: ExplorerMediaLoader? = null,
    onRetry: ((String) -> Unit)? = null,
    reveal: Boolean = false,
    clearance: RevealClearance = RevealClearance()
) {
    var full by rememberSaveable(take.key) { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    var rowHeight by remember { mutableStateOf(0) }
    LaunchedEffect(reveal, take.key) {
        if (!reveal) return@LaunchedEffect
        delay(250)
        val top = with(density) { clearance.top.toPx() }
        val bottom = with(density) { clearance.bottom.toPx() }
        runCatching { bring.bringIntoView(Rect(0f, -top, 1f, rowHeight + bottom)) }
    }
    val retry = take.retryStep?.takeIf { onRetry != null }
    Column(Modifier.fillMaxWidth().then(modifier).bringIntoViewRequester(bring).onSizeChanged { rowHeight = it.height }.testTag("questionnaire.take.${take.key}")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(
                    if (take.openable) Modifier.clickable(role = Role.Button, onClickLabel = QuestionnaireCopy.t(if (open) "collapseTake" else "playTake"), onClick = onToggle)
                    else Modifier
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(take.title.ifBlank { null }, take.subtitle).joinToString(", ")
                    if (take.openable) stateDescription = if (open) "expanded" else "collapsed"
                }
                .padding(vertical = 6.dp)
                .testTag("questionnaire.take.${take.key}.head"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!open) TakePlayDot(enabled = take.openable)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(take.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                take.subtitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (take.retryStep != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted
                    )
                }
            }
            if (open && !take.local) OpenFullViewButton { full = true }
            if (retry != null) Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onRetry?.invoke(retry) }
                    .padding(horizontal = 12.dp).testTag("questionnaire.take.${take.key}.retry"),
                contentAlignment = Alignment.Center
            ) { Text(QuestionnaireCopy.t("retry"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
            if (take.openable) Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.field.body)
        }
        if (open && take.openable) TakePreview(take, name, full, { full = it }, loader)
    }
}

@Composable
internal fun TakePlayDot(modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier.size(36.dp).clip(CircleShape).background(
            when {
                active -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.field.surface200
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (enabled) Icons.Filled.PlayArrow else Icons.Filled.CloudUpload, contentDescription = null,
            tint = when {
                active -> MaterialTheme.colorScheme.onPrimary
                enabled -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.field.muted
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun TakesList(
    heading: String?,
    takes: List<InPlaceTake>,
    scope: String,
    open: String?,
    name: String,
    onOpen: (String?) -> Unit,
    onManage: (() -> Unit)? = null,
    loader: ExplorerMediaLoader? = null,
    onRetry: ((String) -> Unit)? = null,
    reveal: String? = null,
    clearance: RevealClearance = RevealClearance()
) {
    if (takes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("questionnaire.takes.$scope")) {
        if (heading != null || onManage != null) Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(heading.orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.field.body, modifier = Modifier.weight(1f))
            onManage?.let { manage ->
                Box(
                    Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = manage).padding(horizontal = 8.dp).testTag("questionnaire.takes.manage"),
                    contentAlignment = Alignment.Center
                ) { Text(QuestionnaireCopy.t("manageTakes"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
            }
        }
        takes.forEach { take ->
            val isOpen = InPlaceTakes.isOpen(open, scope, take)
            TakeInPlaceRow(
                take, name, isOpen, onToggle = { onOpen(if (isOpen) null else InPlaceTakes.openKey(scope, take)) }, loader = loader,
                onRetry = onRetry, reveal = reveal == take.key, clearance = clearance
            )
        }
    }
}
