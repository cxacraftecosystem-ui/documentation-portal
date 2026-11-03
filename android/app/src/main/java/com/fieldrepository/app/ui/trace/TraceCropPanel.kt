package com.fieldrepository.app.ui.trace

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlin.math.roundToInt

const val TRACE_CROP_PREVIEW_EDGE_PX: Int = 1024

@Composable
internal fun TraceFramePanel(
    photograph: Uri,
    applied: TraceFrameChoice?,
    enabled: Boolean,
    onApply: (TraceFrameChoice?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Crop,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "The part of the photograph to trace",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (applied == null) {
                        "The whole photograph."
                    } else {
                        "${applied.rect.width}×${applied.rect.height} of " +
                            "${applied.frameWidth}×${applied.frameHeight}."
                    },
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = { open = !open },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (open) "Done" else "Choose a frame", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (open) {
            TraceFrameChooser(
                photograph = photograph,
                applied = applied,
                enabled = enabled,
                onApply = onApply,
            )
        }
    }
}

@Composable
private fun TraceFrameChooser(
    photograph: Uri,
    applied: TraceFrameChoice?,
    enabled: Boolean,
    onApply: (TraceFrameChoice?) -> Unit,
) {
    val context = LocalContext.current
    val key = photograph.toString()
    var image by remember(key) { mutableStateOf<TraceDisplayImage?>(null) }
    var decoding by remember(key) { mutableStateOf(true) }

    LaunchedEffect(key) {
        decoding = true
        image = traceDecodeForDisplay(context, photograph, TRACE_CROP_PREVIEW_EDGE_PX)
        decoding = false
    }

    val decoded = image
    when {
        decoded != null -> TraceFrameEditor(
            decoded = decoded,
            sourceKey = key,
            applied = applied,
            enabled = enabled,
            onApply = onApply,
        )

        decoding -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Opening the photograph…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }

        else -> TracePanelNote(
            warning = true,
            text = "Can't show this photo here, so the whole photo will be traced.",
            polite = true,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceFrameEditor(
    decoded: TraceDisplayImage,
    sourceKey: String,
    applied: TraceFrameChoice?,
    enabled: Boolean,
    onApply: (TraceFrameChoice?) -> Unit,
) {
    var rect by remember(sourceKey) { mutableStateOf<TraceCropRect?>(null) }
    var clampNote by remember(sourceKey) { mutableStateOf("") }
    var draft by remember(sourceKey) { mutableStateOf<Pair<String, String>?>(null) }

    val (frameWidth, frameHeight) = remember(decoded) {
        traceWorkingSize(decoded.sourceWidth, decoded.sourceHeight)
    }
    val preview: ImageBitmap = remember(decoded) { decoded.bitmap.asImageBitmap() }

    val box = rect ?: traceWholeFrame(frameWidth, frameHeight)
    fun setBox(next: TraceCropRect) {
        rect = traceClampCrop(next, frameWidth, frameHeight)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        TraceCropOverlay(
            preview = preview,
            box = box,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            enabled = enabled,
            onBox = { setBox(it) },
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TRACE_CROP_FIELDS.forEach { field ->
                TraceCropNumberField(
                    label = field,
                    value = when (field) {
                        "Left" -> box.x
                        "Top" -> box.y
                        "Width" -> box.width
                        else -> box.height
                    },
                    draft = draft?.takeIf { it.first == field }?.second,
                    enabled = enabled,
                    onType = { draft = field to it },
                    onCommit = { text ->
                        draft = null
                        val typed = text.trim().toIntOrNull()
                        if (typed == null) {
                            clampNote = ""
                        } else {
                            val candidate = when (field) {
                                "Left" -> box.copy(x = typed)
                                "Top" -> box.copy(y = typed)
                                "Width" -> box.copy(width = typed)
                                else -> box.copy(height = typed)
                            }
                            val next = traceClampCrop(candidate, frameWidth, frameHeight)
                            setBox(next)
                            val kept = when (field) {
                                "Left" -> next.x
                                "Top" -> next.y
                                "Width" -> next.width
                                else -> next.height
                            }
                            clampNote = if (kept == typed) {
                                ""
                            } else {
                                traceCropClampNote(field, typed, next, frameWidth, frameHeight)
                            }
                        }
                    },
                )
            }
        }

        if (clampNote.isNotEmpty()) {
            TracePanelNote(warning = false, text = clampNote, polite = true)
        }

        Text(
            "${traceCropReadout(box, frameWidth, frameHeight)} $TRACE_CROP_ENGINE_NOTE",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )

        ActionRow {
            OutlinedButton(
                onClick = {
                    onApply(
                        if (traceIsWholeFrame(box, frameWidth, frameHeight)) {
                            null
                        } else {
                            TraceFrameChoice(box, frameWidth, frameHeight)
                        },
                    )
                },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use this frame", style = MaterialTheme.typography.labelMedium)
            }
            if (applied != null || !traceIsWholeFrame(box, frameWidth, frameHeight)) {
                OutlinedButton(
                    onClick = {
                        setBox(traceWholeFrame(frameWidth, frameHeight))
                        clampNote = ""
                        onApply(null)
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Use whole photo", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        val onScreen = if (traceIsWholeFrame(box, frameWidth, frameHeight)) null else box
        if (applied?.rect != onScreen) {
            TracePanelNote(
                warning = false,
                text = if (applied == null) {
                    "Tracing the whole photo."
                } else {
                    traceCropStaleNote(applied.rect)
                },
                polite = true,
            )
        }
    }
}

private val TRACE_CROP_FIELDS = listOf("Left", "Top", "Width", "Height")

@Composable
private fun TraceCropOverlay(
    preview: ImageBitmap,
    box: TraceCropRect,
    frameWidth: Int,
    frameHeight: Int,
    enabled: Boolean,
    onBox: (TraceCropRect) -> Unit,
) {
    var viewWidth by remember { mutableFloatStateOf(0f) }
    var viewHeight by remember { mutableFloatStateOf(0f) }

    val latestBox by rememberUpdatedState(box)

    val aspect = if (preview.height > 0) preview.width.toFloat() / preview.height.toFloat() else 1f
    val toFrameX = if (viewWidth > 0f) frameWidth / viewWidth else 0f
    val toFrameY = if (viewHeight > 0f) frameHeight / viewHeight else 0f
    val toViewX = if (frameWidth > 0) viewWidth / frameWidth else 0f
    val toViewY = if (frameHeight > 0) viewHeight / frameHeight else 0f

    val density = LocalDensity.current
    val boxWidthDp = with(density) { (box.width * toViewX).toDp() }
    val boxHeightDp = with(density) { (box.height * toViewY).toDp() }
    val half = with(density) { 22.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(MaterialTheme.field.surface200, RoundedCornerShape(8.dp))
            .onSizeChanged {
                viewWidth = it.width.toFloat()
                viewHeight = it.height.toFloat()
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            drawImage(
                image = preview,
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            val left = box.x * toViewX
            val top = box.y * toViewY
            val right = (box.x + box.width) * toViewX
            val bottom = (box.y + box.height) * toViewY
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset(0f, 0f), Size(size.width, top))
            drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
            drawRect(shade, Offset(0f, top), Size(left, bottom - top))
            drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = Offset(left - 1f, top - 1f),
                size = Size((right - left) + 2f, (bottom - top) + 2f),
                style = Stroke(width = 3f),
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 1.5f),
            )
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset((box.x * toViewX).roundToInt(), (box.y * toViewY).roundToInt())
                }
                .size(width = boxWidthDp, height = boxHeightDp)
                .pointerInput(enabled, frameWidth, frameHeight, toFrameX, toFrameY) {
                    if (!enabled) return@pointerInput
                    var start = latestBox
                    var totalX = 0f
                    var totalY = 0f
                    detectDragGestures(
                        onDragStart = {
                            start = latestBox
                            totalX = 0f
                            totalY = 0f
                        },
                    ) { change, drag ->
                        change.consume()
                        totalX += drag.x * toFrameX
                        totalY += drag.y * toFrameY
                        onBox(
                            traceMoveCrop(start, totalX.toInt(), totalY.toInt(), frameWidth, frameHeight)
                        )
                    }
                }
                .semantics {
                    contentDescription = "The frame that will be traced. Drag it to move it; the " +
                        "four boxes below set it exactly."
                },
        )

        TraceCropCorner.entries.forEach { corner ->
            val atRight = corner == TraceCropCorner.TOP_RIGHT || corner == TraceCropCorner.BOTTOM_RIGHT
            val atBottom = corner == TraceCropCorner.BOTTOM_LEFT || corner == TraceCropCorner.BOTTOM_RIGHT
            val cx = (if (atRight) box.x + box.width else box.x) * toViewX
            val cy = (if (atBottom) box.y + box.height else box.y) * toViewY
            Box(
                modifier = Modifier
                    .offset {
                        val maxX = (viewWidth - half * 2f).coerceAtLeast(0f)
                        val maxY = (viewHeight - half * 2f).coerceAtLeast(0f)
                        IntOffset(
                            (cx - half).coerceIn(0f, maxX).roundToInt(),
                            (cy - half).coerceIn(0f, maxY).roundToInt(),
                        )
                    }
                    .size(44.dp)
                    .pointerInput(enabled, frameWidth, frameHeight, toFrameX, toFrameY) {
                        if (!enabled) return@pointerInput
                        var start = latestBox
                        var totalX = 0f
                        var totalY = 0f
                        detectDragGestures(
                            onDragStart = {
                                start = latestBox
                                totalX = 0f
                                totalY = 0f
                            },
                        ) { change, drag ->
                            change.consume()
                            totalX += drag.x * toFrameX
                            totalY += drag.y * toFrameY
                            onBox(
                                traceMoveCorner(
                                    start, corner, totalX.toInt(), totalY.toInt(), frameWidth, frameHeight,
                                ),
                            )
                        }
                    }
                    .semantics { contentDescription = traceCornerName(corner) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

private fun traceCornerName(corner: TraceCropCorner): String = when (corner) {
    TraceCropCorner.TOP_LEFT -> "Top-left corner of the frame. Drag it, or use the boxes below."
    TraceCropCorner.TOP_RIGHT -> "Top-right corner of the frame. Drag it, or use the boxes below."
    TraceCropCorner.BOTTOM_LEFT -> "Bottom-left corner of the frame. Drag it, or use the boxes below."
    TraceCropCorner.BOTTOM_RIGHT -> "Bottom-right corner of the frame. Drag it, or use the boxes below."
}

@Composable
private fun TraceCropNumberField(
    label: String,
    value: Int,
    draft: String?,
    enabled: Boolean,
    onType: (String) -> Unit,
    onCommit: (String) -> Unit,
) {
    val shown = draft ?: value.toString()
    Column(modifier = Modifier.width(150.dp)) {
        TracePanelLabel(label)
        OutlinedTextField(
            value = shown,
            onValueChange = onType,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit(shown) }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused && draft != null) onCommit(draft) },
        )
    }
}
