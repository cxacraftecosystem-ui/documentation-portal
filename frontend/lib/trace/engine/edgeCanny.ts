import { GrayF, Mask } from './buffers';
import { GradientOp, Gradients, gaussianBlur, gradients } from './convolve';
import { autoCannyThresholds, hysteresis } from './threshold';

export function nonMaximumSuppression(g: Gradients): GrayF {
  const mag = g.magnitude();
  const w = mag.width;
  const h = mag.height;
  const out = new Float32Array(w * h);
  const gx = g.gx.data;
  const gy = g.gy.data;
  const m = mag.data;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const c = m[i];
      if (c <= 0) continue;
      const ax = gx[i];
      const ay = gy[i];
      const denom = Math.max(Math.abs(ax), Math.abs(ay));
      if (denom <= 0) continue;
      const dx = ax / denom;
      const dy = ay / denom;
      const a = mag.sampleBilinear(x + dx, y + dy);
      const b = mag.sampleBilinear(x - dx, y - dy);
      if (c >= a && c >= b) out[i] = c;
    }
  }
  return new GrayF(w, h, out);
}

export class Ridge {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly magnitude: Float32Array,
    readonly offsetX: Float32Array,
    readonly offsetY: Float32Array,
  ) {}

  offsetXAt(x: number, y: number): number {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return 0;
    return this.offsetX[y * this.width + x];
  }

  offsetYAt(x: number, y: number): number {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return 0;
    return this.offsetY[y * this.width + x];
  }

  snap(xs: Float32Array, ys: Float32Array, n = Math.min(xs.length, ys.length)): void {
    const count = Math.max(0, Math.min(n, Math.min(xs.length, ys.length)));
    for (let i = 0; i < count; i++) {
      const x = Math.round(xs[i]);
      const y = Math.round(ys[i]);
      if (x < 0 || y < 0 || x >= this.width || y >= this.height) continue;
      const j = y * this.width + x;
      xs[i] += this.offsetX[j];
      ys[i] += this.offsetY[j];
    }
  }
}

export function subpixelRidge(g: Gradients): Ridge {
  const mag = g.magnitude();
  const w = mag.width;
  const h = mag.height;
  const m = mag.data;
  const gx = g.gx.data;
  const gy = g.gy.data;
  const peak = new Float32Array(w * h);
  const offsetX = new Float32Array(w * h);
  const offsetY = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const c0 = m[i];
      peak[i] = c0;
      if (c0 <= 0) continue;
      const denomAxis = Math.max(Math.abs(gx[i]), Math.abs(gy[i]));
      if (denomAxis <= 0) continue;
      const ux = gx[i] / denomAxis;
      const uy = gy[i] / denomAxis;
      const a = mag.sampleBilinear(x - ux, y - uy);
      const c = mag.sampleBilinear(x + ux, y + uy);
      const denom = a - 2 * c0 + c;
      if (denom >= 0) continue;
      let t = (0.5 * (a - c)) / denom;
      if (!(t > -0.5)) t = -0.5;
      if (!(t < 0.5)) t = 0.5;
      offsetX[i] = t * ux;
      offsetY[i] = t * uy;
      peak[i] = c0 + 0.5 * t * (c - a) + 0.5 * t * t * denom;
    }
  }
  return new Ridge(w, h, peak, offsetX, offsetY);
}

export function detect(
  src: GrayF,
  blurSigma: number,
  low: number,
  high: number,
  op: GradientOp = GradientOp.SCHARR,
): Mask {
  const blurred = gaussianBlur(src, blurSigma);
  const nms = nonMaximumSuppression(gradients(blurred, op));
  return hysteresis(nms, low, high);
}

export function detectAuto(src: GrayF, blurSigma: number, sensitivity = 0.33): Mask {
  const blurred = gaussianBlur(src, blurSigma);
  const g = gradients(blurred, GradientOp.SCHARR);
  const mag = g.magnitude();
  const t = autoCannyThresholds(mag, sensitivity);
  const nms = nonMaximumSuppression(g);
  return hysteresis(nms, t[0], t[1]);
}
