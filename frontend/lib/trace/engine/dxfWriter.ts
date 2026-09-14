import { DEFAULT_FLATTEN_TOLERANCE, VecDocument, VecPoint } from './path';

/**
 * DXF R12 (AC1009) ASCII writer.
 *
 * R12 rather than a modern release on purpose: it is the one DXF version every CAM package, laser cutter
 * and CNC controller in existence reads, and it has no LWPOLYLINE, no object handles and no class table —
 * so there is nothing in the file that an old reader can choke on.
 *
 * Curves are **flattened**: R12 has no spline entity, and emitting one would produce a file that opens
 * empty in exactly the tool chains this format exists to serve. DXF's y axis points up, so every
 * coordinate is mirrored through the document height.
 */

/** DXF group codes and values go on alternating lines, which is the whole format. */
function pair(out: string[], code: number, value: string): void {
  out.push(String(code));
  out.push(value);
}

function num(v: number): string {
  if (!Number.isFinite(v)) return '0.0';
  const s = v.toFixed(6);
  return s;
}

/**
 * Write a DXF R12 file.
 *
 * @param flatten flattening tolerance in document units for curve segments
 * @returns the complete DXF bytes. Layer names come from the document's layers, sanitised to the R12
 *          character set (upper-case letters, digits, `$`, `-`, `_`), because a name with a space makes
 *          some readers reject the table.
 */
export function writeDxf(doc: VecDocument, flatten = DEFAULT_FLATTEN_TOLERANCE): Uint8Array {
  const height = Math.max(1, doc.height);
  const out: string[] = [];

  const layerNames: string[] = [];
  for (const layer of doc.layers) layerNames.push(dxfName(layer.name, layerNames.length));

  pair(out, 0, 'SECTION');
  pair(out, 2, 'HEADER');
  pair(out, 9, '$ACADVER');
  pair(out, 1, 'AC1009');
  pair(out, 9, '$INSBASE');
  pair(out, 10, '0.0');
  pair(out, 20, '0.0');
  pair(out, 30, '0.0');
  pair(out, 9, '$EXTMIN');
  pair(out, 10, '0.0');
  pair(out, 20, '0.0');
  pair(out, 30, '0.0');
  pair(out, 9, '$EXTMAX');
  pair(out, 10, num(doc.width));
  pair(out, 20, num(height));
  pair(out, 30, '0.0');
  pair(out, 0, 'ENDSEC');

  pair(out, 0, 'SECTION');
  pair(out, 2, 'TABLES');
  pair(out, 0, 'TABLE');
  pair(out, 2, 'LAYER');
  pair(out, 70, String(Math.max(1, layerNames.length)));
  if (layerNames.length === 0) {
    writeLayerRecord(out, '0');
  } else {
    for (const name of layerNames) writeLayerRecord(out, name);
  }
  pair(out, 0, 'ENDTAB');
  pair(out, 0, 'ENDSEC');

  pair(out, 0, 'SECTION');
  pair(out, 2, 'ENTITIES');
  for (let li = 0; li < doc.layers.length; li++) {
    const layer = doc.layers[li];
    if (!layer.visible) continue;
    const name = layerNames[li];
    for (const shape of layer.shapes) {
      const pts = shape.path.flatten(flatten);
      if (pts.length < 2) continue;
      writePolyline(out, name, pts, shape.path.closed, height);
    }
  }
  pair(out, 0, 'ENDSEC');
  pair(out, 0, 'EOF');
  return new TextEncoder().encode(`${out.join('\r\n')}\r\n`);
}

function writeLayerRecord(out: string[], name: string): void {
  pair(out, 0, 'LAYER');
  pair(out, 2, name);
  pair(out, 70, '0');
  pair(out, 62, '7'); // white/black, the "use the viewer's default" colour index
  pair(out, 6, 'CONTINUOUS');
}

function writePolyline(
  out: string[],
  layer: string,
  pts: readonly VecPoint[],
  closed: boolean,
  height: number,
): void {
  pair(out, 0, 'POLYLINE');
  pair(out, 8, layer);
  pair(out, 66, '1'); // vertices follow
  pair(out, 70, closed ? '1' : '0');
  pair(out, 10, '0.0');
  pair(out, 20, '0.0');
  pair(out, 30, '0.0');
  for (let i = 0; i < pts.length; i++) {
    pair(out, 0, 'VERTEX');
    pair(out, 8, layer);
    pair(out, 10, num(pts[i].x));
    pair(out, 20, num(height - pts[i].y));
    pair(out, 30, '0.0');
  }
  pair(out, 0, 'SEQEND');
  pair(out, 8, layer);
}

/** @returns an R12-safe layer name; falls back to `LAYER<n>` when nothing usable survives. */
function dxfName(name: string, index: number): string {
  let out = '';
  const upper = name.toUpperCase();
  for (let i = 0; i < upper.length && out.length < 31; i++) {
    const c = upper[i];
    if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c === '$' || c === '-' || c === '_') {
      out += c;
    } else if (c === ' ') {
      out += '_';
    }
  }
  return out.length > 0 ? out : `LAYER${index}`;
}
