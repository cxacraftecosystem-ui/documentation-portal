package com.offlinetracer.pipeline

import com.offlinetracer.imaging.Classify
import kotlin.math.roundToInt

data class SubjectPreset(
    val id: String,
    val name: String,
    val hint: String,
    val adjust: (TraceParams) -> TraceParams,
)

object Subjects {

    private fun scale(v: Float, factor: Float): Float = v * factor

    private fun scale(v: Int, factor: Float): Int {
        if (v <= 0) return v
        val r = (v * factor).roundToInt()
        return if (r < 1) 1 else r
    }

    private fun raise(v: Float, delta: Float): Float = v + delta

    private fun separateSubject(m: MatteParams): MatteParams =
        if (m.mode == MatteMode.NONE) m.copy(mode = MatteMode.SUBJECT) else m

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

    fun byId(id: String): SubjectPreset? = index[id.trim()]

    fun default(): SubjectPreset = index["photo"] ?: ALL[0]

    private const val TEXTURE_COLOUR_SPLIT = 0.12f

    fun suggestFor(profile: Classify.SourceProfile): SubjectPreset =
        when (Classify.kindOf(profile)) {
            Classify.SourceKind.LINE_ART -> SKETCH
            Classify.SourceKind.FLAT_GRAPHIC -> LOGO
            Classify.SourceKind.TEXTURED ->
                if (profile.saturationSpread < TEXTURE_COLOUR_SPLIT) STONE_CARVING else TEXTILE
            Classify.SourceKind.SMOOTH_OBJECT -> POTTERY
            Classify.SourceKind.PHOTOGRAPH, Classify.SourceKind.UNKNOWN -> PHOTO
        }
}

data class AutoDecision(
    val params: TraceParams,
    val applied: Boolean,
    val kind: Classify.SourceKind,
    val confidence: Float,
    val subject: SubjectPreset?,
    val notes: List<String>,
)

object Auto {

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
            val preview = ArrayList<String>(notes.size + 1)
            preview.add(
                "Auto-detection is set to suggest only, so nothing below was actually changed."
            )
            preview.addAll(notes)
            return AutoDecision(params, false, kind, confidence, subject, preview)
        }
        return AutoDecision(restored, true, kind, confidence, subject, notes)
    }

    private fun matteMode(
        params: TraceParams,
        auto: AutoParams,
        profile: Classify.SourceProfile,
    ): MatteMode = when {
        params.matte.mode != MatteMode.NONE -> params.matte.mode
        auto.allowMatte && profile.separableSubject -> MatteMode.SUBJECT
        else -> MatteMode.NONE
    }

    private fun preserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
        edge = after.edge.copy(engine = before.edge.engine),
        output = after.output.copy(
            vectorMode = before.output.vectorMode,
            fillClosed = before.output.fillClosed,
        ),
        styleId = before.styleId,
        auto = before.auto,
    )

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

    private fun list(items: List<String>): String = when (items.size) {
        0 -> "nothing"
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.subList(0, items.size - 1).joinToString(", ") + " and " + items.last()
    }

    private fun percent(v: Float): Int {
        if (!v.isFinite()) return 0
        val p = (v * 100f).roundToInt()
        return if (p < 0) 0 else if (p > 100) 100 else p
    }
}
