import { Mask } from './buffers';
import { label } from './components';
import { VecPoint } from './path';

/**
 * Contour tracing. See ALGORITHMS.md §10.1.
 *
 * Moore-neighbour tracing with **Jacob's stopping criterion**, which is the correct one: stopping merely
 * on "revisited the start pixel" fails on a shape with a one-pixel isthmus and re-traces one lobe
 * forever. The trace stops when it is about to leave the start pixel in the same direction it first did.
 */

/** One traced boundary. Points are pixel coordinates; the closing point is not repeated. */
export interface Contour {
  readonly points: readonly VecPoint[];
  readonly isHole: boolean;
  /** The foreground component this boundary belongs to — for a hole, the component that encloses it. */
  readonly label: number;
}

// Clockwise from east, in image coordinates (y down).
const DX = [1, 1, 0, -1, -1, -1, 0, 1];
const DY = [0, 1, 1, 1, 0, -1, -1, -1];
const DIR_N = 6;
const DIR_S = 2;

// (dx + 1) * 3 + (dy + 1) -> direction index; -1 for the zero vector, which cannot occur.
const DIR_OF = new Int32Array([5, 4, 3, 6, -1, 2, 7, 0, 1]);

/**
 * Trace every outer boundary and every hole.
 *
 * Outer boundaries come out **clockwise** and holes **counter-clockwise** (positive and negative
 * shoelace area respectively in y-down coordinates). Opposite windings are what make `fill-rule:
 * evenodd` render holes correctly with no extra bookkeeping, so the winding is enforced by checking the
 * signed area rather than trusted to fall out of the traversal.
 *
 * @returns contours in a deterministic order: every component's outer boundary in raster order of its
 *          first pixel, then every hole in raster order of its first pixel.
 */
export function trace(mask: Mask): Contour[] {
  return traceInternal(mask, true);
}

/** As {@link trace} but skips holes — for silhouettes and stencils where interior detail is unwanted. */
export function traceOuterOnly(mask: Mask): Contour[] {
  return traceInternal(mask, false);
}

function traceInternal(mask: Mask, withHoles: boolean): Contour[] {
  const w = mask.width;
  const h = mask.height;
  const out: Contour[] = [];
  const fg = label(mask, 8);
  if (fg.count === 0) return out;

  // First pixel of each foreground component in raster order; that pixel's north neighbour is
  // guaranteed background, because a foreground north neighbour would be 8-connected and therefore the
  // same component with a smaller y.
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

  // The background is labelled 4-connected while the foreground is 8-connected. That pairing is the
  // standard one: with an 8-connected background a hole leaks out through any diagonal touch and is
  // never found.
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
    // The topmost-leftmost pixel of an enclosed background region always has foreground directly above.
    const start = i - w;
    if (start < 0) continue;
    const pts = moore(mask, start, DIR_S);
    out.push({ points: orient(pts, false), isHole: true, label: fg.labels[start] });
  }
  return out;
}

/**
 * One Moore-neighbour walk.
 * @param startIdx     packed index of a foreground pixel
 * @param backtrackDir direction from `startIdx` to an adjacent **background** cell (out of bounds counts)
 * @returns the boundary pixels in traversal order; a single-element list for an isolated pixel.
 */
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
  // Every boundary pixel can be entered from at most 8 directions, so 8 * area bounds any legal walk.
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
      // The neighbour examined just before this one is background (or out of bounds), and consecutive
      // Moore neighbours are themselves adjacent, so it is also adjacent to the pixel we step onto —
      // which makes it the new backtrack cell.
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
    // The start pixel is already at index 0. Re-appending it on a legitimate revisit (the one-pixel
    // isthmus case) would put a duplicate vertex in the middle of the polygon.
    if (ny * w + nx !== startIdx) pts.push({ x: px, y: py });
  }
  return pts;
}

/** Shoelace area; positive means clockwise in y-down image coordinates. */
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
