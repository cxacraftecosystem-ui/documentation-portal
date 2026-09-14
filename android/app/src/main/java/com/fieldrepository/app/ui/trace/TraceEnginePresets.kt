package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.StylePreset
import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.SubjectPreset
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams

/**
 * **THE TWO PRESET REGISTERS, READ OUT OF THE VENDORED ENGINE — AND THE PLACES WHERE IT AND THE
 * PORTAL DO NOT AGREE.**
 *
 * ── WHAT THIS FILE IS ─────────────────────────────────────────────────────────────────────────
 *
 * [TraceEngineRuntime] has six members and two of them are preset work: `applyStyle` and
 * `applySubject`. This file is those two, plus the `presets()` tables they are chosen from, backed by
 * `:core-pipeline`'s `Styles` and `Subjects`.
 *
 * Each half is stated at two levels. The `TraceParams` forms carry the judgement and are what the tests
 * exercise; the [TraceValues] forms are two-line compositions over `TraceEngineParams.kt`'s codec —
 * `traceParamsOf` in, `traceValuesOfParams` out — and are what the runtime calls. That split keeps
 * every decision below testable without a JSON round trip, and keeps the round trip itself in the one
 * file that owns it.
 *
 * ── THERE IS NO KOTLIN TABLE OF PRESET NAMES HERE, AND THERE MUST NOT BE ──────────────────────
 *
 * Every id, name, description and group below is read from `Styles.ALL` and `Subjects.ALL` at run time.
 * The ONLY strings this file owns are the divergence notes and the two refusals, and each of them is
 * named, constant and pinned by a test. **ALL TWENTY STYLES SHIP, INCLUDING THE TEN THAT LOOK WRONG
 * FOR THIS PRODUCT** — `stencil`, `silhouette`, `laser-cut`, `embroidery`, `craft-pattern`,
 * `colouring-book`, `single-stroke`, `tattoo-outline`, `comic`, `woodcut` target a cutting machine, a
 * plotter or an embroidery hoop, and it is tempting to ship ten.
 *
 * **Do not.** Three reasons, in increasing order of how badly it would go: somebody photographing a
 * block print really does want `woodcut`; the ids are BINDING — they are written into
 * `TraceParams.styleId` and therefore into anything persisted — so a shortened list on one client
 * cannot open the other client's saved trace; and a filtered copy of somebody else's register is a
 * second register that drifts, which is the failure this repository has already met in its own
 * dashboard-tile list, where eleven rows of twenty stood for months. What changes on a handset is ORDER
 * and SEARCHABILITY, not membership — see [traceStyleOptions].
 *
 * ── THE TWO REGISTERS ARE NOT THE PORTAL'S, AND THIS IS THE HONEST ACCOUNT OF HOW ─────────────
 *
 * The two registers below belong to the KOTLIN engine, and the portal's belong to the TypeScript one.
 * They are two vendorings of one upstream and they are not identical, which is what this section is
 * for.
 *
 * **STYLES AGREE ON THE THING THAT IS BINDING.** Both registers carry the same twenty ids in the same
 * order — `clean-line` … `minimal` — and the id is what is persisted. What differs is display text,
 * which both registers say may differ: **one name** (`comic` is "Comic ink" here and "Comic" on the
 * portal) and **fourteen of the twenty groups**, because the engines group into different sets — four
 * here (Drawing, Print & relief, Fabrication, Education) against five there (Line art, Drawing,
 * Technical, Print & relief, Making), with only "Print & relief" common. Somebody therefore reads
 * "Fabrication · Technical drawing" on a handset and "Technical · Technical drawing" on a laptop.
 * Nothing about the drawing changes.
 *
 * **SUBJECTS DISAGREE ON THE SET, AND THAT IS NOT SOMETHING TO PICK A WINNER FOR SILENTLY.** This
 * engine has TWELVE; the portal has TEN. Nine ids are shared and spelled identically. The other three
 * here — `wood-carving`, `stone-carving`, `metalwork` — have no portal row, and the portal's `carving`
 * ("Wood & stone carving") has no row here.
 *
 * This file takes **the upstream twelve**, because they are what the engine actually contains and a
 * filtered copy of somebody else's register is a second register that drifts. It then says so where a
 * researcher can see it: [TRACE_SUBJECT_DIVERGENCE_NOTES] puts a sentence on each of the three rows the
 * portal has no match for, and [traceNoSuchSubjectSentence] answers a portal-only id with the remedy
 * rather than with "no such thing".
 *
 * ── AND THE ONE BEHAVIOURAL DIFFERENCE, WHICH MATTERS MORE THAN EITHER LIST ───────────────────
 *
 * **A SUBJECT IS IDEMPOTENT ON THE PORTAL AND COMPOUNDS HERE.** The TypeScript tables are *absolute*
 * overrides pushed through a merge, so re-applying one is a no-op. The Kotlin tables are *relative*:
 * every one of the twelve `adjust` bodies is built from `scale()` and `raise()`, so a second
 * application multiplies again. `painting` on `clean-line` takes `cleanup.minBlobArea` 24 → 60 → 150.
 * `Subjects.kt` claims only the weaker property — that applying twice cannot leave the legal range —
 * and that claim is true.
 *
 * Nothing here "fixes" that. Correcting it would be a third behaviour, belonging to neither engine,
 * invented in the adapter — and vendored judgement is not this repository's to improve. Instead it is
 * pinned by a test so it cannot become a surprise, stated on every subject row by
 * [TRACE_SUBJECT_COMPOUNDS_NOTE] with the remedy that actually works, and handed to an owner as a
 * decision rather than absorbed as an accident.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences this file owns
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Appended to **every** subject row, because it is true of every one of the twelve.
 *
 * See the file header. The remedy is the real one: [traceApplyStyle] returns the style's whole tree and
 * discards what was there, so re-picking the style is a genuine reset to the preset.
 */
internal const val TRACE_SUBJECT_COMPOUNDS_NOTE: String =
    "Tapping this again adjusts the settings that are on screen now, so it compounds; pick the " +
        "style again to start from the preset."

/**
 * The subjects this engine carries that the portal's register has no row for, in `Subjects.ALL` order.
 *
 * `TraceEnginePresetsTest` asserts this against `Subjects.ALL` itself, so the day the register changes
 * the test fails and somebody decides, rather than the two drifting quietly.
 */
internal val TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE: List<String> =
    listOf("wood-carving", "stone-carving", "metalwork")

/** The reverse: the portal's subject ids this engine has no row for. Same pinning. */
internal val TRACE_SUBJECTS_ONLY_ON_THE_PORTAL: List<String> = listOf("carving")

/** Shared by the two halves of the portal's single `carving` row. Declared once so it reads once. */
private const val TRACE_SPLIT_CARVING_NOTE: String =
    "The portal carries wood and stone as one “Wood & stone carving” material, so a laptop cannot " +
        "tell which of the two was chosen here."

/**
 * The sentence each unmatched subject row carries, on screen, under the material's own hint.
 *
 * Keyed by id and covering exactly [TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE] — the test asserts the two
 * agree, so a row cannot be added to one without the other. Two sentences rather than one because the
 * two facts are different: two rows here are one row there, and one row here is no row there.
 */
internal val TRACE_SUBJECT_DIVERGENCE_NOTES: Map<String, String> = linkedMapOf(
    "wood-carving" to TRACE_SPLIT_CARVING_NOTE,
    "stone-carving" to TRACE_SPLIT_CARVING_NOTE,
    "metalwork" to
        "The portal has no metalwork material at all, so this choice exists only on the handset.",
)

/* ────────────────────────────────────────────────────────────────────────────
 * The tables
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Styles.ALL` and `Subjects.ALL` as the picker reads them.
 *
 * Built once. Both are immutable `val`s initialised with the presets themselves, so nothing about this
 * can change between calls and re-mapping thirty-two entries on every composition would buy nothing.
 */
internal fun tracePresetTables(): TracePresetTables = TRACE_PRESET_TABLES

private val TRACE_PRESET_TABLES: TracePresetTables = TracePresetTables(
    styles = Styles.ALL.map(::traceStyleRow),
    subjects = Subjects.ALL.map(::traceSubjectRow),
)

/**
 * One style row, in `Styles.ALL` order — which is the display order the register calls binding
 * alongside the ids ("append to the end, never renumber").
 *
 * The order is NOT grouped-contiguous in either engine: `Styles.ALL` runs Drawing, Drawing,
 * Fabrication, Fabrication, Drawing, so a group recurs after another has intervened. That is exactly
 * why [traceStyleOptions] folds the group into the label instead of drawing sticky headers over the
 * list — see its own KDoc, which makes the argument from the search box.
 */
private fun traceStyleRow(style: StylePreset): TracePreset = TracePreset(
    id = style.id,
    name = style.name,
    description = style.description,
    group = style.group,
)

/**
 * One subject row: the engine's own hint, then the sentences this client owes the researcher.
 *
 * [TracePreset.group] is empty because subjects are a flat list — the same shape the picker is built
 * against on both clients.
 */
private fun traceSubjectRow(subject: SubjectPreset): TracePreset = TracePreset(
    id = subject.id,
    name = subject.name,
    description = buildString {
        append(subject.hint)
        append(' ')
        append(TRACE_SUBJECT_COMPOUNDS_NOTE)
        TRACE_SUBJECT_DIVERGENCE_NOTES[subject.id]?.let {
            append(' ')
            append(it)
        }
    },
    group = "",
)

/* ────────────────────────────────────────────────────────────────────────────
 * Applying a style
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Styles.byId(styleId).params`, WHOLE — a style is a complete tree, not a diff.
 *
 * ── [base] IS READ AND DISCARDED, ON PURPOSE ──────────────────────────────────────────────────
 *
 * One sentence covers it — "a user who switches styles expects the second one to look like itself
 * rather than like a blend of the two" — and `Styles.kt` argues the same from the other end: the
 * presets differ structurally, in engine and vector mode, not cosmetically.
 *
 * [TraceEngineRuntime.applyStyle] leaves room for an implementation to keep what a style legitimately
 * does not name, and the answer here is that it keeps nothing. The parameter stays in the signature so
 * the discard is visible at the place a reader would look for it rather than being a missing argument
 * nobody notices.
 *
 * The knob protection that [traceApplySubject] honours is deliberately absent here. A style gets no
 * such guard, and the panel treats a style as its new baseline. A style that quietly kept six values
 * from the last one would be the blend both registers say a designer does not expect.
 *
 * One difference from the TypeScript register is worth knowing about even though it changes nothing
 * here. There, a factory function FORCES `params.styleId` to the preset's own id, so "a copy-pasted
 * entry cannot ship a style that reports itself as a different one". Here each of the twenty writes its
 * own `styleId` by hand, so the guarantee is a convention rather than a construction — which is why
 * `TraceEnginePresetsTest` asserts `params.styleId == id` for all twenty rather than assuming it.
 *
 * @throws IllegalArgumentException with [traceNoSuchStyleSentence] for an id this register does not
 *   carry. A refusal and not `Styles.default()`: falling back would put `clean-line` on screen under
 *   the name of whatever was asked for, which is a silent substitution. The panel prints the message.
 */
@Suppress("UNUSED_PARAMETER")
internal fun traceApplyStyle(base: TraceParams, styleId: String): TraceParams {
    val preset = Styles.byId(styleId)
        ?: throw IllegalArgumentException(traceNoSuchStyleSentence(styleId))
    // `sanitized()` is a fixpoint on every preset — the engine's own `StylesTest` asserts it — so this
    // changes nothing today. It is here because the sanitiser is the sole authority on legality and a
    // tree that has not been through it is a tree nobody has checked.
    return preset.params.sanitized()
}

/* ────────────────────────────────────────────────────────────────────────────
 * Applying a subject
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `Subjects.byId(subjectId).adjust(base)`, with the style's identity and the researcher's own knobs
 * restored on top.
 *
 * ── THE THREE STEPS ARE THE ENGINE'S OWN, MINUS THE ONE THAT NEEDS A PHOTOGRAPH ───────────────
 *
 * `Subjects.kt`'s automatic path does `preserveIdentity(params, subject.adjust(params))`, then a matte
 * decision measured from the classifier, then `Knobs.restore(...).sanitized()`. This does the first and
 * the third. The middle one is skipped because it takes a `Classify.SourceProfile`, which only a run of
 * the pipeline over real pixels can produce — so the subject's own matte request stands here, and the
 * measurement overrules it later inside the trace if it disagrees.
 *
 * ── WHY THE TWO GUARDS ARE ENFORCED RATHER THAN TRUSTED ───────────────────────────────────────
 *
 * The register promises both: `adjust` "never touches `edge.engine`, `output.vectorMode`,
 * `output.fillClosed` or `styleId` — those are what a style *is*", and the automatic path "never
 * overwrites a knob named in `handTuned`". Today the twelve tables keep the first promise unaided.
 *
 * Enforcing anyway is upstream's own reasoning: the subject tables "are data, they are edited by hand,
 * and this is the one path that applies one *without a user having asked for it* — so it is the one
 * path where a table with a stray `engine =` in it would change somebody's export with nothing on
 * screen to explain it." The panel re-applies a subject on top of whatever is there, on a tap, after
 * any amount of hand tuning, so this is that path on this client.
 *
 * `auto` is carried across with the four identity fields, matching `preserveIdentity` exactly. It is
 * what holds `handTuned` and `mode`, and a subject table that reset either would be undoing a decision
 * made elsewhere on the screen.
 *
 * **THE HAND-TUNED RESTORE IS A NO-OP TODAY AND IS STILL NOT DEAD CODE.** No client writes
 * `auto.handTuned`, so [Knobs.restore] returns its input unchanged. `AutoParams.sanitized` does not
 * validate the list against the six names, so the day somebody does write it, an unprotected knob fails
 * silently — which is the argument [TRACE_KNOBS] was written under and the reason the wiring exists
 * before the feature that needs it.
 *
 * ── WHAT THIS FUNCTION DOES NOT PROMISE ───────────────────────────────────────────────────────
 *
 * **It is not idempotent.** See the file header. Callers that re-apply must expect a changed tree, the
 * surface says so on every row through [TRACE_SUBJECT_COMPOUNDS_NOTE], and the panel's own
 * [traceOverwriteNotice] names every setting that moved.
 *
 * @throws IllegalArgumentException with [traceNoSuchSubjectSentence] for an unknown id.
 */
internal fun traceApplySubject(base: TraceParams, subjectId: String): TraceParams {
    val preset = Subjects.byId(subjectId)
        ?: throw IllegalArgumentException(traceNoSuchSubjectSentence(subjectId))
    val adjusted = tracePreserveIdentity(base, preset.adjust(base))
    return Knobs.restore(base, adjusted, base.auto.handTuned).sanitized()
}

/**
 * Restores the four fields that make a style what it is, plus the `auto` block.
 *
 * A LOCAL MIRROR OF `Subjects.kt`'s own `preserveIdentity`, WHICH IS `private` THERE. The vendored file
 * is copied verbatim and hashed in `android/UPSTREAM-MANIFEST-KOTLIN.txt`, so widening its visibility is
 * not available; six lines that a test compares field by field against the contract is the cheaper of
 * the two honest options. `TraceEnginePresetsTest` walks all twenty styles against all twelve subjects
 * and asserts the four fields survive, which is the check that would catch this copy going stale.
 */
private fun tracePreserveIdentity(before: TraceParams, after: TraceParams): TraceParams = after.copy(
    edge = after.edge.copy(engine = before.edge.engine),
    output = after.output.copy(
        vectorMode = before.output.vectorMode,
        fillClosed = before.output.fillClosed,
    ),
    styleId = before.styleId,
    auto = before.auto,
)

/* ────────────────────────────────────────────────────────────────────────────
 * The two refusals
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "There is no style called …".
 *
 * A plain `IllegalArgumentException` carrying this sentence and nothing else. [TraceFailureKind] has no
 * member that fits — its five are about memory, the engine, the frame and the image — and pressing one
 * of them into service would be saying something untrue about what happened. The panel prints
 * `"That style could not be applied. "` followed by this, so a researcher reads one sentence with a
 * cause in it rather than a protocol apology with the real reason in brackets at the end.
 */
internal fun traceNoSuchStyleSentence(styleId: String): String =
    "There is no style called \"${styleId.trim()}\"."

/**
 * "There is no subject called …" — the same sentence, **with the register difference spelled out when
 * that is what actually happened.**
 *
 * The bare form is the plain refusal. The longer form fires for the ids in
 * [TRACE_SUBJECTS_ONLY_ON_THE_PORTAL], where "there is no such subject" would be true and useless: the
 * id is real, it is the portal's, and a host may well have seeded it from a record's own category.
 * Naming the three rows that replaced it turns a dead end into one tap.
 */
internal fun traceNoSuchSubjectSentence(subjectId: String): String {
    val bare = "There is no subject called \"${subjectId.trim()}\"."
    if (subjectId.trim() !in TRACE_SUBJECTS_ONLY_ON_THE_PORTAL) return bare
    return bare + " The portal's list has ten materials and this engine's has twelve: “Wood & stone " +
        "carving” is split into “Wood carving” and “Stone carving”, and “Metalwork” is added. Choose " +
        "one of those."
}

/* ────────────────────────────────────────────────────────────────────────────
 * The same two halves, at the boundary a runtime actually calls
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * [traceApplyStyle] as [TraceEngineRuntime.applyStyle] needs it.
 *
 * ── WHY THIS IS A SEPARATE FUNCTION RATHER THAN THE ONLY ONE ──────────────────────────────────
 *
 * Everything above is decided on the engine's own `TraceParams`, so the decisions are testable with no
 * JSON in the way, and the day a leaf is added to the vendored tree they keep working untouched. This
 * pair is the whole of the translation, and it lives beside the decisions rather than inside them.
 *
 * NOT `suspend`, and not on a dispatcher. Applying a preset is a handful of field copies and one
 * `sanitized()` — microseconds — so the `withContext` that [TraceEngineRuntime] requires belongs at the
 * implementation of the interface, where the same wrapper also covers `trace`. Putting it here would be
 * paying for a thread hop per tap to guard arithmetic that never blocks.
 *
 * @throws IllegalArgumentException for an unknown id, exactly as the `TraceParams` form does.
 */
internal fun traceApplyStyle(base: TraceValues, styleId: String): TraceValues =
    traceValuesOfParams(traceApplyStyle(traceParamsOf(base), styleId))

/**
 * [traceApplySubject] as [TraceEngineRuntime.applySubject] needs it. See the note above.
 *
 * The round trip is what makes the two guards reach the tree at all: `base` arrives as text, so
 * `auto.handTuned` — the one leaf the flattener deliberately skips, because it is an array and no
 * control reads it — is recovered from the tree by [traceParamsOf] rather than from the flat map, and
 * is therefore honoured here even though nothing on this screen can see it.
 */
internal fun traceApplySubject(base: TraceValues, subjectId: String): TraceValues =
    traceValuesOfParams(traceApplySubject(traceParamsOf(base), subjectId))
