import { GrayF, Mask } from './buffers';
import { VecPoint } from './path';

export interface Polyline {
  readonly points: readonly VecPoint[];
  readonly closed: boolean;
}

export interface PolylinesWithWidths {
  readonly polylines: readonly Polyline[];
  readonly widths: readonly Float32Array[];
}

const DX = [1, 1, 0, -1, -1, -1, 0, 1];
const DY = [0, 1, 1, 1, 0, -1, -1, -1];

function opposite(d: number): number {
  return (d + 4) & 7;
}

export function trace(skeleton: Mask): Polyline[] {
  const w = skeleton.width;
  const h = skeleton.height;
  const n = w * h;
  const d = skeleton.data;
  const out: Polyline[] = [];

  const deg = new Uint8Array(n);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      if (d[row + x] === 0) continue;
      let c = 0;
      for (let k = 0; k < 8; k++) {
        const nx = x + DX[k];
        const ny = y + DY[k];
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
        if (d[ny * w + nx] !== 0) c++;
      }
      deg[row + x] = c;
    }
  }

  const usedDir = new Uint8Array(n);
  const visited = new Uint8Array(n);

  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const start = row + x;
      if (d[start] === 0) continue;
      const dg = deg[start];
      if (dg === 2) continue;
      if (dg === 0) {
        visited[start] = 1;
        out.push({ points: [{ x, y }], closed: false });
        continue;
      }
      for (let k = 0; k < 8; k++) {
        if ((usedDir[start] & (1 << k)) !== 0) continue;
        const fx = x + DX[k];
        const fy = y + DY[k];
        if (fx < 0 || fy < 0 || fx >= w || fy >= h) continue;
        const firstIdx = fy * w + fx;
        if (d[firstIdx] === 0) continue;
        usedDir[start] |= 1 << k;
        usedDir[firstIdx] |= 1 << opposite(k);
        const pts: VecPoint[] = [
          { x, y },
          { x: fx, y: fy },
        ];
        let prev = start;
        let cur = firstIdx;
        let cx = fx;
        let cy = fy;
        if (deg[cur] === 2) visited[cur] = 1;
        let guard = 0;
        while (deg[cur] === 2 && guard++ <= n) {
          let nextIdx = -1;
          let nk = -1;
          for (let j = 0; j < 8; j++) {
            const tx = cx + DX[j];
            const ty = cy + DY[j];
            if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
            const ti = ty * w + tx;
            if (d[ti] === 0 || ti === prev) continue;
            nextIdx = ti;
            nk = j;
            break;
          }
          if (nextIdx < 0) break;
          usedDir[cur] |= 1 << nk;
          usedDir[nextIdx] |= 1 << opposite(nk);
          prev = cur;
          cur = nextIdx;
          cy = (cur / w) | 0;
          cx = cur - cy * w;
          pts.push({ x: cx, y: cy });
          if (deg[cur] === 2) visited[cur] = 1;
        }
        out.push({ points: pts, closed: pts.length > 2 && cur === start });
      }
    }
  }

  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const start = row + x;
      if (d[start] === 0 || visited[start] !== 0 || deg[start] !== 2) continue;
      const pts: VecPoint[] = [{ x, y }];
      visited[start] = 1;
      let prev = -1;
      let cur = start;
      let cx = x;
      let cy = y;
      let guard = 0;
      for (;;) {
        let nextIdx = -1;
        for (let j = 0; j < 8; j++) {
          const tx = cx + DX[j];
          const ty = cy + DY[j];
          if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue;
          const ti = ty * w + tx;
          if (d[ti] === 0 || ti === prev) continue;
          nextIdx = ti;
          break;
        }
        if (nextIdx < 0 || nextIdx === start || guard++ > n) break;
        visited[nextIdx] = 1;
        prev = cur;
        cur = nextIdx;
        cy = (cur / w) | 0;
        cx = cur - cy * w;
        pts.push({ x: cx, y: cy });
      }
      out.push({ points: pts, closed: true });
    }
  }
  return out;
}

export interface ChainParams {
  readonly maxTurnDegrees: number;
  readonly tangentSpan: number;
  readonly minBranchLength: number;
  readonly pruneRounds: number;
  readonly maxNodeDegree: number;
}

export const DEFAULT_CHAIN_PARAMS: ChainParams = {
  maxTurnDegrees: 55,
  tangentSpan: 6,
  minBranchLength: 0,
  pruneRounds: 3,
  maxNodeDegree: 4,
};

export function chainParams(over: Partial<ChainParams> = {}): ChainParams {
  return { ...DEFAULT_CHAIN_PARAMS, ...over };
}

const MAX_TURN_DEGREES = 80;

const MAX_PRUNE_ROUNDS = 10;

const NODE_KEY_STRIDE = 4194304;

function nodeKey(p: VecPoint): number {
  return Math.round(p.y) * NODE_KEY_STRIDE + Math.round(p.x);
}

function polylineLength(pl: Polyline): number {
  const pts = pl.points;
  let total = 0;
  for (let i = 1; i < pts.length; i++) {
    const dx = pts[i].x - pts[i - 1].x;
    const dy = pts[i].y - pts[i - 1].y;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  if (pl.closed && pts.length > 1) {
    const dx = pts[0].x - pts[pts.length - 1].x;
    const dy = pts[0].y - pts[pts.length - 1].y;
    total += Math.sqrt(dx * dx + dy * dy);
  }
  return total;
}

function tangentAt(
  points: readonly VecPoint[],
  fromStart: boolean,
  span: number,
  out: Float64Array,
): boolean {
  const n = points.length;
  if (n < 2) return false;
  const step = fromStart ? 1 : -1;
  let idx = fromStart ? 0 : n - 1;
  const end = points[idx];
  let far = end;
  let acc = 0;
  let count = 0;
  while (count < n - 1) {
    const next = points[idx + step];
    const dx = next.x - points[idx].x;
    const dy = next.y - points[idx].y;
    acc += Math.sqrt(dx * dx + dy * dy);
    idx += step;
    far = next;
    count++;
    if (acc >= span) break;
  }
  const vx = far.x - end.x;
  const vy = far.y - end.y;
  const len = Math.sqrt(vx * vx + vy * vy);
  if (len < 1e-9) return false;
  out[0] = vx / len;
  out[1] = vy / len;
  return true;
}

function appendRun(
  dst: VecPoint[],
  src: readonly VecPoint[],
  reversed: boolean,
  skipFirst: boolean,
): void {
  const n = src.length;
  const from = skipFirst ? 1 : 0;
  if (reversed) {
    for (let i = n - 1 - from; i >= 0; i--) dst.push(src[i]);
  } else {
    for (let i = from; i < n; i++) dst.push(src[i]);
  }
}

function nodeDegrees(
  nodeOf: Int32Array,
  alive: Uint8Array,
  nodeCount: number,
  polylineCount: number,
): Int32Array {
  const degree = new Int32Array(nodeCount);
  for (let i = 0; i < polylineCount; i++) {
    if (alive[i] === 0) continue;
    degree[nodeOf[2 * i]]++;
    degree[nodeOf[2 * i + 1]]++;
  }
  return degree;
}

export function chain(
  polylines: readonly Polyline[],
  params: ChainParams = DEFAULT_CHAIN_PARAMS,
): Polyline[] {
  const n = polylines.length;
  if (n === 0) return polylines.slice();

  const chainable = new Uint8Array(n);
  const nodeOf = new Int32Array(2 * n).fill(-1);
  const keyToNode = new Map<number, number>();
  let nodeCount = 0;
  for (let i = 0; i < n; i++) {
    const pl = polylines[i];
    if (pl.closed || pl.points.length < 2) continue;
    chainable[i] = 1;
    for (let port = 0; port < 2; port++) {
      const p = port === 0 ? pl.points[0] : pl.points[pl.points.length - 1];
      const k = nodeKey(p);
      const existing = keyToNode.get(k);
      if (existing !== undefined) {
        nodeOf[2 * i + port] = existing;
      } else {
        nodeOf[2 * i + port] = nodeCount;
        keyToNode.set(k, nodeCount);
        nodeCount++;
      }
    }
  }
  if (nodeCount === 0) return polylines.slice();

  const alive = chainable.slice();

  if (params.minBranchLength > 0 && params.pruneRounds > 0) {
    const rounds = Math.min(params.pruneRounds, MAX_PRUNE_ROUNDS);
    for (let round = 0; round < rounds; round++) {
      const degree = nodeDegrees(nodeOf, alive, nodeCount, n);
      const doomed = new Uint8Array(n);
      let removed = 0;
      for (let i = 0; i < n; i++) {
        if (alive[i] === 0) continue;
        const na = nodeOf[2 * i];
        const nb = nodeOf[2 * i + 1];
        if (na === nb) continue;
        if (polylineLength(polylines[i]) >= params.minBranchLength) continue;
        if ((degree[na] === 1 && degree[nb] >= 3) || (degree[nb] === 1 && degree[na] >= 3)) {
          doomed[i] = 1;
          removed++;
        }
      }
      if (removed === 0) break;
      for (let i = 0; i < n; i++) if (doomed[i] !== 0) alive[i] = 0;
    }
  }

  const span = params.tangentSpan > 0 ? params.tangentSpan : 1;
  const tanX = new Float64Array(2 * n);
  const tanY = new Float64Array(2 * n);
  const hasTangent = new Uint8Array(2 * n);
  const scratch = new Float64Array(2);
  for (let i = 0; i < n; i++) {
    if (alive[i] === 0) continue;
    for (let port = 0; port < 2; port++) {
      if (!tangentAt(polylines[i].points, port === 0, span, scratch)) continue;
      tanX[2 * i + port] = scratch[0];
      tanY[2 * i + port] = scratch[1];
      hasTangent[2 * i + port] = 1;
    }
  }

  const degree = nodeDegrees(nodeOf, alive, nodeCount, n);
  const offset = new Int32Array(nodeCount + 1);
  for (let v = 0; v < nodeCount; v++) offset[v + 1] = offset[v] + degree[v];
  const cursor = offset.slice();
  const endsAt = new Int32Array(offset[nodeCount]);
  for (let i = 0; i < n; i++) {
    if (alive[i] === 0) continue;
    endsAt[cursor[nodeOf[2 * i]]++] = 2 * i;
    endsAt[cursor[nodeOf[2 * i + 1]]++] = 2 * i + 1;
  }

  let turn = Math.fround(params.maxTurnDegrees);
  if (!Number.isFinite(turn) || turn < 0) turn = 0;
  if (turn > MAX_TURN_DEGREES) turn = MAX_TURN_DEGREES;
  const cosLimit = Math.cos((turn * Math.PI) / 180);
  const maxDegree = params.maxNodeDegree < 2 ? 2 : params.maxNodeDegree;

  const candDot: number[] = [];
  const candNode: number[] = [];
  const candA: number[] = [];
  const candB: number[] = [];
  for (let v = 0; v < nodeCount; v++) {
    const d = degree[v];
    if (d < 2 || d > maxDegree) continue;
    for (let a = offset[v]; a < offset[v + 1]; a++) {
      const ea = endsAt[a];
      if (hasTangent[ea] === 0) continue;
      for (let b = a + 1; b < offset[v + 1]; b++) {
        const eb = endsAt[b];
        if (hasTangent[eb] === 0) continue;
        if (ea >> 1 === eb >> 1) continue;
        const dot = -(tanX[ea] * tanX[eb] + tanY[ea] * tanY[eb]);
        if (dot < cosLimit) continue;
        candDot.push(dot);
        candNode.push(v);
        candA.push(ea);
        candB.push(eb);
      }
    }
  }

  const order: number[] = new Array<number>(candDot.length);
  for (let i = 0; i < order.length; i++) order[i] = i;
  order.sort((p, q) => {
    if (candDot[p] !== candDot[q]) return candDot[q] - candDot[p];
    if (candNode[p] !== candNode[q]) return candNode[p] - candNode[q];
    if (candA[p] !== candA[q]) return candA[p] - candA[q];
    return candB[p] - candB[q];
  });
  const link = new Int32Array(2 * n).fill(-1);
  for (let c = 0; c < order.length; c++) {
    const ea = candA[order[c]];
    const eb = candB[order[c]];
    if (link[ea] >= 0 || link[eb] >= 0) continue;
    link[ea] = eb;
    link[eb] = ea;
  }

  const out: Polyline[] = [];
  const visited = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (chainable[i] === 0) {
      out.push(polylines[i]);
      continue;
    }
    if (alive[i] === 0 || visited[i] !== 0) continue;

    let headPoly: number = i;
    let headRev: boolean = false;
    let cyclic = false;
    for (let steps = 0; steps <= n; steps++) {
      const q: number = link[2 * headPoly + (headRev ? 1 : 0)];
      if (q < 0) break;
      const prevPoly = q >> 1;
      if (prevPoly === i) {
        cyclic = true;
        break;
      }
      headRev = (q & 1) === 0;
      headPoly = prevPoly;
    }
    if (cyclic) {
      headPoly = i;
      headRev = false;
    }

    const pts: VecPoint[] = [];
    let curPoly: number = headPoly;
    let curRev: boolean = headRev;
    for (let used = 0; used <= n; used++) {
      visited[curPoly] = 1;
      appendRun(pts, polylines[curPoly].points, curRev, pts.length > 0);
      const q: number = link[2 * curPoly + (curRev ? 0 : 1)];
      if (q < 0) break;
      const nextPoly = q >> 1;
      if (visited[nextPoly] !== 0) break;
      curRev = (q & 1) === 1;
      curPoly = nextPoly;
    }

    if (cyclic && pts.length >= 4) {
      pts.pop();
      out.push({ points: pts, closed: true });
    } else {
      out.push({ points: pts, closed: false });
    }
  }
  return out;
}

export function traceWithWidths(skeleton: Mask, dt: GrayF): PolylinesWithWidths {
  if (dt.width !== skeleton.width || dt.height !== skeleton.height) {
    throw new Error('traceWithWidths(): distance transform size does not match the skeleton');
  }
  const polylines = trace(skeleton);
  const widths: Float32Array[] = new Array<Float32Array>(polylines.length);
  for (let i = 0; i < polylines.length; i++) {
    const pts = polylines[i].points;
    const arr = new Float32Array(pts.length);
    for (let j = 0; j < pts.length; j++) arr[j] = 2 * dt.clamped(pts[j].x | 0, pts[j].y | 0);
    widths[i] = arr;
  }
  return { polylines, widths };
}
