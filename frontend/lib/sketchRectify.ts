/**
 * Turning a photograph of a pencil sketch into a printable plate: perspective correction, then
 * line-art extraction by local thresholding.
 *
 * WHAT THIS IS FOR, AND WHY IT IS ARITHMETIC RATHER THAN A SERVICE
 *
 * Stage 11 records the sketches a workshop produced, and the way a sketch actually enters this
 * archive is that somebody photographs a sheet of paper lying on a table, at whatever angle they were
 * standing, under whatever light the room has — which in a village workshop is a tube light on one
 * wall. The registry field is `sketch.image` and it is required, so that photograph IS the record. The
 * report then prints it: a skewed grey rectangle of paper with a drawing somewhere inside it, at an
 * angle nobody chose, with a brightness gradient running across the page.
 *
 * Rectified and thresholded, the same photograph is a plate — the sheet square to the page, the
 * pencil black, the paper white. Every step of that is plane projective geometry and local statistics
 * over a luma plane. No network, no model, no server. That is the whole point: the designer who can
 * see whether the result kept the faint construction lines is the designer standing in the room with
 * the original sheet still in front of them, and they are four days from a connection.
 *
 * THE ORIGINAL IS NEVER TOUCHED. NOTHING IN THIS FILE WRITES TO IT.
 *
 * `docs/MEDIA_PIPELINE.md` §5 records a deliberate refusal to re-encode field media: "This is a
 * heritage documentation archive: the original file *is* the artifact, and re-encoding through a
 * canvas destroys full resolution and strips the EXIF the app deliberately preserves." That refusal
 * governs this file completely. {@link rectifySketchFile} READS a `File` and RETURNS A NEW ONE. It has
 * no path that overwrites, replaces, re-uploads or deletes its input, and the caller
 * (`SketchRectifyField`) puts the new file in a DIFFERENT registry field — `sketch.lineArtFile` —
 * precisely so that the value in `sketch.image` still points at the untouched photograph. A derived
 * plate is a second artifact that cites the first; it is never a corrected version of it.
 *
 * THE SHAPE OF THIS FILE: A PURE CORE, THEN A THIN BROWSER ADAPTER
 *
 * Everything above {@link rectifySketchFile} is plain arithmetic over a {@link GreyPlane} — no DOM
 * types, no `File`, no `fetch`, no React. That is not tidiness. It is what lets every case in
 * e2e/sketch-rectify.spec.ts be a construction with an answer known in advance, and it is what makes
 * the Android port a transcription rather than a rewrite: Kotlin has `ByteArray` and `Math.hypot` and
 * nothing else is required. The same arrangement as `lib/imageQuality.ts`, whose `GreyPlane` this
 * file reuses rather than declaring a second, subtly different one.
 *
 * WHAT THIS FILE DELIBERATELY DOES NOT DO
 *
 * It does not vectorise. `backend/app/ai_features/` contains a dormant vectoriser, and clean black
 * line art on white is exactly the input such a thing wants — a bitmap plate is the natural feedstock
 * for an SVG trace, and `sketch.lineArtFile` is declared as "An SVG or vector export". That
 * connection is real and is worth recording, but nothing here enables it, calls it, or assumes it
 * will ever run. What this file produces is a raster plate that stands on its own in the report.
 */

import { type GreyPlane, contrastStdDev, toGreyPlane, resampleGrey } from "@/lib/imageQuality";
import { type Homography, type Point, applyHomography, solveHomography } from "@/lib/photoMeasure";

/* ────────────────────────────────────────────────────────────────────────────
 * Constants, every one of them with the reason it is that number
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The long edge the rectified plate is produced at.
 *
 * DERIVED FROM THE REPORT'S OWN GEOMETRY, not chosen for looking round. `imageQuality.MIN_LONG_EDGE_PX`
 * is 1280 and its comment records where that comes from: the report is A4 with 25mm margins, so a
 * full-width plate is 160mm ≈ 6.3in, and at the 150 dpi this repository commits to that is ~945px,
 * with 1280 the threshold below which a photograph is called too small to print. 1600 sits above that
 * with room to spare, so a plate is never the thing that trips the report's own resolution warning,
 * while still bounding the work: 1600x1200 is 1.9M pixels, and every pass below is linear in that.
 *
 * IT IS ALSO A CEILING, NEVER A TARGET. {@link rectifiedSize} scales DOWN to fit this and never scales
 * up — a sheet that occupied 900px of the original frame is resampled to 900px, because inventing
 * pixels a camera never recorded would make a plate that looks more detailed than the evidence behind
 * it, which is the one direction this archive must never move in.
 */
export const RECTIFY_MAX_EDGE_PX = 1600;

/**
 * The long edge the automatic corner guess runs at.
 *
 * Small on purpose, and the reason is robustness rather than speed. The guess looks for the sheet as
 * the largest bright region; at full resolution the paper's own texture, the pencil strokes on it and
 * the JPEG noise all fragment that region into pieces, and the largest connected component becomes an
 * arbitrary patch. Averaging down to 480px is a low-pass filter that removes exactly that structure
 * and leaves the shape of the sheet.
 *
 * THE PRECISION COST IS REAL AND IS ACCEPTED because of what the guess is FOR: at 480px a corner is
 * located to about ±1px, which is ±4px on a 1920px original. That is visibly off, and it does not
 * matter, because the guess is only ever a starting position for four handles the designer drags. It
 * is never a committed answer — see {@link guessSheetCorners}.
 */
export const GUESS_EDGE_PX = 480;

/**
 * Sauvola's `k`. Higher pulls the threshold further below the local mean, so less survives as ink.
 *
 * 0.2 is the value from the original paper and the one every document-binarisation implementation
 * uses. It is left at the standard rather than tuned to the sketches in this repository's fixtures,
 * because tuning a constant to a handful of photographs produces a number that is worse on the next
 * photograph and impossible for anyone to re-derive.
 */
export const SAUVOLA_K = 0.2;

/** Sauvola's `R`: the dynamic range the local standard deviation is normalised against. Half of 255. */
export const SAUVOLA_R = 128;

/**
 * The local window's side length, as a fraction of the plate's SHORT edge.
 *
 * THE WINDOW MUST BE COMFORTABLY WIDER THAN A PENCIL STROKE, and that is the entire constraint. The
 * local mean is what a pixel is compared against; a window that fits INSIDE a stroke sees only ink, so
 * the mean is ink, so the stroke is not darker than its neighbourhood and the stroke is erased. A
 * pencil line on an A4 sheet resampled to 1200px across is roughly 3–6px wide, and 1/24 of the short
 * edge is 50px at that size — an order of magnitude of headroom.
 *
 * The other end is bounded by the gradient this whole method exists to defeat: the window must be
 * SMALLER than the scale over which the tube light varies. A lamp gradient runs across the whole
 * sheet, so anything under a few percent of the page is safe. 1/24 sits between the two by a wide
 * margin in both directions, which is why it is a fraction of the image rather than a pixel count —
 * a pixel count would be correct at one output size only.
 */
export const SAUVOLA_WINDOW_FRACTION = 1 / 24;

/** The smallest window that is ever used, for a plate small enough that the fraction underflows. */
export const SAUVOLA_MIN_WINDOW = 15;

/**
 * What a sample taken outside the source photograph reads as: paper white.
 *
 * WHY WHITE AND NOT THE CLAMPED EDGE PIXEL. Both are wrong, and they are wrong in different ways. A
 * designer marks a corner beyond the frame when the sheet was photographed cut off. Clamping
 * replicates the last row of real photograph across the whole missing region, which produces a smear
 * that looks like content — a streak of table-coloured pixels the reader has no way to know was
 * invented. White reads as "there was nothing here", which is the truth: that part of the sheet was
 * not photographed. An honest absence beats a plausible fabrication, the same rule
 * `market_analysis.py` follows when it withholds a quantile it cannot support.
 */
export const OUTSIDE_VALUE = 255;

/**
 * The fraction of the guessed quadrilateral that must actually be sheet before the guess is offered.
 *
 * A true sheet fills its own corner quadrilateral almost completely — the shape IS a quadrilateral, so
 * the only shortfall is the pixels the threshold missed. A guess that has latched onto something else
 * (two bright objects bridged by a highlight, a window in the background, the designer's sleeve) makes
 * a quadrilateral whose interior is mostly not the bright region, and this ratio collapses. 0.85 is
 * strict enough to reject those and loose enough to survive a shadowed corner.
 *
 * BELOW THIS THE GUESS IS NOT SHOWN AT ALL — it is not shown greyed, or shown with a caveat. See
 * {@link guessSheetCorners}: a wrong quadrilateral presented as a suggestion is worse than no
 * suggestion, because dragging four handles back from a confident wrong answer is more work than
 * placing them from nothing, and some designers will not notice it is wrong.
 */
export const GUESS_MIN_FILL = 0.85;

/**
 * The fraction of the FRAME the guessed sheet must occupy before the guess is offered.
 *
 * Somebody photographing a sketch fills the frame with it. A "sheet" occupying 6% of the picture is a
 * bright object on a table, not the subject, and rectifying to it would crop the sketch away entirely.
 */
export const GUESS_MIN_FRAME_SHARE = 0.15;

/**
 * How much of each edge of the guessed quadrilateral must run along actual sheet.
 *
 * WHAT THIS CATCHES THAT {@link GUESS_MIN_FILL} DOES NOT, and the reason it had to be added: fill is a
 * ratio of AREAS, and area is a blunt instrument for the question "is this shape a quadrilateral". An
 * L-shaped bright region — the sort of thing a sheet half-occluded by a sleeve or a second sheet
 * makes — fills 87% of the quadrilateral drawn through its four extreme points, which sails past a
 * fill test set anywhere a genuinely shadowed corner could survive. The test suite found this with a
 * synthetic L; tightening the fill threshold enough to reject it would have started rejecting real
 * sheets, which is trading a rare wrong answer for a common missing one.
 *
 * Edges are the discriminating measurement because a quadrilateral is DEFINED by them. On a real
 * sheet the straight line between two adjacent corners lies along the paper's own boundary, so a
 * sample taken just inside it is on the sheet. On the L, the edge joining the two extreme corners
 * either side of the notch flies across empty table, and every sample along it misses.
 */
export const GUESS_MIN_EDGE_SUPPORT = 0.85;

/**
 * The standard deviation, in luma counts, below which the frame is too flat to contain a sheet.
 *
 * THIS IS A REFUSAL TO ANSWER, and it exists because the search cannot fail on its own. Otsu's method
 * always returns a level, even for a frame of one single value, and a threshold applied to a constant
 * image marks EVERY pixel as bright — one component, covering the frame, filling its own bounding
 * quadrilateral perfectly. So a photograph of a blank dark table, an out-of-focus wall, or a lens cap
 * produced a guess with fill 1.0 and frame share 0.99: the most confident possible answer, drawn
 * round the whole picture, from an image containing no sheet at all. Confidence computed from a
 * degenerate split is not weak evidence, it is no evidence, and it has to be rejected before it is
 * measured rather than by hoping the measurements notice.
 *
 * A sheet against a table is a two-population frame by construction, so any real subject clears this
 * easily. `imageQuality.MIN_CONTRAST_STDDEV` is the same number reached for a different judgement
 * there (whether a photograph is flat enough that a blur score would be meaningless); the two are
 * deliberately separate constants because they answer different questions and either could move
 * without the other.
 */
export const GUESS_MIN_CONTRAST = 12;

/* ────────────────────────────────────────────────────────────────────────────
 * Corner handling
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four corners of the sheet, in the order this module always uses: TL, TR, BR, BL. */
export type Corners = readonly [Point, Point, Point, Point];

/** A refusal carrying a sentence for the designer, never a code. Mirrors `photoMeasure.Refusal`. */
export type RectifyRefusal = { ok: false; reason: string };

function finite(...values: number[]): boolean {
  return values.every((value) => Number.isFinite(value));
}

/**
 * Put four marked points into TL, TR, BR, BL order, whatever order they were clicked in.
 *
 * WHAT THIS PREVENTS is the bow-tie. `photoMeasure.isSimpleConvexQuad` documents the failure at
 * length: a designer marking corners in reading order — top-left, top-right, BOTTOM-LEFT,
 * bottom-right — describes a crossed quadrilateral, and the homography from a crossed quadrilateral is
 * a perfectly valid projective map that folds the plane through itself. It solves, it produces finite
 * numbers, and it resamples the sheet inside out. There is no later stage at which anything notices.
 *
 * That module CATCHES the bow-tie and refuses. This one makes it unrepresentable instead, and the
 * difference is a deliberate response to what the two features ask of a designer. Measuring asks for
 * four corners of a rectangle of KNOWN SIZE, where which corner is which carries meaning the caller
 * has to respect, so a wrong order must be reported rather than silently reinterpreted. Rectifying
 * asks only "where is the sheet"; every ordering of the same four points describes the same sheet, and
 * refusing one of them would be refusing to answer a question that was asked perfectly clearly.
 *
 * Sorting by angle about the centroid is what makes it unrepresentable: points taken in angular order
 * around an interior point cannot cross, for any four points that have an interior at all. The
 * rotation afterwards picks the corner nearest the origin as TL, so the output is not merely
 * non-crossing but consistently labelled — which is what lets the caller say "drag the top-left
 * handle" and mean it.
 *
 * Returns null only for input that is not four finite points; every genuine degeneracy (coincident
 * points, three in a line) is left to {@link solveHomography}, which already tests for all of them and
 * already has the sentences.
 */
export function orderCorners(points: readonly Point[]): Corners | null {
  if (points.length !== 4) return null;
  for (const point of points) if (!finite(point.x, point.y)) return null;

  const centreX = (points[0].x + points[1].x + points[2].x + points[3].x) / 4;
  const centreY = (points[0].y + points[1].y + points[2].y + points[3].y) / 4;

  // Ascending atan2 walks clockwise ON SCREEN, because image y grows downward. The visual direction
  // does not matter to the homography — what matters is that it is monotonic, which is what makes the
  // ring non-crossing.
  const ring = [...points].sort(
    (left, right) => Math.atan2(left.y - centreY, left.x - centreX) - Math.atan2(right.y - centreY, right.x - centreX)
  );

  // Rotate so the corner closest to the image origin leads. `x + y` rather than a true distance
  // because it is exact in integers and picks the same corner for every sheet that is not tilted past
  // 45 degrees — and a sheet tilted past 45 degrees has no "top left" that a designer would agree on
  // anyway, so any consistent choice is as good as another.
  let start = 0;
  for (let index = 1; index < 4; index += 1) {
    if (ring[index].x + ring[index].y < ring[start].x + ring[start].y) start = index;
  }
  return [ring[start % 4], ring[(start + 1) % 4], ring[(start + 2) % 4], ring[(start + 3) % 4]];
}

/**
 * The pixel size of the rectified plate for a given marked quadrilateral.
 *
 * THE ASPECT RATIO THIS PRODUCES IS AN ESTIMATE, AND THAT IS NOT A DEFECT THAT CAN BE FIXED HERE.
 * Four image points of an unknown rectangle do not determine its true proportions: recovering those
 * needs the camera's focal length, which a photograph in this archive does not reliably carry. So the
 * estimate is the honest one — take each pair of opposite edges at the length they appear, and use the
 * longer of each pair — and the caller is given `aspect` to override it. `SketchRectifyField` offers
 * A4 and A3, because a designer who knows the sheet was A4 holds information this function cannot
 * derive, and the whole reason the manual path exists is that the person has knowledge the pixels do
 * not.
 *
 * THE LONGER OF EACH PAIR, not the average, because the longer edge is the one that was nearer the
 * camera and therefore the better resolved; sizing to the average would throw away detail that was
 * actually recorded on that side of the sheet.
 */
export function rectifiedSize(
  corners: Corners,
  options?: { maxEdge?: number; aspect?: number }
): { width: number; height: number } | null {
  const maxEdge = options?.maxEdge ?? RECTIFY_MAX_EDGE_PX;
  if (!Number.isFinite(maxEdge) || maxEdge < 1) return null;
  const [topLeft, topRight, bottomRight, bottomLeft] = corners;
  for (const point of corners) if (!finite(point.x, point.y)) return null;

  const top = Math.hypot(topRight.x - topLeft.x, topRight.y - topLeft.y);
  const bottom = Math.hypot(bottomRight.x - bottomLeft.x, bottomRight.y - bottomLeft.y);
  const left = Math.hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y);
  const right = Math.hypot(bottomRight.x - topRight.x, bottomRight.y - topRight.y);

  let width = Math.max(top, bottom);
  let height = Math.max(left, right);
  if (!(width >= 1) || !(height >= 1)) return null;

  // An explicit aspect replaces the estimate but must not enlarge the plate, so the edge that would
  // have to grow is left alone and the other is brought to it.
  const aspect = options?.aspect;
  if (aspect !== undefined) {
    if (!Number.isFinite(aspect) || aspect <= 0) return null;
    if (width / height > aspect) width = height * aspect;
    else height = width / aspect;
  }

  // Never upscale — see RECTIFY_MAX_EDGE_PX.
  const scale = Math.min(1, maxEdge / Math.max(width, height));
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Resampling
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Bilinear sample of `plane` at a real-valued coordinate, in the pixel-centre convention: integer
 * `(x, y)` names the centre of pixel `(x, y)`.
 *
 * BILINEAR AND NOT NEAREST-NEIGHBOUR, for a reason specific to what is being resampled. Nearest
 * neighbour on a rotated grid drops and repeats whole source pixels, and a pencil line is 3–6px wide:
 * dropping every other sample along it turns a continuous stroke into a dashed one. That damage then
 * goes THROUGH the threshold below and comes out the other side as a dashed black line in the plate —
 * a change to what the sketch says, made by a resampling choice, invisible to everyone downstream.
 *
 * Interpolation before thresholding, never after, for the same reason: the threshold is the step that
 * destroys the intermediate values interpolation needs.
 */
export function bilinearSample(plane: GreyPlane, x: number, y: number, outside: number = OUTSIDE_VALUE): number {
  if (!finite(x, y)) return outside;
  // Half a pixel of slack at each edge is the region still covered by a real pixel's own area; beyond
  // it there is genuinely no photograph, and OUTSIDE_VALUE explains why that reads as paper.
  if (x < -0.5 || y < -0.5 || x > plane.width - 0.5 || y > plane.height - 0.5) return outside;

  const x0 = Math.floor(x);
  const y0 = Math.floor(y);
  const fx = x - x0;
  const fy = y - y0;
  // Clamped taps: inside the half-pixel border above, one of the four neighbours legitimately lies
  // outside the array, and the nearest real pixel is the correct value for it — this is the boundary
  // of a sample that IS covered, not the fabricated-content case OUTSIDE_VALUE guards.
  const left = Math.min(Math.max(x0, 0), plane.width - 1);
  const right = Math.min(Math.max(x0 + 1, 0), plane.width - 1);
  const top = Math.min(Math.max(y0, 0), plane.height - 1);
  const bottom = Math.min(Math.max(y0 + 1, 0), plane.height - 1);

  const topLeft = plane.data[top * plane.width + left];
  const topRight = plane.data[top * plane.width + right];
  const bottomLeft = plane.data[bottom * plane.width + left];
  const bottomRight = plane.data[bottom * plane.width + right];

  const upper = topLeft + (topRight - topLeft) * fx;
  const lower = bottomLeft + (bottomRight - bottomLeft) * fx;
  return upper + (lower - upper) * fy;
}

/**
 * The homography carrying the OUTPUT plate's corners onto the marked corners in the source photograph.
 *
 * THE DIRECTION IS THE WHOLE TRICK, and it is why this module needs no matrix inversion anywhere.
 * Resampling has to answer, for each pixel of the output, "which point of the input does this come
 * from" — so the transform wanted is output→input. Solving in that direction directly means the eight
 * unknowns come straight out of `solveHomography` and are used as they are.
 *
 * The alternative — solve input→output the way a reader expects, then invert a 3x3 — is a second
 * hand-written linear-algebra routine, with its own degeneracies and its own tests, to arrive at
 * exactly these numbers.
 *
 * WHY NOT FORWARD-MAP THE INPUT INSTEAD. Pushing each source pixel to where it lands scatters rather
 * than gathers: wherever the map is locally expanding, output pixels are left that no source pixel
 * hit, and the plate comes out perforated with holes that then threshold to white speckle through the
 * strokes. Gathering visits every output pixel exactly once by construction.
 *
 * REUSED, NOT REWRITTEN. `photoMeasure.solveHomography` is already the hand-written 8x8 direct linear
 * transform with partial pivoting this needs — no matrix dependency, `h33` fixed at 1, degeneracy
 * refused on the raw marks — and e2e/photo-measure.spec.ts already proves it recovers a known
 * transform including a fifth point that took no part in the fit. Writing a second Gaussian
 * elimination here would have been a second implementation to keep correct, and the ported Kotlin
 * would then have had two of them as well.
 */
export function rectifyHomography(corners: Corners, width: number, height: number): Homography | null {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width < 2 || height < 2) return null;
  // The output rectangle's own corners, in the same TL, TR, BR, BL order. `width - 1` because these
  // are pixel CENTRES: the last addressable centre of a 100px-wide plate is at 99, and using 100 here
  // would resample the sheet a fraction small and shift the whole plate half a pixel up and left.
  const destination: Point[] = [
    { x: 0, y: 0 },
    { x: width - 1, y: 0 },
    { x: width - 1, y: height - 1 },
    { x: 0, y: height - 1 }
  ];
  return solveHomography(destination, [...corners]);
}

/**
 * Resample the quadrilateral `corners` of `plane` into an upright plate of the given size.
 *
 * Returns null when the marks cannot determine a homography — coincident corners, or three of them in
 * a line. That refusal comes from {@link solveHomography} and is the designer's to fix by moving a
 * handle, which is why the caller turns it into a sentence rather than a silent empty plate.
 */
export function rectifyPlane(plane: GreyPlane, corners: Corners, width: number, height: number): GreyPlane | null {
  const homography = rectifyHomography(corners, width, height);
  if (!homography) return null;

  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    const rowOffset = y * width;
    for (let x = 0; x < width; x += 1) {
      const source = applyHomography(homography, { x, y });
      data[rowOffset + x] = bilinearSample(plane, source.x, source.y);
    }
  }
  return { data, width, height };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Thresholding — turning grey paper into black line on white
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Summed-area tables of the plane and of its squares, so any window's mean and variance are O(1).
 *
 * Both are `Float64Array` and both are `(width + 1) * (height + 1)` with a zero row and column, which
 * removes every boundary branch from the inner loop below.
 *
 * THE SQUARES GENUINELY NEED 64 BITS. A 1600x1200 plate of pure white sums to 1.9M * 255² ≈ 1.2e11.
 * That is far past a 32-bit integer and past the point where `Float32Array` has unit precision — in
 * `Float32Array` the running total stops changing when the increment falls below its ULP, so the
 * bottom-right of the table would silently stop accumulating and every window in the lower half of the
 * image would get a wrong variance. A double carries integers exactly to 2^53, which is seven orders
 * of magnitude of headroom over the worst case here.
 */
export function integralPlanes(plane: GreyPlane): { sum: Float64Array; squares: Float64Array; stride: number } {
  const stride = plane.width + 1;
  const sum = new Float64Array(stride * (plane.height + 1));
  const squares = new Float64Array(stride * (plane.height + 1));
  for (let y = 0; y < plane.height; y += 1) {
    let rowSum = 0;
    let rowSquares = 0;
    const source = y * plane.width;
    const above = y * stride;
    const current = (y + 1) * stride;
    for (let x = 0; x < plane.width; x += 1) {
      const value = plane.data[source + x];
      rowSum += value;
      rowSquares += value * value;
      sum[current + x + 1] = sum[above + x + 1] + rowSum;
      squares[current + x + 1] = squares[above + x + 1] + rowSquares;
    }
  }
  return { sum, squares, stride };
}

/** The window side length used for a plate of this size — odd, so it has a centre pixel. */
export function sauvolaWindow(plane: GreyPlane): number {
  const shortEdge = Math.min(plane.width, plane.height);
  const scaled = Math.round(shortEdge * SAUVOLA_WINDOW_FRACTION);
  const bounded = Math.max(SAUVOLA_MIN_WINDOW, scaled);
  return bounded % 2 === 1 ? bounded : bounded + 1;
}

/**
 * Sauvola local thresholding: black where the pixel is darker than its own neighbourhood expects.
 *
 *     T(x, y) = m(x, y) · (1 + k · (s(x, y) / R − 1))
 *
 * where `m` and `s` are the mean and standard deviation of the window around the pixel.
 *
 * WHY A LOCAL METHOD AND NOT A GLOBAL ONE — THIS IS THE ENTIRE JUSTIFICATION FOR THE FILE
 *
 * The sketch is photographed under a tube light on one wall, so one side of the sheet is materially
 * brighter than the other. A global threshold is a single number for the whole page, and there is no
 * single number that works: it has to be high enough to catch a pencil stroke on the BRIGHT side,
 * where the stroke is still lighter than plain paper on the dark side — and any threshold that high
 * classifies the entire dark half of the blank page as ink. The plate comes out with the sketch
 * visible at one end and a solid black rectangle at the other. Lower the threshold to fix that and the
 * strokes on the bright side vanish instead. The two failures are not a tuning problem; they are the
 * same threshold being asked to mean two different things about two differently-lit pieces of paper.
 *
 * A local threshold is not a refinement of that. It removes the premise: every pixel is compared with
 * the paper immediately around it, which is lit the way it is lit, so the lamp gradient cancels
 * instead of being fought. e2e/sketch-rectify.spec.ts pins exactly this and nothing else is the point
 * of that test: on a synthetic page with a lamp gradient and strokes of constant local contrast, Otsu
 * — the strongest global method, chosen so the comparison is not a strawman — floods the dark half and
 * simultaneously loses the strokes on the bright half, while this function recovers the strokes at
 * both ends and leaves the paper clean.
 *
 * WHY SAUVOLA AND NOT PLAIN LOCAL-MEAN (NIBLACK). Niblack's `T = m + k·s` has no term that recognises
 * a window containing no ink at all. On blank paper `s` is just sensor noise, the threshold sits a
 * hair below the mean, and roughly half the noise falls below it: a clean margin comes out as dense
 * black speckle. Sauvola's `s/R` factor collapses toward `T ≈ m·(1 − k)` exactly there, which is well
 * below every pixel of an unmarked region, so blank paper stays blank. On a sketch that is mostly
 * blank paper by area — which every sketch is — that difference is the difference between a plate and
 * a page of dirt.
 *
 * The output is 0 for ink and 255 for paper: black line art on white, ready to print, and the natural
 * input for the dormant vectoriser noted in the file header.
 */
export function sauvolaThreshold(
  plane: GreyPlane,
  options?: { window?: number; k?: number; r?: number }
): GreyPlane {
  const window = options?.window ?? sauvolaWindow(plane);
  const k = options?.k ?? SAUVOLA_K;
  const r = options?.r ?? SAUVOLA_R;
  const radius = Math.max(1, Math.floor(window / 2));
  const { sum, squares, stride } = integralPlanes(plane);
  const data = new Uint8ClampedArray(plane.width * plane.height);

  for (let y = 0; y < plane.height; y += 1) {
    // The window is CLIPPED to the plane rather than the image being padded, and the pixel count is
    // the clipped one. Padding with a constant would put invented paper in every window along the
    // border, biasing the mean there toward the pad value and drawing a black or white frame around
    // every plate — on a rectified sheet the border is the edge of the paper, which is exactly where a
    // sketch's own frame lines tend to be.
    const top = Math.max(0, y - radius);
    const bottom = Math.min(plane.height - 1, y + radius);
    const topRow = top * stride;
    const bottomRow = (bottom + 1) * stride;
    const rows = bottom - top + 1;
    for (let x = 0; x < plane.width; x += 1) {
      const left = Math.max(0, x - radius);
      const right = Math.min(plane.width - 1, x + radius);
      const count = rows * (right - left + 1);

      const total = sum[bottomRow + right + 1] - sum[bottomRow + left] - sum[topRow + right + 1] + sum[topRow + left];
      const totalSquares =
        squares[bottomRow + right + 1] - squares[bottomRow + left] - squares[topRow + right + 1] + squares[topRow + left];

      const mean = total / count;
      // Clamped at zero because the algebraic identity E[x²] − E[x]² is very slightly negative for a
      // constant window once rounding is involved, and Math.sqrt of that is NaN — which would
      // propagate into the threshold and paint an arbitrary patch.
      const variance = Math.max(0, totalSquares / count - mean * mean);
      const threshold = mean * (1 + k * (Math.sqrt(variance) / r - 1));
      data[y * plane.width + x] = plane.data[y * plane.width + x] < threshold ? 0 : 255;
    }
  }
  return { data, width: plane.width, height: plane.height };
}

/**
 * Otsu's global threshold value: the single grey level that best separates the histogram into two
 * classes by between-class variance.
 *
 * PRESENT AS THE COUNTEREXAMPLE, NOT AS AN OPTION THE UI OFFERS. Nothing in the application calls this
 * to make a plate. It exists so that the claim in {@link sauvolaThreshold}'s comment — that a global
 * threshold cannot work under a lamp gradient — is a measured fact in the test suite rather than an
 * assertion in a comment, and Otsu is used rather than a plain mean so that the comparison is against
 * the best global method rather than a weak one. It is also genuinely used, internally, by
 * {@link guessSheetCorners}, where a global split IS the right tool: telling bright sheet from dark
 * table is a two-population question about the whole frame, which is the exact situation Otsu is for.
 */
export function otsuThreshold(plane: GreyPlane): number {
  const histogram = new Float64Array(256);
  for (let index = 0; index < plane.data.length; index += 1) histogram[plane.data[index]] += 1;
  const total = plane.data.length;
  if (total === 0) return 128;

  let sumAll = 0;
  for (let level = 0; level < 256; level += 1) sumAll += level * histogram[level];

  let backgroundWeight = 0;
  let backgroundSum = 0;
  let best = 0;
  let bestVariance = -1;
  for (let level = 0; level < 256; level += 1) {
    backgroundWeight += histogram[level];
    if (backgroundWeight === 0) continue;
    const foregroundWeight = total - backgroundWeight;
    if (foregroundWeight === 0) break;
    backgroundSum += level * histogram[level];
    const backgroundMean = backgroundSum / backgroundWeight;
    const foregroundMean = (sumAll - backgroundSum) / foregroundWeight;
    const between = backgroundWeight * foregroundWeight * (backgroundMean - foregroundMean) ** 2;
    if (between > bestVariance) {
      bestVariance = between;
      best = level;
    }
  }
  return best;
}

/** Apply a single global threshold. The counterexample of {@link sauvolaThreshold}; see {@link otsuThreshold}. */
export function globalThreshold(plane: GreyPlane, level?: number): GreyPlane {
  const cut = level ?? otsuThreshold(plane);
  const data = new Uint8ClampedArray(plane.width * plane.height);
  for (let index = 0; index < data.length; index += 1) data[index] = plane.data[index] <= cut ? 0 : 255;
  return { data, width: plane.width, height: plane.height };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The automatic corner guess
 * ──────────────────────────────────────────────────────────────────────────── */

export type CornerGuess = {
  /** In the coordinates of the plane that was passed in, not of the reduced plane the search ran on. */
  corners: Corners;
  /** How much of the guessed quadrilateral was actually sheet, 0..1. See {@link GUESS_MIN_FILL}. */
  fill: number;
  /** How much of the frame the guessed sheet occupies, 0..1. See {@link GUESS_MIN_FRAME_SHARE}. */
  frameShare: number;
  /** The weakest of the four edges, 0..1. See {@link GUESS_MIN_EDGE_SUPPORT}. */
  edgeSupport: number;
};

/**
 * The weakest of the four edges: for each, the fraction of samples taken just inside it that are
 * actually on the bright region.
 *
 * Sampling INWARD, along the line from the edge toward the quadrilateral's centre, rather than
 * exactly on the edge: the extreme-point corners sit ON the boundary, where a pixel is as likely to
 * have fallen on the dark side of the threshold as the bright one, so a sample taken exactly on the
 * line would report a real sheet's edges as half-supported. Two pixels in is unambiguously interior
 * for anything that is genuinely a sheet, and still unambiguously exterior where the edge crosses a
 * notch.
 */
function edgeSupport(
  corners: Corners,
  bright: Uint8Array,
  width: number,
  height: number,
  samples: number = 32
): number {
  const centreX = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) / 4;
  const centreY = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) / 4;
  let weakest = 1;
  for (let edge = 0; edge < 4; edge += 1) {
    const from = corners[edge];
    const to = corners[(edge + 1) % 4];
    let hits = 0;
    let total = 0;
    for (let step = 1; step < samples; step += 1) {
      const t = step / samples;
      const x = from.x + (to.x - from.x) * t;
      const y = from.y + (to.y - from.y) * t;
      const towardX = centreX - x;
      const towardY = centreY - y;
      const length = Math.hypot(towardX, towardY);
      if (length < 1) continue;
      const px = Math.round(x + (towardX / length) * 2);
      const py = Math.round(y + (towardY / length) * 2);
      if (px < 0 || py < 0 || px >= width || py >= height) continue;
      total += 1;
      if (bright[py * width + px] === 1) hits += 1;
    }
    if (total === 0) return 0;
    weakest = Math.min(weakest, hits / total);
  }
  return weakest;
}

/**
 * Binary closing — dilate by `radius`, then erode by the same — over the bright mask.
 *
 * WHY THE SEARCH CANNOT WORK WITHOUT THIS, discovered by running the guess against a generated
 * photograph of an actual sketch rather than against a blank rectangle. The sheet is found as the
 * largest CONNECTED bright region, and a drawing is made of dark strokes ON that sheet. Any stroke
 * that runs from one edge of the paper to the other — a border, a fold line, a construction line
 * ruled across the page, all of which are ordinary things to draw — CUTS THE BRIGHT REGION IN TWO.
 * The largest component is then a band between two strokes, and the "sheet" that comes back is a
 * slice of the real one. The failure is silent and looks like a plausible answer.
 *
 * Closing removes it because a stroke is thin and a sheet is not. Dilating swallows any dark line
 * narrower than twice the radius, joining the paper back into one region; eroding by the same amount
 * puts the outer boundary back where it was, so the corners this is all in aid of do not move. That
 * asymmetry — thin features vanish, large ones are restored exactly — is the entire reason the two
 * passes are done in that order rather than either alone.
 *
 * Both passes go through a summed-area table so the cost is independent of the radius: a pixel is
 * bright after dilation when its window sum is above zero, and survives erosion when its window sum
 * is the whole window. `Float64Array` is unnecessary for a 0/1 mask at this size, but it is what
 * makes the count exact for any radius rather than only for small ones.
 */
function closeBrightMask(mask: Uint8Array, width: number, height: number, radius: number): Uint8Array {
  if (radius < 1) return mask;

  const boxPass = (source: Uint8Array, keep: (sum: number, count: number) => boolean): Uint8Array => {
    const stride = width + 1;
    const table = new Float64Array(stride * (height + 1));
    for (let y = 0; y < height; y += 1) {
      let row = 0;
      for (let x = 0; x < width; x += 1) {
        row += source[y * width + x];
        table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + row;
      }
    }
    const out = new Uint8Array(source.length);
    for (let y = 0; y < height; y += 1) {
      const top = Math.max(0, y - radius);
      const bottom = Math.min(height - 1, y + radius);
      const topRow = top * stride;
      const bottomRow = (bottom + 1) * stride;
      for (let x = 0; x < width; x += 1) {
        const left = Math.max(0, x - radius);
        const right = Math.min(width - 1, x + radius);
        const sum =
          table[bottomRow + right + 1] - table[bottomRow + left] - table[topRow + right + 1] + table[topRow + left];
        const count = (bottom - top + 1) * (right - left + 1);
        out[y * width + x] = keep(sum, count) ? 1 : 0;
      }
    }
    return out;
  };

  const dilated = boxPass(mask, (sum) => sum > 0);
  return boxPass(dilated, (sum, count) => sum === count);
}

/**
 * How far the closing above reaches, as a fraction of the guess plane's short edge.
 *
 * It has to exceed half the width of the widest stroke that might cross the sheet, and stay far below
 * the smallest gap between the sheet and anything else bright in the frame. At {@link GUESS_EDGE_PX}
 * a pencil line off a 12 MP photograph is on the order of one pixel, so 1/120 of the short edge —
 * three pixels on a 360px-tall guess plane, bridging gaps up to six — clears the first bound by a
 * wide margin while remaining under one percent of the frame.
 */
export const GUESS_CLOSE_FRACTION = 1 / 120;

/** Twice the signed area of the polygon — the shoelace sum. Sign carries the winding direction. */
function shoelaceArea(corners: Corners): number {
  let total = 0;
  for (let index = 0; index < 4; index += 1) {
    const current = corners[index];
    const next = corners[(index + 1) % 4];
    total += current.x * next.y - next.x * current.y;
  }
  return Math.abs(total) / 2;
}

/**
 * Guess where the sheet is: the largest bright region in the frame, taken at its four extreme corners.
 *
 * THE MANUAL PATH IS THE REAL FEATURE AND THIS IS A CONVENIENCE ON TOP OF IT. `SketchRectifyField`
 * starts every sketch with four draggable handles inset from the frame edges, and it does that whether
 * or not this function found anything. A guess, when there is one, MOVES those handles to a better
 * starting position and says on screen that it did. It never commits, never rectifies on its own, and
 * is never the only way to place a corner.
 *
 * HOW IT DECIDES, and why each step is defensible rather than merely effective on the fixtures:
 *
 *  1. Average the plane down to {@link GUESS_EDGE_PX}. Paper texture, pencil strokes and JPEG noise
 *     all fragment the sheet into pieces at full resolution; averaging removes that structure and
 *     leaves the shape.
 *  2. Split bright from dark at {@link otsuThreshold}. "Sheet against table" is a two-population
 *     question about the whole frame, which is the situation Otsu's method is actually for — unlike
 *     the line-art step, where the two populations differ from one end of the page to the other.
 *  3. Take the LARGEST 4-CONNECTED BRIGHT COMPONENT, not every bright pixel. A bright wall behind the
 *     table, or a sheet of paper further away, is bright too; without this step the extremes would be
 *     taken across all of them at once and the "sheet" would span the whole picture.
 *  4. Take that component's extremes along the two diagonals: min(x+y) is the top-left corner,
 *     max(x−y) the top-right, max(x+y) the bottom-right, min(x−y) the bottom-left. For a convex
 *     quadrilateral these are its actual corners, which is why the shape is recovered rather than its
 *     bounding box — a bounding box would be wrong for every sheet that is not already square to the
 *     frame, which is every sheet this feature exists for.
 *  5. REFUSE unless the result looks like a photograph of a sheet: it must fill its own quadrilateral
 *     ({@link GUESS_MIN_FILL}) and occupy a real share of the frame ({@link GUESS_MIN_FRAME_SHARE}).
 *
 * Step 5 is the one that matters, and it is the reason this function returns `null` rather than a
 * low-confidence answer with a caveat. A confidently-drawn wrong quadrilateral is worse than nothing:
 * dragging four handles back off a wrong answer is more work than placing them on a blank photograph,
 * and a designer working quickly will accept it. Uncertain means silent, and silent means the manual
 * default stands — which is what the requirement "the manual path must always exist and must be the
 * default when the guess is uncertain" asks for.
 */
export function guessSheetCorners(plane: GreyPlane): CornerGuess | null {
  if (plane.width < 8 || plane.height < 8) return null;

  const scale = Math.min(1, GUESS_EDGE_PX / Math.max(plane.width, plane.height));
  const width = Math.max(4, Math.round(plane.width * scale));
  const height = Math.max(4, Math.round(plane.height * scale));
  const small = scale < 1 ? resampleGrey(plane, width, height) : plane;

  // Before anything is measured: a frame with no contrast has no two populations for Otsu to
  // separate, and every measurement below would be computed from a split that means nothing. See
  // GUESS_MIN_CONTRAST — this is the difference between "no sheet found" and a maximally confident
  // rectangle drawn round a photograph of a table.
  if (contrastStdDev(small) < GUESS_MIN_CONTRAST) return null;

  const cut = otsuThreshold(small);
  const raw = new Uint8Array(small.width * small.height);
  for (let index = 0; index < raw.length; index += 1) raw[index] = small.data[index] > cut ? 1 : 0;
  // Heal the drawing's own strokes out of the mask before looking for the sheet — without this a
  // single line ruled across the page splits the paper into two regions and the search finds a band.
  // See closeBrightMask.
  const bright = closeBrightMask(
    raw,
    small.width,
    small.height,
    Math.max(1, Math.round(Math.min(small.width, small.height) * GUESS_CLOSE_FRACTION))
  );

  // Iterative flood fill over an explicit stack. Recursion here would be one frame per pixel and a
  // 480x360 region is 172,800 of them — a guaranteed stack overflow, which on a phone is a crashed tab
  // in the middle of fieldwork rather than a caught error.
  const labels = new Int32Array(bright.length).fill(-1);
  const stack: number[] = [];
  let bestArea = 0;
  let bestLabel = -1;
  let label = 0;
  for (let seed = 0; seed < bright.length; seed += 1) {
    if (bright[seed] === 0 || labels[seed] !== -1) continue;
    labels[seed] = label;
    stack.push(seed);
    let area = 0;
    while (stack.length) {
      const index = stack.pop() as number;
      area += 1;
      const x = index % small.width;
      const y = (index - x) / small.width;
      if (x > 0 && bright[index - 1] === 1 && labels[index - 1] === -1) {
        labels[index - 1] = label;
        stack.push(index - 1);
      }
      if (x < small.width - 1 && bright[index + 1] === 1 && labels[index + 1] === -1) {
        labels[index + 1] = label;
        stack.push(index + 1);
      }
      if (y > 0 && bright[index - small.width] === 1 && labels[index - small.width] === -1) {
        labels[index - small.width] = label;
        stack.push(index - small.width);
      }
      if (y < small.height - 1 && bright[index + small.width] === 1 && labels[index + small.width] === -1) {
        labels[index + small.width] = label;
        stack.push(index + small.width);
      }
    }
    if (area > bestArea) {
      bestArea = area;
      bestLabel = label;
    }
    label += 1;
  }
  if (bestLabel === -1 || bestArea < 16) return null;

  let topLeft = { x: 0, y: 0 };
  let topRight = { x: 0, y: 0 };
  let bottomRight = { x: 0, y: 0 };
  let bottomLeft = { x: 0, y: 0 };
  let minSum = Infinity;
  let maxSum = -Infinity;
  let minDiff = Infinity;
  let maxDiff = -Infinity;
  for (let index = 0; index < labels.length; index += 1) {
    if (labels[index] !== bestLabel) continue;
    const x = index % small.width;
    const y = (index - x) / small.width;
    if (x + y < minSum) {
      minSum = x + y;
      topLeft = { x, y };
    }
    if (x + y > maxSum) {
      maxSum = x + y;
      bottomRight = { x, y };
    }
    if (x - y > maxDiff) {
      maxDiff = x - y;
      topRight = { x, y };
    }
    if (x - y < minDiff) {
      minDiff = x - y;
      bottomLeft = { x, y };
    }
  }

  const ordered = orderCorners([topLeft, topRight, bottomRight, bottomLeft]);
  if (!ordered) return null;
  const quadArea = shoelaceArea(ordered);
  if (quadArea <= 0) return null;

  const fill = bestArea / quadArea;
  const frameShare = quadArea / (small.width * small.height);
  const support = edgeSupport(ordered, bright, small.width, small.height);
  if (fill < GUESS_MIN_FILL || frameShare < GUESS_MIN_FRAME_SHARE || support < GUESS_MIN_EDGE_SUPPORT) return null;

  // Back to the caller's coordinates. The reduced plane's pixel (x, y) covers a block of the original,
  // so the +0.5 centres the corner in that block rather than pinning it to the block's top-left —
  // a half-block bias applied to all four corners would shrink the quadrilateral systematically.
  const back = 1 / (scale < 1 ? scale : 1);
  const lift = (point: Point): Point => ({ x: (point.x + 0.5) * back, y: (point.y + 0.5) * back });
  const lifted = orderCorners([lift(ordered[0]), lift(ordered[1]), lift(ordered[2]), lift(ordered[3])]);
  if (!lifted) return null;
  return { corners: lifted, fill, frameShare, edgeSupport: support };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The whole operation, still pure
 * ──────────────────────────────────────────────────────────────────────────── */

export type PlateOptions = {
  /** Force a known sheet proportion (A4 portrait is 210/297). See {@link rectifiedSize}. */
  aspect?: number;
  maxEdge?: number;
  /** False produces the rectified GREY plate with no thresholding — see {@link makePlate}. */
  lineArt?: boolean;
  sauvola?: { window?: number; k?: number; r?: number };
};

export type Plate = {
  ok: true;
  plane: GreyPlane;
  /** Milliseconds spent resampling and thresholding — no decode, which the adapter times separately. */
  elapsedMs: number;
};

/**
 * Rectify, then optionally extract line art. The one entry point the UI's preview calls.
 *
 * `lineArt: false` IS A FIRST-CLASS OUTCOME, not a debugging switch. A pencil sketch with soft shading
 * or a very faint construction line is a drawing whose information is in the greys, and thresholding
 * it to two levels throws that away; a designer who can see both previews side by side is the only one
 * who can tell. So "straighten it but leave the tones alone" is a real answer, and so — one level up,
 * in the UI — is keeping the original photograph and producing nothing at all.
 */
export function makePlate(plane: GreyPlane, corners: Corners, options?: PlateOptions): Plate | RectifyRefusal {
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  const size = rectifiedSize(corners, { maxEdge: options?.maxEdge, aspect: options?.aspect });
  if (!size) {
    return { ok: false, reason: "Those four marks do not enclose a sheet. Drag the handles to the corners of the paper." };
  }
  const rectified = rectifyPlane(plane, corners, size.width, size.height);
  if (!rectified) {
    return {
      ok: false,
      reason: "Those corners lie in a straight line, so there is no sheet between them. Move one of the handles."
    };
  }
  const plane2 = options?.lineArt === false ? rectified : sauvolaThreshold(rectified, options?.sauvola);
  const elapsedMs = (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt;
  return { ok: true, plane: plane2, elapsedMs };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The browser adapter — the only part below that knows what a browser is
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * RGBA from a grey plane, for putting back on a canvas. Alpha is always opaque.
 *
 * The buffer is allocated explicitly rather than by length so the result is typed over `ArrayBuffer`
 * and not `ArrayBufferLike`. `ImageData`'s constructor will not accept the latter — a typed array
 * over a `SharedArrayBuffer` cannot be handed to the compositor — and `new Uint8ClampedArray(n)`
 * infers the wider type.
 */
export function greyToRgba(plane: GreyPlane): Uint8ClampedArray<ArrayBuffer> {
  const rgba = new Uint8ClampedArray(new ArrayBuffer(plane.width * plane.height * 4));
  for (let index = 0; index < plane.data.length; index += 1) {
    const value = plane.data[index];
    const offset = index * 4;
    rgba[offset] = value;
    rgba[offset + 1] = value;
    rgba[offset + 2] = value;
    rgba[offset + 3] = 255;
  }
  return rgba;
}

/** `OffscreenCanvas` where it exists, a detached `<canvas>` where it does not. As `imageQuality`. */
function canvasFor(width: number, height: number): OffscreenCanvas | HTMLCanvasElement | null {
  if (typeof OffscreenCanvas !== "undefined") return new OffscreenCanvas(width, height);
  if (typeof document === "undefined") return null;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

function readPixels(bitmap: ImageBitmap, width: number, height: number): Uint8ClampedArray | null {
  const canvas = canvasFor(width, height);
  if (!canvas) return null;
  const context = canvas.getContext("2d", { willReadFrequently: true }) as
    | OffscreenCanvasRenderingContext2D
    | CanvasRenderingContext2D
    | null;
  if (!context) return null;
  context.drawImage(bitmap, 0, 0, width, height);
  return context.getImageData(0, 0, width, height).data;
}

/**
 * Decode `file` to a bounded luma plane, off the main thread as far as the platform allows.
 *
 * KEEPING A 12 MP PHOTOGRAPH FROM FREEZING THE TAB, which on the handsets this app runs on is the
 * difference between a feature and a hang. The tactics are `imageQuality.measureImageFile`'s, for the
 * same reasons, and they are repeated here rather than shared because that function returns
 * MEASUREMENTS and this one needs the PIXELS:
 *
 * - `createImageBitmap(file)` decodes off the main thread in every engine that has it, so the
 *   expensive part of a 4000x3000 JPEG never runs where the UI does.
 * - The second `createImageBitmap` resizes off-thread too, and the full-size bitmap is `close()`d the
 *   instant the resized one exists. A 4000x3000 decode is ~48 MB of RGBA; holding two at once on a
 *   cheap phone is how a tab gets killed.
 * - `resizeQuality: "high"` is a box-ish filter. A cheap resize aliases, and aliasing is
 *   high-frequency energy landing in exactly the band the pencil strokes occupy — a fast downscale
 *   would manufacture speckle for the threshold to find.
 * - Everything after this point runs at `maxEdge` or below, which bounds every later pass.
 *
 * Both bitmaps are closed in `finally`, on every path including the throw.
 */
export async function loadSketchPlane(
  file: Blob,
  maxEdge: number = RECTIFY_MAX_EDGE_PX
): Promise<{ plane: GreyPlane; sourceWidth: number; sourceHeight: number; elapsedMs: number } | null> {
  if (typeof createImageBitmap === "undefined") return null;
  const startedAt = typeof performance !== "undefined" ? performance.now() : Date.now();
  let full: ImageBitmap | null = null;
  let scaled: ImageBitmap | null = null;
  try {
    full = await createImageBitmap(file);
    const sourceWidth = full.width;
    const sourceHeight = full.height;
    if (sourceWidth < 1 || sourceHeight < 1) return null;

    const scale = Math.min(1, maxEdge / Math.max(sourceWidth, sourceHeight));
    const workWidth = Math.max(1, Math.round(sourceWidth * scale));
    const workHeight = Math.max(1, Math.round(sourceHeight * scale));
    scaled =
      scale < 1
        ? await createImageBitmap(full, { resizeWidth: workWidth, resizeHeight: workHeight, resizeQuality: "high" })
        : full;
    if (scaled !== full) {
      full.close();
      full = null;
    }

    const rgba = readPixels(scaled, workWidth, workHeight);
    if (!rgba) return null;
    return {
      plane: toGreyPlane(rgba, workWidth, workHeight),
      sourceWidth,
      sourceHeight,
      elapsedMs: (typeof performance !== "undefined" ? performance.now() : Date.now()) - startedAt
    };
  } catch {
    // A corrupt file, a codec this browser lacks (HEIC), or a bitmap the GPU refused. None of those is
    // the designer's problem, and none of them may disturb the photograph that is already attached.
    return null;
  } finally {
    full?.close();
    if (scaled && scaled !== full) scaled.close();
  }
}

/**
 * Encode a plate as a PNG blob.
 *
 * PNG AND NEVER JPEG, and this is not a preference. Line art is very nearly bilevel, and JPEG's
 * discrete cosine transform reconstructs a hard black-to-white edge as a ringing overshoot — a halo of
 * grey and a fringe of lighter-than-white running along both sides of every stroke. On a plate whose
 * entire content is thin strokes, that artifact is a significant fraction of the image, it survives
 * into the printed report, and it is exactly the sort of damage the vectoriser noted in the file
 * header would then trace as real geometry. PNG is lossless, and a two-tone image compresses so well
 * that the file is typically smaller than the JPEG would have been.
 *
 * This encodes the DERIVED plate. The original photograph is not read, not re-encoded and not written
 * by anything in this file — see the header.
 */
export async function encodePlatePng(plane: GreyPlane): Promise<Blob | null> {
  const canvas = canvasFor(plane.width, plane.height);
  if (!canvas) return null;
  const context = canvas.getContext("2d") as OffscreenCanvasRenderingContext2D | CanvasRenderingContext2D | null;
  if (!context) return null;
  context.putImageData(new ImageData(greyToRgba(plane), plane.width, plane.height), 0, 0);
  if ("convertToBlob" in canvas) return canvas.convertToBlob({ type: "image/png" });
  return new Promise((resolve) => (canvas as HTMLCanvasElement).toBlob((blob) => resolve(blob), "image/png"));
}

/**
 * The derived file's name.
 *
 * It carries the original's stem so that a plate and the photograph it came from can be told to belong
 * together by reading the two filenames in a folder, months later, by somebody who has neither this
 * application nor the database — which is the state a heritage archive has to survive.
 */
export function derivedPlateName(originalName: string, lineArt: boolean): string {
  const stem = originalName.replace(/\.[^.]+$/, "") || "sketch";
  return `${stem}-${lineArt ? "lineart" : "rectified"}.png`;
}

export type RectifyOutcome = {
  file: File;
  plane: GreyPlane;
  /** Decode, then rectify + threshold. Reported on screen — see the UI's "took N ms". */
  decodeMs: number;
  processMs: number;
};

/**
 * The whole browser-side operation: decode the original, rectify, threshold, encode a NEW file.
 *
 * `corners` are in the coordinates of the plane returned by {@link loadSketchPlane}, which is what the
 * UI marks on, so nothing here has to know the original's full resolution.
 *
 * THE INPUT FILE IS ONLY EVER READ. The returned `File` is a new object with a new name; the caller
 * attaches it to a different registry field and the original's value is not touched. See the header
 * and `docs/MEDIA_PIPELINE.md` §5.
 */
export async function rectifySketchFile(
  file: Blob,
  originalName: string,
  corners: Corners,
  options?: PlateOptions
): Promise<RectifyOutcome | RectifyRefusal> {
  const loaded = await loadSketchPlane(file, options?.maxEdge ?? RECTIFY_MAX_EDGE_PX);
  if (!loaded) {
    return { ok: false, reason: "This browser could not decode that photograph, so it cannot be straightened here." };
  }
  const plate = makePlate(loaded.plane, corners, options);
  if (!plate.ok) return plate;
  const blob = await encodePlatePng(plate.plane);
  if (!blob) return { ok: false, reason: "This browser could not write the straightened image out." };
  const lineArt = options?.lineArt !== false;
  return {
    file: new File([blob], derivedPlateName(originalName, lineArt), { type: "image/png" }),
    plane: plate.plane,
    decodeMs: loaded.elapsedMs,
    processMs: plate.elapsedMs
  };
}
