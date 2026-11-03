"use client";

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
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export const UNASSIGNED_WORKSHOP = "none";

const WORKSHOP_PAGE_SIZE = 100;

export type WorkshopScope = {
  workshopIds: string[];
  setWorkshopIds: (next: string[]) => void;
  workshops: Workshop[];
  loading: boolean;
  settling: boolean;
  queryValue: string | undefined;
  summary: string;
};

export function useWorkshopScope({
  defaultToMostRecent = true,
  initialWorkshopIds
}: {
  defaultToMostRecent?: boolean;
  initialWorkshopIds?: string[];
} = {}): WorkshopScope {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [loading, setLoading] = useState(true);
  const [workshopIds, setWorkshopIdsState] = useState<string[]>(initialWorkshopIds ?? []);
  const touched = useRef(Boolean(initialWorkshopIds?.length));
  const [settling, setSettling] = useState(!initialWorkshopIds?.length && defaultToMostRecent);

  useEffect(() => {
    let cancelled = false;
    listResource<Workshop>("/workshops", { pageSize: WORKSHOP_PAGE_SIZE })
      .then((result) => {
        if (cancelled) return;
        const ordered = sortWorkshopsByOccurrence(result.items);
        setWorkshops(ordered);
        if (defaultToMostRecent && !touched.current && ordered.length > 0) {
          setWorkshopIdsState([ordered[0].id]);
        }
      })
      .catch(() => {
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
    () => (workshopIds.length ? workshopIds.join(",") : undefined),
    [workshopIds]
  );

  const summary = useMemo(() => {
    if (!workshopIds.length) return "Every workshop, and records not linked to one.";
    const named = workshopIds
      .filter((id) => id !== UNASSIGNED_WORKSHOP)
      .map((id) => workshopDisplayTitle(workshops.find((workshop) => workshop.id === id)));
    const includesUnassigned = workshopIds.includes(UNASSIGNED_WORKSHOP);
    if (!named.length) return "Only records not linked to a workshop.";
    const list = named.length <= 3 ? named.join(", ") : `${named.slice(0, 3).join(", ")} and ${named.length - 3} more`;
    return `${list}${includesUnassigned ? ", plus records not linked to a workshop" : ""}.`;
  }, [workshopIds, workshops]);

  return { workshopIds, setWorkshopIds, workshops, loading, settling, queryValue, summary };
}

export function workshopScopeLabel(workshop: Workshop): string {
  const title = workshopDisplayTitle(workshop);
  const when = formatDate(workshopOccurrenceDate(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

export function WorkshopScopeSelect({
  scope,
  label = "Workshops",
  className = "",
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
      ...workshops.map((workshop) => ({ value: workshop.id, label: workshopScopeLabel(workshop) })),
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
              className="text-[11px] font-semibold text-purple-700 dark:text-purple-300 hover:underline"
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

export function workshopScopeLinkParams(workshopIds: string[]) {
  return { workshopIds: workshopIds.length ? workshopIds.join(",") : undefined };
}

export function workshopScopeFromSearchParams(params: URLSearchParams): string[] {
  return (params.get("workshopIds") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}
