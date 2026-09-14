import { FillRule, LineCap, LineJoin, VecDocument, VecShape, VecStyle } from './path';

/**
 * Encapsulated PostScript 3.0 writer.
 *
 * EPS exists here for print shops and older sign-cutting software that will not take an SVG. Like the PDF
 * writer, it emits one coordinate-system flip (`1 0 0 -1 0 H concat`) rather than negating every y, so the
 * geometry in the file reads the same as the geometry in the document.
 *
 * PostScript has **no alpha**. Rather than pretend, partially transparent paint is composited against the
 * document background at write time and the fact is recorded in a comment in the file — a silently opaque
 * export is the failure mode this avoids.
 */

const COORD_PRECISION = 3;

function num(v: number): string {
  if (!Number.isFinite(v)) return '0';
  let s = v.toFixed(COORD_PRECISION);
  if (s.indexOf('.') >= 0) s = s.replace(/0+$/, '').replace(/\.$/, '');
  return s === '-0' ? '0' : s;
}

function comp(v: number): string {
  return num((v & 0xff) / 255);
}

/** PostScript comments run to end of line, so a newline in a title would inject code. */
function psComment(s: string): string {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c >= ' ' && c <= '~') out += c;
  }
  return out;
}

function capCode(c: LineCap): number {
  if (c === LineCap.ROUND) return 1;
  if (c === LineCap.SQUARE) return 2;
  return 0;
}

function joinCode(j: LineJoin): number {
  if (j === LineJoin.ROUND) return 1;
  if (j === LineJoin.BEVEL) return 2;
  return 0;
}

/** Blend a colour towards the page background by its own alpha, since PostScript cannot express alpha. */
function flatten(argb: number, opacity: number, background: number | null): string {
  const a = (((argb >>> 24) & 0xff) / 255) * opacity;
  const bg = background === null ? 0xffffffff : background;
  const br = (bg >>> 16) & 0xff;
  const bgg = (bg >>> 8) & 0xff;
  const bb = bg & 0xff;
  const r = ((argb >>> 16) & 0xff) * a + br * (1 - a);
  const g = ((argb >>> 8) & 0xff) * a + bgg * (1 - a);
  const b = (argb & 0xff) * a + bb * (1 - a);
  return `${comp(Math.round(r))} ${comp(Math.round(g))} ${comp(Math.round(b))} setrgbcolor`;
}

function pathOps(shape: VecShape): string[] {
  const parts: string[] = ['newpath'];
  const path = shape.path;
  parts.push(`${num(path.start.x)} ${num(path.start.y)} moveto`);
  let cur = path.start;
  for (const seg of path.segments) {
    if (seg.kind === 'line') {
      parts.push(`${num(seg.to.x)} ${num(seg.to.y)} lineto`);
    } else if (seg.kind === 'cubic') {
      parts.push(
        `${num(seg.c1.x)} ${num(seg.c1.y)} ${num(seg.c2.x)} ${num(seg.c2.y)} ` +
          `${num(seg.to.x)} ${num(seg.to.y)} curveto`,
      );
    } else {
      // PostScript has no quadratic; the exact cubic equivalent lifts the control point to 2/3.
      const c1x = cur.x + (2 / 3) * (seg.c.x - cur.x);
      const c1y = cur.y + (2 / 3) * (seg.c.y - cur.y);
      const c2x = seg.to.x + (2 / 3) * (seg.c.x - seg.to.x);
      const c2y = seg.to.y + (2 / 3) * (seg.c.y - seg.to.y);
      parts.push(
        `${num(c1x)} ${num(c1y)} ${num(c2x)} ${num(c2y)} ${num(seg.to.x)} ${num(seg.to.y)} curveto`,
      );
    }
    cur = seg.to;
  }
  if (path.closed) parts.push('closepath');
  return parts;
}

function shapeOps(shape: VecShape, layerOpacity: number, background: number | null): string[] {
  const style: VecStyle = { ...shape.style, opacity: shape.style.opacity * layerOpacity };
  const parts: string[] = [];
  const hasFill = style.fill !== null;
  const hasStroke = style.stroke !== null && style.strokeWidth > 0;
  if (!hasFill && !hasStroke) return parts;
  if (hasFill && hasStroke) {
    // Fill then stroke needs the path twice: PostScript consumes the current path on either operator.
    parts.push('gsave');
    parts.push(...pathOps(shape));
    parts.push(flatten(style.fill as number, style.opacity, background));
    parts.push(style.fillRule === FillRule.EVENODD ? 'eofill' : 'fill');
    parts.push('grestore');
  } else if (hasFill) {
    parts.push(...pathOps(shape));
    parts.push(flatten(style.fill as number, style.opacity, background));
    parts.push(style.fillRule === FillRule.EVENODD ? 'eofill' : 'fill');
    return parts;
  }
  if (hasStroke) {
    parts.push(...pathOps(shape));
    parts.push(flatten(style.stroke as number, style.opacity, background));
    parts.push(`${num(style.strokeWidth)} setlinewidth`);
    parts.push(`${capCode(style.cap)} setlinecap`);
    parts.push(`${joinCode(style.join)} setlinejoin`);
    if (style.join === LineJoin.MITER) parts.push(`${num(style.miterLimit)} setmiterlimit`);
    parts.push('stroke');
  }
  return parts;
}

const ascii = new TextEncoder();

/**
 * Write an EPS file.
 * @returns the complete EPS bytes, with a `%%BoundingBox` matching the document canvas.
 */
export function writeEps(
  doc: VecDocument,
  options: { includeMetadata: boolean; title?: string } = { includeMetadata: true },
): Uint8Array {
  const width = Math.max(1, doc.width);
  const height = Math.max(1, doc.height);
  const lines: string[] = [];
  lines.push('%!PS-Adobe-3.0 EPSF-3.0');
  lines.push(`%%BoundingBox: 0 0 ${Math.ceil(width)} ${Math.ceil(height)}`);
  lines.push(`%%HiResBoundingBox: 0 0 ${num(width)} ${num(height)}`);
  if (options.includeMetadata) {
    lines.push('%%Creator: Offline Tracer');
    lines.push(`%%Title: ${psComment(options.title ?? 'Offline Tracer export')}`);
    lines.push('%%Note: PostScript has no alpha channel; translucent paint was composited on export.');
  }
  lines.push('%%LanguageLevel: 3');
  lines.push('%%Pages: 1');
  lines.push('%%EndComments');
  lines.push('%%BeginProlog');
  lines.push('/OTsave save def');
  lines.push('%%EndProlog');
  lines.push('%%Page: 1 1');
  lines.push('gsave');
  if (doc.background !== null) {
    lines.push(flatten(doc.background, 1, null));
    lines.push(`newpath 0 0 moveto ${num(width)} 0 lineto ${num(width)} ${num(height)} lineto ` +
      `0 ${num(height)} lineto closepath fill`);
  }
  // One flip so the document's y-down geometry can be written verbatim.
  lines.push(`1 0 0 -1 0 ${num(height)} concat`);
  for (const layer of doc.layers) {
    if (!layer.visible) continue;
    for (const shape of layer.shapes) {
      const ops = shapeOps(shape, layer.opacity, doc.background);
      for (const op of ops) lines.push(op);
    }
  }
  lines.push('grestore');
  lines.push('showpage');
  lines.push('OTsave restore');
  lines.push('%%EOF');
  return ascii.encode(`${lines.join('\n')}\n`);
}
