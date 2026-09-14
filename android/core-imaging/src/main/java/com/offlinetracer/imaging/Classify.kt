package com.offlinetracer.imaging

import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Source classification (ALGORITHMS §12): cheap, deterministic statistics that pick the right preset
 * far more often than a fixed default does, plus the **confidence** that decides whether acting on
 * them is defensible at all.
 *
 * ### Why there is a confidence and not just three booleans
 *
 * §12 was written when the classification was only ever *shown*. A label that is wrong costs the
 * user one glance. A label that is wrong and has been **applied** costs them a trace they cannot
 * explain, because the settings they can see are not the settings that ran. So every consumer that
 * acts needs to know how sure this is, and the answer has to be a number rather than a flag: the
 * right response to "probably a textile" is not the same as the response to "certainly line art".
 *
 * [SourceProfile.confidence] is therefore built from two independent parts — how much evidence the
 * winning class has, and how far ahead of the runner-up it is. A frame that scores 0.8 for two
 * classes at once is *ambiguous*, not confident, and the margin term is what says so. A caller that
 * refuses to act below a threshold then behaves correctly on the case that matters most: the one
 * where the image genuinely could be either thing.
 *
 * ### Why these statistics
 *
 * Four numbers were not enough to choose a pipeline with. Each of the added ones answers a question
 * the original four cannot, and each is O(pixels) with no iteration and no randomness:
 *
 *  - **background uniformity** — is there a clean backdrop? This is the single question that decides
 *    whether background separation may run at all, and no combination of histogram statistics
 *    answers it, because a busy background and a busy subject produce the same histogram.
 *  - **subject coverage** — how much of the frame the thing occupies. A subject filling 4% of the
 *    frame and one filling 95% want different working resolutions and different blob floors, and
 *    they are indistinguishable by every other measure here.
 *  - **stroke-width statistics** — the distance transform's ridge values are the half-widths of
 *    whatever ink the source contains. Line art has a *tight* distribution (a pen has one nib); a
 *    photograph's dark regions have a distribution as wide as the objects in it. This is the
 *    strongest single discriminator between "already drawn" and "photographed", and it is the one
 *    thing bimodality gets wrong most often — a white sculpture on a black cloth is strongly
 *    bimodal and is not line art.
 *  - **colour count and palette flatness** — a screenprint spends most of its pixels in a handful of
 *    colour cells. A photograph never does, however few *distinct* colours it appears to have.
 *  - **dominant-orientation concentration** — textiles, engravings and architecture put most of
 *    their gradient energy in one or two directions; a thrown pot puts it in all of them. Entropy
 *    measures the spread of the whole distribution and stays high for a *bimodal* orientation field
 *    (a woven fabric has two peaks), so peak concentration is a genuinely different measurement.
 *  - **texture energy** — mean |∇²| separates a glazed surface from a woven one at equal edge
 *    density, which is exactly the pair edge density alone confuses.
 *
 * Everything is measured on a proxy no larger than [PROXY_LONG_EDGE] on the long edge, which is
 * also what keeps the answer stable: classification is a coarse-scale question and measuring it at
 * full resolution makes it a measurement of the camera's sensor noise.
 */
object Classify {

    /**
     * What the source *is*, as one of five mutually exclusive answers.
     *
     * Deliberately not "which subject preset" — the subject lists are per-client and longer than
     * this, and a classifier that named preset ids would have to change whenever a picker gained a
     * row. Each client maps a kind onto its own preset (`Subjects.suggestFor`).
     *
     * [UNKNOWN] is not a sixth class but the absence of one: it is what a [SourceProfile] carries
     * when it was constructed by hand rather than measured, and consumers must treat it as "no
     * evidence" rather than as a classification.
     */
    enum class SourceKind { UNKNOWN, LINE_ART, FLAT_GRAPHIC, TEXTURED, SMOOTH_OBJECT, PHOTOGRAPH }

    /**
     * Evidence for each class, 0..1 and **not** normalised to sum to 1.
     *
     * They are not probabilities and are not presented as any: two classes are allowed to score 0.9
     * at once, and that co-occurrence is precisely the signal [SourceProfile.confidence] reads.
     * Normalising would destroy it — a softmax over two 0.9s and over two 0.3s produce the same
     * pair of 0.5s, and only one of those two frames is worth acting on.
     *
     * Five named fields rather than a map or an array because a [SourceProfile] is compared with
     * `==` by its own tests: Kotlin's data-class equality compares an array by identity, so an
     * array field would make two identical profiles unequal.
     */
    data class ClassScores(
        val lineArt: Float = 0f,
        val flatGraphic: Float = 0f,
        val textured: Float = 0f,
        val smoothObject: Float = 0f,
        val photograph: Float = 0f,
    ) {
        /** @return the score for [kind]; 0 for [SourceKind.UNKNOWN], which no class scores for. */
        fun of(kind: SourceKind): Float = when (kind) {
            SourceKind.LINE_ART -> lineArt
            SourceKind.FLAT_GRAPHIC -> flatGraphic
            SourceKind.TEXTURED -> textured
            SourceKind.SMOOTH_OBJECT -> smoothObject
            SourceKind.PHOTOGRAPH -> photograph
            SourceKind.UNKNOWN -> 0f
        }
    }

    /**
     * @param bimodality Otsu's between-class variance ratio σ²b/σ²total, 0..1. High means the
     *   histogram is two separated lumps.
     * @param edgeDensity Fraction of pixels surviving Canny at auto thresholds, 0..1.
     * @param orientationEntropy Shannon entropy of the magnitude-weighted gradient orientation
     *   histogram, normalised to 0..1. Low means the edges point in a few directions only.
     * @param saturationSpread Standard deviation of HSV saturation across the frame.
     * @param suggestion One human-readable sentence naming the recommended preset and why.
     * @param backgroundUniformity 0..1; 1 is a perfectly even backdrop. Below ~0.6 there is no
     *   clean background and background separation must not be offered.
     * @param subjectCoverage Fraction of the frame that differs from the border colour, 0..1.
     * @param strokeWidthPx Mean ink stroke width in **proxy** pixels, measured at the ridge of the
     *   distance transform. 0 when the source contains no ink at all.
     * @param strokeWidthConsistency 0..1; 1 is "every stroke is the same width", which is what a pen
     *   or a printing plate produces and what a photograph never does.
     * @param colourCount Number of 4-bit-per-channel colour cells holding at least 0.2% of pixels.
     * @param paletteFlatness Fraction of the frame spent in the four most-used colour cells, 0..1.
     * @param orientationConcentration Fraction of gradient energy in the strongest orientation and
     *   its two neighbouring bins, 0..1. High means "strongly directional".
     * @param dominantOrientation Radians in `[0, π)`, at the centre of the strongest bin. Meaningless
     *   when [orientationConcentration] is low, and reported anyway so a caller can say so.
     * @param textureEnergy Mean |∇²I| normalised against [TEXTURE_ENERGY_FULL], 0..1.
     * @param separableSubject Whether there is one subject on a clean enough background for a matte
     *   to be worth running. **This, and not [subjectCoverage], is the matte's precondition.**
     * @param scores Per-class evidence; see [ClassScores].
     * @param kind The winning class, or [SourceKind.UNKNOWN] for a profile that was never measured.
     * @param confidence 0..1 in the winning [kind]. A consumer that *acts* must have a floor on this.
     *
     * Every field after [suggestion] carries a default so that a caller which only has the four
     * statistics §12 originally specified — a restored project, a hand-built test fixture — still
     * constructs a legal profile. Those defaults are all "no evidence", which is what makes
     * [SourceKind.UNKNOWN] and a confidence of 0 the honest answer for such a profile rather than an
     * accidental classification.
     */
    data class SourceProfile(
        val bimodality: Float,
        val edgeDensity: Float,
        val orientationEntropy: Float,
        val saturationSpread: Float,
        val isLineArt: Boolean,
        val isHighTexture: Boolean,
        val isFlatGraphic: Boolean,
        val suggestion: String,
        val backgroundUniformity: Float = 0f,
        val subjectCoverage: Float = 0f,
        val strokeWidthPx: Float = 0f,
        val strokeWidthConsistency: Float = 0f,
        val colourCount: Int = 0,
        val paletteFlatness: Float = 0f,
        val orientationConcentration: Float = 0f,
        val dominantOrientation: Float = 0f,
        val textureEnergy: Float = 0f,
        val separableSubject: Boolean = false,
        val scores: ClassScores = ClassScores(),
        val kind: SourceKind = SourceKind.UNKNOWN,
        val confidence: Float = 0f,
    )

    /**
     * Figure/ground, measured from the frame's own border.
     *
     * The border ring is the only part of a photograph that is background with near certainty — a
     * subject that runs off all four edges is not a subject anybody is trying to cut out — so its
     * colour statistics answer both "is the backdrop clean" and "how much of the frame is not the
     * backdrop" in one pass, with no seeds, no matte, no model and no user input.
     *
     * Two numbers and no rectangle, deliberately. Where the subject *is* is a decision, and it is
     * `Subject.locate`'s: that one mattes the frame properly and refuses in four documented ways.
     * This one is the classifier's own estimate — it has to run before any matte exists and on every
     * profile, so it has to be a ring statistic rather than a segmentation, and a box derived from it
     * would be a second, cheaper, slightly different answer to a question that already has an owner.
     */
    data class FigureGround(val backgroundUniformity: Float, val coverage: Float)

    /** Statistics are stable well below full resolution and this keeps classification off the clock. */
    const val PROXY_LONG_EDGE = 512

    /** §12 thresholds. Named, because a bare `0.75f` in an `if` is unreviewable. */
    private const val LINE_ART_BIMODALITY = 0.75f
    private const val LINE_ART_DARK_MODE = 0.35f
    private const val LINE_ART_LIGHT_MODE = 0.65f

    /**
     * Stroke-width consistency below which a bimodal source is **not** line art.
     *
     * §12's own text warns that separability plus two end-of-range modes is not enough, and names
     * the photograph of a light object on a dark ground as the counter-example. It is not the only
     * one: a two-colour poster of a dark shape on cream is *perfectly* bimodal with both modes at
     * the ends, and thresholding it as if it were a pen drawing throws away the fact that it is a
     * region and not a stroke. What separates them is that a stroke has a width and a shape does
     * not: measured on a three-colour flat graphic the stroke-width consistency is 0.48 against a
     * pen drawing's 0.93, because the "strokes" are the medial axes of solid blocks and their widths
     * span the whole shape. 0.6 sits in the middle of that gap.
     */
    private const val LINE_ART_STROKE_CONSISTENCY = 0.60f

    private const val HIGH_TEXTURE_EDGE_DENSITY = 0.18f
    private const val FLAT_GRAPHIC_ENTROPY = 0.72f
    private const val FLAT_GRAPHIC_MAX_COLOURS = 16

    /** Orientation bins over 0..π. 36 gives 5° resolution, which is finer than any decision here. */
    private const val ORIENTATION_BINS = 36

    /**
     * Local standard deviation, in 0..1 intensity units, above which a pixel counts as textured, and
     * the radius it is measured over.
     *
     * 0.06 is about fifteen 8-bit code values across a 7×7 window: far above the ±2–4 codes of
     * sensor noise on a well-lit photograph, far below the full swing of a woven or hammered
     * surface. The radius is the smallest that can see a repeat rather than a single edge.
     */
    private const val TEXTURE_LOCAL_STDDEV = 0.06f
    private const val TEXTURE_RADIUS = 3

    /**
     * Dynamic range below which the frame has no content to classify.
     *
     * Two 8-bit code values. Below that there is nothing to measure and every statistic degenerates
     * — a blank scan and a 1×1 thumbnail both land here — and the honest answer is
     * [SourceKind.UNKNOWN] with zero confidence rather than whichever class happens to score highest
     * on an image of nothing. Getting this wrong is not academic: without the guard a pure white
     * frame classified as a flat graphic at 69% confidence, which is a confident answer about an
     * empty page.
     */
    private const val BLANK_RANGE = 2f / 255f

    /** Width of the border ring sampled for background statistics, as a fraction of the short edge. */
    private const val BORDER_BAND_FRACTION = 0.06f

    /**
     * Per-channel standard deviation (0..255) of the border ring at which uniformity reaches 0.
     *
     * 32 is an eighth of the range: a backdrop varying by more than that is a *scene*, not a
     * backdrop, and no matte should be offered for it.
     */
    private const val RING_SPREAD_FULL = 32f

    /** Colour distance from the ring mean that counts as subject, as `base + gain · ringStdDev`. */
    private const val RING_TOLERANCE_BASE = 14f
    private const val RING_TOLERANCE_GAIN = 2.5f
    private const val RING_TOLERANCE_MAX = 96f

    /** A frame is "on a clean background" from here up. */
    private const val CLEAN_BACKGROUND = 0.62f

    /** A subject smaller than this is dust; larger than this is not a subject but the whole picture. */
    private const val MIN_SUBJECT_COVERAGE = 0.02f
    private const val MAX_SUBJECT_COVERAGE = 0.75f

    /**
     * Profiles [src] and returns every statistic, the class it implies and how sure that is.
     *
     * Runs on a proxy no larger than [PROXY_LONG_EDGE] px on the long edge. Safe on any image size
     * including 1×1, where every statistic degenerates to 0, the kind is the least committal one and
     * the confidence is 0 — which is the answer that makes a caller refuse to act.
     */
    fun profile(src: RgbaImage): SourceProfile {
        val proxy = Resample.scaleToLongEdge(src, PROXY_LONG_EDGE)
        val gray = Color.toGray(proxy)

        val bimodality = clamp01(Threshold.otsuSeparability(gray))
        val threshold = Threshold.otsu(gray)
        val hist = Contrast.histogram(gray, 256)
        val darkMode = classMean(hist, 0, binOf(threshold))
        val lightMode = classMean(hist, binOf(threshold), 256)

        val edges = EdgeCanny.detectAuto(gray, 1.2f)
        val edgeDensity = edges.countTrue().toFloat() / gray.size.toFloat()

        val orientation = orientationStats(gray)
        val saturationSpread = saturationSpread(proxy)
        val palette = palette(proxy)
        val ground = figureGround(proxy)
        val strokes = strokeStats(gray, threshold)
        val textureEnergy = textureEnergy(gray)

        // The two modes must sit near the ends of the range, not merely be separated: a photograph
        // of a light object on a mid-grey table is strongly bimodal too, and thresholding it as if
        // it were a pen drawing loses everything in the mid-tones. The stroke-width term rejects the
        // other half of that family — the flat two-colour graphic, which is equally bimodal and is a
        // region rather than a stroke; see [LINE_ART_STROKE_CONSISTENCY].
        val isLineArt = bimodality > LINE_ART_BIMODALITY &&
            darkMode < LINE_ART_DARK_MODE &&
            lightMode > LINE_ART_LIGHT_MODE &&
            strokes.consistency >= LINE_ART_STROKE_CONSISTENCY
        val isHighTexture = edgeDensity > HIGH_TEXTURE_EDGE_DENSITY
        val isFlatGraphic = !isLineArt &&
            orientation.entropy < FLAT_GRAPHIC_ENTROPY &&
            palette.count <= FLAT_GRAPHIC_MAX_COLOURS

        val separableSubject = ground.backgroundUniformity >= CLEAN_BACKGROUND &&
            ground.coverage >= MIN_SUBJECT_COVERAGE &&
            ground.coverage <= MAX_SUBJECT_COVERAGE

        // A frame with no dynamic range has nothing in it to classify, and every statistic above is
        // measuring round-off. Answering UNKNOWN at zero confidence is what makes a caller that acts
        // on the classification leave it alone; answering the highest-scoring class would be a
        // confident statement about an empty page.
        val range = gray.range()
        if (range.second - range.first < BLANK_RANGE) {
            return SourceProfile(
                bimodality = bimodality,
                edgeDensity = edgeDensity,
                orientationEntropy = orientation.entropy,
                saturationSpread = saturationSpread,
                isLineArt = isLineArt,
                isHighTexture = isHighTexture,
                isFlatGraphic = isFlatGraphic,
                suggestion = "This frame is one flat tone with nothing in it to classify, so no " +
                    "preset was suggested and nothing was changed.",
                backgroundUniformity = ground.backgroundUniformity,
                subjectCoverage = ground.coverage,
                strokeWidthPx = strokes.meanWidth,
                strokeWidthConsistency = strokes.consistency,
                colourCount = palette.count,
                paletteFlatness = palette.flatness,
                orientationConcentration = orientation.concentration,
                dominantOrientation = orientation.dominant,
                textureEnergy = textureEnergy,
                separableSubject = false,
                scores = ClassScores(),
                kind = SourceKind.UNKNOWN,
                confidence = 0f,
            )
        }

        val scores = score(
            bimodality = bimodality,
            modeSplit = lightMode - darkMode,
            edgeDensity = edgeDensity,
            orientationEntropy = orientation.entropy,
            colourCount = palette.count,
            paletteFlatness = palette.flatness,
            backgroundUniformity = ground.backgroundUniformity,
            subjectCoverage = ground.coverage,
            strokeConsistency = strokes.consistency,
            textureEnergy = textureEnergy,
            isLineArt = isLineArt,
            isHighTexture = isHighTexture,
            isFlatGraphic = isFlatGraphic,
        )
        val kind = winner(scores)
        val confidence = confidenceOf(scores, kind)

        return SourceProfile(
            bimodality = bimodality,
            edgeDensity = edgeDensity,
            orientationEntropy = orientation.entropy,
            saturationSpread = saturationSpread,
            isLineArt = isLineArt,
            isHighTexture = isHighTexture,
            isFlatGraphic = isFlatGraphic,
            suggestion = suggestion(
                kind = kind,
                confidence = confidence,
                bimodality = bimodality,
                edgeDensity = edgeDensity,
                orientationEntropy = orientation.entropy,
                backgroundUniformity = ground.backgroundUniformity,
            ),
            backgroundUniformity = ground.backgroundUniformity,
            subjectCoverage = ground.coverage,
            strokeWidthPx = strokes.meanWidth,
            strokeWidthConsistency = strokes.consistency,
            colourCount = palette.count,
            paletteFlatness = palette.flatness,
            orientationConcentration = orientation.concentration,
            dominantOrientation = orientation.dominant,
            textureEnergy = textureEnergy,
            separableSubject = separableSubject,
            scores = scores,
            kind = kind,
            confidence = confidence,
        )
    }

    /**
     * The kind a [SourceProfile] implies, falling back to the §12 flags when the profile was never
     * measured.
     *
     * The fallback is what keeps a hand-constructed profile — the shape every test and every
     * restored project has — meaningful rather than silently classified as whatever the enum's first
     * entry happens to be. The order of the fallback is §12's decision list and the priority is the
     * one that document argues for: "already line art" wins, because running an edge detector over
     * existing strokes traces *both sides of every stroke* and doubles every line, which is a worse
     * failure than choosing a slightly wrong class for a photograph.
     */
    fun kindOf(profile: SourceProfile): SourceKind {
        if (profile.kind != SourceKind.UNKNOWN) return profile.kind
        return when {
            profile.isLineArt -> SourceKind.LINE_ART
            profile.isFlatGraphic -> SourceKind.FLAT_GRAPHIC
            profile.isHighTexture -> SourceKind.TEXTURED
            else -> SourceKind.PHOTOGRAPH
        }
    }

    /** Sentence-case name of a kind, for the one place a UI has to print it. */
    fun nameOf(kind: SourceKind): String = when (kind) {
        SourceKind.UNKNOWN -> "unclassified"
        SourceKind.LINE_ART -> "line art"
        SourceKind.FLAT_GRAPHIC -> "flat graphic"
        SourceKind.TEXTURED -> "textured surface"
        SourceKind.SMOOTH_OBJECT -> "object on a clean background"
        SourceKind.PHOTOGRAPH -> "photograph"
    }

    // -----------------------------------------------------------------------------------------------
    // Figure and ground
    // -----------------------------------------------------------------------------------------------

    /**
     * How clean the backdrop is, and how much of the frame is not it.
     *
     * The reference is the ring's **mean plus its spread**, not a fixed tolerance: a photograph on
     * white card and one on a mottled cloth need completely different distances to mean the same
     * thing, and a fixed number picks one of them and is wrong about the other. The tolerance widens
     * with the ring's own standard deviation for exactly that reason.
     *
     * A fully transparent pixel is background whatever its colour channels say, so an already
     * cut-out PNG measures as the cut-out it is instead of as its meaningless matte colour.
     */
    fun figureGround(src: RgbaImage): FigureGround {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = src.pixels
        val band = max(1, (min(w, h) * BORDER_BAND_FRACTION).roundToInt())

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumRR = 0.0
        var sumGG = 0.0
        var sumBB = 0.0
        var ring = 0L
        for (y in 0 until h) {
            val edgeRow = y < band || y >= h - band
            val row = y * w
            var x = 0
            while (x < w) {
                if (!edgeRow && x == band && w - band > band) {
                    // Skip the interior of a non-edge row in one jump rather than testing every
                    // pixel of a 4 MP frame for membership of a 6% ring.
                    x = w - band
                    continue
                }
                val p = px[row + x]
                val r = ((p ushr 16) and 0xFF).toDouble()
                val g = ((p ushr 8) and 0xFF).toDouble()
                val b = (p and 0xFF).toDouble()
                sumR += r
                sumG += g
                sumB += b
                sumRR += r * r
                sumGG += g * g
                sumBB += b * b
                ring++
                x++
            }
        }
        if (ring <= 0L) return FigureGround(0f, 0f)

        val inv = 1.0 / ring
        val mR = sumR * inv
        val mG = sumG * inv
        val mB = sumB * inv
        val vR = max(0.0, sumRR * inv - mR * mR)
        val vG = max(0.0, sumGG * inv - mG * mG)
        val vB = max(0.0, sumBB * inv - mB * mB)
        val sd = sqrt((vR + vG + vB) / 3.0).toFloat()
        val uniformity = clamp01(1f - sd / RING_SPREAD_FULL)
        val tolerance = min(
            RING_TOLERANCE_MAX,
            RING_TOLERANCE_BASE + RING_TOLERANCE_GAIN * sd,
        )
        val tolSq = (tolerance * tolerance).toDouble()

        var subject = 0
        for (i in 0 until n) {
            val p = px[i]
            if (((p ushr 24) and 0xFF) < 128) continue
            val dr = ((p ushr 16) and 0xFF) - mR
            val dg = ((p ushr 8) and 0xFF) - mG
            val db = (p and 0xFF) - mB
            if (dr * dr + dg * dg + db * db > tolSq) subject++
        }
        return FigureGround(uniformity, if (n <= 0) 0f else subject.toFloat() / n.toFloat())
    }

    // -----------------------------------------------------------------------------------------------
    // The individual statistics
    // -----------------------------------------------------------------------------------------------

    private class OrientationStats(
        val entropy: Float,
        val concentration: Float,
        val dominant: Float,
    )

    /**
     * Magnitude-weighted gradient-orientation histogram, folded to 0..π, and the three numbers read
     * off it.
     *
     * Orientation is undirected — a light-to-dark edge and the dark-to-light edge on the other side
     * of the same stroke are the same line — so the two halves of the circle are folded together.
     * Without that fold every straight line contributes two opposite peaks and a logo scores as
     * high-entropy as a photograph.
     *
     * Entropy and concentration are read from the **same** histogram rather than from two passes,
     * which is not only cheaper but the only way they cannot disagree about which bins exist.
     * Concentration wraps at the ends, because 0 and π are the same direction and a peak that
     * straddles the seam is one peak.
     */
    private fun orientationStats(gray: GrayF): OrientationStats {
        val g = Convolve.gradients(gray)
        val gx = g.gx.data
        val gy = g.gy.data
        val n = gx.size
        if (n == 0) return OrientationStats(0f, 0f, 0f)

        var sumMag = 0.0
        for (i in 0 until n) {
            val x = gx[i]
            val y = gy[i]
            sumMag += sqrt(x * x + y * y).toDouble()
        }
        // Flat regions have a meaningless orientation and there are far more of them than there are
        // edges; counting them measures sensor noise and nothing else.
        val floor = maxOf(1e-4, (sumMag / n) * 0.5)

        val bins = DoubleArray(ORIENTATION_BINS)
        var total = 0.0
        for (i in 0 until n) {
            val x = gx[i]
            val y = gy[i]
            val m = sqrt(x * x + y * y).toDouble()
            if (m < floor) continue
            var a = atan2(y, x).toDouble()
            if (a < 0.0) a += Math.PI
            var b = (a / Math.PI * ORIENTATION_BINS).toInt()
            if (b < 0) b = 0
            if (b >= ORIENTATION_BINS) b = ORIENTATION_BINS - 1
            bins[b] += m
            total += m
        }
        if (total <= 0.0) return OrientationStats(0f, 0f, 0f)

        var entropy = 0.0
        for (b in 0 until ORIENTATION_BINS) {
            val p = bins[b] / total
            if (p > 0.0) entropy -= p * ln(p)
        }

        var bestBin = 0
        var bestMass = -1.0
        for (b in 0 until ORIENTATION_BINS) {
            val prev = bins[(b + ORIENTATION_BINS - 1) % ORIENTATION_BINS]
            val next = bins[(b + 1) % ORIENTATION_BINS]
            val mass = prev + bins[b] + next
            if (mass > bestMass) {
                bestMass = mass
                bestBin = b
            }
        }
        val concentration = clamp01((bestMass / total).toFloat())
        val dominant = ((bestBin + 0.5) / ORIENTATION_BINS * Math.PI).toFloat()
        return OrientationStats(
            entropy = clamp01((entropy / ln(ORIENTATION_BINS.toDouble())).toFloat()),
            concentration = concentration,
            dominant = dominant,
        )
    }

    /** Standard deviation of HSV saturation, `(max - min) / max`, over the whole frame. */
    private fun saturationSpread(src: RgbaImage): Float {
        val p = src.pixels
        val n = p.size
        if (n == 0) return 0f
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val px = p[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            var hi = r
            if (g > hi) hi = g
            if (b > hi) hi = b
            var lo = r
            if (g < lo) lo = g
            if (b < lo) lo = b
            val s = if (hi == 0) 0.0 else (hi - lo).toDouble() / hi.toDouble()
            sum += s
            sumSq += s * s
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        if (variance <= 0.0) return 0f
        return sqrt(variance).toFloat()
    }

    private class Palette(val count: Int, val flatness: Float)

    /**
     * How many colours the source actually uses, and how much of the frame the busiest four of them
     * own.
     *
     * The count alone is not enough: a photograph of a red wall and a two-colour screenprint can
     * both report a handful of occupied cells, and only the screenprint spends 95% of its pixels in
     * them. Both numbers come out of one 4096-cell histogram at 4 bits per channel, which is coarse
     * on purpose — finer cells make a JPEG's ringing look like a palette.
     */
    private fun palette(src: RgbaImage): Palette {
        val counts = IntArray(4096)
        val p = src.pixels
        for (i in p.indices) {
            val px = p[i]
            val r = (px ushr 20) and 0xF
            val g = (px ushr 12) and 0xF
            val b = (px ushr 4) and 0xF
            counts[(r shl 8) or (g shl 4) or b]++
        }
        val minCount = maxOf(1, (p.size / 500))
        var used = 0
        // The four largest, by insertion into a fixed four-slot ladder: a sort of 4096 entries for
        // four numbers is the sort of thing that only shows up as "why is classification slow".
        var t0 = 0
        var t1 = 0
        var t2 = 0
        var t3 = 0
        for (i in counts.indices) {
            val c = counts[i]
            if (c >= minCount) used++
            if (c > t0) {
                t3 = t2; t2 = t1; t1 = t0; t0 = c
            } else if (c > t1) {
                t3 = t2; t2 = t1; t1 = c
            } else if (c > t2) {
                t3 = t2; t2 = c
            } else if (c > t3) {
                t3 = c
            }
        }
        val flatness = if (p.isEmpty()) 0f else clamp01((t0 + t1 + t2 + t3).toFloat() / p.size.toFloat())
        return Palette(used, flatness)
    }

    private class StrokeStats(val meanWidth: Float, val consistency: Float)

    /**
     * Mean and consistency of the ink stroke width, from the ridge of the distance transform.
     *
     * The distance transform of the ink mask gives, at every ink pixel, the distance to the nearest
     * paper; at the *ridge* of that field — a local maximum, i.e. the medial axis — that distance is
     * the stroke's half-width. Taking the ridge rather than thinning the mask first is the whole
     * economy of this measurement: Zhang–Suen iterates to a fixed point over the whole frame, and a
     * four-neighbour maximum test is one pass and answers the same question to the precision this
     * decision needs.
     *
     * Consistency is `1 − stddev/mean`, which is dimensionless and therefore comparable across
     * resolutions and across sources. A pen, a printing plate and an engraving tool each produce one
     * width and land near 1; the dark regions of a photograph are objects rather than strokes and
     * their widths span the whole image, landing near 0.
     *
     * Returns zeroes when the mask is entirely ink or entirely paper — there is no stroke to measure
     * in either, and the distance transform of an all-foreground mask is a measurement of the frame.
     */
    private fun strokeStats(gray: GrayF, threshold: Float): StrokeStats {
        val ink = Threshold.fixed(gray, threshold, invert = true)
        val on = ink.countTrue()
        if (on == 0 || on == ink.size) return StrokeStats(0f, 0f)

        val dt = Distance.euclidean(ink, insideForeground = true)
        val w = dt.width
        val h = dt.height
        var sum = 0.0
        var sumSq = 0.0
        var count = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = dt[x, y]
                if (v <= 0f) continue
                if (v < dt.clamped(x - 1, y) || v < dt.clamped(x + 1, y)) continue
                if (v < dt.clamped(x, y - 1) || v < dt.clamped(x, y + 1)) continue
                val width = 2.0 * v
                sum += width
                sumSq += width * width
                count++
            }
        }
        if (count <= 0L) return StrokeStats(0f, 0f)
        val mean = sum / count
        if (mean <= 0.0) return StrokeStats(0f, 0f)
        val variance = max(0.0, sumSq / count - mean * mean)
        val consistency = clamp01((1.0 - sqrt(variance) / mean).toFloat())
        return StrokeStats(mean.toFloat(), consistency)
    }

    /**
     * The fraction of the frame whose local standard deviation exceeds [TEXTURE_LOCAL_STDDEV].
     *
     * **An area, not an amplitude**, and that is the whole point. The obvious measure — mean |∇²I| —
     * was tried first and is useless here, because it is dominated by however many *edges* the frame
     * contains rather than by how much of it is textured: a black-on-white pen drawing and a woven
     * cloth both saturated it, which is precisely the pair this statistic exists to separate. Two
     * hard edges produce a large mean and cover nothing; a weave produces a modest local deviation
     * and covers everything.
     *
     * Being an area it is already normalised to 0..1 with no calibration constant multiplying it,
     * and it is insensitive to sensor noise by construction: noise of a few code values sits an
     * order of magnitude below the threshold whatever fraction of the frame carries it.
     *
     * Both moments come from summed-area tables, so the cost is two O(n) prefix passes and four
     * lookups per pixel regardless of the radius.
     */
    private fun textureEnergy(gray: GrayF): Float {
        val w = gray.width
        val h = gray.height
        val n = w * h
        if (n <= 0) return 0f
        val squares = GrayF(w, h)
        val sd = gray.data
        val qd = squares.data
        for (i in 0 until n) qd[i] = sd[i] * sd[i]
        val satV = Convolve.summedAreaTable(gray)
        val satQ = Convolve.summedAreaTable(squares)

        val varianceFloor = (TEXTURE_LOCAL_STDDEV * TEXTURE_LOCAL_STDDEV).toDouble()
        var textured = 0
        for (y in 0 until h) {
            val y0 = max(0, y - TEXTURE_RADIUS)
            val y1 = min(h - 1, y + TEXTURE_RADIUS)
            for (x in 0 until w) {
                val x0 = max(0, x - TEXTURE_RADIUS)
                val x1 = min(w - 1, x + TEXTURE_RADIUS)
                val area = ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()).toDouble()
                val mean = Convolve.rectSum(satV, w, h, x0, y0, x1, y1) / area
                val meanSq = Convolve.rectSum(satQ, w, h, x0, y0, x1, y1) / area
                if (meanSq - mean * mean > varianceFloor) textured++
            }
        }
        return clamp01(textured.toFloat() / n.toFloat())
    }

    // -----------------------------------------------------------------------------------------------
    // Scoring
    // -----------------------------------------------------------------------------------------------

    /**
     * Linear ramp: 0 at or below [lo], 1 at or above [hi].
     *
     * Every threshold in this file is expressed as a ramp rather than as a step, because a step at
     * `x = 0.75` makes two frames that differ by one pixel classify differently and a user cannot be
     * told why. A ramp turns that cliff into a confidence the caller can refuse to act on.
     */
    private fun ramp(v: Float, lo: Float, hi: Float): Float {
        if (hi <= lo) return if (v >= hi) 1f else 0f
        return clamp01((v - lo) / (hi - lo))
    }

    @Suppress("LongParameterList")
    private fun score(
        bimodality: Float,
        modeSplit: Float,
        edgeDensity: Float,
        orientationEntropy: Float,
        colourCount: Int,
        paletteFlatness: Float,
        backgroundUniformity: Float,
        subjectCoverage: Float,
        strokeConsistency: Float,
        textureEnergy: Float,
        isLineArt: Boolean,
        isHighTexture: Boolean,
        isFlatGraphic: Boolean,
    ): ClassScores {
        // Weights are stated inline and sum to 1 within each class, so a score is always a weighted
        // mean of 0..1 evidence and always lands in 0..1 — which is what lets the margin below be
        // read as a fraction of the winner rather than as an uncalibrated difference.
        // Stroke consistency carries the largest single weight, above bimodality, because it is the
        // only term here that a *photograph* cannot fake: a shaded object against a plain ground is
        // as bimodal and as palette-flat as a drawing, and the widths of its dark regions are not.
        var lineArt = 0.25f * ramp(bimodality, 0.55f, 0.85f) +
            0.22f * ramp(modeSplit, 0.25f, 0.60f) +
            0.35f * strokeConsistency +
            0.18f * ramp(paletteFlatness, 0.45f, 0.95f)

        var flatGraphic = 0.30f * (1f - ramp(colourCount.toFloat(), 4f, 40f)) +
            0.30f * ramp(paletteFlatness, 0.40f, 0.90f) +
            0.20f * (1f - ramp(textureEnergy, 0.10f, 0.45f)) +
            0.20f * (1f - ramp(orientationEntropy, 0.55f, 0.90f))

        // Texture area outweighs edge density here, and deliberately: Canny's auto thresholds are
        // taken from the median gradient magnitude, so a frame that is textured *everywhere* raises
        // its own thresholds and reports a low edge density. Measured on a woven pattern that fills
        // the frame, edge density came back at 0.014 while the texture area was 1.0 — the statistic
        // §12 names for this job is the one that fails on the strongest case of it.
        var textured = 0.30f * ramp(edgeDensity, 0.10f, 0.30f) +
            0.60f * ramp(textureEnergy, 0.25f, 0.75f) +
            0.10f * (1f - strokeConsistency)

        // "Isolated" is a band, not a threshold: a subject occupying 0.5% of the frame is a speck
        // and one occupying 95% of it has no background to be isolated from.
        //
        // The palette term is what stops this class swallowing every flat graphic. A logo on white
        // has a clean background, an isolated subject, no texture and no edge density — it satisfies
        // every other term completely — and the one thing it is not is *tonal*. A photograph of an
        // object spends its pixels across the palette; a graphic spends them in four cells.
        val isolated = ramp(subjectCoverage, 0.02f, 0.10f) *
            (1f - ramp(subjectCoverage, 0.70f, 0.95f))
        // The clean background multiplies rather than contributes. It is a *precondition* of the
        // class and not one vote among four: as an additive term worth a quarter of the score, a
        // full-frame woven fabric with no background at all still scored 0.60 here on the strength
        // of the other three, which is a confident answer of "object on a clean background" about a
        // frame that has neither an object nor a background.
        val smoothObject = ramp(backgroundUniformity, 0.55f, 0.90f) * (
            0.35f * isolated +
                0.20f * (1f - ramp(textureEnergy, 0.12f, 0.45f)) +
                0.20f * (1f - ramp(edgeDensity, 0.08f, 0.25f)) +
                0.25f * (1f - ramp(paletteFlatness, 0.35f, 0.85f))
            )

        // The residual hypothesis, and the only one whose score rises with *clutter*. Without that
        // term a photographed pot on a white sweep would score as high for "photograph" as for
        // "object on a clean background" — they are both true — and the margin would collapse to
        // nothing on exactly the frame this classifier exists to recognise.
        val clutter = 0.5f * (1f - backgroundUniformity) + 0.5f * ramp(edgeDensity, 0.05f, 0.18f)
        val photograph = 0.45f + 0.30f * clamp01(clutter)

        // §12's own bi-level test, the texture density test and the flat-graphic test are strong
        // evidence in their own right — they are the thresholds the document argues for — so a class
        // whose hard flag is set is floored rather than left to be outvoted by a smooth ramp.
        if (isLineArt) lineArt = max(lineArt, 0.90f)
        if (isHighTexture) textured = max(textured, 0.75f)
        if (isFlatGraphic) flatGraphic = max(flatGraphic, 0.75f)

        // §12's decision list is ordered and line art comes first, so the two classes are not
        // allowed to be rivals: an ink drawing genuinely is "few colours, flat palette, no texture"
        // and scores 0.95 as a flat graphic, which would leave the two within noise of each other
        // and collapse the confidence on the one source type where the wrong answer is expensive.
        // The flag is the same `!isLineArt` exclusion `isFlatGraphic` itself carries.
        if (isLineArt) flatGraphic = min(flatGraphic, 0.35f)

        // Saturation spread is deliberately absent from every score above. It says nothing about
        // *which* class a frame is and everything about which subject preset a class maps to — grey
        // texture is stone, coloured texture is cloth — so it belongs to `Subjects.suggestFor` and
        // scoring on it here would make the same measurement count twice.
        return ClassScores(
            lineArt = clamp01(lineArt),
            flatGraphic = clamp01(flatGraphic),
            textured = clamp01(textured),
            smoothObject = clamp01(smoothObject),
            photograph = clamp01(photograph),
        )
    }

    private fun winner(s: ClassScores): SourceKind {
        // Ties break towards the *least* consequential action, in the order line art last: if
        // "photograph" and "line art" score identically the right answer is the one whose preset
        // changes least, and the confidence will refuse to act on either.
        var best = SourceKind.PHOTOGRAPH
        var bestScore = s.photograph
        if (s.smoothObject > bestScore) {
            best = SourceKind.SMOOTH_OBJECT
            bestScore = s.smoothObject
        }
        if (s.textured > bestScore) {
            best = SourceKind.TEXTURED
            bestScore = s.textured
        }
        if (s.flatGraphic > bestScore) {
            best = SourceKind.FLAT_GRAPHIC
            bestScore = s.flatGraphic
        }
        if (s.lineArt > bestScore) best = SourceKind.LINE_ART
        return best
    }

    /**
     * How sure the classification is: **how much evidence the winner has**, and **how far ahead of
     * the runner-up it is**, weighted 0.55 / 0.45.
     *
     * Both halves are necessary and neither is sufficient. Evidence alone calls a frame confident
     * when two classes both describe it — which is the ambiguous case, the one where acting is worst.
     * Margin alone calls a frame confident when the winner scores 0.3 and everything else scores 0.1,
     * which is not a classification but an absence of one. A frame needs a strong winner *and* a
     * clear second place before anything is allowed to change under the user.
     *
     * The margin is relative (`(top − second) / top`) rather than absolute, so it means the same
     * thing at both ends of the range: 0.9 against 0.45 is as decisive as 0.4 against 0.2.
     */
    private fun confidenceOf(s: ClassScores, kind: SourceKind): Float {
        val top = s.of(kind)
        if (top <= 0f) return 0f
        var second = 0f
        for (k in SourceKind.values()) {
            if (k == kind || k == SourceKind.UNKNOWN) continue
            val v = s.of(k)
            if (v > second) second = v
        }
        val margin = clamp01((top - second) / top)
        return clamp01(0.55f * top + 0.45f * margin)
    }

    // -----------------------------------------------------------------------------------------------
    // The sentence
    // -----------------------------------------------------------------------------------------------

    /**
     * One sentence naming the class, the preset it implies and the measurement that decided it.
     *
     * The confidence is spelled out in the sentence rather than left to a badge, because the sentence
     * is what gets pasted into a bug report: "72% sure" and "31% sure" are the difference between a
     * classifier that was wrong and one that said it did not know.
     */
    private fun suggestion(
        kind: SourceKind,
        confidence: Float,
        bimodality: Float,
        edgeDensity: Float,
        orientationEntropy: Float,
        backgroundUniformity: Float,
    ): String = when (kind) {
        SourceKind.LINE_ART ->
            "This is already line art (${percent(bimodality)}% bimodal, ${percent(confidence)}% " +
                "confident), so the suggested preset is \"Ink Scan\": adaptive thresholding recovers " +
                "the original strokes at their true width, where an edge detector would trace both " +
                "sides of every line and double it."
        SourceKind.FLAT_GRAPHIC ->
            "This looks like a flat graphic or logo (few colours, ${percent(orientationEntropy)}% " +
                "orientation entropy, ${percent(confidence)}% confident), so the suggested preset is " +
                "\"Flat Graphic\": outline mode over a clean threshold keeps the shapes crisp and " +
                "closed."
        SourceKind.TEXTURED ->
            "This is a heavily textured subject (${percent(edgeDensity)}% of pixels are edges, " +
                "${percent(confidence)}% confident), so the suggested preset is \"Coherent Line\": " +
                "flow-based edges with stronger denoising and a larger minimum blob area suppress " +
                "texture that a per-pixel detector would trace."
        SourceKind.SMOOTH_OBJECT ->
            "This looks like a single object on a clean background " +
                "(${percent(backgroundUniformity)}% uniform backdrop, ${percent(confidence)}% " +
                "confident), so the suggested preset is \"Clean Line\" with the background separated: " +
                "the silhouette is the drawing and the backdrop is not."
        SourceKind.PHOTOGRAPH, SourceKind.UNKNOWN ->
            "This looks like a photographed subject (${percent(edgeDensity)}% edge density, " +
                "${percent(orientationEntropy)}% orientation entropy, ${percent(confidence)}% " +
                "confident), so the suggested preset is \"Clean Line\": flow-based edges at the " +
                "default sensitivity."
    }

    // -----------------------------------------------------------------------------------------------
    // Small shared helpers
    // -----------------------------------------------------------------------------------------------

    /** Mean intensity (0..1) of the histogram bins in `[from, to)`, or 0 for an empty class. */
    private fun classMean(hist: IntArray, from: Int, to: Int): Float {
        val bins = hist.size
        if (bins <= 0) return 0f
        val lo = Px.clamp(from, 0, bins)
        val hi = Px.clamp(to, 0, bins)
        var weight = 0.0
        var count = 0.0
        for (i in lo until hi) {
            weight += i.toDouble() * hist[i]
            count += hist[i].toDouble()
        }
        if (count <= 0.0) return 0f
        return (weight / count / (bins - 1).coerceAtLeast(1)).toFloat()
    }

    private fun binOf(threshold: Float): Int = Px.clamp((threshold * 256f).toInt(), 0, 256)

    private fun clamp01(v: Float): Float = if (v.isNaN()) 0f else Px.clamp01(v)

    /**
     * Integer percent for the suggestion sentence. Deliberately not `String.format`: that is
     * locale-dependent, and a suggestion that reads "0,18" in one locale and "0.18" in another is
     * the kind of difference that only shows up in a bug report from another continent.
     */
    private fun percent(v: Float): Int = Px.clamp((v * 100f).roundToInt(), 0, 100)
}
