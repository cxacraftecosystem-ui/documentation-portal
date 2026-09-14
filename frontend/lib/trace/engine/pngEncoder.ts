import { GrayF, Px, RgbaImage } from './buffers';

/**
 * A complete PNG encoder, including its own DEFLATE compressor.
 *
 * The Kotlin engine borrows `java.util.zip`; there is no equivalent a Worker can reach — `CompressionStream`
 * is not available in every target and is asynchronous, and pulling in pako would put a dependency in a
 * module that is required to have none. So DEFLATE is here: fixed-Huffman blocks over a hash-chain LZ77
 * matcher, which is a few hundred lines and compresses line art to within a few percent of zlib's
 * default level.
 *
 * Two details are the classic ways to get PNG wrong and both are handled here:
 *  - **every multi-byte integer in the format is big-endian**, including chunk lengths, the IHDR
 *    dimensions and the trailing CRC — a little-endian slip produces a file that some decoders open
 *    and others reject, which is far harder to diagnose than a file nothing opens;
 *  - the per-scanline filter is *chosen*, not fixed. Filter 0 alone is legal and simple, but on line
 *    art — long runs of identical pixels with occasional 1px transitions — Paeth and Up roughly halve
 *    the compressed size for a few adds per byte.
 *
 * Note that DEFLATE output is **not** byte-comparable between the two engines and is not expected to
 * be: `zlib` and this compressor make different (both legal) choices. What the parity fixtures compare
 * is the decoded image, which is exact.
 */

const COLOUR_TYPE_GRAY = 0;
const COLOUR_TYPE_RGBA = 6;

const FILTER_NONE = 0;
const FILTER_SUB = 1;
const FILTER_UP = 2;
const FILTER_AVERAGE = 3;
const FILTER_PAETH = 4;

/**
 * The fixed 8-byte signature. The 0x89 high bit catches 7-bit-clean transfers and the CR-LF / LF pair
 * catches a transfer that "helpfully" translated line endings.
 */
const SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

/**
 * Encodes `src` as an 8-bit RGBA PNG (colour type 6), preserving alpha exactly.
 *
 * @param dpi when positive, writes a `pHYs` chunk so print software picks up the physical size; 0
 *            omits the chunk entirely rather than asserting a resolution nobody supplied.
 * @returns the complete PNG file bytes.
 */
export function encode(src: RgbaImage, dpi = 0): Uint8Array {
  const w = src.width;
  const h = src.height;
  const stride = w * 4;
  const raw = new Uint8Array((stride + 1) * h);
  const cur = new Uint8Array(stride);
  const filter = new RowFilter(stride, 4);
  const px = src.pixels;
  let o = 0;
  for (let y = 0; y < h; y++) {
    let i = y * w;
    let c = 0;
    for (let x = 0; x < w; x++, i++, c += 4) {
      const argb = px[i];
      cur[c] = (argb >>> 16) & 0xff;
      cur[c + 1] = (argb >>> 8) & 0xff;
      cur[c + 2] = argb & 0xff;
      cur[c + 3] = (argb >>> 24) & 0xff;
    }
    o = filter.emit(cur, raw, o);
  }
  return assemble(raw, w, h, COLOUR_TYPE_RGBA, dpi);
}

/**
 * Encodes `src` as an 8-bit greyscale PNG (colour type 0). Values are clamped to 0..1 and rounded
 * through `Px.toByte255`, so an un-normalised intermediate buffer exports as a clipped image rather
 * than as wrapped-around noise.
 *
 * @param dpi when positive, writes a `pHYs` chunk; 0 omits it.
 * @returns the complete PNG file bytes.
 */
export function encodeGray(src: GrayF, dpi = 0): Uint8Array {
  const w = src.width;
  const h = src.height;
  const raw = new Uint8Array((w + 1) * h);
  const cur = new Uint8Array(w);
  const filter = new RowFilter(w, 1);
  const d = src.data;
  let o = 0;
  for (let y = 0; y < h; y++) {
    let i = y * w;
    for (let x = 0; x < w; x++, i++) cur[x] = Px.toByte255(d[i]);
    o = filter.emit(cur, raw, o);
  }
  return assemble(raw, w, h, COLOUR_TYPE_GRAY, dpi);
}

function assemble(raw: Uint8Array, w: number, h: number, colourType: number, dpi: number): Uint8Array {
  const idat = zlibDeflate(raw);
  const chunks: Uint8Array[] = [];
  chunks.push(new Uint8Array(SIGNATURE));

  const ihdr = new Uint8Array(13);
  putBe32(ihdr, 0, w);
  putBe32(ihdr, 4, h);
  ihdr[8] = 8; // bit depth
  ihdr[9] = colourType;
  ihdr[10] = 0; // compression method: DEFLATE, the only legal value
  ihdr[11] = 0; // filter method: adaptive, the only legal value
  ihdr[12] = 0; // interlace: none
  chunks.push(chunk('IHDR', ihdr));

  if (dpi > 0) {
    // pHYs is in pixels per metre, so the conversion is dpi / 0.0254 and not dpi * anything.
    const ppm = Math.round(dpi / 0.0254);
    const phys = new Uint8Array(9);
    putBe32(phys, 0, ppm);
    putBe32(phys, 4, ppm);
    phys[8] = 1; // unit specifier: metre
    chunks.push(chunk('pHYs', phys));
  }

  chunks.push(chunk('IDAT', idat));
  chunks.push(chunk('IEND', new Uint8Array(0)));

  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}

function chunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  putBe32(out, 0, data.length);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i) & 0xff;
  out.set(data, 8);
  // The CRC covers the type *and* the data but never the length field — a decoder that includes the
  // length rejects every well-formed PNG, and vice versa.
  putBe32(out, 8 + data.length, crc32(out.subarray(4, 8 + data.length)));
  return out;
}

function putBe32(b: Uint8Array, off: number, v: number): void {
  b[off] = (v >>> 24) & 0xff;
  b[off + 1] = (v >>> 16) & 0xff;
  b[off + 2] = (v >>> 8) & 0xff;
  b[off + 3] = v & 0xff;
}

/**
 * Per-scanline filter selection. Holds the previous raw row and one scratch buffer per candidate
 * filter, so the encoder allocates once per image rather than once per row.
 */
class RowFilter {
  private readonly prev: Uint8Array;
  private readonly sub: Uint8Array;
  private readonly up: Uint8Array;
  private readonly avg: Uint8Array;
  private readonly pae: Uint8Array;

  constructor(
    private readonly stride: number,
    private readonly bpp: number,
  ) {
    this.prev = new Uint8Array(stride);
    this.sub = new Uint8Array(stride);
    this.up = new Uint8Array(stride);
    this.avg = new Uint8Array(stride);
    this.pae = new Uint8Array(stride);
  }

  /** Writes the filter byte and the filtered row into `dst` at `at`; returns the new offset. */
  emit(cur: Uint8Array, dst: Uint8Array, at: number): number {
    const n = this.stride;
    const b = this.bpp;
    const prev = this.prev;
    const sub = this.sub;
    const up = this.up;
    const avg = this.avg;
    const pae = this.pae;

    for (let i = 0; i < n; i++) sub[i] = i >= b ? (cur[i] - cur[i - b]) & 0xff : cur[i];
    for (let i = 0; i < n; i++) up[i] = (cur[i] - prev[i]) & 0xff;
    for (let i = 0; i < n; i++) {
      const left = i >= b ? cur[i - b] : 0;
      // (left + above) >> 1 is the floor of the average; both operands are non-negative so the shift
      // and an integer divide agree.
      avg[i] = (cur[i] - ((left + prev[i]) >> 1)) & 0xff;
    }
    for (let i = 0; i < n; i++) {
      const a = i >= b ? cur[i - b] : 0;
      const bb = prev[i];
      const c = i >= b ? prev[i - b] : 0;
      const p = a + bb - c;
      const pa = p > a ? p - a : a - p;
      const pb = p > bb ? p - bb : bb - p;
      const pc = p > c ? p - c : c - p;
      const pred = pa <= pb && pa <= pc ? a : pb <= pc ? bb : c;
      pae[i] = (cur[i] - pred) & 0xff;
    }

    // Minimum sum of absolute *signed* byte values: the heuristic the PNG spec itself recommends. It
    // is not optimal, but it is one pass and it picks Up on flat artwork and Paeth on gradients,
    // which is where the whole saving comes from.
    let bestType = FILTER_NONE;
    let best = score(cur, n);
    let s = score(sub, n);
    if (s < best) {
      best = s;
      bestType = FILTER_SUB;
    }
    s = score(up, n);
    if (s < best) {
      best = s;
      bestType = FILTER_UP;
    }
    s = score(avg, n);
    if (s < best) {
      best = s;
      bestType = FILTER_AVERAGE;
    }
    s = score(pae, n);
    if (s < best) {
      best = s;
      bestType = FILTER_PAETH;
    }

    dst[at++] = bestType;
    const chosen =
      bestType === FILTER_NONE
        ? cur
        : bestType === FILTER_SUB
          ? sub
          : bestType === FILTER_UP
            ? up
            : bestType === FILTER_AVERAGE
              ? avg
              : pae;
    dst.set(chosen.subarray(0, n), at);
    prev.set(cur.subarray(0, n));
    return at + n;
  }
}

function score(a: Uint8Array, n: number): number {
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const v = a[i];
    sum += v < 128 ? v : 256 - v;
  }
  return sum;
}

// ---------------------------------------------------------------------------------------
// CRC-32 and Adler-32
// ---------------------------------------------------------------------------------------

let crcTable: Int32Array | null = null;

function crc32Table(): Int32Array {
  let t = crcTable;
  if (t === null) {
    t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c;
    }
    crcTable = t;
  }
  return t;
}

/** Standard PNG CRC-32 of `data`. Exported because the round-trip test verifies chunk integrity. */
export function crc32(data: Uint8Array): number {
  const t = crc32Table();
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = t[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** Adler-32 as zlib specifies it — the trailing checksum of the compressed stream. */
export function adler32(data: Uint8Array): number {
  let a = 1;
  let b = 0;
  // 5552 is the largest block that cannot overflow a 32-bit accumulator, so the modulo runs per block
  // rather than per byte.
  let i = 0;
  while (i < data.length) {
    const end = Math.min(i + 5552, data.length);
    for (; i < end; i++) {
      a += data[i];
      b += a;
    }
    a %= 65521;
    b %= 65521;
  }
  return ((b << 16) | a) >>> 0;
}

// ---------------------------------------------------------------------------------------
// DEFLATE
// ---------------------------------------------------------------------------------------

const WINDOW = 32768;
const MIN_MATCH = 3;
const MAX_MATCH = 258;
/** Hash chain depth. 32 is well past the point where line art gains anything measurable. */
const MAX_CHAIN = 32;

const LENGTH_BASE = [
  3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131,
  163, 195, 227, 258,
];
const LENGTH_EXTRA = [
  0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
];
const DIST_BASE = [
  1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049,
  3073, 4097, 6145, 8193, 12289, 16385, 24577,
];
const DIST_EXTRA = [
  0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
];

/** LSB-first bit sink, which is the order DEFLATE packs both extra bits and block headers in. */
class BitWriter {
  private buf: Uint8Array = new Uint8Array(1024);
  private len = 0;
  private acc = 0;
  private nbits = 0;

  bits(value: number, count: number): void {
    this.acc |= (value & ((1 << count) - 1)) << this.nbits;
    this.nbits += count;
    while (this.nbits >= 8) {
      this.byte(this.acc & 0xff);
      this.acc >>>= 8;
      this.nbits -= 8;
    }
  }

  /**
   * Huffman codes travel **most significant bit first**, unlike everything else in the format. The
   * reversal is the single most common source of a stream that inflates to garbage.
   */
  code(value: number, count: number): void {
    let rev = 0;
    for (let i = 0; i < count; i++) rev |= ((value >>> (count - 1 - i)) & 1) << i;
    this.bits(rev, count);
  }

  align(): void {
    if (this.nbits > 0) {
      this.byte(this.acc & 0xff);
      this.acc = 0;
      this.nbits = 0;
    }
  }

  finish(): Uint8Array {
    this.align();
    return this.buf.subarray(0, this.len);
  }

  private byte(v: number): void {
    if (this.len === this.buf.length) {
      const grown = new Uint8Array(this.buf.length * 2);
      grown.set(this.buf);
      this.buf = grown;
    }
    this.buf[this.len++] = v;
  }
}

/** Fixed-Huffman literal/length code and its bit count, per RFC 1951 §3.2.6. */
function litCode(sym: number): { code: number; len: number } {
  if (sym < 144) return { code: 0x30 + sym, len: 8 };
  if (sym < 256) return { code: 0x190 + (sym - 144), len: 9 };
  if (sym < 280) return { code: sym - 256, len: 7 };
  return { code: 0xc0 + (sym - 280), len: 8 };
}

function writeLiteral(bw: BitWriter, sym: number): void {
  const c = litCode(sym);
  bw.code(c.code, c.len);
}

function lengthIndex(len: number): number {
  // 28 codes plus the exact-258 special case; a linear scan over 29 entries is not worth a table.
  for (let i = LENGTH_BASE.length - 1; i >= 0; i--) if (len >= LENGTH_BASE[i]) return i;
  return 0;
}

function distIndex(dist: number): number {
  for (let i = DIST_BASE.length - 1; i >= 0; i--) if (dist >= DIST_BASE[i]) return i;
  return 0;
}

/**
 * zlib stream (RFC 1950) wrapping a single final fixed-Huffman DEFLATE block.
 *
 * @returns the compressed bytes including the 2-byte zlib header and the 4-byte big-endian Adler-32.
 */
export function zlibDeflate(data: Uint8Array): Uint8Array {
  const bw = new BitWriter();
  bw.bits(1, 1); // BFINAL
  bw.bits(1, 2); // BTYPE = 01, fixed Huffman

  const n = data.length;
  // Hash chains over 3-byte prefixes. `head` is the most recent position per hash, `prevPos` links
  // backwards; both are position-indexed so no allocation happens inside the match loop.
  const HASH_BITS = 15;
  const HASH_SIZE = 1 << HASH_BITS;
  const head = new Int32Array(HASH_SIZE).fill(-1);
  const prevPos = new Int32Array(n > 0 ? n : 1).fill(-1);

  let pos = 0;
  while (pos < n) {
    let bestLen = 0;
    let bestDist = 0;
    if (pos + MIN_MATCH <= n) {
      const h = hash3(data, pos, HASH_BITS);
      let cand = head[h];
      let chain = 0;
      const limit = pos - WINDOW;
      while (cand >= 0 && cand > limit && chain < MAX_CHAIN) {
        chain++;
        // Cheap reject before the full compare: if the byte one past the current best does not
        // match, this candidate cannot beat it.
        if (data[cand + bestLen] === data[pos + bestLen]) {
          let l = 0;
          const max = Math.min(MAX_MATCH, n - pos);
          while (l < max && data[cand + l] === data[pos + l]) l++;
          if (l > bestLen) {
            bestLen = l;
            bestDist = pos - cand;
            if (l >= MAX_MATCH) break;
          }
        }
        cand = prevPos[cand];
      }
      prevPos[pos] = head[h];
      head[h] = pos;
    }

    if (bestLen >= MIN_MATCH) {
      const li = lengthIndex(bestLen);
      const lc = litCode(257 + li);
      bw.code(lc.code, lc.len);
      if (LENGTH_EXTRA[li] > 0) bw.bits(bestLen - LENGTH_BASE[li], LENGTH_EXTRA[li]);
      const di = distIndex(bestDist);
      bw.code(di, 5); // distance codes 0..29 are a flat 5-bit code in the fixed tree
      if (DIST_EXTRA[di] > 0) bw.bits(bestDist - DIST_BASE[di], DIST_EXTRA[di]);
      // Insert the interior positions of the match so a later match can start inside it.
      for (let k = 1; k < bestLen; k++) {
        const p = pos + k;
        if (p + MIN_MATCH > n) break;
        const hh = hash3(data, p, HASH_BITS);
        prevPos[p] = head[hh];
        head[hh] = p;
      }
      pos += bestLen;
    } else {
      writeLiteral(bw, data[pos]);
      pos++;
    }
  }

  writeLiteral(bw, 256); // end of block
  const body = bw.finish();

  const out = new Uint8Array(body.length + 6);
  // CMF/FLG: deflate, 32 KiB window, and an FCHECK that makes the pair a multiple of 31.
  out[0] = 0x78;
  out[1] = 0x01;
  out.set(body, 2);
  const ad = adler32(data);
  putBe32(out, 2 + body.length, ad);
  return out;
}

function hash3(data: Uint8Array, pos: number, bits: number): number {
  return ((data[pos] << 10) ^ (data[pos + 1] << 5) ^ data[pos + 2]) & ((1 << bits) - 1);
}
