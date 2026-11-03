package com.offlinetracer.imaging

import kotlin.math.abs
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeTest {

    private fun verticalStep(w: Int, h: Int, edgeX: Int): GrayF {
        val g = GrayF(w, h)
        for (y in 0 until h) for (x in 0 until w) g[x, y] = if (x < edgeX) 0.1f else 0.9f
        return g
    }

    private fun columnsWithInk(m: Mask, y: Int): List<Int> {
        val out = ArrayList<Int>()
        for (x in 0 until m.width) if (m[x, y]) out.add(x)
        return out
    }

    @Test
    fun cannyFiresOnTheStepAndNowhereElse() {
        val src = verticalStep(32, 16, 16)
        val edges = EdgeCanny.detect(src, 1f, 0.05f, 0.15f)
        assertTrue(edges.countTrue() > 0, "the step must be detected")
        for (y in 4 until 12) {
            val cols = columnsWithInk(edges, y)
            assertTrue(cols.isNotEmpty(), "row $y found no edge")
            for (c in cols) assertTrue(abs(c - 15.5f) <= 2f, "row $y fired at $c, far from the step")
        }
    }

    @Test
    fun cannyNonMaximumSuppressionKeepsTheEdgeThin() {
        val src = verticalStep(32, 16, 16)
        val edges = EdgeCanny.detect(src, 1.2f, 0.05f, 0.15f)
        for (y in 3 until 13) {
            assertTrue(columnsWithInk(edges, y).size <= 2, "row $y is ${columnsWithInk(edges, y).size} px thick")
        }
    }

    @Test
    fun cannyOnAConstantImageFindsNothing() {
        assertEquals(0, EdgeCanny.detect(GrayF(24, 24).fill(0.5f), 1f, 0.05f, 0.2f).countTrue())
    }

    @Test
    fun detectAutoFindsTheStepWithoutBeingToldTheThresholds() {
        val src = verticalStep(40, 20, 20)
        val edges = EdgeCanny.detectAuto(src, 1f)
        assertTrue(edges.countTrue() > 0, "auto thresholds must still find an obvious step")
        assertTrue(
            edges.countTrue() < edges.size / 4,
            "auto thresholds must not mark a quarter of the image, got ${edges.countTrue()}",
        )
    }

    @Test
    fun nonMaximumSuppressionNeverIncreasesTheMagnitude() {
        val src = verticalStep(24, 12, 12)
        val g = Convolve.gradients(Convolve.gaussianBlur(src, 1f))
        val magnitude = g.magnitude()
        val thin = EdgeCanny.nonMaximumSuppression(g)
        assertEquals(magnitude.width, thin.width)
        for (i in magnitude.data.indices) {
            assertTrue(
                thin.data[i] <= magnitude.data[i] + 1e-5f,
                "suppression must only remove, index $i went from ${magnitude.data[i]} to ${thin.data[i]}",
            )
        }
        assertTrue(thin.range().second > 0f, "suppression must not erase the ridge")
    }

    @Test
    fun dogOfAConstantIsTheConstantTimesOneMinusTau() {
        val out = EdgeDog.dog(GrayF(20, 20).fill(0.5f), 1f, 1.6f, 0.98f)
        for (v in out.data) assertEquals(0.5f * (1f - 0.98f), v, 1e-3f)
    }

    @Test
    fun dogChangesSignAcrossAStep() {
        val out = EdgeDog.dog(verticalStep(32, 8, 16), 1.5f, 1.6f, 0.98f)
        val left = out[14, 4]
        val right = out[17, 4]
        assertTrue(left * right < 0f, "a DoG must change sign across an edge, got $left and $right")
    }

    @Test
    fun softThresholdMatchesTheFormula() {
        val eps = 0.5f
        val phi = 20f
        assertEquals(1f, EdgeDog.softThreshold(0.5f, eps, phi), 1e-6f)
        assertEquals(1f, EdgeDog.softThreshold(0.9f, eps, phi), 1e-6f)
        assertEquals(1f + tanh(phi * (0.4f - eps)), EdgeDog.softThreshold(0.4f, eps, phi), 1e-5f)
        assertEquals(1f + tanh(phi * (0f - eps)), EdgeDog.softThreshold(0f, eps, phi), 1e-5f)
    }

    @Test
    fun xdogReturnsInkDensityInRangeAndIsFlatOnAFlatImage() {
        val out = EdgeDog.xdog(GrayF(24, 24).fill(0.6f), 1f)
        val first = out.data[0]
        for (v in out.data) {
            assertTrue(v >= -1e-4f && v <= 1f + 1e-4f, "ink density must be 0..1, got $v")
            assertEquals(first, v, 1e-4f, "a flat image must produce a flat response")
        }
        assertTrue(first > 0.5f, "a flat image must come out as paper, got $first")
    }

    @Test
    fun xdogDarkensAtAnEdge() {
        val src = verticalStep(40, 12, 20)
        val out = EdgeDog.xdog(src, 1f, 1.6f, 0.98f, 0.5f, 20f)
        var minAtEdge = Float.MAX_VALUE
        for (x in 17..22) minAtEdge = minOf(minAtEdge, out[x, 6])
        assertTrue(minAtEdge < out[36, 6] - 0.05f, "the edge must be darker than flat paper")

        val edgeOnly = EdgeDog.xdog(src, 1f, 1.6f, 0.98f, 0.05f, 20f)
        var minEdgeOnly = Float.MAX_VALUE
        for (x in 17..22) minEdgeOnly = minOf(minEdgeOnly, edgeOnly[x, 6])
        assertTrue(minEdgeOnly < 0.05f, "the step must still ink at eps = 0.05, got $minEdgeOnly")
        assertTrue(edgeOnly[3, 6] > 0.95f, "the flat dark half must be paper at eps = 0.05, got ${edgeOnly[3, 6]}")
        assertTrue(edgeOnly[36, 6] > 0.95f, "the flat light half must be paper, got ${edgeOnly[36, 6]}")
    }

    @Test
    fun logZeroCrossingsFindTheStep() {
        val src = verticalStep(32, 16, 16)
        val edges = EdgeLog.detect(src, 1.4f, 0.005f)
        assertTrue(edges.countTrue() > 0)
        for (y in 4 until 12) {
            for (c in columnsWithInk(edges, y)) {
                assertTrue(abs(c - 15.5f) <= 3f, "row $y fired at $c, far from the step")
            }
        }
    }

    @Test
    fun logResponseOfAConstantIsZeroAndHasNoZeroCrossings() {
        val flat = GrayF(20, 20).fill(0.4f)
        val response = EdgeLog.logResponse(flat, 1.4f)
        for (v in response.data) assertEquals(0f, v, 1e-4f)
        assertEquals(0, EdgeLog.zeroCrossings(response, 0.001f).countTrue())
    }

    @Test
    fun structureTensorFlowIsTangentToTheEdge() {
        val src = verticalStep(40, 40, 20)
        val field = EdgeFlow.structureTensorFlow(src, 2f)
        assertEquals(40, field.width)
        for (y in 15 until 25) {
            val i = y * 40 + 19
            assertTrue(abs(field.ty[i]) > 1e-6f, "the tangent at row $y is zero next to a strong edge")
            assertTrue(
                abs(field.ty[i]) > 5f * abs(field.tx[i]),
                "the tangent at row $y was (${field.tx[i]}, ${field.ty[i]}), not vertical",
            )
        }
    }

    @Test
    fun structureTensorFlowVectorsAreUnitLength() {
        val src = verticalStep(24, 24, 12)
        val field = EdgeFlow.structureTensorFlow(src, 2f)
        for (i in field.tx.indices) {
            val len = kotlin.math.sqrt(field.tx[i] * field.tx[i] + field.ty[i] * field.ty[i])
            assertTrue(
                abs(len - 1f) < 1e-3f || len < 1e-3f,
                "tangents must be normalised (or zero where there is no structure), got $len",
            )
        }
    }

    @Test
    fun etfRefinementKeepsTheFieldTangentAndFinite() {
        val src = verticalStep(32, 32, 16)
        val field = EdgeFlow.refineEtf(EdgeFlow.structureTensorFlow(src, 2f), 3, 5)
        for (i in field.tx.indices) {
            assertTrue(!field.tx[i].isNaN() && !field.ty[i].isNaN(), "ETF produced NaN at $i")
        }
        for (y in 12 until 20) {
            val i = y * 32 + 15
            assertTrue(
                abs(field.ty[i]) > 3f * abs(field.tx[i]) && abs(field.ty[i]) > 1e-6f,
                "the refined field must still follow the edge, got (${field.tx[i]}, ${field.ty[i]})",
            )
        }
    }

    @Test
    fun fdogReturnsInkDensityInRange() {
        val src = verticalStep(48, 48, 24)
        val field = EdgeFlow.refineEtf(EdgeFlow.structureTensorFlow(src, 2f), 2, 4)
        val ink = EdgeFlow.fdog(src, field, 1f, 3f, 0.99f, 2, 0.5f, 20f)
        assertEquals(48, ink.width)
        for (v in ink.data) assertTrue(v >= -1e-4f && v <= 1f + 1e-4f, "ink density must be 0..1, got $v")
        var minAtEdge = Float.MAX_VALUE
        for (x in 21..26) minAtEdge = minOf(minAtEdge, ink[x, 24])
        assertTrue(minAtEdge < ink[43, 24], "the edge must be darker than flat paper")
    }

    private fun antiAliasedDisc(w: Int, h: Int, r: Double, cx: Double, cy: Double): GrayF {
        val g = GrayF(w, h)
        val ss = 8
        for (y in 0 until h) for (x in 0 until w) {
            var inside = 0
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val px = x - 0.5 + (sx + 0.5) / ss
                val py = y - 0.5 + (sy + 0.5) / ss
                val dx = px - cx
                val dy = py - cy
                if (dx * dx + dy * dy <= r * r) inside++
            }
            g[x, y] = (0.9 - 0.8 * inside.toDouble() / (ss * ss)).toFloat()
        }
        return g
    }

    @Test
    fun subpixelRidgeLandsOnTheTrueCircleFarCloserThanTheGridCan() {
        val r = 18.0
        val cx = 31.37
        val cy = 32.61
        val src = antiAliasedDisc(64, 64, r, cx, cy)
        val g = Convolve.gradients(Convolve.gaussianBlur(src, 1.2f))
        val nms = EdgeCanny.nonMaximumSuppression(g)
        val ridge = EdgeCanny.subpixelRidge(g)
        var peak = 0f
        for (v in nms.data) if (v > peak) peak = v

        var n = 0
        var sumInt = 0.0
        var sumSub = 0.0
        for (y in 2 until 62) for (x in 2 until 62) {
            val i = y * 64 + x
            if (nms.data[i] < 0.25f * peak) continue
            val ei = Math.hypot(x - cx, y - cy) - r
            val es = Math.hypot(x + ridge.offsetX[i] - cx, y + ridge.offsetY[i] - cy) - r
            sumInt += ei * ei
            sumSub += es * es
            n++
        }
        assertTrue(n > 60, "the disc must produce a ridge, got $n pixels")
        val rmsInt = Math.sqrt(sumInt / n)
        val rmsSub = Math.sqrt(sumSub / n)
        assertTrue(rmsInt > 0.25, "the integer grid should be about a third of a pixel out, got $rmsInt")
        assertTrue(rmsSub < 0.12, "sub-pixel radial error must be well under a tenth of a pixel, got $rmsSub")
        assertTrue(rmsSub * 4.0 < rmsInt, "refinement must be a large win, got $rmsInt -> $rmsSub")
    }

    @Test
    fun subpixelRidgeNeverLeavesTheSurvivingPixelsOwnHalfCell() {
        val src = antiAliasedDisc(64, 64, 18.0, 31.37, 32.61)
        val g = Convolve.gradients(Convolve.gaussianBlur(src, 1.2f))
        val nms = EdgeCanny.nonMaximumSuppression(g)
        val ridge = EdgeCanny.subpixelRidge(g)
        val magnitude = g.magnitude()
        var checked = 0
        for (i in nms.data.indices) {
            if (nms.data[i] <= 0f) continue
            checked++
            assertTrue(abs(ridge.offsetX[i]) <= 0.5f + 1e-5f, "offsetX ${ridge.offsetX[i]} at $i")
            assertTrue(abs(ridge.offsetY[i]) <= 0.5f + 1e-5f, "offsetY ${ridge.offsetY[i]} at $i")
            assertTrue(
                ridge.magnitude[i] >= magnitude.data[i] - 1e-5f,
                "the interpolated peak must not fall below the sample it refines, at $i",
            )
        }
        assertTrue(checked > 60, "nothing was checked; the fixture produced no ridge")
    }

    @Test
    fun subpixelRidgeIsZeroWhereThereIsNoRidgeAndOnADegenerateImage() {
        val flat = EdgeCanny.subpixelRidge(Convolve.gradients(GrayF(16, 16).fill(0.5f)))
        for (i in flat.offsetX.indices) {
            assertEquals(0f, flat.offsetX[i], "a constant image has no ridge to refine")
            assertEquals(0f, flat.offsetY[i], "a constant image has no ridge to refine")
            assertEquals(0f, flat.magnitude[i], "a constant image has no gradient")
        }
        val one = EdgeCanny.subpixelRidge(Convolve.gradients(GrayF(1, 1, floatArrayOf(0.4f))))
        assertEquals(1, one.width)
        assertEquals(1, one.height)
        assertEquals(0f, one.offsetX[0])
        assertEquals(0f, one.offsetY[0])
        assertEquals(0f, one.offsetXAt(5, 5), "reads outside the image answer 0, not an exception")
        assertEquals(0f, one.offsetYAt(-1, 0), "reads outside the image answer 0, not an exception")
    }

    @Test
    fun ridgeSnapMovesEachPointOntoItsOwnRidgeAndLeavesOutsidePointsAlone() {
        val src = antiAliasedDisc(64, 64, 18.0, 31.37, 32.61)
        val g = Convolve.gradients(Convolve.gaussianBlur(src, 1.2f))
        val nms = EdgeCanny.nonMaximumSuppression(g)
        val ridge = EdgeCanny.subpixelRidge(g)

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (y in 0 until 64) for (x in 0 until 64) {
            if (nms.data[y * 64 + x] > 0f) {
                xs.add(x.toFloat())
                ys.add(y.toFloat())
            }
        }
        assertTrue(xs.size > 60, "no ridge pixels to snap")
        xs.add(-4f); ys.add(-4f)
        xs.add(200f); ys.add(200f)
        val ax = FloatArray(xs.size) { xs[it] }
        val ay = FloatArray(ys.size) { ys[it] }
        val bx = ax.copyOf()
        val by = ay.copyOf()
        ridge.snap(bx, by)
        for (i in ax.indices) {
            assertTrue(abs(bx[i] - ax[i]) <= 0.5f + 1e-5f, "point $i moved ${bx[i] - ax[i]} in x")
            assertTrue(abs(by[i] - ay[i]) <= 0.5f + 1e-5f, "point $i moved ${by[i] - ay[i]} in y")
        }
        assertEquals(-4f, bx[ax.size - 2], "a point left of the image must not be moved")
        assertEquals(200f, by[ay.size - 1], "a point past the image must not be moved")

        val cx = 31.37
        val cy = 32.61
        var sumInt = 0.0
        var sumSub = 0.0
        val n = ax.size - 2
        for (i in 0 until n) {
            val ei = Math.hypot(ax[i] - cx, ay[i] - cy) - 18.0
            val es = Math.hypot(bx[i] - cx, by[i] - cy) - 18.0
            sumInt += ei * ei
            sumSub += es * es
        }
        assertTrue(sumSub < sumInt, "snapping must reduce the total radial error, $sumInt -> $sumSub")
    }

    @Test
    fun everyEngineSurvivesAOnePixelImage() {
        val one = GrayF(1, 1, floatArrayOf(0.5f))
        EdgeCanny.detect(one, 1f, 0.1f, 0.2f)
        EdgeCanny.detectAuto(one, 1f)
        EdgeDog.dog(one, 1f)
        EdgeDog.xdog(one, 1f)
        EdgeLog.detect(one, 1.4f, 0.01f)
        val field = EdgeFlow.structureTensorFlow(one, 2f)
        EdgeFlow.refineEtf(field, 2, 3)
        EdgeFlow.fdog(one, field)
    }

    @Test
    fun everyEngineSurvivesAnAllZeroImage() {
        val zero = GrayF(16, 16)
        assertEquals(0, EdgeCanny.detect(zero, 1f, 0.1f, 0.2f).countTrue())
        EdgeCanny.detectAuto(zero, 1f)
        for (v in EdgeDog.dog(zero, 1f).data) assertEquals(0f, v, 1e-5f)
        for (v in EdgeDog.xdog(zero, 1f).data) assertTrue(!v.isNaN())
        EdgeLog.detect(zero, 1.4f, 0.01f)
        val field = EdgeFlow.structureTensorFlow(zero, 2f)
        for (i in field.tx.indices) assertTrue(!field.tx[i].isNaN() && !field.ty[i].isNaN())
        for (v in EdgeFlow.fdog(zero, field).data) assertTrue(!v.isNaN())
    }
}
