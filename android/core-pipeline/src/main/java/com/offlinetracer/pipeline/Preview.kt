package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The interactive preview: the same trace, at a smaller working size.
 *
 * The one rule this file exists to enforce is that **a preview is not a cheaper different pipeline**.
 * It calls [Pipeline.run] with the identical stage list, the identical engines and the identical
 * cleanup, changing only [PreprocessParams.workingLongEdge] and the knobs that are measured in working
 * pixels. Any shortcut — skipping the matte, skipping spur pruning, substituting a faster engine — would
 * make the preview lie, and a preview that lies is worse than no preview: the user tunes twenty sliders
 * against it and then discovers on export that half of them did something else.
 *
 * ### Why some parameters are rescaled and others are not
 *
 * Two kinds of number are measured in working pixels and they behave differently under a resolution
 * change:
 *
 *  - **Geometric** knobs are measured against *features in the image*: a 24 px blob, a 12 px gap, a
 *    3 px path, a 1.6 px stroke. Halve the resolution and the same feature is half as many pixels
 *    across, so these must be scaled or the preview drops things the final render keeps — and worse,
 *    the two disagree about *how many* things they dropped, which shows up as contradictory notes.
 *  - **Filter σ** knobs are measured against the *pixel grid*: a Gaussian of σ 1.2 removes structure at
 *    the sampling scale. The box-average downscale is itself a low-pass matched to the new grid, so
 *    scaling σ down as well would double-count the resampler's own filtering and hand the edge engine a
 *    noisier response than the final render ever sees. These are left alone.
 *
 * The distinction is worth stating because it is the whole reason a 720 px preview of a 6000 px photo
 * looks like the export rather than like a different drawing.
 */
object Preview {

    /** The long edge a preview runs at unless the caller says otherwise. */
    const val DEFAULT_LONG_EDGE = 720

    /**
     * Traces [src] at a reduced working resolution.
     *
     * Never enlarges the trace: if the requested [longEdge] is at or above what [Pipeline.run] would
     * have used anyway, the parameters are passed through untouched and the "preview" is the full
     * trace. That case matters — it is every small image — and returning a *rescaled* version of the
     * full parameters for it would make a thumbnail-sized source render differently from itself.
     *
     * Classification is off: [Classify][com.offlinetracer.imaging.Classify] answers from its own 512 px
     * proxy, so it would return the same profile for the same source and cost the same to compute on
     * every keystroke.
     *
     * @return a [TraceResult] whose document is in the same frame coordinates as the full render's, so
     *   a preview and an export can be overlaid.
     * @throws CancelledException if [cancel] was cancelled.
     */
    fun runPreview(
        src: RgbaImage,
        params: TraceParams,
        longEdge: Int = DEFAULT_LONG_EDGE,
        cancel: CancellationToken = CancellationToken(),
        progress: ProgressListener? = null,
    ): TraceResult {
        val p = params.sanitized()
        val sourceLongEdge = max(src.width, src.height)

        // What the full trace would run at: the working cap, but never an upscale of the source.
        val full = min(sourceLongEdge, p.preprocess.workingLongEdge)
        val requested = max(Limits.MIN_WORKING_EDGE, longEdge)
        val target = min(full, requested)

        if (target >= full) return Pipeline.run(src, p, progress, cancel, classify = false)

        val ratio = target.toFloat() / full.toFloat()
        return Pipeline.run(src, scaleToPreview(p, target, ratio), progress, cancel, classify = false)
    }

    /**
     * Rescales the geometric knobs of [params] for a trace running at [workingLongEdge], where [ratio]
     * is `previewEdge / fullEdge`.
     *
     * Public because the UI needs it too: a slider that reads "minimum blob 260 px" while the preview it
     * is driving used 32 is the same lie by a different route, so the caller can ask what the preview
     * actually ran with.
     *
     * The result is sanitised, so it is legal for any [ratio] including 0 and 1.
     */
    fun scaleToPreview(params: TraceParams, workingLongEdge: Int, ratio: Float): TraceParams {
        val r = if (!ratio.isFinite() || ratio <= 0f) 1f else if (ratio > 1f) 1f else ratio
        val p = params
        return p.copy(
            preprocess = p.preprocess.copy(
                workingLongEdge = workingLongEdge,
                // Dust is a feature of the image, so its radius scales; the median's *cost* is not the
                // reason it shrinks.
                medianRadius = length(p.preprocess.medianRadius, r),
            ),
            matte = p.matte.copy(feather = p.matte.feather * r),
            edge = p.edge.copy(
                // A local-threshold window is sized against stroke width, which is a feature.
                adaptiveRadius = length(p.edge.adaptiveRadius, r),
            ),
            cleanup = p.cleanup.copy(
                minBlobArea = area(p.cleanup.minBlobArea, r),
                closeRadius = length(p.cleanup.closeRadius, r),
                openRadius = length(p.cleanup.openRadius, r),
                maxGap = length(p.cleanup.maxGap, r),
                pruneSpurs = length(p.cleanup.pruneSpurs, r),
                fillHolesUpTo = area(p.cleanup.fillHolesUpTo, r),
            ),
            output = p.output.copy(
                simplify = p.output.simplify * r,
                fitError = p.output.fitError * r,
                // Scaling the stroke width is what makes the two renders agree on line weight. The
                // assemble stage multiplies it by frame/working, and working is smaller here by exactly
                // this ratio, so the two cancel and the preview's strokes land at the export's weight.
                strokeWidth = p.output.strokeWidth * r,
                minPathLength = p.output.minPathLength * r,
            ),
        ).sanitized()
    }

    /**
     * Scales a pixel radius, keeping a non-zero radius non-zero.
     *
     * `(1 * 0.35).roundToInt()` is 0, and a `closeRadius` that becomes 0 turns "join what nearly
     * touches" into "do nothing" — so the preview would report a different number of bridged gaps than
     * the export, which is exactly the disagreement this file exists to prevent. A radius the caller set
     * to 0 stays 0.
     */
    private fun length(v: Int, r: Float): Int {
        if (v <= 0) return v
        val scaled = (v * r).roundToInt()
        return if (scaled < 1) 1 else scaled
    }

    /** Scales a pixel *area* by `r²`, on the same keep-it-non-zero rule as [length]. */
    private fun area(v: Int, r: Float): Int {
        if (v <= 0) return v
        val scaled = (v * r * r).roundToInt()
        return if (scaled < 1) 1 else scaled
    }
}
