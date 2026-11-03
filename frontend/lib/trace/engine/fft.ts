export function nextPowerOfTwo(n: number): number {
  if (n <= 1) return 1;
  let p = 1;
  while (p < n) p *= 2;
  return p;
}

function isPowerOfTwo(n: number): boolean {
  return n > 0 && (n & (n - 1)) === 0;
}

export function transform(real: Float32Array, imag: Float32Array, inverse: boolean): void {
  const n = real.length;
  if (imag.length !== n) throw new Error('transform(): real and imag must be the same length');
  if (!isPowerOfTwo(n)) throw new Error(`transform(): length ${n} is not a power of two`);
  if (n === 1) return;

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
