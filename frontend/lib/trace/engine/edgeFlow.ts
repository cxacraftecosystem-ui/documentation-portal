import { GrayF, Px } from './buffers';
import { GradientOp, gaussianBlur, gradients } from './convolve';
import { softThreshold } from './edgeDog';

export class FlowField {
  constructor(
    readonly width: number,
    readonly height: number,
    readonly tx: Float32Array,
    readonly ty: Float32Array,
    readonly magnitude: Float32Array,
  ) {}
}

const ETF_ETA = 1;

export function structureTensorFlow(src: GrayF, sigma = 2): FlowField {
  const w = src.width;
  const h = src.height;
  const n = w * h;
  const g = gradients(src, GradientOp.SCHARR);
  const gx = g.gx.data;
  const gy = g.gy.data;

  const exx = new Float32Array(n);
  const exy = new Float32Array(n);
  const eyy = new Float32Array(n);
  const mag = new Float32Array(n);
  let maxMag = 0;
  for (let i = 0; i < n; i++) {
    const a = gx[i];
    const b = gy[i];
    exx[i] = a * a;
    exy[i] = a * b;
    eyy[i] = b * b;
    const m = Math.sqrt(a * a + b * b);
    mag[i] = m;
    if (m > maxMag) maxMag = m;
  }
  const jxx = gaussianBlur(new GrayF(w, h, exx), sigma).data;
  const jxy = gaussianBlur(new GrayF(w, h, exy), sigma).data;
  const jyy = gaussianBlur(new GrayF(w, h, eyy), sigma).data;

  const invMax = maxMag > 0 ? 1 / maxMag : 0;
  for (let i = 0; i < n; i++) mag[i] *= invMax;

  const tx = new Float32Array(n);
  const ty = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const a = jxx[i];
    const b = jxy[i];
    const c = jyy[i];
    const diff = a - c;
    const root = Math.sqrt(diff * diff + 4 * b * b);
    const lmin = (a + c - root) * 0.5;
    let vx: number;
    let vy: number;
    if (Math.abs(b) > 1e-12) {
      vx = b;
      vy = lmin - a;
    } else if (a <= c) {
      vx = 1;
      vy = 0;
    } else {
      vx = 0;
      vy = 1;
    }
    const len = Math.sqrt(vx * vx + vy * vy);
    if (len > 1e-20) {
      let nx = vx / len;
      let ny = vy / len;
      if (ny < 0 || (ny === 0 && nx < 0)) {
        nx = -nx;
        ny = -ny;
      }
      tx[i] = nx;
      ty[i] = ny;
    } else {
      tx[i] = 1;
      ty[i] = 0;
    }
  }
  return new FlowField(w, h, tx, ty, mag);
}

export function refineEtf(field: FlowField, iterations = 3, radius = 5): FlowField {
  const w = field.width;
  const h = field.height;
  const n = w * h;
  let tx = field.tx.slice();
  let ty = field.ty.slice();
  let nx = new Float32Array(n);
  let ny = new Float32Array(n);
  const mag = field.magnitude;
  const r = Math.max(1, radius | 0);
  const iters = Math.max(0, iterations | 0);
  const r2 = r * r;

  for (let it = 0; it < iters; it++) {
    for (let y = 0; y < h; y++) {
      const row = y * w;
      for (let x = 0; x < w; x++) {
        const i = row + x;
        const cx = tx[i];
        const cy = ty[i];
        const cg = mag[i];
        let sx = 0;
        let sy = 0;
        for (let dy = -r; dy <= r; dy++) {
          const yy = y + dy;
          const cyy = yy < 0 ? 0 : yy >= h ? h - 1 : yy;
          const srow = cyy * w;
          const dy2 = dy * dy;
          for (let dx = -r; dx <= r; dx++) {
            if (dx * dx + dy2 >= r2) continue;
            const xx = x + dx;
            const j = srow + (xx < 0 ? 0 : xx >= w ? w - 1 : xx);
            const ox = tx[j];
            const oy = ty[j];
            const dot = cx * ox + cy * oy;
            const wd = dot < 0 ? -dot : dot;
            if (wd === 0) continue;
            const wm = (1 + Math.tanh(ETF_ETA * (mag[j] - cg))) * 0.5;
            const weight = (dot >= 0 ? 1 : -1) * wm * wd;
            sx += ox * weight;
            sy += oy * weight;
          }
        }
        const len = Math.sqrt(sx * sx + sy * sy);
        if (len > 1e-20) {
          nx[i] = sx / len;
          ny[i] = sy / len;
        } else {
          nx[i] = cx;
          ny[i] = cy;
        }
      }
    }
    const sxa = tx;
    const sya = ty;
    tx = nx;
    ty = ny;
    nx = sxa;
    ny = sya;
  }
  return new FlowField(w, h, tx, ty, mag);
}

export interface FlowParams {
  readonly tensorSigma: number;
  readonly etfIterations: number;
  readonly etfRadius: number;
  readonly sigmaC: number;
  readonly sigmaM: number;
  readonly tau: number;
  readonly fdogIterations: number;
  readonly epsilon: number;
  readonly phi: number;
}

export function flowParams(over: Partial<FlowParams> = {}): FlowParams {
  return {
    tensorSigma: 2,
    etfIterations: 3,
    etfRadius: 5,
    sigmaC: 1,
    sigmaM: 3,
    tau: 0.99,
    fdogIterations: 3,
    epsilon: 0.5,
    phi: 20,
    ...over,
  };
}

const MAX_TAU = 0.999;

const SIGMA_RATIO = Math.fround(1.6);

export function fdog(
  src: GrayF,
  field: FlowField,
  sigmaC = 1,
  sigmaM = 3,
  tau = 0.99,
  iterations = 3,
  epsilon = 0.5,
  phi = 20,
): GrayF {
  if (field.width !== src.width || field.height !== src.height) {
    throw new Error('fdog(): flow field size does not match the source');
  }
  const t = Math.fround(Px.clamp(tau, 0, MAX_TAU));
  const iters = Px.clampInt(iterations, 1, 16);
  const sc = Math.fround(clampSigma(sigmaC, 16));
  const sm = Math.fround(clampSigma(sigmaM, 32));
  const ss = Math.fround(SIGMA_RATIO * sc);
  const eps = Math.fround(epsilon);
  const sharpness = Math.fround(phi);

  const rc = radiusFor(ss);
  const kernAcross = new Float64Array(2 * rc + 1);
  {
    const gc = new Float64Array(2 * rc + 1);
    const gs = new Float64Array(2 * rc + 1);
    const dc = 2 * sc * sc;
    const ds = 2 * ss * ss;
    let sumC = 0;
    let sumS = 0;
    for (let i = -rc; i <= rc; i++) {
      const a = Math.exp(-(i * i) / dc);
      const b = Math.exp(-(i * i) / ds);
      gc[i + rc] = a;
      gs[i + rc] = b;
      sumC += a;
      sumS += b;
    }
    for (let i = 0; i < kernAcross.length; i++) kernAcross[i] = gc[i] / sumC - t * (gs[i] / sumS);
  }
  const scale = 1 / (1 - t);

  const rm = radiusFor(sm);
  const kernAlong = new Float64Array(rm + 1);
  {
    const dm = 2 * sm * sm;
    for (let i = 0; i <= rm; i++) kernAlong[i] = Math.exp(-(i * i) / dm);
  }

  const work = src.copy();
  const wd = work.data;
  const ink = new Float32Array(wd.length);
  for (let pass = 1; ; pass++) {
    const along = smoothAlongFlow(
      dogAcrossFlow(work, field, kernAcross, rc, scale),
      field,
      kernAlong,
      rm,
    );
    const ad = along.data;
    for (let i = 0; i < ink.length; i++) ink[i] = softThreshold(ad[i], eps, sharpness);
    if (pass >= iters) break;
    for (let i = 0; i < wd.length; i++) if (ink[i] < wd[i]) wd[i] = ink[i];
  }
  return new GrayF(src.width, src.height, ink);
}

function clampSigma(v: number, hi: number): number {
  return Number.isNaN(v) || v < 0.05 ? 0.05 : v > hi ? hi : v;
}

function radiusFor(sigma: number): number {
  const r = Math.ceil(3 * sigma);
  return r < 1 ? 1 : r;
}

function dogAcrossFlow(
  src: GrayF,
  field: FlowField,
  kern: Float64Array,
  radius: number,
  scale: number,
): GrayF {
  const w = src.width;
  const h = src.height;
  const out = new Float32Array(w * h);
  const d = src.data;
  const tx = field.tx;
  const ty = field.ty;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      const gx = ty[i];
      const gy = -tx[i];
      let acc = kern[radius] * d[i];
      for (let s = 1; s <= radius; s++) {
        const ox = gx * s;
        const oy = gy * s;
        acc += kern[radius + s] * src.sampleBilinear(x + ox, y + oy);
        acc += kern[radius - s] * src.sampleBilinear(x - ox, y - oy);
      }
      out[i] = acc * scale;
    }
  }
  return new GrayF(w, h, out);
}

function smoothAlongFlow(src: GrayF, field: FlowField, kern: Float64Array, radius: number): GrayF {
  const w = src.width;
  const h = src.height;
  const out = new Float32Array(w * h);
  const d = src.data;
  const tx = field.tx;
  const ty = field.ty;
  const maxX = w - 1;
  const maxY = h - 1;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w; x++) {
      const i = row + x;
      let acc = kern[0] * d[i];
      let wsum = kern[0];
      for (let side = 0; side < 2; side++) {
        let dx = side === 0 ? tx[i] : -tx[i];
        let dy = side === 0 ? ty[i] : -ty[i];
        let cx = x;
        let cy = y;
        for (let s = 1; s <= radius; s++) {
          cx += dx;
          cy += dy;
          if (cx < 0 || cy < 0 || cx > maxX || cy > maxY) break;
          const kw = kern[s];
          acc += kw * src.sampleBilinear(cx, cy);
          wsum += kw;
          const j = Px.clampInt(Math.round(cy), 0, maxY) * w + Px.clampInt(Math.round(cx), 0, maxX);
          let ntx = tx[j];
          let nty = ty[j];
          if (ntx * dx + nty * dy < 0) {
            ntx = -ntx;
            nty = -nty;
          }
          if (ntx * ntx + nty * nty < 1e-12) break;
          dx = ntx;
          dy = nty;
        }
      }
      out[i] = wsum > 0 ? acc / wsum : d[i];
    }
  }
  return new GrayF(w, h, out);
}

export function coherentLineDrawing(src: GrayF, params: FlowParams): GrayF {
  const field = refineEtf(
    structureTensorFlow(src, params.tensorSigma),
    params.etfIterations,
    params.etfRadius,
  );
  return fdog(
    src,
    field,
    params.sigmaC,
    params.sigmaM,
    params.tau,
    params.fdogIterations,
    params.epsilon,
    params.phi,
  );
}
