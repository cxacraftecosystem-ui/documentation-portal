import { project, VIEW_BOX } from "@/components/map/projection";
import type { MapPoint } from "@/components/map/types";

const MIN_RADIUS = 12.5;
const MAX_RADIUS = 26;

const GAP = 3;

const MAX_SHIFT = 46;

const MAX_PASSES = 60;

export type PlacedPin = {
  point: MapPoint;
  x: number;
  y: number;
  anchorX: number;
  anchorY: number;
  radius: number;
  displaced: boolean;
  uncertainty: number;
};

const UNCERTAINTY_KM: Record<MapPoint["precision"], number> = {
  SUBJECT_PIN: 0,
  MEASURED: 0,
  TOWN: 0,
  DISTRICT: 45,
  STATE: 220,
  NATION: 0
};

export function pinRadius(total: number, busiest: number): number {
  if (busiest <= 0) return MIN_RADIUS;
  const share = Math.sqrt(total) / Math.sqrt(busiest);
  return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * share;
}

export function layoutPins(points: MapPoint[], unitsPerKilometre: number): PlacedPin[] {
  const busiest = points.reduce((most, point) => Math.max(most, point.total), 0);

  const ordered = [...points].sort((a, b) => b.total - a.total || a.key.localeCompare(b.key));

  const placed: PlacedPin[] = [];
  for (const point of ordered) {
    const { x: anchorX, y: anchorY } = project(point.longitude, point.latitude);
    const radius = pinRadius(point.total, busiest);
    let x = anchorX;
    let y = anchorY;

    for (let pass = 0; pass < MAX_PASSES; pass += 1) {
      const clash = placed.find((other) => {
        const needed = other.radius + radius + GAP;
        return Math.hypot(other.x - x, other.y - y) < needed;
      });
      if (!clash) break;

      const needed = clash.radius + radius + GAP;
      let dx = x - clash.x;
      let dy = y - clash.y;
      let distance = Math.hypot(dx, dy);
      if (distance < 0.001) {
        const angle = (hash(point.key) % 360) * (Math.PI / 180);
        dx = Math.cos(angle);
        dy = Math.sin(angle);
        distance = 1;
      }
      const push = (needed - distance) / distance;
      x += dx * push;
      y += dy * push;

      const drift = Math.hypot(x - anchorX, y - anchorY);
      if (drift > MAX_SHIFT) {
        x = anchorX + ((x - anchorX) / drift) * MAX_SHIFT;
        y = anchorY + ((y - anchorY) / drift) * MAX_SHIFT;
        break;
      }
    }

    x = clamp(x, radius, VIEW_BOX.width - radius);
    y = clamp(y, radius, VIEW_BOX.height - radius);

    placed.push({
      point,
      x,
      y,
      anchorX,
      anchorY,
      radius,
      displaced: Math.hypot(x - anchorX, y - anchorY) > 0.75,
      uncertainty: UNCERTAINTY_KM[point.precision] * unitsPerKilometre
    });
  }

  return placed.sort((a, b) => b.radius - a.radius);
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function hash(text: string): number {
  let value = 0;
  for (let index = 0; index < text.length; index += 1) {
    value = (value * 31 + text.charCodeAt(index)) >>> 0;
  }
  return value;
}
