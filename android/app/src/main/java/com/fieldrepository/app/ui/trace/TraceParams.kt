package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * **THE TRACING CONTROLS THIS HANDSET OFFERS — as a table, and as an argument about a thumb.**
 *
 * ── WHAT THIS IS THE MIRROR OF ────────────────────────────────────────────────────────────────
 *
 * The web client's `traceParamTable.ts` is the portal's table: seventeen sliders, nine toggles, four
 * choices and one named-option control, whose labels, hints, groups and NARROWED maxima came from the
 * upstream tracer's own dock. This file is that table on Android. Every label and every [hint] below
 * is that file's string, character for character — because a researcher who has tuned a trace on the
 * laptop and then does it again in a village must not have to learn a second vocabulary for the same
 * slider.
 *
 * ── WHAT IS DELIBERATELY DIFFERENT, AND WHY EACH DIFFERENCE EXISTS ────────────────────────────
 *
 *  1. **Four of the web's sliders cannot be operated by a thumb, measured rather than felt.** A 360 dp
 *     handset gives a ~328 dp track and a fingertip lands reliably to about 8 dp, so a researcher can
 *     reach roughly **41 distinguishable positions** without a fine-adjust affordance. Against that,
 *     and against the values the engine's own twenty styles and twelve subjects actually write:
 *
 *     | slider | web range / step | what a thumb gets |
 *     | --- | --- | --- |
 *     | `cleanup.minBlobArea` | 0..1000 / 1 | **15 of the 26 preset values are <= 64**, which is 6.4% of the track — 2 dp to 21 dp, i.e. **19 dp, about two fingertips, for fifteen different values** |
 *     | `output.minPathLength` | 0..200 / 0.5 | **all 14 preset values are <= 40** — the bottom fifth, 66 dp |
 *     | `edge.xdogPhi` | 0.1..300 / 0.1 | the hint itself says "3 is soft graphite"; **0.1..6 is the first 6.5 dp of the track**, so the entire soft half of the control's own documented range is one fingertip |
 *     | `preprocess.workingLongEdge` | 256..4096 / 64 | one 64 px step is **5.5 dp**, under what a fingertip resolves, so the engine's own default of 2048 is not a value a thumb can reliably land on |
 *
 *     The first three keep their range and get a non-linear [TraceScale], so the values the presets
 *     use spread across travel a thumb has. **The range is unchanged** — widening or narrowing one
 *     would make the two clients disagree about what a researcher may ASK for, which is a different
 *     and worse thing than disagreeing about how the asking is drawn. The fourth stops being a slider
 *     at all: on a phone the trace resolution is a time-and-memory control, not a look control, so it
 *     is three named options with their cost stated ([TRACE_RESOLUTION]).
 *
 *  2. **One control is CUT: `cleanup.thinning`** (Zhang–Suen vs Guo–Hall). See [TRACE_CUT] for the
 *     argument, which is about vendoring discipline and not about screen space.
 *
 *  3. **`output.background` is RELOCATED to the export step**, not removed, because it is a property
 *     of the file being written rather than of the tracing. [TraceTier.EXPORT].
 *
 *  4. **Six controls lead and the rest are one disclosure away**, and the six are not quite the web's
 *     seven — `output.strokeWidth` is demoted. See [TRACE_PRIMARY_KEYS].
 *
 *  5. **A control that cannot affect this trace says so** ([traceInactiveReason]). The web draws
 *     thirty-one live rows on a laptop and a researcher can see the whole dock at once; on a 6" screen
 *     a slider that does nothing under the current settings is a slider somebody will spend a minute
 *     on. Every reason below was read off the pipeline, and one of them is a genuine trap: the
 *     `sketch` subject selects `MEDIAN`, and the MEDIAN branch reads `medianRadius` and never
 *     `denoiseStrength`, so the "Noise reduction" slider is inert exactly where this feature is most
 *     used.
 *
 * ── WHAT THIS FILE IS NOT ─────────────────────────────────────────────────────────────────────
 *
 * **It is not a clamp table and must never become one.** `:core-pipeline`'s `Params.kt` declares 74
 * leaves with 74 individually-argued bounds, several of which encode a measured incident rather than
 * a taste, and `sanitized()` is documented idempotent precisely so a UI may run it on every slider
 * tick without ever disagreeing with the pipeline about what "legal" means. The range figures
 * below — [TraceSlider.min], [TraceSlider.max] and [TraceSlider.step] — are **display ranges**: they
 * decide where a thumb may travel, never what the engine will accept. Every value this panel produces
 * goes to [TraceEngineRuntime.withOverrides] and comes back sanitised, and the sanitised value is what
 * is drawn afterwards.
 *
 * (The designer portal calls the same three fields `DwRange`. That name is written here once, in
 * backticks and NOT in KDoc brackets, so a search for it still lands on this paragraph — `[DwRange]`
 * was a dangling link to a type this repository does not have, which Dokka reports and a reader
 * follows to nothing.)
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Groups — the web's five, plus the one this client needs
 * ──────────────────────────────────────────────────────────────────────────── */

/** Verbatim from the web's table. A researcher looks for a control by pipeline stage. */
const val TRACE_GROUP_SOURCE = "Source"

/** Not an upstream group: the engine ships an unsharp mask and the upstream UI never offered it. */
const val TRACE_GROUP_SHARPEN = "Sharpening"

const val TRACE_GROUP_EDGES = "Edges"

const val TRACE_GROUP_CLEANUP = "Cleanup"

const val TRACE_GROUP_OUTPUT = "Output"

/**
 * Not a web group. The one control the export step owns — see [TraceTier.EXPORT].
 *
 * It has a group of its own rather than being dropped from the table, because the change-reporting
 * functions below walk the table to say what a preset moved, and a control that is not in the table
 * cannot be reported. Relocating a row and deleting it are different things.
 */
const val TRACE_GROUP_EXPORT = "Export"

/**
 * Display order.
 *
 * Sharpening sits directly after Source because it IS a source-stage operation — the pipeline applies
 * the unsharp mask to the grey plane before any edge engine runs — and a researcher who has just told
 * the panel the photograph is soft should not have to scroll past the edge controls to say how soft.
 */
val TRACE_GROUPS: List<String> = listOf(
    TRACE_GROUP_SOURCE,
    TRACE_GROUP_SHARPEN,
    TRACE_GROUP_EDGES,
    TRACE_GROUP_CLEANUP,
    TRACE_GROUP_OUTPUT,
    TRACE_GROUP_EXPORT,
)

/* ────────────────────────────────────────────────────────────────────────────
 * Tiers
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where a control is drawn, which on a handset is most of what "how important is it" means. */
enum class TraceTier {
    /** On screen from the first frame, above the disclosure. */
    PRIMARY,

    /** Behind one disclosure, in the web's own groups and the web's own order. */
    ADVANCED,

    /** Not in the parameter panel at all: on the step that writes the file. */
    EXPORT,
}

/* ────────────────────────────────────────────────────────────────────────────
 * Scales
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How a slider's TRAVEL maps onto its VALUE. Nothing here changes the range.
 *
 * A linear slider assumes the useful values are spread evenly across the range, and for fourteen of
 * these seventeen sliders they are. For three they are not, by a factor of fifty — see this file's
 * header, difference 1.
 */
enum class TraceScale {
    /** `value = min + (max - min) * t`. */
    LINEAR,

    /**
     * `value = min + (max - min) * t^2`.
     *
     * A quarter of the travel reaches a sixteenth of the range, so `minBlobArea`'s 6..64 — where
     * fifteen of the twenty-six preset values live — occupies the first quarter of the track instead
     * of the first 2 dp, and 1000 is still reachable at the end.
     */
    SQUARE,

    /**
     * `value = min * (max / min)^t`. Requires `min > 0`.
     *
     * For `xdogPhi` over its 0.1..300 range: the styles write 6, 40, 60, 120 and 200, and the engine's
     * own default is 20. Linear travel puts 3 — the value this control's own hint names as "soft
     * graphite" — at **3.2 dp** from the left of a 328 dp track, with everything softer than that
     * crammed inside it; geometric travel puts the same 3 at **139 dp** and spreads 6/20/40/60/120/200
     * across 168..311 dp. The gain is not that the six preset values separate (they were already about
     * 15 dp apart) — it is that the half of the range the hint describes becomes reachable at all.
     */
    LOG,
}

/**
 * The commands that produced the preset-value distributions in this file's header, so the claim can
 * be re-checked rather than believed. Run in `android/core-pipeline/src/main/java/com/offlinetracer/pipeline/`.
 */
const val TRACE_SCALE_RECHECK: String =
    "grep -o \"minBlobArea = [0-9]*\"   Styles.kt Subjects.kt | sed 's/.*= //' | sort -n | uniq -c\n" +
        "grep -o \"minPathLength = [0-9.]*\" Styles.kt Subjects.kt | sed 's/.*= //' | sort -n | uniq -c\n" +
        "grep -o \"xdogPhi = [0-9.]*\"      Styles.kt             | sed 's/.*= //' | sort -n | uniq -c"

/* ────────────────────────────────────────────────────────────────────────────
 * Control shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the panel.
 *
 * [key] is the dot path the engine's own tree flattens to (`edge.flow.sigmaM`), which is what
 * [TraceValues] is keyed by and what [TraceEngineRuntime.withOverrides] takes. It is the identity of
 * a control across both clients and across a vendored update; nothing else here is.
 *
 * [hint] is the UPSTREAM's sentence and is never paraphrased. [handsetNote] is this client's own, for
 * the places where a phone differs from a browser, and is kept separate so a parity test can pin the
 * first without tripping over the second.
 */
@Immutable
sealed class TraceControl {
    abstract val key: String
    abstract val label: String
    abstract val hint: String
    abstract val group: String
    abstract val tier: TraceTier

    /** A sentence true only on this client, or null. Rendered under [hint], never instead of it. */
    open val handsetNote: String? = null
}

/** A continuous control. [min]/[max]/[step] are DISPLAY bounds — see this file's header. */
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
    /**
     * True when the engine stores this leaf as an integer.
     *
     * IT MATTERS, and not for tidiness. The sanitiser truncates toward zero for integer leaves, so a
     * slider that sent 2047.9999 would be answered with 2047 and the readout would settle one below
     * the value the thumb was on. The patch rounds first.
     */
    val integral: Boolean = false,
    override val handsetNote: String? = null,
) : TraceControl()

/** A two-state control. */
@Immutable
class TraceToggle(
    override val key: String,
    override val label: String,
    override val hint: String,
    override val group: String,
    override val tier: TraceTier,
    override val handsetNote: String? = null,
    /**
     * How the value is read out of a sanitised tree.
     *
     * A parameter with a default rather than a `when (key)` inside a helper, because exactly one
     * control needs a different answer and a hidden special case is how the wrong one gets it.
     * `output.background` holds a packed colour or `null`, and `null` is the ONLY spelling of a
     * transparent export — so its toggle asks whether the leaf is PRESENT.
     */
    val read: (TraceValues) -> Boolean? = { it.flag(key) },
    val patch: (Boolean) -> Map<String, TraceValue> = { mapOf(key to TraceValue.Flag(it)) },
) : TraceControl()

/** One row of a [TraceChoice]. [value] is the engine's enum name, which equals its wire string. */
@Immutable
data class TraceChoiceOption(val value: String, val label: String)

/** A one-of-many control over a string enum. */
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

/** One row of a [TraceNumberChoice]: what it costs, said in the row rather than discovered. */
@Immutable
data class TraceNumberOption(val value: Double, val label: String, val note: String)

/**
 * A one-of-many control over a NUMERIC leaf.
 *
 * Exists for exactly one control, and is a shape rather than a special case for the same reason
 * [TraceToggle.read] is a parameter: `preprocess.workingLongEdge` is a number the engine sanitises as
 * a number, and modelling it as a string choice would mean converting at both ends, which is two
 * places for a "2048" to become a "2048.0".
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Reading and writing one control
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * This slider's current value, or **null when the engine did not send this leaf at all**.
 *
 * Null is not coerced to [TraceSlider.min], and that is deliberate. A missing leaf means this build
 * knows about a control the runtime's engine copy does not have — a vendored update removed or
 * renamed it — and a row drawn at its minimum would show a researcher a value nothing holds and let
 * them "change" it to the same nothing. The panel skips the row and [traceMissingKeys] says how many
 * it skipped, which is the same discipline the pipeline states for its own notes: a surface that
 * silently drops something and one that had nothing to drop must not look identical.
 */
fun TraceSlider.read(values: TraceValues): Double? = values.number(key)

/** One patch entry, snapped to [TraceSlider.step] and rounded when integral. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Travel <-> value
 * ──────────────────────────────────────────────────────────────────────────── */

/** Where on the track (0..1) this value sits, under this slider's scale. */
fun TraceSlider.fractionOf(value: Double): Float {
    if (max <= min) return 0f
    val v = value.coerceIn(min, max)
    val t = when (scale) {
        TraceScale.LINEAR -> (v - min) / (max - min)
        TraceScale.SQUARE -> sqrt((v - min) / (max - min))
        // `min > 0` is guaranteed by the table for every LOG slider, but a zero here would be a silent
        // NaN across the whole track rather than a crash, so it is checked where it would do damage
        // rather than asserted somewhere a reader has to go and find.
        TraceScale.LOG -> if (min <= 0.0) 0.0 else ln(v / min) / ln(max / min)
    }
    return t.coerceIn(0.0, 1.0).toFloat()
}

/** The value at this point on the track, snapped to the step. Inverse of [fractionOf]. */
fun TraceSlider.valueAt(fraction: Float): Double {
    val t = fraction.toDouble().coerceIn(0.0, 1.0)
    val raw = when (scale) {
        TraceScale.LINEAR -> min + (max - min) * t
        TraceScale.SQUARE -> min + (max - min) * t * t
        TraceScale.LOG -> if (min <= 0.0) min else min * (max / min).pow(t)
    }
    return snap(raw)
}

/**
 * The nearest legal position on this slider.
 *
 * The rounding to the step's own decimal count is not cosmetic: `0.05 * 7` is 0.35000000000000003 in
 * binary floating point, and that value would reach the readout, the wire and the cross-runtime
 * parity record. The engine would sanitise it to itself, so nothing would fail — the two clients
 * would simply hold different numbers for the same thumb position, which is the class of divergence
 * this whole feature is disciplined against.
 */
fun TraceSlider.snap(value: Double): Double {
    if (step <= 0.0) return value.coerceIn(min, max)
    val steps = ((value - min) / step).roundToLong()
    val snapped = min + steps * step
    val factor = 10.0.pow(traceDecimals(step))
    return (kotlin.math.round(snapped * factor) / factor).coerceIn(min, max)
}

/**
 * Decimal places for a readout, derived from the step so an integer control never shows "12.00" and a
 * 0.01 control never shows "0". Verbatim from the web's `formatValue`.
 */
fun traceDecimals(step: Double): Int = when {
    step >= 1.0 -> 0
    step >= 0.1 -> 1
    else -> 2
}

/**
 * The numeric readout every slider carries. Verbatim from the web's `formatValue`.
 *
 * **`Locale.ROOT`, PINNED AT THE FORMATTER AND NOT AT THE JVM.** `app/build.gradle.kts` runs the unit
 * tests as en_US precisely so a locale bug in a formatter can fail a test at all, and its own comment
 * forbids "fixing" such a failure by changing the build file. A `%.2f` under a locale with a comma
 * decimal separator would write "0,35" into a readout beside a slider and into anything that read it
 * back — half of Europe, and every handset set to one of those languages.
 */
fun traceFormatValue(value: Double, step: Double): String {
    if (!value.isFinite()) return "0"
    if (step >= 1.0) return value.roundToInt().toString()
    val decimals = traceDecimals(step)
    return String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
}

/* ────────────────────────────────────────────────────────────────────────────
 * The table
 * ──────────────────────────────────────────────────────────────────────────── */

/** Packed opaque white — how "a white background" is spelled in `output.background`. */
const val TRACE_OPAQUE_WHITE: Double = 4294967295.0

/**
 * The three trace resolutions this handset offers, with their cost in the row.
 *
 * ── WHY THREE NAMED OPTIONS AND NOT A SLIDER ──────────────────────────────────────────────────
 *
 * See this file's header, difference 1: sixty stops on forty-one reachable positions means 2048 — the
 * engine's own default — is a value a thumb cannot land on. But the deeper reason is that on a phone
 * this is not a look control. Above 8192 px "nothing on a phone can allocate the six float buffers a
 * trace needs at once", and the cost curve was measured directly: at the product's own input size a
 * full trace was 2.9 s with the adaptive engine and 16.7 s with the shipped flow default on a
 * LAPTOP's V8, which scales to roughly 12–20 s and 67–117 s on a mid-range handset. A control whose
 * settings are "twelve seconds", "half a minute" and "two minutes" should say so rather than let the
 * researcher discover it.
 *
 * ── THE CEILING IS THE RUNTIME'S, NOT THIS FILE'S ─────────────────────────────────────────────
 *
 * `Detailed` is offered only where [TraceAvailability.maxWorkingLongEdge] reaches it, and that number
 * is measured by the half that can measure it. **Until a device measurement exists the panel caps at
 * Standard**, because an unmeasured ceiling written into a screen is a guess wearing a limit's
 * clothes.
 */
val TRACE_RESOLUTION: List<TraceNumberOption> = listOf(
    TraceNumberOption(
        value = 1024.0,
        label = "Fast",
        note = "1024 px. About a quarter of the work of Standard. Fine for a large, boldly drawn sheet.",
    ),
    TraceNumberOption(
        value = 2048.0,
        label = "Standard",
        note = "2048 px. The engine's own default, and what the portal uses unless it is told otherwise.",
    ),
    TraceNumberOption(
        value = 4096.0,
        label = "Detailed",
        note = "4096 px. Four times the work of Standard, for faint pencil and fine hatching. Slow on a phone.",
    ),
)

/**
 * The seventeen sliders.
 *
 * Order is the web's. Ranges are the web's NARROWED maxima, which are themselves narrower than the
 * engine's — 4096 not 8192, sharpen radius 8 not 32 — each with its reason recorded at the web site.
 * Carrying the narrowing verbatim is the point: it is the only place this product's constraints on
 * the parameter surface are already written down, and widening one here would let a handset ask for
 * something the portal will not.
 */
val TRACE_SLIDERS: List<TraceSlider> = listOf(
    TraceSlider(
        key = "preprocess.denoiseStrength",
        label = "Noise reduction",
        hint = "How hard the chosen filter smooths before edges are looked for. Too much erases the thinnest lines.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    TraceSlider(
        key = "preprocess.claheClip",
        label = "Local contrast",
        hint = "CLAHE clip limit. Higher pulls detail out of shadow and haze, and eventually amplifies grain with it.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        min = 1.0,
        max = 8.0,
        step = 0.1,
    ),
    TraceSlider(
        key = "preprocess.unsharpAmount",
        label = "Sharpen amount",
        hint = "How much of the difference between the photograph and a blurred copy of it is added back. 0 is off; past about 2 a pencil line grows a pale halo on both sides.",
        group = TRACE_GROUP_SHARPEN,
        // PRIMARY, following the portal: a photograph taken under one tube light in a workshop is soft
        // far more often than it is noisy.
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 5.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "preprocess.unsharpSigma",
        label = "Sharpen radius",
        hint = "The width of detail sharpening acts on, in pixels. Small values crisp the hairlines; large ones lift broad tonal edges and leave the hairlines alone.",
        group = TRACE_GROUP_SHARPEN,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.sensitivity",
        label = "Edge sensitivity",
        hint = "More ink as it rises. The one control that means the same thing in every edge engine.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 1.0,
        step = 0.01,
    ),
    TraceSlider(
        key = "edge.blurSigma",
        label = "Pre-blur",
        hint = "Gaussian applied before the detector. Raise it to ignore texture, lower it to keep hairlines.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 8.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.flow.sigmaM",
        label = "Stroke length",
        hint = "How far the flow-based engine follows a stroke before letting it end. Only affects the flow engine.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.05,
        max = 12.0,
        step = 0.05,
    ),
    TraceSlider(
        key = "edge.xdogPhi",
        label = "Edge hardness",
        hint = "XDoG sharpness: 3 is soft graphite, 300 is a woodcut with no soft edge anywhere.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        min = 0.1,
        max = 300.0,
        step = 0.1,
        // Geometric travel, and the argument is the HINT rather than the presets. "3 is soft graphite,
        // 300 is a woodcut": on a linear 328 dp track everything from 0.1 to 6 fits inside the first
        // 6.5 dp, so the whole graphite end of the range the sentence describes is a single fingertip
        // and 3 itself sits 3.2 dp from the left edge. On a log track 3 is at 139 dp and 6 at 168 dp.
        // The five values the styles write (6, 40, 60, 120, 200) and the engine's default of 20 stay
        // 15 dp or more apart under both mappings; it is the soft half that only one of them has.
        scale = TraceScale.LOG,
    ),
    TraceSlider(
        key = "cleanup.minBlobArea",
        label = "Minimum speck",
        hint = "Ink blobs smaller than this many pixels are dust and are dropped.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 1000.0,
        step = 1.0,
        scale = TraceScale.SQUARE,
        integral = true,
        handsetNote = "This is the workbench-grit control: a sheet photographed on a table traces the " +
            "table's texture as dust unless something drops it.",
    ),
    TraceSlider(
        key = "cleanup.closeRadius",
        label = "Close gaps",
        hint = "Morphological closing radius: joins strokes that already nearly touch. Too large fuses neighbouring lines.",
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
        hint = "How far apart two stroke ends may be and still be joined. Ignored when gap bridging is off.",
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
        hint = "Skeleton branches shorter than this are removed. 0 keeps every whisker thinning produced.",
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
        hint = "Douglas–Peucker tolerance in working pixels. Fewer nodes, straighter runs; 0 keeps every traced vertex.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.PRIMARY,
        min = 0.0,
        max = 8.0,
        step = 0.1,
    ),
    TraceSlider(
        key = "output.corner",
        label = "Keep corners",
        // Copied and NOT paraphrased, because the control is inverted from intuition and this sentence
        // is the only thing that says so: higher keeps MORE corners.
        hint = "Higher keeps MORE corners: a vertex survives as a corner when its neighbours subtend a sharper angle than this.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
        min = 0.0,
        max = 180.0,
        step = 1.0,
    ),
    TraceSlider(
        key = "output.smoothIterations",
        label = "Smoothing passes",
        hint = "Chaikin passes over the polyline before curve fitting. Each one rounds the geometry a little more.",
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
        // DEMOTED FROM THE PORTAL'S PRIMARY SEVEN, and this is the one tiering decision that differs
        // from the portal's own. It changes nothing about which lines the engine FOUND — it is applied
        // at document assembly, after every decision has been made. A researcher whose trace is wrong
        // will not fix it with stroke width; one whose trace is right sets it once. On a laptop dock
        // that costs nothing. On a 6" screen a primary row is a scarce thing.
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
        // All fourteen preset values are <= 40, i.e. the bottom fifth of a linear track.
        scale = TraceScale.SQUARE,
    ),
)

/** The nine toggles, in the web's order. */
val TRACE_TOGGLES: List<TraceToggle> = listOf(
    TraceToggle(
        key = "preprocess.invertInput",
        label = "Source is light-on-dark",
        hint = "Set for chalkboards, negatives and white ink on black, so “ink” stays the dark class everywhere downstream.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "preprocess.claheEnabled",
        label = "Equalise local contrast",
        hint = "Turn off for a source that is already evenly lit; CLAHE will otherwise amplify its grain.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "preprocess.perspectiveCorrect",
        label = "Rectify the page",
        hint = "Look for a document quadrilateral and flatten to it. For photographs of paper taken at an angle.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        // THE ONE CONSEQUENCE A RESEARCHER CANNOT SEE COMING, so it is said here rather than discovered
        // when the comparator refuses. Rectifying makes the document's frame the flattened PAGE rather
        // than the photograph, and the two plates can then no longer be laid over each other at all —
        // `TraceFailureKind.FRAME_MISMATCH`, which costs the comparison and never the drawing.
        handsetNote = "This is the engine's own automatic search for the sheet, and it changes the " +
            "drawing's frame — so when it finds a page there is no side-by-side comparison to show, " +
            "only the drawing. Leave it off for a sheet photographed square on.",
    ),
    TraceToggle(
        key = "cleanup.skeletonize",
        label = "Reduce ink to centrelines",
        hint = "Thins strokes to one pixel before tracing. Ignored in outline mode, where a skeleton would delete the boundary being traced.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "cleanup.bridgeGaps",
        label = "Bridge stroke ends",
        hint = "Join nearby stroke ends that point at each other. Off keeps a line that stopped, stopped.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "cleanup.removeBorderTouching",
        label = "Drop shapes touching the frame",
        hint = "Removes the drawing board, the scanner lid and the table edge, which a photograph almost always includes.",
        group = TRACE_GROUP_CLEANUP,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.modulateWidth",
        label = "Vary width with the stroke",
        hint = "Samples the distance transform per node so thick-and-thin brushwork survives. Emitted as a filled outline, because SVG has no variable-width stroke.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.fillClosed",
        label = "Fill closed shapes",
        hint = "Paints every closed region solid instead of outlining it. Outline mode with fill is the woodcut look.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.ADVANCED,
    ),
    TraceToggle(
        key = "output.background",
        label = "White background",
        hint = "Off exports a transparent background.",
        // RELOCATED. It is not a tracing parameter; it is a property of the file being written, and on
        // the web it sits among the sliders — which is why the comparator has to paint the trace on
        // opaque white unconditionally regardless of what it says. Here it belongs on the step that
        // writes the file, defaulting to White, because a transparent SVG dropped into a document
        // renders on whatever ground that document happens to have.
        group = TRACE_GROUP_EXPORT,
        tier = TraceTier.EXPORT,
        handsetNote = "The comparison above always shows the drawing on white so the wipe means " +
            "something. This decides the file only.",
        // `null` is a meaningful value here — it is how "transparent" is spelled — which is why this
        // cannot be expressed as a flag.
        read = { it.present("output.background") },
        patch = { white ->
            mapOf(
                "output.background" to
                    if (white) TraceValue.Num(TRACE_OPAQUE_WHITE) else TraceValue.Absent,
            )
        },
    ),
)

/** The four string choices. The fifth the web draws is cut — see [TRACE_CUT]. */
val TRACE_CHOICES: List<TraceChoice> = listOf(
    TraceChoice(
        key = "edge.engine",
        label = "Edge engine",
        hint = "Which detector runs. They fail differently, which is why all of them exist.",
        group = TRACE_GROUP_EDGES,
        tier = TraceTier.ADVANCED,
        // MODEL is deliberately absent, upstream's reason and still true: it needs a side-loaded edge
        // model, none ships, and choosing it falls back to the flow engine plus a note. "An option that
        // cannot work is worse than no option" — and adding a side-load affordance to a handset that
        // may be offline for a fortnight is worse still.
        options = listOf(
            TraceChoiceOption("FDOG", "Flow (long coherent strokes)"),
            TraceChoiceOption("XDOG", "XDoG (drawn look, tunable hardness)"),
            TraceChoiceOption("CANNY", "Canny (straight geometric edges)"),
            TraceChoiceOption("ADAPTIVE", "Adaptive threshold (already line art)"),
            TraceChoiceOption("LOG", "Laplacian (thinnest, most delicate)"),
        ),
        handsetNote = "Flow is the engine's default and by far the slowest on a phone — it was " +
            "measured at about six times the others at the same size. The panel says what a change " +
            "here will cost before you run it.",
    ),
    TraceChoice(
        key = "preprocess.denoise",
        label = "Noise filter",
        hint = "Bilateral keeps edges, median kills speckle, anisotropic flattens woven texture.",
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
        hint = "Separates the subject from its background. Never applied unless you choose it, and the pipeline reports how much of the frame it removed.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.ADVANCED,
        // SUBJECT is absent for the web's reason, inverted from MODEL's: it is not broken, it is what a
        // subject preset selects on the researcher's behalf, and offering it twice lets the two
        // disagree about which one decided.
        options = listOf(
            TraceChoiceOption("NONE", "None"),
            TraceChoiceOption("BORDER_FLOOD", "Flood from the border"),
            TraceChoiceOption("SALIENCY", "Keep the salient subject"),
        ),
        handsetNote = "The engine's default is None and stays there: a wrong matte deleting half of " +
            "somebody's artwork is the worst failure this can have.",
    ),
    TraceChoice(
        key = "output.vectorMode",
        label = "Vector mode",
        hint = "Centreline turns one stroke into one open path; outline turns one region into one closed path. The most consequential choice here.",
        group = TRACE_GROUP_OUTPUT,
        tier = TraceTier.PRIMARY,
        options = listOf(
            TraceChoiceOption("CENTERLINE", "Centreline (one stroke, one path)"),
            TraceChoiceOption("OUTLINE", "Outline (one region, one closed path)"),
        ),
    ),
)

/** The one numeric choice: the web's Trace resolution slider, reshaped. */
val TRACE_NUMBER_CHOICES: List<TraceNumberChoice> = listOf(
    TraceNumberChoice(
        key = "preprocess.workingLongEdge",
        label = "Trace resolution",
        hint = "The long edge the trace runs at. Higher resolves finer detail and costs time; the vector is scaled back to the source size either way.",
        group = TRACE_GROUP_SOURCE,
        tier = TraceTier.PRIMARY,
        options = TRACE_RESOLUTION,
    ),
)

/**
 * Every control this handset draws, in group order and then choices, sliders, toggles within a group
 * — the portal's own rendering order, so a researcher finds the same control in the same place on both
 * clients.
 */
val TRACE_CONTROLS: List<TraceControl> = TRACE_GROUPS.flatMap { group ->
    val inGroup: (TraceControl) -> Boolean = { it.group == group }
    TRACE_NUMBER_CHOICES.filter(inGroup) +
        TRACE_CHOICES.filter(inGroup) +
        TRACE_SLIDERS.filter(inGroup) +
        TRACE_TOGGLES.filter(inGroup)
}

/**
 * The controls this handset draws.
 *
 * **THE NUMBER LIVES HERE AND NOWHERE ELSE.** The web's own table records what happens otherwise: its
 * header said "eight toggles" and "twenty-nine controls", neither of which any command produced, while
 * the button printed thirty-two, and a reader reconciling the two went hunting for three controls that
 * had never been dropped. If you find yourself writing a total into a KDoc, write the command instead
 * — and `TraceParamsTest` runs it.
 *
 * **This is not the portal's thirty-two**, and the arithmetic is worth stating once so nobody
 * "corrects" it: thirty-two minus the one cut control ([TRACE_CUT]) is thirty-one, and the trace
 * resolution stopped being a slider without stopping being a control. Two omitted ENUM OPTIONS
 * (`edge.engine = MODEL`, `matte.mode = SUBJECT`) are not controls and cannot be subtracted from a
 * row count; the portal never drew either as a row.
 */
val TRACE_PARAM_COUNT: Int = TRACE_CONTROLS.size

/**
 * How many controls the disclosure holds, **as the TABLE has them**.
 *
 * NOT WHAT THE TOGGLE PRINTS, and the distinction earns its keep on a handset. This is the count of
 * rows this build knows about; [traceAdvancedRevealed] is the count this device's engine will actually
 * draw, and on a build whose vendored engine is a version apart the two differ by exactly the leaves
 * [traceMissingKeys] is reporting. The copy uses the second; the parity tests use this one, because a
 * test asking "did somebody quietly retier a control" must not be able to be answered by a runtime that
 * simply failed to send it.
 */
val TRACE_ADVANCED_COUNT: Int = TRACE_CONTROLS.count { it.tier == TraceTier.ADVANCED }

/**
 * The controls the panel leads with, as a SET rather than an order.
 *
 * The portal leads with seven; this leads with six of them plus the two preset pickers, which are not
 * in this table because they are not leaves — a style writes the whole tree and a subject adjusts it.
 * `output.strokeWidth` is demoted (its reason is at its own row), and `preprocess.workingLongEdge`
 * appears here as the three-option control rather than as a slider.
 *
 * The principle carried over is about the engine and not about the other client's layout: **each of
 * these changes the KIND of drawing that comes out, while the rest tune a drawing the researcher
 * already has.**
 */
val TRACE_PRIMARY_KEYS: List<String> =
    TRACE_CONTROLS.filter { it.tier == TraceTier.PRIMARY }.map { it.key }

/* ────────────────────────────────────────────────────────────────────────────
 * The one disclosure — what is behind it, and what the press is called
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The words on the press, and the one string in this file that is NOT this client's own choice.
 *
 * ── WHY A CONSTANT, AND WHY THIS PARTICULAR WORDING ───────────────────────────────────────────
 *
 * [TraceControl.handsetNote] exists precisely so this client can say things the portal has no reason
 * to. **This phrase is the exception, and it is the owner's own**: advanced settings sit "inside an
 * internal accordion with an action such as 'Show more options'". Both clients had to be given the
 * SAME name for the same press, because a researcher who has learned where the rest of the settings
 * live on the laptop must not have to find them again under a different name in a village.
 *
 * It replaced this client's earlier "Show everything (N more)", which is the better English and still
 * lost, for that reason alone.
 */
const val TRACE_DISCLOSURE_ACTION: String = "Show more options"

/** "1 setting" / "24 settings", so no copy below has to guess a plural off a derived number. */
private fun traceSettings(count: Int): String = if (count == 1) "1 setting" else "$count settings"

/**
 * The advanced rows the disclosure will ACTUALLY draw, grouped, in the table's own group order.
 *
 * ── ONE SECTION, THE SAME GROUP HEADINGS, AND NOTHING ALLOWED TO FALL BETWEEN THEM ────────────
 *
 * The panel draws [TraceTier.PRIMARY] above the disclosure and this list below it, and the two are
 * derived from the SAME `tier` field by opposite tests — so they are exhaustive and disjoint by
 * construction and a control added to [TRACE_CONTROLS] lands in one of them without anybody
 * remembering to put it there. That is the property `TraceParamsTest` pins, because the way a later
 * tidy-up loses a control is not by deleting it: it is by maintaining two hand-written lists that stop
 * adding up.
 *
 * The headings are the table's own [TRACE_GROUPS], unchanged — a researcher looks for a control by the
 * pipeline stage it belongs to, and splitting the taxonomy by importance instead would mean knowing
 * whether somebody had called a cleanup control essential before you could find it.
 *
 * ── AND WHY A CONTROL THE ENGINE DID NOT SEND IS NOT COUNTED HERE ─────────────────────────────
 *
 * The panel skips a row whose leaf the runtime's engine copy has no value for (see [TraceSlider.read]).
 * If this returned it anyway, the toggle would promise a row the press does not produce — the exact
 * failure the portal's own button had, where it "read 'Show all 32 controls' while 7 of the 32 were
 * already in front of the designer". The membership test is `key in values.keys`, character for
 * character what [traceMissingKeys] filters on, so the number on the toggle and the number in the
 * version-skew note cannot disagree.
 *
 * A group with nothing left in it is dropped rather than drawn as a heading over nothing.
 */
fun traceAdvancedGroups(values: TraceValues): List<Pair<String, List<TraceControl>>> =
    TRACE_GROUPS.mapNotNull { group ->
        val rows = TRACE_CONTROLS.filter {
            it.group == group && it.tier == TraceTier.ADVANCED && it.key in values.keys
        }
        if (rows.isEmpty()) null else group to rows
    }

/**
 * How many rows this press reveals, on this device, right now.
 *
 * **THE NUMBER IN THE COPY IS THIS ONE AND IS NEVER TYPED.** [TRACE_PARAM_COUNT]'s header records what
 * typing it costs.
 *
 * NOT [TRACE_ADVANCED_COUNT], and the difference is the point: that constant is what the TABLE holds,
 * which is the right number for a parity test and the wrong one for a button, because on a handset
 * whose engine is a version apart some of those rows will not be drawn.
 */
fun traceAdvancedRevealed(values: TraceValues): Int =
    traceAdvancedGroups(values).sumOf { it.second.size }

/**
 * What the toggle says, in both states.
 *
 * The closed arm is the web's own template — [TRACE_DISCLOSURE_ACTION], the count, and the changed
 * count when there is one.
 *
 * [changedHidden] is the count of controls BEHIND THIS PRESS that no longer hold their preset's value
 * — see [traceChangedBehindDisclosure] for why that is a narrower question than "what is not on
 * screen", and why the two are allowed to be different numbers.
 */
fun traceDisclosureLabel(open: Boolean, revealed: Int, changedHidden: Int): String = if (open) {
    "Hide the other ${traceSettings(revealed)}"
} else {
    TRACE_DISCLOSURE_ACTION + " · ${traceSettings(revealed)}" +
        (if (changedHidden > 0) " · $changedHidden changed" else "")
}

/**
 * What TalkBack reads after "double tap to".
 *
 * A VERB PHRASE, because that is the grammar `onClickLabel` is spoken in. The visible label is a noun
 * phrase with a count in it and would be read as "double tap to Show more options · 24 settings",
 * which is a heading and not an action. `stateDescription` says what the section IS, `onClickLabel`
 * says what the press will DO, and the chevron beside the words carries neither to somebody who cannot
 * see it.
 */
fun traceDisclosureClickLabel(open: Boolean, revealed: Int): String = if (open) {
    "hide the other ${traceSettings(revealed)}"
} else {
    "show the other ${traceSettings(revealed)}"
}

/**
 * "Expanded" / "Collapsed" — the state a screen reader is owed and a chevron does not give it.
 *
 * The same two words every other disclosure in this application uses, deliberately: a reader who has
 * met one disclosure here should not have to learn that another one calls the same state something
 * else. NOT `selectable`, which would announce "selected" — the wrong noun for a section that opens.
 */
fun traceDisclosureState(open: Boolean): String = if (open) "Expanded" else "Collapsed"

/**
 * The line under a CLOSED toggle saying what is inside it.
 *
 * **NOT THE PORTAL'S SENTENCE, AND THE DIFFERENCE IS A FACT RATHER THAN A PREFERENCE.** The portal's
 * reads "Inside: the part of the photograph to trace, the N settings that are not above, and the
 * formats you can download a copy in", because its one disclosure swallowed its frame chooser and its
 * download buttons as well. On this client it holds settings and nothing else: the frame panel is
 * already one collapsed row with a summary that is true whether it is open or shut, and the export card
 * is a slot on the step that writes the file. Claiming them here would send a researcher looking for
 * the frame tool inside a section it is not in.
 */
fun traceDisclosureBlurb(revealed: Int): String =
    "Inside: the ${traceSettings(revealed)} that are not above, under the same headings as the " +
        "portal. Nothing in there is required — the trace runs on what is on screen now."

/**
 * The sentence naming the folded-away controls a preset has moved, or null when it has moved none.
 *
 * **PROGRESSIVE DISCLOSURE IS ONLY HONEST IF WHAT IT HIDES CAN STILL ANNOUNCE ITSELF.** A setting that
 * is quietly affecting the drawing while out of sight is the defect class this panel exists to not
 * ship; the portal prints this sentence character for character.
 *
 * "Not on screen" rather than "hidden", and that word was chosen on this client: "hidden" points a
 * researcher at the one disclosure, and one of the tiers this measures lives on the export step
 * entirely. What is true of all of them is that the control is not in front of the researcher.
 *
 * Null rather than an empty string so a caller cannot render an empty notice box — the same contract
 * [traceOverwriteNotice] holds itself to.
 */
fun traceHiddenChangedSentence(labels: List<String>): String? = when {
    labels.isEmpty() -> null
    labels.size == 1 -> "One setting that is not on screen has moved: ${labels.first()}."
    else ->
        "${labels.size} settings that are not on screen have moved: ${labels.joinToString(", ")}."
}

/**
 * The changed controls THIS PRESS would reveal — the count that goes on the toggle itself.
 *
 * ── WHY THIS IS A NARROWER QUESTION THAN [traceChangedHiddenLabels] ───────────────────────────
 *
 * That function answers "what has moved that the researcher cannot see", which on this client includes
 * [TraceTier.EXPORT] whenever the export card is not composed — before the first trace finishes, there
 * is no card. A count of THAT on the toggle would be the toggle claiming to reveal a control that lives
 * on another step, and a researcher who pressed it and could not find the fourth name would be right to
 * distrust everything else the panel says.
 *
 * So the toggle counts what the toggle produces, and the sentence under it NAMES everything that is out
 * of sight wherever it lives. The two are equal in the ordinary case and are each true when they are
 * not.
 */
fun traceChangedBehindDisclosure(before: TraceValues, after: TraceValues): List<String> =
    traceChangedHiddenLabels(
        before = before,
        after = after,
        visible = setOf(TraceTier.PRIMARY, TraceTier.EXPORT),
    )

/* ────────────────────────────────────────────────────────────────────────────
 * The cut list
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Controls the portal draws and this handset does not, with the argument for each.
 *
 * A map rather than a list because the reason is the point: somebody will eventually notice a missing
 * slider, and what they need is not the fact that it was left out but why — in one place, next to the
 * table it was left out of. The panel DRAWS this list at the foot of the disclosure, so somebody
 * looking for the thinning control finds the answer where they looked for the control.
 *
 * **`cleanup.thinning` (Zhang–Suen vs Guo–Hall) is the only one, and it is cut on discipline rather
 * than on screen space.** The engine's own documentation says Guo–Hall "keeps diagonals better and
 * grows fewer spurs" — it documents one kernel as better and then defaults to the other. A control
 * whose correct setting is written down in the library and whose wrong setting is the default is a bug
 * report, not a knob. Worse, the difference is invisible at a 720 px preview and visible in the
 * exported SVG, so a researcher who flips it changes an archived drawing on the strength of something
 * they cannot see. And it is precisely the divergence a file-hash manifest cannot catch: default it one
 * way here and the other on the web, and one sheet of paper yields two different line arts depending on
 * which client traced it.
 *
 * Also never exposed, and not listed here because the portal does not draw them either:
 * `preprocess.autoOrient` (declared, defaulted, sanitised and read by nothing in the engine);
 * `output.fitError` (a non-positive value asks the fitter to subdivide to its depth cap on every curve,
 * which is minutes per trace on a phone); `edge.xdogEpsilon` (a measured incident: at 0.5 a shaded
 * terracotta pot came back with 18–66% of its body inked as tone, 2770 shapes and silhouette recall
 * 29%, against 193 shapes and 93% at 0.08); the six `auto.*` leaves; and the vectoriser fields the
 * pipeline hard-codes.
 */
val TRACE_CUT: Map<String, String> = mapOf(
    "cleanup.thinning" to
        "The engine's own documentation says Guo–Hall is the better kernel and the engine defaults to " +
        "Zhang–Suen. The difference cannot be seen at preview size and can be seen in the exported " +
        "file, and if the two clients defaulted differently one sheet of paper would produce two " +
        "different drawings. It stays at the engine's default on both.",
)

/* ────────────────────────────────────────────────────────────────────────────
 * A control that cannot do anything right now
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One sentence saying this control has no effect under the current settings, or null.
 *
 * ── WHY THIS EXISTS ON THE HANDSET AND NOT ON THE PORTAL ──────────────────────────────────────
 *
 * The portal draws thirty-one live rows on a screen a researcher can take in at once, and a slider that
 * does nothing there is a small waste. Here the panel is a column on a 6" screen and every row costs a
 * scroll, so a researcher can easily spend a minute moving a control the pipeline is not reading — and
 * then conclude the trace is broken.
 *
 * ── EVERY REASON WAS READ OFF THE PIPELINE, NOT INFERRED FROM A LABEL ─────────────────────────
 *
 * The one that matters most is the last. `DenoiseMode.MEDIAN`'s branch reads `medianRadius` and never
 * `denoiseStrength`, and `MEDIAN` is what the **`sketch` subject** selects — which is the subject this
 * panel opens on by default. So on the ordinary handset configuration the "Noise reduction" slider is
 * inert, and nothing on the portal says so.
 *
 * The control is still DRAWN and still WRITABLE. Greying it out would stop a researcher setting a value
 * for the configuration they are about to switch to, and the honest thing is a sentence rather than a
 * disabled row.
 */
fun traceInactiveReason(control: TraceControl, values: TraceValues): String? {
    val engine = values.choice("edge.engine")
    val outline = values.choice("output.vectorMode") == "OUTLINE"
    return when (control.key) {
        // blurSigma is passed only in the CANNY arm.
        "edge.blurSigma" ->
            if (engine != null && engine != "CANNY") "Only the Canny engine reads this." else null
        // The flow settings are read only in the default (FDOG) arm.
        "edge.flow.sigmaM" ->
            if (engine != null && engine != "FDOG") "Only the Flow engine reads this." else null
        // XDoG and FDOG share xdogPhi; nothing else reads it.
        "edge.xdogPhi" ->
            if (engine != null && engine != "XDOG" && engine != "FDOG") {
                "Only the XDoG and Flow engines read this."
            } else {
                null
            }
        // `skeletonize && !outlineMode`.
        "cleanup.skeletonize" ->
            if (outline) "Outline mode traces the edge of a region, so nothing is thinned." else null
        /*
          Inside the skeletonize branch only.

          TWO SEPARATE CONDITIONS AND NEITHER IS ASSUMED, because `skeletonize` above folds "the flag
          is off" together with "the flag was never sent", and those mean opposite things. A tree with
          no `cleanup.skeletonize` leaf is a version skew ([traceMissingKeys] reports it); reading it
          as "thinning is switched off" would put a confident sentence under a control on the strength
          of a leaf that is not there.
        */
        "cleanup.pruneSpurs" -> when {
            values.flag("cleanup.skeletonize") == false ->
                "“Reduce ink to centrelines” is off, so there is no skeleton to prune."
            outline -> "Outline mode traces the edge of a region, so nothing is thinned."
            else -> null
        }
        // Both bridging branches require bridgeGaps.
        "cleanup.maxGap" ->
            if (values.flag("cleanup.bridgeGaps") == false) "“Bridge stroke ends” is off." else null
        // The CLAHE call is inside `if (claheEnabled)`.
        "preprocess.claheClip" ->
            if (values.flag("preprocess.claheEnabled") == false) {
                "“Equalise local contrast” is off."
            } else {
                null
            }
        // The unsharp mask runs only when the amount is above zero. A MISSING amount is not a zero
        // one: `?: 0.0` here would claim this slider is inert on a tree that has simply never
        // mentioned sharpening.
        "preprocess.unsharpSigma" ->
            values.number("preprocess.unsharpAmount")
                ?.takeIf { it <= 0.0 }
                ?.let { "“Sharpen amount” is 0." }
        // See this function's header. The MEDIAN case is the trap.
        "preprocess.denoiseStrength" -> when (values.choice("preprocess.denoise")) {
            "NONE" -> "The noise filter is set to None."
            "MEDIAN" ->
                "The median filter works from a fixed radius the panel does not expose, not from this."
            else -> null
        }
        else -> null
    }
}

/**
 * The keys this build draws a control for that the runtime's engine did not send a value for.
 *
 * Empty on every healthy build. Non-empty means a vendored update renamed or removed a leaf, and the
 * panel prints one sentence saying how many rows it is not drawing — because a panel that quietly
 * drops a control and a panel with nothing to drop must not look the same, which is the pipeline's own
 * rule about its notes, applied to the surface that renders them.
 */
fun traceMissingKeys(values: TraceValues): List<String> =
    TRACE_CONTROLS.map { it.key }.filter { it !in values.keys }

/* ────────────────────────────────────────────────────────────────────────────
 * Saying what changed
 * ──────────────────────────────────────────────────────────────────────────── */

/** How a control's value compares, whatever kind it is. Never rendered; only compared. */
private fun TraceControl.compareValue(values: TraceValues): Any? = when (this) {
    is TraceSlider -> read(values)
    is TraceToggle -> read(values)
    is TraceChoice -> read(values)
    is TraceNumberChoice -> read(values)
}

/** The labels of every control whose value differs. Mirrors the web's `changedLabels`. */
fun traceChangedLabels(before: TraceValues, after: TraceValues): List<String> =
    TRACE_CONTROLS.filter { it.compareValue(before) != it.compareValue(after) }.map { it.label }

/**
 * The labels of every changed control that is NOT currently on screen.
 *
 * **Progressive disclosure is only honest if what it hides can still announce itself.** A style that
 * moved four advanced values must say so while those rows are folded away, or the panel is lying about
 * what the trace is doing.
 *
 * [visible] is the set of tiers the caller is drawing right now, which is a slightly stronger version
 * of the portal's question. The portal asks "is it essential"; this client also has a tier that lives
 * on a different step entirely, and a preset that changed the export background while the export step
 * is closed is exactly as hidden as one that changed an advanced slider.
 */
fun traceChangedHiddenLabels(
    before: TraceValues,
    after: TraceValues,
    visible: Set<TraceTier>,
): List<String> = TRACE_CONTROLS
    .filter { it.tier !in visible && it.compareValue(before) != it.compareValue(after) }
    .map { it.label }

/**
 * One sentence naming what a preset just overwrote, or null when it overwrote nothing.
 *
 * Null rather than an empty string so a caller cannot render an empty notice box. Wording verbatim from
 * the web's `overwriteNotice`, whose reason is that **a preset that silently discards five minutes of
 * tuning is the one failure a preset list can have.**
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Seeding the Subject picker from whatever the host knows
 * ──────────────────────────────────────────────────────────────────────────── */

/** The subject the engine's own table calls a scan of pencil or ink. This panel's default. */
const val TRACE_DEFAULT_SUBJECT_ID: String = "sketch"

/**
 * The subject preset a product category suggests — **a pre-selection, never a silent application.**
 *
 * ── WHY THE MAP IS HERE WHEN NOTHING IN THIS APP YET FILLS IT ─────────────────────────────────
 *
 * A subject preset is the engine's own answer to "what material is this" — the one fact the person
 * standing in the workshop knows and the engine cannot. Where a host knows that already, asking again
 * is a picker on a phone for a question that has an answer.
 *
 * **This product has no product-category vocabulary yet**, so nothing in this repository passes a key
 * that is in this map, and [TracePanel] leaves `subjectHint` null by default. The map is carried
 * ACROSS rather than invented here: these seven keys are the design-workshop registry's own
 * `PRODUCT_CATEGORY` values, so the day this app grows the same column the mapping is already the one
 * the other client uses, rather than a second opinion written from memory beside the feature that
 * needed it. `TraceParamsTest` pins every value in it against `Subjects.ALL`, so a key here can never
 * name a subject the engine does not carry.
 *
 * ── AND WHY IT WOULD BE SHOWN RATHER THAN APPLIED ─────────────────────────────────────────────
 *
 * The upstream's own contract for work done on somebody's behalf is "a named suggestion with a one-tap
 * override". The panel therefore opens with this subject SELECTED and VISIBLE in its own control, with
 * a sentence saying where it came from, and one button applies it. It is not applied invisibly and it
 * is not applied twice.
 *
 * ── WHAT IS DELIBERATELY NOT MAPPED ───────────────────────────────────────────────────────────
 *
 * `APPAREL` and `HOME_FURNISHING` are left on the default. Both are usually cloth and both could
 * reasonably be `textile`, but a garment or a cushion can be leather, cane or metal, and the cost of
 * guessing wrong is a preset that has quietly changed the denoise filter under somebody who never asked.
 * The seven that ARE mapped are woven, worked or cast by definition.
 */
val TRACE_SUBJECT_FOR_CATEGORY: Map<String, String> = mapOf(
    "JEWELLERY" to "jewellery",
    "SAREE" to "textile",
    "DUPATTA_STOLE" to "textile",
    "YARDAGE" to "textile",
    "FLOOR_COVERING" to "textile",
    "TABLE_LINEN" to "textile",
    // The design workshop's registry maps DECORATIVE to "carving", which is the PORTAL's subject id.
    // This engine splits that row in two and has no `carving` at all — see
    // `TRACE_SUBJECTS_ONLY_ON_THE_PORTAL`, and `traceNoSuchSubjectSentence`, which answers the
    // portal's id with the remedy rather than with "no such thing". Wood is the more common of the
    // two in this product's records, and it is a PRE-SELECTION a researcher can change in one tap.
    "DECORATIVE" to "wood-carving",
)

/** The subject to open with for this hint. Never null: the default is a real answer. */
fun traceSubjectFor(category: String?): String =
    TRACE_SUBJECT_FOR_CATEGORY[category?.trim().orEmpty()] ?: TRACE_DEFAULT_SUBJECT_ID
