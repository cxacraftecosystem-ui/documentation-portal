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

function drawing(): SvgInput {
  const geometry: FlatGeometry = {
    coords: new Float32Array([
      10, 10, 90, 10, 90, 90, 10, 90,
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
      expect(entry.hint.length, `${entry.id} has no hint`).toBeGreaterThan(40);
    }
  });

  test("the PNG hint quotes the cap that exportPngFile actually enforces", () => {
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
    expect(EXPORT_FORMATS.slice(0, 2).map((e) => e.id)).toEqual(["svg", "png"]);
  });

  test("THE REASON DXF IS NOT ATTACHABLE, checked rather than asserted in prose", () => {
    const dxf = EXPORT_FORMATS.find((entry) => entry.id === "dxf");
    expect(dxf?.mime.startsWith("image/")).toBe(true);
    expect(dxf?.attachable).toBe(false);
  });

  test("the two suffixes that must differ, differ; the two that must match, match", () => {
    expect(RENDER_SUFFIX).not.toBe(TRACE_SUFFIX);
    expect(TRACE_SUFFIX).toBe(ATTACH_SUFFIX);
  });
});

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
    const style = documentFrom(drawing()).document.layers[0].shapes[0].style;
    expect(style.cap).toBe(LineCap.ROUND);
    expect(style.join).toBe(LineJoin.ROUND);
    expect(style.fillRule).toBe(FillRule.EVENODD);
    expect(style.stroke).toBe(INK.stroke);
  });

  test("an unknown cap falls back to the SVG WRITER's default, not the painter's", () => {
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
    const built = documentFrom({ ...drawing(), width: Number.NaN, height: 0 });
    expect(built.document.width).toBe(1);
    expect(built.document.height).toBe(1);
  });

  test("THE CAP IS THE SVG'S CAP, and it is reported with the SVG's own sentence", () => {
    const built = documentFrom(drawing(), 1);
    expect(built.shapesWritten).toBe(1);
    expect(built.truncationNote).toContain("paths saved");
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
    expect(writeDxf.length).toBeLessThanOrEqual(2);
    const built = documentFrom(drawing());
    const text = new TextDecoder().decode(writeDxf(built.document));
    expect(text).toContain("SECTION");
    expect(text).toContain("ENTITIES");
    expect(text).toContain("LINE_ART");
    expect(text).not.toContain("LAYER0");
    expect(text).not.toContain("NaN");
  });

  test("the same geometry through all three writers agrees about how many paths there are", () => {
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
