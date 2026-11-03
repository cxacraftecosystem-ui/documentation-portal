import { GrayF, Px } from './buffers';
import { gaussianBlur, gaussianBlurDouble } from './convolve';

const MAX_TAU = 0.999;

export function dog(src: GrayF, sigma: number, k = 1.6, tau = 0.98): GrayF {
  const t = Px.clamp(tau, 0, MAX_TAU);
  const s1 = sigma < 0 ? 0 : sigma;
  const s2 = k > 0 ? s1 * k : s1;
  const narrow = gaussianBlur(src, s1);
  const wide = gaussianBlur(src, s2);
  const n = src.data.length;
  const out = new Float32Array(n);
  const a = narrow.data;
  const b = wide.data;
  for (let i = 0; i < n; i++) out[i] = a[i] - t * b[i];
  return new GrayF(src.width, src.height, out);
}

export function softThreshold(u: number, epsilon: number, phi: number): number {
  if (Number.isNaN(u)) return 1;
  if (u >= epsilon) return 1;
  const v = 1 + Math.tanh(phi * (u - epsilon));
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

export function xdog(
  src: GrayF,
  sigma: number,
  k = 1.6,
  tau = 0.98,
  epsilon = 0.5,
  phi = 20,
): GrayF {
  const t = Math.fround(Px.clamp(tau, 0, MAX_TAU));
  const eps = Math.fround(epsilon);
  const sharpness = Math.fround(phi);
  const s1 = Math.fround(sigma < 0 ? 0 : sigma);
  const kf = Math.fround(k);
  const s2 = kf > 0 ? Math.fround(s1 * kf) : s1;

  const a = gaussianBlurDouble(src, s1);
  const b = gaussianBlurDouble(src, s2);
  const scale = 1 / (1 - t);
  const n = a.length;
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = softThreshold(Math.fround((a[i] - t * b[i]) * scale), eps, sharpness);
  }
  return new GrayF(src.width, src.height, out);
}
