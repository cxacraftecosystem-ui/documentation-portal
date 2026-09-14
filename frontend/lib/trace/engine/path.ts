/**
 * Vector primitives: points, segments, paths, transforms, styles and documents.
 *
 * Kotlin's `sealed interface VecSeg` becomes a discriminated union on `kind`, with a companion
 * `VecSeg` object carrying the three constructors so call sites read the same in both engines.
 */

/** A point in document space. Plain object, not a class: point lists are the hot data here. */
export interface VecPoint {
  readonly x: number;
  readonly y: number;
}

/** @returns a VecPoint; use this rather than an object literal so the shape stays monomorphic. */
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

/** Constructors for {@link VecSeg}, mirroring `VecSeg.Line` / `.Cubic` / `.Quad`. */
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

/** 2x3 affine transform laid out as `[a c e / b d f / 0 0 1]`, matching the SVG matrix order. */
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

  /**
   * The isotropic scale factor `sqrt(|det|)`: 1 for any rotation, translation or reflection, `s` for a
   * uniform scale `s`, and the geometric mean for an anisotropic one.
   *
   * This is the factor stroke widths and flatness tolerances scale by, since neither has a direction.
   * It is not cosmetic: the pipeline traces at working resolution and multiplies by `original/working`
   * on export, so a width that did not scale with the geometry comes out several times too thin on a
   * downscaled 12 MP source.
   */
  meanScale(): number {
    return Math.sqrt(Math.abs(this.a * this.d - this.b * this.c));
  }

  /** @returns `this * o` — `o` is applied first, then `this`, which is the SVG composition order. */
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

/** Default flattening tolerance in document units, shared by every consumer that needs a polyline. */
export const DEFAULT_FLATTEN_TOLERANCE = 0.25;

/**
 * 2^18 pieces is far past anything a sane tolerance asks for; the cap exists only so a cusped or
 * degenerate curve (coincident control points, infinities from a bad parse) emits a coarse polyline
 * instead of subdividing until the heap gives out.
 */
const FLATTEN_MAX_DEPTH = 18;

/** Below this the flatness test is float noise and subdivision never terminates early. */
const MIN_FLATTEN_TOLERANCE = 1e-3;

/**
 * One path: a start point plus a chain of segments.
 *
 * `strokeWidths`, when present, holds one width per **anchor** (`points().length` entries) and is what
 * a width-modulated centreline carries from the distance transform through to the outliner.
 */
export class VecPath {
  constructor(
    readonly start: VecPoint,
    readonly segments: readonly VecSeg[],
    readonly closed = false,
    readonly id = '',
    readonly strokeWidths: Float32Array | null = null,
  ) {}

  /** @returns the anchors only — the start point followed by each segment's `to`. */
  points(): VecPoint[] {
    const out: VecPoint[] = [this.start];
    for (let i = 0; i < this.segments.length; i++) out.push(this.segments[i].to);
    return out;
  }

  /**
   * Flatten to a polyline by **adaptive de Casteljau subdivision** with an explicit stack.
   *
   * Adaptive and not a fixed step count on purpose: a fixed count over-tessellates a 2 px curve into
   * dozens of collinear points that the simplifier then has to remove, and under-tessellates a 2000 px
   * curve into a visible polygon. The depth cap means a cusped or degenerate curve degrades in
   * smoothness rather than subdividing until the heap gives out.
   *
   * For a closed path the result does **not** repeat the start point; a trailing anchor that already
   * coincides with it is dropped, so no consumer ever sees a zero-length closing edge.
   *
   * @param tolerance maximum deviation in document units; values below 1e-3 are treated as 1e-3,
   *                  below which the flatness test is float noise and subdivision never terminates
   */
  flatten(tolerance = DEFAULT_FLATTEN_TOLERANCE): VecPoint[] {
    const out: VecPoint[] = [this.start];
    if (this.segments.length === 0) return out;

    const tol = tolerance < MIN_FLATTEN_TOLERANCE ? MIN_FLATTEN_TOLERANCE : tolerance;
    const tolSq = tol * tol;
    // Allocated once per call, not once per segment: this runs over every path in the document on
    // every preview frame.
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
        // Degree-elevate to a cubic so there is exactly one subdivision code path. A separate
        // quadratic flattener is a second place for the tolerance test to drift out of agreement.
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

  /**
   * Tight bounding box as `[minX, minY, maxX, maxY]`.
   *
   * Curve extrema are solved analytically — the roots of the derivative — not approximated from the
   * control hull or from a flattened polyline. A control-hull box is up to a third too large on a
   * strongly curved segment, and the document bounds become the SVG `viewBox`, so a loose box shows as
   * unexpected margin around every export.
   *
   * A path with no segments returns the degenerate box at `start`.
   */
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

  /**
   * @returns a new path with every anchor and control point mapped through `m`, and `strokeWidths`
   *          scaled by `m.meanScale()` — a width that did not scale with the geometry would come out
   *          several times too thin on an export from a downscaled source. A singular transform yields
   *          zero widths, which is the honest answer for collapsed geometry.
   */
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

  /** @returns the flattened arc length, including the closing segment when the path is closed. */
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

  /**
   * @returns the same geometry traversed backwards. Control points swap so the curve shape is
   *          preserved exactly; `strokeWidths` is reversed alongside the anchors it describes.
   */
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

  /** @returns true when the path has no segments, i.e. nothing to draw but the start point. */
  isEmpty(): boolean {
    return this.segments.length === 0;
  }

  /**
   * Structural equality including `strokeWidths` contents.
   *
   * Kotlin's generated `equals` on a data class holding a `FloatArray` compares the array by identity,
   * which makes two identical paths unequal and silently breaks undo/redo deduplication. The same trap
   * exists here for anyone reaching for `===`, so the comparison is provided rather than left to chance.
   */
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

/** @returns the point on a cubic Bezier at parameter `t`. Exported because every fitter needs it. */
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

/** @returns the point on a quadratic Bezier at parameter `t`. */
export function quadAt(p0: VecPoint, p1: VecPoint, p2: VecPoint, t: number): VecPoint {
  const mt = 1 - t;
  const a = mt * mt;
  const b = 2 * mt * t;
  const c = t * t;
  return { x: a * p0.x + b * p1.x + c * p2.x, y: a * p0.y + b * p1.y + c * p2.y };
}

/**
 * Adaptive de Casteljau subdivision with an explicit stack.
 *
 * The right half is pushed before the left so the left pops first and the emitted points stay in
 * parameter order. Only the *end* point of each flat piece is emitted; the caller has already emitted
 * the start.
 */
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

    let t = sp * 8; // right half
    stack[t] = xm; stack[t + 1] = ym;
    stack[t + 2] = x123; stack[t + 3] = y123;
    stack[t + 4] = x23; stack[t + 5] = y23;
    stack[t + 6] = x3; stack[t + 7] = y3;
    depths[sp] = depth + 1;
    sp++;

    t = sp * 8; // left half
    stack[t] = x0; stack[t + 1] = y0;
    stack[t + 2] = x01; stack[t + 3] = y01;
    stack[t + 4] = x012; stack[t + 5] = y012;
    stack[t + 6] = xm; stack[t + 7] = ym;
    depths[sp] = depth + 1;
    sp++;
  }
}

/**
 * The standard "control points close to the chord" flatness test: it bounds the true deviation without
 * a square root or a division, which matters because it runs once per subdivision step of every curve
 * in the document.
 */
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

/** Writes `[min, max]` of a cubic Bezier component into `out`, including its analytic extrema. */
function cubicExtent(p0: number, p1: number, p2: number, p3: number, out: Float64Array): void {
  let lo = p0 < p3 ? p0 : p3;
  let hi = p0 > p3 ? p0 : p3;

  // B'(t)/3 = A t^2 + B t + C, with A = -p0 + 3p1 - 3p2 + p3, B = 2(p0 - 2p1 + p2), C = p1 - p0.
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

/** Writes `[min, max]` of a quadratic Bezier component into `out`, including its extremum. */
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

/** Paint and stroke attributes. `null` for `stroke` or `fill` means "none", as in SVG. */
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

/** The Kotlin data class's default arguments, in one place. */
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

/** @returns {@link DEFAULT_STYLE} with `over` applied — the equivalent of Kotlin's named arguments. */
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

/** A whole drawing: a canvas size, ordered layers (bottom first) and an optional background. */
export class VecDocument {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly layers: readonly VecLayer[],
    readonly background: number | null = null,
  ) {}

  /**
   * @returns `[minX, minY, maxX, maxY]` over every shape in every layer, **including hidden ones** —
   *          a hidden layer is still part of the document and an export that cropped it away would lose
   *          data the moment it was made visible again. `[0, 0, width, height]` when there are no shapes.
   */
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

  /** @returns the total anchor count across the document — the number the UI shows as "nodes". */
  nodeCount(): number {
    let n = 0;
    for (const layer of this.layers) {
      for (const shape of layer.shapes) n += shape.path.segments.length + 1;
    }
    return n;
  }

  /**
   * @returns a new document with every shape mapped through `m`, stroke widths scaled by
   *          `m.meanScale()`, and the canvas scaled by the transform's **column norms**
   *          (`hypot(a, b)` by `hypot(c, d)`). A rotation therefore leaves the canvas alone, which is
   *          what the export path wants: it composes a working-to-output scale and never a rotation,
   *          and a canvas that silently grew under a rotation would crop on the next export.
   */
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
