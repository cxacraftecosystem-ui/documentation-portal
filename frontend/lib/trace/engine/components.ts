import { Mask } from './buffers';

export class Labels {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly labels: Int32Array,
    readonly count: number,
    readonly area: Int32Array,
    readonly bounds: Int32Array,
  ) {}

  maskOf(label: number): Mask {
    const out = new Uint8Array(this.labels.length);
    if (label >= 1 && label <= this.count) {
      for (let i = 0; i < out.length; i++) out[i] = this.labels[i] === label ? 1 : 0;
    }
    return new Mask(this.width, this.height, out);
  }

  areaOf(label: number): number {
    if (label < 0 || label > this.count) return 0;
    return this.area[label];
  }
}

function findRoot(parent: Int32Array, x: number): number {
  let root = x;
  while (parent[root] !== root) root = parent[root];
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

export function label(src: Mask, connectivity = 8): Labels {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const d = src.data;
  const eight = connectivity !== 4;

  const prov = new Int32Array(n);
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
      if (x > 0 && prov[i - 1] !== 0) best = prov[i - 1];
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
  bounds[0] = 0;
  bounds[1] = 0;
  bounds[2] = -1;
  bounds[3] = -1;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const l = labels[row + x];
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

export function removeSmallBlobs(src: Mask, minArea: number): Mask {
  if (minArea <= 1) return src.copy();
  const lab = label(src, 8);
  const keep = new Uint8Array(lab.count + 1);
  for (let l = 1; l <= lab.count; l++) keep[l] = lab.area[l] >= minArea ? 1 : 0;
  return applyKeep(lab, keep);
}

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
    for (let k = 0; k < count; k++) cur[doomed[k]] = 0;
  }
  return new Mask(w, h, cur);
}

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
