package com.offlinetracer.pipeline

import kotlinx.serialization.Serializable

@Serializable
enum class EdgeEngine { CANNY, XDOG, FDOG, ADAPTIVE, LOG, MODEL }

@Serializable
enum class DenoiseMode { NONE, BILATERAL, MEDIAN, ANISOTROPIC }

@Serializable
enum class MatteMode { NONE, BORDER_FLOOD, SALIENCY, SUBJECT }

@Serializable
enum class AutoMode { OFF, SUGGEST, APPLY }

@Serializable
enum class ThinningMode { ZHANG_SUEN, GUO_HALL }

@Serializable
enum class VectorModeParam { CENTERLINE, OUTLINE }

internal object Limits {

    const val MIN_WORKING_EDGE = 256
    const val MAX_WORKING_EDGE = 8192

    const val MIN_SIGMA = 0.05f
    const val MAX_SIGMA = 32f

    const val MAX_RADIUS = 256

    const val MAX_AREA = 67_108_864

    const val MAX_ANGLE = 180f

    const val MAX_LENGTH = 8192f

    const val DEFAULT_STYLE_ID = "clean-line"

    const val XDOG_EPSILON = 0.08f

    fun i(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    fun f(v: Float, lo: Float, hi: Float, fallback: Float): Float =
        if (v.isNaN()) fallback else if (v < lo) lo else if (v > hi) hi else v
}

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

@Serializable
data class MatteParams(
    val mode: MatteMode = MatteMode.NONE,
    val tolerance: Float = 0.18f,
    val feather: Float = 1.5f,
    val threshold: Float = 0.5f,
) {
    fun sanitized(): MatteParams = MatteParams(
        mode = mode,
        tolerance = Limits.f(tolerance, 0f, 1f, 0.18f),
        feather = Limits.f(feather, 0f, Limits.MAX_SIGMA, 1.5f),
        threshold = Limits.f(threshold, 0f, 1f, 0.5f),
    )
}

@Serializable
data class AutoParams(
    val mode: AutoMode = AutoMode.SUGGEST,
    val subjectId: String = "",
    val handTuned: Set<String> = emptySet(),
    val minConfidence: Float = 0.55f,
    val allowMatte: Boolean = true,
    val allowCrop: Boolean = true,
) {
    fun sanitized(): AutoParams = AutoParams(
        mode = mode,
        subjectId = subjectId.trim(),
        handTuned = handTuned.mapNotNullTo(LinkedHashSet()) { it.trim().ifEmpty { null } },
        minConfidence = Limits.f(minConfidence, 0.05f, 1f, 0.55f),
        allowMatte = allowMatte,
        allowCrop = allowCrop,
    )
}

object Knobs {

    const val EDGE_SENSITIVITY = "edge sensitivity"
    const val STROKE_WIDTH = "stroke width"
    const val SIMPLIFY = "simplify"
    const val CORNER = "corner threshold"
    const val MIN_PATH_LENGTH = "minimum path length"
    const val MIN_BLOB_AREA = "minimum blob area"

    val ALL: List<String> = listOf(
        EDGE_SENSITIVITY,
        STROKE_WIDTH,
        SIMPLIFY,
        CORNER,
        MIN_PATH_LENGTH,
        MIN_BLOB_AREA,
    )

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
    fun sanitized(): FlowSettings = FlowSettings(
        tensorSigma = Limits.f(tensorSigma, Limits.MIN_SIGMA, 16f, 2f),
        etfIterations = Limits.i(etfIterations, 0, 16),
        etfRadius = Limits.i(etfRadius, 1, 32),
        sigmaC = Limits.f(sigmaC, Limits.MIN_SIGMA, 16f, 1f),
        sigmaM = Limits.f(sigmaM, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 3f),
        tau = Limits.f(tau, 0f, 0.999f, 0.99f),
        fdogIterations = Limits.i(fdogIterations, 1, 16),
    )
}

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
    fun sanitized(): EdgeParams {
        var lo = if (cannyLow.isNaN() || cannyLow < 0f) AUTO_THRESHOLD else Limits.f(cannyLow, 0f, 1f, AUTO_THRESHOLD)
        var hi = if (cannyHigh.isNaN() || cannyHigh < 0f) AUTO_THRESHOLD else Limits.f(cannyHigh, 0f, 1f, AUTO_THRESHOLD)
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
        const val AUTO_THRESHOLD = -1f
    }
}

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
    fun sanitized(): OutputParams = OutputParams(
        vectorMode = vectorMode,
        simplify = Limits.f(simplify, 0f, 64f, 1f),
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
