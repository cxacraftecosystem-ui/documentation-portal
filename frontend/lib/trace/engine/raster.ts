import { GrayF, Mask, Px, RgbaImage } from './buffers';
import {
  DEFAULT_FLATTEN_TOLERANCE,
  FillRule,
  Mat2D,
  VecDocument,
  VecPath,
  VecShape,
} from './path';
import { outlineStroke } from './strokeStyle';

/**
 * Scanline rasterisation. See ALGORITHMS.md §11.
 *
 * Coverage is sampled on an ordered `samples x samples` grid — 4x4 by default, so 16 samples per pixel.
 * Strokes are converted to outlines first and filled with the **non-zero** rule, which is what SVG
 * requires and what makes a self-overlapping stroke render solid instead of showing seams where it
 * crosses itself.
 */

/** Cap on supersampling; 8x8 is already past the point of visible improvement and costs 4x more. */
const MAX_SAMPLES = 8;

interface Edges {
  readonly ymin: Float64Array;
  readonly ymax: Float64Array;
  readonly x: Float64Array;
  readonly slope: Float64Array;
  readonly dir: Int8Array;
  readonly count: number;
}

function buildEdges(paths: readonly VecPath[], flatten: number): Edges {
  const axs: number[] = [];
  const ays: number[] = [];
  const bxs: number[] = [];
  const bys: number[] = [];
  for (const path of paths) {
    const pts = path.flatten(flatten);
    const n = pts.length;
    if (n < 2) continue;
    // Every path is filled as though closed: an unclosed fill is not a thing SVG has, and leaving the
    // final edge out puts a diagonal gash across the shape.
    for (let i = 0; i < n; i++) {
      const a = pts[i];
      const b = pts[(i + 1) % n];
      if (a.y === b.y) continue;
      axs.push(a.x);
      ays.push(a.y);
      bxs.push(b.x);
      bys.push(b.y);
    }
  }
  const count = axs.length;
  const ymin = new Float64Array(count);
  const ymax = new Float64Array(count);
  const x = new Float64Array(count);
  const slope = new Float64Array(count);
  const dir = new Int8Array(count);
  for (let i = 0; i < count; i++) {
    const ax = axs[i];
    const ay = ays[i];
    const bx = bxs[i];
    const by = bys[i];
    if (ay < by) {
      ymin[i] = ay;
      ymax[i] = by;
      x[i] = ax;
      slope[i] = (bx - ax) / (by - ay);
      dir[i] = 1;
    } else {
      ymin[i] = by;
      ymax[i] = ay;
      x[i] = bx;
      slope[i] = (ax - bx) / (ay - by);
      dir[i] = -1;
    }
  }
  return { ymin, ymax, x, slope, dir, count };
}

/** Accumulates one horizontal span's sub-sample coverage into a destination row. */
function addSpan(
  cov: Float32Array,
  rowBase: number,
  w: number,
  samples: number,
  inv: number,
  xa: number,
  xb: number,
): void {
  if (!(xb > xa)) return;
  // Sub-column j is covered when its centre (j + 0.5) / samples lies inside [xa, xb).
  let j0 = Math.ceil(xa * samples - 0.5);
  let j1 = Math.ceil(xb * samples - 0.5) - 1;
  if (j0 < 0) j0 = 0;
  const maxJ = w * samples - 1;
  if (j1 > maxJ) j1 = maxJ;
  if (j1 < j0) return;
  const px0 = (j0 / samples) | 0;
  const px1 = (j1 / samples) | 0;
  if (px0 === px1) {
    cov[rowBase + px0] += (j1 - j0 + 1) * inv;
    return;
  }
  cov[rowBase + px0] += ((px0 + 1) * samples - j0) * inv;
  const full = samples * inv;
  for (let p = px0 + 1; p < px1; p++) cov[rowBase + p] += full;
  cov[rowBase + px1] += (j1 - px1 * samples + 1) * inv;
}

/**
 * Rasterise a fill to a coverage plane.
 *
 * @param samples supersampling grid side, clamped to `1..8`
 * @returns a new GrayF of coverage in 0..1; all zeros when the paths contribute no non-horizontal edges.
 */
export function fill(
  paths: readonly VecPath[],
  w: number,
  h: number,
  rule: FillRule,
  samples = 4,
): GrayF {
  const dw = Math.max(1, w | 0);
  const dh = Math.max(1, h | 0);
  const ss = Px.clampInt(samples, 1, MAX_SAMPLES);
  const cov = new Float32Array(dw * dh);
  const e = buildEdges(paths, DEFAULT_FLATTEN_TOLERANCE);
  if (e.count === 0) return new GrayF(dw, dh, cov);

  const order: number[] = new Array<number>(e.count);
  for (let i = 0; i < e.count; i++) order[i] = i;
  order.sort((a, b) => e.ymin[a] - e.ymin[b]);

  let active: number[] = [];
  let nextEdge = 0;
  const inv = 1 / (ss * ss);
  const xs = new Float64Array(e.count);
  const ds = new Int8Array(e.count);
  const idx: number[] = new Array<number>(e.count);

  for (let y = 0; y < dh; y++) {
    const rowBase = y * dw;
    for (let sr = 0; sr < ss; sr++) {
      const sy = y + (sr + 0.5) / ss;
      while (nextEdge < e.count && e.ymin[order[nextEdge]] <= sy) {
        active.push(order[nextEdge]);
        nextEdge++;
      }
      if (active.length === 0) continue;
      let write = 0;
      for (let k = 0; k < active.length; k++) {
        const ei = active[k];
        if (e.ymax[ei] <= sy) continue;
        active[write++] = ei;
      }
      active.length = write;
      let m = 0;
      for (let k = 0; k < active.length; k++) {
        const ei = active[k];
        if (e.ymin[ei] > sy) continue;
        xs[m] = e.x[ei] + (sy - e.ymin[ei]) * e.slope[ei];
        ds[m] = e.dir[ei];
        m++;
      }
      if (m < 2) continue;
      for (let k = 0; k < m; k++) idx[k] = k;
      const view = idx.slice(0, m);
      view.sort((a, b) => xs[a] - xs[b]);
      if (rule === FillRule.EVENODD) {
        for (let k = 0; k + 1 < m; k += 2) {
          addSpan(cov, rowBase, dw, ss, inv, xs[view[k]], xs[view[k + 1]]);
        }
      } else {
        let winding = 0;
        let spanStart = 0;
        for (let k = 0; k < m; k++) {
          const prev = winding;
          winding += ds[view[k]];
          if (prev === 0 && winding !== 0) spanStart = xs[view[k]];
          else if (prev !== 0 && winding === 0) {
            addSpan(cov, rowBase, dw, ss, inv, spanStart, xs[view[k]]);
          }
        }
      }
    }
  }
  for (let i = 0; i < cov.length; i++) if (cov[i] > 1) cov[i] = 1;
  return new GrayF(dw, dh, cov);
}

/**
 * Rasterise a fill to a binary mask at the 50% coverage level.
 * @returns a new Mask; the midpoint threshold is what makes a 1 px stroke survive as 1 px rather than
 *          vanishing or doubling.
 */
export function toMask(
  paths: readonly VecPath[],
  w: number,
  h: number,
  rule: FillRule,
): Mask {
  const cov = fill(paths, w, h, rule, 4);
  const out = new Uint8Array(cov.data.length);
  for (let i = 0; i < out.length; i++) out[i] = cov.data[i] >= 0.5 ? 1 : 0;
  return new Mask(cov.width, cov.height, out);
}

/** Source-over composite of one flat colour through a coverage plane. */
function composite(target: RgbaImage, cov: Float32Array, argb: number, opacity: number): void {
  const px = target.pixels;
  const sa = ((argb >>> 24) & 0xff) / 255;
  const sr = (argb >>> 16) & 0xff;
  const sg = (argb >>> 8) & 0xff;
  const sb = argb & 0xff;
  const op = Px.clamp01(opacity);
  for (let i = 0; i < px.length; i++) {
    const a = cov[i] * sa * op;
    if (a <= 0) continue;
    const dst = px[i];
    const da = ((dst >>> 24) & 0xff) / 255;
    const dr = (dst >>> 16) & 0xff;
    const dg = (dst >>> 8) & 0xff;
    const db = dst & 0xff;
    const outA = a + da * (1 - a);
    if (outA <= 0) {
      px[i] = 0;
      continue;
    }
    // Straight-alpha source-over. Compositing the colours weighted by *coverage* alone would darken every
    // antialiased edge that lands on a transparent background.
    const inv = 1 / outA;
    px[i] = RgbaImage.argb(
      Math.round(outA * 255),
      Math.round((sr * a + dr * da * (1 - a)) * inv),
      Math.round((sg * a + dg * da * (1 - a)) * inv),
      Math.round((sb * a + db * da * (1 - a)) * inv),
    );
  }
}

/**
 * Draw one shape into an existing image: fill first, then stroke, which is SVG's paint order.
 * @param samples supersampling grid side, clamped to `1..8`
 */
export function renderShape(target: RgbaImage, shape: VecShape, samples = 4): void {
  const style = shape.style;
  if (style.fill !== null) {
    const cov = fill([shape.path], target.width, target.height, style.fillRule, samples);
    composite(target, cov.data, style.fill, style.opacity);
  }
  if (style.stroke !== null && style.strokeWidth > 0) {
    const outline = outlineStroke(
      shape.path,
      style.strokeWidth,
      style.cap,
      style.join,
      style.miterLimit,
    );
    if (!outline.isEmpty()) {
      const cov = fill([outline], target.width, target.height, FillRule.NONZERO, samples);
      composite(target, cov.data, style.stroke, style.opacity);
    }
  }
}

/**
 * Render a whole document.
 *
 * The document is scaled to fit `w` x `h` **non-uniformly** if asked, because a caller that requests an
 * explicit pixel size has already decided the aspect ratio; stroke widths scale by the isotropic mean so
 * they stay visually consistent under a slightly non-square scale.
 *
 * @param background packed ARGB to clear with; pass 0 for transparent
 * @returns a new RgbaImage of `w` x `h`. Hidden layers are skipped and layer opacity multiplies into
 *          each shape's own.
 */
export function render(
  doc: VecDocument,
  w: number,
  h: number,
  background: number,
  samples = 4,
): RgbaImage {
  const dw = Math.max(1, w | 0);
  const dh = Math.max(1, h | 0);
  const target = new RgbaImage(dw, dh);
  target.fill(background);
  const sx = doc.width > 0 ? dw / doc.width : 1;
  const sy = doc.height > 0 ? dh / doc.height : 1;
  const m = Mat2D.scale(sx, sy);
  const widthScale = m.meanScale();
  for (const layer of doc.layers) {
    if (!layer.visible) continue;
    for (const shape of layer.shapes) {
      const scaled: VecShape = {
        path: shape.path.transform(m),
        style: {
          ...shape.style,
          strokeWidth: shape.style.strokeWidth * widthScale,
          opacity: shape.style.opacity * layer.opacity,
        },
      };
      renderShape(target, scaled, samples);
    }
  }
  return target;
}
