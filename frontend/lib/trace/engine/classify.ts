import { GrayF, RgbaImage } from './buffers';
import { Channel, channel, toGray } from './color';
import {
  GradientOp,
  gaussianBlur,
  gradients,
  rectSum,
  summedAreaTable,
} from './convolve';
import * as Distance from './distance';
import { detectAuto } from './edgeCanny';
import { scaleToLongEdge } from './resample';
import { fixed, otsu, otsuSeparability } from './threshold';

/**
 * Source classification. See ALGORITHMS.md §12, and `Classify.kt`, which this mirrors statistic for
 * statistic.
 *
 * Cheap deterministic measurements, not a neural net — and, since the pipeline now *acts* on them, a
 * per-class **confidence** as well as the flags.
 *
 * ### Why the confidence exists
 *
 * §12 was written when the classification was only ever shown. A label that is wrong costs the user
 * one glance; a label that is wrong and has been applied costs them a trace they cannot explain,
 * because the settings they can see are not the settings that ran. {@link SourceProfile.confidence}
 * is built from two independent parts — how much evidence the winning class has, and how far ahead of
 * the runner-up it is — so that a frame scoring 0.8 for two classes at once reads as *ambiguous*
 * rather than as confident, and a caller that refuses to act below a threshold behaves correctly on
 * exactly the frames where the image genuinely could be either thing.
 */

/** What the source *is*, as one of five mutually exclusive answers. See `Classify.SourceKind`. */
export enum SourceKind {
  /** Not classified. Carried by a profile that was constructed by hand rather than measured. */
  UNKNOWN = 'UNKNOWN',
  LINE_ART = 'LINE_ART',
  FLAT_GRAPHIC = 'FLAT_GRAPHIC',
  TEXTURED = 'TEXTURED',
  SMOOTH_OBJECT = 'SMOOTH_OBJECT',
  PHOTOGRAPH = 'PHOTOGRAPH',
}

/**
 * Evidence for each class, 0..1 and **not** normalised to sum to 1.
 *
 * They are not probabilities: two classes may score 0.9 at once, and that co-occurrence is precisely
 * the signal {@link SourceProfile.confidence} reads. A softmax over two 0.9s and over two 0.3s
 * produces the same pair of 0.5s, and only one of those two frames is worth acting on.
 */
export interface ClassScores {
  readonly lineArt: number;
  readonly flatGraphic: number;
  readonly textured: number;
  readonly smoothObject: number;
  readonly photograph: number;
}

/**
 * What {@link profile} measured.
 *
 * Everything after `suggestion` is **optional**, and the reason is a compatibility one rather than a
 * modelling one: a profile is a value that crosses versions — it comes out of a restored project and
 * out of a hand-built fixture as often as out of a measurement — and a caller holding only the four
 * statistics §12 originally specified still has a legal profile. Every consumer reads the optional
 * fields through a default of "no evidence", which is what makes {@link SourceKind.UNKNOWN} and a
 * confidence of 0 the honest answer for such a profile rather than an accidental classification.
 * `Classify.kt` spells the same thing as constructor defaults on a data class.
 */
export interface SourceProfile {
  /** Otsu's `sigma_b^2 / sigma_total^2`. Above 0.75 with modes near the ends means "already bi-level". */
  readonly bimodality: number;
  /** Fraction of pixels surviving Canny at auto thresholds. Above 0.18 means "high texture". */
  readonly edgeDensity: number;
  /** Shannon entropy of the magnitude-weighted gradient orientation histogram, normalised to 0..1. */
  readonly orientationEntropy: number;
  /** Standard deviation of HSV saturation across the image. */
  readonly saturationSpread: number;
  readonly isLineArt: boolean;
  readonly isHighTexture: boolean;
  readonly isFlatGraphic: boolean;
  /** A {@link module:styles} preset id. Never empty. */
  readonly suggestion: string;
  /** 0..1; 1 is a perfectly even backdrop. Below ~0.6 there is no clean background at all. */
  readonly backgroundUniformity?: number;
  /** Fraction of the frame that differs from the border colour, 0..1. */
  readonly subjectCoverage?: number;
  /** Mean ink stroke width in *proxy* pixels, from the ridge of the distance transform. */
  readonly strokeWidthPx?: number;
  /** 0..1; 1 is "every stroke the same width", which a pen produces and a photograph never does. */
  readonly strokeWidthConsistency?: number;
  /** 4-bit-per-channel colour cells holding at least 0.2% of the pixels. */
  readonly colourCount?: number;
  /** Fraction of the frame spent in the four most-used colour cells, 0..1. */
  readonly paletteFlatness?: number;
  /** Fraction of gradient energy in the strongest orientation and its two neighbours, 0..1. */
  readonly orientationConcentration?: number;
  /** Radians in `[0, pi)` at the centre of the strongest orientation bin. */
  readonly dominantOrientation?: number;
  /** Fraction of the frame whose local standard deviation exceeds the texture floor, 0..1. */
  readonly textureEnergy?: number;
  /** Whether there is one subject on a background clean enough for a matte to be worth running. */
  readonly separableSubject?: boolean;
  readonly scores?: ClassScores;
  readonly kind?: SourceKind;
  /** 0..1 in {@link kind}. A consumer that *acts* must have a floor on this. */
  readonly confidence?: number;
}

/**
 * Figure/ground, measured from the frame's own border.
 *
 * The border ring is the only part of a photograph that is background with near certainty — a subject
 * running off all four edges is not one anybody is cutting out — so its colour statistics answer both
 * "is the backdrop clean" and "how much of the frame is not the backdrop" in one pass, with no seeds,
 * no matte, no model and no user input.
 *
 * Two numbers and no rectangle, deliberately. Where the subject *is* is a decision, and it belongs to
 * `subject.locate`: that one mattes the frame properly and refuses in four documented ways. This is
 * the classifier's own estimate — it has to run before any matte exists and on every profile, so it
 * has to be a ring statistic rather than a segmentation, and a box derived from it would be a second,
 * cheaper, slightly different answer to a question that already has an owner.
 */
export interface FigureGround {
  readonly backgroundUniformity: number;
  readonly coverage: number;
}

/**
 * Long edge of the proxy every statistic is measured on; classification is a coarse-scale question,
 * and measuring at full resolution measures the sensor rather than the subject.
 *
 * Matched to `Classify.PROXY_LONG_EDGE` in the Kotlin engine. The stroke-width statistic is in proxy
 * pixels, so the two engines disagreeing about the proxy size would make them disagree about the
 * classification of the same photograph.
 */
export const PROXY_LONG_EDGE = 512;

const ORIENTATION_BINS = 36;

const BIMODALITY_LINE_ART = 0.75;
const EDGE_DENSITY_TEXTURE = 0.18;
const ENTROPY_FLAT_GRAPHIC = 0.72;
const FLAT_GRAPHIC_MAX_COLOURS = 16;
const SATURATION_FLAT_GRAPHIC = 0.32;

/**
 * Stroke-width consistency below which a bimodal source is **not** line art.
 *
 * §12 warns that separability plus two end-of-range modes is not enough and names the photograph of a
 * light object on a dark ground. It is not the only counter-example: a two-colour poster is
 * *perfectly* bimodal with both modes at the ends, and thresholding it as if it were a pen drawing
 * throws away the fact that it is a region and not a stroke. Measured on a three-colour flat graphic
 * the consistency is 0.48 against a pen drawing's 0.93; 0.6 sits in the middle of that gap.
 */
const LINE_ART_STROKE_CONSISTENCY = 0.6;

/** Local standard deviation above which a pixel counts as textured, and the radius it is read over. */
const TEXTURE_LOCAL_STDDEV = 0.06;
const TEXTURE_RADIUS = 3;

/** Two 8-bit code values: below this dynamic range there is no content to classify. */
const BLANK_RANGE = 2 / 255;

const BORDER_BAND_FRACTION = 0.06;
const RING_SPREAD_FULL = 32;
const RING_TOLERANCE_BASE = 14;
const RING_TOLERANCE_GAIN = 2.5;
const RING_TOLERANCE_MAX = 96;

const CLEAN_BACKGROUND = 0.62;
const MIN_SUBJECT_COVERAGE = 0.02;
const MAX_SUBJECT_COVERAGE = 0.75;

// Style ids from FEATURES.md §6, written as literals rather than imported: `styles.ts` is a table of
// presets and importing it here would drag the whole preset list into the classifier for five strings.
// The ids are binding and persisted, so they cannot drift silently.
const STYLE_SINGLE_STROKE = 'single-stroke';
const STYLE_CLEAN_LINE = 'clean-line';
const STYLE_MINIMAL = 'minimal';
const STYLE_STENCIL = 'stencil';
const STYLE_SILHOUETTE = 'silhouette';

const NO_SCORES: ClassScores = {
  lineArt: 0,
  flatGraphic: 0,
  textured: 0,
  smoothObject: 0,
  photograph: 0,
};

function clamp01(v: number): number {
  if (!Number.isFinite(v)) return 0;
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/**
 * Linear ramp: 0 at or below `lo`, 1 at or above `hi`.
 *
 * Every threshold here is a ramp rather than a step, because a step at `x = 0.75` makes two frames
 * that differ by one pixel classify differently and a user cannot be told why. A ramp turns that
 * cliff into a confidence the caller can refuse to act on.
 */
function ramp(v: number, lo: number, hi: number): number {
  if (hi <= lo) return v >= hi ? 1 : 0;
  return clamp01((v - lo) / (hi - lo));
}

/**
 * Measure a source and classify it.
 * @returns a fully populated profile; every field is finite even for a 1x1 or single-colour image,
 *          and a frame with no dynamic range comes back as {@link SourceKind.UNKNOWN} at zero
 *          confidence rather than as whichever class scores highest on an image of nothing.
 */
export function profile(src: RgbaImage): SourceProfile {
  const proxy = scaleToLongEdge(src, PROXY_LONG_EDGE);
  const gray = toGray(proxy);

  const bimodality = otsuSeparability(gray);
  const edges = detectAuto(gray, 1);
  const edgeDensity = edges.countTrue() / edges.size;
  const orientation = orientationStats(gray);
  const saturationSpread = standardDeviation(channel(proxy, Channel.SATURATION));
  const palette = paletteOf(proxy);
  const ground = figureGround(proxy);
  const t = otsu(gray);
  const strokes = strokeStats(gray, t);
  const texture = textureEnergy(gray);

  // "Bi-level" needs more than a high separability score: a photograph of a white sculpture against a
  // black cloth also separates cleanly, and so does a two-colour poster. The class means must sit near
  // the ends of the range *and* the strokes must have one width — see LINE_ART_STROKE_CONSISTENCY.
  const modes = classMeans(gray, t);
  const isLineArt =
    bimodality > BIMODALITY_LINE_ART &&
    modes.low < 0.35 &&
    modes.high > 0.65 &&
    strokes.consistency >= LINE_ART_STROKE_CONSISTENCY;
  const isHighTexture = edgeDensity > EDGE_DENSITY_TEXTURE;
  const isFlatGraphic =
    !isLineArt &&
    !isHighTexture &&
    orientation.entropy < ENTROPY_FLAT_GRAPHIC &&
    saturationSpread < SATURATION_FLAT_GRAPHIC &&
    palette.count <= FLAT_GRAPHIC_MAX_COLOURS;

  const separableSubject =
    ground.backgroundUniformity >= CLEAN_BACKGROUND &&
    ground.coverage >= MIN_SUBJECT_COVERAGE &&
    ground.coverage <= MAX_SUBJECT_COVERAGE;

  const base = {
    bimodality,
    edgeDensity,
    orientationEntropy: orientation.entropy,
    saturationSpread,
    isLineArt,
    isHighTexture,
    isFlatGraphic,
    backgroundUniformity: ground.backgroundUniformity,
    subjectCoverage: ground.coverage,
    strokeWidthPx: strokes.meanWidth,
    strokeWidthConsistency: strokes.consistency,
    colourCount: palette.count,
    paletteFlatness: palette.flatness,
    orientationConcentration: orientation.concentration,
    dominantOrientation: orientation.dominant,
    textureEnergy: texture,
  };

  // A frame with no dynamic range has nothing in it to classify, and every statistic above is
  // measuring round-off. Without this guard a pure white page classified as a flat graphic at 69%
  // confidence — a confident answer about an empty page.
  const span = gray.range();
  if (span.hi - span.lo < BLANK_RANGE) {
    return {
      ...base,
      suggestion: STYLE_CLEAN_LINE,
      separableSubject: false,
      scores: NO_SCORES,
      kind: SourceKind.UNKNOWN,
      confidence: 0,
    };
  }

  const scores = score({
    bimodality,
    modeSplit: modes.high - modes.low,
    edgeDensity,
    orientationEntropy: orientation.entropy,
    colourCount: palette.count,
    paletteFlatness: palette.flatness,
    backgroundUniformity: ground.backgroundUniformity,
    subjectCoverage: ground.coverage,
    strokeConsistency: strokes.consistency,
    textureEnergy: texture,
    isLineArt,
    isHighTexture,
    isFlatGraphic,
  });
  const kind = winner(scores);

  // The order of this ladder is the priority order. "Already line art" wins because running an edge
  // detector over existing strokes traces *both sides of every stroke* and doubles every line, which
  // is a worse failure than choosing a slightly wrong style for a photograph.
  let suggestion: string;
  if (kind === SourceKind.LINE_ART) suggestion = STYLE_SINGLE_STROKE;
  else if (kind === SourceKind.FLAT_GRAPHIC) suggestion = STYLE_STENCIL;
  else if (kind === SourceKind.TEXTURED) suggestion = STYLE_MINIMAL;
  else if (kind === SourceKind.SMOOTH_OBJECT) suggestion = STYLE_SILHOUETTE;
  else suggestion = STYLE_CLEAN_LINE;

  return {
    ...base,
    suggestion,
    separableSubject,
    scores,
    kind,
    confidence: confidenceOf(scores, kind),
  };
}

/**
 * The kind a profile implies, falling back to the §12 flags when it was never measured.
 *
 * The fallback keeps a hand-constructed profile meaningful instead of classifying it as whatever the
 * enum's first entry happens to be. Its order is §12's decision list, line art first.
 */
export function kindOf(p: SourceProfile): SourceKind {
  if (p.kind !== undefined && p.kind !== SourceKind.UNKNOWN) return p.kind;
  if (p.isLineArt) return SourceKind.LINE_ART;
  if (p.isFlatGraphic) return SourceKind.FLAT_GRAPHIC;
  if (p.isHighTexture) return SourceKind.TEXTURED;
  return SourceKind.PHOTOGRAPH;
}

/** Sentence-case name of a kind, for the one place a UI has to print it. */
export function nameOf(kind: SourceKind): string {
  switch (kind) {
    case SourceKind.LINE_ART:
      return 'line art';
    case SourceKind.FLAT_GRAPHIC:
      return 'flat graphic';
    case SourceKind.TEXTURED:
      return 'textured surface';
    case SourceKind.SMOOTH_OBJECT:
      return 'object on a clean background';
    case SourceKind.PHOTOGRAPH:
      return 'photograph';
    default:
      return 'unclassified';
  }
}

/** The confidence of a profile that may not carry one. Absent means "never measured", i.e. none. */
export function confidenceOfProfile(p: SourceProfile): number {
  return typeof p.confidence === 'number' && Number.isFinite(p.confidence) ? p.confidence : 0;
}

/**
 * How clean the backdrop is, and how much of the frame is not it.
 *
 * The reference is the ring's mean **plus its spread**, not a fixed tolerance: a photograph on white
 * card and one on a mottled cloth need completely different distances to mean the same thing, and a
 * fixed number picks one of them and is wrong about the other.
 *
 * A fully transparent pixel is background whatever its colour channels say, so an already cut-out PNG
 * measures as the cut-out it is rather than as its meaningless matte colour.
 */
export function figureGround(src: RgbaImage): FigureGround {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const px = src.pixels;
  const band = Math.max(1, Math.round(Math.min(w, h) * BORDER_BAND_FRACTION));

  let sumR = 0;
  let sumG = 0;
  let sumB = 0;
  let sumRR = 0;
  let sumGG = 0;
  let sumBB = 0;
  let ring = 0;
  for (let y = 0; y < h; y++) {
    const edgeRow = y < band || y >= h - band;
    const row = y * w;
    let x = 0;
    while (x < w) {
      if (!edgeRow && x === band && w - band > band) {
        // Skip the interior of a non-edge row in one jump rather than testing every pixel of a 4 MP
        // frame for membership of a 6% ring.
        x = w - band;
        continue;
      }
      const p = px[row + x];
      const r = (p >>> 16) & 0xff;
      const g = (p >>> 8) & 0xff;
      const b = p & 0xff;
      sumR += r;
      sumG += g;
      sumB += b;
      sumRR += r * r;
      sumGG += g * g;
      sumBB += b * b;
      ring++;
      x++;
    }
  }
  if (ring <= 0) return { backgroundUniformity: 0, coverage: 0 };

  const inv = 1 / ring;
  const mR = sumR * inv;
  const mG = sumG * inv;
  const mB = sumB * inv;
  const vR = Math.max(0, sumRR * inv - mR * mR);
  const vG = Math.max(0, sumGG * inv - mG * mG);
  const vB = Math.max(0, sumBB * inv - mB * mB);
  const sd = Math.sqrt((vR + vG + vB) / 3);
  const uniformity = clamp01(1 - sd / RING_SPREAD_FULL);
  const tolerance = Math.min(RING_TOLERANCE_MAX, RING_TOLERANCE_BASE + RING_TOLERANCE_GAIN * sd);
  const tolSq = tolerance * tolerance;

  let subject = 0;
  for (let i = 0; i < n; i++) {
    const p = px[i];
    if (((p >>> 24) & 0xff) < 128) continue;
    const dr = ((p >>> 16) & 0xff) - mR;
    const dg = ((p >>> 8) & 0xff) - mG;
    const db = (p & 0xff) - mB;
    if (dr * dr + dg * dg + db * db > tolSq) subject++;
  }
  return { backgroundUniformity: uniformity, coverage: n > 0 ? subject / n : 0 };
}

interface ClassMeans {
  readonly low: number;
  readonly high: number;
}

function classMeans(src: GrayF, t: number): ClassMeans {
  const d = src.data;
  let lowSum = 0;
  let lowN = 0;
  let highSum = 0;
  let highN = 0;
  for (let i = 0; i < d.length; i++) {
    const v = d[i];
    if (v <= t) {
      lowSum += v;
      lowN++;
    } else {
      highSum += v;
      highN++;
    }
  }
  return {
    low: lowN > 0 ? lowSum / lowN : 0,
    high: highN > 0 ? highSum / highN : 1,
  };
}

interface OrientationStats {
  readonly entropy: number;
  readonly concentration: number;
  readonly dominant: number;
}

/**
 * Magnitude-weighted orientation histogram over 36 bins covering 180 degrees (a gradient and its
 * negation describe the same edge, so the histogram is modulo pi), and the three numbers read off it.
 *
 * Entropy and concentration come from the **same** histogram, which is not only cheaper but the only
 * way they cannot disagree about which bins exist. Concentration wraps at the ends, because 0 and pi
 * are the same direction and a peak straddling the seam is one peak.
 */
function orientationStats(src: GrayF): OrientationStats {
  const g = gradients(gaussianBlur(src, 1), GradientOp.SCHARR);
  const gx = g.gx.data;
  const gy = g.gy.data;
  const hist = new Float64Array(ORIENTATION_BINS);
  let total = 0;
  for (let i = 0; i < gx.length; i++) {
    const a = gx[i];
    const b = gy[i];
    const m = Math.sqrt(a * a + b * b);
    if (m <= 1e-6) continue;
    let ang = Math.atan2(b, a);
    if (ang < 0) ang += Math.PI;
    if (ang >= Math.PI) ang -= Math.PI;
    let bin = ((ang / Math.PI) * ORIENTATION_BINS) | 0;
    if (bin >= ORIENTATION_BINS) bin = ORIENTATION_BINS - 1;
    hist[bin] += m;
    total += m;
  }
  if (total <= 0) return { entropy: 0, concentration: 0, dominant: 0 };

  let entropy = 0;
  for (let i = 0; i < ORIENTATION_BINS; i++) {
    const p = hist[i] / total;
    if (p > 0) entropy -= p * Math.log(p);
  }

  let bestBin = 0;
  let bestMass = -1;
  for (let b = 0; b < ORIENTATION_BINS; b++) {
    const prev = hist[(b + ORIENTATION_BINS - 1) % ORIENTATION_BINS];
    const next = hist[(b + 1) % ORIENTATION_BINS];
    const mass = prev + hist[b] + next;
    if (mass > bestMass) {
      bestMass = mass;
      bestBin = b;
    }
  }
  return {
    entropy: clamp01(entropy / Math.log(ORIENTATION_BINS)),
    concentration: clamp01(bestMass / total),
    dominant: ((bestBin + 0.5) / ORIENTATION_BINS) * Math.PI,
  };
}

function standardDeviation(src: GrayF): number {
  const d = src.data;
  const n = d.length;
  if (n === 0) return 0;
  let sum = 0;
  for (let i = 0; i < n; i++) sum += d[i];
  const mean = sum / n;
  let acc = 0;
  for (let i = 0; i < n; i++) {
    const diff = d[i] - mean;
    acc += diff * diff;
  }
  const variance = acc / n;
  return variance > 0 ? Math.sqrt(variance) : 0;
}

interface Palette {
  readonly count: number;
  readonly flatness: number;
}

/**
 * How many colours the source actually uses, and how much of the frame the busiest four of them own.
 *
 * The count alone is not enough: a photograph of a red wall and a two-colour screenprint can both
 * report a handful of occupied cells, and only the screenprint spends 95% of its pixels in them. Both
 * come out of one 4096-cell histogram at 4 bits per channel, coarse on purpose — finer cells make a
 * JPEG's ringing look like a palette.
 */
function paletteOf(src: RgbaImage): Palette {
  const counts = new Int32Array(4096);
  const p = src.pixels;
  for (let i = 0; i < p.length; i++) {
    const v = p[i];
    const r = (v >>> 20) & 0xf;
    const g = (v >>> 12) & 0xf;
    const b = (v >>> 4) & 0xf;
    counts[(r << 8) | (g << 4) | b]++;
  }
  const minCount = Math.max(1, Math.floor(p.length / 500));
  let used = 0;
  // The four largest by insertion into a fixed four-slot ladder: sorting 4096 entries for four
  // numbers is the sort of thing that only ever shows up as "why is classification slow".
  let t0 = 0;
  let t1 = 0;
  let t2 = 0;
  let t3 = 0;
  for (let i = 0; i < counts.length; i++) {
    const c = counts[i];
    if (c >= minCount) used++;
    if (c > t0) {
      t3 = t2;
      t2 = t1;
      t1 = t0;
      t0 = c;
    } else if (c > t1) {
      t3 = t2;
      t2 = t1;
      t1 = c;
    } else if (c > t2) {
      t3 = t2;
      t2 = c;
    } else if (c > t3) {
      t3 = c;
    }
  }
  return {
    count: used,
    flatness: p.length === 0 ? 0 : clamp01((t0 + t1 + t2 + t3) / p.length),
  };
}

interface StrokeStats {
  readonly meanWidth: number;
  readonly consistency: number;
}

/**
 * Mean and consistency of the ink stroke width, from the ridge of the distance transform.
 *
 * The distance transform gives, at every ink pixel, the distance to the nearest paper; at the *ridge*
 * of that field — a local maximum, i.e. the medial axis — that distance is the stroke's half-width.
 * Taking the ridge rather than thinning first is the whole economy of the measurement: Zhang-Suen
 * iterates to a fixed point over the frame, and a four-neighbour maximum test is one pass and answers
 * the same question to the precision this decision needs.
 *
 * Consistency is `1 - stddev/mean`, dimensionless and therefore comparable across resolutions. A pen,
 * a printing plate and an engraving tool each produce one width and land near 1; the dark regions of a
 * photograph are objects rather than strokes and their widths span the image, landing near 0.
 *
 * Zeroes when the mask is entirely ink or entirely paper — there is no stroke to measure in either,
 * and the distance transform of an all-foreground mask measures the frame.
 */
function strokeStats(gray: GrayF, threshold: number): StrokeStats {
  const ink = fixed(gray, threshold, true);
  const on = ink.countTrue();
  if (on === 0 || on === ink.size) return { meanWidth: 0, consistency: 0 };

  const dt = Distance.euclidean(ink, true);
  const w = dt.width;
  const h = dt.height;
  let sum = 0;
  let sumSq = 0;
  let count = 0;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const v = dt.get(x, y);
      if (v <= 0) continue;
      if (v < dt.clamped(x - 1, y) || v < dt.clamped(x + 1, y)) continue;
      if (v < dt.clamped(x, y - 1) || v < dt.clamped(x, y + 1)) continue;
      const width = 2 * v;
      sum += width;
      sumSq += width * width;
      count++;
    }
  }
  if (count <= 0) return { meanWidth: 0, consistency: 0 };
  const mean = sum / count;
  if (mean <= 0) return { meanWidth: 0, consistency: 0 };
  const variance = Math.max(0, sumSq / count - mean * mean);
  return { meanWidth: mean, consistency: clamp01(1 - Math.sqrt(variance) / mean) };
}

/**
 * The fraction of the frame whose local standard deviation exceeds {@link TEXTURE_LOCAL_STDDEV}.
 *
 * **An area, not an amplitude**, and that is the point. The obvious measure — mean |laplacian| — was
 * tried first and is useless here, because it is dominated by however many *edges* the frame contains
 * rather than by how much of it is textured: a black-on-white pen drawing and a woven cloth both
 * saturated it, which is precisely the pair this statistic exists to separate. Being an area it is
 * already normalised with no calibration constant, and noise of a few code values sits an order of
 * magnitude below the threshold whatever fraction of the frame carries it.
 */
function textureEnergy(gray: GrayF): number {
  const w = gray.width;
  const h = gray.height;
  const n = w * h;
  if (n <= 0) return 0;
  const squares = new GrayF(w, h);
  for (let i = 0; i < n; i++) squares.data[i] = gray.data[i] * gray.data[i];
  const satV = summedAreaTable(gray);
  const satQ = summedAreaTable(squares);

  const varianceFloor = TEXTURE_LOCAL_STDDEV * TEXTURE_LOCAL_STDDEV;
  let textured = 0;
  for (let y = 0; y < h; y++) {
    const y0 = Math.max(0, y - TEXTURE_RADIUS);
    const y1 = Math.min(h - 1, y + TEXTURE_RADIUS);
    for (let x = 0; x < w; x++) {
      const x0 = Math.max(0, x - TEXTURE_RADIUS);
      const x1 = Math.min(w - 1, x + TEXTURE_RADIUS);
      const area = (x1 - x0 + 1) * (y1 - y0 + 1);
      const mean = rectSum(satV, w, h, x0, y0, x1, y1) / area;
      const meanSq = rectSum(satQ, w, h, x0, y0, x1, y1) / area;
      if (meanSq - mean * mean > varianceFloor) textured++;
    }
  }
  return clamp01(textured / n);
}

interface ScoreInput {
  readonly bimodality: number;
  readonly modeSplit: number;
  readonly edgeDensity: number;
  readonly orientationEntropy: number;
  readonly colourCount: number;
  readonly paletteFlatness: number;
  readonly backgroundUniformity: number;
  readonly subjectCoverage: number;
  readonly strokeConsistency: number;
  readonly textureEnergy: number;
  readonly isLineArt: boolean;
  readonly isHighTexture: boolean;
  readonly isFlatGraphic: boolean;
}

function score(s: ScoreInput): ClassScores {
  // Weights are stated inline and sum to 1 within each class, so a score is always a weighted mean of
  // 0..1 evidence and always lands in 0..1 — which is what lets the margin below be read as a fraction
  // of the winner rather than as an uncalibrated difference.
  //
  // Stroke consistency carries the largest single weight in `lineArt`, above bimodality, because it is
  // the only term a *photograph* cannot fake: a shaded object against a plain ground is as bimodal and
  // as palette-flat as a drawing, and the widths of its dark regions are not.
  let lineArt =
    0.25 * ramp(s.bimodality, 0.55, 0.85) +
    0.22 * ramp(s.modeSplit, 0.25, 0.6) +
    0.35 * s.strokeConsistency +
    0.18 * ramp(s.paletteFlatness, 0.45, 0.95);

  let flatGraphic =
    0.3 * (1 - ramp(s.colourCount, 4, 40)) +
    0.3 * ramp(s.paletteFlatness, 0.4, 0.9) +
    0.2 * (1 - ramp(s.textureEnergy, 0.1, 0.45)) +
    0.2 * (1 - ramp(s.orientationEntropy, 0.55, 0.9));

  // Texture area outweighs edge density here, and deliberately: Canny's auto thresholds are taken from
  // the median gradient magnitude, so a frame that is textured *everywhere* raises its own thresholds
  // and reports a low edge density. Measured on a woven pattern filling the frame, edge density came
  // back at 0.014 while the texture area was 1.0 — the statistic §12 names for this job is the one
  // that fails on the strongest case of it.
  let textured =
    0.3 * ramp(s.edgeDensity, 0.1, 0.3) +
    0.6 * ramp(s.textureEnergy, 0.25, 0.75) +
    0.1 * (1 - s.strokeConsistency);

  // "Isolated" is a band, not a threshold: a subject occupying 0.5% of the frame is a speck and one
  // occupying 95% has no background to be isolated from. The palette term is what stops this class
  // swallowing every flat graphic — a logo on white satisfies every other term completely, and the one
  // thing it is not is *tonal*.
  const isolated =
    ramp(s.subjectCoverage, 0.02, 0.1) * (1 - ramp(s.subjectCoverage, 0.7, 0.95));
  // The clean background multiplies rather than contributes. It is a *precondition* of the class, not
  // one vote among four: as an additive quarter, a full-frame woven fabric with no background at all
  // still scored 0.60 here on the strength of the other three.
  const smoothObject =
    ramp(s.backgroundUniformity, 0.55, 0.9) *
    (0.35 * isolated +
      0.2 * (1 - ramp(s.textureEnergy, 0.12, 0.45)) +
      0.2 * (1 - ramp(s.edgeDensity, 0.08, 0.25)) +
      0.25 * (1 - ramp(s.paletteFlatness, 0.35, 0.85)));

  // The residual hypothesis, and the only one whose score rises with *clutter*. Without that term a
  // photographed pot on a white sweep would score as high for "photograph" as for "object on a clean
  // background" — both are true — and the margin would collapse on exactly the frame this classifier
  // exists to recognise.
  const clutter =
    0.5 * (1 - s.backgroundUniformity) + 0.5 * ramp(s.edgeDensity, 0.05, 0.18);
  const photograph = 0.45 + 0.3 * clamp01(clutter);

  // §12's own bi-level, texture-density and flat-graphic tests are strong evidence in their own right,
  // so a class whose hard flag is set is floored rather than left to be outvoted by a smooth ramp.
  if (s.isLineArt) lineArt = Math.max(lineArt, 0.9);
  if (s.isHighTexture) textured = Math.max(textured, 0.75);
  if (s.isFlatGraphic) flatGraphic = Math.max(flatGraphic, 0.75);
  // §12's decision list is ordered and line art comes first, so the two are not allowed to be rivals:
  // an ink drawing genuinely is "few colours, flat palette, no texture" and scores 0.95 as a flat
  // graphic, which would collapse the confidence on the one source type where the wrong answer is
  // expensive. This is the same `!isLineArt` exclusion `isFlatGraphic` itself carries.
  if (s.isLineArt) flatGraphic = Math.min(flatGraphic, 0.35);

  // Saturation spread is deliberately absent from every score. It says nothing about *which* class a
  // frame is and everything about which subject preset a class maps to — grey texture is stone,
  // coloured texture is cloth — so it belongs to `suggestFor` and scoring on it here would make the
  // same measurement count twice.
  return {
    lineArt: clamp01(lineArt),
    flatGraphic: clamp01(flatGraphic),
    textured: clamp01(textured),
    smoothObject: clamp01(smoothObject),
    photograph: clamp01(photograph),
  };
}

function scoreOf(s: ClassScores, kind: SourceKind): number {
  switch (kind) {
    case SourceKind.LINE_ART:
      return s.lineArt;
    case SourceKind.FLAT_GRAPHIC:
      return s.flatGraphic;
    case SourceKind.TEXTURED:
      return s.textured;
    case SourceKind.SMOOTH_OBJECT:
      return s.smoothObject;
    case SourceKind.PHOTOGRAPH:
      return s.photograph;
    default:
      return 0;
  }
}

/**
 * Ties break towards the *least* consequential action, line art last: if "photograph" and "line art"
 * score identically the right answer is the one whose preset changes least, and the confidence refuses
 * to act on either anyway.
 */
function winner(s: ClassScores): SourceKind {
  let best = SourceKind.PHOTOGRAPH;
  let bestScore = s.photograph;
  if (s.smoothObject > bestScore) {
    best = SourceKind.SMOOTH_OBJECT;
    bestScore = s.smoothObject;
  }
  if (s.textured > bestScore) {
    best = SourceKind.TEXTURED;
    bestScore = s.textured;
  }
  if (s.flatGraphic > bestScore) {
    best = SourceKind.FLAT_GRAPHIC;
    bestScore = s.flatGraphic;
  }
  if (s.lineArt > bestScore) best = SourceKind.LINE_ART;
  return best;
}

/**
 * How sure the classification is: **how much evidence the winner has**, and **how far ahead of the
 * runner-up it is**, weighted 0.55 / 0.45.
 *
 * Both halves are necessary and neither is sufficient. Evidence alone calls a frame confident when two
 * classes both describe it — the ambiguous case, the one where acting is worst. Margin alone calls a
 * frame confident when the winner scores 0.3 and everything else scores 0.1, which is not a
 * classification but an absence of one.
 *
 * The margin is relative (`(top - second) / top`) rather than absolute, so it means the same thing at
 * both ends of the range: 0.9 against 0.45 is as decisive as 0.4 against 0.2.
 */
function confidenceOf(s: ClassScores, kind: SourceKind): number {
  const top = scoreOf(s, kind);
  if (top <= 0) return 0;
  let second = 0;
  for (const k of [
    SourceKind.LINE_ART,
    SourceKind.FLAT_GRAPHIC,
    SourceKind.TEXTURED,
    SourceKind.SMOOTH_OBJECT,
    SourceKind.PHOTOGRAPH,
  ]) {
    if (k === kind) continue;
    const v = scoreOf(s, k);
    if (v > second) second = v;
  }
  return clamp01(0.55 * top + 0.45 * clamp01((top - second) / top));
}
