import { GrayF, Mask } from './buffers';
import { gaussianBlur, laplacian } from './convolve';

export function logResponse(src: GrayF, sigma: number): GrayF {
  return laplacian(gaussianBlur(src, sigma));
}

export function zeroCrossings(log: GrayF, slopeThreshold: number): Mask {
  const w = log.width;
  const h = log.height;
  const d = log.data;
  const out = new Uint8Array(w * h);
  const t = Math.abs(slopeThreshold);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const v = d[i];
      if (x + 1 < w) {
        const j = i + 1;
        const u = d[j];
        if (straddlesZero(v, u) && Math.abs(v - u) > t) {
          if (Math.abs(v) <= Math.abs(u)) out[i] = 1;
          else out[j] = 1;
        }
      }
      if (y + 1 < h) {
        const j = i + w;
        const u = d[j];
        if (straddlesZero(v, u) && Math.abs(v - u) > t) {
          if (Math.abs(v) <= Math.abs(u)) out[i] = 1;
          else out[j] = 1;
        }
      }
    }
  }
  return new Mask(w, h, out);
}

function straddlesZero(a: number, b: number): boolean {
  return (a < 0 && b > 0) || (a > 0 && b < 0);
}

export function detect(src: GrayF, sigma: number, slopeThreshold: number): Mask {
  return zeroCrossings(logResponse(src, sigma), slopeThreshold);
}
