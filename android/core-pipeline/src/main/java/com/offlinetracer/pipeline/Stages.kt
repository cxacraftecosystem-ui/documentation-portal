package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import com.offlinetracer.imaging.Color
import com.offlinetracer.imaging.Components
import com.offlinetracer.imaging.Contrast
import com.offlinetracer.imaging.Denoise
import com.offlinetracer.imaging.Distance
import com.offlinetracer.imaging.EdgeCanny
import com.offlinetracer.imaging.EdgeDog
import com.offlinetracer.imaging.EdgeFlow
import com.offlinetracer.imaging.EdgeLog
import com.offlinetracer.imaging.EdgeModelRegistry
import com.offlinetracer.imaging.Geometry
import com.offlinetracer.imaging.GradientOp
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Matte
import com.offlinetracer.imaging.Morphology
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.imaging.SeShape
import com.offlinetracer.imaging.Thinning
import com.offlinetracer.imaging.Threshold
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.Mat2D
import com.offlinetracer.vector.StrokeStyle
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import com.offlinetracer.vector.VectorMode
import com.offlinetracer.vector.Vectorize
import com.offlinetracer.vector.VectorizeParams
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One entry in the pipeline's fixed stage list.
 *
 * @param id stable identifier, reported through [ProgressListener] and [StageResult]. Persisted in
 *   nothing, but a progress UI is built against it, so it does not change.
 * @param label the sentence-case name a human reads while the stage runs.
 * @param isTileLocal whether the stage's output at a pixel depends only on a bounded neighbourhood of
 *   that pixel. **This is not a performance annotation, it is a correctness one** (ALGORITHMS §13):
 *   only tile-local stages may be run per tile on an oversized image. CLAHE needs a global tile
 *   lattice, Otsu a global histogram, and connected components, thinning and contour tracing a global
 *   view of connectivity — run any of them per tile and the result has a visible seam at every tile
 *   boundary that no later stage can remove.
 */
data class Stage(val id: String, val label: String, val isTileLocal: Boolean)

/**
 * The pipeline's stage catalogue and the implementation of each stage.
 *
 * The order here **is** the pipeline. It is declared once, in one list, so that the progress bar, the
 * timing report and the code that actually runs cannot disagree about how many stages there are or
 * what they are called — a progress bar that reaches 100% two stages before the work finishes is a
 * bug that only ever shows up on somebody else's phone.
 */
object Stages {

    const val ORIENT = "orient"
    const val PERSPECTIVE = "perspective"
    const val DOWNSCALE = "downscale"
    const val GRAYSCALE = "grayscale"
    const val DENOISE = "denoise"
    const val CONTRAST = "contrast"
    const val MATTE = "matte"
    const val CROP = "crop"
    const val EDGE = "edge"
    const val BINARISE = "binarise"
    const val MORPHOLOGY = "morphology"
    const val BLOBS = "blobs"
    const val BRIDGE = "bridge"
    const val SKELETON = "skeleton"
    const val PRUNE = "prune"
    const val DISTANCE = "distance"
    const val VECTORISE = "vectorise"
    const val STYLE = "style"
    const val ASSEMBLE = "assemble"

    /** The nineteen stages, in execution order. */
    val ALL: List<Stage> = listOf(
        Stage(ORIENT, "Reading orientation", false),
        Stage(PERSPECTIVE, "Correcting perspective", false),
        Stage(DOWNSCALE, "Scaling to working size", false),
        // Tile-local and never actually tiled. `isTileLocal` says an operation *may* be run per tile,
        // not that it is: this one is pointwise, so its halo would be zero and the tiled and untiled
        // computations are the same arithmetic in the same order. Tiling it would buy a patch copy and
        // a write-back per tile for an identical answer.
        Stage(GRAYSCALE, "Converting to greyscale", true),
        Stage(DENOISE, "Removing noise", true),
        // CLAHE interpolates between a global lattice of per-tile histograms; per-image-tile it would
        // build a different lattice inside each tile and seam at every boundary.
        Stage(CONTRAST, "Enhancing local contrast", false),
        // Every matte is global by construction: a border flood is a whole-image connectivity
        // problem, spectral saliency is a whole-image FFT, and the fused subject matte's colour
        // posterior is built from the whole frame's border band against the whole of its interior.
        Stage(MATTE, "Separating the background", false),
        // Cropping to the subject is a whole-frame decision by construction — the box is the extent
        // of everything that is not the background, which no tile can see.
        Stage(CROP, "Cropping to the subject", false),
        // Canny's hysteresis floods across the whole image and FDoG integrates along streamlines that
        // are free to leave any tile they started in.
        Stage(EDGE, "Detecting edges", false),
        // Otsu needs the whole histogram.
        Stage(BINARISE, "Binarising", false),
        Stage(MORPHOLOGY, "Cleaning up", true),
        Stage(BLOBS, "Filtering specks", false),
        Stage(BRIDGE, "Bridging gaps", false),
        Stage(SKELETON, "Finding centrelines", false),
        Stage(PRUNE, "Pruning spurs", false),
        Stage(DISTANCE, "Measuring stroke width", false),
        Stage(VECTORISE, "Tracing paths", false),
        Stage(STYLE, "Styling strokes", false),
        Stage(ASSEMBLE, "Assembling the document", false),
    )

    private val index: Map<String, Stage> = LinkedHashMap<String, Stage>(ALL.size * 2).apply {
        for (s in ALL) put(s.id, s)
    }

    fun byId(id: String): Stage? = index[id]

    /**
     * [byId] for a caller naming a stage as a literal constant from this object.
     *
     * The stage list is fixed at compile time, so a miss here is a typo in a constant and not a
     * runtime condition worth a nullable return at every call site — but it is still worth failing on,
     * because the alternative (a synthesised `Stage(id, id, false)`) would silently mark a tile-local
     * stage as global and disable tiling rather than reporting anything.
     */
    fun required(id: String): Stage =
        index[id] ?: error("\"$id\" is not a stage in the catalogue; the stage constants and Stages.ALL disagree")

    /** The stage ids in execution order — what a progress UI enumerates. */
    fun ids(): List<String> {
        val out = ArrayList<String>(ALL.size)
        for (s in ALL) out.add(s.id)
        return out
    }

    /** The stages that may legally be run per tile on an oversized image. */
    fun tileLocal(): List<Stage> {
        val out = ArrayList<Stage>(ALL.size)
        for (s in ALL) if (s.isTileLocal) out.add(s)
        return out
    }

    /** The stages that must see the whole frame. */
    fun global(): List<Stage> {
        val out = ArrayList<Stage>(ALL.size)
        for (s in ALL) if (!s.isTileLocal) out.add(s)
        return out
    }
}

/**
 * What the edge stage produced.
 *
 * Three of the five classical engines ([EdgeEngine.CANNY], [EdgeEngine.LOG], [EdgeEngine.ADAPTIVE])
 * are *intrinsically* binary — they end in a hysteresis flood, a zero-crossing test or a local
 * threshold — while XDoG, FDoG and a learned model produce a continuous response. Rather than force
 * the binary three back through a threshold they have already applied (which would re-quantise a
 * decision and can only lose information), the stage carries both: [mask] when the engine already
 * decided, [response] always, so the preview and the binarise stage each read the one they need.
 *
 * @param response ink strength in 0..1 where **1 is ink**. Note the polarity flip against
 *   [EdgeDog.xdog], which returns ink *density* with 1 = paper; the flip happens once, here.
 * @param engine the engine that actually ran, which is not always the one that was requested.
 */
internal class EdgeOutcome(val response: GrayF, val mask: Mask?, val engine: EdgeEngine)

/**
 * The stage implementations.
 *
 * Every function is a plain transform of its arguments: nothing here reads shared state, nothing
 * mutates its input, and the only side effect permitted is appending a sentence to `notes`. That is
 * what lets [Pipeline] and [Preview] run the identical list in the identical order and lets a stage be
 * exercised in isolation from a test.
 *
 * Parameters are assumed **already sanitised** ([TraceParams.sanitized]). No function here re-checks a
 * range, on purpose: a stage that defends itself is a stage where the real clamping rule is invisible,
 * and two stages will eventually disagree about what "legal" means.
 */
internal object StageOps {

    /**
     * Above this long edge a tile-local stage is worth tiling (ALGORITHMS §13).
     *
     * Aliased rather than restated: [Pipeline] reads this to decide, the stages below read
     * [TilingPlan.decide] to partition, and the two agreeing is the whole point.
     */
    const val TILE_THRESHOLD = TilingPlan.TILE_THRESHOLD

    /** A matte removing more than this fraction of the frame is reported in stronger language. */
    private const val MATTE_ALARM_FRACTION = 0.60f

    /** The engine a requested-but-missing [EdgeEngine.MODEL] falls back to. */
    private val MODEL_FALLBACK = EdgeEngine.FDOG

    // -------------------------------------------------------------------------------------------
    // 1 · orient
    // -------------------------------------------------------------------------------------------

    /**
     * Honours the source's recorded orientation.
     *
     * This is a pass-through and is a stage anyway. By the time an [RgbaImage] exists the platform
     * decoder has already applied EXIF orientation — `BitmapFactory` on Android and `createImageBitmap`
     * on the web both do — so re-rotating here would rotate twice. The stage exists so that a host
     * which decodes raw pixels itself has one documented place to hook, and so the stage list does not
     * change shape depending on the platform.
     */
    @Suppress("UNUSED_PARAMETER")
    fun orient(src: RgbaImage, p: PreprocessParams): RgbaImage = src

    // -------------------------------------------------------------------------------------------
    // 2 · perspective
    // -------------------------------------------------------------------------------------------

    /**
     * Looks for a document quadrilateral and rectifies to it.
     *
     * A rectification changes the frame the geometry lives in, so the caller must scale the finished
     * vector against **this** result's dimensions rather than the original's; [Pipeline] does. When no
     * confident quad is found the frame is returned untouched and a note says so, because a silent
     * no-op on a control the user deliberately switched on is indistinguishable from a broken control.
     */
    fun perspective(src: RgbaImage, p: PreprocessParams, notes: MutableList<String>): RgbaImage {
        if (!p.perspectiveCorrect) return src
        val quad = Geometry.detectDocumentQuad(Color.toGray(src))
        if (quad == null || quad.size != 8) {
            notes.add(
                "Perspective correction was requested but no page-like quadrilateral was found, so " +
                    "the frame was left exactly as it came in."
            )
            return src
        }
        val ordered = Geometry.orderQuad(quad)
        // The output rectangle takes the longer of each pair of opposite edges. Averaging them
        // instead squashes a strongly tilted page, because the near edge is genuinely longer in the
        // photograph and the far edge is the one that lost detail.
        val topW = edgeLength(ordered, 0, 1)
        val bottomW = edgeLength(ordered, 3, 2)
        val leftH = edgeLength(ordered, 0, 3)
        val rightH = edgeLength(ordered, 1, 2)
        val outW = max(8, ceil(max(topW, bottomW).toDouble()).toInt())
        val outH = max(8, ceil(max(leftH, rightH).toDouble()).toInt())
        val dst = floatArrayOf(
            0f, 0f,
            (outW - 1).toFloat(), 0f,
            (outW - 1).toFloat(), (outH - 1).toFloat(),
            0f, (outH - 1).toFloat(),
        )
        val homography = Geometry.solveHomography(ordered, dst)
        val warped = Geometry.warpPerspective(src, homography, outW, outH)
        notes.add(
            "Perspective correction rectified the page from ${src.width}x${src.height} to " +
                "${outW}x$outH, so the exported coordinates are in the rectified frame rather than " +
                "in the original photograph."
        )
        return warped
    }

    private fun edgeLength(quad: FloatArray, a: Int, b: Int): Float {
        val dx = (quad[b * 2] - quad[a * 2]).toDouble()
        val dy = (quad[b * 2 + 1] - quad[a * 2 + 1]).toDouble()
        return sqrt(dx * dx + dy * dy).toFloat()
    }

    // -------------------------------------------------------------------------------------------
    // 3 · downscale
    // -------------------------------------------------------------------------------------------

    /**
     * Scales to the working long edge with a box average (never a point sample — ALGORITHMS §2).
     *
     * The downscale costs nothing in output resolution: the vector result is produced in working
     * coordinates and scaled back by `source/working` at assembly, so this trades processing time for
     * detail and never for export size. The note says so, because "why is my 12 MP photo being traced
     * at 2048 px" is otherwise a reasonable thing to be worried about.
     */
    fun downscale(src: RgbaImage, p: PreprocessParams, notes: MutableList<String>): RgbaImage {
        val fit = Resample.fitWithin(src.width, src.height, p.workingLongEdge)
        if (fit.size < 2 || (fit[0] == src.width && fit[1] == src.height)) return src
        notes.add(
            "The trace ran at ${fit[0]}x${fit[1]} instead of ${src.width}x${src.height} and the " +
                "vector result was scaled back up, so the export is at full size and nothing was " +
                "lost to the downscale."
        )
        return Resample.resize(src, fit[0], fit[1])
    }

    // -------------------------------------------------------------------------------------------
    // 4 · grayscale
    // -------------------------------------------------------------------------------------------

    /**
     * Rec.601 luma, then an optional inversion.
     *
     * Inversion is not cosmetic: every stage after this one treats **dark as ink**, so a white-on-black
     * source must be flipped here or the whole cleanup chain removes the artwork and keeps the
     * background.
     */
    fun grayscale(src: RgbaImage, p: PreprocessParams): GrayF {
        val gray = Color.toGray(src)
        return if (p.invertInput) Contrast.invert(gray) else gray
    }

    // -------------------------------------------------------------------------------------------
    // 5 · denoise
    // -------------------------------------------------------------------------------------------

    /** Spatial σ for the bilateral filter, in pixels. */
    private fun bilateralSpace(strength: Float): Float = 0.6f + 2.4f * strength

    /** Range σ for the bilateral filter, in 0..1 intensity units. */
    private fun bilateralRange(strength: Float): Float = 0.04f + 0.22f * strength

    private fun anisotropicIterations(strength: Float): Int = 2 + (strength * 12f).roundToInt()

    /** Perona–Malik κ: larger diffuses across stronger gradients, i.e. smooths harder. */
    private fun anisotropicKappa(strength: Float): Float = 0.03f + 0.17f * strength

    /** The widest neighbourhood the chosen filter reads, used to size the tile halo. */
    private fun denoiseRadius(p: PreprocessParams): Int = when (p.denoise) {
        DenoiseMode.NONE -> 0
        DenoiseMode.BILATERAL -> max(1, ceil(2.0 * bilateralSpace(p.denoiseStrength)).toInt())
        DenoiseMode.MEDIAN -> p.medianRadius
        // Diffusion propagates one pixel per iteration, so its effective support is its iteration
        // count. Sizing the halo by anything smaller puts a faint cross at every tile corner.
        DenoiseMode.ANISOTROPIC -> anisotropicIterations(p.denoiseStrength)
    }

    /**
     * Runs the selected noise filter, tiled when [tiled] is set.
     *
     * The three filters are offered because they fail differently (ALGORITHMS §4): bilateral is right
     * for sensor noise, median for dust and speckle on a scan, and Perona–Malik for a woven texture
     * that neither of the other two can flatten without also flattening the pattern on top of it.
     */
    fun denoise(
        gray: GrayF,
        p: PreprocessParams,
        tiled: Boolean,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): GrayF {
        // [tiled] is the caller's decision, taken once from the working size so that every tile-local
        // stage makes the same one. The *partition* is re-derived here from this stage's own dimensions:
        // TilingPlan.decide is a pure function of the working size and every tile-local stage runs at
        // the working size, so re-deriving it is the same decision rather than a second, independent
        // one. The flag is still honoured as a veto, so a caller can force a whole-frame trace.
        val plan = TilingPlan.decide(gray.width, gray.height)
        val useTiles = tiled && plan.tiled

        // Emitted before the "no denoising" early return, and from this stage rather than from the
        // orchestrator, because whether the trace was tiled is a property of the *trace*: with
        // denoising switched off the morphological close/open is still tiled, and a note that appeared
        // only when a noise filter happened to be enabled would be missing from exactly the traces
        // somebody is trying to explain a seam in. This is the first tile-local stage and it always
        // runs, which makes it the earliest point that both knows the plan and can write a note.
        if (useTiles) notes.add(TilingPlan.note(plan))

        val support = denoiseRadius(p)
        if (p.denoise == DenoiseMode.NONE || support <= 0) return gray
        if (!useTiles) return denoiseWhole(gray, p, cancel)

        // The tile operation runs concurrently, so it must be pure. It is: it reads only the immutable
        // params and allocates its own output. The token is deliberately not threaded in — throwing out
        // of a worker thread would abandon the other tiles mid-write.
        return TilingPlan.runGray(Stages.required(Stages.DENOISE), plan, support, gray) { patch ->
            denoiseWhole(patch, p, null)
        }
    }

    private fun denoiseWhole(gray: GrayF, p: PreprocessParams, cancel: CancellationToken?): GrayF =
        when (p.denoise) {
            DenoiseMode.NONE -> gray
            DenoiseMode.BILATERAL -> Denoise.bilateral(
                gray,
                bilateralSpace(p.denoiseStrength),
                bilateralRange(p.denoiseStrength),
            )
            DenoiseMode.MEDIAN -> Denoise.median(gray, p.medianRadius)
            DenoiseMode.ANISOTROPIC -> {
                // Run in short bursts so a fourteen-iteration diffusion is interruptible. The result
                // is bit-identical to one long call: each pass reads only the previous pass's output,
                // so resuming from an intermediate is the same computation.
                val total = anisotropicIterations(p.denoiseStrength)
                val kappa = anisotropicKappa(p.denoiseStrength)
                var cur = gray
                var done = 0
                while (done < total) {
                    cancel?.throwIfCancelled()
                    val step = if (total - done > 2) 2 else total - done
                    cur = Denoise.anisotropicDiffusion(cur, step, kappa)
                    done += step
                }
                cur
            }
        }

    // -------------------------------------------------------------------------------------------
    // 6 · contrast
    // -------------------------------------------------------------------------------------------

    /**
     * CLAHE, then tone, then sharpening — in that order and for that reason.
     *
     * CLAHE first because local contrast is what makes a faint pencil line survive the same threshold
     * as a bold ink one, and it must see the original tonal distribution rather than one a gamma curve
     * has already redistributed. Sharpening last because unsharp masking amplifies whatever contrast
     * exists at the moment it runs, so running it before CLAHE amplifies noise that CLAHE would then
     * amplify again.
     */
    fun contrast(gray: GrayF, p: PreprocessParams, cancel: CancellationToken): GrayF {
        var g = gray
        if (p.claheEnabled) g = Contrast.clahe(g, p.claheTiles, p.claheTiles, p.claheClip)
        cancel.throwIfCancelled()
        if (p.gamma != 1f) g = Contrast.gamma(g, p.gamma)
        if (p.brightness != 0f || p.contrast != 0f) {
            g = Contrast.brightnessContrast(g, p.brightness, p.contrast)
        }
        cancel.throwIfCancelled()
        if (p.unsharpAmount > 0f) g = Contrast.unsharpMask(g, p.unsharpSigma, p.unsharpAmount)
        return g
    }

    // -------------------------------------------------------------------------------------------
    // 7 · matte
    // -------------------------------------------------------------------------------------------

    /**
     * Applies the chosen background matte to the greyscale image, compositing over paper white.
     *
     * The matte is composited over **1.0** rather than over 0: everything downstream treats dark as
     * ink, so knocking the background out to black would replace a busy background with the largest
     * solid ink region in the image.
     *
     * How much of the frame the matte removed is always reported, and above 60% the sentence changes
     * to name it as a likely mistake. A wrong matte silently deleting half of somebody's artwork is
     * the worst failure this app can have (ALGORITHMS §8), and the difference between "the trace found
     * nothing" and "the matte deleted it" has to be visible without re-running anything.
     *
     * ### Why [MatteMode.SUBJECT] is the only mode that can decline
     *
     * The flood and the saliency cut return a bare alpha: there is no value in either of them that
     * says how much of the answer is evidence and how much is the cue's failure mode, so applying one
     * is unconditional by construction. [Matte.subjectMatte] returns its own verdict, and this stage
     * honours it — below [Matte.MIN_CONFIDENCE] the greyscale is handed back **untouched** and the
     * matte's own sentence goes into the notes. Falling through to no matte is the only safe
     * direction: the failure is then "the background is still there", which the user can see and fix,
     * rather than "most of the drawing is gone", which is indistinguishable from a trace that found
     * nothing.
     */
    fun matte(
        working: RgbaImage,
        gray: GrayF,
        p: MatteParams,
        notes: MutableList<String>,
    ): GrayF {
        if (p.mode == MatteMode.NONE) return gray
        val alpha = when (p.mode) {
            MatteMode.BORDER_FLOOD -> Matte.borderFlood(working, p.tolerance, p.feather)
            MatteMode.SALIENCY -> Matte.saliencyMatte(working, p.threshold, p.feather)
            MatteMode.SUBJECT -> {
                val found = Matte.subjectMatte(working, p.tolerance, p.feather)
                if (!found.confident) {
                    notes.add(
                        "The subject matte was not sure enough to remove the background, so the " +
                            "whole frame was traced with the background still in it. ${found.reason}"
                    )
                    return gray
                }
                found.alpha
            }
            MatteMode.NONE -> return gray
        }
        if (alpha.width != gray.width || alpha.height != gray.height) {
            notes.add(
                "The background matte produced a ${alpha.width}x${alpha.height} mask for a " +
                    "${gray.width}x${gray.height} image, so it was discarded and the whole frame was " +
                    "traced."
            )
            return gray
        }

        var kept = 0.0
        val ad = alpha.data
        for (i in ad.indices) kept += Px.clamp01(ad[i]).toDouble()
        val removed = if (ad.isEmpty()) 0f else (1.0 - kept / ad.size).toFloat()
        val name = when (p.mode) {
            MatteMode.BORDER_FLOOD -> "border-flood"
            MatteMode.SALIENCY -> "saliency"
            MatteMode.SUBJECT -> "subject"
            MatteMode.NONE -> "background"
        }
        notes.add(
            if (removed > MATTE_ALARM_FRACTION) {
                "The $name background matte removed ${percent(removed)}% of the frame — if part of " +
                    "your artwork is missing, that is where it went, so lower the matte tolerance or " +
                    "turn the matte off."
            } else {
                "The $name background matte removed ${percent(removed)}% of the frame."
            }
        )
        return Matte.applyMatte(gray, alpha, 1f)
    }

    // -------------------------------------------------------------------------------------------
    // 8 · crop to subject
    // -------------------------------------------------------------------------------------------

    /**
     * What the crop stage decided.
     *
     * @param gray the working greyscale every later stage runs on — the crop when one was taken,
     *   and the identical input object when one was not.
     * @param offsetX where [gray]'s origin sits inside the un-cropped working image. **Every
     *   coordinate produced downstream is relative to this**, and [assemble] is the one place that
     *   undoes it. Both are 0 when no crop was taken — which is not the same statement as "no crop
     *   was taken", since a subject in the top-left corner crops to an offset of exactly (0, 0); the
     *   size of [gray] against the working image is what distinguishes the two, and nothing here
     *   needs to.
     */
    internal class CropOutcome(val gray: GrayF, val offsetX: Int, val offsetY: Int)

    /**
     * Long edge of the proxy the subject box is measured on.
     *
     * [Subject.locate] mattes the frame it is handed, which is a Lab conversion and a flood over every
     * pixel of it. A crop box does not need pixel precision — it carries a 4% margin of its own — so
     * measuring it on a proxy costs a box that is a working pixel or two loose and saves matting a
     * 2048 px frame to decide where to cut it.
     */
    private const val CROP_PROXY_LONG_EDGE = 512

    /** No crop is taken that would leave a working image with an edge shorter than this. */
    private const val MIN_CROP_EDGE = 64

    /**
     * Crops the working image to the subject — **only** when the subject box is confident.
     *
     * ### What this buys, and what it costs
     *
     * The gain is not resolution: the crop is taken after the downscale, so the subject is traced at
     * the same pixels it would have been anyway and the working image gets *smaller*. The gain is
     * that every global statistic downstream stops being computed over the background. Otsu's
     * threshold, Canny's auto thresholds and the binarise stage's Otsu-times-sensitivity cut are all
     * read from the whole frame's histogram, and a photograph that is 70% empty backdrop puts 70% of
     * its mass in one lump that has nothing to do with the subject. Removing it moves the threshold
     * onto the thing being traced.
     *
     * (Cropping *before* the downscale would buy the resolution too, and is the natural next step;
     * it is not done here because the subject box is only trustworthy once the matte has run, and
     * the matte needs the working image. That ordering is a deliberate trade, not an oversight.)
     *
     * ### Why it refuses so often
     *
     * The decision is [Subject.locate]'s, not this stage's: it mattes the frame, bounds what the matte
     * kept, and returns `confident` only when **both** halves are trustworthy. A busy background, a
     * subject filling the frame and a matte that kept almost nothing are each a case where a box
     * exists, looks plausible and is wrong. A crop is the most destructive thing in this pipeline —
     * everything outside it is gone from the export with no way back short of re-tracing — so the bar
     * is deliberately higher than for any other automatic decision, and every refusal is reported in
     * [Subject]'s own words rather than paraphrased here.
     */
    fun cropToSubject(
        working: RgbaImage,
        gray: GrayF,
        p: TraceParams,
        notes: MutableList<String>,
    ): CropOutcome {
        val none = CropOutcome(gray, 0, 0)
        if (p.auto.mode != AutoMode.APPLY || !p.auto.allowCrop) return none
        // The matte stage returns its input untouched when it is disabled or when it failed, so the
        // sizes normally agree; a disagreement means the matte replaced the plane with one of a
        // different size, and cropping against a box measured on the other one would be worse than
        // not cropping at all.
        if (working.width != gray.width || working.height != gray.height) return none

        val proxy = Resample.scaleToLongEdge(working, CROP_PROXY_LONG_EDGE)
        val find = Subject.locate(proxy, p.matte.tolerance)
        if (!find.confident) {
            notes.add("The working image was not cropped to the subject. ${find.reason}")
            return none
        }

        val w = gray.width
        val h = gray.height
        val sx = w.toDouble() / proxy.width.toDouble()
        val sy = h.toDouble() / proxy.height.toDouble()
        // The box's far edge is exclusive (`x + w`), so it scales as itself; the near edge floors and
        // the far edge ceils, which can only ever make the crop a fraction of a proxy pixel *larger*.
        // Rounding both ends to nearest would let a box lose up to half a proxy pixel — several
        // working pixels — off the right and bottom of every crop, and losing subject is the one
        // direction this rounding is not allowed to err in.
        val x0 = Px.clamp(kotlin.math.floor(find.box.x * sx).toInt(), 0, w - 1)
        val y0 = Px.clamp(kotlin.math.floor(find.box.y * sy).toInt(), 0, h - 1)
        val x1 = Px.clamp(kotlin.math.ceil((find.box.x + find.box.w) * sx).toInt(), x0 + 1, w)
        val y1 = Px.clamp(kotlin.math.ceil((find.box.y + find.box.h) * sy).toInt(), y0 + 1, h)
        val cw = x1 - x0
        val ch = y1 - y0

        if (cw >= w && ch >= h) return none
        if (cw < MIN_CROP_EDGE || ch < MIN_CROP_EDGE) {
            notes.add(
                "The detected subject is only ${cw}x$ch px at the working size, which is too small " +
                    "to trace on its own, so the whole frame was traced instead."
            )
            return none
        }

        notes.add(
            "Cropped the working image to the detected subject: ${cw}x$ch px at ($x0, $y0) inside " +
                "the ${w}x$h frame, so the background no longer pulls the automatic thresholds. " +
                "The exported coordinates are still in the original frame."
        )
        return CropOutcome(Resample.crop(gray, x0, y0, cw, ch), x0, y0)
    }

    // -------------------------------------------------------------------------------------------
    // 9 · edge
    // -------------------------------------------------------------------------------------------

    /**
     * The one control that means the same thing in every engine is `sensitivity`, and this is how the
     * threshold-like knobs absorb it: `1` at the midpoint, `0` at full sensitivity, `2` at none.
     *
     * Every knob it multiplies is one where *larger means less ink* (a Sauvola `k`, an adaptive-mean
     * offset, a zero-crossing slope), so one factor covers all of them and the mapping is monotone in
     * the direction the label promises.
     */
    private fun suppressionFactor(sensitivity: Float): Float = 2f - 2f * sensitivity

    /** Canny's auto-threshold `s`, mapped so the default sensitivity reproduces the literature's 0.33. */
    private fun cannySensitivity(sensitivity: Float): Float = 0.08f + 0.50f * sensitivity

    /**
     * Runs the requested edge engine, or the nearest thing to it that is actually available.
     *
     * A requested [EdgeEngine.MODEL] that cannot run falls back to FDoG **and says so**. Every
     * fallback path here appends a note, including the one where a side-loaded model throws: a model
     * that fails and is quietly replaced looks exactly like a model that ran and disagreed with the
     * user's expectations, and the two need completely different responses.
     */
    fun edges(
        gray: GrayF,
        p: EdgeParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): EdgeOutcome {
        var engine = p.engine
        if (engine == EdgeEngine.MODEL) {
            val model = if (p.modelId.isEmpty()) null else EdgeModelRegistry.byId(p.modelId)
            when {
                model == null -> notes.add(
                    if (p.modelId.isEmpty()) {
                        "A learned edge model was selected but no model was chosen, so the " +
                            "flow-based FDoG engine ran instead."
                    } else {
                        "The edge model \"${p.modelId}\" is not installed on this device, so the " +
                            "flow-based FDoG engine ran instead."
                    }
                )
                !model.isAvailable -> notes.add(
                    "The edge model \"${model.displayName}\" is installed but not ready to run, so " +
                        "the flow-based FDoG engine ran instead."
                )
                else -> {
                    val inferred = try {
                        model.infer(gray)
                    } catch (t: Throwable) {
                        // A side-loaded model is third-party code running on an unknown ABI; the one
                        // thing that must not happen is the whole trace dying with it.
                        notes.add(
                            "The edge model \"${model.displayName}\" failed while running " +
                                "(${describe(t)}), so the flow-based FDoG engine ran instead."
                        )
                        null
                    }
                    if (inferred != null) {
                        if (inferred.width == gray.width && inferred.height == gray.height) {
                            return EdgeOutcome(clamp01(inferred), null, EdgeEngine.MODEL)
                        }
                        notes.add(
                            "The edge model \"${model.displayName}\" returned a " +
                                "${inferred.width}x${inferred.height} map for a " +
                                "${gray.width}x${gray.height} image, so its result was discarded and " +
                                "the flow-based FDoG engine ran instead."
                        )
                    }
                }
            }
            engine = MODEL_FALLBACK
        }

        return when (engine) {
            EdgeEngine.CANNY -> {
                val mask = if (p.cannyLow < 0f || p.cannyHigh < 0f) {
                    EdgeCanny.detectAuto(gray, p.blurSigma, cannySensitivity(p.sensitivity))
                } else {
                    EdgeCanny.detect(gray, p.blurSigma, p.cannyLow, p.cannyHigh, GradientOp.SCHARR)
                }
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.CANNY)
            }

            EdgeEngine.LOG -> {
                val slope = max(0f, p.logSlope * suppressionFactor(p.sensitivity))
                val mask = EdgeLog.detect(gray, p.logSigma, slope)
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.LOG)
            }

            EdgeEngine.ADAPTIVE -> {
                // No edge detector at all (ALGORITHMS §7.4). On material that is already line art a
                // gradient-based engine traces *both sides* of every stroke and doubles every line;
                // a local threshold recovers the strokes at their true width.
                val mask = if (p.useSauvola) {
                    Threshold.sauvola(gray, p.adaptiveRadius, sauvolaK(p.sensitivity), invert = true)
                } else {
                    val c = Px.clamp(p.adaptiveC * suppressionFactor(p.sensitivity), -1f, 1f)
                    Threshold.adaptiveMean(gray, p.adaptiveRadius, c, invert = true)
                }
                EdgeOutcome(mask.toGray(), mask, EdgeEngine.ADAPTIVE)
            }

            EdgeEngine.XDOG -> {
                val density = EdgeDog.xdog(
                    gray, p.dogSigma, p.dogK, p.dogTau, p.xdogEpsilon, p.xdogPhi,
                )
                EdgeOutcome(Contrast.invert(density), null, EdgeEngine.XDOG)
            }

            // MODEL is unreachable here (it was rewritten to the fallback above) but is listed rather
            // than thrown on, because an unreachable `error()` in a hot path is a crash waiting for
            // somebody to add a sixth engine and forget this branch.
            EdgeEngine.FDOG, EdgeEngine.MODEL -> {
                cancel.throwIfCancelled()
                val tensor = EdgeFlow.structureTensorFlow(gray, p.flow.tensorSigma)
                cancel.throwIfCancelled()
                val field = EdgeFlow.refineEtf(tensor, p.flow.etfIterations, p.flow.etfRadius)
                cancel.throwIfCancelled()
                val density = EdgeFlow.fdog(
                    gray, field,
                    p.flow.sigmaC, p.flow.sigmaM, p.flow.tau, p.flow.fdogIterations,
                    p.xdogEpsilon, p.xdogPhi,
                )
                EdgeOutcome(Contrast.invert(density), null, EdgeEngine.FDOG)
            }
        }
    }

    /**
     * Sauvola's `k` from sensitivity. `T = m(1 + k(σ/R − 1))`, so on flat paper `T = m(1 − k)`: a
     * larger `k` lowers the threshold and keeps less ink, which is why sensitivity runs it downwards.
     */
    private fun sauvolaK(sensitivity: Float): Float = 0.34f - 0.28f * sensitivity

    // -------------------------------------------------------------------------------------------
    // 10 · binarise
    // -------------------------------------------------------------------------------------------

    /**
     * Turns a continuous edge response into ink, or passes through a mask the engine already produced.
     *
     * The cut is Otsu's threshold **scaled by sensitivity** rather than sensitivity used directly as a
     * threshold. Otsu adapts to the response's actual distribution — an XDoG at φ=300 and a learned
     * model's probability map do not live on remotely the same scale — while the scale factor keeps the
     * slider monotone and predictable: 1.0× at the midpoint, 0.4× wide open, 1.6× closed down.
     *
     * Hysteresis rather than a flat threshold, seeded at `t` and grown down to `t/2`, because a faint
     * continuation of a strong stroke is a stroke and a faint mark on its own is noise. A flat
     * threshold cannot tell those apart at any setting.
     */
    fun binarise(edge: EdgeOutcome, p: EdgeParams): Mask {
        val direct = edge.mask
        if (direct != null) return direct
        val otsu = Threshold.otsu(edge.response)
        val t = Px.clamp(otsu * (1.6f - 1.2f * p.sensitivity), 0.01f, 0.99f)
        return Threshold.hysteresis(edge.response, t * 0.5f, t)
    }

    // -------------------------------------------------------------------------------------------
    // 11 · morphology
    // -------------------------------------------------------------------------------------------

    /**
     * Close, then open, then fill small holes.
     *
     * Close before open: closing joins strokes that nearly touch and opening removes what is left too
     * thin to be a stroke, so running them the other way round removes the speck *and* the near-touch
     * it would have joined. Ellipse structuring elements throughout, because a rectangle leaves
     * axis-aligned corners on every closed gap and those corners survive all the way into the vector.
     *
     * ### Only two thirds of this stage may be tiled
     *
     * The close/open pair is tile-local with a composite support of `2·(closeRadius + openRadius)`:
     * closing is a dilate then an erode and opening an erode then a dilate, so each of the four passes
     * reaches its own radius further back into the input. Both run inside a single tile pass rather than
     * two, so the halo is paid once.
     *
     * The hole fill does **not** go in the tile pass, whatever [Stage.isTileLocal] says about the stage
     * as a whole — the annotation describes the part tiling is applied to, and this is the part it is
     * not. `Components.fillHoles` labels the *background* and keeps the components under an area
     * threshold, which is connected-component work and global by construction (ALGORITHMS §13). Per
     * tile, a hole straddling a boundary is two smaller holes, each of which passes an area test the
     * whole hole would have failed — so it gets filled in a band down every tile edge and nowhere else.
     * That is the seam this stage would otherwise produce, and it is the reason the stage cannot simply
     * be handed to [TilingPlan.runMask] entire.
     */
    fun morphology(mask: Mask, p: CleanupParams, cancel: CancellationToken): Mask {
        var m = mask
        val support = 2 * (p.closeRadius + p.openRadius)
        if (support > 0) {
            val plan = TilingPlan.decide(mask.width, mask.height)
            m = if (plan.tiled) {
                // Pure, for the same reason the denoise tile op is, and likewise without the token.
                TilingPlan.runMask(Stages.required(Stages.MORPHOLOGY), plan, support, m) { patch ->
                    closeThenOpen(patch, p, null)
                }
            } else {
                closeThenOpen(m, p, cancel)
            }
        }
        cancel.throwIfCancelled()
        if (p.fillHolesUpTo > 0) m = Components.fillHoles(m, p.fillHolesUpTo)
        return m
    }

    private fun closeThenOpen(mask: Mask, p: CleanupParams, cancel: CancellationToken?): Mask {
        var m = mask
        if (p.closeRadius > 0) m = Morphology.close(m, p.closeRadius, SeShape.ELLIPSE)
        cancel?.throwIfCancelled()
        if (p.openRadius > 0) m = Morphology.open(m, p.openRadius, SeShape.ELLIPSE)
        return m
    }

    // -------------------------------------------------------------------------------------------
    // 12 · blob filter
    // -------------------------------------------------------------------------------------------

    /**
     * Drops isolated pixels, specks, frame-touching components and everything past the "keep N
     * largest" cap — **counting each one and reporting it**.
     *
     * The counting is why this function labels the mask itself instead of only calling the library
     * helpers. `Components.removeSmallBlobs` returns a mask, not a tally, and a pipeline that discards
     * four thousand components looks identical on screen to one that found nothing. That ambiguity is
     * the bug class this project takes most seriously, so the extra labelling pass is the price of
     * being able to say which of the two happened.
     */
    fun blobFilter(
        mask: Mask,
        p: CleanupParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): Mask {
        var m = mask
        if (p.removeIsolated) m = Components.removeIsolated(m, 1, 8)
        cancel.throwIfCancelled()

        if (p.minBlobArea > 1) {
            val labels = Components.label(m, 8)
            var small = 0
            for (l in 1..labels.count) if (labels.area[l] < p.minBlobArea) small++
            m = Components.removeSmallBlobs(m, p.minBlobArea)
            if (small > 0) {
                notes.add(
                    "Removed $small of ${labels.count} ${noun(labels.count, "region", "regions")} " +
                        "for being smaller than ${p.minBlobArea} pixels."
                )
            }
        }
        cancel.throwIfCancelled()

        if (p.removeBorderTouching) {
            val labels = Components.label(m, 8)
            var touching = 0
            val lastX = labels.width - 1
            val lastY = labels.height - 1
            for (l in 1..labels.count) {
                val b = l * 4
                if (labels.bounds[b] == 0 || labels.bounds[b + 1] == 0 ||
                    labels.bounds[b + 2] == lastX || labels.bounds[b + 3] == lastY
                ) {
                    touching++
                }
            }
            m = Components.removeBorderTouching(m)
            if (touching > 0) {
                notes.add(
                    "Discarded $touching ${noun(touching, "region", "regions")} touching the frame " +
                        "edge, which is usually the scan border rather than the artwork."
                )
            }
        }
        cancel.throwIfCancelled()

        if (p.keepLargest > 0) {
            val total = Components.label(m, 8).count
            val dropped = if (total > p.keepLargest) total - p.keepLargest else 0
            m = Components.keepLargest(m, p.keepLargest)
            if (dropped > 0) {
                notes.add(
                    "Kept only the ${p.keepLargest} largest " +
                        "${noun(p.keepLargest, "region", "regions")} and discarded $dropped."
                )
            }
        }
        return m
    }

    // -------------------------------------------------------------------------------------------
    // 13 · gap bridging
    // -------------------------------------------------------------------------------------------

    /**
     * Joins stroke ends across gaps that morphological closing cannot span.
     *
     * `Thinning.bridgeEndpoints` is defined on a skeleton, because a "stroke end" is a degree-1 pixel
     * and a full-width mask has none. So this stage thins a **scratch copy** purely to locate the ends,
     * computes the bridges there, and unions the bridge segments back into the full-width mask. Two
     * things fall out of doing it that way rather than after skeletonisation:
     *
     *  - outline mode benefits too, and it is the mode that needs it most — a colouring page leaks
     *    through a 2 px gap whether or not anything was ever thinned;
     *  - the distance transform, which is measured on this mask, sees the bridges as part of the
     *    stroke rather than as a separate hairline.
     *
     * A close of radius `r` spans about `2r`; this spans `maxGap` without fusing adjacent strokes,
     * which is the whole reason gap closing is two stages and not one (ALGORITHMS §9).
     */
    fun bridge(
        mask: Mask,
        p: CleanupParams,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): Mask {
        if (!p.bridgeGaps || p.maxGap <= 0) return mask
        val scratch = thin(mask, p)
        cancel.throwIfCancelled()
        val bridged = Thinning.bridgeEndpoints(scratch, p.maxGap, p.maxBridgeAngle)
        val added = bridged.subtract(scratch)
        val runs = Components.label(added, 8).count
        if (runs == 0) return mask
        notes.add(
            "Bridged $runs ${noun(runs, "gap", "gaps")} of up to ${p.maxGap} px between stroke ends."
        )
        return mask.or(added)
    }

    // -------------------------------------------------------------------------------------------
    // 14 · skeletonise · 15 · prune
    // -------------------------------------------------------------------------------------------

    /** Zhang–Suen or Guo–Hall, per [CleanupParams.thinning]. */
    fun thin(mask: Mask, p: CleanupParams): Mask =
        if (p.thinning == ThinningMode.GUO_HALL) Thinning.guoHall(mask) else Thinning.zhangSuen(mask)

    /**
     * Removes the hair that thinning grows.
     *
     * Thinning any slightly noisy blob produces short branches ending in a degree-1 pixel, and
     * unpruned hair becomes dozens of three-point paths in the SVG — which is worse than noise,
     * because each one is a real object the user now has to select and delete.
     */
    fun prune(mask: Mask, p: CleanupParams): Mask =
        if (p.pruneSpurs > 0) Thinning.pruneSpurs(mask, p.pruneSpurs) else mask

    // -------------------------------------------------------------------------------------------
    // 16 · distance transform
    // -------------------------------------------------------------------------------------------

    /**
     * Exact Euclidean distance from every ink pixel to the nearest background pixel.
     *
     * The caller must pass the **pre-skeleton** mask. The distance at a skeleton pixel is the original
     * stroke's half-width, which is the entire point of modulated width; measured on the skeleton
     * itself every value would be 1 and every stroke would come back the same weight.
     */
    fun distance(fullWidthMask: Mask): GrayF = Distance.euclidean(fullWidthMask, insideForeground = true)

    // -------------------------------------------------------------------------------------------
    // 17 · vectorise
    // -------------------------------------------------------------------------------------------

    /**
     * Traces the mask and drops the runt paths, **counting them**.
     *
     * Length filtering is done here rather than inside `Vectorize.run` — which can do it — because
     * `Vectorize` returns shapes and not a tally. Running it with no length filter and filtering
     * afterwards costs one extra `length()` per path and buys the exact number dropped, which the UI
     * is required to show. Four thousand discarded paths and an empty result are the same picture.
     *
     * `Vectorize.run` is one library call and cannot be interrupted from out here, so the token is
     * checked on either side of it and inside the filter loop.
     */
    fun vectorise(
        mask: Mask,
        o: OutputParams,
        dt: GrayF?,
        notes: MutableList<String>,
        cancel: CancellationToken,
    ): List<VecShape> {
        cancel.throwIfCancelled()
        val params = VectorizeParams(
            mode = if (o.vectorMode == VectorModeParam.OUTLINE) VectorMode.OUTLINE else VectorMode.CENTERLINE,
            simplifyEpsilon = o.simplify,
            fitError = o.fitError,
            cornerThresholdDegrees = o.corner,
            smoothIterations = o.smoothIterations,
            minPathLength = 0f,
            strokeWidth = o.strokeWidth,
            modulateWidth = o.modulateWidth && dt != null,
            widthScale = o.widthScale,
            minWidth = 0.4f,
            // The ceiling has to move with the nominal weight, or a 3.2 px tattoo outline would be
            // capped at the same 12 px as a 0.7 px engraving line and lose its widest strokes.
            maxWidth = max(12f, o.strokeWidth * 6f),
            // `fillClosed` is the user-facing name for the same decision Vectorize calls
            // `fillRegions`: is a traced region a solid mark, or an outline with a paper interior?
            // It has to reach the tracer and not just [style], because it also decides whether holes
            // are traced at all.
            fillRegions = o.fillClosed,
        )
        val traced = Vectorize.run(mask, params, dt)
        cancel.throwIfCancelled()
        if (o.minPathLength <= 0f || traced.isEmpty()) return traced

        val kept = ArrayList<VecShape>(traced.size)
        var i = 0
        for (shape in traced) {
            if ((i and 0xFF) == 0) cancel.throwIfCancelled()
            i++
            if (shape.path.length() >= o.minPathLength) kept.add(shape)
        }
        val dropped = traced.size - kept.size
        if (dropped > 0) {
            notes.add(
                "Dropped $dropped of ${traced.size} traced " +
                    "${noun(traced.size, "path", "paths")} for being shorter than " +
                    "${number(o.minPathLength)} px, keeping ${kept.size}."
            )
        }
        return kept
    }

    // -------------------------------------------------------------------------------------------
    // 18 · style
    // -------------------------------------------------------------------------------------------

    /**
     * Applies the user's colour, weight and fill.
     *
     * `Vectorize` deliberately returns everything stroked in plain black, because it is the one stage
     * that does not know the user's palette. Restyling in exactly one place is what stops a stroke
     * colour existing in two forms that drift apart.
     *
     * A width-modulated path becomes a **filled outline**: SVG has no variable-width stroke, so the
     * centreline is offset by ±w/2 and closed (ALGORITHMS §10.7). If the outline comes back empty —
     * a degenerate two-point path, a zero width — the uniform stroke is used instead, because a shape
     * that silently disappears is worse than one drawn at the wrong weight.
     */
    fun style(shapes: List<VecShape>, o: OutputParams): List<VecShape> {
        if (shapes.isEmpty()) return shapes
        val out = ArrayList<VecShape>(shapes.size)
        for (shape in shapes) {
            val widths = shape.path.strokeWidths
            if (o.modulateWidth && widths != null && widths.size >= 2) {
                val outline = StrokeStyle.variableWidthOutline(shape.path, widths, LineCap.ROUND)
                if (!outline.isEmpty()) {
                    out.add(
                        VecShape(
                            outline.copy(id = shape.path.id),
                            VecStyle(
                                stroke = null,
                                strokeWidth = o.strokeWidth,
                                fill = o.strokeColor,
                                // NONZERO, not EVENODD: a stroke ribbon that crosses itself at a tight
                                // hairpin would render with a hole through the overlap under EVENODD.
                                fillRule = FillRule.NONZERO,
                                cap = LineCap.ROUND,
                                join = LineJoin.ROUND,
                            ),
                        )
                    )
                    continue
                }
            }
            out.add(
                VecShape(
                    shape.path,
                    VecStyle(
                        stroke = o.strokeColor,
                        strokeWidth = o.strokeWidth,
                        fill = if (o.fillClosed && shape.path.closed) o.strokeColor else null,
                        fillRule = FillRule.EVENODD,
                        cap = LineCap.ROUND,
                        join = LineJoin.ROUND,
                    ),
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------------------------------------
    // 19 · assemble
    // -------------------------------------------------------------------------------------------

    /**
     * Wraps the shapes in a document and **maps working coordinates to frame coordinates**.
     *
     * This is the step that makes a vector export resolution-independent. Everything upstream ran at
     * the working size; without this map the SVG would describe a 2048 px drawing of a 6000 px
     * photograph, and every downstream consumer — the raster renderer, the PDF page box, the DXF units
     * — would inherit the mistake. `VecDocument.transform` carries the stroke widths through with the
     * geometric mean of the two scales, which is what keeps a hairline a hairline.
     *
     * ### The two transforms, and the order they compose in
     *
     * With a crop there are **two** changes of frame between a traced point and an exported one, not
     * one, and they do not commute:
     *
     * ```
     * p_frame = (p_cropped + cropOffset) · (frame / uncroppedWorking)
     * ```
     *
     * The offset is in *un-cropped working* pixels, so it must be added **before** the scale, not
     * after. `Mat2D.scale(s) * Mat2D.translate(o)` is exactly that composition — the right-hand
     * matrix applies first — and writing it the other way round puts every path off by the crop
     * offset times the downscale factor, which on a 6000 px photograph traced at 2048 is a three-fold
     * error in the *displacement* only: the drawing is the right size, in the wrong place, which is
     * the kind of wrong that looks like a rendering bug rather than a coordinate bug.
     *
     * The scale is `frame / uncroppedWorking` and **not** `frame / croppedWorking`. The crop removed
     * pixels; it did not resample the ones that remain, so the pixel pitch downstream is still the
     * pitch the downscale established. Dividing by the cropped size instead would stretch the subject
     * to fill the whole page — an error that grows with how much was cropped and is invisible on an
     * image that happened not to be cropped at all.
     *
     * @param workingWidth, workingHeight the **un-cropped** working size: the frame `offsetX` and
     *   `offsetY` are measured in and the size the downscale factor was derived from.
     * @param frameWidth, frameHeight the frame the geometry belongs to: the source image, or the
     *   rectified page when perspective correction ran.
     * @param offsetX, offsetY where the cropped working image's origin sits inside the un-cropped
     *   working image; both 0 when no crop was taken.
     */
    fun assemble(
        shapes: List<VecShape>,
        workingWidth: Int,
        workingHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
        o: OutputParams,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): VecDocument {
        val layer = VecLayer(id = "trace", name = "Trace", shapes = shapes)
        val working = VecDocument(
            workingWidth.toFloat(),
            workingHeight.toFloat(),
            listOf(layer),
            o.background,
        )
        val scaled = workingWidth != frameWidth || workingHeight != frameHeight
        if (!scaled && offsetX == 0 && offsetY == 0) return working

        val sx = frameWidth.toFloat() / workingWidth.toFloat()
        val sy = frameHeight.toFloat() / workingHeight.toFloat()
        val map = Mat2D.scale(sx, sy) * Mat2D.translate(offsetX.toFloat(), offsetY.toFloat())
        val out = working.transform(map)
        // The canvas is restated exactly instead of being left as `working * scale`: the scale is a
        // float division, and a document that comes out 5999.9995 px wide crops a column off every
        // raster export and rounds down in every page box. It is restated even when the transform was
        // a pure translation, because `transform` scales the declared canvas too and a crop must not
        // shrink the page the drawing is exported onto.
        return out.copy(width = frameWidth.toFloat(), height = frameHeight.toFloat())
    }

    // -------------------------------------------------------------------------------------------
    // Small shared helpers
    // -------------------------------------------------------------------------------------------

    /** Clamp a model's output into the 0..1 its contract promises but cannot be trusted to honour. */
    private fun clamp01(src: GrayF): GrayF {
        val d = src.data
        var needs = false
        for (v in d) {
            if (v < 0f || v > 1f || v.isNaN()) {
                needs = true
                break
            }
        }
        if (!needs) return src
        val out = FloatArray(d.size)
        for (i in d.indices) out[i] = if (d[i].isNaN()) 0f else Px.clamp01(d[i])
        return GrayF(src.width, src.height, out)
    }

    /** Singular or plural, so a note never reads "Removed 1 regions". */
    private fun noun(n: Int, one: String, many: String): String = if (n == 1) one else many

    /**
     * One decimal place, locale-independently.
     *
     * Deliberately not `String.format`: that is locale-dependent, and a note that reads "3,5 px" on
     * one continent and "3.5 px" on another is a difference that only ever surfaces in a bug report
     * nobody can reproduce.
     */
    private fun number(v: Float): String {
        if (!v.isFinite() || v < 0f) return "0"
        val tenths = (v * 10f).roundToInt()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) whole.toString() else "$whole.$frac"
    }

    private fun percent(fraction: Float): Int =
        Px.clamp((if (fraction.isNaN()) 0f else fraction * 100f).roundToInt(), 0, 100)

    /** A throwable's class and message, short enough to sit inside a sentence. */
    private fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "error"
        val message = t.message
        return if (message.isNullOrBlank()) name else "$name: ${message.trim()}"
    }
}
