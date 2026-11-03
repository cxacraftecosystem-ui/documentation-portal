package com.fieldrepository.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class MapZoomTest {
    private val frame = Size(400f, 300f)
    private val eps = 1e-3f

    @Test
    fun pinchingKeepsThePointUnderTheFingersInPlace() {
        val focus = Offset(100f, 80f)
        val (scale, offset) = MapZoomMath.zoomAbout(1f, Offset.Zero, 2f, focus, frame)
        assertEquals(2f, scale, eps)
        val state = MapZoomState(scale, offset)
        val content = state.toContent(focus)
        assertEquals(100f, content.x, eps)
        assertEquals(80f, content.y, eps)
    }

    @Test
    fun scaleIsClampedToOneThroughEight() {
        assertEquals(8f, MapZoomMath.zoomAbout(6f, Offset.Zero, 4f, Offset.Zero, frame).first, eps)
        assertEquals(1f, MapZoomMath.zoomAbout(1.5f, Offset.Zero, 0.1f, Offset.Zero, frame).first, eps)
        assertEquals(1f, MapZoomState(0.2f).scale, eps)
        assertEquals(8f, MapZoomState(20f).scale, eps)
    }

    @Test
    fun theMapNeverLeavesItsBox() {
        assertEquals(Offset.Zero, MapZoomMath.clampOffset(Offset(50f, -50f), 1f, frame))
        val clamped = MapZoomMath.clampOffset(Offset(-10_000f, 10_000f), 2f, frame)
        assertEquals(-400f, clamped.x, eps)
        assertEquals(0f, clamped.y, eps)
    }

    @Test
    fun aTapIsMappedBackThroughTheTransform() {
        val state = MapZoomState(4f, Offset(-600f, -300f))
        val screen = state.toScreen(Offset(200f, 120f))
        assertEquals(200f, screen.x, eps)
        assertEquals(180f, screen.y, eps)
        val back = state.toContent(screen)
        assertEquals(200f, back.x, eps)
        assertEquals(120f, back.y, eps)
    }

    @Test
    fun doubleTapDoublesThenResetsAtTheTop() {
        val (two, _) = MapZoomMath.doubleTapTarget(1f, Offset.Zero, Offset(200f, 150f), frame)
        assertEquals(2f, two, eps)
        val (eight, _) = MapZoomMath.doubleTapTarget(6f, Offset.Zero, Offset(200f, 150f), frame)
        assertEquals(8f, eight, eps)
        val (reset, offset) = MapZoomMath.doubleTapTarget(8f, Offset(-100f, -100f), Offset(200f, 150f), frame)
        assertEquals(1f, reset, eps)
        assertEquals(Offset.Zero, offset)
    }

    @Test
    fun oneFingerAtRestBelongsToThePage() {
        assertFalse(MapZoomMath.mapOwnsGesture(1, 1f))
        assertTrue(MapZoomMath.mapOwnsGesture(2, 1f))
        assertTrue(MapZoomMath.mapOwnsGesture(1, 1.5f))
        assertFalse(MapZoomMath.mapOwnsGesture(0, 3f))
    }

    @Test
    fun resetReturnsToTheWholeMap() {
        val state = MapZoomState(3f, Offset(-50f, -20f))
        assertTrue(state.isZoomed)
        state.reset()
        assertEquals(1f, state.scale, eps)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun fittingAStateCentresItAndKeepsTheMapCovered() {
        val box = Rect(150f, 112.5f, 250f, 187.5f)
        val (scale, offset) = MapZoomMath.fitTarget(box, frame)
        assertEquals(min(400f / 116f, 300f / 91f), scale, eps)
        val centre = Offset(offset.x + 200f * scale, offset.y + 150f * scale)
        assertEquals(200f, centre.x, 0.5f)
        assertEquals(150f, centre.y, 0.5f)

        val (cornerScale, cornerOffset) = MapZoomMath.fitTarget(Rect(0f, 0f, 40f, 30f), frame)
        assertEquals(8f, cornerScale, eps)
        assertEquals(Offset.Zero, cornerOffset)

        assertEquals(8f, MapZoomMath.fitTarget(Rect(200f, 150f, 200f, 150f), frame).first, eps)
        assertEquals(1f, MapZoomMath.fitTarget(Rect(0f, 0f, 400f, 300f), frame).first, eps)
    }
}
