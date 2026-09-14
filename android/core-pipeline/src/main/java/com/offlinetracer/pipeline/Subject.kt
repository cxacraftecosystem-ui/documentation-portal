package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Matte
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import kotlin.math.roundToInt

/**
 * Where the subject is, as a rectangle the caller may crop to — or a refusal.
 *
 * [x], [y], [w], [h] are always a **legal** sub-rectangle of the frame the box was measured in, so
 * `Resample.crop(img, box.x, box.y, box.w, box.h)` is safe without any further checking. When
 * [confident] is `false` the rectangle is the whole frame, which makes the crop a no-op rather than
 * a decision: a caller that forgets to test [confident] loses nothing, which is the only acceptable
 * failure mode for an operation that can otherwise throw away most of somebody's artwork.
 *
 * @property coverage fraction of the frame the alpha kept above the threshold, 0..1. This is the
 *   measurement the refusals are made on, and it is reported even when the box is refused so a UI
 *   can say *why* without recomputing anything.
 * @property confident `true` only when the box is a genuine, trustworthy crop to a subject.
 * @property reason one sentence, in the user's language, for what happened. Written to be shown.
 */
data class SubjectBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val coverage: Float,
    val confident: Boolean,
    val reason: String,
)

/**
 * A finished subject search: the matte, the box, and one verdict covering both.
 *
 * @property alpha the matte that [box] was measured from, the size of the source.
 * @property confident `true` only when **both** the matte and the box are trustworthy. Cropping to
 *   a box measured from a matte nobody believes is the exact failure this whole file exists to make
 *   impossible, so the two verdicts are ANDed here rather than at each call site.
 */
data class SubjectFind(
    val alpha: GrayF,
    val box: SubjectBox,
    val confidence: Float,
    val confident: Boolean,
    val reason: String,
)

/**
 * Locating and cropping to the thing the user actually photographed.
 *
 * This is the half of "extract only the relevant object" that comes after matting: [Matte] decides
 * which pixels are subject, and this decides which *rectangle* is worth keeping, or refuses.
 *
 * **Every refusal returns the full frame.** An alpha that kept almost everything separated nothing,
 * and an alpha that kept almost nothing is a failed matte; cropping to either destroys the picture,
 * so both come back as the whole frame with [SubjectBox.confident] `false` and a sentence saying so.
 * That asymmetry is deliberate and it is the reason this is a separate module rather than four lines
 * inside the pipeline: the refusals are the feature.
 *
 * The aspect ratio is never forced. A tall pot crops to a tall box, and a caller that wants a square
 * can expand this one — expanding a box cannot lose subject, and cropping to a forced square can.
 */
object Subject {

    /** Alpha at or above which a pixel counts as subject. Matches the matte's own decision level. */
    const val DEFAULT_THRESHOLD = 0.5f

    /**
     * Margin added on every side, as a fraction of the tight box's **longer** side.
     *
     * Of the longer side, not of each axis independently: a 3 px wide vertical stroke would get a
     * margin of 0 px horizontally under a per-axis rule, and clip on exactly the axis where clipping
     * is most visible. One margin, taken from the dimension that has something to measure.
     */
    const val DEFAULT_MARGIN_FRACTION = 0.04f

    /**
     * Coverage at or below which the box is refused: the matte found a subject smaller than half a
     * percent of the frame, which is far more often a failed separation than a small subject.
     */
    const val MIN_COVERAGE = 0.004f

    /**
     * Coverage at or above which the box is refused: nothing was separated out, so there is no
     * background to crop away.
     */
    const val MAX_COVERAGE = 0.97f

    /**
     * Absolute floor on the number of subject pixels, independent of frame size.
     *
     * [MIN_COVERAGE] alone is not enough. On a 16×16 thumbnail a single lit pixel is 0.4% of the
     * frame — above the fraction — and cropping a photograph to one pixel plus a margin is the worst
     * output this function could produce. A subject is at least a small blob, whatever the frame.
     */
    const val MIN_SUBJECT_PIXELS = 16

    /**
     * The tight bounds of `alpha >= threshold`, expanded by a margin, or the full frame if cropping
     * to it would be wrong.
     *
     * One pass over the alpha accumulating a count and four extremes — O(n), no allocation, and the
     * same answer every time for the same input.
     *
     * The margin exists because the boundary of a matte is not the boundary of the drawing: a stroke
     * that ends exactly on the alpha's edge is traced right up to it, and a tight crop then slices
     * the stroke's outer half off. It is a fraction of the box rather than a fixed pixel count so it
     * behaves the same on a 400 px thumbnail and a 6000 px scan.
     *
     * @param threshold clamped to 0..1.
     * @param marginFraction negative is treated as zero. Any positive value gives at least 1 px, so
     *   asking for a margin never silently produces none.
     * @return a [SubjectBox] that is always a legal sub-rectangle of [alpha]'s frame.
     */
    fun boundingBox(
        alpha: GrayF,
        threshold: Float = DEFAULT_THRESHOLD,
        marginFraction: Float = DEFAULT_MARGIN_FRACTION,
    ): SubjectBox {
        val w = alpha.width
        val h = alpha.height
        val n = w * h
        val t = Px.clamp(threshold, 0f, 1f)
        val d = alpha.data

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var count = 0
        var y = 0
        while (y < h) {
            val row = y * w
            var rowHit = false
            var x = 0
            while (x < w) {
                if (d[row + x] >= t) {
                    count++
                    rowHit = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
                x++
            }
            if (rowHit) {
                if (y < minY) minY = y
                maxY = y
            }
            y++
        }

        val coverage = count.toFloat() / n
        if (count == 0) {
            return SubjectBox(
                0, 0, w, h, 0f, false,
                "The matte kept no pixels at all, so there is nothing to crop to and the whole " +
                    "frame was kept.",
            )
        }
        if (count < MIN_SUBJECT_PIXELS) {
            return SubjectBox(
                0, 0, w, h, coverage, false,
                "The matte kept $count " + (if (count == 1) "pixel" else "pixels") +
                    ", which is too little to be a subject, so the whole frame was kept.",
            )
        }
        if (coverage <= MIN_COVERAGE) {
            return SubjectBox(
                0, 0, w, h, coverage, false,
                "The matte kept ${percent(coverage)}% of the frame, which reads as a failed " +
                    "separation rather than a small subject, so the whole frame was kept.",
            )
        }
        if (coverage >= MAX_COVERAGE) {
            return SubjectBox(
                0, 0, w, h, coverage, false,
                "The matte kept ${percent(coverage)}% of the frame, so there is no background to " +
                    "crop away and the whole frame was kept.",
            )
        }

        val tightW = maxX - minX + 1
        val tightH = maxY - minY + 1
        val margin = if (marginFraction <= 0f) {
            0
        } else {
            maxOf(1, (marginFraction * maxOf(tightW, tightH)).roundToInt())
        }
        val x0 = maxOf(0, minX - margin)
        val y0 = maxOf(0, minY - margin)
        val x1 = minOf(w - 1, maxX + margin)
        val y1 = minOf(h - 1, maxY + margin)
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1

        val reason = if (bw == w && bh == h) {
            "The subject reaches every edge of the frame, so nothing was cropped away."
        } else {
            "Cropped to the subject: ${bw}x$bh of ${w}x$h (${percent(coverage)}% of the frame is " +
                "subject), with a $margin px margin so nothing at the edge of it is clipped."
        }
        return SubjectBox(x0, y0, bw, bh, coverage, true, reason)
    }

    /**
     * [Matte.subjectMatte] followed by [boundingBox], with the two verdicts combined.
     *
     * This is the one call the pipeline needs. Doing it in two steps at the call site is fine too,
     * but then the `&&` between the two `confident` flags lives at the call site, and that `&&` is
     * the thing that must not be forgotten.
     *
     * @param tolerance ΔE76 tolerance for the matte's border flood, 0..1.
     * @return a [SubjectFind] whose [SubjectFind.alpha] is all-opaque and whose box is the full
     *   frame if either half refused.
     */
    fun locate(
        src: RgbaImage,
        tolerance: Float = 0.18f,
        threshold: Float = DEFAULT_THRESHOLD,
        marginFraction: Float = DEFAULT_MARGIN_FRACTION,
    ): SubjectFind {
        val matte = Matte.subjectMatte(src, tolerance)
        val box = boundingBox(matte.alpha, threshold, marginFraction)
        val confident = matte.confident && box.confident
        return SubjectFind(
            alpha = matte.alpha,
            box = box,
            confidence = matte.confidence,
            confident = confident,
            reason = matte.reason + " " + box.reason,
        )
    }

    /**
     * [src] cropped to [box].
     *
     * Safe against a box measured from a differently-sized alpha (a proxy, say): [Resample.crop]
     * clamps the rectangle into the image rather than throwing. A caller cropping the full-resolution
     * image with a box measured on a proxy still has to scale the box itself — clamping keeps that
     * mistake from crashing, it does not make it correct.
     */
    fun crop(src: RgbaImage, box: SubjectBox): RgbaImage = Resample.crop(src, box.x, box.y, box.w, box.h)

    /** Grey-plane form of [crop], clamped identically. */
    fun crop(src: GrayF, box: SubjectBox): GrayF = Resample.crop(src, box.x, box.y, box.w, box.h)

    /**
     * 0..1 as a whole percent. Integer arithmetic and not a formatter, because `String.format` is
     * locale-aware and would put a comma in the decimal separator for half of Europe.
     */
    private fun percent(v: Float): Int = (Px.clamp01(v) * 100f).roundToInt()
}
