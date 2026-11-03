import {
  DEFAULT_DERIVED_SUFFIX,
  buildSvg,
  derivedFileName,
  shapePathData,
  type SvgInput,
  type SvgResult
} from "./geometryToSvg";

export interface ExportOutcome {
  readonly file: File;
  readonly note: string | null;
}

export interface ExportRefusal {
  readonly reason: string;
}

export type ExportResult = ExportOutcome | ExportRefusal;

export function isExported(result: ExportResult): result is ExportOutcome {
  return "file" in result;
}

export const PNG_MAX_EDGE_PX = 2048;

export const EXPORT_FORMATS = [
  {
    id: "svg",
    label: "SVG",
    engineFormat: "SVG",
    extension: "svg",
    mime: "image/svg+xml",
    attachable: true,
    download: "Download the trace (SVG)",
    hint: "Scalable vector. Opens in Illustrator, Inkscape and CorelDRAW."
  },
  {
    id: "png",
    label: "PNG",
    engineFormat: "PNG",
    extension: "png",
    mime: "image/png",
    attachable: true,
    download: "Download the rendered image (PNG)",
    hint: `Transparent image, up to ${PNG_MAX_EDGE_PX}px. Opens anywhere.`
  },
  {
    id: "pdf",
    label: "PDF",
    engineFormat: "PDF",
    extension: "pdf",
    mime: "application/pdf",
    attachable: false,
    download: "Download a PDF to send on",
    hint: "Vector that opens anywhere. Best for email."
  },
  {
    id: "dxf",
    label: "DXF",
    engineFormat: "DXF",
    extension: "dxf",
    mime: "image/vnd.dxf",
    attachable: false,
    download: "Download for a CAD or cutting machine (DXF)",
    hint: "For laser cutters, CNC and CAD (DXF R12, outlines only)."
  },
  {
    id: "eps",
    label: "EPS",
    engineFormat: "EPS",
    extension: "eps",
    mime: "application/postscript",
    attachable: false,
    download: "Download for a print shop (EPS)",
    hint: "For print shops that don’t take SVG. No transparency."
  }
] as const;

export type ExportFormatEntry = (typeof EXPORT_FORMATS)[number];

export type ExportFormatId = ExportFormatEntry["id"];

export type AttachFormatId = Extract<ExportFormatEntry, { attachable: true }>["id"];

export type VectorFormatId = Exclude<ExportFormatId, "svg" | "png">;

export const ATTACHABLE_FORMATS: readonly Extract<ExportFormatEntry, { attachable: true }>[] =
  EXPORT_FORMATS.filter(
    (entry): entry is Extract<ExportFormatEntry, { attachable: true }> => entry.attachable
  );

export const NOT_OFFERED = [
  {
    format: "JPEG",
    reason:
      "Lossy, and lossy is at its worst on exactly this: hard black edges on white come back with " +
      "grey mush around them. It carries no transparency either, so a traced drawing would arrive " +
      "on a white rectangle. `exportDocument` throws for it by design; PNG is the raster answer."
  },
  {
    format: "WEBP",
    reason:
      "The same lossy objection as JPEG, and it is a web delivery format rather than one a print " +
      "shop, a CAD package or an archive reader would take. `exportDocument` throws for it too."
  },
  {
    format: "TIFF",
    reason:
      "The engine writes it uncompressed — a 2048px plate is 16 MB of RGBA — for a use nobody has " +
      "asked for. PNG is the same pixels an order of magnitude smaller, and the vector formats are " +
      "the answer to “I need it bigger”."
  },
  {
    format: "BMP",
    reason:
      "Uncompressed 32bpp, the same 16 MB, in a format whose only advantage is opening on a computer " +
      "from 1998. PNG covers every reader that would take a BMP."
  },
  {
    format: "PROJECT",
    reason:
      "`.otproj` is the tracer's own session file — geometry plus every parameter, so a trace can be " +
      "reopened and re-tuned. It is not a drawing, it is useful only inside an application the " +
      "researcher does not have, and `exportDocument` refuses it as well (`ProjectCodec` writes it). " +
      "Worth revisiting if this panel ever grows a “reopen this trace” door; there is nothing to " +
      "reopen it with today, because nothing on this panel is stored."
  }
] as const;

export const ATTACH_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const TRACE_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const RENDER_SUFFIX = "traced";

export interface ExportNaming {
  readonly suffix?: string;
}

export function exportSvgFile(
  input: SvgInput,
  sourceName: string,
  provenanceNote?: string,
  naming: ExportNaming = {}
): ExportOutcome {
  const result: SvgResult = buildSvg(input, { provenanceNote });
  const name = derivedFileName(sourceName, "svg", naming.suffix ?? ATTACH_SUFFIX);
  return {
    file: new File([result.svg], name, { type: "image/svg+xml" }),
    note: result.truncationNote
  };
}

const WRITER_UNAVAILABLE =
  "This format didn’t load. Reload the page, or use SVG.";

export async function exportVectorFile(
  format: VectorFormatId,
  input: SvgInput,
  sourceName: string,
  provenanceNote?: string,
  naming: ExportNaming = {}
): Promise<ExportResult> {
  const entry = EXPORT_FORMATS.find((candidate) => candidate.id === format);
  if (entry === undefined) return { reason: `${String(format)} is not a format this panel can write.` };

  try {
    const { documentFrom } = await import("./geometryToDocument");
    const built = documentFrom(input);

    let bytes: Uint8Array;
    if (format === "pdf") {
      const { writePdf } = await import("@/lib/trace/engine/pdfWriter");
      bytes = writePdf(built.document, { includeMetadata: true, title: provenanceNote });
    } else if (format === "eps") {
      const { writeEps } = await import("@/lib/trace/engine/epsWriter");
      bytes = writeEps(built.document, { includeMetadata: true, title: provenanceNote });
    } else {
      const { writeDxf } = await import("@/lib/trace/engine/dxfWriter");
      bytes = writeDxf(built.document);
    }

    const name = derivedFileName(sourceName, entry.extension, naming.suffix ?? ATTACH_SUFFIX);
    const buffer = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(buffer).set(bytes);
    return {
      file: new File([buffer], name, { type: entry.mime }),
      note: built.truncationNote
    };
  } catch (error) {
    if (error instanceof Error && /import|module|chunk|fetch/i.test(error.message)) {
      return { reason: WRITER_UNAVAILABLE };
    }
    return {
      reason:
        `The drawing was traced but the ${entry.label} could not be written` +
        (error instanceof Error && error.message ? `: ${error.message}. ` : ". ") +
        "The SVG download is unaffected."
    };
  }
}

export async function exportPngFile(
  input: SvgInput,
  sourceName: string,
  maxEdge: number = PNG_MAX_EDGE_PX,
  naming: ExportNaming = {}
): Promise<ExportResult> {
  const sourceWidth = Number.isFinite(input.width) && input.width > 0 ? input.width : 1;
  const sourceHeight = Number.isFinite(input.height) && input.height > 0 ? input.height : 1;
  const scale = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
  const width = Math.max(1, Math.round(sourceWidth * scale));
  const height = Math.max(1, Math.round(sourceHeight * scale));

  const canvas = createCanvas(width, height);
  if (canvas === null) {
    return { reason: "This browser can’t make a PNG. Use SVG." };
  }
  const context = canvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  if (context === null) {
    return { reason: "This browser can’t make a PNG. Use SVG." };
  }

  paintGeometry(context, input, scale);

  const blob = await canvasToBlob(canvas);
  if (blob === null) {
    return {
      reason:
        "Couldn’t make a PNG — the image may be too large. Use SVG or lower “Trace resolution”."
    };
  }
  const name = derivedFileName(sourceName, "png", naming.suffix ?? ATTACH_SUFFIX);
  const note =
    scale < 1
      ? `PNG reduced to ${width}x${height} from ${Math.round(sourceWidth)}x${Math.round(sourceHeight)}. SVG is full size.`
      : null;
  return { file: new File([blob], name, { type: "image/png" }), note };
}

export function paintGeometry(
  context: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  input: SvgInput,
  scale: number
): void {
  const width = Number.isFinite(input.width) && input.width > 0 ? input.width : 1;
  const height = Number.isFinite(input.height) && input.height > 0 ? input.height : 1;

  context.save();
  context.scale(scale, scale);

  if (input.background !== null) {
    context.globalAlpha = alphaOf(input.background);
    context.fillStyle = argbToCss(input.background);
    context.fillRect(0, 0, width, height);
    context.globalAlpha = 1;
  }

  const geometry = input.geometry;
  const count = geometry.styleIndex.length;
  for (let i = 0; i < count; i += 1) {
    const d = shapePathData(geometry, i, 3);
    if (d.length === 0) continue;
    const style = geometry.styleTable[geometry.styleIndex[i]];
    if (!style) continue;
    const path = new Path2D(d);
    const shapeOpacity = Number.isFinite(style.opacity) ? Math.min(1, Math.max(0, style.opacity)) : 1;
    if (style.fill !== null) {
      context.globalAlpha = shapeOpacity * alphaOf(style.fill);
      context.fillStyle = argbToCss(style.fill);
      context.fill(path, style.fillRule === "NONZERO" ? "nonzero" : "evenodd");
    }
    if (style.stroke !== null) {
      context.globalAlpha = shapeOpacity * alphaOf(style.stroke);
      context.strokeStyle = argbToCss(style.stroke);
      context.lineWidth = Number.isFinite(style.strokeWidth) && style.strokeWidth > 0 ? style.strokeWidth : 1;
      context.lineCap = style.cap === "SQUARE" ? "square" : style.cap === "BUTT" ? "butt" : "round";
      context.lineJoin = style.join === "MITER" ? "miter" : style.join === "BEVEL" ? "bevel" : "round";
      if (Number.isFinite(style.miterLimit) && style.miterLimit > 0) context.miterLimit = style.miterLimit;
      context.stroke(path);
    }
  }

  context.restore();
}

function alphaOf(argb: number): number {
  return ((argb >>> 24) & 0xff) / 255;
}

function argbToCss(argb: number): string {
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return `rgb(${r} ${g} ${b})`;
}

export function createCanvas(width: number, height: number): HTMLCanvasElement | OffscreenCanvas | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

export async function canvasToBlob(canvas: HTMLCanvasElement | OffscreenCanvas): Promise<Blob | null> {
  if ("convertToBlob" in canvas) {
    try {
      return await canvas.convertToBlob({ type: "image/png" });
    } catch {
      return null;
    }
  }
  return await new Promise<Blob | null>((resolve) => {
    canvas.toBlob((blob) => resolve(blob), "image/png");
  });
}
