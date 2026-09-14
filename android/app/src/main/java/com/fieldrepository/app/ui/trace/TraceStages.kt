package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

/**
 * **THE TWELVE STAGES THE WEB'S ENGINE REPORTS, AND THE SIX KNOB NAMES THE ENGINE PROTECTS.**
 *
 * Two tables, both of which are CONTRACTS WITH A FILE NOBODY HERE MAY EDIT, which is why they are in
 * a file of their own with a test that reads the vendored Kotlin and fails when either drifts.
 * Everything else about the trace surface is a design decision; these two are transcriptions, and a
 * transcription that nothing checks is a transcription that is already wrong.
 *
 * ── THE LABELS BELOW ARE FOR WEIGHTING AND FOR THE TEST. THEY ARE NOT WHAT IS DRAWN ───────────
 *
 * The progress row renders [TraceProgress.label] — the string the ENGINE sent with the event — and
 * never [TraceStage.label] from this table. Re-typing engine wording in a client is how "the two
 * clients would eventually describe one operation differently". This table exists so the bar can be
 * weighted (below) and so a vendored update that inserts a thirteenth stage fails a unit test instead
 * of silently mis-labelling a progress bar.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The stages
 * ──────────────────────────────────────────────────────────────────────────── */

/** One pipeline stage. [id] is stable — the UI keys its progress rows on them. */
@Immutable
data class TraceStage(val id: String, val label: String)

/**
 * The upstream TypeScript's `STAGES`, in execution order, transcribed exactly.
 *
 * Two of them run their bodies only under a condition and **every one of them still fires** — `matte`
 * posts progress and records a timing whether or not a matte mode is set, and `crop` does the same
 * unless auto-crop is applying. So there is no way to tell a skipped stage from a fast one at this
 * boundary, and **the surface must not invent a "skipped" state**. It shows the label the engine sent.
 *
 * **THE ENGINE THIS APP ACTUALLY RUNS REPORTS NINETEEN, NOT THESE TWELVE.** That is not an error in
 * this table — it is the real divergence between the two vendorings, and [TRACE_ENGINE_STAGES] in
 * `TraceRuntime.kt` is what the panel passes to [traceProgressSentence]. This list stays because it
 * is the WEB's shape, it is what [TraceProgressWeights.Unweighted] is derived from for a device that
 * has never finished a trace, and `TraceStagesTest` holds it against the Kotlin engine's own list so
 * the divergence is a measured fact rather than a rumour.
 */
val TRACE_STAGES: List<TraceStage> = listOf(
    TraceStage("prepare", "Preparing image"),
    TraceStage("matte", "Separating background"),
    TraceStage("crop", "Cropping to the subject"),
    TraceStage("gray", "Converting to grey"),
    TraceStage("denoise", "Reducing noise"),
    TraceStage("contrast", "Enhancing contrast"),
    TraceStage("edge", "Detecting edges"),
    TraceStage("cleanup", "Cleaning up"),
    TraceStage("skeleton", "Thinning strokes"),
    TraceStage("distance", "Measuring stroke width"),
    TraceStage("vectorize", "Tracing vectors"),
    TraceStage("document", "Assembling document"),
)

/** Twelve, from the table rather than from anybody's memory. */
val TRACE_STAGE_COUNT: Int = TRACE_STAGES.size

/** Position in execution order, or -1 for an id this build has never heard of. */
fun traceStageIndex(stageId: String): Int = TRACE_STAGES.indexOfFirst { it.id == stageId }

/**
 * The two stages that dominate the wall clock, named so a progress UI can be built knowing it.
 *
 * The feasibility measurements put **13,037 of 16,655 ms** in `edge` alone at the product's input
 * size with the shipped flow engine, and `vectorize` is the other long one. `gray` and `distance` are
 * near-instant, and `distance` does not run at all unless width modulation is on. A bar driven off
 * `index / 12` therefore RUSHES to a half and then appears to hang for most of the trace, which reads
 * as a crash. Hence [TraceProgressWeights].
 */
val TRACE_SLOW_STAGE_IDS: List<String> = listOf("edge", "vectorize")

/* ────────────────────────────────────────────────────────────────────────────
 * How far along a trace really is
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Turns a stage boundary into a bar position, using what THIS DEVICE measured last time.
 *
 * ── WHY NOT JUST USE THE FRACTION THE ENGINE SENDS ────────────────────────────────────────────
 *
 * Because it is a stage COUNT and not a time estimate. The engine posts `index / n` at the START of
 * each stage, so the events are evenly spaced whatever the stages cost, and **the engine's fraction
 * never reaches 1.0.** On a laptop the distortion is a shrug; on a phone where `edge` is four fifths
 * of a twenty-second wait, a bar that sits at 0.5 for sixteen seconds is a bar a researcher stops
 * believing.
 *
 * ── AND WHY THE WEIGHTS ARE MEASURED RATHER THAN GUESSED ──────────────────────────────────────
 *
 * A finished result carries `{id, label, millis}` per stage, so after the first full trace on a device
 * the true shape of the curve is already in hand — for THIS phone, THIS photograph and THESE
 * parameters, which is better than any table could be. It costs no new engine surface and no new
 * measurement. Before that first trace, [Unweighted] is the engine's own `index / n`, honestly
 * labelled as the thing it is.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT DO ────────────────────────────────────────────────────────
 *
 * It does not interpolate WITHIN a stage. The engine reports boundaries and nothing else, so a bar
 * that crept forward during `edge` would be an animation of a number nobody measured — and the one
 * stage where a researcher most wants to know is the one where the creeping would be pure invention.
 * The stage LABEL is the primary signal and the bar is secondary, which is the same order of
 * importance the timings above imply.
 */
@Immutable
class TraceProgressWeights private constructor(
    private val startFractions: Map<String, Float>,
    /** True when the weights came from a real run on this device rather than from the stage count. */
    val measured: Boolean,
) {

    /**
     * The bar position at the start of [stageId].
     *
     * Falls back to [fallback] — the fraction the engine itself sent — for an id this build does not
     * know, which is what the nineteen-stage engine looks like from a twelve-row table on the first
     * run of a session. That fallback is not theoretical here: it is the ordinary path until one
     * trace has finished, after which [from] rebuilds the weights off the engine's OWN ids.
     */
    fun fractionAt(stageId: String, fallback: Float): Float =
        startFractions[stageId] ?: fallback

    companion object {
        /** The engine's own `index / 12`, for a device that has not yet completed a full trace. */
        val Unweighted: TraceProgressWeights = TraceProgressWeights(
            startFractions = TRACE_STAGES.withIndex().associate { (index, stage) ->
                stage.id to index.toFloat() / TRACE_STAGE_COUNT
            },
            measured = false,
        )

        /**
         * Weights from a completed run's own timings.
         *
         * Returns [Unweighted] rather than a division by zero when the timings are empty or sum to
         * nothing — which is a real case and not a defensive one: a preview reports no timings at all,
         * and a trace of a blank sheet can finish fast enough for every stage to round to zero.
         */
        fun from(timings: List<TraceStageTiming>): TraceProgressWeights {
            val total = timings.sumOf { it.millis }
            if (timings.isEmpty() || total <= 0L) return Unweighted
            var elapsed = 0L
            val out = LinkedHashMap<String, Float>(timings.size)
            for (timing in timings) {
                out[timing.id] = elapsed.toFloat() / total.toFloat()
                elapsed += timing.millis
            }
            return TraceProgressWeights(startFractions = out, measured = true)
        }
    }
}

/**
 * A sentence naming the stage a trace is in, for a screen reader and for a live region.
 *
 * "Stage 7 of 19" and not a percentage, because the percentage is the thing this file has just spent
 * a page explaining is not a time estimate. The label is the engine's. A stage the given list does
 * not know is described by its label alone rather than by a wrong number.
 *
 * ── [stages] IS A PARAMETER BECAUSE THE COUNT IS THE RUNTIME'S TO SAY, NOT THIS TABLE'S ───────
 *
 * The Kotlin engine this handset runs reports **nineteen** stages, because it separates steps the
 * TypeScript fuses — `prepare` is `orient` + `perspective` + `downscale`, `cleanup` is `binarise` +
 * `morphology` + `blobs` + `bridge` — and three ids are spelled differently. Seven ids appear in both
 * lists and five of those seven sit at a different position, so reading the id out of [TRACE_STAGES]
 * would announce "Stage 2 of 12" while the engine was on stage 7 of 19 — a wrong number spoken
 * confidently, which is worse than the label alone. [TRACE_ENGINE_STAGES] is what the panel passes and
 * it is built from the engine's own `Stages.ALL` at run time, so a vendored update that adds a stage
 * moves this sentence with it and needs no edit here.
 *
 * The default stays [TRACE_STAGES] so the table above is still exercised by its own test, and so a
 * caller with no runtime in hand gets the documented twelve rather than nothing.
 */
fun traceProgressSentence(
    progress: TraceProgress,
    stages: List<TraceStage> = TRACE_STAGES,
): String {
    val index = stages.indexOfFirst { it.id == progress.stageId }
    return if (index < 0) {
        progress.label
    } else {
        "${progress.label}. Stage ${index + 1} of ${stages.size}."
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The knobs the engine protects
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The engine's `Knobs`, transcribed exactly, keyed by the constant name it uses.
 *
 * ── WHY A SURFACE THAT DOES NOT USE AUTO-DETECTION STILL CARRIES THIS ─────────────────────────
 *
 * `Params.kt` calls these strings **a contract with the UI: an editor's knob table uses exactly these
 * labels**. They are how a client tells the engine which values a person set by hand, so that
 * auto-detection can put them back after a preset overwrote them (`Knobs.restore`).
 *
 * **No shipping client has ever exercised that contract.** Neither the portal nor this panel writes
 * any of the `auto.*` leaves, so the protection has never been asked for — and `AutoParams.sanitized`
 * trims the list without validating it against these six names, so a typo would be silently
 * unprotected: auto-detection would overwrite the hand-set value and nothing anywhere would say so.
 *
 * So this table exists now, pinned by a test now, for the day somebody offers `AutoMode.APPLY` on this
 * client. Writing it later, from memory, next to the feature that needs it, is exactly how the typo
 * gets in. `TraceStagesTest` asserts it against `Knobs.ALL` itself rather than a copy of it.
 */
val TRACE_KNOBS: Map<String, String> = linkedMapOf(
    "EDGE_SENSITIVITY" to "edge sensitivity",
    "STROKE_WIDTH" to "stroke width",
    "SIMPLIFY" to "simplify",
    "CORNER" to "corner threshold",
    "MIN_PATH_LENGTH" to "minimum path length",
    "MIN_BLOB_AREA" to "minimum blob area",
)

/** Every protected knob, in the order the engine's own `Knobs.ALL` lists them. */
val TRACE_KNOB_NAMES: List<String> = TRACE_KNOBS.values.toList()

/**
 * The control each protected knob names, so a future `AutoMode.APPLY` can build the hand-tuned set
 * from the rows a researcher actually moved rather than from a hand-written list.
 *
 * The mapping is the reason the wire strings are not the labels: the engine says "corner threshold"
 * and the panel says "Keep corners", and both are correct in their own vocabulary. A client that sent
 * its own label would be sending a string `Knobs.restore` has never heard of.
 */
val TRACE_KNOB_KEY_FOR_NAME: Map<String, String> = mapOf(
    "edge sensitivity" to "edge.sensitivity",
    "stroke width" to "output.strokeWidth",
    "simplify" to "output.simplify",
    "corner threshold" to "output.corner",
    "minimum path length" to "output.minPathLength",
    "minimum blob area" to "cleanup.minBlobArea",
)
