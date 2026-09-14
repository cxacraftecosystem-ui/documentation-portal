import { Mask } from './buffers';

/**
 * Connected components. See ALGORITHMS.md §9.
 *
 * Two-pass union-find with path compression and union by rank. The alternative — flood filling from
 * every unlabelled pixel — needs a frontier the size of the image and is measurably slower for the
 * many-small-blobs case that dominates here (dust on a scan is thousands of 2 px components).
 */

/**
 * Labelling result. Labels run `1..count`; 0 is background.
 *
 * `area[label]` and `bounds[4 * label .. 4 * label + 3]` are indexed **by label**, so both arrays have
 * `count + 1` entries' worth of room and slot 0 describes the background. Indexing by label rather
 * than by a dense 0-based index is what lets callers keep a label from `labels` and look it up without
 * an offset, and an off-by-one there silently attributes one blob's area to its neighbour.
 */
export class Labels {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly labels: Int32Array,
    readonly count: number,
    readonly area: Int32Array,
    readonly bounds: Int32Array,
  ) {}

  /** @returns a Mask that is `true` exactly where `labels` equals `label`; blank for an unknown label. */
  maskOf(label: number): Mask {
    const out = new Uint8Array(this.labels.length);
    if (label >= 1 && label <= this.count) {
      for (let i = 0; i < out.length; i++) out[i] = this.labels[i] === label ? 1 : 0;
    }
    return new Mask(this.width, this.height, out);
  }

  /** @returns the pixel count of `label`, or 0 for an unknown label. */
  areaOf(label: number): number {
    if (label < 0 || label > this.count) return 0;
    return this.area[label];
  }
}

function findRoot(parent: Int32Array, x: number): number {
  let root = x;
  while (parent[root] !== root) root = parent[root];
  // Path compression on the way back, which is what keeps the second pass linear on a spiral shape.
  let cur = x;
  while (parent[cur] !== root) {
    const next = parent[cur];
    parent[cur] = root;
    cur = next;
  }
  return root;
}

function union(parent: Int32Array, rank: Int32Array, a: number, b: number): void {
  const ra = findRoot(parent, a);
  const rb = findRoot(parent, b);
  if (ra === rb) return;
  if (rank[ra] < rank[rb]) {
    parent[ra] = rb;
  } else if (rank[ra] > rank[rb]) {
    parent[rb] = ra;
  } else {
    parent[rb] = ra;
    rank[ra]++;
  }
}

/**
 * Label the foreground.
 *
 * @param connectivity 8 (default) or 4; anything else is treated as 8
 * @returns labels numbered in raster order of their first pixel, so the result is reproducible and
 *          independent of the union-find's internal root choices.
 */
export function label(src: Mask, connectivity = 8): Labels {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const d = src.data;
  const eight = connectivity !== 4;

  const prov = new Int32Array(n);
  // Provisional labels start at 1; capacity is bounded by the checkerboard worst case.
  const cap = (n >> 1) + 2;
  const parent = new Int32Array(cap);
  const rank = new Int32Array(cap);
  let next = 1;

  for (let y = 0; y < h; y++) {
    const row = y * w;
    const rowUp = row - w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      if (d[i] === 0) continue;
      let best = 0;
      // West
      if (x > 0 && prov[i - 1] !== 0) best = prov[i - 1];
      // North
      if (y > 0 && prov[rowUp + x] !== 0) {
        const l = prov[rowUp + x];
        if (best === 0) best = l;
        else union(parent, rank, best, l);
      }
      if (eight && y > 0) {
        if (x > 0 && prov[rowUp + x - 1] !== 0) {
          const l = prov[rowUp + x - 1];
          if (best === 0) best = l;
          else union(parent, rank, best, l);
        }
        if (x < w - 1 && prov[rowUp + x + 1] !== 0) {
          const l = prov[rowUp + x + 1];
          if (best === 0) best = l;
          else union(parent, rank, best, l);
        }
      }
      if (best === 0) {
        best = next++;
        parent[best] = best;
        rank[best] = 0;
      }
      prov[i] = best;
    }
  }

  // Second pass: resolve to roots, then renumber roots by first appearance in raster order.
  const remap = new Int32Array(next);
  const labels = new Int32Array(n);
  let count = 0;
  for (let i = 0; i < n; i++) {
    const p = prov[i];
    if (p === 0) continue;
    const root = findRoot(parent, p);
    let final = remap[root];
    if (final === 0) {
      final = ++count;
      remap[root] = final;
    }
    labels[i] = final;
  }

  const area = new Int32Array(count + 1);
  const bounds = new Int32Array(4 * (count + 1));
  for (let l = 1; l <= count; l++) {
    bounds[4 * l] = w;
    bounds[4 * l + 1] = h;
    bounds[4 * l + 2] = -1;
    bounds[4 * l + 3] = -1;
  }
  // The background slot is a normalised empty box rather than an inverted one, which reads as a
  // nonsense rectangle in a debugger and in any UI overlay that draws it.
  bounds[0] = 0;
  bounds[1] = 0;
  bounds[2] = -1;
  bounds[3] = -1;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const l = labels[row + x];
      // Slot 0 stays at zero. It is the background's slot, and callers sum `area` to get the total ink
      // count — counting the background there silently doubles that answer.
      if (l === 0) continue;
      area[l]++;
      const b = 4 * l;
      if (x < bounds[b]) bounds[b] = x;
      if (y < bounds[b + 1]) bounds[b + 1] = y;
      if (x > bounds[b + 2]) bounds[b + 2] = x;
      if (y > bounds[b + 3]) bounds[b + 3] = y;
    }
  }
  return new Labels(w, h, labels, count, area, bounds);
}

/**
 * Drop 8-connected components smaller than `minArea`.
 * @param minArea <= 1 returns `src.copy()` (every component has at least one pixel)
 */
export function removeSmallBlobs(src: Mask, minArea: number): Mask {
  if (minArea <= 1) return src.copy();
  const lab = label(src, 8);
  const keep = new Uint8Array(lab.count + 1);
  for (let l = 1; l <= lab.count; l++) keep[l] = lab.area[l] >= minArea ? 1 : 0;
  return applyKeep(lab, keep);
}

/**
 * Keep only the `n` largest components — the "only the subject" control.
 *
 * Ties are broken by the lower label, i.e. by raster order of the first pixel, so the choice is
 * reproducible rather than dependent on a sort's stability.
 *
 * @param n <= 0 returns a blank mask of the same size
 */
export function keepLargest(src: Mask, n: number): Mask {
  if (n <= 0) return src.blank();
  const lab = label(src, 8);
  if (lab.count <= n) return src.copy();
  const order = new Int32Array(lab.count);
  for (let i = 0; i < lab.count; i++) order[i] = i + 1;
  const areas = lab.area;
  const sorted = Array.from(order).sort((a, b) => (areas[b] - areas[a]) || a - b);
  const keep = new Uint8Array(lab.count + 1);
  for (let i = 0; i < n && i < sorted.length; i++) keep[sorted[i]] = 1;
  return applyKeep(lab, keep);
}

/** Drop every component with a pixel on the image border — for scans that include a frame. */
export function removeBorderTouching(src: Mask): Mask {
  const lab = label(src, 8);
  const keep = new Uint8Array(lab.count + 1);
  keep.fill(1);
  keep[0] = 0;
  const w = lab.width;
  const h = lab.height;
  for (let x = 0; x < w; x++) {
    keep[lab.labels[x]] = 0;
    keep[lab.labels[(h - 1) * w + x]] = 0;
  }
  for (let y = 0; y < h; y++) {
    keep[lab.labels[y * w]] = 0;
    keep[lab.labels[y * w + w - 1]] = 0;
  }
  keep[0] = 0;
  return applyKeep(lab, keep);
}

function applyKeep(lab: Labels, keep: Uint8Array): Mask {
  const out = new Uint8Array(lab.labels.length);
  for (let i = 0; i < out.length; i++) {
    const l = lab.labels[i];
    out[i] = l !== 0 && keep[l] !== 0 ? 1 : 0;
  }
  return new Mask(lab.width, lab.height, out);
}

/**
 * Clear any foreground pixel with fewer than `minNeighbours` foreground 8-neighbours, iterated to a
 * fixed point.
 *
 * @param maxPasses hard bound so a pathological input cannot loop; 8 is enough for every real case
 *                  because each pass strictly shrinks the mask
 * @returns a new Mask; `src.copy()` when `minNeighbours <= 0`
 */
export function removeIsolated(src: Mask, minNeighbours = 1, maxPasses = 8): Mask {
  if (minNeighbours <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const cur = src.data.slice();
  const passes = Math.max(1, maxPasses | 0);
  const doomed = new Int32Array(w * h);
  for (let pass = 0; pass < passes; pass++) {
    let count = 0;
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        const i = row + x;
        if (cur[i] === 0) continue;
        let nb = 0;
        const y0 = y > 0 ? y - 1 : 0;
        const y1 = y < h - 1 ? y + 1 : h - 1;
        const x0 = x > 0 ? x - 1 : 0;
        const x1 = x < w - 1 ? x + 1 : w - 1;
        for (let ny = y0; ny <= y1; ny++) {
          const nrow = ny * w;
          for (let nx = x0; nx <= x1; nx++) {
            if (nrow + nx !== i && cur[nrow + nx] !== 0) nb++;
          }
        }
        if (nb < minNeighbours) doomed[count++] = i;
      }
    }
    if (count === 0) break;
    // Deletions are applied after the whole pass. Deleting in place makes the result scan-order
    // dependent, and it would then differ between the Kotlin and TypeScript engines.
    for (let k = 0; k < count; k++) cur[doomed[k]] = 0;
  }
  return new Mask(w, h, cur);
}

/**
 * Fill enclosed background regions up to `maxHoleArea` pixels.
 *
 * The background is labelled **4-connected** while the foreground is 8-connected. That pairing is the
 * standard one and it is what makes a diagonal chain of ink count as a closed loop: with 8-connected
 * background, a hole leaks out through the diagonal and never gets filled.
 *
 * @param maxHoleArea <= 0 returns `src.copy()` (the feature is off)
 */
export function fillHoles(src: Mask, maxHoleArea: number): Mask {
  if (maxHoleArea <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const bg = src.invert();
  const lab = label(bg, 4);
  const touches = new Uint8Array(lab.count + 1);
  for (let x = 0; x < w; x++) {
    touches[lab.labels[x]] = 1;
    touches[lab.labels[(h - 1) * w + x]] = 1;
  }
  for (let y = 0; y < h; y++) {
    touches[lab.labels[y * w]] = 1;
    touches[lab.labels[y * w + w - 1]] = 1;
  }
  const out = src.data.slice();
  for (let i = 0; i < out.length; i++) {
    const l = lab.labels[i];
    if (l !== 0 && touches[l] === 0 && lab.area[l] <= maxHoleArea) out[i] = 1;
  }
  return new Mask(w, h, out);
}
