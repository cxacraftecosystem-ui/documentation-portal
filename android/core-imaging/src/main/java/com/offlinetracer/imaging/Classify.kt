package com.offlinetracer.imaging

import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Classify {

    enum class SourceKind { UNKNOWN, LINE_ART, FLAT_GRAPHIC, TEXTURED, SMOOTH_OBJECT, PHOTOGRAPH }

    data class ClassScores(
        val lineArt: Float = 0f,
        val flatGraphic: Float = 0f,
        val textured: Float = 0f,
        val smoothObject: Float = 0f,
        val photograph: Float = 0f,
    ) {
        fun of(kind: SourceKind): Float = when (kind) {
            SourceKind.LINE_ART -> lineArt
            SourceKind.FLAT_GRAPHIC -> flatGraphic
            SourceKind.TEXTURED -> textured
            SourceKind.SMOOTH_OBJECT -> smoothObject
            SourceKind.PHOTOGRAPH -> photograph
            SourceKind.UNKNOWN -> 0f
        }
    }

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

    data class FigureGround(val backgroundUniformity: Float, val coverage: Float)

    const val PROXY_LONG_EDGE = 512

    private const val LINE_ART_BIMODALITY = 0.75f
    private const val LINE_ART_DARK_MODE = 0.35f
    private const val LINE_ART_LIGHT_MODE = 0.65f

    private const val LINE_ART_STROKE_CONSISTENCY = 0.60f

    private const val HIGH_TEXTURE_EDGE_DENSITY = 0.18f
    private const val FLAT_GRAPHIC_ENTROPY = 0.72f
    private const val FLAT_GRAPHIC_MAX_COLOURS = 16

    private const val ORIENTATION_BINS = 36

    private const val TEXTURE_LOCAL_STDDEV = 0.06f
    private const val TEXTURE_RADIUS = 3

    private const val BLANK_RANGE = 2f / 255f

    private const val BORDER_BAND_FRACTION = 0.06f

    private const val RING_SPREAD_FULL = 32f

    private const val RING_TOLERANCE_BASE = 14f
    private const val RING_TOLERANCE_GAIN = 2.5f
    private const val RING_TOLERANCE_MAX = 96f

    private const val CLEAN_BACKGROUND = 0.62f

    private const val MIN_SUBJECT_COVERAGE = 0.02f
    private const val MAX_SUBJECT_COVERAGE = 0.75f

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

    fun kindOf(profile: SourceProfile): SourceKind {
        if (profile.kind != SourceKind.UNKNOWN) return profile.kind
        return when {
            profile.isLineArt -> SourceKind.LINE_ART
            profile.isFlatGraphic -> SourceKind.FLAT_GRAPHIC
            profile.isHighTexture -> SourceKind.TEXTURED
            else -> SourceKind.PHOTOGRAPH
        }
    }

    fun nameOf(kind: SourceKind): String = when (kind) {
        SourceKind.UNKNOWN -> "unclassified"
        SourceKind.LINE_ART -> "line art"
        SourceKind.FLAT_GRAPHIC -> "flat graphic"
        SourceKind.TEXTURED -> "textured surface"
        SourceKind.SMOOTH_OBJECT -> "object on a clean background"
        SourceKind.PHOTOGRAPH -> "photograph"
    }

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

    private class OrientationStats(
        val entropy: Float,
        val concentration: Float,
        val dominant: Float,
    )

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
        var lineArt = 0.25f * ramp(bimodality, 0.55f, 0.85f) +
            0.22f * ramp(modeSplit, 0.25f, 0.60f) +
            0.35f * strokeConsistency +
            0.18f * ramp(paletteFlatness, 0.45f, 0.95f)

        var flatGraphic = 0.30f * (1f - ramp(colourCount.toFloat(), 4f, 40f)) +
            0.30f * ramp(paletteFlatness, 0.40f, 0.90f) +
            0.20f * (1f - ramp(textureEnergy, 0.10f, 0.45f)) +
            0.20f * (1f - ramp(orientationEntropy, 0.55f, 0.90f))

        var textured = 0.30f * ramp(edgeDensity, 0.10f, 0.30f) +
            0.60f * ramp(textureEnergy, 0.25f, 0.75f) +
            0.10f * (1f - strokeConsistency)

        val isolated = ramp(subjectCoverage, 0.02f, 0.10f) *
            (1f - ramp(subjectCoverage, 0.70f, 0.95f))
        val smoothObject = ramp(backgroundUniformity, 0.55f, 0.90f) * (
            0.35f * isolated +
                0.20f * (1f - ramp(textureEnergy, 0.12f, 0.45f)) +
                0.20f * (1f - ramp(edgeDensity, 0.08f, 0.25f)) +
                0.25f * (1f - ramp(paletteFlatness, 0.35f, 0.85f))
            )

        val clutter = 0.5f * (1f - backgroundUniformity) + 0.5f * ramp(edgeDensity, 0.05f, 0.18f)
        val photograph = 0.45f + 0.30f * clamp01(clutter)

        if (isLineArt) lineArt = max(lineArt, 0.90f)
        if (isHighTexture) textured = max(textured, 0.75f)
        if (isFlatGraphic) flatGraphic = max(flatGraphic, 0.75f)

        if (isLineArt) flatGraphic = min(flatGraphic, 0.35f)

        return ClassScores(
            lineArt = clamp01(lineArt),
            flatGraphic = clamp01(flatGraphic),
            textured = clamp01(textured),
            smoothObject = clamp01(smoothObject),
            photograph = clamp01(photograph),
        )
    }

    private fun winner(s: ClassScores): SourceKind {
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

    private fun percent(v: Float): Int = Px.clamp((v * 100f).roundToInt(), 0, 100)
}
