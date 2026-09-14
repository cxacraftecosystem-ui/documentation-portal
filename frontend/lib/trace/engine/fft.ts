/**
 * Radix-2 Cooley-Tukey FFT. See ALGORITHMS.md §8.
 *
 * Exists solely for the spectral-residual saliency matte, which needs a complex 2-D transform of a
 * 64x64 proxy. Decimation in time with an explicit bit-reversal permutation, in place, no allocation
 * beyond the twiddle tables.
 *
 * Sign convention: forward is `X[k] = sum x[n] e^(-2*pi*i*k*n/N)`; inverse is the same sum with `+i`
 * and a `1/N` scale, so `transform(re, im, true)` after `transform(re, im, false)` is the identity.
 */

/** @returns the smallest power of two >= `n`, and 1 for any `n <= 1`. */
export function nextPowerOfTwo(n: number): number {
  if (n <= 1) return 1;
  let p = 1;
  while (p < n) p *= 2;
  return p;
}

function isPowerOfTwo(n: number): boolean {
  return n > 0 && (n & (n - 1)) === 0;
}

/**
 * In-place complex FFT.
 * @param real length must be a power of two
 * @param imag same length as `real`
 * @param inverse true applies the `+i` kernel and divides by N
 * @throws if the lengths differ or the length is not a power of two.
 */
export function transform(real: Float32Array, imag: Float32Array, inverse: boolean): void {
  const n = real.length;
  if (imag.length !== n) throw new Error('transform(): real and imag must be the same length');
  if (!isPowerOfTwo(n)) throw new Error(`transform(): length ${n} is not a power of two`);
  if (n === 1) return;

  // Bit-reversal permutation.
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      const tr = real[i];
      real[i] = real[j];
      real[j] = tr;
      const ti = imag[i];
      imag[i] = imag[j];
      imag[j] = ti;
    }
  }

  const sign = inverse ? 1 : -1;
  // Twiddles come from a table computed with direct cos/sin per stage rather than from an angle
  // recurrence. The recurrence is faster and drifts: over the 2048 steps of the last stage of a 4096
  // point transform the accumulated error is large enough to break a 1e-4 round-trip assertion, which
  // is exactly the tolerance the two engines are held to.
  const tw = new Float64Array(n);
  for (let len = 2; len <= n; len <<= 1) {
    const half = len >> 1;
    const ang = (sign * 2 * Math.PI) / len;
    for (let k = 0; k < half; k++) {
      tw[2 * k] = Math.cos(ang * k);
      tw[2 * k + 1] = Math.sin(ang * k);
    }
    for (let i = 0; i < n; i += len) {
      for (let k = 0; k < half; k++) {
        const a = i + k;
        const b = a + half;
        const cr = tw[2 * k];
        const ci = tw[2 * k + 1];
        const xr = real[b] * cr - imag[b] * ci;
        const xi = real[b] * ci + imag[b] * cr;
        real[b] = real[a] - xr;
        imag[b] = imag[a] - xi;
        real[a] += xr;
        imag[a] += xi;
      }
    }
  }

  if (inverse) {
    const inv = 1 / n;
    for (let i = 0; i < n; i++) {
      real[i] *= inv;
      imag[i] *= inv;
    }
  }
}

/**
 * In-place complex 2-D FFT: every row, then every column.
 * @param w must be a power of two, and `w * h` must equal the array lengths
 * @param h must be a power of two
 * @throws if either dimension is not a power of two or the lengths disagree.
 */
export function transform2d(
  real: Float32Array,
  imag: Float32Array,
  w: number,
  h: number,
  inverse: boolean,
): void {
  if (real.length !== w * h || imag.length !== w * h) {
    throw new Error('transform2d(): array length must equal w * h');
  }
  if (!isPowerOfTwo(w) || !isPowerOfTwo(h)) {
    throw new Error(`transform2d(): ${w}x${h} is not a power of two on both axes`);
  }
  const rowR = new Float32Array(w);
  const rowI = new Float32Array(w);
  for (let y = 0; y < h; y++) {
    const base = y * w;
    rowR.set(real.subarray(base, base + w));
    rowI.set(imag.subarray(base, base + w));
    transform(rowR, rowI, inverse);
    real.set(rowR, base);
    imag.set(rowI, base);
  }
  const colR = new Float32Array(h);
  const colI = new Float32Array(h);
  for (let x = 0; x < w; x++) {
    for (let y = 0; y < h; y++) {
      colR[y] = real[y * w + x];
      colI[y] = imag[y * w + x];
    }
    transform(colR, colI, inverse);
    for (let y = 0; y < h; y++) {
      real[y * w + x] = colR[y];
      imag[y * w + x] = colI[y];
    }
  }
}
