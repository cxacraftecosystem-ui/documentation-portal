import { GrayF, Mask, RgbaImage, Px } from './buffers';

/**
 * Resampling. See ALGORITHMS.md §2.
 *
 * Downscaling averages over the **exact source rectangle** of each destination pixel rather than
 * point-sampling. Point-sampling a 12 MP photo down to 2048 px aliases high-frequency texture into
 * something that looks exactly like an edge, and the edge detector then faithfully traces the alias.
 * This is the single most important quality decision in the preprocessing chain.
 *
 * The two axes carry **independent** tap tables. A 2-D box filter is exactly separable, so this is
 * the same answer as the 2-D integral rather than an approximation, and it means one axis can
 * box-average down while the other interpolates up — which is what a resize to a square proxy does
 * to a portrait image, and where a single "is this an upscale?" test silently picks the wrong filter.
 */

/**
 * Per-destination-index tap list for one axis: `count[i]` taps starting at source index `start[i]`,
 * with the weights at `w[i * stride .. i * stride + count[i] - 1]`.
 *
 * Flat arrays rather than an array of arrays because the table is read once per destination pixel per
 * row, and the extra indirection shows up in the profile at working resolution.
 */
interface Kernel1D {
  readonly stride: number;
  readonly start: Int32Array;
  readonly count: Int32Array;
  readonly w: Float32Array;
}

function buildKernel(srcN: number, dstN: number): Kernel1D {
  const scale = srcN / dstN;
  return scale > 1 ? boxKernel(srcN, dstN, scale) : linearKernel(srcN, dstN, scale);
}

function boxKernel(srcN: number, dstN: number, scale: number): Kernel1D {
  const stride = Math.ceil(scale) + 1;
  const start = new Int32Array(dstN);
  const count = new Int32Array(dstN);
  const wts = new Float32Array(dstN * stride);
  for (let i = 0; i < dstN; i++) {
    const x0 = i * scale;
    const x1 = x0 + scale;
    let j0 = Math.floor(x0);
    let j1 = Math.ceil(x1) - 1;
    if (j0 < 0) j0 = 0;
    if (j1 > srcN - 1) j1 = srcN - 1;
    if (j1 < j0) j1 = j0;
    const off = i * stride;
    let c = 0;
    let sum = 0;
    for (let j = j0; j <= j1 && c < stride; j++, c++) {
      const lo = x0 > j ? x0 : j;
      const hi = x1 < j + 1 ? x1 : j + 1;
      const ov = hi - lo > 0 ? hi - lo : 0;
      wts[off + c] = ov;
      sum += ov;
    }
    // Renormalise per destination pixel. The last pixel's source rectangle can end a rounding step
    // past the image, and leaving that row summing to less than 1 shows up as a one-pixel dark seam
    // along the right and bottom edges of every downscale.
    if (sum > 0) {
      const inv = 1 / sum;
      for (let t = 0; t < c; t++) wts[off + t] *= inv;
    } else {
      wts[off] = 1;
      c = 1;
    }
    start[i] = j0;
    count[i] = c;
  }
  return { stride, start, count, w: wts };
}

function linearKernel(srcN: number, dstN: number, scale: number): Kernel1D {
  const start = new Int32Array(dstN);
  const count = new Int32Array(dstN);
  const wts = new Float32Array(dstN * 2);
  for (let i = 0; i < dstN; i++) {
    // Pixel-centre mapping. The naive `i * scale` shifts the image by half a destination pixel,
    // which is invisible on a photograph and very visible as a drift when a mask and its grey source
    // are resized separately and then compared.
    const pos = (i + 0.5) * scale - 0.5;
    let j0 = Math.floor(pos);
    let t = pos - j0;
    if (j0 < 0) {
      j0 = 0;
      t = 0;
    }
    if (j0 >= srcN - 1) {
      j0 = srcN - 1;
      t = 0;
    }
    const off = i * 2;
    if (t === 0) {
      wts[off] = 1;
      wts[off + 1] = 0;
      count[i] = 1;
    } else {
      wts[off] = 1 - t;
      wts[off + 1] = t;
      count[i] = 2;
    }
    start[i] = j0;
  }
  return { stride: 2, start, count, w: wts };
}

/**
 * Resize a grey image: exact box-area average on any axis that shrinks, linear interpolation on any
 * axis that grows.
 *
 * @returns a new GrayF of exactly `max(1, w)` x `max(1, h)`; `src.copy()` when the size already
 *          matches. Non-positive requests are coerced to 1 rather than throwing — a zero-sized
 *          preview request is a race in the UI, not a programming error.
 */
export function resizeGray(src: GrayF, w: number, h: number): GrayF {
  const dw = Math.max(1, w | 0);
  const dh = Math.max(1, h | 0);
  if (dw === src.width && dh === src.height) return src.copy();

  const srcW = src.width;
  const s = src.data;
  const hk = buildKernel(srcW, dw);
  const vk = buildKernel(src.height, dh);
  const out = new Float32Array(dw * dh);

  // Horizontally-resampled source rows are cached in a ring exactly as wide as the vertical kernel.
  // A full intermediate image would be src.height * dw floats — ~100 MB for a 12 MP input — and
  // destination rows only ever walk forward, so a ring is both smaller and enough.
  const ringSize = vk.stride;
  const ring = new Float32Array(ringSize * dw);
  const ringRow = new Int32Array(ringSize).fill(-1);

  for (let dy = 0; dy < dh; dy++) {
    const vs = vk.start[dy];
    const vc = vk.count[dy];
    const vo = dy * vk.stride;
    const outBase = dy * dw;
    for (let k = 0; k < vc; k++) {
      const sy = vs + k;
      const slot = sy % ringSize;
      const rb = slot * dw;
      if (ringRow[slot] !== sy) {
        ringRow[slot] = sy;
        const srcBase = sy * srcW;
        for (let dx = 0; dx < dw; dx++) {
          const hc = hk.count[dx];
          const ho = dx * hk.stride;
          let acc = 0;
          let idx = srcBase + hk.start[dx];
          for (let t = 0; t < hc; t++, idx++) acc += hk.w[ho + t] * s[idx];
          ring[rb + dx] = acc;
        }
      }
      const wgt = vk.w[vo + k];
      for (let dx = 0; dx < dw; dx++) out[outBase + dx] += wgt * ring[rb + dx];
    }
  }
  return new GrayF(dw, dh, out);
}

/**
 * Resize a packed ARGB image, all four channels averaged independently (non-premultiplied).
 *
 * The pipeline resizes the source before any matte exists, so there is no alpha to premultiply
 * against, and doing it anyway would darken every edge of a straight-alpha PNG.
 *
 * @returns a new RgbaImage of exactly `max(1, w)` x `max(1, h)`.
 */
export function resizeRgba(src: RgbaImage, w: number, h: number): RgbaImage {
  const dw = Math.max(1, w | 0);
  const dh = Math.max(1, h | 0);
  if (dw === src.width && dh === src.height) return src.copy();

  const srcW = src.width;
  const p = src.pixels;
  const hk = buildKernel(srcW, dw);
  const vk = buildKernel(src.height, dh);
  const out = new Uint32Array(dw * dh);
  const ringSize = vk.stride;
  const ring = new Float32Array(ringSize * dw * 4);
  const ringRow = new Int32Array(ringSize).fill(-1);
  const acc = new Float32Array(dw * 4);

  for (let dy = 0; dy < dh; dy++) {
    acc.fill(0);
    const vs = vk.start[dy];
    const vc = vk.count[dy];
    const vo = dy * vk.stride;
    for (let k = 0; k < vc; k++) {
      const sy = vs + k;
      const slot = sy % ringSize;
      const rb = slot * dw * 4;
      if (ringRow[slot] !== sy) {
        ringRow[slot] = sy;
        const srcBase = sy * srcW;
        for (let dx = 0; dx < dw; dx++) {
          const hc = hk.count[dx];
          const ho = dx * hk.stride;
          let aA = 0;
          let aR = 0;
          let aG = 0;
          let aB = 0;
          let idx = srcBase + hk.start[dx];
          for (let t = 0; t < hc; t++, idx++) {
            const wv = hk.w[ho + t];
            const px = p[idx];
            aA += wv * ((px >>> 24) & 0xff);
            aR += wv * ((px >>> 16) & 0xff);
            aG += wv * ((px >>> 8) & 0xff);
            aB += wv * (px & 0xff);
          }
          const o = rb + dx * 4;
          ring[o] = aA;
          ring[o + 1] = aR;
          ring[o + 2] = aG;
          ring[o + 3] = aB;
        }
      }
      const wgt = vk.w[vo + k];
      const n4 = dw * 4;
      for (let t = 0; t < n4; t++) acc[t] += wgt * ring[rb + t];
    }
    const outBase = dy * dw;
    for (let dx = 0; dx < dw; dx++) {
      const o = dx * 4;
      out[outBase + dx] = RgbaImage.argb(
        Px.clamp(Math.floor(acc[o] + 0.5), 0, 255),
        Px.clamp(Math.floor(acc[o + 1] + 0.5), 0, 255),
        Px.clamp(Math.floor(acc[o + 2] + 0.5), 0, 255),
        Px.clamp(Math.floor(acc[o + 3] + 0.5), 0, 255),
      );
    }
  }
  return new RgbaImage(dw, dh, out);
}

/**
 * Nearest neighbour, always. Averaging is deliberately not offered: a mask that comes back with
 * intermediate values is no longer a mask, and every consumer downstream (morphology, thinning,
 * contour tracing) would have to invent its own re-threshold.
 *
 * @returns a new Mask of exactly `max(1, w)` x `max(1, h)`.
 */
export function resizeMask(src: Mask, w: number, h: number): Mask {
  const dw = Math.max(1, w | 0);
  const dh = Math.max(1, h | 0);
  if (dw === src.width && dh === src.height) return src.copy();
  const sw = src.width;
  const sh = src.height;
  const out = new Uint8Array(dw * dh);
  const sx = sw / dw;
  const sy = sh / dh;
  const xmap = new Int32Array(dw);
  for (let dx = 0; dx < dw; dx++) {
    xmap[dx] = Px.clamp(Math.floor((dx + 0.5) * sx), 0, sw - 1);
  }
  const s = src.data;
  for (let dy = 0; dy < dh; dy++) {
    const sBase = Px.clamp(Math.floor((dy + 0.5) * sy), 0, sh - 1) * sw;
    const oBase = dy * dw;
    for (let dx = 0; dx < dw; dx++) out[oBase + dx] = s[sBase + xmap[dx]];
  }
  return new Mask(dw, dh, out);
}

/** Dispatcher mirroring Kotlin's three `Resample.resize` overloads. */
export function resize(src: GrayF, w: number, h: number): GrayF;
export function resize(src: RgbaImage, w: number, h: number): RgbaImage;
export function resize(src: Mask, w: number, h: number): Mask;
export function resize(
  src: GrayF | RgbaImage | Mask,
  w: number,
  h: number,
): GrayF | RgbaImage | Mask {
  if (src instanceof GrayF) return resizeGray(src, w, h);
  if (src instanceof RgbaImage) return resizeRgba(src, w, h);
  return resizeMask(src, w, h);
}

/**
 * Aspect-preserving fit. **Never upscales** — asking for a 4096 px long edge from a 600 px scan
 * returns 600, because inventing pixels before edge detection invents edges too.
 *
 * @param maxLongEdge non-positive means "no limit".
 * @returns `[newW, newH]`, both >= 1.
 */
export function fitWithin(w: number, h: number, maxLongEdge: number): Int32Array {
  const sw = Math.max(1, w | 0);
  const sh = Math.max(1, h | 0);
  if (maxLongEdge <= 0) return new Int32Array([sw, sh]);
  const longEdge = Math.max(sw, sh);
  if (longEdge <= maxLongEdge) return new Int32Array([sw, sh]);
  if (sw >= sh) {
    return new Int32Array([maxLongEdge, Math.max(1, Math.round((sh * maxLongEdge) / sw))]);
  }
  return new Int32Array([Math.max(1, Math.round((sw * maxLongEdge) / sh)), maxLongEdge]);
}

/** @returns `src` scaled so its long edge is at most `maxLongEdge`; a copy when already small enough. */
export function scaleToLongEdge(src: RgbaImage, maxLongEdge: number): RgbaImage {
  const wh = fitWithin(src.width, src.height, maxLongEdge);
  return resizeRgba(src, wh[0], wh[1]);
}

/**
 * Sub-rectangle of `src`.
 *
 * The rectangle is clamped into the image rather than validated: a crop that runs off the edge is a
 * user gesture near the border, not a programming error, and throwing there loses the whole
 * operation. The result is always at least 1x1.
 */
export function cropRgba(src: RgbaImage, x: number, y: number, w: number, h: number): RgbaImage {
  const x0 = Px.clamp(x | 0, 0, src.width - 1);
  const y0 = Px.clamp(y | 0, 0, src.height - 1);
  const cw = Px.clamp(w | 0, 1, src.width - x0);
  const ch = Px.clamp(h | 0, 1, src.height - y0);
  const out = new Uint32Array(cw * ch);
  for (let row = 0; row < ch; row++) {
    const s = (y0 + row) * src.width + x0;
    out.set(src.pixels.subarray(s, s + cw), row * cw);
  }
  return new RgbaImage(cw, ch, out);
}

/** @see cropRgba */
export function cropGray(src: GrayF, x: number, y: number, w: number, h: number): GrayF {
  const x0 = Px.clamp(x | 0, 0, src.width - 1);
  const y0 = Px.clamp(y | 0, 0, src.height - 1);
  const cw = Px.clamp(w | 0, 1, src.width - x0);
  const ch = Px.clamp(h | 0, 1, src.height - y0);
  const out = new Float32Array(cw * ch);
  for (let row = 0; row < ch; row++) {
    const s = (y0 + row) * src.width + x0;
    out.set(src.data.subarray(s, s + cw), row * cw);
  }
  return new GrayF(cw, ch, out);
}

/** Dispatcher mirroring Kotlin's two `Resample.crop` overloads. */
export function crop(src: GrayF, x: number, y: number, w: number, h: number): GrayF;
export function crop(src: RgbaImage, x: number, y: number, w: number, h: number): RgbaImage;
export function crop(
  src: GrayF | RgbaImage,
  x: number,
  y: number,
  w: number,
  h: number,
): GrayF | RgbaImage {
  if (src instanceof GrayF) return cropGray(src, x, y, w, h);
  return cropRgba(src, x, y, w, h);
}

/**
 * Pad with a constant. Negative pad amounts are treated as zero — padding gives a filter room to
 * work, and a caller that computes a negative margin wants no padding on that side, not a silent
 * crop.
 *
 * @returns a new GrayF of `left + src.width + right` by `top + src.height + bottom`.
 */
export function pad(
  src: GrayF,
  left: number,
  top: number,
  right: number,
  bottom: number,
  value: number,
): GrayF {
  const l = Math.max(0, left | 0);
  const t = Math.max(0, top | 0);
  const r = Math.max(0, right | 0);
  const b = Math.max(0, bottom | 0);
  if (l === 0 && t === 0 && r === 0 && b === 0) return src.copy();
  const w = src.width + l + r;
  const h = src.height + t + b;
  const out = new Float32Array(w * h);
  if (value !== 0) out.fill(value);
  for (let y = 0; y < src.height; y++) {
    out.set(src.data.subarray(y * src.width, (y + 1) * src.width), (y + t) * w + l);
  }
  return new GrayF(w, h, out);
}
