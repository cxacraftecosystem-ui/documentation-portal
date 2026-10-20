import type { MediaFile } from "@/lib/types";

/**
 * On-device photograph quality checks — blur, resolution, duplicates and missing views.
 *
 * WHY THIS RUNS ON THE HANDSET, AND WHY IT RUNS AT SELECTION
 *
 * A designer photographs a product in a village that is a couple of hundred kilometres from the
 * next reliable connection, and the upload happens hours or days later. A check that runs on the
 * server therefore reports "that photograph is out of focus" to somebody sitting at a desk, about an
 * object that has since been sold, in a cluster nobody is going back to this month. The finding is
 * true and completely useless. The only moment the warning is worth anything is the moment the
 * designer is still standing in front of the object and can simply take the picture again — which
 * is the instant the file is CHOSEN, before it joins the upload queue. Everything below is
 * arithmetic on pixels the browser has already decoded; nothing here touches the network.
 *
 * THESE CHECKS ARE READ-ONLY ON THE PIXELS, AND THAT IS A HARD RULE
 *
 * docs/MEDIA_PIPELINE.md §5 records a deliberate refusal to re-encode images: this is a heritage
 * archive, the original file IS the artifact, and a canvas round-trip destroys full resolution and
 * strips the EXIF the app preserves on purpose (`collectExifMetadata`: captured files go up
 * unchanged). So this module MEASURES and never modifies. It decodes to
 * a scratch bitmap, reads numbers off it, closes it, and leaves the `File` byte-for-byte untouched.
 * There is no `toBlob`, no `toDataURL`, no re-compression and no write path anywhere in this file,
 * and there must never be one — a "helpful" downscale here would quietly become the thing the
 * pipeline document exists to forbid.
 *
 * A FINDING IS ADVICE, NEVER A REFUSAL
 *
 * Every function here returns descriptions. None of them can stop an upload, delete a file or swap
 * one out, and the surfacing must keep it that way. A designer may have deliberately photographed
 * something blurred (a loom in motion), or may be holding the ONLY photograph that will ever exist
 * of an object that is about to leave. Blocking that is a worse failure than every problem this
 * module can detect, put together.
 *
 * THE SHAPE OF THIS FILE: A PURE CORE, THEN A THIN BROWSER ADAPTER
 *
 * Everything above {@link measureImageFile} is plain arithmetic over a greyscale plane — no DOM, no
 * `File`, no network — so it can be unit-tested directly against images the test GENERATES, and so
 * the measurement can be ported to Kotlin for Android parity without carrying any of the web's
 * decoding machinery with it. Only the adapter below it knows what a browser is.
 */

// ---------------------------------------------------------------------------
// The pure numeric core — no DOM, no network, no React
// ---------------------------------------------------------------------------

/**
 * A single-channel (luma) image. Deliberately not `ImageData`: that type is a DOM interface, and
 * naming it here would drag the whole browser lib into the half of this module that must stay
 * portable and directly testable.
 */
export type GreyPlane = {
  /** One byte of luma per pixel, row-major, `width * height` long. */
  data: Uint8ClampedArray;
  width: number;
  height: number;
};

/**
 * The long edge every measurement is taken at, and the reason the thresholds below are numbers
 * rather than opinions.
 *
 * A 12 MP phone photograph is around 4000x3000. Convolving twelve million pixels on the main thread
 * of a low-end handset is a visible freeze at exactly the moment the designer wants to take the next
 * picture, and it is unnecessary: defocus is a LOW-frequency phenomenon — a photograph that is
 * genuinely out of focus is still obviously out of focus at 640px, because the blur radius scales
 * down with everything else. 640 on the long edge is ~307k pixels for a 4:3 frame, about forty times
 * less arithmetic than full size.
 *
 * TWO CONSEQUENCES, BOTH DELIBERATE.
 *
 * First, the blur threshold is meaningless without this number: variance of the Laplacian depends
 * entirely on the scale it is measured at, so moving this constant invalidates
 * {@link BLUR_VARIANCE_FLOOR} and the calibration in e2e/image-quality.spec.ts must be re-run.
 * They move together or not at all.
 *
 * Second, downscaling is itself a low-pass filter, so a photograph that is only SLIGHTLY soft looks
 * sharp here — a 4px blur at 4000px wide is a 0.64px blur at 640px, which is nothing. That is a
 * deliberate bias toward silence, not an oversight: this check is meant to catch the photograph that
 * is badly out of focus, and to say nothing at all about the marginal ones.
 */
export const WORK_EDGE_PX = 640;

/**
 * Below this the "blurred" warning is withheld no matter how low the Laplacian variance is, because
 * the measure stops meaning anything.
 *
 * Variance of the Laplacian scales with the square of the image's own contrast, so a PERFECTLY sharp
 * photograph of a low-contrast subject scores like a blurred one. That is not a rare corner in this
 * archive — undyed cotton on a white sheet, raw bamboo against a mud wall and a pale terracotta pot
 * in flat shade are all ordinary craft documentation, and all of them are low-contrast by nature.
 * Warning on those would train designers to ignore the warning, which costs more than the blurred
 * photographs it would have caught.
 *
 * So contrast is measured first (the standard deviation of the luma plane, 0–255), and where there
 * is too little of it for the blur measure to discriminate, this module says nothing rather than
 * guessing.
 *
 * MEASURED, in e2e/image-quality.spec.ts: a perfectly sharp 1/f field scaled to a flat subject
 * scores a blur variance of 57 — below {@link BLUR_VARIANCE_FLOOR}, i.e. it WOULD be reported as
 * blurred — at a contrast of 9.0. Blurring, by contrast, barely moves this number at all (a
 * photo-like field measures 32.2 sharp and 29.6 after a radius-4 blur), because defocus removes
 * high-frequency detail and leaves the overall spread of tones intact. That asymmetry is what makes
 * contrast a safe guard: it can distinguish "flat" from "unfocused", so withholding the warning
 * below 12 costs no genuine blur detections while removing the one false positive that matters.
 */
export const MIN_CONTRAST_STDDEV = 12;

/**
 * The variance-of-Laplacian score below which a photograph is called blurred, measured on a
 * {@link WORK_EDGE_PX} greyscale downscale.
 *
 * CALIBRATED, NOT ASSERTED. Every number below was measured by e2e/image-quality.spec.ts at
 * {@link WORK_EDGE_PX}, and that spec fails if the separation this constant depends on stops
 * holding:
 *
 *   SHARP                                          BLURRED (same sources, box blur radius 1/2/4)
 *   1/f "photo-like" field, seed 1337 ....  733     .......................  5.1 /  2.4 /  1.8
 *   1/f "photo-like" field, seed 90210 ...  807     .......................  5.5 /  2.4 /  1.8
 *   real JPEG through the browser decoder  15473    real JPEG, 6px Gaussian  ..........  56.3
 *   real 12 MP JPEG, browser decoder .....  9308
 *   checkerboard, 8px cells .............. 40106    (synthetic ceiling, not a photograph)
 *
 * So the entire realistic sharp population sits at 733 and above, and everything genuinely out of
 * focus sits at 56.3 and below — a gap of more than an order of magnitude with nothing in it. 60 is
 * placed at the BOTTOM of that gap, hard against the blurred population rather than in the middle.
 *
 * That asymmetry is the whole design. A missed blurred photograph costs one photograph. A false
 * "this is blurred" on a photograph that is perfectly sharp costs the feature: a designer who is
 * wrongly warned twice stops reading warnings, and then the real one goes past unread too. At 60 the
 * lowest realistic sharp score clears the line by a factor of twelve, which is the margin that
 * absorbs the low-detail subjects this corpus does not contain — a plain pot against a plain wall is
 * sharp and carries far less edge energy than any sample above. The cost of that choice is real and
 * accepted: photographs that are only slightly soft score in the hundreds and pass unremarked. Do
 * not "tighten" this number toward the middle of the gap without re-running the calibration and
 * thinking again about which of the two errors this feature can afford.
 */
export const BLUR_VARIANCE_FLOOR = 60;

/**
 * The long edge a photograph needs to survive being printed as a plate in the generated report.
 *
 * DERIVED FROM THE REPORT'S OWN GEOMETRY, not from a rule of thumb. The report is A4 with 25mm
 * margins (`report_model.py` `sizeMm` / `margin_mm`), so the text column — and therefore a
 * full-width plate at `width_pct: 100` — is 160mm, or 6.2992in of paper (`report_docx.py`
 * `text_w_mm`, and the identical figures in Android's `DocxWriter.kt` and the web preview's
 * `previewModel.ts`). The full-width landscape plate is the binding case: the portrait height cap of
 * 153mm needs fewer pixels, and every multi-up gallery cell needs far fewer.
 *
 * The dpi to multiply it by is the one figure this repository actually commits to:
 * `report_raster.RENDER_DPI = 200.0`, the resolution the app rasterises its own maps and charts at,
 * mirrored in Kotlin as a value that "must equal report_raster.RENDER_DPI". 6.2992in × 200dpi = 1260
 * pixels; 1280 is that rounded up.
 *
 * WHY NOT THE 300 DPI PRINT STANDARD, which would put this at 1890. Because a warning has to be
 * unarguable to survive being read twice. Below 1280 a photograph provably cannot fill the plate at
 * the resolution this app itself considers adequate — there is nothing to debate. Between 1280 and
 * 1890 it merely falls short of commercial offset print, which is a real but arguable concern, and
 * firing there would flag a great many photographs that look perfectly good in the delivered
 * document. Silence is the better answer in that band. (Note that photographs are embedded in the
 * report at their original size and never resampled — nothing downstream will rescue a small one,
 * which is why saying so at capture time is the only chance anybody gets.)
 *
 * Stated rather than hidden: the warning quotes both this figure and the reason, because "too small"
 * with no number tells a designer nothing they can act on, whereas "1024x768 — a catalogue plate
 * needs about 1280px on the long edge" tells them whether to re-shoot and at what setting.
 */
export const MIN_LONG_EDGE_PX = 1280;

/**
 * How many of a 64-bit perceptual hash's bits may differ before two photographs are called the same
 * shot.
 *
 * Two genuinely unrelated photographs sit near 32 (half the bits, i.e. chance). The same frame
 * re-encoded or re-imported sits at 0–2. Two exposures of one object seconds apart land in the low
 * single digits. 6 therefore separates "you have photographed this twice" from "these are two
 * different products" with a wide margin on both sides — and, again, it is set at the tight end on
 * purpose, because telling a designer that two distinct products are duplicates is the failure that
 * gets the whole warning ignored. Measured distances are recorded in the calibration spec.
 */
export const NEAR_DUPLICATE_MAX_DISTANCE = 6;

/**
 * Rec.601 luma, the same weighting every image library uses for "convert to greyscale".
 *
 * Kept as a named function rather than inlined because the blur measure and the perceptual hash must
 * agree on what grey means — two different greyscale conversions would make the two measurements
 * quietly incomparable with each other and with the Kotlin port.
 */
export function toGreyPlane(rgba: Uint8ClampedArray, width: number, height: number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let index = 0; index < data.length; index += 1) {
    const offset = index * 4;
    data[index] = (rgba[offset] * 299 + rgba[offset + 1] * 587 + rgba[offset + 2] * 114) / 1000;
  }
  return { data, width, height };
}

/**
 * Box-average `plane` down to `width` x `height`.
 *
 * Averaging every source pixel that falls in a target cell, rather than sampling one of them, is
 * what makes the perceptual hash stable: a nearest-neighbour shrink of the same photograph taken a
 * frame apart lands on different source pixels and produces a different hash, which is precisely the
 * near-duplicate this is supposed to catch.
 */
export function resampleGrey(plane: GreyPlane, width: number, height: number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    const sourceTop = Math.floor((y * plane.height) / height);
    const sourceBottom = Math.max(sourceTop + 1, Math.floor(((y + 1) * plane.height) / height));
    for (let x = 0; x < width; x += 1) {
      const sourceLeft = Math.floor((x * plane.width) / width);
      const sourceRight = Math.max(sourceLeft + 1, Math.floor(((x + 1) * plane.width) / width));
      let total = 0;
      let count = 0;
      for (let sy = sourceTop; sy < sourceBottom; sy += 1) {
        for (let sx = sourceLeft; sx < sourceRight; sx += 1) {
          total += plane.data[sy * plane.width + sx];
          count += 1;
        }
      }
      data[y * width + x] = count > 0 ? total / count : 0;
    }
  }
  return { data, width, height };
}

/** Standard deviation of the luma plane, 0–255 — the contrast guard of {@link MIN_CONTRAST_STDDEV}. */
export function contrastStdDev(plane: GreyPlane): number {
  const { data } = plane;
  if (data.length === 0) return 0;
  let sum = 0;
  for (let index = 0; index < data.length; index += 1) sum += data[index];
  const mean = sum / data.length;
  let squares = 0;
  for (let index = 0; index < data.length; index += 1) {
    const delta = data[index] - mean;
    squares += delta * delta;
  }
  return Math.sqrt(squares / data.length);
}

/**
 * Variance of the Laplacian — the standard sharpness measure.
 *
 * The 4-neighbour Laplacian [[0,1,0],[1,-4,1],[0,1,0]] is a second-derivative filter, so it responds
 * to edges and to nothing else. A sharp photograph is full of hard edges and its response has a wide
 * spread; blurring smears every edge across many pixels, which flattens the response toward zero and
 * collapses the variance. Higher is sharper.
 *
 * The border ring is skipped rather than clamped or wrapped: a clamped edge invents a zero-gradient
 * boundary and a wrapped one invents an enormous artificial edge where the top row meets the bottom.
 * Both would be measuring the convolution's own boundary policy rather than the photograph.
 */
export function laplacianVariance(plane: GreyPlane): number {
  const { data, width, height } = plane;
  if (width < 3 || height < 3) return 0;
  let sum = 0;
  let squares = 0;
  let count = 0;
  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const index = y * width + x;
      const response =
        data[index - width] + data[index + width] + data[index - 1] + data[index + 1] - 4 * data[index];
      sum += response;
      squares += response * response;
      count += 1;
    }
  }
  if (count === 0) return 0;
  const mean = sum / count;
  return squares / count - mean * mean;
}

/** Hex, so a hash can sit in a `Set`, a React key or a log line without any BigInt ceremony. */
const HASH_EDGE = 8;

/**
 * A 64-bit dHash: box-average to 9x8 greyscale, then record whether each pixel is brighter than the
 * one to its right.
 *
 * A DIFFERENCE hash rather than an average hash on purpose. aHash compares every pixel to the frame's
 * mean, so it is dominated by overall exposure — two different products shot against the same wall in
 * the same light hash alike, which is a false duplicate. dHash encodes the direction of local
 * gradients instead, which is a property of the SUBJECT, and it is naturally immune to the exposure
 * and white-balance drift between two shots of one object.
 */
export function differenceHash(plane: GreyPlane): string {
  const small = resampleGrey(plane, HASH_EDGE + 1, HASH_EDGE);
  let hex = "";
  for (let y = 0; y < HASH_EDGE; y += 1) {
    let byte = 0;
    for (let x = 0; x < HASH_EDGE; x += 1) {
      const left = small.data[y * (HASH_EDGE + 1) + x];
      const right = small.data[y * (HASH_EDGE + 1) + x + 1];
      byte = (byte << 1) | (left > right ? 1 : 0);
    }
    hex += byte.toString(16).padStart(2, "0");
  }
  return hex;
}

/** Differing bits between two {@link differenceHash} values; 64 means nothing in common. */
export function hammingDistance(left: string, right: string): number {
  if (left.length !== right.length) return HASH_EDGE * HASH_EDGE;
  let distance = 0;
  for (let index = 0; index < left.length; index += 1) {
    let diff = parseInt(left[index], 16) ^ parseInt(right[index], 16);
    while (diff) {
      distance += diff & 1;
      diff >>= 1;
    }
  }
  return distance;
}

/** Everything one photograph yields, before any of it is judged. */
export type ImageMeasurement = {
  /** The file's own pixel dimensions, NOT the working size — this is what the report has to print. */
  width: number;
  height: number;
  /** Variance of the Laplacian at {@link WORK_EDGE_PX}. Higher is sharper. */
  blurScore: number;
  /** Luma standard deviation at the working size; below {@link MIN_CONTRAST_STDDEV} blur is unjudgeable. */
  contrast: number;
  /** 64-bit dHash as 16 hex characters, for near-duplicate comparison. */
  perceptualHash: string;
  /** Wall-clock cost of decode + measurement, so the on-device budget can be asserted rather than assumed. */
  elapsedMs: number;
};

/**
 * Judge one measurement against a working size's thresholds. Pure, so the test can drive it with
 * fabricated numbers and prove the boundaries land where the constants say they do.
 */
export function isBlurred(measurement: Pick<ImageMeasurement, "blurScore" | "contrast">): boolean {
  // The contrast guard comes FIRST and is not a tie-breaker: where there is too little contrast for
  // the measure to discriminate, the answer is "no finding", never "probably blurred".
  if (measurement.contrast < MIN_CONTRAST_STDDEV) return false;
  return measurement.blurScore < BLUR_VARIANCE_FLOOR;
}

export function isUnderResolution(measurement: Pick<ImageMeasurement, "width" | "height">): boolean {
  return Math.max(measurement.width, measurement.height) < MIN_LONG_EDGE_PX;
}

// ---------------------------------------------------------------------------
// Findings
// ---------------------------------------------------------------------------

/**
 * The vocabulary is MEDIA_QUALITY_FLAG in backend/app/services/stage_schema.py, verbatim — the same
 * words stage 21's "Media quality flags" table records against a file. Inventing a second set of
 * names here would mean the warning a designer reads in the field and the flag the archive stores
 * describe the same problem differently.
 */
export type QualityFlag = "BLUR" | "LOW_RESOLUTION" | "DUPLICATE" | "MISSING_VIEW";

/** SEVERITY in the same registry: LOW / MEDIUM / HIGH. */
export type QualitySeverity = "LOW" | "MEDIUM" | "HIGH";

export type QualityFinding = {
  flag: QualityFlag;
  severity: QualitySeverity;
  /**
   * What to put in front of the designer — and it always carries the MEASUREMENT, not just the
   * verdict. "Blur score 42 (sharp photographs here score 300+)" lets somebody who deliberately
   * photographed a moving loom dismiss it in one read; "Image may be blurred" gives them nothing to
   * judge with and is indistinguishable from the app being wrong.
   */
  message: string;
};

/**
 * A photograph already attached to this workshop, as far as duplicate detection is concerned.
 *
 * `checksum` is the SHA-256 the upload already computed and stored on the row (see `computeChecksum`
 * in lib/media.ts and §2.8 of docs/MEDIA_PIPELINE.md) — so an EXACT duplicate is a lookup against
 * data that already exists, not a second hash of somebody else's file.
 */
export type AttachedImage = {
  label: string;
  checksum?: string | null;
  /** Only present for files still in this browser; a remote row has no pixels here to hash. */
  perceptualHash?: string | null;
};

/** Lift the fields duplicate detection needs off a media row, tolerating the ones that predate it. */
export function attachedImageFromMedia(media: Pick<MediaFile, "originalFilename"> & { checksum?: string | null }): AttachedImage {
  return { label: media.originalFilename, checksum: media.checksum ?? null };
}

function plural(count: number, one: string, many: string): string {
  return count === 1 ? one : many;
}

/**
 * Everything wrong with one photograph, given what it is being compared against.
 *
 * Pure and synchronous: it takes measurements and returns sentences. It cannot upload, cannot fetch
 * and cannot refuse.
 */
export function findQualityIssues({
  measurement,
  checksum,
  attached
}: {
  measurement: ImageMeasurement;
  /** This file's own SHA-256, when it has been computed; absent is "unknown", never "unique". */
  checksum?: string | null;
  attached?: AttachedImage[];
}): QualityFinding[] {
  const findings: QualityFinding[] = [];

  if (isBlurred(measurement)) {
    findings.push({
      flag: "BLUR",
      severity: "MEDIUM",
      message:
        `Sharpness score ${Math.round(measurement.blurScore)} — a sharp photograph normally scores ` +
        `well above ${BLUR_VARIANCE_FLOOR} here. This one may be out of focus. If it is meant to be ` +
        `soft, or it is the only shot you have, keep it.`
    });
  }

  if (isUnderResolution(measurement)) {
    findings.push({
      flag: "LOW_RESOLUTION",
      severity: "MEDIUM",
      message:
        `${measurement.width}x${measurement.height} — a catalogue plate needs about ` +
        `${MIN_LONG_EDGE_PX}px on the long edge to print sharply at A4. This will still be saved; it ` +
        `will simply print soft.`
    });
  }

  const candidates = attached ?? [];

  // Exact first, and exclusively: when the bytes are identical there is nothing a perceptual hash
  // can add, and reporting "duplicate" twice about one file reads as two separate problems.
  const identical = checksum ? candidates.filter((item) => item.checksum && item.checksum === checksum) : [];
  if (identical.length) {
    findings.push({
      flag: "DUPLICATE",
      severity: "LOW",
      message:
        `The same file is already attached here as ${identical.map((item) => `"${item.label}"`).join(", ")} ` +
        `— identical content, so this looks like the same import twice.`
    });
  } else {
    const near = candidates
      .map((item) => ({
        item,
        distance:
          item.perceptualHash && measurement.perceptualHash
            ? hammingDistance(measurement.perceptualHash, item.perceptualHash)
            : Number.POSITIVE_INFINITY
      }))
      .filter((entry) => entry.distance <= NEAR_DUPLICATE_MAX_DISTANCE)
      .sort((left, right) => left.distance - right.distance);
    if (near.length) {
      findings.push({
        flag: "DUPLICATE",
        severity: "LOW",
        message:
          `This looks like the same shot as ${near.map((entry) => `"${entry.item.label}"`).join(", ")}. ` +
          `Two views of one object are worth keeping; two copies of one view are not.`
      });
    }
  }

  return findings;
}

// ---------------------------------------------------------------------------
// Missing views — ONLY where the registry actually has named view slots
// ---------------------------------------------------------------------------

/**
 * The named single-image view slots that genuinely exist in the stage registry.
 *
 * VERIFIED AGAINST backend/app/services/stage_definitions.py RATHER THAN ASSUMED, because this is
 * exactly the check that is tempting to invent. Stage 6's `existingProduct` entity really does
 * declare `viewFront` / `viewBack` / `viewDetail` as separate IMAGE fields (lines 538-540), so
 * "you have a front but no back" is a statement about a slot the designer can actually see and fill.
 *
 * Nowhere else has them. Stage 11's `sketch` has one `image`; stage 16's `finalProduct` has the
 * `finalPhotos` gallery, `catalogPhotos` and `turntablePhotos` — galleries, not named views. A
 * "missing back view" warning on either would be pointing at a field that does not exist, which is
 * worse than no warning at all: it asks for something the form cannot accept. So this map is the
 * whole feature, and it grows only when the registry does.
 */
export const NAMED_VIEW_SLOTS: Record<string, Record<string, string>> = {
  existingProduct: { viewFront: "Front view", viewBack: "Back view", viewDetail: "Detail view" }
};

/**
 * Which named views this row is missing — but only once at least one has been filled.
 *
 * The "at least one" rule is what keeps this from nagging. Every one of these slots is Advanced-tier
 * and optional; a designer who filled none of them has decided this product does not need a
 * multi-view record, and telling them three times that they have not started is noise. A designer who
 * filled the front and the detail has plainly begun the set, and THAT is the person for whom "no back
 * view" is a useful thing to hear while the product is still on the table.
 */
export function findMissingViews(entityKey: string, filled: Record<string, unknown>): QualityFinding[] {
  const slots = NAMED_VIEW_SLOTS[entityKey];
  if (!slots) return [];
  const keys = Object.keys(slots);
  const present = keys.filter((key) => {
    const value = filled[key];
    return Array.isArray(value) ? value.length > 0 : Boolean(value);
  });
  if (present.length === 0 || present.length === keys.length) return [];
  const missing = keys.filter((key) => !present.includes(key));
  return [
    {
      flag: "MISSING_VIEW",
      severity: "LOW",
      message:
        `${present.length} of ${keys.length} views captured — no ${missing
          .map((key) => slots[key].toLowerCase())
          .join(", no ")}. ${plural(missing.length, "That view is", "Those views are")} easiest to add now, ` +
        `while the product is still in front of you.`
    }
  ];
}

// ---------------------------------------------------------------------------
// The browser adapter — the only part below that knows what a browser is
// ---------------------------------------------------------------------------

/** Formats a browser will decode to pixels. Anything else is not measurable and gets no findings. */
export function isMeasurableImage(file: File): boolean {
  return file.type.startsWith("image/") && file.type !== "image/svg+xml";
}

/**
 * Draw a bitmap at the working size and read the pixels back.
 *
 * `OffscreenCanvas` where it exists, a detached `<canvas>` where it does not (Safari carried
 * `createImageBitmap` for several versions before `OffscreenCanvas`). The fallback element is never
 * attached to the document, so it forces no layout and paints nothing.
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
 * Decode `file` and measure it, or return null when this browser cannot.
 *
 * KEEPING THE MAIN THREAD FREE, which on a low-end handset is the difference between a check nobody
 * notices and a camera button that stops responding:
 *
 * - `createImageBitmap` decodes OFF the main thread in every engine that has it, so the expensive
 *   part of a 12 MP JPEG never runs where the UI does.
 * - The second `createImageBitmap` resizes off-thread too, and the full-size bitmap is `close()`d the
 *   instant it has been read. A 4000x3000 decode is ~48 MB of RGBA; holding two of those on a cheap
 *   phone while the designer takes the next photograph is how a tab gets killed.
 * - Only the working-size plane — 640px on the long edge — is ever convolved, and that runs on the
 *   main thread because at that size it is sub-millisecond arithmetic. A worker would cost a second
 *   copy of the pixels and a message round-trip to save less than the copy costs.
 *
 * Failure is always silent and always null. A photograph in a format this browser will not decode is
 * a photograph with no findings, never an error in front of the designer — the file is fine and the
 * upload is already on its way.
 */
export async function measureImageFile(file: File): Promise<ImageMeasurement | null> {
  if (typeof createImageBitmap === "undefined" || !isMeasurableImage(file)) return null;
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const width = full.width;
    const height = full.height;
    if (width < 1 || height < 1) return null;

    const scale = Math.min(1, WORK_EDGE_PX / Math.max(width, height));
    const workWidth = Math.max(1, Math.round(width * scale));
    const workHeight = Math.max(1, Math.round(height * scale));
    scaled =
      scale < 1
        ? await createImageBitmap(full, {
            resizeWidth: workWidth,
            resizeHeight: workHeight,
            // A box-ish filter, not nearest-neighbour: a cheap resize aliases, aliasing is
            // high-frequency energy, and high-frequency energy is precisely what the blur measure
            // reads — so a fast downscale would make blurred photographs score as sharp.
            resizeQuality: "high"
          })
        : full;
    // Freed before the pixel read rather than after, so the big allocation and the working copy never
    // coexist any longer than they must.
    if (scaled !== full) {
      full.close();
      full = null;
    }

    const rgba = readPixels(scaled, workWidth, workHeight);
    if (!rgba) return null;
    const plane = toGreyPlane(rgba, workWidth, workHeight);
    const elapsedMs = (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt;
    return {
      width,
      height,
      blurScore: laplacianVariance(plane),
      contrast: contrastStdDev(plane),
      perceptualHash: differenceHash(plane),
      elapsedMs
    };
  } catch {
    // A corrupt file, an unsupported codec (HEIC on a browser without it), or a bitmap the GPU
    // refused. None of those is the designer's problem and none of them stops the upload.
    return null;
  } finally {
    full?.close();
    if (scaled && scaled !== full) scaled.close();
  }
}
