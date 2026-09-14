/**
 * Turning a finished trace into a `File` the ordinary upload door will take.
 *
 * THE DOOR IS `onAttach`, AND THIS FILE DELIBERATELY DOES NOT KNOW WHAT IS BEHIND IT. The derived
 * file goes in through the same door a camera photograph uses, so eager pre-upload, per-file retry,
 * multipart, orphan cleanup and the offline draft store — everything `lib/media.ts`'s header lists —
 * all already apply to it. **Nothing here uploads anything itself.** These functions return a `File`
 * and stop. A panel that uploaded its own file would be a second upload path to keep working
 * offline, in an application whose whole point is working offline.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHICH FORMATS, AND WHAT EACH ONE COST TO REACH
 * ────────────────────────────────────────────────────────────────────────────
 *
 * `engine/exportFormats.ts` can write SVG, PDF, EPS, DXF, PNG, BMP and TIFF. This offers **SVG, PNG,
 * PDF, EPS and DXF**, and {@link EXPORT_FORMATS} is the whole list: the id, the words on the control
 * and the sentence a researcher reads, in ONE table, so a writer cannot be wired into one of the
 * three and forgotten in the others. `e2e/trace-export-formats-unit.spec.ts` holds that shut in both
 * directions — every entry in the table must produce bytes, and every format the engine can write
 * must be either in the table or in {@link NOT_OFFERED} with a stated reason.
 *
 * ── TWO OF THE FIVE ARE FREE; THREE NEEDED AN ADAPTER ──────────────────────────────────────────
 *
 * `exportDocument` takes a `VecDocument` and the worker never sends one — the geometry crosses as six
 * flat typed arrays (`worker/protocol.ts` explains why: "a million allocations to structured-clone on
 * every preview frame"). The rehydration is `./geometryToDocument.ts`, about sixty lines, and it is
 * the exact inverse of `trace.worker.serializeGeometry`; it is one pass over geometry that is already
 * computed, on a BUTTON PRESS rather than per slider tick, behind an `await import()` so a researcher
 * who never exports a PDF never downloads the code that writes one.
 *
 * It is worth saying plainly because the easy half looks like the whole job: "the three additional
 * writers exist and are unexposed — adding them is a table entry each plus a button" is true and is
 * the cheap half. Without the adapter the table entry and the button attach to nothing.
 *
 * ── WHY EACH FORMAT IS OFFERED AT ALL ─────────────────────────────────────────────────────────
 *
 *   - SVG is the only form that can still be edited, re-scaled or sent to a plotter afterwards, and
 *     it is what a line-art attachment is for.
 *   - PNG is the form anybody can open, print or drop into a slide with no vector tool at all.
 *   - PDF, EPS and DXF exist because the drawing leaves this application: a print shop that will not
 *     take an SVG takes EPS, a CNC or laser controller takes DXF R12 and nothing newer, and a PDF
 *     opens on every machine anybody will ever mail it to.
 *
 * ── ONLY SVG AND PNG MAY BE ATTACHED, AND THAT IS A DECISION WITH A CHECKABLE REASON ───────────
 *
 * `attachable` in the table is false for the three new ones, and the reason is one grep away:
 * `lib/media.inferMediaType` types a file by its MIME prefix, and `image/vnd.dxf` — which is what a
 * DXF's own media type is — `startsWith("image/")`. So a `.dxf` filed on a record arrives in the
 * archive typed **IMAGE**, and `MediaLightbox` renders anything typed IMAGE through an `<img>`
 * element. The result is a media tile and a lightbox showing a broken picture, for a file no browser
 * has ever been able to decode, on a record the Android client also reads.
 *
 * Re-check with `grep -n "startsWith(\"image/\")" frontend/lib/media.ts` and
 * `grep -n "kind === \"IMAGE\"" frontend/components/media/MediaLightbox.tsx`.
 *
 * EPS and PDF do not have that problem (`application/postscript` is DOCUMENT, `application/pdf` is
 * PDF) and are still download-only, for the plainer reason: they buy a researcher nothing they do not
 * already get by downloading them, and every extra file on a record is a file somebody has to open to
 * find out what it is. Flipping one boolean is the whole of the change if that judgement ever moves.
 *
 * ── WHAT NONE OF THE FIVE DOES ────────────────────────────────────────────────────────────────
 *
 * Choosing a format changes what the RESEARCHER holds. A DOWNLOAD never reaches the record at all,
 * and an attach lands wherever the host points `onAttach` — this file has no opinion about that and
 * makes no claim about what any downstream document does with the bytes.
 */

import {
  DEFAULT_DERIVED_SUFFIX,
  buildSvg,
  derivedFileName,
  shapePathData,
  type SvgInput,
  type SvgResult
} from "./geometryToSvg";

/** What a caller gets back: the file to attach, plus anything the researcher must be told. */
export interface ExportOutcome {
  readonly file: File;
  /** Non-null when the drawing was truncated or otherwise reduced. Show it. */
  readonly note: string | null;
}

export interface ExportRefusal {
  readonly reason: string;
}

export type ExportResult = ExportOutcome | ExportRefusal;

export function isExported(result: ExportResult): result is ExportOutcome {
  return "file" in result;
}

/**
 * The longest edge a rendered PNG may have.
 *
 * A trace of a 4096px photograph would otherwise produce a 4096px canvas, which is 67 MB of RGBA
 * before compression — on the handsets this runs on that is a `toBlob` that returns null and a
 * feature that fails with no explanation. 2048 is large enough that a printed plate at 300 dpi is
 * still about 17 cm across, and the vector formats beside it have no ceiling at all for anyone who
 * needs more.
 *
 * DECLARED HERE, ABOVE THE TABLE, BECAUSE THE TABLE QUOTES IT. The number a researcher reads on
 * screen and the number `exportPngFile` enforces are one constant — a cap written out in copy is a
 * second copy that goes stale, and the rule "a stated cap IS the enforced one" only holds if there is
 * one of them.
 */
export const PNG_MAX_EDGE_PX = 2048;

/**
 * The formats offered, in the order the panel lists them.
 *
 * ONE TABLE FOR EVERY SURFACE: the id the panel switches on, the words on the control, and the
 * sentence underneath. A format is exposed by adding a row here and nothing else — the "Attach as"
 * chips render from {@link ATTACHABLE_FORMATS} and the download buttons from this array, so a row
 * cannot exist without a control and a control cannot exist without a row. That bijection is what
 * `e2e/trace-export-formats-unit.spec.ts` asserts, and it is what was missing while three finished
 * writers sat in the engine with nothing on screen able to reach them.
 *
 * SVG LEADS because it is the only form that can still be edited, re-scaled or sent to a plotter
 * afterwards. The two attachable formats come first, so the chooser above and the download row below
 * list them in the same order.
 *
 * `engineFormat` names the member of `engine/exportFormats.ExportFormat` a row corresponds to. It is a
 * plain string rather than the enum value, so this table stays importable without pulling the engine
 * onto the page graph — that enum lives in a module which also imports the rasteriser and the PNG
 * encoder. The spec compares the two and fails if they ever disagree; same for `mime` and `extension`,
 * which are checked against `ExportOptions`' own answers rather than trusted.
 */
export const EXPORT_FORMATS = [
  {
    id: "svg",
    label: "SVG",
    engineFormat: "SVG",
    extension: "svg",
    mime: "image/svg+xml",
    attachable: true,
    /** The words on this format's take-away button. */
    download: "Download the trace (SVG)",
    hint: "The traced paths themselves. Scales to any size without ever going blocky, and opens in Illustrator, Inkscape and CorelDRAW."
  },
  {
    id: "png",
    label: "PNG",
    // Written by `canvas.toBlob` rather than by the engine's own PNG encoder — `exportFormats.ts`
    // states in its own header that the platform layer owns the pixel formats the browser already has
    // an encoder for. The row still names the engine format, because a researcher gets a PNG either
    // way and the spec's accounting is over FORMATS, not over which function wrote the bytes.
    engineFormat: "PNG",
    extension: "png",
    mime: "image/png",
    attachable: true,
    download: "Download the rendered image (PNG)",
    hint: `The drawing rendered as a picture, transparent wherever the drawing is not, up to ${PNG_MAX_EDGE_PX}px on its long edge. Opens anywhere and drops straight into a letter or a slide, but it is pixels — enlarge it and it goes soft.`
  },
  {
    id: "pdf",
    label: "PDF",
    engineFormat: "PDF",
    extension: "pdf",
    mime: "application/pdf",
    attachable: false,
    download: "Download a PDF to send on",
    hint: "Vector, and it opens on every machine you could mail it to without anybody installing anything. The one to attach to an email, or to hand to somebody who only needs to look at it."
  },
  {
    id: "dxf",
    label: "DXF",
    engineFormat: "DXF",
    extension: "dxf",
    mime: "image/vnd.dxf",
    attachable: false,
    download: "Download for a CAD or cutting machine (DXF)",
    hint: "The outlines as CAD geometry, for a laser cutter, a CNC router or a drafting package. It is DXF R12, which every controller reads: curves arrive as many short straight lines, and colour, fill and line thickness are not carried at all."
  },
  {
    id: "eps",
    label: "EPS",
    engineFormat: "EPS",
    extension: "eps",
    mime: "application/postscript",
    attachable: false,
    download: "Download for a print shop (EPS)",
    hint: "Vector PostScript, for a print shop or sign-cutting software that will not take an SVG. PostScript has no transparency, so anything part-see-through is flattened onto the background as the file is written."
  }
] as const;

export type ExportFormatEntry = (typeof EXPORT_FORMATS)[number];

export type ExportFormatId = ExportFormatEntry["id"];

/** The subset that may be FILED on the record. See the header: the other three are take-away only. */
export type AttachFormatId = Extract<ExportFormatEntry, { attachable: true }>["id"];

/** Everything {@link exportVectorFile} handles, and nothing else. */
export type VectorFormatId = Exclude<ExportFormatId, "svg" | "png">;

/**
 * The rows the "Attach as" chooser draws.
 *
 * DERIVED, NEVER A SECOND LIST. A hand-written array of the attachable ids is a second register of one
 * fact, and a second register goes stale silently — nothing fails, a format simply stops appearing in
 * one of the two places. Flipping `attachable` on a row is the whole of the change needed to move a
 * format between the two surfaces.
 */
export const ATTACHABLE_FORMATS: readonly Extract<ExportFormatEntry, { attachable: true }>[] =
  EXPORT_FORMATS.filter(
    (entry): entry is Extract<ExportFormatEntry, { attachable: true }> => entry.attachable
  );

/**
 * The engine formats this panel deliberately does NOT offer, and why.
 *
 * A LIST OF ABSENCES IS A STRANGE THING TO SHIP, AND IT IS THE POINT OF THIS ONE. Three finished
 * writers sat in `engine/exportFormats.ts` unreachable from any control for as long as this feature
 * existed upstream, and nothing anywhere said whether that was a decision or an oversight — which is
 * precisely why it read as an oversight to the audit that found it.
 * `e2e/trace-export-formats-unit.spec.ts` requires every member of `ExportFormat` to appear in
 * {@link EXPORT_FORMATS} or here, so the next writer added to the engine cannot go unexposed the same
 * silent way: somebody has to wire it up or write down why not.
 *
 * These are reasons, not apologies — every one of them would be a fair thing to change if a
 * researcher asked for it.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * WHAT ONE PHOTOGRAPH PRODUCES, AND THE SUFFIX EACH THING CARRIES
 *
 * The panel hands ONE plate to the host and hands the researcher a copy of the drawing in any of
 * {@link EXPORT_FORMATS} on their own device. Every one of them comes out of the SAME geometry through
 * the SAME three converters — `buildSvg`, `paintGeometry` and `documentFrom` — on purpose: a download
 * drawn by different code from the file the record gets is a download that can disagree with the
 * archive, and the disagreement would surface as "the drawing I emailed is not the drawing in the
 * record".
 *
 * SO THE ONLY THING THAT DISTINGUISHES THEM IS THE NAME, WHICH MAKES THE NAME LOAD-BEARING. There are
 * two words in play rather than one per format, because what a suffix has to separate is a RASTER of
 * the drawing from the DRAWING — two files that would otherwise both be `photo-line-art.png`. The
 * vector downloads share one word and are told apart by their extension, which is what a researcher
 * reads a file by anyway:
 *
 *   ATTACH_SUFFIX ("line-art")  the plate handed to `onAttach`.
 *   TRACE_SUFFIX ("line-art")   every downloaded VECTOR form — `.svg`, `.pdf`, `.dxf`, `.eps`.
 *                               Deliberately the same word as the attach: the SVG is byte-for-byte
 *                               the file the host receives, and giving the copy on the researcher's
 *                               laptop a different name would invite the belief that it is a
 *                               different drawing. The other three are that same drawing written out
 *                               for a different machine, so they say so by sharing the word. Two
 *                               constants rather than one alias because they answer to different
 *                               questions — if the archive ever renames its plate, the download must
 *                               not silently follow.
 *   RENDER_SUFFIX ("traced")    the downloaded RENDERED raster. A different word, because a PNG
 *                               named "-line-art.png" is exactly what an attached PNG is called.
 * ──────────────────────────────────────────────────────────────────────────── */

export const ATTACH_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const TRACE_SUFFIX = DEFAULT_DERIVED_SUFFIX;
export const RENDER_SUFFIX = "traced";

/** Per-call naming, for the callers that are not the attach. See the block above. */
export interface ExportNaming {
  /** The word between the image's stem and the extension. Defaults to {@link ATTACH_SUFFIX}. */
  readonly suffix?: string;
}

/* ────────────────────────────────────────────────────────────────────────────
 * SVG
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * @returns an `image/svg+xml` file.
 *
 * The MIME type matters beyond tidiness: it is what `lib/media.inferMediaType` reads to decide the
 * archive's `MediaType`, and it is what any blur or resolution check would have to exclude — a
 * Laplacian variance measured on a rasterised vector says nothing about the photograph anybody took.
 * Declaring the type honestly is what lets those rules do their job without a special case.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * PDF, EPS and DXF — the engine's own writers, reached through one adapter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence a researcher is shown when the writer for a format could not be fetched.
 *
 * WORDED FOR THE ONE CAUSE THAT ACTUALLY HAPPENS. `traceRuntime`'s `ENGINE_UNAVAILABLE` covers the
 * same failure for the tracer and its comment names the case: a field hotspot drops one chunk request
 * and the raw error is "Failed to fetch dynamically imported module: https://…/chunk-a91f2c.js" —
 * true, useless, and not a sentence anybody wrote for a reader. This one adds the part that is only
 * true here: the SVG is already on the page and needs no chunk at all, so there is a way forward that
 * does not depend on the connection coming back.
 */
const WRITER_UNAVAILABLE =
  "That format’s writer could not be loaded — check your connection and reload the page. The SVG " +
  "download needs nothing extra and works either way.";

/**
 * Write the traced geometry as a PDF, an EPS or a DXF.
 *
 * ONE FUNCTION FOR THREE FORMATS, because the three differ only in which writer is called: the
 * rehydration, the cap, the naming and the refusal are identical, and three copies of them would be
 * three chances for one format to drift. See `geometryToDocument.ts` for why a rehydration is needed
 * at all — the worker sends flat typed arrays and every one of these writers takes a `VecDocument`.
 *
 * BOTH IMPORTS ARE DYNAMIC, AND THAT IS THE RULE RATHER THAN A TUNING CHOICE. `TracePanel.tsx`'s
 * property 3 is that the engine is not in the page's bundle until somebody traces something, and
 * `lib/trace/README.md` §4 publishes the measured cost of breaking it. Neither import is at the top of
 * this file, so a researcher who only ever attaches an SVG never downloads a line of
 * `engine/path.ts`. The per-format `import()` is kept separate from the adapter's on purpose:
 * whoever exports a DXF pays for the DXF writer and not for the PDF one.
 *
 * A FAILED IMPORT IS A REFUSAL, NOT AN EXCEPTION. `TracePanel` prints an {@link ExportRefusal} in the
 * red alert beside the buttons and keeps the panel open with the trace intact; letting the rejection
 * escape would surface as "That file could not be made: Failed to fetch dynamically imported
 * module…" through the catch-all, which is the message this feature already decided once that nobody
 * should be shown.
 *
 * PROVENANCE REACHES TWO OF THE THREE, AND THE THIRD IS SAID OUT LOUD ON SCREEN. `writePdf` puts the
 * note in the document's `/Title` and `writeEps` in its `%%Title:` comment, so a file mailed on can
 * still say what made it and from what — the property `buildSvg` provides through an XML comment.
 * **DXF R12 has no such channel**, and `writeDxf` accordingly takes no metadata argument at all: a
 * downloaded `.dxf` records nothing about how it was traced. That is skipped work, so it is stated
 * beside the button rather than quietly tolerated — exactly as the same gap already is for the PNG.
 *
 * @returns the file, or a refusal a researcher can act on.
 */
export async function exportVectorFile(
  format: VectorFormatId,
  input: SvgInput,
  sourceName: string,
  provenanceNote?: string,
  naming: ExportNaming = {}
): Promise<ExportResult> {
  const entry = EXPORT_FORMATS.find((candidate) => candidate.id === format);
  // Unreachable through the panel, which only ever passes an id it read off the table. Answered
  // rather than thrown, because the alternative is a caller holding a stale id crashing a render.
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
      // There is no metadata argument to pass. See the header: the gap is stated on screen instead.
      bytes = writeDxf(built.document);
    }

    const name = derivedFileName(sourceName, entry.extension, naming.suffix ?? ATTACH_SUFFIX);
    /*
      COPIED INTO A PLAIN `ArrayBuffer` RATHER THAN HANDED STRAIGHT TO `File`, and the reason is the
      type rather than the bytes: a writer returns `Uint8Array<ArrayBufferLike>`, and `BlobPart` wants
      `ArrayBufferView<ArrayBuffer>` — `ArrayBufferLike` also covers `SharedArrayBuffer`, which a Blob
      cannot take. The alternative is `as BlobPart`, which is a cast that goes on compiling after the
      lib types move under it. One copy of a file-sized buffer, once, on a button press.
    */
    const buffer = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(buffer).set(bytes);
    return {
      file: new File([buffer], name, { type: entry.mime }),
      // The SVG's own sentence, shared through `truncationNoteFor`, so a drawing that met the ceiling
      // reports it identically whichever format was asked for.
      note: built.truncationNote
    };
  } catch (error) {
    // A dropped chunk and a writer that threw on real geometry are different faults and must not read
    // as one sentence — the first is worth retrying on a better connection and the second never is.
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

/* ────────────────────────────────────────────────────────────────────────────
 * PNG
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Render the traced paths to a PNG.
 *
 * WHY `Path2D` AND NOT A RASTERISED SVG STRING. The obvious route — put the SVG in an `<img>` and
 * draw it — is a network-shaped operation in disguise: an SVG loaded into an image element is a
 * separate document fetch, it is tainted in some browsers, and it silently drops external references.
 * `Path2D` accepts the same path data directly, draws synchronously, and cannot reach anything.
 * The whole render therefore stays on this device, which is this feature's requirement and not merely
 * a preference.
 *
 * It runs on the MAIN thread, unlike the trace itself, and that is a deliberate difference rather
 * than an oversight: this is one pass of the browser's own C++ path rasteriser over geometry that is
 * already computed, measured in tens of milliseconds, and it happens once when a button is pressed —
 * not per slider tick. The engine runs in a worker because `Pipeline.run` is seconds of TypeScript;
 * this is not that.
 */
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
    return { reason: "This browser would not give the page a drawing surface, so a PNG cannot be made here. The SVG can." };
  }
  const context = canvas.getContext("2d") as
    | CanvasRenderingContext2D
    | OffscreenCanvasRenderingContext2D
    | null;
  if (context === null) {
    return { reason: "This browser would not give the page a drawing surface, so a PNG cannot be made here. The SVG can." };
  }

  paintGeometry(context, input, scale);

  const blob = await canvasToBlob(canvas);
  if (blob === null) {
    return {
      reason:
        "The drawing was traced but this browser could not turn it into a PNG — usually because the " +
        "image is very large. Attach the SVG instead, or lower “Trace resolution” and try again."
    };
  }
  const name = derivedFileName(sourceName, "png", naming.suffix ?? ATTACH_SUFFIX);
  const note =
    scale < 1
      ? `The PNG is ${width}x${height}, reduced from ${Math.round(sourceWidth)}x${Math.round(sourceHeight)} so it stays printable on a phone. The SVG beside it has no such limit.`
      : null;
  return { file: new File([blob], name, { type: "image/png" }), note };
}

/**
 * Paint a traced document onto a 2D context, already scaled.
 *
 * SHARED BY THE LIVE PREVIEW AND THE PNG EXPORT, WHICH IS THE WHOLE REASON IT IS A FUNCTION. A
 * preview drawn by different code from the file that gets attached is a preview that can lie, and it
 * lies in the one direction nobody checks — the picture on screen looks right and the file does not.
 * One painter means the thing a researcher approved is the thing the record receives.
 *
 * Scaling the CONTEXT rather than the coordinates keeps stroke widths proportional, which is the
 * difference between a reduced drawing and a reduced drawing whose hairlines stayed the same width.
 *
 * TRANSLUCENCY IS THE PLACE THIS HAS ALREADY DIVERGED ONCE — see the note at the fill and stroke
 * passes. A colour's alpha byte and `style.opacity` are two separate multiplications in the SVG the
 * writer emits, so they are two separate multiplications here.
 */
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
    // THE COLOUR'S ALPHA IS MULTIPLIED IN, PER PASS, BECAUSE THE SVG WRITER DOES EXACTLY THAT.
    // `geometryToSvg.styleAttrs` emits `fill-opacity` / `stroke-opacity` from the colour's own alpha
    // byte AND `opacity` from `style.opacity`, and SVG multiplies the two. Setting `globalAlpha` from
    // `style.opacity` alone therefore painted a translucent stroke opaque — on screen and in the
    // attached PNG — while the attached SVG showed it translucent, which is the one divergence
    // between the painter and the writer this shared function exists to prevent. The background path
    // above already did it this way, which is what made the omission look accidental rather than
    // meant. (One approximation remains and is not fixable here: SVG's `opacity` composites the whole
    // element, so a half-transparent path whose own fill and stroke overlap does not darken at the seam,
    // while two canvas passes do. Today's geometry never sets both on one shape.)
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

/** The alpha byte of a packed ARGB colour, as 0..1. Multiplied into `globalAlpha` — see `paintGeometry`. */
function alphaOf(argb: number): number {
  return ((argb >>> 24) & 0xff) / 255;
}

/**
 * Packed ARGB to a CSS colour, RGB only.
 *
 * The alpha is deliberately NOT spelled here: it is multiplied into `globalAlpha` by the caller, per
 * fill and per stroke, so that one colour's translucency reaches the canvas the same way it reaches
 * the SVG. Spelling it in an `rgba()` here as well would apply it twice.
 */
function argbToCss(argb: number): string {
  const r = (argb >>> 16) & 0xff;
  const g = (argb >>> 8) & 0xff;
  const b = argb & 0xff;
  return `rgb(${r} ${g} ${b})`;
}

/**
 * A drawing surface, `OffscreenCanvas` where it exists and a detached `<canvas>` where it does not.
 *
 * EXPORTED FOR `comparisonPlates.ts`, WHICH IS THE ONLY OTHER PLACE THAT PAINTS THIS GEOMETRY. The
 * fallback exists because Safari carried `createImageBitmap` for several versions before
 * `OffscreenCanvas` (`decodeToPixels.ts` names the same asymmetry), and a second copy of that
 * knowledge is a second thing to update the next time a browser moves. Same argument as
 * `paintGeometry`: one painter, one surface, one opinion.
 */
export function createCanvas(width: number, height: number): HTMLCanvasElement | OffscreenCanvas | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

/** PNG bytes off either surface kind, or null when the browser refused. Exported for the same reason. */
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
