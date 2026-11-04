export interface VecPoint {
  readonly x: number;
  readonly y: number;
}

export function vecPoint(x: number, y: number): VecPoint {
  return { x, y };
}

export interface LineSeg {
  readonly kind: 'line';
  readonly to: VecPoint;
}

export interface CubicSeg {
  readonly kind: 'cubic';
  readonly c1: VecPoint;
  readonly c2: VecPoint;
  readonly to: VecPoint;
}

export interface QuadSeg {
  readonly kind: 'quad';
  readonly c: VecPoint;
  readonly to: VecPoint;
}

export type VecSeg = LineSeg | CubicSeg | QuadSeg;

export const VecSeg = {
  line(to: VecPoint): LineSeg {
    return { kind: 'line', to };
  },
  cubic(c1: VecPoint, c2: VecPoint, to: VecPoint): CubicSeg {
    return { kind: 'cubic', c1, c2, to };
  },
  quad(c: VecPoint, to: VecPoint): QuadSeg {
    return { kind: 'quad', c, to };
  },
} as const;

export class Mat2D {
  constructor(
    readonly a: number,
    readonly b: number,
    readonly c: number,
    readonly d: number,
    readonly e: number,
    readonly f: number,
  ) {}

  apply(p: VecPoint): VecPoint {
    return { x: this.a * p.x + this.c * p.y + this.e, y: this.b * p.x + this.d * p.y + this.f };
  }

  meanScale(): number {
    return Math.sqrt(Math.abs(this.a * this.d - this.b * this.c));
  }

  times(o: Mat2D): Mat2D {
    return new Mat2D(
      this.a * o.a + this.c * o.b,
      this.b * o.a + this.d * o.b,
      this.a * o.c + this.c * o.d,
      this.b * o.c + this.d * o.d,
      this.a * o.e + this.c * o.f + this.e,
      this.b * o.e + this.d * o.f + this.f,
    );
  }

  static readonly IDENTITY = new Mat2D(1, 0, 0, 1, 0, 0);

  static translate(tx: number, ty: number): Mat2D {
    return new Mat2D(1, 0, 0, 1, tx, ty);
  }

  static scale(sx: number, sy: number): Mat2D {
    return new Mat2D(sx, 0, 0, sy, 0, 0);
  }

  static rotate(radians: number): Mat2D {
    const c = Math.cos(radians);
    const s = Math.sin(radians);
    return new Mat2D(c, s, -s, c, 0, 0);
  }

  static rotateAbout(radians: number, cx: number, cy: number): Mat2D {
    return Mat2D.translate(cx, cy).times(Mat2D.rotate(radians)).times(Mat2D.translate(-cx, -cy));
  }
}

export enum FillRule {
  NONZERO = 'NONZERO',
  EVENODD = 'EVENODD',
}

export enum LineCap {
  BUTT = 'BUTT',
  ROUND = 'ROUND',
  SQUARE = 'SQUARE',
}

export enum LineJoin {
  MITER = 'MITER',
  ROUND = 'ROUND',
  BEVEL = 'BEVEL',
}

export const DEFAULT_FLATTEN_TOLERANCE = 0.25;

const FLATTEN_MAX_DEPTH = 18;

const MIN_FLATTEN_TOLERANCE = 1e-3;

export class VecPath {
  constructor(
    readonly start: VecPoint,
    readonly segments: readonly VecSeg[],
    readonly closed = false,
    readonly id = '',
    readonly strokeWidths: Float32Array | null = null,
  ) {}

  points(): VecPoint[] {
    const out: VecPoint[] = [this.start];
    for (let i = 0; i < this.segments.length; i++) out.push(this.segments[i].to);
    return out;
  }

  flatten(tolerance = DEFAULT_FLATTEN_TOLERANCE): VecPoint[] {
    const out: VecPoint[] = [this.start];
    if (this.segments.length === 0) return out;

    const tol = tolerance < MIN_FLATTEN_TOLERANCE ? MIN_FLATTEN_TOLERANCE : tolerance;
    const tolSq = tol * tol;
    const stack = new Float64Array(8 * (FLATTEN_MAX_DEPTH + 2));
    const depths = new Int32Array(FLATTEN_MAX_DEPTH + 2);

    let cx = this.start.x;
    let cy = this.start.y;
    for (let i = 0; i < this.segments.length; i++) {
      const seg = this.segments[i];
      if (seg.kind === 'line') {
        out.push(seg.to);
      } else if (seg.kind === 'cubic') {
        flattenCubic(
          out, stack, depths,
          cx, cy, seg.c1.x, seg.c1.y, seg.c2.x, seg.c2.y, seg.to.x, seg.to.y, tolSq,
        );
      } else {
        const c1x = cx + (2 / 3) * (seg.c.x - cx);
        const c1y = cy + (2 / 3) * (seg.c.y - cy);
        const c2x = seg.to.x + (2 / 3) * (seg.c.x - seg.to.x);
        const c2y = seg.to.y + (2 / 3) * (seg.c.y - seg.to.y);
        flattenCubic(out, stack, depths, cx, cy, c1x, c1y, c2x, c2y, seg.to.x, seg.to.y, tolSq);
      }
      cx = seg.to.x;
      cy = seg.to.y;
    }

    if (this.closed && out.length > 1) {
      const last = out[out.length - 1];
      if (last.x === this.start.x && last.y === this.start.y) out.pop();
    }
    return out;
  }

  bounds(): Float32Array {
    let minX = this.start.x;
    let minY = this.start.y;
    let maxX = this.start.x;
    let maxY = this.start.y;
    const acc = new Float64Array(2);

    let cx = this.start.x;
    let cy = this.start.y;
    for (let i = 0; i < this.segments.length; i++) {
      const s = this.segments[i];
      const e = s.to;
      if (s.kind === 'line') {
        if (e.x < minX) minX = e.x;
        if (e.x > maxX) maxX = e.x;
        if (e.y < minY) minY = e.y;
        if (e.y > maxY) maxY = e.y;
      } else if (s.kind === 'cubic') {
        cubicExtent(cx, s.c1.x, s.c2.x, e.x, acc);
        if (acc[0] < minX) minX = acc[0];
        if (acc[1] > maxX) maxX = acc[1];
        cubicExtent(cy, s.c1.y, s.c2.y, e.y, acc);
        if (acc[0] < minY) minY = acc[0];
        if (acc[1] > maxY) maxY = acc[1];
      } else {
        quadExtent(cx, s.c.x, e.x, acc);
        if (acc[0] < minX) minX = acc[0];
        if (acc[1] > maxX) maxX = acc[1];
        quadExtent(cy, s.c.y, e.y, acc);
        if (acc[0] < minY) minY = acc[0];
        if (acc[1] > maxY) maxY = acc[1];
      }
      cx = e.x;
      cy = e.y;
    }
    return Float32Array.from([minX, minY, maxX, maxY]);
  }

  transform(m: Mat2D): VecPath {
    const segs: VecSeg[] = new Array<VecSeg>(this.segments.length);
    for (let i = 0; i < this.segments.length; i++) {
      const s = this.segments[i];
      if (s.kind === 'line') segs[i] = VecSeg.line(m.apply(s.to));
      else if (s.kind === 'cubic') {
        segs[i] = VecSeg.cubic(m.apply(s.c1), m.apply(s.c2), m.apply(s.to));
      } else segs[i] = VecSeg.quad(m.apply(s.c), m.apply(s.to));
    }
    let widths: Float32Array | null = null;
    if (this.strokeWidths !== null) {
      const k = m.meanScale();
      widths = new Float32Array(this.strokeWidths.length);
      for (let i = 0; i < widths.length; i++) widths[i] = this.strokeWidths[i] * k;
    }
    return new VecPath(m.apply(this.start), segs, this.closed, this.id, widths);
  }

  length(): number {
    const pts = this.flatten();
    let total = 0;
    for (let i = 1; i < pts.length; i++) {
      total += Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    }
    if (this.closed && pts.length > 1) {
      const a = pts[pts.length - 1];
      const b = pts[0];
      total += Math.hypot(b.x - a.x, b.y - a.y);
    }
    return total;
  }

  reversed(): VecPath {
    const anchors = this.points();
    const n = anchors.length;
    if (n < 2) return new VecPath(this.start, [], this.closed, this.id, this.strokeWidths);
    const segs: VecSeg[] = [];
    for (let i = this.segments.length - 1; i >= 0; i--) {
      const s = this.segments[i];
      const target = anchors[i];
      if (s.kind === 'line') segs.push(VecSeg.line(target));
      else if (s.kind === 'cubic') segs.push(VecSeg.cubic(s.c2, s.c1, target));
      else segs.push(VecSeg.quad(s.c, target));
    }
    let widths: Float32Array | null = null;
    if (this.strokeWidths !== null) {
      widths = new Float32Array(this.strokeWidths.length);
      for (let i = 0; i < widths.length; i++) {
        widths[i] = this.strokeWidths[this.strokeWidths.length - 1 - i];
      }
    }
    return new VecPath(anchors[n - 1], segs, this.closed, this.id, widths);
  }

  isEmpty(): boolean {
    return this.segments.length === 0;
  }

  equals(other: VecPath): boolean {
    if (this === other) return true;
    if (
      this.closed !== other.closed ||
      this.id !== other.id ||
      this.segments.length !== other.segments.length ||
      this.start.x !== other.start.x ||
      this.start.y !== other.start.y
    ) {
      return false;
    }
    for (let i = 0; i < this.segments.length; i++) {
      if (!segEquals(this.segments[i], other.segments[i])) return false;
    }
    const a = this.strokeWidths;
    const b = other.strokeWidths;
    if (a === null || b === null) return a === b;
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }
}

function pointEquals(a: VecPoint, b: VecPoint): boolean {
  return a.x === b.x && a.y === b.y;
}

function segEquals(a: VecSeg, b: VecSeg): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === 'line') return pointEquals(a.to, b.to);
  if (a.kind === 'cubic') {
    const c = b as CubicSeg;
    return pointEquals(a.c1, c.c1) && pointEquals(a.c2, c.c2) && pointEquals(a.to, c.to);
  }
  const q = b as QuadSeg;
  return pointEquals(a.c, q.c) && pointEquals(a.to, q.to);
}

export function cubicAt(
  p0: VecPoint,
  p1: VecPoint,
  p2: VecPoint,
  p3: VecPoint,
  t: number,
): VecPoint {
  const mt = 1 - t;
  const a = mt * mt * mt;
  const b = 3 * mt * mt * t;
  const c = 3 * mt * t * t;
  const d = t * t * t;
  return {
    x: a * p0.x + b * p1.x + c * p2.x + d * p3.x,
    y: a * p0.y + b * p1.y + c * p2.y + d * p3.y,
  };
}

export function quadAt(p0: VecPoint, p1: VecPoint, p2: VecPoint, t: number): VecPoint {
  const mt = 1 - t;
  const a = mt * mt;
  const b = 2 * mt * t;
  const c = t * t;
  return { x: a * p0.x + b * p1.x + c * p2.x, y: a * p0.y + b * p1.y + c * p2.y };
}

function flattenCubic(
  out: VecPoint[],
  stack: Float64Array,
  depths: Int32Array,
  px0: number, py0: number,
  px1: number, py1: number,
  px2: number, py2: number,
  px3: number, py3: number,
  toleranceSq: number,
): void {
  stack[0] = px0; stack[1] = py0;
  stack[2] = px1; stack[3] = py1;
  stack[4] = px2; stack[5] = py2;
  stack[6] = px3; stack[7] = py3;
  depths[0] = 0;
  let sp = 1;

  while (sp > 0) {
    sp--;
    const b = sp * 8;
    const x0 = stack[b];
    const y0 = stack[b + 1];
    const x1 = stack[b + 2];
    const y1 = stack[b + 3];
    const x2 = stack[b + 4];
    const y2 = stack[b + 5];
    const x3 = stack[b + 6];
    const y3 = stack[b + 7];
    const depth = depths[sp];

    if (depth >= FLATTEN_MAX_DEPTH || isFlatCubic(x0, y0, x1, y1, x2, y2, x3, y3, toleranceSq)) {
      out.push({ x: x3, y: y3 });
      continue;
    }

    const x01 = (x0 + x1) * 0.5;
    const y01 = (y0 + y1) * 0.5;
    const x12 = (x1 + x2) * 0.5;
    const y12 = (y1 + y2) * 0.5;
    const x23 = (x2 + x3) * 0.5;
    const y23 = (y2 + y3) * 0.5;
    const x012 = (x01 + x12) * 0.5;
    const y012 = (y01 + y12) * 0.5;
    const x123 = (x12 + x23) * 0.5;
    const y123 = (y12 + y23) * 0.5;
    const xm = (x012 + x123) * 0.5;
    const ym = (y012 + y123) * 0.5;

    let t = sp * 8;
    stack[t] = xm; stack[t + 1] = ym;
    stack[t + 2] = x123; stack[t + 3] = y123;
    stack[t + 4] = x23; stack[t + 5] = y23;
    stack[t + 6] = x3; stack[t + 7] = y3;
    depths[sp] = depth + 1;
    sp++;

    t = sp * 8;
    stack[t] = x0; stack[t + 1] = y0;
    stack[t + 2] = x01; stack[t + 3] = y01;
    stack[t + 4] = x012; stack[t + 5] = y012;
    stack[t + 6] = xm; stack[t + 7] = ym;
    depths[sp] = depth + 1;
    sp++;
  }
}

function isFlatCubic(
  x0: number, y0: number, x1: number, y1: number,
  x2: number, y2: number, x3: number, y3: number,
  toleranceSq: number,
): boolean {
  let ux = 3 * x1 - 2 * x0 - x3;
  let uy = 3 * y1 - 2 * y0 - y3;
  let vx = 3 * x2 - 2 * x3 - x0;
  let vy = 3 * y2 - 2 * y3 - y0;
  ux *= ux;
  uy *= uy;
  vx *= vx;
  vy *= vy;
  const mx = ux > vx ? ux : vx;
  const my = uy > vy ? uy : vy;
  return mx + my <= 16 * toleranceSq;
}

function cubicExtent(p0: number, p1: number, p2: number, p3: number, out: Float64Array): void {
  let lo = p0 < p3 ? p0 : p3;
  let hi = p0 > p3 ? p0 : p3;

  const a = -p0 + 3 * p1 - 3 * p2 + p3;
  const b = 2 * (p0 - 2 * p1 + p2);
  const c = p1 - p0;

  const consider = (t: number): void => {
    if (t <= 0 || t >= 1) return;
    const mt = 1 - t;
    const v = mt * mt * mt * p0 + 3 * mt * mt * t * p1 + 3 * mt * t * t * p2 + t * t * t * p3;
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  };

  if (Math.abs(a) < 1e-9) {
    if (Math.abs(b) > 1e-9) consider(-c / b);
  } else {
    const disc = b * b - 4 * a * c;
    if (disc >= 0) {
      const sq = Math.sqrt(disc);
      consider((-b + sq) / (2 * a));
      consider((-b - sq) / (2 * a));
    }
  }
  out[0] = lo;
  out[1] = hi;
}

function quadExtent(p0: number, p1: number, p2: number, out: Float64Array): void {
  let lo = p0 < p2 ? p0 : p2;
  let hi = p0 > p2 ? p0 : p2;
  const den = p0 - 2 * p1 + p2;
  if (Math.abs(den) > 1e-9) {
    const t = (p0 - p1) / den;
    if (t > 0 && t < 1) {
      const mt = 1 - t;
      const v = mt * mt * p0 + 2 * mt * t * p1 + t * t * p2;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
  }
  out[0] = lo;
  out[1] = hi;
}

export interface VecStyle {
  readonly stroke: number | null;
  readonly strokeWidth: number;
  readonly fill: number | null;
  readonly fillRule: FillRule;
  readonly cap: LineCap;
  readonly join: LineJoin;
  readonly miterLimit: number;
  readonly opacity: number;
}

export const DEFAULT_STYLE: VecStyle = {
  stroke: 0xff000000,
  strokeWidth: 1.5,
  fill: null,
  fillRule: FillRule.EVENODD,
  cap: LineCap.ROUND,
  join: LineJoin.ROUND,
  miterLimit: 4,
  opacity: 1,
};

export function vecStyle(over: Partial<VecStyle> = {}): VecStyle {
  return { ...DEFAULT_STYLE, ...over };
}

export interface VecShape {
  readonly path: VecPath;
  readonly style: VecStyle;
}

export function vecShape(path: VecPath, style: VecStyle = DEFAULT_STYLE): VecShape {
  return { path, style };
}

export interface VecLayer {
  readonly id: string;
  readonly name: string;
  readonly shapes: readonly VecShape[];
  readonly visible: boolean;
  readonly locked: boolean;
  readonly opacity: number;
}

export function vecLayer(
  id: string,
  name: string,
  shapes: readonly VecShape[],
  over: Partial<Omit<VecLayer, 'id' | 'name' | 'shapes'>> = {},
): VecLayer {
  return { id, name, shapes, visible: true, locked: false, opacity: 1, ...over };
}

export class VecDocument {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly layers: readonly VecLayer[],
    readonly background: number | null = null,
  ) {}

  bounds(): Float32Array {
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    let any = false;
    for (const layer of this.layers) {
      for (const shape of layer.shapes) {
        const b = shape.path.bounds();
        if (!Number.isFinite(b[0])) continue;
        any = true;
        if (b[0] < minX) minX = b[0];
        if (b[1] < minY) minY = b[1];
        if (b[2] > maxX) maxX = b[2];
        if (b[3] > maxY) maxY = b[3];
      }
    }
    const out = new Float32Array(4);
    if (!any) {
      out[2] = this.width;
      out[3] = this.height;
      return out;
    }
    out[0] = minX;
    out[1] = minY;
    out[2] = maxX;
    out[3] = maxY;
    return out;
  }

  shapeCount(): number {
    let n = 0;
    for (const layer of this.layers) n += layer.shapes.length;
    return n;
  }

  nodeCount(): number {
    let n = 0;
    for (const layer of this.layers) {
      for (const shape of layer.shapes) n += shape.path.segments.length + 1;
    }
    return n;
  }

  transform(m: Mat2D): VecDocument {
    const sx = Math.hypot(m.a, m.b);
    const sy = Math.hypot(m.c, m.d);
    const k = m.meanScale();
    const layers = this.layers.map((layer) => ({
      ...layer,
      shapes: layer.shapes.map((s) => ({
        path: s.path.transform(m),
        style: { ...s.style, strokeWidth: s.style.strokeWidth * k },
      })),
    }));
    return new VecDocument(this.width * sx, this.height * sy, layers, this.background);
  }
}
