/**
 * District polygons for the coverage map's choropleth, loaded only when the Coverage view opens.
 *
 * `public/boundaries/district-shapes.json` is written by `scripts/build_boundaries.py --shapes`:
 * every district the boundary data has, keyed by its LGD code, each ring one record in the same
 * polyline scheme as the national outline and read here with that outline's own decoder
 * (`decodePolyline`), never a third copy. The polygons are clipped to the verified national outline
 * at build time, and the map clips them to it again in the browser — the outline, not this file, is
 * what shows India's extent.
 *
 * Fetched once per page load and kept; the Places view never asks for it.
 */

import { decodePolyline, type Ring } from "@/components/map/indiaGeometry";
import { project } from "@/components/map/projection";

export type DistrictShape = {
  lgd: number;
  stateLgd: number;
  /** Closed rings of [longitude, latitude]. Holes are ordinary rings, drawn even-odd. */
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

/** One encoded ring back to closed coordinates. */
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

/** A shape's rings as one SVG path through the shared projection. */
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
