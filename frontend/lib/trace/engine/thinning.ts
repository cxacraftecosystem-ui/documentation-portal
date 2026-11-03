import { Mask } from './buffers';

const NX = [0, 1, 1, 1, 0, -1, -1, -1];
const NY = [-1, -1, 0, 1, 1, 1, 0, -1];

export function neighbourCount(m: Mask, x: number, y: number): number {
  let n = 0;
  for (let k = 0; k < 8; k++) if (m.safe(x + NX[k], y + NY[k])) n++;
  return n;
}

export function transitions(m: Mask, x: number, y: number): number {
  let count = 0;
  let prev = m.safe(x + NX[7], y + NY[7]);
  for (let k = 0; k < 8; k++) {
    const cur = m.safe(x + NX[k], y + NY[k]);
    if (!prev && cur) count++;
    prev = cur;
  }
  return count;
}

function readNeighbours(d: Uint8Array, w: number, h: number, x: number, y: number, p: Int32Array): number {
  let b = 0;
  for (let k = 0; k < 8; k++) {
    const nx = x + NX[k];
    const ny = y + NY[k];
    const v = nx < 0 || ny < 0 || nx >= w || ny >= h ? 0 : d[ny * w + nx];
    p[k] = v;
    b += v;
  }
  return b;
}

function countTransitions(p: Int32Array): number {
  let a = 0;
  let prev = p[7];
  for (let k = 0; k < 8; k++) {
    if (prev === 0 && p[k] === 1) a++;
    prev = p[k];
  }
  return a;
}

export function zhangSuen(src: Mask, maxIterations = 200): Mask {
  const w = src.width;
  const h = src.height;
  const d = src.data.slice();
  const p = new Int32Array(8);
  const doomed = new Int32Array(w * h);
  const iters = Math.max(1, maxIterations | 0);

  for (let it = 0; it < iters; it++) {
    let changed = false;
    for (let sub = 0; sub < 2; sub++) {
      let count = 0;
      for (let y = 0; y < h; y++) {
        const row = y * w;
        for (let x = 0; x < w; x++) {
          const i = row + x;
          if (d[i] === 0) continue;
          const b = readNeighbours(d, w, h, x, y, p);
          if (b < 2 || b > 6) continue;
          if (countTransitions(p) !== 1) continue;
          if (sub === 0) {
            if (p[0] * p[2] * p[4] !== 0) continue;
            if (p[2] * p[4] * p[6] !== 0) continue;
          } else {
            if (p[0] * p[2] * p[6] !== 0) continue;
            if (p[0] * p[4] * p[6] !== 0) continue;
          }
          doomed[count++] = i;
        }
      }
      if (count > 0) {
        for (let k = 0; k < count; k++) d[doomed[k]] = 0;
        changed = true;
      }
    }
    if (!changed) break;
  }
  return new Mask(w, h, d);
}

export function guoHall(src: Mask, maxIterations = 200): Mask {
  const w = src.width;
  const h = src.height;
  const d = src.data.slice();
  const p = new Int32Array(8);
  const doomed = new Int32Array(w * h);
  const iters = Math.max(1, maxIterations | 0);

  for (let it = 0; it < iters; it++) {
    let changed = false;
    for (let sub = 0; sub < 2; sub++) {
      let count = 0;
      for (let y = 0; y < h; y++) {
        const row = y * w;
        for (let x = 0; x < w; x++) {
          const i = row + x;
          if (d[i] === 0) continue;
          readNeighbours(d, w, h, x, y, p);
          const p2 = p[0];
          const p3 = p[1];
          const p4 = p[2];
          const p5 = p[3];
          const p6 = p[4];
          const p7 = p[5];
          const p8 = p[6];
          const p9 = p[7];
          const c =
            (p2 === 0 && (p3 === 1 || p4 === 1) ? 1 : 0) +
            (p4 === 0 && (p5 === 1 || p6 === 1) ? 1 : 0) +
            (p6 === 0 && (p7 === 1 || p8 === 1) ? 1 : 0) +
            (p8 === 0 && (p9 === 1 || p2 === 1) ? 1 : 0);
          if (c !== 1) continue;
          const n1 =
            (p9 | p2) + (p3 | p4) + (p5 | p6) + (p7 | p8);
          const n2 =
            (p2 | p3) + (p4 | p5) + (p6 | p7) + (p8 | p9);
          const nn = n1 < n2 ? n1 : n2;
          if (nn < 2 || nn > 3) continue;
          const m =
            sub === 0
              ? (p6 | p7 | (p9 === 0 ? 1 : 0)) & p8
              : (p2 | p3 | (p5 === 0 ? 1 : 0)) & p4;
          if (m !== 0) continue;
          doomed[count++] = i;
        }
      }
      if (count > 0) {
        for (let k = 0; k < count; k++) d[doomed[k]] = 0;
        changed = true;
      }
    }
    if (!changed) break;
  }
  return new Mask(w, h, d);
}

export function endpoints(skeleton: Mask): Int32Array {
  return collectByDegree(skeleton, 1, 1);
}

export function junctions(skeleton: Mask): Int32Array {
  return collectByDegree(skeleton, 3, 8);
}

function collectByDegree(m: Mask, lo: number, hi: number): Int32Array {
  const w = m.width;
  const h = m.height;
  const d = m.data;
  const buf = new Int32Array(w * h);
  let n = 0;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      if (d[row + x] === 0) continue;
      let deg = 0;
      for (let k = 0; k < 8; k++) {
        const nx = x + NX[k];
        const ny = y + NY[k];
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
        if (d[ny * w + nx] !== 0) deg++;
      }
      if (deg >= lo && deg <= hi) buf[n++] = row + x;
    }
  }
  return buf.slice(0, n);
}

function degreeOf(d: Uint8Array, w: number, h: number, x: number, y: number): number {
  let deg = 0;
  for (let k = 0; k < 8; k++) {
    const nx = x + NX[k];
    const ny = y + NY[k];
    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
    if (d[ny * w + nx] !== 0) deg++;
  }
  return deg;
}

function crossingOf(d: Uint8Array, w: number, h: number, x: number, y: number): number {
  let a = 0;
  let prev = 0;
  {
    const nx = x + NX[7];
    const ny = y + NY[7];
    prev = nx < 0 || ny < 0 || nx >= w || ny >= h ? 0 : d[ny * w + nx];
  }
  for (let k = 0; k < 8; k++) {
    const nx = x + NX[k];
    const ny = y + NY[k];
    const cur = nx < 0 || ny < 0 || nx >= w || ny >= h ? 0 : d[ny * w + nx];
    if (prev === 0 && cur !== 0) a++;
    prev = cur;
  }
  return a;
}

function pickNext(d: Uint8Array, w: number, h: number, x: number, y: number, exclude: number): number {
  let best = -1;
  let bestCrossing = -1;
  for (let k = 0; k < 8; k++) {
    const nx = x + NX[k];
    const ny = y + NY[k];
    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
    const j = ny * w + nx;
    if (d[j] === 0 || j === exclude) continue;
    const c = crossingOf(d, w, h, nx, ny);
    if (c > bestCrossing) {
      bestCrossing = c;
      best = j;
    }
  }
  return best;
}

function soleNeighbour(d: Uint8Array, w: number, h: number, x: number, y: number, exclude: number): number {
  let found = -1;
  let n = 0;
  for (let k = 0; k < 8; k++) {
    const nx = x + NX[k];
    const ny = y + NY[k];
    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
    const j = ny * w + nx;
    if (d[j] === 0 || j === exclude) continue;
    n++;
    if (n > 1) return -1;
    found = j;
  }
  return n === 1 ? found : -1;
}

export function pruneSpurs(skeleton: Mask, minBranchLength: number, maxRounds = 10): Mask {
  const w = skeleton.width;
  const h = skeleton.height;
  const d = skeleton.data.slice();
  if (minBranchLength <= 0 || maxRounds <= 0) return new Mask(w, h, d);

  const limit = minBranchLength > 65536 ? 65536 : minBranchLength | 0;
  const path = new Int32Array(limit);
  const toDelete = new Uint8Array(d.length);
  const rounds = maxRounds | 0;

  for (let round = 0; round < rounds; round++) {
    toDelete.fill(0);
    let removedAny = false;
    for (let start = 0; start < d.length; start++) {
      if (d[start] === 0) continue;
      const sy = (start / w) | 0;
      const sx = start - sy * w;
      if (degreeOf(d, w, h, sx, sy) !== 1) continue;

      let prev = -1;
      let cur = start;
      let n = 0;
      let hitJunction = false;
      while (n < limit) {
        const cy = (cur / w) | 0;
        const cx = cur - cy * w;
        if (n > 0 && crossingOf(d, w, h, cx, cy) >= 3) {
          hitJunction = true;
          break;
        }
        path[n++] = cur;
        const nxt = pickNext(d, w, h, cx, cy, prev);
        if (nxt < 0) break;
        prev = cur;
        cur = nxt;
      }
      if (hitJunction && n < limit) {
        for (let k = 0; k < n; k++) toDelete[path[k]] = 1;
        removedAny = true;
      }
    }
    if (!removedAny) break;
    for (let i = 0; i < d.length; i++) if (toDelete[i] !== 0) d[i] = 0;
  }
  return new Mask(w, h, d);
}

function endpointDirection(
  d: Uint8Array,
  w: number,
  h: number,
  index: number,
  lookBack: number,
  out: Float32Array,
): boolean {
  const y0 = (index / w) | 0;
  const x0 = index - y0 * w;
  let px = x0;
  let py = y0;
  let prev = -1;
  for (let step = 0; step < lookBack; step++) {
    const nxt = soleNeighbour(d, w, h, px, py, prev);
    if (nxt < 0) break;
    prev = py * w + px;
    py = (nxt / w) | 0;
    px = nxt - py * w;
  }
  const dx = x0 - px;
  const dy = y0 - py;
  const len = Math.sqrt(dx * dx + dy * dy);
  if (len > 1e-6) {
    out[0] = dx / len;
    out[1] = dy / len;
    return true;
  }
  out[0] = 0;
  out[1] = 0;
  return false;
}

interface BridgeCandidate {
  readonly a: number;
  readonly b: number;
  readonly dist: number;
}

export function bridgeEndpoints(skeleton: Mask, maxGap: number, maxAngleDegrees = 60): Mask {
  if (maxGap <= 0) return skeleton.copy();
  const w = skeleton.width;
  const h = skeleton.height;
  const d = skeleton.data.slice();
  const ends = endpoints(skeleton);
  if (ends.length < 2) return new Mask(w, h, d);

  const lookBack = 5;
  const dirs = new Float32Array(ends.length * 2);
  const hasDir = new Uint8Array(ends.length);
  const tmp = new Float32Array(2);
  for (let i = 0; i < ends.length; i++) {
    hasDir[i] = endpointDirection(d, w, h, ends[i], lookBack, tmp) ? 1 : 0;
    dirs[i * 2] = tmp[0];
    dirs[i * 2 + 1] = tmp[1];
  }

  const cosLimit = Math.cos((Math.abs(maxAngleDegrees) * Math.PI) / 180);
  const maxGap2 = maxGap * maxGap;
  const candidates: BridgeCandidate[] = [];
  for (let i = 0; i < ends.length; i++) {
    if (hasDir[i] === 0) continue;
    const yi = (ends[i] / w) | 0;
    const xi = ends[i] - yi * w;
    for (let j = i + 1; j < ends.length; j++) {
      if (hasDir[j] === 0) continue;
      const yj = (ends[j] / w) | 0;
      const xj = ends[j] - yj * w;
      const dx = xj - xi;
      const dy = yj - yi;
      const dist2 = dx * dx + dy * dy;
      if (dist2 > maxGap2 || dist2 === 0) continue;
      const dot = dirs[i * 2] * -dirs[j * 2] + dirs[i * 2 + 1] * -dirs[j * 2 + 1];
      if (!(dot > cosLimit)) continue;
      candidates.push({ a: i, b: j, dist: dist2 });
    }
  }
  candidates.sort((p, q) => p.dist - q.dist || p.a - q.a || p.b - q.b);

  const used = new Uint8Array(ends.length);
  const line = new Int32Array(2 * (Math.ceil(maxGap) + 2));
  for (let c = 0; c < candidates.length; c++) {
    const cand = candidates[c];
    if (used[cand.a] !== 0 || used[cand.b] !== 0) continue;
    const ya = (ends[cand.a] / w) | 0;
    const xa = ends[cand.a] - ya * w;
    const yb = (ends[cand.b] / w) | 0;
    const xb = ends[cand.b] - yb * w;
    const n = bresenham(xa, ya, xb, yb, line);
    let blocked = false;
    for (let k = 1; k < n - 1; k++) {
      if (d[line[k * 2 + 1] * w + line[k * 2]] !== 0) {
        blocked = true;
        break;
      }
    }
    if (blocked) continue;
    for (let k = 0; k < n; k++) d[line[k * 2 + 1] * w + line[k * 2]] = 1;
    used[cand.a] = 1;
    used[cand.b] = 1;
  }
  return new Mask(w, h, d);
}

function bresenham(x0: number, y0: number, x1: number, y1: number, out: Int32Array): number {
  let x = x0;
  let y = y0;
  const dx = Math.abs(x1 - x0);
  const dy = -Math.abs(y1 - y0);
  const sx = x0 < x1 ? 1 : -1;
  const sy = y0 < y1 ? 1 : -1;
  let err = dx + dy;
  let n = 0;
  const cap = out.length >> 1;
  for (;;) {
    if (n >= cap) break;
    out[n * 2] = x;
    out[n * 2 + 1] = y;
    n++;
    if (x === x1 && y === y1) break;
    const e2 = 2 * err;
    if (e2 >= dy) {
      err += dy;
      x += sx;
    }
    if (e2 <= dx) {
      err += dx;
      y += sy;
    }
  }
  return n;
}
