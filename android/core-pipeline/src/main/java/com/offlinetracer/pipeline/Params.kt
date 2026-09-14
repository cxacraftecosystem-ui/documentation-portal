package com.offlinetracer.pipeline

import kotlinx.serialization.Serializable

/**
 * Every knob the engine exposes, as one serialisable tree, plus the single clamping choke point the
 * whole module depends on.
 *
 * Parameters arrive from three sources that cannot be trusted to agree with each other:
 *
 *  1. a project saved by an **older build**, whose JSON may be missing fields entirely or may carry
 *     a value that was legal then and is not now;
 *  2. a **state restore** (process death, a shared URL, an undo entry) that was never validated;
 *  3. a **slider mid-drag**, which streams every intermediate value including the ones at the ends
 *     of its travel.
 *
 * [TraceParams.sanitized] clamps all of it into the legal range in one place. That is why no stage
 * in this module defends itself: a stage that re-checks its own inputs is a stage where the *real*
 * clamping rule is invisible, and two stages will eventually disagree about what "legal" means.
 * The pipeline sanitises once on entry and every stage after that reads the values as given.
 */

/** Which edge engine runs. `MODEL` requires a side-loaded [com.offlinetracer.imaging.EdgeModel]. */
@Serializable
enum class EdgeEngine { CANNY, XDOG, FDOG, ADAPTIVE, LOG, MODEL }

/** Which noise filter runs before contrast enhancement. They fail differently, so all four exist. */
@Serializable
enum class DenoiseMode { NONE, BILATERAL, MEDIAN, ANISOTROPIC }

/**
 * Which background matte is offered. Never applied without the user having chosen it.
 *
 * [SUBJECT] is the one that can refuse. The other two always hand back an alpha — a bare flood or a
 * bare saliency cut has no way to say "I do not believe this", so a caller applying one is acting on
 * a measurement with no error bar. [SUBJECT] fuses the flood with two further cues and returns a
 * confidence with it ([com.offlinetracer.imaging.Matte.MatteResult]), and the matte stage applies it
 * **only** when that confidence clears [com.offlinetracer.imaging.Matte.MIN_CONFIDENCE]. That is why
 * it is the mode auto-detection and the object subjects choose: it is the only one whose failure mode
 * is "the background stayed" rather than "the artwork went".
 *
 * Appended rather than inserted: the enum is serialised **by name** by kotlinx, and a project saved
 * before this entry existed still decodes, but a reader that switched to ordinals would silently
 * reinterpret every stored mode.
 */
@Serializable
enum class MatteMode { NONE, BORDER_FLOOD, SALIENCY, SUBJECT }

/**
 * What auto-detection is allowed to do with what it found.
 *
 * Three states rather than a boolean, because "off" and "look but do not touch" are genuinely
 * different products and a user who has been surprised once will want the middle one.
 *
 * - [OFF] does not even classify. Nothing is measured and [TraceResult.profile] is null.
 * - [SUGGEST] classifies and writes what it *would* have changed into [TraceResult.notes], changing
 *   nothing. This is ALGORITHMS §12's original contract — a named suggestion with a one-tap
 *   override — and it is what a user who disagreed with an automatic choice should be able to fall
 *   back to without losing the classification altogether.
 * - [APPLY] applies the detected subject, subject to every guard in [Auto].
 */
@Serializable
enum class AutoMode { OFF, SUGGEST, APPLY }

/** Which skeletonisation kernel runs. Guo–Hall keeps diagonals better and grows fewer spurs. */
@Serializable
enum class ThinningMode { ZHANG_SUEN, GUO_HALL }

/**
 * Mirror of [com.offlinetracer.vector.Vectorize.VectorMode] that lives in the serialisable tree.
 *
 * It is a separate enum on purpose: `:core-vector` is not a serialisation module, and making the
 * on-disk format depend on an enum declared outside this module would mean a rename over there
 * silently invalidates every saved project over here.
 */
@Serializable
enum class VectorModeParam { CENTERLINE, OUTLINE }

/**
 * Named bounds and the two clamp primitives, kept in one object so a limit is stated exactly once.
 *
 * Scoped inside an object rather than left at file scope because private *top-level* declarations
 * still occupy the package namespace in Kotlin, and `f`/`i` are names any sibling file would also
 * want.
 */
internal object Limits {

    /** Below 256 px the working image cannot hold a legible line; above 8192 px nothing on a phone
     *  can allocate the six float buffers a trace needs at once. */
    const val MIN_WORKING_EDGE = 256
    const val MAX_WORKING_EDGE = 8192

    /**
     * The smallest σ with any meaning: `Convolve.gaussianBlur` treats σ ≤ 0.05 as an identity copy,
     * so clamping up to it keeps "no blur" reachable while satisfying the σ > 0 requirement. A σ of
     * exactly 0 would be a legal-looking value that some future kernel divides by.
     */
    const val MIN_SIGMA = 0.05f
    const val MAX_SIGMA = 32f

    /** Radii are in working pixels; 256 already spans an eighth of the largest working image. */
    const val MAX_RADIUS = 256

    /** Areas are pixel counts, bounded by the largest working image (8192² ≈ 6.7e7). */
    const val MAX_AREA = 67_108_864

    /** Angles in this module are always unsigned turn angles in degrees. */
    const val MAX_ANGLE = 180f

    /** Path lengths and simplification tolerances are in working pixels. */
    const val MAX_LENGTH = 8192f

    /** The style a blank or unknown id falls back to. */
    const val DEFAULT_STYLE_ID = "clean-line"

    /**
     * The XDoG/FDoG ink level, and the single most consequential number in this file.
     *
     * ε is an *intensity* (ALGORITHMS §7.2): the soft threshold is monotone in `u` and `Stages`
     * binarises the density immediately, so the ink mask is exactly the level set
     * `u ≤ ε + atanh(inkThreshold − 1) / φ`. A flat region of intensity `I` answers `u = I`, so
     * anything darker than that level is inked *as tone*, edge or no edge.
     *
     * At the previous 0.5 the level sat at 0.47, above the body of almost every photographed object.
     * Measured on a shaded terracotta pot: 18–66% of the smooth body came back as ink, the skeleton
     * of that solid region shattered into thousands of 5–20 px fragments, and the real silhouette
     * was *lost*, because a skeleton runs down the middle of a filled region rather than along its
     * edge. Recall of the true silhouette was 29%, of the painted bands 3%.
     *
     * 0.08 puts the level at 0.05 — below any tone a photograph of a lit object contains, and far
     * above the deeply negative response a genuine edge produces. Same subject, same everything
     * else: 2770 shapes to 193, 426 kB to 30 kB, silhouette recall 29% → 93%, bands 3% → 85%. The
     * tone response is not lost, only moved to where it belongs: a genuinely near-black region still
     * fills, which is what [Styles] `comic` and `woodcut` raise it again for.
     */
    const val XDOG_EPSILON = 0.08f

    fun i(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /**
     * Clamps [v] into `[lo, hi]`, mapping NaN to [fallback].
     *
     * NaN needs its own branch because every comparison against it is false, so the plain
     * `if (v < lo) lo else if (v > hi) hi else v` form returns NaN unchanged — and a NaN σ turns an
     * entire image into NaN two stages later, where it is unattributable.
     */
    fun f(v: Float, lo: Float, hi: Float, fallback: Float): Float =
        if (v.isNaN()) fallback else if (v < lo) lo else if (v > hi) hi else v
}

/**
 * Everything that happens to the pixels before an edge engine sees them.
 *
 * @param autoOrient honour the source's recorded orientation. The platform decoder has already
 *   applied EXIF by the time an `RgbaImage` exists, so this is a hook for layers that have not.
 * @param perspectiveCorrect look for a document quadrilateral and rectify to it.
 * @param workingLongEdge the long edge the trace runs at, 256..8192. The vector result is scaled
 *   back to source size afterwards, so this trades time for detail and never for output resolution.
 * @param denoiseStrength 0..1, mapped to each filter's own units by the pipeline.
 * @param medianRadius radius in pixels for [DenoiseMode.MEDIAN]; 0 disables the filter.
 * @param brightness, contrast both −1..1.
 * @param gamma 0.05..10; 1 is identity.
 * @param unsharpAmount 0..5; 0 disables the sharpener.
 * @param invertInput set for white-on-black sources, so "ink" stays the dark class everywhere else.
 */
@Serializable
data class PreprocessParams(
    val autoOrient: Boolean = true,
    val perspectiveCorrect: Boolean = false,
    val workingLongEdge: Int = 2048,
    val denoise: DenoiseMode = DenoiseMode.BILATERAL,
    val denoiseStrength: Float = 0.5f,
    val medianRadius: Int = 1,
    val claheEnabled: Boolean = true,
    val claheClip: Float = 2f,
    val claheTiles: Int = 8,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val unsharpAmount: Float = 0f,
    val unsharpSigma: Float = 1.5f,
    val invertInput: Boolean = false,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): PreprocessParams = PreprocessParams(
        autoOrient = autoOrient,
        perspectiveCorrect = perspectiveCorrect,
        workingLongEdge = Limits.i(workingLongEdge, Limits.MIN_WORKING_EDGE, Limits.MAX_WORKING_EDGE),
        denoise = denoise,
        denoiseStrength = Limits.f(denoiseStrength, 0f, 1f, 0.5f),
        medianRadius = Limits.i(medianRadius, 0, 32),
        claheEnabled = claheEnabled,
        claheClip = Limits.f(claheClip, 1f, 16f, 2f),
        claheTiles = Limits.i(claheTiles, 1, 64),
        brightness = Limits.f(brightness, -1f, 1f, 0f),
        contrast = Limits.f(contrast, -1f, 1f, 0f),
        gamma = Limits.f(gamma, 0.05f, 10f, 1f),
        unsharpAmount = Limits.f(unsharpAmount, 0f, 5f, 0f),
        unsharpSigma = Limits.f(unsharpSigma, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 1.5f),
        invertInput = invertInput,
    )
}

/**
 * Background separation. Every matte is a suggestion the user accepts, never a silent edit — a wrong
 * matte deleting half of somebody's artwork is the worst failure this app can have, so the pipeline
 * also states in [TraceResult.notes] how much of the frame a matte removed.
 *
 * **The default is [MatteMode.NONE] and stays there.** A library default that removed backgrounds
 * would make the engine's contract "these are your pixels unless a matte disagrees", and the one
 * caller that cannot see the result — a batch of two hundred files — is the one that would find out
 * last. Turning it on is a decision, made in exactly three places: the user's own picker, an object
 * subject preset ([Subjects]), and [Auto] when the classifier measured a separable subject.
 *
 * @param tolerance 0..1, scaled to a ΔE76 distance by [com.offlinetracer.imaging.Matte]. Read by
 *   [MatteMode.BORDER_FLOOD] and by [MatteMode.SUBJECT]'s connectivity cue.
 * @param feather Gaussian σ in pixels applied to the alpha edge, 0..32. For [MatteMode.SUBJECT] this
 *   is softening *on top of* the guided filter, which has already put the transition on the object's
 *   own edge, so a small value is normally right there and 0 is not a mistake.
 * @param threshold 0..1 saliency cut for [MatteMode.SALIENCY]; unread by every other mode.
 */
@Serializable
data class MatteParams(
    val mode: MatteMode = MatteMode.NONE,
    val tolerance: Float = 0.18f,
    val feather: Float = 1.5f,
    val threshold: Float = 0.5f,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): MatteParams = MatteParams(
        mode = mode,
        tolerance = Limits.f(tolerance, 0f, 1f, 0.18f),
        feather = Limits.f(feather, 0f, Limits.MAX_SIGMA, 1.5f),
        threshold = Limits.f(threshold, 0f, 1f, 0.5f),
    )
}

/**
 * Automatic source detection: whether it runs, what it is allowed to change, and what it must leave
 * alone.
 *
 * ### Why the caller has to name the knobs it owns
 *
 * [handTuned] carries the knobs the user has moved by hand, and auto restores every one of them
 * after applying whatever it detected. The alternative — auto inferring "the user probably meant
 * that" by diffing against the style preset — cannot work, because a knob that happens to equal its
 * preset value is indistinguishable from one the user deliberately set *back* to it. Only the UI
 * knows, so only the UI can say, and it already tracks exactly this set.
 *
 * The names are the editor's own visible labels rather than field paths, on purpose: the sentence
 * auto writes into [TraceResult.notes] has to name the control the user can see, and a note reading
 * "kept your `cleanup.minBlobArea`" is a note about a thing that is not on the screen.
 *
 * ### Why the default is [AutoMode.SUGGEST] and not [AutoMode.APPLY]
 *
 * This is about what a *library* default may do, not about what the product should do. [Pipeline.run]
 * is handed a complete parameter tree by every caller — a [Styles] preset, a restored project, a
 * [Batch] job, a test — and every one of them means what it says. A default that let auto-detection
 * rewrite `minBlobArea` underneath a caller that had set it explicitly would make the engine's
 * contract "these are your settings unless the classifier disagrees", which is not a contract
 * anything can be built on; it was tried, and it silently changed the answer of a test that had set
 * a blob floor of a million to prove the floor was reported.
 *
 * [AutoMode.SUGGEST] still classifies and still writes the whole "this is line art, 72% sure, and
 * here is every setting I would have changed" sentence into [TraceResult.notes], so the detection is
 * no longer cosmetic and nothing is hidden. Turning it up to [AutoMode.APPLY] is the **UI's**
 * decision, which is exactly where ALGORITHMS §12 puts it: shown to the user as a named suggestion
 * with a one-tap override.
 *
 * @param mode see [AutoMode].
 * @param subjectId a subject the user chose by hand. **Non-empty disables the automatic choice
 *   entirely** — the material is the one thing auto-detection decides, and a user who has already
 *   decided it is not asking.
 * @param handTuned knob labels ([Knobs]) auto must restore after applying anything.
 * @param minConfidence the [com.offlinetracer.imaging.Classify.SourceProfile.confidence] floor
 *   below which auto does nothing and says so. 0.55 is a hair above the value a two-way tie can
 *   reach (0.55·top with a zero margin), so an ambiguous frame is always refused.
 * @param allowMatte whether a measured separable subject may switch [MatteMode.SUBJECT] on. It is
 *   also the switch that takes a matte **away**: on the automatic path the profile's measurement of
 *   this frame outranks the subject preset's prior about the material, so a preset that asked for a
 *   matte gets none when the classifier found no background to remove.
 * @param allowCrop whether a confident subject box may crop the working image to the subject.
 */
@Serializable
data class AutoParams(
    val mode: AutoMode = AutoMode.SUGGEST,
    val subjectId: String = "",
    val handTuned: Set<String> = emptySet(),
    val minConfidence: Float = 0.55f,
    val allowMatte: Boolean = true,
    val allowCrop: Boolean = true,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): AutoParams = AutoParams(
        mode = mode,
        subjectId = subjectId.trim(),
        // Trimmed and de-duplicated so that two spellings of the same label cannot make a knob
        // appear protected in one build and unprotected in the next.
        handTuned = handTuned.mapNotNullTo(LinkedHashSet()) { it.trim().ifEmpty { null } },
        // A floor of 0 would mean "act on anything", which is the behaviour this whole parameter
        // exists to prevent; a floor above 1 would mean "never act" and is spelled AutoMode.OFF.
        minConfidence = Limits.f(minConfidence, 0.05f, 1f, 0.55f),
        allowMatte = allowMatte,
        allowCrop = allowCrop,
    )
}

/**
 * The knobs the editor exposes by their visible labels, so [AutoParams.handTuned] and the sentence
 * auto writes name the same things the user can see.
 *
 * These strings are a **contract with the UI**: `EditorScreen.KNOBS` uses exactly these labels and
 * this table is what lets the engine put a hand-tuned value back after a preset overwrote it. They
 * live here rather than in the UI because the engine is the side that has to honour them, and a
 * label that exists in only one of the two places silently stops protecting its knob.
 */
object Knobs {

    const val EDGE_SENSITIVITY = "edge sensitivity"
    const val STROKE_WIDTH = "stroke width"
    const val SIMPLIFY = "simplify"
    const val CORNER = "corner threshold"
    const val MIN_PATH_LENGTH = "minimum path length"
    const val MIN_BLOB_AREA = "minimum blob area"

    /** Every protected knob, in the order the editor lists them. */
    val ALL: List<String> = listOf(
        EDGE_SENSITIVITY,
        STROKE_WIDTH,
        SIMPLIFY,
        CORNER,
        MIN_PATH_LENGTH,
        MIN_BLOB_AREA,
    )

    /**
     * Copies the knobs named in [names] from [user] back onto [applied].
     *
     * @return [applied] unchanged when nothing was hand-tuned, so the common case allocates nothing.
     */
    fun restore(user: TraceParams, applied: TraceParams, names: Set<String>): TraceParams {
        if (names.isEmpty()) return applied
        var out = applied
        if (EDGE_SENSITIVITY in names) {
            out = out.copy(edge = out.edge.copy(sensitivity = user.edge.sensitivity))
        }
        if (STROKE_WIDTH in names) {
            out = out.copy(output = out.output.copy(strokeWidth = user.output.strokeWidth))
        }
        if (SIMPLIFY in names) {
            out = out.copy(output = out.output.copy(simplify = user.output.simplify))
        }
        if (CORNER in names) {
            out = out.copy(output = out.output.copy(corner = user.output.corner))
        }
        if (MIN_PATH_LENGTH in names) {
            out = out.copy(output = out.output.copy(minPathLength = user.output.minPathLength))
        }
        if (MIN_BLOB_AREA in names) {
            out = out.copy(cleanup = out.cleanup.copy(minBlobArea = user.cleanup.minBlobArea))
        }
        return out
    }

    /** @return the labels of the knobs whose values differ between [a] and [b], in [ALL] order. */
    fun changedBetween(a: TraceParams, b: TraceParams): List<String> {
        val out = ArrayList<String>(ALL.size)
        if (a.edge.sensitivity != b.edge.sensitivity) out.add(EDGE_SENSITIVITY)
        if (a.output.strokeWidth != b.output.strokeWidth) out.add(STROKE_WIDTH)
        if (a.output.simplify != b.output.simplify) out.add(SIMPLIFY)
        if (a.output.corner != b.output.corner) out.add(CORNER)
        if (a.output.minPathLength != b.output.minPathLength) out.add(MIN_PATH_LENGTH)
        if (a.cleanup.minBlobArea != b.cleanup.minBlobArea) out.add(MIN_BLOB_AREA)
        return out
    }
}

/**
 * The flow-field knobs for [EdgeEngine.FDOG], mirroring
 * [com.offlinetracer.imaging.EdgeFlow.FlowParams] minus the two soft-threshold terms, which live on
 * [EdgeParams] so XDoG and FDoG share one ε/φ pair in the UI.
 *
 * @param sigmaM the "how long is a stroke" knob: the Gaussian along the flow.
 */
@Serializable
data class FlowSettings(
    val tensorSigma: Float = 2f,
    val etfIterations: Int = 3,
    val etfRadius: Int = 5,
    val sigmaC: Float = 1f,
    val sigmaM: Float = 3f,
    val tau: Float = 0.99f,
    val fdogIterations: Int = 3,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): FlowSettings = FlowSettings(
        tensorSigma = Limits.f(tensorSigma, Limits.MIN_SIGMA, 16f, 2f),
        etfIterations = Limits.i(etfIterations, 0, 16),
        etfRadius = Limits.i(etfRadius, 1, 32),
        sigmaC = Limits.f(sigmaC, Limits.MIN_SIGMA, 16f, 1f),
        sigmaM = Limits.f(sigmaM, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 3f),
        // τ ≥ 1 inverts the DC term of the difference of Gaussians and 1/(1−τ) explodes.
        tau = Limits.f(tau, 0f, 0.999f, 0.99f),
        fdogIterations = Limits.i(fdogIterations, 1, 16),
    )
}

/**
 * Which edge engine runs and how it is tuned.
 *
 * @param sensitivity 0..1, the one control that means the same thing in every engine: more ink as
 *   it rises. Each engine maps it to its own units in `Stages`.
 * @param cannyLow, cannyHigh explicit hysteresis thresholds in 0..1. **Negative means auto** — the
 *   sentinel is preserved by sanitisation as exactly −1 so "auto" has a single representation and a
 *   saved project cannot come back with a −0.3 that reads as auto in one build and as 0 in another.
 * @param dogTau how much of the coarse Gaussian is subtracted, 0..0.999.
 * @param xdogPhi soft-threshold sharpness, 0.1..500: large is hard technical line work, small is
 *   pencil-like.
 * @param adaptiveC offset subtracted from the local mean, −1..1.
 * @param modelId id of a registered [com.offlinetracer.imaging.EdgeModel]; only read for
 *   [EdgeEngine.MODEL], and an id that is not registered produces a note, not a failure.
 */
@Serializable
data class EdgeParams(
    val engine: EdgeEngine = EdgeEngine.FDOG,
    val sensitivity: Float = 0.5f,
    val blurSigma: Float = 1.2f,
    val cannyLow: Float = -1f,
    val cannyHigh: Float = -1f,
    val dogSigma: Float = 1.0f,
    val dogK: Float = 1.6f,
    val dogTau: Float = 0.98f,
    val xdogEpsilon: Float = Limits.XDOG_EPSILON,
    val xdogPhi: Float = 20f,
    val flow: FlowSettings = FlowSettings(),
    val adaptiveRadius: Int = 15,
    val adaptiveC: Float = 0.02f,
    val useSauvola: Boolean = true,
    val logSigma: Float = 1.4f,
    val logSlope: Float = 0.02f,
    val modelId: String = "",
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): EdgeParams {
        var lo = if (cannyLow.isNaN() || cannyLow < 0f) AUTO_THRESHOLD else Limits.f(cannyLow, 0f, 1f, AUTO_THRESHOLD)
        var hi = if (cannyHigh.isNaN() || cannyHigh < 0f) AUTO_THRESHOLD else Limits.f(cannyHigh, 0f, 1f, AUTO_THRESHOLD)
        // Normalising the order here rather than in the detector keeps "which is the high one" a
        // property of the parameters, so the UI and the engine cannot disagree about it.
        if (lo >= 0f && hi >= 0f && lo > hi) {
            val t = lo
            lo = hi
            hi = t
        }
        return EdgeParams(
            engine = engine,
            sensitivity = Limits.f(sensitivity, 0f, 1f, 0.5f),
            blurSigma = Limits.f(blurSigma, Limits.MIN_SIGMA, 16f, 1.2f),
            cannyLow = lo,
            cannyHigh = hi,
            dogSigma = Limits.f(dogSigma, Limits.MIN_SIGMA, 16f, 1f),
            // k ≤ 1 makes the "coarse" Gaussian the finer of the two and the difference changes sign.
            dogK = Limits.f(dogK, 1.05f, 8f, 1.6f),
            dogTau = Limits.f(dogTau, 0f, 0.999f, 0.98f),
            xdogEpsilon = Limits.f(xdogEpsilon, -2f, 2f, Limits.XDOG_EPSILON),
            xdogPhi = Limits.f(xdogPhi, 0.1f, 500f, 20f),
            flow = flow.sanitized(),
            adaptiveRadius = Limits.i(adaptiveRadius, 1, Limits.MAX_RADIUS),
            adaptiveC = Limits.f(adaptiveC, -1f, 1f, 0.02f),
            useSauvola = useSauvola,
            logSigma = Limits.f(logSigma, Limits.MIN_SIGMA, 16f, 1.4f),
            logSlope = Limits.f(logSlope, 0f, 1f, 0.02f),
            modelId = modelId.trim(),
        )
    }

    companion object {
        /** The one canonical "derive the thresholds from the image" value. */
        const val AUTO_THRESHOLD = -1f
    }
}

/**
 * Binary cleanup: what is removed, what is joined, and whether the ink is reduced to centrelines.
 *
 * @param minBlobArea components smaller than this many pixels are dust and are dropped. The count
 *   dropped is always reported in [TraceResult.notes].
 * @param closeRadius morphological closing radius — joins gaps of roughly `2·r` that already nearly
 *   touch. Endpoint bridging handles the rest.
 * @param maxGap endpoint-bridging reach in pixels; `bridgeGaps` off ignores it.
 * @param maxBridgeAngle acceptance cone half-angle in degrees, 0..180.
 * @param skeletonize reduce ink to one-pixel centrelines. Ignored in outline mode, where the
 *   region boundary is the thing being traced and a skeleton would delete it.
 * @param pruneSpurs branches shorter than this many pixels are removed; 0 disables pruning.
 * @param keepLargest keep only the N largest components; **0 means disabled**, not "keep none".
 */
@Serializable
data class CleanupParams(
    val minBlobArea: Int = 24,
    val removeIsolated: Boolean = true,
    val closeRadius: Int = 1,
    val openRadius: Int = 0,
    val bridgeGaps: Boolean = true,
    val maxGap: Int = 12,
    val maxBridgeAngle: Float = 60f,
    val skeletonize: Boolean = true,
    val thinning: ThinningMode = ThinningMode.ZHANG_SUEN,
    val pruneSpurs: Int = 4,
    val fillHolesUpTo: Int = 0,
    val keepLargest: Int = 0,
    val removeBorderTouching: Boolean = false,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): CleanupParams = CleanupParams(
        minBlobArea = Limits.i(minBlobArea, 0, Limits.MAX_AREA),
        removeIsolated = removeIsolated,
        closeRadius = Limits.i(closeRadius, 0, 64),
        openRadius = Limits.i(openRadius, 0, 64),
        bridgeGaps = bridgeGaps,
        maxGap = Limits.i(maxGap, 0, 1024),
        maxBridgeAngle = Limits.f(maxBridgeAngle, 0f, Limits.MAX_ANGLE, 60f),
        skeletonize = skeletonize,
        thinning = thinning,
        pruneSpurs = Limits.i(pruneSpurs, 0, Limits.MAX_RADIUS),
        fillHolesUpTo = Limits.i(fillHolesUpTo, 0, Limits.MAX_AREA),
        keepLargest = Limits.i(keepLargest, 0, 65_536),
        removeBorderTouching = removeBorderTouching,
    )
}

/**
 * How the cleaned mask becomes geometry, and how that geometry is painted.
 *
 * @param vectorMode centreline (one stroke becomes one open path) or outline (one region becomes
 *   one closed path). This is the most consequential choice in the app.
 * @param simplify Douglas–Peucker tolerance in working pixels; 0 keeps every traced vertex.
 * @param fitError Bezier fitting tolerance in working pixels.
 * @param corner corner threshold in degrees, 0..180. **Higher keeps more corners**: a vertex is a
 *   corner when the angle its neighbours subtend is *sharper* than this, so 140° protects gentle
 *   bends and 70° protects only spikes.
 * @param minPathLength paths shorter than this are dropped, and the count dropped is always
 *   reported in [TraceResult.notes].
 * @param modulateWidth sample the distance transform per vertex so the SVG reproduces thick-and-thin
 *   brushwork. Emitted as a filled outline, because SVG has no variable-width stroke.
 * @param background `null` means transparent.
 */
@Serializable
data class OutputParams(
    val vectorMode: VectorModeParam = VectorModeParam.CENTERLINE,
    val simplify: Float = 1.0f,
    val fitError: Float = 1.6f,
    val corner: Float = 100f,
    val smoothIterations: Int = 1,
    val strokeWidth: Float = 1.6f,
    val modulateWidth: Boolean = false,
    val widthScale: Float = 1f,
    val minPathLength: Float = 3f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val background: Int? = null,
    val fillClosed: Boolean = false,
) {
    /** @return a copy with every field inside its legal range; idempotent. */
    fun sanitized(): OutputParams = OutputParams(
        vectorMode = vectorMode,
        simplify = Limits.f(simplify, 0f, 64f, 1f),
        // A non-positive fit error asks the fitter to subdivide until it hits its depth cap on every
        // curve, which is minutes of work for a result no better than the polyline it started from.
        fitError = Limits.f(fitError, 0.01f, 64f, 1.6f),
        corner = Limits.f(corner, 0f, Limits.MAX_ANGLE, 100f),
        smoothIterations = Limits.i(smoothIterations, 0, 8),
        strokeWidth = Limits.f(strokeWidth, 0.01f, 64f, 1.6f),
        modulateWidth = modulateWidth,
        widthScale = Limits.f(widthScale, 0.01f, 16f, 1f),
        minPathLength = Limits.f(minPathLength, 0f, Limits.MAX_LENGTH, 3f),
        strokeColor = strokeColor,
        background = background,
        fillClosed = fillClosed,
    )
}

/**
 * The complete parameter tree for one trace.
 *
 * @param styleId the [Styles] preset these values came from, carried so the UI can say "you have
 *   modified Pencil sketch" rather than showing an anonymous pile of sliders.
 */
@Serializable
data class TraceParams(
    val preprocess: PreprocessParams = PreprocessParams(),
    val matte: MatteParams = MatteParams(),
    val edge: EdgeParams = EdgeParams(),
    val cleanup: CleanupParams = CleanupParams(),
    val output: OutputParams = OutputParams(),
    val auto: AutoParams = AutoParams(),
    val styleId: String = Limits.DEFAULT_STYLE_ID,
) {
    /**
     * Clamps every field of every sub-tree into its legal range.
     *
     * Idempotent by construction — `p.sanitized() == p.sanitized().sanitized()` for every `p`,
     * including ones carrying NaN, infinities and values from a build that no longer exists. That
     * property is what lets the pipeline sanitise once on entry and lets the UI sanitise on every
     * slider tick without the two disagreeing.
     *
     * @return a new [TraceParams]; the receiver is never modified.
     */
    fun sanitized(): TraceParams = TraceParams(
        preprocess = preprocess.sanitized(),
        matte = matte.sanitized(),
        edge = edge.sanitized(),
        cleanup = cleanup.sanitized(),
        output = output.sanitized(),
        auto = auto.sanitized(),
        styleId = styleId.trim().ifEmpty { Limits.DEFAULT_STYLE_ID },
    )
}
