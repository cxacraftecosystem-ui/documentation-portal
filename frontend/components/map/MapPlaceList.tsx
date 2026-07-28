"use client";

/**
 * The map as text. Not a fallback — the other half of the same instrument.
 *
 * The SVG beside this is `aria-hidden`, so this list is the ONLY way a screen-reader or
 * keyboard-only user reads the map, and it is therefore held to a higher bar than a caption: every
 * place, every count, the precision of every point, and the same click-through to the records. A
 * sighted user gets the shape of the country; everyone gets the numbers. Nothing is available in
 * one and missing from the other.
 *
 * Each row is a real `<button>` in the natural tab order, and hovering a row lights its pin exactly
 * as hovering a pin lights the row — the two views are one selection.
 */

import { Crosshair, MapPin } from "lucide-react";

import { countLabel, type MapPoint, type MapPrecision, type UnplacedPlace } from "@/components/map/types";
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

export function MapPlaceList({
  points,
  unplaced,
  selectedKey,
  focusKeys = [],
  onSelect,
  onHover
}: {
  points: MapPoint[];
  unplaced: UnplacedPlace[];
  selectedKey: string | null;
  focusKeys?: string[];
  onSelect: (key: string) => void;
  onHover?: (key: string | null) => void;
}) {
  const focused = new Set(focusKeys);

  return (
    <div className="grid gap-3">
      <ul className="grid grid-cols-[minmax(0,1fr)] gap-1.5">
        {points.map((point) => {
          const isSelected = point.key === selectedKey;
          const types = (Object.entries(point.counts) as Array<[RecordType, number]>).filter(
            ([, count]) => count > 0
          );
          return (
            <li key={point.key} className="min-w-0">
              <button
                type="button"
                aria-pressed={isSelected}
                onClick={() => onSelect(point.key)}
                onPointerEnter={() => onHover?.(point.key)}
                onPointerLeave={() => onHover?.(null)}
                onFocus={() => onHover?.(point.key)}
                onBlur={() => onHover?.(null)}
                className={`flex w-full items-start gap-3 rounded-md border px-3 py-2.5 text-left transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700 ${
                  isSelected
                    ? "border-purple-700 bg-purple-50"
                    : "border-line-200 bg-white hover:border-purple-300"
                }`}
              >
                <span
                  className={`mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg ${
                    point.layer === "CAPTURE" ? "bg-ink-900 text-white" : "bg-purple-700 text-white"
                  }`}
                >
                  {point.layer === "CAPTURE" ? (
                    <Crosshair className="h-4 w-4" aria-hidden />
                  ) : (
                    <MapPin className="h-4 w-4" aria-hidden />
                  )}
                </span>

                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <span className="break-words font-display text-sm font-semibold text-ink-900">
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
