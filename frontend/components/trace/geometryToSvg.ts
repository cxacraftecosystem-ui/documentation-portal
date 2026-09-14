/**
 * The worker's answer, turned into an SVG file.
 *
 * WHY THIS IS NOT `engine/svgWriter.ts`. The engine has a perfectly good SVG writer and this is not a
 * complaint about it — but `SvgWriter.write` takes a `VecDocument`, and a `VecDocument` is what the
 * worker deliberately does NOT send. `worker/protocol.ts` explains the choice in its own header: a
 * 50,000-path trace is "roughly a million coordinates … a million allocations to structured-clone on
 * every preview frame", so the geometry crosses as six flat typed arrays instead. Using the engine's
 * writer would mean rehydrating those arrays back into `VecPath` objects through `engine/path.ts`
 * (22,982 bytes of source) on the MAIN thread, allocating the million objects the protocol exists to
 * avoid, and pulling a second engine module onto the page graph — all to produce a string that the
 * flat arrays already contain in order.
 *
 * So this walks the arrays and writes the string. It is about a hundred lines, it allocates nothing
 * per shape but the string, it imports nothing at all, and it runs in Node — which is why
 * `e2e/trace-options-unit.spec.ts` can assert what it emits without a browser.
 *
 * IT IS STILL A COPY OF SOMEBODY ELSE'S CONVENTIONS, AND THE COPY IS FAITHFUL ON PURPOSE. Every
 * formatting decision below was read off `engine/svgWriter.ts` rather than invented, because two
 * writers that disagree about how to spell a colour produce two different files from one drawing:
 *
 *   - `colourHex` packs ARGB and emits `#rrggbb`, with alpha split out into `fill-opacity` /
 *     `stroke-opacity` only when it is not 255.
 *   - `num` is fixed-point, strips trailing zeros, normalises `-0` to `0`, clamps to ±1e7, and
 *     **substitutes 0 for a non-finite value**. That last one is load-bearing and the upstream says
 *     why: "A single `NaN` or `Infinity` anywhere in an SVG attribute makes the renderer drop the
 *     element that carries it — silently, and only in some renderers … A wrong pixel is debuggable; a
 *     blank export is not."
 *   - A non-positive or non-finite canvas dimension falls back to 1 unit so the file still opens.
 *
 * If the engine's writer ever becomes reachable cheaply — a worker message that returns the string, a
 * `traceClient` export — prefer it and delete this. Two writers is one more than the right number.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The shapes this file reads
 *
 * Structurally identical to `worker/protocol.SerializedGeometry` and to `engine/path.VecStyle`, but
 * declared locally and STRUCTURALLY, so this module imports nothing and the spec can build a geometry
 * by hand. The enum-valued style fields are plain strings here for the same reason: the engine's
 * `FillRule`, `LineCap` and `LineJoin` are string enums whose values equal their names, so a string
 * comparison against them is exact.
 * ──────────────────────────────────────────────────────────────────────────── */

/** Segment kind codes, matching `worker/protocol.ts`. Two coordinates per code, four, or six. */
export const VERB_LINE = 0;
export const VERB_QUAD = 1;
export const VERB_CUBIC = 2;

export interface GeometryStyle {
  readonly stroke: number | null;
  readonly strokeWidth: number;
  readonly fill: number | null;
  readonly fillRule: string;
  readonly cap: string;
  readonly join: string;
  readonly miterLimit: number;
  readonly opacity: number;
}

export interface FlatGeometry {
  readonly coords: Float32Array;
  readonly verbs: Uint8Array;
  readonly verbStarts: Uint32Array;
  readonly coordStarts: Uint32Array;
  readonly closed: Uint8Array;
  readonly styleTable: readonly GeometryStyle[];
  readonly styleIndex: Uint32Array;
}

export interface SvgInput {
  readonly geometry: FlatGeometry;
  readonly width: number;
  readonly height: number;
  /** Packed ARGB, or null for a transparent document. */
  readonly background: number | null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Formatting — every rule here is `engine/svgWriter.ts`'s
 * ──────────────────────────────────────────────────────────────────────────── */

/** Beyond this a coordinate is meaningless and some renderers overflow their fixed-point maths. */
const MAX_COORD = 1e7;

function hex2(v: number): string {
  const s = (v & 0xff).toString(16);
  return s.length === 1 ? `0${s}` : s;
}

function colourHex(argb: number): string {
  return `#${hex2(argb >>> 16)}${hex2(argb >>> 8)}${hex2(argb)}`;
}

/** Fixed-point, locale-independent, and never non-finite. See the header. */
function num(v: number, precision: number): string {
  const finite = Number.isFinite(v) ? v : 0;
  const clamped = finite > MAX_COORD ? MAX_COORD : finite < -MAX_COORD ? -MAX_COORD : finite;
  let s = clamped.toFixed(precision);
  if (s.indexOf(".") >= 0) s = s.replace(/0+$/, "").replace(/\.$/, "");
  return s === "-0" ? "0" : s;
}

/**
 * A canvas dimension every writer can use, whatever the worker sent.
 *
 * EXPORTED FOR `geometryToDocument.ts`, WHICH FEEDS THE ENGINE'S OWN WRITERS. Those writers guard with
 * `Math.max(1, doc.width)`, and `Math.max(1, NaN)` is `NaN` — so a document whose size never became a
 * number reaches a PDF's `/MediaBox` as `0.0` and the file opens empty. One rule for "what counts as a
 * usable dimension" is what keeps the SVG this module writes and the PDF the engine writes agreeing
 * about the page they are drawing on.
 */
export function sanitizeDimension(v: number): number {
  if (!Number.isFinite(v) || v <= 0) return 1;
  return v > MAX_COORD ? MAX_COORD : v;
}

function capName(c: string): string {
  if (c === "ROUND") return "round";
  if (c === "SQUARE") return "square";
  return "butt";
}

function joinName(j: string): string {
  if (j === "ROUND") return "round";
  if (j === "BEVEL") return "bevel";
  return "miter";
}

function styleAttrs(style: GeometryStyle, precision: number): string {
  const parts: string[] = [];
  if (style.fill === null) {
    parts.push('fill="none"');
  } else {
    parts.push(`fill="${colourHex(style.fill)}"`);
    const a = (style.fill >>> 24) & 0xff;
    if (a !== 255) parts.push(`fill-opacity="${num(a / 255, 3)}"`);
    parts.push(`fill-rule="${style.fillRule === "NONZERO" ? "nonzero" : "evenodd"}"`);
  }
  if (style.stroke === null) {
    parts.push('stroke="none"');
  } else {
    parts.push(`stroke="${colourHex(style.stroke)}"`);
    const a = (style.stroke >>> 24) & 0xff;
    if (a !== 255) parts.push(`stroke-opacity="${num(a / 255, 3)}"`);
    parts.push(`stroke-width="${num(style.strokeWidth, precision)}"`);
    parts.push(`stroke-linecap="${capName(style.cap)}"`);
    parts.push(`stroke-linejoin="${joinName(style.join)}"`);
    if (style.join === "MITER" && style.miterLimit !== 4) {
      parts.push(`stroke-miterlimit="${num(style.miterLimit, precision)}"`);
    }
  }
  if (style.opacity !== 1) parts.push(`opacity="${num(style.opacity, 3)}"`);
  return parts.join(" ");
}

/* ────────────────────────────────────────────────────────────────────────────
 * The walk
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * @returns the `d` attribute for one shape, or the empty string when it has no start point.
 *
 * A shape's coordinate run BEGINS with its start point, and the verb list covers everything after it —
 * so the cursor starts two coordinates in and each verb consumes two, four or six more. A run that
 * cannot supply what its verbs claim is truncated rather than read past: a trace interrupted mid-post
 * is the one way that can happen, and half a drawing is a better answer than an exception in a
 * component's render.
 */
export function shapePathData(geometry: FlatGeometry, index: number, precision: number): string {
  const coordStart = geometry.coordStarts[index];
  const coordEnd = geometry.coordStarts[index + 1];
  if (coordEnd - coordStart < 2) return "";

  const parts: string[] = [
    `M${num(geometry.coords[coordStart], precision)} ${num(geometry.coords[coordStart + 1], precision)}`
  ];

  let cursor = coordStart + 2;
  const verbStart = geometry.verbStarts[index];
  const verbEnd = geometry.verbStarts[index + 1];
  for (let v = verbStart; v < verbEnd; v += 1) {
    const verb = geometry.verbs[v];
    const needed = verb === VERB_CUBIC ? 6 : verb === VERB_QUAD ? 4 : 2;
    if (cursor + needed > coordEnd) break;
    const n = (offset: number): string => num(geometry.coords[cursor + offset], precision);
    if (verb === VERB_CUBIC) {
      parts.push(`C${n(0)} ${n(1)} ${n(2)} ${n(3)} ${n(4)} ${n(5)}`);
    } else if (verb === VERB_QUAD) {
      parts.push(`Q${n(0)} ${n(1)} ${n(2)} ${n(3)}`);
    } else {
      parts.push(`L${n(0)} ${n(1)}`);
    }
    cursor += needed;
  }

  if (geometry.closed[index] === 1) parts.push("Z");
  return parts.join(" ");
}

export interface SvgOptions {
  /** Decimal places for coordinates. The engine's own default is 2 and there is no reason to differ. */
  readonly precision?: number;
  /**
   * A sentence written into the file as an XML comment.
   *
   * Not decoration. A vector file that turns up in a heritage submission six months from now should
   * be able to say what made it and from what, because the alternative is somebody assuming a
   * researcher drew it by hand. Nothing identifying goes in here — see {@link buildSvg}.
   */
  readonly provenanceNote?: string;
}

/** How many shapes one file may carry. See {@link buildSvg}. */
export const MAX_SHAPES_PER_FILE = 200000;

export interface SvgResult {
  readonly svg: string;
  readonly shapesWritten: number;
  /** Non-null when {@link MAX_SHAPES_PER_FILE} truncated the drawing. A sentence, ready to show. */
  readonly truncationNote: string | null;
}

/**
 * Write the whole document.
 *
 * THE CAP IS REPORTED, NEVER SILENT. This repository's most repeated bug class is a list that quietly
 * stops — indistinguishable from a place with no records — so a drawing that hits the shape ceiling
 * comes back with a sentence saying so and the caller is expected to put it on screen. The ceiling is
 * high enough that a real drawing will not meet it; a trace of a photograph of gravel will.
 *
 * NOTHING IDENTIFYING IS WRITTEN INTO THE FILE. No researcher name, no account, no workshop, no
 * coordinates, no timestamp beyond what the caller passes in `provenanceNote`. This file is uploaded
 * to a shared archive and handed on, and a comment naming the person who traced it would be an
 * identity leak by a path nobody would think to audit. The record already knows who attached it.
 */
export function buildSvg(input: SvgInput, options: SvgOptions = {}): SvgResult {
  const precision = options.precision ?? 2;
  const width = sanitizeDimension(input.width);
  const height = sanitizeDimension(input.height);
  const shapeCount = Math.max(0, input.geometry.styleIndex.length);
  const limit = Math.min(shapeCount, MAX_SHAPES_PER_FILE);

  const out: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<svg xmlns="http://www.w3.org/2000/svg" width="${num(width, precision)}" height="${num(height, precision)}" ` +
      `viewBox="0 0 ${num(width, precision)} ${num(height, precision)}">`
  ];

  if (options.provenanceNote) {
    // `--` cannot appear inside an XML comment, and a note that produced a malformed file would be a
    // worse outcome than a note that reads slightly oddly.
    out.push(`<!-- ${options.provenanceNote.replace(/-{2,}/g, "-")} -->`);
  }

  if (input.background !== null) {
    const alpha = (input.background >>> 24) & 0xff;
    const opacity = alpha === 255 ? "" : ` fill-opacity="${num(alpha / 255, 3)}"`;
    out.push(
      `<rect x="0" y="0" width="${num(width, precision)}" height="${num(height, precision)}" ` +
        `fill="${colourHex(input.background)}"${opacity}/>`
    );
  }

  for (let i = 0; i < limit; i += 1) {
    const d = shapePathData(input.geometry, i, precision);
    if (d.length === 0) continue;
    const style = input.geometry.styleTable[input.geometry.styleIndex[i]];
    // A style index pointing outside the table means the message was built by something that is not
    // the worker. Drawing the path in the engine's own default black is better than dropping it.
    const attrs = style ? styleAttrs(style, precision) : 'fill="none" stroke="#000000" stroke-width="1.5"';
    out.push(`<path d="${d}" ${attrs}/>`);
  }

  out.push("</svg>");

  return {
    svg: out.join("\n"),
    shapesWritten: limit,
    truncationNote: truncationNoteFor(shapeCount, limit)
  };
}

/**
 * The one sentence a researcher is shown when {@link MAX_SHAPES_PER_FILE} cut a drawing short.
 *
 * ONE SENTENCE, NOT ONE PER FORMAT. Every writer this feature offers caps at the same ceiling, and
 * five formats each phrasing the cut differently would read as five different faults — so
 * `geometryToDocument.ts` calls this rather than writing its own. A list that quietly stops is
 * indistinguishable from a place with no records, and the same is true of a drawing that quietly
 * stops.
 *
 * @returns the sentence, or null when nothing was dropped.
 */
export function truncationNoteFor(shapeCount: number, shapesWritten: number): string | null {
  if (shapeCount <= shapesWritten) return null;
  return (
    `This drawing has ${shapeCount.toLocaleString("en-IN")} separate paths and the file holds the ` +
    `first ${shapesWritten.toLocaleString("en-IN")}. Raise “Minimum speck” or “Simplify” and trace again ` +
    "to get a drawing that fits."
  );
}

/**
 * The suffix a derived file carries when the caller does not name one.
 *
 * Exported so the two things that must agree — the name a file is given and the name a test expects —
 * read it from one place. `traceExport.ts` declares the others beside it.
 */
export const DEFAULT_DERIVED_SUFFIX = "line-art";

/**
 * The stem used when the source image has no usable name of its own.
 *
 * A CONSTANT RATHER THAN A LITERAL IN THE `??`, because it is the one part of a derived name that is
 * NOT the researcher's own file talking. A blob fetched from a URL, a paste, a camera stream — all
 * three reach this function with nothing to build a stem from, and `"image.svg"` in a downloads folder
 * beside four others is a name that identifies nothing. Kept deliberately generic all the same: the
 * only honest thing to say about a file whose source had no name is what kind of thing it is.
 */
export const UNNAMED_SOURCE_STEM = "image";

/**
 * @returns a name for the derived file, built from the source image's own.
 *
 * The source name is kept and a suffix added, rather than a fresh name being invented, because the
 * two files sit in one record and a reviewer has to be able to tell which photograph a plate came
 * from.
 *
 * ── AND THE HOST WILL RENAME IT AGAIN, WHICH DOES NOT MAKE THIS POINTLESS ───────────────────────
 *
 * `lib/media.ts` renames every upload through `renameMediaFile` into this archive's one scheme
 * (`{RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}`) — its own header says why: "no two
 * capture screens can drift into naming the same kind of file differently". So the name this function
 * produces is what a DOWNLOAD lands under, and what the attach hands the host BEFORE it renames.
 * Both matter: the download is the copy that leaves the application entirely, and the host's rename
 * needs a sane extension and a sane stem to work from.
 *
 * ── WHY THE SUFFIX IS A PARAMETER, AND WHY IT IS NOT A SECOND FUNCTION ──────────────────────────
 *
 * The panel produces THREE kinds of thing from one photograph: the line art it attaches, the same
 * vector geometry downloaded to the device, and a rendered raster downloaded to the device. Two of
 * those are a `.svg` and a `.png` of the drawing and the third is a `.png` of the drawing, so
 * "-line-art.png" would name both the attachable plate and the downloaded render — one filename for
 * two artefacts, in a downloads folder where the record's own provenance is not there to tell them
 * apart. A parameter keeps ONE naming rule (stem, one suffix, extension) applying to all three; a
 * second helper would be a second rule.
 *
 * The suffix goes through the same deny-list as the stem. It is a constant at every call site today,
 * but a name-building function that trusts one of its inputs and sanitises the other is a function
 * whose next caller has to know which is which.
 */
export function derivedFileName(
  sourceName: string,
  extension: string,
  suffix: string = DEFAULT_DERIVED_SUFFIX
): string {
  const trimmed = sourceName.replace(/\.[^./\\]+$/, "").trim();
  const base = trimmed.length > 0 ? trimmed : UNNAMED_SOURCE_STEM;
  // Windows, S3 keys and a .docx relationship id all dislike a different subset of the punctuation a
  // phone gallery will happily put in a filename, so the intersection is what survives.
  const safe = base.replace(/[^\w\-. ]+/g, "_").slice(0, 80);
  const tag = suffix.replace(/[^\w\-. ]+/g, "_").trim();
  return tag.length > 0 ? `${safe}-${tag}.${extension}` : `${safe}.${extension}`;
}
