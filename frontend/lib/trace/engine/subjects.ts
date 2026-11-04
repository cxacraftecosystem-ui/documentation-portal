import { SourceKind, SourceProfile, confidenceOfProfile, kindOf, nameOf } from './classify';
import {
  AutoMode,
  AutoParams,
  DenoiseMode,
  EdgeEngine,
  KNOB_NAMES,
  MatteMode,
  TraceParams,
  TraceParamsInput,
  VectorModeParam,
  knobsChangedBetween,
  restoreHandTuned,
  sanitizeTraceParams,
  withOverrides,
} from './params';

export interface SubjectPreset {
  readonly id: string;
  readonly name: string;
  readonly hint: string;
  readonly adjust: (p: TraceParams) => TraceParams;
}

function subject(
  id: string,
  name: string,
  hint: string,
  over: TraceParamsInput,
  separates = false,
): SubjectPreset {
  return {
    id,
    name,
    hint,
    adjust: (p: TraceParams): TraceParams => {
      const next = withOverrides(p, { ...over, styleId: p.styleId });
      return separates && next.matte.mode === MatteMode.NONE
        ? withOverrides(next, { matte: { mode: MatteMode.SUBJECT } })
        : next;
    },
  };
}

export const ALL: readonly SubjectPreset[] = [
  subject(
    'painting',
    'Painting',
    'Suppresses canvas weave and brush texture so the drawn forms survive, not the surface.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.7, claheClip: 1.6 },
      edge: { engine: EdgeEngine.FDOG, flow: { sigmaM: 4 } },
      cleanup: { minBlobArea: 48 },
    },
  ),
  subject(
    'pottery',
    'Pottery & ceramics',
    'Favours the long continuous profile of a thrown form, ignores glaze speckle, and separates ' +
      'away whatever the pot was standing on.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.6 },
      edge: { engine: EdgeEngine.FDOG, flow: { sigmaM: 5 } },
      cleanup: { minBlobArea: 64, maxGap: 20, closeRadius: 2 },
    },
    true,
  ),
  subject(
    'textile',
    'Textile & weaving',
    'Weave is periodic texture, not line work: anisotropic diffusion removes it before any edge ' +
      'detector can trace it.',
    {
      preprocess: { denoise: DenoiseMode.ANISOTROPIC, denoiseStrength: 0.85, claheClip: 1.4 },
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.4, flow: { sigmaM: 5, tensorSigma: 3 } },
      cleanup: { minBlobArea: 160, pruneSpurs: 6 },
    },
  ),
  subject(
    'jewellery',
    'Jewellery & filigree',
    'Keeps the thinnest detail: Laplacian-of-Gaussian crossings, almost no blob removal, and the ' +
      'cloth or tray underneath separated away.',
    {
      preprocess: { denoise: DenoiseMode.NONE, claheClip: 2.5 },
      edge: { engine: EdgeEngine.LOG, logSigma: 1.1, logSlope: 0.012 },
      cleanup: { minBlobArea: 6, closeRadius: 0, pruneSpurs: 2, removeIsolated: false },
      output: { simplify: 0.5, strokeWidth: 0.8, minPathLength: 2 },
    },
    true,
  ),
  subject(
    'sculpture',
    'Sculpture',
    'Form before surface: heavier smoothing, coarse detail, gaps bridged across shadow, and the ' +
      'gallery wall behind the piece separated away.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.6 },
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.45, flow: { sigmaM: 5 } },
      cleanup: { minBlobArea: 120, maxGap: 24, closeRadius: 2 },
      output: { smoothIterations: 2, simplify: 1.6 },
    },
    true,
  ),
  subject(
    'architecture',
    'Architecture',
    'Straight-line bias with corners protected and long bridges across mortar joints and glazing bars.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.35 },
      edge: { engine: EdgeEngine.CANNY, blurSigma: 1.4 },
      cleanup: { minBlobArea: 48, maxGap: 32, maxBridgeAngle: 35, pruneSpurs: 6 },
      output: { corner: 150, smoothIterations: 0, simplify: 2, fitError: 3 },
    },
  ),
  subject(
    'logo',
    'Logo & flat graphic',
    'Already bi-level: adaptive thresholding recovers the true strokes, where an edge detector would ' +
      'trace both sides of every one and double it.',
    {
      preprocess: { denoise: DenoiseMode.NONE, claheEnabled: false },
      edge: { engine: EdgeEngine.ADAPTIVE, useSauvola: true, adaptiveRadius: 21 },
      cleanup: { minBlobArea: 24, skeletonize: false, closeRadius: 1 },
      output: { vectorMode: VectorModeParam.OUTLINE, fillClosed: true, simplify: 1, fitError: 1.2 },
    },
  ),
  subject(
    'sketch',
    'Sketch & drawing',
    'Scanned pencil or ink: no denoise, no local contrast, and the strokes taken as they are.',
    {
      preprocess: { denoise: DenoiseMode.MEDIAN, medianRadius: 1, claheEnabled: false },
      edge: { engine: EdgeEngine.ADAPTIVE, useSauvola: true, adaptiveRadius: 15, adaptiveC: 0.02 },
      cleanup: { minBlobArea: 16, pruneSpurs: 3 },
      output: { simplify: 0.8, modulateWidth: true, widthScale: 0.9 },
    },
  ),
  subject(
    'photo',
    'Photograph',
    'A general photograph: sensor noise removed, local contrast raised, coherent lines only.',
    {
      preprocess: { denoise: DenoiseMode.BILATERAL, denoiseStrength: 0.5, claheEnabled: true },
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.5 },
      cleanup: { minBlobArea: 32 },
    },
  ),
  subject(
    'carving',
    'Wood & stone carving',
    'Grain and tool marks are texture; the incised line is not. Diffusion first, then flow-based edges.',
    {
      preprocess: { denoise: DenoiseMode.ANISOTROPIC, denoiseStrength: 0.75, claheClip: 2.5 },
      edge: { engine: EdgeEngine.FDOG, sensitivity: 0.55, flow: { sigmaM: 4, tensorSigma: 2.5 } },
      cleanup: { minBlobArea: 100, closeRadius: 2, pruneSpurs: 5 },
    },
  ),
];

const BY_ID = new Map<string, SubjectPreset>(ALL.map((s) => [s.id, s]));

export function byId(id: string): SubjectPreset | null {
  return BY_ID.get(id) ?? null;
}

const TEXTURE_COLOUR_SPLIT = 0.12;

export function suggestFor(profile: SourceProfile): SubjectPreset {
  switch (kindOf(profile)) {
    case SourceKind.LINE_ART:
      return byId('sketch') ?? ALL[7];
    case SourceKind.FLAT_GRAPHIC:
      return byId('logo') ?? ALL[6];
    case SourceKind.TEXTURED:
      return profile.saturationSpread < TEXTURE_COLOUR_SPLIT
        ? byId('carving') ?? ALL[9]
        : byId('textile') ?? ALL[2];
    case SourceKind.SMOOTH_OBJECT:
      return byId('pottery') ?? ALL[1];
    default:
      return byId('photo') ?? ALL[8];
  }
}

export interface AutoDecision {
  readonly params: TraceParams;
  readonly applied: boolean;
  readonly kind: SourceKind;
  readonly confidence: number;
  readonly subject: SubjectPreset | null;
  readonly notes: readonly string[];
}

function preserveIdentity(before: TraceParams, after: TraceParams): TraceParams {
  return {
    ...after,
    edge: { ...after.edge, engine: before.edge.engine },
    output: {
      ...after.output,
      vectorMode: before.output.vectorMode,
      fillClosed: before.output.fillClosed,
    },
    auto: before.auto,
    styleId: before.styleId,
  };
}

function matteMode(params: TraceParams, auto: AutoParams, profile: SourceProfile): MatteMode {
  if (params.matte.mode !== MatteMode.NONE) return params.matte.mode;
  if (auto.allowMatte && profile.separableSubject === true) return MatteMode.SUBJECT;
  return MatteMode.NONE;
}

function denoiseName(mode: DenoiseMode): string {
  switch (mode) {
    case DenoiseMode.NONE:
      return 'off';
    case DenoiseMode.BILATERAL:
      return 'bilateral';
    case DenoiseMode.MEDIAN:
      return 'median';
    default:
      return 'anisotropic diffusion';
  }
}

function describeChanges(before: TraceParams, after: TraceParams): string[] {
  const out: string[] = [];
  if (before.preprocess.denoise !== after.preprocess.denoise) {
    out.push(`the noise filter (${denoiseName(after.preprocess.denoise)})`);
  } else if (before.preprocess.denoiseStrength !== after.preprocess.denoiseStrength) {
    out.push('the denoising strength');
  }
  if (before.preprocess.claheEnabled !== after.preprocess.claheEnabled) {
    out.push(after.preprocess.claheEnabled ? 'local contrast, now on' : 'local contrast, now off');
  }
  if (before.preprocess.claheClip !== after.preprocess.claheClip) out.push('the contrast limit');
  if (before.preprocess.workingLongEdge !== after.preprocess.workingLongEdge) {
    out.push(`the working resolution (${after.preprocess.workingLongEdge} px)`);
  }
  if (before.matte.mode !== after.matte.mode) out.push('background separation');
  out.push(...knobsChangedBetween(before, after));
  if (before.output.smoothIterations !== after.output.smoothIterations) out.push('path smoothing');
  if (before.output.fitError !== after.output.fitError) out.push('the curve fitting tolerance');
  if (
    before.cleanup.closeRadius !== after.cleanup.closeRadius ||
    before.cleanup.maxGap !== after.cleanup.maxGap
  ) {
    out.push('gap closing');
  }
  if (before.cleanup.pruneSpurs !== after.cleanup.pruneSpurs) out.push('spur pruning');
  if (before.cleanup.skeletonize !== after.cleanup.skeletonize) out.push('centreline thinning');
  if (
    before.edge.flow.sigmaM !== after.edge.flow.sigmaM ||
    before.edge.flow.tensorSigma !== after.edge.flow.tensorSigma
  ) {
    out.push('the flow field');
  }
  return out;
}

function list(items: readonly string[]): string {
  if (items.length === 0) return 'nothing';
  if (items.length === 1) return items[0];
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

function percent(v: number): number {
  if (!Number.isFinite(v)) return 0;
  const p = Math.round(v * 100);
  return p < 0 ? 0 : p > 100 ? 100 : p;
}

export function autoDecide(profile: SourceProfile | null, params: TraceParams): AutoDecision {
  const auto = params.auto;
  if (auto.mode === AutoMode.OFF) {
    return {
      params,
      applied: false,
      kind: SourceKind.UNKNOWN,
      confidence: 0,
      subject: null,
      notes: [],
    };
  }
  if (profile === null) {
    return {
      params,
      applied: false,
      kind: SourceKind.UNKNOWN,
      confidence: 0,
      subject: null,
      notes: [
        'Auto-detection was switched on but this trace did not classify the source, so nothing was ' +
          'detected and no setting was changed.',
      ],
    };
  }

  const kind = profile.kind ?? SourceKind.UNKNOWN;
  const confidence = confidenceOfProfile(profile);
  const name = nameOf(kind);
  const sure = percent(confidence);

  if (auto.subjectId.length > 0) {
    const chosen = byId(auto.subjectId);
    return {
      params,
      applied: false,
      kind,
      confidence,
      subject: chosen,
      notes: [
        `Auto-detection read this as ${name} (${sure}% sure) but you had already chosen ` +
          `"${chosen?.name ?? auto.subjectId}", so your choice was kept and nothing was changed.`,
      ],
    };
  }

  if (kind === SourceKind.UNKNOWN) {
    return {
      params,
      applied: false,
      kind,
      confidence,
      subject: null,
      notes: [
        'Auto-detection found nothing in this frame it could classify, so every setting was left ' +
          'exactly as you had it.',
      ],
    };
  }
  if (confidence < auto.minConfidence) {
    return {
      params,
      applied: false,
      kind,
      confidence,
      subject: null,
      notes: [
        `Auto-detection was not sure enough to act — its best guess was ${name} at ${sure}% and it ` +
          `needs ${percent(auto.minConfidence)}% — so every setting was left exactly as you had it. ` +
          'Pick a subject by hand if that guess was right.',
      ],
    };
  }

  const subject = suggestFor(profile);
  const adjusted = preserveIdentity(params, subject.adjust(params));
  const matted = { ...adjusted, matte: { ...adjusted.matte, mode: matteMode(params, auto, profile) } };
  const restored = sanitizeTraceParams(restoreHandTuned(params, matted, auto.handTuned));

  const changed = describeChanges(params, restored);
  const kept = auto.handTuned.filter((n) => KNOB_NAMES.includes(n));
  const notes: string[] = [];
  notes.push(
    changed.length === 0
      ? `Auto-detection read this as ${name} (${sure}% sure) and chose "${subject.name}", which ` +
          'asked for nothing your settings did not already do.'
      : `Auto-detection read this as ${name} (${sure}% sure) and applied "${subject.name}", which ` +
          `changed ${list(changed)}.`,
  );
  if (kept.length > 0) {
    notes.push(
      `It left ${list(kept)} exactly as you set ${kept.length === 1 ? 'it' : 'them'} by hand.`,
    );
  }
  if (auto.mode === AutoMode.SUGGEST) {
    return {
      params,
      applied: false,
      kind,
      confidence,
      subject,
      notes: [
        'Auto-detection is set to suggest only, so nothing below was actually changed.',
        ...notes,
      ],
    };
  }
  return { params: restored, applied: true, kind, confidence, subject, notes };
}
