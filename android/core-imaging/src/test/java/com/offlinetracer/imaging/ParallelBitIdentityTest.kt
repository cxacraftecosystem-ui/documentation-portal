package com.offlinetracer.imaging

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The safety net for parallelising the engine.** Every stage that splits its work across the pool
 * is run twice on the same input — once with `Parallel.maxThreads = 1` and once with 8 — and the two
 * results are required to be **bit-identical**, element by element, not merely close.
 *
 * This is not belt-and-braces. Two independent engines are held to an absolute 1e-4 on float stages
 * (ALGORITHMS §14) and to *exact* equality on masks, integers and `d` strings, and XDoG and FDoG
 * multiply an input error by about a thousand on the way out. A reassociated sum — a per-share
 * accumulator merged in whatever order the threads finished, a scratch buffer shared between shares,
 * a share reading a value another share had already updated — is therefore a parity regression, and it
 * is one that a tolerance-based test would report as green. So the assertion here is raw bits.
 *
 * Read a failure here as one of exactly three things:
 *
 *  1. a loop was split whose iterations are not independent (a running sum, a flood, a shared
 *     accumulator, an in-place update read by a neighbour);
 *  2. scratch state was shared between shares instead of allocated per share — the classic one is
 *     `Distance`'s lower-envelope `v`/`z` arrays and `Resample`'s row ring;
 *  3. a barrier was removed, so a pass started before the pass it reads had finished.
 *
 * The image is 384×352. That size is deliberate: it is above every grain in [Parallel] (8 row shares
 * at [Parallel.ROWS_NEIGHBOURHOOD] and [Parallel.ROWS_KERNEL], 2 at [Parallel.PIXELS_MAP], 8 columns
 * for the distance transform), so a stage that failed to split would be a vacuous pass —
 * [theTestImageActuallySplits] exists to fail loudly if a threshold is ever raised past it.
 */
class ParallelBitIdentityTest {

    private val original = Parallel.maxThreads

    @AfterTest
    fun restore() {
        Parallel.maxThreads = original
    }

    private val w = 384
    private val h = 352

    /**
     * Deterministic and deliberately awkward: a diagonal ramp so no two neighbours are equal, a hard
     * vertical edge, a dark disc, and a sparse grid of saturated specks. Flat synthetic input is the
     * one thing that would hide a reassociation, because every ordering of equal values agrees.
     */
    private fun source(width: Int = w, height: Int = h): RgbaImage {
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - width / 3
                val dy = y - height / 2
                val disc = dx * dx + dy * dy < (height / 5) * (height / 5)
                var r = (x * 7 + y * 3) % 256
                var g = if (x > width * 2 / 3) 230 else (y * 5) % 200
                var b = if (disc) 20 else (x * y) % 251
                if (x % 37 == 0 && y % 41 == 0) {
                    r = 255
                    g = 0
                    b = 0
                }
                px[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbaImage(width, height, px)
    }

    private fun mask(width: Int = w, height: Int = h): Mask {
        val m = Mask(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - width / 2
                val dy = y - height / 2
                m[x, y] = dx * dx + dy * dy < (height / 4) * (height / 4) ||
                    (x % 29 == 0 && y % 31 == 0) ||
                    (y == height / 5 && x in 4 until width - 4)
            }
        }
        return m
    }

    /** Runs [body] single-threaded, then with eight shares, and hands back both results. */
    private fun <T> bothWays(body: () -> T): Pair<T, T> {
        Parallel.maxThreads = 1
        val one = body()
        Parallel.maxThreads = 8
        val many = body()
        return one to many
    }

    /**
     * [stride] is the row length of the buffer being compared, used only to turn a flat index into a
     * row and column in the failure message — which is the first thing you want to know, because a
     * mismatch that starts exactly on a share boundary names the bug immediately.
     */
    private fun same(name: String, r: Pair<FloatArray, FloatArray>, stride: Int = w) {
        val (one, many) = r
        assertEquals(one.size, many.size, "$name: size changed")
        for (i in one.indices) {
            // Raw bits, not `==`: it is the only comparison that distinguishes 0.0 from -0.0 and
            // treats NaN as equal to itself, and both of those are legitimate stage outputs here.
            if (one[i].toRawBits() != many[i].toRawBits()) {
                assertEquals(
                    one[i].toRawBits(), many[i].toRawBits(),
                    "$name differs at index $i (row ${i / stride}, col ${i % stride}): " +
                        "1 thread gave ${one[i]}, 8 threads gave ${many[i]}",
                )
            }
        }
    }

    private fun sameD(name: String, r: Pair<DoubleArray, DoubleArray>) {
        val (one, many) = r
        assertEquals(one.size, many.size, "$name: size changed")
        for (i in one.indices) {
            if (one[i].toRawBits() != many[i].toRawBits()) {
                assertEquals(
                    one[i].toRawBits(), many[i].toRawBits(),
                    "$name differs at index $i: 1 thread gave ${one[i]}, 8 threads gave ${many[i]}",
                )
            }
        }
    }

    private fun sameI(name: String, r: Pair<IntArray, IntArray>) {
        val (one, many) = r
        assertEquals(one.size, many.size, "$name: size changed")
        for (i in one.indices) {
            if (one[i] != many[i]) {
                assertEquals(one[i], many[i], "$name differs at index $i")
            }
        }
    }

    private fun sameM(name: String, r: Pair<BooleanArray, BooleanArray>, stride: Int = w) {
        val (one, many) = r
        assertEquals(one.size, many.size, "$name: size changed")
        for (i in one.indices) {
            if (one[i] != many[i]) {
                assertEquals(
                    one[i], many[i],
                    "$name differs at index $i (row ${i / stride}, col ${i % stride})",
                )
            }
        }
    }

    /**
     * Anti-vacuity guard. Every assertion in this class is worthless if the test image is small enough
     * to run inline, so prove that it is not — for each grain the engine actually uses.
     */
    @Test
    fun theTestImageActuallySplits() {
        Parallel.maxThreads = 8
        for (grain in intArrayOf(Parallel.ROWS_NEIGHBOURHOOD, Parallel.ROWS_KERNEL)) {
            val shares = AtomicInteger(0)
            Parallel.rows(h, grain) { _, _ -> shares.incrementAndGet() }
            assertTrue(
                shares.get() > 1,
                "a ${h}-row image must split at grain $grain or this whole test proves nothing " +
                    "(got ${shares.get()} share)",
            )
        }
        val shares = AtomicInteger(0)
        Parallel.chunks(w * h, Parallel.PIXELS_MAP) { _, _ -> shares.incrementAndGet() }
        assertTrue(
            shares.get() > 1,
            "${w * h} pixels must split at the per-pixel grain (got ${shares.get()} share)",
        )
        val cols = AtomicInteger(0)
        Parallel.chunks(w, Parallel.ROWS_KERNEL) { _, _ -> cols.incrementAndGet() }
        assertTrue(cols.get() > 1, "$w columns must split for the distance transform")
    }

    @Test
    fun colourConversionsAreBitIdentical() {
        val rgba = source()
        val gray = Color.toGray(rgba)
        same("Color.toGray", bothWays { Color.toGray(rgba).data })
        same("Color.toGrayLinear", bothWays { Color.toGrayLinear(rgba).data })
        same("Color.channel(SATURATION)", bothWays { Color.channel(rgba, Channel.SATURATION).data })
        sameI("Color.toRgba", bothWays { Color.toRgba(gray, false).pixels })
        val lab = bothWays { Color.toLabPlanes(rgba) }
        same("Color.toLabPlanes[L]", lab.first[0] to lab.second[0])
        same("Color.toLabPlanes[a]", lab.first[1] to lab.second[1])
        same("Color.toLabPlanes[b]", lab.first[2] to lab.second[2])
    }

    @Test
    fun convolutionAndDerivativesAreBitIdentical() {
        val gray = Color.toGray(source())
        same("Convolve.gaussianBlur", bothWays { Convolve.gaussianBlur(gray, 2.3f).data })
        sameD("Convolve.gaussianBlurDouble", bothWays { Convolve.gaussianBlurDouble(gray, 2.3f) })
        same("Convolve.gradients.gx", bothWays { Convolve.gradients(gray).gx.data })
        same("Convolve.gradients.gy", bothWays { Convolve.gradients(gray).gy.data })
        same("Gradients.magnitude", bothWays { Convolve.gradients(gray).magnitude().data })
        same("Gradients.direction", bothWays { Convolve.gradients(gray).direction().data })
        same("Convolve.laplacian", bothWays { Convolve.laplacian(gray).data })
        // Left sequential on purpose; here to catch it if that ever changes carelessly.
        same("Convolve.boxBlur", bothWays { Convolve.boxBlur(gray, 3).data })
    }

    @Test
    fun denoiseIsBitIdentical() {
        val gray = Color.toGray(source())
        same("Denoise.bilateral", bothWays { Denoise.bilateral(gray, 3f, 0.15f).data })
        same("Denoise.median(1)", bothWays { Denoise.median(gray, 1).data })
        same("Denoise.median(3)", bothWays { Denoise.median(gray, 3).data })
        same("Denoise.anisotropicDiffusion", bothWays { Denoise.anisotropicDiffusion(gray, 3, 0.1f).data })
        same("Denoise.despeckle", bothWays { Denoise.despeckle(gray, 2, 0.05f).data })
    }

    @Test
    fun contrastIsBitIdentical() {
        val gray = Color.toGray(source())
        same("Contrast.clahe(8x8)", bothWays { Contrast.clahe(gray, 8, 8, 2f).data })
        // An uneven partition, so the tile-row split has to land on the same lattice either way.
        same("Contrast.clahe(7x5)", bothWays { Contrast.clahe(gray, 7, 5, 3f).data })
        same("Contrast.gamma", bothWays { Contrast.gamma(gray, 1.8f).data })
        same("Contrast.levels", bothWays { Contrast.levels(gray, 0.1f, 0.9f, 2.2f).data })
        same("Contrast.equalize", bothWays { Contrast.equalize(gray).data })
        same("Contrast.percentileStretch", bothWays { Contrast.percentileStretch(gray, 1f).data })
        same("Contrast.unsharpMask", bothWays { Contrast.unsharpMask(gray, 1.5f, 0.8f, 0.01f).data })
    }

    /**
     * The distance transform is the one to watch. Felzenszwalb's two passes carry the parabola vertex
     * list `v` and the breakpoints `z` from the first half of a line to the second, so a shared
     * scratch buffer produces a field that is wrong without looking wrong.
     */
    @Test
    fun theDistanceTransformIsBitIdentical() {
        val m = mask()
        same("Distance.euclidean", bothWays { Distance.euclidean(m).data })
        same("Distance.euclidean(background)", bothWays { Distance.euclidean(m, false).data })
    }

    @Test
    fun theEdgeEnginesAreBitIdentical() {
        val gray = Color.toGray(source())
        sameM("EdgeCanny.detectAuto", bothWays { EdgeCanny.detectAuto(gray, 1.4f).data })
        sameM("EdgeCanny.detect", bothWays { EdgeCanny.detect(gray, 1.2f, 0.05f, 0.15f).data })
        same("EdgeCanny.nonMaximumSuppression", bothWays {
            EdgeCanny.nonMaximumSuppression(Convolve.gradients(gray)).data
        })
        same("EdgeDog.dog", bothWays { EdgeDog.dog(gray, 1.1f).data })
        same("EdgeDog.xdog", bothWays { EdgeDog.xdog(gray, 1.1f).data })
        sameM("EdgeLog.detect", bothWays { EdgeLog.detect(gray, 1.3f, 1e-4f).data })
    }

    /**
     * FDoG is the stage the parallelisation was for and the stage with the most to lose: at τ = 0.99
     * step 1 divides by 0.01 and the feedback multiplies what survives by another 100 per pass, so a
     * single reassociated add would show up as a visibly different drawing, not as a last-bit wobble.
     */
    @Test
    fun theFlowTierIsBitIdentical() {
        val gray = Color.toGray(source())
        val tensor = bothWays { EdgeFlow.structureTensorFlow(gray, 2f) }
        same("structureTensorFlow.tx", tensor.first.tx to tensor.second.tx)
        same("structureTensorFlow.ty", tensor.first.ty to tensor.second.ty)
        same("structureTensorFlow.magnitude", tensor.first.magnitude to tensor.second.magnitude)

        val field = EdgeFlow.structureTensorFlow(gray, 2f)
        val etf = bothWays { EdgeFlow.refineEtf(field, 3, 5) }
        same("refineEtf.tx", etf.first.tx to etf.second.tx)
        same("refineEtf.ty", etf.first.ty to etf.second.ty)

        val refined = EdgeFlow.refineEtf(field, 3, 5)
        same("fdog", bothWays { EdgeFlow.fdog(gray, refined, 1f, 3f, 0.99f, 3, 0.5f, 20f).data })
        same("coherentLineDrawing", bothWays {
            EdgeFlow.coherentLineDrawing(gray, EdgeFlow.FlowParams()).data
        })
    }

    @Test
    fun morphologyIsBitIdentical() {
        val m = mask()
        val gray = Color.toGray(source())
        for (shape in SeShape.entries) {
            sameM("Morphology.dilate($shape)", bothWays { Morphology.dilate(m, 3, shape).data })
            sameM("Morphology.erode($shape)", bothWays { Morphology.erode(m, 3, shape).data })
        }
        sameM("Morphology.gradient", bothWays { Morphology.gradient(m, 2).data })
        same("Morphology.dilateGray", bothWays { Morphology.dilateGray(gray, 3).data })
        same("Morphology.erodeGray", bothWays { Morphology.erodeGray(gray, 3).data })
    }

    /**
     * Resampling caches horizontally-resampled source rows in a ring keyed by row index. Per share it
     * is a memo and the answer is unchanged; shared between shares it is both a data race and a cache
     * that thrashes, so this covers a downscale (box kernel, ring deeper than one row) and an upscale
     * (linear kernel) in both pixel formats.
     */
    @Test
    fun resamplingIsBitIdentical() {
        val rgba = source()
        val gray = Color.toGray(rgba)
        val m = mask()
        same("Resample.resize(gray, down)", bothWays { Resample.resize(gray, 137, 111).data })
        same("Resample.resize(gray, up)", bothWays { Resample.resize(gray, 811, 640).data })
        sameI("Resample.resize(rgba, down)", bothWays { Resample.resize(rgba, 137, 111).pixels })
        sameI("Resample.resize(rgba, up)", bothWays { Resample.resize(rgba, 811, 640).pixels })
        sameM("Resample.resize(mask)", bothWays { Resample.resize(m, 137, 111).data })
    }

    @Test
    fun mattingIsBitIdentical() {
        val rgba = source()
        same("Matte.borderFlood", bothWays { Matte.borderFlood(rgba, 0.2f, 1.5f).data })
        same("Matte.spectralSaliency", bothWays { Matte.spectralSaliency(rgba).data })
        same("Matte.saliencyMatte", bothWays { Matte.saliencyMatte(rgba).data })
        val alpha = Matte.borderFlood(rgba, 0.2f, 1.5f)
        sameI("Matte.applyMatte", bothWays { Matte.applyMatte(rgba, alpha, 0xFF203040.toInt()).pixels })
    }

    /**
     * The stages that were left sequential — hysteresis, union-find labelling, thinning, the
     * summed-area thresholds — still consume parallelised producers, so they get the same treatment.
     * Their output is compared exactly because it is a mask or a label map, where "close" is not a
     * thing.
     */
    @Test
    fun theSequentialStagesDownstreamAreBitIdentical() {
        val gray = Color.toGray(source())
        val m = mask()
        sameM("Threshold.sauvola", bothWays { Threshold.sauvola(gray, 8, 0.2f).data })
        sameM("Threshold.adaptiveMean", bothWays { Threshold.adaptiveMean(gray, 8, 0.02f).data })
        sameM("Threshold.hysteresis", bothWays {
            Threshold.hysteresis(Convolve.gradients(gray).magnitude(), 0.02f, 0.08f).data
        })
        sameM("Thinning.zhangSuen", bothWays { Thinning.zhangSuen(m).data })
        sameI("Components.label", bothWays { Components.label(m).labels })
    }

    /**
     * The cross-engine fixture shapes, end to end. These run entirely inline, and the point of
     * asserting them anyway is that "inline" must mean *identical*, not merely "probably fine" — a
     * parity failure has to be reproducible on one thread to be debuggable at all.
     */
    @Test
    fun theParityFixtureShapesAreIdenticalAndUnaffected() {
        for (size in listOf(1 to 1, 24 to 18)) {
            val (fw, fh) = size
            val img = source(fw, fh)
            val gray = Color.toGray(img)
            val name = "${fw}x$fh"
            same("$name Color.toGray", bothWays { Color.toGray(img).data }, fw)
            same("$name Contrast.clahe", bothWays { Contrast.clahe(gray, 8, 8, 2f).data }, fw)
            same("$name EdgeDog.xdog", bothWays { EdgeDog.xdog(gray, 1.1f).data }, fw)
            same("$name coherentLineDrawing", bothWays {
                EdgeFlow.coherentLineDrawing(gray, EdgeFlow.FlowParams()).data
            }, fw)
            val m = Mask(fw, fh).fill(true)
            m[0, 0] = false
            same("$name Distance.euclidean", bothWays { Distance.euclidean(m).data }, fw)
        }
    }
}
