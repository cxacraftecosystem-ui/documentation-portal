import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  binLabels,
  binOf,
  coverageHref,
  coverageParams,
  coverageQueryFromParams,
  fyLabel,
  metricValue,
  parentNote,
  quantileBreaks,
  registerFromCoverage
} from "@/components/map/coverage/coverageMath";
import type { CoverageResponse } from "@/components/map/coverage/coverageTypes";
import { decodePolyline, indiaRings } from "@/components/map/indiaGeometry";
import { decodeRing, decodeShapes } from "@/components/map/shapeGeometry";

/**
 * The coverage view's pure half, without a browser: the shape decoder, the quantile bins, the FY
 * labels, the URL round trip, the register the alias pickers are built from, and the rule that the
 * coverage code never reaches a mapping library.
 */

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/** The encoder `scripts/build_boundaries.py` writes rings with (`encode_web`), ported for the test. */
function encode(points: Array<[number, number]>): string {
  let out = "";
  let x = 0;
  let y = 0;
  const varint = (value: number) => {
    let zig = value < 0 ? ~(value << 1) : value << 1;
    for (;;) {
      const chunk = zig & 0x1f;
      zig >>= 5;
      out += ALPHABET[chunk | (zig ? 0x20 : 0)];
      if (!zig) return;
    }
  };
  varint(points.length);
  for (const [lon, lat] of points) {
    const qx = Math.round(lon * 1000);
    const qy = Math.round(lat * 1000);
    varint(qx - x);
    varint(qy - y);
    x = qx;
    y = qy;
  }
  return out;
}

test("a district ring round-trips through the outline's own decoder", () => {
  const ring: Array<[number, number]> = [
    [71.391, 25.751],
    [71.9, 25.2],
    [70.85, 24.95],
    [70.12, 25.6]
  ];
  const decoded = decodeRing(encode(ring));
  expect(decoded).toEqual([...ring, ring[0]]);
  expect(decodePolyline(encode(ring), false)[0].points).toEqual(ring);
});

test("exporting the decoder left the national outline exactly as it was", () => {
  const rings = indiaRings();
  expect(rings.length).toBe(307);
  expect(rings.flat().length).toBe(308);
  const lats = rings.flat(2).map((point) => point[1]);
  expect(Math.max(...lats)).toBeCloseTo(37.082, 3);
  expect(Math.min(...lats)).toBeCloseTo(6.756, 3);
});

test("the shapes file decodes, when the build has written it", () => {
  const path = join(__dirname, "../public/boundaries/district-shapes.json");
  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch {
    test.skip(true, "district-shapes.json not built");
    return;
  }
  const shapes = decodeShapes(JSON.parse(raw));
  expect(shapes.length).toBeGreaterThan(700);
  expect(new Set(shapes.map((shape) => shape.lgd)).size).toBe(shapes.length);
  for (const shape of shapes) {
    expect(shape.rings.length).toBeGreaterThan(0);
    for (const ring of shape.rings) expect(ring[0]).toEqual(ring[ring.length - 1]);
  }
  expect(raw.length).toBeLessThan(400_000);
});

test("quantile bins skip zero, collapse duplicates and label their ranges", () => {
  expect(quantileBreaks([0, 0, 0])).toEqual([]);
  const breaks = quantileBreaks([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  expect(breaks).toEqual([2, 4, 6, 8, 10]);
  expect(binOf(0, breaks)).toBe(-1);
  expect(binOf(1, breaks)).toBe(0);
  expect(binOf(5, breaks)).toBe(2);
  expect(binOf(99, breaks)).toBe(4);
  expect(binLabels(breaks)).toEqual(["1–2", "3–4", "5–6", "7–8", "9–10"]);
  expect(quantileBreaks([3, 3, 3, 7])).toEqual([3, 7]);
  expect(binLabels([1, 5])).toEqual(["1", "2–5"]);
});

test("financial-year labels run April to March", () => {
  expect(fyLabel(2025)).toBe("2025-26");
  expect(fyLabel(2099)).toBe("2099-00");
  expect(fyLabel(2009)).toBe("2009-10");
});

test("the filters round-trip through the URL", () => {
  const query = coverageQueryFromParams(
    new URLSearchParams("view=coverage&level=state&craftCode=ajrakh,bagru&fy=2025&fy=junk&scheme=DPD&stateLgd=8")
  );
  expect(query).toEqual({ level: "STATE", craftCode: ["ajrakh", "bagru"], scheme: ["DPD"], fy: [2025], stateLgd: 8 });
  expect(coverageParams(query)).toEqual({
    level: "STATE",
    craftCode: "ajrakh,bagru",
    scheme: "DPD",
    fy: "2025",
    stateLgd: "8"
  });
  expect(coverageHref(query, "artisans")).toBe(
    "/map?view=coverage&level=STATE&craftCode=ajrakh%2Cbagru&scheme=DPD&fy=2025&stateLgd=8&metric=artisans"
  );
  expect(coverageQueryFromParams(new URLSearchParams("view=coverage")).level).toBe("DISTRICT");
});

test("All records sums the record buckets, never crafts", () => {
  const counts = { workshops: 1, artisans: 2, crafts: 50, products: 3, tools: 4, media: 5 };
  expect(metricValue(counts, "workshops")).toBe(1);
  expect(metricValue(counts, "artisans")).toBe(2);
  expect(metricValue(counts, "all")).toBe(15);
  expect(parentNote("Barmer")).toBe("Shown inside Barmer (parent)");
  expect(parentNote(null)).toBeNull();
});

test("the alias pickers' register is every coded district, from units and gaps together", () => {
  const body = {
    units: [
      { stateLgd: 8, districtLgd: 90, state: "Rajasthan", district: "Barmer" },
      { stateLgd: 8, districtLgd: null, state: "Rajasthan", district: null }
    ],
    gaps: {
      districtsWithoutWorkshop: {
        count: 2,
        byState: [
          { stateLgd: 8, state: "Rajasthan", total: 3, without: [{ lgd: 110, name: "Jaipur" }] },
          { stateLgd: 37, state: "Ladakh", total: 1, without: [{ lgd: null, name: "Nubra" }] }
        ]
      },
      statesWithoutWorkshop: [{ lgd: 37, name: "Ladakh" }]
    }
  } as unknown as CoverageResponse;
  expect(registerFromCoverage(body)).toEqual([
    { lgd: 37, name: "Ladakh", districts: [] },
    {
      lgd: 8,
      name: "Rajasthan",
      districts: [
        { lgd: 90, name: "Barmer" },
        { lgd: 110, name: "Jaipur" }
      ]
    }
  ]);
});

test("the coverage view never reaches a mapping library or a tile server", () => {
  const dir = join(__dirname, "../components/map/coverage");
  const files = [
    ...readdirSync(dir).map((name) => join(dir, name)),
    join(__dirname, "../components/map/shapeGeometry.ts")
  ];
  for (const file of files) {
    const source = readFileSync(file, "utf8");
    expect(source, file).not.toMatch(/maplibre|maptiler|googleapis|tile\.openstreetmap|mapbox/i);
    expect(source, file).not.toMatch(/<select/);
  }
});

test("the map page offers Places | Coverage and keeps the depiction caption", () => {
  const page = readFileSync(join(__dirname, "../app/(protected)/map/page.tsx"), "utf8");
  expect(page).toContain('searchParams.get("view") === "coverage"');
  expect(page).toContain('href: "/map?view=coverage"');
  const view = readFileSync(join(__dirname, "../components/map/coverage/CoverageView.tsx"), "utf8");
  expect(view).toContain(
    "External boundaries of India as depicted by Survey of India. District boundaries are indicative."
  );
  const map = readFileSync(join(__dirname, "../components/map/coverage/CoverageMap.tsx"), "utf8");
  expect(map).toContain("clipPath={`url(#${clipId})`}");
  expect(map).toContain("aria-hidden");
});
