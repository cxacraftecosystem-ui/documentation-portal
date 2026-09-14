import { Px, RgbaImage } from './buffers';
import { writeDxf } from './dxfWriter';
import { writeEps } from './epsWriter';
import { Mat2D, VecDocument } from './path';
import { writePdf } from './pdfWriter';
import { encode as encodePng, encodeGray as encodePngGray } from './pngEncoder';
import { render } from './raster';
import { SvgOptions, svgOptions, write as writeSvg } from './svgWriter';

/**
 * Export formats, options, and the single dispatcher every caller uses.
 *
 * **JPEG and WEBP are deliberately absent.** Writing a JPEG encoder by hand would be worse than the
 * platform's in every dimension that matters, so {@link exportDocument} throws for those two and the
 * platform layer routes them through `canvas.toBlob` (web) or `Bitmap.compress` (Android).
 * {@link ExportOptions.isVector} and this comment are where that fact is recorded, so it cannot be
 * discovered by surprise at run time.
 */

export enum ExportFormat {
  PNG = 'PNG',
  JPEG = 'JPEG',
  WEBP = 'WEBP',
  SVG = 'SVG',
  PDF = 'PDF',
  EPS = 'EPS',
  DXF = 'DXF',
  TIFF = 'TIFF',
  BMP = 'BMP',
  PROJECT = 'PROJECT',
}

/**
 * Everything an export needs.
 *
 * A class rather than an interface because `isVector`, `extension` and `mimeType` are properties in the
 * Kotlin contract, and a caller who has to remember to call three free functions will eventually write a
 * `.svg` file with a PNG mime type.
 *
 * @property width  0 means "the document's own size"; `scale` still applies
 * @property height 0 means "the document's own size"
 * @property background null means transparent — honoured by the raster formats, and expressed by the
 *           vector ones as simply not emitting a background rectangle
 */
export class ExportOptions {
  readonly format: ExportFormat;
  readonly width: number;
  readonly height: number;
  readonly scale: number;
  readonly background: number | null;
  readonly quality: number;
  readonly dpi: number;
  readonly precision: number;
  readonly includeMetadata: boolean;
  readonly flattenLayers: boolean;

  constructor(init: ExportOptionsInit) {
    this.format = init.format;
    this.width = Math.max(0, (init.width ?? 0) | 0);
    this.height = Math.max(0, (init.height ?? 0) | 0);
    this.scale = Px.clamp(init.scale ?? 1, 0.01, 64);
    this.background = init.background ?? null;
    this.quality = Px.clampInt(init.quality ?? 95, 1, 100);
    this.dpi = Px.clampInt(init.dpi ?? 300, 1, 2400);
    this.precision = Px.clampInt(init.precision ?? 2, 0, 8);
    this.includeMetadata = init.includeMetadata ?? true;
    this.flattenLayers = init.flattenLayers ?? false;
  }

  /** True for the resolution-independent formats. PROJECT counts: it stores geometry, not pixels. */
  get isVector(): boolean {
    return (
      this.format === ExportFormat.SVG ||
      this.format === ExportFormat.PDF ||
      this.format === ExportFormat.EPS ||
      this.format === ExportFormat.DXF ||
      this.format === ExportFormat.PROJECT
    );
  }

  /** File extension without the dot. */
  get extension(): string {
    switch (this.format) {
      case ExportFormat.PNG:
        return 'png';
      case ExportFormat.JPEG:
        return 'jpg';
      case ExportFormat.WEBP:
        return 'webp';
      case ExportFormat.SVG:
        return 'svg';
      case ExportFormat.PDF:
        return 'pdf';
      case ExportFormat.EPS:
        return 'eps';
      case ExportFormat.DXF:
        return 'dxf';
      case ExportFormat.TIFF:
        return 'tiff';
      case ExportFormat.BMP:
        return 'bmp';
      default:
        return 'otproj';
    }
  }

  get mimeType(): string {
    switch (this.format) {
      case ExportFormat.PNG:
        return 'image/png';
      case ExportFormat.JPEG:
        return 'image/jpeg';
      case ExportFormat.WEBP:
        return 'image/webp';
      case ExportFormat.SVG:
        return 'image/svg+xml';
      case ExportFormat.PDF:
        return 'application/pdf';
      case ExportFormat.EPS:
        return 'application/postscript';
      case ExportFormat.DXF:
        return 'image/vnd.dxf';
      case ExportFormat.TIFF:
        return 'image/tiff';
      case ExportFormat.BMP:
        return 'image/bmp';
      default:
        return 'application/json';
    }
  }

  /** @returns a copy with `over` applied, re-clamped. */
  with(over: Partial<ExportOptionsInit>): ExportOptions {
    return new ExportOptions({
      format: this.format,
      width: this.width,
      height: this.height,
      scale: this.scale,
      background: this.background,
      quality: this.quality,
      dpi: this.dpi,
      precision: this.precision,
      includeMetadata: this.includeMetadata,
      flattenLayers: this.flattenLayers,
      ...over,
    });
  }
}

/** Constructor shape for {@link ExportOptions}; every field but `format` has a documented default. */
export interface ExportOptionsInit {
  format: ExportFormat;
  width?: number;
  height?: number;
  scale?: number;
  background?: number | null;
  quality?: number;
  dpi?: number;
  precision?: number;
  includeMetadata?: boolean;
  flattenLayers?: boolean;
}

/** @returns the pixel dimensions a raster export should produce for `doc` under `o`. */
export function outputSize(doc: VecDocument, o: ExportOptions): { width: number; height: number } {
  const baseW = o.width > 0 ? o.width : doc.width;
  const baseH = o.height > 0 ? o.height : doc.height;
  return {
    width: Math.max(1, Math.round(baseW * o.scale)),
    height: Math.max(1, Math.round(baseH * o.scale)),
  };
}

const utf8 = new TextEncoder();

/** @returns the bytes of `s` as UTF-8. Centralised so no writer hand-rolls a byte loop. */
export function encodeUtf8(s: string): Uint8Array {
  return utf8.encode(s);
}

/** @returns `doc` scaled by `o.scale`, with `o.background` applied when it is set. */
function prepareVectorDoc(doc: VecDocument, o: ExportOptions): VecDocument {
  const scaled =
    o.scale === 1
      ? doc
      : new VecDocument(
          doc.width * o.scale,
          doc.height * o.scale,
          scaleShapes(doc, o.scale),
          doc.background,
        );
  return o.background === null
    ? scaled
    : new VecDocument(scaled.width, scaled.height, scaled.layers, o.background);
}

/**
 * Scale every shape, including its stroke widths.
 *
 * `VecPath.transform` deliberately leaves `strokeWidth` alone — it is a style property, not geometry — so
 * a scale that did not touch it would emit a 300 dpi export with hairlines meant for a 72 dpi preview.
 */
function scaleShapes(doc: VecDocument, scale: number): VecDocument['layers'] {
  const m = Mat2D.scale(scale, scale);
  const widthScale = m.meanScale();
  return doc.layers.map((layer) => ({
    ...layer,
    shapes: layer.shapes.map((s) => ({
      path: s.path.transform(m),
      style: { ...s.style, strokeWidth: s.style.strokeWidth * widthScale },
    })),
  }));
}

/** SVG export: {@link writeSvg} with the export options mapped across, then UTF-8 encoded. */
export function exportSvg(doc: VecDocument, o: ExportOptions): Uint8Array {
  const opts: SvgOptions = svgOptions({
    precision: o.precision,
    includeMetadata: o.includeMetadata,
    groupByLayer: !o.flattenLayers,
  });
  return encodeUtf8(writeSvg(prepareVectorDoc(doc, o), opts));
}

/** PDF 1.4 export. */
export function exportPdf(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writePdf(prepareVectorDoc(doc, o), { includeMetadata: o.includeMetadata });
}

/** EPS 3.0 export. */
export function exportEps(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writeEps(prepareVectorDoc(doc, o), { includeMetadata: o.includeMetadata });
}

/** DXF R12 export. Curves are flattened, because R12 has no spline entity. */
export function exportDxf(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writeDxf(prepareVectorDoc(doc, o));
}

/**
 * BMP export: 32-bit BGRA, `BITMAPINFOHEADER`, bottom-up.
 *
 * 32 bpp with `BI_RGB` rather than 24 bpp: the fourth byte is ignored by readers that do not understand
 * alpha and honoured by the ones that do, whereas 24 bpp would silently composite the artwork against
 * whatever the reader felt like. Rows are 4-byte aligned for free at 32 bpp.
 */
export function encodeBmp(src: RgbaImage): Uint8Array {
  const w = src.width;
  const h = src.height;
  const pixelBytes = w * h * 4;
  const headerSize = 14 + 40;
  const out = new Uint8Array(headerSize + pixelBytes);
  const view = new DataView(out.buffer);
  out[0] = 0x42;
  out[1] = 0x4d;
  view.setUint32(2, out.length, true);
  view.setUint32(10, headerSize, true);
  view.setUint32(14, 40, true);
  view.setInt32(18, w, true);
  view.setInt32(22, h, true);
  view.setUint16(26, 1, true);
  view.setUint16(28, 32, true);
  view.setUint32(30, 0, true); // BI_RGB
  view.setUint32(34, pixelBytes, true);
  view.setInt32(38, 2835, true); // ~72 dpi in pixels per metre
  view.setInt32(42, 2835, true);
  const px = src.pixels;
  let o = headerSize;
  // Bottom-up row order is the baseline BMP convention; a negative height means top-down and is the
  // single most common way a hand-written BMP comes out upside down in one viewer and not another.
  for (let y = h - 1; y >= 0; y--) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const v = px[row + x];
      out[o++] = v & 0xff;
      out[o++] = (v >>> 8) & 0xff;
      out[o++] = (v >>> 16) & 0xff;
      out[o++] = (v >>> 24) & 0xff;
    }
  }
  return out;
}

/**
 * TIFF export: baseline, uncompressed, RGBA in one strip, little-endian.
 *
 * `ExtraSamples = 2` (unassociated alpha) is written explicitly. Without it a reader is entitled to treat
 * the fourth sample as premultiplied, and every antialiased edge comes back dark.
 */
export function encodeTiff(src: RgbaImage): Uint8Array {
  const w = src.width;
  const h = src.height;
  const pixelBytes = w * h * 4;
  const entryCount = 12;
  const ifdSize = 2 + entryCount * 12 + 4;
  // BitsPerSample and the two rationals live outside the IFD because their values exceed four bytes.
  const bitsOffset = 8 + ifdSize;
  const resOffset = bitsOffset + 8;
  const dataOffset = resOffset + 16;
  const out = new Uint8Array(dataOffset + pixelBytes);
  const view = new DataView(out.buffer);
  out[0] = 0x49;
  out[1] = 0x49;
  view.setUint16(2, 42, true);
  view.setUint32(4, 8, true);
  view.setUint16(8, entryCount, true);

  let p = 10;
  const entry = (tag: number, type: number, count: number, value: number): void => {
    view.setUint16(p, tag, true);
    view.setUint16(p + 2, type, true);
    view.setUint32(p + 4, count, true);
    // A SHORT that fits in the value field is left-aligned in it; a LONG or an offset fills it.
    if (type === 3 && count === 1) view.setUint16(p + 8, value, true);
    else view.setUint32(p + 8, value, true);
    p += 12;
  };
  entry(256, 4, 1, w); // ImageWidth
  entry(257, 4, 1, h); // ImageLength
  entry(258, 3, 4, bitsOffset); // BitsPerSample
  entry(259, 3, 1, 1); // Compression = none
  entry(262, 3, 1, 2); // PhotometricInterpretation = RGB
  entry(273, 4, 1, dataOffset); // StripOffsets
  entry(277, 3, 1, 4); // SamplesPerPixel
  entry(278, 4, 1, h); // RowsPerStrip
  entry(279, 4, 1, pixelBytes); // StripByteCounts
  entry(282, 5, 1, resOffset); // XResolution
  entry(283, 5, 1, resOffset + 8); // YResolution
  entry(338, 3, 1, 2); // ExtraSamples = unassociated alpha
  view.setUint32(p, 0, true); // next IFD: none

  for (let i = 0; i < 4; i++) view.setUint16(bitsOffset + i * 2, 8, true);
  view.setUint32(resOffset, 72, true);
  view.setUint32(resOffset + 4, 1, true);
  view.setUint32(resOffset + 8, 72, true);
  view.setUint32(resOffset + 12, 1, true);

  const px = src.pixels;
  let o = dataOffset;
  for (let i = 0; i < px.length; i++) {
    const v = px[i];
    out[o++] = (v >>> 16) & 0xff;
    out[o++] = (v >>> 8) & 0xff;
    out[o++] = v & 0xff;
    out[o++] = (v >>> 24) & 0xff;
  }
  return out;
}

/** PNG export of a packed image, with the dpi written into a `pHYs` chunk when non-zero. */
export function encodePngImage(src: RgbaImage, dpi = 0): Uint8Array {
  return encodePng(src, dpi);
}

/** PNG export of a grey plane; roughly a quarter the size of the RGBA form. */
export { encodePngGray };

/**
 * The one dispatcher.
 *
 * @param raster an already-rendered raster to use for the pixel formats. When null, the document is
 *               rendered at {@link outputSize} — passing the pipeline's own preview avoids rendering the
 *               same geometry twice.
 * @returns the encoded file bytes.
 * @throws for JPEG and WEBP, which have no encoder here by design (see the module comment), and for
 *         PROJECT, which is `project.ts`'s job because it needs the parameters as well as the geometry.
 */
export function exportDocument(
  doc: VecDocument,
  raster: RgbaImage | null,
  o: ExportOptions,
): Uint8Array {
  switch (o.format) {
    case ExportFormat.SVG:
      return exportSvg(doc, o);
    case ExportFormat.PDF:
      return exportPdf(doc, o);
    case ExportFormat.EPS:
      return exportEps(doc, o);
    case ExportFormat.DXF:
      return exportDxf(doc, o);
    case ExportFormat.PNG:
    case ExportFormat.BMP:
    case ExportFormat.TIFF: {
      const size = outputSize(doc, o);
      const img =
        raster !== null && raster.width === size.width && raster.height === size.height
          ? raster
          : render(doc, size.width, size.height, o.background ?? 0);
      if (o.format === ExportFormat.PNG) return encodePngImage(img, o.dpi);
      if (o.format === ExportFormat.BMP) return encodeBmp(img);
      return encodeTiff(img);
    }
    case ExportFormat.JPEG:
    case ExportFormat.WEBP:
      throw new Error(
        `${o.format} is not encoded by the engine: the platform layer must route it through ` +
          'canvas.toBlob (web) or Bitmap.compress (Android). See exportFormats.ts.',
      );
    default:
      throw new Error('PROJECT is encoded by ProjectCodec, not by exportDocument.');
  }
}
