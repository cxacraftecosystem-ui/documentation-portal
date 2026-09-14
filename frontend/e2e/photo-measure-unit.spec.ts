import { expect, test } from "@playwright/test";

import {
  DEFAULT_MARK_SIGMA_PX,
  MIN_REFERENCE_PIXELS,
  applyHomography,
  convertLength,
  distanceBetween,
  distanceSigma,
  markSigmaForDisplayScale,
  measureByRectification,
  measureBySameScale,
  methodMarker,
  propagateUncertainty,
  roundToUncertainty,
  solveHomography,
  solveLinearSystem,
  type Homography,
  type Point
} from "@/lib/photoMeasure";

/**
 * Unit tests for `lib/photoMeasure.ts` — the projective geometry behind "measure this from a photo".
 *
 * WHY SYNTHETIC CASES AND NOT PHOTOGRAPHS. Every case below has an answer that is known in advance
 * because the case was CONSTRUCTED from the answer: a scale of exactly two, a homography written
 * down and then inverted, a segment whose world length is √24400. A test against a real photograph
 * could only assert that the module agrees with whatever it printed the day it was written, which is
 * a regression test for arithmetic nobody ever checked.
 *
 * WHY THE NUMBERS ARE PINNED AND NOT THE VERDICTS. `expect(result.ok).toBe(true)` passes for a
 * function that returns the reference length unchanged. Every assertion here is on a VALUE, to a
 * stated precision, and the precisions are chosen so that a real defect cannot slip under them:
 * 1e-9 on a homography recovery, 1e-6 on a rectified length in millimetres (a micron on an A4 sheet).
 *
 * THE FIFTH POINT IS THE WHOLE HOMOGRAPHY TEST. Any solve that returns something at all reproduces
 * the four points it was fitted to — that is what "fitted" means, and a completely wrong
 * implementation still passes it. A point that took no part in the fit is the only evidence that
 * what came back is the transform rather than a curve through four dots.
 *
 * THE FILENAME ENDS `-unit` DELIBERATELY: `npm run test:unit` selects on exactly that suffix, and a
 * pure suite that the unit gate does not run is a suite nobody notices going red.
 */

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/**
 * A deliberately awkward projective transform: it rotates a little, shears a little, translates a
 * long way, and — the part that matters — has a non-zero bottom row, so parallel lines in the world
 * genuinely converge in the image. A transform with h31 = h32 = 0 is an affine map, and an affine
 * map is recovered correctly by code that has no projective handling in it at all.
 */
const TILT: Homography = [1.2, 0.15, 300, 0.05, 1.1, 250, 0.0004, 0.0002, 1];

/**
 * The same shape of transform with a much steeper bottom row — a handset held over a sheet at arm's
 * length rather than square above it, which is how these photographs are actually taken.
 *
 * It exists because {@link TILT} is too polite to make the argument: under TILT the naive two-mark
 * reading is only 1.6% short, and a test asserting that would suggest the four-point method is a
 * refinement. Under this one it is 31% short, which is what an oblique photograph really costs.
 */
const OBLIQUE: Homography = [1.2, 0.15, 300, 0.05, 1.1, 250, 0.0012, 0.0009, 1];

/**
 * The same sheet as photographed by an actual handset: an A4 spread across a 4000x3000 frame.
 *
 * THIS FIXTURE IS THE ONLY ONE THAT TESTS THE CONDITIONING. The 8x8 system built from THESE
 * coordinates mixes a constant column with x·u terms of order 1e7, and that is where a solve starts
 * throwing away digits. A suite that only ever measured a 500 px toy image would let a conditioning
 * regression through while reporting full marks — the module's own header records the measurement
 * that settled whether Hartley normalisation was needed here, and this is the case it was made on.
 */
const HANDSET: Homography = [9.6, 1.2, 700, 0.4, 8.8, 500, 0.00035, 0.00018, 1];

/** A4, in millimetres — the sheet a researcher in a village actually has. */
const A4 = { width: 210, height: 297, unit: "mm" as const };

/** The A4 corners in world millimetres, in order around the sheet. */
const A4_CORNERS: Point[] = [
  { x: 0, y: 0 },
  { x: A4.width, y: 0 },
  { x: A4.width, y: A4.height },
  { x: 0, y: A4.height }
];

function projectAll(h: Homography, points: Point[]): Point[] {
  return points.map((point) => applyHomography(h, point));
}

// ---------------------------------------------------------------------------
// The linear algebra, on its own
// ---------------------------------------------------------------------------

test.describe("photoMeasure — Gaussian elimination", () => {
  test("solves a small system exactly and refuses a singular one", () => {
    // 2x + y = 5 ; x - y = 1  →  x = 2, y = 1.
    const solved = solveLinearSystem(
      [
        [2, 1],
        [1, -1]
      ],
      [5, 1]
    );
    expect(solved).not.toBeNull();
    expect(solved![0]).toBeCloseTo(2, 12);
    expect(solved![1]).toBeCloseTo(1, 12);

    // The second row is the first, doubled: there is no unique answer, and returning one would be a
    // fabrication. NOT an Infinity, NOT a NaN — null, so every caller has to decide what to say.
    expect(
      solveLinearSystem(
        [
          [1, 2],
          [2, 4]
        ],
        [3, 6]
      )
    ).toBeNull();
  });

  test("partial pivoting survives a zero in the first pivot position", () => {
    // Without a row swap the very first division is by zero and every later entry is NaN.
    const solved = solveLinearSystem(
      [
        [0, 1],
        [1, 0]
      ],
      [3, 4]
    );
    expect(solved).not.toBeNull();
    expect(solved![0]).toBeCloseTo(4, 12);
    expect(solved![1]).toBeCloseTo(3, 12);
  });
});

// ---------------------------------------------------------------------------
// The homography
// ---------------------------------------------------------------------------

test.describe("photoMeasure — homography", () => {
  test("applyHomography divides by the third row", () => {
    // Hand-computed: x' = 1.2·210 + 300 = 552 ; y' = 0.05·210 + 250 = 260.5 ; w = 0.0004·210 + 1 = 1.084.
    const mapped = applyHomography(TILT, { x: 210, y: 0 });
    expect(mapped.x).toBeCloseTo(552 / 1.084, 9);
    expect(mapped.y).toBeCloseTo(260.5 / 1.084, 9);
    expect(mapped.x).toBeCloseTo(509.2250922509225, 9);
    expect(mapped.y).toBeCloseTo(240.3136531365314, 9);
  });

  test("a known homography applied to known points is recovered from them", () => {
    const imaged = projectAll(TILT, A4_CORNERS);
    const recovered = solveHomography(imaged, A4_CORNERS);
    expect(recovered).not.toBeNull();

    // The four fitted corners come back exactly — necessary, and on its own worth nothing.
    for (let index = 0; index < 4; index += 1) {
      const back = applyHomography(recovered!, imaged[index]);
      expect(back.x).toBeCloseTo(A4_CORNERS[index].x, 9);
      expect(back.y).toBeCloseTo(A4_CORNERS[index].y, 9);
    }

    // THE FIFTH POINT — see the file header. It took no part in the fit, so only a genuinely
    // recovered transform sends it home.
    const fifth: Point = { x: 137.5, y: 88.25 };
    const roundTrip = applyHomography(recovered!, applyHomography(TILT, fifth));
    expect(roundTrip.x).toBeCloseTo(137.5, 9);
    expect(roundTrip.y).toBeCloseTo(88.25, 9);

    // A sixth, far outside the marked rectangle, where a badly-conditioned solve goes wrong first.
    const outside: Point = { x: -60, y: 420 };
    const outsideBack = applyHomography(recovered!, applyHomography(TILT, outside));
    expect(outsideBack.x).toBeCloseTo(-60, 8);
    expect(outsideBack.y).toBeCloseTo(420, 8);
  });

  test("four collinear points are refused rather than solved", () => {
    const collinear: Point[] = [
      { x: 100, y: 100 },
      { x: 200, y: 150 },
      { x: 300, y: 200 },
      { x: 400, y: 250 }
    ];
    expect(solveHomography(collinear, A4_CORNERS)).toBeNull();
  });

  test("three collinear points out of four are refused too", () => {
    // The failure mode that actually happens: a researcher marks three corners along the edge of a
    // sheet lying flat against a wall. The system is singular and Gaussian elimination on it produces
    // ±Infinity, which then propagates into a finite-looking millimetre figure.
    const nearlyDegenerate: Point[] = [
      { x: 100, y: 100 },
      { x: 200, y: 100 },
      { x: 300, y: 100 },
      { x: 150, y: 400 }
    ];
    expect(solveHomography(nearlyDegenerate, A4_CORNERS)).toBeNull();
  });

  test("a duplicated corner is refused", () => {
    const duplicated: Point[] = [
      { x: 100, y: 100 },
      { x: 100, y: 100 },
      { x: 300, y: 220 },
      { x: 120, y: 260 }
    ];
    expect(solveHomography(duplicated, A4_CORNERS)).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// The same-plane scale method
// ---------------------------------------------------------------------------

test.describe("photoMeasure — same-plane scale", () => {
  test("an exact scale of two returns exactly twice the reference length", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 300, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 400 }, to: { x: 500, y: 400 } }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value).toBe(200);
    expect(result.referencePixels).toBe(200);
    expect(result.targetPixels).toBe(400);
    expect(result.method).toBe("SCALE");
    expect(result.unit).toBe("mm");
  });

  test("the scale is invariant to the direction the marks were placed in", () => {
    const forward = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 300, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 400 }, to: { x: 340, y: 320 } }
    });
    const reversed = measureBySameScale({
      reference: { from: { x: 300, y: 100 }, to: { x: 100, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 340, y: 320 }, to: { x: 100, y: 400 } }
    });
    expect(forward.ok && reversed.ok).toBe(true);
    if (!forward.ok || !reversed.ok) return;
    // 240-80 → hypot(240, 80) = 252.98221281347036 px over a 200 px / 100 mm reference.
    expect(forward.value).toBeCloseTo(126.49110640673518, 12);
    expect(reversed.value).toBeCloseTo(forward.value, 12);
  });

  test("a diagonal reference measures a diagonal target", () => {
    // 3-4-5, twice over: the reference is 50 px for 25 mm, the target is 250 px.
    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 30, y: 40 }, length: 25, unit: "mm" },
      target: { from: { x: 10, y: 10 }, to: { x: 160, y: 210 } }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.referencePixels).toBeCloseTo(50, 12);
    expect(result.targetPixels).toBeCloseTo(250, 12);
    expect(result.value).toBeCloseTo(125, 12);
  });
});

// ---------------------------------------------------------------------------
// Uncertainty
// ---------------------------------------------------------------------------

test.describe("photoMeasure — uncertainty", () => {
  test("a distance between two independently placed marks is √2 times as uncertain as one mark", () => {
    expect(distanceSigma(2)).toBeCloseTo(2.8284271247461903, 12);
    expect(distanceSigma(3)).toBeCloseTo(4.242640687119285, 12);
    expect(distanceSigma(0)).toBe(0);
  });

  test("a 3 px error on a 200 px reference is 1.5% of the reference", () => {
    // Stated as the module's own arithmetic rather than as a comment, so it cannot drift: with the
    // per-endpoint sigma set to 3/√2 the DISTANCE sigma is exactly 3 px.
    const markSigmaPx = 3 / Math.SQRT2;
    expect(distanceSigma(markSigmaPx)).toBeCloseTo(3, 12);

    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    // 3/200 = 1.5% on the reference, 3/400 = 0.75% on the target, in quadrature:
    // √(0.015² + 0.0075²) = 0.016770509831248423.
    expect(result.relativeUncertainty).toBeCloseTo(0.016770509831248423, 12);
    expect(result.value).toBe(200);
    expect(result.uncertainty).toBeCloseTo(3.3541019662496847, 12);
  });

  test("a shorter reference is a wider error bar, and the widening is quantified", () => {
    const markSigmaPx = 3 / Math.SQRT2;
    const shared = {
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx
    };
    const long = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      ...shared
    });
    const short = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 50, y: 0 }, length: 25, unit: "mm" },
      ...shared
    });
    expect(long.ok && short.ok).toBe(true);
    if (!long.ok || !short.ok) return;

    // Same answer — 400 px against 50 px/25 mm is still 200 mm — and four times the doubt from the
    // reference: √((3/50)² + (3/400)²) = √0.00365625 = 0.06046693311223912.
    expect(short.value).toBe(200);
    expect(short.relativeUncertainty).toBeCloseTo(0.06046693311223912, 12);
    // The widening is pinned as a RATIO as well, because that is the statement a researcher acts on:
    // marking against a scale bar a quarter as long in the frame is 3.6 times the doubt.
    expect(short.relativeUncertainty / long.relativeUncertainty).toBeCloseTo(3.605551275463989, 9);
  });

  test("a known reference length that is itself uncertain widens the bar further", () => {
    const base = {
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" as const },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx: 3 / Math.SQRT2
    };
    const exact = measureBySameScale(base);
    const sloppy = measureBySameScale({ ...base, referenceLengthSigma: 2 });
    expect(exact.ok && sloppy.ok).toBe(true);
    if (!exact.ok || !sloppy.ok) return;
    // 2 mm on a 100 mm card is another 2%, in quadrature with 1.677%:
    // √(0.00028125 + 0.0004) = √0.00068125 = 0.026100766272276376.
    expect(sloppy.relativeUncertainty).toBeCloseTo(0.026100766272276376, 12);
    expect(sloppy.relativeUncertainty).toBeGreaterThan(exact.relativeUncertainty);
  });

  test("the numerical propagation reproduces the closed form it replaces", () => {
    // The length of a segment between two marks, as a function of its four coordinates. The analytic
    // answer is √2·σ, and the generic propagator is what the rectified path has to trust — so it is
    // checked here against a case whose answer is known rather than only inside one.
    const propagated = propagateUncertainty([0, 0, 300, 400], 2, (c) =>
      distanceBetween({ x: c[0], y: c[1] }, { x: c[2], y: c[3] })
    );
    expect(propagated).not.toBeNull();

    // AGREEMENT TO ~4e-6 RELATIVE, AND NOT TO MACHINE PRECISION, ON PURPOSE. The propagator steps by
    // σ itself, so what is left over is the genuine second-order curvature of `distance` across ±2 px
    // of a 500 px segment — a property of a first-order propagation, not a defect in it. Pinning the
    // residual rather than hiding it behind a loose tolerance is what makes this test fail if the
    // step size or the differencing scheme is ever changed.
    const analytic = 2 * Math.SQRT2;
    expect(propagated!).toBeCloseTo(analytic, 4);
    expect(Math.abs(propagated! - analytic) / analytic).toBeLessThan(1e-5);
  });

  test("a mark placed at a higher zoom is a smaller uncertainty, in image pixels", () => {
    // A 4000 px photograph shown 400 px wide is displayed at 0.1, so one screen pixel IS ten image
    // pixels: careful marking at that zoom is still worth only ±15 image px.
    expect(markSigmaForDisplayScale(0.1)).toBeCloseTo(15, 12);
    expect(markSigmaForDisplayScale(1)).toBeCloseTo(1.5, 12);
    expect(markSigmaForDisplayScale(4)).toBeCloseTo(0.375, 12);
    // A caller that cannot say what zoom a mark was placed at gets the 1:1 fallback rather than a
    // division by zero — and never a NaN sigma, which would silently erase the whole error bar.
    expect(markSigmaForDisplayScale(0)).toBe(DEFAULT_MARK_SIGMA_PX);
    expect(markSigmaForDisplayScale(Number.NaN)).toBe(DEFAULT_MARK_SIGMA_PX);
    expect(markSigmaForDisplayScale(-2)).toBe(DEFAULT_MARK_SIGMA_PX);
  });

  test("zooming in narrows the error bar on the very same marks", () => {
    const marks = {
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" as const },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } }
    };
    const placedFarOut = measureBySameScale({ ...marks, markSigmaPx: markSigmaForDisplayScale(0.25) });
    const placedZoomedIn = measureBySameScale({ ...marks, markSigmaPx: markSigmaForDisplayScale(4) });
    expect(placedFarOut.ok && placedZoomedIn.ok).toBe(true);
    if (!placedFarOut.ok || !placedZoomedIn.ok) return;
    // The ANSWER is identical — marks are stored in image pixels, so zoom cannot move a measurement —
    // and only the doubt about it changes, by the factor the zoom changed by.
    expect(placedZoomedIn.value).toBe(placedFarOut.value);
    expect(placedFarOut.relativeUncertainty / placedZoomedIn.relativeUncertainty).toBeCloseTo(16, 9);
  });

  test("the propagator refuses when the function refuses anywhere in the neighbourhood", () => {
    // A perturbation that lands on a degenerate configuration means the error bar cannot be computed,
    // and an error bar that quietly omits one of its twelve terms understates the doubt.
    expect(propagateUncertainty([1, 2], 1, () => null)).toBeNull();
    expect(propagateUncertainty([1, 2], 1, (c) => (c[0] > 1.5 ? null : 10))).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// The four-point rectification
// ---------------------------------------------------------------------------

test.describe("photoMeasure — rectified (four-point) measurement", () => {
  test("a length measured across a known tilt comes back to a micron", () => {
    // A segment whose WORLD length is √(120² + 100²) = √24400 = 156.20499351813308 mm, photographed
    // through TILT along with the A4 sheet it lies on.
    const worldFrom: Point = { x: 30, y: 40 };
    const worldTo: Point = { x: 150, y: 140 };
    const imaged = projectAll(TILT, A4_CORNERS);
    const [targetFrom, targetTo] = projectAll(TILT, [worldFrom, worldTo]);

    const result = measureByRectification({
      corners: [imaged[0], imaged[1], imaged[2], imaged[3]],
      rectangle: A4,
      target: { from: targetFrom, to: targetTo }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.method).toBe("RECTIFIED");
    expect(result.unit).toBe("mm");
    expect(result.value).toBeCloseTo(156.20499351813308, 6);
    expect(Number.isFinite(result.uncertainty)).toBe(true);
    expect(result.uncertainty).toBeGreaterThan(0);
  });

  test("the tilt correction is reported, and it is the reason the feature exists", () => {
    const imaged = projectAll(OBLIQUE, A4_CORNERS);
    const [targetFrom, targetTo] = projectAll(OBLIQUE, [
      { x: 30, y: 40 },
      { x: 150, y: 140 }
    ]);
    const result = measureByRectification({
      corners: [imaged[0], imaged[1], imaged[2], imaged[3]],
      rectangle: A4,
      target: { from: targetFrom, to: targetTo }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    // The rectified answer is still exact, however oblique the photograph.
    expect(result.value).toBeCloseTo(156.20499351813308, 6);

    // What the SAME two marks would have said with the sheet's first edge used as a plain scale bar:
    // 108.10 mm against a true 156.20 mm. THIS IS THE WHOLE ARGUMENT FOR THE FOUR-POINT METHOD — a
    // 31% error, on a dimension that is multiplied into a cost sheet, from a photograph that looks
    // perfectly reasonable.
    expect(result.uncorrectedValue).toBeCloseTo(108.098357173005, 6);
    expect(result.tiltCorrection).toBeCloseTo(0.30797118108483285, 9);

    // And the gentler tilt is reported as gentler, so the number means something rather than always
    // reading "large": the same object, the same marks, a less oblique camera.
    const gentle = measureByRectification({
      corners: projectAll(TILT, A4_CORNERS) as [Point, Point, Point, Point],
      rectangle: A4,
      target: {
        from: applyHomography(TILT, { x: 30, y: 40 }),
        to: applyHomography(TILT, { x: 150, y: 140 })
      }
    });
    expect(gentle.ok).toBe(true);
    if (!gentle.ok) return;
    expect(gentle.value).toBeCloseTo(156.20499351813308, 6);
    expect(gentle.tiltCorrection).toBeCloseTo(0.01592637686383975, 9);
  });

  test("at real handset resolution the rectified length is still exact", () => {
    // A 12 MP frame, an A4 sheet across the middle of it, and a 156.20499351813308 mm segment on the
    // sheet. See HANDSET for why this case is not redundant with the 500 px ones above.
    const imaged = projectAll(HANDSET, A4_CORNERS);
    for (const corner of imaged) {
      expect(corner.x).toBeGreaterThan(0);
      expect(corner.x).toBeLessThan(4000);
      expect(corner.y).toBeGreaterThan(0);
      expect(corner.y).toBeLessThan(3000);
    }

    const [targetFrom, targetTo] = projectAll(HANDSET, [
      { x: 30, y: 40 },
      { x: 150, y: 140 }
    ]);
    const result = measureByRectification({
      corners: [imaged[0], imaged[1], imaged[2], imaged[3]],
      rectangle: A4,
      target: { from: targetFrom, to: targetTo }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // A nanometre on an A4 sheet. Nothing in the field needs this precision; it is asserted because
    // it is what separates a properly conditioned solve from one that is merely close.
    expect(result.value).toBeCloseTo(156.20499351813308, 9);

    // And the transform itself, on a point that took no part in the fit, to the same precision.
    const recovered = solveHomography(imaged, A4_CORNERS);
    expect(recovered).not.toBeNull();
    const fifth = applyHomography(recovered!, applyHomography(HANDSET, { x: 137.5, y: 88.25 }));
    expect(fifth.x).toBeCloseTo(137.5, 9);
    expect(fifth.y).toBeCloseTo(88.25, 9);
  });

  test("an untilted rectangle rectifies to the same answer the scale method gives", () => {
    // The correction must not INVENT a difference where there is none: with the sheet square to the
    // sensor the two methods have to agree exactly, or the four-point path would silently move every
    // dimension a researcher bothered to be careful about.
    const corners: [Point, Point, Point, Point] = [
      { x: 100, y: 100 },
      { x: 520, y: 100 },
      { x: 520, y: 694 },
      { x: 100, y: 694 }
    ];
    const target = { from: { x: 150, y: 200 }, to: { x: 350, y: 200 } };
    const rectified = measureByRectification({ corners, rectangle: A4, target });
    const scaled = measureBySameScale({
      reference: { from: corners[0], to: corners[1], length: A4.width, unit: "mm" },
      target
    });
    expect(rectified.ok && scaled.ok).toBe(true);
    if (!rectified.ok || !scaled.ok) return;
    expect(rectified.value).toBeCloseTo(100, 9);
    expect(scaled.value).toBeCloseTo(100, 9);
    expect(rectified.tiltCorrection).toBeCloseTo(0, 9);
  });

  test("collinear corners are refused with a reason and never with a number", () => {
    const result = measureByRectification({
      corners: [
        { x: 100, y: 100 },
        { x: 200, y: 100 },
        { x: 300, y: 100 },
        { x: 400, y: 100 }
      ],
      rectangle: A4,
      target: { from: { x: 120, y: 200 }, to: { x: 260, y: 260 } }
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason.length).toBeGreaterThan(20);
    // The whole point of the refusal: nothing that could be read as a measurement comes back.
    expect(Object.prototype.hasOwnProperty.call(result, "value")).toBe(false);
  });

  test("corners marked in a crossing order are refused rather than folded", () => {
    // A bow-tie. The homography still solves — it is a perfectly good projective map — and it
    // produces a plausible-looking millimetre figure from a mirror-folded plane. There is nothing
    // downstream that could ever notice, so it has to be caught here.
    const imaged = projectAll(TILT, A4_CORNERS);
    const result = measureByRectification({
      corners: [imaged[0], imaged[1], imaged[3], imaged[2]],
      rectangle: A4,
      target: { from: imaged[0], to: imaged[2] }
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain("order");
  });
});

// ---------------------------------------------------------------------------
// Refusals, and the NaN guard
// ---------------------------------------------------------------------------

test.describe("photoMeasure — refusals", () => {
  test("a reference too short to measure against is refused, not scaled up", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 100 + MIN_REFERENCE_PIXELS - 1, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 300 }, to: { x: 900, y: 300 } }
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toContain("reference");
  });

  test("exactly at the floor it is accepted — the floor is a floor, not a fence", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 100 + MIN_REFERENCE_PIXELS, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 300 }, to: { x: 900, y: 300 } }
    });
    expect(result.ok).toBe(true);
  });

  test("a zero-length target is refused", () => {
    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 50, y: 50 }, to: { x: 50, y: 50 } }
    });
    expect(result.ok).toBe(false);
  });

  test("a reference of zero or negative real length is refused", () => {
    for (const length of [0, -10]) {
      const result = measureBySameScale({
        reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length, unit: "mm" },
        target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } }
      });
      expect(result.ok).toBe(false);
    }
  });

  test("NaN and Infinity in, refusal out — never a finite-looking answer", () => {
    const poisoned = measureBySameScale({
      reference: { from: { x: Number.NaN, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } }
    });
    expect(poisoned.ok).toBe(false);

    const infinite = measureByRectification({
      corners: [
        { x: 0, y: 0 },
        { x: Number.POSITIVE_INFINITY, y: 0 },
        { x: 400, y: 500 },
        { x: 0, y: 500 }
      ],
      rectangle: A4,
      target: { from: { x: 10, y: 10 }, to: { x: 20, y: 20 } }
    });
    expect(infinite.ok).toBe(false);
  });

  test("every field of a successful measurement is a finite number", () => {
    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx: DEFAULT_MARK_SIGMA_PX
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    for (const [key, value] of Object.entries(result)) {
      if (typeof value !== "number") continue;
      expect(Number.isFinite(value), `${key} is not finite`).toBe(true);
    }
  });
});

// ---------------------------------------------------------------------------
// Units and honest precision
// ---------------------------------------------------------------------------

test.describe("photoMeasure — proposing at an honest precision", () => {
  test("a value is rounded to the decimal place its own error bar reaches", () => {
    // 200 mm ± 3.35 mm: the doubt is in the units column, so the answer is quoted there too.
    expect(roundToUncertainty(199.847123, 3.3541)).toEqual({ value: 200, decimals: 0 });
    // ± 0.335 → one decimal.
    expect(roundToUncertainty(19.98471, 0.335)).toEqual({ value: 20, decimals: 1 });
    // ± 0.0335 → two.
    expect(roundToUncertainty(19.98471, 0.0335)).toEqual({ value: 19.98, decimals: 2 });
    // ± 0.00335 → three.
    expect(roundToUncertainty(19.98471, 0.00335)).toEqual({ value: 19.985, decimals: 3 });
  });

  test("an absurdly narrow bar is capped rather than quoting floating-point noise", () => {
    const rounded = roundToUncertainty(19.984712345678, 1e-9);
    expect(rounded.decimals).toBe(4);
    expect(rounded.value).toBeCloseTo(19.9847, 12);
  });

  test("a missing or impossible error bar never produces NaN", () => {
    expect(roundToUncertainty(12.3456, 0).value).toBe(12.3456);
    expect(roundToUncertainty(12.3456, -1).value).toBe(12.3456);
    expect(Number.isNaN(roundToUncertainty(Number.NaN, 1).value)).toBe(true);
    expect(roundToUncertainty(Number.NaN, 1).decimals).toBe(0);
  });

  test("the rounded proposal survives the unit conversion into a record column", () => {
    // The whole chain a proposal actually travels: measured in mm, offered into an inches column.
    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx: 3 / Math.SQRT2
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    const asCm = convertLength(result.value, result.unit, "cm");
    const doubtCm = convertLength(result.uncertainty, result.unit, "cm");
    expect(asCm).not.toBeNull();
    expect(doubtCm).not.toBeNull();
    // 200 mm ± 3.354 mm becomes 20 cm ± 0.335 cm, quoted to one decimal.
    expect(asCm!).toBeCloseTo(20, 12);
    expect(doubtCm!).toBeCloseTo(0.33541019662496847, 12);
    expect(roundToUncertainty(asCm!, doubtCm!)).toEqual({ value: 20, decimals: 1 });
  });
});

test.describe("photoMeasure — units", () => {
  test("converts between the units a reference or a column can declare", () => {
    expect(convertLength(1, "m", "cm")).toBeCloseTo(100, 12);
    expect(convertLength(250, "mm", "cm")).toBeCloseTo(25, 12);
    expect(convertLength(1, "in", "mm")).toBeCloseTo(25.4, 12);
    expect(convertLength(30.48, "cm", "in")).toBeCloseTo(12, 12);
    expect(convertLength(7, "cm", "cm")).toBe(7);
  });

  test("an unknown unit is refused rather than assumed", () => {
    // A column declaring `unit="hands"` must not have a centimetre figure written into it.
    expect(convertLength(1, "cm", "hands" as never)).toBeNull();
    expect(convertLength(1, "furlong" as never, "cm")).toBeNull();
  });
});

/**
 * The marker is the only thing in this module that another system PARSES rather than reads, so these
 * are string tests and they are meant to be. The API's `measurement_provenance` service matches
 * `method` against an enum and `technique` against its geometry techniques, and a marker that misses
 * either is not rejected — it degrades to `UNRECORDED`, silently and by design, because failing a
 * researcher's save over a provenance hint would trade a real loss for a cosmetic one. So a typo here
 * does not break a build or a request. It writes "nobody recorded a method" onto a dimension that was
 * in fact measured, for ever, and the only thing standing between that and production is an assertion
 * on the literal spelling.
 */
test.describe("photoMeasure — how the answer describes itself", () => {
  test("a scale measurement is PHOTO_GEOMETRY by the SCALE technique", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 300, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 400 }, to: { x: 500, y: 400 } }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // Both keys, both spellings, and the whole object — an extra key would reach the provenance blob
    // and be stored beside a dimension for the life of the record.
    expect(methodMarker(result)).toEqual({ method: "PHOTO_GEOMETRY", technique: "SCALE" });
  });

  test("a rectified measurement is the SAME method and a different technique", () => {
    const imaged = projectAll(TILT, A4_CORNERS);
    const [targetFrom, targetTo] = projectAll(TILT, [
      { x: 30, y: 40 },
      { x: 150, y: 140 }
    ]);
    const result = measureByRectification({
      corners: [imaged[0], imaged[1], imaged[2], imaged[3]],
      rectangle: A4,
      target: { from: targetFrom, to: targetTo }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // THE POINT OF THE PAIR: four corners and two marks are the same KIND of number to whoever reads
    // the record — arithmetic over marks a person placed — and differ only for somebody re-deriving
    // it. That is why the server carries a separate `technique` key instead of two more enum members,
    // and a change that promoted RECTIFIED to its own `method` would break this test first.
    expect(methodMarker(result)).toEqual({ method: "PHOTO_GEOMETRY", technique: "RECTIFIED" });
  });

  test("the marker carries the method and nothing about how well it was measured", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 300, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 400 }, to: { x: 500, y: 400 } }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.uncertainty).toBeGreaterThan(0);
    // `uncertainty` IS a propagated error bar, and the wire keeps a hard `confidenceIsCalibrated:
    // false` beside the vision model's number precisely so the two can never be mistaken for each
    // other on a wire that carries both. Putting this one on the marker would land a real error bar
    // in the same key an uncalibrated self-report arrives under. It is spent on screen instead,
    // before the accept, because the record has no column for it.
    expect(Object.keys(methodMarker(result)).sort()).toEqual(["method", "technique"]);
  });
});
