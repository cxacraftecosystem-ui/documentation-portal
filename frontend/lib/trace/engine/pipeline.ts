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

/**
 * The orchestrator. One trace, start to finish.
 *
 * `notes` is not decoration. Every cap the pipeline applies — blobs dropped, paths dropped below
 * `minPathLength`, a requested model that was not available, a matte that removed most of the frame —
 * appends a sentence, and the UI is **required** to show them. A pipeline that silently discards 4 000
 * paths and one that found nothing look identical on screen otherwise, and that ambiguity is the bug class
 * this project takes most seriously.
 *
 * {@link run} is `async` and yields a macrotask between stages. Not for parallelism — there is none — but
 * so a worker stays responsive to a cancel message while a 12 MP trace is in flight. Without the yield the
 * worker's message queue is not drained until the whole trace finishes and "Cancel" does nothing.
 */

/** One stage's identity and wall-clock cost. */
export interface StageResult {
  readonly id: string;
  readonly label: string;
  readonly millis: number;
}

/** Everything one trace produced. */
export interface TraceResult {
  /** The vector document, in **source** pixel coordinates — see the note on {@link run}. */
  readonly document: VecDocument;
  /** The final binary mask at working resolution, for the on-canvas preview. */
  readonly preview: Mask;
  /** The grey image the edge stage actually saw, after denoise and contrast. */
  readonly processedGray: GrayF;
  /** The distance transform, present only when width modulation asked for one. */
  readonly distanceTransform: GrayF | null;
  readonly stages: readonly StageResult[];
  /**
   * The resolution the trace ran at, which is the size of `preview` and `processedGray`. After a crop
   * this is the **cropped** size, because that is what the stages after the crop actually saw;
   * {@link cropX} and {@link cropY} say where it sits.
   */
  readonly workingWidth: number;
  readonly workingHeight: number;
  /**
   * Where `preview` and `processedGray` sit inside the un-cropped working image, both 0 when no crop
   * was taken. A consumer overlaying the preview on the source has to honour them, and this is the
   * only place the offset is recorded.
   */
  readonly cropX: number;
  readonly cropY: number;
  /**
   * The frame `document`'s coordinates live in. **A crop never changes it** — cropping decides what
   * is traced, not what coordinate system the result is reported in.
   */
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly profile: SourceProfile | null;
  /**
   * The parameters the stages actually ran with. Identical to the sanitised input unless
   * auto-detection changed something, and carried back so a UI can show what ran rather than what was
   * asked for — the two differing silently is the whole failure mode auto-detection invites.
   */
  readonly appliedParams: TraceParams;
  /** The subject auto-detection applied, or empty when it applied none. */
  readonly autoSubjectId: string;
  /** Every sentence in here must be rendered. See the module comment. */
  readonly notes: readonly string[];
  readonly totalMillis: number;
}

/** Progress callback. `fraction` is 0..1 across the whole trace, not within the stage. */
export type ProgressListener = (stageId: string, label: string, fraction: number) => void;

/** Thrown by {@link CancellationToken.throwIfCancelled}; carries no state worth inspecting. */
export class CancelledError extends Error {
  constructor() {
    super('Trace cancelled');
    this.name = 'CancelledError';
  }
}

/**
 * A one-way flag. Deliberately not an `AbortSignal`: the engine must run identically under vitest, in a
 * worker and on a JVM-shaped API, and `AbortSignal` exists in only one of those.
 */
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

/** An optional side-loaded edge detector. No weights ship with the app and none are downloaded. */
export interface EdgeModel {
  readonly id: string;
  readonly displayName: string;
  readonly isAvailable: boolean;
  /** @returns edge probability in 0..1, the same size as `src`. */
  infer(src: GrayF): GrayF;
}

const registeredModels = new Map<string, EdgeModel>();

/** Registry for side-loaded models. Empty by default, and the app is fully functional that way. */
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

/**
 * The stages by name, destructured once.
 *
 * Named rather than indexed because inserting a stage renumbers every index after it, and an index
 * that is one out does not fail — it reports the wrong label and the wrong timing for the rest of the
 * trace, which looks like a slow stage rather than like a bug.
 */
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

/** @returns the stage ids, in execution order. Stable: the UI keys its progress rows on them. */
export function stageIds(): string[] {
  return STAGES.map((s) => s.id);
}

/**
 * Yield a macrotask.
 *
 * `setTimeout` and not `queueMicrotask` or `await null`: a microtask runs before the event loop turns, so
 * neither drains the worker's message queue and neither lets a cancel message arrive.
 */
function yieldToEventLoop(): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, 0);
  });
}

/** Accumulates stage timings and notes so the stage bodies stay free of bookkeeping. */
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

/** Maps `sensitivity` onto the ink/paper split of an ink-density map. Higher sensitivity keeps more ink. */
function inkThreshold(sensitivity: number): number {
  return 0.5 + (sensitivity - 0.5) * 0.6;
}

/** Maps `sensitivity` onto a multiplier that *reduces* a threshold offset as sensitivity rises. */
function sensitivityBias(sensitivity: number): number {
  return 2 - 2 * sensitivity;
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? `1 ${one}` : `${n} ${many}`;
}

/**
 * Long edge of the proxy the subject box is measured on.
 *
 * `subject.locate` mattes the frame it is handed, which is a Lab conversion and a flood over every
 * pixel of it. A crop box does not need pixel precision — it carries a 4% margin of its own — so
 * measuring it on a proxy costs a box that is a working pixel or two loose and saves matting a 2048 px
 * frame to decide where to cut it.
 */
const CROP_PROXY_LONG_EDGE = 512;

/** No crop is taken that would leave a working image with an edge shorter than this. */
const MIN_CROP_EDGE = 64;

/**
 * A matte removing more than this fraction of the frame is reported in stronger language.
 *
 * 0.6 and not "almost all of it": the sentence exists for the user whose subject went missing, and by
 * the time 90% of the frame is gone they no longer need to be told. Half the picture disappearing is
 * already the failure — and it is the level the Kotlin engine has always alarmed at, so the two say
 * the same thing about the same image.
 */
const MATTE_ALARM_FRACTION = 0.6;

function clampInt(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v;
}

/**
 * Run a full trace.
 *
 * The returned {@link TraceResult.document} is in **source** coordinates: the trace runs at
 * `workingLongEdge` and the geometry is scaled by `source / working` here, once, so every exporter and every
 * viewer sees the same numbers. Leaving it in working coordinates and scaling at export time was tried, and
 * three different consumers each applied the factor a different number of times.
 *
 * @param params   sanitised on entry, so no stage defends itself
 * @param progress called at the start of each stage; may be omitted
 * @param cancel   checked between every stage
 * @param classify false skips source classification, which is what the preview path wants
 * @returns the document, the working-resolution preview mask, the grey the edge stage saw, timings and notes
 * @throws {@link CancelledError} if `cancel` was cancelled; nothing else is thrown for any legal image.
 */
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

  // --- prepare -------------------------------------------------------------------------------------
  await ctx.begin(STAGE_PREPARE);
  // Classification runs on the source rather than on the working image, and *before* the downscale,
  // because auto-detection is allowed to change `workingLongEdge` and cannot be asked to decide that
  // after the decision has been carried out. It answers from its own 512 px proxy either way.
  //
  // It runs whenever the caller asked for it **or** auto-detection is in a mode that will actually
  // change a setting. The second half is what keeps a preview honest: a preview is not allowed to be a
  // cheaper different pipeline, and one where auto chose no subject while the export's auto chose one
  // would have the user tuning sliders against a picture the export will not reproduce. `SUGGEST`
  // changes nothing, so a preview that skips it is identical to one that does not.
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
  // The frame the downscale factor is derived from, and the frame the crop offset below is measured
  // in. Kept separately from the post-crop size because the crop removes pixels without resampling the
  // ones that remain, so the pixel pitch stays the pitch *this* size established.
  const uncroppedWidth = working.width;
  const uncroppedHeight = working.height;
  ctx.end(STAGE_PREPARE);

  // --- matte ---------------------------------------------------------------------------------------
  //
  // `SUBJECT` is the only mode that can decline. The flood and the saliency cut return a bare alpha —
  // nothing in either says how much of the answer is evidence and how much is that cue's failure mode
  // — so applying one is unconditional by construction. `subjectMatte` returns its own verdict and
  // this stage honours it: below `MIN_CONFIDENCE` the frame is left exactly as it was and the matte's
  // own sentence goes into the notes. Falling through to no matte is the only safe direction, because
  // the failure is then "the background is still there", which a user can see and fix, rather than
  // "most of the drawing is gone", which is indistinguishable from a trace that found nothing.
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
      // Kotlin's `when` over `MatteMode` is exhaustive at compile time and this chain is not, so the
      // failure a new mode would cause here — reaching no branch, running nothing, saying nothing —
      // is written down instead. A background separation the user switched on and that silently did
      // not happen is precisely the bug this mode was added to fix.
      ctx.note(
        `Background separation was set to "${String(p.matte.mode)}", which this build does not ` +
          'know how to run, so the whole frame was traced.',
      );
    }
    if (alpha !== null) {
      let removed = 0;
      for (let i = 0; i < alpha.data.length; i++) if (alpha.data[i] < 0.5) removed++;
      const fraction = removed / alpha.data.length;
      // Composited over white, because ink is the dark class everywhere downstream and a transparent
      // background would read as black.
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

  // --- crop ----------------------------------------------------------------------------------------
  //
  // What this buys is not resolution — the crop is taken after the downscale, so the subject is traced
  // at the pixels it would have been anyway. It is that every global statistic downstream stops being
  // computed over the background: Otsu's threshold, Canny's auto thresholds and the ink cut are all
  // read from the whole frame's histogram, and a photograph that is 70% empty backdrop puts 70% of its
  // mass in a lump that has nothing to do with the subject.
  //
  // It refuses far more often than it acts, and deliberately. A crop is the most destructive thing in
  // this pipeline — everything outside it is gone from the export — so the bar is higher than for any
  // other automatic decision. When there was a box and it was declined, the note says so; when there
  // was no subject at all there is nothing to report and nothing is said.
  await ctx.begin(STAGE_CROP);
  let cropX = 0;
  let cropY = 0;
  if (p.auto.mode === AutoMode.APPLY && p.auto.allowCrop) {
    // The decision is `subject.locate`'s: it mattes the frame, bounds what the matte kept, and is
    // `confident` only when **both** halves are trustworthy. A busy background, a subject filling the
    // frame and a matte that kept almost nothing each produce a box that exists, looks plausible and
    // is wrong, and each refusal comes back in that module's own words rather than paraphrased here.
    const proxy = Resample.scaleToLongEdge(working, CROP_PROXY_LONG_EDGE);
    const find = Subject.locate(proxy, p.matte.tolerance);
    if (!find.confident) {
      ctx.note(`The working image was not cropped to the subject. ${find.reason}`);
    } else {
      const sxProxy = working.width / proxy.width;
      const syProxy = working.height / proxy.height;
      // The box's far edge is exclusive (`x + w`), so it scales as itself; the near edge floors and
      // the far edge ceils, which can only ever make the crop a fraction of a proxy pixel *larger*.
      // Rounding both ends to nearest would let a box lose up to half a proxy pixel — several working
      // pixels — off the right and bottom, and losing subject is the one direction this rounding is
      // not allowed to err in.
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

  // --- gray ----------------------------------------------------------------------------------------
  await ctx.begin(STAGE_GRAY);
  let grey = Color.toGray(working);
  if (p.preprocess.invertInput) grey = Contrast.invert(grey);
  ctx.end(STAGE_GRAY);

  // --- denoise -------------------------------------------------------------------------------------
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

  // --- contrast ------------------------------------------------------------------------------------
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

  // --- edge ----------------------------------------------------------------------------------------
  await ctx.begin(STAGE_EDGE);
  let mask = runEdgeStage(processedGray, p, ctx);
  ctx.end(STAGE_EDGE);

  // --- cleanup -------------------------------------------------------------------------------------
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

  // --- skeleton ------------------------------------------------------------------------------------
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
        // Each bridge consumes two endpoints, so the endpoint count always falls by an even number.
        ctx.note(
          `Bridged ${plural(Math.round((before - after) / 2), 'gap', 'gaps')} between stroke ends.`,
        );
      }
    }
  } else if (p.cleanup.bridgeGaps && p.cleanup.maxGap > 0) {
    // Outline mode still needs its topology closed, or a bucket fill leaks and a cut falls apart. The
    // skeleton is computed as scratch purely to find the endpoints, and only the *new* bridge pixels are
    // merged back — dilated to the closing radius so the bridge is as substantial as the strokes it joins.
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

  // --- distance ------------------------------------------------------------------------------------
  await ctx.begin(STAGE_DISTANCE);
  let dt: GrayF | null = null;
  if (p.output.modulateWidth) {
    // The DT of the mask *before* thinning: the distance from a skeleton pixel to the nearest background
    // pixel is the stroke's half-width, and a DT of the skeleton itself is zero everywhere.
    dt = Distance.euclidean(preSkeletonMask, true);
  }
  ctx.end(STAGE_DISTANCE);

  // --- vectorize -----------------------------------------------------------------------------------
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
      // `fillClosed` is the user-facing name for the same decision Vectorize calls `fillRegions`:
      // is a traced region a solid mark, or an outline with a paper interior? It has to reach the
      // tracer and not just the styler, because it also decides whether holes are traced at all.
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

  // --- document ------------------------------------------------------------------------------------
  await ctx.begin(STAGE_DOCUMENT);
  // With a crop there are **two** changes of frame between a traced point and an exported one, and
  // they do not commute:
  //
  //     p_source = (p_cropped + cropOffset) · (source / uncroppedWorking)
  //
  // The offset is in *un-cropped working* pixels, so it must be added **before** the scale.
  // `scale(s).times(translate(o))` is exactly that — the argument applies first — and writing it the
  // other way round puts every path off by the offset times the downscale factor, which on a 6000 px
  // photograph traced at 2048 is a three-fold error in the displacement only: the drawing is the right
  // size, in the wrong place, which reads as a rendering bug rather than a coordinate bug.
  //
  // The scale divides by the **un-cropped** working size, not the cropped one. The crop removed pixels;
  // it did not resample the ones that remain, so the pixel pitch is still the one the downscale
  // established. Dividing by the cropped size would stretch the subject to fill the whole page — an
  // error that grows with how much was cropped and is invisible on an image that was not cropped.
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
  // Named `traced`, not `document`: `document` is a DOM global under this tsconfig's lib, and a local that
  // shadows it makes a genuine accidental DOM reference in this file impossible to grep for.
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

/** Selects and runs the edge engine, returning an ink mask where `true` is ink. */
function runEdgeStage(grey: GrayF, p: TraceParams, ctx: RunContext): Mask {
  const e = p.edge;
  let engine = e.engine;
  if (engine === EdgeEngine.MODEL) {
    const model = e.modelId === '' ? null : edgeModelRegistry.byId(e.modelId);
    if (model === null || !model.isAvailable) {
      // A missing model is a note, never a failure: no weights ship with the app and the classical
      // engines are the product, not a fallback to apologise for.
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
        // Sauvola's k falls as sensitivity rises, which is the direction that keeps more ink.
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

/**
 * A fast, low-resolution trace for live parameter feedback.
 *
 * Shares every stage with {@link run} — the two can only ever differ in resolution, which is the whole
 * point: a preview that used different code would eventually disagree with the result it is previewing.
 *
 * @param longEdge working long edge for the preview, clamped by the params' own limits
 */
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
