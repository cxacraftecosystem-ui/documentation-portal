package com.offlinetracer.export

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle

/**
 * Shared test geometry. Deliberately small and hand-checkable: every expectation in the format
 * tests is derived from these numbers rather than from a golden file, so a change in behaviour
 * shows up as a failing assertion instead of a diff nobody can read.
 */
internal object Fixtures {

    const val DOC_W = 100f
    const val DOC_H = 50f

    /** Number of drawable shapes in [document]. */
    const val SHAPE_COUNT = 3

    fun closedRect(x: Float, y: Float, w: Float, h: Float): VecPath = VecPath(
        start = VecPoint(x, y),
        segments = listOf(
            VecSeg.Line(VecPoint(x + w, y)),
            VecSeg.Line(VecPoint(x + w, y + h)),
            VecSeg.Line(VecPoint(x, y + h)),
        ),
        closed = true,
    )

    fun openCurve(): VecPath = VecPath(
        start = VecPoint(5f, 45f),
        segments = listOf(VecSeg.Cubic(VecPoint(20f, 8f), VecPoint(60f, 44f), VecPoint(94f, 16f))),
        closed = false,
    )

    /**
     * Two layers whose names both need sanitising for DXF (a space, punctuation, lowercase),
     * one stroked open path, one stroked closed path and one filled closed path.
     */
    fun document(): VecDocument = VecDocument(
        width = DOC_W,
        height = DOC_H,
        layers = listOf(
            VecLayer(
                id = "cut",
                name = "Cut lines!",
                shapes = listOf(
                    VecShape(closedRect(10f, 10f, 30f, 20f), VecStyle()),
                    VecShape(openCurve(), VecStyle(strokeWidth = 2f)),
                ),
            ),
            VecLayer(
                id = "engrave",
                name = "engrave 2",
                shapes = listOf(
                    VecShape(
                        closedRect(55f, 8f, 30f, 30f),
                        VecStyle(stroke = null, fill = 0xFFCC3322.toInt()),
                    ),
                ),
            ),
        ),
    )

    /** A document with no geometry at all — the degenerate case every writer must survive. */
    fun emptyDocument(): VecDocument = VecDocument(DOC_W, DOC_H, emptyList())

    /**
     * A deterministic image with every channel varying independently, including partial alpha,
     * so a channel swap or a dropped alpha byte cannot pass a round-trip test by coincidence.
     */
    fun image(w: Int, h: Int): RgbaImage {
        val px = IntArray(w * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = (200 + x * 7 + y * 3) and 0xFF
                val r = (x * 37 + 11) and 0xFF
                val g = (y * 53 + 29) and 0xFF
                val b = (x * y * 13 + 5) and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
        }
        return RgbaImage(w, h, px)
    }

    /** Greyscale ramp with a couple of out-of-range values to prove clamping, not wrapping. */
    fun gray(w: Int, h: Int): GrayF {
        val data = FloatArray(w * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                data[i] = ((x * 17 + y * 31) % 256) / 255f
                i++
            }
        }
        data[0] = -0.5f
        data[data.size - 1] = 1.5f
        return GrayF(w, h, data)
    }
}
