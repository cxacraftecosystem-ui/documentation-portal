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

const TILT: Homography = [1.2, 0.15, 300, 0.05, 1.1, 250, 0.0004, 0.0002, 1];

const OBLIQUE: Homography = [1.2, 0.15, 300, 0.05, 1.1, 250, 0.0012, 0.0009, 1];

const HANDSET: Homography = [9.6, 1.2, 700, 0.4, 8.8, 500, 0.00035, 0.00018, 1];

const A4 = { width: 210, height: 297, unit: "mm" as const };

const A4_CORNERS: Point[] = [
  { x: 0, y: 0 },
  { x: A4.width, y: 0 },
  { x: A4.width, y: A4.height },
  { x: 0, y: A4.height }
];

function projectAll(h: Homography, points: Point[]): Point[] {
  return points.map((point) => applyHomography(h, point));
}

test.describe("photoMeasure — Gaussian elimination", () => {
  test("solves a small system exactly and refuses a singular one", () => {
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

test.describe("photoMeasure — homography", () => {
  test("applyHomography divides by the third row", () => {
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

    for (let index = 0; index < 4; index += 1) {
      const back = applyHomography(recovered!, imaged[index]);
      expect(back.x).toBeCloseTo(A4_CORNERS[index].x, 9);
      expect(back.y).toBeCloseTo(A4_CORNERS[index].y, 9);
    }

    const fifth: Point = { x: 137.5, y: 88.25 };
    const roundTrip = applyHomography(recovered!, applyHomography(TILT, fifth));
    expect(roundTrip.x).toBeCloseTo(137.5, 9);
    expect(roundTrip.y).toBeCloseTo(88.25, 9);

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
    expect(forward.value).toBeCloseTo(126.49110640673518, 12);
    expect(reversed.value).toBeCloseTo(forward.value, 12);
  });

  test("a diagonal reference measures a diagonal target", () => {
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

test.describe("photoMeasure — uncertainty", () => {
  test("a distance between two independently placed marks is √2 times as uncertain as one mark", () => {
    expect(distanceSigma(2)).toBeCloseTo(2.8284271247461903, 12);
    expect(distanceSigma(3)).toBeCloseTo(4.242640687119285, 12);
    expect(distanceSigma(0)).toBe(0);
  });

  test("a 3 px error on a 200 px reference is 1.5% of the reference", () => {
    const markSigmaPx = 3 / Math.SQRT2;
    expect(distanceSigma(markSigmaPx)).toBeCloseTo(3, 12);

    const result = measureBySameScale({
      reference: { from: { x: 0, y: 0 }, to: { x: 200, y: 0 }, length: 100, unit: "mm" },
      target: { from: { x: 0, y: 50 }, to: { x: 400, y: 50 } },
      markSigmaPx
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;

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

    expect(short.value).toBe(200);
    expect(short.relativeUncertainty).toBeCloseTo(0.06046693311223912, 12);
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
    expect(sloppy.relativeUncertainty).toBeCloseTo(0.026100766272276376, 12);
    expect(sloppy.relativeUncertainty).toBeGreaterThan(exact.relativeUncertainty);
  });

  test("the numerical propagation reproduces the closed form it replaces", () => {
    const propagated = propagateUncertainty([0, 0, 300, 400], 2, (c) =>
      distanceBetween({ x: c[0], y: c[1] }, { x: c[2], y: c[3] })
    );
    expect(propagated).not.toBeNull();

    const analytic = 2 * Math.SQRT2;
    expect(propagated!).toBeCloseTo(analytic, 4);
    expect(Math.abs(propagated! - analytic) / analytic).toBeLessThan(1e-5);
  });

  test("a mark placed at a higher zoom is a smaller uncertainty, in image pixels", () => {
    expect(markSigmaForDisplayScale(0.1)).toBeCloseTo(15, 12);
    expect(markSigmaForDisplayScale(1)).toBeCloseTo(1.5, 12);
    expect(markSigmaForDisplayScale(4)).toBeCloseTo(0.375, 12);
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
    expect(placedZoomedIn.value).toBe(placedFarOut.value);
    expect(placedFarOut.relativeUncertainty / placedZoomedIn.relativeUncertainty).toBeCloseTo(16, 9);
  });

  test("the propagator refuses when the function refuses anywhere in the neighbourhood", () => {
    expect(propagateUncertainty([1, 2], 1, () => null)).toBeNull();
    expect(propagateUncertainty([1, 2], 1, (c) => (c[0] > 1.5 ? null : 10))).toBeNull();
  });
});

test.describe("photoMeasure — rectified (four-point) measurement", () => {
  test("a length measured across a known tilt comes back to a micron", () => {
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

    expect(result.value).toBeCloseTo(156.20499351813308, 6);

    expect(result.uncorrectedValue).toBeCloseTo(108.098357173005, 6);
    expect(result.tiltCorrection).toBeCloseTo(0.30797118108483285, 9);

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
    expect(result.value).toBeCloseTo(156.20499351813308, 9);

    const recovered = solveHomography(imaged, A4_CORNERS);
    expect(recovered).not.toBeNull();
    const fifth = applyHomography(recovered!, applyHomography(HANDSET, { x: 137.5, y: 88.25 }));
    expect(fifth.x).toBeCloseTo(137.5, 9);
    expect(fifth.y).toBeCloseTo(88.25, 9);
  });

  test("an untilted rectangle rectifies to the same answer the scale method gives", () => {
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
    expect(Object.prototype.hasOwnProperty.call(result, "value")).toBe(false);
  });

  test("corners marked in a crossing order are refused rather than folded", () => {
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

test.describe("photoMeasure — proposing at an honest precision", () => {
  test("a value is rounded to the decimal place its own error bar reaches", () => {
    expect(roundToUncertainty(199.847123, 3.3541)).toEqual({ value: 200, decimals: 0 });
    expect(roundToUncertainty(19.98471, 0.335)).toEqual({ value: 20, decimals: 1 });
    expect(roundToUncertainty(19.98471, 0.0335)).toEqual({ value: 19.98, decimals: 2 });
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
    expect(convertLength(1, "cm", "hands" as never)).toBeNull();
    expect(convertLength(1, "furlong" as never, "cm")).toBeNull();
  });
});

test.describe("photoMeasure — how the answer describes itself", () => {
  test("a scale measurement is PHOTO_GEOMETRY by the SCALE technique", () => {
    const result = measureBySameScale({
      reference: { from: { x: 100, y: 100 }, to: { x: 300, y: 100 }, length: 100, unit: "mm" },
      target: { from: { x: 100, y: 400 }, to: { x: 500, y: 400 } }
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
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
    expect(Object.keys(methodMarker(result)).sort()).toEqual(["method", "technique"]);
  });
});
