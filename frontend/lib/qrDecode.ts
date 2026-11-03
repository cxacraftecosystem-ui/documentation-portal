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

export type QrDecodeRefusal =
  | "NO_SYMBOL"
  | "UNSUPPORTED_VERSION"
  | "UNREADABLE"
  | "DATA_UNRECOVERABLE"
  | "UNSUPPORTED_CONTENT";

export type QrRegion = { x: number; y: number; width: number; height: number };

export type QrDecodeResult =
  | { ok: true; text: string; version: number; level: QrEccLevel; mask: number }
  | {
      ok: false;
      reason: QrDecodeRefusal;
      message: string;
      region?: QrRegion;
    };

function gfPower(exponent: number): number {
  return GF_EXP[((exponent % 255) + 255) % 255];
}

function gfInverse(a: number): number {
  if (a === 0) return 0;
  return GF_EXP[255 - GF_LOG[a]];
}

function gfDivide(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[(GF_LOG[a] - GF_LOG[b] + 255) % 255];
}

type Poly = Uint8Array;

function polyEvaluate(poly: Poly, x: number): number {
  let value = 0;
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

function polyDerivative(poly: Poly): Poly {
  const out = new Uint8Array(Math.max(1, poly.length - 1));
  for (let degree = 1; degree < poly.length; degree += 2) out[degree - 1] = poly[degree];
  return out;
}

function correctBlock(received: Uint8Array, ecCount: number): number {
  const syndromes = new Uint8Array(ecCount);
  let anyError = false;
  for (let index = 0; index < ecCount; index++) {
    let value = 0;
    const x = gfPower(index);
    for (let position = 0; position < received.length; position++) value = gfMultiply(value, x) ^ received[position];
    syndromes[index] = value;
    if (value !== 0) anyError = true;
  }
  if (!anyError) return 0;

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

  let degree = lambda.length - 1;
  while (degree > 0 && lambda[degree] === 0) degree--;
  if (degree !== errors || errors === 0) return -1;
  if (errors * 2 > ecCount) return -1;

  const positions: number[] = [];
  for (let power = 0; power < received.length; power++) {
    if (polyEvaluate(lambda, gfPower(255 - (power % 255))) === 0) positions.push(power);
  }
  if (positions.length !== errors) return -1;

  const syndromePoly = syndromes;
  const product = polyMultiply(syndromePoly, lambda);
  const omega = product.slice(0, ecCount);
  const lambdaPrime = polyDerivative(lambda);

  for (const power of positions) {
    const locator = gfPower(power);
    const inverse = gfInverse(locator);
    const denominator = polyEvaluate(lambdaPrime, inverse);
    if (denominator === 0) return -1;
    const magnitude = gfMultiply(locator, gfDivide(polyEvaluate(omega, inverse), denominator));
    const index = received.length - 1 - power;
    if (index < 0 || index >= received.length) return -1;
    received[index] ^= magnitude;
  }

  for (let index = 0; index < ecCount; index++) {
    let value = 0;
    const x = gfPower(index);
    for (let position = 0; position < received.length; position++) value = gfMultiply(value, x) ^ received[position];
    if (value !== 0) return -1;
  }
  return errors;
}

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

  while (reader.remaining >= 4) {
    const mode = reader.read(4);
    if (mode === MODE_TERMINATOR) break;

    if (mode === MODE_ECI) {
      const first = reader.read(8);
      if ((first & 0b1000_0000) === 0) {
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

function decodeBytes(raw: Uint8Array): string {
  if (typeof TextDecoder === "function") {
    try {
      return new TextDecoder("utf-8", { fatal: true }).decode(raw);
    } catch {
    }
  }
  let out = "";
  for (const byte of raw) out += String.fromCharCode(byte);
  return out;
}

function bitDistance(left: number, right: number): number {
  let difference = left ^ right;
  let count = 0;
  while (difference !== 0) {
    difference &= difference - 1;
    count++;
  }
  return count;
}

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

  const order = qrDataModuleOrder(version);
  const totalCodewords = TOTAL_CODEWORDS[version - 1];
  const totalBits = totalCodewords * 8 + REMAINDER_BITS[version - 1];
  const stream = new Uint8Array(totalCodewords);
  for (let bitIndex = 0; bitIndex < order.length && bitIndex < totalBits; bitIndex++) {
    const [row, column] = order[bitIndex];
    const dark = matrix[row][column] !== maskInverts(format.mask, row, column);
    if (dark) stream[bitIndex >>> 3] |= 0x80 >>> (bitIndex & 7);
  }

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

const BINARIZE_BLOCK = 8;
const MIN_DYNAMIC_RANGE = 24;

export function binarizeForQr(plane: GreyPlane): GreyPlane {
  const { width, height, data } = plane;
  const blocksX = Math.max(1, Math.ceil(width / BINARIZE_BLOCK));
  const blocksY = Math.max(1, Math.ceil(height / BINARIZE_BLOCK));
  const level = new Float64Array(blocksX * blocksY);

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
        threshold = min / 2;
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

type FinderCandidate = { x: number; y: number; moduleSize: number; count: number };

type DarkMap = { dark: (x: number, y: number) => boolean; width: number; height: number };

function isFinderRatio(counts: readonly number[]): boolean {
  let total = 0;
  for (const count of counts) {
    if (count === 0) return false;
    total += count;
  }
  if (total < 7) return false;
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

function centreFromEnd(counts: readonly number[], end: number): number {
  return end - counts[4] - counts[3] - counts[2] / 2;
}

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

  const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
  if (5 * Math.abs(total - originalTotal) >= 2 * originalTotal) return Number.NaN;

  return isFinderRatio(counts) ? centreFromEnd(counts, position) : Number.NaN;
}

function recordCandidate(candidates: FinderCandidate[], x: number, y: number, moduleSize: number): void {
  for (const candidate of candidates) {
    if (
      Math.abs(x - candidate.x) <= candidate.moduleSize &&
      Math.abs(y - candidate.y) <= candidate.moduleSize &&
      Math.abs(moduleSize - candidate.moduleSize) <= Math.max(1, candidate.moduleSize * 0.5)
    ) {
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
        if ((state & 1) === 1) state++;
        counts[state]++;
        continue;
      }
      if ((state & 1) === 0) {
        if (state === 4) {
          if (isFinderRatio(counts)) consider(x);
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

    if (state === 4 && isFinderRatio(counts)) consider(map.width);
  }

  return candidates;
}

function orderFinders(candidates: FinderCandidate[]): [FinderCandidate, FinderCandidate, FinderCandidate] | null {
  const confirmed = candidates.filter((candidate) => candidate.count >= 2);
  const pool = (confirmed.length >= 3 ? confirmed : candidates).slice().sort((left, right) => right.count - left.count);
  if (pool.length < 3) return null;
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
        const sizeSpread = Math.max(...sizes.map((size) => Math.abs(size - meanSize))) / meanSize;
        if (sizeSpread > 0.5) continue;

        const distances = [
          distance(trio[0], trio[1]),
          distance(trio[0], trio[2]),
          distance(trio[1], trio[2])
        ];
        const longest = Math.max(...distances);
        if (longest < meanSize * 7) continue;
        const shortSides = distances.filter((value) => value !== longest);
        if (shortSides.length !== 2) continue;
        const shortMean = (shortSides[0] + shortSides[1]) / 2;
        if (shortMean <= 0) continue;
        const squareness = Math.abs(shortSides[0] - shortSides[1]) / shortMean;
        const diagonal = Math.abs(longest - shortMean * Math.SQRT2) / (shortMean * Math.SQRT2);

        const score = sizeSpread + squareness + diagonal;
        if (score >= bestScore) continue;
        const longestIndex = distances.indexOf(longest);
        const topLeft = longestIndex === 0 ? trio[2] : longestIndex === 1 ? trio[1] : trio[0];
        const others = trio.filter((candidate) => candidate !== topLeft);
        bestScore = score;
        best = [topLeft, others[0], others[1]];
      }
    }
  }

  if (!best) return null;

  const [topLeft, first, second] = best;
  const cross =
    (first.x - topLeft.x) * (second.y - topLeft.y) - (first.y - topLeft.y) * (second.x - topLeft.x);
  return cross >= 0 ? [topLeft, first, second] : [topLeft, second, first];
}

function distance(left: { x: number; y: number }, right: { x: number; y: number }): number {
  return Math.hypot(left.x - right.x, left.y - right.y);
}

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
  let state = 0;
  const limit = bx + xStep;

  for (let x = ax, y = ay; x !== limit; x += xStep) {
    const realX = steep ? y : x;
    const realY = steep ? x : y;
    if (realX < 0 || realY < 0 || realX >= map.width || realY >= map.height) return Number.NaN;
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

function finderWidthAlong(map: DarkMap, from: FinderCandidate, to: FinderCandidate): number {
  const forward = darkLightDarkRun(map, Math.round(from.x), Math.round(from.y), Math.round(to.x), Math.round(to.y));
  const backward = darkLightDarkRun(
    map,
    Math.round(from.x),
    Math.round(from.y),
    Math.round(from.x - (to.x - from.x)),
    Math.round(from.y - (to.y - from.y))
  );
  return forward + backward - 1;
}

function moduleSizeAlong(map: DarkMap, from: FinderCandidate, to: FinderCandidate, fallback: number): number {
  const first = finderWidthAlong(map, from, to);
  const second = finderWidthAlong(map, to, from);
  if (Number.isNaN(first) && Number.isNaN(second)) return fallback;
  if (Number.isNaN(first)) return second / 7;
  if (Number.isNaN(second)) return first / 7;
  return (first + second) / 14;
}

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

type AlignmentCandidate = { x: number; y: number; count: number };

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

function alignmentRowHits(map: DarkMap, y: number, left: number, right: number, moduleSize: number): number[] {
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
  for (let index = 2; index + 2 < runs.length; index++) {
    const centre = runs[index];
    if (!centre.dark) continue;
    const before = runs[index - 1];
    const after = runs[index + 1];
    if (!oneModule(centre.length) || !oneModule(before.length) || !oneModule(after.length)) continue;
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

function alignmentCentreY(map: DarkMap, x: number, y: number, moduleSize: number): number {
  const near = (run: number) => Math.abs(run - moduleSize) <= moduleSize * 0.7;
  const ceiling = Math.ceil(moduleSize * 2.5);

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

  let lightAbove = 0;
  position = topOfCentre;
  while (position >= 0 && !map.dark(x, position) && lightAbove <= ceiling) {
    lightAbove++;
    position--;
  }
  if (!near(lightAbove) || position < 0 || !map.dark(x, position)) return Number.NaN;

  let lightBelow = 0;
  position = bottomOfCentre;
  while (position < map.height && !map.dark(x, position) && lightBelow <= ceiling) {
    lightBelow++;
    position++;
  }
  if (!near(lightBelow) || position >= map.height || !map.dark(x, position)) return Number.NaN;

  return y + 0.5 - up + (up + down) / 2;
}

function sampleGrid(plane: GreyPlane, transform: Homography, dimension: number): boolean[][] {
  const matrix: boolean[][] = [];
  for (let row = 0; row < dimension; row++) {
    const line: boolean[] = [];
    for (let column = 0; column < dimension; column++) {
      const point = applyHomography(transform, { x: column + 0.5, y: row + 0.5 });
      line.push(bilinearSample(plane, point.x, point.y, 255) < 128);
    }
    matrix.push(line);
  }
  return matrix;
}

function transpose(matrix: boolean[][]): boolean[][] {
  return matrix.map((row, rowIndex) => row.map((_, columnIndex) => matrix[columnIndex][rowIndex]));
}

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

  const mirrored = decodeQrMatrix(transpose(matrix));
  if (mirrored.ok) return mirrored;

  return { ...straight, region };
}

function regionOf(
  finders: [FinderCandidate, FinderCandidate, FinderCandidate],
  plane: GreyPlane
): QrRegion {
  const [topLeft, topRight, bottomLeft] = finders;
  const corners = [
    topLeft,
    topRight,
    bottomLeft,
    { x: topRight.x - topLeft.x + bottomLeft.x, y: topRight.y - topLeft.y + bottomLeft.y }
  ];
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
