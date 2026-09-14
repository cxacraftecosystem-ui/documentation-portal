package com.offlinetracer.pipeline

/**
 * The twenty style presets, which are where most of this product's judgement actually lives.
 *
 * A preset is not a convenience wrapper over the defaults. Every one of them is a different answer to
 * a different question, and the differences are structural rather than cosmetic: the engine changes,
 * the vector mode changes, the cleanup aggression changes by an order of magnitude. Two presets that
 * differ only in stroke width would be worse than one preset, because the user would have to try both
 * to learn that.
 *
 * The list is ordered for display and is **stable**: [StylePreset.id] is persisted in project files
 * and in [TraceParams.styleId], so an id is never renamed and never reused for a different look. The
 * order is the order the picker shows, grouped by [StylePreset.group].
 *
 * Every preset's [StylePreset.params] is already inside the legal range, i.e. `sanitized()` is a
 * fixpoint on it. That is asserted by `StylesTest` and it matters: a preset that needed clamping
 * would mean the value written here is not the value that runs, and the difference would be invisible
 * in the picker.
 */
data class StylePreset(
    val id: String,
    val name: String,
    /** One sentence naming **when** to reach for this preset, not what it does internally. */
    val description: String,
    /** One of [Styles.GROUP_DRAWING], [Styles.GROUP_PRINT], [Styles.GROUP_FABRICATION],
     *  [Styles.GROUP_EDUCATION]. */
    val group: String,
    val params: TraceParams,
)

object Styles {

    const val GROUP_DRAWING = "Drawing"
    const val GROUP_PRINT = "Print & relief"
    const val GROUP_FABRICATION = "Fabrication"
    const val GROUP_EDUCATION = "Education"

    // -------------------------------------------------------------------------------------------
    // 1 · clean-line
    // -------------------------------------------------------------------------------------------

    private val CLEAN_LINE = StylePreset(
        id = "clean-line",
        name = "Clean line",
        description = "Start here for a photograph of almost any subject: flow-based edges give long, " +
            "coherent strokes where a per-pixel detector gives broken fragments.",
        group = GROUP_DRAWING,
        // The engine defaults *are* this preset, on purpose: the value a user gets before touching
        // anything must be a named, describable style rather than an anonymous pile of sliders.
        params = TraceParams(styleId = "clean-line"),
    )

    // -------------------------------------------------------------------------------------------
    // 2 · pencil-sketch
    // -------------------------------------------------------------------------------------------

    private val PENCIL_SKETCH = StylePreset(
        id = "pencil-sketch",
        name = "Pencil sketch",
        description = "Use when you want the result to look drawn rather than detected — a low XDoG " +
            "sharpness keeps the soft tonal edge of a pencil instead of a hard technical line.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoise = DenoiseMode.BILATERAL,
                denoiseStrength = 0.35f,
                claheClip = 2.5f,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                sensitivity = 0.55f,
                dogSigma = 1.3f,
                // φ = 3 drags the ink level down by 0.17 on its own, so ε carries that back: 0.27
                // puts the level at 0.10, a little above the other line styles because a sketch is
                // allowed to reach for the faintest marks.
                xdogEpsilon = 0.27f,
                // φ is the whole preset: 3 is a soft graphite transition, 200 is a drafting pen.
                xdogPhi = 3f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 12,
                pruneSpurs = 2,
                // A pencil line that stops is a pencil line that stopped; forcing the ends together
                // is exactly what makes a sketch look machine-made.
                bridgeGaps = false,
            ),
            output = OutputParams(
                simplify = 0.8f,
                fitError = 2f,
                corner = 80f,
                smoothIterations = 2,
                strokeWidth = 1.2f,
                modulateWidth = true,
                widthScale = 0.9f,
                minPathLength = 2.5f,
            ),
            styleId = "pencil-sketch",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 3 · technical-drawing
    // -------------------------------------------------------------------------------------------

    private val TECHNICAL_DRAWING = StylePreset(
        id = "technical-drawing",
        name = "Technical drawing",
        description = "Use for engineering drawings, schematics and CAD printouts, where a straight " +
            "run must stay straight and a corner must stay square.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
                // Median, not bilateral: the enemy on a printed drawing is dust and speckle, which is
                // what a median removes without rounding the line ends.
                denoise = DenoiseMode.MEDIAN,
                denoiseStrength = 0.3f,
                medianRadius = 1,
                claheClip = 3f,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                blurSigma = 0.8f,
                dogSigma = 0.8f,
                dogTau = 0.985f,
                // At φ = 200 the level is ε to within 0.003, so this is the level: 0.05, below any
                // tone a photographed drawing carries and above nothing but genuine edges.
                xdogEpsilon = 0.053f,
                xdogPhi = 200f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 16,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 3,
                maxGap = 6,
            ),
            output = OutputParams(
                simplify = 1.6f,
                fitError = 1f,
                // 150° protects even a gentle bend, which is what stops a chamfer becoming a curve.
                corner = 150f,
                smoothIterations = 0,
                strokeWidth = 1f,
                minPathLength = 4f,
            ),
            styleId = "technical-drawing",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 4 · blueprint
    // -------------------------------------------------------------------------------------------

    private val BLUEPRINT = StylePreset(
        id = "blueprint",
        name = "Blueprint",
        description = "Use to print a plan as white line work on cyanotype blue, for presentation " +
            "boards and drawing-office reproductions.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoise = DenoiseMode.MEDIAN,
                denoiseStrength = 0.3f,
                medianRadius = 1,
                claheClip = 3f,
                claheTiles = 12,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                blurSigma = 0.9f,
                dogSigma = 0.9f,
                dogTau = 0.985f,
                // The same 0.05 level as technical-drawing, corrected for this preset's φ.
                xdogEpsilon = 0.054f,
                xdogPhi = 150f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 20,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 3,
                maxGap = 8,
                // A photographed or scanned plan almost always carries the drawing board's frame,
                // and on a blue ground that frame is the first thing the eye lands on.
                removeBorderTouching = true,
            ),
            output = OutputParams(
                simplify = 1.5f,
                fitError = 1.1f,
                corner = 145f,
                smoothIterations = 0,
                strokeWidth = 1.3f,
                minPathLength = 4f,
                strokeColor = 0xFFEAF2FF.toInt(),
                background = 0xFF10315C.toInt(),
            ),
            styleId = "blueprint",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 5 · tattoo-outline
    // -------------------------------------------------------------------------------------------

    private val TATTOO_OUTLINE = StylePreset(
        id = "tattoo-outline",
        name = "Tattoo outline",
        description = "Use for transfer-ready tattoo linework: heavy uniform strokes with the fine " +
            "detail deliberately thrown away, because detail that thin will not hold in skin.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoiseStrength = 0.7f,
                claheClip = 3f,
            ),
            edge = EdgeParams(
                sensitivity = 0.45f,
                flow = FlowSettings(sigmaM = 4.5f),
            ),
            cleanup = CleanupParams(
                minBlobArea = 96,
                closeRadius = 2,
                maxGap = 18,
                maxBridgeAngle = 75f,
                pruneSpurs = 8,
            ),
            output = OutputParams(
                simplify = 1.8f,
                fitError = 2.2f,
                corner = 95f,
                smoothIterations = 2,
                strokeWidth = 3.2f,
                minPathLength = 12f,
            ),
            styleId = "tattoo-outline",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 6 · mandala
    // -------------------------------------------------------------------------------------------

    private val MANDALA = StylePreset(
        id = "mandala",
        name = "Mandala",
        description = "Use for symmetric ornament and filigree, where the thinnest lines are the " +
            "point and a heavier engine would swallow them.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoiseStrength = 0.25f,
                claheClip = 2.5f,
                claheTiles = 10,
                unsharpAmount = 0.6f,
            ),
            edge = EdgeParams(
                // Laplacian-of-Gaussian zero crossings give the thinnest, most delicate lines of any
                // engine here, which is exactly what ornament needs and what it costs elsewhere.
                engine = EdgeEngine.LOG,
                sensitivity = 0.6f,
                logSigma = 1.1f,
                logSlope = 0.012f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 8,
                maxGap = 6,
                pruneSpurs = 2,
            ),
            output = OutputParams(
                simplify = 0.5f,
                fitError = 1.1f,
                corner = 120f,
                strokeWidth = 1.1f,
                // Working pixels, and the working long edge is 2048 by default: a 2 px path there is
                // a tenth of a millimetre on a printed page — not fine detail but arithmetic
                // residue. 6 px is still far below the thinnest thing ornament is made of.
                minPathLength = 6f,
            ),
            styleId = "mandala",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 7 · comic
    // -------------------------------------------------------------------------------------------

    private val COMIC = StylePreset(
        id = "comic",
        name = "Comic ink",
        description = "Use for inked comic and manga panels: bold varying strokes with the flat " +
            "mid-tones dropped, so the result reads as ink rather than as shading.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoiseStrength = 0.5f,
                claheClip = 3.5f,
                contrast = 0.25f,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                sensitivity = 0.62f,
                dogSigma = 1.1f,
                // "the flat mid-tones dropped, so the result reads as ink rather than as shading" is
                // this preset's own description, and at 0.42 it did the exact opposite: the level sat
                // at 0.41 and every mid-tone became ink. 0.11 is a level of 0.10 — high enough to
                // still ink a genuine black, low enough that shading is not mistaken for it.
                xdogEpsilon = 0.11f,
                xdogPhi = 45f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 40,
                closeRadius = 2,
                maxGap = 14,
                pruneSpurs = 6,
            ),
            output = OutputParams(
                simplify = 1.4f,
                fitError = 1.8f,
                corner = 105f,
                smoothIterations = 2,
                strokeWidth = 2.4f,
                modulateWidth = true,
                widthScale = 1.15f,
                minPathLength = 8f,
            ),
            styleId = "comic",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 8 · architectural
    // -------------------------------------------------------------------------------------------

    private val ARCHITECTURAL = StylePreset(
        id = "architectural",
        name = "Architectural",
        description = "Use for buildings and interiors photographed from the ground: the frame is " +
            "rectified first so verticals come back vertical.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(
                perspectiveCorrect = true,
                workingLongEdge = 2560,
                denoiseStrength = 0.4f,
            ),
            edge = EdgeParams(
                // Canny, not FDoG: a building is made of genuinely straight geometric edges, and
                // FDoG's flow smoothing is designed to curve a stroke along its neighbours.
                engine = EdgeEngine.CANNY,
                sensitivity = 0.45f,
                blurSigma = 1.4f,
                // Explicit hysteresis rather than the auto rule. `EdgeCanny.detectAuto` puts its
                // thresholds at (1 ± 0.33) × the MEDIAN gradient magnitude, and on a photograph the
                // median gradient *is* the sensor noise: measured, the median was 0.00045 while the
                // real edges sat at 0.036 and the smooth body at 0.001, so auto seeded half the
                // frame. These two sit above the body's 99th percentile and below the weakest real
                // edge, and hysteresis grows the rest.
                cannyLow = 0.004f,
                cannyHigh = 0.012f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 32,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 5,
                maxGap = 10,
            ),
            output = OutputParams(
                simplify = 2f,
                fitError = 1.2f,
                corner = 155f,
                smoothIterations = 0,
                strokeWidth = 1.4f,
                minPathLength = 6f,
            ),
            styleId = "architectural",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 9 · continuous-line
    // -------------------------------------------------------------------------------------------

    private val CONTINUOUS_LINE = StylePreset(
        id = "continuous-line",
        name = "Continuous line",
        description = "Use for the one-line-drawing look: a long flow radius and a wide bridging " +
            "reach merge the strokes into a handful of very long paths.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.6f),
            edge = EdgeParams(
                sensitivity = 0.42f,
                flow = FlowSettings(
                    etfIterations = 5,
                    etfRadius = 7,
                    sigmaC = 1.4f,
                    // σm is the "how long is a stroke" knob and this preset is entirely about it.
                    sigmaM = 8f,
                    tau = 0.985f,
                ),
            ),
            cleanup = CleanupParams(
                minBlobArea = 120,
                closeRadius = 3,
                maxGap = 40,
                // A wide cone as well as a long reach: a continuous line is allowed to double back,
                // which a 60° cone rejects.
                maxBridgeAngle = 110f,
                pruneSpurs = 12,
            ),
            output = OutputParams(
                simplify = 2.4f,
                fitError = 3f,
                corner = 70f,
                smoothIterations = 3,
                strokeWidth = 2f,
                minPathLength = 40f,
            ),
            styleId = "continuous-line",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 10 · single-stroke
    // -------------------------------------------------------------------------------------------

    private val SINGLE_STROKE = StylePreset(
        id = "single-stroke",
        name = "Single stroke",
        description = "Use for pen plotters, engravers and CNC: the fewest and longest centreline " +
            "tool paths, with every short hop removed rather than travelled.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoise = DenoiseMode.MEDIAN,
                medianRadius = 2,
            ),
            edge = EdgeParams(
                // A plotter source is nearly always already line art, and ALGORITHMS §7.4 is explicit
                // that running an edge detector over line art traces *both sides* of every stroke.
                engine = EdgeEngine.ADAPTIVE,
                adaptiveRadius = 21,
                adaptiveC = 0.03f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 64,
                closeRadius = 2,
                maxGap = 24,
                maxBridgeAngle = 90f,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 14,
            ),
            output = OutputParams(
                simplify = 3f,
                fitError = 3.5f,
                corner = 90f,
                smoothIterations = 2,
                strokeWidth = 1f,
                // A pen-down / pen-up cycle costs more time than a 60 px path is worth drawing.
                minPathLength = 60f,
            ),
            styleId = "single-stroke",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 11 · calligraphy
    // -------------------------------------------------------------------------------------------

    private val CALLIGRAPHY = StylePreset(
        id = "calligraphy",
        name = "Calligraphy",
        description = "Use for brush and broad-nib lettering: the distance transform modulates the " +
            "width so the thick-and-thin of the original stroke survives into the vector.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.4f),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                sensitivity = 0.55f,
                // A level of 0.08, corrected for φ = 12. Slightly above the plain line styles: a
                // brush stroke's edge is soft, so its response is weaker than a printed line's.
                xdogEpsilon = 0.123f,
                xdogPhi = 12f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 20,
                maxGap = 8,
                pruneSpurs = 3,
            ),
            output = OutputParams(
                simplify = 0.9f,
                fitError = 1.5f,
                corner = 110f,
                smoothIterations = 2,
                strokeWidth = 1.8f,
                modulateWidth = true,
                widthScale = 1.35f,
                minPathLength = 4f,
            ),
            styleId = "calligraphy",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 12 · woodcut
    // -------------------------------------------------------------------------------------------

    private val WOODCUT = StylePreset(
        id = "woodcut",
        name = "Woodcut",
        description = "Use for woodcut and linocut looks: solid filled blocks with chisel-like " +
            "corners, rather than lines of even weight.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoiseStrength = 0.55f,
                claheClip = 4f,
                contrast = 0.3f,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                sensitivity = 0.58f,
                dogSigma = 1.6f,
                dogTau = 0.99f,
                // The one preset that keeps a real tone response, because "solid filled blocks" is
                // what a woodcut is. A level of 0.18 fills a genuinely dark mass and leaves a lit
                // mid-tone alone; every line preset here sits at 0.05.
                xdogEpsilon = 0.182f,
                // The hardest transition in the set: a woodcut has no soft edges anywhere.
                xdogPhi = 300f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 60,
                closeRadius = 2,
                openRadius = 1,
                bridgeGaps = false,
                skeletonize = false,
                pruneSpurs = 0,
                fillHolesUpTo = 24,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 1.6f,
                fitError = 2.4f,
                corner = 135f,
                smoothIterations = 0,
                strokeWidth = 0.8f,
                minPathLength = 8f,
                fillClosed = true,
            ),
            styleId = "woodcut",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 13 · stencil
    // -------------------------------------------------------------------------------------------

    private val STENCIL = StylePreset(
        id = "stencil",
        name = "Stencil",
        description = "Use for spray and screen stencils: holes are filled and islands dropped so " +
            "every shape that remains is one continuous, cuttable region.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.6f),
            edge = EdgeParams(
                engine = EdgeEngine.ADAPTIVE,
                adaptiveRadius = 25,
                adaptiveC = 0.03f,
            ),
            cleanup = CleanupParams(
                // The three numbers that make a stencil physically possible: nothing thinner than a
                // bridge, no speck small enough to fall out, no hole small enough to tear.
                minBlobArea = 200,
                closeRadius = 3,
                openRadius = 2,
                bridgeGaps = false,
                skeletonize = false,
                pruneSpurs = 0,
                fillHolesUpTo = 400,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 2.2f,
                fitError = 3f,
                corner = 120f,
                strokeWidth = 1f,
                minPathLength = 20f,
                // A stencil is a cut file: closed paths a blade follows, with the holes that survive
                // `fillHolesUpTo` kept as their own closed paths. Painting the regions solid would
                // both hide those holes and give the operator a black rectangle to cut around.
                // Matches the TypeScript preset.
                fillClosed = false,
            ),
            styleId = "stencil",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 14 · silhouette
    // -------------------------------------------------------------------------------------------

    private val SILHOUETTE = StylePreset(
        id = "silhouette",
        name = "Silhouette",
        description = "Use when only the subject's outline matters: the background is matted away " +
            "and only the largest few shapes are kept, filled solid.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.6f),
            // The one preset that opts into a matte, because a silhouette *is* a figure-ground
            // decision. The pipeline still reports in TraceResult.notes how much of the frame the
            // matte removed, so a matte that ate the subject is visible rather than mysterious.
            matte = MatteParams(mode = MatteMode.SALIENCY, feather = 2f),
            edge = EdgeParams(
                engine = EdgeEngine.ADAPTIVE,
                adaptiveRadius = 41,
                adaptiveC = 0.02f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 400,
                closeRadius = 4,
                openRadius = 2,
                bridgeGaps = false,
                skeletonize = false,
                pruneSpurs = 0,
                fillHolesUpTo = 5000,
                keepLargest = 3,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 2.5f,
                fitError = 3.5f,
                corner = 115f,
                smoothIterations = 2,
                strokeWidth = 1f,
                minPathLength = 24f,
                fillClosed = true,
            ),
            styleId = "silhouette",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 15 · engraving
    // -------------------------------------------------------------------------------------------

    private val ENGRAVING = StylePreset(
        id = "engraving",
        name = "Engraving",
        description = "Use for banknote, copper-plate and steel engraving: the finest hatching is " +
            "resolved without adjacent lines merging into a block.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(
                // Hatching at 3 000 px is hatching; at 2 048 px adjacent lines land in the same pixel
                // and no later stage can separate them again.
                workingLongEdge = 3072,
                denoise = DenoiseMode.NONE,
                claheClip = 3.5f,
                claheTiles = 12,
                unsharpAmount = 0.9f,
                unsharpSigma = 1f,
            ),
            edge = EdgeParams(
                engine = EdgeEngine.XDOG,
                sensitivity = 0.66f,
                blurSigma = 0.7f,
                dogSigma = 0.6f,
                dogK = 1.5f,
                dogTau = 0.995f,
                // `sensitivity` moves the level by only atanh(t−1)/φ — 0.002 across its whole travel
                // at φ = 220 — so "the finest hatching is resolved" has to be spent on ε. 0.122 is a
                // level of 0.12, the highest of the line presets.
                xdogEpsilon = 0.122f,
                xdogPhi = 220f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 6,
                // Isolated-pixel removal and morphological closing both fuse neighbouring hatch
                // lines, which is the one failure this preset cannot recover from.
                removeIsolated = false,
                closeRadius = 0,
                bridgeGaps = false,
                pruneSpurs = 1,
            ),
            output = OutputParams(
                simplify = 0.4f,
                fitError = 0.9f,
                corner = 125f,
                smoothIterations = 0,
                strokeWidth = 0.7f,
                // Working pixels, as in `mandala`: the finest hatching this preset exists for is
                // hundreds of pixels long at 2048, so nothing 1.5 px long is hatching.
                minPathLength = 6f,
            ),
            styleId = "engraving",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 16 · colouring-book
    // -------------------------------------------------------------------------------------------

    private val COLOURING_BOOK = StylePreset(
        id = "colouring-book",
        name = "Colouring book",
        description = "Use to make a printable colouring page: every region is closed and every " +
            "small detail dropped, so a bucket fill or a crayon cannot leak out.",
        group = GROUP_EDUCATION,
        params = TraceParams(
            preprocess = PreprocessParams(
                denoiseStrength = 0.65f,
                claheClip = 2.5f,
            ),
            edge = EdgeParams(
                sensitivity = 0.4f,
                flow = FlowSettings(sigmaM = 4f),
            ),
            cleanup = CleanupParams(
                // The whole preset in three numbers: nothing small survives, a 3 px close joins what
                // nearly touches, and a 28 px bridge joins what does not. Morphological closing alone
                // cannot span 28 px without also fusing adjacent strokes — ALGORITHMS §9, stage 2.
                minBlobArea = 260,
                closeRadius = 3,
                maxGap = 28,
                maxBridgeAngle = 95f,
                skeletonize = false,
                pruneSpurs = 0,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 1.8f,
                fitError = 2.6f,
                smoothIterations = 2,
                // Uniform and heavy: a colouring line has to be visible under a crayon, and a
                // modulated width would taper to nothing at exactly the places a child aims for.
                strokeWidth = 2.6f,
                minPathLength = 24f,
                background = 0xFFFFFFFF.toInt(),
            ),
            styleId = "colouring-book",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 17 · embroidery
    // -------------------------------------------------------------------------------------------

    private val EMBROIDERY = StylePreset(
        id = "embroidery",
        name = "Embroidery",
        description = "Use for machine embroidery and satin stitch: long centreline runs wide " +
            "enough to sew, with the fabric weave diffused away first.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
                // Perona–Malik is the only filter here that flattens a woven texture without also
                // flattening the pattern printed on top of it.
                denoise = DenoiseMode.ANISOTROPIC,
                denoiseStrength = 0.7f,
            ),
            edge = EdgeParams(
                sensitivity = 0.45f,
                flow = FlowSettings(sigmaM = 5f),
            ),
            cleanup = CleanupParams(
                minBlobArea = 180,
                closeRadius = 2,
                maxGap = 20,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 10,
            ),
            output = OutputParams(
                simplify = 2.6f,
                fitError = 3.2f,
                corner = 85f,
                smoothIterations = 3,
                strokeWidth = 2.2f,
                modulateWidth = true,
                widthScale = 1.2f,
                // A stitch run shorter than this costs more in trims and thread jumps than it adds.
                minPathLength = 45f,
            ),
            styleId = "embroidery",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 18 · laser-cut
    // -------------------------------------------------------------------------------------------

    private val LASER_CUT = StylePreset(
        id = "laser-cut",
        name = "Laser cut",
        description = "Use for laser and vinyl cutting: closed hairline outlines in cut-red, with " +
            "anything smaller than the kerf removed.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.6f),
            edge = EdgeParams(
                engine = EdgeEngine.ADAPTIVE,
                adaptiveRadius = 31,
                adaptiveC = 0.025f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 320,
                closeRadius = 3,
                openRadius = 2,
                bridgeGaps = false,
                skeletonize = false,
                pruneSpurs = 0,
                fillHolesUpTo = 120,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 2f,
                fitError = 2f,
                corner = 130f,
                smoothIterations = 1,
                // A hairline, because the cutter follows the path itself and a wide stroke would be
                // read by some drivers as two cuts a stroke-width apart.
                strokeWidth = 0.3f,
                minPathLength = 30f,
                strokeColor = 0xFFFF0000.toInt(),
            ),
            styleId = "laser-cut",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 19 · craft-pattern
    // -------------------------------------------------------------------------------------------

    private val CRAFT_PATTERN = StylePreset(
        id = "craft-pattern",
        name = "Craft pattern",
        description = "Use for printable cut-and-paste templates: a small number of large closed " +
            "shapes at a weight that survives a home printer and a pair of scissors.",
        group = GROUP_EDUCATION,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.7f),
            edge = EdgeParams(
                sensitivity = 0.38f,
                flow = FlowSettings(sigmaM = 4.5f),
            ),
            cleanup = CleanupParams(
                minBlobArea = 400,
                closeRadius = 3,
                maxGap = 22,
                skeletonize = false,
                pruneSpurs = 0,
                // A template with 300 pieces is not a template. This is the cap that makes the sheet
                // usable, and the pipeline reports how many shapes it cost.
                keepLargest = 12,
            ),
            output = OutputParams(
                vectorMode = VectorModeParam.OUTLINE,
                simplify = 3f,
                fitError = 3.6f,
                corner = 95f,
                smoothIterations = 3,
                strokeWidth = 2f,
                minPathLength = 36f,
                background = 0xFFFFFFFF.toInt(),
            ),
            styleId = "craft-pattern",
        ),
    )

    // -------------------------------------------------------------------------------------------
    // 20 · minimal
    // -------------------------------------------------------------------------------------------

    private val MINIMAL = StylePreset(
        id = "minimal",
        name = "Minimal",
        description = "Use when you want the fewest lines that still read as the subject: the " +
            "lowest sensitivity, the largest blob filter and the heaviest simplification here.",
        group = GROUP_DRAWING,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.75f),
            edge = EdgeParams(
                sensitivity = 0.28f,
                // The lowest ink level in the set (0.03), which is what "the fewest lines" costs:
                // only the strongest edges answer at all.
                xdogEpsilon = 0.067f,
                flow = FlowSettings(sigmaM = 6f, tau = 0.995f),
            ),
            cleanup = CleanupParams(
                minBlobArea = 600,
                closeRadius = 2,
                maxGap = 16,
                pruneSpurs = 16,
                keepLargest = 24,
            ),
            output = OutputParams(
                simplify = 3.6f,
                fitError = 4.5f,
                corner = 65f,
                smoothIterations = 3,
                strokeWidth = 2.8f,
                minPathLength = 90f,
            ),
            styleId = "minimal",
        ),
    )

    /**
     * The twenty presets in display order. The order and the ids are part of the persisted contract;
     * append to the end, never renumber.
     */
    val ALL: List<StylePreset> = listOf(
        CLEAN_LINE,
        PENCIL_SKETCH,
        TECHNICAL_DRAWING,
        BLUEPRINT,
        TATTOO_OUTLINE,
        MANDALA,
        COMIC,
        ARCHITECTURAL,
        CONTINUOUS_LINE,
        SINGLE_STROKE,
        CALLIGRAPHY,
        WOODCUT,
        STENCIL,
        SILHOUETTE,
        ENGRAVING,
        COLOURING_BOOK,
        EMBROIDERY,
        LASER_CUT,
        CRAFT_PATTERN,
        MINIMAL,
    )

    /**
     * Lookup index built once. A linear scan of twenty entries is cheap, but `byId` is called on
     * every parameter change, every project load and every batch item, and a map makes the cost
     * something nobody has to think about again.
     */
    private val index: Map<String, StylePreset> = LinkedHashMap<String, StylePreset>(ALL.size * 2).apply {
        for (s in ALL) put(s.id, s)
    }

    /** @return the preset with this id, trimmed, or `null`. Callers fall back to [default]. */
    fun byId(id: String): StylePreset? = index[id.trim()]

    /**
     * The preset a blank, unknown or outdated id resolves to.
     *
     * Reads through [byId] rather than referencing `CLEAN_LINE` directly so that the default and the
     * id written into [Limits.DEFAULT_STYLE_ID] cannot drift apart silently.
     */
    fun default(): StylePreset = index[Limits.DEFAULT_STYLE_ID] ?: ALL[0]

    /** @return the distinct group names in the order they first appear in [ALL]. */
    fun groups(): List<String> {
        val out = ArrayList<String>(4)
        for (s in ALL) if (!out.contains(s.group)) out.add(s.group)
        return out
    }

    /** @return the presets in [group], in display order; empty for an unknown group. */
    fun inGroup(group: String): List<StylePreset> {
        val out = ArrayList<StylePreset>(ALL.size)
        for (s in ALL) if (s.group == group) out.add(s)
        return out
    }
}
