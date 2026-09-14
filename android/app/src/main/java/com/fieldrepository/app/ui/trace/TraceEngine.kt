package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * **THE SEAM BETWEEN THE TRACE SURFACE AND WHATEVER RUNS THE VENDORED ENGINE.**
 *
 * ── WHAT THIS FILE IS, IN ONE PARAGRAPH ───────────────────────────────────────────────────────
 *
 * A researcher photographs a paper sketch, a rubbing, a pattern draft or a hand-drawn tool diagram
 * and turns it into vector line art that can be filed on the record beside the photograph. The web
 * client does it with `frontend/lib/trace/` and this handset does it with `android/core-imaging`,
 * `core-vector`, `core-pipeline` and `core-export` — ONE upstream engine, vendored twice, hashed file
 * by file in `android/UPSTREAM-MANIFEST-KOTLIN.txt`. This file is the boundary the handset's UI is
 * written against, and NOTHING on the other side of it is decided here: the surface takes a
 * [TraceEngineRuntime], and a runtime is whatever runs that engine.
 *
 * ── WHY THE UI IS WRITTEN AGAINST A PORT WHEN THERE IS EXACTLY ONE IMPLEMENTATION ─────────────
 *
 * Because the split has already been paid for once and has already been collected on. In the sibling
 * repository this surface was written against this same interface while the runtime question was
 * open: one candidate ran the vendored TypeScript inside an `androidx.javascriptengine` isolate, the
 * other compiled the vendored Kotlin into the APK. The owner chose the Kotlin, the JavaScript route
 * was deleted — the bundle, the isolate, the dependency and the bridge — and **the ~8,000 lines of
 * panel above this interface did not change a line for it.**
 *
 * That is not a hedge, it is the same split the vendored engine itself argues for. `Pipeline.run`
 * refuses to cancel through any platform's own cancellation type because "the engine must run
 * identically under vitest, in a worker and on a JVM-shaped API". The upstream author already drew
 * this line. This file draws it in Kotlin.
 *
 * `TraceRuntime.kt` is the implementation, `TraceHost.kt` mounts it, and there is no second one and
 * no fallback. A build that wanted one would add it here and change nothing above.
 *
 * ── WHAT MUST NEVER HAPPEN ON THIS SIDE OF THE SEAM ───────────────────────────────────────────
 *
 * **No Kotlin clamp table, ever.** `:core-pipeline`'s `Params.kt` declares 74 leaves with 74
 * individually-argued clamps, several of which encode a MEASURED INCIDENT rather than a taste —
 * `xdogEpsilon = 0.08` carries a note about a shaded terracotta pot that came back with 18–66% of its
 * body inked as tone at 0.5. `TraceParams.sanitized()` is the sole authority on what is legal, and it
 * is documented idempotent precisely so a UI may run it on every slider tick without ever disagreeing
 * with the pipeline. So [TraceEngineRuntime.withOverrides] is the engine's own merge-and-sanitise and
 * this side never merges, never clamps and never rounds a bound.
 *
 * The ranges in `TraceParams.kt` are therefore **display ranges, not legality**: they are the web
 * panel's own narrowed maxima, carried verbatim so the two clients agree about what a researcher may
 * ask for. Widening one here would let a handset ask for something the portal will not.
 *
 * ── AND WHY THE VALUES CROSS AS A FLAT MAP ────────────────────────────────────────────────────
 *
 * The tree has seven sections and one nested object (`edge.flow`). Modelling it as Kotlin data
 * classes would be a second declaration of 74 leaves, in a second language, that nothing can check
 * against the first. A flat `Map<String, TraceValue>` keyed by the SAME dot paths the web table uses
 * (`edge.flow.sigmaM`) has no opinion about the tree's shape, so a vendored update that adds a leaf
 * adds a key and breaks nothing. [TraceValues.wire] carries the tree itself, untouched, so whatever
 * goes back to the engine is what the engine handed over.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Values
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One leaf of the engine's parameter tree, as it crosses this boundary.
 *
 * [Absent] is not "missing". It is how `output.background: null` is spelled — the ONLY spelling of a
 * transparent export — and it is a sealed case rather than a nullable Double because a nullable would
 * not round-trip: a `Double?` set to null is indistinguishable from a key the engine never sent, and
 * the two mean opposite things at the document stage.
 */
@Immutable
sealed class TraceValue {
    data class Num(val value: Double) : TraceValue()

    data class Flag(val value: Boolean) : TraceValue()

    /** A string enum, or `styleId`. The engine's enums have values equal to their names. */
    data class Choice(val value: String) : TraceValue()

    /** `null` — today only `output.background`. See the class header. */
    data object Absent : TraceValue()
}

/**
 * The sanitised parameter tree, flattened for reading and carried whole for sending back.
 *
 * TWO REPRESENTATIONS OF ONE THING, AND THE SECOND IS THE AUTHORITY. [leaves] exists so a slider can
 * read its own value in one map lookup. [wire] is the tree exactly as the runtime produced it, and it
 * is what goes back — so a leaf this Kotlin has never heard of survives a round trip untouched
 * instead of being silently dropped by a re-serialisation from [leaves].
 */
@Immutable
class TraceValues(
    private val leaves: Map<String, TraceValue>,
    /** The whole sanitised tree, opaque to this side. Handed straight back to the runtime. */
    val wire: String,
) {
    operator fun get(key: String): TraceValue? = leaves[key]

    fun number(key: String): Double? = (leaves[key] as? TraceValue.Num)?.value

    fun flag(key: String): Boolean? = (leaves[key] as? TraceValue.Flag)?.value

    fun choice(key: String): String? = (leaves[key] as? TraceValue.Choice)?.value

    /**
     * True when the leaf holds a value rather than the engine's `null`.
     *
     * This is what the "White background" toggle reads, mirroring the web's
     * `read: (p) => p.output.background !== null`.
     */
    fun present(key: String): Boolean {
        val leaf = leaves[key] ?: return false
        return leaf != TraceValue.Absent
    }

    val keys: Set<String> get() = leaves.keys

    /** The style this tree belongs to. Every preset writes its own id, so it cannot lie. */
    val styleId: String get() = choice(TRACE_STYLE_ID_KEY).orEmpty()
}

/** The key `TraceParams.styleId` flattens to. Written into anything persisted. */
const val TRACE_STYLE_ID_KEY: String = "styleId"

/* ────────────────────────────────────────────────────────────────────────────
 * Presets, as the engine's own tables
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One entry of the engine's `Styles.ALL` or `Subjects.ALL`.
 *
 * READ FROM THE ENGINE AT RUN TIME, NEVER TRANSCRIBED HERE. The twenty style ids are binding — they
 * are written into `TraceParams.styleId` and therefore into anything persisted — so a shortened or
 * re-typed list on one client cannot open the other client's saved trace. A hand-copied table would
 * also be a second copy of somebody else's register, which is the failure mode this repository has
 * already met in its own dashboard-tile list, where eleven rows of twenty stood for months and "the
 * honest reading of a missing tile was 'this tile is not expected'".
 *
 * [group] is empty for a subject; subjects are a flat list. Nothing here counts either table.
 */
@Immutable
data class TracePreset(
    val id: String,
    val name: String,
    /** The upstream's own sentence. Rendered under the row, never as a tooltip — a phone has no hover. */
    val description: String,
    val group: String,
)

/** Both tables, in the engine's own order. */
@Immutable
data class TracePresetTables(
    val styles: List<TracePreset>,
    val subjects: List<TracePreset>,
)

/* ────────────────────────────────────────────────────────────────────────────
 * A run
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Why a trace is being run — which decides the resolution and what happens to the answer.
 *
 * ONE IN-FLIGHT RUN COVERS ALL OF THESE, and the panel holds exactly one busy flag for them. The web
 * learned that the hard way: two independent busy flags meant "the loser would report 'the trace did
 * not finish' while the winner quietly succeeded".
 */
enum class TraceRunKind {
    /**
     * A small, fast trace to look at while tuning. Emits NO progress events — the upstream worker
     * calls its preview entry point without a listener, so there is nothing to drive a bar with and
     * the surface shows a working line instead of one.
     */
    PREVIEW,

    /** Full resolution, because the answer is about to be attached. */
    ATTACH,

    /** Full resolution, because the answer is about to be written out as a file the researcher keeps. */
    EXPORT,
}

/** True for the two kinds that run at full resolution and therefore report progress. */
val TraceRunKind.isFullResolution: Boolean get() = this != TraceRunKind.PREVIEW

/** What the researcher asked for. */
@Immutable
data class TraceRequest(
    /**
     * The photograph to trace, as a `content://` Uri the host already holds.
     *
     * ── A Uri AND NOT A FILE PATH, WHICH IS THE ONE SHAPE CHANGE THIS PORT MAKES TO THE SEAM ──
     *
     * The design-workshop original took an absolute path, because its photographs had already been
     * copied into that app's own media directory by a capture pipeline the panel could see. This
     * panel deliberately cannot see any such thing: it is handed ONE image by whatever screen mounts
     * it, and on this client an image arrives as a `content://` Uri from the camera, the gallery
     * picker, a share intent or a record's media list. Resolving a Uri to a path is not possible in
     * general on Android 10 and later, and every app that tried is now carrying the scar.
     *
     * So the decode reads through `ContentResolver`, twice — bounds and then pixels — and the panel
     * never holds a `File` at all until it writes the ONE derived file it hands to `onAttach`.
     */
    val photograph: Uri,
    val params: TraceValues,
    val kind: TraceRunKind,
    /**
     * Which REGION of the decoded photograph the engine is handed, or null for all of it.
     *
     * A TRACE INPUT AND NOT AN EDIT. Nothing about this writes to the record: the photograph is
     * untouched, the crop is taken afresh from the whole decode on every run, and widening the frame
     * back out is therefore always possible. `TraceCrop.kt`'s header holds the three rules that make
     * it a trace input rather than a file.
     *
     * It carries the frame it was aimed in, because a rectangle without one cannot be re-scaled, and
     * the decode this meets is allowed to come back at a size the panel did not predict.
     */
    val frame: TraceFrameChoice? = null,
    /**
     * The long edge both display plates come back at.
     *
     * NOT NEGOTIABLE ON A HANDSET, and 1024 is the web's own number. Two 1024x1024 ARGB_8888 bitmaps
     * are 8.4 MB, which is affordable; the full-resolution pair at 4096 is ~134 MB, and three copies
     * of one big buffer is already how a 2 GB handset kills a page. On the web that is a slow tab.
     * Here it is an out-of-memory in a village.
     */
    val plateLongEdgePx: Int = TRACE_PLATE_LONG_EDGE_PX,
)

/** See [TraceRequest.plateLongEdgePx]. Mirrors the web's `COMPARISON_LONG_EDGE_PX`. */
const val TRACE_PLATE_LONG_EDGE_PX: Int = 1024

/** One stage boundary. Mirrors the client-side `TraceProgress` field for field. */
@Immutable
data class TraceProgress(
    /** One of the engine's stage ids. Stable: the UI keys its progress rows on them. */
    val stageId: String,
    /** The engine's own label. Rendered as sent — see [TRACE_STAGES] for why it is never reworded. */
    val label: String,
    /** `index / n` at the START of the stage. Never reaches 1.0. */
    val fraction: Float,
)

/** One measured stage. */
@Immutable
data class TraceStageTiming(val id: String, val label: String, val millis: Long)

/**
 * What a finished trace hands back.
 *
 * The two bitmaps are DISPLAY PLATES and neither of them may ever reach a record. What gets attached
 * is [svg], which is the engine's own writer output and has been through no canvas.
 */
@Immutable
class TraceResult(
    /**
     * The vector document, as the engine's own writer spelled it.
     *
     * THIS STRING IS THE ARTEFACT. The cross-runtime parity harness compares it EXACTLY, because what
     * reaches a record is a string, so nothing on this side may re-print, re-indent or "tidy" it.
     */
    val svg: String,
    /**
     * The shapes themselves, kept so the export can paint a picture of them. Null when a host has none.
     *
     * ── WHY THE RESULT HOLDS THIS AT ALL, WHICH IS A COST AND NOT A CONVENIENCE ───────────────
     *
     * The PNG export cannot be built from anything else: the SVG is the artefact and re-parsing it
     * would need an SVG reader on this side, which is a second opinion about the one string the
     * cross-runtime parity harness compares exactly; and the display plates are 1024 px, forced onto
     * white, and forbidden from reaching a file. So a picture of the drawing means the drawing, and
     * this is where it lives between the run that produced it and the button that saves it.
     *
     * WHAT IT COSTS, AS ARITHMETIC RATHER THAN AS A GUESS. [TraceGeometry] is flat arrays: the
     * coordinates dominate at four bytes each, and a 50,000-path trace is roughly a million
     * coordinates — so the worst case this feature admits is about 4 MB retained, against the 8.4 MB
     * of display plates the same result already holds and the 16.8 MB the PNG itself allocates while
     * it is being written. It is retained for as long as a result is on screen and released with it.
     *
     * NULLABLE BECAUSE THE EXPORT CARD IS DELIBERATELY COMPOSABLE WITHOUT ONE, not because a run can
     * fail to produce it. A host that has no geometry to give gets [TRACE_NO_GEOMETRY_SENTENCE] and
     * the SVG door, which still works.
     */
    val geometry: TraceGeometry?,
    /**
     * The trace, rendered for the comparator, PAINTED ON OPAQUE WHITE — or null.
     *
     * The white belongs to the COMPARISON and not to the export, and it is not optional:
     * `output.background` defaults to null, and a transparent AFTER layer stacked over the photograph
     * shows the photograph through both layers — the divider then moves and nothing changes, which is
     * indistinguishable from a broken slider.
     *
     * **NULLABLE, AND THAT IS THE WHOLE POINT.** A plate is a display artefact and [svg] is the
     * archive one, so a plate that could not be built must cost the comparison and nothing else. When
     * this is null so is [photographPlate], and [plateRefusal] says why in a sentence written to be
     * read.
     */
    val tracePlate: Bitmap?,
    /**
     * The photograph, from THE DECODED PIXELS THE ENGINE WAS HANDED, at the same size as [tracePlate].
     *
     * Not from the Uri a second time. Two decoders hold different EXIF opinions, so one layer can
     * arrive rotated and the other upright — which reads on screen as "the trace came out sideways".
     * These are also the pixels the engine actually traced, which is what the comparison is about.
     *
     * Null exactly when [tracePlate] is null. The two are built together or not at all — a comparator
     * with one layer is not a comparator.
     */
    val photographPlate: Bitmap?,
    /**
     * Why there are no plates, for a trace that itself succeeded. Empty when there are.
     *
     * A SENTENCE AND NOT A FLAG: a sentence that has to be printed is a value. The panel prints it
     * where the comparator would have been, and the drawing above it stays attachable.
     */
    val plateRefusal: String,
    /** The document's own frame: the source size, or the rectified page. */
    val width: Int,
    val height: Int,
    /** The resolution the trace RAN at. Smaller than [width] for a preview, and that must be stated. */
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<TraceStageTiming>,
    val totalMillis: Long,
    /**
     * **EVERY SENTENCE, RENDERED WITHOUT EXCEPTION.**
     *
     * `Pipeline.kt` calls rendering these a REQUIREMENT and names the bug it prevents: "a pipeline
     * that silently discarded four thousand paths and one that genuinely found nothing produce the
     * same blank canvas", which is the ambiguity this project takes most seriously.
     */
    val notes: List<String>,
    /**
     * The parameters the stages ACTUALLY RAN WITH, which is not always the ones that were sent.
     *
     * Auto-detection runs before the first stage. Rendering the panel from the request instead would
     * leave a dock that says one thing beside a drawing produced by another.
     */
    val appliedParams: TraceValues,
    /** The subject the engine applied by itself, or empty. Stated on screen when non-empty. */
    val autoSubjectId: String,
    /**
     * The style the engine's classifier suggests for this photograph, or empty.
     *
     * **ALWAYS EMPTY ON THIS RUNTIME**, and that is a feature the Kotlin engine does not have rather
     * than a field somebody forgot — see [TRACE_NO_SUGGESTION_NOTE], which holds the three ways of
     * recovering an id and why each is worse than an empty field.
     */
    val suggestedStyleId: String,
    /**
     * What was done to the photograph before the engine saw it, as a clause for the exported file's
     * provenance note. Empty when the whole photograph was traced.
     *
     * Built by [traceCropNote] beside the arithmetic that produced it, in the web's exact words,
     * because a handset's drawing and the portal's drawing land in one archive and a reviewer holding
     * both should not have to decide whether two phrasings mean two operations.
     */
    val frameNote: String = "",
) {
    /** True when the trace ran below full resolution — the sentence the panel must print. */
    val isPreview: Boolean get() = workingWidth < width || workingHeight < height

    /** True when both plates are here, i.e. when there is a comparison to show. */
    val hasPlates: Boolean get() = tracePlate != null && photographPlate != null
}

/** Done, or refused in a sentence. Cancellation is NEITHER — see [TraceEngineRuntime.trace]. */
sealed class TraceOutcome {
    data class Done(val result: TraceResult) : TraceOutcome()

    /**
     * The trace could not run, in one sentence a researcher can act on.
     *
     * A REFUSAL IS NOT AN EXCEPTION here: the caller has to print it, and a sentence that has to be
     * printed is a value. Exceptions are for the cases nobody wrote a sentence for.
     */
    data class Refused(val reason: String) : TraceOutcome()
}

/* ────────────────────────────────────────────────────────────────────────────
 * What this handset can actually do
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the runtime on THIS phone will and will not do, measured rather than assumed.
 *
 * ── WHY THE TWO CEILINGS ARE HERE AND NOT IN THE UI ───────────────────────────────────────────
 *
 * Measured, on a laptop's V8 at the product's own input size of 1600x1200: a full trace is **2.9 s
 * with the adaptive engine and 16.7 s with the shipped flow default**, because flow is 5.7x
 * everything else and 13,037 of those 16,655 ms are in one stage. Scaled to a mid-range Android
 * handset that is roughly 12–20 s against 67–117 s. **The desktop-to-handset factor was reasoned
 * from published single-thread figures, not measured on a device**, and it is the single number that
 * decides whether this is usable.
 *
 * So the ceilings are a property of the runtime, which is the half that can measure them, and
 * [measuredOn] is the sentence that says whether anybody has. A UI that hard-coded 2048 would be
 * writing an unmeasured claim into a screen.
 *
 * ── THIS IS ONLY ABOUT HOW BIG, NEVER ABOUT WHETHER ───────────────────────────────────────────
 *
 * There is no `canTrace` here and there must not be one. The engine is `:core-imaging`,
 * `:core-vector`, `:core-pipeline` and `:core-export`, compiled into the APK by the same Gradle build
 * that compiles this file. **If this app runs, it traces.** A boolean that is true on every device is
 * not a gate; it is a field every reader has to check before they can conclude nothing happens, and
 * an apology for a device that is not the problem is a false remedy waiting to be shown.
 *
 * What can still stop ONE trace is memory, and that is answered per trace against the frame actually
 * being traced — [traceMemoryRefusal], which runs after the decode and before the first stage and
 * names both numbers in its sentence. That is a better answer than a field here could ever be: a
 * phone with a full heap at four o'clock is not a phone that cannot trace.
 *
 * ── AND WHY FLOW GETS A CEILING OF ITS OWN RATHER THAN A SUBSTITUTION ─────────────────────────
 *
 * The obvious fix — quietly swap flow for adaptive on a phone — is the worst available option and the
 * one the vendoring discipline exists to prevent: one sheet of paper would then produce two different
 * drawings depending on which client traced it. So the handset REFUSES and names the remedy, the
 * researcher chooses, and both clients then agree because they are running the same parameters.
 */
@Immutable
data class TraceAvailability(
    /** The largest `preprocess.workingLongEdge` this device has been measured to survive. */
    val maxWorkingLongEdge: Int,
    /** The largest working long edge the flow edge engine may run at here. See the class header. */
    val fdogMaxWorkingLongEdge: Int,
    /**
     * What the two ceilings were measured on and when, e.g. "Galaxy M32 (SM-M325F), Android 13,
     * 2026-09-14". **Null means nobody has measured them and they are conservative guesses** — which
     * the panel says out loud rather than presenting a guess as a limit.
     */
    val measuredOn: String?,
)

/* ────────────────────────────────────────────────────────────────────────────
 * The runtime
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Whatever runs the vendored engine on this phone.
 *
 * ── CANCELLATION IS KOTLIN'S, AND THAT IS A DELIBERATE PORT OF THE ENGINE'S OWN CHOICE ────────
 *
 * [trace] is a `suspend fun`, so cancelling it is cancelling its job. There is no handle object, no
 * `cancel()` method and no second flag, because the vendored `CancellationToken` is deliberately not
 * any one platform's cancellation type — "the engine must run identically under vitest, in a worker
 * and on a JVM-shaped API". A structured-concurrency job IS the JVM-shaped API that describes.
 *
 * **A cancelled trace throws `CancellationException` and MUST NOT be reported as a failure.** A
 * cancel "must never reach the user as one", and the panel honours it by never turning a
 * `CancellationException` into an error line.
 *
 * **Cancellation is granular between stages and inside the long ones, and nowhere else.** Worst-case
 * latency is therefore roughly the duration of one sub-step of the longest stage — `edge` or
 * `vectorise`, seconds on a phone at full resolution. The SURFACE must not promise instant, which is
 * why it says "Stopping…" rather than disappearing.
 *
 * ── SUPERSEDING ───────────────────────────────────────────────────────────────────────────────
 *
 * A second [trace] while one is in flight is the caller's job to sequence: cancel, then launch. A
 * cancelled coroutine cannot leak a pending promise, a progress closure and a listener per drag tick
 * of a slider, which is one more reason this port is shaped like a suspend function.
 *
 * ── IMPLEMENTATIONS MUST BE MAIN-SAFE ─────────────────────────────────────────────────────────
 *
 * **THIS IS PROPERTY 2 OF THE WHOLE FEATURE AND THERE IS NO PATH AROUND IT.** Every method here is
 * called from a composable's scope, which is the main thread. `Pipeline.run` is straight loops over
 * typed arrays that never yield — a 12 MP trace is seconds of solid CPU — so an implementation that
 * ran it on the caller's dispatcher would be a FROZEN app, not a slow one, and Android would kill it
 * with an ANR. `withContext` belongs INSIDE the implementation, never at the call site, so that no
 * future caller can forget it. `TraceRuntime.kt` puts it on every one of the six.
 */
interface TraceEngineRuntime {

    /** What this phone can do. Cheap and synchronous — the panel reads it while composing. */
    val availability: TraceAvailability

    /** `Styles.ALL` and `Subjects.ALL`, read from the engine rather than transcribed. */
    suspend fun presets(): TracePresetTables

    /**
     * The engine's factory defaults, sanitised.
     *
     * NOTE FOR WHOEVER IMPLEMENTS THIS: the shipped default carries `edge.engine = FDOG` and
     * `auto.mode = SUGGEST`. Neither is changed here — the default tree is the engine's to state —
     * but the panel bars flow above [TraceAvailability.fdogMaxWorkingLongEdge]. Both arguments are in
     * `TraceParams.kt`.
     */
    suspend fun defaults(): TraceValues

    /**
     * The engine's own merge-then-sanitise of [patch] over [base].
     *
     * ONE CALL, NOT TWO, and no Kotlin merge in between. The web splits them only because a static
     * value import of the engine's params module would put ~28 KB of engine source into a page
     * bundle. No such cost exists here, so the copy does not exist here either — and a copy of
     * somebody else's merge rule is one more thing that drifts.
     */
    suspend fun withOverrides(base: TraceValues, patch: Map<String, TraceValue>): TraceValues

    /**
     * Apply a style preset: that preset's whole parameter tree.
     *
     * A STYLE IS A COMPLETE TREE, NOT A DIFF: "a user who switches styles expects the second one to
     * look like itself rather than like a blend of the two". So this does not merge onto [base] —
     * [base] is passed only so an implementation can keep what a style legitimately does not name.
     */
    suspend fun applyStyle(base: TraceValues, styleId: String): TraceValues

    /**
     * Apply a subject preset: `Subjects.byId(subjectId).adjust(base)`.
     *
     * A SUBJECT IS A MODIFIER ON A STYLE, NOT A SECOND STYLE LIST — it nudges denoise, blob area and
     * engine choice for the MATERIAL while leaving the look the style chose intact.
     *
     * **IT COMPOUNDS ON THIS ENGINE**, which the web's does not. See `TracePresets.kt` for the
     * measurement, the sentence every subject row carries because of it, and why nothing here
     * "fixes" a vendored judgement.
     */
    suspend fun applySubject(base: TraceValues, subjectId: String): TraceValues

    /**
     * Run one trace, reporting each stage boundary.
     *
     * [onProgress] is called on the main thread, once per stage, and NEVER for a
     * [TraceRunKind.PREVIEW] — the vendored worker passes no listener to its preview entry point.
     *
     * **HOW MANY STAGES THERE ARE IS THE RUNTIME'S TO SAY, NOT THIS PORT'S.** The vendored Kotlin
     * engine reports NINETEEN where the TypeScript's table has twelve, because it separates steps the
     * TypeScript fuses. [TRACE_ENGINE_STAGES] in `TraceRuntime.kt` states which is which and what the
     * difference costs the progress UI.
     *
     * @throws kotlinx.coroutines.CancellationException when the job is cancelled. Not a failure.
     */
    suspend fun trace(
        request: TraceRequest,
        onProgress: (TraceProgress) -> Unit,
    ): TraceOutcome
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one sentence left about the runtime itself
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence for a panel that finished loading and has no parameters to draw.
 *
 * ── WHEN THIS IS ACTUALLY REACHED, WHICH IS ALMOST NEVER ──────────────────────────────────────
 *
 * `TracePanel` shows it in exactly one state: the load coroutine ran to completion, threw nothing,
 * and left `params` or `presets` null. [TraceEngineRuntime.defaults] and [TraceEngineRuntime.presets]
 * both read compiled-in tables and neither can return null, so on this build the state is
 * unreachable — the honest reading is that it is a backstop for a future runtime, not a description
 * of anything a researcher meets today. A thrown failure takes the other branch and is reported with
 * the reason it carried, which is a better sentence than this one and is preferred wherever there is
 * one.
 *
 * SO IT BLAMES NOTHING AND NAMES WHAT SURVIVES. It cannot know what went wrong — that is the whole
 * shape of the state — so it does not guess at a cause, and it says the two things that are true
 * whatever the cause: the photograph is untouched, and trying again is free.
 *
 * **IT DOES NOT APOLOGISE FOR THE DEVICE**, and its predecessor in the sibling repository did. That
 * one said the tracer was "not available on this phone yet", which was true of a build whose engine
 * was a JavaScript bundle needing a recent WebView and is false of this one: the engine is four
 * Gradle modules inside the APK, so there is no phone this app installs on that cannot trace. A
 * sentence that sends a researcher to update something that will not change the answer is worse than
 * no sentence at all, and it is not recoverable by anything they can do in a village.
 */
const val TRACE_ENGINE_SILENT_SENTENCE: String =
    "The tracing controls did not come back this time, and nothing has said why. Close this and open " +
        "it again — the photograph is untouched either way."
