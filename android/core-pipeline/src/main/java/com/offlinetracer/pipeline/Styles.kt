package com.offlinetracer.pipeline

data class StylePreset(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val params: TraceParams,
)

object Styles {

    const val GROUP_DRAWING = "Drawing"
    const val GROUP_PRINT = "Print & relief"
    const val GROUP_FABRICATION = "Fabrication"
    const val GROUP_EDUCATION = "Education"

    private val CLEAN_LINE = StylePreset(
        id = "clean-line",
        name = "Clean line",
        description = "Start here for a photograph of almost any subject: flow-based edges give long, " +
            "coherent strokes where a per-pixel detector gives broken fragments.",
        group = GROUP_DRAWING,
        params = TraceParams(styleId = "clean-line"),
    )

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
                xdogEpsilon = 0.27f,
                xdogPhi = 3f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 12,
                pruneSpurs = 2,
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

    private val TECHNICAL_DRAWING = StylePreset(
        id = "technical-drawing",
        name = "Technical drawing",
        description = "Use for engineering drawings, schematics and CAD printouts, where a straight " +
            "run must stay straight and a corner must stay square.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
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
                corner = 150f,
                smoothIterations = 0,
                strokeWidth = 1f,
                minPathLength = 4f,
            ),
            styleId = "technical-drawing",
        ),
    )

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
                xdogEpsilon = 0.054f,
                xdogPhi = 150f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 20,
                thinning = ThinningMode.GUO_HALL,
                pruneSpurs = 3,
                maxGap = 8,
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
                minPathLength = 6f,
            ),
            styleId = "mandala",
        ),
    )

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
                engine = EdgeEngine.CANNY,
                sensitivity = 0.45f,
                blurSigma = 1.4f,
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
                    sigmaM = 8f,
                    tau = 0.985f,
                ),
            ),
            cleanup = CleanupParams(
                minBlobArea = 120,
                closeRadius = 3,
                maxGap = 40,
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
                minPathLength = 60f,
            ),
            styleId = "single-stroke",
        ),
    )

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
                xdogEpsilon = 0.182f,
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
                fillClosed = false,
            ),
            styleId = "stencil",
        ),
    )

    private val SILHOUETTE = StylePreset(
        id = "silhouette",
        name = "Silhouette",
        description = "Use when only the subject's outline matters: the background is matted away " +
            "and only the largest few shapes are kept, filled solid.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(denoiseStrength = 0.6f),
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

    private val ENGRAVING = StylePreset(
        id = "engraving",
        name = "Engraving",
        description = "Use for banknote, copper-plate and steel engraving: the finest hatching is " +
            "resolved without adjacent lines merging into a block.",
        group = GROUP_PRINT,
        params = TraceParams(
            preprocess = PreprocessParams(
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
                xdogEpsilon = 0.122f,
                xdogPhi = 220f,
            ),
            cleanup = CleanupParams(
                minBlobArea = 6,
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
                minPathLength = 6f,
            ),
            styleId = "engraving",
        ),
    )

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
                strokeWidth = 2.6f,
                minPathLength = 24f,
                background = 0xFFFFFFFF.toInt(),
            ),
            styleId = "colouring-book",
        ),
    )

    private val EMBROIDERY = StylePreset(
        id = "embroidery",
        name = "Embroidery",
        description = "Use for machine embroidery and satin stitch: long centreline runs wide " +
            "enough to sew, with the fabric weave diffused away first.",
        group = GROUP_FABRICATION,
        params = TraceParams(
            preprocess = PreprocessParams(
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
                minPathLength = 45f,
            ),
            styleId = "embroidery",
        ),
    )

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
                strokeWidth = 0.3f,
                minPathLength = 30f,
                strokeColor = 0xFFFF0000.toInt(),
            ),
            styleId = "laser-cut",
        ),
    )

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

    private val index: Map<String, StylePreset> = LinkedHashMap<String, StylePreset>(ALL.size * 2).apply {
        for (s in ALL) put(s.id, s)
    }

    fun byId(id: String): StylePreset? = index[id.trim()]

    fun default(): StylePreset = index[Limits.DEFAULT_STYLE_ID] ?: ALL[0]

    fun groups(): List<String> {
        val out = ArrayList<String>(4)
        for (s in ALL) if (!out.contains(s.group)) out.add(s.group)
        return out
    }

    fun inGroup(group: String): List<StylePreset> {
        val out = ArrayList<StylePreset>(ALL.size)
        for (s in ALL) if (s.group == group) out.add(s)
        return out
    }
}
