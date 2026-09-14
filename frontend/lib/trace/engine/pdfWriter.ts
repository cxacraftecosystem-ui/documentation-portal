import {
  FillRule,
  LineCap,
  LineJoin,
  VecDocument,
  VecPath,
  VecShape,
  VecStyle,
} from './path';

/**
 * PDF 1.4 writer.
 *
 * Vector in, vector out: the paths become PDF path operators, so the file is resolution independent and
 * a print shop can scale it without asking for the original.
 *
 * PDF's y axis points **up** and the document's points down, so the whole content stream runs under one
 * `1 0 0 -1 0 H cm` flip. Flipping each coordinate instead would also mirror every stroke's dash phase
 * and every text baseline the moment either is added.
 */

/** Coordinate precision in the content stream. Beyond three decimals a PDF viewer cannot resolve it. */
const COORD_PRECISION = 3;

function num(v: number): string {
  if (!Number.isFinite(v)) return '0';
  let s = v.toFixed(COORD_PRECISION);
  if (s.indexOf('.') >= 0) s = s.replace(/0+$/, '').replace(/\.$/, '');
  return s === '-0' ? '0' : s;
}

function comp(v: number): string {
  return num(v / 255);
}

/** Escapes the three characters that terminate a PDF literal string. */
function pdfString(s: string): string {
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '(' || c === ')' || c === '\\') out += `\\${c}`;
    // PDF literal strings are byte strings; anything outside printable ASCII is dropped rather than
    // guessed at, because a mis-encoded /Title makes some readers reject the whole trailer.
    else if (c >= ' ' && c <= '~') out += c;
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

function pathOps(path: VecPath): string {
  const parts: string[] = [];
  parts.push(`${num(path.start.x)} ${num(path.start.y)} m`);
  let cur = path.start;
  for (const seg of path.segments) {
    if (seg.kind === 'line') {
      parts.push(`${num(seg.to.x)} ${num(seg.to.y)} l`);
    } else if (seg.kind === 'cubic') {
      parts.push(
        `${num(seg.c1.x)} ${num(seg.c1.y)} ${num(seg.c2.x)} ${num(seg.c2.y)} ` +
          `${num(seg.to.x)} ${num(seg.to.y)} c`,
      );
    } else {
      // PDF has no quadratic operator; the exact cubic equivalent raises the control points to 2/3.
      const c1x = cur.x + (2 / 3) * (seg.c.x - cur.x);
      const c1y = cur.y + (2 / 3) * (seg.c.y - cur.y);
      const c2x = seg.to.x + (2 / 3) * (seg.c.x - seg.to.x);
      const c2y = seg.to.y + (2 / 3) * (seg.c.y - seg.to.y);
      parts.push(
        `${num(c1x)} ${num(c1y)} ${num(c2x)} ${num(c2y)} ${num(seg.to.x)} ${num(seg.to.y)} c`,
      );
    }
    cur = seg.to;
  }
  if (path.closed) parts.push('h');
  return parts.join('\n');
}

function paintOperator(style: VecStyle, closed: boolean): string {
  const hasFill = style.fill !== null;
  const hasStroke = style.stroke !== null && style.strokeWidth > 0;
  const eo = style.fillRule === FillRule.EVENODD ? '*' : '';
  if (hasFill && hasStroke) return closed ? `b${eo}` : `B${eo}`;
  if (hasFill) return `f${eo}`;
  if (hasStroke) return closed ? 's' : 'S';
  return 'n';
}

/** One alpha pair needs one ExtGState; the pairs are deduplicated so a 4 000-path file has a few. */
function alphaKey(style: VecStyle): string {
  const fa = style.fill === null ? 1 : ((style.fill >>> 24) & 0xff) / 255;
  const sa = style.stroke === null ? 1 : ((style.stroke >>> 24) & 0xff) / 255;
  return `${(fa * style.opacity).toFixed(4)},${(sa * style.opacity).toFixed(4)}`;
}

function buildContent(doc: VecDocument, height: number, gsNames: Map<string, string>): string {
  const parts: string[] = [];
  parts.push('q');
  parts.push(`1 0 0 -1 0 ${num(height)} cm`);
  if (doc.background !== null) {
    parts.push(
      `${comp(doc.background >>> 16)} ${comp(doc.background >>> 8)} ${comp(doc.background)} rg`,
    );
    parts.push(`0 0 ${num(doc.width)} ${num(doc.height)} re f`);
  }
  for (const layer of doc.layers) {
    if (!layer.visible) continue;
    for (const shape of layer.shapes) {
      parts.push(shapeOps(shape, layer.opacity, gsNames));
    }
  }
  parts.push('Q');
  return parts.join('\n');
}

function shapeOps(shape: VecShape, layerOpacity: number, gsNames: Map<string, string>): string {
  const style: VecStyle = { ...shape.style, opacity: shape.style.opacity * layerOpacity };
  const parts: string[] = ['q'];
  const gs = gsNames.get(alphaKey(style));
  if (gs !== undefined) parts.push(`/${gs} gs`);
  if (style.fill !== null) {
    parts.push(`${comp(style.fill >>> 16)} ${comp(style.fill >>> 8)} ${comp(style.fill)} rg`);
  }
  if (style.stroke !== null) {
    parts.push(`${comp(style.stroke >>> 16)} ${comp(style.stroke >>> 8)} ${comp(style.stroke)} RG`);
    parts.push(`${num(style.strokeWidth)} w`);
    parts.push(`${capCode(style.cap)} J`);
    parts.push(`${joinCode(style.join)} j`);
    if (style.join === LineJoin.MITER) parts.push(`${num(style.miterLimit)} M`);
  }
  parts.push(pathOps(shape.path));
  parts.push(paintOperator(style, shape.path.closed));
  parts.push('Q');
  return parts.join('\n');
}

const ascii = new TextEncoder();

/**
 * Write a one-page PDF.
 *
 * @param doc      the document; its `width`/`height` become the MediaBox in points
 * @param options  only `includeMetadata` and the document geometry are read here — a PDF is vector, so
 *                 `width`, `height`, `scale` and `dpi` are applied by the caller before this point
 * @returns the complete PDF file bytes, with a correct cross-reference table.
 */
export function writePdf(
  doc: VecDocument,
  options: { includeMetadata: boolean; title?: string } = { includeMetadata: true },
): Uint8Array {
  const width = Math.max(1, doc.width);
  const height = Math.max(1, doc.height);

  // Collect the distinct alpha pairs first: the Resources dictionary has to name them all before the
  // content stream can reference any of them.
  const gsNames = new Map<string, string>();
  for (const layer of doc.layers) {
    if (!layer.visible) continue;
    for (const shape of layer.shapes) {
      const style: VecStyle = { ...shape.style, opacity: shape.style.opacity * layer.opacity };
      const key = alphaKey(style);
      if (key !== '1.0000,1.0000' && !gsNames.has(key)) {
        gsNames.set(key, `GS${gsNames.size}`);
      }
    }
  }
  const content = buildContent(doc, height, gsNames);
  const contentBytes = ascii.encode(content);

  const objects: Uint8Array[] = [];
  const push = (body: string): number => {
    objects.push(ascii.encode(body));
    return objects.length; // 1-based object number
  };

  const catalogNum = 1;
  const pagesNum = 2;
  const pageNum = 3;
  const contentNum = 4;
  const firstGsNum = 5;
  const infoNum = firstGsNum + gsNames.size;

  // The map's insertion order is the object order, and it is also the order the /ExtGState references are
  // written below, which is what keeps /GSn pointing at the state it names.
  const gsEntries: string[] = [];
  for (const key of gsNames.keys()) {
    const [fillAlpha, strokeAlpha] = key.split(',');
    gsEntries.push(`<< /Type /ExtGState /ca ${fillAlpha} /CA ${strokeAlpha} >>`);
  }

  const resourceParts: string[] = [];
  if (gsNames.size > 0) {
    const refs: string[] = [];
    let n = firstGsNum;
    for (const name of gsNames.values()) refs.push(`/${name} ${n++} 0 R`);
    resourceParts.push(`/ExtGState << ${refs.join(' ')} >>`);
  }
  const resources = resourceParts.length > 0 ? `<< ${resourceParts.join(' ')} >>` : '<< >>';

  push(`<< /Type /Catalog /Pages ${pagesNum} 0 R >>`);
  push(`<< /Type /Pages /Kids [${pageNum} 0 R] /Count 1 >>`);
  push(
    `<< /Type /Page /Parent ${pagesNum} 0 R /MediaBox [0 0 ${num(width)} ${num(height)}] ` +
      `/Resources ${resources} /Contents ${contentNum} 0 R >>`,
  );
  push(`<< /Length ${contentBytes.length} >>\nstream\n${content}\nendstream`);
  for (const entry of gsEntries) push(entry);
  if (options.includeMetadata) {
    const title = pdfString(options.title ?? 'Offline Tracer export');
    push(`<< /Title (${title}) /Producer (Offline Tracer) /Creator (Offline Tracer) >>`);
  }

  const header = '%PDF-1.4\n';
  const chunks: Uint8Array[] = [ascii.encode(header)];
  const offsets: number[] = [];
  let offset = header.length;
  for (let i = 0; i < objects.length; i++) {
    const prefix = ascii.encode(`${i + 1} 0 obj\n`);
    const suffix = ascii.encode('\nendobj\n');
    offsets.push(offset);
    chunks.push(prefix, objects[i], suffix);
    offset += prefix.length + objects[i].length + suffix.length;
  }
  const xrefOffset = offset;
  const xrefLines: string[] = [`xref\n0 ${objects.length + 1}\n`, '0000000000 65535 f \n'];
  for (const off of offsets) {
    xrefLines.push(`${off.toString().padStart(10, '0')} 00000 n \n`);
  }
  const trailerInfo = options.includeMetadata ? ` /Info ${infoNum} 0 R` : '';
  xrefLines.push(
    `trailer\n<< /Size ${objects.length + 1} /Root ${catalogNum} 0 R${trailerInfo} >>\n` +
      `startxref\n${xrefOffset}\n%%EOF\n`,
  );
  chunks.push(ascii.encode(xrefLines.join('')));

  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let p = 0;
  for (const c of chunks) {
    out.set(c, p);
    p += c.length;
  }
  return out;
}
