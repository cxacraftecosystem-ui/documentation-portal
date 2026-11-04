import { DEFAULT_FLATTEN_TOLERANCE, VecDocument, VecPoint } from './path';

function pair(out: string[], code: number, value: string): void {
  out.push(String(code));
  out.push(value);
}

function num(v: number): string {
  if (!Number.isFinite(v)) return '0.0';
  const s = v.toFixed(6);
  return s;
}

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
  pair(out, 62, '7');
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
  pair(out, 66, '1');
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
