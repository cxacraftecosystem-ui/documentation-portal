export const ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

export type QrEccLevel = "L" | "M" | "Q" | "H";

const ECC_FORMAT_BITS: Record<QrEccLevel, number> = { L: 0b01, M: 0b00, Q: 0b11, H: 0b10 };

export const MIN_VERSION = 1;
export const MAX_VERSION = 6;

export const TOTAL_CODEWORDS = [26, 44, 70, 100, 134, 172];

export const ALIGNMENT_CENTRES: number[][] = [[], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34]];

export const BLOCKS: Record<QrEccLevel, number[][]> = {
  L: [
    [7, 1, 19, 0, 0],
    [10, 1, 34, 0, 0],
    [15, 1, 55, 0, 0],
    [20, 1, 80, 0, 0],
    [26, 1, 108, 0, 0],
    [18, 2, 68, 0, 0]
  ],
  M: [
    [10, 1, 16, 0, 0],
    [16, 1, 28, 0, 0],
    [26, 1, 44, 0, 0],
    [18, 2, 32, 0, 0],
    [24, 2, 43, 0, 0],
    [16, 4, 27, 0, 0]
  ],
  Q: [
    [13, 1, 13, 0, 0],
    [22, 1, 22, 0, 0],
    [18, 2, 17, 0, 0],
    [26, 2, 24, 0, 0],
    [18, 2, 15, 2, 16],
    [24, 4, 19, 0, 0]
  ],
  H: [
    [17, 1, 9, 0, 0],
    [28, 1, 16, 0, 0],
    [22, 2, 13, 0, 0],
    [16, 4, 9, 0, 0],
    [22, 2, 11, 2, 12],
    [28, 4, 15, 0, 0]
  ]
};

export const REMAINDER_BITS = [0, 7, 7, 7, 7, 7];

export const ECC_LEVELS: readonly QrEccLevel[] = ["L", "M", "Q", "H"];

export const GF_EXP = new Uint8Array(512);
export const GF_LOG = new Uint8Array(256);

(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
})();

export function gfMultiply(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function generatorPolynomial(degree: number): Uint8Array {
  let poly = new Uint8Array([1]);
  for (let i = 0; i < degree; i++) {
    const next = new Uint8Array(poly.length + 1);
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMultiply(poly[j], GF_EXP[i]);
    }
    poly = next;
  }
  return poly;
}

function errorCorrectionFor(data: Uint8Array, ecCount: number): Uint8Array {
  const generator = generatorPolynomial(ecCount);
  const remainder = new Uint8Array(ecCount);
  for (const byte of data) {
    const factor = byte ^ remainder[0];
    remainder.copyWithin(0, 1);
    remainder[ecCount - 1] = 0;
    if (factor !== 0) {
      for (let i = 0; i < ecCount; i++) remainder[i] ^= gfMultiply(generator[i + 1], factor);
    }
  }
  return remainder;
}

class BitBuffer {
  private bits: number[] = [];

  put(value: number, length: number): void {
    for (let i = length - 1; i >= 0; i--) this.bits.push((value >>> i) & 1);
  }

  get length(): number {
    return this.bits.length;
  }

  toBytes(): Uint8Array {
    const out = new Uint8Array(Math.ceil(this.bits.length / 8));
    this.bits.forEach((bit, index) => {
      if (bit) out[index >>> 3] |= 0x80 >>> (index & 7);
    });
    return out;
  }
}

export function isQrAlphanumeric(text: string): boolean {
  for (const character of text) {
    if (!ALPHANUMERIC.includes(character)) return false;
  }
  return true;
}

export function qrCapacity(version: number, level: QrEccLevel): number {
  const [, g1Blocks, g1Data, g2Blocks, g2Data] = BLOCKS[level][version - 1];
  const dataBits = (g1Blocks * g1Data + g2Blocks * g2Data) * 8 - 4 - 9;
  const pairs = Math.floor(dataBits / 11);
  return pairs * 2 + (dataBits - pairs * 11 >= 6 ? 1 : 0);
}

export class QrEncodeError extends Error {
  readonly reason: "NOT_ALPHANUMERIC" | "TOO_LONG" | "EMPTY";

  constructor(reason: "NOT_ALPHANUMERIC" | "TOO_LONG" | "EMPTY", message: string) {
    super(message);
    this.name = "QrEncodeError";
    this.reason = reason;
  }
}

export type QrSymbol = {
  matrix: boolean[][];
  size: number;
  version: number;
  level: QrEccLevel;
  mask: number;
};

function newMatrix(size: number): boolean[][] {
  return Array.from({ length: size }, () => new Array<boolean>(size).fill(false));
}

function drawFinder(matrix: boolean[][], reserved: boolean[][], top: number, left: number): void {
  const size = matrix.length;
  for (let row = -1; row <= 7; row++) {
    for (let column = -1; column <= 7; column++) {
      const r = top + row;
      const c = left + column;
      if (r < 0 || r >= size || c < 0 || c >= size) continue;
      const ring = Math.max(Math.abs(row - 3), Math.abs(column - 3));
      matrix[r][c] = ring !== 2 && ring <= 3;
      reserved[r][c] = true;
    }
  }
}

function drawFunctionPatterns(matrix: boolean[][], reserved: boolean[][], version: number): void {
  const size = matrix.length;

  drawFinder(matrix, reserved, 0, 0);
  drawFinder(matrix, reserved, 0, size - 7);
  drawFinder(matrix, reserved, size - 7, 0);

  for (let i = 8; i < size - 8; i++) {
    const dark = i % 2 === 0;
    matrix[6][i] = dark;
    reserved[6][i] = true;
    matrix[i][6] = dark;
    reserved[i][6] = true;
  }

  const centres = ALIGNMENT_CENTRES[version - 1];
  for (const centreRow of centres) {
    for (const centreColumn of centres) {
      const onFinder =
        (centreRow === 6 && centreColumn === 6) ||
        (centreRow === 6 && centreColumn === size - 7) ||
        (centreRow === size - 7 && centreColumn === 6);
      if (onFinder) continue;
      for (let row = -2; row <= 2; row++) {
        for (let column = -2; column <= 2; column++) {
          matrix[centreRow + row][centreColumn + column] = Math.max(Math.abs(row), Math.abs(column)) !== 1;
          reserved[centreRow + row][centreColumn + column] = true;
        }
      }
    }
  }

  for (let i = 0; i <= 8; i++) {
    if (i !== 6) {
      reserved[8][i] = true;
      reserved[i][8] = true;
    }
  }
  for (let i = 0; i < 8; i++) {
    reserved[8][size - 1 - i] = true;
    reserved[size - 1 - i][8] = true;
  }
  matrix[size - 8][8] = true;
  reserved[size - 8][8] = true;
}

export function formatBits(level: QrEccLevel, mask: number): number {
  const data = (ECC_FORMAT_BITS[level] << 3) | mask;
  let remainder = data;
  for (let i = 0; i < 10; i++) remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
  return (((data << 10) | remainder) ^ 0x5412) & 0x7fff;
}

function drawFormat(matrix: boolean[][], level: QrEccLevel, mask: number): void {
  const size = matrix.length;
  const bits = formatBits(level, mask);
  const bit = (index: number) => ((bits >>> index) & 1) === 1;

  for (let i = 0; i <= 5; i++) matrix[i][8] = bit(i);
  matrix[7][8] = bit(6);
  matrix[8][8] = bit(7);
  matrix[8][7] = bit(8);
  for (let i = 9; i < 15; i++) matrix[8][14 - i] = bit(i);

  for (let i = 0; i < 8; i++) matrix[8][size - 1 - i] = bit(i);
  for (let i = 8; i < 15; i++) matrix[size - 15 + i][8] = bit(i);
}

export function maskInverts(mask: number, row: number, column: number): boolean {
  switch (mask) {
    case 0:
      return (row + column) % 2 === 0;
    case 1:
      return row % 2 === 0;
    case 2:
      return column % 3 === 0;
    case 3:
      return (row + column) % 3 === 0;
    case 4:
      return (Math.floor(row / 2) + Math.floor(column / 3)) % 2 === 0;
    case 5:
      return ((row * column) % 2) + ((row * column) % 3) === 0;
    case 6:
      return (((row * column) % 2) + ((row * column) % 3)) % 2 === 0;
    default:
      return (((row + column) % 2) + ((row * column) % 3)) % 2 === 0;
  }
}

function penalty(matrix: boolean[][]): number {
  const size = matrix.length;
  let score = 0;

  for (let outer = 0; outer < size; outer++) {
    for (const horizontal of [true, false]) {
      let runValue = horizontal ? matrix[outer][0] : matrix[0][outer];
      let runLength = 1;
      for (let inner = 1; inner < size; inner++) {
        const value = horizontal ? matrix[outer][inner] : matrix[inner][outer];
        if (value === runValue) {
          runLength++;
          continue;
        }
        if (runLength >= 5) score += runLength - 2;
        runValue = value;
        runLength = 1;
      }
      if (runLength >= 5) score += runLength - 2;
    }
  }

  for (let row = 0; row < size - 1; row++) {
    for (let column = 0; column < size - 1; column++) {
      const value = matrix[row][column];
      if (value === matrix[row][column + 1] && value === matrix[row + 1][column] && value === matrix[row + 1][column + 1]) {
        score += 3;
      }
    }
  }

  const forward = [true, false, true, true, true, false, true, false, false, false, false];
  const backward = [...forward].reverse();
  for (let outer = 0; outer < size; outer++) {
    for (const horizontal of [true, false]) {
      for (let start = 0; start + 11 <= size; start++) {
        let matchesForward = true;
        let matchesBackward = true;
        for (let offset = 0; offset < 11; offset++) {
          const value = horizontal ? matrix[outer][start + offset] : matrix[start + offset][outer];
          if (value !== forward[offset]) matchesForward = false;
          if (value !== backward[offset]) matchesBackward = false;
        }
        if (matchesForward) score += 40;
        if (matchesBackward) score += 40;
      }
    }
  }

  let dark = 0;
  for (const row of matrix) for (const value of row) if (value) dark++;
  const percent = (dark * 100) / (size * size);
  score += Math.floor(Math.abs(percent - 50) / 5) * 10;

  return score;
}

function buildCodewords(text: string, version: number, level: QrEccLevel): Uint8Array {
  const [ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data] = BLOCKS[level][version - 1];
  const totalData = g1Blocks * g1Data + g2Blocks * g2Data;

  const buffer = new BitBuffer();
  buffer.put(0b0010, 4);
  buffer.put(text.length, 9);
  for (let i = 0; i + 1 < text.length; i += 2) {
    buffer.put(ALPHANUMERIC.indexOf(text[i]) * 45 + ALPHANUMERIC.indexOf(text[i + 1]), 11);
  }
  if (text.length % 2 === 1) buffer.put(ALPHANUMERIC.indexOf(text[text.length - 1]), 6);

  buffer.put(0, Math.min(4, totalData * 8 - buffer.length));
  if (buffer.length % 8 !== 0) buffer.put(0, 8 - (buffer.length % 8));
  const bytes = buffer.toBytes();
  const data = new Uint8Array(totalData);
  data.set(bytes.subarray(0, Math.min(bytes.length, totalData)));
  for (let i = bytes.length; i < totalData; i++) data[i] = (i - bytes.length) % 2 === 0 ? 0xec : 0x11;

  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  let offset = 0;
  for (let block = 0; block < g1Blocks + g2Blocks; block++) {
    const length = block < g1Blocks ? g1Data : g2Data;
    const slice = data.subarray(offset, offset + length);
    offset += length;
    dataBlocks.push(slice);
    ecBlocks.push(errorCorrectionFor(slice, ecPerBlock));
  }

  const out = new Uint8Array(TOTAL_CODEWORDS[version - 1]);
  let written = 0;
  const longestData = Math.max(g1Data, g2Data);
  for (let index = 0; index < longestData; index++) {
    for (const block of dataBlocks) if (index < block.length) out[written++] = block[index];
  }
  for (let index = 0; index < ecPerBlock; index++) {
    for (const block of ecBlocks) out[written++] = block[index];
  }
  return out;
}

export function qrFunctionModules(version: number): boolean[][] {
  const size = 17 + 4 * version;
  const matrix = newMatrix(size);
  const reserved = newMatrix(size);
  drawFunctionPatterns(matrix, reserved, version);
  return reserved;
}

export function qrDataModuleOrder(version: number): Array<[number, number]> {
  const size = 17 + 4 * version;
  const reserved = qrFunctionModules(version);
  const order: Array<[number, number]> = [];

  for (let right = size - 1; right >= 1; right -= 2) {
    const rightColumn = right <= 6 ? right - 1 : right;
    for (let vertical = 0; vertical < size; vertical++) {
      for (let offset = 0; offset < 2; offset++) {
        const column = rightColumn - offset;
        const upward = ((rightColumn + 1) & 2) === 0;
        const row = upward ? size - 1 - vertical : vertical;
        if (reserved[row][column]) continue;
        order.push([row, column]);
      }
    }
  }
  return order;
}

function drawCodewords(matrix: boolean[][], reserved: boolean[][], codewords: Uint8Array, version: number): void {
  const totalBits = codewords.length * 8 + REMAINDER_BITS[version - 1];
  void reserved;
  const order = qrDataModuleOrder(version);

  for (let bitIndex = 0; bitIndex < order.length; bitIndex++) {
    if (bitIndex >= totalBits) break;
    const [row, column] = order[bitIndex];
    const byte = bitIndex >>> 3;
    matrix[row][column] = byte < codewords.length && ((codewords[byte] >>> (7 - (bitIndex & 7))) & 1) === 1;
  }
}

export function buildQrMatrix(text: string, version: number, level: QrEccLevel, mask: number): boolean[][] {
  const size = 17 + 4 * version;
  const matrix = newMatrix(size);
  const reserved = newMatrix(size);
  drawFunctionPatterns(matrix, reserved, version);
  drawCodewords(matrix, reserved, buildCodewords(text, version, level), version);
  for (let row = 0; row < size; row++) {
    for (let column = 0; column < size; column++) {
      if (!reserved[row][column] && maskInverts(mask, row, column)) matrix[row][column] = !matrix[row][column];
    }
  }
  drawFormat(matrix, level, mask);
  return matrix;
}

export function encodeQr(text: string, level: QrEccLevel = "Q"): QrSymbol {
  if (!text) throw new QrEncodeError("EMPTY", "There is nothing to encode.");
  if (!isQrAlphanumeric(text)) {
    throw new QrEncodeError(
      "NOT_ALPHANUMERIC",
      "This code contains characters a compact QR symbol cannot carry. Codes are upper-case letters, digits and the punctuation the standard allows."
    );
  }

  let version = 0;
  for (let candidate = MIN_VERSION; candidate <= MAX_VERSION; candidate++) {
    if (text.length <= qrCapacity(candidate, level)) {
      version = candidate;
      break;
    }
  }
  if (!version) {
    throw new QrEncodeError(
      "TOO_LONG",
      `This code is ${text.length} characters, which is too long for a printed QR code. The most it can hold is ${qrCapacity(MAX_VERSION, level)}.`
    );
  }

  let best: boolean[][] | null = null;
  let bestMask = 0;
  let bestScore = Number.POSITIVE_INFINITY;
  for (let mask = 0; mask < 8; mask++) {
    const candidate = buildQrMatrix(text, version, level, mask);
    const score = penalty(candidate);
    if (score < bestScore) {
      bestScore = score;
      best = candidate;
      bestMask = mask;
    }
  }

  return { matrix: best as boolean[][], size: 17 + 4 * version, version, level, mask: bestMask };
}

export type QrSvg = {
  path: string;
  extent: number;
  symbol: QrSymbol;
};

export function qrSvgPath(symbol: QrSymbol, quiet = 4): QrSvg {
  const parts: string[] = [];
  for (let row = 0; row < symbol.size; row++) {
    let column = 0;
    while (column < symbol.size) {
      if (!symbol.matrix[row][column]) {
        column++;
        continue;
      }
      let run = 1;
      while (column + run < symbol.size && symbol.matrix[row][column + run]) run++;
      parts.push(`M${column + quiet} ${row + quiet}h${run}v1h-${run}z`);
      column += run;
    }
  }
  return { path: parts.join(""), extent: symbol.size + quiet * 2, symbol };
}
