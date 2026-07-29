"use client";

/**
 * The map as text. Not a fallback — the other half of the same instrument.
 *
 * The SVG beside this is `aria-hidden`, so this list is the ONLY way a screen-reader or keyboard-only
 * user reads the map, and it is therefore held to a higher bar than a caption: every place, every count,
 * the precision of every point, and the same click-through to the records. A sighted user gets the shape
 * of the country; everyone gets the numbers. Nothing is available in one and missing from the other.
 *
 * THE ROWS ARE NUMBERED, and the number is not decoration. It is the shared name for a place across the
 * two views: the pin's hover label says "3 · Jaipur" and this row says "3", so "the third one" means one
 * thing on the picture and on the list. Clicking a pin scrolls this list to its row and flashes it (see
 * `components/hooks/useRevealRow`), and pointing at either one lights the other.
 *
 * A ROW CAN OPEN. At NATION level the whole country is one dot and one row, and at STATE level a state is
 * one dot and one row — so "click the pin, find its row" has nowhere to go. Those rows therefore carry
 * the level below them (`point.children`) as a disclosure: the states inside the nation, the districts
 * inside the state. Choosing one drills the whole map down to it, because a child's key is a real point
 * key at the child level — see `MapPointChild`.
 */

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

/**
 * How each precision tier is described in words. The map draws uncertainty as a halo; this says the
 * same thing in a sentence, because a halo communicates nothing to a screen reader.
 *
 * Two of the six are MEASUREMENTS and four are lookups, and the wording keeps them apart on purpose:
 * a reader who cannot tell a dropped pin from a state capital will read the second as the first.
 */
const PRECISION_NOTE: Record<MapPrecision, string> = {
  // Covers two shapes: a pin inside a named district, and — for a point keyed `pin:` — a pin with no
  // state or district recorded at all, which the server places as itself rather than reporting as
  // unplaced. Its `region` names the coordinate and says the names are missing.
  SUBJECT_PIN: "pin dropped on the subject's own place",
  MEASURED: "GPS fix taken while recording",
  TOWN: "town located from the typed place name",
  DISTRICT: "district position learned from pins inside it",
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

/** What a level's children are called, for the disclosure's own label. */
const CHILD_NOUN: Record<AdminLevel, [singular: string, plural: string]> = {
  NATION: ["place", "places"],
  STATE: ["state", "states"],
  DISTRICT: ["district", "districts"]
};

/**
 * Unknown precision values must not blank the badge. The server's vocabulary can gain a tier before
 * this client is redeployed, and an empty pill beside a real count reads as a broken row.
 */
function precisionBadge(precision: MapPrecision): string {
  return PRECISION_BADGE[precision] ?? String(precision);
}

function precisionNote(precision: MapPrecision): string {
  return PRECISION_NOTE[precision] ?? "position of unstated precision";
}

/**
 * How the disclosure describes what is inside it. A CAPTURE point's children are tighter GPS clusters
 * rather than administrative units, so calling them "districts" would be wrong in the one way this whole
 * subsystem refuses to be wrong — it would lend a measurement an administrative name it does not have.
 */
function childSummary(point: MapPoint, childLevel: AdminLevel | null | undefined): string {
  const count = point.children?.length ?? 0;
  if (point.layer === "CAPTURE") {
    return `${count} separate recording ${count === 1 ? "place" : "places"} inside this pin`;
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
  /** The pin under the pointer on the map, so the two views light up together. */
  hoveredKey?: string | null;
  /** Which level `point.children` are keyed at, straight from the server. */
  childLevel?: AdminLevel | null;
  /** Rows whose disclosure is open. Held by the page so selecting a pin can open one. */
  expandedKeys: ReadonlySet<string>;
  /** The row flashing right now, from `useRevealRow`. */
  flashKey?: string | null;
  /** Row ref registrar from `useRevealRow`, so the page can scroll a row into view. */
  registerRow?: (key: string) => (node: HTMLElement | null) => void;
  onSelect: (key: string) => void;
  onHover?: (key: string | null) => void;
  onToggleExpanded: (key: string) => void;
  /** Drill the map down to a child: switch to its level and select it. */
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
          // The shared name for this place across both views. 1-based and in the server's own order
          // (busiest first), which is the order the pins were sorted in — so the numbers cannot disagree.
          const ordinal = index + 1;
          // The ORDINAL, not the key, because a point key is arbitrary text from the server
          // (`district:Rajasthan|Jaipur`, `capture:0.25:26_75`) and sanitising it for an HTML id can map
          // two distinct keys onto one string. The ordinal is unique within this list by construction.
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
                  className="flex w-full items-start gap-3 rounded-md px-3 py-2.5 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
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
                    {/* The number the map's hover label repeats. Not announced separately: it is already
                        in the row's accessible name below, and a bare "3" read out on its own is noise. */}
                    <span
                      aria-hidden
                      className="text-center font-display text-[10px] font-bold leading-none text-ink-500"
                    >
                      {ordinal}
                    </span>
                  </span>

                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                      <span className="break-words font-display text-sm font-semibold text-ink-900">
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

                    <span className="mt-0.5 block break-words text-xs text-ink-500">{point.region}</span>

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

                    <span className="mt-1 block break-words text-[11px] leading-4 text-ink-500">
                      {precisionNote(point.precision)}
                      {point.layer === "CAPTURE" && point.fixes ? (
                        <>
                          {" · "}
                          {point.fixes} {point.fixes === 1 ? "fix" : "fixes"} spread across{" "}
                          {formatSpread(point.spreadMetres ?? 0)}
                          {point.medianAccuracy ? `, median accuracy ±${point.medianAccuracy} m` : ""}
                        </>
                      ) : null}
                      {/* Grouping to a district loses no information — it moves the finer names here. */}
                      {point.layer === "ORIGIN" && point.places?.length ? (
                        <>
                          {" · covers "}
                          {point.places.slice(0, 3).map((name) => `“${name}”`).join(", ")}
                          {point.places.length > 3 ? ` and ${point.places.length - 3} more` : ""}
                        </>
                      ) : null}
                      {/* How much of this point's POSITION is measurement. A district holding forty
                          records of which two carry a pin is a different thing from one where all forty
                          do, and the pin looks identical either way. */}
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
                      // Conditional, for the same reason the walkthrough's step card makes it
                      // conditional: the panel is unmounted when closed, and an `aria-controls`
                      // pointing at an id that is not in the document is worse than none — it tells a
                      // screen reader there is somewhere to go and then loses it.
                      aria-controls={isExpanded ? panelId : undefined}
                      onClick={() => onToggleExpanded(point.key)}
                      className="flex w-full items-center gap-1.5 rounded text-left text-[11px] font-semibold text-purple-700 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
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
                              className="flex w-full items-baseline gap-2 rounded border border-line-200 bg-surface-50 px-2 py-1.5 text-left transition-colors hover:border-purple-300 hover:bg-purple-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
                            >
                              <span className="w-5 shrink-0 text-right font-display text-[10px] font-bold text-ink-300">
                                {ordinal}.{childIndex + 1}
                              </span>
                              <span className="min-w-0 flex-1">
                                <span className="block break-words text-xs font-medium text-ink-900">
                                  {child.label}
                                </span>
                                <span className="block break-words text-[11px] leading-4 text-ink-500">
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
                        {/* WHY THESE ARE LINKS INTO THE MAP AND NOT INTO THE RECORDS. Choosing one
                            re-groups the whole map at the finer level and selects it, which is the same
                            navigation as moving the Detail control and then clicking that pin — so the
                            reader ends up somewhere they could have reached by hand, with the pin, the
                            borders and the record panel all agreeing. */}
                        <li className="px-2 pt-0.5 text-[11px] leading-4 text-ink-300">
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
          {/* A place quietly missing from a map is indistinguishable from a place with no records,
              so what could not be placed is named here rather than dropped. */}
          <p className="mt-1 text-xs leading-5 text-ink-700">
            These records carry no state on their location and no place name the atlas can resolve, so
            there is nothing to place them by. They are counted in the totals but have no pin. Adding a
            state — and ideally a district — to the record puts it on the map.
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
