import {
  CubicSeg,
  FillRule,
  LineCap,
  LineJoin,
  QuadSeg,
  VecDocument,
  VecLayer,
  VecPath,
  VecPoint,
  VecSeg,
  VecShape,
  VecStyle,
  vecStyle,
} from './path';
import { TraceParams, TraceParamsInput, sanitizeTraceParams } from './params';

/**
 * Project persistence.
 *
 * The on-disk form is plain JSON, hand-rolled rather than reflected, for one reason: a project saved today
 * must open in a build written next year. Every reader below supplies a default for a missing field and
 * clamps or drops anything illegal, so an older or newer file degrades instead of throwing. `decode` never
 * rejects a file it can partially understand — losing somebody's work to a field that gained a member is
 * not acceptable.
 *
 * Geometry is stored compactly (`{k, p}` per segment) because a traced document routinely holds tens of
 * thousands of segments and the verbose form triples the file for no benefit a human reader would notice.
 */

/** Current on-disk schema version. Bump only for a change a reader cannot infer. */
export const PROJECT_SCHEMA_VERSION = 1;

export interface ProjectMeta {
  readonly id: string;
  readonly name: string;
  readonly createdAt: number;
  readonly updatedAt: number;
  readonly tags: readonly string[];
  readonly subjectId: string;
  readonly favourite: boolean;
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly thumbnailPath: string;
}

export interface ProjectDocument {
  readonly meta: ProjectMeta;
  readonly params: TraceParams;
  /** The vector document, serialised by {@link encodeDocument}. Kept as a string so the two halves of a
   * project can be stored and versioned independently. */
  readonly layersJson: string;
  readonly historyVersion: number;
}

/** @returns a meta record with every field present; `id` and `name` are the only things worth supplying. */
export function projectMeta(over: Partial<ProjectMeta> = {}): ProjectMeta {
  return {
    id: '',
    name: 'Untitled',
    createdAt: 0,
    updatedAt: 0,
    tags: [],
    subjectId: '',
    favourite: false,
    sourceWidth: 0,
    sourceHeight: 0,
    thumbnailPath: '',
    ...over,
  };
}

// ---------------------------------------------------------------------------------------------------
// Readers. Every one takes `unknown` and returns something valid.
// ---------------------------------------------------------------------------------------------------

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function readNumber(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback;
}

function readInt(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : fallback;
}

function readString(v: unknown, fallback: string): string {
  return typeof v === 'string' ? v : fallback;
}

function readBool(v: unknown, fallback: boolean): boolean {
  return typeof v === 'boolean' ? v : fallback;
}

function readColour(v: unknown, fallback: number | null): number | null {
  if (v === null) return null;
  if (typeof v !== 'number' || !Number.isFinite(v)) return fallback;
  return v >>> 0;
}

function readStringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  for (const item of v) if (typeof item === 'string') out.push(item);
  return out;
}

function readEnum<T extends Record<string, string>>(
  e: T,
  v: unknown,
  fallback: T[keyof T],
): T[keyof T] {
  if (typeof v === 'string' && Object.prototype.hasOwnProperty.call(e, v)) {
    return e[v] as T[keyof T];
  }
  return fallback;
}

function readPoint(v: unknown, fallback: VecPoint): VecPoint {
  if (!Array.isArray(v) || v.length < 2) return fallback;
  return { x: readNumber(v[0], fallback.x), y: readNumber(v[1], fallback.y) };
}

// ---------------------------------------------------------------------------------------------------
// Geometry codec
// ---------------------------------------------------------------------------------------------------

function encodeSegment(seg: VecSeg): unknown {
  if (seg.kind === 'line') return { k: 'L', p: [seg.to.x, seg.to.y] };
  if (seg.kind === 'cubic') {
    return { k: 'C', p: [seg.c1.x, seg.c1.y, seg.c2.x, seg.c2.y, seg.to.x, seg.to.y] };
  }
  return { k: 'Q', p: [seg.c.x, seg.c.y, seg.to.x, seg.to.y] };
}

function decodeSegment(v: unknown, cursor: VecPoint): VecSeg | null {
  if (!isRecord(v)) return null;
  const k = readString(v.k, '');
  const p = v.p;
  if (!Array.isArray(p)) return null;
  if (k === 'L' && p.length >= 2) {
    return VecSeg.line({ x: readNumber(p[0], cursor.x), y: readNumber(p[1], cursor.y) });
  }
  if (k === 'C' && p.length >= 6) {
    const c: CubicSeg = VecSeg.cubic(
      { x: readNumber(p[0], cursor.x), y: readNumber(p[1], cursor.y) },
      { x: readNumber(p[2], cursor.x), y: readNumber(p[3], cursor.y) },
      { x: readNumber(p[4], cursor.x), y: readNumber(p[5], cursor.y) },
    );
    return c;
  }
  if (k === 'Q' && p.length >= 4) {
    const q: QuadSeg = VecSeg.quad(
      { x: readNumber(p[0], cursor.x), y: readNumber(p[1], cursor.y) },
      { x: readNumber(p[2], cursor.x), y: readNumber(p[3], cursor.y) },
    );
    return q;
  }
  return null;
}

function encodeStyle(style: VecStyle): unknown {
  return {
    stroke: style.stroke,
    strokeWidth: style.strokeWidth,
    fill: style.fill,
    fillRule: style.fillRule,
    cap: style.cap,
    join: style.join,
    miterLimit: style.miterLimit,
    opacity: style.opacity,
  };
}

function decodeStyle(v: unknown): VecStyle {
  if (!isRecord(v)) return vecStyle();
  return vecStyle({
    stroke: readColour(v.stroke, 0xff000000),
    strokeWidth: readNumber(v.strokeWidth, 1.5),
    fill: readColour(v.fill, null),
    fillRule: readEnum(FillRule, v.fillRule, FillRule.EVENODD),
    cap: readEnum(LineCap, v.cap, LineCap.ROUND),
    join: readEnum(LineJoin, v.join, LineJoin.ROUND),
    miterLimit: readNumber(v.miterLimit, 4),
    opacity: readNumber(v.opacity, 1),
  });
}

function encodePath(path: VecPath): unknown {
  const out: Record<string, unknown> = {
    s: [path.start.x, path.start.y],
    g: path.segments.map(encodeSegment),
  };
  if (path.closed) out.c = true;
  if (path.id !== '') out.i = path.id;
  if (path.strokeWidths !== null) out.w = Array.from(path.strokeWidths);
  return out;
}

function decodePath(v: unknown): VecPath {
  if (!isRecord(v)) return new VecPath({ x: 0, y: 0 }, []);
  const start = readPoint(v.s, { x: 0, y: 0 });
  const segments: VecSeg[] = [];
  let cursor = start;
  if (Array.isArray(v.g)) {
    for (const raw of v.g) {
      const seg = decodeSegment(raw, cursor);
      // A segment that cannot be read is dropped rather than replaced by a guess: an invented line to
      // the origin is far more visible, and far harder to explain, than one missing curve.
      if (seg === null) continue;
      segments.push(seg);
      cursor = seg.to;
    }
  }
  let widths: Float32Array | null = null;
  if (Array.isArray(v.w)) {
    widths = new Float32Array(v.w.length);
    for (let i = 0; i < v.w.length; i++) widths[i] = readNumber(v.w[i], 0);
  }
  return new VecPath(start, segments, readBool(v.c, false), readString(v.i, ''), widths);
}

function encodeShape(shape: VecShape): unknown {
  return { p: encodePath(shape.path), y: encodeStyle(shape.style) };
}

function decodeShape(v: unknown): VecShape {
  if (!isRecord(v)) return { path: new VecPath({ x: 0, y: 0 }, []), style: vecStyle() };
  return { path: decodePath(v.p), style: decodeStyle(v.y) };
}

function encodeLayer(layer: VecLayer): unknown {
  return {
    id: layer.id,
    name: layer.name,
    visible: layer.visible,
    locked: layer.locked,
    opacity: layer.opacity,
    shapes: layer.shapes.map(encodeShape),
  };
}

function decodeLayer(v: unknown, index: number): VecLayer {
  if (!isRecord(v)) {
    return { id: `layer${index}`, name: `Layer ${index + 1}`, shapes: [], visible: true, locked: false, opacity: 1 };
  }
  const shapes: VecShape[] = [];
  if (Array.isArray(v.shapes)) for (const raw of v.shapes) shapes.push(decodeShape(raw));
  return {
    id: readString(v.id, `layer${index}`),
    name: readString(v.name, `Layer ${index + 1}`),
    shapes,
    visible: readBool(v.visible, true),
    locked: readBool(v.locked, false),
    opacity: readNumber(v.opacity, 1),
  };
}

/**
 * Serialise a vector document.
 * @returns compact JSON; the inverse of {@link decodeDocument} for every document this engine produces.
 */
export function encodeDocument(doc: VecDocument): string {
  return JSON.stringify({
    v: PROJECT_SCHEMA_VERSION,
    width: doc.width,
    height: doc.height,
    background: doc.background,
    layers: doc.layers.map(encodeLayer),
  });
}

/**
 * Parse a vector document.
 * @returns a document; a 1x1 empty document when the input is not JSON at all, because a caller that has
 *          just opened a corrupt file needs an editor to open, not an exception.
 */
export function decodeDocument(json: string): VecDocument {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return new VecDocument(1, 1, []);
  }
  if (!isRecord(parsed)) return new VecDocument(1, 1, []);
  const layers: VecLayer[] = [];
  if (Array.isArray(parsed.layers)) {
    for (let i = 0; i < parsed.layers.length; i++) layers.push(decodeLayer(parsed.layers[i], i));
  }
  return new VecDocument(
    Math.max(1, readNumber(parsed.width, 1)),
    Math.max(1, readNumber(parsed.height, 1)),
    layers,
    readColour(parsed.background, null),
  );
}

/** Serialise a whole project: metadata, parameters and the geometry blob. */
export function encode(doc: ProjectDocument): string {
  return JSON.stringify({
    v: PROJECT_SCHEMA_VERSION,
    meta: {
      id: doc.meta.id,
      name: doc.meta.name,
      createdAt: doc.meta.createdAt,
      updatedAt: doc.meta.updatedAt,
      tags: Array.from(doc.meta.tags),
      subjectId: doc.meta.subjectId,
      favourite: doc.meta.favourite,
      sourceWidth: doc.meta.sourceWidth,
      sourceHeight: doc.meta.sourceHeight,
      thumbnailPath: doc.meta.thumbnailPath,
    },
    params: doc.params,
    layersJson: doc.layersJson,
    historyVersion: doc.historyVersion,
  });
}

/**
 * Parse a whole project.
 * @returns a project with every field present. Parameters go through {@link sanitizeTraceParams}, which is
 *          the only reason a file written by an older build with a now-illegal value still opens.
 */
export function decode(json: string): ProjectDocument {
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(json);
  } catch {
    parsed = null;
  }
  const root = isRecord(parsed) ? parsed : {};
  const rawMeta = isRecord(root.meta) ? root.meta : {};
  const meta: ProjectMeta = {
    id: readString(rawMeta.id, ''),
    name: readString(rawMeta.name, 'Untitled'),
    createdAt: readInt(rawMeta.createdAt, 0),
    updatedAt: readInt(rawMeta.updatedAt, 0),
    tags: readStringArray(rawMeta.tags),
    subjectId: readString(rawMeta.subjectId, ''),
    favourite: readBool(rawMeta.favourite, false),
    sourceWidth: Math.max(0, readInt(rawMeta.sourceWidth, 0)),
    sourceHeight: Math.max(0, readInt(rawMeta.sourceHeight, 0)),
    thumbnailPath: readString(rawMeta.thumbnailPath, ''),
  };
  const params = sanitizeTraceParams((isRecord(root.params) ? root.params : {}) as TraceParamsInput);
  return {
    meta,
    params,
    layersJson: readString(root.layersJson, ''),
    historyVersion: Math.max(1, readInt(root.historyVersion, 1)),
  };
}

/** @returns a project record from its parts, with `layersJson` produced by {@link encodeDocument}. */
export function fromDocument(
  meta: ProjectMeta,
  params: TraceParams,
  doc: VecDocument,
  historyVersion = 1,
): ProjectDocument {
  return { meta, params, layersJson: encodeDocument(doc), historyVersion };
}
