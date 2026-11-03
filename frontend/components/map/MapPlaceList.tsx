"use client";

import { ChevronDown, Crosshair, MapPin } from "lucide-react";

import {
  countLabel,
  type AdminLevel,
  type MapPoint,
  type MapPointChild,
  type MapPrecision,
  type UnplacedPlace
} from "@/components/map/types";
import type { RecordType } from "@/components/search/SearchFilters";

const PRECISION_NOTE: Record<MapPrecision, string> = {
  SUBJECT_PIN: "pin dropped on the subject's own place",
  MEASURED: "GPS fix taken while recording",
  TOWN: "town located from the typed place name",
  DISTRICT: "district position worked out from pins inside it",
  STATE: "state only — drawn at the state's seat",
  NATION: "every placed record, folded into one point"
};

const PRECISION_BADGE: Record<MapPrecision, string> = {
  SUBJECT_PIN: "Pinned",
  MEASURED: "Measured",
  TOWN: "Town",
  DISTRICT: "District",
  STATE: "State",
  NATION: "Nation"
};

const CHILD_NOUN: Record<AdminLevel, [singular: string, plural: string]> = {
  NATION: ["place", "places"],
  STATE: ["state", "states"],
  DISTRICT: ["district", "districts"]
};

function precisionBadge(precision: MapPrecision): string {
  return PRECISION_BADGE[precision] ?? String(precision);
}

function precisionNote(precision: MapPrecision): string {
  return PRECISION_NOTE[precision] ?? "position of unstated precision";
}

function childSummary(point: MapPoint, childLevel: AdminLevel | null | undefined): string {
  const count = point.children?.length ?? 0;
  if (point.layer === "CAPTURE") {
    return `${count} recording ${count === 1 ? "place" : "places"} here`;
  }
  const [singular, plural] = CHILD_NOUN[childLevel ?? "DISTRICT"] ?? CHILD_NOUN.DISTRICT;
  return `${count} ${count === 1 ? singular : plural} inside this point`;
}

export function MapPlaceList({
  points,
  unplaced,
  selectedKey,
  focusKeys = [],
  hoveredKey = null,
  childLevel,
  expandedKeys,
  flashKey = null,
  registerRow,
  onSelect,
  onHover,
  onToggleExpanded,
  onDrillDown
}: {
  points: MapPoint[];
  unplaced: UnplacedPlace[];
  selectedKey: string | null;
  focusKeys?: string[];
  hoveredKey?: string | null;
  childLevel?: AdminLevel | null;
  expandedKeys: ReadonlySet<string>;
  flashKey?: string | null;
  registerRow?: (key: string) => (node: HTMLElement | null) => void;
  onSelect: (key: string) => void;
  onHover?: (key: string | null) => void;
  onToggleExpanded: (key: string) => void;
  onDrillDown: (child: MapPointChild) => void;
}) {
  const focused = new Set(focusKeys);

  return (
    <div className="grid gap-3">
      <ul className="grid grid-cols-[minmax(0,1fr)] gap-1.5">
        {points.map((point, index) => {
          const isSelected = point.key === selectedKey;
          const isHovered = point.key === hoveredKey;
          const children = point.children ?? [];
          const isExpanded = expandedKeys.has(point.key);
          const types = (Object.entries(point.counts) as Array<[RecordType, number]>).filter(
            ([, count]) => count > 0
          );
          const ordinal = index + 1;
          const panelId = `map-place-children-${ordinal}`;

          return (
            <li key={point.key} className="min-w-0">
              <div
                ref={registerRow?.(point.key)}
                data-flash={flashKey === point.key ? "true" : undefined}
                className={`fr-flash-row min-w-0 rounded-md border transition-colors ${
                  isSelected
                    ? "border-purple-700 bg-purple-50"
                    : isHovered
                      ? "border-purple-300 bg-purple-50/60"
                      : "border-line-200 bg-card"
                }`}
              >
                <button
                  type="button"
                  aria-pressed={isSelected}
                  onClick={() => onSelect(point.key)}
                  onPointerEnter={() => onHover?.(point.key)}
                  onPointerLeave={() => onHover?.(null)}
                  onFocus={() => onHover?.(point.key)}
                  onBlur={() => onHover?.(null)}
                  className="flex w-full items-start gap-3 rounded-md px-3 py-2.5 text-left focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
                >
                  <span className="mt-0.5 grid shrink-0 gap-1">
                    <span
                      className={`grid h-7 w-7 place-items-center rounded-lg ${
                        point.layer === "CAPTURE" ? "bg-ink-900 text-white" : "bg-purple-700 text-white"
                      }`}
                    >
                      {point.layer === "CAPTURE" ? (
                        <Crosshair className="h-4 w-4" aria-hidden />
                      ) : (
                        <MapPin className="h-4 w-4" aria-hidden />
                      )}
                    </span>
                    <span
                      aria-hidden
                      className="text-center font-display text-[10px] font-bold leading-none text-ink-500"
                    >
                      {ordinal}
                    </span>
                  </span>

                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                      <span className="wrap-break-word font-display text-sm font-semibold text-ink-900">
                        <span className="text-ink-500">{ordinal}. </span>
                        {point.label}
                      </span>
                      <span className="rounded-full border border-line-200 bg-surface-50 px-1.5 py-px text-[10px] font-medium uppercase tracking-wide text-ink-500">
                        {precisionBadge(point.precision)}
                      </span>
                      {focused.has(point.key) ? (
                        <span className="rounded-full bg-purple-700 px-1.5 py-px text-[10px] font-medium uppercase tracking-wide text-white">
                          This record
                        </span>
                      ) : null}
                    </span>

                    <span className="mt-0.5 block wrap-break-word text-xs text-ink-500">{point.region}</span>

                    <span className="mt-1 block text-xs text-ink-700">
                      <span className="font-semibold text-ink-900">{point.total}</span>{" "}
                      {point.total === 1 ? "record" : "records"}
                      {types.length ? (
                        <span className="text-ink-500">
                          {" — "}
                          {types.map(([type, count]) => countLabel(type, count)).join(", ")}
                        </span>
                      ) : null}
                    </span>

                    <span className="mt-1 block wrap-break-word text-[11px] leading-4 text-ink-500">
                      {precisionNote(point.precision)}
                      {point.layer === "CAPTURE" && point.fixes ? (
                        <>
                          {" · "}
                          {point.fixes} {point.fixes === 1 ? "fix" : "fixes"} spread across{" "}
                          {formatSpread(point.spreadMetres ?? 0)}
                          {point.medianAccuracy ? `, median accuracy ±${point.medianAccuracy} m` : ""}
                        </>
                      ) : null}
                      {point.layer === "ORIGIN" && point.places?.length ? (
                        <>
                          {" · covers "}
                          {point.places.slice(0, 3).map((name) => `“${name}”`).join(", ")}
                          {point.places.length > 3 ? ` and ${point.places.length - 3} more` : ""}
                        </>
                      ) : null}
                      {point.layer === "ORIGIN" && typeof point.pinnedRecords === "number" && point.total > 0 ? (
                        <>
                          {" · "}
                          {point.pinnedRecords === 0
                            ? "no dropped pins here yet"
                            : point.pinnedRecords === point.total
                              ? "every record here is pinned"
                              : `${point.pinnedRecords} of ${point.total} pinned`}
                        </>
                      ) : null}
                      {point.fromPlaceText ? (
                        <>
                          {" · "}
                          {point.fromPlaceText}{" "}
                          {point.fromPlaceText === 1 ? "record placed" : "records placed"} from a typed
                          place name rather than a stated address
                        </>
                      ) : null}
                    </span>
                  </span>
                </button>

                {children.length ? (
                  <div className="border-t border-line-200 px-3 py-1.5">
                    <button
                      type="button"
                      aria-expanded={isExpanded}
                      aria-controls={isExpanded ? panelId : undefined}
                      onClick={() => onToggleExpanded(point.key)}
                      className="flex w-full items-center gap-1.5 rounded text-left text-[11px] font-semibold text-purple-700 dark:text-purple-300 hover:underline focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
                    >
                      <ChevronDown
                        className={`h-3.5 w-3.5 shrink-0 transition-transform ${isExpanded ? "rotate-180" : ""}`}
                        aria-hidden
                      />
                      {childSummary(point, childLevel)}
                    </button>

                    {isExpanded ? (
                      <ul id={panelId} className="mb-1 mt-1.5 grid grid-cols-[minmax(0,1fr)] gap-1">
                        {children.map((child, childIndex) => (
                          <li key={child.key} className="min-w-0">
                            <button
                              type="button"
                              onClick={() => onDrillDown(child)}
                              className="flex w-full items-baseline gap-2 rounded border border-line-200 bg-surface-50 px-2 py-1.5 text-left transition-colors hover:border-purple-300 hover:bg-purple-50 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
                            >
                              <span className="w-5 shrink-0 text-right font-display text-[10px] font-bold text-ink-500">
                                {ordinal}.{childIndex + 1}
                              </span>
                              <span className="min-w-0 flex-1">
                                <span className="block wrap-break-word text-xs font-medium text-ink-900">
                                  {child.label}
                                </span>
                                <span className="block wrap-break-word text-[11px] leading-4 text-ink-500">
                                  {child.region}
                                </span>
                              </span>
                              <span className="shrink-0 whitespace-nowrap text-[11px] font-semibold text-ink-700">
                                {child.total}
                              </span>
                            </button>
                          </li>
                        ))}
                        {point.childrenTruncated ? (
                          <li className="px-2 text-[11px] leading-4 text-ink-500">
                            The busiest are listed. Switch the detail level to{" "}
                            {(childLevel ?? "DISTRICT").toLowerCase()} to see them all.
                          </li>
                        ) : null}
                        <li className="px-2 pt-0.5 text-[11px] leading-4 text-ink-500">
                          Choosing one moves the map to {(childLevel ?? "district").toLowerCase()} detail.
                        </li>
                      </ul>
                    ) : null}
                  </div>
                ) : null}
              </div>
            </li>
          );
        })}
      </ul>

      {unplaced.length ? (
        <div className="rounded-md border border-amber-100 bg-amber-100/40 p-3">
          <h3 className="font-display text-xs font-semibold uppercase tracking-wide text-amber-800">
            Not on the map
          </h3>
          <p className="mt-1 text-xs leading-5 text-ink-700">
            No state on record. Add a state and district to place them.
          </p>
          <ul className="mt-2 grid gap-1">
            {unplaced.map((entry) => (
              <li key={entry.label} className="text-xs text-ink-700">
                <span className="font-medium text-ink-900">{entry.label}</span> — {entry.total}{" "}
                {entry.total === 1 ? "record" : "records"}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function formatSpread(metres: number): string {
  if (metres < 1) return "a single point";
  if (metres < 1000) return `${metres} m`;
  return `${(metres / 1000).toFixed(1)} km`;
}
