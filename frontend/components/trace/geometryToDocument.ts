import {
  DEFAULT_STYLE,
  FillRule,
  LineCap,
  LineJoin,
  VecDocument,
  VecPath,
  VecSeg,
  vecLayer,
  vecPoint,
  vecShape,
  type VecShape,
  type VecStyle
} from "@/lib/trace/engine/path";

import {
  MAX_SHAPES_PER_FILE,
  VERB_CUBIC,
  VERB_QUAD,
  sanitizeDimension,
  truncationNoteFor,
  type FlatGeometry,
  type GeometryStyle,
  type SvgInput
} from "./geometryToSvg";

export const TRACE_LAYER_NAME = "Line art";

export interface DocumentResult {
  readonly document: VecDocument;
  readonly shapesWritten: number;
  readonly truncationNote: string | null;
}

export function documentFrom(input: SvgInput, limit: number = MAX_SHAPES_PER_FILE): DocumentResult {
  const geometry = input.geometry;
  const shapeCount = Math.max(0, geometry.styleIndex.length);
  const written = Math.min(shapeCount, Math.max(0, limit));

  const shapes: VecShape[] = [];
  for (let i = 0; i < written; i += 1) {
    const path = pathFor(geometry, i);
    if (path === null) continue;
    shapes.push(vecShape(path, styleFor(geometry.styleTable[geometry.styleIndex[i]])));
  }

  return {
    document: new VecDocument(
      sanitizeDimension(input.width),
      sanitizeDimension(input.height),
      [vecLayer("trace", TRACE_LAYER_NAME, shapes)],
      input.background
    ),
    shapesWritten: written,
    truncationNote: truncationNoteFor(shapeCount, written)
  };
}

function pathFor(geometry: FlatGeometry, index: number): VecPath | null {
  const coordStart = geometry.coordStarts[index];
  const coordEnd = geometry.coordStarts[index + 1];
  if (coordEnd - coordStart < 2) return null;

  const c = geometry.coords;
  const start = vecPoint(c[coordStart], c[coordStart + 1]);
  const segments: VecSeg[] = [];

  let cursor = coordStart + 2;
  const verbStart = geometry.verbStarts[index];
  const verbEnd = geometry.verbStarts[index + 1];
  for (let v = verbStart; v < verbEnd; v += 1) {
    const verb = geometry.verbs[v];
    const needed = verb === VERB_CUBIC ? 6 : verb === VERB_QUAD ? 4 : 2;
    if (cursor + needed > coordEnd) break;
    if (verb === VERB_CUBIC) {
      segments.push(
        VecSeg.cubic(
          vecPoint(c[cursor], c[cursor + 1]),
          vecPoint(c[cursor + 2], c[cursor + 3]),
          vecPoint(c[cursor + 4], c[cursor + 5])
        )
      );
    } else if (verb === VERB_QUAD) {
      segments.push(VecSeg.quad(vecPoint(c[cursor], c[cursor + 1]), vecPoint(c[cursor + 2], c[cursor + 3])));
    } else {
      segments.push(VecSeg.line(vecPoint(c[cursor], c[cursor + 1])));
    }
    cursor += needed;
  }

  return new VecPath(start, segments, geometry.closed[index] === 1);
}

function styleFor(style: GeometryStyle | undefined): VecStyle {
  if (!style) return { ...DEFAULT_STYLE, cap: LineCap.BUTT, join: LineJoin.MITER };
  return {
    stroke: style.stroke,
    strokeWidth: Number.isFinite(style.strokeWidth) && style.strokeWidth > 0 ? style.strokeWidth : 1,
    fill: style.fill,
    fillRule: style.fillRule === "NONZERO" ? FillRule.NONZERO : FillRule.EVENODD,
    cap: style.cap === "ROUND" ? LineCap.ROUND : style.cap === "SQUARE" ? LineCap.SQUARE : LineCap.BUTT,
    join: style.join === "ROUND" ? LineJoin.ROUND : style.join === "BEVEL" ? LineJoin.BEVEL : LineJoin.MITER,
    miterLimit: Number.isFinite(style.miterLimit) && style.miterLimit > 0 ? style.miterLimit : 4,
    opacity: Number.isFinite(style.opacity) ? Math.min(1, Math.max(0, style.opacity)) : 1
  };
}
