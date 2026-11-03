import { decodePolyline, type Ring } from "@/components/map/indiaGeometry";
import { project } from "@/components/map/projection";

export type DistrictShape = {
  lgd: number;
  stateLgd: number;
  rings: Ring[];
  centroid: [number, number];
};

export type ShapesFile = {
  version: string;
  source: string;
  asOf: string;
  scale: number;
  districts: Array<{ lgd: number; stateLgd: number; rings: string[]; centroid: [number, number] }>;
};

export const SHAPES_ASSET = "/boundaries/district-shapes.json";

export function decodeRing(encoded: string): Ring {
  const [record] = decodePolyline(encoded, false);
  if (!record || !record.points.length) return [];
  return [...record.points, record.points[0]];
}

export function decodeShapes(file: ShapesFile): DistrictShape[] {
  return file.districts.map((row) => ({
    lgd: row.lgd,
    stateLgd: row.stateLgd,
    rings: row.rings.map(decodeRing).filter((ring) => ring.length >= 4),
    centroid: row.centroid
  }));
}

export function shapePath(shape: DistrictShape): string {
  const parts: string[] = [];
  for (const ring of shape.rings) {
    ring.forEach(([longitude, latitude], index) => {
      const { x, y } = project(longitude, latitude);
      parts.push(`${index === 0 ? "M" : "L"}${x.toFixed(1)} ${y.toFixed(1)}`);
    });
    parts.push("Z");
  }
  return parts.join("");
}

let pending: Promise<DistrictShape[]> | null = null;

export function loadShapes(): Promise<DistrictShape[]> {
  if (pending) return pending;
  pending = fetch(SHAPES_ASSET, { cache: "force-cache" })
    .then((response) => {
      if (!response.ok) throw new Error(`${SHAPES_ASSET} returned ${response.status}`);
      return response.json() as Promise<ShapesFile>;
    })
    .then(decodeShapes)
    .catch((cause: unknown) => {
      pending = null;
      throw cause;
    });
  return pending;
}
