/**
 * Reading a QR symbol out of a photograph, in this browser, from nothing but this file.
 *
 * ── WHY THIS EXISTS AT ALL, AND WHY THE ARGUMENT AGAINST IT NO LONGER HOLDS ──────────────────
 *
 * `components/designworkshop/WorkshopCodeScanner.tsx` used to refuse to bundle a decoder, in
 * writing, and the refusal was reasoned: "every JavaScript QR decoder worth shipping is tens of
 * kilobytes of image processing that would sit in the bundle of every device — including the ones
 * that have the native detector — to serve the browser share that does not."
 *
 * THAT ARGUMENT WAS BUILT ON A MEASUREMENT THAT WAS NEVER TAKEN, and when it was taken it said the
 * opposite. `lib/identityCardLocal.ts` records the probe, run on this machine on 2026-08-09:
 *
 * | browser                                                  | `BarcodeDetector` |
 * |----------------------------------------------------------|-------------------|
 * | Chrome 151, headed                                        | **absent**        |
 * | Chrome 151, headed, `--enable-experimental-web-platform-features` | **absent** |
 * | Edge 151, headed and headless                             | **absent**        |
 * | Chromium 151 (Playwright), incl. `--enable-blink-features=ShapeDetection` | **absent** |
 *
 * Chrome's own documentation says barcode detection ships "on macOS, ChromeOS, and Android". The
 * machines this web client is actually used on are Windows laptops in a district office. So "the
 * browser share that does not have it" was not a minority to be traded away — on this surface it
 * was EVERYBODY, and the cost of the missing decoder was not a slower path but the total absence of
 * both camera scanning and photograph upload, on every laptop, every time.
 *
 * ── WHY UPLOAD IS THE PATH THAT MATTERS, NOT A NICETY ────────────────────────────────────────
 *
 * A camera scan needs a working camera, a granted permission, enough light, and a second device in
 * the room holding the code up. An upload needs a file: a screenshot, a photograph taken in the
 * morning before the artisan went home, a WhatsApp forward, a printed sheet photographed last week,
 * a laptop with no camera at all. In this application's field conditions the upload is frequently
 * the only route that exists, which is why {@link decodeQrFromGrey} is built for a PHOTOGRAPH OF A
 * SCREEN — skewed, glared, and holding a code that is a small part of a large picture — rather than
 * for the clean synthetic images a decoder is easy to pass on.
 *
 * ── WHY IT IS HAND-WRITTEN, THE SAME ARGUMENT `qrEncode.ts` MAKES ────────────────────────────
 *
 * `frontend/package.json` carries no QR library, and the encoder next door is hand-written for
 * reasons that apply here word for word: ISO 18004 was frozen in 2000 and will not change again,
 * the symbols must still read in 2030, and a dependency brings a transitive tree, its future CVEs
 * and its future breaking major under a feature whose whole specification is stable. What is new is
 * that this half costs far less to own than it looks, because almost none of it is new code:
 *
 *  - **The bit-level tables and every placement rule come from `lib/qrEncode.ts`** — the same block
 *    layout, the same zigzag, the same mask formulae, the same GF(256) tables, the same
 *    format-information generator. Nothing is transcribed a second time. See that file's header for
 *    why this is a correctness property and not a convenience: a decoder with its own copy of the
 *    tables reads a card as a DIFFERENT identifier rather than failing, and the check digit agrees
 *    all the way because both halves computed it over the same wrong bytes.
 *  - **The image-level work comes from `lib/sketchRectify.ts` and `lib/photoMeasure.ts`** — Sauvola
 *    local thresholding, the projective solve, and bilinear sampling, all already in this
 *    repository, already justified at length, and already under test. The glare on a photographed
 *    screen is the same problem as the lamp gradient on a photographed sketch, and it has the same
 *    answer; a photographed card at an angle is the same problem as a photographed sheet at an
 *    angle, and it has the same answer.
 *
 * So what is genuinely new here is the DETECTOR — finding the three finder patterns and the
 * alignment pattern in a photograph — and the Reed-Solomon ERROR CORRECTION, which is the half of
 * the code the encoder never needed.
 *
 * ── VERSIONS 1 TO 6, AND WHY THE CEILING IS A REFUSAL RATHER THAN A GAP ──────────────────────
 *
 * This reads exactly the versions `qrEncode.ts` writes, because it reads that file's own tables.
 * A larger symbol — someone's payment QR, a shipping label — is DETECTED and then refused by name:
 * "this is a QR code, but not one this app printed". That is the honest answer and it is a more
 * useful one than a decode would be, because a payment QR resolving to nothing is exactly as
 * unhelpful as a payment QR not being read.
 *
 * Raising the ceiling means adding the alignment centres and the block table for versions 7 and up
 * to `qrEncode.ts` — 34 more rows of ISO 18004 table 9, transcribed by hand. Do not do it to "be
 * general": every row is a chance to make a card read as the wrong record, and the existing rows
 * are the ones `e2e/workshop-codes.spec.ts` verifies against reportlab.
 *
 * PURE — no React, no fetch, no DOM, no clock, no randomness — in the sense `lib/marketAnalysis.ts`
 * is, and for the same reason: it has to run on a laptop that has had no signal for three days, and
 * be testable without one. The browser glue (a File, a bitmap, a video frame) lives in
 * `lib/qrImageDecode.ts`, so everything below can be driven from a plain array of numbers.
 */

import { type GreyPlane } from "@/lib/imageQuality";
import { applyHomography, solveHomography, type Homography, type Point } from "@/lib/photoMeasure";
import { bilinearSample, sauvolaThreshold } from "@/lib/sketchRectify";
import {
  ALIGNMENT_CENTRES,
  ALPHANUMERIC,
  BLOCKS,
  ECC_LEVELS,
  GF_EXP,
  GF_LOG,
  MAX_VERSION,
  MIN_VERSION,
  REMAINDER_BITS,
  TOTAL_CODEWORDS,
  formatBits,
  gfMultiply,
  maskInverts,
  qrDataModuleOrder,
  type QrEccLevel
} from "@/lib/qrEncode";

/* ────────────────────────────────────────────────────────────────────────────
 * The answers this module gives
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Why a picture produced no code.
 *
 * EVERY ONE OF THESE LEADS TO A DIFFERENT NEXT ACTION, which is the whole reason they are not one
 * "could not read it". "Nothing that looks like a code is in this picture" means photograph it
 * again with the whole square in frame; "this is a code but not one of ours" means stop trying;
 * "the code is damaged" means find a cleaner copy. A single message would send every one of those
 * people to do the same wrong thing.
 */
export type QrDecodeRefusal =
  /** Nothing in the picture looks like a QR symbol — no three finder patterns were found. */
  | "NO_SYMBOL"
  /** A symbol was found, but it is larger than any symbol this application prints. */
  | "UNSUPPORTED_VERSION"
  /** A symbol was found and its grid could not be read — too small, too blurred, too skewed. */
  | "UNREADABLE"
  /** The grid was read and the error correction could not repair it. Damaged or badly sampled. */
  | "DATA_UNRECOVERABLE"
  /** Read perfectly, and carries something this build does not turn into text (Kanji, multi-part). */
  | "UNSUPPORTED_CONTENT";

/**
 * Where in the picture the symbol appeared, as fractions of the width and height.
 *
 * NORMALISED, NOT IN PIXELS, and that is what makes it useful: `lib/qrImageDecode.ts` finds the
 * symbol in a cheap downscaled copy and then re-cuts THAT REGION out of the full-resolution
 * original. A rectangle in the coordinates of the copy would be meaningless against the original.
 * This is the whole mechanism behind reading a code that occupies a twentieth of a 12-megapixel
 * photograph — see that file's `decodeQrFromImageSource`.
 */
export type QrRegion = { x: number; y: number; width: number; height: number };

export type QrDecodeResult =
  | { ok: true; text: string; version: number; level: QrEccLevel; mask: number }
  | {
      ok: false;
      reason: QrDecodeRefusal;
      /** A sentence for the person holding the phone. Names the next action; never a code. */
      message: string;
      /** Present whenever a symbol was located, even if it could not be read. See {@link QrRegion}. */
      region?: QrRegion;
    };

/* ────────────────────────────────────────────────────────────────────────────
 * GF(256) arithmetic beyond what the encoder needed
 *
 * The tables themselves are `qrEncode.ts`'s, built once at its module load from the primitive
 * polynomial QR specifies. What is added here is division and evaluation, which an encoder never
 * has to do: encoding divides a message by a known generator, decoding has to solve for an unknown
 * error polynomial.
 * ──────────────────────────────────────────────────────────────────────────── */

/** α raised to a power, for any non-negative exponent. */
function gfPower(exponent: number): number {
  return GF_EXP[((exponent % 255) + 255) % 255];
}

/**
 * The multiplicative inverse of a non-zero field element.
 *
 * Zero has no inverse and asking for one is a defect in the caller, not a value to invent — so it
 * returns 0, which makes any expression using it collapse to 0 rather than to a plausible wrong
 * number. Every call site below has already excluded zero.
 */
function gfInverse(a: number): number {
  if (a === 0) return 0;
  return GF_EXP[255 - GF_LOG[a]];
}

function gfDivide(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[(GF_LOG[a] - GF_LOG[b] + 255) % 255];
}

/**
 * Polynomials here are LITTLE-ENDIAN: `poly[i]` is the coefficient of x^i.
 *
 * The encoder's `generatorPolynomial` is big-endian, and the two conventions living in one file
 * would be a standing invitation to a silent reversal. They are kept apart deliberately: nothing
 * below ever touches the encoder's polynomials, and nothing in the encoder touches these. The one
 * place the two meet is the received codeword array, which is neither — it is a byte stream, and it
 * is converted explicitly where it is used.
 */
type Poly = Uint8Array;

function polyEvaluate(poly: Poly, x: number): number {
  let value = 0;
  // Horner from the top coefficient down, which is one multiply per term and no powers to build.
  for (let degree = poly.length - 1; degree >= 0; degree--) value = gfMultiply(value, x) ^ poly[degree];
  return value;
}

function polyMultiply(left: Poly, right: Poly): Poly {
  const out = new Uint8Array(left.length + right.length - 1);
  for (let i = 0; i < left.length; i++) {
    if (left[i] === 0) continue;
    for (let j = 0; j < right.length; j++) out[i + j] ^= gfMultiply(left[i], right[j]);
  }
  return out;
}

/**
 * The formal derivative of a polynomial over GF(2^m).
 *
 * IN CHARACTERISTIC TWO EVERY EVEN-DEGREE TERM VANISHES, because its coefficient is multiplied by
 * an even integer and 2 = 0 in this field. That is not a shortcut, it is the derivative — and it is
 * the single most commonly mis-implemented line in a Reed-Solomon decoder, because the textbook
 * formula `n·a_n·x^(n-1)` looks as though it needs the integer n.
 */
function polyDerivative(poly: Poly): Poly {
  const out = new Uint8Array(Math.max(1, poly.length - 1));
  for (let degree = 1; degree < poly.length; degree += 2) out[degree - 1] = poly[degree];
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reed-Solomon decoding
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Repair one Reed-Solomon block in place, or say it cannot be done.
 *
 * WHY THIS IS THE HALF THAT MAKES THE FEATURE REAL. A QR code printed on a card that has spent a
 * fortnight in a workshop has a thumbprint on it, a smear of dye, a fold across a corner — and the
 * cards this application prints are level Q, which means a quarter of every block may be destroyed
 * and the identifier still comes back EXACTLY right. Without this function a decoder reads only
 * pristine symbols, which is to say it reads them on a developer's screen and not in a courtyard.
 *
 * `received` is the block as read: `data` codewords followed by `ec` codewords, highest-order
 * first. It is corrected in place. The return value is the number of errors repaired, or -1 when
 * the block is beyond repair — which is a genuine and expected answer (a symbol sampled off a
 * grid that was one module out produces garbage in every block), and is why the caller must never
 * treat a corrected block as merely "probably right".
 *
 * The roots are α⁰…α^(ec-1), matching `qrEncode.generatorPolynomial`, which builds its generator
 * from exactly those. If that ever changes, this changes with it — which is why the sentence is
 * here rather than in a commit message.
 */
function correctBlock(received: Uint8Array, ecCount: number): number {
  // ── Syndromes. Each is the received polynomial evaluated at one root of the generator. All zero
  // means the block is already consistent, which is the overwhelmingly common case: a clean read of
  // a clean symbol never enters the machinery below.
  const syndromes = new Uint8Array(ecCount);
  let anyError = false;
  for (let index = 0; index < ecCount; index++) {
    let value = 0;
    const x = gfPower(index);
    // The received array is highest-order first, so Horner runs forward through it.
    for (let position = 0; position < received.length; position++) value = gfMultiply(value, x) ^ received[position];
    syndromes[index] = value;
    if (value !== 0) anyError = true;
  }
  if (!anyError) return 0;

  // ── Berlekamp-Massey: the shortest linear recurrence the syndromes obey. Its coefficients are
  // the error LOCATOR polynomial Λ, whose roots point at the corrupted positions.
  let lambda: Poly = new Uint8Array([1]);
  let previous: Poly = new Uint8Array([1]);
  let errors = 0;
  let shift = 1;
  let previousDiscrepancy = 1;

  for (let step = 0; step < ecCount; step++) {
    let discrepancy = syndromes[step];
    for (let index = 1; index <= errors; index++) {
      if (index < lambda.length) discrepancy ^= gfMultiply(lambda[index], syndromes[step - index]);
    }

    if (discrepancy === 0) {
      shift++;
      continue;
    }

    const scale = gfDivide(discrepancy, previousDiscrepancy);
    const adjusted = new Uint8Array(Math.max(lambda.length, previous.length + shift));
    adjusted.set(lambda);
    for (let index = 0; index < previous.length; index++) {
      adjusted[index + shift] ^= gfMultiply(scale, previous[index]);
    }

    if (2 * errors <= step) {
      const before = lambda;
      lambda = adjusted;
      previous = before;
      previousDiscrepancy = discrepancy;
      errors = step + 1 - errors;
      shift = 1;
    } else {
      lambda = adjusted;
      shift++;
    }
  }

  // Trim the leading zero coefficients Berlekamp-Massey can leave behind, so the degree below is
  // the true degree. An untrimmed Λ makes the count check reject blocks that are perfectly
  // correctable.
  let degree = lambda.length - 1;
  while (degree > 0 && lambda[degree] === 0) degree--;
  if (degree !== errors || errors === 0) return -1;
  // More errors than the code can carry. `ec/2` is the theoretical limit; past it the syndromes are
  // consistent with several different original messages and "correcting" would be inventing one.
  if (errors * 2 > ecCount) return -1;

  // ── Chien search: which positions Λ points at. Position `power` counts from the END of the
  // received array, because that is the convention in which the received polynomial's coefficient
  // of x^power lives at index `length - 1 - power`.
  const positions: number[] = [];
  for (let power = 0; power < received.length; power++) {
    if (polyEvaluate(lambda, gfPower(255 - (power % 255))) === 0) positions.push(power);
  }
  if (positions.length !== errors) return -1;

  // ── Forney: how much each of those positions is wrong by. Ω = S·Λ truncated to ec terms.
  const syndromePoly = syndromes;
  const product = polyMultiply(syndromePoly, lambda);
  const omega = product.slice(0, ecCount);
  const lambdaPrime = polyDerivative(lambda);

  for (const power of positions) {
    const locator = gfPower(power);
    const inverse = gfInverse(locator);
    const denominator = polyEvaluate(lambdaPrime, inverse);
    // A zero denominator means Λ has a repeated root, which a genuine error pattern cannot produce.
    // It means the syndromes were not describing errors at all — refuse rather than divide.
    if (denominator === 0) return -1;
    // With the generator's first root at α⁰, the magnitude carries one factor of the locator.
    const magnitude = gfMultiply(locator, gfDivide(polyEvaluate(omega, inverse), denominator));
    const index = received.length - 1 - power;
    if (index < 0 || index >= received.length) return -1;
    received[index] ^= magnitude;
  }

  // ── The verification that makes the answer trustworthy. Berlekamp-Massey and Forney will happily
  // produce a "correction" for a block that is beyond repair; recomputing the syndromes over the
  // repaired block is the only thing that distinguishes a real repair from a confident invention,
  // and the whole point of this feature is that a scanned identifier is never quietly wrong.
  for (let index = 0; index < ecCount; index++) {
    let value = 0;
    const x = gfPower(index);
    for (let position = 0; position < received.length; position++) value = gfMultiply(value, x) ^ received[position];
    if (value !== 0) return -1;
  }
  return errors;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the bit stream a symbol carries
 * ──────────────────────────────────────────────────────────────────────────── */

/** A cursor over the data codewords, which the segment parser walks in bit-sized steps. */
class BitReader {
  private position = 0;

  constructor(private readonly bytes: Uint8Array) {}

  get remaining(): number {
    return this.bytes.length * 8 - this.position;
  }

  read(count: number): number {
    let value = 0;
    for (let index = 0; index < count; index++) {
      const bit = (this.bytes[this.position >>> 3] >>> (7 - (this.position & 7))) & 1;
      value = (value << 1) | bit;
      this.position++;
    }
    return value;
  }
}

/** ISO 18004 mode indicators, named so the parser below reads as the standard does. */
const MODE_TERMINATOR = 0b0000;
const MODE_NUMERIC = 0b0001;
const MODE_ALPHANUMERIC = 0b0010;
const MODE_STRUCTURED_APPEND = 0b0011;
const MODE_BYTE = 0b0100;
const MODE_FNC1_FIRST = 0b0101;
const MODE_ECI = 0b0111;
const MODE_KANJI = 0b1000;
const MODE_FNC1_SECOND = 0b1001;

type SegmentOutcome = { ok: true; text: string } | { ok: false; reason: QrDecodeRefusal; message: string };

/**
 * Turn the corrected data codewords into the string the symbol was built from.
 *
 * THE CHARACTER-COUNT WIDTHS ARE THE VERSION 1–9 ONES, unconditionally, and that is correct rather
 * than lazy: {@link MAX_VERSION} is 6. If the ceiling is ever raised past 9 these widths become
 * version-dependent (10 bits for numeric at version 10 and up, and so on) and a symbol read with
 * the wrong width does not fail — it reads a plausible number of characters from the wrong offset
 * and returns text that was never encoded. Raising the ceiling therefore means changing this
 * function, and the assertion below is what makes that a failing test rather than a wrong scan.
 */
function readSegments(bytes: Uint8Array, version: number): SegmentOutcome {
  if (version > 9) {
    return {
      ok: false,
      reason: "UNSUPPORTED_VERSION",
      message: "Too large to read. Type the code under the QR."
    };
  }

  const reader = new BitReader(bytes);
  let text = "";

  // A byte segment's charset is ISO-8859-1 unless an ECI segment said otherwise; in practice
  // everything modern writes UTF-8 and declares ECI 26, and plenty writes UTF-8 and declares
  // nothing. So the bytes are gathered and decoded with UTF-8 first, falling back to Latin-1 —
  // never the other way round, because Latin-1 decodes ANY byte sequence and would silently turn
  // valid UTF-8 into mojibake that no later stage could detect.
  while (reader.remaining >= 4) {
    const mode = reader.read(4);
    if (mode === MODE_TERMINATOR) break;

    if (mode === MODE_ECI) {
      // The designator is 1, 2 or 3 bytes, told apart by its leading bits. It says which character
      // set follows; this build reads it only to step over it correctly.
      const first = reader.read(8);
      if ((first & 0b1000_0000) === 0) {
        // one byte, nothing more to read
      } else if ((first & 0b1100_0000) === 0b1000_0000) {
        reader.read(8);
      } else if ((first & 0b1110_0000) === 0b1100_0000) {
        reader.read(16);
      } else {
        return {
          ok: false,
          reason: "UNREADABLE",
          message: "Can't read this code. Type the code under the QR."
        };
      }
      continue;
    }

    if (mode === MODE_FNC1_FIRST) continue;
    if (mode === MODE_FNC1_SECOND) {
      reader.read(8);
      continue;
    }

    if (mode === MODE_STRUCTURED_APPEND) {
      return {
        ok: false,
        reason: "UNSUPPORTED_CONTENT",
        message:
          "Not a workshop code. Type the code under the QR."
      };
    }

    if (mode === MODE_KANJI) {
      return {
        ok: false,
        reason: "UNSUPPORTED_CONTENT",
        message: "This isn't a workshop code."
      };
    }

    if (mode === MODE_NUMERIC) {
      const count = reader.read(10);
      // The EXACT bit cost, not an estimate. A generous estimate refuses codes that are perfectly
      // whole, and a mean one reads past the end of the buffer and returns zeros as characters —
      // the second of which is the dangerous direction, because zeros look like data.
      const remainder = count % 3;
      if (Math.floor(count / 3) * 10 + (remainder === 2 ? 7 : remainder === 1 ? 4 : 0) > reader.remaining) {
        return truncated();
      }
      let index = 0;
      while (index + 3 <= count) {
        const triple = reader.read(10);
        if (triple > 999) return corrupt();
        text += String(triple).padStart(3, "0");
        index += 3;
      }
      if (count - index === 2) {
        const pair = reader.read(7);
        if (pair > 99) return corrupt();
        text += String(pair).padStart(2, "0");
      } else if (count - index === 1) {
        const single = reader.read(4);
        if (single > 9) return corrupt();
        text += String(single);
      }
      continue;
    }

    if (mode === MODE_ALPHANUMERIC) {
      const count = reader.read(9);
      // Exact, for the reason spelled out on the numeric branch above.
      if (Math.floor(count / 2) * 11 + (count % 2) * 6 > reader.remaining) return truncated();
      let index = 0;
      while (index + 2 <= count) {
        const pair = reader.read(11);
        const high = Math.floor(pair / 45);
        const low = pair % 45;
        if (high >= ALPHANUMERIC.length) return corrupt();
        text += ALPHANUMERIC[high] + ALPHANUMERIC[low];
        index += 2;
      }
      if (index < count) {
        const single = reader.read(6);
        if (single >= ALPHANUMERIC.length) return corrupt();
        text += ALPHANUMERIC[single];
      }
      continue;
    }

    if (mode === MODE_BYTE) {
      const count = reader.read(8);
      if (count * 8 > reader.remaining) return truncated();
      const raw = new Uint8Array(count);
      for (let index = 0; index < count; index++) raw[index] = reader.read(8);
      text += decodeBytes(raw);
      continue;
    }

    return {
      ok: false,
      reason: "UNREADABLE",
      message: "Can't read this code. Type the code under the QR."
    };
  }

  return { ok: true, text };
}

function truncated(): SegmentOutcome {
  return {
    ok: false,
    reason: "DATA_UNRECOVERABLE",
    message:
      "The code is cut off or damaged. Photograph the whole square, or type the code."
  };
}

function corrupt(): SegmentOutcome {
  return {
    ok: false,
    reason: "DATA_UNRECOVERABLE",
    message:
      "The code is damaged. Photograph the whole square, or type the code."
  };
}

/** UTF-8 if the bytes really are UTF-8, Latin-1 otherwise. See the note in {@link readSegments}. */
function decodeBytes(raw: Uint8Array): string {
  if (typeof TextDecoder === "function") {
    try {
      return new TextDecoder("utf-8", { fatal: true }).decode(raw);
    } catch {
      // Not UTF-8. Fall through to Latin-1, which cannot fail.
    }
  }
  let out = "";
  for (const byte of raw) out += String.fromCharCode(byte);
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * From a module matrix to a string
 * ──────────────────────────────────────────────────────────────────────────── */

/** How many bits differ between two 15-bit words. */
function bitDistance(left: number, right: number): number {
  let difference = left ^ right;
  let count = 0;
  while (difference !== 0) {
    difference &= difference - 1;
    count++;
  }
  return count;
}

/**
 * The level and mask a symbol declares, read from whichever of its two format copies is cleaner.
 *
 * IT IS A NEAREST-MATCH AGAINST GENERATED CANDIDATES, not a hand-written BCH decoder. All 32 legal
 * format strings are produced by `qrEncode.formatBits` — the very function that wrote the one in
 * the symbol — and the nearest is taken. The BCH(15,5) code corrects three bit errors, so a
 * distance of 3 is accepted and 4 is refused; accepting more would mean choosing between two
 * candidates that are equally close, which is a coin toss over which MASK to remove and produces a
 * complete, clean, entirely wrong read.
 *
 * Both copies are tried because the format block is the one part of a symbol with no
 * error-correction blocks behind it: a thumbprint over the corner of a card destroys copy one
 * outright, and copy two, on the opposite side, is the reason the standard writes it twice.
 */
function readFormat(matrix: boolean[][]): { level: QrEccLevel; mask: number } | null {
  const size = matrix.length;
  const bit = (row: number, column: number) => (matrix[row][column] ? 1 : 0);

  let first = 0;
  for (let index = 0; index <= 5; index++) first |= bit(index, 8) << index;
  first |= bit(7, 8) << 6;
  first |= bit(8, 8) << 7;
  first |= bit(8, 7) << 8;
  for (let index = 9; index < 15; index++) first |= bit(8, 14 - index) << index;

  let second = 0;
  for (let index = 0; index < 8; index++) second |= bit(8, size - 1 - index) << index;
  for (let index = 8; index < 15; index++) second |= bit(size - 15 + index, 8) << index;

  let best: { level: QrEccLevel; mask: number } | null = null;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const level of ECC_LEVELS) {
    for (let mask = 0; mask < 8; mask++) {
      const candidate = formatBits(level, mask);
      for (const read of [first, second]) {
        const distance = bitDistance(candidate, read);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = { level, mask };
        }
      }
    }
  }
  return bestDistance <= 3 ? best : null;
}

/**
 * Read a symbol whose modules are already known — the pure heart of the decoder.
 *
 * Exported because it is the honest unit under test: `e2e/qr-decode-unit.spec.ts` drives it with
 * matrices `qrEncode.buildQrMatrix` produced, at every version and every level, so the round trip
 * is proved against an encoder that is itself proved against reportlab. A test that only ever went
 * through the image pipeline would be testing the photograph simulator as much as the decoder.
 *
 * `matrix[row][column]`, true for a dark module, row 0 at the top — the same shape
 * `qrEncode.buildQrMatrix` returns, deliberately, so the two can be passed to one another.
 */
export function decodeQrMatrix(matrix: boolean[][]): QrDecodeResult {
  const size = matrix.length;
  if (!size || matrix.some((row) => row.length !== size)) {
    return { ok: false, reason: "UNREADABLE", message: "Not a QR code." };
  }
  if ((size - 17) % 4 !== 0) {
    return {
      ok: false,
      reason: "UNREADABLE",
      message: "Couldn't measure the QR code. Try again."
    };
  }
  const version = (size - 17) / 4;
  if (version < MIN_VERSION || version > MAX_VERSION) {
    return {
      ok: false,
      reason: "UNSUPPORTED_VERSION",
      message:
        "This isn't a workshop code."
    };
  }

  const format = readFormat(matrix);
  if (!format) {
    return {
      ok: false,
      reason: "UNREADABLE",
      message:
        "Couldn't read the code. Photograph it straight on, without glare."
    };
  }

  // Unmasking is the same call the encoder masked with, so it cannot disagree — see
  // `qrEncode.maskInverts`. It is applied to a copy: the caller's matrix is not ours to modify,
  // and the mirror retry in `decodeQrFromGrey` decodes the same matrix twice.
  const order = qrDataModuleOrder(version);
  const totalCodewords = TOTAL_CODEWORDS[version - 1];
  const totalBits = totalCodewords * 8 + REMAINDER_BITS[version - 1];
  const stream = new Uint8Array(totalCodewords);
  for (let bitIndex = 0; bitIndex < order.length && bitIndex < totalBits; bitIndex++) {
    const [row, column] = order[bitIndex];
    const dark = matrix[row][column] !== maskInverts(format.mask, row, column);
    if (dark) stream[bitIndex >>> 3] |= 0x80 >>> (bitIndex & 7);
  }

  // ── De-interleave. The encoder wove the blocks together codeword by codeword so that a smudge
  // over a contiguous patch damages a little of every block rather than destroying one outright;
  // this is that weave run backwards. It has to agree with `qrEncode.buildCodewords` exactly, which
  // is why both read the same `BLOCKS` row.
  const [ecPerBlock, group1Blocks, group1Data, group2Blocks, group2Data] = BLOCKS[format.level][version - 1];
  const blockCount = group1Blocks + group2Blocks;
  const dataLengths: number[] = [];
  for (let block = 0; block < blockCount; block++) dataLengths.push(block < group1Blocks ? group1Data : group2Data);

  const dataBlocks = dataLengths.map((length) => new Uint8Array(length));
  const ecBlocks = Array.from({ length: blockCount }, () => new Uint8Array(ecPerBlock));

  let cursor = 0;
  const longestData = Math.max(group1Data, group2Data);
  for (let index = 0; index < longestData; index++) {
    for (let block = 0; block < blockCount; block++) {
      if (index < dataLengths[block]) dataBlocks[block][index] = stream[cursor++];
    }
  }
  for (let index = 0; index < ecPerBlock; index++) {
    for (let block = 0; block < blockCount; block++) ecBlocks[block][index] = stream[cursor++];
  }

  // ── Correct each block, then concatenate the data halves in block order.
  const corrected: number[] = [];
  for (let block = 0; block < blockCount; block++) {
    const joined = new Uint8Array(dataLengths[block] + ecPerBlock);
    joined.set(dataBlocks[block], 0);
    joined.set(ecBlocks[block], dataLengths[block]);
    if (correctBlock(joined, ecPerBlock) < 0) {
      return {
        ok: false,
        reason: "DATA_UNRECOVERABLE",
        message:
          "Too blurred or damaged. Photograph it in focus, or type the code."
      };
    }
    for (let index = 0; index < dataLengths[block]; index++) corrected.push(joined[index]);
  }

  const segments = readSegments(Uint8Array.from(corrected), version);
  if (!segments.ok) return { ok: false, reason: segments.reason, message: segments.message };
  return { ok: true, text: segments.text, version, level: format.level, mask: format.mask };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Finding a symbol in a photograph
 *
 * From here down is the detector: the part that turns "somewhere in this picture there is a square"
 * into a grid of modules. It is the part `qrEncode.ts` has no counterpart for.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Turn a greyscale photograph into black and white, for a QR symbol specifically.
 *
 * ── WHY THIS IS NOT `sketchRectify.sauvolaThreshold`, WHICH IS RIGHT NEXT DOOR ───────────────
 *
 * It was, first, and it failed in a way worth recording because the reasoning that led to it is
 * sound and still leads there. Sauvola is the repository's local threshold, it is proved against a
 * lamp gradient in `e2e/sketch-rectify.spec.ts`, and glare on a screen is a lighting problem of
 * exactly that family. But Sauvola's window is sized for a SKETCH — a fraction of the short edge —
 * and it thresholds each pixel against the MEAN and standard deviation of that window. Both
 * assumptions come from the same place: a page of line art is overwhelmingly blank paper, so a
 * window is mostly paper and its mean is the paper's brightness.
 *
 * A QR symbol breaks both. Its dark modules are half its area, so the "paper" mean does not exist;
 * and, fatally, a window can lie ENTIRELY INSIDE ONE DARK MODULE. When it does, the local mean is
 * the dark value itself, the standard deviation is nearly zero, Sauvola's threshold collapses to
 * `m·(1 − k)` — a shade BELOW the very pixels it is judging — and the whole module is classified as
 * light. That is not a subtle loss of quality: the 3×3 core of every finder pattern is the largest
 * solid dark area in the symbol, so it is the first thing to be erased, and the detector then finds
 * no symbol at all in a photograph a person can read at a glance.
 *
 * It is also counter-intuitive in a way that cost real time here: the failure gets WORSE as the
 * photograph gets BETTER. A code filling the frame has modules twenty pixels across and windows
 * comfortably inside them; a code in the corner of a large photograph has modules of four pixels
 * and every window spans several. The first symbols to fail were the clean, close, well-framed
 * ones.
 *
 * ── WHAT THIS DOES INSTEAD ───────────────────────────────────────────────────────────────────
 *
 * A local MIDRANGE — the midpoint between the darkest and lightest pixel of a neighbourhood —
 * computed on a grid of 8-pixel blocks and smoothed across each block's neighbours. Min and max do
 * not care what proportion of the window is dark, which is the whole point, and a block that is
 * genuinely uniform is detected by its tiny dynamic range and given its NEIGHBOURS' threshold
 * instead of a meaningless one of its own. That single rule is what lets a window inside a dark
 * module still come out dark.
 *
 * This is the classic hybrid binarizer, and it is here rather than imported for the reason the rest
 * of this file is: it is thirty lines of arithmetic against a stable specification, and the
 * alternative was a dependency.
 *
 * Output matches the repository's convention and `sauvolaThreshold`'s: 0 is dark, 255 is light.
 */
const BINARIZE_BLOCK = 8;
/** Below this spread, a block has no edge in it and its own midrange means nothing. */
const MIN_DYNAMIC_RANGE = 24;

export function binarizeForQr(plane: GreyPlane): GreyPlane {
  const { width, height, data } = plane;
  const blocksX = Math.max(1, Math.ceil(width / BINARIZE_BLOCK));
  const blocksY = Math.max(1, Math.ceil(height / BINARIZE_BLOCK));
  const level = new Float64Array(blocksX * blocksY);

  // Raster order matters: the uniform-block rule reads the neighbours ABOVE and to the LEFT, which
  // are the ones already decided.
  for (let by = 0; by < blocksY; by++) {
    for (let bx = 0; bx < blocksX; bx++) {
      const x0 = bx * BINARIZE_BLOCK;
      const x1 = Math.min(width, x0 + BINARIZE_BLOCK);
      const y0 = by * BINARIZE_BLOCK;
      const y1 = Math.min(height, y0 + BINARIZE_BLOCK);
      let min = 255;
      let max = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const value = data[y * width + x];
          if (value < min) min = value;
          if (value > max) max = value;
        }
      }

      let threshold = (min + max) / 2;
      if (max - min <= MIN_DYNAMIC_RANGE) {
        // A block with no edge in it: blank paper, or the inside of one large module. Assume light
        // — half the darkest pixel is a threshold every pixel in a light block clears …
        threshold = min / 2;
        // … unless the neighbourhood says this block is darker than the surrounding paper, which is
        // precisely the "inside a dark module" case. Without these four lines the core of every
        // finder pattern reads as light and nothing is ever found.
        if (bx > 0 && by > 0) {
          const neighbours =
            (level[(by - 1) * blocksX + bx] +
              2 * level[by * blocksX + bx - 1] +
              level[(by - 1) * blocksX + bx - 1]) /
            4;
          if (min < neighbours) threshold = neighbours;
        }
      }
      level[by * blocksX + bx] = threshold;
    }
  }

  const out = new Uint8ClampedArray(width * height);
  const smoothed = blocksX >= 5 && blocksY >= 5;
  for (let by = 0; by < blocksY; by++) {
    for (let bx = 0; bx < blocksX; bx++) {
      let threshold: number;
      if (smoothed) {
        // Averaged over the 5×5 block neighbourhood, clamped away from the edges. Smoothing is what
        // stops a hard seam appearing along a block boundary that happens to fall on a module edge
        // — a seam there splits a module in two and the sampler reads whichever half it lands on.
        const left = Math.min(Math.max(bx, 2), blocksX - 3);
        const top = Math.min(Math.max(by, 2), blocksY - 3);
        let total = 0;
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) total += level[(top + dy) * blocksX + left + dx];
        }
        threshold = total / 25;
      } else {
        threshold = level[by * blocksX + bx];
      }

      const x0 = bx * BINARIZE_BLOCK;
      const x1 = Math.min(width, x0 + BINARIZE_BLOCK);
      const y0 = by * BINARIZE_BLOCK;
      const y1 = Math.min(height, y0 + BINARIZE_BLOCK);
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) out[y * width + x] = data[y * width + x] <= threshold ? 0 : 255;
      }
    }
  }

  return { data: out, width, height };
}

/** One candidate finder pattern: a centre, the module size implied by it, and how often it was seen. */
type FinderCandidate = { x: number; y: number; moduleSize: number; count: number };

/** A view of the thresholded image that answers one question fast: is this pixel dark? */
type DarkMap = { dark: (x: number, y: number) => boolean; width: number; height: number };

/**
 * Whether five consecutive runs are the 1:1:3:1:1 of a finder pattern.
 *
 * THE TOLERANCE IS HALF A MODULE, and it is generous on purpose. The runs are measured in whole
 * pixels off a photograph, so a symbol whose module is 3 pixels across can only ever report run
 * lengths in units of a third of a module; a tight tolerance rejects every small symbol, which is
 * to say every symbol photographed from any distance at all. The centre run gets three times the
 * tolerance because it is three times the length and its measurement error scales with it.
 */
function isFinderRatio(counts: readonly number[]): boolean {
  let total = 0;
  for (const count of counts) {
    if (count === 0) return false;
    total += count;
  }
  if (total < 7) return false;
  // Named `moduleWidth` and not `module`: Next's `no-assign-module-variable` rule refuses the bare
  // name outright, because a module-scoped `module` shadows the CommonJS binding the bundler relies
  // on and the failure that produces is a build error a long way from here.
  const moduleWidth = total / 7;
  const tolerance = moduleWidth / 2;
  return (
    Math.abs(moduleWidth - counts[0]) < tolerance &&
    Math.abs(moduleWidth - counts[1]) < tolerance &&
    Math.abs(3 * moduleWidth - counts[2]) < 3 * tolerance &&
    Math.abs(moduleWidth - counts[3]) < tolerance &&
    Math.abs(moduleWidth - counts[4]) < tolerance
  );
}

/** The centre of the middle run, given where the five runs ended. */
function centreFromEnd(counts: readonly number[], end: number): number {
  return end - counts[4] - counts[3] - counts[2] / 2;
}

/**
 * Confirm a candidate by walking the other axis through it, and return the refined centre.
 *
 * WHY A ONE-AXIS MATCH IS NOT ENOUGH. A row of text, a barcode, a striped fabric — a photograph
 * taken in a workshop is full of things that produce a 1:1:3:1:1 run of dark and light along one
 * line and nothing like a finder pattern in two dimensions. Without this check the detector locks
 * onto the weave of a sari in the background and reports a symbol that is not there, which costs
 * far more than a missed scan: it produces a confident wrong answer somewhere downstream.
 *
 * `horizontal` picks the axis to walk. Both directions are needed and they are the same walk with
 * the coordinates swapped, so they are one function rather than two that drift.
 */
function crossCheck(
  map: DarkMap,
  fixed: number,
  start: number,
  maxCount: number,
  originalTotal: number,
  horizontal: boolean
): number {
  const limit = horizontal ? map.width : map.height;
  const at = (position: number) => (horizontal ? map.dark(position, fixed) : map.dark(fixed, position));
  const counts = [0, 0, 0, 0, 0];

  // Backwards from the centre: the middle dark run, then light, then dark.
  let position = start;
  while (position >= 0 && at(position) && counts[2] <= maxCount) {
    counts[2]++;
    position--;
  }
  if (position < 0 || counts[2] > maxCount) return Number.NaN;
  while (position >= 0 && !at(position) && counts[1] <= maxCount) {
    counts[1]++;
    position--;
  }
  if (position < 0 || counts[1] > maxCount) return Number.NaN;
  while (position >= 0 && at(position) && counts[0] <= maxCount) {
    counts[0]++;
    position--;
  }
  if (counts[0] > maxCount) return Number.NaN;

  // Forwards from the centre.
  position = start + 1;
  while (position < limit && at(position) && counts[2] <= maxCount) {
    counts[2]++;
    position++;
  }
  if (position >= limit || counts[2] > maxCount) return Number.NaN;
  while (position < limit && !at(position) && counts[3] <= maxCount) {
    counts[3]++;
    position++;
  }
  if (position >= limit || counts[3] > maxCount) return Number.NaN;
  while (position < limit && at(position) && counts[4] <= maxCount) {
    counts[4]++;
    position++;
  }
  if (counts[4] > maxCount) return Number.NaN;

  // The two axes must agree about how big the thing is. A 40% disagreement is a different feature
  // that happens to overlap, not the same square measured twice.
  const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
  if (5 * Math.abs(total - originalTotal) >= 2 * originalTotal) return Number.NaN;

  return isFinderRatio(counts) ? centreFromEnd(counts, position) : Number.NaN;
}

/** Merge a confirmed centre into the running list, averaging with any candidate it duplicates. */
function recordCandidate(candidates: FinderCandidate[], x: number, y: number, moduleSize: number): void {
  for (const candidate of candidates) {
    if (
      Math.abs(x - candidate.x) <= candidate.moduleSize &&
      Math.abs(y - candidate.y) <= candidate.moduleSize &&
      Math.abs(moduleSize - candidate.moduleSize) <= Math.max(1, candidate.moduleSize * 0.5)
    ) {
      // A running average rather than a replacement: a finder pattern is crossed by several scan
      // rows and each gives a slightly different centre, and their mean is materially more accurate
      // than any one of them. That accuracy is not cosmetic — the whole grid is projected from
      // these three points, so half a pixel of error at the corner is a whole module of error at
      // the far side of a 41-module symbol.
      const next = candidate.count + 1;
      candidate.x = (candidate.x * candidate.count + x) / next;
      candidate.y = (candidate.y * candidate.count + y) / next;
      candidate.moduleSize = (candidate.moduleSize * candidate.count + moduleSize) / next;
      candidate.count = next;
      return;
    }
  }
  candidates.push({ x, y, moduleSize, count: 1 });
}

/**
 * Every plausible finder pattern in the image.
 *
 * The scan steps `rowStep` rows at a time. A finder pattern is seven modules tall, so on any symbol
 * large enough to decode at all several rows cross it — stepping is what keeps a 2-megapixel frame
 * affordable, and the step is derived from the image height rather than fixed so that a small
 * working copy is still scanned densely.
 */
function findFinders(map: DarkMap, rowStep: number): FinderCandidate[] {
  const candidates: FinderCandidate[] = [];

  for (let y = rowStep - 1; y < map.height; y += rowStep) {
    let counts = [0, 0, 0, 0, 0];
    let state = 0;

    const consider = (end: number) => {
      const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
      let centreX = centreFromEnd(counts, end);
      const centreY = crossCheck(map, Math.round(centreX), y, counts[2], total, false);
      if (Number.isNaN(centreY)) return;
      centreX = crossCheck(map, Math.round(centreY), Math.round(centreX), counts[2], total, true);
      if (Number.isNaN(centreX)) return;
      recordCandidate(candidates, centreX, centreY, total / 7);
    };

    for (let x = 0; x < map.width; x++) {
      if (map.dark(x, y)) {
        // A dark pixel while counting light closes that run and opens a dark one.
        if ((state & 1) === 1) state++;
        counts[state]++;
        continue;
      }
      if ((state & 1) === 0) {
        if (state === 4) {
          if (isFinderRatio(counts)) consider(x);
          // Whether or not it matched, the last three runs may be the first three of the next
          // candidate — a finder pattern's trailing dark run is also a following pattern's leading
          // one when two sit side by side. Dropping all five would miss those.
          counts = [counts[2], counts[3], counts[4], 1, 0];
          state = 3;
          continue;
        }
        state++;
        counts[state]++;
        continue;
      }
      counts[state]++;
    }

    // A pattern that runs to the edge of the frame never sees the light pixel that would close it.
    if (state === 4 && isFinderRatio(counts)) consider(map.width);
  }

  return candidates;
}

/** The three finder centres, in the order TL, TR, BL, or null when there is no plausible trio. */
function orderFinders(candidates: FinderCandidate[]): [FinderCandidate, FinderCandidate, FinderCandidate] | null {
  // Confirmed candidates first: one crossing is a coincidence, two is a pattern. When fewer than
  // three survive that bar the unconfirmed ones are used rather than refusing — a symbol at the
  // very edge of legibility is exactly the one a designer is trying hardest to read.
  const confirmed = candidates.filter((candidate) => candidate.count >= 2);
  const pool = (confirmed.length >= 3 ? confirmed : candidates).slice().sort((left, right) => right.count - left.count);
  if (pool.length < 3) return null;
  // Eight is a ceiling on the combinatorics below, not a claim about how many patterns a photograph
  // can contain: 8 candidates is 56 triples, which is free, and 20 would be 1140.
  const limited = pool.slice(0, 8);

  let best: [FinderCandidate, FinderCandidate, FinderCandidate] | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (let a = 0; a < limited.length; a++) {
    for (let b = a + 1; b < limited.length; b++) {
      for (let c = b + 1; c < limited.length; c++) {
        const trio = [limited[a], limited[b], limited[c]];
        const sizes = trio.map((candidate) => candidate.moduleSize);
        const meanSize = (sizes[0] + sizes[1] + sizes[2]) / 3;
        if (meanSize <= 0) continue;
        // Three finder patterns of ONE symbol are the same size as one another. This is the term
        // that stops the detector assembling a trio out of one real symbol and two unrelated marks.
        const sizeSpread = Math.max(...sizes.map((size) => Math.abs(size - meanSize))) / meanSize;
        if (sizeSpread > 0.5) continue;

        const distances = [
          distance(trio[0], trio[1]),
          distance(trio[0], trio[2]),
          distance(trio[1], trio[2])
        ];
        const longest = Math.max(...distances);
        if (longest < meanSize * 7) continue;
        // The two short sides are the symbol's edges and the long one is its diagonal, so the trio
        // should be an isoceles right triangle. Both facts are checked: the short sides equal to
        // each other, and the long side at √2 of them.
        const shortSides = distances.filter((value) => value !== longest);
        if (shortSides.length !== 2) continue;
        const shortMean = (shortSides[0] + shortSides[1]) / 2;
        if (shortMean <= 0) continue;
        const squareness = Math.abs(shortSides[0] - shortSides[1]) / shortMean;
        const diagonal = Math.abs(longest - shortMean * Math.SQRT2) / (shortMean * Math.SQRT2);

        const score = sizeSpread + squareness + diagonal;
        if (score >= bestScore) continue;
        // Whichever vertex is NOT on the longest side is the corner one — the top-left.
        const longestIndex = distances.indexOf(longest);
        const topLeft = longestIndex === 0 ? trio[2] : longestIndex === 1 ? trio[1] : trio[0];
        const others = trio.filter((candidate) => candidate !== topLeft);
        bestScore = score;
        best = [topLeft, others[0], others[1]];
      }
    }
  }

  if (!best) return null;

  // Which of the two remaining is top-right and which is bottom-left is a question of handedness,
  // and getting it wrong transposes the whole symbol. In image coordinates — y growing downward —
  // an unmirrored symbol has a positive cross product going TL→TR→BL; a negative one means the two
  // are the other way round, or that the picture itself is mirrored. Swapping makes the first case
  // right, and `decodeQrFromGrey` retries the transpose for the second.
  const [topLeft, first, second] = best;
  const cross =
    (first.x - topLeft.x) * (second.y - topLeft.y) - (first.y - topLeft.y) * (second.x - topLeft.x);
  return cross >= 0 ? [topLeft, first, second] : [topLeft, second, first];
}

function distance(left: { x: number; y: number }, right: { x: number; y: number }): number {
  return Math.hypot(left.x - right.x, left.y - right.y);
}

/**
 * How far the dark-light-dark run reaches from `from` in the direction of `to`.
 *
 * Walked with Bresenham along the real line between the two finder centres, and that direction is
 * the entire point — see {@link moduleSizeAlong}. Starting inside a finder's dark core, the run
 * covers half that core, the light ring and the outer dark ring: three and a half modules, measured
 * along the axis that was asked about rather than along the image's.
 *
 * Returns NaN when the run leaves the picture before completing, which is a real outcome for a
 * symbol at the very edge of the frame and must not be mistaken for a measurement of zero.
 */
function darkLightDarkRun(map: DarkMap, fromX: number, fromY: number, toX: number, toY: number): number {
  const steep = Math.abs(toY - fromY) > Math.abs(toX - fromX);
  let ax = fromX;
  let ay = fromY;
  let bx = toX;
  let by = toY;
  if (steep) {
    ax = fromY;
    ay = fromX;
    bx = toY;
    by = toX;
  }

  const dx = Math.abs(bx - ax);
  const dy = Math.abs(by - ay);
  let error = -dx / 2;
  const xStep = ax < bx ? 1 : -1;
  const yStep = ay < by ? 1 : -1;
  // 0: inside the first dark run. 1: in the light ring. 2: in the outer dark ring.
  let state = 0;
  const limit = bx + xStep;

  for (let x = ax, y = ay; x !== limit; x += xStep) {
    const realX = steep ? y : x;
    const realY = steep ? x : y;
    if (realX < 0 || realY < 0 || realX >= map.width || realY >= map.height) return Number.NaN;
    // `state === 1` is "expecting dark next"; the comparison reads as "the pixel is what would end
    // the run this state is in".
    if ((state === 1) === map.dark(realX, realY)) {
      if (state === 2) return Math.hypot(x - ax, y - ay);
      state++;
    }
    error += dy;
    if (error > 0) {
      if (y === by) break;
      y += yStep;
      error -= dx;
    }
  }
  return state === 2 ? Math.hypot(bx + xStep - ax, by - ay) : Number.NaN;
}

/**
 * The width of a whole finder pattern, seven modules, measured along one direction.
 *
 * Both halves of the run are measured — outward toward the other finder and outward away from it —
 * and summed, so the answer is symmetric about the centre and does not depend on which of the two
 * finders the walk started from.
 */
function finderWidthAlong(map: DarkMap, from: FinderCandidate, to: FinderCandidate): number {
  const forward = darkLightDarkRun(map, Math.round(from.x), Math.round(from.y), Math.round(to.x), Math.round(to.y));
  const backward = darkLightDarkRun(
    map,
    Math.round(from.x),
    Math.round(from.y),
    Math.round(from.x - (to.x - from.x)),
    Math.round(from.y - (to.y - from.y))
  );
  // The centre pixel is counted by both halves.
  return forward + backward - 1;
}

/**
 * The module size along the line joining two finder patterns.
 *
 * ── WHY THIS IS DIRECTIONAL, AND WHY AVERAGING THE TWO DIRECTIONS IS EXACT ───────────────────
 *
 * A photograph of a card held at an angle compresses the symbol along one axis. Measure the module
 * from a HORIZONTAL scan line and you learn the module size across the top edge; use that same
 * number down the vertical edge, which is 27% shorter, and the symbol is measured as having a
 * quarter fewer rows than it has. That is not a small error — the version comes out wrong, the grid
 * is the wrong size, and every codeword after the first is read from the wrong place. It is the
 * defect that failed the two skewed cases in `e2e/qr-decode-unit.spec.ts` while every flat one
 * passed, which is a diagnostic worth remembering: on this pipeline a *better* photograph, taken
 * closer and squarer, fails differently from a worse one.
 *
 * The fix is to measure along each edge and average the two, and the averaging is EXACT rather than
 * a tolerable approximation. Writing m₁ and m₂ for the module sizes along the two edges and n for
 * the true number of modules between the finder centres, the two estimates against the averaged
 * module size are 2n·m₁/(m₁+m₂) and 2n·m₂/(m₁+m₂); their mean is n, for any m₁ and m₂ whatever. The
 * anisotropy cancels algebraically, which is why this holds for a steeply angled photograph and not
 * merely for a slightly tilted one.
 *
 * `fallback` is the module size the detector estimated from the scan that found the pattern, used
 * when the run walks off the edge of the picture — a symbol against the frame edge is still worth
 * trying to read.
 */
function moduleSizeAlong(map: DarkMap, from: FinderCandidate, to: FinderCandidate, fallback: number): number {
  const first = finderWidthAlong(map, from, to);
  const second = finderWidthAlong(map, to, from);
  if (Number.isNaN(first) && Number.isNaN(second)) return fallback;
  if (Number.isNaN(first)) return second / 7;
  if (Number.isNaN(second)) return first / 7;
  // Seven modules per pattern, two patterns.
  return (first + second) / 14;
}

/**
 * The symbol's size in modules, from the distance between finder centres.
 *
 * Every legal QR is 17 + 4·version modules a side, so the answer must be 1 modulo 4. A measurement
 * one out is snapped; a measurement two out is refused rather than snapped in an arbitrary
 * direction, because guessing there is guessing about the symbol's VERSION, and a version wrong by
 * one reads every codeword from the wrong offset and produces confident nonsense.
 */
function estimateDimension(
  topLeft: FinderCandidate,
  topRight: FinderCandidate,
  bottomLeft: FinderCandidate,
  moduleSize: number
): number | null {
  if (!(moduleSize > 0)) return null;
  const across = Math.round(distance(topLeft, topRight) / moduleSize);
  const down = Math.round(distance(topLeft, bottomLeft) / moduleSize);
  let dimension = Math.round((across + down) / 2) + 7;
  switch (dimension & 0x03) {
    case 0:
      dimension++;
      break;
    case 2:
      dimension--;
      break;
    case 3:
      return null;
    default:
      break;
  }
  return dimension >= 21 ? dimension : null;
}

/**
 * Hunt for the alignment pattern near where the geometry says it should be.
 *
 * WHY IT IS WORTH HUNTING FOR. Three points determine an affine map — a map with no perspective in
 * it at all — and an affine map is exactly right only for a symbol photographed square-on. A card
 * held at an angle, or a code on a screen photographed from a chair, is a projective view: parallel
 * edges converge, and an affine grid laid over it drifts progressively across the symbol until, a
 * module or two out at the far corner, every sampled bit past the middle is wrong. The alignment
 * pattern is the fourth point that pins that corner down, and it is the difference between reading
 * a photograph and reading a scan.
 *
 * Returns null when it is not found, which is not a failure — the caller falls back to the affine
 * estimate, which is correct for a flat photograph and is what version 1 symbols always use because
 * they carry no alignment pattern at all.
 *
 * ── THE WINNER IS THE MOST-CONFIRMED CANDIDATE, NOT THE NEAREST ONE ──────────────────────────
 *
 * This used to keep whichever match landed closest to `estimate`, and that is the wrong question to
 * ask, for a reason that is easy to miss: `estimate` is the PARALLELOGRAM guess, and the whole
 * reason this function is being called is that the parallelogram guess is wrong under perspective.
 * Ranking by distance to it therefore ranks by agreement with the very error being corrected — and
 * on a steeply angled card the true pattern loses to a lucky data module by several modules.
 *
 * The honest discriminator is the one {@link orderFinders} already uses on finder patterns: one
 * crossing is a coincidence, several is a structure. A real 5x5 alignment pattern is crossed by
 * roughly `moduleSize` scan rows through its centre module, and every one of them produces the same
 * triple and the same vertical confirmation; a data module that happens to look like a light ring
 * satisfies it on one row by luck and not on its neighbours. So the hits are CLUSTERED and COUNTED,
 * exactly as {@link recordCandidate} does, and the cluster with the most members wins. Distance to
 * the estimate is kept only as the tie-break between equally-confirmed clusters, where it is a fair
 * question again.
 *
 * Clustering pays a second time: averaging a cluster's members is worth a few tenths of a pixel at
 * the corner the whole homography is pinned by, and the grid is projected across the symbol from it.
 */
type AlignmentCandidate = { x: number; y: number; count: number };

/** Merge a confirmed alignment centre into the running list. {@link recordCandidate}, one shape simpler. */
function recordAlignment(candidates: AlignmentCandidate[], x: number, y: number, moduleSize: number): void {
  for (const candidate of candidates) {
    if (Math.abs(x - candidate.x) <= moduleSize && Math.abs(y - candidate.y) <= moduleSize) {
      const next = candidate.count + 1;
      candidate.x = (candidate.x * candidate.count + x) / next;
      candidate.y = (candidate.y * candidate.count + y) / next;
      candidate.count = next;
      return;
    }
  }
  candidates.push({ x, y, count: 1 });
}

/**
 * The x of every alignment-pattern centre this row could be crossing.
 *
 * ── THE RUN PATTERN MATCHED IS LIGHT-DARK-LIGHT, AND THE PARITY IS THE WHOLE POINT ───────────
 *
 * A row through the middle of a 5x5 alignment pattern reads
 *
 *     [ … data … ] [ring D] [ring L] [centre D] [ring L] [ring D] [ … data … ]
 *
 * and the obvious thing to match is the DARK-LIGHT-DARK in the middle of that, one module each.
 * This did exactly that, and it was wrong in a way no amount of tolerance-tuning fixes: the two
 * dark ring runs are NOT one module wide in general, because the ring is one module of dark with
 * arbitrary DATA beyond it, and a dark data module against the ring fuses with it into a run two or
 * three modules long. The pattern was then rejected on every row — measured, on the mirrored case
 * in `e2e/qr-decode-unit.spec.ts`: runs of 15px, 8px, 7px against a 7.6px module, refused because
 * the leading run was two modules of fused dark rather than one.
 *
 * The light runs have no such freedom. Each is bounded by the dark ring on one side and the dark
 * centre on the other, both of which are part of the pattern — so a LIGHT-DARK-LIGHT triple of one
 * module each is invariant no matter what the surrounding data does, and the dark run in the middle
 * of it IS the centre module, with no ambiguity about which of two dark runs was matched. (The
 * D-L-D form had that ambiguity too: it matches once with the centre in the middle and again with
 * the centre as its leading run, and the second match returns the ring's x, not the centre's.)
 *
 * Dark is still required immediately beyond both light runs — that is the ring, and requiring it is
 * what keeps this from matching an isolated dark module sitting in blank space.
 *
 * EVERY RUN IS MEASURED AGAINST THE MODULE SIZE THE DETECTOR ALREADY KNOWS, never against the
 * triple's own mean. {@link isFinderRatio} may normalise against `total / 7` because a finder's
 * 1:1:3:1:1 is a SHAPE — the long middle run pins the scale. An alignment pattern's 1:1:1 is
 * scale-FREE: three consecutive runs of any equal length satisfy it, so a self-normalised test asks
 * only "are these three runs similar to each other", which the symbol's data area answers yes to
 * constantly. It did not even ask that strictly — with the mean free to float, a 1:2:1 triple
 * (`counts` of [10, 21, 10] against a 9.4px module) passed, because the mean drifted up to 13.7 and
 * every run then sat inside a tolerance computed from the drifted mean.
 *
 * Half a module of tolerance, matching {@link isFinderRatio}. It cannot be tighter: the runs are
 * measured in whole pixels off a photograph, and at the far corner of a steeply angled card the
 * local module genuinely differs from the average.
 */
function alignmentRowHits(map: DarkMap, y: number, left: number, right: number, moduleSize: number): number[] {
  // The row as alternating runs. Built once rather than walked by a state machine, because the test
  // below needs to look at a run's NEIGHBOURS on both sides, which a forward-only machine cannot do
  // without carrying the previous two runs — the arrangement this replaced, and where its defects
  // hid.
  const runs: { dark: boolean; start: number; length: number }[] = [];
  for (let x = left; x <= right; x++) {
    const dark = map.dark(x, y);
    const last = runs[runs.length - 1];
    if (last && last.dark === dark) last.length++;
    else runs.push({ dark, start: x, length: 1 });
  }

  const tolerance = moduleSize * 0.5;
  const oneModule = (length: number) => Math.abs(length - moduleSize) < tolerance;
  const hits: number[] = [];
  // From index 1 to length-2: the triple needs a run on each side of it to be the ring, and a run
  // clipped by the window edge cannot be trusted to be a whole one.
  for (let index = 2; index + 2 < runs.length; index++) {
    const centre = runs[index];
    if (!centre.dark) continue;
    const before = runs[index - 1];
    const after = runs[index + 1];
    if (!oneModule(centre.length) || !oneModule(before.length) || !oneModule(after.length)) continue;
    // The ring beyond each light run. Its LENGTH is deliberately not checked — see the header.
    if (!runs[index - 2].dark || !runs[index + 2].dark) continue;
    hits.push(centre.start + centre.length / 2);
  }
  return hits;
}

function findAlignment(map: DarkMap, estimate: Point, moduleSize: number): Point | null {
  for (const allowance of [4, 8]) {
    const radius = Math.max(3, Math.round(moduleSize * allowance));
    const left = Math.max(0, Math.floor(estimate.x - radius));
    const right = Math.min(map.width - 1, Math.ceil(estimate.x + radius));
    const top = Math.max(0, Math.floor(estimate.y - radius));
    const bottom = Math.min(map.height - 1, Math.ceil(estimate.y + radius));
    if (right - left < 3 || bottom - top < 3) continue;
    if (DEBUG_DETECTION.on) DEBUG_DETECTION.window = { left, right, top, bottom, estimate, moduleSize };

    const candidates: AlignmentCandidate[] = [];
    for (let y = top; y <= bottom; y++) {
      for (const hit of alignmentRowHits(map, y, left, right, moduleSize)) {
        const centreY = alignmentCentreY(map, Math.round(hit), y, moduleSize);
        if (DEBUG_DETECTION.on) (DEBUG_DETECTION.tries ??= []).push({ x: hit, y, centreY });
        if (!Number.isNaN(centreY)) recordAlignment(candidates, hit, centreY, moduleSize);
      }
    }

    // Most-confirmed first; distance to the estimate only separates equals. See the header.
    let best: AlignmentCandidate | null = null;
    for (const candidate of candidates) {
      if (
        !best ||
        candidate.count > best.count ||
        (candidate.count === best.count && distance(candidate, estimate) < distance(best, estimate))
      ) {
        best = candidate;
      }
    }
    if (DEBUG_DETECTION.on) DEBUG_DETECTION.tries = candidates.map((candidate) => ({ ...candidate }));
    if (best) return { x: best.x, y: best.y };
  }
  return null;
}

/**
 * Confirm an alignment-pattern candidate by its STRUCTURE up and down, and refine its centre.
 *
 * WHY A STRUCTURAL CHECK AND NOT MERELY A SIZE ONE. The search window sits over the middle of the
 * symbol, which is solid data — and a QR's data area is FULL of dark-light-dark triples one module
 * wide, because that is what half-random modules look like. A check that only asked "is the dark
 * run about one module tall" accepted dozens of them, the nearest to the estimate won, and the
 * fourth point of the homography was then a random data module a few modules from the truth. That
 * did not fail loudly: it produced a grid slightly askew, sampled a hundred modules wrongly, and
 * came out as "too damaged to repair" for a symbol that was perfectly clean — which is how this
 * ended up being diagnosed by drawing the grid rather than by reading the message.
 *
 * The alignment pattern's real signature is that its centre module has LIGHT on all four sides and
 * DARK beyond that: it is the only 3x3 light ring in the symbol. Requiring the light ring and the
 * dark ring beyond it, one module each, on the vertical axis — the horizontal axis having already
 * been matched by the caller — is what distinguishes it from data.
 *
 * Returns the refined centre, or NaN.
 */
function alignmentCentreY(map: DarkMap, x: number, y: number, moduleSize: number): number {
  const near = (run: number) => Math.abs(run - moduleSize) <= moduleSize * 0.7;
  const ceiling = Math.ceil(moduleSize * 2.5);

  // The dark centre module, measured from the sample point in both directions.
  let up = 0;
  let position = y;
  while (position >= 0 && map.dark(x, position) && up <= ceiling) {
    up++;
    position--;
  }
  if (up === 0 || up > ceiling || position < 0) return Number.NaN;
  const topOfCentre = position;

  let down = 0;
  position = y + 1;
  while (position < map.height && map.dark(x, position) && down <= ceiling) {
    down++;
    position++;
  }
  if (down > ceiling) return Number.NaN;
  const bottomOfCentre = position;
  if (!near(up + down)) return Number.NaN;

  // The light ring above, then the dark ring beyond it.
  let lightAbove = 0;
  position = topOfCentre;
  while (position >= 0 && !map.dark(x, position) && lightAbove <= ceiling) {
    lightAbove++;
    position--;
  }
  if (!near(lightAbove) || position < 0 || !map.dark(x, position)) return Number.NaN;

  // And the same below.
  let lightBelow = 0;
  position = bottomOfCentre;
  while (position < map.height && !map.dark(x, position) && lightBelow <= ceiling) {
    lightBelow++;
    position++;
  }
  if (!near(lightBelow) || position >= map.height || !map.dark(x, position)) return Number.NaN;

  return y + 0.5 - up + (up + down) / 2;
}

/**
 * Read the module grid out of the photograph, given the map from grid coordinates to image ones.
 *
 * Sampling is BILINEAR OVER THE THRESHOLDED image rather than nearest-neighbour, which makes each
 * module a weighted vote of the four pixels around its centre instead of the single pixel that
 * happened to land there. On a symbol whose modules are three or four pixels across — which is what
 * a code occupying a corner of a photograph looks like — one stray pixel per module is the
 * difference between a clean read and a block Reed-Solomon cannot repair.
 */
function sampleGrid(plane: GreyPlane, transform: Homography, dimension: number): boolean[][] {
  const matrix: boolean[][] = [];
  for (let row = 0; row < dimension; row++) {
    const line: boolean[] = [];
    for (let column = 0; column < dimension; column++) {
      const point = applyHomography(transform, { x: column + 0.5, y: row + 0.5 });
      // 255 outside the image: beyond the photograph is the quiet zone as far as this is concerned,
      // which is light. Reading it as dark would fabricate a border of modules around the symbol.
      line.push(bilinearSample(plane, point.x, point.y, 255) < 128);
    }
    matrix.push(line);
  }
  return matrix;
}

/** The transpose of a module matrix — the un-mirroring retry. See {@link decodeQrFromGrey}. */
function transpose(matrix: boolean[][]): boolean[][] {
  return matrix.map((row, rowIndex) => row.map((_, columnIndex) => matrix[columnIndex][rowIndex]));
}

/**
 * Find and read a QR symbol in a greyscale image.
 *
 * ── THE PIPELINE, AND WHAT EACH STAGE IS FOR ─────────────────────────────────────────────────
 *
 * 1. **Sauvola local thresholding** (`lib/sketchRectify.ts`). Not a global threshold, and the
 *    reason is the whole reason this decoder can read a photograph of a screen: a screen
 *    photographed at an angle has a specular highlight across part of it, and a monitor's own
 *    brightness falls off toward its edges. There is no single grey level that separates dark
 *    modules from light ones across a glare — any level dark enough to see modules inside the
 *    highlight reads half the unglared symbol as dark. A local threshold compares every pixel with
 *    the paper immediately around it, so the highlight cancels instead of being fought. That
 *    function's own header proves the point on a lamp gradient, in the test suite.
 * 2. **Finder detection**, twice confirmed, on both axes. See {@link crossCheck}.
 * 3. **Alignment pattern**, for the perspective a card held at an angle really has. See
 *    {@link findAlignment}.
 * 4. **Projective sampling** through `photoMeasure.solveHomography` — the same hand-written direct
 *    linear transform the measuring feature uses, already proved in `e2e/photo-measure.spec.ts`
 *    against a fifth point that took no part in the fit.
 * 5. **{@link decodeQrMatrix}**, and if that fails at the format strip, the same grid TRANSPOSED.
 *    A transpose is a mirror about the diagonal, which is what a photograph taken with a front
 *    camera in mirror mode, or of a reflection in a window, actually is. It costs one retry and it
 *    turns a class of "unreadable" into a read.
 *
 * The failure carries a {@link QrRegion} whenever the symbol was FOUND but not READ, which is what
 * lets `lib/qrImageDecode.ts` cut that region out of the full-resolution original and try again —
 * the mechanism for a code that is a small part of a large picture.
 */
export const DEBUG_DETECTION: { on: boolean; last: unknown; tries?: unknown[]; window?: unknown } = {
  on: false,
  last: null
};

export function decodeQrFromGrey(plane: GreyPlane): QrDecodeResult {
  if (plane.width < 21 || plane.height < 21) {
    return {
      ok: false,
      reason: "NO_SYMBOL",
      message: "Too small. Use the original photo."
    };
  }

  const binary = binarizeForQr(plane);
  const map: DarkMap = {
    width: binary.width,
    height: binary.height,
    dark: (x, y) => binary.data[y * binary.width + x] === 0
  };

  // Two passes at different densities. The dense pass is what finds a small symbol; the coarse one
  // is what keeps a large photograph affordable, and it usually succeeds first.
  const coarseStep = Math.max(1, Math.floor(Math.min(map.width, map.height) / 160));
  let candidates = findFinders(map, coarseStep);
  let finders = orderFinders(candidates);
  if (!finders && coarseStep > 1) {
    candidates = findFinders(map, 1);
    finders = orderFinders(candidates);
  }

  if (!finders) {
    return {
      ok: false,
      reason: "NO_SYMBOL",
      message:
        "No QR code found. Get the whole square in focus, or crop to it."
    };
  }

  const [topLeft, topRight, bottomLeft] = finders;
  const region = regionOf(finders, plane);
  // Measured ALONG each edge and averaged, never taken from the scan direction alone — see
  // {@link moduleSizeAlong} for why that distinction decides whether a tilted card reads at all.
  const scanned = (topLeft.moduleSize + topRight.moduleSize + bottomLeft.moduleSize) / 3;
  const moduleSize =
    (moduleSizeAlong(map, topLeft, topRight, scanned) + moduleSizeAlong(map, topLeft, bottomLeft, scanned)) / 2;
  const dimension = estimateDimension(topLeft, topRight, bottomLeft, moduleSize);
  if (!dimension) {
    return {
      ok: false,
      reason: "UNREADABLE",
      message:
        "Couldn't read the code. Photograph it straight on, or type the code.",
      region
    };
  }
  const version = (dimension - 17) / 4;
  if (version < MIN_VERSION || version > MAX_VERSION) {
    return {
      ok: false,
      reason: "UNSUPPORTED_VERSION",
      message:
        "This isn't a workshop code.",
      region
    };
  }

  // The grid coordinates of the three finder centres are fixed by the standard: each finder is
  // seven modules across with its centre three and a half in from its own corner.
  const gridPoints: Point[] = [
    { x: 3.5, y: 3.5 },
    { x: dimension - 3.5, y: 3.5 },
    { x: 3.5, y: dimension - 3.5 }
  ];
  const imagePoints: Point[] = [
    { x: topLeft.x, y: topLeft.y },
    { x: topRight.x, y: topRight.y },
    { x: bottomLeft.x, y: bottomLeft.y }
  ];

  let fourthGrid: Point;
  let fourthImage: Point;
  const alignmentCentres = ALIGNMENT_CENTRES[version - 1];
  const alignmentModule = alignmentCentres.length ? alignmentCentres[alignmentCentres.length - 1] : 0;
  let alignment: Point | null = null;
  if (alignmentModule > 0) {
    // Where the bottom-right of the symbol would be if the three finders were three corners of a
    // parallelogram, walked back three modules to the alignment pattern's own centre.
    const parallelogram = {
      x: topRight.x - topLeft.x + bottomLeft.x,
      y: topRight.y - topLeft.y + bottomLeft.y
    };
    const back = 1 - 3 / (dimension - 7);
    alignment = findAlignment(
      map,
      { x: topLeft.x + back * (parallelogram.x - topLeft.x), y: topLeft.y + back * (parallelogram.y - topLeft.y) },
      moduleSize
    );
  }

  if (alignment) {
    fourthGrid = { x: alignmentModule + 0.5, y: alignmentModule + 0.5 };
    fourthImage = alignment;
  } else {
    // No alignment pattern, or none found: the fourth point is the parallelogram estimate, which
    // makes the solve produce exactly the affine map three points determine. Written this way
    // rather than as a separate three-point path so there is ONE sampling route to keep correct.
    fourthGrid = { x: dimension - 3.5, y: dimension - 3.5 };
    fourthImage = { x: topRight.x - topLeft.x + bottomLeft.x, y: topRight.y - topLeft.y + bottomLeft.y };
  }

  const transform = solveHomography([...gridPoints, fourthGrid], [...imagePoints, fourthImage]);
  if (!transform) {
    return {
      ok: false,
      reason: "UNREADABLE",
      message:
        "Too distorted. Photograph it straight on, or type the code.",
      region
    };
  }

  if (DEBUG_DETECTION.on) {
    DEBUG_DETECTION.last = { topLeft, topRight, bottomLeft, moduleSize, dimension, alignment, fourthGrid, fourthImage };
  }
  const matrix = sampleGrid(binary, transform, dimension);
  const straight = decodeQrMatrix(matrix);
  if (straight.ok) return straight;

  // The mirror retry. Only worth it when the symbol was located and the READING failed — a
  // transposed grid of nothing is still nothing.
  const mirrored = decodeQrMatrix(transpose(matrix));
  if (mirrored.ok) return mirrored;

  return { ...straight, region };
}

/** The bounding box of a located symbol, with a margin, as fractions of the plane. */
function regionOf(
  finders: [FinderCandidate, FinderCandidate, FinderCandidate],
  plane: GreyPlane
): QrRegion {
  const [topLeft, topRight, bottomLeft] = finders;
  // The fourth corner is not a detected point, so the box is built from the parallelogram estimate
  // as well as the three real ones — otherwise it clips the bottom-right quarter of the symbol off,
  // which is the quarter a re-cut at higher resolution most needs.
  const corners = [
    topLeft,
    topRight,
    bottomLeft,
    { x: topRight.x - topLeft.x + bottomLeft.x, y: topRight.y - topLeft.y + bottomLeft.y }
  ];
  // TEN MODULES, and the number is load-bearing rather than generous. The finder CENTRES are three
  // and a half modules inside the symbol's own edge, and the standard's quiet zone is four more
  // outside it — so a margin under seven and a half modules cuts the quiet zone off, and a symbol
  // re-cut without its quiet zone is a symbol the second attempt cannot find either. Ten leaves
  // room for the estimate being slightly off.
  const margin = topLeft.moduleSize * 10;
  const left = Math.min(...corners.map((corner) => corner.x)) - margin;
  const right = Math.max(...corners.map((corner) => corner.x)) + margin;
  const top = Math.min(...corners.map((corner) => corner.y)) - margin;
  const bottom = Math.max(...corners.map((corner) => corner.y)) + margin;

  const clamp = (value: number) => Math.min(1, Math.max(0, value));
  const x = clamp(left / plane.width);
  const y = clamp(top / plane.height);
  return {
    x,
    y,
    width: clamp(right / plane.width) - x,
    height: clamp(bottom / plane.height) - y
  };
}
