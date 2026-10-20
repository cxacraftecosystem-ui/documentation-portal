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

/*
 * PINCH, PAN AND DOUBLE-TAP FOR THE TWO HAND-DRAWN MAPS (Places and Coverage), in both apps.
 *
 * Shared with the Field Repository byte for byte apart from the package line.
 *
 * The maps stay what they were — the shipped official outline on a Compose Canvas, no SDK, no tiles —
 * and this adds a transform on top: screen = offset + scale * frame, where "frame" is the map's own
 * resting layout (the whole country, or a focused state fitted to the box). Everything a map draws is
 * positioned through [MapZoomState.toScreen]; every tap is read back through [MapZoomState.toContent]
 * before it is hit-tested, so a tap lands on what is under the finger at any zoom.
 *
 * THE PAGE STILL SCROLLS. The maps sit in a scrolling column, so ONE finger at scale 1 is left alone
 * for the column: only two or more fingers pinch and pan. Once zoomed in, one finger pans the map,
 * after the platform's touch slop so a tap is still a tap.
 */

/** The zoom range: the resting map, up to eight times closer. */
const val MAP_MIN_ZOOM = 1f
const val MAP_MAX_ZOOM = 8f

/** A double tap zooms by this much about the tap point; at [MAP_MAX_ZOOM] it resets. */
const val MAP_DOUBLE_TAP_FACTOR = 2f

/** Content scale and translation, in pixels. Read during draw, so a pinch recomposes nothing else. */
@Stable
class MapZoomState(scale: Float = 1f, offset: Offset = Offset.Zero) {
    var scale by mutableFloatStateOf(scale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM))
        private set
    var offset by mutableStateOf(offset)
        private set

    /** True once the map is zoomed in at all: what shows the Reset control. */
    val isZoomed: Boolean get() = scale > MAP_MIN_ZOOM + 0.001f

    /** A frame point (the map's resting layout) to where it is drawn on screen. */
    fun toScreen(content: Offset): Offset = offset + content * scale

    /** A screen point (a tap) back to the frame, for hit-testing. */
    fun toContent(screen: Offset): Offset = (screen - offset) / scale

    /** Apply one step of a gesture: zoom by [zoom] about [centroid], then move by [pan], clamped. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset, frame: Size) {
        val next = MapZoomMath.zoomAbout(scale, offset, zoom, centroid, frame)
        scale = next.first
        offset = MapZoomMath.clampOffset(next.second + pan, scale, frame)
    }

    /** Set both at once, clamped. */
    fun snapTo(newScale: Float, newOffset: Offset, frame: Size) {
        scale = newScale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
        offset = MapZoomMath.clampOffset(newOffset, scale, frame)
    }

    /** Back to the resting map. */
    fun reset() {
        scale = MAP_MIN_ZOOM
        offset = Offset.Zero
    }

    /**
     * Move to a target, animated unless [instant] (reduced motion): a straight interpolation of scale
     * and offset together, so the point under the tap stays under it the whole way.
     */
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

/** The pure arithmetic, so `MapZoomTest` can hold it on the JVM. */
object MapZoomMath {
    fun clampScale(scale: Float): Float = scale.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)

    /**
     * Keep the content covering the frame: the scaled map is never dragged so far that empty space
     * shows on any side. At scale 1 the only legal offset is zero.
     */
    fun clampOffset(offset: Offset, scale: Float, frame: Size): Offset {
        if (frame.width <= 0f || frame.height <= 0f) return Offset.Zero
        val minX = frame.width * (1f - scale)
        val minY = frame.height * (1f - scale)
        return Offset(offset.x.coerceIn(minX, 0f), offset.y.coerceIn(minY, 0f))
    }

    /** Scale by [factor] about [focus] (a screen point), keeping the content under it in place. */
    fun zoomAbout(scale: Float, offset: Offset, factor: Float, focus: Offset, frame: Size): Pair<Float, Offset> {
        val next = clampScale(scale * factor)
        val ratio = next / scale
        val moved = focus - (focus - offset) * ratio
        return next to clampOffset(moved, next, frame)
    }

    /** Where a double tap at [focus] goes: twice as close, or back to 1 from the top of the range. */
    fun doubleTapTarget(scale: Float, offset: Offset, focus: Offset, frame: Size): Pair<Float, Offset> =
        if (scale >= MAP_MAX_ZOOM - 0.001f) {
            MAP_MIN_ZOOM to Offset.Zero
        } else {
            zoomAbout(scale, offset, MAP_DOUBLE_TAP_FACTOR, focus, frame)
        }

    /**
     * Whether a gesture with [pointers] fingers down belongs to the map. Two or more always do; one
     * does only once zoomed in — at scale 1 it is the page's scroll.
     */
    fun mapOwnsGesture(pointers: Int, scale: Float): Boolean =
        pointers >= 2 || (pointers == 1 && scale > MAP_MIN_ZOOM + 0.001f)
}

@Composable
fun rememberMapZoomState(): MapZoomState = remember { MapZoomState() }

/**
 * Pinch (two fingers), pan (two fingers, or one once zoomed in), double tap (×2 about the tap, reset
 * at ×8) and tap. [onTap] receives the tap already mapped back to the FRAME, ready to hit-test.
 * [frame] is the drawn box; [Size.Unspecified] uses the element's own size.
 */
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

/**
 * The way back to the whole map: a 48dp round button, shown only while zoomed in. Place it at the
 * map's top end.
 */
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
