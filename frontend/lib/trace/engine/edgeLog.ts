import { GrayF, Mask } from './buffers';
import { gaussianBlur, laplacian } from './convolve';

/**
 * Laplacian-of-Gaussian zero crossings. See ALGORITHMS.md §7.5.
 *
 * Kept because it gives the thinnest, most delicate lines of any engine here, which is exactly what
 * jewellery and filigree need and exactly what Canny's hysteresis destroys by fattening every
 * response into a connected ridge.
 */

/** @returns the signed LoG response: an 8-neighbour Laplacian of a Gaussian-blurred copy. */
export function logResponse(src: GrayF, sigma: number): GrayF {
  return laplacian(gaussianBlur(src, sigma));
}

/**
 * Zero crossings of a signed response.
 *
 * Each horizontal and vertical 4-neighbour pair is examined **once**, and when it straddles zero with
 * a slope steeper than `slopeThreshold` the mark goes on whichever of the two pixels is closer to zero.
 * Marking both — the obvious implementation — doubles every line to 2 px wide, which defeats the only
 * reason to choose this engine.
 *
 * @param slopeThreshold minimum `|a - b|` across the pair; scale it with the LoG kernel's own gain
 * @returns a Mask whose `true` pixels are edges.
 */
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

/**
 * True only when `a` and `b` lie strictly on opposite sides of zero.
 *
 * §7.5 defines an edge as a *sign change*, and zero has no sign — a response of exactly 0 is what the
 * finite kernel returns across every flat region, and treating it as positive marks a one-pixel false
 * edge wherever a negative tail meets that plateau. Spelled out rather than as `a * b < 0` so the
 * Kotlin mirror can be identical: there the product of two opposite subnormals underflows to `-0f`
 * and loses a real crossing.
 */
function straddlesZero(a: number, b: number): boolean {
  return (a < 0 && b > 0) || (a > 0 && b < 0);
}

/** Convenience: {@link logResponse} then {@link zeroCrossings}. */
export function detect(src: GrayF, sigma: number, slopeThreshold: number): Mask {
  return zeroCrossings(logResponse(src, sigma), slopeThreshold);
}
