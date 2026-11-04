package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

const val TRACE_GROUP_SOURCE = "Source"

const val TRACE_GROUP_SHARPEN = "Sharpening"

const val TRACE_GROUP_EDGES = "Edges"

const val TRACE_GROUP_CLEANUP = "Cleanup"

const val TRACE_GROUP_OUTPUT = "Output"

const val TRACE_GROUP_EXPORT = "Export"

val TRACE_GROUPS: List<String> = listOf(
    TRACE_GROUP_SOURCE,
    TRACE_GROUP_SHARPEN,
    TRACE_GROUP_EDGES,
    TRACE_GROUP_CLEANUP,
    TRACE_GROUP_OUTPUT,
    TRACE_GROUP_EXPORT,
)

enum class TraceTier {
    PRIMARY,

    ADVANCED,

    EXPORT,
}

enum class TraceScale {
    LINEAR,

    SQUARE,

    LOG,
}

const val TRACE_SCALE_RECHECK: String =
    "grep -o \"minBlobArea = [0-9]*\"   Styles.kt Subjects.kt | sed 's/.*= //' | sort -n | uniq -c\n" +
        "grep -o \"minPathLength = [0-9.]*\" Styles.kt Subjects.kt | sed 's/.*= //' | sort -n | uniq -c\n" +
        "grep -o \"xdogPhi = [0-9.]*\"      Styles.kt             | sed 's/.*= //' | sort -n | uniq -c"

@Immutable
sealed class TraceControl {
    abstract val key: String
    abstract val label: String
    abstract val hint: String
    abstract val group: String
    abstract val tier: TraceTier

    open val handsetNote: String? = null
}

@Immutable
class TraceSlider(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: TraceTier,
    val min: Double,
    val max: Double,
    val step: Double,
    val scale: TraceScale = TraceScale.LINEAR,
    val integral: Boolean = false,
    override val handsetNote: String? = null,
) : TraceControl()

@Immutable
class TraceToggle(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: TraceTier,
    override val handsetNote: String? = null,
    val read: (TraceValues) -> Boolean? = { it.flag(key) },
    val patch: (Boolean) -> Map<String, TraceValue> = { mapOf(key to TraceValue.Flag(it)) },
) : TraceControl()

@Immutable
data class TraceChoiceOption(val value: String, val label: String)

@Immutable
class TraceChoice(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: TraceTier,
    val options: List<TraceChoiceOption>,
    override val handsetNote: String? = null,
) : TraceControl()

@Immutable
data class TraceNumberOption(val value: Double, val label: String, val note: String)

@Immutable
class TraceNumberChoice(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: TraceTier,
    val options: List<TraceNumberOption>,
    override val handsetNote: String? = null,
) : TraceControl()

fun TraceSlider.read(values: TraceValues): Double? = values.number(key)

fun TraceSlider.patch(value: Double): Map<String, TraceValue> {
    val snapped = snap(value)
    val out = if (integral) snapped.roundToLong().toDouble() else snapped
    return mapOf(key to TraceValue.Num(out))
}

fun TraceChoice.read(values: TraceValues): String? = values.choice(key)

fun TraceChoice.patch(value: String): Map<String, TraceValue> =
    mapOf(key to TraceValue.Choice(value))

fun TraceNumberChoice.read(values: TraceValues): Double? = values.number(key)

fun TraceNumberChoice.patch(value: Double): Map<String, TraceValue> =
    mapOf(key to TraceValue.Num(value.roundToLong().toDouble()))

fun TraceSlider.fractionOf(value: Double): Float {
    if (max <= min) return 0f
    val v = value.coerceIn(min, max)
    val t = when (scale) {
        TraceScale.LINEAR -> (v - min) / (max - min)
        TraceScale.SQUARE -> sqrt((v - min) / (max - min))
        TraceScale.LOG -> if (min <= 0.0) 0.0 else ln(v / min) / ln(max / min)
    }
    return t.coerceIn(0.0, 1.0).toFloat()
}

fun TraceSlider.valueAt(fraction: Float): Double {
    val t = fraction.toDouble().coerceIn(0.0, 1.0)
    val raw = when (scale) {
        TraceScale.LINEAR -> min + (max - min) * t
        TraceScale.SQUARE -> min + (max - min) * t * t
        TraceScale.LOG -> if (min <= 0.0) min else min * (max / min).pow(t)
    }
    return snap(raw)
}

fun TraceSlider.snap(value: Double): Double {
    if (step <= 0.0) return value.coerceIn(min, max)
    val steps = ((value - min) / step).roundToLong()
    val snapped = min + steps * step
    val factor = 10.0.pow(traceDecimals(step))
    return (kotlin.math.round(snapped * factor) / factor).coerceIn(min, max)
}

fun traceDecimals(step: Double): Int = when {
    step >= 1.0 -> 0
    step >= 0.1 -> 1
    else -> 2
}

fun traceFormatValue(value: Double, step: Double): String {
    if (!value.isFinite()) return "0"
    if (step >= 1.0) return value.roundToInt().toString()
    val decimals = traceDecimals(step)
    return String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
}

const val TRACE_OPAQUE_WHITE: Double = 4294967295.0

val TRACE_RESOLUTION: List<TraceNumberOption> = listOf(
    TraceNumberOption(
        value = 1024.0,
        label = "Fast",
        note = "1024 px. Fast; fine for bold drawings.",
    ),
    TraceNumberOption(
        value = 2048.0,
        label = "Standard",
        note = "2048 px. The default.",
    ),
    TraceNumberOption(
        value = 4096.0,
        label = "Detailed",
        note = "4096 px. For faint pencil and fine hatching. Slow.",
    ),
)

val TRACE_SLIDERS: List<TraceSlider> = listOf(
    TraceSlider(
        key = "preprocess.denoiseStrength",
        label = "Noise reduction",
        hint = "Smoothing before edge detection. Too much erases thin lines.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    TraceSlider(
        key = "preprocess.claheClip",
        label = "Local contrast",
        hint = "Higher lifts detail from shadow, but adds grain.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        min = 1.0,
        max = 8.0,
        step = 0.1,
    ),
    TraceSlider(
        key = "preprocess.unsharpAmount",
        label = "Sharpen amount",
        hint = "Sharpening strength. 0 is off; above 2 lines get halos.",
        group = TRACE_GROUP_SHARPEN,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 5.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "preprocess.unsharpSigma",
        label = "Sharpen radius",
        hint = "Sharpening radius. Small for hairlines, large for broad edges.",
        group = TRACE_GROUP_SHARPEN,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.sensitivity",
        label = "Edge sensitivity",
        hint = "Higher means more ink.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    TraceSlider(
        key = "edge.blurSigma",
        label = "Pre-blur",
        hint = "Raise to ignore texture, lower to keep hairlines.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.flow.sigmaM",
        label = "Stroke length",
        hint = "How far Flow follows a stroke.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 12.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.xdogPhi",
        label = "Edge hardness",
        hint = "3 is soft graphite, 300 is a hard woodcut.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.1,
        max = 300.0,
        step = 0.1,
        scale = TraceScale.LOG,
    ),
    TraceSlider(
        key = "cleanup.minBlobArea",
        label = "Minimum speck",
        hint = "Drops specks smaller than this (px).",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 1000.0,
        step = 1.0,
        scale = TraceScale.SQUARE,
        integral = true,
        handsetNote = "Raise to drop table texture.",
    ),
    TraceSlider(
        key = "cleanup.closeRadius",
        label = "Close gaps",
        hint = "Joins strokes that nearly touch. Too high fuses lines.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 8.0,
        step = 1.0,
        integral = true,
    ),
    TraceSlider(
        key = "cleanup.maxGap",
        label = "Bridge reach",
        hint = "Max gap to bridge between stroke ends.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 64.0,
        step = 1.0,
        integral = true,
    ),
    TraceSlider(
        key = "cleanup.pruneSpurs",
        label = "Prune spurs",
        hint = "Removes short whiskers. 0 keeps all.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 32.0,
        step = 1.0,
        integral = true,
    ),
    TraceSlider(
        key = "output.simplify",
        label = "Simplify",
        hint = "Higher means fewer nodes and straighter lines.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 8.0,
        step = 0.1,
    ),
    TraceSlider(
        key = "output.corner",
        label = "Keep corners",
        hint = "Higher keeps more corners.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 180.0,
        step = 1.0,
    ),
    TraceSlider(
        key = "output.smoothIterations",
        label = "Smoothing passes",
        hint = "Each pass rounds the lines a little more.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 8.0,
        step = 1.0,
        integral = true,
    ),
    TraceSlider(
        key = "output.strokeWidth",
        label = "Stroke width",
        hint = "Painted width of the exported line, in document units.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "output.minPathLength",
        label = "Minimum path",
        hint = "Paths shorter than this are dropped.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 200.0,
        step = 0.5,
        scale = TraceScale.SQUARE,
    ),
)

val TRACE_TOGGLES: List<TraceToggle> = listOf(
    TraceToggle(
        key = "preprocess.invertInput",
        label = "Source is light-on-dark",
        hint = "For chalkboards, negatives and white-on-black.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "preprocess.claheEnabled",
        label = "Equalise local contrast",
        hint = "Turn off for evenly lit photos.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "preprocess.perspectiveCorrect",
        label = "Rectify the page",
        hint = "Flattens paper photographed at an angle.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        handsetNote = "Disables the comparison view. Leave off for square-on photos.",
    ),
    TraceToggle(
        key = "cleanup.skeletonize",
        label = "Reduce ink to centrelines",
        hint = "Thins strokes to one pixel. Ignored in outline mode.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "cleanup.bridgeGaps",
        label = "Bridge stroke ends",
        hint = "Joins nearby stroke ends.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "cleanup.removeBorderTouching",
        label = "Drop shapes touching the frame",
        hint = "Removes board, lid and table edges.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.modulateWidth",
        label = "Vary width with the stroke",
        hint = "Keeps thick-and-thin brushwork.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.fillClosed",
        label = "Fill closed shapes",
        hint = "Fills closed regions solid.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.background",
        label = "White background",
        hint = "Off exports a transparent background.",
        group = TRACE_GROUP_EXPORT,
        tier = TraceTier.EXPORT,
        handsetNote = "Affects the file only.",
        read = { it.present("output.background") },
        patch = { white ->
            mapOf(
                "output.background" to
                    if (white) TraceValue.Num(TRACE_OPAQUE_WHITE) else TraceValue.Absent,
            )
        },
    ),
)

val TRACE_CHOICES: List<TraceChoice> = listOf(
    TraceChoice(
        key = "edge.engine",
        label = "Edge engine",
        hint = "Which edge detector to use.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        options = listOf(
            TraceChoiceOption("FDOG", "Flow (long coherent strokes)"),
            TraceChoiceOption("XDOG", "XDoG (drawn look, tunable hardness)"),
            TraceChoiceOption("CANNY", "Canny (straight geometric edges)"),
            TraceChoiceOption("ADAPTIVE", "Adaptive threshold (already line art)"),
            TraceChoiceOption("LOG", "Laplacian (thinnest, most delicate)"),
        ),
        handsetNote = "Flow is the default but about six times slower on a phone.",
    ),
    TraceChoice(
        key = "preprocess.denoise",
        label = "Noise filter",
        hint = "Bilateral keeps edges, median removes speckle, anisotropic flattens weave.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        options = listOf(
            TraceChoiceOption("NONE", "None"),
            TraceChoiceOption("BILATERAL", "Bilateral"),
            TraceChoiceOption("MEDIAN", "Median"),
            TraceChoiceOption("ANISOTROPIC", "Anisotropic"),
        ),
    ),
    TraceChoice(
        key = "matte.mode",
        label = "Background matte",
        hint = "Separates the subject from its background.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        options = listOf(
            TraceChoiceOption("NONE", "None"),
            TraceChoiceOption("BORDER_FLOOD", "Flood from the border"),
            TraceChoiceOption("SALIENCY", "Keep the salient subject"),
        ),
        handsetNote = "Off by default — a wrong matte can delete artwork.",
    ),
    TraceChoice(
        key = "output.vectorMode",
        label = "Vector mode",
        hint = "Centreline: one path per stroke. Outline: one closed path per region.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.PRIMARY,
        options = listOf(
            TraceChoiceOption("CENTERLINE", "Centreline (one stroke, one path)"),
            TraceChoiceOption("OUTLINE", "Outline (one region, one closed path)"),
        ),
    ),
)

val TRACE_NUMBER_CHOICES: List<TraceNumberChoice> = listOf(
    TraceNumberChoice(
        key = "preprocess.workingLongEdge",
        label = "Trace resolution",
        hint = "Higher captures finer detail but takes longer.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.PRIMARY,
        options = TRACE_RESOLUTION,
    ),
)

val TRACE_CONTROLS: List<TraceControl> = TRACE_GROUPS.flatMap { group ->
    val inGroup: (TraceControl) -> Boolean = { it.group == group }
    TRACE_NUMBER_CHOICES.filter(inGroup) +
        TRACE_CHOICES.filter(inGroup) +
        TRACE_SLIDERS.filter(inGroup) +
        TRACE_TOGGLES.filter(inGroup)
}

val TRACE_PARAM_COUNT: Int = TRACE_CONTROLS.size

val TRACE_ADVANCED_COUNT: Int = TRACE_CONTROLS.count { it.tier == TraceTier.ADVANCED }

val TRACE_PRIMARY_KEYS: List<String> =
    TRACE_CONTROLS.filter { it.tier == TraceTier.PRIMARY }.map { it.key }

const val TRACE_DISCLOSURE_ACTION: String = "Show more options"

private fun traceSettings(count: Int): String = if (count == 1) "1 setting" else "$count settings"

fun traceAdvancedGroups(values: TraceValues): List<Pair<String, List<TraceControl>>> =
    TRACE_GROUPS.mapNotNull { group ->
        val rows = TRACE_CONTROLS.filter {
            it.group == group && it.tier == TraceTier.ADVANCED && it.key in values.keys
        }
        if (rows.isEmpty()) null else group to rows
    }

fun traceAdvancedRevealed(values: TraceValues): Int =
    traceAdvancedGroups(values).sumOf { it.second.size }

fun traceDisclosureLabel(open: Boolean, revealed: Int, changedHidden: Int): String = if (open) {
    "Hide the other ${traceSettings(revealed)}"
} else {
    TRACE_DISCLOSURE_ACTION + " · ${traceSettings(revealed)}" +
        (if (changedHidden > 0) " · $changedHidden changed" else "")
}

fun traceDisclosureClickLabel(open: Boolean, revealed: Int): String = if (open) {
    "hide the other ${traceSettings(revealed)}"
} else {
    "show the other ${traceSettings(revealed)}"
}

fun traceDisclosureState(open: Boolean): String = if (open) "Expanded" else "Collapsed"

fun traceDisclosureBlurb(revealed: Int): String =
    "The other ${traceSettings(revealed)}. All optional."

fun traceHiddenChangedSentence(labels: List<String>): String? = when {
    labels.isEmpty() -> null
    labels.size == 1 -> "A hidden setting changed: ${labels.first()}."
    else ->
        "${labels.size} hidden settings changed: ${labels.joinToString(", ")}."
}

fun traceChangedBehindDisclosure(before: TraceValues, after: TraceValues): List<String> =
    traceChangedHiddenLabels(
        before = before,
        after = after,
        visible = setOf(TraceTier.PRIMARY, TraceTier.EXPORT),
    )

val TRACE_CUT: Map<String, String> = mapOf(
    "cleanup.thinning" to
        "The engine's own documentation says Guo–Hall is the better kernel and the engine defaults to " +
        "Zhang–Suen. The difference cannot be seen at preview size and can be seen in the exported " +
        "file, and if the two clients defaulted differently one sheet of paper would produce two " +
        "different drawings. It stays at the engine's default on both.",
)

fun traceInactiveReason(control: TraceControl, values: TraceValues): String? {
    val engine = values.choice("edge.engine")
    val outline = values.choice("output.vectorMode") == "OUTLINE"
    return when (control.key) {
        "edge.blurSigma" ->
            if (engine != null && engine != "CANNY") "Only the Canny engine reads this." else null
        "edge.flow.sigmaM" ->
            if (engine != null && engine != "FDOG") "Only the Flow engine reads this." else null
        "edge.xdogPhi" ->
            if (engine != null && engine != "XDOG" && engine != "FDOG") {
                "Only the XDoG and Flow engines read this."
            } else {
                null
            }
        "cleanup.skeletonize" ->
            if (outline) "Outline mode traces the edge of a region, so nothing is thinned." else null
        "cleanup.pruneSpurs" -> when {
            values.flag("cleanup.skeletonize") == false ->
                "“Reduce ink to centrelines” is off, so there is no skeleton to prune."
            outline -> "Outline mode traces the edge of a region, so nothing is thinned."
            else -> null
        }
        "cleanup.maxGap" ->
            if (values.flag("cleanup.bridgeGaps") == false) "“Bridge stroke ends” is off." else null
        "preprocess.claheClip" ->
            if (values.flag("preprocess.claheEnabled") == false) {
                "“Equalise local contrast” is off."
            } else {
                null
            }
        "preprocess.unsharpSigma" ->
            values.number("preprocess.unsharpAmount")
                ?.takeIf { it <= 0.0 }
                ?.let { "“Sharpen amount” is 0." }
        "preprocess.denoiseStrength" -> when (values.choice("preprocess.denoise")) {
            "NONE" -> "The noise filter is set to None."
            "MEDIAN" ->
                "The median filter uses a fixed radius instead."
            else -> null
        }
        else -> null
    }
}

fun traceMissingKeys(values: TraceValues): List<String> =
    TRACE_CONTROLS.map { it.key }.filter { it !in values.keys }

private fun TraceControl.compareValue(values: TraceValues): Any? = when (this) {
    is TraceSlider -> read(values)
    is TraceToggle -> read(values)
    is TraceChoice -> read(values)
    is TraceNumberChoice -> read(values)
}

fun traceChangedLabels(before: TraceValues, after: TraceValues): List<String> =
    TRACE_CONTROLS.filter { it.compareValue(before) != it.compareValue(after) }.map { it.label }

fun traceChangedHiddenLabels(
    before: TraceValues,
    after: TraceValues,
    visible: Set<TraceTier>,
): List<String> = TRACE_CONTROLS
    .filter { it.tier !in visible && it.compareValue(before) != it.compareValue(after) }
    .map { it.label }

fun traceOverwriteNotice(
    source: String,
    before: TraceValues,
    after: TraceValues,
): String? {
    val overwritten = traceChangedLabels(before, after)
    if (overwritten.isEmpty()) return null
    val list = overwritten.joinToString(", ")
    return if (overwritten.size == 1) {
        "$source changed one setting: $list."
    } else {
        "$source changed ${overwritten.size} settings: $list."
    }
}

const val TRACE_DEFAULT_SUBJECT_ID: String = "sketch"

val TRACE_SUBJECT_FOR_CATEGORY: Map<String, String> = mapOf(
    "JEWELLERY" to "jewellery",
    "SAREE" to "textile",
    "DUPATTA_STOLE" to "textile",
    "YARDAGE" to "textile",
    "FLOOR_COVERING" to "textile",
    "TABLE_LINEN" to "textile",
    "DECORATIVE" to "wood-carving",
)

fun traceSubjectFor(category: String?): String =
    TRACE_SUBJECT_FOR_CATEGORY[category?.trim().orEmpty()] ?: TRACE_DEFAULT_SUBJECT_ID
