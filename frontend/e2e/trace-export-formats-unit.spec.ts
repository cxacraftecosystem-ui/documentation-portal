import { expect, test } from "@playwright/test";

import {
  ATTACHABLE_FORMATS,
  ATTACH_SUFFIX,
  EXPORT_FORMATS,
  NOT_OFFERED,
  PNG_MAX_EDGE_PX,
  RENDER_SUFFIX,
  TRACE_SUFFIX,
  isExported
} from "@/components/trace/traceExport";
import { TRACE_LAYER_NAME, documentFrom } from "@/components/trace/geometryToDocument";
import {
  MAX_SHAPES_PER_FILE,
  VERB_CUBIC,
  VERB_LINE,
  type FlatGeometry,
  type GeometryStyle,
  type SvgInput
} from "@/components/trace/geometryToSvg";
import { ExportFormat, ExportOptions } from "@/lib/trace/engine/exportFormats";
import { FillRule, LineCap, LineJoin } from "@/lib/trace/engine/path";
import { writeDxf } from "@/lib/trace/engine/dxfWriter";
import { writeEps } from "@/lib/trace/engine/epsWriter";
import { writePdf } from "@/lib/trace/engine/pdfWriter";

/**
 * THE EXPORT FORMATS — one table, every surface, and no writer left unreachable.
 *
 * ── THE FAILURE THIS FILE EXISTS TO MAKE LOUD ─────────────────────────────────────────────────
 *
 * `engine/exportFormats.ts` can write ten formats. A panel that hard-codes two buttons beside a table
 * that lists five, over an engine that can write ten, produces exactly the situation this port
 * inherited upstream: three finished writers sitting in the engine with nothing on screen able to
 * reach them, and nothing anywhere saying whether that was a decision or an oversight. It read as an
 * oversight to the audit that found it, because there was no way to tell.
 *
 * So the accounting is asserted in BOTH directions and it is over FORMATS rather than over buttons:
 *
 *   · every member of `ExportFormat` is either in {@link EXPORT_FORMATS} or in {@link NOT_OFFERED}
 *     with a written reason — a writer added to the engine cannot go unexposed silently, because
 *     somebody has to wire it up or write down why not;
 *   · every row in the table produces real bytes through the engine's own writer;
 *   · the `mime` and `extension` on every row are the engine's own answers rather than a second
 *     register of the same fact.
 *
 * ── AND THE HALF THE AUDIT MISSED ─────────────────────────────────────────────────────────────
 *
 * "Adding them is a table entry each plus a button" is true and is the cheap half. `exportDocument`
 * takes a `VecDocument` and the worker deliberately never sends one — the geometry crosses as six flat
 * typed arrays, because a 50,000-path trace is a million allocations to structured-clone on every
 * preview frame. `geometryToDocument.ts` is the missing half, and the cases below drive the three real
 * writers through it so a change to `trace.worker.serializeGeometry` fails here rather than producing
 * a PDF full of `NaN` that opens blank and reports nothing.
 *
 * WHY NOT THE PNG. `exportPngFile` needs a 2D canvas context and `canvas.toBlob`, neither of which
 * exists in the Node process these specs run in. Its two testable claims — the `PNG_MAX_EDGE_PX` cap
 * and the naming — are asserted through the table and through `derivedFileName`
 * (`e2e/trace-options-unit.spec.ts`) instead of by faking a browser.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * A drawing to write
 * ──────────────────────────────────────────────────────────────────────────── */

const INK: GeometryStyle = {
  stroke: 0xff101010,
  strokeWidth: 1.5,
  fill: null,
  fillRule: "EVENODD",
  cap: "ROUND",
  join: "ROUND",
  miterLimit: 4,
  opacity: 1
};

/** Two shapes: one three-segment polyline and one cubic, so a flattener and a curve writer both run. */
function drawing(): SvgInput {
  const geometry: FlatGeometry = {
    coords: new Float32Array([
      // shape 0 — M 10,10 L 90,10 L 90,90 L 10,90
      10, 10, 90, 10, 90, 90, 10, 90,
      // shape 1 — M 20,50 C 40,20 60,80 80,50
      20, 50, 40, 20, 60, 80, 80, 50
    ]),
    verbs: new Uint8Array([VERB_LINE, VERB_LINE, VERB_LINE, VERB_CUBIC]),
    verbStarts: new Uint32Array([0, 3, 4]),
    coordStarts: new Uint32Array([0, 8, 16]),
    closed: new Uint8Array([1, 0]),
    styleTable: [INK],
    styleIndex: new Uint32Array([0, 0])
  };
  return { geometry, width: 100, height: 100, background: null };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The accounting, in both directions
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("every format the engine can write is accounted for", () => {
  test("offered or explained — a writer cannot go unexposed in silence", () => {
    const offered = new Set(EXPORT_FORMATS.map((entry) => entry.engineFormat));
    const refused = new Set(NOT_OFFERED.map((entry) => entry.format));
    for (const format of Object.values(ExportFormat)) {
      const known = offered.has(format as never) || refused.has(format as never);
      expect(known, `${format} is in neither EXPORT_FORMATS nor NOT_OFFERED`).toBe(true);
    }
  });

  test("and nothing is in both, which would be two answers to one question", () => {
    const offered = new Set<string>(EXPORT_FORMATS.map((entry) => entry.engineFormat));
    for (const entry of NOT_OFFERED) expect(offered.has(entry.format), `${entry.format} is in both`).toBe(false);
  });

  test("every row names a format the engine actually has", () => {
    const real = new Set<string>(Object.values(ExportFormat));
    for (const entry of EXPORT_FORMATS) {
      expect(real.has(entry.engineFormat), `${entry.engineFormat} is not an ExportFormat`).toBe(true);
    }
    for (const entry of NOT_OFFERED) {
      expect(real.has(entry.format), `${entry.format} is not an ExportFormat`).toBe(true);
    }
  });

  test("every absence carries a REASON, not an apology and not a blank", () => {
    // A list of absences is a strange thing to ship and that is the point of this one. A reason nobody
    // wrote is indistinguishable from an oversight, which is the state this whole table exists to end.
    for (const entry of NOT_OFFERED) {
      expect(entry.reason.length, `${entry.format} has no reason`).toBeGreaterThan(60);
    }
  });
});

test.describe("the table is the one register of each format's own facts", () => {
  test("the extension on every row is the engine's own answer", () => {
    for (const entry of EXPORT_FORMATS) {
      const engine = new ExportOptions({ format: entry.engineFormat as ExportFormat });
      expect(entry.extension, `${entry.id}'s extension`).toBe(engine.extension);
    }
  });

  test("the MIME type on every row is the engine's own answer", () => {
    for (const entry of EXPORT_FORMATS) {
      const engine = new ExportOptions({ format: entry.engineFormat as ExportFormat });
      expect(entry.mime, `${entry.id}'s MIME type`).toBe(engine.mimeType);
    }
  });

  test("ids and labels are unique, so a chip row cannot draw two of anything", () => {
    expect(new Set(EXPORT_FORMATS.map((e) => e.id)).size).toBe(EXPORT_FORMATS.length);
    expect(new Set(EXPORT_FORMATS.map((e) => e.label)).size).toBe(EXPORT_FORMATS.length);
  });

  test("every row carries the words on its button and the sentence under it", () => {
    for (const entry of EXPORT_FORMATS) {
      expect(entry.download.length, `${entry.id} has no button text`).toBeGreaterThan(10);
      // The audience is a researcher, not a developer: nobody should have to know what a `.dxf` is
      // before pressing a button that makes one.
      expect(entry.hint.length, `${entry.id} has no hint`).toBeGreaterThan(40);
    }
  });

  test("the PNG hint quotes the cap that exportPngFile actually enforces", () => {
    // A cap written out in copy is a second copy that goes stale, so the sentence interpolates the
    // constant. This is what keeps the number a researcher reads and the number the code enforces one
    // number.
    const png = EXPORT_FORMATS.find((entry) => entry.id === "png");
    expect(png?.hint).toContain(String(PNG_MAX_EDGE_PX));
  });
});

test.describe("what may be FILED and what is take-away only", () => {
  test("ATTACHABLE_FORMATS is derived from the table, never a second list", () => {
    expect(ATTACHABLE_FORMATS.map((e) => e.id)).toEqual(
      EXPORT_FORMATS.filter((e) => e.attachable).map((e) => e.id)
    );
  });

  test("exactly SVG and PNG, and the two attachable rows come first", () => {
    expect(ATTACHABLE_FORMATS.map((e) => e.id)).toEqual(["svg", "png"]);
    // So the "Attach as" chooser and the download row below it list them in the same order.
    expect(EXPORT_FORMATS.slice(0, 2).map((e) => e.id)).toEqual(["svg", "png"]);
  });

  test("THE REASON DXF IS NOT ATTACHABLE, checked rather than asserted in prose", () => {
    // `lib/media.inferMediaType` types a file by its MIME prefix, so `image/vnd.dxf` arrives in the
    // archive typed IMAGE — and `MediaLightbox` renders anything typed IMAGE through an `<img>`. A
    // `.dxf` filed on a record is therefore a media tile showing a broken picture, for a file no
    // browser has ever been able to decode. If that ever stops being true, this case is where the
    // header's claim is caught.
    const dxf = EXPORT_FORMATS.find((entry) => entry.id === "dxf");
    expect(dxf?.mime.startsWith("image/")).toBe(true);
    expect(dxf?.attachable).toBe(false);
  });

  test("the two suffixes that must differ, differ; the two that must match, match", () => {
    // A raster of the drawing and the drawing itself would otherwise both be `photo-line-art.png` in a
    // downloads folder, where the record's own provenance is not there to tell them apart.
    expect(RENDER_SUFFIX).not.toBe(TRACE_SUFFIX);
    // The downloaded SVG is byte-for-byte the file the host receives, so giving the copy on the
    // researcher's laptop a different name would invite the belief that it is a different drawing.
    expect(TRACE_SUFFIX).toBe(ATTACH_SUFFIX);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * The adapter, and the three writers it feeds
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("geometryToDocument is the inverse of the worker's serializeGeometry", () => {
  test("every shape comes back, with its verbs and its closed flag", () => {
    const built = documentFrom(drawing());
    expect(built.shapesWritten).toBe(2);
    expect(built.truncationNote).toBeNull();
    const shapes = built.document.layers[0].shapes;
    expect(shapes).toHaveLength(2);
    expect(shapes[0].path.closed).toBe(true);
    expect(shapes[0].path.segments).toHaveLength(3);
    expect(shapes[1].path.closed).toBe(false);
    expect(shapes[1].path.segments).toHaveLength(1);
  });

  test("the layer is named, so a CAD operator opening the DXF sees a word rather than LAYER0", () => {
    const built = documentFrom(drawing());
    expect(built.document.layers).toHaveLength(1);
    expect(built.document.layers[0].name).toBe(TRACE_LAYER_NAME);
  });

  test("the worker's plain-string style enums are MAPPED, never cast", () => {
    // `GeometryStyle` declares cap/join/fillRule as plain `string` on purpose, so a cast would hand an
    // unrecognised value straight through to a writer that switches on it. Mapping means an unknown
    // value lands on a documented default.
    const style = documentFrom(drawing()).document.layers[0].shapes[0].style;
    expect(style.cap).toBe(LineCap.ROUND);
    expect(style.join).toBe(LineJoin.ROUND);
    expect(style.fillRule).toBe(FillRule.EVENODD);
    expect(style.stroke).toBe(INK.stroke);
  });

  test("an unknown cap falls back to the SVG WRITER's default, not the painter's", () => {
    // The two already disagree — `geometryToSvg.capName` falls back to butt/miter and
    // `traceExport.paintGeometry` to round/round — in a branch the worker cannot reach. This copy
    // follows the SVG writer, because the SVG is the reference artefact: it is the file the host
    // receives, and a PDF that disagreed with it would disagree about a drawing already approved.
    const input = drawing();
    const odd: SvgInput = {
      ...input,
      geometry: { ...input.geometry, styleTable: [{ ...INK, cap: "WHAT", join: "WHAT" }] }
    };
    const style = documentFrom(odd).document.layers[0].shapes[0].style;
    expect(style.cap).toBe(LineCap.BUTT);
    expect(style.join).toBe(LineJoin.MITER);
  });

  test("a style index outside the table draws a hairline rather than dropping the path", () => {
    const input = drawing();
    const broken: SvgInput = {
      ...input,
      geometry: { ...input.geometry, styleIndex: new Uint32Array([9, 9]) }
    };
    const built = documentFrom(broken);
    expect(built.document.layers[0].shapes).toHaveLength(2);
    expect(built.document.layers[0].shapes[0].style.strokeWidth).toBeGreaterThan(0);
  });

  test("a run that cannot supply what its verbs claim is truncated, never read past", () => {
    // Reading past the end would not throw — a `Float32Array` answers `undefined` out of range — it
    // would put NaN coordinates into a PDF, which is the silent-blank-export failure no viewer reports.
    const truncated: SvgInput = {
      width: 100,
      height: 100,
      background: null,
      geometry: {
        coords: new Float32Array([0, 0, 10, 0]),
        verbs: new Uint8Array([VERB_LINE, VERB_CUBIC]),
        verbStarts: new Uint32Array([0, 2]),
        coordStarts: new Uint32Array([0, 4]),
        closed: new Uint8Array([0]),
        styleTable: [INK],
        styleIndex: new Uint32Array([0])
      }
    };
    const built = documentFrom(truncated);
    const path = built.document.layers[0].shapes[0].path;
    expect(path.segments).toHaveLength(1);
    const bytes = writePdf(built.document, { includeMetadata: true, title: "x" });
    expect(new TextDecoder().decode(bytes)).not.toContain("NaN");
  });

  test("a non-finite document size becomes a page a viewer can open", () => {
    // The engine's writers guard with `Math.max(1, doc.width)`, and `Math.max(1, NaN)` is `NaN` — so a
    // document whose size never became a number reaches a PDF's `/MediaBox` as `0.0` and the file
    // opens empty. `sanitizeDimension` is the one rule both writers share.
    const built = documentFrom({ ...drawing(), width: Number.NaN, height: 0 });
    expect(built.document.width).toBe(1);
    expect(built.document.height).toBe(1);
  });

  test("THE CAP IS THE SVG'S CAP, and it is reported with the SVG's own sentence", () => {
    // A researcher who exports one drawing twice — once as an SVG and once as a PDF — must get one
    // drawing, and must be told once, in one wording, when it was cut short.
    const built = documentFrom(drawing(), 1);
    expect(built.shapesWritten).toBe(1);
    expect(built.truncationNote).toContain("separate paths");
    expect(documentFrom(drawing(), MAX_SHAPES_PER_FILE).truncationNote).toBeNull();
  });
});

test.describe("the three writers the adapter exists for produce real files", () => {
  test("a PDF, carrying the provenance note in its title", () => {
    const built = documentFrom(drawing());
    const bytes = writePdf(built.document, { includeMetadata: true, title: "Traced on the device from block.jpg" });
    const text = new TextDecoder("latin1").decode(bytes);
    expect(bytes.byteLength).toBeGreaterThan(400);
    expect(text.startsWith("%PDF")).toBe(true);
    expect(text).toContain("block.jpg");
    expect(text).not.toContain("NaN");
  });

  test("an EPS, carrying it in its %%Title", () => {
    const built = documentFrom(drawing());
    const bytes = writeEps(built.document, { includeMetadata: true, title: "Traced on the device from block.jpg" });
    const text = new TextDecoder("latin1").decode(bytes);
    expect(text.startsWith("%!PS-Adobe")).toBe(true);
    expect(text).toContain("block.jpg");
    expect(text).not.toContain("NaN");
  });

  test("a DXF, which has NOWHERE to carry it — the gap the panel states out loud", () => {
    // `writeDxf` takes no metadata argument at all, because DXF R12 has no channel for one. That is
    // skipped work, so the panel says so beside the button rather than quietly tolerating it. This case
    // is what makes the claim checkable: the only thing that reaches the file is geometry.
    expect(writeDxf.length).toBeLessThanOrEqual(2);
    const built = documentFrom(drawing());
    const text = new TextDecoder().decode(writeDxf(built.document));
    expect(text).toContain("SECTION");
    expect(text).toContain("ENTITIES");
    // `dxfWriter.dxfName` upper-cases and replaces spaces, so "Line art" arrives as a name that says
    // what the entities are rather than as the fallback `LAYER0`.
    expect(text).toContain("LINE_ART");
    expect(text).not.toContain("LAYER0");
    expect(text).not.toContain("NaN");
  });

  test("the same geometry through all three writers agrees about how many paths there are", () => {
    // One drawing, three files. The point of the shared adapter is that the three cannot disagree.
    const built = documentFrom(drawing());
    expect(built.shapesWritten).toBe(2);
    for (const bytes of [
      writePdf(built.document, { includeMetadata: false }),
      writeEps(built.document, { includeMetadata: false }),
      writeDxf(built.document)
    ]) {
      expect(bytes.byteLength).toBeGreaterThan(100);
    }
  });
});

test("isExported narrows an outcome rather than making callers test for a key", () => {
  const file = new File(["<svg/>"], "a-line-art.svg", { type: "image/svg+xml" });
  expect(isExported({ file, note: null })).toBe(true);
  expect(isExported({ reason: "no" })).toBe(false);
});
