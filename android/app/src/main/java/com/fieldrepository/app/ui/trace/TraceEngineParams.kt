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

/**
 * **THE TRANSLATION BETWEEN THE PANEL'S FLAT MAP AND THE VENDORED ENGINE'S NESTED TREE.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * `TraceEngine.kt` declares [TraceValues]: a flat `Map<String, TraceValue>` keyed by dot paths
 * (`edge.flow.sigmaM`) plus the sanitised tree carried whole as [TraceValues.wire]. The vendored engine
 * declares `com.offlinetracer.pipeline.TraceParams`: a tree of seven records, one of them nested a
 * second level (`edge.flow`). This file is the only place the two meet.
 *
 * It imports no Android and no Compose, for the reason `TraceWire.kt`'s header gives: this module
 * declares JUnit 4 and no Robolectric, so anything touching `android.*` is by construction code no unit
 * test here can reach — and a conversion nobody can test is exactly the kind that is quietly wrong for
 * a year.
 *
 * ── THE KEY SPELLINGS ARE THE WEB ENGINE'S ────────────────────────────────────────────────────
 *
 * The two clients' saved parameter trees have to open on each other, so a key spelled
 * `preprocess.workingLongEdge` here and `preprocess.longEdge` there would be a project the portal can
 * save and the handset cannot read. [TRACE_LEAF_KEYS] is DERIVED by flattening the engine's own default
 * tree rather than transcribed, so it cannot fall behind a re-vendor, and `TraceEngineParamsTest`
 * asserts that every key a control in `TraceParams.kt` names is in it.
 *
 * ── HOW MANY LEAVES, AND THE COMMAND THAT SAYS SO ─────────────────────────────────────────────
 *
 * `TraceParams` has **74 leaves**. Counted, not claimed — list the constructor properties of the seven
 * `@Serializable` records and subtract the eight that are not leaves (the six sub-trees of
 * `TraceParams`, `EdgeParams.flow`, and `Knobs.ALL`, which is a label table and not a parameter):
 *
 *     cd android/core-pipeline/src/main/java/com/offlinetracer/pipeline
 *     grep "^    val " Params.kt | grep -vE ": (PreprocessParams|MatteParams|EdgeParams|CleanupParams|OutputParams|AutoParams|FlowSettings|List<String>) " | wc -l
 *
 * **[TRACE_LEAF_KEYS] holds 73 of those 74, and the missing one is named below.** That number is not
 * written down anywhere in this file: the list is derived, so its size is a measurement rather than a
 * claim.
 *
 * ── THE ONE LEAF WITH NO DOTTED KEY: `auto.handTuned` ─────────────────────────────────────────
 *
 * It is a `Set<String>` — the editor's visible labels for the knobs the user moved by hand, which
 * `Knobs.restore` puts back after auto-detection has overwritten them. Three facts decide its treatment
 * and none of them is a preference:
 *
 *  1. [TraceValue] has four cases — `Num`, `Flag`, `Choice`, `Absent` — and none of them is a list.
 *     Adding a fifth would be rewriting the seam this file is written against.
 *  2. The flattener in `TraceWire.kt` SKIPS arrays outright and says so, so inventing a key for it here
 *     would make the map and the flattener disagree about what a leaf is, and `traceMissingKeys` would
 *     start reporting a phantom.
 *  3. Nothing is lost. It survives in [TraceValues.wire] with everything else, and [traceApplyLeaves]
 *     carries it across from `base` untouched, so a patch can never drop it.
 *
 * So: 74 leaves, 73 dotted keys, one array carried whole. Stated here rather than discovered.
 *
 * ── COLOURS CROSS UNSIGNED, WHICH IS THE ONE PLACE THE TWO ENGINES SPELL A VALUE DIFFERENTLY ──
 *
 * `output.strokeColor` is a Kotlin `Int` and its default `0xFF000000.toInt()` is **-16777216**.
 * JavaScript has no signed 32-bit integer, so the web's colour sanitiser ends with `>>> 0` and the same
 * black is **4278190080** in every tree the portal has ever written — which is also what
 * [TRACE_OPAQUE_WHITE] already assumes, because the "White background" toggle patches that number. A
 * tree that carried -16777216 would therefore be a tree the portal reads as a nearly-transparent dark
 * blue.
 *
 * So the two colour leaves are converted at the boundary, in both directions, and NOWHERE ELSE:
 * `argb.toLong() and 0xFFFFFFFF` going out, `value.toLong().toInt()` coming back. Every other leaf is
 * whatever kotlinx serialisation makes of it.
 *
 * ── SANITISING IS THE ENGINE'S, ALWAYS, AND THERE IS NO CLAMP TABLE HERE ──────────────────────
 *
 * `TraceEngine.kt` states the rule at its own head — "No Kotlin clamp table, ever" — because several of
 * the engine's bounds encode a measured incident rather than a taste. Every function below that
 * produces a `TraceParams` ends in `.sanitized()`, which is documented idempotent precisely so a UI may
 * run it on every slider tick without disagreeing with the pipeline. The only arithmetic this file does
 * to a number is the truncation an integer leaf needs on the way in, and that mirrors the web's
 * `Math.trunc` rather than inventing a rounding.
 *
 * ── UNKNOWN KEYS ARE IGNORED, DELIBERATELY ───────────────────────────────────────────────────
 *
 * A patch or a saved tree from an older build may name a leaf that has been renamed or removed.
 * [traceApplyLeaves] simply does not look for keys it does not know, so such an entry has no effect and
 * nothing throws; [traceParamsOfWire] does the same for the tree form via `ignoreUnknownKeys`. That is
 * what the web does too. Use [traceUnknownLeafKeys] where a surface wants to SAY that keys were
 * ignored; this layer will not decide that for it.
 *
 * What is NOT ignored is a value of the wrong shape for a key this file does know — a `Choice` where a
 * number belongs, or a `NaN`. Those are refused, for the reason a dropped key is refused: "the slider
 * moves, the trace runs, and the parameter the researcher changed is the one that did not change". The
 * web's sanitiser would instead reset such a leaf to its factory default. Refusing is the better of the
 * two — it can only be reached by a caller bug, since every patch in this app is built by
 * `TRACE_CONTROLS` with the leaf's own kind — and the divergence is recorded here rather than found
 * later.
 */

/**
 * The reader and writer for the tree form.
 *
 * **`encodeDefaults = true` IS LOAD-BEARING AND NOT A STYLE CHOICE.** kotlinx omits any property equal
 * to its default, so without it `Json.encodeToString(TraceParams())` is `{}` — the panel would come up
 * with no leaves at all and every control would report itself missing.
 *
 * `ignoreUnknownKeys` is what lets a tree saved by a newer build load here; `coerceInputValues` is what
 * makes an unknown ENUM member fall back to the property's default instead of throwing, which is
 * exactly the web's own contract: "An unrecognised enum string falls back rather than propagating into
 * a `switch` with no arm". Both are the difference between an old tree opening and an old tree crashing.
 */
private val traceParamsJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/* ────────────────────────────────────────────────────────────────────────────
 * Tree -> flat
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sanitised tree as JSON, in the spelling the portal writes.
 *
 * This string is what goes into [TraceValues.wire] and therefore into anything persisted, so it carries
 * the two colour leaves unsigned — see the file header. Key ORDER is the engine's own declaration
 * order, because kotlinx encodes constructor properties in order and every rebuild below copies through
 * a `LinkedHashMap`.
 */
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

/**
 * The engine's tree, as the panel reads it.
 *
 * **THE FLATTENING IS [traceValuesOf]'s, NOT THIS FILE'S**, and that is the whole point of routing
 * through a JSON string rather than walking the records by hand. One walk classifies a leaf as
 * `Num`/`Flag`/`Choice`/`Absent`, one rule skips the one array, and one line maps
 * `output.background: null` to [TraceValue.Absent]. A second flattener written here would agree with it
 * on the day it was written and would be the only thing able to disagree with it afterwards — and it
 * would disagree silently, because a leaf classified as the wrong kind does not fail: it draws a blank
 * control.
 */
internal fun traceValuesOfParams(params: TraceParams): TraceValues =
    traceValuesOf(traceWireOf(params))

/** The flat map on its own, for a caller that wants the leaves without the tree beside them. */
internal fun traceFlattenParams(params: TraceParams): Map<String, TraceValue> {
    val values = traceValuesOfParams(params)
    return values.keys.associateWith { key -> values[key]!! }
}

/**
 * Every dotted key, in the engine's own declaration order.
 *
 * DERIVED FROM THE ENGINE'S DEFAULT TREE, never transcribed — so this list cannot fall behind a
 * re-vendor, and its size is a measurement rather than a claim.
 */
internal val TRACE_LEAF_KEYS: List<String> = traceValuesOfParams(TraceParams()).keys.toList()

private val traceLeafKeySet: Set<String> = TRACE_LEAF_KEYS.toSet()

/** True for a key [traceApplyLeaves] will act on. */
internal fun traceIsLeafKey(key: String): Boolean = key in traceLeafKeySet

/**
 * The keys in [keys] this build would ignore, in the order given.
 *
 * For a surface that wants to SAY that a stale saved tree named leaves that no longer exist —
 * [traceMissingKeys] is the mirror of this, and between them they cover both directions of a version
 * skew. `auto.handTuned` is reported here, correctly: it is a leaf of the tree and it is not a key of
 * the flat map.
 */
internal fun traceUnknownLeafKeys(keys: Iterable<String>): List<String> =
    keys.filterNot { it in traceLeafKeySet }

/* ────────────────────────────────────────────────────────────────────────────
 * Flat -> tree
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The engine's own merge-then-sanitise: [patch] laid over [base], then `sanitized()`.
 *
 * One leaf per line below, each naming its own dotted key beside the field it fills, because that
 * adjacency is the only thing a reader can check this table against. A key not in [patch] leaves [base]
 * alone; a key [patch] carries that this file does not know is ignored (file header).
 *
 * `auto.handTuned` is carried across from [base] untouched — it has no dotted key, so a patch cannot
 * name it and cannot lose it.
 */
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

/**
 * A whole flat map read as a tree, i.e. [traceApplyLeaves] over the engine's factory defaults.
 *
 * This is the inverse of [traceFlattenParams] for every one of the dotted keys:
 * `traceFlattenParams(traceNestLeaves(v)) == v` for any map `v` that a flatten produced, which is what
 * `TraceEngineParamsTest` asserts.
 */
internal fun traceNestLeaves(leaves: Map<String, TraceValue>): TraceParams =
    traceApplyLeaves(TraceParams(), leaves)

/**
 * A tree written by either client, read back.
 *
 * Takes the portal's JSON as readily as this app's: unknown keys are ignored, an unknown enum member
 * falls back to that property's default, and both colour leaves are converted from the portal's
 * unsigned spelling. A tree that is not JSON at all, or is JSON this cannot read, is REFUSED with the
 * sentence [traceValuesOf] refuses one with — not repaired into something plausible.
 */
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

/**
 * The tree behind a [TraceValues], via [TraceValues.wire] rather than via its leaves.
 *
 * THE WIRE AND NOT THE MAP, for the reason `TraceEngine.kt` gives where it declares the class: the wire
 * is the authority and the map is a reading convenience. Going through the map would drop
 * `auto.handTuned`, which has no key, and would drop any leaf a newer engine has added that this build
 * has never heard of — both of which the wire carries through untouched.
 */
internal fun traceParamsOf(values: TraceValues): TraceParams = traceParamsOfWire(values.wire)

/* ────────────────────────────────────────────────────────────────────────────
 * The seven records, one leaf per line
 * ──────────────────────────────────────────────────────────────────────────── */

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

/**
 * The five `auto` leaves a flat map can name, plus the one it cannot.
 *
 * `handTuned` comes from [base] and only from [base]; see the file header for why it has no key.
 */
private fun traceAutoOf(base: AutoParams, p: TraceLeaves) = AutoParams(
    mode = p.pick("auto.mode", base.mode, AutoMode.SUGGEST, AutoMode.entries),
    subjectId = p.text("auto.subjectId", base.subjectId),
    handTuned = base.handTuned,
    minConfidence = p.num("auto.minConfidence", base.minConfidence),
    allowMatte = p.flag("auto.allowMatte", base.allowMatte),
    allowCrop = p.flag("auto.allowCrop", base.allowCrop),
)

/* ────────────────────────────────────────────────────────────────────────────
 * Reading one leaf
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One patch, read leaf by leaf. A key that is absent answers the base value, so a one-key patch changes
 * one field and nothing else.
 */
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

    /**
     * TRUNCATION TOWARD ZERO, matching `Math.trunc` in the web's integer clamp — which
     * [TraceSlider.integral] already had to account for on the slider side, because a leaf sent as
     * 2047.9999 comes back 2047 and the readout settles one below the thumb. Kotlin's `Double.toInt()`
     * truncates toward zero and saturates at the `Int` bounds; every integer leaf's legal range is far
     * inside those, so the engine's own clamp afterwards lands on the same value the web's does.
     */
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

    /**
     * An enum leaf. A name that is not a member of [all] falls back to [unknown], which is that
     * property's factory default — the web's own contract, not this file's invention, and the reason a
     * tree from a newer build naming an engine this one has never heard of still opens.
     */
    fun <T : Enum<T>> pick(key: String, base: T, unknown: T, all: List<T>): T =
        when (val leaf = patch[key]) {
            null -> base
            is TraceValue.Choice -> all.firstOrNull { it.name == leaf.value } ?: unknown
            else -> wrong(key, leaf, "a name")
        }

    /** A packed ARGB colour, arriving unsigned. See the file header. */
    fun colour(key: String, base: Int): Int = when (val leaf = patch[key]) {
        null -> base
        is TraceValue.Num -> traceSignedColour(finite(key, leaf.value))
        else -> wrong(key, leaf, "a packed colour")
    }

    /**
     * `output.background`, the one leaf whose `null` is a value rather than an absence — it is the only
     * spelling of a transparent export, which is why the "White background" toggle asks whether the
     * leaf is PRESENT rather than reading a flag.
     */
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

/** What a leaf is, in the words the refusal above puts on screen. */
private fun traceKindOf(leaf: TraceValue): String = when (leaf) {
    is TraceValue.Num -> "a number"
    is TraceValue.Flag -> "a true/false flag"
    is TraceValue.Choice -> "a name"
    TraceValue.Absent -> "nothing at all"
}

/* ────────────────────────────────────────────────────────────────────────────
 * Colours
 * ──────────────────────────────────────────────────────────────────────────── */

/** Kotlin's signed `Int` as the unsigned 32-bit number every tree the portal writes carries. */
private fun traceUnsignedColour(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

/** The inverse: 4294967295 back to `0xFFFFFFFF.toInt()`, which is -1, not `Int.MAX_VALUE`. */
private fun traceSignedColour(packed: Double): Int = packed.toLong().toInt()

/** The leaves of `output` that hold a packed colour. Their own keys, not their dotted paths. */
private val TRACE_COLOUR_LEAVES: List<String> = listOf("strokeColor", "background")

/**
 * The two colour leaves of an incoming tree, rewritten so kotlinx can decode them into `Int`.
 *
 * WITHOUT THIS, 4294967295 IS NOT AN `Int` AND THE DECODE FAILS OUTRIGHT — every tree the portal has
 * written carries opaque black as 4278190080 and opaque white as 4294967295, both above `Int.MAX`.
 * `JsonNull` is tested before anything else for the reason the flattener states: it IS a
 * `JsonPrimitive`, so a check that asks the general question first reads `null` as the string "null"
 * and the "White background" toggle sticks on forever.
 */
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
