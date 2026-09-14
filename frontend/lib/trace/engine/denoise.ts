import { GrayF, Px } from './buffers';

/**
 * Denoising. See ALGORITHMS.md §4.
 *
 * Bilateral and median are both offered because they fail differently: bilateral is the right tool
 * for sensor noise, median for salt-and-pepper dust on a scan. Picking one for the user would make
 * the other class of source untraceable.
 */

/**
 * Edge-preserving bilateral filter, edge-clamped.
 *
 * The range term is read from a 256-entry LUT indexed by `round(|dI| * 255)`; recomputing `exp` per
 * neighbour pair made this stage dominate the whole pipeline. Spatial radius is `ceil(2 * sigmaSpace)`
 * — 2σ not 3σ, because the range term already suppresses the tail and the kernel is 2-D.
 *
 * @returns a new GrayF; `src.copy()` when either sigma is <= 0.
 */
export function bilateral(src: GrayF, sigmaSpace: number, sigmaRange: number): GrayF {
  if (sigmaSpace <= 0 || sigmaRange <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const r = Math.max(1, Math.ceil(2 * sigmaSpace));
  const side = 2 * r + 1;

  const spatial = new Float32Array(side * side);
  const invSpace = 1 / (2 * sigmaSpace * sigmaSpace);
  for (let dy = -r; dy <= r; dy++) {
    for (let dx = -r; dx <= r; dx++) {
      spatial[(dy + r) * side + (dx + r)] = Math.exp(-(dx * dx + dy * dy) * invSpace);
    }
  }
  const rangeLut = new Float32Array(256);
  const invRange = 1 / (2 * sigmaRange * sigmaRange);
  for (let i = 0; i < 256; i++) {
    const t = i / 255;
    rangeLut[i] = Math.exp(-(t * t) * invRange);
  }

  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const centre = d[row + x];
      let acc = 0;
      let wsum = 0;
      for (let dy = -r; dy <= r; dy++) {
        const sy = y + dy;
        const srow = (sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w;
        const krow = (dy + r) * side;
        for (let dx = -r; dx <= r; dx++) {
          const sx = x + dx;
          const v = d[srow + (sx < 0 ? 0 : sx >= w ? w - 1 : sx)];
          let idx = Math.round(Math.abs(v - centre) * 255);
          if (idx > 255) idx = 255;
          const weight = spatial[krow + dx + r] * rangeLut[idx];
          acc += weight * v;
          wsum += weight;
        }
      }
      out[row + x] = wsum > 0 ? acc / wsum : centre;
    }
  }
  return new GrayF(w, h, out);
}

/**
 * Median filter, edge-clamped.
 *
 * `radius == 1` sorts the 9 samples exactly. `radius >= 2` uses a sliding 256-bin histogram, which is
 * O(1) amortised in the window width; the price is that the output is quantised to 1/255 steps. That
 * is the trade the reference algorithm makes and both engines make it identically, because a median
 * that costs O(r^2) makes radius 6 unusable on a phone.
 *
 * @returns a new GrayF; `src.copy()` when `radius <= 0`.
 */
export function median(src: GrayF, radius: number): GrayF {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  return r === 1 ? median3(src) : medianHistogram(src, r);
}

function median3(src: GrayF): GrayF {
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const out = new Float32Array(w * h);
  const buf = new Float32Array(9);
  for (let y = 0; y < h; y++) {
    const ym = (y > 0 ? y - 1 : 0) * w;
    const y0 = y * w;
    const yp = (y < h - 1 ? y + 1 : h - 1) * w;
    for (let x = 0; x < w; x++) {
      const xm = x > 0 ? x - 1 : 0;
      const xp = x < w - 1 ? x + 1 : w - 1;
      buf[0] = d[ym + xm];
      buf[1] = d[ym + x];
      buf[2] = d[ym + xp];
      buf[3] = d[y0 + xm];
      buf[4] = d[y0 + x];
      buf[5] = d[y0 + xp];
      buf[6] = d[yp + xm];
      buf[7] = d[yp + x];
      buf[8] = d[yp + xp];
      // Insertion sort: nine elements, no allocation, and it beats every clever network in practice.
      for (let i = 1; i < 9; i++) {
        const v = buf[i];
        let j = i - 1;
        while (j >= 0 && buf[j] > v) {
          buf[j + 1] = buf[j];
          j--;
        }
        buf[j + 1] = v;
      }
      out[y0 + x] = buf[4];
    }
  }
  return new GrayF(w, h, out);
}

function medianHistogram(src: GrayF, r: number): GrayF {
  const w = src.width;
  const h = src.height;
  const out = new Float32Array(w * h);
  const bins = new Int32Array(256);
  const q = new Uint8Array(w * h);
  const d = src.data;
  for (let i = 0; i < d.length; i++) q[i] = Px.toByte255(d[i]);
  const win = (2 * r + 1) * (2 * r + 1);
  const half = (win + 1) >> 1;

  for (let y = 0; y < h; y++) {
    bins.fill(0);
    // Prime the window at x = 0, columns [-r, r] with out-of-range columns clamped to column 0.
    for (let dy = -r; dy <= r; dy++) {
      const sy = y + dy;
      const srow = (sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w;
      for (let dx = -r; dx <= r; dx++) {
        const sx = dx < 0 ? 0 : dx >= w ? w - 1 : dx;
        bins[q[srow + sx]]++;
      }
    }
    out[y * w] = pickMedian(bins, half) / 255;
    for (let x = 1; x < w; x++) {
      const addX = x + r;
      const subX = x - r - 1;
      const ax = addX >= w ? w - 1 : addX;
      const sx = subX < 0 ? 0 : subX;
      for (let dy = -r; dy <= r; dy++) {
        const sy = y + dy;
        const srow = (sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w;
        bins[q[srow + ax]]++;
        bins[q[srow + sx]]--;
      }
      out[y * w + x] = pickMedian(bins, half) / 255;
    }
  }
  return new GrayF(w, h, out);
}

function pickMedian(bins: Int32Array, half: number): number {
  let cum = 0;
  for (let i = 0; i < 256; i++) {
    cum += bins[i];
    if (cum >= half) return i;
  }
  return 255;
}

/**
 * Perona-Malik anisotropic diffusion with conduction function `c(g) = exp(-(g/kappa)^2)`.
 *
 * @param iterations fixed count; 0 or fewer returns `src.copy()`
 * @param kappa      conduction threshold; <= 0 returns `src.copy()`
 * @param lambda     step size, clamped to 0.25 which is the stability limit for a 4-neighbourhood
 * @returns a new GrayF the same size as `src`.
 */
export function anisotropicDiffusion(
  src: GrayF,
  iterations: number,
  kappa: number,
  lambda = 0.25,
): GrayF {
  const iters = iterations | 0;
  if (iters <= 0 || kappa <= 0) return src.copy();
  const lam = Px.clamp(lambda, 0, 0.25);
  const w = src.width;
  const h = src.height;
  let cur = src.data.slice();
  let next = new Float32Array(w * h);
  const invK2 = 1 / (kappa * kappa);
  for (let it = 0; it < iters; it++) {
    for (let y = 0; y < h; y++) {
      const row = y * w;
      const rowN = (y > 0 ? y - 1 : 0) * w;
      const rowS = (y < h - 1 ? y + 1 : h - 1) * w;
      for (let x = 0; x < w; x++) {
        const c = cur[row + x];
        const dn = cur[rowN + x] - c;
        const ds = cur[rowS + x] - c;
        const de = cur[row + (x < w - 1 ? x + 1 : w - 1)] - c;
        const dw = cur[row + (x > 0 ? x - 1 : 0)] - c;
        next[row + x] =
          c +
          lam *
            (Math.exp(-dn * dn * invK2) * dn +
              Math.exp(-ds * ds * invK2) * ds +
              Math.exp(-de * de * invK2) * de +
              Math.exp(-dw * dw * invK2) * dw);
      }
    }
    const swap = cur;
    cur = next;
    next = swap;
  }
  return new GrayF(w, h, cur);
}

/**
 * Median only where the pixel disagrees with its neighbourhood by more than `threshold`.
 *
 * This is the correct shape for dust removal: a plain median softens every stroke it passes over,
 * while despeckle leaves the 99% of pixels that agree with their surroundings bit-identical.
 *
 * @returns a new GrayF; `src.copy()` when `radius <= 0`.
 */
export function despeckle(src: GrayF, radius: number, threshold: number): GrayF {
  if (radius <= 0) return src.copy();
  const med = median(src, radius);
  const n = src.data.length;
  const out = new Float32Array(n);
  const a = src.data;
  const m = med.data;
  const t = Math.abs(threshold);
  for (let i = 0; i < n; i++) {
    const v = a[i];
    out[i] = Math.abs(v - m[i]) > t ? m[i] : v;
  }
  return new GrayF(src.width, src.height, out);
}
