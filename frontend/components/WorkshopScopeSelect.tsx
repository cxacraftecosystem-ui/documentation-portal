"use client";

/**
 * THE ONE WORKSHOP SCOPE CONTROL, shared by every screen that draws a conclusion from workshops:
 * the questionnaire's completion matrix, the consolidated questionnaire (index and document), and the
 * map.
 *
 * WHY IT IS ONE COMPONENT AND NOT THREE. Those screens answer three different questions about the same
 * unit of fieldwork, and a researcher moves between them while holding one thought — "what came out of
 * last week's workshop". If each screen grew its own picker they would disagree about the default, about
 * whether "all" is a selection or an absence, and about whether records with no workshop are in or out.
 * Any one of those disagreements makes two screens report different numbers for the same question, with
 * nothing on either screen to say which is right. So the vocabulary lives here once, and the wire format
 * it produces is the SAME `workshopIds` parameter `services/record_filters.resolve_workshop_ids` parses
 * on the server.
 *
 * THE THREE STATES, and why "all" is an empty array rather than every id:
 *
 *   []                         ALL workshops. Sends no parameter at all, which is what the server reads
 *                              as "do not filter". Listing every id instead would silently exclude any
 *                              workshop created after the page loaded, and would break the moment the
 *                              list outgrew one page.
 *   ["w1", "w2"]               Those workshops.
 *   [..., UNASSIGNED_WORKSHOP] Also (or only) records that are not linked to a workshop. Without this a
 *                              scope of "every workshop" would quietly drop everything filed before
 *                              workshops existed, and nothing on screen would say so.
 *
 * THE DEFAULT IS THE MOST RECENT WORKSHOP, not "all". That is the requirement and it is also the right
 * default: these screens are read during and just after a workshop, and a matrix showing every artisan
 * ever recorded against every interview ever taken is not the question anybody opened it to ask. "All
 * records" is one click away and says so.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CalendarRange, Layers } from "lucide-react";

import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { Workshop } from "@/lib/types";
import {
  sortWorkshopsByOccurrence,
  workshopOccurrenceDate
} from "@/components/forms/WorkshopSelect";

/**
 * The reserved id meaning "records not linked to any workshop". Byte-for-byte
 * `record_filters.UNASSIGNED_WORKSHOP` on the server and `WorkshopScope.UNASSIGNED` on Android — a
 * reserved word rather than an empty string, because an empty string is what a blank field sends and
 * "the user chose nothing" must not mean "show me only the orphans".
 */
export const UNASSIGNED_WORKSHOP = "none";

/** How many workshops the picker loads. The list route caps `pageSize` at 100. */
const WORKSHOP_PAGE_SIZE = 100;

export type WorkshopScope = {
  /** The selected ids; `[]` means every workshop. May contain {@link UNASSIGNED_WORKSHOP}. */
  workshopIds: string[];
  setWorkshopIds: (next: string[]) => void;
  /** Every workshop, most recent occurrence first. */
  workshops: Workshop[];
  loading: boolean;
  /** True until the default selection has been applied, so a screen can hold its first fetch. */
  settling: boolean;
  /** The value to spread into `buildQuery` / pass to `apiFetch`. Undefined when scoping to all. */
  queryValue: string | undefined;
  /** One line naming what is in scope, for a panel subtitle. */
  summary: string;
};

/**
 * Loads the workshops and owns the selection.
 *
 * `defaultToMostRecent` exists for the one screen where it would be wrong — a document that is
 * ordinarily read whole. Pass false there and the scope starts at "all".
 */
export function useWorkshopScope({
  defaultToMostRecent = true,
  initialWorkshopIds
}: {
  defaultToMostRecent?: boolean;
  /** Seeds the selection from a URL, which then wins over the most-recent default. */
  initialWorkshopIds?: string[];
} = {}): WorkshopScope {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [loading, setLoading] = useState(true);
  const [workshopIds, setWorkshopIdsState] = useState<string[]>(initialWorkshopIds ?? []);
  // The default is applied ONCE, and only if the user has not already chosen. Without the ref a
  // re-render that re-ran the effect would drag the selection back to the most recent workshop under
  // somebody who had just picked two others.
  const touched = useRef(Boolean(initialWorkshopIds?.length));
  const [settling, setSettling] = useState(!initialWorkshopIds?.length && defaultToMostRecent);

  useEffect(() => {
    let cancelled = false;
    listResource<Workshop>("/workshops", { pageSize: WORKSHOP_PAGE_SIZE })
      .then((result) => {
        if (cancelled) return;
        // The list route already orders by occurrence, but it is re-sorted here for the same reason
        // WorkshopSelect re-sorts: the client must not depend on the server's order to decide which
        // workshop is "the most recent", because getting that wrong picks the wrong default silently.
        const ordered = sortWorkshopsByOccurrence(result.items);
        setWorkshops(ordered);
        if (defaultToMostRecent && !touched.current && ordered.length > 0) {
          setWorkshopIdsState([ordered[0].id]);
        }
      })
      .catch(() => {
        // A picker that could not load its options must not also silence the screen behind it: fall
        // through to "all workshops", which is a complete and honest answer.
        if (!cancelled) setWorkshops([]);
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        setSettling(false);
      });
    return () => {
      cancelled = true;
    };
  }, [defaultToMostRecent]);

  const setWorkshopIds = useCallback((next: string[]) => {
    touched.current = true;
    setWorkshopIdsState(next);
  }, []);

  const queryValue = useMemo(
    // Comma-joined, which `resolve_workshop_ids` accepts alongside repeated parameters. Undefined
    // rather than "" when scoping to all, so `buildQuery` drops the key entirely — the server reads an
    // ABSENT parameter as "every workshop", and an empty one would be indistinguishable from a bug.
    () => (workshopIds.length ? workshopIds.join(",") : undefined),
    [workshopIds]
  );

  const summary = useMemo(() => {
    if (!workshopIds.length) return "Every workshop, and records not linked to one.";
    const named = workshopIds
      .filter((id) => id !== UNASSIGNED_WORKSHOP)
      .map((id) => workshops.find((workshop) => workshop.id === id)?.title?.trim() || "Untitled workshop");
    const includesUnassigned = workshopIds.includes(UNASSIGNED_WORKSHOP);
    if (!named.length) return "Only records not linked to a workshop.";
    const list = named.length <= 3 ? named.join(", ") : `${named.slice(0, 3).join(", ")} and ${named.length - 3} more`;
    return `${list}${includesUnassigned ? ", plus records not linked to a workshop" : ""}.`;
  }, [workshopIds, workshops]);

  return { workshopIds, setWorkshopIds, workshops, loading, settling, queryValue, summary };
}

/** Title plus the day it ran — the same label `WorkshopSelect` uses, so one workshop reads one way. */
function optionLabel(workshop: Workshop): string {
  const title = workshop.title?.trim() || "Untitled workshop";
  const when = formatDate(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

/**
 * The control. A multi-select over the workshops, an "All records" shortcut, and a line of text saying
 * what is currently in scope.
 *
 * `MultiSelectDropdown` is reused rather than reimplemented — it already floats its panel out of an
 * `overflow-hidden` ancestor, grows a search box past eight options, offers a filter-aware "Select
 * all", and is what every other multi-select in the app looks like.
 */
export function WorkshopScopeSelect({
  scope,
  label = "Workshops",
  className = "",
  /** Rendered under the control. Turn off where the parent panel already carries the sentence. */
  showSummary = true
}: {
  scope: WorkshopScope;
  label?: string;
  className?: string;
  showSummary?: boolean;
}) {
  const { workshopIds, setWorkshopIds, workshops, loading } = scope;

  const options = useMemo(
    () => [
      ...workshops.map((workshop) => ({ value: workshop.id, label: optionLabel(workshop) })),
      // Last, and named as what it is. It is not a workshop, so it does not belong among them in the
      // reading order — but it has to be selectable, or a scope of "every workshop" silently drops
      // every record filed before workshops existed.
      { value: UNASSIGNED_WORKSHOP, label: "Not linked to a workshop" }
    ],
    [workshops]
  );

  const all = workshopIds.length === 0;

  return (
    <div className={`grid min-w-0 content-start gap-1.5 ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="field-label inline-flex items-center gap-1.5">
          <CalendarRange className="h-3.5 w-3.5" aria-hidden />
          {label}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            aria-pressed={all}
            onClick={() => setWorkshopIds([])}
            className={`rounded-full border px-2.5 py-0.5 text-[11px] font-medium transition-colors ${
              all
                ? "border-purple-700 bg-purple-700 text-white"
                : "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300 hover:text-purple-700"
            }`}
          >
            All records
          </button>
          {!all && workshops.length > 0 ? (
            <button
              type="button"
              onClick={() => setWorkshopIds([workshops[0].id])}
              className="text-[11px] font-semibold text-purple-700 hover:underline"
            >
              Most recent only
            </button>
          ) : null}
        </div>
      </div>

      <MultiSelectDropdown
        values={workshopIds}
        onChange={setWorkshopIds}
        options={options}
        ariaLabel="Choose which workshops to include"
        placeholder={loading ? "Loading workshops…" : all ? "All workshops" : "Select workshops"}
        emptyLabel="No workshops recorded yet"
      />

      {showSummary ? (
        <p className="flex items-start gap-1.5 text-[11px] leading-4 text-ink-500">
          <Layers className="mt-px h-3 w-3 shrink-0" aria-hidden />
          <span>{scope.summary}</span>
        </p>
      ) : null}
    </div>
  );
}

/**
 * The scope as URL parameters, so a filtered view can be linked to and come back the same.
 *
 * The key is `workshopIds`, spelled exactly as the API spells it, so a link can be pasted into either
 * and mean the same thing.
 */
export function workshopScopeLinkParams(workshopIds: string[]) {
  return { workshopIds: workshopIds.length ? workshopIds.join(",") : undefined };
}

/** The inverse: read a selection out of a URL. Unknown ids are kept — the server judges them. */
export function workshopScopeFromSearchParams(params: URLSearchParams): string[] {
  return (params.get("workshopIds") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}
