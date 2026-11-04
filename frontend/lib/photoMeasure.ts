export type Point = { x: number; y: number };

export type Homography = readonly [number, number, number, number, number, number, number, number, number];

export type LengthUnit = "mm" | "cm" | "m" | "in";

export const LENGTH_UNITS: Record<LengthUnit, number> = { mm: 1, cm: 10, m: 1000, in: 25.4 };

export type Refusal = {
  ok: false;
  reason: string;
};

export type MeasurementMethod = "SCALE" | "RECTIFIED";

export type Measurement = {
  ok: true;
  method: MeasurementMethod;
  value: number;
  unit: LengthUnit;
  uncertainty: number;
  relativeUncertainty: number;
  referencePixels: number;
  targetPixels: number;
  uncorrectedValue?: number;
  tiltCorrection?: number;
};

export type MeasureResult = Measurement | Refusal;

export const PHOTO_GEOMETRY = "PHOTO_GEOMETRY";

export type MeasurementMarker = {
  method: typeof PHOTO_GEOMETRY;
  technique: MeasurementMethod;
};

export function methodMarker(measurement: Measurement): MeasurementMarker {
  return { method: PHOTO_GEOMETRY, technique: measurement.method };
}

export const MIN_REFERENCE_PIXELS = 40;

export const SCREEN_MARK_SIGMA_PX = 1.5;

export const DEFAULT_MARK_SIGMA_PX = 2;

const COLLINEAR_SIN = 0.02;

const MIN_HORIZON_MARGIN = 0.05;

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

export function distanceSigma(markSigmaPx: number): number {
  return Math.SQRT2 * markSigmaPx;
}

export function markSigmaForDisplayScale(displayScale: number, screenSigmaPx: number = SCREEN_MARK_SIGMA_PX): number {
  if (!finite(displayScale, screenSigmaPx) || displayScale <= 0 || screenSigmaPx < 0) return DEFAULT_MARK_SIGMA_PX;
  return screenSigmaPx / displayScale;
}

export function convertLength(value: number, from: LengthUnit, to: LengthUnit): number | null {
  const fromFactor = LENGTH_UNITS[from];
  const toFactor = LENGTH_UNITS[to];
  if (fromFactor === undefined || toFactor === undefined || !finite(value)) return null;
  if (from === to) return value;
  return (value * fromFactor) / toFactor;
}

export function roundToUncertainty(value: number, uncertainty: number): { value: number; decimals: number } {
  if (!finite(value, uncertainty) || uncertainty <= 0) {
    return { value, decimals: finite(value) ? 2 : 0 };
  }
  const place = Math.floor(Math.log10(uncertainty));
  const decimals = Math.min(4, Math.max(0, -place));
  const factor = 10 ** decimals;
  const rounded = Math.round(value * factor) / factor;
  return { value: finite(rounded) ? rounded : value, decimals };
}

export function solveLinearSystem(matrix: number[][], rhs: number[]): number[] | null {
  const n = rhs.length;
  if (matrix.length !== n) return null;
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
  if (!finite(...x)) return null;
  return x;
}

export function applyHomography(h: Homography, point: Point): Point {
  const w = h[6] * point.x + h[7] * point.y + h[8];
  return {
    x: (h[0] * point.x + h[1] * point.y + h[2]) / w,
    y: (h[3] * point.x + h[4] * point.y + h[5]) / w
  };
}

function homographyDenominator(h: Homography, point: Point): number {
  return h[6] * point.x + h[7] * point.y + h[8];
}

function sineAt(a: Point, b: Point, c: Point): number {
  const ax = a.x - b.x;
  const ay = a.y - b.y;
  const cx = c.x - b.x;
  const cy = c.y - b.y;
  const lengths = Math.hypot(ax, ay) * Math.hypot(cx, cy);
  if (lengths === 0) return 0;
  return Math.abs(ax * cy - ay * cx) / lengths;
}

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

export function solveHomography(from: readonly Point[], to: readonly Point[]): Homography | null {
  if (from.length !== 4 || to.length !== 4) return null;
  if (!pointsFinite(from) || !pointsFinite(to)) return null;

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

export type SameScaleInput = {
  reference: { from: Point; to: Point; length: number; unit: LengthUnit };
  target: { from: Point; to: Point };
  markSigmaPx?: number;
  referenceLengthSigma?: number;
};

export function measureBySameScale(input: SameScaleInput): MeasureResult {
  const { reference, target } = input;
  const markSigmaPx = input.markSigmaPx ?? DEFAULT_MARK_SIGMA_PX;
  const referenceLengthSigma = input.referenceLengthSigma ?? 0;

  if (!pointsFinite([reference.from, reference.to, target.from, target.to])) {
    return { ok: false, reason: "A mark is missing. Place all four again." };
  }
  if (!finite(reference.length, markSigmaPx, referenceLengthSigma) || markSigmaPx < 0 || referenceLengthSigma < 0) {
    return { ok: false, reason: "Enter a valid reference length." };
  }
  if (LENGTH_UNITS[reference.unit] === undefined) {
    return { ok: false, reason: `“${reference.unit}” isn’t a supported unit.` };
  }
  if (reference.length <= 0) {
    return { ok: false, reason: "Reference length must be above zero." };
  }

  const referencePixels = distanceBetween(reference.from, reference.to);
  const targetPixels = distanceBetween(target.from, target.to);
  if (referencePixels < MIN_REFERENCE_PIXELS) {
    return {
      ok: false,
      reason:
        `The reference is too short (${Math.round(referencePixels)} px). Zoom in and spread the marks.`
    };
  }
  if (targetPixels === 0) {
    return { ok: false, reason: "The two object marks overlap." };
  }

  const value = (reference.length * targetPixels) / referencePixels;
  const sigmaDistance = distanceSigma(markSigmaPx);
  const relative = Math.sqrt(
    (sigmaDistance / referencePixels) ** 2 +
      (sigmaDistance / targetPixels) ** 2 +
      (referenceLengthSigma / reference.length) ** 2
  );
  const uncertainty = relative * value;
  if (!finite(value, relative, uncertainty)) {
    return { ok: false, reason: "These marks don’t give a reliable measurement." };
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

export type RectificationInput = {
  corners: readonly [Point, Point, Point, Point];
  rectangle: { width: number; height: number; unit: LengthUnit };
  target: { from: Point; to: Point };
  markSigmaPx?: number;
};

export function measureByRectification(input: RectificationInput): MeasureResult {
  const { corners, rectangle, target } = input;
  const markSigmaPx = input.markSigmaPx ?? DEFAULT_MARK_SIGMA_PX;

  if (!pointsFinite(corners) || !pointsFinite([target.from, target.to])) {
    return { ok: false, reason: "A mark is missing. Place the corners and ends again." };
  }
  if (!finite(rectangle.width, rectangle.height, markSigmaPx) || markSigmaPx < 0) {
    return { ok: false, reason: "Enter a valid rectangle size." };
  }
  if (LENGTH_UNITS[rectangle.unit] === undefined) {
    return { ok: false, reason: `“${rectangle.unit}” isn’t a supported unit.` };
  }
  if (rectangle.width <= 0 || rectangle.height <= 0) {
    return { ok: false, reason: "Width and height must be above zero." };
  }
  if (distanceBetween(target.from, target.to) === 0) {
    return { ok: false, reason: "The two object marks overlap." };
  }
  if (!isSimpleConvexQuad(corners)) {
    return {
      ok: false,
      reason:
        "Corners cross over. Mark them in order around the edge."
    };
  }

  const world: Point[] = [
    { x: 0, y: 0 },
    { x: rectangle.width, y: 0 },
    { x: rectangle.width, y: rectangle.height },
    { x: 0, y: rectangle.height }
  ];

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
        "Can’t use these corners. Re-mark them and keep both ends on the surface."
    };
  }
  if (value === 0) {
    return { ok: false, reason: "Both ends land in the same place." };
  }

  const uncertainty = propagateUncertainty(coordinates, markSigmaPx, evaluate);
  if (uncertainty === null) {
    return {
      ok: false,
      reason:
        "Too angled to measure. Photograph more squarely and mark again."
    };
  }

  const referencePixels = distanceBetween(corners[0], corners[1]);
  const targetPixels = distanceBetween(target.from, target.to);
  const uncorrectedValue =
    referencePixels > 0 ? (rectangle.width * targetPixels) / referencePixels : Number.NaN;
  const tiltCorrection = finite(uncorrectedValue) ? Math.abs(value - uncorrectedValue) / value : Number.NaN;

  const relativeUncertainty = uncertainty / value;
  if (!finite(value, uncertainty, relativeUncertainty, referencePixels, targetPixels, uncorrectedValue, tiltCorrection)) {
    return { ok: false, reason: "These marks don’t give a reliable measurement." };
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
