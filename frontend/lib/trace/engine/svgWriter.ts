import { FillRule, LineCap, LineJoin, VecDocument, VecShape, VecStyle } from './path';
import { toD } from './svgPathData';

/**
 * SVG serialisation.
 *
 * Colours are written as `#rrggbb` with a separate `*-opacity` attribute whenever alpha is below 255.
 * `#rrggbbaa` is valid CSS Color 4 but is not understood by several cutting-plotter and CNC tool chains
 * that are a primary target of this app, and a silently ignored alpha there means a stroke that plots as
 * fully opaque.
 */

export interface SvgOptions {
  readonly precision: number;
  readonly includeMetadata: boolean;
  readonly groupByLayer: boolean;
  readonly prettyPrint: boolean;
  readonly widthUnit: string;
  readonly title: string;
}

export const DEFAULT_SVG_OPTIONS: SvgOptions = {
  precision: 2,
  includeMetadata: true,
  groupByLayer: true,
  prettyPrint: true,
  widthUnit: 'px',
  title: 'Offline Tracer export',
};

/** @returns {@link DEFAULT_SVG_OPTIONS} with `over` applied. */
export function svgOptions(over: Partial<SvgOptions> = {}): SvgOptions {
  return { ...DEFAULT_SVG_OPTIONS, ...over };
}

/** Escapes the five XML metacharacters. Applied to every string that reaches the output. */
export function escapeXml(s: string): string {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '&') out += '&amp;';
    else if (c === '<') out += '&lt;';
    else if (c === '>') out += '&gt;';
    else if (c === '"') out += '&quot;';
    else if (c === "'") out += '&apos;';
    // XML 1.0 cannot represent the C0 controls other than tab, newline and carriage return at all —
    // not even as a numeric entity — so they are dropped rather than escaped. A layer name pasted
    // from a spreadsheet routinely carries one, and a single stray 0x01 makes the whole file
    // unopenable in every parser.
    else if (c >= ' ' || c === '\t' || c === '\n' || c === '\r') out += c;
  }
  return out;
}

function hex2(v: number): string {
  const s = (v & 0xff).toString(16);
  return s.length === 1 ? `0${s}` : s;
}

function colourHex(argb: number): string {
  return `#${hex2(argb >>> 16)}${hex2(argb >>> 8)}${hex2(argb)}`;
}

/**
 * Fixed-point, locale-independent, and **never non-finite**.
 *
 * A single `NaN` or `Infinity` anywhere in an SVG attribute makes the renderer drop the element that
 * carries it — silently, and only in some renderers — so a finite substitute is always emitted. A wrong
 * pixel is debuggable; a blank export is not.
 */
function num(v: number, precision: number): string {
  const finite = Number.isFinite(v) ? v : 0;
  const clamped = finite > MAX_COORD ? MAX_COORD : finite < -MAX_COORD ? -MAX_COORD : finite;
  let s = clamped.toFixed(precision);
  if (s.indexOf('.') >= 0) s = s.replace(/0+$/, '').replace(/\.$/, '');
  return s === '-0' ? '0' : s;
}

/** Beyond this a coordinate is meaningless and some renderers overflow their fixed-point maths. */
const MAX_COORD = 1e7;

/** A non-positive or non-finite canvas dimension falls back to 1 unit so the file still opens. */
function sanitizeDimension(v: number): number {
  if (!Number.isFinite(v) || v <= 0) return 1;
  return v > MAX_COORD ? MAX_COORD : v;
}

function capName(c: LineCap): string {
  if (c === LineCap.ROUND) return 'round';
  if (c === LineCap.SQUARE) return 'square';
  return 'butt';
}

function joinName(j: LineJoin): string {
  if (j === LineJoin.ROUND) return 'round';
  if (j === LineJoin.BEVEL) return 'bevel';
  return 'miter';
}

function styleAttrs(style: VecStyle, precision: number): string {
  const parts: string[] = [];
  if (style.fill === null) {
    parts.push('fill="none"');
  } else {
    parts.push(`fill="${colourHex(style.fill)}"`);
    const a = (style.fill >>> 24) & 0xff;
    if (a !== 255) parts.push(`fill-opacity="${num(a / 255, 3)}"`);
    parts.push(`fill-rule="${style.fillRule === FillRule.NONZERO ? 'nonzero' : 'evenodd'}"`);
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
    if (style.join === LineJoin.MITER && style.miterLimit !== 4) {
      parts.push(`stroke-miterlimit="${num(style.miterLimit, precision)}"`);
    }
  }
  if (style.opacity !== 1) parts.push(`opacity="${num(style.opacity, 3)}"`);
  return parts.join(' ');
}

function shapeElement(shape: VecShape, options: SvgOptions, indent: string): string {
  const d = toD(shape.path, options.precision);
  const attrs = styleAttrs(shape.style, options.precision);
  const id = shape.path.id !== '' ? ` id="${escapeXml(shape.path.id)}"` : '';
  return `${indent}<path${id} ${attrs} d="${d}"/>`;
}

/**
 * Serialise a document.
 *
 * The `viewBox` is always `0 0 width height` — the document's own canvas, not the shapes' bounding box.
 * Cropping to the content would move the artwork relative to the source image, and a user who traces a
 * photo and then imports the SVG over it expects the two to line up.
 *
 * @returns a complete standalone SVG document with an XML declaration.
 */
export function write(doc: VecDocument, options: SvgOptions = DEFAULT_SVG_OPTIONS): string {
  const p = Math.max(0, Math.min(8, options.precision | 0));
  const opts: SvgOptions = { ...options, precision: p };
  const docW = sanitizeDimension(doc.width);
  const docH = sanitizeDimension(doc.height);
  const nl = opts.prettyPrint ? '\n' : '';
  const ind = opts.prettyPrint ? '  ' : '';
  const parts: string[] = [];
  parts.push('<?xml version="1.0" encoding="UTF-8" standalone="no"?>');
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" version="1.1" ` +
      `width="${num(docW, p)}${escapeXml(opts.widthUnit)}" ` +
      `height="${num(docH, p)}${escapeXml(opts.widthUnit)}" ` +
      `viewBox="0 0 ${num(docW, p)} ${num(docH, p)}">`,
  );
  if (opts.includeMetadata) {
    parts.push(`${ind}<title>${escapeXml(opts.title)}</title>`);
    parts.push(
      `${ind}<desc>Generated by Offline Tracer. All processing performed on device.</desc>`,
    );
  }
  if (doc.background !== null) {
    const a = (doc.background >>> 24) & 0xff;
    const op = a !== 255 ? ` fill-opacity="${num(a / 255, 3)}"` : '';
    parts.push(
      `${ind}<rect x="0" y="0" width="${num(docW, p)}" height="${num(docH, p)}" ` +
        `fill="${colourHex(doc.background)}"${op}/>`,
    );
  }
  for (const layer of doc.layers) {
    if (opts.groupByLayer) {
      const attrs: string[] = [`id="${escapeXml(layer.id)}"`];
      attrs.push(`data-name="${escapeXml(layer.name)}"`);
      if (!layer.visible) attrs.push('display="none"');
      if (layer.opacity !== 1) attrs.push(`opacity="${num(layer.opacity, 3)}"`);
      parts.push(`${ind}<g ${attrs.join(' ')}>`);
      for (const shape of layer.shapes) parts.push(shapeElement(shape, opts, ind + ind));
      parts.push(`${ind}</g>`);
    } else {
      // Flattened output drops layer visibility, so a hidden layer must not silently become visible.
      if (!layer.visible) continue;
      for (const shape of layer.shapes) parts.push(shapeElement(shape, opts, ind));
    }
  }
  parts.push('</svg>');
  return parts.join(nl === '' ? '' : nl) + nl;
}
