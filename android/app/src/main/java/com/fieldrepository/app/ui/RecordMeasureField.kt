package com.fieldrepository.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.fieldrepository.app.data.KnownRectangle
import com.fieldrepository.app.data.MeasurePoint
import com.fieldrepository.app.data.MeasureResult
import com.fieldrepository.app.data.MeasureSegment
import com.fieldrepository.app.data.PhotoMeasure
import com.fieldrepository.app.data.RoundedValue
import com.fieldrepository.app.data.ScaleReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Immutable
data class RecordDimension(
    val column: String,
    val label: String,
    val unit: String = "in",
)

val PRODUCT_MEASURE_DIMENSIONS: List<RecordDimension> = listOf(
    RecordDimension("lengthInches", "Length (inches)"),
    RecordDimension("breadthInches", "Breadth (inches)"),
    RecordDimension("heightInches", "Height (inches)"),
)

val TOOL_MEASURE_DIMENSIONS: List<RecordDimension> = listOf(
    RecordDimension("lengthInches", "Length (inches)"),
    RecordDimension("breadthInches", "Breadth (inches)"),
    RecordDimension("heightInches", "Height (inches)"),
)

internal fun measurableDimensions(dimensions: List<RecordDimension>): List<RecordDimension> =
    dimensions.filter { it.unit in PhotoMeasure.LENGTH_UNITS }

internal const val RECORD_DECIMALS: Int = 2

private fun clampedToColumn(offered: String): BigDecimal? =
    offered.toBigDecimalOrNull()
        ?.takeIf { it.scale() > RECORD_DECIMALS }
        ?.setScale(RECORD_DECIMALS, RoundingMode.HALF_UP)

internal fun recordProposalText(offered: String): String {
    val clamped = clampedToColumn(offered) ?: return offered
    return if (clamped.signum() == 0) "" else clamped.toPlainString()
}

internal fun recordProposalNote(dimension: RecordDimension, offered: String): String? {
    val clamped = clampedToColumn(offered) ?: return null
    if (clamped.signum() == 0) {
        return "About $offered ${dimension.unit} rounds to zero in “${dimension.label}”, so it was left empty."
    }
    return "“${dimension.label}” rounded $offered ${dimension.unit} to ${clamped.toPlainString()}."
}

internal fun panelReplaceWarning(current: String?): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return "“$trimmed” is in this field now. This replaces it."
}

internal fun recordPhotoLabel(index: Int, total: Int): String =
    if (total > 1) "Photo ${index + 1} of $total" else "Photo ${index + 1}"

internal fun formatRounded(rounded: RoundedValue): String =
    String.format(Locale.ROOT, "%.${rounded.decimals}f", rounded.value)

internal fun measureSummary(
    marksPlaced: Int,
    marksNeeded: Int,
    fourCorner: Boolean,
    referenceLength: String,
    referenceUnit: String,
    rectWidth: String,
    rectHeight: String,
    rectUnit: String,
): String? {
    val size = if (fourCorner) {
        val width = rectWidth.trim()
        val height = rectHeight.trim()
        if (width.isEmpty() || height.isEmpty()) null else "$width × $height $rectUnit rectangle"
    } else {
        val length = referenceLength.trim()
        if (length.isEmpty()) null else "$length $referenceUnit reference"
    }
    if (marksPlaced <= 0 && size == null) return null

    val parts = mutableListOf<String>()
    parts += if (marksPlaced <= 0) "no marks placed yet" else "$marksPlaced of $marksNeeded marks placed"
    parts += size ?: if (fourCorner) "no rectangle size yet" else "no reference length yet"
    parts += if (fourCorner) "four-corner method" else "same-plane method"
    return parts.joinToString(" · ")
}

internal val RECORD_UNITS: Set<String> get() = PhotoMeasure.LENGTH_UNITS.keys

private enum class MeasureMode { SCALE, RECTIFY }

private enum class MarkId { REF_A, REF_B, C0, C1, C2, C3, TGT_A, TGT_B }

private data class Mark(
    val fx: Double,
    val fy: Double,
    val zoomWhenPlaced: Float,
    val placed: Boolean,
)

private val SCALE_MARKS = listOf(MarkId.REF_A, MarkId.REF_B, MarkId.TGT_A, MarkId.TGT_B)
private val RECTIFY_MARKS =
    listOf(MarkId.C0, MarkId.C1, MarkId.C2, MarkId.C3, MarkId.TGT_A, MarkId.TGT_B)

private val MARK_BADGE: Map<MarkId, String> = mapOf(
    MarkId.REF_A to "R1",
    MarkId.REF_B to "R2",
    MarkId.C0 to "1",
    MarkId.C1 to "2",
    MarkId.C2 to "3",
    MarkId.C3 to "4",
    MarkId.TGT_A to "A",
    MarkId.TGT_B to "B",
)

private val MARK_NAME: Map<MarkId, String> = mapOf(
    MarkId.REF_A to "Reference, first end",
    MarkId.REF_B to "Reference, second end",
    MarkId.C0 to "Rectangle corner 1",
    MarkId.C1 to "Rectangle corner 2, along the width edge from corner 1",
    MarkId.C2 to "Rectangle corner 3, diagonally opposite corner 1",
    MarkId.C3 to "Rectangle corner 4",
    MarkId.TGT_A to "The dimension, first end",
    MarkId.TGT_B to "The dimension, second end",
)

private val SEEDS: Map<MarkId, Pair<Double, Double>> = mapOf(
    MarkId.REF_A to (0.14 to 0.84),
    MarkId.REF_B to (0.52 to 0.84),
    MarkId.C0 to (0.20 to 0.20),
    MarkId.C1 to (0.80 to 0.22),
    MarkId.C2 to (0.82 to 0.78),
    MarkId.C3 to (0.18 to 0.76),
    MarkId.TGT_A to (0.30 to 0.42),
    MarkId.TGT_B to (0.72 to 0.44),
)

private data class ScalePreset(val label: String, val length: Double, val unit: String)
private data class RectPreset(val label: String, val width: Double, val height: Double, val unit: String)

private val SCALE_PRESETS = listOf(
    ScalePreset("Grid, 5 squares = 5 in", 5.0, "in"),
    ScalePreset("Scale card, 100 mm", 100.0, "mm"),
    ScalePreset("Steel rule, 300 mm", 300.0, "mm"),
    ScalePreset("A4 short edge, 210 mm", 210.0, "mm"),
    ScalePreset("₹5 coin, 23 mm", 23.0, "mm"),
)

private val RECT_PRESETS = listOf(
    RectPreset("A4 sheet", 210.0, 297.0, "mm"),
    RectPreset("A5 sheet", 148.0, 210.0, "mm"),
    RectPreset("Bank/ID card", 85.6, 54.0, "mm"),
)

private const val MIN_ZOOM = 1f

private const val MAX_ZOOM = 8f

private const val WORKING_COPY_MAX_PX = 2048

private const val MIN_VIEWPORT_RATIO = 1f
private const val MAX_VIEWPORT_RATIO = 2f

private val MARK_TOUCH = 44.dp
private val MARK_DISC = 26.dp

private fun firstMark(mode: MeasureMode): MarkId =
    if (mode == MeasureMode.SCALE) MarkId.REF_A else MarkId.C0

private class MeasureConfig {
    var mode by mutableStateOf(MeasureMode.SCALE)
    var photoIndex by mutableStateOf(0)
    var referenceLength by mutableStateOf("")
    var referenceUnit by mutableStateOf("in")
    var rectWidth by mutableStateOf("")
    var rectHeight by mutableStateOf("")
    var rectUnit by mutableStateOf("mm")
    var selected by mutableStateOf(firstMark(MeasureMode.SCALE))

    var columnNote by mutableStateOf<String?>(null)

    val marks = mutableStateMapOf<MarkId, Mark>()

    val needed: List<MarkId> get() = if (mode == MeasureMode.SCALE) SCALE_MARKS else RECTIFY_MARKS

    val placedCount: Int get() = needed.count { marks[it]?.placed == true }

    val allPlaced: Boolean get() = needed.all { marks[it]?.placed == true }

    fun seed() {
        SEEDS.forEach { (id, seed) ->
            marks[id] = Mark(fx = seed.first, fy = seed.second, zoomWhenPlaced = 1f, placed = false)
        }
    }

    fun place(id: MarkId, fx: Double, fy: Double, zoom: Float) {
        marks[id] = Mark(fx = fx.coerceIn(0.0, 1.0), fy = fy.coerceIn(0.0, 1.0), zoomWhenPlaced = zoom, placed = true)
    }
}

@Composable
internal fun RecordMeasureField(
    dimensions: List<RecordDimension>,
    current: Map<String, String>,
    photos: List<Uri>,
    enabled: Boolean = true,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
) {
    val targets = remember(dimensions) { measurableDimensions(dimensions) }
    if (targets.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val config = remember { MeasureConfig().also { it.seed() } }

    var picked by remember { mutableStateOf<Uri?>(null) }
    val sources = remember(photos, picked) { (photos + listOfNotNull(picked)).distinct() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            picked = uri
            config.photoIndex = (photos + uri).distinct().indexOf(uri)
            expanded = true
        }
    }
    val index = if (sources.isEmpty()) 0 else config.photoIndex.coerceIn(0, sources.size - 1)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Measure a dimension from a photograph",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Mark the object against something of known size in the same photo.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = { if (sources.isEmpty()) pickImage.launch("image/*") else expanded = !expanded },
                enabled = enabled,
            ) {
                Text(if (sources.isEmpty()) "Choose a photo" else if (expanded) "Close" else "Open")
            }
        }
        if (expanded && sources.isNotEmpty()) {
            MeasurePanelOpen(
                config = config,
                photo = sources[index],
                photoCount = sources.size,
                photoIndex = index,
                targets = targets,
                current = current,
                enabled = enabled,
                onPropose = onPropose,
                onClose = { expanded = false },
            )
        } else {
            measureSummary(
                marksPlaced = config.placedCount,
                marksNeeded = config.needed.size,
                fourCorner = config.mode == MeasureMode.RECTIFY,
                referenceLength = config.referenceLength,
                referenceUnit = config.referenceUnit,
                rectWidth = config.rectWidth,
                rectHeight = config.rectHeight,
                rectUnit = config.rectUnit,
            )?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MeasurePanelOpen(
    config: MeasureConfig,
    photo: Uri,
    photoCount: Int,
    photoIndex: Int,
    targets: List<RecordDimension>,
    current: Map<String, String>,
    enabled: Boolean,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = remember(photo) {
            ImageRequest.Builder(context)
                .data(photo)
                .size(WORKING_COPY_MAX_PX, WORKING_COPY_MAX_PX)
                .build()
        },
    )
    val state by painter.state.collectAsState()
    val intrinsic = painter.intrinsicSize
    val imgW = if (intrinsic.isSpecified) intrinsic.width else 0f
    val imgH = if (intrinsic.isSpecified) intrinsic.height else 0f

    var zoom by remember(photo) { mutableStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val viewW = viewport.width.toFloat()
    val viewH = viewport.height.toFloat()
    val fit = if (imgW > 0f && imgH > 0f && viewW > 0f && viewH > 0f) {
        min(viewW / imgW, viewH / imgH)
    } else {
        0f
    }
    val drawnW = imgW * fit
    val drawnH = imgH * fit
    val ratio = if (imgW > 0f && imgH > 0f) {
        (imgW / imgH).coerceIn(MIN_VIEWPORT_RATIO, MAX_VIEWPORT_RATIO)
    } else {
        4f / 3f
    }

    fun screenOf(mark: Mark): Offset = Offset(
        x = viewW / 2f + ((mark.fx.toFloat() * imgW - imgW / 2f) * fit) * zoom + pan.x,
        y = viewH / 2f + ((mark.fy.toFloat() * imgH - imgH / 2f) * fit) * zoom + pan.y,
    )

    fun fractionOf(point: Offset): Pair<Double, Double> {
        if (fit <= 0f) return 0.5 to 0.5
        val ix = ((point.x - viewW / 2f - pan.x) / zoom) / fit + imgW / 2f
        val iy = ((point.y - viewH / 2f - pan.y) / zoom) / fit + imgH / 2f
        return (ix / imgW).toDouble() to (iy / imgH).toDouble()
    }

    fun clampPan(offered: Offset): Offset {
        val slackX = max(0f, (drawnW * zoom - viewW) / 2f)
        val slackY = max(0f, (drawnH * zoom - viewH) / 2f)
        return Offset(offered.x.coerceIn(-slackX, slackX), offered.y.coerceIn(-slackY, slackY))
    }

    val result = measureNow(config = config, imgW = imgW, imgH = imgH, fit = fit)
    val scrollPhotos = rememberScrollState()
    val scrollPresets = rememberScrollState()
    val scrollMarks = rememberScrollState()

    if (photoCount > 1) {
        PanelLabel("Which photograph")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPhotos),
        ) {
            repeat(photoCount) { position ->
                FilterChip(
                    selected = position == photoIndex,
                    onClick = {
                        if (position != photoIndex) {
                            config.photoIndex = position
                            config.seed()
                            config.selected = firstMark(config.mode)
                            config.columnNote = null
                        }
                    },
                    label = { Text(recordPhotoLabel(position, photoCount), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }

    PanelLabel("Method")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = config.mode == MeasureMode.SCALE,
            onClick = { config.mode = MeasureMode.SCALE; config.selected = firstMark(MeasureMode.SCALE) },
            label = { Text("Same plane, two marks", style = MaterialTheme.typography.labelMedium) },
        )
        FilterChip(
            selected = config.mode == MeasureMode.RECTIFY,
            onClick = { config.mode = MeasureMode.RECTIFY; config.selected = firstMark(MeasureMode.RECTIFY) },
            label = { Text("Four corners", style = MaterialTheme.typography.labelMedium) },
        )
    }
    PanelNote(
        warning = config.mode == MeasureMode.SCALE,
        text = if (config.mode == MeasureMode.SCALE) {
            "Only accurate if the reference and object are in the same flat plane, facing the camera. If unsure, use four corners."
        } else {
            "Mark the four corners of something of known size (A4, card, grid sheet), in order around it. Both ends of the object must sit on it."
        },
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(min = 96.dp)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(enabled, fit, viewport) {
                if (!enabled || fit <= 0f) return@pointerInput
                detectTapGestures { offset ->
                    val (fx, fy) = fractionOf(offset)
                    config.place(config.selected, fx, fy, zoom)
                    config.selected =
                        config.needed.firstOrNull { config.marks[it]?.placed != true } ?: config.selected
                }
            }
            .pointerInput(enabled, fit, viewport) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    pan = clampPan(pan + drag)
                }
            },
    ) {
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = "The photograph being measured",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            )
            config.needed.forEach { id ->
                val mark = config.marks[id] ?: return@forEach
                val at = screenOf(mark)
                MarkHandle(
                    badge = MARK_BADGE.getValue(id),
                    name = MARK_NAME.getValue(id),
                    placed = mark.placed,
                    selected = id == config.selected,
                    outlined = id == MarkId.TGT_A || id == MarkId.TGT_B,
                    onClick = { config.selected = id },
                    modifier = Modifier
                        .offset {
                            val half = MARK_TOUCH.toPx() / 2f
                            IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt())
                        }
                        .pointerInput(id, enabled, fit, zoom) {
                            if (!enabled || fit <= 0f || imgW <= 0f || imgH <= 0f) return@pointerInput
                            detectDragGestures(onDragStart = { config.selected = id }) { change, drag ->
                                change.consume()
                                val held = config.marks[id] ?: return@detectDragGestures
                                config.place(
                                    id,
                                    held.fx + drag.x / (fit * zoom) / imgW,
                                    held.fy + drag.y / (fit * zoom) / imgH,
                                    zoom,
                                )
                            }
                        },
                )
            }
        } else {
            Text(
                when (state) {
                    is AsyncImagePainter.State.Error ->
                        "Can't open this photo. Pick another."
                    else -> "Opening the photograph…"
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        ) {
            ZoomButton(zoomIn = false, enabled = enabled && zoom > MIN_ZOOM) {
                zoom = max(MIN_ZOOM, zoom / 1.5f)
                pan = clampPan(pan)
            }
            ZoomButton(zoomIn = true, enabled = enabled && zoom < MAX_ZOOM) {
                zoom = min(MAX_ZOOM, zoom * 1.5f)
                pan = clampPan(pan)
            }
        }
    }

    Text(
        "Zoom ${String.format(Locale.ROOT, "%.1f", zoom)}× — zoom in for more precise marks.",
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
    )

    PanelLabel("Tap the photograph to place")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollMarks),
    ) {
        config.needed.forEach { id ->
            val placed = config.marks[id]?.placed == true
            FilterChip(
                selected = id == config.selected,
                onClick = { config.selected = id },
                label = { Text(MARK_BADGE.getValue(id) + if (placed) "" else " ·", style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
    Text(MARK_NAME.getValue(config.selected), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Nudge ${MARK_BADGE.getValue(config.selected)}",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        NudgeButton(Icons.Filled.ChevronLeft, "left", enabled) { nudge(config, -1.0, 0.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.ChevronRight, "right", enabled) { nudge(config, 1.0, 0.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.KeyboardArrowUp, "up", enabled) { nudge(config, 0.0, -1.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.KeyboardArrowDown, "down", enabled) { nudge(config, 0.0, 1.0, imgW, imgH, zoom) }
    }

    if (config.mode == MeasureMode.SCALE) {
        PanelLabel("Reference length")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPresets),
        ) {
            SCALE_PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = { config.referenceLength = trimNumber(preset.length); config.referenceUnit = preset.unit },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(preset.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
        OutlinedTextField(
            value = config.referenceLength,
            onValueChange = { config.referenceLength = it },
            label = { Text("Reference length") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        UnitChips(selected = config.referenceUnit, enabled = enabled) { config.referenceUnit = it }
    } else {
        PanelLabel("Rectangle size")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPresets),
        ) {
            RECT_PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = {
                        config.rectWidth = trimNumber(preset.width)
                        config.rectHeight = trimNumber(preset.height)
                        config.rectUnit = preset.unit
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(preset.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = config.rectWidth,
                onValueChange = { config.rectWidth = it },
                label = { Text("Width, 1→2") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.rectHeight,
                onValueChange = { config.rectHeight = it },
                label = { Text("Height, 2→3") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        UnitChips(selected = config.rectUnit, enabled = enabled) { config.rectUnit = it }
    }

    MeasurementReadout(
        result = result,
        config = config,
        targets = targets,
        current = current,
        enabled = enabled,
        onPropose = { dimension, text, offered, technique ->
            config.columnNote = recordProposalNote(dimension, offered)
            if (text.isNotBlank()) onPropose(dimension.column, text, technique)
        },
    )
    config.columnNote?.let { note ->
        Text(note, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onClose) { Text("Close") }
}

private fun measureNow(config: MeasureConfig, imgW: Float, imgH: Float, fit: Float): MeasureResult? {
    if (imgW <= 0f || imgH <= 0f || fit <= 0f) return null
    if (!config.allPlaced) return null

    fun pointOf(id: MarkId): MeasurePoint {
        val mark = config.marks.getValue(id)
        return MeasurePoint(mark.fx * imgW, mark.fy * imgH)
    }

    val sigma = config.needed.maxOf { id ->
        PhotoMeasure.markSigmaForDisplayScale((fit * config.marks.getValue(id).zoomWhenPlaced).toDouble())
    }
    val target = MeasureSegment(pointOf(MarkId.TGT_A), pointOf(MarkId.TGT_B))

    return if (config.mode == MeasureMode.SCALE) {
        val length = config.referenceLength.trim().toDoubleOrNull() ?: return null
        PhotoMeasure.measureBySameScale(
            reference = ScaleReference(
                from = pointOf(MarkId.REF_A),
                to = pointOf(MarkId.REF_B),
                length = length,
                unit = config.referenceUnit,
            ),
            target = target,
            markSigmaPx = sigma,
        )
    } else {
        val width = config.rectWidth.trim().toDoubleOrNull() ?: return null
        val height = config.rectHeight.trim().toDoubleOrNull() ?: return null
        PhotoMeasure.measureByRectification(
            corners = listOf(MarkId.C0, MarkId.C1, MarkId.C2, MarkId.C3).map { pointOf(it) },
            rectangle = KnownRectangle(width = width, height = height, unit = config.rectUnit),
            target = target,
            markSigmaPx = sigma,
        )
    }
}

private fun nudge(config: MeasureConfig, dx: Double, dy: Double, imgW: Float, imgH: Float, zoom: Float) {
    if (imgW <= 0f || imgH <= 0f) return
    val id = config.selected
    val mark = config.marks[id] ?: return
    config.place(id, mark.fx + dx / imgW, mark.fy + dy / imgH, zoom)
}

@Composable
private fun MeasurementReadout(
    result: MeasureResult?,
    config: MeasureConfig,
    targets: List<RecordDimension>,
    current: Map<String, String>,
    enabled: Boolean,
    onPropose: (RecordDimension, String, String, String?) -> Unit,
) {
    if (!config.allPlaced) {
        val remaining = config.needed.filter { config.marks[it]?.placed != true }
        Text(
            "${remaining.size} of ${config.needed.size} marks left to place (${remaining.joinToString(", ") { MARK_BADGE.getValue(it) }}).",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    if (result == null) {
        Text(
            if (config.mode == MeasureMode.SCALE) {
                "Enter the reference length."
            } else {
                "Enter the rectangle size."
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    if (result is MeasureResult.Refusal) {
        PanelNote(warning = true, text = result.reason)
        return
    }

    val measurement = result as MeasureResult.Measurement
    val shown = PhotoMeasure.roundToUncertainty(measurement.value, measurement.uncertainty)
    val doubt = PhotoMeasure.roundToUncertainty(measurement.uncertainty, measurement.uncertainty)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Measured — not saved yet",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${formatRounded(shown)} ± ${formatRounded(doubt)} ${measurement.unit}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "±${String.format(Locale.ROOT, "%.1f", measurement.relativeUncertainty * 100)}% (reference ${measurement.referencePixels.roundToInt()} px, object ${measurement.targetPixels.roundToInt()} px).",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        measurement.tiltCorrection?.let { tilt ->
            Text(
                buildString {
                    append("Correcting for the tilt of that surface changed this by ")
                    append(String.format(Locale.ROOT, "%.1f", tilt * 100))
                    append("%")
                    measurement.uncorrectedValue?.let { uncorrected ->
                        append(" — two marks alone would have read ")
                        append(String.format(Locale.ROOT, "%.1f", uncorrected))
                        append(" ")
                        append(measurement.unit)
                    }
                    append(". ")
                    append(
                        if (tilt < 0.01) {
                            "Two marks would have been enough."
                        } else {
                            "Four corners made the difference."
                        }
                    )
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        PanelLabel("Propose this into")
        targets.forEach { dimension ->
            val converted = PhotoMeasure.convertLength(measurement.value, measurement.unit, dimension.unit)
            val convertedDoubt =
                PhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, dimension.unit)
            if (converted == null || convertedDoubt == null) {
                Text(
                    "Can't convert to ${dimension.unit}.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                return@forEach
            }
            val offered = formatRounded(PhotoMeasure.roundToUncertainty(converted, convertedDoubt))
            val text = recordProposalText(offered)
            Button(
                onClick = { onPropose(dimension, text, offered, measurement.method) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${dimension.label}: $offered ${dimension.unit}", style = MaterialTheme.typography.labelLarge)
            }
            panelReplaceWarning(current[dimension.column])?.let {
                Text(it, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            "Rounded to its measured precision.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MarkHandle(
    badge: String,
    name: String,
    placed: Boolean,
    selected: Boolean,
    outlined: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(MARK_TOUCH)
            .semantics {
                contentDescription = if (placed) name else "$name, not placed yet"
            }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MARK_DISC)
                .background(
                    color = if (outlined) Color.White.copy(alpha = 0.85f) else accent,
                    shape = CircleShape,
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                color = if (outlined) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
}

@Composable
private fun PanelNote(warning: Boolean, text: String) {
    Text(
        text,
        color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.field.surface50,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    )
}

@Composable
private fun UnitChips(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        PhotoMeasure.LENGTH_UNITS.keys.forEach { unit ->
            FilterChip(
                selected = unit == selected,
                onClick = { onSelect(unit) },
                enabled = enabled,
                label = { Text(unit, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun ZoomButton(zoomIn: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            if (zoomIn) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = if (zoomIn) "Zoom in" else "Zoom out",
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NudgeButton(icon: ImageVector, direction: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(icon, contentDescription = "Nudge the selected mark $direction", modifier = Modifier.size(18.dp))
    }
}

private fun trimNumber(value: Double): String =
    if (abs(value % 1.0) < 1e-9) value.toLong().toString() else value.toString()
