import { GrayF, Px, RgbaImage } from './buffers';
import { subjectMatte } from './matte';
import { cropGray, cropRgba } from './resample';

const f32 = Math.fround;

export const DEFAULT_THRESHOLD = 0.5;

export const DEFAULT_MARGIN_FRACTION = f32(0.04);

export const MIN_COVERAGE = f32(0.004);

export const MAX_COVERAGE = f32(0.97);

export const MIN_SUBJECT_PIXELS = 16;

export interface SubjectBox {
  readonly x: number;
  readonly y: number;
  readonly w: number;
  readonly h: number;
  readonly coverage: number;
  readonly confident: boolean;
  readonly reason: string;
}

export interface SubjectFind {
  readonly alpha: GrayF;
  readonly box: SubjectBox;
  readonly confidence: number;
  readonly confident: boolean;
  readonly reason: string;
}

export function boundingBox(
  alpha: GrayF,
  threshold = DEFAULT_THRESHOLD,
  marginFraction = DEFAULT_MARGIN_FRACTION,
): SubjectBox {
  const w = alpha.width;
  const h = alpha.height;
  const n = w * h;
  const t = f32(Px.clamp(threshold, 0, 1));
  const d = alpha.data;

  let minX = w;
  let minY = h;
  let maxX = -1;
  let maxY = -1;
  let count = 0;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let rowHit = false;
    for (let x = 0; x < w; x++) {
      if (d[row + x] >= t) {
        count++;
        rowHit = true;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
      }
    }
    if (rowHit) {
      if (y < minY) minY = y;
      maxY = y;
    }
  }

  const coverage = f32(count / n);
  if (count === 0) {
    return full(
      w,
      h,
      0,
      'The matte kept no pixels at all, so there is nothing to crop to and the whole frame was kept.',
    );
  }
  if (count < MIN_SUBJECT_PIXELS) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${count} ${count === 1 ? 'pixel' : 'pixels'}, which is too little to be a ` +
        'subject, so the whole frame was kept.',
    );
  }
  if (coverage <= MIN_COVERAGE) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${percent(coverage)}% of the frame, which reads as a failed separation ` +
        'rather than a small subject, so the whole frame was kept.',
    );
  }
  if (coverage >= MAX_COVERAGE) {
    return full(
      w,
      h,
      coverage,
      `The matte kept ${percent(coverage)}% of the frame, so there is no background to crop away ` +
        'and the whole frame was kept.',
    );
  }

  const tightW = maxX - minX + 1;
  const tightH = maxY - minY + 1;
  const margin =
    marginFraction <= 0 ? 0 : Math.max(1, Math.round(f32(marginFraction * Math.max(tightW, tightH))));
  const x0 = Math.max(0, minX - margin);
  const y0 = Math.max(0, minY - margin);
  const x1 = Math.min(w - 1, maxX + margin);
  const y1 = Math.min(h - 1, maxY + margin);
  const bw = x1 - x0 + 1;
  const bh = y1 - y0 + 1;

  const reason =
    bw === w && bh === h
      ? 'The subject reaches every edge of the frame, so nothing was cropped away.'
      : `Cropped to the subject: ${bw}x${bh} of ${w}x${h} (${percent(coverage)}% of the frame is ` +
        `subject), with a ${margin} px margin so nothing at the edge of it is clipped.`;
  return { x: x0, y: y0, w: bw, h: bh, coverage, confident: true, reason };
}

export function locate(
  src: RgbaImage,
  tolerance = 0.18,
  threshold = DEFAULT_THRESHOLD,
  marginFraction = DEFAULT_MARGIN_FRACTION,
): SubjectFind {
  const matte = subjectMatte(src, tolerance);
  const box = boundingBox(matte.alpha, threshold, marginFraction);
  return {
    alpha: matte.alpha,
    box,
    confidence: matte.confidence,
    confident: matte.confident && box.confident,
    reason: `${matte.reason} ${box.reason}`,
  };
}

export function crop(src: RgbaImage, box: SubjectBox): RgbaImage;
export function crop(src: GrayF, box: SubjectBox): GrayF;
export function crop(src: RgbaImage | GrayF, box: SubjectBox): RgbaImage | GrayF {
  if (src instanceof GrayF) return cropGray(src, box.x, box.y, box.w, box.h);
  return cropRgba(src, box.x, box.y, box.w, box.h);
}

function full(w: number, h: number, coverage: number, reason: string): SubjectBox {
  return { x: 0, y: 0, w, h, coverage, confident: false, reason };
}

function percent(v: number): number {
  return Math.round(Px.clamp01(v) * 100);
}
