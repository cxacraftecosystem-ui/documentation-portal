import { GrayF, RgbaImage, Px } from './buffers';

/**
 * Colour and luminance conversions. See ALGORITHMS.md §1.
 *
 * Two luminance definitions exist on purpose. {@link toGray} is Rec.601 luma on *gamma-encoded*
 * values, which is what OpenCV's `COLOR_RGB2GRAY` computes, so every threshold and sigma in the
 * literature transfers directly. {@link toGrayLinear} is physically-correct linear-light Rec.709 and
 * is used **only** by matting, where alpha is a linear quantity and the difference genuinely shows.
 */

/** Which scalar plane to pull out of an RGBA image. */
export enum Channel {
  RED = 'RED',
  GREEN = 'GREEN',
  BLUE = 'BLUE',
  ALPHA = 'ALPHA',
  LUMA = 'LUMA',
  MAX = 'MAX',
  MIN = 'MIN',
  SATURATION = 'SATURATION',
  VALUE = 'VALUE',
}

const INV255 = 1 / 255;

/**
 * Rec.601 luma of the gamma-encoded channels; alpha is ignored.
 * @returns a new GrayF with values in 0..1.
 */
export function toGray(src: RgbaImage): GrayF {
  const n = src.pixels.length;
  const out = new Float32Array(n);
  const px = src.pixels;
  for (let i = 0; i < n; i++) {
    const v = px[i];
    const r = (v >>> 16) & 0xff;
    const g = (v >>> 8) & 0xff;
    const b = v & 0xff;
    out[i] = (0.299 * r + 0.587 * g + 0.114 * b) * INV255;
  }
  return new GrayF(src.width, src.height, out);
}

/**
 * Linear-light Rec.709 luminance: channels are linearised first, then weighted 0.2126/0.7152/0.0722.
 * @returns a new GrayF with values in 0..1.
 */
export function toGrayLinear(src: RgbaImage): GrayF {
  const n = src.pixels.length;
  const out = new Float32Array(n);
  const px = src.pixels;
  // 256-entry LUT: linearize() is a pow() and doing it three times per pixel dominated this stage.
  const lut = linearizeLut();
  for (let i = 0; i < n; i++) {
    const v = px[i];
    out[i] =
      0.2126 * lut[(v >>> 16) & 0xff] + 0.7152 * lut[(v >>> 8) & 0xff] + 0.0722 * lut[v & 0xff];
  }
  return new GrayF(src.width, src.height, out);
}

let cachedLinearLut: Float32Array | null = null;

function linearizeLut(): Float32Array {
  let lut = cachedLinearLut;
  if (lut === null) {
    lut = new Float32Array(256);
    for (let i = 0; i < 256; i++) lut[i] = linearize(i * INV255);
    cachedLinearLut = lut;
  }
  return lut;
}

/**
 * Grey plane back to RGBA, values clamped to 0..1 on the way out.
 * @param opaque true writes alpha 255; false writes the grey level into alpha and leaves RGB black.
 */
export function toRgba(src: GrayF, opaque = true): RgbaImage {
  const n = src.data.length;
  const out = new Uint32Array(n);
  const d = src.data;
  if (opaque) {
    for (let i = 0; i < n; i++) {
      const b = Px.toByte255(d[i]);
      out[i] = (0xff000000 | (b << 16) | (b << 8) | b) >>> 0;
    }
  } else {
    // The grey doubles as alpha *and* stays in RGB. Zeroing RGB here instead would make an ink
    // density map preview as a black sheet at varying opacity rather than as the drawing it is.
    for (let i = 0; i < n; i++) {
      const b = Px.toByte255(d[i]);
      out[i] = ((b << 24) | (b << 16) | (b << 8) | b) >>> 0;
    }
  }
  return new RgbaImage(src.width, src.height, out);
}

/**
 * Extract one scalar plane. SATURATION and VALUE are the HSV definitions
 * (`s = (max-min)/max`, `v = max`); MAX/MIN are the raw channel extremes.
 * @returns a new GrayF with values in 0..1.
 */
export function channel(src: RgbaImage, ch: Channel): GrayF {
  const n = src.pixels.length;
  const out = new Float32Array(n);
  const px = src.pixels;
  for (let i = 0; i < n; i++) {
    const v = px[i];
    const a = (v >>> 24) & 0xff;
    const r = (v >>> 16) & 0xff;
    const g = (v >>> 8) & 0xff;
    const b = v & 0xff;
    let o: number;
    switch (ch) {
      case Channel.RED:
        o = r;
        break;
      case Channel.GREEN:
        o = g;
        break;
      case Channel.BLUE:
        o = b;
        break;
      case Channel.ALPHA:
        o = a;
        break;
      case Channel.LUMA:
        o = 0.299 * r + 0.587 * g + 0.114 * b;
        break;
      case Channel.MAX:
      case Channel.VALUE:
        o = r > g ? (r > b ? r : b) : g > b ? g : b;
        break;
      case Channel.MIN:
        o = r < g ? (r < b ? r : b) : g < b ? g : b;
        break;
      case Channel.SATURATION: {
        const mx = r > g ? (r > b ? r : b) : g > b ? g : b;
        const mn = r < g ? (r < b ? r : b) : g < b ? g : b;
        // mx == 0 is pure black, which has no defined hue or saturation; 0 is the conventional answer.
        out[i] = mx === 0 ? 0 : (mx - mn) / mx;
        continue;
      }
      default:
        throw new Error(`channel(): unknown channel ${String(ch)}`);
    }
    out[i] = o * INV255;
  }
  return new GrayF(src.width, src.height, out);
}

/** @returns the alpha plane scaled to 0..1. */
export function alphaOf(src: RgbaImage): GrayF {
  return channel(src, Channel.ALPHA);
}

/**
 * Replace the alpha plane. RGB is untouched (no premultiplication anywhere in this engine).
 * @throws if `alpha` is not the same size as `src`.
 */
export function withAlpha(src: RgbaImage, alpha: GrayF): RgbaImage {
  if (src.width !== alpha.width || src.height !== alpha.height) {
    throw new Error('withAlpha(): size mismatch');
  }
  const n = src.pixels.length;
  const out = new Uint32Array(n);
  const px = src.pixels;
  const a = alpha.data;
  for (let i = 0; i < n; i++) {
    out[i] = ((Px.toByte255(a[i]) << 24) | (px[i] & 0x00ffffff)) >>> 0;
  }
  return new RgbaImage(src.width, src.height, out);
}

/** sRGB transfer function, gamma-encoded 0..1 to linear-light 0..1. */
export function linearize(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** Inverse sRGB transfer function, linear-light 0..1 to gamma-encoded 0..1. */
export function delinearize(l: number): number {
  return l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
}

// D65 white point, matching the sRGB primaries below.
const XN = 0.95047;
const YN = 1.0;
const ZN = 1.08883;
const LAB_DELTA = 6 / 29;
const LAB_T0 = LAB_DELTA * LAB_DELTA * LAB_DELTA;
const LAB_M = 1 / (3 * LAB_DELTA * LAB_DELTA);

function labF(t: number): number {
  return t > LAB_T0 ? Math.cbrt(t) : LAB_M * t + 4 / 29;
}

/**
 * sRGB (gamma-encoded 0..1) to CIELAB D65, writing L,a,b into `out[0..2]`.
 * @param out must have length >= 3; written in place so per-pixel loops allocate nothing.
 */
export function srgbToLab(r: number, g: number, b: number, out: Float32Array | number[]): void {
  const rl = linearize(r);
  const gl = linearize(g);
  const bl = linearize(b);
  const x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl;
  const y = 0.2126729 * rl + 0.7151522 * gl + 0.072175 * bl;
  const z = 0.0193339 * rl + 0.119192 * gl + 0.9503041 * bl;
  const fx = labF(x / XN);
  const fy = labF(y / YN);
  const fz = labF(z / ZN);
  out[0] = 116 * fy - 16;
  out[1] = 500 * (fx - fy);
  out[2] = 200 * (fy - fz);
}

/**
 * ΔE76 — plain Euclidean distance in Lab. ΔE2000 is not worth its cost here because every consumer
 * compares the result against a tolerance the user tunes by eye anyway.
 */
export function labDistance(
  l1: number,
  a1: number,
  b1: number,
  l2: number,
  a2: number,
  b2: number,
): number {
  const dl = l1 - l2;
  const da = a1 - a2;
  const db = b1 - b2;
  return Math.sqrt(dl * dl + da * da + db * db);
}

/**
 * Whole-image Lab conversion.
 * @returns `[L, a, b]`, each a `Float32Array` of `width * height`, in the same index order as the
 *          source pixels.
 */
export function toLabPlanes(src: RgbaImage): Float32Array[] {
  const n = src.pixels.length;
  const lp = new Float32Array(n);
  const ap = new Float32Array(n);
  const bp = new Float32Array(n);
  const px = src.pixels;
  const tmp = new Float32Array(3);
  for (let i = 0; i < n; i++) {
    const v = px[i];
    srgbToLab(
      ((v >>> 16) & 0xff) * INV255,
      ((v >>> 8) & 0xff) * INV255,
      (v & 0xff) * INV255,
      tmp,
    );
    lp[i] = tmp[0];
    ap[i] = tmp[1];
    bp[i] = tmp[2];
  }
  return [lp, ap, bp];
}
