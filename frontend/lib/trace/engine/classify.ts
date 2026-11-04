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

export enum SourceKind {
  UNKNOWN = 'UNKNOWN',
  LINE_ART = 'LINE_ART',
  FLAT_GRAPHIC = 'FLAT_GRAPHIC',
  TEXTURED = 'TEXTURED',
  SMOOTH_OBJECT = 'SMOOTH_OBJECT',
  PHOTOGRAPH = 'PHOTOGRAPH',
}

export interface ClassScores {
  readonly lineArt: number;
  readonly flatGraphic: number;
  readonly textured: number;
  readonly smoothObject: number;
  readonly photograph: number;
}

export interface SourceProfile {
  readonly bimodality: number;
  readonly edgeDensity: number;
  readonly orientationEntropy: number;
  readonly saturationSpread: number;
  readonly isLineArt: boolean;
  readonly isHighTexture: boolean;
  readonly isFlatGraphic: boolean;
  readonly suggestion: string;
  readonly backgroundUniformity?: number;
  readonly subjectCoverage?: number;
  readonly strokeWidthPx?: number;
  readonly strokeWidthConsistency?: number;
  readonly colourCount?: number;
  readonly paletteFlatness?: number;
  readonly orientationConcentration?: number;
  readonly dominantOrientation?: number;
  readonly textureEnergy?: number;
  readonly separableSubject?: boolean;
  readonly scores?: ClassScores;
  readonly kind?: SourceKind;
  readonly confidence?: number;
}

export interface FigureGround {
  readonly backgroundUniformity: number;
  readonly coverage: number;
}

export const PROXY_LONG_EDGE = 512;

const ORIENTATION_BINS = 36;

const BIMODALITY_LINE_ART = 0.75;
const EDGE_DENSITY_TEXTURE = 0.18;
const ENTROPY_FLAT_GRAPHIC = 0.72;
const FLAT_GRAPHIC_MAX_COLOURS = 16;
const SATURATION_FLAT_GRAPHIC = 0.32;

const LINE_ART_STROKE_CONSISTENCY = 0.6;

const TEXTURE_LOCAL_STDDEV = 0.06;
const TEXTURE_RADIUS = 3;

const BLANK_RANGE = 2 / 255;

const BORDER_BAND_FRACTION = 0.06;
const RING_SPREAD_FULL = 32;
const RING_TOLERANCE_BASE = 14;
const RING_TOLERANCE_GAIN = 2.5;
const RING_TOLERANCE_MAX = 96;

const CLEAN_BACKGROUND = 0.62;
const MIN_SUBJECT_COVERAGE = 0.02;
const MAX_SUBJECT_COVERAGE = 0.75;

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

function ramp(v: number, lo: number, hi: number): number {
  if (hi <= lo) return v >= hi ? 1 : 0;
  return clamp01((v - lo) / (hi - lo));
}

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

export function kindOf(p: SourceProfile): SourceKind {
  if (p.kind !== undefined && p.kind !== SourceKind.UNKNOWN) return p.kind;
  if (p.isLineArt) return SourceKind.LINE_ART;
  if (p.isFlatGraphic) return SourceKind.FLAT_GRAPHIC;
  if (p.isHighTexture) return SourceKind.TEXTURED;
  return SourceKind.PHOTOGRAPH;
}

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

export function confidenceOfProfile(p: SourceProfile): number {
  return typeof p.confidence === 'number' && Number.isFinite(p.confidence) ? p.confidence : 0;
}

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

  let textured =
    0.3 * ramp(s.edgeDensity, 0.1, 0.3) +
    0.6 * ramp(s.textureEnergy, 0.25, 0.75) +
    0.1 * (1 - s.strokeConsistency);

  const isolated =
    ramp(s.subjectCoverage, 0.02, 0.1) * (1 - ramp(s.subjectCoverage, 0.7, 0.95));
  const smoothObject =
    ramp(s.backgroundUniformity, 0.55, 0.9) *
    (0.35 * isolated +
      0.2 * (1 - ramp(s.textureEnergy, 0.12, 0.45)) +
      0.2 * (1 - ramp(s.edgeDensity, 0.08, 0.25)) +
      0.25 * (1 - ramp(s.paletteFlatness, 0.35, 0.85)));

  const clutter =
    0.5 * (1 - s.backgroundUniformity) + 0.5 * ramp(s.edgeDensity, 0.05, 0.18);
  const photograph = 0.45 + 0.3 * clamp01(clutter);

  if (s.isLineArt) lineArt = Math.max(lineArt, 0.9);
  if (s.isHighTexture) textured = Math.max(textured, 0.75);
  if (s.isFlatGraphic) flatGraphic = Math.max(flatGraphic, 0.75);
  if (s.isLineArt) flatGraphic = Math.min(flatGraphic, 0.35);

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
