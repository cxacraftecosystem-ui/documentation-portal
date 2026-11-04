package com.offlinetracer.pipeline

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Matte
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import kotlin.math.roundToInt

data class SubjectBox(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val coverage: Float,
    val confident: Boolean,
    val reason: String,
)

data class SubjectFind(
    val alpha: GrayF,
    val box: SubjectBox,
    val confidence: Float,
    val confident: Boolean,
    val reason: String,
)

object Subject {

    const val DEFAULT_THRESHOLD = 0.5f

    const val DEFAULT_MARGIN_FRACTION = 0.04f

    const val MIN_COVERAGE = 0.004f

    const val MAX_COVERAGE = 0.97f

    const val MIN_SUBJECT_PIXELS = 16

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

    fun crop(src: RgbaImage, box: SubjectBox): RgbaImage = Resample.crop(src, box.x, box.y, box.w, box.h)

    fun crop(src: GrayF, box: SubjectBox): GrayF = Resample.crop(src, box.x, box.y, box.w, box.h)

    private fun percent(v: Float): Int = (Px.clamp01(v) * 100f).roundToInt()
}
