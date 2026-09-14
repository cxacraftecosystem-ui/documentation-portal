package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Color
import com.offlinetracer.imaging.Components
import com.offlinetracer.imaging.Contrast
import com.offlinetracer.imaging.Convolve
import com.offlinetracer.imaging.Denoise
import com.offlinetracer.imaging.Distance
import com.offlinetracer.imaging.EdgeCanny
import com.offlinetracer.imaging.EdgeDog
import com.offlinetracer.imaging.EdgeFlow
import com.offlinetracer.imaging.EdgeLog
import com.offlinetracer.imaging.GradientOp
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Morphology
import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.imaging.SeShape
import com.offlinetracer.imaging.Thinning
import com.offlinetracer.imaging.Threshold
import com.offlinetracer.vector.BezierFit
import com.offlinetracer.vector.ContourTrace
import com.offlinetracer.vector.Simplify
import com.offlinetracer.vector.SkeletonTrace
import com.offlinetracer.vector.SvgPathData
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * ============================================================================================
 * CROSS-ENGINE PARITY FIXTURES — the Kotlin half of ALGORITHMS.md §14
 * ============================================================================================
 *
 * The TypeScript mirror is `web/tests/parity.test.ts`, which documents the
 * `offline-tracer/parity-fixture@1` format and owns fixture **generation**. Both engines read the
 * *same* files from `docs/fixtures/`, so neither side hardcodes an expected number and a
 * disagreement is always a disagreement about the maths rather than about a stale copy of it.
 *
 * Three rules keep that guarantee honest, and each of them exists because the obvious alternative
 * produces a harness that reports green forever:
 *
 *  - **Nothing here writes a fixture.** Generation belongs to one engine only; if both could
 *    generate, the first engine to run would silently define the answer for the second.
 *  - **A missing or empty fixture directory fails.** Skipping is worse than having no harness at
 *    all, because a skip is reported as success by every CI dashboard.
 *  - **Every stage id in a fixture must be recognised.** An id this file does not know how to
 *    compute is a stage that is no longer being compared, which is the same failure mode as a
 *    missing fixture wearing a different hat.
 *
 * Intermediates (`gray`, `blurred`, `mag`, `ink`, `closed`, `cleaned`, `skeleton`, `flow`, `dt`,
 * `biggest`, `simplified`, `fitted`) are computed once and reused exactly as the TypeScript side
 * reuses them. Recomputing one from different inputs would still produce numbers, and the test
 * would still pass or fail — it would just no longer be comparing the two engines.
 */
class ParityTest {

    // -----------------------------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------------------------

    /**
     * The harness cannot be trusted to report a disagreement unless it is first proven to have
     * found its inputs, so the existence of all three fixtures is asserted independently of
     * whether their numbers match.
     */
    @Test
    fun everyDocumentedFixtureIsPresentAndWellFormed() {
        val dir = fixtureDir()
        for (id in FIXTURE_IDS) {
            val fixture = load(dir, id)
            assertTrue(fixture.notes.isNotEmpty(), "$id: notes must describe what the image is for")
            assertTrue(fixture.tolerance > 0f, "$id: floatTolerance must be positive")
            assertTrue(fixture.stages.isNotEmpty(), "$id: a fixture with no stages compares nothing")
        }
    }

    @Test
    fun gradientBlobReproducesEveryStage() = runFixture("gradient-blob") { pipelineStages(it) }

    @Test
    fun stepEdgeReproducesEveryStage() = runFixture("step-edge") { edgeStages(it) }

    @Test
    fun singlePixelReproducesEveryStage() = runFixture("single-pixel") { singlePixelStages(it) }

    // -----------------------------------------------------------------------------------------
    // Stage builders — one call per stage, mirroring web/tests/parity.test.ts one for one
    // -----------------------------------------------------------------------------------------

    /** Whole-chain stages for a tonal image: preprocess, edges, cleanup, trace. */
    private fun pipelineStages(src: RgbaImage): Built {
        val out = Built()

        val gray = Color.toGray(src)
        out.gray("color.toGray", gray)
        out.gray("color.toGrayLinear", Color.toGrayLinear(src))

        val lab = Color.toLabPlanes(src)
        out.gray("color.lab.L", GrayF(src.width, src.height, lab[0]))

        val blurred = Convolve.gaussianBlur(gray, 1.2f)
        out.gray("convolve.gaussianBlur:1.2", blurred)
        out.gray("convolve.boxBlur:2", Convolve.boxBlur(gray, 2))

        // Gradients of the *blurred* image, and one magnitude reused by the median stage below.
        val grad = Convolve.gradients(blurred, GradientOp.SCHARR)
        val mag = grad.magnitude()
        out.gray("convolve.gradients.magnitude", mag)
        out.gray("convolve.laplacian", Convolve.laplacian(blurred))

        out.gray("denoise.bilateral:1.5,0.15", Denoise.bilateral(gray, 1.5f, 0.15f))
        out.gray("denoise.median:2", Denoise.median(gray, 2))
        out.gray("contrast.clahe:4,4,2", Contrast.clahe(gray, 4, 4, 2f))
        out.gray("contrast.equalize", Contrast.equalize(gray))
        out.gray("contrast.percentileStretch:1", Contrast.percentileStretch(gray, 1f))

        out.ints("contrast.histogram:16", Contrast.histogram(gray, 16))

        val otsu = Threshold.otsu(gray)
        out.scalar("threshold.otsu", otsu)
        out.scalar("threshold.otsuSeparability", Threshold.otsuSeparability(gray))
        out.scalar("threshold.median.magnitude", Threshold.median(mag))

        val ink = Threshold.fixed(gray, otsu, true)
        out.mask("threshold.fixed.inverted", ink)
        out.mask("threshold.sauvola:4,0.2", Threshold.sauvola(gray, 4, 0.2f, true))
        out.mask("threshold.adaptiveMean:4,0.02", Threshold.adaptiveMean(gray, 4, 0.02f, true))

        out.gray("edgeDog.xdog:1,1.6,0.98,0.5,20", EdgeDog.xdog(gray, 1f, 1.6f, 0.98f, 0.5f, 20f))
        out.gray("edgeLog.logResponse:1.4", EdgeLog.logResponse(gray, 1.4f))
        out.mask("edgeCanny.detect:1.2,0.08,0.2", EdgeCanny.detect(gray, 1.2f, 0.08f, 0.2f))
        out.mask("edgeCanny.detectAuto:1.2,0.33", EdgeCanny.detectAuto(gray, 1.2f, 0.33f))
        out.mask("edgeLog.detect:1.4,0.02", EdgeLog.detect(gray, 1.4f, 0.02f))

        val flow = EdgeFlow.refineEtf(EdgeFlow.structureTensorFlow(gray, 2f), 2, 3)
        out.gray("edgeFlow.tangent.x", GrayF(src.width, src.height, flow.tx))
        out.gray("edgeFlow.fdog", EdgeFlow.fdog(gray, flow, 1f, 3f, 0.99f, 2, 0.5f, 20f))

        val closed = Morphology.close(ink, 1, SeShape.ELLIPSE)
        out.mask("morphology.close:1", closed)
        out.mask("morphology.open:1", Morphology.open(ink, 1, SeShape.RECT))
        out.gray("morphology.dilateGray:1", Morphology.dilateGray(gray, 1))

        val cleaned = Components.removeSmallBlobs(closed, 3)
        out.mask("components.removeSmallBlobs:3", cleaned)
        val labels = Components.label(cleaned, 8)
        out.ints("components.label.count", intArrayOf(labels.count))
        out.ints("components.label.area", labels.area)
        out.ints("components.label.bounds", labels.bounds)

        val skeleton = Thinning.pruneSpurs(Thinning.zhangSuen(cleaned), 3)
        out.mask("thinning.zhangSuen+pruneSpurs:3", skeleton)
        out.mask("thinning.guoHall", Thinning.guoHall(cleaned))
        out.ints("thinning.endpoints", Thinning.endpoints(skeleton))
        out.ints("thinning.junctions", Thinning.junctions(skeleton))
        out.mask("thinning.bridgeEndpoints:6,60", Thinning.bridgeEndpoints(skeleton, 6, 60f))

        val dt = Distance.euclidean(cleaned, true)
        out.gray("distance.euclidean", dt)

        // Kotlin overloads `resize` on the buffer type where TypeScript spells the two resamplers
        // `resizeGray` and `resizeMask`; the Kotlin `resizeGray` is private and is not this.
        out.gray("resample.down:12x9", Resample.resize(gray, 12, 9))
        out.gray("resample.up:32x24", Resample.resize(gray, 32, 24))
        out.mask("resample.mask:12x9", Resample.resize(cleaned, 12, 9))

        // Geometry: point counts first, then the actual coordinates of the largest contour, so a
        // mismatch says whether the tracer found different *shapes* or merely different *points*.
        val contours = ContourTrace.trace(cleaned)
        out.ints("contourTrace.pointCounts", IntArray(contours.size) { contours[it].points.size })
        out.ints("contourTrace.isHole", IntArray(contours.size) { if (contours[it].isHole) 1 else 0 })
        // Strictly greater, so the first contour of maximal length wins — the same tie-break the
        // TypeScript `reduce` makes, and the choice decides which coordinates get compared.
        var biggest: List<VecPoint> = emptyList()
        for (c in contours) if (c.points.size > biggest.size) biggest = c.points
        out.ints("contourTrace.largest.xy", flattenPoints(biggest))

        val lines = SkeletonTrace.trace(skeleton)
        out.ints("skeletonTrace.pointCounts", IntArray(lines.size) { lines[it].points.size })
        out.ints("skeletonTrace.closed", IntArray(lines.size) { if (lines[it].closed) 1 else 0 })

        // `traceWithWidths` returns a Pair here and a named record in TypeScript; only the widths
        // half is recorded, flattened into one row whose length is the total across all paths. The
        // max(1, ...) guard is load-bearing: GrayF rejects a zero-width buffer.
        val widths = SkeletonTrace.traceWithWidths(skeleton, dt).second
        var total = 0
        for (a in widths) total += a.size
        val flat = FloatArray(max(1, total))
        var at = 0
        for (a in widths) {
            System.arraycopy(a, 0, flat, at, a.size)
            at += a.size
        }
        out.gray("skeletonTrace.widths", GrayF(max(1, total), 1, flat))

        // The vector chain, ending in the string a real export would contain.
        val simplified = Simplify.douglasPeucker(Simplify.removeCollinear(biggest, 0.05f), 1f)
        out.ints("simplify.pointCount", intArrayOf(simplified.size))
        val fitted = BezierFit.fitPath(simplified, 1.6f, true, 100f)
        out.text("bezierFit.toD", SvgPathData.toD(fitted, 2))
        out.scalar("bezierFit.length", fitted.length())
        out.text("svgPathData.roundTrip", roundTripD(fitted))

        return out
    }

    /** Stages for a pure step edge, where the edge engines have an analytic answer to agree on. */
    private fun edgeStages(src: RgbaImage): Built {
        val out = Built()
        val gray = Color.toGray(src)
        out.gray("color.toGray", gray)
        out.mask("edgeCanny.detect:0,0.05,0.2", EdgeCanny.detect(gray, 0f, 0.05f, 0.2f))
        out.mask("edgeCanny.detect:1.2,0.05,0.2", EdgeCanny.detect(gray, 1.2f, 0.05f, 0.2f))
        out.gray("edgeCanny.nms", EdgeCanny.nonMaximumSuppression(Convolve.gradients(gray)))
        out.gray("edgeDog.dog:1,1.6,0.98", EdgeDog.dog(gray, 1f, 1.6f, 0.98f))
        out.gray("edgeDog.xdog:1.4,1.6,0.98,0.5,50", EdgeDog.xdog(gray, 1.4f, 1.6f, 0.98f, 0.5f, 50f))
        out.mask("edgeLog.detect:1,0.001", EdgeLog.detect(gray, 1f, 0.001f))
        val flow = EdgeFlow.structureTensorFlow(gray, 1.5f)
        out.gray("edgeFlow.tangent.y", GrayF(src.width, src.height, flow.ty))
        out.gray("edgeFlow.magnitude", GrayF(src.width, src.height, flow.magnitude))
        // The TypeScript mirror overrides exactly these three knobs and defaults the rest, so the
        // remaining six must come from the FlowParams defaults rather than be restated here.
        out.gray(
            "edgeFlow.coherentLineDrawing",
            EdgeFlow.coherentLineDrawing(
                gray,
                EdgeFlow.FlowParams(etfIterations = 1, etfRadius = 3, fdogIterations = 2),
            ),
        )
        return out
    }

    /** Every stage against a 1x1 image: the degenerate case both engines must survive identically. */
    private fun singlePixelStages(src: RgbaImage): Built {
        val out = Built()
        val gray = Color.toGray(src)
        val ink = Threshold.fixed(gray, 0.5f, true)
        out.gray("color.toGray", gray)
        out.gray("convolve.gaussianBlur:2", Convolve.gaussianBlur(gray, 2f))
        out.gray("convolve.gradients.magnitude", Convolve.gradients(gray).magnitude())
        out.scalar("threshold.otsu", Threshold.otsu(gray))
        out.scalar("threshold.median", Threshold.median(gray))
        out.mask("threshold.fixed.inverted", ink)
        out.gray("contrast.clahe", Contrast.clahe(gray, 8, 8, 2f))
        out.gray("denoise.bilateral", Denoise.bilateral(gray, 2f, 0.2f))
        out.mask("morphology.close:2", Morphology.close(ink, 2))
        out.mask("thinning.zhangSuen", Thinning.zhangSuen(ink))
        out.gray("distance.euclidean", Distance.euclidean(ink))
        out.ints("components.label.count", intArrayOf(Components.label(ink).count))
        val contours = ContourTrace.trace(ink)
        out.ints("contourTrace.pointCounts", IntArray(contours.size) { contours[it].points.size })
        val lines = SkeletonTrace.trace(ink)
        out.ints("skeletonTrace.pointCounts", IntArray(lines.size) { lines[it].points.size })
        out.gray("resample.up:3x3", Resample.resize(gray, 3, 3))
        return out
    }

    private fun flattenPoints(pts: List<VecPoint>): IntArray {
        val out = IntArray(pts.size * 2)
        for (i in pts.indices) {
            out[i * 2] = pts[i].x.roundToInt()
            out[i * 2 + 1] = pts[i].y.roundToInt()
        }
        return out
    }

    /** `toD -> parse -> toD` must be byte-identical, which is what makes a saved project reloadable. */
    private fun roundTripD(path: VecPath): String {
        val back = SvgPathData.parse(SvgPathData.toD(path, 2))
        return if (back.isEmpty()) "" else SvgPathData.toD(back[0], 2)
    }

    // -----------------------------------------------------------------------------------------
    // Comparison
    // -----------------------------------------------------------------------------------------

    private fun runFixture(id: String, build: (RgbaImage) -> Built) {
        val fixture = load(fixtureDir(), id)
        // Built from the *stored* image, never from a freshly synthesised one: the fixture is a
        // self-contained input plus expectations, so the generator can change without changing what
        // is under test.
        val computed = build(fixture.image).byId

        // An id the harness cannot compute is named on its own rather than buried in a list diff,
        // because that failure means a stage has silently stopped being compared.
        val unknown = fixture.stages.map { stageId(id, it) }.filter { !computed.containsKey(it) }
        if (unknown.isNotEmpty()) {
            fail("$id: fixture stage(s) ${unknown.joinToString(", ")} are not computed by ParityTest")
        }
        assertEquals(
            fixture.stages.map { stageId(id, it) },
            computed.keys.toList(),
            "$id: stage list (order and membership must match the TypeScript mirror)",
        )

        val tol = if (fixture.tolerance > 0f) fixture.tolerance else FALLBACK_TOLERANCE
        for (stage in fixture.stages) {
            val sid = stageId(id, stage)
            compare("$id/$sid", stage, computed.getValue(sid), tol)
        }
    }

    private fun compare(where: String, want: JsonObject, got: Actual, tol: Float) {
        val type = want.need("type", where).jsonPrimitive.content
        when (got) {
            is Actual.Gray -> {
                assertEquals("grayf", type, "$where: fixture type")
                val g = got.g
                assertEquals(want.need("width", where).jsonPrimitive.int, g.width, "$where: width")
                assertEquals(want.need("height", where).jsonPrimitive.int, g.height, "$where: height")
                val data = want.need("data", where).jsonArray
                assertEquals(data.size, g.size, "$where: data length")
                var worst = 0f
                var worstAt = -1
                for (k in 0 until data.size) {
                    val e = abs(g.data[k] - data[k].jsonPrimitive.float)
                    if (e > worst) {
                        worst = e
                        worstAt = k
                    }
                }
                if (worst > tol) {
                    val expected = data[worstAt].jsonPrimitive.float
                    fail(
                        "$where: max abs error $worst > tolerance $tol, first at index $worstAt " +
                            "(x=${worstAt % g.width}, y=${worstAt / g.width}): " +
                            "kotlin ${g.data[worstAt]} vs fixture $expected",
                    )
                }
            }
            is Actual.Bin -> {
                assertEquals("mask", type, "$where: fixture type")
                val m = got.m
                assertEquals(want.need("width", where).jsonPrimitive.int, m.width, "$where: width")
                assertEquals(want.need("height", where).jsonPrimitive.int, m.height, "$where: height")
                // Decoded and compared pixel by pixel rather than re-encoded and diffed as two run
                // lists: a one-pixel disagreement shifts every run after it, so a run diff reports
                // the whole tail as wrong and says nothing about where the mask actually differs.
                val expected = decodeRuns(where, want.need("runs", where).jsonArray, m.size)
                for (k in expected.indices) {
                    if (expected[k] != m.data[k]) {
                        fail(
                            "$where: first difference at index $k " +
                                "(x=${k % m.width}, y=${k / m.width}): " +
                                "kotlin ${m.data[k]} vs fixture ${expected[k]}",
                        )
                    }
                }
            }
            is Actual.Scalar -> {
                assertEquals("scalar", type, "$where: fixture type")
                val expected = want.need("value", where).jsonPrimitive.float
                if (abs(got.v - expected) > tol) {
                    fail("$where: kotlin ${got.v} vs fixture $expected exceeds tolerance $tol")
                }
            }
            is Actual.Ints -> {
                assertEquals("ints", type, "$where: fixture type")
                val data = want.need("data", where).jsonArray
                assertEquals(data.size, got.d.size, "$where: data length")
                for (k in got.d.indices) {
                    val expected = data[k].jsonPrimitive.int
                    if (expected != got.d[k]) {
                        fail("$where: first difference at index $k: kotlin ${got.d[k]} vs fixture $expected")
                    }
                }
            }
            is Actual.Text -> {
                assertEquals("string", type, "$where: fixture type")
                assertEquals(want.need("value", where).jsonPrimitive.content, got.s, "$where: value")
            }
        }
    }

    /**
     * Expands a run-length encoded mask. The encoding **always opens with a background run**, so
     * `[0, 5, 3]` is "no background, five foreground, three background"; dropping that leading zero
     * inverts every mask in the file.
     */
    private fun decodeRuns(where: String, runs: JsonArray, cells: Int): BooleanArray {
        val out = BooleanArray(cells)
        var at = 0
        var value = false
        for (r in runs) {
            val n = r.jsonPrimitive.int
            if (n < 0) fail("$where: negative run length $n")
            if (at + n > cells) fail("$where: runs cover more than $cells pixels")
            if (value) for (k in 0 until n) out[at + k] = true
            at += n
            value = !value
        }
        if (at != cells) fail("$where: runs cover $at of $cells pixels")
        return out
    }

    // -----------------------------------------------------------------------------------------
    // Fixture loading — read-only, and loud about anything it cannot read
    // -----------------------------------------------------------------------------------------

    private class Fixture(
        val notes: String,
        val tolerance: Float,
        val image: RgbaImage,
        val stages: List<JsonObject>,
    )

    /**
     * Locates `docs/fixtures` by walking up from the working directory.
     *
     * Gradle runs tests with the working directory set to the module directory, which makes
     * `../../docs/fixtures` correct today. Hardcoding that depth would break the moment the module
     * moves, and it would break as "no fixtures found" rather than as "wrong path" — so the search
     * is bounded and reports the absolute directory it started from.
     */
    private fun fixtureDir(): File {
        val from = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var here: File? = from
        var levels = 0
        while (here != null && levels <= MAX_WALK_UP) {
            val candidate = File(here, "docs/fixtures")
            if (candidate.isDirectory) {
                val found = candidate.listFiles()
                if (found == null || found.none { it.isFile && it.name.endsWith(".json") }) {
                    fail(
                        "${candidate.absolutePath} contains no fixtures. Generation belongs to the " +
                            "TypeScript side (web/tests/parity.test.ts); run it and commit the JSON.",
                    )
                }
                return candidate
            }
            here = here.parentFile
            levels++
        }
        fail(
            "no docs/fixtures directory within $MAX_WALK_UP levels above ${from.absolutePath}; " +
                "the Kotlin parity harness never creates one, because fixtures are generated by " +
                "web/tests/parity.test.ts and reviewed as a diff",
        )
    }

    private fun load(dir: File, id: String): Fixture {
        val file = File(dir, "$id.json")
        if (!file.isFile) {
            fail("${file.absolutePath} is missing; generate it from the TypeScript side, not from here")
        }
        val root: JsonElement = try {
            Json.parseToJsonElement(file.readText())
        } catch (e: Exception) {
            fail("${file.absolutePath} is not parseable JSON: ${e.message}")
        }
        val obj = root.jsonObject
        assertEquals(FORMAT, obj.need("format", id).jsonPrimitive.content, "$id: fixture format")
        assertEquals(id, obj.need("id", id).jsonPrimitive.content, "$id: fixture id must match the file name")

        val image = obj.need("image", id).jsonObject
        val w = image.need("width", id).jsonPrimitive.int
        val h = image.need("height", id).jsonPrimitive.int
        val pixels = image.need("pixels", id).jsonArray
        if (pixels.size != w * h) {
            fail("$id: image has ${pixels.size} pixels, expected ${w * h} for ${w}x$h")
        }
        val packed = IntArray(pixels.size)
        // JSON has no unsigned integers, so 0xFF6080A0 arrives as the decimal 4284514464 — larger
        // than Int.MAX_VALUE. Reading it as a Long and narrowing preserves the bit pattern; asking
        // for an Int directly overflows and every colour in the fixture changes.
        for (i in packed.indices) packed[i] = pixels[i].jsonPrimitive.long.toInt()

        val stages = obj.need("stages", id).jsonArray.map { it.jsonObject }
        return Fixture(
            notes = obj.need("notes", id).jsonPrimitive.content,
            tolerance = obj.need("floatTolerance", id).jsonPrimitive.float,
            image = RgbaImage(w, h, packed),
            stages = stages,
        )
    }

    private fun stageId(fixtureId: String, stage: JsonObject): String =
        stage.need("id", fixtureId).jsonPrimitive.content

    private fun JsonObject.need(key: String, where: String): JsonElement =
        this[key] ?: fail("$where: fixture object has no \"$key\" field")

    // -----------------------------------------------------------------------------------------
    // Computed stage values
    // -----------------------------------------------------------------------------------------

    /** The five comparable shapes, matching the fixture format's `type` discriminator. */
    private sealed interface Actual {
        class Gray(val g: GrayF) : Actual

        class Bin(val m: Mask) : Actual

        class Scalar(val v: Float) : Actual

        class Ints(val d: IntArray) : Actual

        class Text(val s: String) : Actual
    }

    /** Ordered id → value accumulator; insertion order is the pipeline order the fixture records. */
    private class Built {
        val byId = LinkedHashMap<String, Actual>()

        fun gray(id: String, g: GrayF) = add(id, Actual.Gray(g))

        fun mask(id: String, m: Mask) = add(id, Actual.Bin(m))

        fun scalar(id: String, v: Float) = add(id, Actual.Scalar(v))

        fun ints(id: String, d: IntArray) = add(id, Actual.Ints(d))

        fun text(id: String, s: String) = add(id, Actual.Text(s))

        private fun add(id: String, a: Actual) {
            // A duplicate would overwrite the earlier value and shorten the id list, which then
            // fails as a confusing list mismatch instead of as the typo it is.
            if (byId.put(id, a) != null) fail("duplicate stage id '$id' in ParityTest")
        }
    }

    private companion object {
        const val FORMAT = "offline-tracer/parity-fixture@1"

        /** Used only when a fixture records a non-positive tolerance, which it should never do. */
        const val FALLBACK_TOLERANCE = 1e-4f

        const val MAX_WALK_UP = 6

        val FIXTURE_IDS = listOf("single-pixel", "step-edge", "gradient-blob")
    }
}
