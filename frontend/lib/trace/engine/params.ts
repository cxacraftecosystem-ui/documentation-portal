export enum EdgeEngine {
  CANNY = 'CANNY',
  XDOG = 'XDOG',
  FDOG = 'FDOG',
  ADAPTIVE = 'ADAPTIVE',
  LOG = 'LOG',
  MODEL = 'MODEL',
}

export enum DenoiseMode {
  NONE = 'NONE',
  BILATERAL = 'BILATERAL',
  MEDIAN = 'MEDIAN',
  ANISOTROPIC = 'ANISOTROPIC',
}

export enum MatteMode {
  NONE = 'NONE',
  BORDER_FLOOD = 'BORDER_FLOOD',
  SALIENCY = 'SALIENCY',
  SUBJECT = 'SUBJECT',
}

export enum AutoMode {
  OFF = 'OFF',
  SUGGEST = 'SUGGEST',
  APPLY = 'APPLY',
}

export enum ThinningMode {
  ZHANG_SUEN = 'ZHANG_SUEN',
  GUO_HALL = 'GUO_HALL',
}

export enum VectorModeParam {
  CENTERLINE = 'CENTERLINE',
  OUTLINE = 'OUTLINE',
}

export const Limits = {
  MIN_WORKING_EDGE: 256,
  MAX_WORKING_EDGE: 8192,

  MIN_SIGMA: 0.05,
  MAX_SIGMA: 32,

  MAX_RADIUS: 256,

  MAX_AREA: 67108864,

  MAX_ANGLE: 180,

  MAX_LENGTH: 8192,

  DEFAULT_STYLE_ID: 'clean-line',
} as const;

export const AUTO_THRESHOLD = -1;

const XDOG_EPSILON_DEFAULT = 0.08;

function f(v: unknown, lo: number, hi: number, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return v < lo ? lo : v > hi ? hi : v;
}

function i(v: unknown, lo: number, hi: number, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  const t = Math.trunc(v);
  return t < lo ? lo : t > hi ? hi : t;
}

function bool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

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

function colour(v: unknown, fallback: number): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return Math.trunc(v) >>> 0;
}

function nullableColour(v: unknown, fallback: number | null): number | null {
  if (v === null) return null;
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return Math.trunc(v) >>> 0;
}

function text(v: unknown, fallback: string): string {
  return typeof v === 'string' ? v.trim() : fallback;
}

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

export interface MatteParams {
  readonly mode: MatteMode;
  readonly tolerance: number;
  readonly feather: number;
  readonly threshold: number;
}

export interface FlowSettings {
  readonly tensorSigma: number;
  readonly etfIterations: number;
  readonly etfRadius: number;
  readonly sigmaC: number;
  readonly sigmaM: number;
  readonly tau: number;
  readonly fdogIterations: number;
}

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

export interface AutoParams {
  readonly mode: AutoMode;
  readonly subjectId: string;
  readonly handTuned: readonly string[];
  readonly minConfidence: number;
  readonly allowMatte: boolean;
  readonly allowCrop: boolean;
}

export interface TraceParams {
  readonly preprocess: PreprocessParams;
  readonly matte: MatteParams;
  readonly edge: EdgeParams;
  readonly cleanup: CleanupParams;
  readonly output: OutputParams;
  readonly auto: AutoParams;
  readonly styleId: string;
}

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

export function defaultMatteParams(): MatteParams {
  return { mode: MatteMode.NONE, tolerance: 0.18, feather: 1.5, threshold: 0.5 };
}

export function sanitizeMatteParams(p: MatteParamsInput = {}): MatteParams {
  return {
    mode: enumOf(MatteMode, p.mode, MatteMode.NONE),
    tolerance: f(p.tolerance, 0, 1, 0.18),
    feather: f(p.feather, 0, Limits.MAX_SIGMA, 1.5),
    threshold: f(p.threshold, 0, 1, 0.5),
  };
}

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

export function sanitizeFlowSettings(p: FlowSettingsInput = {}): FlowSettings {
  return {
    tensorSigma: f(p.tensorSigma, Limits.MIN_SIGMA, 16, 2),
    etfIterations: i(p.etfIterations, 0, 16, 3),
    etfRadius: i(p.etfRadius, 1, 32, 5),
    sigmaC: f(p.sigmaC, Limits.MIN_SIGMA, 16, 1),
    sigmaM: f(p.sigmaM, Limits.MIN_SIGMA, Limits.MAX_SIGMA, 3),
    tau: f(p.tau, 0, 0.999, 0.99),
    fdogIterations: i(p.fdogIterations, 1, 16, 3),
  };
}

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

export function sanitizeOutputParams(p: OutputParamsInput = {}): OutputParams {
  return {
    vectorMode: enumOf(VectorModeParam, p.vectorMode, VectorModeParam.CENTERLINE),
    simplify: f(p.simplify, 0, 64, 1),
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

export const DEFAULT_AUTO_MODE = AutoMode.SUGGEST;

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

export function sanitizeAutoParams(p: AutoParamsInput = {}): AutoParams {
  const raw = Array.isArray(p.handTuned) ? p.handTuned : [];
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
    minConfidence: f(p.minConfidence, 0.05, 1, 0.55),
    allowMatte: bool(p.allowMatte, true),
    allowCrop: bool(p.allowCrop, true),
  };
}

export const Knobs = {
  EDGE_SENSITIVITY: 'edge sensitivity',
  STROKE_WIDTH: 'stroke width',
  SIMPLIFY: 'simplify',
  CORNER: 'corner threshold',
  MIN_PATH_LENGTH: 'minimum path length',
  MIN_BLOB_AREA: 'minimum blob area',
} as const;

export const KNOB_NAMES: readonly string[] = [
  Knobs.EDGE_SENSITIVITY,
  Knobs.STROKE_WIDTH,
  Knobs.SIMPLIFY,
  Knobs.CORNER,
  Knobs.MIN_PATH_LENGTH,
  Knobs.MIN_BLOB_AREA,
];

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
