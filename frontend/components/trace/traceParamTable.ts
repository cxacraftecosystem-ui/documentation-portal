/**
 * The tracing controls this application offers a researcher, as a table rather than as JSX.
 *
 * WHERE THE LIST CAME FROM. The upstream tracer this engine was vendored from builds its dock from
 * `web/src/state/paramSpecs.ts`, and this file is that table, re-expressed for this repository. The
 * labels, hints, ranges and group names are the upstream's own words, kept verbatim wherever they
 * still read correctly on a fieldwork handset, because somebody who has used the other application
 * should not have to learn a second vocabulary for the same slider.
 *
 * HOW MANY CONTROLS, COUNTED RATHER THAN REMEMBERED. Upstream declares sixteen sliders, nine toggles
 * and five choices — thirty controls in four groups. This table keeps ALL THIRTY and adds the two
 * sharpening sliders of difference 1 below, in five groups. The counts on both sides were produced by
 * running, in each directory:
 *
 *     awk '/^export const TOGGLES/,/^\];/' <file> | grep -cE "^\s+key: "
 *
 * **The number lives in `PARAM_COUNT` and nowhere else.** A total written into a comment is a second
 * register of one fact, and the upstream's own header carried one that was three out while the button
 * in the panel printed the real figure — a reader reconciling the two went hunting for three controls
 * that had never been dropped. If you find yourself writing a total into a comment, write the command
 * instead.
 *
 * WHAT IS DELIBERATELY DIFFERENT, AND WHY EACH DIFFERENCE EXISTS
 *
 *  1. **Sharpening is exposed here and is not exposed upstream.** `engine/params.ts` declares
 *     `preprocess.unsharpAmount` (0..5, default 0) and `preprocess.unsharpSigma` (0.05..32, default
 *     1.5), and `engine/pipeline.ts` runs them — `if (p.preprocess.unsharpAmount > 0) grey =
 *     Contrast.unsharpMask(grey, p.preprocess.unsharpSigma, p.preprocess.unsharpAmount)`. Upstream's
 *     own UI never offers them, so the capability ships in the engine and no user can reach it. A
 *     photograph taken in a workshop under one tube light is soft far more often than it is noisy, so
 *     the two controls get a group of their own.
 *  2. **`write` is a PATCH, not a call to `withOverrides`.** Upstream's specs call the engine's
 *     `withOverrides` directly, which is free there because the whole application is the tracer. Here
 *     a value import of `engine/params` from a table a page imports statically would put ~8 KB gzipped
 *     of engine source into that page's bundle — the exact thing `lib/trace/traceClient.ts`'s header
 *     forbids. So every entry returns a plain `TraceParamsInput` tree and the merge happens in
 *     {@link mergeParams}, which is pure and imports nothing at runtime. Every import in this file is
 *     `import type` and TypeScript erases all of them.
 *  3. **Choice values are plain strings cast to the engine's enum types.** The enums are string enums
 *     whose values equal their names, so `'FDOG'` IS `EdgeEngine.FDOG` at run time — but naming the
 *     enum as a *value* to say so would be the same static import point 2 removes. The cast is safe in
 *     the direction that matters: the worker runs `sanitizeTraceParams` on arrival, and its `enumOf`
 *     helper answers an unrecognised string with the documented default rather than throwing. A typo
 *     here would therefore be silent, which is why `e2e/trace-options-unit.spec.ts` round-trips every
 *     option value through the real sanitiser and fails on one that does not survive.
 *
 * THE GROUPING IS THE POINT, NOT DECORATION. Every control in one column is a wall, and a wall reads
 * as "this screen is for somebody else" — upstream says so in its own `ESSENTIAL_KEYS` comment and
 * leads with six. This table keeps that idea and keeps those six, because the reasoning behind them is
 * about the engine rather than about the other app's layout: each of the six changes the KIND of
 * drawing that comes out, while the rest tune a drawing the researcher already has.
 */

import type { TraceParams, TraceParamsInput } from "@/lib/trace/traceClient";

/**
 * The five enum-valued settings, typed by INDEXING `TraceParams` rather than by importing the enums.
 *
 * `lib/trace/README.md` §3 states the rule this obeys: "Import `traceClient.ts` and nothing else.
 * Outside this directory, `engine/` and `worker/` are named in exactly one file" — its own spec. But
 * `traceClient.ts` re-exports only `TraceParams` and `TraceParamsInput`, not `EdgeEngine` and its four
 * siblings, so a table that needs to write one of them appears to need a second import path.
 *
 * It does not. The enums are reachable THROUGH the type that is exported, and an indexed access says
 * exactly what is meant: whatever `edge.engine` is declared to hold. That keeps the engine unnamed
 * outside its directory, survives the enums being renamed upstream, and — being types — erases to
 * nothing. The values themselves are written as the plain strings they are; see the header.
 */
type EdgeEngineValue = TraceParams["edge"]["engine"];
type DenoiseModeValue = TraceParams["preprocess"]["denoise"];
type MatteModeValue = TraceParams["matte"]["mode"];
type VectorModeValue = TraceParams["output"]["vectorMode"];
type ThinningModeValue = TraceParams["cleanup"]["thinning"];

/* ────────────────────────────────────────────────────────────────────────────
 * Groups
 * ──────────────────────────────────────────────────────────────────────────── */

export const GROUP_SOURCE = "Source";
/** Not an upstream group. See this file's header, difference 1. */
export const GROUP_SHARPEN = "Sharpening";
export const GROUP_EDGES = "Edges";
export const GROUP_CLEANUP = "Cleanup";
export const GROUP_OUTPUT = "Output";

/**
 * Display order.
 *
 * Sharpening sits directly after Source because it IS a source-stage operation — the pipeline applies
 * the unsharp mask to the grey plane before any edge engine runs — and a researcher who has just told
 * the panel the photograph is soft should not have to scroll past the edge controls to say how soft.
 */
export const PARAM_GROUPS: readonly string[] = [
  GROUP_SOURCE,
  GROUP_SHARPEN,
  GROUP_EDGES,
  GROUP_CLEANUP,
  GROUP_OUTPUT
];

/* ────────────────────────────────────────────────────────────────────────────
 * Spec shapes
 * ──────────────────────────────────────────────────────────────────────────── */

export interface SliderSpec {
  readonly key: string;
  readonly label: string;
  /** One sentence on what raising it does — never a restatement of the label. */
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

/* ────────────────────────────────────────────────────────────────────────────
 * The table
 * ──────────────────────────────────────────────────────────────────────────── */

export const SLIDERS: readonly SliderSpec[] = [
  {
    key: "preprocess.workingLongEdge",
    label: "Trace resolution",
    hint: "The long edge the trace runs at. Higher resolves finer detail and costs time; the vector is scaled back to the source size either way.",
    group: GROUP_SOURCE,
    min: 256,
    // The engine allows 8192. This stops at 4096 for the reason upstream's dock stops there — asking
    // the pipeline to work above the resolution the pixels actually have only upsamples them — and for
    // one this repository adds: these are handsets, and a 4096 trace is already several seconds of a
    // worker thread on the phones this application is used from.
    max: 4096,
    step: 64,
    read: (p) => p.preprocess.workingLongEdge,
    patch: (v) => ({ preprocess: { workingLongEdge: Math.round(v) } })
  },
  {
    key: "preprocess.denoiseStrength",
    label: "Noise reduction",
    hint: "How hard the chosen filter smooths before edges are looked for. Too much erases the thinnest lines.",
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
    hint: "CLAHE clip limit. Higher pulls detail out of shadow and haze, and eventually amplifies grain with it.",
    group: GROUP_SOURCE,
    min: 1,
    max: 8,
    step: 0.1,
    read: (p) => p.preprocess.claheClip,
    patch: (v) => ({ preprocess: { claheClip: v } })
  },

  /* ── Sharpening: the two controls the upstream engine has and its UI never offered ── */
  {
    key: "preprocess.unsharpAmount",
    label: "Sharpen amount",
    hint: "How much of the difference between the photograph and a blurred copy of it is added back. 0 is off; past about 2 a pencil line grows a pale halo on both sides.",
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
    hint: "The width of detail sharpening acts on, in pixels. Small values crisp the hairlines; large ones lift broad tonal edges and leave the hairlines alone.",
    group: GROUP_SHARPEN,
    min: 0.05,
    // The engine's `Limits.MAX_SIGMA` is 32. A radius that large on a line drawing is not sharpening
    // any more — it is a local contrast boost with a halo the width of a finger — and the control that
    // does that deliberately is "Local contrast" three rows up. Stopping at 8 keeps this slider's
    // whole travel useful, which a 0.05–32 range would not.
    max: 8,
    step: 0.05,
    read: (p) => p.preprocess.unsharpSigma,
    patch: (v) => ({ preprocess: { unsharpSigma: v } })
  },

  {
    key: "edge.sensitivity",
    label: "Edge sensitivity",
    hint: "More ink as it rises. The one control that means the same thing in every edge engine.",
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
    hint: "Gaussian applied before the detector. Raise it to ignore texture, lower it to keep hairlines.",
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
    hint: "How far the flow-based engine follows a stroke before letting it end. Only affects the flow engine.",
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
    hint: "XDoG sharpness: 3 is soft graphite, 300 is a woodcut with no soft edge anywhere.",
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
    hint: "Ink blobs smaller than this many pixels are dust and are dropped.",
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
    hint: "Morphological closing radius: joins strokes that already nearly touch. Too large fuses neighbouring lines.",
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
    hint: "How far apart two stroke ends may be and still be joined. Ignored when gap bridging is off.",
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
    hint: "Skeleton branches shorter than this are removed. 0 keeps every whisker thinning produced.",
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
    hint: "Douglas–Peucker tolerance in working pixels. Fewer nodes, straighter runs; 0 keeps every traced vertex.",
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
    hint: "Higher keeps MORE corners: a vertex survives as a corner when its neighbours subtend a sharper angle than this.",
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
    hint: "Chaikin passes over the polyline before curve fitting. Each one rounds the geometry a little more.",
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
    hint: "Painted width of the exported line, in document units.",
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
    hint: "Paths shorter than this are dropped.",
    group: GROUP_OUTPUT,
    min: 0,
    max: 200,
    step: 0.5,
    read: (p) => p.output.minPathLength,
    patch: (v) => ({ output: { minPathLength: v } })
  }
];

/** Packed opaque white — how "a white background" is spelled in `output.background`. */
const OPAQUE_WHITE = 0xffffffff;

export const TOGGLES: readonly ToggleSpec[] = [
  {
    key: "preprocess.invertInput",
    label: "Source is light-on-dark",
    hint: "Set for chalkboards, negatives and white ink on black, so “ink” stays the dark class everywhere downstream.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.invertInput,
    patch: (v) => ({ preprocess: { invertInput: v } })
  },
  {
    key: "preprocess.claheEnabled",
    label: "Equalise local contrast",
    hint: "Turn off for a source that is already evenly lit; CLAHE will otherwise amplify its grain.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.claheEnabled,
    patch: (v) => ({ preprocess: { claheEnabled: v } })
  },
  {
    key: "preprocess.perspectiveCorrect",
    label: "Rectify the page",
    hint: "Look for a document quadrilateral and flatten to it. For photographs of paper taken at an angle.",
    group: GROUP_SOURCE,
    read: (p) => p.preprocess.perspectiveCorrect,
    patch: (v) => ({ preprocess: { perspectiveCorrect: v } })
  },
  {
    key: "cleanup.skeletonize",
    label: "Reduce ink to centrelines",
    hint: "Thins strokes to one pixel before tracing. Ignored in outline mode, where a skeleton would delete the boundary being traced.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.skeletonize,
    patch: (v) => ({ cleanup: { skeletonize: v } })
  },
  {
    key: "cleanup.bridgeGaps",
    label: "Bridge stroke ends",
    hint: "Join nearby stroke ends that point at each other. Off keeps a line that stopped, stopped.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.bridgeGaps,
    patch: (v) => ({ cleanup: { bridgeGaps: v } })
  },
  {
    key: "cleanup.removeBorderTouching",
    label: "Drop shapes touching the frame",
    hint: "Removes the drawing board, the scanner lid and the table edge, which a photograph almost always includes.",
    group: GROUP_CLEANUP,
    read: (p) => p.cleanup.removeBorderTouching,
    patch: (v) => ({ cleanup: { removeBorderTouching: v } })
  },
  {
    key: "output.modulateWidth",
    label: "Vary width with the stroke",
    hint: "Samples the distance transform per node so thick-and-thin brushwork survives. Emitted as a filled outline, because SVG has no variable-width stroke.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.modulateWidth,
    patch: (v) => ({ output: { modulateWidth: v } })
  },
  {
    key: "output.fillClosed",
    label: "Fill closed shapes",
    hint: "Paints every closed region solid instead of outlining it. Outline mode with fill is the woodcut look.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.fillClosed,
    patch: (v) => ({ output: { fillClosed: v } })
  },
  {
    key: "output.background",
    label: "White background",
    hint: "Off exports a transparent background.",
    group: GROUP_OUTPUT,
    read: (p) => p.output.background !== null,
    // `null` is a meaningful value here — it is how "transparent" is spelled — which is why this patch
    // cannot be expressed as a number.
    patch: (v) => ({ output: { background: v ? OPAQUE_WHITE : null } })
  }
];

export const CHOICES: readonly ChoiceSpec[] = [
  {
    key: "edge.engine",
    label: "Edge engine",
    hint: "Which detector runs. They fail differently, which is why all of them exist.",
    group: GROUP_EDGES,
    // MODEL is deliberately absent, and the reason is upstream's and still holds: it needs a
    // side-loaded edge model, none ships, and choosing it would silently fall back to another engine
    // plus a note. An option that cannot work is worse than no option.
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
    hint: "Bilateral keeps edges, median kills speckle, anisotropic flattens woven texture.",
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
    hint: "Separates the subject from its background. Never applied unless you choose it, and the pipeline reports how much of the frame it removed.",
    group: GROUP_SOURCE,
    // SUBJECT is absent from this list for the same reason MODEL is absent above, inverted: it is not
    // broken, it is what a subject preset selects on the researcher's behalf. Offering it twice would
    // let the two disagree about which one decided.
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
    hint: "Centreline turns one stroke into one open path; outline turns one region into one closed path. The most consequential choice here.",
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
    hint: "Guo–Hall keeps diagonals better and grows fewer spurs; Zhang–Suen is the classic.",
    group: GROUP_CLEANUP,
    options: [
      { value: "ZHANG_SUEN", label: "Zhang–Suen" },
      { value: "GUO_HALL", label: "Guo–Hall" }
    ],
    read: (p) => p.cleanup.thinning,
    patch: (v) => ({ cleanup: { thinning: v as ThinningModeValue } })
  }
];

/**
 * The controls the panel leads with.
 *
 * A SET, NOT AN ORDER. `TracePanel` renders group by group in `PARAM_GROUPS` order and, inside a
 * group, choices then sliders then toggles — so the collapsed view reads Trace resolution, Sharpen
 * amount, Edge sensitivity, Minimum speck, Vector mode, Simplify, Stroke width, which is not this
 * array's order and is not meant to be. Only {@link isEssential} consumes it, and it asks a
 * membership question. Grouping beats a flat ranking here for the same reason the full view is
 * grouped: "which stage of the pipeline is this" is how a researcher looks for a control.
 *
 * Upstream's six, unchanged, plus sharpen amount as a seventh — because a photograph taken in a
 * workshop under one tube light is soft far more often than it is noisy, and a control nobody can
 * find is the same as a control that does not exist. Everything else stays one press away behind
 * "Show more options", which is the panel's ONE disclosure and holds the download formats as well.
 * What makes a control essential is what it does to the drawing rather than where the panel puts it.
 */
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

/** True for a control the panel shows before the researcher asks for everything. */
export function isEssential(key: string): boolean {
  return ESSENTIAL_SET.has(key);
}

/** Total controls in the table. */
export const PARAM_COUNT = SLIDERS.length + TOGGLES.length + CHOICES.length;

/**
 * How many controls the disclosure actually reveals — the total minus the ones already on screen.
 *
 * THE NUMBER THE BUTTON PRINTS, AND UPSTREAM'S USED TO BE THE WRONG ONE. A button reading "Show all
 * 32 controls" while 7 of the 32 are already in front of the reader promises 32 and reveals 25. The
 * honest number — and the one a researcher can check by looking — counts the ones that are NOT on
 * screen.
 *
 * COUNTED OFF THE TABLE RATHER THAN OFF `ESSENTIAL_KEYS.length`, because those are two different
 * facts: the key list is what the panel ASKS for, and this is what the table HAS. An essential key
 * naming a control that no longer exists would leave the two disagreeing, and the disagreement would
 * surface as a button promising one more control than the press produces.
 */
export const ADVANCED_COUNT =
  SLIDERS.filter((spec) => !isEssential(spec.key)).length +
  TOGGLES.filter((spec) => !isEssential(spec.key)).length +
  CHOICES.filter((spec) => !isEssential(spec.key)).length;

/* ────────────────────────────────────────────────────────────────────────────
 * Applying a change
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Deep-merge a partial override onto a complete tree.
 *
 * THIS IS A COPY OF `engine/params.withOverrides`'s MERGE HALF, AND THE COPY IS DELIBERATE. That
 * function is `sanitizeTraceParams({ ...spread per section... })` — a merge followed by a sanitise.
 * Importing it here as a *value* is the static engine import this file's header exists to avoid, and
 * the sanitise half is already reachable asynchronously through `loadTraceParams()`. So the merge is
 * reproduced and the sanitise is applied by {@link applyParamPatch} using the engine's own function,
 * which means the authority on what is legal is still the engine and only the plumbing is local.
 *
 * A LOCAL COPY OF SOMEBODY ELSE'S RULE DRIFTS UNLESS SOMETHING WATCHES IT.
 * `e2e/trace-options-unit.spec.ts` compares this function's output against the real `withOverrides`
 * for every entry in the table, so the day the upstream adds a nested section this spread does not
 * know about, a spec fails rather than a setting silently stopping working.
 *
 * `edge.flow` is the one nested object in the tree and is spread explicitly for that reason.
 */
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

/**
 * Merge a patch onto the current parameters and hand the result to the engine's own sanitiser.
 *
 * `sanitize` comes from `loadTraceParams()`. Running it on every slider tick is intended rather than
 * wasteful: `engine/params.ts` documents the sanitiser as idempotent precisely so a UI can do this
 * without ever disagreeing with the pipeline about what "legal" means.
 */
export function applyParamPatch(
  base: TraceParams,
  over: TraceParamsInput,
  sanitize: (input: TraceParamsInput) => TraceParams
): TraceParams {
  return sanitize(mergeParams(base, over));
}

/* ────────────────────────────────────────────────────────────────────────────
 * Telling the researcher what changed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * @returns the labels of every control whose value differs between `a` and `b`.
 *
 * Two jobs, both of them a case of this repository's rule that work done on a researcher's behalf has
 * to be visible: ringing the rows a preset moved, and naming the hand-set values a newly chosen
 * preset overwrote. A preset that silently discards five minutes of tuning is the one failure a
 * preset list can have.
 */
export function changedLabels(a: TraceParams, b: TraceParams): string[] {
  const out: string[] = [];
  for (const s of SLIDERS) if (s.read(a) !== s.read(b)) out.push(s.label);
  for (const t of TOGGLES) if (t.read(a) !== t.read(b)) out.push(t.label);
  for (const c of CHOICES) if (c.read(a) !== c.read(b)) out.push(c.label);
  return out;
}

/**
 * @returns the labels of every changed control that is currently COLLAPSED.
 *
 * Progressive disclosure is only honest if what it hides can still announce itself. A style that
 * moved four advanced values must say so while those rows are folded away, or the panel is lying
 * about what the trace is doing.
 */
export function changedAdvancedLabels(a: TraceParams, b: TraceParams): string[] {
  return changedLabels(a, b).filter((label) => {
    const spec =
      SLIDERS.find((s) => s.label === label) ??
      TOGGLES.find((t) => t.label === label) ??
      CHOICES.find((c) => c.label === label);
    return spec !== undefined && !isEssential(spec.key);
  });
}

/**
 * @returns the value formatted for the numeric readout, with a decimal count derived from `step`, so
 *   an integer control never shows "12.00" and a 0.01 control never shows "0".
 */
export function formatValue(v: number, step: number): string {
  if (!Number.isFinite(v)) return "0";
  if (step >= 1) return String(Math.round(v));
  const decimals = step >= 0.1 ? 1 : 2;
  return v.toFixed(decimals);
}

/**
 * One sentence naming what a preset just overwrote, or null when it overwrote nothing.
 *
 * Null rather than an empty string so a caller cannot accidentally render an empty notice box.
 */
export function overwriteNotice(source: string, before: TraceParams, after: TraceParams): string | null {
  const overwritten = changedLabels(before, after);
  if (overwritten.length === 0) return null;
  const list = overwritten.join(", ");
  return overwritten.length === 1
    ? `${source} changed one setting: ${list}.`
    : `${source} changed ${overwritten.length} settings: ${list}.`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * A control that cannot do anything right now
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One sentence saying this control has no effect under the current settings, or null.
 *
 * ── THE TRAP THIS EXISTS FOR, WHICH IS NOT HYPOTHETICAL ───────────────────────────────────────
 *
 * `DenoiseMode.MEDIAN`'s branch in `engine/pipeline.ts` reads `preprocess.medianRadius` and never
 * `preprocess.denoiseStrength` — verified at `pipeline.ts:520-534`, where the MEDIAN arm calls
 * `Denoise.median(grey, p.preprocess.medianRadius)` and `strength` is read only by the other three.
 * MEDIAN is what the `sketch` subject selects. So a researcher can drag "Noise reduction" for a
 * minute on the commonest configuration this panel has, watch nothing change, and reasonably conclude
 * the trace is broken — and nothing anywhere said so.
 *
 * ── EVERY REASON WAS READ OFF `pipeline.ts`, NOT INFERRED FROM A LABEL ────────────────────────
 *
 * Each arm below names the condition in the engine that makes it true. That is the only way this can
 * be maintained: a sentence saying "this does nothing" is a strong claim, and one derived from what a
 * control's NAME suggests rather than from what the pipeline reads is a claim that will be wrong the
 * first time a stage is reordered.
 *
 * ── THE CONTROL STAYS DRAWN AND STAYS WRITABLE ────────────────────────────────────────────────
 *
 * A sentence, never a disabled row. Greying it out would stop a researcher setting a value for the
 * configuration they are about to switch to — which is exactly what somebody comparing two engines
 * does — and would say "you may not" where the truth is "not yet".
 *
 * ── WHY THERE IS NO "THE FLAG WAS NEVER SENT" ARM ─────────────────────────────────────────────
 *
 * The parameters here are a typed tree that `sanitizeTraceParams` has already completed, so every
 * leaf exists and "the flag is off" cannot be confused with "the flag is missing". A surface that
 * took a partial map would need that distinction — putting a confident sentence under a control on
 * the strength of a leaf that is not there is how a version skew becomes a lie — and this one does
 * not have to.
 */
export function inactiveReason(key: string, params: TraceParams): string | null {
  const engine = params.edge.engine;
  const outline = params.output.vectorMode === "OUTLINE";
  switch (key) {
    // `pipeline.ts:806-808` — blurSigma is passed only in the CANNY arm.
    case "edge.blurSigma":
      return engine === "CANNY" ? null : "Only the Canny engine reads this.";
    // `pipeline.ts:838-852` — the flow settings are read only in the default (FDOG) arm.
    case "edge.flow.sigmaM":
      return engine === "FDOG" ? null : "Only the Flow engine reads this.";
    // `pipeline.ts:817` and `:851` — XDoG and FDOG share xdogPhi; nothing else reads it.
    case "edge.xdogPhi":
      return engine === "XDOG" || engine === "FDOG"
        ? null
        : "Only the XDoG and Flow engines read this.";
    // `pipeline.ts:613` — `p.cleanup.skeletonize && !outlineMode`.
    case "cleanup.skeletonize":
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    // `pipeline.ts:619` — inside the skeletonize branch only, so BOTH conditions are reported.
    case "cleanup.pruneSpurs":
      if (!params.cleanup.skeletonize) return "“Reduce ink to centrelines” is off, so there is no skeleton to prune.";
      return outline ? "Outline mode traces the edge of a region, so nothing is thinned." : null;
    // `pipeline.ts:620` and `:631` — both branches require bridgeGaps.
    case "cleanup.maxGap":
      return params.cleanup.bridgeGaps ? null : "“Bridge stroke ends” is off.";
    // `pipeline.ts:540-545` — the CLAHE call is inside `if (p.preprocess.claheEnabled)`.
    case "preprocess.claheClip":
      return params.preprocess.claheEnabled ? null : "“Equalise local contrast” is off.";
    // `pipeline.ts:552-553` — the unsharp mask runs only when the amount is above zero.
    case "preprocess.unsharpSigma":
      return params.preprocess.unsharpAmount > 0 ? null : "“Sharpen amount” is 0.";
    // `pipeline.ts:520-534` — see this function's header. The MEDIAN case is the trap.
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
