import { GrayF } from './buffers';

export enum GradientOp {
  SOBEL = 'SOBEL',
  SCHARR = 'SCHARR',
}

const MIN_SIGMA = 0.05;

const MIN_SIGMA_F32 = Math.fround(MIN_SIGMA);

export function gaussianKernel(sigma: number): Float32Array {
  if (!(sigma > MIN_SIGMA)) return new Float32Array([0, 1, 0]);
  const s = sigma;
  const r = Math.max(1, Math.ceil(3 * s));
  const n = 2 * r + 1;
  const k = new Float32Array(n);
  const inv2s2 = 1 / (2 * s * s);
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const d = i - r;
    const v = Math.exp(-(d * d) * inv2s2);
    k[i] = v;
    sum += v;
  }
  const invSum = 1 / sum;
  for (let i = 0; i < n; i++) k[i] *= invSum;
  return k;
}

export function gaussianKernelDouble(sigma: number): Float64Array {
  const s = Math.fround(sigma);
  if (!(s > MIN_SIGMA_F32)) return new Float64Array([0, 1, 0]);
  const r = Math.max(1, Math.ceil(3 * s));
  const n = 2 * r + 1;
  const k = new Float64Array(n);
  const inv = -1 / (2 * s * s);
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const d = i - r;
    const v = Math.exp(d * d * inv);
    k[i] = v;
    sum += v;
  }
  const norm = 1 / sum;
  for (let i = 0; i < n; i++) k[i] *= norm;
  return k;
}

export function gaussianBlur(src: GrayF, sigma: number): GrayF {
  if (sigma <= MIN_SIGMA) return src.copy();
  const k = gaussianKernel(sigma);
  return separable(src, k, k);
}

export function gaussianBlurDouble(src: GrayF, sigma: number): Float64Array {
  const k = gaussianKernelDouble(sigma);
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const r = k.length >> 1;
  const mid = new Float64Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < k.length; i++) {
        let sx = x - r + i;
        if (sx < 0) sx = 0;
        if (sx > w - 1) sx = w - 1;
        acc += k[i] * d[row + sx];
      }
      mid[row + x] = acc;
    }
  }
  const out = new Float64Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < k.length; i++) {
        let sy = y - r + i;
        if (sy < 0) sy = 0;
        if (sy > h - 1) sy = h - 1;
        acc += k[i] * mid[sy * w + x];
      }
      out[row + x] = acc;
    }
  }
  return out;
}

export function boxBlur(src: GrayF, radius: number): GrayF {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const tmp = new Float32Array(w * h);
  const out = new Float32Array(w * h);
  const d = src.data;
  const win = 2 * r + 1;
  const inv = 1 / win;

  for (let y = 0; y < h; y++) {
    const row = y * w;
    let sum = 0;
    for (let i = -r; i <= r; i++) sum += d[row + (i < 0 ? 0 : i >= w ? w - 1 : i)];
    tmp[row] = sum * inv;
    for (let x = 1; x < w; x++) {
      const add = x + r;
      const sub = x - r - 1;
      sum += d[row + (add >= w ? w - 1 : add)] - d[row + (sub < 0 ? 0 : sub)];
      tmp[row + x] = sum * inv;
    }
  }

  for (let x = 0; x < w; x++) {
    let sum = 0;
    for (let i = -r; i <= r; i++) sum += tmp[(i < 0 ? 0 : i >= h ? h - 1 : i) * w + x];
    out[x] = sum * inv;
    for (let y = 1; y < h; y++) {
      const add = y + r;
      const sub = y - r - 1;
      sum += tmp[(add >= h ? h - 1 : add) * w + x] - tmp[(sub < 0 ? 0 : sub) * w + x];
      out[y * w + x] = sum * inv;
    }
  }
  return new GrayF(w, h, out);
}

export function separable(src: GrayF, kx: Float32Array, ky: Float32Array): GrayF {
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const tmp = kx.length === 0 ? d.slice() : new Float32Array(w * h);
  if (kx.length !== 0) {
    const rx = kx.length >> 1;
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        let acc = 0;
        for (let i = 0; i < kx.length; i++) {
          const sx = x - rx + i;
          acc += d[row + (sx < 0 ? 0 : sx >= w ? w - 1 : sx)] * kx[i];
        }
        tmp[row + x] = acc;
      }
    }
  }
  if (ky.length === 0) return new GrayF(w, h, tmp);
  const ry = ky.length >> 1;
  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let acc = 0;
      for (let i = 0; i < ky.length; i++) {
        const sy = y - ry + i;
        acc += tmp[(sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w + x] * ky[i];
      }
      out[row + x] = acc;
    }
  }
  return new GrayF(w, h, out);
}

export function convolve3(src: GrayF, k: Float32Array | number[]): GrayF {
  if (k.length !== 9) throw new Error('convolve3(): kernel must have 9 taps');
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const out = new Float32Array(w * h);
  const k0 = k[0];
  const k1 = k[1];
  const k2 = k[2];
  const k3 = k[3];
  const k4 = k[4];
  const k5 = k[5];
  const k6 = k[6];
  const k7 = k[7];
  const k8 = k[8];
  for (let y = 0; y < h; y++) {
    const ym = (y > 0 ? y - 1 : 0) * w;
    const y0 = y * w;
    const yp = (y < h - 1 ? y + 1 : h - 1) * w;
    for (let x = 0; x < w; x++) {
      const xm = x > 0 ? x - 1 : 0;
      const xp = x < w - 1 ? x + 1 : w - 1;
      out[y0 + x] =
        d[ym + xm] * k0 +
        d[ym + x] * k1 +
        d[ym + xp] * k2 +
        d[y0 + xm] * k3 +
        d[y0 + x] * k4 +
        d[y0 + xp] * k5 +
        d[yp + xm] * k6 +
        d[yp + x] * k7 +
        d[yp + xp] * k8;
    }
  }
  return new GrayF(w, h, out);
}

export class Gradients {
  constructor(
    readonly gx: GrayF,
    readonly gy: GrayF,
  ) {}

  magnitude(): GrayF {
    const n = this.gx.data.length;
    const out = new Float32Array(n);
    const a = this.gx.data;
    const b = this.gy.data;
    for (let i = 0; i < n; i++) out[i] = Math.sqrt(a[i] * a[i] + b[i] * b[i]);
    return new GrayF(this.gx.width, this.gx.height, out);
  }

  direction(): GrayF {
    const n = this.gx.data.length;
    const out = new Float32Array(n);
    const a = this.gx.data;
    const b = this.gy.data;
    for (let i = 0; i < n; i++) out[i] = Math.atan2(b[i], a[i]);
    return new GrayF(this.gx.width, this.gx.height, out);
  }
}

const SCHARR_X = [3, 0, -3, 10, 0, -10, 3, 0, -3].map((v) => v / 32);
const SCHARR_Y = [3, 10, 3, 0, 0, 0, -3, -10, -3].map((v) => v / 32);
const SOBEL_X = [1, 0, -1, 2, 0, -2, 1, 0, -1].map((v) => v / 8);
const SOBEL_Y = [1, 2, 1, 0, 0, 0, -1, -2, -1].map((v) => v / 8);

export function gradients(src: GrayF, op: GradientOp = GradientOp.SCHARR): Gradients {
  const kx = op === GradientOp.SOBEL ? SOBEL_X : SCHARR_X;
  const ky = op === GradientOp.SOBEL ? SOBEL_Y : SCHARR_Y;
  return new Gradients(convolve3(src, kx), convolve3(src, ky));
}

const LAPLACIAN_8 = [1, 1, 1, 1, -8, 1, 1, 1, 1];

export function laplacian(src: GrayF): GrayF {
  return convolve3(src, LAPLACIAN_8);
}

export function summedAreaTable(src: GrayF): Float64Array {
  const w = src.width;
  const h = src.height;
  const sw = w + 1;
  const sat = new Float64Array(sw * (h + 1));
  const d = src.data;
  for (let y = 0; y < h; y++) {
    let rowSum = 0;
    const srow = (y + 1) * sw;
    const prow = y * sw;
    const drow = y * w;
    for (let x = 0; x < w; x++) {
      rowSum += d[drow + x];
      sat[srow + x + 1] = sat[prow + x + 1] + rowSum;
    }
  }
  return sat;
}

export function rectSum(
  sat: Float64Array,
  w: number,
  h: number,
  x0: number,
  y0: number,
  x1: number,
  y1: number,
): number {
  const ax = x0 < 0 ? 0 : x0;
  const ay = y0 < 0 ? 0 : y0;
  const bx = x1 > w - 1 ? w - 1 : x1;
  const by = y1 > h - 1 ? h - 1 : y1;
  if (bx < ax || by < ay) return 0;
  const sw = w + 1;
  return (
    sat[(by + 1) * sw + (bx + 1)] -
    sat[ay * sw + (bx + 1)] -
    sat[(by + 1) * sw + ax] +
    sat[ay * sw + ax]
  );
}
