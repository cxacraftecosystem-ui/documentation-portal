import { GrayF, Mask } from './buffers';

const INF = 1e20;

export function euclidean(src: Mask, insideForeground = true): GrayF {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const d = src.data;
  const f = new Float64Array(n);
  let anySource = false;
  for (let i = 0; i < n; i++) {
    const isSource = insideForeground ? d[i] === 0 : d[i] !== 0;
    if (isSource) {
      f[i] = 0;
      anySource = true;
    } else {
      f[i] = INF;
    }
  }
  if (!anySource) {
    return new GrayF(w, h);
  }

  const maxDim = Math.max(w, h);
  const row = new Float64Array(maxDim);
  const dt = new Float64Array(maxDim);
  const v = new Int32Array(maxDim);
  const z = new Float64Array(maxDim + 1);

  for (let x = 0; x < w; x++) {
    for (let y = 0; y < h; y++) row[y] = f[y * w + x];
    lowerEnvelope(row, h, dt, v, z);
    for (let y = 0; y < h; y++) f[y * w + x] = dt[y];
  }
  for (let y = 0; y < h; y++) {
    const base = y * w;
    for (let x = 0; x < w; x++) row[x] = f[base + x];
    lowerEnvelope(row, w, dt, v, z);
    for (let x = 0; x < w; x++) f[base + x] = dt[x];
  }

  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = Math.sqrt(f[i]);
  return new GrayF(w, h, out);
}

function lowerEnvelope(
  fRow: Float64Array,
  n: number,
  dt: Float64Array,
  v: Int32Array,
  z: Float64Array,
): void {
  let k = 0;
  v[0] = 0;
  z[0] = -INF;
  z[1] = INF;
  for (let q = 1; q < n; q++) {
    let top = k;
    let s = 0;
    for (;;) {
      const p = v[top];
      s = (fRow[q] + q * q - (fRow[p] + p * p)) / (2 * (q - p));
      if (top > 0 && s <= z[top]) {
        top--;
        continue;
      }
      break;
    }
    k = top + 1;
    v[k] = q;
    z[k] = s;
    z[k + 1] = INF;
  }
  let k2 = 0;
  for (let q = 0; q < n; q++) {
    while (z[k2 + 1] < q) k2++;
    const p = v[k2];
    const diff = q - p;
    dt[q] = diff * diff + fRow[p];
  }
}

export function strokeWidthAt(dt: GrayF, x: number, y: number): number {
  return 2 * dt.clamped(x | 0, y | 0);
}
