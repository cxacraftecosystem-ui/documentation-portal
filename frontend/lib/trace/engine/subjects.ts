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

/**
 * Subject presets — the ten materials of FEATURES.md §8.
 *
 * A subject is a **modifier on a style, not a second style list**. It nudges denoise, blob area and engine
 * choice for the *material* while leaving the look the style chose intact, which is why `adjust` takes and
 * returns a whole {@link TraceParams} rather than being a parameter table of its own.
 *
 * By contract the UI must say when a subject changed a parameter the user had set by hand. That is the
 * caller's job, but it is why `adjust` is a pure function of its input: the caller can diff before/after.
 */

export interface SubjectPreset {
  readonly id: string;
  readonly name: string;
  /** One sentence explaining what this subject changes and why, for display next to the control. */
  readonly hint: string;
  readonly adjust: (p: TraceParams) => TraceParams;
}

/**
 * @param separates whether this subject is a **discrete object photographed standing on or in front
 *   of something else**, and therefore has a background in the frame that is not part of the drawing.
 *   Those subjects ask for {@link MatteMode.SUBJECT} — and only when nothing else has already asked
 *   for something, because a style that named a matte and a user who moved the control both *decided*
 *   while a subject preset only carries a prior about the material. That "only when NONE" is also
 *   what keeps `adjust` idempotent, which the UI relies on: it re-adjusts on every edit.
 *
 *   `SUBJECT` and not `BORDER_FLOOD` because it is the only mode that can decline: the fused matte
 *   reports a confidence and the matte stage refuses below it, so a preset that is wrong about *this*
 *   photograph costs a background that stayed, not a subject that went.
 */
function subject(
  id: string,
  name: string,
  hint: string,
  over: TraceParamsInput,
  separates = false,
): SubjectPreset {
  // The override is applied through `withOverrides`, so a subject cannot introduce an out-of-range value
  // and cannot change `styleId` — the style is still the style, adjusted.
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

/**
 * All ten subjects, in the order FEATURES.md §8 lists them.
 *
 * Three of them ask for a background matte — pottery, jewellery, sculpture — and the division is not
 * about how photographic the material is, it is about whether the frame contains a background at all.
 * Those three are discrete objects photographed standing on or in front of something else: there is a
 * subject, there is a backdrop, and the backdrop is not part of the drawing.
 *
 * The other seven are the cases where a matte has nothing to find and can only subtract. `logo` and
 * `sketch` are a flat graphic and a scan, whose paper *is* the picture's ground — a matte there would
 * be cutting the drawing out of its own page. `painting`, `textile` and `carving` are surfaces
 * photographed face-on, so any split a matte finds is a split *inside* the artwork. `architecture`
 * looks like an object and is not: a building's "background" is sky, street and neighbouring facades,
 * all deliberately in frame, and it reaches every edge besides. `photo` is the general case where by
 * definition nothing about the frame is known, and guessing "there is a subject" is exactly the guess
 * it exists not to make — when the frame really does hold one, {@link autoDecide} measures that and
 * switches the matte on itself.
 */
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

/** @returns the subject with that id, or null. */
export function byId(id: string): SubjectPreset | null {
  return BY_ID.get(id) ?? null;
}

/** Saturation spread below which a textured surface is mineral rather than woven. */
const TEXTURE_COLOUR_SPLIT = 0.12;

/**
 * Map a measured source profile onto the subject most likely to suit it.
 *
 * A decision list over {@link SourceKind}, not a score, so it is explainable in one line to a user who
 * disagrees with it — and disagreeing is expected. What the caller *does* with the answer depends on
 * the profile's confidence; this function only says which subject the class implies.
 *
 * A profile that was never measured falls back to §12's flag ladder inside {@link kindOf}, so a
 * hand-constructed profile still maps to the subject its flags describe.
 *
 * @returns always a preset; `photo` is the fallback because it is the least opinionated of the ten.
 */
export function suggestFor(profile: SourceProfile): SubjectPreset {
  switch (kindOf(profile)) {
    case SourceKind.LINE_ART:
      return byId('sketch') ?? ALL[7];
    case SourceKind.FLAT_GRAPHIC:
      return byId('logo') ?? ALL[6];
    case SourceKind.TEXTURED:
      // Heavy texture with almost no colour variation is a mineral surface; heavy texture *with*
      // colour variation is cloth. Both need the texture suppressed, by different filters — which is
      // the one decision saturation spread is the right measurement for.
      return profile.saturationSpread < TEXTURE_COLOUR_SPLIT
        ? byId('carving') ?? ALL[9]
        : byId('textile') ?? ALL[2];
    case SourceKind.SMOOTH_OBJECT:
      // A single object on a clean sweep: curved, glazed or turned, with no true corners and a
      // silhouette that is the drawing.
      return byId('pottery') ?? ALL[1];
    default:
      return byId('photo') ?? ALL[8];
  }
}

/**
 * What {@link autoDecide} concluded.
 *
 * @property params the parameters to trace with. The same object as the input whenever `applied` is
 *   false, so a caller can test cheaply for "nothing happened".
 * @property notes one sentence per decision, **including the decisions not to act**. An automatic
 *   change nobody can see is indistinguishable from a bug, and an automatic change that was
 *   *declined* is indistinguishable from one that never ran — so both are written down.
 */
export interface AutoDecision {
  readonly params: TraceParams;
  readonly applied: boolean;
  readonly kind: SourceKind;
  readonly confidence: number;
  readonly subject: SubjectPreset | null;
  readonly notes: readonly string[];
}

/**
 * Restores the three fields that make a style what it is.
 *
 * A {@link SubjectPreset} in this engine is an *absolute* override table and several of them do set
 * `engine` and `vectorMode` — which is correct when a human picks the subject, and wrong on the
 * automatic path, because "colouring book" meaning outline mode is the style's identity and no fact
 * about the material may turn it into a centreline trace. This is the one place a preset is applied
 * *without a user having asked for it*, so it is the one place that has to enforce the boundary.
 */
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

/**
 * The matte the automatic path runs with — decided from the *measurement*, not inherited from the
 * subject the measurement chose.
 *
 * Three rules, in order, and the order is the argument:
 *
 * 1. **A matte already on the tree is a decision and is returned untouched.** It came from the style
 *    or from the user's own control, and neither a classifier nor a subject table outranks either.
 * 2. **Otherwise the profile decides.** `separableSubject` is the measured statement that this frame
 *    holds one subject on a background clean enough to separate, which is the precondition for a
 *    matte and the only evidence here that is about the picture in front of us. It answers `SUBJECT`
 *    and not `BORDER_FLOOD` because the fused matte already contains the flood as its connectivity
 *    cue, adds a colour posterior that survives a subject touching the frame edge (the flood's
 *    outright failure case), and — the part that matters on a path nobody asked for — reports a
 *    confidence the matte stage can refuse on. A bare flood cannot say "I do not believe this".
 * 3. **Anything else gets none, including a matte the subject preset asked for.** That subtraction is
 *    deliberate: `pottery` asking for a matte is a prior about the material ("a pot stands on
 *    something"), while `separableSubject` is a measurement of this photograph. A pot shot on a
 *    cluttered workbench has no background to remove, the measurement says so, and the measurement
 *    wins. Without this the automatic path would inherit the preset's assumption and the profile's
 *    answer would be decoration.
 */
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

/**
 * The user-visible settings that differ between two parameter trees, named as the editor names them.
 *
 * Wider than {@link KNOB_NAMES} on purpose: those are the knobs the *user* can move and therefore the
 * ones auto must not overwrite, while this is what auto must *confess to*, which includes settings
 * that have no slider. A denoise filter switched from bilateral to anisotropic diffusion changes the
 * result more than any slider here and would otherwise be invisible.
 */
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

/** "a", "a and b", "a, b and c" — a list a person reads, not a comma-joined dump. */
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

/**
 * Automatic source detection, applied.
 *
 * ALGORITHMS §12 has always required the classification to be *shown* with a one-tap override and
 * never applied invisibly. This function is what makes "applied" and "invisibly" separable: it applies
 * the detected subject and then writes down, in {@link AutoDecision.notes}, the class it detected, how
 * sure it was, and the name of every setting it moved. Those sentences reach the user through
 * `TraceResult.notes`, which the UI is required to render.
 *
 * Four rules bound what it may do, and each exists because the alternative is a change the user cannot
 * attribute:
 *
 * 1. **It never chooses when the user has already chosen.** A non-empty `subjectId` is a decision, and
 *    a detector that overrules one is a detector nobody can use.
 * 2. **It never overwrites a hand-tuned knob**, and the note says which knobs were kept.
 * 3. **It never changes the style's identity** — see {@link preserveIdentity}.
 * 4. **Below `minConfidence` it does nothing and says so.** A wrong automatic choice is worse than no
 *    choice, because the user cannot tell that it happened.
 * 5. **The background matte is decided by the measurement, not by the subject it chose** — see
 *    {@link matteMode}, which switches `SUBJECT` on for a measured separable subject and off again
 *    for a preset that asked for one on a frame with no separable subject in it.
 *
 * @param profile the measured source, or null if classification did not run — in which case nothing is
 *   applied and a note says why, because an auto mode that silently does nothing is the exact failure
 *   this function was written to remove.
 */
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

  // The profile's *own* kind, not `kindOf`'s flag fallback. The fallback exists so that a hand-built
  // profile still maps to a sensible subject, and a mapping is not a classification: nothing measured
  // it, so nothing scored it, so the confidence beside it describes nothing. Acting on that pair would
  // be acting on a guess with a made-up certainty.
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
    // Not a low score but the absence of one: a blank frame, or a profile nothing measured. Reporting
    // it as "best guess: unclassified at 0%" would read as a failed classification rather than as
    // there being nothing there to classify.
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
    // The sentences above are written in the past tense because that is what they describe in APPLY
    // mode; in SUGGEST mode the whole set is prefixed rather than reworded, so the two modes cannot
    // drift into describing different things.
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
