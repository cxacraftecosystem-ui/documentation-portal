"use client";

import { memo, useEffect, useId, useMemo, useState } from "react";

import { loadBorders, type BorderLine } from "@/components/map/borderGeometry";
import { layoutPins, type PlacedPin } from "@/components/map/layout";
import {
  bordersToPath,
  indiaOutlinePath,
  unitsPerKilometre,
  VIEW_BOX
} from "@/components/map/projection";
import type { AdminLevel, MapPoint } from "@/components/map/types";

export type IndiaMapProps = {
  points: MapPoint[];
  level?: AdminLevel;
  focusKeys?: string[];
  selectedKey?: string | null;
  hoveredKey?: string | null;
  onSelect?: (key: string) => void;
  onHover?: (key: string | null) => void;
  className?: string;
};

const Coastline = memo(function Coastline() {
  return (
    <path
      d={indiaOutlinePath()}
      fillRule="evenodd"
      className="fill-purple-50 stroke-purple-200"
      strokeWidth={1.6}
      strokeLinejoin="round"
      vectorEffect="non-scaling-stroke"
    />
  );
});

function useBorders(level: AdminLevel): { state: BorderLine[]; district: BorderLine[] } {
  const [state, setState] = useState<BorderLine[]>([]);
  const [district, setDistrict] = useState<BorderLine[]>([]);

  const wantsState = level === "STATE" || level === "DISTRICT";
  const wantsDistrict = level === "DISTRICT";

  useEffect(() => {
    if (!wantsState) return;
    let live = true;
    loadBorders("state")
      .then((lines) => {
        if (live) setState(lines);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [wantsState]);

  useEffect(() => {
    if (!wantsDistrict) return;
    let live = true;
    loadBorders("district")
      .then((lines) => {
        if (live) setDistrict(lines);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [wantsDistrict]);

  return { state: wantsState ? state : [], district: wantsDistrict ? district : [] };
}

export function IndiaMap({
  points,
  level = "DISTRICT",
  focusKeys = [],
  selectedKey = null,
  hoveredKey = null,
  onSelect,
  onHover,
  className = ""
}: IndiaMapProps) {
  const pins = useMemo(() => layoutPins(points, unitsPerKilometre()), [points]);
  const focused = useMemo(() => new Set(focusKeys), [focusKeys]);
  const active = pins.find((pin) => pin.point.key === (hoveredKey ?? selectedKey)) ?? null;

  const ordinals = useMemo(
    () => new Map(points.map((point, index) => [point.key, index + 1])),
    [points]
  );

  const borders = useBorders(level);
  const statePath = useMemo(() => bordersToPath(borders.state), [borders.state]);
  const districtPath = useMemo(() => bordersToPath(borders.district), [borders.district]);
  const clipId = `${useId()}-india`;

  return (
    <svg
      viewBox={`0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`}
      className={`h-auto w-full max-w-full ${className}`}
      role="presentation"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <clipPath id={clipId}>
          <path d={indiaOutlinePath()} clipRule="evenodd" />
        </clipPath>
      </defs>

      <Coastline />

      <g clipPath={`url(#${clipId})`} fill="none" strokeLinejoin="round" strokeLinecap="round">
        {districtPath ? (
          <path d={districtPath} className="stroke-ink-300" strokeWidth={0.6} opacity={0.7} />
        ) : null}
        {statePath ? <path d={statePath} className="stroke-ink-500" strokeWidth={1.2} /> : null}
      </g>

      {pins
        .filter((pin) => pin.uncertainty > 4)
        .map((pin) => (
          <circle
            key={`halo-${pin.point.key}`}
            cx={pin.anchorX}
            cy={pin.anchorY}
            r={pin.uncertainty}
            className="fill-purple-700/6 stroke-purple-300"
            strokeWidth={1}
            strokeDasharray="4 5"
          />
        ))}

      {pins
        .filter((pin) => pin.displaced)
        .map((pin) => (
          <g key={`leader-${pin.point.key}`}>
            <line
              x1={pin.anchorX}
              y1={pin.anchorY}
              x2={pin.x}
              y2={pin.y}
              className="stroke-purple-400"
              strokeWidth={1.2}
            />
            <circle cx={pin.anchorX} cy={pin.anchorY} r={2} className="fill-purple-500" />
          </g>
        ))}

      {pins.map((pin) => (
        <Pin
          key={pin.point.key}
          pin={pin}
          isFocused={focused.has(pin.point.key)}
          isActive={pin.point.key === selectedKey || pin.point.key === hoveredKey}
          onSelect={onSelect}
          onHover={onHover}
        />
      ))}

      {active ? <HoverLabel pin={active} ordinal={ordinals.get(active.point.key)} /> : null}
      <ScaleBar />
    </svg>
  );
}

function Pin({
  pin,
  isFocused,
  isActive,
  onSelect,
  onHover
}: {
  pin: PlacedPin;
  isFocused: boolean;
  isActive: boolean;
  onSelect?: (key: string) => void;
  onHover?: (key: string | null) => void;
}) {
  const { point, x, y, radius } = pin;
  const measured = point.layer === "CAPTURE";

  return (
    <g
      className="cursor-pointer"
      onClick={() => onSelect?.(point.key)}
      onPointerEnter={() => onHover?.(point.key)}
      onPointerLeave={() => onHover?.(null)}
    >
      <circle cx={x} cy={y} r={radius + 8} fill="transparent" />

      {isFocused ? (
        <circle
          cx={x}
          cy={y}
          r={radius + 7}
          className="fill-none stroke-purple-700"
          strokeWidth={2.5}
          strokeDasharray="5 4"
        />
      ) : null}

      {measured ? (
        <>
          <circle
            cx={x}
            cy={y}
            r={radius}
            className={`fill-white stroke-purple-700 ${isActive ? "opacity-100" : "opacity-95"}`}
            strokeWidth={4}
          />
          <circle cx={x} cy={y} r={Math.max(3, radius * 0.32)} className="fill-purple-700" />
        </>
      ) : (
        <circle
          cx={x}
          cy={y}
          r={radius}
          className={`fill-purple-700 stroke-white ${isActive ? "opacity-100" : "opacity-90"}`}
          strokeWidth={2}
        />
      )}

      <text
        x={x}
        y={y}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize={radius * (point.total >= 100 ? 0.72 : point.total >= 10 ? 0.9 : 1.05)}
        className={`pointer-events-none font-display font-bold ${
          measured ? "fill-purple-900" : "fill-white"
        }`}
        style={measured ? { paintOrder: "stroke", stroke: "white", strokeWidth: 4 } : undefined}
      >
        {point.total}
      </text>
    </g>
  );
}

function HoverLabel({ pin, ordinal }: { pin: PlacedPin; ordinal?: number }) {
  const text = ordinal ? `${ordinal} · ${pin.point.label}` : pin.point.label;
  const width = Math.max(96, text.length * 11 + 28);
  const left = Math.min(Math.max(pin.x - width / 2, 4), VIEW_BOX.width - width - 4);
  const above = pin.y - pin.radius - 40 > 0;
  const top = above ? pin.y - pin.radius - 40 : pin.y + pin.radius + 10;

  return (
    <g className="pointer-events-none">
      <rect x={left} y={top} width={width} height={31} rx={8} className="fill-ink-900" />
      <text
        x={left + width / 2}
        y={top + 16}
        textAnchor="middle"
        dominantBaseline="central"
        className="fill-white font-sans text-[15px] font-medium"
      >
        {text}
      </text>
    </g>
  );
}

function ScaleBar() {
  const kilometres = 500;
  const length = kilometres * unitsPerKilometre();
  const x = 26;
  const y = VIEW_BOX.height - 34;
  return (
    <g className="pointer-events-none">
      <line x1={x} y1={y} x2={x + length} y2={y} className="stroke-ink-500" strokeWidth={2} />
      <line x1={x} y1={y - 5} x2={x} y2={y + 5} className="stroke-ink-500" strokeWidth={2} />
      <line
        x1={x + length}
        y1={y - 5}
        x2={x + length}
        y2={y + 5}
        className="stroke-ink-500"
        strokeWidth={2}
      />
      <text x={x} y={y + 22} className="fill-ink-500 font-sans text-[15px]">
        {kilometres} km
      </text>
    </g>
  );
}
