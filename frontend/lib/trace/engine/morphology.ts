import { GrayF, Mask } from './buffers';

export enum SeShape {
  RECT = 'RECT',
  CROSS = 'CROSS',
  ELLIPSE = 'ELLIPSE',
}

function seOffsets(radius: number, shape: SeShape): Int32Array {
  const r = radius;
  const list: number[] = [];
  for (let dy = -r; dy <= r; dy++) {
    for (let dx = -r; dx <= r; dx++) {
      let inside: boolean;
      switch (shape) {
        case SeShape.CROSS:
          inside = dx === 0 || dy === 0;
          break;
        case SeShape.ELLIPSE:
          inside = dx * dx + dy * dy <= r * r;
          break;
        default:
          inside = true;
      }
      if (inside) {
        list.push(dx, dy);
      }
    }
  }
  return Int32Array.from(list);
}

function rectDilate(src: Mask, r: number): Mask {
  const w = src.width;
  const h = src.height;
  const tmp = new Uint8Array(w * h);
  const out = new Uint8Array(w * h);
  const d = src.data;

  for (let y = 0; y < h; y++) {
    const row = y * w;
    let count = 0;
    for (let i = 0; i <= r && i < w; i++) count += d[row + i];
    tmp[row] = count > 0 ? 1 : 0;
    for (let x = 1; x < w; x++) {
      const add = x + r;
      const sub = x - r - 1;
      if (add < w) count += d[row + add];
      if (sub >= 0) count -= d[row + sub];
      tmp[row + x] = count > 0 ? 1 : 0;
    }
  }
  for (let x = 0; x < w; x++) {
    let count = 0;
    for (let i = 0; i <= r && i < h; i++) count += tmp[i * w + x];
    out[x] = count > 0 ? 1 : 0;
    for (let y = 1; y < h; y++) {
      const add = y + r;
      const sub = y - r - 1;
      if (add < h) count += tmp[add * w + x];
      if (sub >= 0) count -= tmp[sub * w + x];
      out[y * w + x] = count > 0 ? 1 : 0;
    }
  }
  return new Mask(w, h, out);
}

function rectErode(src: Mask, r: number): Mask {
  const w = src.width;
  const h = src.height;
  const tmp = new Uint8Array(w * h);
  const out = new Uint8Array(w * h);
  const d = src.data;

  for (let y = 0; y < h; y++) {
    const row = y * w;
    let count = 0;
    for (let i = 0; i <= r && i < w; i++) count += d[row + i];
    for (let x = 0; x < w; x++) {
      if (x > 0) {
        const add = x + r;
        const sub = x - r - 1;
        if (add < w) count += d[row + add];
        if (sub >= 0) count -= d[row + sub];
      }
      const lo = x - r;
      const hi = x + r;
      const full = hi - lo + 1;
      const present = Math.min(w - 1, hi) - Math.max(0, lo) + 1;
      tmp[row + x] = present === full && count === full ? 1 : 0;
    }
  }
  for (let x = 0; x < w; x++) {
    let count = 0;
    for (let i = 0; i <= r && i < h; i++) count += tmp[i * w + x];
    for (let y = 0; y < h; y++) {
      if (y > 0) {
        const add = y + r;
        const sub = y - r - 1;
        if (add < h) count += tmp[add * w + x];
        if (sub >= 0) count -= tmp[sub * w + x];
      }
      const lo = y - r;
      const hi = y + r;
      const full = hi - lo + 1;
      const present = Math.min(h - 1, hi) - Math.max(0, lo) + 1;
      out[y * w + x] = present === full && count === full ? 1 : 0;
    }
  }
  return new Mask(w, h, out);
}

function offsetDilate(src: Mask, offsets: Int32Array): Mask {
  const w = src.width;
  const h = src.height;
  const out = new Uint8Array(w * h);
  const d = src.data;
  const m = offsets.length;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let hit = 0;
      for (let k = 0; k < m; k += 2) {
        const sx = x + offsets[k];
        const sy = y + offsets[k + 1];
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
        if (d[sy * w + sx] !== 0) {
          hit = 1;
          break;
        }
      }
      out[row + x] = hit;
    }
  }
  return new Mask(w, h, out);
}

function offsetErode(src: Mask, offsets: Int32Array): Mask {
  const w = src.width;
  const h = src.height;
  const out = new Uint8Array(w * h);
  const d = src.data;
  const m = offsets.length;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let all = 1;
      for (let k = 0; k < m; k += 2) {
        const sx = x + offsets[k];
        const sy = y + offsets[k + 1];
        if (sx < 0 || sy < 0 || sx >= w || sy >= h || d[sy * w + sx] === 0) {
          all = 0;
          break;
        }
      }
      out[row + x] = all;
    }
  }
  return new Mask(w, h, out);
}

export function dilate(src: Mask, radius: number, shape: SeShape = SeShape.ELLIPSE): Mask {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  if (shape === SeShape.RECT) return rectDilate(src, r);
  return offsetDilate(src, seOffsets(r, shape));
}

export function erode(src: Mask, radius: number, shape: SeShape = SeShape.ELLIPSE): Mask {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  if (shape === SeShape.RECT) return rectErode(src, r);
  return offsetErode(src, seOffsets(r, shape));
}

export function open(src: Mask, radius: number, shape: SeShape = SeShape.ELLIPSE): Mask {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  return dilate(erode(src, r, shape), r, shape);
}

export function close(src: Mask, radius: number, shape: SeShape = SeShape.ELLIPSE): Mask {
  const r = radius | 0;
  if (r <= 0) return src.copy();
  return erode(dilate(src, r, shape), r, shape);
}

export function gradient(src: Mask, radius: number, shape: SeShape = SeShape.ELLIPSE): Mask {
  const r = radius | 0;
  if (r <= 0) return src.blank();
  return dilate(src, r, shape).subtract(erode(src, r, shape));
}

export function dilateGray(src: GrayF, radius: number): GrayF {
  return extremumGray(src, radius | 0, true);
}

export function erodeGray(src: GrayF, radius: number): GrayF {
  return extremumGray(src, radius | 0, false);
}

function extremumGray(src: GrayF, r: number, wantMax: boolean): GrayF {
  if (r <= 0) return src.copy();
  const w = src.width;
  const h = src.height;
  const d = src.data;
  const tmp = new Float32Array(w * h);
  const out = new Float32Array(w * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let best = d[row + x];
      for (let i = -r; i <= r; i++) {
        const sx = x + i;
        const v = d[row + (sx < 0 ? 0 : sx >= w ? w - 1 : sx)];
        if (wantMax ? v > best : v < best) best = v;
      }
      tmp[row + x] = best;
    }
  }
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      let best = tmp[row + x];
      for (let i = -r; i <= r; i++) {
        const sy = y + i;
        const v = tmp[(sy < 0 ? 0 : sy >= h ? h - 1 : sy) * w + x];
        if (wantMax ? v > best : v < best) best = v;
      }
      out[row + x] = best;
    }
  }
  return new GrayF(w, h, out);
}
