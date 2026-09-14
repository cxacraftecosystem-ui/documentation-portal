package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import kotlin.math.roundToInt

/**
 * Subject presets: what the thing in the photograph *is*, as an adjustment rather than a replacement.
 *
 * A style says how the drawing should look; a subject says what the material does to the pixels
 * before anybody gets to decide how it looks. Those are independent choices, so a [SubjectPreset]
 * takes an existing [TraceParams] and nudges it — "this is a textile, so diffuse the weave and raise
 * the blob floor" — and the result composes with any of the twenty [Styles]. Replacing the parameters
 * instead would make the two pickers fight: choosing a subject would silently discard the style the
 * user had just chosen, which is the single most confusing thing a two-picker UI can do.
 *
 * Two rules keep the composition honest:
 *
 *  1. **A subject never changes the edge engine or the vector mode.** Those are the style's identity —
 *     "colouring book" means outline mode and nothing about the subject can make it mean centreline.
 *     A subject changes strengths, radii, floors and tolerances only.
 *  2. **Every adjustment is relative** (a factor or an offset applied to whatever the style set), then
 *     run through [TraceParams.sanitized]. That is why applying a subject to any of the twenty styles
 *     can never produce an illegal value, and why applying one twice — which a UI that re-adjusts on
 *     every edit will do — cannot walk a parameter out of range.
 *
 * [SubjectPreset.adjust] also leaves [TraceParams.styleId] alone, so the UI can still say "Woodcut,
 * adjusted for stone carving" rather than losing track of where the numbers came from.
 */
data class SubjectPreset(
    val id: String,
    val name: String,
    /** One sentence explaining what this material does to the image and what is done about it. */
    val hint: String,
    val adjust: (TraceParams) -> TraceParams,
)

object Subjects {

    // -------------------------------------------------------------------------------------------
    // Relative-adjustment helpers
    // -------------------------------------------------------------------------------------------

    /** Multiply a float knob. Kept as a named function so every subject reads the same way. */
    private fun scale(v: Float, factor: Float): Float = v * factor

    /**
     * Multiply an integer knob, rounding away from zero for any non-zero input.
     *
     * The rounding rule matters: `(1 * 0.5).roundToInt()` is 0, and a `closeRadius` that silently
     * becomes 0 turns "close small gaps" into "do nothing" — a behaviour change disguised as a
     * scaling. A knob the style deliberately set to 0 stays 0.
     */
    private fun scale(v: Int, factor: Float): Int {
        if (v <= 0) return v
        val r = (v * factor).roundToInt()
        return if (r < 1) 1 else r
    }

    /** Offset a 0..1 knob; the sanitiser does the clamping, so this stays arithmetic. */
    private fun raise(v: Float, delta: Float): Float = v + delta

    /**
     * Asks for [MatteMode.SUBJECT], and only when nothing else has already asked for something.
     *
     * The "only when NONE" is the whole rule. A subject preset is a statement about the *material* —
     * "a pot is photographed standing on something, so there is a background behind it" — and a
     * material is weaker evidence than a choice: a style that named a matte (`silhouette` names
     * saliency) and a user who moved the control both decided, and a prior does not overrule a
     * decision. It also keeps [SubjectPreset.adjust] idempotent, which the UI relies on because it
     * re-adjusts on every edit.
     *
     * [MatteMode.SUBJECT] rather than [MatteMode.BORDER_FLOOD] because this is the automatic-ish path
     * and it is the only mode that can decline: `Matte.subjectMatte` reports a confidence and the
     * matte stage refuses below it, so a preset that is wrong about *this* photograph costs a
     * background that stayed rather than a subject that went.
     */
    private fun separateSubject(m: MatteParams): MatteParams =
        if (m.mode == MatteMode.NONE) m.copy(mode = MatteMode.SUBJECT) else m

    // -------------------------------------------------------------------------------------------
    // The presets
    //
    // Four of the twelve ask for a background matte — pottery, jewellery, sculpture, metalwork — and
    // the division is not about how photographic the material is, it is about whether the frame
    // contains a background at all. Those four are **discrete objects photographed standing on or in
    // front of something else**: there is a subject, there is a backdrop, and the backdrop is not
    // part of the drawing.
    //
    // The other eight are not shy versions of the same thing, they are the cases where a matte has
    // nothing to find and can only subtract:
    //  - `logo` and `sketch` are a flat graphic and a scan. The paper *is* the picture's ground, and
    //    a matte that "separated" it would be cutting the drawing out of its own page.
    //  - `painting`, `textile`, `wood-carving`, `stone-carving` are surfaces photographed
    //    face-on: the material fills the frame, so any split the matte finds is a split *inside* the
    //    artwork.
    //  - `architecture` is the one that looks like an object and is not — a building's "background"
    //    is sky, street and neighbouring facades, all of which are in the picture on purpose, and it
    //    reaches every edge of the frame besides.
    //  - `photo` is the general case, where by definition nothing about the frame is known. Guessing
    //    "there is a subject" is exactly the guess this preset exists not to make; when the frame
    //    really does hold one, `Auto` measures that and switches the matte on itself.
    // -------------------------------------------------------------------------------------------

    private val PAINTING = SubjectPreset(
        id = "painting",
        name = "Painting",
        hint = "Canvas weave and brush impasto both read as edges, so denoising and the blob floor " +
            "rise and the flow smoothing lengthens to follow the brushwork instead of the texture.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    denoise = if (p.preprocess.denoise == DenoiseMode.NONE) {
                        DenoiseMode.BILATERAL
                    } else {
                        p.preprocess.denoise
                    },
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.25f),
                    claheClip = scale(p.preprocess.claheClip, 0.9f),
                ),
                edge = p.edge.copy(
                    sensitivity = raise(p.edge.sensitivity, -0.06f),
                    flow = p.edge.flow.copy(sigmaM = scale(p.edge.flow.sigmaM, 1.3f)),
                ),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 2.5f)),
                output = p.output.copy(simplify = scale(p.output.simplify, 1.2f)),
            ).sanitized()
        },
    )

    private val POTTERY = SubjectPreset(
        id = "pottery",
        name = "Pottery",
        hint = "A glazed curved surface is nearly all specular highlight and has almost no true " +
            "corners, so highlights are pulled down and the corner threshold drops — and a thrown " +
            "pot stands on something, so the background behind it is separated away.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    brightness = raise(p.preprocess.brightness, -0.05f),
                    contrast = raise(p.preprocess.contrast, 0.1f),
                    gamma = scale(p.preprocess.gamma, 1.1f),
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.15f),
                ),
                matte = separateSubject(p.matte),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 1.6f)),
                output = p.output.copy(
                    // Lower is fewer corners protected: a thrown pot genuinely has none, and a
                    // protected "corner" on a rim is a staircase artefact being preserved.
                    corner = scale(p.output.corner, 0.8f),
                    smoothIterations = p.output.smoothIterations + 1,
                ),
            ).sanitized()
        },
    )

    private val TEXTILE = SubjectPreset(
        id = "textile",
        name = "Textile",
        hint = "A woven surface produces more edges than the pattern printed on it, so anisotropic " +
            "diffusion flattens the weave — the only filter here that does so without flattening " +
            "the pattern too.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    denoise = DenoiseMode.ANISOTROPIC,
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.3f),
                ),
                edge = p.edge.copy(
                    sensitivity = raise(p.edge.sensitivity, -0.08f),
                    flow = p.edge.flow.copy(sigmaM = scale(p.edge.flow.sigmaM, 1.4f)),
                ),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 3f)),
                output = p.output.copy(simplify = scale(p.output.simplify, 1.3f)),
            ).sanitized()
        },
    )

    private val JEWELLERY = SubjectPreset(
        id = "jewellery",
        name = "Jewellery",
        hint = "On filigree and settings the small components *are* the subject, so every blur " +
            "shrinks, the blob floor drops, the working resolution rises and the cloth or tray it " +
            "was photographed on is separated away.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    workingLongEdge = scale(p.preprocess.workingLongEdge, 1.25f),
                    unsharpAmount = raise(p.preprocess.unsharpAmount, 0.5f),
                ),
                matte = separateSubject(p.matte),
                edge = p.edge.copy(
                    sensitivity = raise(p.edge.sensitivity, 0.1f),
                    blurSigma = scale(p.edge.blurSigma, 0.75f),
                    dogSigma = scale(p.edge.dogSigma, 0.7f),
                    logSigma = scale(p.edge.logSigma, 0.75f),
                    flow = p.edge.flow.copy(sigmaC = scale(p.edge.flow.sigmaC, 0.8f)),
                ),
                cleanup = p.cleanup.copy(
                    // The one subject where the usual "dust is small, therefore small is dust"
                    // assumption is wrong, so the blob floor and the spur prune both come down.
                    minBlobArea = scale(p.cleanup.minBlobArea, 0.3f),
                    pruneSpurs = scale(p.cleanup.pruneSpurs, 0.5f),
                ),
                output = p.output.copy(
                    simplify = scale(p.output.simplify, 0.5f),
                    fitError = scale(p.output.fitError, 0.7f),
                    minPathLength = scale(p.output.minPathLength, 0.5f),
                ),
            ).sanitized()
        },
    )

    private val SCULPTURE = SubjectPreset(
        id = "sculpture",
        name = "Sculpture",
        hint = "A carved form is described by shading rather than by outline, so local contrast rises " +
            "and the flow smoothing lengthens to find the form lines instead of the shadows, with " +
            "the gallery wall behind the piece separated away.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    claheEnabled = true,
                    claheClip = scale(p.preprocess.claheClip, 1.4f),
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.15f),
                ),
                matte = separateSubject(p.matte),
                edge = p.edge.copy(
                    sensitivity = raise(p.edge.sensitivity, -0.04f),
                    flow = p.edge.flow.copy(sigmaM = scale(p.edge.flow.sigmaM, 1.5f)),
                ),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 1.8f)),
                output = p.output.copy(corner = scale(p.output.corner, 0.9f)),
            ).sanitized()
        },
    )

    private val ARCHITECTURE = SubjectPreset(
        id = "architecture",
        name = "Architecture",
        hint = "Buildings are photographed from below and made of straight lines, so the frame is " +
            "rectified and the corner threshold and minimum path length both rise.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(perspectiveCorrect = true),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 2f)),
                output = p.output.copy(
                    corner = scale(p.output.corner, 1.35f),
                    // Mortar courses and roof tiles are short; window frames are long.
                    minPathLength = scale(p.output.minPathLength, 1.6f),
                    simplify = scale(p.output.simplify, 1.3f),
                    smoothIterations = p.output.smoothIterations - 1,
                ),
            ).sanitized()
        },
    )

    private val LOGO = SubjectPreset(
        id = "logo",
        name = "Logo or flat graphic",
        hint = "Everything here is already a clean edge, so every filter that would soften one is " +
            "switched off and the tolerances tighten.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    denoise = DenoiseMode.NONE,
                    // CLAHE on a flat graphic invents gradients inside areas that are one flat
                    // colour, and the edge engine then traces the gradient it just created.
                    claheEnabled = false,
                    unsharpAmount = 0f,
                ),
                edge = p.edge.copy(sensitivity = raise(p.edge.sensitivity, 0.05f)),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 0.6f)),
                output = p.output.copy(
                    simplify = scale(p.output.simplify, 0.7f),
                    fitError = scale(p.output.fitError, 0.6f),
                    corner = scale(p.output.corner, 1.3f),
                    smoothIterations = 0,
                ),
            ).sanitized()
        },
    )

    private val SKETCH = SubjectPreset(
        id = "sketch",
        name = "Sketch on paper",
        hint = "A pencil or charcoal drawing is faint and dusty, so local contrast rises to lift the " +
            "strokes, a median removes the dust, and the blob floor drops.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    denoise = DenoiseMode.MEDIAN,
                    medianRadius = 1,
                    claheEnabled = true,
                    claheClip = scale(p.preprocess.claheClip, 1.5f),
                    gamma = scale(p.preprocess.gamma, 0.9f),
                ),
                edge = p.edge.copy(sensitivity = raise(p.edge.sensitivity, 0.12f)),
                cleanup = p.cleanup.copy(
                    minBlobArea = scale(p.cleanup.minBlobArea, 0.5f),
                    pruneSpurs = scale(p.cleanup.pruneSpurs, 0.6f),
                ),
                output = p.output.copy(minPathLength = scale(p.output.minPathLength, 0.6f)),
            ).sanitized()
        },
    )

    private val PHOTO = SubjectPreset(
        id = "photo",
        name = "Photograph",
        hint = "The general case, where nothing about the material is known: moderate denoising and " +
            "local contrast are guaranteed to be on and nothing else is pushed.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    // Floors rather than settings. A photograph always has sensor noise and always
                    // has a lighting gradient, so a style that turned these off was tuned for a
                    // material this subject explicitly says we do not have.
                    denoise = if (p.preprocess.denoise == DenoiseMode.NONE) {
                        DenoiseMode.BILATERAL
                    } else {
                        p.preprocess.denoise
                    },
                    denoiseStrength = if (p.preprocess.denoiseStrength < 0.4f) {
                        0.4f
                    } else {
                        p.preprocess.denoiseStrength
                    },
                    claheEnabled = true,
                    unsharpAmount = raise(p.preprocess.unsharpAmount, 0.3f),
                ),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 1.4f)),
            ).sanitized()
        },
    )

    private val WOOD_CARVING = SubjectPreset(
        id = "wood-carving",
        name = "Wood carving",
        hint = "Wood grain runs in one direction and reads as a bundle of parallel edges, so the flow " +
            "field is refined harder and lengthened along the strokes that matter.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.25f),
                ),
                edge = p.edge.copy(
                    sensitivity = raise(p.edge.sensitivity, -0.07f),
                    flow = p.edge.flow.copy(
                        // More ETF iterations is the specific answer to directional texture: the
                        // refinement votes with the neighbourhood, and grain is a minority direction
                        // at the scale of a carved line.
                        etfIterations = p.edge.flow.etfIterations + 2,
                        sigmaM = scale(p.edge.flow.sigmaM, 1.45f),
                    ),
                ),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 2.4f)),
                output = p.output.copy(simplify = scale(p.output.simplify, 1.15f)),
            ).sanitized()
        },
    )

    private val STONE_CARVING = SubjectPreset(
        id = "stone-carving",
        name = "Stone carving",
        hint = "Stone is low contrast with heavy surface texture, so local contrast rises hard, the " +
            "blob floor rises hardest, and gaps are bridged further.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    claheEnabled = true,
                    claheClip = scale(p.preprocess.claheClip, 1.5f),
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.3f),
                ),
                edge = p.edge.copy(sensitivity = raise(p.edge.sensitivity, -0.05f)),
                cleanup = p.cleanup.copy(
                    minBlobArea = scale(p.cleanup.minBlobArea, 3.5f),
                    closeRadius = p.cleanup.closeRadius + 1,
                    maxGap = scale(p.cleanup.maxGap, 1.5f),
                    pruneSpurs = scale(p.cleanup.pruneSpurs, 1.5f),
                ),
            ).sanitized()
        },
    )

    private val METALWORK = SubjectPreset(
        id = "metalwork",
        name = "Metalwork",
        hint = "Polished or hammered metal turns every light source into a false edge, so highlights " +
            "are pulled down before the edge engine sees them, and the surface the piece was set " +
            "down on is separated away.",
        adjust = { p ->
            p.copy(
                preprocess = p.preprocess.copy(
                    brightness = raise(p.preprocess.brightness, -0.1f),
                    gamma = scale(p.preprocess.gamma, 1.15f),
                    claheClip = scale(p.preprocess.claheClip, 1.2f),
                    denoiseStrength = raise(p.preprocess.denoiseStrength, 0.2f),
                    unsharpAmount = raise(p.preprocess.unsharpAmount, 0.2f),
                ),
                matte = separateSubject(p.matte),
                edge = p.edge.copy(sensitivity = raise(p.edge.sensitivity, -0.03f)),
                cleanup = p.cleanup.copy(minBlobArea = scale(p.cleanup.minBlobArea, 2f)),
            ).sanitized()
        },
    )

    /** Every subject, in picker order. Ids are persisted in [ProjectMeta.subjectId]. */
    val ALL: List<SubjectPreset> = listOf(
        PAINTING,
        POTTERY,
        TEXTILE,
        JEWELLERY,
        SCULPTURE,
        ARCHITECTURE,
        LOGO,
        SKETCH,
        PHOTO,
        WOOD_CARVING,
        STONE_CARVING,
        METALWORK,
    )

    private val index: Map<String, SubjectPreset> =
        LinkedHashMap<String, SubjectPreset>(ALL.size * 2).apply {
            for (s in ALL) put(s.id, s)
        }

    /** @return the subject with this id, trimmed, or `null`. */
    fun byId(id: String): SubjectPreset? = index[id.trim()]

    /** The subject a blank or unknown id resolves to: the one that assumes nothing. */
    fun default(): SubjectPreset = index["photo"] ?: ALL[0]

    /** Saturation spread below which a textured surface is mineral rather than woven. */
    private const val TEXTURE_COLOUR_SPLIT = 0.12f

    /**
     * Maps a [Classify.SourceProfile] to the subject its statistics imply.
     *
     * The mapping is a decision list over [Classify.SourceKind], not a score, so it is explainable
     * in one line to a user who disagrees with it — and disagreeing is expected. What the caller
     * does with the answer depends on [Classify.SourceProfile.confidence]; this function only says
     * which subject the class implies and takes no view on whether it should be applied.
     *
     * A profile that was never measured falls back to §12's flag ladder inside [Classify.kindOf],
     * so a hand-constructed profile still maps to the subject its flags describe.
     */
    fun suggestFor(profile: Classify.SourceProfile): SubjectPreset =
        when (Classify.kindOf(profile)) {
            // Already bi-level: pen, pencil or print on paper. This is the one case where the wrong
            // answer is expensive, because edge-detecting line art traces both sides of every stroke.
            Classify.SourceKind.LINE_ART -> SKETCH
            Classify.SourceKind.FLAT_GRAPHIC -> LOGO
            // Heavy texture with almost no colour variation is a mineral surface; heavy texture
            // *with* colour variation is cloth. Both need the texture suppressed, by different
            // filters — which is the one decision saturation spread is the right measurement for.
            Classify.SourceKind.TEXTURED ->
                if (profile.saturationSpread < TEXTURE_COLOUR_SPLIT) STONE_CARVING else TEXTILE
            // A single object on a clean sweep: curved, glazed or turned, with no true corners and a
            // silhouette that is the drawing.
            Classify.SourceKind.SMOOTH_OBJECT -> POTTERY
            Classify.SourceKind.PHOTOGRAPH, Classify.SourceKind.UNKNOWN -> PHOTO
        }
}

/**
 * What [Auto.decide] concluded.
 *
 * @param params the parameters to trace with. Identical to the input whenever [applied] is false,
 *   including reference equality, so a caller can test cheaply for "nothing happened".
 * @param applied whether anything was changed.
 * @param subject the subject auto chose, or `null` when it declined to choose one.
 * @param notes one sentence per decision, **including the decisions not to act**. An automatic
 *   change nobody can see is indistinguishable from a bug, and an automatic change that was
 *   *declined* is indistinguishable from one that never ran — so both are written down.
 */
data class AutoDecision(
    val params: TraceParams,
    val applied: Boolean,
    val kind: Classify.SourceKind,
    val confidence: Float,
    val subject: SubjectPreset?,
    val notes: List<String>,
)

/**
 * Automatic source detection, applied.
 *
 * ALGORITHMS §12 has always required the classification to be *shown* with a one-tap override and
 * never applied invisibly. This object is what makes "applied" and "invisibly" separable: it applies
 * the detected subject and then writes down, in [AutoDecision.notes], the class it detected, how
 * sure it was, and the name of every setting it moved. Those sentences reach the user through
 * [TraceResult.notes], which the UI is required to render.
 *
 * Four rules bound what it may do, and each exists because the alternative is a change the user
 * cannot attribute:
 *
 *  1. **It never chooses when the user has already chosen.** A non-empty [AutoParams.subjectId] is a
 *     decision, and a detector that overrules one is a detector nobody can use.
 *  2. **It never overwrites a hand-tuned knob.** [AutoParams.handTuned] is restored on top of
 *     whatever the subject set, and the note says which knobs were kept.
 *  3. **It never changes the style's identity.** The edge engine, the vector mode and the fill are
 *     what a style *is* — "colouring book" means outline mode — and no fact about the material can
 *     turn one into another. This is the same invariant [SubjectPreset] documents; enforcing it here
 *     as well means a subject table edited carelessly cannot break it through the automatic path.
 *  4. **Below [AutoParams.minConfidence] it does nothing and says so.** A wrong automatic choice is
 *     worse than no choice, because the user cannot tell that it happened.
 *  5. **The background matte is decided by the measurement, not by the subject it chose.** See
 *     [matteMode]: it switches [MatteMode.SUBJECT] on for a measured separable subject and off again
 *     for a preset that asked for one on a frame with no separable subject in it.
 */
object Auto {

    /**
     * Decides what auto-detection should do about [profile] and returns [params] with it applied.
     *
     * @param profile the measured source, or `null` if classification did not run — in which case
     *   nothing is applied and a note says why, because an auto mode that silently does nothing is
     *   the exact failure this object was written to remove.
     */
    fun decide(profile: Classify.SourceProfile?, params: TraceParams): AutoDecision {
        val auto = params.auto
        if (auto.mode == AutoMode.OFF) {
            return AutoDecision(params, false, Classify.SourceKind.UNKNOWN, 0f, null, emptyList())
        }
        if (profile == null) {
            return AutoDecision(
                params, false, Classify.SourceKind.UNKNOWN, 0f, null,
                listOf(
                    "Auto-detection was switched on but this trace did not classify the source, so " +
                        "nothing was detected and no setting was changed."
                ),
            )
        }

        // The profile's *own* kind, not [Classify.kindOf]'s flag fallback. The fallback exists so that
        // a hand-built profile still maps to a sensible subject, and a mapping is not a
        // classification: nothing measured it, so nothing scored it, so the confidence beside it
        // describes nothing. Acting on that pair would be acting on a guess with a made-up certainty.
        val kind = profile.kind
        val confidence = profile.confidence
        val name = Classify.nameOf(kind)
        val sure = percent(confidence)
        val floor = percent(auto.minConfidence)

        if (auto.subjectId.isNotEmpty()) {
            val chosen = Subjects.byId(auto.subjectId)
            return AutoDecision(
                params, false, kind, confidence, chosen,
                listOf(
                    "Auto-detection read this as $name ($sure% sure) but you had already chosen " +
                        "\"${chosen?.name ?: auto.subjectId}\", so your choice was kept and nothing " +
                        "was changed."
                ),
            )
        }

        if (kind == Classify.SourceKind.UNKNOWN) {
            // Not a low score but the absence of one: a blank frame, or a profile nothing measured.
            // Reporting it as "best guess: unclassified at 0%" would read as a failed classification
            // rather than as there being nothing there to classify.
            return AutoDecision(
                params, false, kind, confidence, null,
                listOf(
                    "Auto-detection found nothing in this frame it could classify, so every setting " +
                        "was left exactly as you had it."
                ),
            )
        }
        if (confidence < auto.minConfidence) {
            return AutoDecision(
                params, false, kind, confidence, null,
                listOf(
                    "Auto-detection was not sure enough to act — its best guess was $name at " +
                        "$sure% and it needs $floor% — so every setting was left exactly as you had " +
                        "it. Pick a subject by hand if that guess was right."
                ),
            )
        }

        val subject = Subjects.suggestFor(profile)
        val adjusted = preserveIdentity(params, subject.adjust(params))
        val matted = adjusted.copy(matte = adjusted.matte.copy(mode = matteMode(params, auto, profile)))
        val restored = Knobs.restore(params, matted, auto.handTuned).sanitized()

        val changed = describeChanges(params, restored)
        val kept = auto.handTuned.filter { it in Knobs.ALL }
        val notes = ArrayList<String>(3)
        if (changed.isEmpty()) {
            notes.add(
                "Auto-detection read this as $name ($sure% sure) and chose \"${subject.name}\", " +
                    "which asked for nothing your settings did not already do."
            )
        } else {
            notes.add(
                "Auto-detection read this as $name ($sure% sure) and applied \"${subject.name}\", " +
                    "which changed ${list(changed)}."
            )
        }
        if (kept.isNotEmpty()) {
            notes.add(
                "It left ${list(kept)} exactly as you set ${if (kept.size == 1) "it" else "them"} " +
                    "by hand."
            )
        }
        if (auto.mode == AutoMode.SUGGEST) {
            // The sentences above are written in the past tense because that is what they describe
            // in APPLY mode; in SUGGEST mode the whole set is prefixed rather than reworded, so the
            // two modes cannot drift into describing different things.
            val preview = ArrayList<String>(notes.size + 1)
            preview.add(
                "Auto-detection is set to suggest only, so nothing below was actually changed."
            )
            preview.addAll(notes)
            return AutoDecision(params, false, kind, confidence, subject, preview)
        }
        return AutoDecision(restored, true, kind, confidence, subject, notes)
    }

    /**
     * The matte the automatic path runs with — decided from the *measurement*, not inherited from the
     * subject the measurement chose.
     *
     * Three rules, in order, and the order is the argument:
     *
     *  1. **A matte already on the tree is a decision and is returned untouched.** It came from the
     *     style (`silhouette` names saliency) or from the user's own control, and neither a
     *     classifier nor a subject table outranks either.
     *  2. **Otherwise the profile decides.** [Classify.SourceProfile.separableSubject] is the
     *     measured statement that this frame holds one subject on a background clean enough to
     *     separate, which is the precondition for a matte and the *only* evidence here that is about
     *     the picture in front of us. It answers [MatteMode.SUBJECT] and not [MatteMode.BORDER_FLOOD]
     *     because the fused matte already contains the flood as its connectivity cue, adds a colour
     *     posterior that survives a subject touching the frame edge (the flood's outright failure
     *     case), and — the part that matters on a path nobody asked for — reports a confidence the
     *     matte stage can refuse on. A bare flood cannot say "I do not believe this".
     *  3. **Anything else gets none, including a matte the subject preset asked for.** That is the
     *     subtraction, and it is deliberate: `pottery` asking for a matte is a prior about the
     *     material ("a pot stands on something"), while `separableSubject` is a measurement of this
     *     photograph. A pot shot on a cluttered workbench has no background to remove, the
     *     measurement says so, and the measurement wins. Without this the automatic path would
     *     inherit the preset's assumption and the profile's answer would be decoration.
     */
    private fun matteMode(
        params: TraceParams,
        auto: AutoParams,
        profile: Classify.SourceProfile,
    ): MatteMode = when {
        params.matte.mode != MatteMode.NONE -> params.matte.mode
        auto.allowMatte && profile.separableSubject -> MatteMode.SUBJECT
        else -> MatteMode.NONE
    }

    /**
     * Restores the three fields that make a style what it is.
     *
     * [SubjectPreset] already promises not to touch them, and this enforces the promise rather than
     * trusting it. The reason is not defensiveness for its own sake: the subject tables are data,
     * they are edited by hand, and this is the one path that applies one *without a user having
     * asked for it* — so it is the one path where a table with a stray `engine =` in it would change
     * somebody's export with nothing on screen to explain it.
     */
    private fun preserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
        edge = after.edge.copy(engine = before.edge.engine),
        output = after.output.copy(
            vectorMode = before.output.vectorMode,
            fillClosed = before.output.fillClosed,
        ),
        styleId = before.styleId,
        auto = before.auto,
    )

    /**
     * The user-visible settings that differ between two parameter trees, named as the editor names
     * them.
     *
     * Wider than [Knobs.ALL] on purpose: the knobs there are the ones the *user* can move and
     * therefore the ones auto must not overwrite, while this list is what auto must *confess to*,
     * which includes settings that have no slider. A denoise filter switched from bilateral to
     * anisotropic diffusion changes the result more than any slider here and would otherwise be
     * invisible.
     */
    private fun describeChanges(before: TraceParams, after: TraceParams): List<String> {
        val out = ArrayList<String>(8)
        if (before.preprocess.denoise != after.preprocess.denoise) {
            out.add("the noise filter (${denoiseName(after.preprocess.denoise)})")
        } else if (before.preprocess.denoiseStrength != after.preprocess.denoiseStrength) {
            out.add("the denoising strength")
        }
        if (before.preprocess.claheEnabled != after.preprocess.claheEnabled) {
            out.add(
                if (after.preprocess.claheEnabled) "local contrast, now on" else "local contrast, now off"
            )
        }
        if (before.preprocess.workingLongEdge != after.preprocess.workingLongEdge) {
            out.add("the working resolution (${after.preprocess.workingLongEdge} px)")
        }
        if (before.preprocess.perspectiveCorrect != after.preprocess.perspectiveCorrect) {
            out.add("perspective correction")
        }
        if (before.matte.mode != after.matte.mode) out.add("background separation")
        out.addAll(Knobs.changedBetween(before, after))
        if (before.output.smoothIterations != after.output.smoothIterations) {
            out.add("path smoothing")
        }
        if (before.output.fitError != after.output.fitError) out.add("the curve fitting tolerance")
        if (before.cleanup.closeRadius != after.cleanup.closeRadius ||
            before.cleanup.maxGap != after.cleanup.maxGap
        ) {
            out.add("gap closing")
        }
        if (before.cleanup.pruneSpurs != after.cleanup.pruneSpurs) out.add("spur pruning")
        if (before.edge.flow != after.edge.flow) out.add("the flow field")
        return out
    }

    private fun denoiseName(mode: DenoiseMode): String = when (mode) {
        DenoiseMode.NONE -> "off"
        DenoiseMode.BILATERAL -> "bilateral"
        DenoiseMode.MEDIAN -> "median"
        DenoiseMode.ANISOTROPIC -> "anisotropic diffusion"
    }

    /** "a", "a and b", "a, b and c" — a list a person reads, not a comma-joined dump. */
    private fun list(items: List<String>): String = when (items.size) {
        0 -> "nothing"
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.subList(0, items.size - 1).joinToString(", ") + " and " + items.last()
    }

    /** Integer percent, locale-independently — see the note on `Classify.percent`. */
    private fun percent(v: Float): Int {
        if (!v.isFinite()) return 0
        val p = (v * 100f).roundToInt()
        return if (p < 0) 0 else if (p > 100) 100 else p
    }
}
