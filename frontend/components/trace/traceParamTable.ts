import type { TraceParams, TraceParamsInput } from "@/lib/trace/traceClient";

type EdgeEngineValue = TraceParams["edge"]["engine"];
type DenoiseModeValue = TraceParams["preprocess"]["denoise"];
type MatteModeValue = TraceParams["matte"]["mode"];
type VectorModeValue = TraceParams["output"]["vectorMode"];
type ThinningModeValue = TraceParams["cleanup"]["thinning"];

export const GROUP_SOURCE = "Source";
export const GROUP_SHARPEN = "Sharpening";
export const GROUP_EDGES = "Edges";
export const GROUP_CLEANUP = "Cleanup";
export const GROUP_OUTPUT = "Output";

export const PARAM_GROUPS: readonly string[] = [
  GROUP_SOURCE,
  GROUP_SHARPEN,
  GROUP_EDGES,
  GROUP_CLEANUP,
  GROUP_OUTPUT
];

export interface SliderSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly min: number;
  readonly max: number;
  readonly step: number;
  readonly read: (p: TraceParams) => number;
  readonly patch: (v: number) => TraceParamsInput;
}

export interface ToggleSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly read: (p: TraceParams) => boolean;
  readonly patch: (v: boolean) => TraceParamsInput;
}

export interface ChoiceOption {
  readonly value: string;
  readonly label: string;
}

export interface ChoiceSpec {
  readonly key: string;
  readonly label: string;
  readonly hint: string;
  readonly group: string;
  readonly options: readonly ChoiceOption[];
  readonly read: (p: TraceParams) => string;
  readonly patch: (v: string) => TraceParamsInput;
}

export const SLIDERS: readonly SliderSpec[] = [
  {
    key: "preprocess.workingLongEdge",
    label: "Trace resolution",
    hint: "Higher catches finer detail but takes longer.",
    group: GROUP_SOURCE,
    min: 256,
    max: 4096,
    step: 64,
    read: (p) => p.preprocess.workingLongEdge,
    patch: (v) => ({ preprocess: { workingLongEdge: Math.round(v) } })
  },
  {
    key: "preprocess.denoiseStrength",
    label: "Noise reduction",
    hint: "Smooths grain first. Too much loses fine lines.",
    group: GROUP_SOURCE,
    min: 0,
    max: 1,
    step: 0.01,
    read: (p) => p.preprocess.denoiseStrength,
    patch: (v) => ({ preprocess: { denoiseStrength: v } })
  },
  {
    key: "preprocess.claheClip",
    label: "Local contrast",
    hint: "Brings out detail in shadow. Too much adds grain.",
    group: GROUP_SOURCE,
    min: 1,
    max: 8,
    step: 0.1,
    read: (p) => p.preprocess.claheClip,
    patch: (v) => ({ preprocess: { claheClip: v } })
  },

  {
    key: "preprocess.unsharpAmount",
    label: "Sharpen amount",
    hint: "0 is off. Above 2, pencil lines get a pale halo.",
    group: GROUP_SHARPEN,
    min: 0,
    max: 5,
    step: 0.05,
    read: (p) => p.preprocess.unsharpAmount,
    patch: (v) => ({ preprocess: { unsharpAmount: v } })
  },
  {
    key: "preprocess.unsharpSigma",
    label: "Sharpen radius",
    hint: "Small sharpens fine lines; large sharpens broad edges.",
    group: GROUP_SHARPEN,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.preprocess.unsharpSigma,
    patch: (v) => ({ preprocess: { unsharpSigma: v } })
  },

  {
    key: "edge.sensitivity",
    label: "Edge sensitivity",
    hint: "Higher picks up more ink.",
    group: GROUP_EDGES,
    min: 0,
    max: 1,
    step: 0.01,
    read: (p) => p.edge.sensitivity,
    patch: (v) => ({ edge: { sensitivity: v } })
  },
  {
    key: "edge.blurSigma",
    label: "Pre-blur",
    hint: "Raise to ignore texture; lower to keep fine lines.",
    group: GROUP_EDGES,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.edge.blurSigma,
    patch: (v) => ({ edge: { blurSigma: v } })
  },
  {
    key: "edge.flow.sigmaM",
    label: "Stroke length",
    hint: "How far a stroke is followed before it ends. Flow engine only.",
    group: GROUP_EDGES,
    min: 0.05,
    max: 12,
    step: 0.05,
    read: (p) => p.edge.flow.sigmaM,
    patch: (v) => ({ edge: { flow: { sigmaM: v } } })
  },
  {
    key: "edge.xdogPhi",
    label: "Edge hardness",
    hint: "Low is soft like graphite; high is crisp like a woodcut.",
    group: GROUP_EDGES,
    min: 0.1,
    max: 300,
    step: 0.1,
    read: (p) => p.edge.xdogPhi,
    patch: (v) => ({ edge: { xdogPhi: v } })
  },
  {
    key: "cleanup.minBlobArea",
    label: "Minimum speck",
    hint: "Specks smaller than this are dropped as dust.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 1000,
    step: 1,
    read: (p) => p.cleanup.minBlobArea,
    patch: (v) => ({ cleanup: { minBlobArea: Math.round(v) } })
  },
  {
    key: "cleanup.closeRadius",
    label: "Close gaps",
    hint: "Joins strokes that nearly touch. Too high merges lines.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 8,
    step: 1,
    read: (p) => p.cleanup.closeRadius,
    patch: (v) => ({ cleanup: { closeRadius: Math.round(v) } })
  },
  {
    key: "cleanup.maxGap",
    label: "Bridge reach",
    hint: "Largest gap between stroke ends that gets joined.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 64,
    step: 1,
    read: (p) => p.cleanup.maxGap,
    patch: (v) => ({ cleanup: { maxGap: Math.round(v) } })
  },
  {
    key: "cleanup.pruneSpurs",
    label: "Prune spurs",
    hint: "Removes short stray branches. 0 keeps them all.",
    group: GROUP_CLEANUP,
    min: 0,
    max: 32,
    step: 1,
    read: (p) => p.cleanup.pruneSpurs,
    patch: (v) => ({ cleanup: { pruneSpurs: Math.round(v) } })
  },
  {
    key: "output.simplify",
    label: "Simplify",
    hint: "Higher gives fewer points and straighter lines. 0 keeps every point.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 8,
    step: 0.1,
    read: (p) => p.output.simplify,
    patch: (v) => ({ output: { simplify: v } })
  },
  {
    key: "output.corner",
    label: "Keep corners",
    hint: "Higher keeps more sharp corners.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 180,
    step: 1,
    read: (p) => p.output.corner,
    patch: (v) => ({ output: { corner: v } })
  },
  {
    key: "output.smoothIterations",
    label: "Smoothing passes",
    hint: "Each pass rounds the lines a little more.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 8,
    step: 1,
    read: (p) => p.output.smoothIterations,
    patch: (v) => ({ output: { smoothIterations: Math.round(v) } })
  },
  {
    key: "output.strokeWidth",
    label: "Stroke width",
    hint: "Line width in the exported file.",
    group: GROUP_OUTPUT,
    min: 0.05,
    max: 8,
    step: 0.05,
    read: (p) => p.output.strokeWidth,
    patch: (v) => ({ output: { strokeWidth: v } })
  },
  {
    key: "output.minPathLength",
    label: "Minimum path",
    hint: "Shorter paths are dropped.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 200,
    step: 0.5,
    read: (p) => p.output.minPathLength,
    patch: (v) => ({ output: { minPathLength: v } })
  }
];

const OPAQUE_WHITE = 0xffffffff;

export const TOGGLES: readonly ToggleSpec[] = [
  {
    key: "preprocess.invertInput",
    label: "Source is light-on-dark",
    hint: "For chalkboards, negatives or white ink on black.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.invertInput,
    patch: (v) => ({ preprocess: { invertInput: v } })
  },
  {
    key: "preprocess.claheEnabled",
    label: "Equalise local contrast",
    hint: "Turn off for evenly lit sources to avoid extra grain.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.claheEnabled,
    patch: (v) => ({ preprocess: { claheEnabled: v } })
  },
  {
    key: "preprocess.perspectiveCorrect",
    label: "Rectify the page",
    hint: "Straightens a page photographed at an angle.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.perspectiveCorrect,
    patch: (v) => ({ preprocess: { perspectiveCorrect: v } })
  },
  {
    key: "cleanup.skeletonize",
    label: "Reduce ink to centrelines",
    hint: "Thins strokes to single lines. Not used in outline mode.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.skeletonize,
    patch: (v) => ({ cleanup: { skeletonize: v } })
  },
  {
    key: "cleanup.bridgeGaps",
    label: "Bridge stroke ends",
    hint: "Joins nearby stroke ends that point at each other.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.bridgeGaps,
    patch: (v) => ({ cleanup: { bridgeGaps: v } })
  },
  {
    key: "cleanup.removeBorderTouching",
    label: "Drop shapes touching the frame",
    hint: "Removes the board, table edge or scanner lid around the drawing.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.removeBorderTouching,
    patch: (v) => ({ cleanup: { removeBorderTouching: v } })
  },
  {
    key: "output.modulateWidth",
    label: "Vary width with the stroke",
    hint: "Keeps thick-and-thin brushwork. Exported as filled shapes.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.modulateWidth,
    patch: (v) => ({ output: { modulateWidth: v } })
  },
  {
    key: "output.fillClosed",
    label: "Fill closed shapes",
    hint: "Fills closed shapes solid instead of outlining them.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.fillClosed,
    patch: (v) => ({ output: { fillClosed: v } })
  },
  {
    key: "output.background",
    label: "White background",
    hint: "Off gives a transparent background.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.background !== null,
    patch: (v) => ({ output: { background: v ? OPAQUE_WHITE : null } })
  }
];

export const CHOICES: readonly ChoiceSpec[] = [
  {
    key: "edge.engine",
    label: "Edge engine",
    hint: "Each detector suits different drawings. Try another if lines go missing.",
    group: GROUP_EDGES,
    options: [
      { value: "FDOG", label: "Flow (long coherent strokes)" },
      { value: "XDOG", label: "XDoG (drawn look, tunable hardness)" },
      { value: "CANNY", label: "Canny (straight geometric edges)" },
      { value: "ADAPTIVE", label: "Adaptive threshold (already line art)" },
      { value: "LOG", label: "Laplacian (thinnest, most delicate)" }
    ],
    read: (p) => p.edge.engine,
    patch: (v) => ({ edge: { engine: v as EdgeEngineValue } })
  },
  {
    key: "preprocess.denoise",
    label: "Noise filter",
    hint: "Bilateral keeps edges, median removes speckle, anisotropic flattens texture.",
    group: GROUP_SOURCE,
    options: [
      { value: "NONE", label: "None" },
      { value: "BILATERAL", label: "Bilateral" },
      { value: "MEDIAN", label: "Median" },
      { value: "ANISOTROPIC", label: "Anisotropic" }
    ],
    read: (p) => p.preprocess.denoise,
    patch: (v) => ({ preprocess: { denoise: v as DenoiseModeValue } })
  },
  {
    key: "matte.mode",
    label: "Background matte",
    hint: "Removes the background around the subject. Off unless you choose it.",
    group: GROUP_SOURCE,
    options: [
      { value: "NONE", label: "None" },
      { value: "BORDER_FLOOD", label: "Flood from the border" },
      { value: "SALIENCY", label: "Keep the salient subject" }
    ],
    read: (p) => p.matte.mode,
    patch: (v) => ({ matte: { mode: v as MatteModeValue } })
  },
  {
    key: "output.vectorMode",
    label: "Vector mode",
    hint: "Centreline gives one path per stroke; outline gives one closed path per shape.",
    group: GROUP_OUTPUT,
    options: [
      { value: "CENTERLINE", label: "Centreline (one stroke, one path)" },
      { value: "OUTLINE", label: "Outline (one region, one closed path)" }
    ],
    read: (p) => p.output.vectorMode,
    patch: (v) => ({ output: { vectorMode: v as VectorModeValue } })
  },
  {
    key: "cleanup.thinning",
    label: "Thinning kernel",
    hint: "Guo–Hall keeps diagonals cleaner; Zhang–Suen is the classic.",
    group: GROUP_CLEANUP,
    options: [
      { value: "ZHANG_SUEN", label: "Zhang–Suen" },
      { value: "GUO_HALL", label: "Guo–Hall" }
    ],
    read: (p) => p.cleanup.thinning,
    patch: (v) => ({ cleanup: { thinning: v as ThinningModeValue } })
  }
];

export const ESSENTIAL_KEYS: readonly string[] = [
  "output.vectorMode",
  "edge.sensitivity",
  "preprocess.unsharpAmount",
  "cleanup.minBlobArea",
  "output.simplify",
  "output.strokeWidth",
  "preprocess.workingLongEdge"
];

const ESSENTIAL_SET = new Set(ESSENTIAL_KEYS);

export function isEssential(key: string): boolean {
  return ESSENTIAL_SET.has(key);
}

export const PARAM_COUNT = SLIDERS.length + TOGGLES.length + CHOICES.length;

export const ADVANCED_COUNT =
  SLIDERS.filter((spec) => !isEssential(spec.key)).length +
  TOGGLES.filter((spec) => !isEssential(spec.key)).length +
  CHOICES.filter((spec) => !isEssential(spec.key)).length;

export function mergeParams(base: TraceParams, over: TraceParamsInput): TraceParamsInput {
  return {
    preprocess: { ...base.preprocess, ...over.preprocess },
    matte: { ...base.matte, ...over.matte },
    edge: { ...base.edge, ...over.edge, flow: { ...base.edge.flow, ...over.edge?.flow } },
    cleanup: { ...base.cleanup, ...over.cleanup },
    output: { ...base.output, ...over.output },
    auto: { ...base.auto, ...over.auto },
    styleId: over.styleId ?? base.styleId
  };
}

export function applyParamPatch(
  base: TraceParams,
  over: TraceParamsInput,
  sanitize: (input: TraceParamsInput) => TraceParams
): TraceParams {
  return sanitize(mergeParams(base, over));
}

export function changedLabels(a: TraceParams, b: TraceParams): string[] {
  const out: string[] = [];
  for (const s of SLIDERS) if (s.read(a) !== s.read(b)) out.push(s.label);
  for (const t of TOGGLES) if (t.read(a) !== t.read(b)) out.push(t.label);
  for (const c of CHOICES) if (c.read(a) !== c.read(b)) out.push(c.label);
  return out;
}

export function changedAdvancedLabels(a: TraceParams, b: TraceParams): string[] {
  return changedLabels(a, b).filter((label) => {
    const spec =
      SLIDERS.find((s) => s.label === label) ??
      TOGGLES.find((t) => t.label === label) ??
      CHOICES.find((c) => c.label === label);
    return spec !== undefined && !isEssential(spec.key);
  });
}

export function formatValue(v: number, step: number): string {
  if (!Number.isFinite(v)) return "0";
  if (step >= 1) return String(Math.round(v));
  const decimals = step >= 0.1 ? 1 : 2;
  return v.toFixed(decimals);
}

export function overwriteNotice(source: string, before: TraceParams, after: TraceParams): string | null {
  const overwritten = changedLabels(before, after);
  if (overwritten.length === 0) return null;
  const list = overwritten.join(", ");
  return overwritten.length === 1
    ? `${source} changed one setting: ${list}.`
    : `${source} changed ${overwritten.length} settings: ${list}.`;
}

export function inactiveReason(key: string, params: TraceParams): string | null {
  const engine = params.edge.engine;
  const outline = params.output.vectorMode === "OUTLINE";
  switch (key) {
    case "edge.blurSigma":
      return engine === "CANNY" ? null : "Only the Canny engine reads this.";
    case "edge.flow.sigmaM":
      return engine === "FDOG" ? null : "Only the Flow engine reads this.";
    case "edge.xdogPhi":
      return engine === "XDOG" || engine === "FDOG"
        ? null
        : "Only the XDoG and Flow engines read this.";
    case "cleanup.skeletonize":
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    case "cleanup.pruneSpurs":
      if (!params.cleanup.skeletonize) return "“Reduce ink to centrelines” is off, so there is no skeleton to prune.";
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    case "cleanup.maxGap":
      return params.cleanup.bridgeGaps ? null : "“Bridge stroke ends” is off.";
    case "preprocess.claheClip":
      return params.preprocess.claheEnabled ? null : "“Equalise local contrast” is off.";
    case "preprocess.unsharpSigma":
      return params.preprocess.unsharpAmount > 0 ? null : "“Sharpen amount” is 0.";
    case "preprocess.denoiseStrength":
      if (params.preprocess.denoise === "NONE") return "The noise filter is set to None.";
      if (params.preprocess.denoise === "MEDIAN") {
        return "The median filter works from a fixed radius the panel does not expose, not from this.";
      }
      return null;
    default:
      return null;
  }
}
