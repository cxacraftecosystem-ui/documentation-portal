import { GrayF, Mask, Px, RgbaImage } from './buffers';
import { toGray, toLabPlanes } from './color';
import { fillHoles, removeSmallBlobs } from './components';
import { boxBlur, gaussianBlur } from './convolve';
import { nextPowerOfTwo, transform2d } from './fft';
import { resizeGray } from './resample';

const f32 = Math.fround;

const MAX_LAB_DISTANCE = 100;

const FLAT_LUMA_SPAN = f32(1e-5);

const BINS_L = 8;

const BINS_AB = 16;

const BIN_COUNT = BINS_L * BINS_AB * BINS_AB;

const MIN_BIN_EVIDENCE = 4;

const W_CONNECT = 0.45;
const W_COLOUR = 0.35;
const W_SALIENCY = 0.2;

const MIN_SPECK_AREA = 16;
const SPECK_AREA_DIVISOR = 400;

const MIN_HOLE_AREA = 64;
const HOLE_AREA_DIVISOR = 50;

const GUIDE_RADIUS_FRACTION = f32(0.03);
const GUIDE_EPS = f32(1e-4);

const MIN_MATTE_PIXELS = 16;

const MIN_KEEP_FRACTION = f32(0.005);

const HEALTHY_LOW = f32(0.03);
const HEALTHY_HIGH = f32(0.9);
const MAX_KEEP_FRACTION = f32(0.99);

const AGREE_LOW = 0.5;
const AGREE_HIGH = f32(0.9);

const DECIDED_LOW = f32(0.15);
const DECIDED_HIGH = f32(0.85);
const DECISIVE_LOW = f32(0.6);
const DECISIVE_HIGH = f32(0.9);

export const MIN_CONFIDENCE = 0.5;

export interface MatteResult {
  readonly alpha: GrayF;
  readonly coverage: number;
  readonly confidence: number;
  readonly confident: boolean;
  readonly reason: string;
}

export function borderFlood(src: RgbaImage, tolerance: number, feather = 1.5): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const alpha = new GrayF(w, h);
  const [lp, ap, bp] = toLabPlanes(src);

  const tol = f32(f32(Px.clamp(tolerance, 0, 1)) * MAX_LAB_DISTANCE);
  const tolSq = f32(tol * tol);

  const flooded = new Uint8Array(n);
  const queued = new Uint8Array(n);
  const stack = new Int32Array(n);
  let sp = 0;

  const c0 = 0;
  const c1 = w - 1;
  const c2 = (h - 1) * w;
  const c3 = (h - 1) * w + (w - 1);
  let mL = median4(lp[c0], lp[c1], lp[c2], lp[c3]);
  let mA = median4(ap[c0], ap[c1], ap[c2], ap[c3]);
  let mB = median4(bp[c0], bp[c1], bp[c2], bp[c3]);

  for (let x = 0; x < w; x++) {
    const top = x;
    if (queued[top] === 0) {
      queued[top] = 1;
      stack[sp++] = top;
    }
    const bottom = (h - 1) * w + x;
    if (queued[bottom] === 0) {
      queued[bottom] = 1;
      stack[sp++] = bottom;
    }
  }
  for (let y = 0; y < h; y++) {
    const left = y * w;
    if (queued[left] === 0) {
      queued[left] = 1;
      stack[sp++] = left;
    }
    const right = y * w + (w - 1);
    if (queued[right] === 0) {
      queued[right] = 1;
      stack[sp++] = right;
    }
  }

  let sumL = 0;
  let sumA = 0;
  let sumB = 0;
  let cnt = 0;
  let pops = 0;
  const maxPops = 8 * n + 4 * (w + h) + 64;

  while (sp > 0 && pops < maxPops) {
    const idx = stack[--sp];
    queued[idx] = 0;
    pops++;
    if (flooded[idx] !== 0) continue;

    const dl = f32(lp[idx] - mL);
    const da = f32(ap[idx] - mA);
    const db = f32(bp[idx] - mB);
    const d2 = f32(f32(f32(dl * dl) + f32(da * da)) + f32(db * db));
    if (d2 > tolSq) continue;

    flooded[idx] = 1;
    sumL += lp[idx];
    sumA += ap[idx];
    sumB += bp[idx];
    cnt++;
    const inv = 1 / cnt;
    mL = f32(sumL * inv);
    mA = f32(sumA * inv);
    mB = f32(sumB * inv);

    const py = (idx / w) | 0;
    const px = idx - py * w;
    const yLo = py > 0 ? py - 1 : 0;
    const yHi = py < h - 1 ? py + 1 : h - 1;
    const xLo = px > 0 ? px - 1 : 0;
    const xHi = px < w - 1 ? px + 1 : w - 1;
    for (let ny = yLo; ny <= yHi; ny++) {
      const row = ny * w;
      for (let nx = xLo; nx <= xHi; nx++) {
        const ni = row + nx;
        if (flooded[ni] === 0 && queued[ni] === 0) {
          queued[ni] = 1;
          stack[sp++] = ni;
        }
      }
    }
  }

  if (cnt >= n) return alpha.fill(1);

  const out = alpha.data;
  for (let i = 0; i < n; i++) out[i] = flooded[i] !== 0 ? 0 : 1;
  if (feather <= 0.05) return alpha;
  const soft = gaussianBlur(alpha, feather);
  for (let i = 0; i < soft.data.length; i++) soft.data[i] = Px.clamp01(soft.data[i]);
  return soft;
}

export function spectralSaliency(src: RgbaImage, proxySize = 64): GrayF {
  const p = Px.clampInt(proxySize, 4, 1024);
  const proxy = resizeGray(toGray(src), p, p);

  const proxyRange = proxy.range();
  if (proxyRange.hi - proxyRange.lo <= FLAT_LUMA_SPAN) return new GrayF(src.width, src.height);

  const fw = nextPowerOfTwo(p);
  const fh = fw;
  const fn = fw * fh;
  const re = new Float32Array(fn);
  const im = new Float32Array(fn);
  for (let y = 0; y < p; y++) {
    const srcRow = y * p;
    const dstRow = y * fw;
    for (let x = 0; x < p; x++) re[dstRow + x] = proxy.data[srcRow + x];
  }

  transform2d(re, im, fw, fh, false);

  const logMag = new Float32Array(fn);
  const cosP = new Float32Array(fn);
  const sinP = new Float32Array(fn);
  for (let i = 0; i < fn; i++) {
    const a = re[i];
    const b = im[i];
    const m = Math.sqrt(a * a + b * b);
    logMag[i] = Math.log(m + 1e-8);
    if (m > 1e-20) {
      cosP[i] = a / m;
      sinP[i] = b / m;
    } else {
      cosP[i] = 1;
      sinP[i] = 0;
    }
  }

  const smoothed = boxBlur(new GrayF(fw, fh, logMag), 1).data;
  for (let i = 0; i < fn; i++) {
    const r = Math.exp(logMag[i] - smoothed[i]);
    re[i] = r * cosP[i];
    im[i] = r * sinP[i];
  }

  transform2d(re, im, fw, fh, true);

  const sal = new GrayF(p, p);
  for (let y = 0; y < p; y++) {
    const srcRow = y * fw;
    const dstRow = y * p;
    for (let x = 0; x < p; x++) {
      const a = re[srcRow + x];
      const b = im[srcRow + x];
      sal.data[dstRow + x] = a * a + b * b;
    }
  }

  const blurred = gaussianBlur(sal, 3);
  const range = blurred.range();
  const span = range.hi - range.lo;
  if (span <= 1e-12) return new GrayF(src.width, src.height);
  const inv = 1 / span;
  const bd = blurred.data;
  for (let i = 0; i < bd.length; i++) bd[i] = Px.clamp01((bd[i] - range.lo) * inv);
  return resizeGray(blurred, src.width, src.height);
}

export function saliencyMatte(src: RgbaImage, threshold = 0.5, feather = 2): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const sal = spectralSaliency(src);
  const t = f32(Px.clamp(threshold, 0, 1));

  const mask = new Mask(w, h);
  let on = 0;
  for (let i = 0; i < n; i++) {
    if (sal.data[i] >= t) {
      mask.data[i] = 1;
      on++;
    }
  }
  if (on * 200 < n) return new GrayF(w, h).fill(1);

  const solid = fillHoles(mask, Math.max(64, (n / 100) | 0));
  const alpha = solid.toGray();
  if (feather <= 0.05) return alpha;
  const soft = gaussianBlur(alpha, feather);
  for (let i = 0; i < soft.data.length; i++) soft.data[i] = Px.clamp01(soft.data[i]);
  return soft;
}

export function borderLikeness(src: RgbaImage, bandFraction = 0.06): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const out = new GrayF(w, h);
  const shortSide = w < h ? w : h;
  if (shortSide < 3) return out.fill(0.5);
  const frac = f32(Px.clamp(bandFraction, 0, 0.49));
  const band = Px.clampInt(Math.round(f32(frac * shortSide)), 1, (shortSide - 1) >> 1);

  const [lp, ap, bp] = toLabPlanes(src);
  const bandHist = new Int32Array(BIN_COUNT);
  const innerHist = new Int32Array(BIN_COUNT);
  let bandTotal = 0;
  let innerTotal = 0;
  const bin = new Int32Array(n);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    const edgeRow = y < band || y >= h - band;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const q = labBin(lp[i], ap[i], bp[i]);
      bin[i] = q;
      if (edgeRow || x < band || x >= w - band) {
        bandHist[q]++;
        bandTotal++;
      } else {
        innerHist[q]++;
        innerTotal++;
      }
    }
  }
  if (bandTotal === 0 || innerTotal === 0) return out.fill(0.5);

  const posterior = new Float32Array(BIN_COUNT);
  for (let q = 0; q < BIN_COUNT; q++) {
    const cb = bandHist[q];
    const ci = innerHist[q];
    if (cb + ci < MIN_BIN_EVIDENCE) {
      posterior[q] = 0.5;
    } else {
      const fb = cb / bandTotal;
      const fi = ci / innerTotal;
      posterior[q] = fb / (fb + fi);
    }
  }
  const od = out.data;
  for (let i = 0; i < n; i++) od[i] = posterior[bin[i]];
  return out;
}

export function guidedFilter(guide: GrayF, input: GrayF, radius: number, eps: number): GrayF {
  if (guide.width !== input.width || guide.height !== input.height) {
    throw new Error(
      `guidedFilter(): guide ${guide.width}x${guide.height} does not match ` +
        `input ${input.width}x${input.height}`,
    );
  }
  if (radius <= 0) return input.copy();
  const w = guide.width;
  const h = guide.height;
  const n = w * h;
  const g = guide.data;
  const p = input.data;

  const gg = new GrayF(w, h);
  const gp = new GrayF(w, h);
  for (let i = 0; i < n; i++) {
    const gi = g[i];
    gg.data[i] = gi * gi;
    gp.data[i] = gi * p[i];
  }
  const meanG = boxBlur(guide, radius).data;
  const meanP = boxBlur(input, radius).data;
  const meanGG = boxBlur(gg, radius).data;
  const meanGP = boxBlur(gp, radius).data;

  const a = new GrayF(w, h);
  const b = new GrayF(w, h);
  const e = eps < 0 ? 0 : eps;
  for (let i = 0; i < n; i++) {
    const mg = meanG[i];
    const mp = meanP[i];
    let varG = meanGG[i] - mg * mg;
    if (varG < 0) varG = 0;
    const den = varG + e;
    const ai = den <= 0 ? 0 : (meanGP[i] - mg * mp) / den;
    a.data[i] = ai;
    b.data[i] = mp - ai * mg;
  }
  const ma = boxBlur(a, radius).data;
  const mb = boxBlur(b, radius).data;
  const out = new GrayF(w, h);
  for (let i = 0; i < n; i++) out.data[i] = ma[i] * g[i] + mb[i];
  return out;
}

export function subjectMatte(src: RgbaImage, tolerance = 0.18, feather = 0): MatteResult {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  if (n < MIN_MATTE_PIXELS) {
    return {
      alpha: new GrayF(w, h).fill(1),
      coverage: 1,
      confidence: 0,
      confident: false,
      reason:
        `A ${w}x${h} image is too small to tell a subject from a background, so the whole ` +
        'frame was kept.',
    };
  }

  const connect = borderFlood(src, tolerance, 0).data;
  const likeness = borderLikeness(src).data;
  const salience = spectralSaliency(src).data;

  const mask = new Mask(w, h);
  const md = mask.data;
  let on = 0;
  let agree = 0;
  for (let i = 0; i < n; i++) {
    const c = connect[i];
    const b = likeness[i];
    const fgByConnect = c >= 0.5;
    const fgByColour = b < 0.5;
    if (fgByConnect === fgByColour) agree++;
    let score: number;
    if (!fgByConnect && !fgByColour) score = 0;
    else if (fgByConnect && fgByColour) score = 1;
    else score = W_CONNECT * c + W_COLOUR * (1 - b) + W_SALIENCY * salience[i];
    if (score >= 0.5) {
      md[i] = 1;
      on++;
    }
  }

  let cleaned = mask;
  if (on > 0) {
    const pruned = removeSmallBlobs(mask, Math.max(MIN_SPECK_AREA, (n / SPECK_AREA_DIVISOR) | 0));
    if (pruned.countTrue() * 10 >= on * 9) cleaned = pruned;
  }
  const filled = fillHoles(cleaned, Math.max(MIN_HOLE_AREA, (n / HOLE_AREA_DIVISOR) | 0));

  const pre = new GrayF(w, h);
  const pd = pre.data;
  const fd = filled.data;
  for (let i = 0; i < n; i++) pd[i] = fd[i] !== 0 ? 1 : 0;

  const shortSide = w < h ? w : h;
  const radius = Math.max(2, Math.round(f32(GUIDE_RADIUS_FRACTION * shortSide)));
  const refined = guidedFilter(toGray(src), pre, radius, GUIDE_EPS);
  let alpha = new GrayF(w, h);
  for (let i = 0; i < n; i++) alpha.data[i] = Px.clamp01(refined.data[i]);
  if (feather > 0.05) {
    const soft = gaussianBlur(alpha, feather);
    for (let i = 0; i < n; i++) soft.data[i] = Px.clamp01(soft.data[i]);
    alpha = soft;
  }

  let kept = 0;
  let decided = 0;
  const ad = alpha.data;
  for (let i = 0; i < n; i++) {
    const v = ad[i];
    kept += v;
    if (v <= DECIDED_LOW || v >= DECIDED_HIGH) decided++;
  }
  const coverage = f32(kept / n);
  if (coverage < MIN_KEEP_FRACTION) {
    return {
      alpha: new GrayF(w, h).fill(1),
      coverage: 1,
      confidence: 0,
      confident: false,
      reason:
        `Background separation kept only ${percent(coverage)}% of the frame, which reads as ` +
        'a failed matte rather than a small subject, so the whole frame was kept.',
    };
  }

  const agreement = f32(agree / n);
  const decisiveness = f32(decided / n);
  const cCoverage = Math.min(
    ramp(coverage, MIN_KEEP_FRACTION, HEALTHY_LOW),
    1 - ramp(coverage, HEALTHY_HIGH, MAX_KEEP_FRACTION),
  );
  const cAgreement = ramp(agreement, AGREE_LOW, AGREE_HIGH);
  const cDecisive = ramp(decisiveness, DECISIVE_LOW, DECISIVE_HIGH);
  const confidence = Math.min(cCoverage, cAgreement, cDecisive);

  let reason: string;
  if (confidence >= MIN_CONFIDENCE) {
    reason =
      `The border flood and the border-colour model agree on ${percent(agreement)}% of the ` +
      `frame; the matte keeps ${percent(coverage)}% of it and ${percent(decisiveness)}% ` +
      'of that is a clear decision rather than a soft edge.';
  } else if (cCoverage <= cAgreement && cCoverage <= cDecisive) {
    reason =
      coverage >= HEALTHY_HIGH
        ? `The matte kept ${percent(coverage)}% of the frame, so almost nothing was ` +
          'separated out — treat this image as having no background to remove.'
        : `The matte kept only ${percent(coverage)}% of the frame, which is small enough ` +
          'that it is more likely a failed separation than a small subject.';
  } else if (cAgreement <= cDecisive) {
    reason =
      'The border flood and the border-colour model disagree about ' +
      `${percent(1 - agreement)}% of the frame, so where the subject ends is a guess ` +
      'rather than a measurement.';
  } else {
    reason =
      `${percent(1 - decisiveness)}% of the matte is neither clearly subject nor clearly ` +
      'background, so it has softened an edge rather than found one.';
  }
  return { alpha, coverage, confidence, confident: confidence >= MIN_CONFIDENCE, reason };
}

export function applyMatteRgba(src: RgbaImage, alpha: GrayF, background: number): RgbaImage {
  if (src.width !== alpha.width || src.height !== alpha.height) {
    throw new Error('applyMatte(): size mismatch');
  }
  const n = src.pixels.length;
  const out = new Uint32Array(n);
  const px = src.pixels;
  const a = alpha.data;
  const bg = background >>> 0;
  const bgA = (bg >>> 24) & 0xff;
  const bgR = (bg >>> 16) & 0xff;
  const bgG = (bg >>> 8) & 0xff;
  const bgB = bg & 0xff;
  for (let i = 0; i < n; i++) {
    const t = Px.clamp01(a[i]);
    const v = px[i];
    const sa = (v >>> 24) & 0xff;
    const sr = (v >>> 16) & 0xff;
    const sg = (v >>> 8) & 0xff;
    const sb = v & 0xff;
    out[i] = RgbaImage.argb(
      Math.round(sa * t + bgA * (1 - t)),
      Math.round(sr * t + bgR * (1 - t)),
      Math.round(sg * t + bgG * (1 - t)),
      Math.round(sb * t + bgB * (1 - t)),
    );
  }
  return new RgbaImage(src.width, src.height, out);
}

export function applyMatteGray(src: GrayF, alpha: GrayF, background: number): GrayF {
  if (src.width !== alpha.width || src.height !== alpha.height) {
    throw new Error('applyMatte(): size mismatch');
  }
  const n = src.data.length;
  const out = new Float32Array(n);
  const d = src.data;
  const a = alpha.data;
  for (let i = 0; i < n; i++) {
    const t = Px.clamp01(a[i]);
    out[i] = d[i] * t + background * (1 - t);
  }
  return new GrayF(src.width, src.height, out);
}

export function applyMatte(src: RgbaImage, alpha: GrayF, background: number): RgbaImage;
export function applyMatte(src: GrayF, alpha: GrayF, background: number): GrayF;
export function applyMatte(
  src: RgbaImage | GrayF,
  alpha: GrayF,
  background: number,
): RgbaImage | GrayF {
  if (src instanceof GrayF) return applyMatteGray(src, alpha, background);
  return applyMatteRgba(src, alpha, background);
}

function median4(a: number, b: number, c: number, d: number): number {
  let v0 = a;
  let v1 = b;
  let v2 = c;
  let v3 = d;
  let t: number;
  if (v0 > v1) {
    t = v0;
    v0 = v1;
    v1 = t;
  }
  if (v2 > v3) {
    t = v2;
    v2 = v3;
    v3 = t;
  }
  if (v0 > v2) {
    t = v0;
    v0 = v2;
    v2 = t;
  }
  if (v1 > v3) {
    t = v1;
    v1 = v3;
    v3 = t;
  }
  if (v1 > v2) {
    t = v1;
    v1 = v2;
    v2 = t;
  }
  return f32((v1 + v2) * 0.5);
}

function labBin(l: number, a: number, b: number): number {
  const li = Px.clampInt(l * (BINS_L / 100), 0, BINS_L - 1);
  const ai = Px.clampInt((a + 128) * (BINS_AB / 256), 0, BINS_AB - 1);
  const bi = Px.clampInt((b + 128) * (BINS_AB / 256), 0, BINS_AB - 1);
  return (li * BINS_AB + ai) * BINS_AB + bi;
}

function ramp(v: number, lo: number, hi: number): number {
  if (hi <= lo) return v >= hi ? 1 : 0;
  return Px.clamp01(f32(f32(v - lo) / f32(hi - lo)));
}

function percent(v: number): number {
  return Math.round(Px.clamp01(v) * 100);
}
