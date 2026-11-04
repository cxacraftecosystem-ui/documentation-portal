import { Px, RgbaImage } from './buffers';
import { writeDxf } from './dxfWriter';
import { writeEps } from './epsWriter';
import { Mat2D, VecDocument } from './path';
import { writePdf } from './pdfWriter';
import { encode as encodePng, encodeGray as encodePngGray } from './pngEncoder';
import { render } from './raster';
import { SvgOptions, svgOptions, write as writeSvg } from './svgWriter';

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

  get isVector(): boolean {
    return (
      this.format === ExportFormat.SVG ||
      this.format === ExportFormat.PDF ||
      this.format === ExportFormat.EPS ||
      this.format === ExportFormat.DXF ||
      this.format === ExportFormat.PROJECT
    );
  }

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

export function outputSize(doc: VecDocument, o: ExportOptions): { width: number; height: number } {
  const baseW = o.width > 0 ? o.width : doc.width;
  const baseH = o.height > 0 ? o.height : doc.height;
  return {
    width: Math.max(1, Math.round(baseW * o.scale)),
    height: Math.max(1, Math.round(baseH * o.scale)),
  };
}

const utf8 = new TextEncoder();

export function encodeUtf8(s: string): Uint8Array {
  return utf8.encode(s);
}

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

export function exportSvg(doc: VecDocument, o: ExportOptions): Uint8Array {
  const opts: SvgOptions = svgOptions({
    precision: o.precision,
    includeMetadata: o.includeMetadata,
    groupByLayer: !o.flattenLayers,
  });
  return encodeUtf8(writeSvg(prepareVectorDoc(doc, o), opts));
}

export function exportPdf(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writePdf(prepareVectorDoc(doc, o), { includeMetadata: o.includeMetadata });
}

export function exportEps(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writeEps(prepareVectorDoc(doc, o), { includeMetadata: o.includeMetadata });
}

export function exportDxf(doc: VecDocument, o: ExportOptions): Uint8Array {
  return writeDxf(prepareVectorDoc(doc, o));
}

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
  view.setUint32(30, 0, true);
  view.setUint32(34, pixelBytes, true);
  view.setInt32(38, 2835, true);
  view.setInt32(42, 2835, true);
  const px = src.pixels;
  let o = headerSize;
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

export function encodeTiff(src: RgbaImage): Uint8Array {
  const w = src.width;
  const h = src.height;
  const pixelBytes = w * h * 4;
  const entryCount = 12;
  const ifdSize = 2 + entryCount * 12 + 4;
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
    if (type === 3 && count === 1) view.setUint16(p + 8, value, true);
    else view.setUint32(p + 8, value, true);
    p += 12;
  };
  entry(256, 4, 1, w);
  entry(257, 4, 1, h);
  entry(258, 3, 4, bitsOffset);
  entry(259, 3, 1, 1);
  entry(262, 3, 1, 2);
  entry(273, 4, 1, dataOffset);
  entry(277, 3, 1, 4);
  entry(278, 4, 1, h);
  entry(279, 4, 1, pixelBytes);
  entry(282, 5, 1, resOffset);
  entry(283, 5, 1, resOffset + 8);
  entry(338, 3, 1, 2);
  view.setUint32(p, 0, true);

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

export function encodePngImage(src: RgbaImage, dpi = 0): Uint8Array {
  return encodePng(src, dpi);
}

export { encodePngGray };

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
