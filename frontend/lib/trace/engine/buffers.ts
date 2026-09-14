/**
 * The four buffer types every stage of the engine speaks — the TypeScript mirror of
 * `android/core-imaging/.../Buffers.kt`.
 *
 * Deliberate choices, because getting these wrong is expensive later:
 *
 *  - {@link GrayF} is the working type for anything analytic (filters, gradients, DoG, tensors). It
 *    is **not** clamped to 0..1: a gradient magnitude, a Laplacian or a DoG response is legitimately
 *    signed and legitimately larger than 1, and clamping intermediate stages is how detail dies.
 *  - {@link Mask} is a `Uint8Array` holding 0/1 rather than `boolean[]`. JS has no packed boolean
 *    array, and a `boolean[]` in V8 is a pointer array: every read is a dereference and every write
 *    can trigger a transition. `Uint8Array` keeps thinning and connected components in cache.
 *  - {@link RgbaImage} packs ARGB into a `Uint32Array` in exactly the layout Android's
 *    `Bitmap.getPixels` produces, so the two engines share fixtures byte for byte. `ImageData` uses
 *    a different byte order and the conversion lives in exactly two functions
 *    ({@link RgbaImage.toImageData} / {@link RgbaImage.fromImageData}); anywhere else it is a bug.
 *  - Every buffer is row-major, index = `y * width + x`, with no stride/padding. Anything that needs
 *    a sub-rectangle copies; there are no views. Views were tried and every off-by-one bug in the
 *    first pipeline traced back to one.
 */

/** A structurally-typed stand-in for the DOM `ImageData`, so `src/engine` never touches the DOM. */
export interface ImageDataLike {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
}

/** Smallest and largest value present in a buffer. Kotlin returns `Pair<Float, Float>`. */
export interface Range {
  readonly lo: number;
  readonly hi: number;
}

/** Row-major single-channel float image. Values are conventionally 0..1 but are never clamped. */
export class GrayF {
  readonly width: number;
  readonly height: number;
  readonly data: Float32Array;

  /**
   * @param width  must be >= 1
   * @param height must be >= 1
   * @param data   optional backing store; must be exactly `width * height` long
   */
  constructor(width: number, height: number, data?: Float32Array) {
    if (!(width > 0) || !(height > 0)) {
      throw new Error(`GrayF must be non-empty, got ${width}x${height}`);
    }
    if (data !== undefined && data.length !== width * height) {
      throw new Error(
        `GrayF data size ${data.length} does not match ${width}x${height} = ${width * height}`,
      );
    }
    this.width = width | 0;
    this.height = height | 0;
    this.data = data ?? new Float32Array(width * height);
  }

  get size(): number {
    return this.data.length;
  }

  /** Unchecked read. Callers in hot loops index {@link data} directly instead. */
  get(x: number, y: number): number {
    return this.data[y * this.width + x];
  }

  set(x: number, y: number, v: number): void {
    this.data[y * this.width + x] = v;
  }

  /** Read with coordinates clamped to the edge — the border policy every analytic filter uses. */
  clamped(x: number, y: number): number {
    const cx = x < 0 ? 0 : x >= this.width ? this.width - 1 : x;
    const cy = y < 0 ? 0 : y >= this.height ? this.height - 1 : y;
    return this.data[cy * this.width + cx];
  }

  /** Bilinear sample in pixel coordinates, edge-clamped. */
  sampleBilinear(fx: number, fy: number): number {
    const x0 = Math.floor(fx);
    const y0 = Math.floor(fy);
    const tx = fx - x0;
    const ty = fy - y0;
    const v00 = this.clamped(x0, y0);
    const v10 = this.clamped(x0 + 1, y0);
    const v01 = this.clamped(x0, y0 + 1);
    const v11 = this.clamped(x0 + 1, y0 + 1);
    const a = v00 + (v10 - v00) * tx;
    const b = v01 + (v11 - v01) * tx;
    return a + (b - a) * ty;
  }

  copy(): GrayF {
    return new GrayF(this.width, this.height, this.data.slice());
  }

  fill(v: number): GrayF {
    this.data.fill(v);
    return this;
  }

  /** Same dimensions, zeroed. Used everywhere a stage needs a destination buffer. */
  blank(): GrayF {
    return new GrayF(this.width, this.height);
  }

  sameSizeAs(other: GrayF): boolean {
    return this.width === other.width && this.height === other.height;
  }

  /** Smallest and largest value present. Returns `{lo: 0, hi: 0}` only if that is the truth. */
  range(): Range {
    const d = this.data;
    let lo = Number.POSITIVE_INFINITY;
    let hi = Number.NEGATIVE_INFINITY;
    for (let i = 0; i < d.length; i++) {
      const v = d[i];
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
    if (lo > hi) return { lo: 0, hi: 0 };
    return { lo, hi };
  }

  /** Allocate matching `other`'s dimensions. */
  static like(other: GrayF): GrayF {
    return new GrayF(other.width, other.height);
  }
}

/** Row-major binary image. `1` means "ink" / foreground everywhere in this engine. */
export class Mask {
  readonly width: number;
  readonly height: number;
  readonly data: Uint8Array;

  constructor(width: number, height: number, data?: Uint8Array) {
    if (!(width > 0) || !(height > 0)) {
      throw new Error(`Mask must be non-empty, got ${width}x${height}`);
    }
    if (data !== undefined && data.length !== width * height) {
      throw new Error(
        `Mask data size ${data.length} does not match ${width}x${height} = ${width * height}`,
      );
    }
    this.width = width | 0;
    this.height = height | 0;
    this.data = data ?? new Uint8Array(width * height);
  }

  get size(): number {
    return this.data.length;
  }

  get(x: number, y: number): boolean {
    return this.data[y * this.width + x] !== 0;
  }

  set(x: number, y: number, v: boolean): void {
    this.data[y * this.width + x] = v ? 1 : 0;
  }

  /**
   * Out-of-bounds reads as background. This is the *opposite* of {@link GrayF.clamped} and it is
   * deliberate: replicating the edge would make a shape touching the border look closed to the
   * contour tracer, and thinning would grow a skeleton along the frame of every photograph.
   */
  safe(x: number, y: number): boolean {
    if (x < 0 || y < 0 || x >= this.width || y >= this.height) return false;
    return this.data[y * this.width + x] !== 0;
  }

  copy(): Mask {
    return new Mask(this.width, this.height, this.data.slice());
  }

  blank(): Mask {
    return new Mask(this.width, this.height);
  }

  countTrue(): number {
    const d = this.data;
    let n = 0;
    for (let i = 0; i < d.length; i++) if (d[i] !== 0) n++;
    return n;
  }

  fill(v: boolean): Mask {
    this.data.fill(v ? 1 : 0);
    return this;
  }

  invert(): Mask {
    const out = new Uint8Array(this.data.length);
    for (let i = 0; i < out.length; i++) out[i] = this.data[i] !== 0 ? 0 : 1;
    return new Mask(this.width, this.height, out);
  }

  and(other: Mask): Mask {
    this.requireSameSize(other, 'and');
    const out = new Uint8Array(this.data.length);
    for (let i = 0; i < out.length; i++) out[i] = this.data[i] !== 0 && other.data[i] !== 0 ? 1 : 0;
    return new Mask(this.width, this.height, out);
  }

  or(other: Mask): Mask {
    this.requireSameSize(other, 'or');
    const out = new Uint8Array(this.data.length);
    for (let i = 0; i < out.length; i++) out[i] = this.data[i] !== 0 || other.data[i] !== 0 ? 1 : 0;
    return new Mask(this.width, this.height, out);
  }

  /** `this AND NOT other` — used to subtract a matte or a protected region. */
  subtract(other: Mask): Mask {
    this.requireSameSize(other, 'subtract');
    const out = new Uint8Array(this.data.length);
    for (let i = 0; i < out.length; i++) out[i] = this.data[i] !== 0 && other.data[i] === 0 ? 1 : 0;
    return new Mask(this.width, this.height, out);
  }

  toGray(): GrayF {
    const out = new Float32Array(this.data.length);
    for (let i = 0; i < out.length; i++) out[i] = this.data[i] !== 0 ? 1 : 0;
    return new GrayF(this.width, this.height, out);
  }

  private requireSameSize(other: Mask, op: string): void {
    if (this.width !== other.width || this.height !== other.height) {
      throw new Error(`${op}(): size mismatch`);
    }
  }

  static like(other: Mask | GrayF): Mask {
    return new Mask(other.width, other.height);
  }
}

/**
 * Packed ARGB image, layout-identical to Android's `Bitmap.getPixels(...)`
 * (`Bitmap.Config.ARGB_8888`), so a fixture written by one engine is readable by the other with no
 * per-pixel conversion.
 *
 * Values read back out of a `Uint32Array` are always **unsigned** (0..4294967295), so
 * `0xFF000000` is opaque black here where the Kotlin side spells it `0xFF000000.toInt()`.
 */
export class RgbaImage {
  readonly width: number;
  readonly height: number;
  readonly pixels: Uint32Array;

  constructor(width: number, height: number, pixels?: Uint32Array) {
    if (!(width > 0) || !(height > 0)) {
      throw new Error(`RgbaImage must be non-empty, got ${width}x${height}`);
    }
    if (pixels !== undefined && pixels.length !== width * height) {
      throw new Error(
        `RgbaImage data size ${pixels.length} does not match ${width}x${height} = ${width * height}`,
      );
    }
    this.width = width | 0;
    this.height = height | 0;
    this.pixels = pixels ?? new Uint32Array(width * height);
  }

  get size(): number {
    return this.pixels.length;
  }

  get(x: number, y: number): number {
    return this.pixels[y * this.width + x];
  }

  set(x: number, y: number, argb: number): void {
    this.pixels[y * this.width + x] = argb;
  }

  copy(): RgbaImage {
    return new RgbaImage(this.width, this.height, this.pixels.slice());
  }

  fill(argb: number): RgbaImage {
    this.pixels.fill(argb >>> 0);
    return this;
  }

  /**
   * The **only** place packed ARGB becomes RGBA bytes. A `Uint32Array` is little-endian on every
   * platform we target, so its bytes read B,G,R,A while `ImageData` wants R,G,B,A — the shuffle is
   * unavoidable and it is centralised here so it cannot be applied twice.
   *
   * @returns a plain object; deliberately not a DOM `ImageData`, because the engine runs in a worker
   *          and must not depend on DOM globals.
   */
  toImageData(): ImageDataLike {
    const out = new Uint8ClampedArray(this.width * this.height * 4);
    const px = this.pixels;
    for (let i = 0, j = 0; i < px.length; i++, j += 4) {
      const v = px[i];
      out[j] = (v >>> 16) & 0xff;
      out[j + 1] = (v >>> 8) & 0xff;
      out[j + 2] = v & 0xff;
      out[j + 3] = (v >>> 24) & 0xff;
    }
    return { data: out, width: this.width, height: this.height };
  }

  /** The inverse of {@link toImageData}; the other half of the one permitted byte-order shuffle. */
  static fromImageData(img: { data: ArrayLike<number>; width: number; height: number }): RgbaImage {
    const n = img.width * img.height;
    if (img.data.length < n * 4) {
      throw new Error(`fromImageData(): expected ${n * 4} bytes, got ${img.data.length}`);
    }
    const px = new Uint32Array(n);
    const d = img.data;
    for (let i = 0, j = 0; i < n; i++, j += 4) {
      px[i] = ((d[j + 3] << 24) | (d[j] << 16) | (d[j + 1] << 8) | d[j + 2]) >>> 0;
    }
    return new RgbaImage(img.width, img.height, px);
  }

  static like(other: RgbaImage): RgbaImage {
    return new RgbaImage(other.width, other.height);
  }

  static argb(a: number, r: number, g: number, b: number): number {
    return (((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) >>> 0;
  }

  static alphaOf(argb: number): number {
    return (argb >>> 24) & 0xff;
  }

  static redOf(argb: number): number {
    return (argb >>> 16) & 0xff;
  }

  static greenOf(argb: number): number {
    return (argb >>> 8) & 0xff;
  }

  static blueOf(argb: number): number {
    return argb & 0xff;
  }
}

/** Clamp helpers used across the engine; centralised so the rounding rule is one rule. */
export const Px = {
  clamp01(v: number): number {
    return v < 0 ? 0 : v > 1 ? 1 : v;
  },

  clamp(v: number, lo: number, hi: number): number {
    return v < lo ? lo : v > hi ? hi : v;
  },

  clampInt(v: number, lo: number, hi: number): number {
    const i = v | 0;
    return i < lo ? lo : i > hi ? hi : i;
  },

  /**
   * 0..1 float to 0..255 byte. Rounds rather than truncating: truncation makes 1.0 the only value
   * that reaches 255 and biases every export half a level dark.
   */
  toByte255(v: number): number {
    const b = Math.round(Px.clamp01(v) * 255);
    return b < 0 ? 0 : b > 255 ? 255 : b;
  },
} as const;
