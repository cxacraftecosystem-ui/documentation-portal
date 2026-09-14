import { GrayF, Px, RgbaImage } from './buffers';
import { gaussianBlur, gradients } from './convolve';
import { resizeGray } from './resample';
import { median } from './threshold';

/**
 * Whole-image geometry: rotation, flips, homographies and document detection.
 *
 * Every resampling operation here **inverse maps** — it walks destination pixels and samples the
 * source — because forward mapping leaves unwritten holes wherever the Jacobian expands, and those
 * holes read as a dotted grid over the whole result.
 */

/**
 * Rotate by a multiple of 90 degrees clockwise.
 * @param quarterTurns any integer; reduced modulo 4, negatives allowed
 * @returns a new RgbaImage; dimensions swap for odd turn counts.
 */
export function rotate90(src: RgbaImage, quarterTurns: number): RgbaImage {
  const t = ((quarterTurns % 4) + 4) % 4;
  if (t === 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const px = src.pixels;
  if (t === 2) {
    const out = new Uint32Array(w * h);
    for (let i = 0, j = w * h - 1; i < px.length; i++, j--) out[j] = px[i];
    return new RgbaImage(w, h, out);
  }
  const ow = h;
  const oh = w;
  const out = new Uint32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const ox = t === 1 ? h - 1 - y : y;
      const oy = t === 1 ? x : w - 1 - x;
      out[oy * ow + ox] = px[row + x];
    }
  }
  return new RgbaImage(ow, oh, out);
}

/** Mirror on either or both axes. @returns a new RgbaImage of the same dimensions. */
export function flip(src: RgbaImage, horizontal: boolean, vertical: boolean): RgbaImage {
  if (!horizontal && !vertical) return src.copy();
  const w = src.width;
  const h = src.height;
  const px = src.pixels;
  const out = new Uint32Array(w * h);
  for (let y = 0; y < h; y++) {
    const sy = vertical ? h - 1 - y : y;
    const srow = sy * w;
    const drow = y * w;
    for (let x = 0; x < w; x++) {
      out[drow + x] = px[srow + (horizontal ? w - 1 - x : x)];
    }
  }
  return new RgbaImage(w, h, out);
}

/**
 * Solve the projective transform mapping four source points to four destination points.
 *
 * @param srcQuad 8 numbers, `x0,y0,x1,y1,x2,y2,x3,y3`
 * @param dstQuad 8 numbers in the same order
 * @returns the 9 row-major coefficients of `H` with `h[8]` fixed to 1, mapping **source to
 *          destination**. Returns the identity when the system is singular (three collinear corners),
 *          because a caller cannot usefully handle a null here and an identity warp is visibly a
 *          no-op rather than silently wrong.
 */
export function solveHomography(
  srcQuad: Float32Array | number[],
  dstQuad: Float32Array | number[],
): Float64Array {
  const a = new Float64Array(8 * 9);
  for (let i = 0; i < 4; i++) {
    const sx = srcQuad[i * 2];
    const sy = srcQuad[i * 2 + 1];
    const dx = dstQuad[i * 2];
    const dy = dstQuad[i * 2 + 1];
    const r0 = (i * 2) * 9;
    a[r0] = sx;
    a[r0 + 1] = sy;
    a[r0 + 2] = 1;
    a[r0 + 6] = -sx * dx;
    a[r0 + 7] = -sy * dx;
    a[r0 + 8] = dx;
    const r1 = (i * 2 + 1) * 9;
    a[r1 + 3] = sx;
    a[r1 + 4] = sy;
    a[r1 + 5] = 1;
    a[r1 + 6] = -sx * dy;
    a[r1 + 7] = -sy * dy;
    a[r1 + 8] = dy;
  }
  const h = new Float64Array(9);
  h[8] = 1;
  // Gaussian elimination with partial pivoting on the 8x9 augmented system.
  for (let col = 0; col < 8; col++) {
    let pivot = col;
    let best = Math.abs(a[col * 9 + col]);
    for (let r = col + 1; r < 8; r++) {
      const v = Math.abs(a[r * 9 + col]);
      if (v > best) {
        best = v;
        pivot = r;
      }
    }
    if (best < 1e-12) {
      h[0] = 1;
      h[1] = 0;
      h[2] = 0;
      h[3] = 0;
      h[4] = 1;
      h[5] = 0;
      h[6] = 0;
      h[7] = 0;
      return h;
    }
    if (pivot !== col) {
      for (let c = 0; c < 9; c++) {
        const t = a[col * 9 + c];
        a[col * 9 + c] = a[pivot * 9 + c];
        a[pivot * 9 + c] = t;
      }
    }
    const inv = 1 / a[col * 9 + col];
    for (let c = col; c < 9; c++) a[col * 9 + c] *= inv;
    for (let r = 0; r < 8; r++) {
      if (r === col) continue;
      const f = a[r * 9 + col];
      if (f === 0) continue;
      for (let c = col; c < 9; c++) a[r * 9 + c] -= f * a[col * 9 + c];
    }
  }
  for (let i = 0; i < 8; i++) h[i] = a[i * 9 + 8];
  return h;
}

/** @returns the adjugate-based inverse of a 3x3, or null when the determinant is degenerate. */
function invert3x3(m: Float64Array): Float64Array | null {
  const a = m[0];
  const b = m[1];
  const c = m[2];
  const d = m[3];
  const e = m[4];
  const f = m[5];
  const g = m[6];
  const hh = m[7];
  const i = m[8];
  const det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g);
  if (!(Math.abs(det) > 1e-15)) return null;
  const inv = 1 / det;
  const out = new Float64Array(9);
  out[0] = (e * i - f * hh) * inv;
  out[1] = (c * hh - b * i) * inv;
  out[2] = (b * f - c * e) * inv;
  out[3] = (f * g - d * i) * inv;
  out[4] = (a * i - c * g) * inv;
  out[5] = (c * d - a * f) * inv;
  out[6] = (d * hh - e * g) * inv;
  out[7] = (b * g - a * hh) * inv;
  out[8] = (a * e - b * d) * inv;
  return out;
}

/**
 * Perspective warp with bilinear sampling.
 *
 * @param h a **source to destination** homography (what {@link solveHomography} returns); it is
 *          inverted internally, because the sampler walks destination pixels
 * @returns a new RgbaImage of `outW` x `outH`; destination pixels whose source falls outside the image
 *          are fully transparent rather than clamped, so an over-large output shows the true extent of
 *          the corrected page instead of a smeared border.
 */
export function warpPerspective(
  src: RgbaImage,
  h: Float64Array,
  outW: number,
  outH: number,
): RgbaImage {
  const ow = Math.max(1, outW | 0);
  const oh = Math.max(1, outH | 0);
  const inv = invert3x3(h);
  const out = new Uint32Array(ow * oh);
  if (inv === null) return new RgbaImage(ow, oh, out);
  const sw = src.width;
  const sh = src.height;
  const px = src.pixels;
  for (let y = 0; y < oh; y++) {
    const row = y * ow;
    for (let x = 0; x < ow; x++) {
      const dx = x + 0.5;
      const dy = y + 0.5;
      const wz = inv[6] * dx + inv[7] * dy + inv[8];
      if (wz === 0) continue;
      const sx = (inv[0] * dx + inv[1] * dy + inv[2]) / wz - 0.5;
      const sy = (inv[3] * dx + inv[4] * dy + inv[5]) / wz - 0.5;
      if (sx < -0.5 || sy < -0.5 || sx > sw - 0.5 || sy > sh - 0.5) continue;
      out[row + x] = sampleBilinearArgb(px, sw, sh, sx, sy);
    }
  }
  return new RgbaImage(ow, oh, out);
}

function sampleBilinearArgb(
  px: Uint32Array,
  w: number,
  h: number,
  fx: number,
  fy: number,
): number {
  const x0 = Math.floor(fx);
  const y0 = Math.floor(fy);
  const tx = fx - x0;
  const ty = fy - y0;
  const xa = Px.clampInt(x0, 0, w - 1);
  const xb = Px.clampInt(x0 + 1, 0, w - 1);
  const ya = Px.clampInt(y0, 0, h - 1);
  const yb = Px.clampInt(y0 + 1, 0, h - 1);
  const p00 = px[ya * w + xa];
  const p10 = px[ya * w + xb];
  const p01 = px[yb * w + xa];
  const p11 = px[yb * w + xb];
  let packed = 0;
  for (let shift = 0; shift < 32; shift += 8) {
    const c00 = (p00 >>> shift) & 0xff;
    const c10 = (p10 >>> shift) & 0xff;
    const c01 = (p01 >>> shift) & 0xff;
    const c11 = (p11 >>> shift) & 0xff;
    const a = c00 + (c10 - c00) * tx;
    const b = c01 + (c11 - c01) * tx;
    packed |= Px.clampInt(Math.round(a + (b - a) * ty), 0, 255) << shift;
  }
  return packed >>> 0;
}

/**
 * Reorder four corners to TL, TR, BR, BL.
 *
 * TL/BR come from the extremes of `x + y` and TR/BL from the extremes of `x - y`. That test is
 * rotation-tolerant up to about 45 degrees, which is far beyond any page a user would photograph, and
 * unlike sorting by angle about the centroid it cannot swap two corners of a nearly-square quad.
 *
 * @param quad 8 numbers; anything shorter is returned unchanged
 */
export function orderQuad(quad: Float32Array | number[]): Float32Array {
  const out = new Float32Array(8);
  if (quad.length < 8) {
    for (let i = 0; i < quad.length; i++) out[i] = quad[i];
    return out;
  }
  let tl = 0;
  let br = 0;
  let tr = 0;
  let bl = 0;
  let minSum = Infinity;
  let maxSum = -Infinity;
  let minDiff = Infinity;
  let maxDiff = -Infinity;
  for (let i = 0; i < 4; i++) {
    const x = quad[i * 2];
    const y = quad[i * 2 + 1];
    const s = x + y;
    const d = x - y;
    if (s < minSum) {
      minSum = s;
      tl = i;
    }
    if (s > maxSum) {
      maxSum = s;
      br = i;
    }
    if (d > maxDiff) {
      maxDiff = d;
      tr = i;
    }
    if (d < minDiff) {
      minDiff = d;
      bl = i;
    }
  }
  const order = [tl, tr, br, bl];
  for (let i = 0; i < 4; i++) {
    out[i * 2] = quad[order[i] * 2];
    out[i * 2 + 1] = quad[order[i] * 2 + 1];
  }
  return out;
}

/** Long edge of the proxy used for quad detection; corners are refined back to full resolution after. */
const QUAD_PROXY = 256;
/** A candidate must cover at least this fraction of the frame, or it is noise rather than a page. */
const QUAD_MIN_AREA_FRACTION = 0.2;

/**
 * Find the largest quadrilateral that plausibly bounds a document.
 *
 * Deliberately simple and deterministic: blur, take the gradient magnitude, keep the strongest
 * responses, take the convex hull of those points, then repeatedly drop the hull vertex whose removal
 * costs the least area until four remain. No Hough transform, no RANSAC, no random seeds.
 *
 * @returns 8 numbers ordered TL,TR,BR,BL in **source** pixel coordinates, or null when nothing covers
 *          at least 20% of the frame — which is the honest answer for a photo with no page in it.
 */
export function detectDocumentQuad(src: GrayF): Float32Array | null {
  const longEdge = Math.max(src.width, src.height);
  const scale = longEdge > QUAD_PROXY ? QUAD_PROXY / longEdge : 1;
  const pw = Math.max(8, Math.round(src.width * scale));
  const ph = Math.max(8, Math.round(src.height * scale));
  const proxy = resizeGray(src, pw, ph);
  const mag = gradients(gaussianBlur(proxy, 1.2)).magnitude();
  // The median plus a margin, so the threshold adapts to the contrast of the photograph instead of
  // assuming a scanner's white background.
  const t = median(mag) * 3 + 1e-4;

  const xs: number[] = [];
  const ys: number[] = [];
  for (let y = 0; y < ph; y++) {
    const row = y * pw;
    for (let x = 0; x < pw; x++) {
      if (mag.data[row + x] > t) {
        xs.push(x);
        ys.push(y);
      }
    }
  }
  if (xs.length < 4) return null;
  const hull = convexHull(xs, ys);
  if (hull.length < 8) return null;
  const quad = reduceToQuad(hull);
  if (quad === null) return null;
  const area = Math.abs(shoelace(quad));
  if (area < QUAD_MIN_AREA_FRACTION * pw * ph) return null;
  const invScale = 1 / scale;
  const full = new Float32Array(8);
  for (let i = 0; i < 8; i++) full[i] = quad[i] * invScale;
  return orderQuad(full);
}

/**
 * Andrew's monotone chain.
 * @returns hull vertices as a flat `x,y` list with no repeated closing vertex; empty when fewer than
 *          three distinct points were supplied.
 */
function convexHull(xs: number[], ys: number[]): Float64Array {
  const n = xs.length;
  const order: number[] = new Array<number>(n);
  for (let i = 0; i < n; i++) order[i] = i;
  order.sort((a, b) => xs[a] - xs[b] || ys[a] - ys[b]);

  const lower: number[] = [];
  for (let k = 0; k < n; k++) {
    const i = order[k];
    while (lower.length >= 2 && chainCross(xs, ys, lower[lower.length - 2], lower[lower.length - 1], i) <= 0) {
      lower.pop();
    }
    lower.push(i);
  }
  const upper: number[] = [];
  for (let k = n - 1; k >= 0; k--) {
    const i = order[k];
    while (upper.length >= 2 && chainCross(xs, ys, upper[upper.length - 2], upper[upper.length - 1], i) <= 0) {
      upper.pop();
    }
    upper.push(i);
  }
  // Both chains repeat the two extreme points; dropping each chain's last entry joins them once.
  const total = lower.length - 1 + upper.length - 1;
  if (total < 3) return new Float64Array(0);
  const hull = new Float64Array(total * 2);
  let w = 0;
  for (let k = 0; k < lower.length - 1; k++) {
    hull[w++] = xs[lower[k]];
    hull[w++] = ys[lower[k]];
  }
  for (let k = 0; k < upper.length - 1; k++) {
    hull[w++] = xs[upper[k]];
    hull[w++] = ys[upper[k]];
  }
  return hull;
}

function chainCross(xs: number[], ys: number[], a: number, b: number, c: number): number {
  return (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a]);
}

function shoelace(p: Float32Array | Float64Array): number {
  let s = 0;
  const n = p.length / 2;
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n;
    s += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1];
  }
  return s * 0.5;
}

/** Greedy vertex removal down to four corners, always dropping the cheapest vertex first. */
function reduceToQuad(hull: Float64Array): Float32Array | null {
  let pts = Array.from(hull);
  let guard = 0;
  while (pts.length > 8 && guard++ < 4096) {
    let bestIdx = -1;
    let bestCost = Infinity;
    const n = pts.length / 2;
    for (let i = 0; i < n; i++) {
      const p = ((i - 1 + n) % n) * 2;
      const c = i * 2;
      const q = ((i + 1) % n) * 2;
      const cost = Math.abs(
        (pts[c] - pts[p]) * (pts[q + 1] - pts[p + 1]) -
          (pts[c + 1] - pts[p + 1]) * (pts[q] - pts[p]),
      );
      if (cost < bestCost) {
        bestCost = cost;
        bestIdx = i;
      }
    }
    if (bestIdx < 0) break;
    pts = pts.slice(0, bestIdx * 2).concat(pts.slice(bestIdx * 2 + 2));
  }
  if (pts.length < 8) return null;
  return Float32Array.from(pts.slice(0, 8));
}

/**
 * Rotate by an arbitrary angle about the image centre, inverse-mapped with bilinear sampling.
 *
 * @param degrees clockwise in image coordinates (y down)
 * @param expand  true grows the canvas to the rotated bounding box; false keeps the original size and
 *                lets the corners fall outside
 * @returns a new RgbaImage; pixels with no source are transparent.
 */
export function rotate(src: RgbaImage, degrees: number, expand = true): RgbaImage {
  const rad = (degrees * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  const sw = src.width;
  const sh = src.height;
  let ow = sw;
  let oh = sh;
  if (expand) {
    ow = Math.max(1, Math.ceil(Math.abs(sw * cos) + Math.abs(sh * sin)));
    oh = Math.max(1, Math.ceil(Math.abs(sw * sin) + Math.abs(sh * cos)));
  }
  const out = new Uint32Array(ow * oh);
  const cx = sw * 0.5;
  const cy = sh * 0.5;
  const ocx = ow * 0.5;
  const ocy = oh * 0.5;
  const px = src.pixels;
  for (let y = 0; y < oh; y++) {
    const dy = y + 0.5 - ocy;
    const row = y * ow;
    for (let x = 0; x < ow; x++) {
      const dx = x + 0.5 - ocx;
      const sx = cos * dx + sin * dy + cx - 0.5;
      const sy = -sin * dx + cos * dy + cy - 0.5;
      if (sx < -0.5 || sy < -0.5 || sx > sw - 0.5 || sy > sh - 0.5) continue;
      out[row + x] = sampleBilinearArgb(px, sw, sh, sx, sy);
    }
  }
  return new RgbaImage(ow, oh, out);
}
