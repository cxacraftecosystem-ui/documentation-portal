package com.fieldrepository.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val MAP_MIN_ZOOM = 1f
const val MAP_MAX_ZOOM = 8f

const val MAP_DOUBLE_TAP_FACTOR = 2f

@Stable
class MapZoomState(scale: Float = 1f, offset: Offset = Offset.Zero) {
    var scale by mutableFloatStateOf(scale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM))
        private set
    var offset by mutableStateOf(offset)
        private set

    val isZoomed: Boolean get() = scale > MAP_MIN_ZOOM + 0.001f

    fun toScreen(content: Offset): Offset = offset + content * scale

    fun toContent(screen: Offset): Offset = (screen - offset) / scale

    fun transform(zoom: Float, pan: Offset, centroid: Offset, frame: Size) {
        val next = MapZoomMath.zoomAbout(scale, offset, zoom, centroid, frame)
        scale = next.first
        offset = MapZoomMath.clampOffset(next.second + pan, scale, frame)
    }

    fun snapTo(newScale: Float, newOffset: Offset, frame: Size) {
        scale = newScale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
        offset = MapZoomMath.clampOffset(newOffset, scale, frame)
    }

    fun reset() {
        scale = MAP_MIN_ZOOM
        offset = Offset.Zero
    }

    suspend fun animateTo(targetScale: Float, targetOffset: Offset, frame: Size, instant: Boolean) {
        if (instant) {
            snapTo(targetScale, targetOffset, frame)
            return
        }
        val fromScale = scale
        val fromOffset = offset
        val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)
        progress.animateTo(1f, tween(durationMillis = 220)) {
            val t = value
            snapTo(
                fromScale + (targetScale - fromScale) * t,
                fromOffset + (targetOffset - fromOffset) * t,
                frame
            )
        }
    }
}

object MapZoomMath {
    fun clampScale(scale: Float): Float = scale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)

    fun clampOffset(offset: Offset, scale: Float, frame: Size): Offset {
        if (frame.width <= 0f || frame.height <= 0f) return Offset.Zero
        val minX = frame.width * (1f - scale)
        val minY = frame.height * (1f - scale)
        return Offset(offset.x.coerceIn(minX, 0f), offset.y.coerceIn(minY, 0f))
    }

    fun zoomAbout(scale: Float, offset: Offset, factor: Float, focus: Offset, frame: Size): Pair<Float, Offset> {
        val next = clampScale(scale * factor)
        val ratio = next / scale
        val moved = focus - (focus - offset) * ratio
        return next to clampOffset(moved, next, frame)
    }

    fun fitTarget(box: Rect, frame: Size, margin: Float = 0.08f): Pair<Float, Offset> {
        if (frame.width <= 0f || frame.height <= 0f) return MAP_MIN_ZOOM to Offset.Zero
        val pad = max(box.width, box.height) * margin
        val width = max(box.width + pad * 2f, 1e-3f)
        val height = max(box.height + pad * 2f, 1e-3f)
        val scale = clampScale(min(frame.width / width, frame.height / height))
        val centre = box.center
        val offset = Offset(frame.width / 2f - centre.x * scale, frame.height / 2f - centre.y * scale)
        return scale to clampOffset(offset, scale, frame)
    }

    fun doubleTapTarget(scale: Float, offset: Offset, focus: Offset, frame: Size): Pair<Float, Offset> =
        if (scale >= MAP_MAX_ZOOM - 0.001f) {
            MAP_MIN_ZOOM to Offset.Zero
        } else {
            zoomAbout(scale, offset, MAP_DOUBLE_TAP_FACTOR, focus, frame)
        }

    fun mapOwnsGesture(pointers: Int, scale: Float): Boolean =
        pointers >= 2 || (pointers == 1 && scale > MAP_MIN_ZOOM + 0.001f)
}

@Composable
fun rememberMapZoomState(): MapZoomState = remember { MapZoomState() }

fun Modifier.mapZoomGestures(
    state: MapZoomState,
    frame: Size = Size.Unspecified,
    reducedMotion: Boolean = false,
    onTap: (Offset) -> Unit
): Modifier = this
    .clipToBounds()
    .pointerInput(state, frame) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val box = if (frame.isSpecified) frame else size.toSize()
            val slop = viewConfiguration.touchSlop
            var travelled = Offset.Zero
            var panning = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (zoom != 1f || pan != Offset.Zero) {
                        state.transform(zoom, pan, centroid, box)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (MapZoomMath.mapOwnsGesture(pressed, state.scale)) {
                    val pan = event.calculatePan()
                    if (!panning) {
                        travelled += pan
                        if (abs(travelled.x) + abs(travelled.y) > slop) panning = true
                    }
                    if (panning && pan != Offset.Zero) {
                        state.transform(1f, pan, Offset.Zero, box)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state, frame, reducedMotion) {
        coroutineScope {
            detectTapGestures(
                onDoubleTap = { point ->
                    val box = if (frame.isSpecified) frame else size.toSize()
                    val (scale, offset) = MapZoomMath.doubleTapTarget(state.scale, state.offset, point, box)
                    launch { state.animateTo(scale, offset, box, instant = reducedMotion) }
                },
                onTap = { point -> onTap(state.toContent(point)) }
            )
        }
    }

@Composable
fun MapZoomResetButton(state: MapZoomState, modifier: Modifier = Modifier) {
    if (!state.isZoomed) return
    FilledTonalIconButton(
        onClick = { state.reset() },
        shape = CircleShape,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "Reset zoom" }
    ) {
        Icon(Icons.Filled.ZoomOutMap, contentDescription = null)
    }
}
