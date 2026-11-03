export const VERB_LINE = 0;
export const VERB_QUAD = 1;
export const VERB_CUBIC = 2;

export interface GeometryStyle {
  readonly stroke: number | null;
  readonly strokeWidth: number;
  readonly fill: number | null;
  readonly fillRule: string;
  readonly cap: string;
  readonly join: string;
  readonly miterLimit: number;
  readonly opacity: number;
}

export interface FlatGeometry {
  readonly coords: Float32Array;
  readonly verbs: Uint8Array;
  readonly verbStarts: Uint32Array;
  readonly coordStarts: Uint32Array;
  readonly closed: Uint8Array;
  readonly styleTable: readonly GeometryStyle[];
  readonly styleIndex: Uint32Array;
}

export interface SvgInput {
  readonly geometry: FlatGeometry;
  readonly width: number;
  readonly height: number;
  readonly background: number | null;
}

const MAX_COORD = 1e7;

function hex2(v: number): string {
  const s = (v & 0xff).toString(16);
  return s.length === 1 ? `0${s}` : s;
}

function colourHex(argb: number): string {
  return `#${hex2(argb >>> 16)}${hex2(argb >>> 8)}${hex2(argb)}`;
}

function num(v: number, precision: number): string {
  const finite = Number.isFinite(v) ? v : 0;
  const clamped = finite > MAX_COORD ? MAX_COORD : finite < -MAX_COORD ? -MAX_COORD : finite;
  let s = clamped.toFixed(precision);
  if (s.indexOf(".") >= 0) s = s.replace(/0+$/, "").replace(/\.$/, "");
  return s === "-0" ? "0" : s;
}

export function sanitizeDimension(v: number): number {
  if (!Number.isFinite(v) || v <= 0) return 1;
  return v > MAX_COORD ? MAX_COORD : v;
}

function capName(c: string): string {
  if (c === "ROUND") return "round";
  if (c === "SQUARE") return "square";
  return "butt";
}

function joinName(j: string): string {
  if (j === "ROUND") return "round";
  if (j === "BEVEL") return "bevel";
  return "miter";
}

function styleAttrs(style: GeometryStyle, precision: number): string {
  const parts: string[] = [];
  if (style.fill === null) {
    parts.push('fill="none"');
  } else {
    parts.push(`fill="${colourHex(style.fill)}"`);
    const a = (style.fill >>> 24) & 0xff;
    if (a !== 255) parts.push(`fill-opacity="${num(a / 255, 3)}"`);
    parts.push(`fill-rule="${style.fillRule === "NONZERO" ? "nonzero" : "evenodd"}"`);
  }
  if (style.stroke === null) {
    parts.push('stroke="none"');
  } else {
    parts.push(`stroke="${colourHex(style.stroke)}"`);
    const a = (style.stroke >>> 24) & 0xff;
    if (a !== 255) parts.push(`stroke-opacity="${num(a / 255, 3)}"`);
    parts.push(`stroke-width="${num(style.strokeWidth, precision)}"`);
    parts.push(`stroke-linecap="${capName(style.cap)}"`);
    parts.push(`stroke-linejoin="${joinName(style.join)}"`);
    if (style.join === "MITER" && style.miterLimit !== 4) {
      parts.push(`stroke-miterlimit="${num(style.miterLimit, precision)}"`);
    }
  }
  if (style.opacity !== 1) parts.push(`opacity="${num(style.opacity, 3)}"`);
  return parts.join(" ");
}

export function shapePathData(geometry: FlatGeometry, index: number, precision: number): string {
  const coordStart = geometry.coordStarts[index];
  const coordEnd = geometry.coordStarts[index + 1];
  if (coordEnd - coordStart < 2) return "";

  const parts: string[] = [
    `M${num(geometry.coords[coordStart], precision)} ${num(geometry.coords[coordStart + 1], precision)}`
  ];

  let cursor = coordStart + 2;
  const verbStart = geometry.verbStarts[index];
  const verbEnd = geometry.verbStarts[index + 1];
  for (let v = verbStart; v < verbEnd; v += 1) {
    const verb = geometry.verbs[v];
    const needed = verb === VERB_CUBIC ? 6 : verb === VERB_QUAD ? 4 : 2;
    if (cursor + needed > coordEnd) break;
    const n = (offset: number): string => num(geometry.coords[cursor + offset], precision);
    if (verb === VERB_CUBIC) {
      parts.push(`C${n(0)} ${n(1)} ${n(2)} ${n(3)} ${n(4)} ${n(5)}`);
    } else if (verb === VERB_QUAD) {
      parts.push(`Q${n(0)} ${n(1)} ${n(2)} ${n(3)}`);
    } else {
      parts.push(`L${n(0)} ${n(1)}`);
    }
    cursor += needed;
  }

  if (geometry.closed[index] === 1) parts.push("Z");
  return parts.join(" ");
}

export interface SvgOptions {
  readonly precision?: number;
  readonly provenanceNote?: string;
}

export const MAX_SHAPES_PER_FILE = 200000;

export interface SvgResult {
  readonly svg: string;
  readonly shapesWritten: number;
  readonly truncationNote: string | null;
}

export function buildSvg(input: SvgInput, options: SvgOptions = {}): SvgResult {
  const precision = options.precision ?? 2;
  const width = sanitizeDimension(input.width);
  const height = sanitizeDimension(input.height);
  const shapeCount = Math.max(0, input.geometry.styleIndex.length);
  const limit = Math.min(shapeCount, MAX_SHAPES_PER_FILE);

  const out: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<svg xmlns="http://www.w3.org/2000/svg" width="${num(width, precision)}" height="${num(height, precision)}" ` +
      `viewBox="0 0 ${num(width, precision)} ${num(height, precision)}">`
  ];

  if (options.provenanceNote) {
    out.push(`<!-- ${options.provenanceNote.replace(/-{2,}/g, "-")} -->`);
  }

  if (input.background !== null) {
    const alpha = (input.background >>> 24) & 0xff;
    const opacity = alpha === 255 ? "" : ` fill-opacity="${num(alpha / 255, 3)}"`;
    out.push(
      `<rect x="0" y="0" width="${num(width, precision)}" height="${num(height, precision)}" ` +
        `fill="${colourHex(input.background)}"${opacity}/>`
    );
  }

  for (let i = 0; i < limit; i += 1) {
    const d = shapePathData(input.geometry, i, precision);
    if (d.length === 0) continue;
    const style = input.geometry.styleTable[input.geometry.styleIndex[i]];
    const attrs = style ? styleAttrs(style, precision) : 'fill="none" stroke="#000000" stroke-width="1.5"';
    out.push(`<path d="${d}" ${attrs}/>`);
  }

  out.push("</svg>");

  return {
    svg: out.join("\n"),
    shapesWritten: limit,
    truncationNote: truncationNoteFor(shapeCount, limit)
  };
}

export function truncationNoteFor(shapeCount: number, shapesWritten: number): string | null {
  if (shapeCount <= shapesWritten) return null;
  return (
    `Only ${shapesWritten.toLocaleString("en-IN")} of ${shapeCount.toLocaleString("en-IN")} paths saved. Raise “Minimum speck” or “Simplify”.`
  );
}

export const DEFAULT_DERIVED_SUFFIX = "line-art";

export const UNNAMED_SOURCE_STEM = "image";

export function derivedFileName(
  sourceName: string,
  extension: string,
  suffix: string = DEFAULT_DERIVED_SUFFIX
): string {
  const trimmed = sourceName.replace(/\.[^./\\]+$/, "").trim();
  const base = trimmed.length > 0 ? trimmed : UNNAMED_SOURCE_STEM;
  const safe = base.replace(/[^\w\-. ]+/g, "_").slice(0, 80);
  const tag = suffix.replace(/[^\w\-. ]+/g, "_").trim();
  return tag.length > 0 ? `${safe}-${tag}.${extension}` : `${safe}.${extension}`;
}
