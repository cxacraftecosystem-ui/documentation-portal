import { INDIA_BOUNDS, indiaRings, type Polygon } from "@/components/map/indiaGeometry";

const VIEW_WIDTH = 1000;

const PADDING = 24;

const MID_LATITUDE = (INDIA_BOUNDS.minLatitude + INDIA_BOUNDS.maxLatitude) / 2;
const LONGITUDE_SCALE = Math.cos((MID_LATITUDE * Math.PI) / 180);

const SPAN_X = (INDIA_BOUNDS.maxLongitude - INDIA_BOUNDS.minLongitude) * LONGITUDE_SCALE;
const SPAN_Y = INDIA_BOUNDS.maxLatitude - INDIA_BOUNDS.minLatitude;

const UNITS_PER_DEGREE = (VIEW_WIDTH - PADDING * 2) / SPAN_X;
const VIEW_HEIGHT = SPAN_Y * UNITS_PER_DEGREE + PADDING * 2;

export const VIEW_BOX = { width: VIEW_WIDTH, height: VIEW_HEIGHT } as const;

export function project(longitude: number, latitude: number): { x: number; y: number } {
  return {
    x: PADDING + (longitude - INDIA_BOUNDS.minLongitude) * LONGITUDE_SCALE * UNITS_PER_DEGREE,
    y: PADDING + (INDIA_BOUNDS.maxLatitude - latitude) * UNITS_PER_DEGREE
  };
}

export function unitsPerKilometre(): number {
  return UNITS_PER_DEGREE / 111.32;
}

export function bordersToPath(lines: Array<Array<[number, number]>>): string {
  const parts: string[] = [];
  for (const line of lines) {
    let out = "";
    let previousX = Number.NaN;
    let previousY = Number.NaN;
    let emitted = 0;
    for (const [longitude, latitude] of line) {
      const { x, y } = project(longitude, latitude);
      const rx = Math.round(x * 10) / 10;
      const ry = Math.round(y * 10) / 10;
      if (emitted > 0 && rx === previousX && ry === previousY) continue;
      out += `${emitted === 0 ? "M" : "L"}${rx} ${ry}`;
      previousX = rx;
      previousY = ry;
      emitted += 1;
    }
    if (emitted >= 2) parts.push(out);
  }
  return parts.join("");
}

let outline: string | null = null;

export function indiaOutlinePath(): string {
  if (outline) return outline;
  const parts: string[] = [];
  for (const polygon of indiaRings() as Polygon[]) {
    for (const ring of polygon) {
      let command = "M";
      for (const [longitude, latitude] of ring) {
        const { x, y } = project(longitude, latitude);
        parts.push(`${command}${x.toFixed(1)} ${y.toFixed(1)}`);
        command = "L";
      }
      parts.push("Z");
    }
  }
  outline = parts.join("");
  return outline;
}
