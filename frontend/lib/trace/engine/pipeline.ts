import { GrayF, Mask, RgbaImage } from './buffers';
import { SourceProfile, profile as profileSource } from './classify';
import * as Color from './color';
import * as Components from './components';
import * as Contrast from './contrast';
import * as Denoise from './denoise';
import * as Distance from './distance';
import * as EdgeCanny from './edgeCanny';
import * as EdgeDog from './edgeDog';
import * as EdgeFlow from './edgeFlow';
import * as EdgeLog from './edgeLog';
import * as Geometry from './geometry';
import * as Matte from './matte';
import * as Morphology from './morphology';
import {
  AutoMode,
  DenoiseMode,
  EdgeEngine,
  MatteMode,
  ThinningMode,
  TraceParams,
  TraceParamsInput,
  VectorModeParam,
  sanitizeTraceParams,
} from './params';
import * as Subject from './subject';
import { autoDecide } from './subjects';
import {
  FillRule,
  LineCap,
  LineJoin,
  Mat2D,
  VecDocument,
  VecLayer,
  VecShape,
  vecStyle,
} from './path';
import * as Resample from './resample';
import * as Thinning from './thinning';
import * as Threshold from './threshold';
import * as Vectorize from './vectorize';

export interface StageResult {
  readonly id: string;
  readonly label: string;
  readonly millis: number;
}

export interface TraceResult {
  readonly document: VecDocument;
  readonly preview: Mask;
  readonly processedGray: GrayF;
  readonly distanceTransform: GrayF | null;
  readonly stages: readonly StageResult[];
  readonly workingWidth: number;
  readonly workingHeight: number;
  readonly cropX: number;
  readonly cropY: number;
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly profile: SourceProfile | null;
  readonly appliedParams: TraceParams;
  readonly autoSubjectId: string;
  readonly notes: readonly string[];
  readonly totalMillis: number;
}

export type ProgressListener = (stageId: string, label: string, fraction: number) => void;

export class CancelledError extends Error {
  constructor() {
    super('Trace cancelled');
    this.name = 'CancelledError';
  }
}

export class CancellationToken {
  private cancelled = false;

  cancel(): void {
    this.cancelled = true;
  }

  get isCancelled(): boolean {
    return this.cancelled;
  }

  throwIfCancelled(): void {
    if (this.cancelled) throw new CancelledError();
  }
}

export interface EdgeModel {
  readonly id: string;
  readonly displayName: string;
  readonly isAvailable: boolean;
  infer(src: GrayF): GrayF;
}

const registeredModels = new Map<string, EdgeModel>();

export const edgeModelRegistry = {
  register(model: EdgeModel): void {
    registeredModels.set(model.id, model);
  },
  available(): EdgeModel[] {
    return Array.from(registeredModels.values()).filter((m) => m.isAvailable);
  },
  byId(id: string): EdgeModel | null {
    return registeredModels.get(id) ?? null;
  },
  clear(): void {
    registeredModels.clear();
  },
} as const;

interface StageSpec {
  readonly id: string;
  readonly label: string;
}

const STAGES: readonly StageSpec[] = [
  { id: 'prepare', label: 'Preparing image' },
  { id: 'matte', label: 'Separating background' },
  { id: 'crop', label: 'Cropping to the subject' },
  { id: 'gray', label: 'Converting to grey' },
  { id: 'denoise', label: 'Reducing noise' },
  { id: 'contrast', label: 'Enhancing contrast' },
  { id: 'edge', label: 'Detecting edges' },
  { id: 'cleanup', label: 'Cleaning up' },
  { id: 'skeleton', label: 'Thinning strokes' },
  { id: 'distance', label: 'Measuring stroke width' },
  { id: 'vectorize', label: 'Tracing vectors' },
  { id: 'document', label: 'Assembling document' },
];

const [
  STAGE_PREPARE,
  STAGE_MATTE,
  STAGE_CROP,
  STAGE_GRAY,
  STAGE_DENOISE,
  STAGE_CONTRAST,
  STAGE_EDGE,
  STAGE_CLEANUP,
  STAGE_SKELETON,
  STAGE_DISTANCE,
  STAGE_VECTORIZE,
  STAGE_DOCUMENT,
] = STAGES;

export function stageIds(): string[] {
  return STAGES.map((s) => s.id);
}

function yieldToEventLoop(): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, 0);
  });
}

class RunContext {
  readonly stages: StageResult[] = [];
  readonly notes: string[] = [];
  private index = 0;
  private startedAt = 0;

  constructor(
    private readonly progress: ProgressListener | undefined,
    private readonly cancel: CancellationToken,
  ) {}

  async begin(spec: StageSpec): Promise<void> {
    this.cancel.throwIfCancelled();
    await yieldToEventLoop();
    this.cancel.throwIfCancelled();
    this.startedAt = Date.now();
    if (this.progress !== undefined) {
      this.progress(spec.id, spec.label, this.index / STAGES.length);
    }
  }

  end(spec: StageSpec): void {
    this.stages.push({ id: spec.id, label: spec.label, millis: Date.now() - this.startedAt });
    this.index++;
  }

  note(sentence: string): void {
    this.notes.push(sentence);
  }
}

function inkThreshold(sensitivity: number): number {
  return 0.5 + (sensitivity - 0.5) * 0.6;
}

function sensitivityBias(sensitivity: number): number {
  return 2 - 2 * sensitivity;
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? `1 ${one}` : `${n} ${many}`;
}

const CROP_PROXY_LONG_EDGE = 512;

const MIN_CROP_EDGE = 64;

const MATTE_ALARM_FRACTION = 0.6;

function clampInt(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v;
}

export async function run(
  src: RgbaImage,
  params: TraceParams | TraceParamsInput,
  progress?: ProgressListener,
  cancel: CancellationToken = new CancellationToken(),
  classify = true,
): Promise<TraceResult> {
  const requested = sanitizeTraceParams(params as TraceParamsInput);
  const ctx = new RunContext(progress, cancel);
  const startedAt = Date.now();
  const sourceWidth = src.width;
  const sourceHeight = src.height;

  await ctx.begin(STAGE_PREPARE);
  const sourceProfile =
    classify || requested.auto.mode === AutoMode.APPLY ? profileSource(src) : null;
  const decision = autoDecide(sourceProfile, requested);
  for (const note of decision.notes) ctx.note(note);
  const p = decision.params;

  let working = Resample.scaleToLongEdge(src, p.preprocess.workingLongEdge);
  if (working.width !== sourceWidth || working.height !== sourceHeight) {
    ctx.note(
      `Traced at ${working.width}x${working.height} and scaled back to ${sourceWidth}x${sourceHeight}. ` +
        'The vector output is resolution independent, so no detail was lost to the downscale.',
    );
  }
  if (p.preprocess.perspectiveCorrect) {
    const quad = Geometry.detectDocumentQuad(Color.toGray(working));
    if (quad === null) {
      ctx.note('No page outline was found, so perspective correction was skipped.');
    } else {
      const ordered = Geometry.orderQuad(quad);
      const w = Math.max(
        Math.hypot(ordered[2] - ordered[0], ordered[3] - ordered[1]),
        Math.hypot(ordered[4] - ordered[6], ordered[5] - ordered[7]),
      );
      const h = Math.max(
        Math.hypot(ordered[6] - ordered[0], ordered[7] - ordered[1]),
        Math.hypot(ordered[4] - ordered[2], ordered[5] - ordered[3]),
      );
      const outW = Math.max(1, Math.round(w));
      const outH = Math.max(1, Math.round(h));
      const dst = Float32Array.from([0, 0, outW, 0, outW, outH, 0, outH]);
      working = Geometry.warpPerspective(
        working,
        Geometry.solveHomography(ordered, dst),
        outW,
        outH,
      );
      ctx.note(`Perspective corrected to a ${outW}x${outH} page.`);
    }
  }
  const uncroppedWidth = working.width;
  const uncroppedHeight = working.height;
  ctx.end(STAGE_PREPARE);

  await ctx.begin(STAGE_MATTE);
  if (p.matte.mode !== MatteMode.NONE) {
    let alpha: GrayF | null = null;
    let which = '';
    if (p.matte.mode === MatteMode.BORDER_FLOOD) {
      alpha = Matte.borderFlood(working, p.matte.tolerance, p.matte.feather);
      which = 'Border flood';
    } else if (p.matte.mode === MatteMode.SALIENCY) {
      alpha = Matte.saliencyMatte(working, p.matte.threshold, p.matte.feather);
      which = 'Saliency';
    } else if (p.matte.mode === MatteMode.SUBJECT) {
      const found = Matte.subjectMatte(working, p.matte.tolerance, p.matte.feather);
      if (found.confident) {
        alpha = found.alpha;
        which = 'Subject';
      } else {
        ctx.note(
          'The subject matte was not sure enough to remove the background, so the whole frame was ' +
            `traced with the background still in it. ${found.reason}`,
        );
      }
    } else {
      ctx.note(
        `That background option ("${String(p.matte.mode)}") isn't available here, so the whole ` +
          'frame was traced.',
      );
    }
    if (alpha !== null) {
      let removed = 0;
      for (let i = 0; i < alpha.data.length; i++) if (alpha.data[i] < 0.5) removed++;
      const fraction = removed / alpha.data.length;
      working = Matte.applyMatteRgba(working, alpha, 0xffffffff);
      ctx.note(`${which} matting removed ${Math.round(fraction * 100)}% of the frame.`);
      if (fraction > MATTE_ALARM_FRACTION) {
        ctx.note(
          'That matte removed most of the frame. If part of your artwork is missing, that is where ' +
            'it went, so lower the matte tolerance or turn matting off.',
        );
      }
    }
  }
  ctx.end(STAGE_MATTE);

  await ctx.begin(STAGE_CROP);
  let cropX = 0;
  let cropY = 0;
  if (p.auto.mode === AutoMode.APPLY && p.auto.allowCrop) {
    const proxy = Resample.scaleToLongEdge(working, CROP_PROXY_LONG_EDGE);
    const find = Subject.locate(proxy, p.matte.tolerance);
    if (!find.confident) {
      ctx.note(`The working image was not cropped to the subject. ${find.reason}`);
    } else {
      const sxProxy = working.width / proxy.width;
      const syProxy = working.height / proxy.height;
      const x0 = clampInt(Math.floor(find.box.x * sxProxy), 0, working.width - 1);
      const y0 = clampInt(Math.floor(find.box.y * syProxy), 0, working.height - 1);
      const x1 = clampInt(
        Math.ceil((find.box.x + find.box.w) * sxProxy),
        x0 + 1,
        working.width,
      );
      const y1 = clampInt(
        Math.ceil((find.box.y + find.box.h) * syProxy),
        y0 + 1,
        working.height,
      );
      const cw = x1 - x0;
      const ch = y1 - y0;
      if (cw < working.width || ch < working.height) {
        if (cw < MIN_CROP_EDGE || ch < MIN_CROP_EDGE) {
          ctx.note(
            `The detected subject is only ${cw}x${ch} px at the working size, which is too small to ` +
              'trace on its own, so the whole frame was traced instead.',
          );
        } else {
          working = Resample.cropRgba(working, x0, y0, cw, ch);
          cropX = x0;
          cropY = y0;
          ctx.note(
            `Cropped the working image to the detected subject: ${cw}x${ch} px at (${x0}, ${y0}) ` +
              `inside the ${uncroppedWidth}x${uncroppedHeight} frame, so the background no longer ` +
              'pulls the automatic thresholds. The exported coordinates are still in the original ' +
              'frame.',
          );
        }
      }
    }
  }
  const workingWidth = working.width;
  const workingHeight = working.height;
  ctx.end(STAGE_CROP);

  await ctx.begin(STAGE_GRAY);
  let grey = Color.toGray(working);
  if (p.preprocess.invertInput) grey = Contrast.invert(grey);
  ctx.end(STAGE_GRAY);

  await ctx.begin(STAGE_DENOISE);
  const strength = p.preprocess.denoiseStrength;
  switch (p.preprocess.denoise) {
    case DenoiseMode.BILATERAL:
      grey = Denoise.bilateral(grey, 1 + 3 * strength, 0.05 + 0.25 * strength);
      break;
    case DenoiseMode.MEDIAN:
      grey = Denoise.median(grey, p.preprocess.medianRadius);
      break;
    case DenoiseMode.ANISOTROPIC:
      grey = Denoise.anisotropicDiffusion(grey, Math.max(1, Math.round(14 * strength)), 0.12);
      break;
    case DenoiseMode.NONE:
      break;
    default:
      break;
  }
  ctx.end(STAGE_DENOISE);

  await ctx.begin(STAGE_CONTRAST);
  if (p.preprocess.claheEnabled) {
    grey = Contrast.clahe(
      grey,
      p.preprocess.claheTiles,
      p.preprocess.claheTiles,
      p.preprocess.claheClip,
    );
  }
  if (p.preprocess.brightness !== 0 || p.preprocess.contrast !== 0) {
    grey = Contrast.brightnessContrast(grey, p.preprocess.brightness, p.preprocess.contrast);
  }
  if (p.preprocess.gamma !== 1) grey = Contrast.gamma(grey, p.preprocess.gamma);
  if (p.preprocess.unsharpAmount > 0) {
    grey = Contrast.unsharpMask(grey, p.preprocess.unsharpSigma, p.preprocess.unsharpAmount);
  }
  const processedGray = grey;
  ctx.end(STAGE_CONTRAST);

  await ctx.begin(STAGE_EDGE);
  let mask = runEdgeStage(processedGray, p, ctx);
  ctx.end(STAGE_EDGE);

  await ctx.begin(STAGE_CLEANUP);
  const outlineMode = p.output.vectorMode === VectorModeParam.OUTLINE;
  if (p.cleanup.closeRadius > 0) mask = Morphology.close(mask, p.cleanup.closeRadius);
  if (p.cleanup.openRadius > 0) mask = Morphology.open(mask, p.cleanup.openRadius);
  if (p.cleanup.fillHolesUpTo > 0) mask = Components.fillHoles(mask, p.cleanup.fillHolesUpTo);
  if (p.cleanup.minBlobArea > 1) {
    const lab = Components.label(mask, 8);
    let droppedBlobs = 0;
    const keep = new Uint8Array(lab.count + 1);
    for (let l = 1; l <= lab.count; l++) {
      if (lab.area[l] >= p.cleanup.minBlobArea) keep[l] = 1;
      else droppedBlobs++;
    }
    if (droppedBlobs > 0) {
      const data = new Uint8Array(mask.size);
      for (let i = 0; i < data.length; i++) {
        const l = lab.labels[i];
        data[i] = l !== 0 && keep[l] !== 0 ? 1 : 0;
      }
      mask = new Mask(mask.width, mask.height, data);
      ctx.note(
        `Dropped ${plural(droppedBlobs, 'blob', 'blobs')} smaller than ` +
          `${p.cleanup.minBlobArea} px as dust.`,
      );
    }
  }
  if (p.cleanup.removeIsolated) mask = Components.removeIsolated(mask, 1, 8);
  if (p.cleanup.removeBorderTouching) {
    const before = Components.label(mask, 8).count;
    mask = Components.removeBorderTouching(mask);
    const after = Components.label(mask, 8).count;
    if (before !== after) {
      ctx.note(`Removed ${plural(before - after, 'shape', 'shapes')} touching the image border.`);
    }
  }
  if (p.cleanup.keepLargest > 0) {
    const before = Components.label(mask, 8).count;
    if (before > p.cleanup.keepLargest) {
      mask = Components.keepLargest(mask, p.cleanup.keepLargest);
      ctx.note(
        `Kept the ${p.cleanup.keepLargest} largest of ${before} shapes and discarded the rest.`,
      );
    }
  }
  const preSkeletonMask = mask;
  ctx.end(STAGE_CLEANUP);

  await ctx.begin(STAGE_SKELETON);
  const skeletonize = p.cleanup.skeletonize && !outlineMode;
  if (skeletonize) {
    mask =
      p.cleanup.thinning === ThinningMode.GUO_HALL
        ? Thinning.guoHall(mask)
        : Thinning.zhangSuen(mask);
    if (p.cleanup.pruneSpurs > 0) mask = Thinning.pruneSpurs(mask, p.cleanup.pruneSpurs);
    if (p.cleanup.bridgeGaps && p.cleanup.maxGap > 0) {
      const before = Thinning.endpoints(mask).length;
      mask = Thinning.bridgeEndpoints(mask, p.cleanup.maxGap, p.cleanup.maxBridgeAngle);
      const after = Thinning.endpoints(mask).length;
      if (after < before) {
        ctx.note(
          `Bridged ${plural(Math.round((before - after) / 2), 'gap', 'gaps')} between stroke ends.`,
        );
      }
    }
  } else if (p.cleanup.bridgeGaps && p.cleanup.maxGap > 0) {
    const scratch = Thinning.zhangSuen(mask);
    const bridged = Thinning.bridgeEndpoints(scratch, p.cleanup.maxGap, p.cleanup.maxBridgeAngle);
    const added = bridged.subtract(scratch);
    const addedCount = added.countTrue();
    if (addedCount > 0) {
      const thick = Morphology.dilate(added, Math.max(1, p.cleanup.closeRadius));
      mask = mask.or(thick);
      ctx.note('Bridged gaps between region edges so the outlines close.');
    }
  }
  const finalMask = mask;
  ctx.end(STAGE_SKELETON);

  await ctx.begin(STAGE_DISTANCE);
  let dt: GrayF | null = null;
  if (p.output.modulateWidth) {
    dt = Distance.euclidean(preSkeletonMask, true);
  }
  ctx.end(STAGE_DISTANCE);

  await ctx.begin(STAGE_VECTORIZE);
  const shapes = Vectorize.run(
    finalMask,
    Vectorize.vectorizeParams({
      mode: outlineMode ? Vectorize.VectorMode.OUTLINE : Vectorize.VectorMode.CENTERLINE,
      simplifyEpsilon: p.output.simplify,
      fitError: p.output.fitError,
      cornerThresholdDegrees: p.output.corner,
      smoothIterations: p.output.smoothIterations,
      minPathLength: p.output.minPathLength,
      strokeWidth: p.output.strokeWidth,
      modulateWidth: p.output.modulateWidth,
      widthScale: p.output.widthScale,
      fillRegions: p.output.fillClosed,
    }),
    dt,
  );
  const stats = Vectorize.lastRunStats();
  if (stats.dropped > 0) {
    ctx.note(
      `Dropped ${plural(stats.dropped, 'path', 'paths')} shorter than ` +
        `${p.output.minPathLength} px, and kept ${plural(stats.emitted, 'path', 'paths')}.`,
    );
  }
  if (stats.emitted === 0) {
    ctx.note(
      'No paths were produced. Raise sensitivity, lower the minimum blob area, or try another edge ' +
        'engine.',
    );
  }
  ctx.end(STAGE_VECTORIZE);

  await ctx.begin(STAGE_DOCUMENT);
  const sx = uncroppedWidth > 0 ? sourceWidth / uncroppedWidth : 1;
  const sy = uncroppedHeight > 0 ? sourceHeight / uncroppedHeight : 1;
  const m = Mat2D.scale(sx, sy).times(Mat2D.translate(cropX, cropY));
  const widthScale = m.meanScale();
  const finalShapes: VecShape[] = new Array<VecShape>(shapes.length);
  for (let i = 0; i < shapes.length; i++) {
    const shape = shapes[i];
    const filled = shape.style.fill !== null;
    const style = filled
      ? vecStyle({
          fill: p.output.strokeColor,
          stroke: null,
          fillRule: shape.style.fillRule,
          opacity: 1,
        })
      : vecStyle({
          stroke: p.output.strokeColor,
          strokeWidth: shape.style.strokeWidth * widthScale,
          fill: null,
          cap: LineCap.ROUND,
          join: LineJoin.ROUND,
          opacity: 1,
        });
    const restyled =
      outlineMode && !p.output.fillClosed && filled
        ? vecStyle({
            stroke: p.output.strokeColor,
            strokeWidth: p.output.strokeWidth * widthScale,
            fill: null,
            fillRule: FillRule.EVENODD,
            cap: LineCap.ROUND,
            join: LineJoin.ROUND,
            opacity: 1,
          })
        : style;
    finalShapes[i] = { path: shape.path.transform(m), style: restyled };
  }
  const layer: VecLayer = {
    id: 'trace',
    name: 'Trace',
    shapes: finalShapes,
    visible: true,
    locked: false,
    opacity: 1,
  };
  const traced = new VecDocument(sourceWidth, sourceHeight, [layer], p.output.background);
  ctx.end(STAGE_DOCUMENT);

  return {
    document: traced,
    preview: finalMask,
    processedGray,
    distanceTransform: dt,
    stages: ctx.stages,
    workingWidth,
    workingHeight,
    cropX,
    cropY,
    sourceWidth,
    sourceHeight,
    profile: sourceProfile,
    appliedParams: p,
    autoSubjectId: decision.applied ? decision.subject?.id ?? '' : '',
    notes: ctx.notes,
    totalMillis: Date.now() - startedAt,
  };
}

function runEdgeStage(grey: GrayF, p: TraceParams, ctx: RunContext): Mask {
  const e = p.edge;
  let engine = e.engine;
  if (engine === EdgeEngine.MODEL) {
    const model = e.modelId === '' ? null : edgeModelRegistry.byId(e.modelId);
    if (model === null || !model.isAvailable) {
      ctx.note(
        e.modelId === ''
          ? 'No edge model was selected, so the flow-based detector was used.'
          : `Edge model "${e.modelId}" is not available on this device, so the flow-based detector ` +
              'was used.',
      );
      engine = EdgeEngine.FDOG;
    } else {
      const prob = model.infer(grey);
      ctx.note(`Edges came from the "${model.displayName}" model.`);
      return Threshold.fixed(prob, 1 - inkThreshold(e.sensitivity), false);
    }
  }
  switch (engine) {
    case EdgeEngine.CANNY: {
      if (e.cannyLow < 0 || e.cannyHigh < 0) {
        return EdgeCanny.detectAuto(grey, e.blurSigma, Math.max(0.02, 0.66 * e.sensitivity));
      }
      return EdgeCanny.detect(grey, e.blurSigma, e.cannyLow, e.cannyHigh);
    }
    case EdgeEngine.XDOG: {
      const density = EdgeDog.xdog(
        grey,
        e.dogSigma,
        e.dogK,
        e.dogTau,
        e.xdogEpsilon,
        e.xdogPhi,
      );
      return Threshold.fixed(density, inkThreshold(e.sensitivity), true);
    }
    case EdgeEngine.ADAPTIVE: {
      if (e.useSauvola) {
        const k = Math.max(0.02, Math.min(0.5, 0.5 - 0.6 * e.sensitivity));
        return Threshold.sauvola(grey, e.adaptiveRadius, k, true);
      }
      return Threshold.adaptiveMean(
        grey,
        e.adaptiveRadius,
        e.adaptiveC * sensitivityBias(e.sensitivity),
        true,
      );
    }
    case EdgeEngine.LOG: {
      return EdgeLog.detect(grey, e.logSigma, e.logSlope * sensitivityBias(e.sensitivity));
    }
    default: {
      const field = EdgeFlow.refineEtf(
        EdgeFlow.structureTensorFlow(grey, e.flow.tensorSigma),
        e.flow.etfIterations,
        e.flow.etfRadius,
      );
      const density = EdgeFlow.fdog(
        grey,
        field,
        e.flow.sigmaC,
        e.flow.sigmaM,
        e.flow.tau,
        e.flow.fdogIterations,
        e.xdogEpsilon,
        e.xdogPhi,
      );
      return Threshold.fixed(density, inkThreshold(e.sensitivity), true);
    }
  }
}

export async function runPreview(
  src: RgbaImage,
  params: TraceParams | TraceParamsInput,
  longEdge = 720,
  cancel: CancellationToken = new CancellationToken(),
): Promise<TraceResult> {
  const base = sanitizeTraceParams(params as TraceParamsInput);
  const preview = sanitizeTraceParams({
    ...base,
    preprocess: { ...base.preprocess, workingLongEdge: longEdge },
  });
  const result = await run(src, preview, undefined, cancel, false);
  return {
    ...result,
    notes: [
      `Preview at ${result.workingWidth}x${result.workingHeight}. The full-resolution trace may find ` +
        'more detail.',
      ...result.notes,
    ],
  };
}
