"use client";

/**
 * The repository's map of India. Reusable: it knows how to draw points, and nothing about where
 * they came from.
 *
 * IT IS A GRAPHIC, AND IT IS MARKED AS ONE. The whole `<svg>` is `aria-hidden`, contains nothing
 * focusable, and is announced to nobody. That is deliberate rather than lazy — an SVG full of
 * tabbable circles is a screen reader reading out "button, button, button" with no way to compare
 * anything. Everything this picture says is said again as text by `MapPlaceList`, which is a real
 * list of places with real counts and real links. A keyboard or screen-reader user loses nothing
 * here; they get the better of the two interfaces.
 *
 * NO MAPPING LIBRARY. There is no basemap, no tiles, no zoom and no pan — this is a static outline
 * and a few dozen pins, and `maplibre-gl` (already in the bundle for the location PICKER, where a
 * real basemap earns its weight) is a quarter of a megabyte plus tile requests to draw it. The
 * whole geometry here is 18 KiB of string and one `<path>`.
 *
 * NOTHING RE-RENDERS ON A POINTER MOVE except the small hover layer. The outline is one memoised
 * element over a path string built once; the pin layout is memoised on the points. Hovering
 * changes one piece of state and repaints a label, which is not a per-frame cost — there is no
 * animation loop in this file at all.
 */

import { memo, useMemo } from "react";

import { layoutPins, type PlacedPin } from "@/components/map/layout";
import { indiaOutlinePath, unitsPerKilometre, VIEW_BOX } from "@/components/map/projection";
import type { MapPoint } from "@/components/map/types";

export type IndiaMapProps = {
  points: MapPoint[];
  /** Keys of points holding the focused record, drawn with a ring so they can be found at a glance. */
  focusKeys?: string[];
  selectedKey?: string | null;
  hoveredKey?: string | null;
  onSelect?: (key: string) => void;
  onHover?: (key: string | null) => void;
  className?: string;
};

/** Built once from a path string that is itself built once — this never has a reason to re-render. */
const Coastline = memo(function Coastline() {
  return (
    <path
      d={indiaOutlinePath()}
      fillRule="evenodd"
      className="fill-purple-50 stroke-purple-200"
      // A hairline stroke is what makes Lakshadweep visible. Those islands are a few kilometres
      // across — genuinely under a pixel at this scale — so a fill alone would render the territory
      // as nothing at all. The stroke shows them as the dots they are without inventing any area.
      strokeWidth={1.6}
      strokeLinejoin="round"
      vectorEffect="non-scaling-stroke"
    />
  );
});

export function IndiaMap({
  points,
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

  return (
    <svg
      viewBox={`0 0 ${VIEW_BOX.width} ${VIEW_BOX.height}`}
      className={`h-auto w-full max-w-full ${className}`}
      role="presentation"
      aria-hidden="true"
      focusable="false"
    >
      <Coastline />

      {/* Uncertainty first, under everything: a halo is context for its pin, not a mark of its own. */}
      {pins
        .filter((pin) => pin.uncertainty > 4)
        .map((pin) => (
          <circle
            key={`halo-${pin.point.key}`}
            cx={pin.anchorX}
            cy={pin.anchorY}
            r={pin.uncertainty}
            className="fill-purple-700/[0.06] stroke-purple-300"
            strokeWidth={1}
            strokeDasharray="4 5"
          />
        ))}

      {/* Leader lines tie a displaced pin back to where the place really is. */}
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

      {active ? <HoverLabel pin={active} /> : null}
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
      // Pointer-only. There is no tabIndex and no role: focusable content inside an aria-hidden
      // subtree is unreachable by assistive technology but still lands in the tab order, which is
      // the worst of both. The list beside this map is the keyboard path.
      className="cursor-pointer"
      onClick={() => onSelect?.(point.key)}
      onPointerEnter={() => onHover?.(point.key)}
      onPointerLeave={() => onHover?.(null)}
    >
      {/* An invisible target a little larger than the mark, so a 9-unit pin is not a 9-unit hit area. */}
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
        // A measured fix is drawn as a target rather than a disc, so the two layers are told apart
        // by SHAPE. Purple is the only action colour this product has, and hue is therefore not
        // available to carry the distinction.
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

      {/* The count belongs ON the pin, on EVERY pin: it is the one mark that stops a place holding
          nine records from reading exactly like a place holding one. The size follows the pin and
          the number of digits, so "317" fits a big pin and "1" does not rattle around in a small
          one. */}
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

/** The place name under the pointer. Drawn in SVG so it cannot be clipped by the map's own box. */
function HoverLabel({ pin }: { pin: PlacedPin }) {
  const width = Math.max(96, pin.point.label.length * 11 + 28);
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
        {pin.point.label}
      </text>
    </g>
  );
}

/** Without one, nobody can tell whether two pins are 20 km apart or 200. */
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
