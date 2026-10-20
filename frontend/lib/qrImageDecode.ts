/**
 * The browser half of reading a QR code: a File, a Blob or a live video frame in, a decoded string
 * or a sentence explaining why not out.
 *
 * ── WHY THIS FILE EXISTS SEPARATELY FROM `lib/qrDecode.ts` ───────────────────────────────────
 *
 * `qrDecode.ts` is pure — no DOM, no fetch, no clock — so it can be driven from a plain array of
 * numbers and tested without a browser, and so it could be ported to Kotlin without dragging any of
 * the web's decoding machinery with it. That purity is only worth anything if something else owns
 * the impure part, and this is it: decoding a file to pixels, bounding how many of them there are,
 * cropping, and closing bitmaps. Everything below the API boundary is `qrDecode`'s; everything about
 * canvases and `ImageBitmap` is here and nowhere else.
 *
 * ── THE RESOLUTION LADDER, WHICH IS THE WHOLE REASON THIS IS MORE THAN TEN LINES ─────────────
 *
 * A QR decoder needs roughly three pixels per module to sample reliably and about two to detect the
 * symbol at all. Those two numbers pull in opposite directions on a real photograph:
 *
 *  - A 12-megapixel handset photo is ~4000x3000. Binarising and scanning twelve million pixels on
 *    the main thread of a cheap field handset is a visible freeze, so the work MUST be bounded.
 *  - But a designer photographs a card lying on a table from standing height, and the code is a
 *    twentieth of the frame — 200px across at full size, 70px at a bounded 1400px working copy,
 *    which is two pixels per module for a version 4 symbol. Bounding the work throws the code away.
 *
 * Neither "always work at full size" nor "always work small" is right, so this does what a person
 * does: LOOK AT THE WHOLE PICTURE FIRST, THEN LOOK CLOSER AT THE PART THAT HAD THE CODE IN IT.
 *
 *  1. Decode a working copy bounded to {@link DETECT_EDGE_PX}. A code that fills a reasonable part
 *     of the frame — the overwhelmingly common case — is read here and nothing else runs.
 *  2. If that failed but {@link QrDecodeResult.region} came back, the symbol was FOUND and only the
 *     reading failed. Re-cut that rectangle out of the FULL-RESOLUTION original and decode again.
 *     The region is expressed in fractions of the plane precisely so it survives the change of
 *     scale — see `qrDecode.ts`'s `QrRegion`. This is the step that reads a code occupying 3% of a
 *     large photograph, and it is cheap: the crop is small, so the second decode is smaller work
 *     than the first.
 *  3. If nothing was found at all AND the original is much bigger than the working copy, try once
 *     more at {@link DEEP_EDGE_PX}. A symbol under about two pixels per module is not merely hard to
 *     read, it is invisible to the finder-pattern scan — there is no region to re-cut because
 *     nothing was located — and this is the only step that can rescue it.
 *
 * The ladder stops at the first success and is bounded at every rung, so the worst case is three
 * decodes of bounded images rather than one decode of an unbounded one.
 *
 * ── THE NATIVE DETECTOR IS A FAST PATH AND NEVER A REQUIREMENT ───────────────────────────────
 *
 * `BarcodeDetector` is tried first where it exists, because where it exists it is hardware-backed
 * and better than anything shipped in JavaScript. It is NEVER required, and that distinction is the
 * entire point of this module. `lib/identityCardLocal.ts` records the probe, run on this machine on
 * 2026-08-09: `BarcodeDetector` is ABSENT in Chrome 151, Edge 151 and Playwright's Chromium on
 * Windows, with and without the experimental-web-platform flag. The machines this client runs on in
 * a district office are Windows laptops. Gating the feature on the native detector — which is what
 * `WorkshopCodeScanner` used to do, for both the camera AND the upload — did not degrade the
 * feature on those machines, it REMOVED it, and left a component whose only working input was the
 * keyboard.
 *
 * So: native where present, bundled everywhere, and the caller never has to ask which ran.
 *
 * ── EVERY REFUSAL IS A SENTENCE THAT NAMES THE NEXT ACTION ───────────────────────────────────
 *
 * A silent no-op is the worst outcome this module can produce: the designer presses Upload again
 * with the same file, then decides the feature is broken. `qrDecode.ts` already writes one sentence
 * per distinguishable cause and this file adds the two it cannot know about — a file this browser
 * cannot decode at all (almost always an iPhone HEIC, which is NOT an unreadable code and must never
 * be reported as one), and a browser with no canvas to decode into.
 */

import { toGreyPlane, type GreyPlane } from "@/lib/imageQuality";
import { decodeQrFromGrey, type QrDecodeRefusal, type QrDecodeResult, type QrRegion } from "@/lib/qrDecode";

/* ────────────────────────────────────────────────────────────────────────────
 * What this module answers
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Why a picture produced no code. {@link QrDecodeRefusal} plus the two causes that are about the
 * BROWSER rather than about the symbol, and that therefore lead to completely different advice.
 */
export type QrImageRefusal =
  | QrDecodeRefusal
  /** The browser could not turn the file into pixels at all — an unsupported format, or a corrupt file. */
  | "FILE_UNREADABLE"
  /** No `createImageBitmap`, or no 2D canvas. Nothing can be read from an image here at all. */
  | "NO_IMAGE_SUPPORT";

export type QrImageResult =
  | { ok: true; text: string; via: "native" | "bundled" }
  | { ok: false; reason: QrImageRefusal; message: string };

/* ────────────────────────────────────────────────────────────────────────────
 * The rungs of the ladder. See the file header for why there are three.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The long edge the first look at a whole picture is bounded to.
 *
 * 1400 rather than `imageQuality.WORK_EDGE_PX`'s 640, and the difference is not a preference. That
 * constant is calibrated for BLUR, which is a low-frequency measurement that survives aggressive
 * downscaling. A QR symbol is the opposite: it is the highest-frequency thing in the picture, and
 * every module lost to a downscale is lost permanently. At 640 a version 4 symbol filling a quarter
 * of the frame is 160px across — under 4 pixels per module, already marginal — and one filling a
 * twentieth is not detectable at all.
 */
const DETECT_EDGE_PX = 1400;

/**
 * The long edge the last-resort pass is bounded to, for an original much larger than that.
 *
 * Twice {@link DETECT_EDGE_PX}, which doubles the pixels per module and is the difference between a
 * symbol the finder scan can see and one it cannot. ~5.9 megapixels of arithmetic is a few hundred
 * milliseconds, which is affordable ONCE, on an upload the designer is already waiting on — and it
 * is not on the camera path, which never takes this rung.
 */
const DEEP_EDGE_PX = 2800;

/**
 * The long edge a re-cut region is scaled to.
 *
 * Smaller than {@link DETECT_EDGE_PX} on purpose: the crop is a small rectangle of a big photograph,
 * so at full resolution it is usually already under this and no downscale happens at all. The bound
 * is here only so that a "region" covering most of a 12-megapixel frame — which happens when the
 * detector finds a symbol spanning the picture — cannot smuggle the unbounded work back in.
 */
const RECUT_EDGE_PX = 1200;

/**
 * How much bigger a source must be than the working copy before rung 3 is worth taking.
 *
 * Below this the second look is the same look: rescaling a 1500px original to 2800 invents no
 * detail, it only costs time. The ladder must not spend hundreds of milliseconds re-deciding
 * something it already decided.
 */
const DEEP_PASS_MIN_SOURCE_PX = DETECT_EDGE_PX * 1.5;

/* ────────────────────────────────────────────────────────────────────────────
 * Canvas glue
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `OffscreenCanvas` where it exists, a detached `<canvas>` where it does not.
 *
 * The third copy of this in the repository (`lib/imageQuality.ts` and `lib/sketchRectify.ts` have
 * the others) and deliberately not a shared import: both of those are module-private, and widening
 * either module's public surface to serve this one would make two well-scoped files answerable to a
 * third. It is six lines against a stable browser API. What follows it is NOT a copy — neither of
 * those two can crop, and cropping is the whole mechanism behind rung 2 of the ladder.
 */
function canvasFor(width: number, height: number): OffscreenCanvas | HTMLCanvasElement | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

/** Draw a source rectangle into a destination-sized canvas and hand back its RGBA bytes. */
function drawToPixels(
  source: CanvasImageSource,
  sx: number,
  sy: number,
  sw: number,
  sh: number,
  dw: number,
  dh: number
): Uint8ClampedArray | null {
  const canvas = canvasFor(dw, dh);
  if (!canvas) return null;
  const context = canvas.getContext("2d", { willReadFrequently: true }) as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
    | null;
  if (!context) return null;
  // Smoothing ON, and "high" where the browser honours it. A nearest-neighbour downscale of a QR
  // drops whole module columns rather than averaging them, which does not blur the symbol — it
  // deletes parts of it, and the deletion is invisible in the result because what comes back still
  // looks like a QR code.
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  try {
    context.drawImage(source, sx, sy, sw, sh, 0, 0, dw, dh);
    return context.getImageData(0, 0, dw, dh).data;
  } catch {
    // A tainted canvas, a zero-sized source, or a video element with no frame yet.
    return null;
  }
}

/** A rectangle of the source, in source pixels. */
type Crop = { x: number; y: number; width: number; height: number };

/**
 * A bounded luma plane from part or all of an image source.
 *
 * `createImageBitmap`'s own resampler is preferred for the downscale — it runs off the main thread
 * in every engine that has it, and its `"high"` quality is a proper area filter, where a single
 * `drawImage` downscale of three times or more aliases badly. Where the browser ignores or refuses
 * the resize options (Safari has shipped several versions that accept the call and return the
 * original size), the returned bitmap is simply not the size that was asked for, and the canvas
 * path below rescales it. Both routes end in the same `toGreyPlane`.
 */
async function planeFrom(source: ImageBitmap | HTMLVideoElement, crop: Crop | null, maxEdge: number): Promise<GreyPlane | null> {
  const sourceWidth = "videoWidth" in source ? source.videoWidth : source.width;
  const sourceHeight = "videoHeight" in source ? source.videoHeight : source.height;
  const sx = Math.max(0, Math.floor(crop?.x ?? 0));
  const sy = Math.max(0, Math.floor(crop?.y ?? 0));
  const sw = Math.max(1, Math.min(sourceWidth - sx, Math.ceil(crop?.width ?? sourceWidth)));
  const sh = Math.max(1, Math.min(sourceHeight - sy, Math.ceil(crop?.height ?? sourceHeight)));
  if (sw < 1 || sh < 1) return null;

  const scale = Math.min(1, maxEdge / Math.max(sw, sh));
  const width = Math.max(1, Math.round(sw * scale));
  const height = Math.max(1, Math.round(sh * scale));

  let resized: ImageBitmap | null = null;
  if (scale < 1 && typeof createImageBitmap === "function") {
    try {
      resized = await createImageBitmap(source, sx, sy, sw, sh, {
        resizeWidth: width,
        resizeHeight: height,
        resizeQuality: "high"
      });
    } catch {
      // Older Safari refuses the options bag outright. The canvas path handles it.
      resized = null;
    }
  }

  try {
    const rgba = resized
      ? drawToPixels(resized, 0, 0, resized.width, resized.height, width, height)
      : drawToPixels(source, sx, sy, sw, sh, width, height);
    return rgba ? toGreyPlane(rgba, width, height) : null;
  } finally {
    resized?.close();
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The native detector, where there is one
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The slice of the Barcode Detection API used here.
 *
 * Declared locally rather than in a global `.d.ts`, the rule `WorkshopCodeScanner` and
 * `identityCardLocal` both state: a global declaration asserts to every other file that the API is
 * always present, which is the exact belief this module exists not to hold.
 */
type BarcodeDetectorLike = { detect(source: CanvasImageSource): Promise<{ rawValue: string }[]> };
type BarcodeDetectorConstructor = {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?: () => Promise<string[]>;
};

/**
 * One detector per tab, or null forever.
 *
 * A promise rather than a detector, so two controls mounting in the same tick share one probe — the
 * shape `identityCardLocal.browserCanReadCards` uses, and for the same reason.
 */
let nativeProbe: Promise<BarcodeDetectorLike | null> | null = null;

/**
 * The native QR detector if this browser has a working one.
 *
 * HAVING THE CONSTRUCTOR IS NOT HAVING THE FORMAT: some builds ship the API with an empty format
 * list, and a detector that never sees a `qr_code` would silently return no results for every frame,
 * which reads as a broken camera rather than an absent API. `getSupportedFormats` is asked wherever
 * it exists.
 *
 * Exported so a surface can SAY which route it is on if it ever needs to, and so a test can assert
 * the bundled path is what runs on a browser without the API. Nothing about the feature is gated on
 * the answer.
 */
export function nativeQrDetector(): Promise<BarcodeDetectorLike | null> {
  if (nativeProbe) return nativeProbe;
  nativeProbe = (async () => {
    const constructor = (globalThis as unknown as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (!constructor) return null;
    try {
      const formats = await constructor.getSupportedFormats?.();
      if (formats && !formats.includes("qr_code")) return null;
      return new constructor({ formats: ["qr_code"] });
    } catch {
      return null;
    }
  })();
  return nativeProbe;
}

/** Ask the native detector, and treat every failure as "it did not read one". */
async function tryNative(source: CanvasImageSource): Promise<string | null> {
  const detector = await nativeQrDetector();
  if (!detector) return null;
  try {
    const found = await detector.detect(source);
    return found.find((code) => code.rawValue)?.rawValue ?? null;
  } catch {
    // A frame arriving mid-resize, a source the platform refused. Never fatal: the bundled decoder
    // is about to be asked the same question.
    return null;
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Refusals this module owns
 * ──────────────────────────────────────────────────────────────────────────── */

/** A decode failure, carried through with `qrDecode`'s own sentence — it is already the right one. */
function fromDecode(result: Extract<QrDecodeResult, { ok: false }>): Extract<QrImageResult, { ok: false }> {
  return { ok: false, reason: result.reason, message: result.message };
}

function unreadableFile(name: string | undefined): Extract<QrImageResult, { ok: false }> {
  return {
    ok: false,
    reason: "FILE_UNREADABLE",
    // NOT "the code could not be read". The commonest cause by a wide margin is an iPhone HEIC,
    // which most browsers refuse outright, and telling somebody their card is unreadable when the
    // truth is that this browser cannot open that FORMAT sends them back to photograph a card that
    // was fine.
    message:
      `This browser could not open ${name ? `“${name}”` : "that file"}. Photographs from an iPhone are often HEIC, ` +
      "which most browsers cannot read — send it as JPEG or PNG, take a screenshot of it and upload that, or type " +
      "the code printed under the QR."
  };
}

const NO_IMAGE_SUPPORT: Extract<QrImageResult, { ok: false }> = {
  ok: false,
  reason: "NO_IMAGE_SUPPORT",
  message:
    "This browser cannot read pictures on this page, so a photograph of a code cannot be decoded here. Type the code " +
    "printed under the QR instead — it opens exactly the same record."
};

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a file
 * ──────────────────────────────────────────────────────────────────────────── */

/** Grow a located region by a margin and turn it into a rectangle in source pixels. */
function cropFromRegion(region: QrRegion, sourceWidth: number, sourceHeight: number): Crop {
  // A tenth of the region on every side. The region already carries the quiet zone (see
  // `qrDecode.regionOf`), so this is slack against the region itself being slightly off — and a
  // symbol re-cut without its quiet zone is a symbol the second attempt cannot find either, which
  // would make this whole rung silently useless.
  const padX = region.width * sourceWidth * 0.1;
  const padY = region.height * sourceHeight * 0.1;
  const left = Math.max(0, region.x * sourceWidth - padX);
  const top = Math.max(0, region.y * sourceHeight - padY);
  const right = Math.min(sourceWidth, (region.x + region.width) * sourceWidth + padX);
  const bottom = Math.min(sourceHeight, (region.y + region.height) * sourceHeight + padY);
  return { x: left, y: top, width: right - left, height: bottom - top };
}

/**
 * Read a QR code out of a picture the designer already has.
 *
 * THE PATH THAT MATTERS IN THIS PRODUCT'S FIELD CONDITIONS, and not a convenience. A camera scan
 * needs a working camera, a granted permission, enough light, and a second device in the room
 * holding the code up. An upload needs a file: a screenshot, a WhatsApp forward, a photograph taken
 * in the morning before the artisan went home, a printed sheet photographed last week, a laptop with
 * no camera at all. On a Windows laptop in a district office it is frequently the ONLY route that
 * exists.
 *
 * `filename` is used only to name the file back in the one refusal that is about the file rather
 * than the code. Nothing else is read from it — in particular the extension is not consulted, because
 * a browser that can decode a file will decode it whatever it is called, and one that cannot will
 * fail whatever it is called.
 */
export async function decodeQrFromFile(file: Blob, filename?: string): Promise<QrImageResult> {
  if (typeof createImageBitmap !== "function") return NO_IMAGE_SUPPORT;

  let bitmap: ImageBitmap | null = null;
  try {
    try {
      // `from-image` explicitly: a card photographed in portrait carries an EXIF orientation, and a
      // decoder handed the unrotated pixels is looking at a sideways picture. The detector copes with
      // rotation, but the region it reports would be in the wrong frame for the re-cut below.
      bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      return unreadableFile(filename);
    }
    if (bitmap.width < 1 || bitmap.height < 1) return unreadableFile(filename);

    // The fast path, where the platform has one.
    const native = await tryNative(bitmap);
    if (native) return { ok: true, text: native, via: "native" };

    return await decodeBitmapWithLadder(bitmap);
  } finally {
    // A 12-megapixel photograph decodes to roughly 48 MB. Leaving a handful of those to the garbage
    // collector on a 2 GB field handset is how a scanner starts failing at the fourth card with an
    // error about the picture rather than about memory.
    bitmap?.close();
  }
}

/** The three rungs of the ladder. See the file header for why each exists. */
async function decodeBitmapWithLadder(bitmap: ImageBitmap): Promise<QrImageResult> {
  const sourceLongEdge = Math.max(bitmap.width, bitmap.height);

  // ── Rung 1: the whole picture, bounded.
  const whole = await planeFrom(bitmap, null, DETECT_EDGE_PX);
  if (!whole) return NO_IMAGE_SUPPORT;
  const first = decodeQrFromGrey(whole);
  if (first.ok) return { ok: true, text: first.text, via: "bundled" };

  // ── Rung 2: the symbol was located but not read. Cut it out of the original and look closer.
  if (first.region) {
    const closer = await planeFrom(bitmap, cropFromRegion(first.region, bitmap.width, bitmap.height), RECUT_EDGE_PX);
    if (closer) {
      const second = decodeQrFromGrey(closer);
      if (second.ok) return { ok: true, text: second.text, via: "bundled" };
    }
  }

  // ── Rung 3: nothing was located at all, and the original has detail the working copy threw away.
  // Deliberately NOT taken when a region came back: rung 2 already looked at that symbol at full
  // resolution, and a third look at the whole frame cannot beat it.
  if (!first.region && sourceLongEdge > DEEP_PASS_MIN_SOURCE_PX) {
    const deep = await planeFrom(bitmap, null, DEEP_EDGE_PX);
    if (deep) {
      const third = decodeQrFromGrey(deep);
      if (third.ok) return { ok: true, text: third.text, via: "bundled" };
      if (third.region) {
        const closer = await planeFrom(bitmap, cropFromRegion(third.region, bitmap.width, bitmap.height), RECUT_EDGE_PX);
        if (closer) {
          const fourth = decodeQrFromGrey(closer);
          if (fourth.ok) return { ok: true, text: fourth.text, via: "bundled" };
        }
      }
    }
  }

  // The FIRST pass's refusal, not the last one's. It is the one taken over the whole picture, so it
  // is the one whose advice is about the picture the designer actually chose — "crop to the code" is
  // useful, and "that crop was too small" would be advice about a crop they never made.
  return fromDecode(first);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a live camera frame
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Read one frame of a playing `<video>`.
 *
 * ONE RUNG, NOT THREE, and that is the difference between this and {@link decodeQrFromFile}. A frame
 * arrives every few tens of milliseconds and the next one is usually better, so spending three
 * decodes on a bad frame buys nothing and costs the frames it would have read. A camera preview is
 * also already small — 640x480 or 1280x720 — so there is no detail a downscale is throwing away.
 *
 * Returns null when there is no frame to read yet, which is not a failure and must not be reported
 * as one: `readyState` below `HAVE_CURRENT_DATA` simply means the stream has not produced a picture.
 *
 * A frame with nothing in it comes back as a NO_SYMBOL refusal rather than null, because the caller
 * — not this function — knows whether it is scanning continuously (ignore it, try the next frame) or
 * answering a deliberate press (say so).
 */
export async function decodeQrFromVideoFrame(video: HTMLVideoElement): Promise<QrImageResult | null> {
  if (video.readyState < 2 || video.videoWidth < 1 || video.videoHeight < 1) return null;

  const native = await tryNative(video);
  if (native) return { ok: true, text: native, via: "native" };

  const plane = await planeFrom(video, null, DETECT_EDGE_PX);
  if (!plane) return null;
  const result = decodeQrFromGrey(plane);
  return result.ok ? { ok: true, text: result.text, via: "bundled" } : fromDecode(result);
}
