package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.fieldrepository.app.ui.LocalAppPreferences
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import com.fieldrepository.app.ui.AppToggle

const val TRACE_PREVIEW_DEBOUNCE_MS: Long = 450L

const val TRACE_AUTO_PREVIEW_BUDGET_MS: Long = 2500L

const val TRACE_DISCLOSURE_TURN_MS: Int = 180

internal const val TRACE_EXPAND_ACTION: String = "Expand the tracing card"

internal const val TRACE_COLLAPSE_ACTION: String = "Collapse the tracing card"

internal const val TRACE_CARD_TITLE: String = "Trace a sketch into line art"

@Stable
internal class TraceCardMemory {

    var tracedName by mutableStateOf("")
        private set

    var shapeCount by mutableIntStateOf(0)
        private set

    var nodeCount by mutableIntStateOf(0)
        private set

    var wasPreview by mutableStateOf(true)
        private set

    var attachedName by mutableStateOf("")
        private set

    fun recordTrace(sourceName: String, shapes: Int, nodes: Int, preview: Boolean) {
        tracedName = sourceName
        shapeCount = shapes
        nodeCount = nodes
        wasPreview = preview
    }

    fun recordAttachment(fileLabel: String) {
        attachedName = fileLabel
    }

    fun forgetTrace() {
        tracedName = ""
        shapeCount = 0
        nodeCount = 0
        wasPreview = true
    }
}

internal fun traceCardSummary(
    tracedName: String,
    shapeCount: Int,
    nodeCount: Int,
    wasPreview: Boolean,
    attachedName: String,
): String? {
    val parts = mutableListOf<String>()
    if (tracedName.isNotBlank()) {
        parts += if (wasPreview) {
            "a preview traced from “${tracedName.trim()}”"
        } else {
            "traced from “${tracedName.trim()}”"
        }
        parts += "$shapeCount paths · $nodeCount nodes"
    }
    if (attachedName.isNotBlank()) parts += "added as “${attachedName.trim()}”"
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}

@Composable
fun TracePanel(
    photograph: Uri?,
    currentFileName: String?,
    enabled: Boolean,
    onAttach: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    subjectHint: String? = null,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val memory = remember { TraceCardMemory() }

    if (!open) {
        val summary = traceCardSummary(
            tracedName = memory.tracedName,
            shapeCount = memory.shapeCount,
            nodeCount = memory.nodeCount,
            wasPreview = memory.wasPreview,
            attachedName = memory.attachedName,
        )
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TracePanelDisclosureHeader(
                icon = Icons.Filled.Gesture,
                title = TRACE_CARD_TITLE,
                expanded = false,
                toggleEnabled = enabled,
                expandAction = TRACE_EXPAND_ACTION,
                collapseAction = TRACE_COLLAPSE_ACTION,
                onToggle = { open = true },
            )
            if (summary == null) {
                Text(
                    "Turn the pencil drawing into vector line art. Added as a separate file; works offline.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
                Text(
                    "Closing discards the trace. Anything added stays added.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ActionRow {
                OutlinedButton(
                    onClick = { open = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (summary == null) "Trace a sketch" else "Open the tracing card again",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        return
    }

    if (photograph == null) {
        TraceEmptyState(enabled = enabled, modifier = modifier, onClose = { open = false })
        return
    }

    TracePanelOpen(
        photograph = photograph,
        currentFileName = currentFileName,
        enabled = enabled,
        subjectHint = subjectHint,
        memory = memory,
        modifier = modifier,
        onClose = { open = false },
        onAttach = onAttach,
        onMessage = onMessage,
        onError = onError,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TracePanelOpen(
    photograph: Uri,
    currentFileName: String?,
    enabled: Boolean,
    subjectHint: String?,
    memory: TraceCardMemory,
    modifier: Modifier,
    onClose: () -> Unit,
    onAttach: (Uri) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = rememberTraceRuntime()
    val exporter = rememberTraceExporter()
    val availability = runtime.availability
    val sourceName = remember(photograph) { traceDisplayName(context, photograph) }

    var presets by remember { mutableStateOf<TracePresetTables?>(null) }
    var params by remember { mutableStateOf<TraceValues?>(null) }

    var baseline by remember { mutableStateOf<TraceValues?>(null) }

    var styleId by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf(traceSubjectFor(subjectHint)) }
    var subjectSeeded by remember { mutableStateOf(subjectHint != null) }

    var result by remember { mutableStateOf<TraceResult?>(null) }
    var resultWire by remember { mutableStateOf<String?>(null) }

    var frame by remember { mutableStateOf<TraceFrameChoice?>(null) }

    var difference by remember { mutableStateOf<Bitmap?>(null) }
    var differenceRefusal by remember { mutableStateOf("") }
    var differenceRunning by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf<TraceRunKind?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TraceProgress?>(null) }
    var weights by remember { mutableStateOf(TraceProgressWeights.Unweighted) }

    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }
    var autoPreview by remember { mutableStateOf(true) }
    var attaching by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }

    var runToken by remember { mutableIntStateOf(0) }

    val patchLock = remember { Mutex() }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val stale = result != null && resultWire != null && resultWire != params?.wire

    LaunchedEffect(runtime) {
        loading = true
        failure = null
        try {
            val tables = runtime.presets()
            val defaults = runtime.defaults()
            presets = tables
            params = defaults
            baseline = defaults
            styleId = defaults.styleId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failure = "Tracing didn't start. Try again, or restart the app."
        }
        loading = false
    }

    fun startRun(kind: TraceRunKind, debounceMs: Long) {
        val values = params ?: return
        val previous = job
        val token = runToken
        val tracedName = sourceName
        job = scope.launch {
            previous?.cancelAndJoin()
            if (debounceMs > 0L) delay(debounceMs)
            running = kind
            stopping = false
            progress = null
            failure = null
            try {
                val outcome = runtime.trace(
                    TraceRequest(
                        photograph = photograph,
                        params = values,
                        kind = kind,
                        frame = frame,
                    ),
                ) { progress = it }
                if (token != runToken) return@launch
                when (outcome) {
                    is TraceOutcome.Refused -> {
                        result = null
                        resultWire = null
                        difference = null
                        differenceRefusal = ""
                        failure = outcome.reason
                    }

                    is TraceOutcome.Done -> {
                        val traced = outcome.result
                        result = traced
                        difference = null
                        differenceRefusal = ""
                        params = traced.appliedParams
                        resultWire = traced.appliedParams.wire
                        memory.recordTrace(
                            sourceName = tracedName,
                            shapes = traced.shapeCount,
                            nodes = traced.nodeCount,
                            preview = traced.isPreview,
                        )
                        if (traced.stages.isNotEmpty()) weights = TraceProgressWeights.from(traced.stages)
                        if (kind == TraceRunKind.PREVIEW &&
                            traced.totalMillis > TRACE_AUTO_PREVIEW_BUDGET_MS
                        ) {
                            autoPreview = false
                            notice = "That took ${traceSeconds(traced.totalMillis)}, so auto-preview is paused. Tap “Update the preview” when ready."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (token != runToken) return@launch
                result = null
                resultWire = null
                failure = "Trace failed. Try again, or restart the app."
            } finally {
                running = null
                stopping = false
                progress = null
            }
        }
    }

    fun armPreview() {
        if (!autoPreview || !resumed) return
        if (running?.isFullResolution == true) return
        startRun(TraceRunKind.PREVIEW, TRACE_PREVIEW_DEBOUNCE_MS)
    }

    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, autoPreview, resumed) {
        if (loading || params == null || primed) return@LaunchedEffect
        if (!autoPreview || !resumed) return@LaunchedEffect
        primed = true
        startRun(TraceRunKind.PREVIEW, 0L)
    }

    fun forgetDerivations() {
        result = null
        resultWire = null
        difference = null
        differenceRefusal = ""
        frame = null
        memory.forgetTrace()
    }

    var derivedFrom by remember { mutableStateOf(photograph) }
    LaunchedEffect(photograph) {
        if (derivedFrom == photograph) return@LaunchedEffect
        derivedFrom = photograph
        runToken++
        job?.cancelAndJoin()
        job = null
        running = null
        stopping = false
        progress = null
        failure = null
        notice = null
        forgetDerivations()
        armPreview()
    }

    fun buildDifference() {
        if (differenceRunning || difference != null) return
        val traced = result ?: return
        val photographPlate = traced.photographPlate ?: return
        val tracePlate = traced.tracePlate ?: return
        differenceRunning = true
        differenceRefusal = ""
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                TracePlates.differencePlate(photographPlate, tracePlate)
            }
            if (result === traced) {
                difference = built
                differenceRefusal = if (built == null) TRACE_DIFFERENCE_REFUSAL else ""
            }
            differenceRunning = false
        }
    }

    fun patch(over: Map<String, TraceValue>) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val next = try {
                    runtime.withOverrides(base, over)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "Couldn't apply that setting. Try again."
                    return@withLock
                }
                params = next
                notice = null
            }
            armPreview()
        }
    }

    fun pickStyle(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = tracePresetName(presets?.styles.orEmpty(), id)
                val next = try {
                    runtime.applyStyle(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "Couldn't apply that style. Try again."
                    return@withLock
                }
                notice = traceOverwriteNotice("The “$name” style", base, next)
                params = next
                baseline = next
                styleId = next.styleId.ifBlank { id }
            }
            armPreview()
        }
    }

    fun pickSubject(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = tracePresetName(presets?.subjects.orEmpty(), id)
                val next = try {
                    runtime.applySubject(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "Couldn't apply that. Try again."
                    return@withLock
                }
                notice = traceOverwriteNotice("The “$name” adjustment", base, next)
                params = next
                subjectId = id
                subjectSeeded = false
            }
            armPreview()
        }
    }

    fun attach() {
        val traced = result ?: return
        if (traced.isPreview) return
        attaching = true
        scope.launch {
            val uri = traceAttachFile(context, traced.svg, sourceName)
            attaching = false
            if (uri == null) {
                onError(TRACE_ATTACH_WRITE_FAILED)
                return@launch
            }
            onAttach(uri)
            memory.recordAttachment(
                traceExportFileName(sourceName, "svg", TRACE_ATTACH_SUFFIX)
            )
            onMessage("Line art added.")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TracePanelDisclosureHeader(
            icon = Icons.Filled.Gesture,
            title = TRACE_CARD_TITLE,
            expanded = true,
            toggleEnabled = true,
            expandAction = TRACE_EXPAND_ACTION,
            collapseAction = TRACE_COLLAPSE_ACTION,
            onToggle = onClose,
        )

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Starting the tracing engine…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val current = params
        val tables = presets
        if (current == null || tables == null) {
            TracePanelNote(warning = true, text = failure ?: TRACE_ENGINE_SILENT_SENTENCE, polite = true)
            return@Column
        }

        TraceFramePanel(
            photograph = photograph,
            applied = frame,
            enabled = enabled && running == null,
            onApply = {
                frame = it
                armPreview()
            },
        )

        val traced = result
        val photographPlate = traced?.photographPlate
        val tracePlate = traced?.tracePlate
        val comparisonStatus = traceComparisonStatus(
            hasPlates = photographPlate != null && tracePlate != null,
            running = running != null,
            failed = failure != null,
            plateRefusal = traced?.plateRefusal.orEmpty(),
            hasResult = traced != null,
        )
        TracePanelLabel("The trace against the photograph")
        if (comparisonStatus.isNotEmpty()) {
            TracePanelNote(
                warning = traced?.plateRefusal?.isNotBlank() == true,
                text = comparisonStatus,
                polite = true,
            )
        }
        if (traced != null) {
            if (photographPlate != null && tracePlate != null) {
                TraceCompare(
                    photograph = photographPlate.asImageBitmap(),
                    trace = tracePlate.asImageBitmap(),
                    tracedWidth = traced.width,
                    tracedHeight = traced.height,
                    difference = difference?.asImageBitmap(),
                    differenceRefusal = differenceRefusal,
                    onDifferenceWanted = { buildDifference() },
                    enabled = enabled && running == null,
                )
            }
            TraceStatsRow(traced)
            if (traced.isPreview) {
                TracePanelNote(
                    warning = false,
                    text = "Preview at ${traced.workingWidth}×${traced.workingHeight}. Tap “Trace the sketch” for full size (${traced.width}×${traced.height}).",
                    polite = true,
                )
            }
            if (stale) {
                TracePanelNote(
                    warning = false,
                    text = "The settings have changed since this drawing was made.",
                    polite = true,
                )
            }
            if (traced.autoSubjectId.isNotBlank()) {
                TracePanelNote(
                    warning = false,
                    text = "Auto-applied “${tracePresetName(tables.subjects, traced.autoSubjectId)}”.",
                )
            }
            TraceNotes(traced.notes)
        }

        if (running != null) {
            TraceProgressRow(
                kind = running,
                progress = progress,
                weights = weights,
                stopping = stopping,
                enabled = enabled,
            ) {
                stopping = true
                job?.cancel()
            }
        }

        failure?.let { TracePanelNote(warning = true, text = it, polite = true) }
        notice?.let { TracePanelNote(warning = false, text = it, polite = true) }

        val missing = remember(current) { traceMissingKeys(current) }
        if (missing.isNotEmpty()) {
            TracePanelNote(
                warning = true,
                text = "${missing.size} of ${TRACE_PARAM_COUNT} settings aren't available on this phone.",
            )
        }

        TraceStylePicker(
            styles = tables.styles,
            selectedId = styleId,
            enabled = enabled && running == null,
            onPick = { pickStyle(it) },
        )
        TraceSubjectPicker(
            subjects = tables.subjects,
            selectedId = subjectId,
            enabled = enabled && running == null,
            seededFromHost = subjectSeeded,
            onPick = { pickSubject(it) },
        )
        if (subjectSeeded) {
            ActionRow {
                OutlinedButton(
                    onClick = { pickSubject(subjectId) },
                    enabled = enabled && running == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        "Adjust for ${tracePresetName(tables.subjects, subjectId).lowercase(Locale.ROOT)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        traced?.let {
            TraceStyleSuggestion(
                styles = tables.styles,
                suggestedStyleId = it.suggestedStyleId,
                currentStyleId = styleId,
                enabled = enabled && running == null,
                onApply = { picked -> pickStyle(picked) },
            )
        }

        val changedFromPreset = remember(baseline, current) {
            baseline?.let { traceChangedLabels(it, current) }.orEmpty().toSet()
        }
        val exportVisible = traced != null
        val visibleTiers = remember(advancedOpen, exportVisible) {
            setOfNotNull(
                TraceTier.PRIMARY,
                TraceTier.ADVANCED.takeIf { advancedOpen },
                TraceTier.EXPORT.takeIf { exportVisible },
            )
        }
        val hiddenChanged = remember(baseline, current, visibleTiers) {
            baseline?.let { traceChangedHiddenLabels(it, current, visibleTiers) }.orEmpty()
        }
        val changedBehindDisclosure = remember(baseline, current) {
            baseline?.let { traceChangedBehindDisclosure(it, current) }.orEmpty()
        }

        TRACE_CONTROLS.filter { it.tier == TraceTier.PRIMARY }.forEach { control ->
            TraceControlRow(
                control = control,
                values = current,
                availability = availability,
                changed = control.label in changedFromPreset,
                enabled = enabled && running == null,
                onPatch = { patch(it) },
            )
        }

        TraceAdvancedSection(
            values = current,
            availability = availability,
            open = advancedOpen,
            changedFromPreset = changedFromPreset,
            hiddenChanged = hiddenChanged,
            changedBehindDisclosure = changedBehindDisclosure,
            enabled = enabled,
            rowsEnabled = enabled && running == null,
            onToggle = { advancedOpen = !advancedOpen },
            onPatch = { patch(it) },
        )

        AppToggle(
            label = "Re-trace a preview as I change settings",
            description = if (resumed) {
                "Turn off to save battery."
            } else {
                "Paused while this screen is in the background."
            },
            checked = autoPreview,
            enabled = enabled,
            onCheckedChange = { autoPreview = it },
        )
        ActionRow {
            OutlinedButton(
                onClick = { startRun(TraceRunKind.PREVIEW, 0L) },
                enabled = enabled && running == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Update the preview", style = MaterialTheme.typography.labelLarge)
            }
        }

        val bar = traceCostRefusal(current, availability)
        bar?.let { TracePanelNote(warning = true, text = it) }
        if (availability.measuredOn == null) {
            Text(
                "Limits are estimates for this phone.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ActionRow {
            Button(
                onClick = { startRun(TraceRunKind.ATTACH, 0L) },
                enabled = enabled && running == null && bar == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trace the sketch", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = { attach() },
                enabled = enabled && running == null && !attaching &&
                    traced != null && !traced.isPreview && !stale,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add the line art", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep photo as is", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (traced != null) {
            TraceExportCard(
                traceSvg = traced.svg,
                geometry = traced.geometry,
                documentWidth = traced.width,
                documentHeight = traced.height,
                documentBackground = traceDocumentBackground(current),
                sourceName = sourceName,
                shapeCount = traced.shapeCount,
                nodeCount = traced.nodeCount,
                frameNote = traced.frameNote,
                isPreview = traced.isPreview,
                exporter = exporter,
                busy = running != null || attaching,
                onBusyChange = { attaching = it },
                onError = onError,
                onBackgroundChange = { white ->
                    val control = TRACE_TOGGLES.first { it.key == "output.background" }
                    patch(control.patch(white))
                },
                onNeedFullResolution = { startRun(TraceRunKind.ATTACH, 0L) },
            )
        }

        tracePanelReplaceWarning(currentFileName)?.let {
            TracePanelNote(warning = true, text = it)
        }
        Text(
            TRACE_ATTACH_SENTENCE,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        TracePanelCollapseButton(prominent = true, title = TRACE_CARD_TITLE, onClick = onClose)
    }
}

internal fun traceCostRefusal(
    values: TraceValues,
    availability: TraceAvailability,
): String? {
    val longEdge = values.number("preprocess.workingLongEdge")?.toInt() ?: return null
    if (longEdge > availability.maxWorkingLongEdge) {
        return "This phone handles up to ${availability.maxWorkingLongEdge} px. Choose a lower resolution."
    }
    if (values.choice("edge.engine") == "FDOG" && longEdge > availability.fdogMaxWorkingLongEdge) {
        return "The Flow edge engine at $longEdge px is too slow here (max ${availability.fdogMaxWorkingLongEdge} px). Lower the resolution or pick another engine under “$TRACE_DISCLOSURE_ACTION”."
    }
    return null
}

internal fun traceSeconds(millis: Long): String =
    if (millis < 1000L) {
        "$millis milliseconds"
    } else {
        String.format(Locale.ROOT, "%.1f seconds", millis / 1000.0)
    }

@Composable
private fun TraceProgressRow(
    kind: TraceRunKind?,
    progress: TraceProgress?,
    weights: TraceProgressWeights,
    stopping: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sentence = when {
            stopping -> "Stopping…"
            progress != null -> traceProgressSentence(progress, TRACE_ENGINE_STAGES)
            kind == TraceRunKind.PREVIEW -> "Tracing a preview…"
            else -> "Tracing…"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(sentence, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        }

        if (kind != null && kind.isFullResolution && progress != null) {
            val fraction = weights.fractionAt(progress.stageId, progress.fraction)
            FieldLinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            if (!weights.measured) {
                Text(
                    "Counts stages until the first trace finishes.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ActionRow {
            TextButton(
                onClick = onCancel,
                enabled = enabled && !stopping,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (stopping) "Stopping…" else "Stop", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TraceNotes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notes.forEach { note ->
            Text(note, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TraceStatsRow(result: TraceResult) {
    Text(
        "${result.shapeCount} paths · ${result.nodeCount} nodes · ${traceSeconds(result.totalMillis)}",
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun TraceAdvancedSection(
    values: TraceValues,
    availability: TraceAvailability,
    open: Boolean,
    changedFromPreset: Set<String>,
    hiddenChanged: List<String>,
    changedBehindDisclosure: List<String>,
    enabled: Boolean,
    rowsEnabled: Boolean,
    onToggle: () -> Unit,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    val groups = remember(values) { traceAdvancedGroups(values) }
    val revealed = remember(values) { traceAdvancedRevealed(values) }

    val stillness = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = if (stillness) 0 else TRACE_DISCLOSURE_TURN_MS),
        label = "trace-advanced-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (revealed == 0) {
            TracePanelLabel("The other settings")
            Text(
                "No other settings are available on this phone.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = traceDisclosureState(open)
                }
                .clickable(
                    enabled = enabled,
                    onClickLabel = traceDisclosureClickLabel(open, revealed),
                    role = Role.Button,
                ) { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = turn },
            )
            Text(
                traceDisclosureLabel(open, revealed, changedBehindDisclosure.size),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        if (!open) {
            Text(
                traceDisclosureBlurb(revealed),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            traceHiddenChangedSentence(hiddenChanged)?.let {
                TracePanelNote(warning = false, text = it, polite = true)
            }
            return@Column
        }

        groups.forEach { (group, rows) ->
            TracePanelLabel(group)
            rows.forEach { control ->
                TraceControlRow(
                    control = control,
                    values = values,
                    availability = availability,
                    changed = control.label in changedFromPreset,
                    enabled = rowsEnabled,
                    onPatch = onPatch,
                )
            }
        }
    }
}

@Composable
private fun TraceControlRow(
    control: TraceControl,
    values: TraceValues,
    availability: TraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    val inactive = traceInactiveReason(control, values)
    when (control) {
        is TraceSlider -> {
            val value = control.read(values) ?: return
            TraceSliderRow(control, value, changed, enabled, inactive, onPatch)
        }

        is TraceToggle -> {
            val on = control.read(values) ?: return
            TraceToggleRow(control, on, changed, enabled, inactive, onPatch)
        }

        is TraceChoice -> {
            val selected = control.read(values) ?: return
            TraceChoiceRow(control, selected, changed, enabled, inactive, onPatch)
        }

        is TraceNumberChoice -> {
            val value = control.read(values) ?: return
            TraceNumberChoiceRow(control, value, availability, changed, enabled, inactive, onPatch)
        }
    }
}

@Composable
private fun TraceSliderRow(
    control: TraceSlider,
    value: Double,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    var travel by remember(value) { mutableFloatStateOf(control.fractionOf(value)) }
    val live = control.valueAt(travel)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, traceFormatValue(live, control.step), changed)
        Slider(
            value = travel,
            onValueChange = { travel = it },
            onValueChangeFinished = { onPatch(control.patch(control.valueAt(travel))) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = "${control.label}, ${traceFormatValue(live, control.step)}"
                },
            enabled = enabled,
            valueRange = 0f..1f,
        )
        TraceRowTail(control, inactive)
    }
}

@Composable
private fun TraceToggleRow(
    control: TraceToggle,
    on: Boolean,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppToggle(
            label = if (changed) "${control.label} · changed" else control.label,
            checked = on,
            enabled = enabled,
            onCheckedChange = { onPatch(control.patch(it)) },
        )
        TraceRowTail(control, inactive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceChoiceRow(
    control: TraceChoice,
    selected: String,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                TracePanelChip(
                    label = option.label,
                    selected = option.value == selected,
                    enabled = enabled,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        TraceRowTail(control, inactive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceNumberChoiceRow(
    control: TraceNumberChoice,
    value: Double,
    availability: TraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                val reachable = option.value <= availability.maxWorkingLongEdge.toDouble()
                TracePanelChip(
                    label = option.label,
                    selected = abs(option.value - value) < 0.5,
                    enabled = enabled && reachable,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        control.options.firstOrNull { abs(it.value - value) < 0.5 }?.let {
            Text(it.note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        TraceRowTail(control, inactive)
    }
}

@Composable
private fun TraceRowHeading(label: String, readout: String?, changed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (changed) "$label · changed" else label,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
        )
        readout?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TraceRowTail(control: TraceControl, inactive: String?) {
    Text(control.hint, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    control.handsetNote?.let {
        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
    }
    inactive?.let {
        Text(
            "Not used by the current settings. $it",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TraceEmptyState(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TracePanelDisclosureHeader(
            icon = Icons.Filled.Gesture,
            title = TRACE_CARD_TITLE,
            expanded = true,
            toggleEnabled = enabled,
            expandAction = TRACE_EXPAND_ACTION,
            collapseAction = TRACE_COLLAPSE_ACTION,
            onToggle = onClose,
        )
        Text(
            "Add a photo above to turn its pencil drawing into vector line art.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
