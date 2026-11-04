package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.AutoMode
import com.offlinetracer.pipeline.AutoParams
import com.offlinetracer.pipeline.CleanupParams
import com.offlinetracer.pipeline.DenoiseMode
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.EdgeParams
import com.offlinetracer.pipeline.FlowSettings
import com.offlinetracer.pipeline.MatteMode
import com.offlinetracer.pipeline.MatteParams
import com.offlinetracer.pipeline.OutputParams
import com.offlinetracer.pipeline.PreprocessParams
import com.offlinetracer.pipeline.ThinningMode
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.VectorModeParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val traceParamsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal fun traceWireOf(params: TraceParams): String {
    val clean = params.sanitized()
    val root = traceParamsJson.encodeToJsonElement(TraceParams.serializer(), clean).jsonObject
    val output = root.getValue("output").jsonObject.toMutableMap()
    output["strokeColor"] = JsonPrimitive(traceUnsignedColour(clean.output.strokeColor))
    clean.output.background?.let { output["background"] = JsonPrimitive(traceUnsignedColour(it)) }
    val out = root.toMutableMap()
    out["output"] = JsonObject(output)
    return traceParamsJson.encodeToString(JsonObject.serializer(), JsonObject(out))
}

internal fun traceValuesOfParams(params: TraceParams): TraceValues =
    traceValuesOf(traceWireOf(params))

internal fun traceFlattenParams(params: TraceParams): Map<String, TraceValue> {
    val values = traceValuesOfParams(params)
    return values.keys.associateWith { key -> values[key]!! }
}

internal val TRACE_LEAF_KEYS: List<String> = traceValuesOfParams(TraceParams()).keys.toList()

private val traceLeafKeySet: Set<String> = TRACE_LEAF_KEYS.toSet()

internal fun traceIsLeafKey(key: String): Boolean = key in traceLeafKeySet

internal fun traceUnknownLeafKeys(keys: Iterable<String>): List<String> =
    keys.filterNot { it in traceLeafKeySet }

internal fun traceApplyLeaves(
    base: TraceParams,
    patch: Map<String, TraceValue>,
): TraceParams {
    if (patch.isEmpty()) return base.sanitized()
    val p = TraceLeaves(patch)
    return TraceParams(
        preprocess = tracePreprocessOf(base.preprocess, p),
        matte = traceMatteOf(base.matte, p),
        edge = traceEdgeOf(base.edge, p),
        cleanup = traceCleanupOf(base.cleanup, p),
        output = traceOutputOf(base.output, p),
        auto = traceAutoOf(base.auto, p),
        styleId = p.text(TRACE_STYLE_ID_KEY, base.styleId),
    ).sanitized()
}

internal fun traceNestLeaves(leaves: Map<String, TraceValue>): TraceParams =
    traceApplyLeaves(TraceParams(), leaves)

internal fun traceParamsOfWire(wire: String): TraceParams {
    val root = runCatching { traceParamsJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "a parameter tree that is not a JSON object",
        )
    val decoded = runCatching {
        traceParamsJson.decodeFromJsonElement(TraceParams.serializer(), traceSignColours(root))
    }.getOrElse { cause ->
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "a parameter tree this app could not read",
            cause,
        )
    }
    return decoded.sanitized()
}

internal fun traceParamsOf(values: TraceValues): TraceParams = traceParamsOfWire(values.wire)

private fun tracePreprocessOf(base: PreprocessParams, p: TraceLeaves) = PreprocessParams(
    autoOrient = p.flag("preprocess.autoOrient", base.autoOrient),
    perspectiveCorrect = p.flag("preprocess.perspectiveCorrect", base.perspectiveCorrect),
    workingLongEdge = p.int("preprocess.workingLongEdge", base.workingLongEdge),
    denoise = p.pick("preprocess.denoise", base.denoise, DenoiseMode.BILATERAL, DenoiseMode.entries),
    denoiseStrength = p.num("preprocess.denoiseStrength", base.denoiseStrength),
    medianRadius = p.int("preprocess.medianRadius", base.medianRadius),
    claheEnabled = p.flag("preprocess.claheEnabled", base.claheEnabled),
    claheClip = p.num("preprocess.claheClip", base.claheClip),
    claheTiles = p.int("preprocess.claheTiles", base.claheTiles),
    brightness = p.num("preprocess.brightness", base.brightness),
    contrast = p.num("preprocess.contrast", base.contrast),
    gamma = p.num("preprocess.gamma", base.gamma),
    unsharpAmount = p.num("preprocess.unsharpAmount", base.unsharpAmount),
    unsharpSigma = p.num("preprocess.unsharpSigma", base.unsharpSigma),
    invertInput = p.flag("preprocess.invertInput", base.invertInput),
)

private fun traceMatteOf(base: MatteParams, p: TraceLeaves) = MatteParams(
    mode = p.pick("matte.mode", base.mode, MatteMode.NONE, MatteMode.entries),
    tolerance = p.num("matte.tolerance", base.tolerance),
    feather = p.num("matte.feather", base.feather),
    threshold = p.num("matte.threshold", base.threshold),
)

private fun traceFlowOf(base: FlowSettings, p: TraceLeaves) = FlowSettings(
    tensorSigma = p.num("edge.flow.tensorSigma", base.tensorSigma),
    etfIterations = p.int("edge.flow.etfIterations", base.etfIterations),
    etfRadius = p.int("edge.flow.etfRadius", base.etfRadius),
    sigmaC = p.num("edge.flow.sigmaC", base.sigmaC),
    sigmaM = p.num("edge.flow.sigmaM", base.sigmaM),
    tau = p.num("edge.flow.tau", base.tau),
    fdogIterations = p.int("edge.flow.fdogIterations", base.fdogIterations),
)

private fun traceEdgeOf(base: EdgeParams, p: TraceLeaves) = EdgeParams(
    engine = p.pick("edge.engine", base.engine, EdgeEngine.FDOG, EdgeEngine.entries),
    sensitivity = p.num("edge.sensitivity", base.sensitivity),
    blurSigma = p.num("edge.blurSigma", base.blurSigma),
    cannyLow = p.num("edge.cannyLow", base.cannyLow),
    cannyHigh = p.num("edge.cannyHigh", base.cannyHigh),
    dogSigma = p.num("edge.dogSigma", base.dogSigma),
    dogK = p.num("edge.dogK", base.dogK),
    dogTau = p.num("edge.dogTau", base.dogTau),
    xdogEpsilon = p.num("edge.xdogEpsilon", base.xdogEpsilon),
    xdogPhi = p.num("edge.xdogPhi", base.xdogPhi),
    flow = traceFlowOf(base.flow, p),
    adaptiveRadius = p.int("edge.adaptiveRadius", base.adaptiveRadius),
    adaptiveC = p.num("edge.adaptiveC", base.adaptiveC),
    useSauvola = p.flag("edge.useSauvola", base.useSauvola),
    logSigma = p.num("edge.logSigma", base.logSigma),
    logSlope = p.num("edge.logSlope", base.logSlope),
    modelId = p.text("edge.modelId", base.modelId),
)

private fun traceCleanupOf(base: CleanupParams, p: TraceLeaves) = CleanupParams(
    minBlobArea = p.int("cleanup.minBlobArea", base.minBlobArea),
    removeIsolated = p.flag("cleanup.removeIsolated", base.removeIsolated),
    closeRadius = p.int("cleanup.closeRadius", base.closeRadius),
    openRadius = p.int("cleanup.openRadius", base.openRadius),
    bridgeGaps = p.flag("cleanup.bridgeGaps", base.bridgeGaps),
    maxGap = p.int("cleanup.maxGap", base.maxGap),
    maxBridgeAngle = p.num("cleanup.maxBridgeAngle", base.maxBridgeAngle),
    skeletonize = p.flag("cleanup.skeletonize", base.skeletonize),
    thinning = p.pick(
        "cleanup.thinning",
        base.thinning,
        ThinningMode.ZHANG_SUEN,
        ThinningMode.entries,
    ),
    pruneSpurs = p.int("cleanup.pruneSpurs", base.pruneSpurs),
    fillHolesUpTo = p.int("cleanup.fillHolesUpTo", base.fillHolesUpTo),
    keepLargest = p.int("cleanup.keepLargest", base.keepLargest),
    removeBorderTouching = p.flag("cleanup.removeBorderTouching", base.removeBorderTouching),
)

private fun traceOutputOf(base: OutputParams, p: TraceLeaves) = OutputParams(
    vectorMode = p.pick(
        "output.vectorMode",
        base.vectorMode,
        VectorModeParam.CENTERLINE,
        VectorModeParam.entries,
    ),
    simplify = p.num("output.simplify", base.simplify),
    fitError = p.num("output.fitError", base.fitError),
    corner = p.num("output.corner", base.corner),
    smoothIterations = p.int("output.smoothIterations", base.smoothIterations),
    strokeWidth = p.num("output.strokeWidth", base.strokeWidth),
    modulateWidth = p.flag("output.modulateWidth", base.modulateWidth),
    widthScale = p.num("output.widthScale", base.widthScale),
    minPathLength = p.num("output.minPathLength", base.minPathLength),
    strokeColor = p.colour("output.strokeColor", base.strokeColor),
    background = p.colourOrNull("output.background", base.background),
    fillClosed = p.flag("output.fillClosed", base.fillClosed),
)

private fun traceAutoOf(base: AutoParams, p: TraceLeaves) = AutoParams(
    mode = p.pick("auto.mode", base.mode, AutoMode.SUGGEST, AutoMode.entries),
    subjectId = p.text("auto.subjectId", base.subjectId),
    handTuned = base.handTuned,
    minConfidence = p.num("auto.minConfidence", base.minConfidence),
    allowMatte = p.flag("auto.allowMatte", base.allowMatte),
    allowCrop = p.flag("auto.allowCrop", base.allowCrop),
)

private class TraceLeaves(private val patch: Map<String, TraceValue>) {

    fun flag(key: String, base: Boolean): Boolean = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Flag -> leaf.value
        else -> wrong(key, leaf, "a true/false flag")
    }

    fun num(key: String, base: Float): Float = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Num -> finite(key, leaf.value).toFloat()
        else -> wrong(key, leaf, "a number")
    }

    fun int(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Num -> finite(key, leaf.value).toInt()
        else -> wrong(key, leaf, "a whole number")
    }

    fun text(key: String, base: String): String = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Choice -> leaf.value
        else -> wrong(key, leaf, "a name")
    }

    fun <T : Enum<T>> pick(key: String, base: T, unknown: T, all: List<T>): T =
        when (val leaf = patch[key]) {
            null -> base
            is TraceValue.Choice -> all.firstOrNull { it.name == leaf.value } ?: unknown
            else -> wrong(key, leaf, "a name")
        }

    fun colour(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Num -> traceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour")
    }

    fun colourOrNull(key: String, base: Int?): Int? = when (val leaf = patch[key]) {
        null -> base
        TraceValue.Absent -> null
        is TraceValue.Num -> traceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour or nothing at all")
    }

    private fun finite(key: String, value: Double): Double {
        if (!value.isFinite()) {
            throw TraceHostFailure(
                TraceFailureKind.ENGINE_ERROR,
                "$key was set to $value, which is not a number the engine can be sent",
            )
        }
        return value
    }

    private fun wrong(key: String, leaf: TraceValue, wanted: String): Nothing =
        throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "$key was sent as ${traceKindOf(leaf)}, and it holds $wanted",
        )
}

private fun traceKindOf(leaf: TraceValue): String = when (leaf) {
    is TraceValue.Num -> "a number"
    is TraceValue.Flag -> "a true/false flag"
    is TraceValue.Choice -> "a name"
    TraceValue.Absent -> "nothing at all"
}

private fun traceUnsignedColour(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

private fun traceSignedColour(packed: Double): Int = packed.toLong().toInt()

private val TRACE_COLOUR_LEAVES: List<String> = listOf("strokeColor", "background")

private fun traceSignColours(root: JsonObject): JsonObject {
    val output = root["output"] as? JsonObject ?: return root
    val fixed = output.toMutableMap()
    for (key in TRACE_COLOUR_LEAVES) {
        val leaf = fixed[key] as? JsonPrimitive ?: continue
        if (leaf is JsonNull || leaf.isString) continue
        val packed = leaf.content.toDoubleOrNull() ?: continue
        fixed[key] = JsonPrimitive(traceSignedColour(packed))
    }
    val out = root.toMutableMap()
    out["output"] = JsonObject(fixed)
    return JsonObject(out)
}
