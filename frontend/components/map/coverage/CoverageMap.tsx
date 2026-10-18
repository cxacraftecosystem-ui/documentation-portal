"use client";

/**
 * The coverage choropleth: districts filled by how much documented work each holds.
 *
 * WHAT SHOWS INDIA'S EXTENT. The verified national outline (`indiaGeometry.ts`, the official
 * depiction) is drawn first as a neutral base fill, and every district fill is clipped to it with an
 * SVG `clipPath`. So a part of Jammu & Kashmir or Ladakh the district data has no polygon for still
 * reads as India, in the "no record" tone — never as blank. State borders sit on top. No tiles, no
 * mapping library: the same hand-drawn SVG and the same `projection.ts` as the Places view.
 *
 * A district with no polygon of its own is drawn inside the parent it was carved from, so the
 * parent's fill carries both and gets a dashed outline; the legend and the unit panel say so.
 *
 * `aria-hidden`, like `IndiaMap`: `CoverageTable` is the text version of everything drawn here.
 */

import { memo, useEffect, useId, useMemo, useState } from "react";

import { loadBorders } from "@/components/map/borderGeometry";
import { binOf } from "@/components/map/coverage/coverageMath";
import type { CoverageMarker } from "@/components/map/coverage/coverageTypes";
import { bordersToPath, indiaOutlinePath, project, VIEW_BOX } from "@/components/map/projection";
import { shapePath, type DistrictShape } from "@/components/map/shapeGeometry";

/** Five sequential steps, one hue, light to dark — reversed in dark mode so "more" is brighter. */
export const BIN_CLASSES = [
  "fill-purple-300 dark:fill-purple-800",
  "fill-purple-400 dark:fill-purple-700",
  "fill-purple-500 dark:fill-purple-600",
  "fill-purple-700 dark:fill-purple-400",
  "fill-purple-900 dark:fill-purple-200"
] as const;

/** Markers closer than this (user units) are drawn as one cluster with a count. */
const CLUSTER_CELL = 22;

export type PolygonFill = {
  /** The number the polygon is coloured by (its own unit plus any child drawn inside it). */
  value: number;
  /** True when a district without its own polygon is drawn inside this one. */
  hostsChild: boolean;
};

export type CoverageMapProps = {
  shapes: DistrictShape[];
  fills: Map<number, PolygonFill>;
  breaks: number[];
  /** Polygons (by LGD) whose districts have no workshop: hatched. */
  noWorkshop: Set<number>;
  markers: CoverageMarker[];
  selectedLgd: number | null;
  /** Narrow the drawing to one state's districts. */
  stateLgd: number | null;
  names: Map<number, string>;
  onSelectPolygon: (lgd: number) => void;
};

type Cluster = { x: number; y: number; markers: CoverageMarker[] };

function clusterMarkers(markers: CoverageMarker[]): Cluster[] {
  const cells = new Map<string, Cluster>();
  for (const marker of markers) {
    const { x, y } = project(marker.lon, marker.lat);
    const key = `${Math.floor(x / CLUSTER_CELL)}:${Math.floor(y / CLUSTER_CELL)}`;
    const cell = cells.get(key);
    if (cell) {
      cell.markers.push(marker);
      cell.x = (cell.x * (cell.markers.length - 1) + x) / cell.markers.length;
      cell.y = (cell.y * (cell.markers.length - 1) + y) / cell.markers.length;
    } else {
      cells.set(key, { x, y, markers: [marker] });
    }
  }
  return [...cells.values()];
}

function stateBox(shapes: DistrictShape[], stateLgd: number | null): string {
  const full = `0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`;
  if (stateLgd === null) return full;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const shape of shapes) {
    if (shape.stateLgd !== stateLgd) continue;
    for (const ring of shape.rings) {
      for (const [longitude, latitude] of ring) {
        const { x, y } = project(longitude, latitude);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
  }
  if (!Number.isFinite(minX)) return full;
  const pad = Math.max(maxX - minX, maxY - minY) * 0.08 + 8;
  return `${(minX - pad).toFixed(1)} ${(minY - pad).toFixed(1)} ${(maxX - minX + pad * 2).toFixed(1)} ${(
    maxY - minY + pad * 2
  ).toFixed(1)}`;
}

function CoverageMapImpl({
  shapes,
  fills,
  breaks,
  noWorkshop,
  markers,
  selectedLgd,
  stateLgd,
  names,
  onSelectPolygon
}: CoverageMapProps) {
  const id = useId().replace(/:/g, "");
  const clipId = `cov-clip-${id}`;
  const hatchId = `cov-hatch-${id}`;
  const outline = useMemo(() => indiaOutlinePath(), []);
  const [statePath, setStatePath] = useState<string | null>(null);
  const [hovered, setHovered] = useState<number | null>(null);

  useEffect(() => {
    let live = true;
    loadBorders("state")
      .then((lines) => {
        if (live) setStatePath(bordersToPath(lines));
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  const visible = useMemo(
    () => (stateLgd === null ? shapes : shapes.filter((shape) => shape.stateLgd === stateLgd)),
    [shapes, stateLgd]
  );
  const paths = useMemo(() => new Map(visible.map((shape) => [shape.lgd, shapePath(shape)])), [visible]);
  const hatchPath = useMemo(
    () =>
      visible
        .filter((shape) => noWorkshop.has(shape.lgd))
        .map((shape) => paths.get(shape.lgd) ?? "")
        .join(""),
    [visible, noWorkshop, paths]
  );
  const viewBox = useMemo(() => stateBox(shapes, stateLgd), [shapes, stateLgd]);
  const clusters = useMemo(() => clusterMarkers(markers), [markers]);
  const hoveredShape = hovered !== null ? visible.find((shape) => shape.lgd === hovered) : undefined;

  return (
    <svg viewBox={viewBox} className="block h-auto w-full" aria-hidden data-testid="coverage-map">
      <defs>
        <clipPath id={clipId}>
          <path d={outline} clipRule="evenodd" />
        </clipPath>
        <pattern id={hatchId} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1={0} y1={0} x2={0} y2={6} className="stroke-ink-300" strokeWidth={1.2} />
        </pattern>
      </defs>

      {/* The base: all of India, in the "no record" tone. */}
      <path d={outline} fillRule="evenodd" className="fill-field-100 stroke-line-200" strokeWidth={1} />

      <g clipPath={`url(#${clipId})`}>
        {visible.map((shape) => {
          const fill = fills.get(shape.lgd);
          const bin = fill ? binOf(fill.value, breaks) : -1;
          const selected = selectedLgd === shape.lgd;
          return (
            <path
              key={shape.lgd}
              d={paths.get(shape.lgd)}
              fillRule="evenodd"
              data-lgd={shape.lgd}
              className={`cursor-pointer transition-opacity ${
                bin >= 0 ? BIN_CLASSES[bin] : "fill-transparent"
              } ${selected ? "stroke-ink-900" : fill?.hostsChild ? "stroke-ink-500" : "stroke-line-200"} hover:opacity-80`}
              strokeWidth={selected ? 2 : fill?.hostsChild ? 1 : 0.5}
              strokeDasharray={fill?.hostsChild && !selected ? "3 2" : undefined}
              onClick={() => onSelectPolygon(shape.lgd)}
              onMouseEnter={() => setHovered(shape.lgd)}
              onMouseLeave={() => setHovered((current) => (current === shape.lgd ? null : current))}
            />
          );
        })}
        {hatchPath ? (
          <path d={hatchPath} fillRule="evenodd" fill={`url(#${hatchId})`} className="pointer-events-none" />
        ) : null}
      </g>

      {statePath ? (
        <path d={statePath} className="pointer-events-none fill-none stroke-ink-500" strokeWidth={1.1} />
      ) : null}
      <path d={outline} fillRule="evenodd" className="pointer-events-none fill-none stroke-ink-700" strokeWidth={1.2} />

      {clusters.map((cluster) => {
        const many = cluster.markers.length > 1;
        const first = cluster.markers[0];
        return (
          <g
            key={`${first.kind}-${first.id}`}
            className="cursor-pointer"
            onClick={() => {
              if (first.districtLgd !== null) onSelectPolygon(first.districtLgd);
            }}
          >
            <circle
              cx={cluster.x}
              cy={cluster.y}
              r={many ? 9 : 4.5}
              className="fill-white stroke-purple-700 dark:fill-purple-950 dark:stroke-purple-300"
              strokeWidth={2}
            />
            {many ? (
              <text
                x={cluster.x}
                y={cluster.y + 3.5}
                textAnchor="middle"
                className="pointer-events-none fill-purple-800 font-sans text-[10px] font-semibold dark:fill-purple-200"
              >
                {cluster.markers.length}
              </text>
            ) : null}
          </g>
        );
      })}

      {hoveredShape ? (
        <HoverLabel shape={hoveredShape} name={names.get(hoveredShape.lgd) ?? ""} value={fills.get(hoveredShape.lgd)?.value ?? 0} />
      ) : null}
    </svg>
  );
}

function HoverLabel({ shape, name, value }: { shape: DistrictShape; name: string; value: number }) {
  if (!name) return null;
  const { x, y } = project(shape.centroid[0], shape.centroid[1]);
  const text = `${name} · ${value}`;
  const width = text.length * 7.4 + 16;
  return (
    <g className="pointer-events-none">
      <rect x={x - width / 2} y={y - 34} width={width} height={24} rx={6} className="fill-ink-900" />
      <text x={x} y={y - 18} textAnchor="middle" className="fill-card font-sans text-[12px] font-medium">
        {text}
      </text>
    </g>
  );
}

export const CoverageMap = memo(CoverageMapImpl);
