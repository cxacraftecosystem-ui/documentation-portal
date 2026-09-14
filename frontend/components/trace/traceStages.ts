/**
 * **THE TWELVE STAGES A TRACE GOES THROUGH, AND HOW FAR ALONG ONE ACTUALLY IS.**
 *
 * ── WHY THE PANEL NEEDS THIS AT ALL ───────────────────────────────────────────────────────────
 *
 * The panel receives `stageId`, `label` and `fraction` on every progress event. Rendering only the
 * label — a bare `"${label}…"` beside a spinner — means a researcher watching a full-resolution trace
 * cannot tell a stalled run from a slow one, which on the stage that takes most of the wall clock is
 * a wait long enough to reach for the reload button.
 *
 * ── WHY NOT JUST DRAW THE FRACTION THE ENGINE SENDS ───────────────────────────────────────────
 *
 * Because it is a stage COUNT and not a time estimate. `engine/pipeline.ts` posts `index / 12` at the
 * START of each stage, so the events are 0.000, 0.083, 0.167 … 0.917 whatever the stages cost — and
 * the last one ever sent is 0.917, so **the engine's fraction never reaches 1.0.** The two stages that
 * dominate a real trace are `edge` and `vectorize`, so a bar driven off the raw fraction rushes to a
 * half and then appears to hang for most of the run, which reads as a crash rather than as work.
 *
 * {@link progressWeights} therefore re-weights the boundaries by what THIS MACHINE measured on its
 * last completed trace, which the result already carries (`SerializedTraceResult.stages`). Until there
 * has been one, the weights are the engine's own even spacing and {@link ProgressWeights.measured} is
 * false — which the panel states in words rather than drawing a bar that will visibly stall with no
 * explanation.
 *
 * ── THE LABELS HERE ARE FOR WEIGHTING AND FOR THE TEST. THEY ARE NOT WHAT IS DRAWN ────────────
 *
 * The progress row renders the label the ENGINE sent with the event, never {@link TraceStage.label}
 * from this table — re-typing engine wording in a client is how two clients end up describing one
 * operation differently. This table exists so the bar can be weighted, and so a vendored engine update
 * that inserts a thirteenth stage fails `e2e/trace-stages-unit.spec.ts` instead of silently
 * mis-numbering a sentence a researcher reads.
 *
 * ── AND WHY IT IS ITS OWN FILE RATHER THAN A CONST IN THE PANEL ───────────────────────────────
 *
 * `TracePanel.tsx`'s header forbids a top-level import from `@/lib/trace/*` in that file, so the
 * table cannot be read off the engine at render time. It is a transcription, and a transcription that
 * nothing checks is a transcription that is already wrong — so it lives where a spec can import it
 * beside the real `engine/pipeline.stageIds()` and compare the two. The panel imports plain numbers
 * and strings, exactly as it does from `traceParamTable.ts`.
 */

/** One pipeline stage. `id` is stable — the engine's own note says the UI keys its progress on them. */
export interface TraceStage {
  readonly id: string;
  readonly label: string;
}

/**
 * `engine/pipeline.ts`'s `STAGES`, in execution order, transcribed exactly.
 *
 * Two of them run their bodies only under a condition and **every one of them still fires**, so there
 * is no way to tell a skipped stage from a fast one at this boundary — and the surface must not invent
 * a "skipped" state. It shows the label the engine sent.
 */
export const TRACE_STAGES: readonly TraceStage[] = [
  { id: "prepare", label: "Preparing image" },
  { id: "matte", label: "Separating background" },
  { id: "crop", label: "Cropping to the subject" },
  { id: "gray", label: "Converting to grey" },
  { id: "denoise", label: "Reducing noise" },
  { id: "contrast", label: "Enhancing contrast" },
  { id: "edge", label: "Detecting edges" },
  { id: "cleanup", label: "Cleaning up" },
  { id: "skeleton", label: "Thinning strokes" },
  { id: "distance", label: "Measuring stroke width" },
  { id: "vectorize", label: "Tracing vectors" },
  { id: "document", label: "Assembling document" }
];

/** Twelve, from the table rather than from anybody's memory. */
export const TRACE_STAGE_COUNT = TRACE_STAGES.length;

/** Position in execution order, or -1 for an id this build has never heard of. */
export function traceStageIndex(stageId: string): number {
  return TRACE_STAGES.findIndex((stage) => stage.id === stageId);
}

/**
 * The sentence beside the spinner, and the one a screen reader is given.
 *
 * "Stage 7 of 12" and not a percentage, because the percentage is the thing this file has just spent a
 * page explaining is not a time estimate. The label is the ENGINE's, passed straight through. A stage
 * this build has never heard of is described by its label alone rather than by a wrong number — the
 * honest answer to a vendored update, and the case the test above exists to make loud instead.
 */
export function traceProgressSentence(stageId: string, label: string): string {
  const index = traceStageIndex(stageId);
  if (index < 0) return label;
  return `${label}. Stage ${index + 1} of ${TRACE_STAGE_COUNT}.`;
}

/** Where each stage STARTS on a 0..1 bar, and whether those positions were measured or guessed. */
export interface ProgressWeights {
  readonly startFractions: Readonly<Record<string, number>>;
  /** False while these are the engine's even spacing. The panel says so on screen when it is. */
  readonly measured: boolean;
}

/** The engine's own `index / 12`, which is what there is before this machine has finished a trace. */
export const UNWEIGHTED: ProgressWeights = {
  startFractions: Object.fromEntries(TRACE_STAGES.map((stage, index) => [stage.id, index / TRACE_STAGE_COUNT])),
  measured: false
};

/**
 * Weights from a completed run's own timings.
 *
 * Answers {@link UNWEIGHTED} rather than dividing by zero when the timings are empty or sum to
 * nothing, which is a real case and not a defensive one: a preview reports no timings at all, and a
 * trace of a blank sheet can finish fast enough for every stage to round to zero.
 */
export function progressWeights(timings: readonly { id: string; millis: number }[]): ProgressWeights {
  let total = 0;
  for (const timing of timings) total += Number.isFinite(timing.millis) ? Math.max(0, timing.millis) : 0;
  if (timings.length === 0 || total <= 0) return UNWEIGHTED;
  const startFractions: Record<string, number> = {};
  let elapsed = 0;
  for (const timing of timings) {
    startFractions[timing.id] = elapsed / total;
    elapsed += Number.isFinite(timing.millis) ? Math.max(0, timing.millis) : 0;
  }
  return { startFractions, measured: true };
}

/**
 * How full the bar is at the start of `stageId`.
 *
 * @param engineFraction the fraction the engine sent, used for a stage the weights have never seen —
 *   which happens on the very first event of a machine's first trace and after a vendored update.
 *   Better a slightly wrong bar than none.
 */
export function fractionAt(weights: ProgressWeights, stageId: string, engineFraction: number): number {
  const known = weights.startFractions[stageId];
  const value = typeof known === "number" ? known : engineFraction;
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

/**
 * The one line under an unweighted bar.
 *
 * The handset's counterpart says "…until this phone has finished one trace"; there is no phone here.
 * Same class of divergence as "Save" against "Download" in the export row — the sentence is shared,
 * the noun is the platform's.
 */
export const PROGRESS_UNMEASURED_NOTE =
  "The bar counts stages, not time, until this browser has finished one trace.";
