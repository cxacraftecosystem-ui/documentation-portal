package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Preview {

    const val DEFAULT_LONG_EDGE = 720

    fun runPreview(
        src: RgbaImage,
        params: TraceParams,
        longEdge: Int = DEFAULT_LONG_EDGE,
        cancel: CancellationToken = CancellationToken(),
        progress: ProgressListener? = null,
    ): TraceResult {
        val p = params.sanitized()
        val sourceLongEdge = max(src.width, src.height)

        val full = min(sourceLongEdge, p.preprocess.workingLongEdge)
        val requested = max(Limits.MIN_WORKING_EDGE, longEdge)
        val target = min(full, requested)

        if (target >= full) return Pipeline.run(src, p, progress, cancel, classify = false)

        val ratio = target.toFloat() / full.toFloat()
        return Pipeline.run(src, scaleToPreview(p, target, ratio), progress, cancel, classify = false)
    }

    fun scaleToPreview(params: TraceParams, workingLongEdge: Int, ratio: Float): TraceParams {
        val r = if (!ratio.isFinite() || ratio <= 0f) 1f else if (ratio > 1f) 1f else ratio
        val p = params
        return p.copy(
            preprocess = p.preprocess.copy(
                workingLongEdge = workingLongEdge,
                medianRadius = length(p.preprocess.medianRadius, r),
            ),
            matte = p.matte.copy(feather = p.matte.feather * r),
            edge = p.edge.copy(
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
                strokeWidth = p.output.strokeWidth * r,
                minPathLength = p.output.minPathLength * r,
            ),
        ).sanitized()
    }

    private fun length(v: Int, r: Float): Int {
        if (v <= 0) return v
        val scaled = (v * r).roundToInt()
        return if (scaled < 1) 1 else scaled
    }

    private fun area(v: Int, r: Float): Int {
        if (v <= 0) return v
        val scaled = (v * r * r).roundToInt()
        return if (scaled < 1) 1 else scaled
    }
}
