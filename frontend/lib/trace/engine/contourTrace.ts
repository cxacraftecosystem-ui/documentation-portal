import { Mask } from './buffers';
import { label } from './components';
import { VecPoint } from './path';

export interface Contour {
  readonly points: readonly VecPoint[];
  readonly isHole: boolean;
  readonly label: number;
}

const DX = [1, 1, 0, -1, -1, -1, 0, 1];
const DY = [0, 1, 1, 1, 0, -1, -1, -1];
const DIR_N = 6;
const DIR_S = 2;

const DIR_OF = new Int32Array([5, 4, 3, 6, -1, 2, 7, 0, 1]);

export function trace(mask: Mask): Contour[] {
  return traceInternal(mask, true);
}

export function traceOuterOnly(mask: Mask): Contour[] {
  return traceInternal(mask, false);
}

function traceInternal(mask: Mask, withHoles: boolean): Contour[] {
  const w = mask.width;
  const h = mask.height;
  const out: Contour[] = [];
  const fg = label(mask, 8);
  if (fg.count === 0) return out;

  const firstPixel = new Int32Array(fg.count + 1).fill(-1);
  for (let i = 0; i < fg.labels.length; i++) {
    const l = fg.labels[i];
    if (l !== 0 && firstPixel[l] < 0) firstPixel[l] = i;
  }
  for (let l = 1; l <= fg.count; l++) {
    const pts = moore(mask, firstPixel[l], DIR_N);
    out.push({ points: orient(pts, true), isHole: false, label: l });
  }
  if (!withHoles) return out;

  const bg = label(mask.invert(), 4);
  const touches = new Uint8Array(bg.count + 1);
  for (let x = 0; x < w; x++) {
    touches[bg.labels[x]] = 1;
    touches[bg.labels[(h - 1) * w + x]] = 1;
  }
  for (let y = 0; y < h; y++) {
    touches[bg.labels[y * w]] = 1;
    touches[bg.labels[y * w + w - 1]] = 1;
  }
  const seen = new Uint8Array(bg.count + 1);
  for (let i = 0; i < bg.labels.length; i++) {
    const l = bg.labels[i];
    if (l === 0 || touches[l] !== 0 || seen[l] !== 0) continue;
    seen[l] = 1;
    const start = i - w;
    if (start < 0) continue;
    const pts = moore(mask, start, DIR_S);
    out.push({ points: orient(pts, false), isHole: true, label: fg.labels[start] });
  }
  return out;
}

function moore(mask: Mask, startIdx: number, backtrackDir: number): VecPoint[] {
  const w = mask.width;
  const h = mask.height;
  const d = mask.data;
  const pts: VecPoint[] = [];
  let py = (startIdx / w) | 0;
  let px = startIdx - py * w;
  pts.push({ x: px, y: py });
  let b = backtrackDir;
  let firstDir = -1;
  const cap = 8 * w * h + 8;
  for (let iter = 0; iter < cap; iter++) {
    let dir = -1;
    let nx = 0;
    let ny = 0;
    let newB = 0;
    for (let k = 1; k <= 8; k++) {
      const cd = (b + k) & 7;
      const cx = px + DX[cd];
      const cy = py + DY[cd];
      if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue;
      if (d[cy * w + cx] === 0) continue;
      const pd = (cd + 7) & 7;
      const ex = px + DX[pd];
      const ey = py + DY[pd];
      newB = DIR_OF[(ex - cx + 1) * 3 + (ey - cy + 1)];
      dir = cd;
      nx = cx;
      ny = cy;
      break;
    }
    if (dir < 0) break;
    if (py * w + px === startIdx && dir === firstDir) break;
    if (firstDir < 0) firstDir = dir;
    px = nx;
    py = ny;
    b = newB;
    if (ny * w + nx !== startIdx) pts.push({ x: px, y: py });
  }
  return pts;
}

function signedArea(pts: readonly VecPoint[]): number {
  const n = pts.length;
  if (n < 3) return 0;
  let s = 0;
  for (let i = 0; i < n; i++) {
    const a = pts[i];
    const bb = pts[(i + 1) % n];
    s += a.x * bb.y - bb.x * a.y;
  }
  return s * 0.5;
}

function orient(pts: VecPoint[], wantClockwise: boolean): VecPoint[] {
  const area = signedArea(pts);
  const isClockwise = area > 0;
  if (area === 0 || isClockwise === wantClockwise) return pts;
  pts.reverse();
  return pts;
}
