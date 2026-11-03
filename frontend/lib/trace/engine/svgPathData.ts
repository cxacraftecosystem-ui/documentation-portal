import { CubicSeg, VecPath, VecPoint, VecSeg } from './path';

function fmt(v: number, precision: number): string {
  if (!Number.isFinite(v)) return '0';
  let s = v.toFixed(precision);
  if (s.indexOf('.') >= 0) {
    s = s.replace(/0+$/, '').replace(/\.$/, '');
  }
  if (s === '-0') return '0';
  return s;
}

export function toD(path: VecPath, precision = 2): string {
  const p = Math.max(0, Math.min(8, precision | 0));
  const parts: string[] = [];
  parts.push(`M${fmt(path.start.x, p)} ${fmt(path.start.y, p)}`);
  let last = '';
  for (const seg of path.segments) {
    if (seg.kind === 'line') {
      parts.push(`${last === 'L' ? ' ' : 'L'}${fmt(seg.to.x, p)} ${fmt(seg.to.y, p)}`);
      last = 'L';
    } else if (seg.kind === 'cubic') {
      parts.push(
        `${last === 'C' ? ' ' : 'C'}${fmt(seg.c1.x, p)} ${fmt(seg.c1.y, p)} ` +
          `${fmt(seg.c2.x, p)} ${fmt(seg.c2.y, p)} ${fmt(seg.to.x, p)} ${fmt(seg.to.y, p)}`,
      );
      last = 'C';
    } else {
      parts.push(
        `${last === 'Q' ? ' ' : 'Q'}${fmt(seg.c.x, p)} ${fmt(seg.c.y, p)} ` +
          `${fmt(seg.to.x, p)} ${fmt(seg.to.y, p)}`,
      );
      last = 'Q';
    }
  }
  if (path.closed) parts.push('Z');
  return parts.join('');
}

class Scanner {
  pos = 0;

  constructor(readonly s: string) {}

  skipSeparators(): void {
    while (this.pos < this.s.length) {
      const c = this.s.charCodeAt(this.pos);
      if (c === 32 || c === 9 || c === 10 || c === 13 || c === 12 || c === 44) this.pos++;
      else break;
    }
  }

  atEnd(): boolean {
    this.skipSeparators();
    return this.pos >= this.s.length;
  }

  peekCommand(): string | null {
    this.skipSeparators();
    if (this.pos >= this.s.length) return null;
    const c = this.s[this.pos];
    return /[MmLlHhVvCcSsQqTtAaZz]/.test(c) ? c : null;
  }

  number(): number | null {
    this.skipSeparators();
    const s = this.s;
    const n = s.length;
    let i = this.pos;
    const start = i;
    if (i < n && (s[i] === '+' || s[i] === '-')) i++;
    let sawDigit = false;
    while (i < n && s[i] >= '0' && s[i] <= '9') {
      i++;
      sawDigit = true;
    }
    if (i < n && s[i] === '.') {
      i++;
      while (i < n && s[i] >= '0' && s[i] <= '9') {
        i++;
        sawDigit = true;
      }
    }
    if (!sawDigit) return null;
    if (i < n && (s[i] === 'e' || s[i] === 'E')) {
      let j = i + 1;
      if (j < n && (s[j] === '+' || s[j] === '-')) j++;
      let expDigit = false;
      while (j < n && s[j] >= '0' && s[j] <= '9') {
        j++;
        expDigit = true;
      }
      if (expDigit) i = j;
    }
    const text = s.slice(start, i);
    const v = Number(text);
    if (!Number.isFinite(v)) return null;
    this.pos = i;
    return v;
  }

  flag(): boolean | null {
    this.skipSeparators();
    const c = this.s[this.pos];
    if (c === '0') {
      this.pos++;
      return false;
    }
    if (c === '1') {
      this.pos++;
      return true;
    }
    return null;
  }
}

interface Builder {
  startX: number;
  startY: number;
  segments: VecSeg[];
  closed: boolean;
}

export function parse(d: string): VecPath[] {
  const out: VecPath[] = [];
  const sc = new Scanner(d);
  let cur: Builder | null = null;
  let cx = 0;
  let cy = 0;
  let sx = 0;
  let sy = 0;
  let lastCubicC2x = 0;
  let lastCubicC2y = 0;
  let hadCubic = false;
  let lastQuadCx = 0;
  let lastQuadCy = 0;
  let hadQuad = false;
  let cmd = '';

  const flush = (): void => {
    if (cur !== null) {
      out.push(new VecPath({ x: cur.startX, y: cur.startY }, cur.segments, cur.closed));
      cur = null;
    }
  };

  let guard = 0;
  const guardLimit = d.length * 4 + 16;
  while (!sc.atEnd() && guard++ < guardLimit) {
    const next = sc.peekCommand();
    if (next !== null) {
      sc.pos++;
      cmd = next;
    } else if (cmd === '') {
      break;
    } else if (cmd === 'M') {
      cmd = 'L';
    } else if (cmd === 'm') {
      cmd = 'l';
    }

    const rel = cmd >= 'a' && cmd <= 'z';
    const upper = cmd.toUpperCase();

    if (upper === 'Z') {
      if (cur !== null) {
        cur.closed = true;
        flush();
      }
      cx = sx;
      cy = sy;
      hadCubic = false;
      hadQuad = false;
      continue;
    }

    if (upper === 'M') {
      const x = sc.number();
      const y = sc.number();
      if (x === null || y === null) break;
      flush();
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      sx = cx;
      sy = cy;
      cur = { startX: cx, startY: cy, segments: [], closed: false };
      hadCubic = false;
      hadQuad = false;
      continue;
    }

    if (cur === null) {
      cur = { startX: cx, startY: cy, segments: [], closed: false };
    }

    if (upper === 'L') {
      const x = sc.number();
      const y = sc.number();
      if (x === null || y === null) break;
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.line({ x: cx, y: cy }));
      hadCubic = false;
      hadQuad = false;
    } else if (upper === 'H') {
      const x = sc.number();
      if (x === null) break;
      cx = rel ? cx + x : x;
      cur.segments.push(VecSeg.line({ x: cx, y: cy }));
      hadCubic = false;
      hadQuad = false;
    } else if (upper === 'V') {
      const y = sc.number();
      if (y === null) break;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.line({ x: cx, y: cy }));
      hadCubic = false;
      hadQuad = false;
    } else if (upper === 'C') {
      const x1 = sc.number();
      const y1 = sc.number();
      const x2 = sc.number();
      const y2 = sc.number();
      const x = sc.number();
      const y = sc.number();
      if (x1 === null || y1 === null || x2 === null || y2 === null || x === null || y === null) break;
      const c1 = { x: rel ? cx + x1 : x1, y: rel ? cy + y1 : y1 };
      const c2 = { x: rel ? cx + x2 : x2, y: rel ? cy + y2 : y2 };
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.cubic(c1, c2, { x: cx, y: cy }));
      lastCubicC2x = c2.x;
      lastCubicC2y = c2.y;
      hadCubic = true;
      hadQuad = false;
    } else if (upper === 'S') {
      const x2 = sc.number();
      const y2 = sc.number();
      const x = sc.number();
      const y = sc.number();
      if (x2 === null || y2 === null || x === null || y === null) break;
      const c1 = hadCubic
        ? { x: 2 * cx - lastCubicC2x, y: 2 * cy - lastCubicC2y }
        : { x: cx, y: cy };
      const c2 = { x: rel ? cx + x2 : x2, y: rel ? cy + y2 : y2 };
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.cubic(c1, c2, { x: cx, y: cy }));
      lastCubicC2x = c2.x;
      lastCubicC2y = c2.y;
      hadCubic = true;
      hadQuad = false;
    } else if (upper === 'Q') {
      const x1 = sc.number();
      const y1 = sc.number();
      const x = sc.number();
      const y = sc.number();
      if (x1 === null || y1 === null || x === null || y === null) break;
      const c = { x: rel ? cx + x1 : x1, y: rel ? cy + y1 : y1 };
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.quad(c, { x: cx, y: cy }));
      lastQuadCx = c.x;
      lastQuadCy = c.y;
      hadQuad = true;
      hadCubic = false;
    } else if (upper === 'T') {
      const x = sc.number();
      const y = sc.number();
      if (x === null || y === null) break;
      const c = hadQuad ? { x: 2 * cx - lastQuadCx, y: 2 * cy - lastQuadCy } : { x: cx, y: cy };
      cx = rel ? cx + x : x;
      cy = rel ? cy + y : y;
      cur.segments.push(VecSeg.quad(c, { x: cx, y: cy }));
      lastQuadCx = c.x;
      lastQuadCy = c.y;
      hadQuad = true;
      hadCubic = false;
    } else if (upper === 'A') {
      const rx = sc.number();
      const ry = sc.number();
      const rot = sc.number();
      const large = sc.flag();
      const sweep = sc.flag();
      const x = sc.number();
      const y = sc.number();
      if (rx === null || ry === null || rot === null || large === null || sweep === null) break;
      if (x === null || y === null) break;
      const ex = rel ? cx + x : x;
      const ey = rel ? cy + y : y;
      const cubics = arcToCubics(cx, cy, rx, ry, rot, large, sweep, ex, ey);
      for (let i = 0; i < cubics.length; i++) cur.segments.push(cubics[i]);
      cx = ex;
      cy = ey;
      hadCubic = false;
      hadQuad = false;
    } else {
      break;
    }
  }
  flush();
  return out;
}

export function arcToCubics(
  x0: number,
  y0: number,
  rx: number,
  ry: number,
  xRotDeg: number,
  largeArc: boolean,
  sweep: boolean,
  x: number,
  y: number,
): CubicSeg[] {
  const lineFallback = (): CubicSeg[] => [
    VecSeg.cubic(
      { x: x0 + (x - x0) / 3, y: y0 + (y - y0) / 3 },
      { x: x0 + (2 * (x - x0)) / 3, y: y0 + (2 * (y - y0)) / 3 },
      { x, y },
    ),
  ];
  let rxa = Math.abs(rx);
  let rya = Math.abs(ry);
  if (rxa === 0 || rya === 0) return lineFallback();
  if (x0 === x && y0 === y) return [];

  const phi = (xRotDeg * Math.PI) / 180;
  const cosPhi = Math.cos(phi);
  const sinPhi = Math.sin(phi);
  const dx2 = (x0 - x) / 2;
  const dy2 = (y0 - y) / 2;
  const x1p = cosPhi * dx2 + sinPhi * dy2;
  const y1p = -sinPhi * dx2 + cosPhi * dy2;

  const lambda = (x1p * x1p) / (rxa * rxa) + (y1p * y1p) / (rya * rya);
  if (lambda > 1) {
    const s = Math.sqrt(lambda);
    rxa *= s;
    rya *= s;
  }

  const rx2 = rxa * rxa;
  const ry2 = rya * rya;
  const num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
  const den = rx2 * y1p * y1p + ry2 * x1p * x1p;
  if (den === 0) return lineFallback();
  let coef = Math.sqrt(Math.max(0, num / den));
  if (largeArc === sweep) coef = -coef;
  const cxp = coef * ((rxa * y1p) / rya);
  const cyp = coef * (-(rya * x1p) / rxa);
  const cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2;
  const cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2;

  const theta1 = Math.atan2((y1p - cyp) / rya, (x1p - cxp) / rxa);
  const theta2 = Math.atan2((-y1p - cyp) / rya, (-x1p - cxp) / rxa);
  let delta = theta2 - theta1;
  if (!sweep && delta > 0) delta -= 2 * Math.PI;
  else if (sweep && delta < 0) delta += 2 * Math.PI;

  const pieces = Math.max(1, Math.ceil(Math.abs(delta) / (Math.PI / 2)));
  const step = delta / pieces;
  const k = (4 / 3) * Math.tan(step / 4);
  const out: CubicSeg[] = [];
  let t = theta1;
  let px = x0;
  let py = y0;
  for (let i = 0; i < pieces; i++) {
    const t2 = t + step;
    const cos1 = Math.cos(t);
    const sin1 = Math.sin(t);
    const cos2 = Math.cos(t2);
    const sin2 = Math.sin(t2);
    const e1x = cx + rxa * cosPhi * cos1 - rya * sinPhi * sin1;
    const e1y = cy + rxa * sinPhi * cos1 + rya * cosPhi * sin1;
    const e2x = cx + rxa * cosPhi * cos2 - rya * sinPhi * sin2;
    const e2y = cy + rxa * sinPhi * cos2 + rya * cosPhi * sin2;
    const d1x = -rxa * cosPhi * sin1 - rya * sinPhi * cos1;
    const d1y = -rxa * sinPhi * sin1 + rya * cosPhi * cos1;
    const d2x = -rxa * cosPhi * sin2 - rya * sinPhi * cos2;
    const d2y = -rxa * sinPhi * sin2 + rya * cosPhi * cos2;
    const c1: VecPoint = { x: (i === 0 ? px : e1x) + k * d1x, y: (i === 0 ? py : e1y) + k * d1y };
    const c2: VecPoint = { x: e2x - k * d2x, y: e2y - k * d2y };
    const to: VecPoint = i === pieces - 1 ? { x, y } : { x: e2x, y: e2y };
    out.push(VecSeg.cubic(c1, c2, to));
    t = t2;
    px = e2x;
    py = e2y;
  }
  return out;
}
