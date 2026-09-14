/**
 * A chosen image, decoded to the RGBA the tracing engine takes.
 *
 * WHY THE DECODER IS HERE AND NOT IN `lib/trace/`. `traceClient.ts`'s header refuses the job in as
 * many words: "No React, no component, no canvas, no `File`. Decoding a photograph into pixels is the
 * caller's job … and a second decoder here would be a second opinion about EXIF orientation." This
 * file is the caller doing its job.
 *
 * AND IT KEEPS THAT ONE OPINION. The decode below is a bare `createImageBitmap(file)` with NO options
 * at all, which is what makes the orientation a traced drawing is drawn at the orientation the
 * browser itself reads out of the file's EXIF. A second call with `imageOrientation: "none"` anywhere
 * in this application would produce a trace rotated relative to the photograph beside it in the same
 * record, and nothing on either would say which of the two had turned.
 *
 * WHY A CAP AT ALL, WHEN THE ENGINE ALREADY HAS ONE. `preprocess.workingLongEdge` caps the resolution
 * the trace RUNS at, and the engine downsamples to it internally — but that happens after the pixels
 * have crossed into the worker. A 12 MP phone photograph is 48 MB of RGBA, and the transfer, the
 * clone `transferableFrom` makes to keep the caller's copy intact, and the decode buffer itself are
 * three copies of it on a handset with 2 GB of RAM. Decoding to a bounded edge first costs nothing in
 * output quality — the trace was never going to run above `workingLongEdge` anyway — and is the
 * difference between a slow tab and a killed one.
 */

/*
  ONE DEFINITION OF THE FALLBACK STEM, IMPORTED RATHER THAN RETYPED. `nameFromUrl` below and
  `derivedFileName` in `geometryToSvg.ts` both have to answer "the source had no usable name" and
  they have to answer it the same way, or a downloaded file is named from one rule and the record's
  copy from another. `geometryToSvg.ts` imports nothing at all, so this costs no graph.
*/
import { UNNAMED_SOURCE_STEM } from "./geometryToSvg";

/**
 * The longest edge a decode is allowed to produce.
 *
 * 4096 rather than a smaller number because it is exactly the ceiling `traceParamTable.ts` puts on
 * "Trace resolution": decoding below that would silently cap a slider the researcher can still see at
 * its top end, which is the kind of disagreement between two limits that nobody finds for a year.
 * Raising the slider's ceiling means raising this in the same edit.
 */
export const DECODE_MAX_EDGE_PX = 4096;

/** Pixels in `ImageData` byte order, plus the size the decode actually produced. */
export interface DecodedPixels {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  /** The file's own pixel size, before any capping. Stated on screen when the two differ. */
  readonly sourceWidth: number;
  readonly sourceHeight: number;
  readonly decodeMs: number;
}

/** Why a decode did not happen, in a sentence written to be shown to a researcher. */
export interface DecodeRefusal {
  readonly reason: string;
}

export type DecodeOutcome = DecodedPixels | DecodeRefusal;

/** Narrowing helper, so a caller reads `if (isDecoded(outcome))` rather than `"data" in outcome`. */
export function isDecoded(outcome: DecodeOutcome): outcome is DecodedPixels {
  return "data" in outcome;
}

/**
 * The image kinds worth naming to a researcher.
 *
 * SVG IS ABSENT AND THAT IS NOT AN OVERSIGHT. An SVG is already vector art, so tracing one is a round
 * trip that can only lose — rasterise, threshold, re-fit curves — and it would arrive at the panel
 * looking like a feature rather than like the mistake it is. A researcher who has an SVG already
 * should attach it through the ordinary media picker, which this panel never replaces.
 *
 * This list is what the panel NAMES; the browser decides what it can actually read. A phone camera
 * roll offers formats (HEIC on iOS, AVIF on newer Android) that a browser may or may not decode, and
 * refusing a file the browser could in fact have opened is worse than a decode that fails with a
 * sentence.
 */
export const TRACEABLE_IMAGE_TYPES = "JPEG, PNG, WebP, GIF, BMP, HEIC and AVIF";

/** The accept attribute, kept beside the sentence above so the two cannot drift apart. */
export const TRACEABLE_ACCEPT = "image/*";

/**
 * Draw a bitmap at the given size and read the pixels back.
 *
 * `OffscreenCanvas` where it exists, a detached `<canvas>` where it does not — Safari carried
 * `createImageBitmap` for several versions before `OffscreenCanvas`, so the pair is not redundant.
 * The fallback element is never attached to the document, so it forces no layout and paints nothing.
 */
function readPixels(bitmap: ImageBitmap, width: number, height: number): Uint8ClampedArray | null {
  if (typeof OffscreenCanvas !== "undefined") {
    const canvas = new OffscreenCanvas(width, height);
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, width, height);
    return context.getImageData(0, 0, width, height).data;
  }
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) return null;
  context.drawImage(bitmap, 0, 0, width, height);
  return context.getImageData(0, 0, width, height).data;
}

/**
 * @returns the working size for a source of this size — the source itself when it is already inside
 *   the cap, so the common case of a scanned A4 at 2480x3508 is not resampled for nothing.
 */
export function workingSizeFor(
  width: number,
  height: number,
  maxEdge: number = DECODE_MAX_EDGE_PX
): { width: number; height: number } {
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  if (scale >= 1) return { width, height };
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

/**
 * Decode `file` to bounded RGBA, or explain why not.
 *
 * FAILURE IS A SENTENCE, NEVER SILENCE. A background quality check nobody asked for is right to fail
 * silently; this is not that. The researcher pressed a button meaning "trace this", so a refusal has
 * to say what happened and what to do instead. The image itself is fine and is untouched either way.
 *
 * The full-size bitmap is `close()`d the instant it has been read, and the resize is a second
 * `createImageBitmap` so it happens off the main thread in every engine that has one — holding a
 * 4000x3000 decode and its resized copy at once is how a cheap phone kills the tab.
 */
export async function decodeToPixels(
  file: Blob,
  maxEdge: number = DECODE_MAX_EDGE_PX
): Promise<DecodeOutcome> {
  if (typeof createImageBitmap === "undefined") {
    return {
      reason:
        "This browser cannot decode images in the page, so a drawing cannot be traced here. " +
        "The image itself is unaffected — attach it as it is, or trace it on a handset."
    };
  }

  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const sourceWidth = full.width;
    const sourceHeight = full.height;
    if (sourceWidth < 1 || sourceHeight < 1) {
      return { reason: "That file decoded to an empty image. Try another photograph." };
    }

    const working = workingSizeFor(sourceWidth, sourceHeight, maxEdge);
    scaled =
      working.width === sourceWidth && working.height === sourceHeight
        ? null
        : await createImageBitmap(full, { resizeWidth: working.width, resizeHeight: working.height });

    const data = readPixels(scaled ?? full, working.width, working.height);
    if (data === null) {
      return {
        reason:
          "This browser would not give the page a drawing surface to read the image back from, " +
          "so it cannot be traced here."
      };
    }

    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    return {
      data,
      width: working.width,
      height: working.height,
      sourceWidth,
      sourceHeight,
      decodeMs: now - startedAt
    };
  } catch {
    // A format this browser will not decode — HEIC on a desktop Chrome, most often — or a truncated
    // file from an interrupted transfer. The two want the same answer from the researcher's side.
    return {
      reason:
        `This browser could not read that image. It reads ${TRACEABLE_IMAGE_TYPES} where the ` +
        "platform supports them; an image in another format can still be attached as it is."
    };
  } finally {
    full?.close();
    scaled?.close();
  }
}

/**
 * Fetch an image the host named by URL, so it can be decoded like any chosen file.
 *
 * ── WHY THE PANEL TAKES A URL AT ALL, WHEN IT ALREADY TAKES A `Blob` ───────────────────────────
 *
 * Because in this application the thing being traced is usually ALREADY ON THE RECORD. A researcher
 * attaches a photograph of a block, a tool, a motif; it uploads; and the decision to trace it comes
 * later, looking at the record, where what is in hand is a `MediaFile` and its URL rather than the
 * `File` object the browser held for a moment during the upload. Making the host re-pick the file
 * from disk to trace something it is already showing would be asking for the same photograph twice.
 *
 * ── THE TWO FAILURES THAT ARE WORTH SEPARATE SENTENCES ────────────────────────────────────────
 *
 * A fetch of a stored object fails in two ways that mean opposite things to the person reading. The
 * network is down or the object has expired — try again, or later. Or the response arrived and the
 * browser would not let the page READ it, which on a cross-origin object store is a CORS header the
 * deployment is missing and no amount of retrying will fix. They are told apart by the status: a
 * CORS block surfaces as a thrown `TypeError` with no response at all, while an expired signature
 * comes back as a real 403.
 *
 * NOTHING IS CACHED AND NOTHING IS WRITTEN. The blob is handed to {@link decodeToPixels} and dropped;
 * the original object in storage is not touched, re-uploaded or re-encoded.
 */
export async function fetchImageBlob(url: string): Promise<Blob | DecodeRefusal> {
  let response: Response;
  try {
    response = await fetch(url);
  } catch {
    return {
      reason:
        "The image could not be fetched from storage, so there is nothing to trace yet. On a working " +
        "connection this usually means the storage bucket is not returning the headers a browser needs " +
        "to read a file from another origin; the image itself is unaffected."
    };
  }
  if (!response.ok) {
    return {
      reason:
        `The image could not be fetched from storage (HTTP ${response.status}). Reload the record and ` +
        "try again — a stored link goes stale after a while, and reloading mints a fresh one."
    };
  }
  return await response.blob();
}

/**
 * A name for a file fetched from a URL, when the host did not pass one.
 *
 * THE LAST PATH SEGMENT, WITHOUT THE QUERY STRING, and a plain fallback when there is nothing usable.
 * A presigned storage URL carries a signature, an expiry and a content-disposition in its query, and
 * none of that belongs in a downloads folder. This is deliberately not clever: the honest answer when
 * a URL has no filename in it is a generic stem, and the host that knows the real name has
 * `TracePanel`'s own `imageName` prop to say so with.
 *
 * A `try` because `new URL` throws on a relative path, which a host may legitimately pass — the app is
 * the origin then, and a plain string split does the same job.
 */
export function nameFromUrl(url: string): string {
  let path = url;
  try {
    path = new URL(url, "http://localhost").pathname;
  } catch {
    path = url.split(/[?#]/)[0] ?? url;
  }
  const segment = path.split("/").filter(Boolean).pop() ?? "";
  const decoded = (() => {
    try {
      return decodeURIComponent(segment);
    } catch {
      // A segment that is not valid percent-encoding is still a usable name; `decodeURIComponent`
      // throws on a bare "%", and losing a filename over a literal percent sign would be absurd.
      return segment;
    }
  })();
  return decoded.trim().length > 0 ? decoded : UNNAMED_SOURCE_STEM;
}
