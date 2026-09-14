/**
 * Measuring a real-world dimension off a photograph, from a reference object of known size.
 *
 * WHY THIS IS ARITHMETIC AND NOT A MODEL, AND WHY THAT IS THE POINT
 *
 * Every dimension on a product or a tool record — `lengthInches`, `breadthInches`, `heightInches` —
 * is typed off a tape measure today, and a mistyped dimension does not stay put: it is multiplied
 * into a cost sheet, printed on the record's own page by `services/record_fields.py`, and read by
 * somebody costing a production run from a document nobody can re-measure. So the feature has to be
 * available at the moment the object is still in the researcher's hands, which in this application
 * means a village with no connection for days. Everything below is plane projective geometry over
 * four to eight marked points — no network, no model, no image decoding, and no matrix library. It
 * runs in a browser, it runs in Node, and it ports to Kotlin unchanged, which is the only
 * arrangement under which the phone in the courtyard and the laptop at the desk can agree about a
 * number that ends up in a cost sheet.
 *
 * THE TWO METHODS, AND WHY BOTH HAVE TO EXIST
 *
 *  1. {@link measureBySameScale} — the honest simple one. Mark the two ends of something whose
 *     length you know, mark the two ends of the thing you want, and the answer is a ratio of pixel
 *     distances. IT IS ONLY TRUE WHEN BOTH LIE IN THE SAME PLANE, PARALLEL TO THE SENSOR. That is
 *     not a footnote: a grid sheet lying flat on a table and a pot standing on it are not in the same
 *     plane, and the pot's height read this way is wrong by however much the perspective happens to
 *     be — silently, plausibly, and by an amount nothing downstream can detect. The caller is
 *     required to say this on screen (see `components/media/RecordPhotoMeasure.tsx`), because a
 *     researcher who discovers it from a cost sheet discovers it too late.
 *
 *  2. {@link measureByRectification} — the four-point correction, for when 1 is not true. Mark the
 *     four corners of a rectangle whose real size you know (a block of grid squares, an A4 sheet, a
 *     scale card), and the homography that carries those four image points onto that rectangle
 *     carries EVERY point of that plane onto its true position. Measuring in the rectified plane is
 *     then exact, whatever the tilt, and {@link Measurement.tiltCorrection} reports how much the tilt
 *     was worth — which is the number that tells a researcher whether method 1 would have done.
 *
 * NOTHING HERE IS ALLOWED TO RETURN A NUMBER IT CANNOT STAND BEHIND
 *
 * Every entry point returns a {@link Measurement} or a {@link Refusal}, and the refusals are the
 * larger half of the module on purpose. A measurement is refused when the reference is too short to
 * measure against, when the four corners are collinear or crossed, when a perturbation of the marks
 * lands on a degenerate configuration, and whenever any input is not finite. A refusal carries a
 * `reason` and NO `value` field at all, so there is nothing for a caller to read by accident.
 *
 * THIS IS ALSO WHY THE FLOOR IS A REFUSAL AND NOT A WARNING. The house rule is that nothing may
 * block fieldwork — but that rule is about RECORDING WHAT WAS SEEN, and this module records nothing.
 * It proposes a computed number, and the researcher always has the tape measure and the keyboard. A
 * proposal whose error bar is wider than the answer is not a weaker measurement, it is a worse
 * outcome than none, because it is the confidence that travels.
 *
 * PURITY IS THE POINT, NOT AN AESTHETIC. No React, no DOM, no `fetch`, no `File`. That is what lets
 * every case in `e2e/photo-measure-unit.spec.ts` be a construction with a known answer rather than a
 * screenshot comparison, and it is what makes the Kotlin port a transcription rather than a rewrite.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/** A point in NATURAL IMAGE PIXELS — never in screen or CSS pixels. See {@link markSigmaForDisplayScale}. */
export type Point = { x: number; y: number };

/**
 * A 3x3 homography, row-major: `[h11, h12, h13, h21, h22, h23, h31, h32, h33]`.
 *
 * A flat tuple rather than a nested array because it is passed around, compared and ported, and a
 * `number[][]` invites an aliasing bug the day somebody reuses a row.
 */
export type Homography = readonly [number, number, number, number, number, number, number, number, number];

/** The units a reference or a record column can plausibly declare. Anything else is refused, never assumed. */
export type LengthUnit = "mm" | "cm" | "m" | "in";

/**
 * Every length unit this module knows, and how many millimetres one of each is.
 *
 * Exported because it is also the membership test elsewhere: a caller asks this map whether a
 * column's declared unit is a length before offering that column as somewhere a measurement may be
 * proposed. One map, so a unit this module cannot convert can never become a destination it writes
 * into.
 *
 * Millimetres is the base because it makes mm↔cm↔m exact in binary floating point.
 */
export const LENGTH_UNITS: Record<LengthUnit, number> = { mm: 1, cm: 10, m: 1000, in: 25.4 };

export type Refusal = {
  ok: false;
  /**
   * A sentence for the researcher, not a code. It says what is wrong with the MARKS, because that is
   * the only thing they can do anything about.
   */
  reason: string;
};

export type MeasurementMethod = "SCALE" | "RECTIFIED";

export type Measurement = {
  ok: true;
  method: MeasurementMethod;
  /** The measured length, in {@link Measurement.unit}. */
  value: number;
  unit: LengthUnit;
  /** One standard deviation, in the same unit. Never zero — see {@link propagateUncertainty}. */
  uncertainty: number;
  /** `uncertainty / value`, precomputed because every caller wants to show a percentage. */
  relativeUncertainty: number;
  /** Pixel length of the reference the scale came from — the number the error bar hangs off. */
  referencePixels: number;
  /** Pixel length of the thing being measured, straight-line in the image. */
  targetPixels: number;
  /**
   * RECTIFIED only: what the SAME marks would have said under {@link measureBySameScale}, using the
   * rectangle's first marked edge as the scale bar.
   *
   * This exists so the four-point method can justify itself. A researcher who marks four corners and
   * is told "correcting for the tilt changed this by 0.2%" has learned that two marks would have
   * done; one told "by 8%" has learned why the extra two were worth it.
   */
  uncorrectedValue?: number;
  /** RECTIFIED only: `|value - uncorrectedValue| / value`. */
  tiltCorrection?: number;
};

export type MeasureResult = Measurement | Refusal;

/* ────────────────────────────────────────────────────────────────────────────
 * How the answer describes ITSELF to whatever stores it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What this module is, in the vocabulary the RECORD speaks: `PHOTO_GEOMETRY`.
 *
 * ── TWO THINGS ARE CALLED "METHOD" HERE AND THEY ARE DIFFERENT AXES ─────────────────────────────
 *
 * {@link MeasurementMethod} above — `"SCALE" | "RECTIFIED"` — is WHICH GEOMETRY was solved, and it
 * matters only to somebody re-deriving the number. The API's `measurement_provenance` service has an
 * enum of the same name whose members are `UNRECORDED` / `TYPED` / `PHOTO_GEOMETRY` / `VISION_MODEL`,
 * and that is a different question: WHAT KIND OF PROCESS produced the number, which is what decides
 * whether a later reader may act on it. Everything this module can return is `PHOTO_GEOMETRY` under
 * that second question, and both of its geometries are — which is exactly why the method marker
 * keeps them on a separate `technique` key instead of adding two more enum members.
 *
 * The collision is not renamed away because both names are right in their own file and both are
 * pinned by tests on both sides. It is written down instead, once, here.
 */
export const PHOTO_GEOMETRY = "PHOTO_GEOMETRY";

/**
 * The one fact a stored dimension needs about where it came from, in the shape the server reads.
 *
 * ── WHY THE AUTHORITY FILE OWNS THIS AND NOT THE SCREEN ─────────────────────────────────────────
 *
 * WHAT WAS WRONG. This client computed nothing on device at all, and the one measuring aid it had —
 * `components/media/GridMeasurement.tsx` — dropped the provenance on the floor: the vision model's
 * number went into the field alone. So a `lengthInches` of 24.5 read back a year later was
 * indistinguishable three ways: a number somebody typed off a tape, a number this module computed
 * from marks on a photograph, and a number a vision model guessed off a grid sheet. Worse than
 * indistinguishable — `records.merge_field_provenance` stamps every changed field with the
 * `{by, byName, at}` of whoever pressed Save and the dimension columns are not in its
 * `PROVENANCE_SKIP_FIELDS`, so the row POSITIVELY ASSERTED that a named human had measured it.
 *
 * WHY HERE. The marker is a statement about HOW THIS MODULE WORKS, so it belongs in the module and
 * not in whichever component happens to render the button this week. Both record forms want it, the
 * Kotlin twin needs the identical two facts, and a marker composed at each screen is two chances to
 * spell `PHOTO_GEOMETRY` differently in a string that a database keeps for the life of the record.
 *
 * IT IS A CLAIM, NOT A PROOF, and the server treats it as one: anything it cannot read degrades to
 * `UNRECORDED` and never to `TYPED`. Nothing here is trusted because it is well-formed.
 */
export type MeasurementMarker = {
  method: typeof PHOTO_GEOMETRY;
  technique: MeasurementMethod;
};

/**
 * The marker for a measurement this module produced. Total, pure, and the only place the two axes
 * above are joined.
 *
 * Takes a {@link Measurement} and not a {@link MeasureResult} on purpose: a refusal has no method to
 * describe, and accepting one here would mean inventing an answer for the case where the module
 * declined to give one. The type makes that unrepresentable rather than handled — the same
 * discipline as `Refusal` carrying no `value` property at all.
 *
 * Nothing about the ERROR BAR travels with this. `uncertainty` is spent on screen, before the accept,
 * because the record has a column for the dimension and none for the doubt. The marker says how the
 * number was arrived at; it does not say how well.
 */
export function methodMarker(measurement: Measurement): MeasurementMarker {
  return { method: PHOTO_GEOMETRY, technique: measurement.method };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Constants, each of which is an argument rather than a preference
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Below this pixel length a reference is refused outright.
 *
 * DERIVED, not chosen. The scale's relative uncertainty is `distanceSigma(σ) / referencePixels`, so
 * at the default {@link DEFAULT_MARK_SIGMA_PX} of 2 px a 40 px reference already carries 7% doubt
 * from the reference alone — a 12 cm dimension proposed as "12 cm ± 0.85 cm". That is the widest bar
 * this module is willing to put a number next to. Anything shorter is a grid sheet photographed from
 * too far away, and the fix is one the researcher can act on in the two seconds they are still
 * holding the object: step closer, or zoom in and re-place the marks.
 */
export const MIN_REFERENCE_PIXELS = 40;

/**
 * How precisely a person places one mark, in SCREEN pixels, once they have zoomed in far enough to
 * see what they are aiming at.
 *
 * Not a measurement of anybody's hands — it is the smallest displacement that is visible on a screen
 * at all, and it is deliberately not smaller. Claiming sub-pixel marking accuracy would narrow every
 * error bar in the feature by pure assertion.
 */
export const SCREEN_MARK_SIGMA_PX = 1.5;

/**
 * The fallback per-mark uncertainty in IMAGE pixels, used when a caller cannot say what zoom the
 * mark was placed at. Equivalent to placing a mark on a photograph displayed at 1:1.
 */
export const DEFAULT_MARK_SIGMA_PX = 2;

/**
 * `|sin θ|` below which three marked points count as collinear.
 *
 * 0.02 is about 1.15°. Three corners of a rectangle that subtend less than that in the image are not
 * a rectangle any more — the sheet is edge-on — and the 8x8 system built from them is singular or
 * so close to it that Gaussian elimination returns numbers of magnitude 1e12 that look perfectly
 * finite all the way into a millimetre figure. The check is on the SINE rather than on the raw cross
 * product because a cross product scales with the size of the quad, so a fixed threshold on it would
 * mean something different for a sheet filling the frame and one in the corner of it.
 */
const COLLINEAR_SIN = 0.02;

/**
 * How far inside the horizon a target point has to stay, as a fraction of the rectangle's own
 * distance from it.
 *
 * A homography maps one line of the image — the vanishing line of the plane — to infinity. A target
 * mark placed near it maps to a colossal world coordinate, and the distance to it is a large finite
 * number with no meaning. Requiring the target's homogeneous denominator to keep the sign of the
 * rectangle's and at least this fraction of its magnitude is what keeps "I marked something on the
 * far wall" from becoming "this pot is 4,180 cm across".
 */
const MIN_HORIZON_MARGIN = 0.05;

/* ────────────────────────────────────────────────────────────────────────────
 * Primitives
 * ──────────────────────────────────────────────────────────────────────────── */

function finite(...values: number[]): boolean {
  for (const value of values) if (!Number.isFinite(value)) return false;
  return true;
}

function pointsFinite(points: readonly Point[]): boolean {
  for (const point of points) if (!finite(point.x, point.y)) return false;
  return true;
}

export function distanceBetween(a: Point, b: Point): number {
  return Math.hypot(b.x - a.x, b.y - a.y);
}

/**
 * The uncertainty of a DISTANCE between two independently placed marks, given the uncertainty of one
 * mark.
 *
 * √2, and the factor is worth stating because the obvious answer is 1. Both ends are marked
 * separately, so both contribute; to first order only the component along the line between them
 * moves the length, giving `σ_d = √(σ² + σ²) = √2 σ`. A module that used σ directly would report
 * every error bar in the feature about 30% narrower than it is, which is exactly the flattering
 * direction to be wrong in.
 */
export function distanceSigma(markSigmaPx: number): number {
  return Math.SQRT2 * markSigmaPx;
}

/**
 * The per-mark uncertainty in IMAGE pixels, for a mark placed while the photograph was displayed at
 * `displayScale` (screen pixels per image pixel).
 *
 * THIS IS WHY ZOOMING IN GENUINELY MAKES THE MEASUREMENT BETTER, and why the error bar in the UI
 * narrows as the researcher pinches in. A 4000 px photograph shown 400 px wide is displayed at 0.1,
 * so one screen pixel IS ten image pixels and a mark placed at that zoom is worth ±15 image px
 * however carefully it was aimed. At 4:1 the same care is worth ±0.375 px. Marks are therefore stored
 * in image pixels — invariant under zoom — while their UNCERTAINTY is recorded from the zoom they
 * were placed at, and a measurement takes the worst of the marks it used.
 *
 * A non-positive or non-finite scale returns the 1:1 fallback rather than dividing by it.
 */
export function markSigmaForDisplayScale(displayScale: number, screenSigmaPx: number = SCREEN_MARK_SIGMA_PX): number {
  if (!finite(displayScale, screenSigmaPx) || displayScale <= 0 || screenSigmaPx < 0) return DEFAULT_MARK_SIGMA_PX;
  return screenSigmaPx / displayScale;
}

/**
 * Convert between length units, or null when either unit is one this module does not know.
 *
 * NULL RATHER THAN A GUESS. The target of a proposal is a record column whose unit the caller
 * declares; if a future column declares `unit="hands"` the honest response is to not offer that
 * column as a destination, not to write a centimetre figure into it.
 */
export function convertLength(value: number, from: LengthUnit, to: LengthUnit): number | null {
  const fromFactor = LENGTH_UNITS[from];
  const toFactor = LENGTH_UNITS[to];
  if (fromFactor === undefined || toFactor === undefined || !finite(value)) return null;
  if (from === to) return value;
  return (value * fromFactor) / toFactor;
}

/**
 * Round a measurement to the precision its own error bar can support, and report how many decimal
 * places that was.
 *
 * WHY A MEASUREMENT MAY NOT BE PROPOSED AT FULL PRECISION. `19.98471 cm ± 0.3 cm` is two claims, and
 * the first one contradicts the second. Once that number is written into `lengthInches` the error bar
 * is gone — the record has one column for the dimension and none for the doubt — so the ONLY thing
 * left carrying the honesty is how many digits were written. A researcher reading `20.0` a season
 * later knows roughly what they were told; one reading `19.98471` has been handed a precision nobody
 * measured, in the field that is multiplied into a cost sheet.
 *
 * The rule is the ordinary one from physical measurement: round the uncertainty to one significant
 * figure, and quote the value to that same decimal place. Capped at four decimals, because past that
 * the arithmetic is describing floating-point noise rather than an object.
 */
export function roundToUncertainty(value: number, uncertainty: number): { value: number; decimals: number } {
  if (!finite(value, uncertainty) || uncertainty <= 0) {
    return { value, decimals: finite(value) ? 2 : 0 };
  }
  // The decimal place of the uncertainty's leading digit: 3.35 → 0, 0.335 → 1, 0.0335 → 2.
  const place = Math.floor(Math.log10(uncertainty));
  const decimals = Math.min(4, Math.max(0, -place));
  const factor = 10 ** decimals;
  const rounded = Math.round(value * factor) / factor;
  return { value: finite(rounded) ? rounded : value, decimals };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Linear algebra — written out rather than imported
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Solve `A x = b` by Gaussian elimination with partial pivoting. Returns null for a singular system.
 *
 * WRITTEN OUT, AND THAT IS THE REQUIREMENT RATHER THAN A PREFERENCE. Adding a matrix package for
 * eight equations puts a dependency on the one code path that has to run identically in a browser, in
 * Node and — once ported — inside an Android app with no npm anywhere near it. Eight equations is
 * thirty lines.
 *
 * PARTIAL PIVOTING IS NOT OPTIONAL HERE. Without a row swap the very first elimination step of a
 * perfectly ordinary correspondence set can divide by a zero (a corner at x = 0), and every
 * subsequent entry is NaN — which travels all the way to a blank measurement with no reason attached.
 *
 * NULL AND NOT AN EXCEPTION: a singular system is a normal thing for a researcher to produce by
 * marking three points along an edge, and the caller has a sentence to say about it.
 */
export function solveLinearSystem(matrix: number[][], rhs: number[]): number[] | null {
  const n = rhs.length;
  if (matrix.length !== n) return null;
  // Copy: the caller's arrays are theirs, and elimination is destructive.
  const a = matrix.map((row) => row.slice());
  const b = rhs.slice();
  for (const row of a) {
    if (row.length !== n) return null;
    if (!finite(...row)) return null;
  }
  if (!finite(...b)) return null;

  for (let column = 0; column < n; column += 1) {
    let pivotRow = column;
    for (let row = column + 1; row < n; row += 1) {
      if (Math.abs(a[row][column]) > Math.abs(a[pivotRow][column])) pivotRow = row;
    }
    const pivot = a[pivotRow][column];
    // A pivot that is exactly zero after choosing the largest available one means the column is
    // entirely zero below the diagonal: the system has no unique solution. Testing for exact zero
    // rather than a tolerance is deliberate — a tolerance here would be a second, hidden degeneracy
    // threshold competing with COLLINEAR_SIN, which is the one the caller can explain to a researcher.
    if (pivot === 0) return null;
    if (pivotRow !== column) {
      const swap = a[pivotRow];
      a[pivotRow] = a[column];
      a[column] = swap;
      const swapB = b[pivotRow];
      b[pivotRow] = b[column];
      b[column] = swapB;
    }
    for (let row = column + 1; row < n; row += 1) {
      const factor = a[row][column] / a[column][column];
      if (factor === 0) continue;
      for (let k = column; k < n; k += 1) a[row][k] -= factor * a[column][k];
      b[row] -= factor * b[column];
    }
  }

  const x = new Array<number>(n).fill(0);
  for (let row = n - 1; row >= 0; row -= 1) {
    let sum = b[row];
    for (let column = row + 1; column < n; column += 1) sum -= a[row][column] * x[column];
    x[row] = sum / a[row][row];
  }
  // A system that was singular only to within rounding produces ±Infinity or NaN here rather than at
  // the pivot test. Catching it now is what keeps a non-finite number out of a millimetre figure.
  if (!finite(...x)) return null;
  return x;
}

/** Map a point through a homography, dividing by the third row. */
export function applyHomography(h: Homography, point: Point): Point {
  const w = h[6] * point.x + h[7] * point.y + h[8];
  return {
    x: (h[0] * point.x + h[1] * point.y + h[2]) / w,
    y: (h[3] * point.x + h[4] * point.y + h[5]) / w
  };
}

/** The homogeneous denominator alone — the sign and magnitude of a point's distance from the horizon. */
function homographyDenominator(h: Homography, point: Point): number {
  return h[6] * point.x + h[7] * point.y + h[8];
}

/**
 * A REFUSAL TO ADD HARTLEY NORMALISATION, recorded here because the textbook says to add it.
 *
 * The standard advice for the direct linear transform is to translate each point set's centroid to
 * the origin and scale it so the mean distance from there is √2, then undo the transforms afterwards.
 * That advice is about the OVER-DETERMINED case: many correspondences, a 2n×9 matrix, and the answer
 * taken as the smallest singular vector, where mixing entries of magnitude 1 with entries of
 * magnitude x·u (1.6e7 on a 4000 px photograph) genuinely destroys the nullspace.
 *
 * This module has four correspondences and eight unknowns — an EXACT solve with partial pivoting, not
 * a fit — and pivoting already handles the dynamic range. It was implemented both ways and measured
 * in the repository this was ported from, recovering a fifth point that took no part in the fit:
 *
 *     A4 sheet imaged across    normalised        raw
 *       500 px .............   7.1e-14 mm      9.0e-14 mm
 *      4000 px .............   6.4e-14 mm      4.3e-14 mm
 *     40000 px .............   0.0e+00 mm      2.8e-14 mm
 *
 * Both sit on machine epsilon for a 297 mm coordinate, and the raw solve is marginally better as
 * often as it is worse. The normalisation was therefore deleted rather than kept as insurance: it was
 * three helper functions and a matrix multiply that no test could distinguish from their absence, and
 * a reader would have had to re-derive that for themselves. If this ever grows to accept more than
 * four correspondences, the advice becomes correct and the code comes back — with a test that fails
 * without it, which is precisely what could not be written for it here.
 *
 * The property that MATTERS is pinned instead, in `e2e/photo-measure-unit.spec.ts`: a length
 * rectified off a 4000x3000 frame is exact to a nanometre on an A4 sheet, however that is achieved.
 */

/** |sin θ| at `b`, between `b→a` and `b→c`. Zero when the three are collinear. */
function sineAt(a: Point, b: Point, c: Point): number {
  const ax = a.x - b.x;
  const ay = a.y - b.y;
  const cx = c.x - b.x;
  const cy = c.y - b.y;
  const lengths = Math.hypot(ax, ay) * Math.hypot(cx, cy);
  if (lengths === 0) return 0;
  return Math.abs(ax * cy - ay * cx) / lengths;
}

/**
 * Whether four points, taken in the order given, walk around a simple convex quadrilateral.
 *
 * WHAT THIS CATCHES is a researcher marking the corners of a sheet as top-left, top-right,
 * BOTTOM-LEFT, bottom-right — the natural reading order, and a crossed quadrilateral. The homography
 * from a bow-tie is a perfectly valid projective map; it solves, it produces finite numbers, and it
 * measures the object on a plane that has been folded through itself. The resulting millimetre figure
 * is wrong by an arbitrary amount and looks exactly like a right one, so there is no later stage at
 * which anything could notice. It is caught here or it is not caught.
 */
function isSimpleConvexQuad(points: readonly Point[]): boolean {
  let sign = 0;
  for (let index = 0; index < 4; index += 1) {
    const a = points[index];
    const b = points[(index + 1) % 4];
    const c = points[(index + 2) % 4];
    const cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
    if (cross === 0) return false;
    const current = cross > 0 ? 1 : -1;
    if (sign === 0) sign = current;
    else if (sign !== current) return false;
  }
  return true;
}

/**
 * The homography carrying `from[i]` onto `to[i]` for four correspondences, or null when the
 * configuration cannot determine one.
 *
 * The system is the standard direct linear transform with `h33` fixed at 1: each correspondence
 * `(x, y) → (u, v)` contributes
 *
 *     h11·x + h12·y + h13 − h31·x·u − h32·y·u = u
 *     h21·x + h22·y + h23 − h31·x·v − h32·y·v = v
 *
 * which is eight equations in eight unknowns for four points — an exact solve, not a fit, so there
 * is no residual to report and no least squares to run.
 *
 * FIXING h33 = 1 IS SAFE HERE AND IS NOT SAFE IN GENERAL: it excludes homographies that send the
 * origin of the source plane to infinity. Both call sites map marked image points onto a rectangle
 * the researcher can see all four corners of, so the origin is a corner of a sheet in front of the
 * camera. The alternative — a nine-unknown nullspace solve — needs an SVD, which is a great deal
 * more code to write out by hand and would still have to be checked against the same test cases.
 */
export function solveHomography(from: readonly Point[], to: readonly Point[]): Homography | null {
  if (from.length !== 4 || to.length !== 4) return null;
  if (!pointsFinite(from) || !pointsFinite(to)) return null;

  // Degeneracy is tested on the RAW points, before any transform, so the reason a solve is refused is
  // a property of what the researcher marked rather than of an intermediate step.
  for (let i = 0; i < 4; i += 1) {
    for (let j = i + 1; j < 4; j += 1) {
      if (distanceBetween(from[i], from[j]) === 0) return null;
      if (distanceBetween(to[i], to[j]) === 0) return null;
    }
  }
  for (let i = 0; i < 4; i += 1) {
    for (let j = i + 1; j < 4; j += 1) {
      for (let k = j + 1; k < 4; k += 1) {
        if (sineAt(from[i], from[j], from[k]) < COLLINEAR_SIN) return null;
        if (sineAt(to[i], to[j], to[k]) < COLLINEAR_SIN) return null;
      }
    }
  }

  const matrix: number[][] = [];
  const rhs: number[] = [];
  for (let index = 0; index < 4; index += 1) {
    const { x, y } = from[index];
    const { x: u, y: v } = to[index];
    matrix.push([x, y, 1, 0, 0, 0, -x * u, -y * u]);
    rhs.push(u);
    matrix.push([0, 0, 0, x, y, 1, -x * v, -y * v]);
    rhs.push(v);
  }
  const solved = solveLinearSystem(matrix, rhs);
  if (!solved) return null;

  const h: Homography = [
    solved[0], solved[1], solved[2],
    solved[3], solved[4], solved[5],
    solved[6], solved[7], 1
  ];
  if (!finite(...h)) return null;
  return h;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Uncertainty
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * First-order propagation of a single per-coordinate uncertainty through an arbitrary scalar
 * function, by central differences.
 *
 * A NUMERICAL JACOBIAN RATHER THAN AN ANALYTIC ONE, DELIBERATELY. The rectified length is the
 * distance between two points mapped through a homography that was itself solved from eight other
 * coordinates; its derivative with respect to any one of those twelve inputs is a page of algebra
 * whose only reviewer would be the person who wrote it. Two extra solves per coordinate is a few
 * microseconds and the correctness is checkable — `e2e/photo-measure-unit.spec.ts` runs this
 * propagator against a case whose analytic answer (√2·σ) is known.
 *
 * THE STEP IS σ ITSELF. For a response that is linear over the neighbourhood — which is what a
 * first-order propagation assumes anyway — `(f(c+σ) − f(c−σ)) / 2` IS the contribution, with no
 * separate step size to choose and no division by a small number.
 *
 * NULL WHEN ANY PERTURBATION FAILS. An error bar assembled from eleven of its twelve terms is
 * narrower than the truth, and narrower is the direction that gets believed.
 */
export function propagateUncertainty(
  coordinates: readonly number[],
  sigma: number,
  evaluate: (coordinates: number[]) => number | null
): number | null {
  if (!finite(sigma) || sigma < 0) return null;
  if (!finite(...coordinates)) return null;
  if (sigma === 0) return 0;
  let sumOfSquares = 0;
  for (let index = 0; index < coordinates.length; index += 1) {
    const up = coordinates.slice();
    const down = coordinates.slice();
    up[index] += sigma;
    down[index] -= sigma;
    const high = evaluate(up);
    const low = evaluate(down);
    if (high === null || low === null || !finite(high, low)) return null;
    const contribution = (high - low) / 2;
    sumOfSquares += contribution * contribution;
  }
  const total = Math.sqrt(sumOfSquares);
  return finite(total) ? total : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Method 1 — same plane, two marks
 * ──────────────────────────────────────────────────────────────────────────── */

export type SameScaleInput = {
  /** The two ends of the object whose length is known, and that length. */
  reference: { from: Point; to: Point; length: number; unit: LengthUnit };
  /** The two ends of the thing being measured. */
  target: { from: Point; to: Point };
  /** Per-mark uncertainty in IMAGE pixels — the worst of the four marks. See {@link markSigmaForDisplayScale}. */
  markSigmaPx?: number;
  /**
   * One standard deviation on the reference's own stated length, in its own unit.
   *
   * Zero by default, which is the right answer for a printed grid sheet or a steel rule and the
   * wrong one for "that brick is about 23 cm". A caller that lets a researcher nominate an
   * approximate reference must pass this, or the error bar will be missing its largest term.
   */
  referenceLengthSigma?: number;
};

export function measureBySameScale(input: SameScaleInput): MeasureResult {
  const { reference, target } = input;
  const markSigmaPx = input.markSigmaPx ?? DEFAULT_MARK_SIGMA_PX;
  const referenceLengthSigma = input.referenceLengthSigma ?? 0;

  if (!pointsFinite([reference.from, reference.to, target.from, target.to])) {
    return { ok: false, reason: "One of the marks has no position. Place all four marks again." };
  }
  if (!finite(reference.length, markSigmaPx, referenceLengthSigma) || markSigmaPx < 0 || referenceLengthSigma < 0) {
    return { ok: false, reason: "The reference length is not a number this can measure against." };
  }
  if (LENGTH_UNITS[reference.unit] === undefined) {
    return { ok: false, reason: `“${reference.unit}” is not a length unit this can convert.` };
  }
  if (reference.length <= 0) {
    return { ok: false, reason: "The reference must have a real length greater than zero." };
  }

  const referencePixels = distanceBetween(reference.from, reference.to);
  const targetPixels = distanceBetween(target.from, target.to);
  if (referencePixels < MIN_REFERENCE_PIXELS) {
    return {
      ok: false,
      reason:
        `The reference is only ${Math.round(referencePixels)} pixels long in this photograph, which is too ` +
        `short to measure against — the error bar would be wider than the answer. Zoom in and place the two ` +
        `marks further apart, or photograph the object closer to the scale.`
    };
  }
  if (targetPixels === 0) {
    return { ok: false, reason: "The two marks on the object are in the same place, so there is nothing to measure." };
  }

  const value = (reference.length * targetPixels) / referencePixels;
  const sigmaDistance = distanceSigma(markSigmaPx);
  // A ratio's relative variance is the sum of its terms' relative variances; the reference's own
  // stated length is a third independent term when the caller admits it is not exact.
  const relative = Math.sqrt(
    (sigmaDistance / referencePixels) ** 2 +
      (sigmaDistance / targetPixels) ** 2 +
      (referenceLengthSigma / reference.length) ** 2
  );
  const uncertainty = relative * value;
  if (!finite(value, relative, uncertainty)) {
    return { ok: false, reason: "These marks do not produce a measurement that can be stood behind." };
  }

  return {
    ok: true,
    method: "SCALE",
    value,
    unit: reference.unit,
    uncertainty,
    relativeUncertainty: relative,
    referencePixels,
    targetPixels
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Method 2 — four corners of a known rectangle, rectified
 * ──────────────────────────────────────────────────────────────────────────── */

export type RectificationInput = {
  /**
   * The four marked corners of the known rectangle, IN ORDER AROUND IT — either direction, but
   * around. Reading order (top-left, top-right, bottom-left, bottom-right) is a crossed quadrilateral
   * and is refused; see {@link isSimpleConvexQuad}.
   */
  corners: readonly [Point, Point, Point, Point];
  /** The rectangle's true size. `corners[0]→corners[1]` is the `width` edge. */
  rectangle: { width: number; height: number; unit: LengthUnit };
  target: { from: Point; to: Point };
  /** Per-mark uncertainty in IMAGE pixels — the worst of the six marks. */
  markSigmaPx?: number;
};

export function measureByRectification(input: RectificationInput): MeasureResult {
  const { corners, rectangle, target } = input;
  const markSigmaPx = input.markSigmaPx ?? DEFAULT_MARK_SIGMA_PX;

  if (!pointsFinite(corners) || !pointsFinite([target.from, target.to])) {
    return { ok: false, reason: "One of the marks has no position. Place the corners and the two ends again." };
  }
  if (!finite(rectangle.width, rectangle.height, markSigmaPx) || markSigmaPx < 0) {
    return { ok: false, reason: "The rectangle's size is not a pair of numbers this can measure against." };
  }
  if (LENGTH_UNITS[rectangle.unit] === undefined) {
    return { ok: false, reason: `“${rectangle.unit}” is not a length unit this can convert.` };
  }
  if (rectangle.width <= 0 || rectangle.height <= 0) {
    return { ok: false, reason: "The rectangle must have a real width and height greater than zero." };
  }
  if (distanceBetween(target.from, target.to) === 0) {
    return { ok: false, reason: "The two marks on the object are in the same place, so there is nothing to measure." };
  }
  if (!isSimpleConvexQuad(corners)) {
    return {
      ok: false,
      reason:
        "Those four corners cross over one another, so they do not enclose the rectangle. Mark them in " +
        "order around it — each corner next to the one before it — rather than in reading order."
    };
  }

  /** The rectangle in its own units, matching the corner order the caller marked. */
  const world: Point[] = [
    { x: 0, y: 0 },
    { x: rectangle.width, y: 0 },
    { x: rectangle.width, y: rectangle.height },
    { x: 0, y: rectangle.height }
  ];

  /**
   * The whole measurement as a function of its twelve marked coordinates, so the value and its error
   * bar are computed by ONE piece of code. Two implementations of the same arithmetic — one for the
   * answer, one for the propagation — is how an error bar comes to describe a different quantity from
   * the number it is printed beside.
   */
  const evaluate = (c: number[]): number | null => {
    const marked: Point[] = [
      { x: c[0], y: c[1] },
      { x: c[2], y: c[3] },
      { x: c[4], y: c[5] },
      { x: c[6], y: c[7] }
    ];
    const from: Point = { x: c[8], y: c[9] };
    const to: Point = { x: c[10], y: c[11] };
    const h = solveHomography(marked, world);
    if (!h) return null;

    // The rectangle's own distance from the vanishing line, as the scale the target is judged
    // against. All four corners share a sign for any homography valid on the quad.
    let cornerDenominator = 0;
    let cornerSign = 0;
    for (const corner of marked) {
      const denominator = homographyDenominator(h, corner);
      if (!finite(denominator) || denominator === 0) return null;
      const sign = denominator > 0 ? 1 : -1;
      if (cornerSign === 0) cornerSign = sign;
      else if (cornerSign !== sign) return null;
      cornerDenominator += Math.abs(denominator);
    }
    cornerDenominator /= 4;

    for (const point of [from, to]) {
      const denominator = homographyDenominator(h, point);
      if (!finite(denominator)) return null;
      if ((denominator > 0 ? 1 : -1) !== cornerSign) return null;
      if (Math.abs(denominator) < MIN_HORIZON_MARGIN * cornerDenominator) return null;
    }

    const rectifiedFrom = applyHomography(h, from);
    const rectifiedTo = applyHomography(h, to);
    if (!pointsFinite([rectifiedFrom, rectifiedTo])) return null;
    const length = distanceBetween(rectifiedFrom, rectifiedTo);
    return finite(length) ? length : null;
  };

  const coordinates = [
    corners[0].x, corners[0].y,
    corners[1].x, corners[1].y,
    corners[2].x, corners[2].y,
    corners[3].x, corners[3].y,
    target.from.x, target.from.y,
    target.to.x, target.to.y
  ];

  const value = evaluate(coordinates);
  if (value === null) {
    return {
      ok: false,
      reason:
        "Those four corners do not define a plane this can rectify — three of them are in a straight line, " +
        "or an end of the object falls where the surface runs out of view. Re-mark the corners of the " +
        "rectangle, and keep both ends of the object on it."
    };
  }
  if (value === 0) {
    return { ok: false, reason: "Both ends of the object rectify to the same place, so there is nothing to measure." };
  }

  const uncertainty = propagateUncertainty(coordinates, markSigmaPx, evaluate);
  if (uncertainty === null) {
    // The marks sit close enough to a degenerate arrangement that nudging one of them by the marking
    // error alone breaks the solve. The measurement may well be finite; it is simply not one this
    // module can put an error bar next to, and it does not offer numbers without one.
    return {
      ok: false,
      reason:
        "These marks are too close to an arrangement this cannot measure for an error bar to be worked out, " +
        "and a measurement is not offered without one. Photograph the rectangle less edge-on and mark it again."
    };
  }

  const referencePixels = distanceBetween(corners[0], corners[1]);
  const targetPixels = distanceBetween(target.from, target.to);
  // What two marks would have said: the first marked edge used as a plain scale bar. See
  // Measurement.uncorrectedValue for why this is reported rather than merely computed.
  const uncorrectedValue =
    referencePixels > 0 ? (rectangle.width * targetPixels) / referencePixels : Number.NaN;
  const tiltCorrection = finite(uncorrectedValue) ? Math.abs(value - uncorrectedValue) / value : Number.NaN;

  const relativeUncertainty = uncertainty / value;
  if (!finite(value, uncertainty, relativeUncertainty, referencePixels, targetPixels, uncorrectedValue, tiltCorrection)) {
    return { ok: false, reason: "These marks do not produce a measurement that can be stood behind." };
  }

  return {
    ok: true,
    method: "RECTIFIED",
    value,
    unit: rectangle.unit,
    uncertainty,
    relativeUncertainty,
    referencePixels,
    targetPixels,
    uncorrectedValue,
    tiltCorrection
  };
}
