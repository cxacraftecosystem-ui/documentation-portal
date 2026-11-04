export interface TraceStage {
  readonly id: string;
  readonly label: string;
}

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

export const TRACE_STAGE_COUNT = TRACE_STAGES.length;

export function traceStageIndex(stageId: string): number {
  return TRACE_STAGES.findIndex((stage) => stage.id === stageId);
}

export function traceProgressSentence(stageId: string, label: string): string {
  const index = traceStageIndex(stageId);
  if (index < 0) return label;
  return `${label}. Stage ${index + 1} of ${TRACE_STAGE_COUNT}.`;
}

export interface ProgressWeights {
  readonly startFractions: Readonly<Record<string, number>>;
  readonly measured: boolean;
}

export const UNWEIGHTED: ProgressWeights = {
  startFractions: Object.fromEntries(TRACE_STAGES.map((stage, index) => [stage.id, index / TRACE_STAGE_COUNT])),
  measured: false
};

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

export function fractionAt(weights: ProgressWeights, stageId: string, engineFraction: number): number {
  const known = weights.startFractions[stageId];
  const value = typeof known === "number" ? known : engineFraction;
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}

export const PROGRESS_UNMEASURED_NOTE =
  "The bar counts stages, not time, until this browser has finished one trace.";
