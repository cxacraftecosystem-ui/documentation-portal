/**
 * The worker's flat arrays, rehydrated into the `VecDocument` the engine's own writers take.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THIS FILE EXISTS, AND WHY IT WAS NOT NEEDED FOR THE SVG
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `geometryToSvg.ts` walks the worker's six typed arrays and writes an SVG string directly, and its
 * header explains why it does not call `engine/svgWriter.ts`: the arrays already contain the file in
 * order, so rehydrating a million `VecPath` objects to produce a string would allocate exactly what
 * the flat protocol exists to avoid.
 *
 * **PDF, EPS and DXF are the case that argument does not cover.** A PDF content stream is not the
 * geometry in order — it is a cross-reference table, an `/ExtGState` dictionary per distinct alpha
 * pair and a coordinate flip; DXF flattens every curve to a polyline through `VecPath.flatten`; EPS
 * composites translucency against the document background because PostScript has no alpha. Each of
 * those is a real algorithm, each is already written in `lib/trace/engine/`, and each takes a
 * `VecDocument`. Hand-writing three more flat-array walkers to avoid one 60-line rehydrator would be
 * three more writers that can disagree with the SVG about what one drawing looks like — and
 * `geometryToSvg.ts`'s own header sets the standard: "Two writers is one more than the right number."
 *
 * So this is the adapter, and it is the inverse of exactly one function:
 * `lib/trace/worker/trace.worker.ts:serializeGeometry`. If that function changes shape, this is where
 * it changes back.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * ⚠ NOTHING MAY IMPORT THIS MODULE AT THE TOP OF A COMPONENT
 * ────────────────────────────────────────────────────────────────────────────
 *
 * It imports `engine/path.ts` as a VALUE — the classes and constructors, not merely the types — which
 * is the one thing `TracePanel.tsx`'s property 3 forbids on the page graph: "Do not add a top-level
 * import from `@/lib/trace/*` to this file." `engine/path.ts` imports nothing itself, so the chunk
 * this pulls is that file plus whichever writer asked for it and no more; it is still ~23 KB of
 * source that a researcher who never presses an export button must not pay for.
 *
 * Its ONE runtime caller is `traceExport.exportVectorFile`, behind
 * `await import("./geometryToDocument")`. `e2e/trace-export-formats-unit.spec.ts` imports it
 * statically, which is fine and is half the reason it lives in its own file: node has no bundle.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHAT IT COSTS, HONESTLY
 * ────────────────────────────────────────────────────────────────────────────
 *
 * One `VecPath` and one `VecSeg` per shape, and one `VecPoint` per anchor — for a 200,000-path trace
 * that is the million allocations `worker/protocol.ts` refuses to make *on every preview frame of a
 * slider drag*. It is made here ONCE, on a button press, over geometry that is already computed, and
 * then thrown away. That is the same trade `traceExport.paintGeometry` already makes on the main
 * thread and states in its own comment: "The engine runs in a worker because `Pipeline.run` is seconds
 * of TypeScript; this is not that."
 */

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

/**
 * The name the single layer carries, and therefore the DXF layer a CAD operator sees.
 *
 * `dxfWriter.dxfName` upper-cases and replaces spaces, so this arrives in the file as `LINE_ART` — a
 * name that says what the entities are, in the one format whose whole audience is somebody opening it
 * in a machine controller. The alternative is `dxfName`'s own fallback, `LAYER0`, which says nothing.
 */
export const TRACE_LAYER_NAME = "Line art";

export interface DocumentResult {
  readonly document: VecDocument;
  readonly shapesWritten: number;
  /** Non-null when {@link MAX_SHAPES_PER_FILE} truncated the drawing. The SVG's own sentence, shared. */
  readonly truncationNote: string | null;
}

/**
 * Build a `VecDocument` from one traced result.
 *
 * THE CAP IS THE SVG'S CAP, DELIBERATELY. A researcher who exports one drawing twice — once as an SVG
 * and once as a PDF — must get one drawing, so both stop at {@link MAX_SHAPES_PER_FILE} and both
 * report the cut with the same sentence. A vector format with no ceiling of its own would quietly
 * produce a file the SVG beside it does not match, and nothing on screen would say which one was short.
 */
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

/**
 * One shape's coordinate run, turned back into a path.
 *
 * THE BOUNDS CHECKS ARE `shapePathData`'s, LINE FOR LINE, and for its reason: "A run that cannot
 * supply what its verbs claim is truncated rather than read past: a trace interrupted mid-post is the
 * one way that can happen, and half a drawing is a better answer than an exception in a component's
 * render." Reading past the end here would not throw — a `Float32Array` answers `undefined` out of
 * range — it would put `NaN` coordinates into a PDF, which is the silent-blank-export failure
 * `geometryToSvg`'s `num()` exists to prevent one layer down and which no viewer reports.
 *
 * @returns null when the shape has no start point, the one case the SVG writer also drops.
 */
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

/**
 * A worker style, mapped onto the engine's enums.
 *
 * WHY A MAP AND NOT A CAST. `worker/protocol.ts` carries `VecStyle` itself, and the engine's
 * `FillRule` / `LineCap` / `LineJoin` are string enums whose values equal their names — so at RUN time
 * a cast would be correct and free. It is not done, because `geometryToSvg.GeometryStyle` declares
 * those three fields as plain `string` on purpose ("so this module imports nothing and the spec can
 * build a geometry by hand"), and a cast would hand an unrecognised string straight through to a
 * writer that switches on it. Mapping means an unknown value lands on a documented default rather than
 * on whatever that writer's `else` branch happens to be.
 *
 * THE DEFAULTS ARE `geometryToSvg.capName`/`joinName`'s — butt and miter — AND NOT
 * `traceExport.paintGeometry`'s, which fall back to round for both. Those two already disagree, in a
 * branch the worker cannot reach. This copy follows the SVG WRITER because the SVG is the reference
 * artefact: it is the file the record receives, and a PDF that disagreed with it would disagree about
 * a drawing the researcher had already approved on screen.
 */
function styleFor(style: GeometryStyle | undefined): VecStyle {
  // A style index pointing outside the table means the message was not built by the worker.
  // `buildSvg` draws that path as a plain black hairline rather than dropping it; so does this.
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
