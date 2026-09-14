/**
 * Every knob the engine exposes, as one JSON-safe tree, plus the single clamping choke point the
 * whole pipeline depends on. Mirrors `com.offlinetracer.pipeline.Params`.
 *
 * Parameters arrive from three sources that cannot be trusted to agree with each other:
 *
 *  1. a project saved by an **older build**, whose JSON may be missing fields entirely or may carry a
 *     value that was legal then and is not now;
 *  2. a **state restore** (a reloaded tab, a shared URL, an undo entry) that was never validated;
 *  3. a **slider mid-drag**, which streams every intermediate value including the ones at the ends of
 *     its travel.
 *
 * {@link sanitizeTraceParams} clamps all of it into the legal range in one place. That is why no stage
 * defends itself: a stage that re-checks its own inputs is a stage where the *real* clamping rule is
 * invisible, and two stages will eventually disagree about what "legal" means.
 *
 * The params are plain `interface`s and the sanitiser is a free function rather than a method, because
 * these values round-trip through `JSON.parse` on every project load — and `JSON.parse` cannot produce
 * an instance of a class, so a `params.sanitized()` call site would throw on exactly the input that
 * needs sanitising most.
 */

/** Which edge engine runs. `MODEL` requires a side-loaded edge model, which none ship. */
export enum EdgeEngine {
  CANNY = 'CANNY',
  XDOG = 'XDOG',
  FDOG = 'FDOG',
  ADAPTIVE = 'ADAPTIVE',
  LOG = 'LOG',
  MODEL = 'MODEL',
}

/** Which noise filter runs before contrast enhancement. They fail differently, so all four exist. */
export enum DenoiseMode {
  NONE = 'NONE',
  BILATERAL = 'BILATERAL',
  MEDIAN = 'MEDIAN',
  ANISOTROPIC = 'ANISOTROPIC',
}

/**
 * Which background matte is offered. Never applied without the user having chosen it.
 *
 * `SUBJECT` is the one that can refuse. The other two always hand back an alpha — a bare flood or a
 * bare saliency cut has no way to say "I do not believe this", so a caller applying one is acting on
 * a measurement with no error bar. `SUBJECT` fuses the flood with two further cues and returns a
 * confidence with it (`matte.MatteResult`), and the matte stage applies it **only** when that
 * confidence clears `matte.MIN_CONFIDENCE`. That is why it is the mode auto-detection and the object
 * subjects choose: it is the only one whose failure mode is "the background stayed" rather than "the
 * artwork went".
 *
 * Appended rather than inserted: the enum is stored by its string value, so a project saved before
 * this entry existed still decodes.
 */
export enum MatteMode {
  NONE = 'NONE',
  BORDER_FLOOD = 'BORDER_FLOOD',
  SALIENCY = 'SALIENCY',
  SUBJECT = 'SUBJECT',
}

/**
 * What auto-detection is allowed to do with what it found.
 *
 * Three states rather than a boolean, because "off" and "look but do not touch" are genuinely
 * different products and a user who has been surprised once will want the middle one.
 *
 * - `OFF` does not even classify. Nothing is measured and `TraceResult.profile` is null.
 * - `SUGGEST` classifies and writes what it *would* have changed into `TraceResult.notes`, changing
 *   nothing. This is ALGORITHMS §12's original contract — a named suggestion with a one-tap override.
 * - `APPLY` applies the detected subject, subject to every guard in {@link module:subjects}'s `Auto`.
 */
export enum AutoMode {
  OFF = 'OFF',
  SUGGEST = 'SUGGEST',
  APPLY = 'APPLY',
}

/** Which skeletonisation kernel runs. Guo–Hall keeps diagonals better and grows fewer spurs. */
export enum ThinningMode {
  ZHANG_SUEN = 'ZHANG_SUEN',
  GUO_HALL = 'GUO_HALL',
}

/**
 * Mirror of `Vectorize.VectorMode` that lives in the serialisable tree.
 *
 * A separate enum on purpose: the vector module is not a serialisation module, and making the on-disk
 * format depend on an enum declared outside this file would mean a rename over there silently
 * invalidates every saved project.
 */
export enum VectorModeParam {
  CENTERLINE = 'CENTERLINE',
  OUTLINE = 'OUTLINE',
}

/** Named bounds, so a limit is stated exactly once. */
export const Limits = {
  /**
   * Below 256 px the working image cannot hold a legible line; above 8192 px nothing on a phone can
   * allocate the six float buffers a trace needs at once.
   */
  MIN_WORKING_EDGE: 256,
  MAX_WORKING_EDGE: 8192,

  /**
   * The smallest sigma with any meaning: `gaussianBlur` treats sigma <= 0.05 as an identity copy, so
   * clamping up to it keeps "no blur" reachable while satisfying the sigma > 0 requirement. A sigma of
   * exactly 0 would be a legal-looking value that some future kernel divides by.
   */
  MIN_SIGMA: 0.05,
  MAX_SIGMA: 32,

  /** Radii are in working pixels; 256 already spans an eighth of the largest working image. */
  MAX_RADIUS: 256,

  /** Areas are pixel counts, bounded by the largest working image (8192² ≈ 6.7e7). */
  MAX_AREA: 67108864,

  /** Angles in this module are always unsigned turn angles in degrees. */
  MAX_ANGLE: 180,

  /** Path lengths and simplification tolerances are in working pixels. */
  MAX_LENGTH: 8192,

  /** The style a blank or unknown id falls back to. */
  DEFAULT_STYLE_ID: 'clean-line',
} as const;

/** The one canonical "derive the thresholds from the image" value for `cannyLow`/`cannyHigh`. */
export const AUTO_THRESHOLD = -1;

/**
 * The XDoG/FDoG ink level, and the single most consequential number in this file.
 *
 * `epsilon` is an *intensity* (ALGORITHMS.md §7.2): the soft threshold is monotone in `u`, and the
 * pipeline binarises the density immediately, so the ink mask is exactly the level set
 * `u <= epsilon + atanh(inkThreshold - 1) / phi`. A flat region of intensity `I` answers `u = I`, so
 * anything darker than that level is inked *as tone*, whether or not it contains an edge.
 *
 * At the previous 0.5 the level sat at 0.47, which is above the body of almost every photographed
 * object: measured on a shaded terracotta pot, 18-66% of the smooth body came back as ink, the
 * skeleton of that solid region shattered into thousands of 5-20 px fragments, and the real
 * silhouette was *lost* because a skeleton runs down the middle of a filled region rather than along
 * its edge. Recall of the true silhouette was 29%, of the painted bands 3%.
 *
 * 0.08 puts the level at 0.05, below any tone a photograph of a lit object contains and far above
 * the deeply negative response a genuine edge produces. Same subject, same everything else: 2770
 * shapes to 193, 426 kB to 30 kB, silhouette recall 29% to 93%, band recall 3% to 85%. The tone
 * response is not lost, only moved to where it belongs — a genuinely near-black region still fills,
 * which is what `comic` and `woodcut` raise it again for.
 */
const XDOG_EPSILON_DEFAULT = 0.08;

/**
 * Clamps `v` into `[lo, hi]`, mapping anything non-numeric or non-finite to `fallback`.
 *
 * NaN needs its own branch because every comparison against it is false, so the plain
 * `v < lo ? lo : v > hi ? hi : v` form returns NaN unchanged — and a NaN sigma turns an entire image
 * into NaN two stages later, where it is unattributable.
 */
function f(v: unknown, lo: number, hi: number, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return v < lo ? lo : v > hi ? hi : v;
}

/** Integer form of {@link f}; truncates toward zero before clamping. */
function i(v: unknown, lo: number, hi: number, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  const t = Math.trunc(v);
  return t < lo ? lo : t > hi ? hi : t;
}

function bool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

/** An unrecognised enum string falls back rather than propagating into a `switch` with no arm. */
function enumOf<T extends Record<string, string>>(
  table: T,
  v: unknown,
  fallback: T[keyof T],
): T[keyof T] {
  if (typeof v === 'string' && Object.prototype.hasOwnProperty.call(table, v)) {
    return table[v] as T[keyof T];
  }
  return fallback;
}

/** A packed ARGB colour; anything not an integer in range falls back. */
function colour(v: unknown, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return Math.trunc(v) >>> 0;
}

/** `null` is a meaningful value here — it is how "transparent" and "no override" are spelled. */
function nullableColour(v: unknown, fallback: number | null): number | null {
  if (v === null) return null;
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return Math.trunc(v) >>> 0;
}

function text(v: unknown, fallback: string): string {
  return typeof v === 'string' ? v.trim() : fallback;
}

/**
 * Everything that happens to the pixels before an edge engine sees them.
 *
 * @property autoOrient honour the source's recorded orientation. Browser decoders have already applied
 *   EXIF by the time an `RgbaImage` exists, so this is a hook for layers that have not.
 * @property perspectiveCorrect look for a document quadrilateral and rectify to it.
 * @property workingLongEdge the long edge the trace runs at, 256..8192. The vector result is scaled
 *   back to source size afterwards, so this trades time for detail and never for output resolution.
 * @property denoiseStrength 0..1, mapped to each filter's own units by the pipeline.
 * @property medianRadius radius in pixels for `MEDIAN`; 0 disables the filter.
 * @property invertInput set for white-on-black sources, so "ink" stays the dark class everywhere else.
 */
export interface PreprocessParams {
  readonly autoOrient: boolean;
  readonly perspectiveCorrect: boolean;
  readonly workingLongEdge: number;
  readonly denoise: DenoiseMode;
  readonly denoiseStrength: number;
  readonly medianRadius: number;
  readonly claheEnabled: boolean;
  readonly claheClip: number;
  readonly claheTiles: number;
  readonly brightness: number;
  readonly contrast: number;
  readonly gamma: number;
  readonly unsharpAmount: number;
  readonly unsharpSigma: number;
  readonly invertInput: boolean;
}

/**
 * Background separation. Every matte is a suggestion the user accepts, never a silent edit — a wrong
 * matte deleting half of somebody's artwork is the worst failure this app can have, so the pipeline
 * also states in `TraceResult.notes` how much of the frame a matte removed.
 *
 * **The default is `NONE` and stays there.** A library default that removed backgrounds would make
 * the engine's contract "these are your pixels unless a matte disagrees", and the one caller that
 * cannot see the result — a batch of two hundred files — is the one that would find out last.
 * Turning it on is a decision, made in exactly three places: the user's own picker, an object subject
 * preset (`subjects.ts`), and `autoDecide` when the classifier measured a separable subject.
 *
 * @property tolerance 0..1, scaled to a ΔE76 distance by the matte stage. Read by `BORDER_FLOOD` and
 *   by `SUBJECT`'s connectivity cue.
 * @property feather Gaussian sigma in pixels applied to the alpha edge, 0..32. For `SUBJECT` this is
 *   softening *on top of* the guided filter, which has already put the transition on the object's own
 *   edge, so a small value is normally right there and 0 is not a mistake.
 * @property threshold 0..1 saliency cut for `SALIENCY`; unread by every other mode.
 */
export interface MatteParams {
  readonly mode: MatteMode;
  readonly tolerance: number;
  readonly feather: number;
  readonly threshold: number;
}

/**
 * The flow-field knobs for `FDOG`, mirroring the imaging module's `FlowParams` minus the two
 * soft-threshold terms, which live on {@link EdgeParams} so XDoG and FDoG share one epsilon/phi pair
 * in the UI.
 *
 * @property sigmaM the "how long is a stroke" knob: the Gaussian along the flow.
 */
export interface FlowSettings {
  readonly tensorSigma: number;
  readonly etfIterations: number;
  readonly etfRadius: number;
  readonly sigmaC: number;
  readonly sigmaM: number;
  readonly tau: number;
  readonly fdogIterations: number;
}

/**
 * Which edge engine runs and how it is tuned.
 *
 * @property sensitivity 0..1, the one control that means the same thing in every engine: more ink as
 *   it rises. Each engine maps it to its own units.
 * @property cannyLow explicit hysteresis threshold in 0..1. **Negative means auto** — the sentinel is
 *   preserved by sanitisation as exactly -1 so "auto" has a single representation and a saved project
 *   cannot come back with a -0.3 that reads as auto in one build and as 0 in another.
 * @property dogTau how much of the coarse Gaussian is subtracted, 0..0.999.
 * @property xdogEpsilon the ink level as an intensity in the same 0..1 space as the input, -2..2.
 *   Anything darker than it is inked as *tone*, edge or no edge — see {@link XDOG_EPSILON_DEFAULT}.
 * @property xdogPhi soft-threshold sharpness, 0.1..500: large is hard technical line work, small is
 *   pencil-like. It shifts the ink level by `atanh(inkThreshold - 1) / phi` and, because the density
 *   is binarised straight afterwards, that shift is the *whole* of its effect on the mask.
 * @property adaptiveC offset subtracted from the local mean, -1..1.
 * @property modelId id of a registered edge model; only read for `MODEL`, and an id that is not
 *   registered produces a note, not a failure.
 */
export interface EdgeParams {
  readonly engine: EdgeEngine;
  readonly sensitivity: number;
  readonly blurSigma: number;
  readonly cannyLow: number;
  readonly cannyHigh: number;
  readonly dogSigma: number;
  readonly dogK: number;
  readonly dogTau: number;
  readonly xdogEpsilon: number;
  readonly xdogPhi: number;
  readonly flow: FlowSettings;
  readonly adaptiveRadius: number;
  readonly adaptiveC: number;
  readonly useSauvola: boolean;
  readonly logSigma: number;
  readonly logSlope: number;
  readonly modelId: string;
}

/**
 * Binary cleanup: what is removed, what is joined, and whether the ink is reduced to centrelines.
 *
 * @property minBlobArea components smaller than this many pixels are dust and are dropped. The count
 *   dropped is always reported in `TraceResult.notes`.
 * @property closeRadius morphological closing radius — joins gaps of roughly `2r` that already nearly
 *   touch. Endpoint bridging handles the rest.
 * @property maxGap endpoint-bridging reach in pixels; `bridgeGaps` off ignores it.
 * @property skeletonize reduce ink to one-pixel centrelines. Ignored in outline mode, where the region
 *   boundary is the thing being traced and a skeleton would delete it.
 * @property pruneSpurs branches shorter than this many pixels are removed; 0 disables pruning.
 * @property keepLargest keep only the N largest components; **0 means disabled**, not "keep none".
 */
export interface CleanupParams {
  readonly minBlobArea: number;
  readonly removeIsolated: boolean;
  readonly closeRadius: number;
  readonly openRadius: number;
  readonly bridgeGaps: boolean;
  readonly maxGap: number;
  readonly maxBridgeAngle: number;
  readonly skeletonize: boolean;
  readonly thinning: ThinningMode;
  readonly pruneSpurs: number;
  readonly fillHolesUpTo: number;
  readonly keepLargest: number;
  readonly removeBorderTouching: boolean;
}

/**
 * How the cleaned mask becomes geometry, and how that geometry is painted.
 *
 * @property vectorMode centreline (one stroke becomes one open path) or outline (one region becomes one
 *   closed path). This is the most consequential choice in the app.
 * @property simplify Douglas–Peucker tolerance in working pixels; 0 keeps every traced vertex.
 * @property corner corner threshold in degrees, 0..180. **Higher keeps more corners**: a vertex is a
 *   corner when the angle its neighbours subtend is *sharper* than this, so 140° protects gentle bends
 *   and 70° protects only spikes.
 * @property minPathLength paths shorter than this are dropped, and the count dropped is always
 *   reported in `TraceResult.notes`.
 * @property modulateWidth sample the distance transform per vertex so the SVG reproduces
 *   thick-and-thin brushwork. Emitted as a filled outline, because SVG has no variable-width stroke.
 * @property background `null` means transparent.
 */
export interface OutputParams {
  readonly vectorMode: VectorModeParam;
  readonly simplify: number;
  readonly fitError: number;
  readonly corner: number;
  readonly smoothIterations: number;
  readonly strokeWidth: number;
  readonly modulateWidth: boolean;
  readonly widthScale: number;
  readonly minPathLength: number;
  readonly strokeColor: number;
  readonly background: number | null;
  readonly fillClosed: boolean;
}

/**
 * Automatic source detection: whether it runs, what it may change, and what it must leave alone.
 *
 * ### Why the caller has to name the knobs it owns
 *
 * `handTuned` carries the knobs the user has moved by hand, and auto restores every one of them after
 * applying whatever it detected. The alternative — auto inferring "the user probably meant that" by
 * diffing against the style preset — cannot work, because a knob that happens to equal its preset
 * value is indistinguishable from one the user deliberately set *back* to it. Only the UI knows, so
 * only the UI can say.
 *
 * The names are the editor's visible labels rather than field paths, on purpose: the sentence auto
 * writes into `TraceResult.notes` has to name the control the user can see.
 *
 * @property mode see {@link AutoMode}.
 * @property subjectId a subject the user chose by hand. **Non-empty disables the automatic choice
 *   entirely** — the material is the one thing auto-detection decides, and a user who has already
 *   decided it is not asking.
 * @property handTuned knob labels ({@link Knobs}) auto must restore after applying anything.
 * @property minConfidence the classifier confidence floor below which auto does nothing and says so.
 *   0.55 is a hair above the value a two-way tie can reach (0.55·top with a zero margin), so an
 *   ambiguous frame is always refused.
 * @property allowMatte whether a measured separable subject may switch {@link MatteMode.SUBJECT} on.
 *   It is also the switch that takes a matte **away**: on the automatic path the profile's
 *   measurement of this frame outranks the subject preset's prior about the material, so a preset
 *   that asked for a matte gets none when the classifier found no background to remove.
 * @property allowCrop whether a confident subject box may crop the working image to the subject.
 */
export interface AutoParams {
  readonly mode: AutoMode;
  readonly subjectId: string;
  readonly handTuned: readonly string[];
  readonly minConfidence: number;
  readonly allowMatte: boolean;
  readonly allowCrop: boolean;
}

/**
 * The complete parameter tree for one trace.
 *
 * @property styleId the preset these values came from, carried so the UI can say "you have modified
 *   Pencil sketch" rather than showing an anonymous pile of sliders.
 */
export interface TraceParams {
  readonly preprocess: PreprocessParams;
  readonly matte: MatteParams;
  readonly edge: EdgeParams;
  readonly cleanup: CleanupParams;
  readonly output: OutputParams;
  readonly auto: AutoParams;
  readonly styleId: string;
}

/** Every field optional and every nested tree partial: the shape a restored project actually has. */
export type PreprocessParamsInput = Partial<PreprocessParams>;
export type MatteParamsInput = Partial<MatteParams>;
export type FlowSettingsInput = Partial<FlowSettings>;
export type EdgeParamsInput = Partial<Omit<EdgeParams, 'flow'>> & { flow?: FlowSettingsInput };
export type CleanupParamsInput = Partial<CleanupParams>;
export type OutputParamsInput = Partial<OutputParams>;
export type AutoParamsInput = Partial<AutoParams>;
export interface TraceParamsInput {
  preprocess?: PreprocessParamsInput;
  matte?: MatteParamsInput;
  edge?: EdgeParamsInput;
  cleanup?: CleanupParamsInput;
  output?: OutputParamsInput;
  auto?: AutoParamsInput;
  styleId?: string;
}

/** @returns the documented defaults; every field present, every value already legal. */
export function defaultPreprocessParams(): PreprocessParams {
  return {
    autoOrient: true,
    perspectiveCorrect: false,
    workingLongEdge: 2048,
    denoise: DenoiseMode.BILATERAL,
    denoiseStrength: 0.5,
    medianRadius: 1,
    claheEnabled: true,
    claheClip: 2,
    claheTiles: 8,
    brightness: 0,
    contrast: 0,
    gamma: 1,
    unsharpAmount: 0,
    unsharpSigma: 1.5,
    invertInput: false,
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizePreprocessParams(p: PreprocessParamsInput = {}): PreprocessParams {
  return {
    autoOrient: bool(p.autoOrient, true),
    perspectiveCorrect: bool(p.perspectiveCorrect, false),
    workingLongEdge: i(p.workingLongEdge, Limits.MIN_WORKING_EDGE, Limits.MAX_WORKING_EDGE, 2048),
    denoise: enumOf(DenoiseMode, p.denoise, DenoiseMode.BILATERAL),
    denoiseStrength: f(p.denoiseStrength, 0, 1, 0.5),
    medianRadius: i(p.medianRadius, 0, 32, 1),
    claheEnabled: bool(p.claheEnabled, true),
    claheClip: f(p.claheClip, 1, 16, 2),
    claheTiles: i(p.claheTiles, 1, 64, 8),
    brightness: f(p.brightness, -1, 1, 0),
    contrast: f(p.contrast, -1, 1, 0),
    gamma: f(p.gamma, 0.05, 10, 1),
    unsharpAmount: f(p.unsharpAmount, 0, 5, 0),
    unsharpSigma: f(p.unsharpSigma, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 1.5),
    invertInput: bool(p.invertInput, false),
  };
}

/** @returns the documented defaults. */
export function defaultMatteParams(): MatteParams {
  return { mode: MatteMode.NONE, tolerance: 0.18, feather: 1.5, threshold: 0.5 };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeMatteParams(p: MatteParamsInput = {}): MatteParams {
  return {
    mode: enumOf(MatteMode, p.mode, MatteMode.NONE),
    tolerance: f(p.tolerance, 0, 1, 0.18),
    feather: f(p.feather, 0, Limits.MAX_SIGMA, 1.5),
    threshold: f(p.threshold, 0, 1, 0.5),
  };
}

/** @returns the documented defaults, matching the reference paper. */
export function defaultFlowSettings(): FlowSettings {
  return {
    tensorSigma: 2,
    etfIterations: 3,
    etfRadius: 5,
    sigmaC: 1,
    sigmaM: 3,
    tau: 0.99,
    fdogIterations: 3,
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeFlowSettings(p: FlowSettingsInput = {}): FlowSettings {
  return {
    tensorSigma: f(p.tensorSigma, Limits.MIN_SIGMA, 16, 2),
    etfIterations: i(p.etfIterations, 0, 16, 3),
    etfRadius: i(p.etfRadius, 1, 32, 5),
    sigmaC: f(p.sigmaC, Limits.MIN_SIGMA, 16, 1),
    sigmaM: f(p.sigmaM, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 3),
    // tau >= 1 inverts the DC term of the difference of Gaussians and 1/(1-tau) explodes.
    tau: f(p.tau, 0, 0.999, 0.99),
    fdogIterations: i(p.fdogIterations, 1, 16, 3),
  };
}

/** @returns the documented defaults. */
export function defaultEdgeParams(): EdgeParams {
  return {
    engine: EdgeEngine.FDOG,
    sensitivity: 0.5,
    blurSigma: 1.2,
    cannyLow: AUTO_THRESHOLD,
    cannyHigh: AUTO_THRESHOLD,
    dogSigma: 1,
    dogK: 1.6,
    dogTau: 0.98,
    xdogEpsilon: XDOG_EPSILON_DEFAULT,
    xdogPhi: 20,
    flow: defaultFlowSettings(),
    adaptiveRadius: 15,
    adaptiveC: 0.02,
    useSauvola: true,
    logSigma: 1.4,
    logSlope: 0.02,
    modelId: '',
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeEdgeParams(p: EdgeParamsInput = {}): EdgeParams {
  const rawLo = p.cannyLow;
  const rawHi = p.cannyHigh;
  let lo =
    typeof rawLo !== 'number' || !Number.isFinite(rawLo) || rawLo < 0
      ? AUTO_THRESHOLD
      : f(rawLo, 0, 1, AUTO_THRESHOLD);
  let hi =
    typeof rawHi !== 'number' || !Number.isFinite(rawHi) || rawHi < 0
      ? AUTO_THRESHOLD
      : f(rawHi, 0, 1, AUTO_THRESHOLD);
  // Normalising the order here rather than in the detector keeps "which is the high one" a property of
  // the parameters, so the UI and the engine cannot disagree about it.
  if (lo >= 0 && hi >= 0 && lo > hi) {
    const t = lo;
    lo = hi;
    hi = t;
  }
  return {
    engine: enumOf(EdgeEngine, p.engine, EdgeEngine.FDOG),
    sensitivity: f(p.sensitivity, 0, 1, 0.5),
    blurSigma: f(p.blurSigma, Limits.MIN_SIGMA, 16, 1.2),
    cannyLow: lo,
    cannyHigh: hi,
    dogSigma: f(p.dogSigma, Limits.MIN_SIGMA, 16, 1),
    // k <= 1 makes the "coarse" Gaussian the finer of the two and the difference changes sign.
    dogK: f(p.dogK, 1.05, 8, 1.6),
    dogTau: f(p.dogTau, 0, 0.999, 0.98),
    xdogEpsilon: f(p.xdogEpsilon, -2, 2, XDOG_EPSILON_DEFAULT),
    xdogPhi: f(p.xdogPhi, 0.1, 500, 20),
    flow: sanitizeFlowSettings(p.flow),
    adaptiveRadius: i(p.adaptiveRadius, 1, Limits.MAX_RADIUS, 15),
    adaptiveC: f(p.adaptiveC, -1, 1, 0.02),
    useSauvola: bool(p.useSauvola, true),
    logSigma: f(p.logSigma, Limits.MIN_SIGMA, 16, 1.4),
    logSlope: f(p.logSlope, 0, 1, 0.02),
    modelId: text(p.modelId, ''),
  };
}

/** @returns the documented defaults. */
export function defaultCleanupParams(): CleanupParams {
  return {
    minBlobArea: 24,
    removeIsolated: true,
    closeRadius: 1,
    openRadius: 0,
    bridgeGaps: true,
    maxGap: 12,
    maxBridgeAngle: 60,
    skeletonize: true,
    thinning: ThinningMode.ZHANG_SUEN,
    pruneSpurs: 4,
    fillHolesUpTo: 0,
    keepLargest: 0,
    removeBorderTouching: false,
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeCleanupParams(p: CleanupParamsInput = {}): CleanupParams {
  return {
    minBlobArea: i(p.minBlobArea, 0, Limits.MAX_AREA, 24),
    removeIsolated: bool(p.removeIsolated, true),
    closeRadius: i(p.closeRadius, 0, 64, 1),
    openRadius: i(p.openRadius, 0, 64, 0),
    bridgeGaps: bool(p.bridgeGaps, true),
    maxGap: i(p.maxGap, 0, 1024, 12),
    maxBridgeAngle: f(p.maxBridgeAngle, 0, Limits.MAX_ANGLE, 60),
    skeletonize: bool(p.skeletonize, true),
    thinning: enumOf(ThinningMode, p.thinning, ThinningMode.ZHANG_SUEN),
    pruneSpurs: i(p.pruneSpurs, 0, Limits.MAX_RADIUS, 4),
    fillHolesUpTo: i(p.fillHolesUpTo, 0, Limits.MAX_AREA, 0),
    keepLargest: i(p.keepLargest, 0, 65536, 0),
    removeBorderTouching: bool(p.removeBorderTouching, false),
  };
}

/** @returns the documented defaults. */
export function defaultOutputParams(): OutputParams {
  return {
    vectorMode: VectorModeParam.CENTERLINE,
    simplify: 1,
    fitError: 1.6,
    corner: 100,
    smoothIterations: 1,
    strokeWidth: 1.6,
    modulateWidth: false,
    widthScale: 1,
    minPathLength: 3,
    strokeColor: 0xff000000,
    background: null,
    fillClosed: false,
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeOutputParams(p: OutputParamsInput = {}): OutputParams {
  return {
    vectorMode: enumOf(VectorModeParam, p.vectorMode, VectorModeParam.CENTERLINE),
    simplify: f(p.simplify, 0, 64, 1),
    // A non-positive fit error asks the fitter to subdivide until it hits its depth cap on every
    // curve, which is minutes of work for a result no better than the polyline it started from.
    fitError: f(p.fitError, 0.01, 64, 1.6),
    corner: f(p.corner, 0, Limits.MAX_ANGLE, 100),
    smoothIterations: i(p.smoothIterations, 0, 8, 1),
    strokeWidth: f(p.strokeWidth, 0.01, 64, 1.6),
    modulateWidth: bool(p.modulateWidth, false),
    widthScale: f(p.widthScale, 0.01, 16, 1),
    minPathLength: f(p.minPathLength, 0, Limits.MAX_LENGTH, 3),
    strokeColor: colour(p.strokeColor, 0xff000000),
    background: nullableColour(p.background, null),
    fillClosed: bool(p.fillClosed, false),
  };
}

/**
 * What {@link AutoParams.mode} defaults to, stated once so both engines can be flipped together.
 *
 * `SUGGEST` and not `APPLY`, and the reason is about what a *library* default may do rather than
 * about what the product should do. `Pipeline.run(src, params)` is handed a complete parameter tree
 * by every caller — a style preset, a restored project, a batch job, a test — and every one of those
 * callers means what it says. A default that let auto-detection rewrite `minBlobArea` underneath a
 * caller that had set it explicitly would make the engine's contract "these are your settings unless
 * the classifier disagrees", which is not a contract anybody can build on.
 *
 * `SUGGEST` still classifies and still writes the full "this is line art, 72% sure, and here is every
 * setting I would have changed" sentence into `TraceResult.notes`, so nothing is hidden and the
 * detection is no longer cosmetic. Turning it up to `APPLY` is the **UI's** decision, which is exactly
 * where ALGORITHMS §12 puts it: shown to the user as a named suggestion with a one-tap override.
 */
export const DEFAULT_AUTO_MODE = AutoMode.SUGGEST;

/** @returns the documented defaults. */
export function defaultAutoParams(): AutoParams {
  return {
    mode: DEFAULT_AUTO_MODE,
    subjectId: '',
    handTuned: [],
    minConfidence: 0.55,
    allowMatte: true,
    allowCrop: true,
  };
}

/** @returns a copy with every field inside its legal range; idempotent. */
export function sanitizeAutoParams(p: AutoParamsInput = {}): AutoParams {
  const raw = Array.isArray(p.handTuned) ? p.handTuned : [];
  // Trimmed and de-duplicated so two spellings of the same label cannot make a knob appear protected
  // in one build and unprotected in the next.
  const seen = new Set<string>();
  for (const name of raw) {
    if (typeof name !== 'string') continue;
    const t = name.trim();
    if (t.length > 0) seen.add(t);
  }
  return {
    mode: enumOf(AutoMode, p.mode, DEFAULT_AUTO_MODE),
    subjectId: text(p.subjectId, ''),
    handTuned: Array.from(seen),
    // A floor of 0 would mean "act on anything", which is the behaviour this parameter exists to
    // prevent; a floor above 1 would mean "never act" and is spelled AutoMode.OFF.
    minConfidence: f(p.minConfidence, 0.05, 1, 0.55),
    allowMatte: bool(p.allowMatte, true),
    allowCrop: bool(p.allowCrop, true),
  };
}

/**
 * The knobs the editor exposes by their visible labels, so {@link AutoParams.handTuned} and the
 * sentence auto writes name the same things the user can see.
 *
 * These strings are a **contract with the UI**: the Android editor's `KNOBS` table uses exactly these
 * labels, and this table is what lets the engine put a hand-tuned value back after a preset overwrote
 * it. They live in the engine because the engine is the side that has to honour them.
 */
export const Knobs = {
  EDGE_SENSITIVITY: 'edge sensitivity',
  STROKE_WIDTH: 'stroke width',
  SIMPLIFY: 'simplify',
  CORNER: 'corner threshold',
  MIN_PATH_LENGTH: 'minimum path length',
  MIN_BLOB_AREA: 'minimum blob area',
} as const;

/** Every protected knob, in the order the editor lists them. */
export const KNOB_NAMES: readonly string[] = [
  Knobs.EDGE_SENSITIVITY,
  Knobs.STROKE_WIDTH,
  Knobs.SIMPLIFY,
  Knobs.CORNER,
  Knobs.MIN_PATH_LENGTH,
  Knobs.MIN_BLOB_AREA,
];

/**
 * Copies the knobs named in `names` from `user` back onto `applied`.
 * @returns `applied` unchanged when nothing was hand-tuned, so the common case allocates nothing.
 */
export function restoreHandTuned(
  user: TraceParams,
  applied: TraceParams,
  names: readonly string[],
): TraceParams {
  if (names.length === 0) return applied;
  const set = new Set(names);
  const edge = set.has(Knobs.EDGE_SENSITIVITY)
    ? { ...applied.edge, sensitivity: user.edge.sensitivity }
    : applied.edge;
  const cleanup = set.has(Knobs.MIN_BLOB_AREA)
    ? { ...applied.cleanup, minBlobArea: user.cleanup.minBlobArea }
    : applied.cleanup;
  const output = {
    ...applied.output,
    strokeWidth: set.has(Knobs.STROKE_WIDTH) ? user.output.strokeWidth : applied.output.strokeWidth,
    simplify: set.has(Knobs.SIMPLIFY) ? user.output.simplify : applied.output.simplify,
    corner: set.has(Knobs.CORNER) ? user.output.corner : applied.output.corner,
    minPathLength: set.has(Knobs.MIN_PATH_LENGTH)
      ? user.output.minPathLength
      : applied.output.minPathLength,
  };
  return { ...applied, edge, cleanup, output };
}

/** @returns the labels of the knobs whose values differ between `a` and `b`, in {@link KNOB_NAMES} order. */
export function knobsChangedBetween(a: TraceParams, b: TraceParams): string[] {
  const out: string[] = [];
  if (a.edge.sensitivity !== b.edge.sensitivity) out.push(Knobs.EDGE_SENSITIVITY);
  if (a.output.strokeWidth !== b.output.strokeWidth) out.push(Knobs.STROKE_WIDTH);
  if (a.output.simplify !== b.output.simplify) out.push(Knobs.SIMPLIFY);
  if (a.output.corner !== b.output.corner) out.push(Knobs.CORNER);
  if (a.output.minPathLength !== b.output.minPathLength) out.push(Knobs.MIN_PATH_LENGTH);
  if (a.cleanup.minBlobArea !== b.cleanup.minBlobArea) out.push(Knobs.MIN_BLOB_AREA);
  return out;
}

/** @returns the documented defaults for a whole trace. */
export function defaultTraceParams(): TraceParams {
  return {
    preprocess: defaultPreprocessParams(),
    matte: defaultMatteParams(),
    edge: defaultEdgeParams(),
    cleanup: defaultCleanupParams(),
    output: defaultOutputParams(),
    auto: defaultAutoParams(),
    styleId: Limits.DEFAULT_STYLE_ID,
  };
}

/**
 * Clamps every field of every sub-tree into its legal range.
 *
 * Idempotent by construction — `sanitize(sanitize(p))` deep-equals `sanitize(p)` for every `p`,
 * including ones carrying NaN, infinities, missing fields and values from a build that no longer
 * exists. That property is what lets the pipeline sanitise once on entry and lets the UI sanitise on
 * every slider tick without the two disagreeing.
 *
 * @returns a new tree; the input is never modified.
 */
export function sanitizeTraceParams(p: TraceParamsInput = {}): TraceParams {
  const styleId = text(p.styleId, Limits.DEFAULT_STYLE_ID);
  return {
    preprocess: sanitizePreprocessParams(p.preprocess),
    matte: sanitizeMatteParams(p.matte),
    edge: sanitizeEdgeParams(p.edge),
    cleanup: sanitizeCleanupParams(p.cleanup),
    output: sanitizeOutputParams(p.output),
    auto: sanitizeAutoParams(p.auto),
    styleId: styleId.length === 0 ? Limits.DEFAULT_STYLE_ID : styleId,
  };
}

/**
 * Deep merge of a partial override onto a complete tree, used by the style and subject presets.
 *
 * The result is sanitised, so a preset cannot introduce an out-of-range value even if its own table
 * is edited carelessly.
 */
export function withOverrides(base: TraceParams, over: TraceParamsInput): TraceParams {
  return sanitizeTraceParams({
    preprocess: { ...base.preprocess, ...over.preprocess },
    matte: { ...base.matte, ...over.matte },
    edge: { ...base.edge, ...over.edge, flow: { ...base.edge.flow, ...over.edge?.flow } },
    cleanup: { ...base.cleanup, ...over.cleanup },
    output: { ...base.output, ...over.output },
    auto: { ...base.auto, ...over.auto },
    styleId: over.styleId ?? base.styleId,
  });
}
