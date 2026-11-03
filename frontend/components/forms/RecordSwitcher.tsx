"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { CalendarRange, PencilLine } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { sortWorkshopsByOccurrence } from "@/components/forms/WorkshopSelect";
import { workshopScopeLabel } from "@/components/WorkshopScopeSelect";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { listResource } from "@/lib/api";
import type { Workshop } from "@/lib/types";
import { loadWorkshopList } from "@/lib/workshopList";


export const RECORD_KINDS = ["artisan", "craft", "process", "product", "tool"] as const;

export type RecordKind = (typeof RECORD_KINDS)[number];

export type SwitchableRecord = {
  id: string;
  name?: string | null;
  place?: string | null;
  productName?: string | null;
  artisanName?: string | null;
  toolkitName?: string | null;
  product?: { productName?: string | null } | null;
  workshopId?: string | null;
};

export const LABEL_SEPARATOR = " · ";

function joined(head: string | null | undefined, tail: string | null | undefined): string {
  const left = (head ?? "").trim();
  const right = (tail ?? "").trim();
  if (!left) return right;
  if (!right) return left;
  return `${left}${LABEL_SEPARATOR}${right}`;
}

type RecordKindSpec = {
  endpoint: string;
  noun: string;
  plural: string;
  singular: string;
  label: (record: SwitchableRecord) => string;
};

const RECORD_KIND_SPECS: Record<RecordKind, RecordKindSpec> = {
  artisan: {
    endpoint: "/artisans",
    noun: "artisans in this workshop",
    plural: "artisans",
    singular: "artisan",
    label: (record) => joined(record.name, record.place)
  },
  craft: {
    endpoint: "/crafts",
    noun: "crafts in this workshop",
    plural: "crafts",
    singular: "craft",
    label: (record) => joined(record.name, record.place)
  },
  process: {
    endpoint: "/processes",
    noun: "processes in this workshop",
    plural: "processes",
    singular: "process",
    label: (record) => joined(record.name, record.product?.productName)
  },
  product: {
    endpoint: "/products",
    noun: "products in this workshop",
    plural: "products",
    singular: "product",
    label: (record) => joined(record.productName, record.artisanName)
  },
  tool: {
    endpoint: "/tools",
    noun: "tools in this workshop",
    plural: "tools",
    singular: "tool",
    label: (record) => joined(record.toolkitName, record.artisanName)
  }
};

export function recordEndpoint(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].endpoint;
}

export function recordNoun(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].noun;
}

export function recordNounFor(kind: RecordKind, workshopId: string): string {
  return workshopId.trim() ? recordNoun(kind) : recordPlural(kind);
}

export const ALL_WORKSHOPS_LABEL = "All workshops";

export function recordSwitchWorkshopOptions(
  workshops: readonly { value: string; label: string }[]
): { value: string; label: string }[] {
  return [{ value: "", label: ALL_WORKSHOPS_LABEL }, ...workshops.filter((option) => option.value.trim() !== "")];
}

export function recordSwitchWorkshopFor(
  selectedId: string,
  recordWorkshopId: string | null | undefined,
  available: readonly string[]
): string {
  if (!selectedId.trim() && recordWorkshopId && recordWorkshopId.trim() && available.includes(recordWorkshopId)) {
    return recordWorkshopId;
  }
  return selectedId;
}

export function recordPlural(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].plural;
}

export function recordSingular(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].singular;
}

export function recordOptionLabel(kind: RecordKind, record: SwitchableRecord): string {
  const label = RECORD_KIND_SPECS[kind].label(record).trim();
  return label || `Untitled ${RECORD_KIND_SPECS[kind].singular}`;
}

export const EDIT_QUERY_PARAM = "edit";

export function recordEditHref(kind: RecordKind, id: string): string {
  const encoded = encodeURIComponent(id);
  switch (kind) {
    case "craft":
      return `/crafts?${EDIT_QUERY_PARAM}=${encoded}`;
    case "process":
      return `/processes?${EDIT_QUERY_PARAM}=${encoded}`;
    case "artisan":
      return `/artisans/${encoded}/edit`;
    case "product":
      return `/products/${encoded}/edit`;
    case "tool":
      return `/tools/${encoded}/edit`;
  }
}

export function isInlineEditSurface(kind: RecordKind): boolean {
  return kind === "craft" || kind === "process";
}

export function recordSwitcherTitle(kind: RecordKind): string {
  return `Open a different ${recordSingular(kind)}`;
}

export type RecordListState = "pending" | "loaded" | "unavailable";

export function recordsAreForWorkshop(loadedForWorkshop: string | null, workshopId: string): boolean {
  return loadedForWorkshop !== null && loadedForWorkshop === workshopId;
}

export function recordListMessage({
  kind,
  state,
  loadedForWorkshop,
  workshopId,
  shown
}: {
  kind: RecordKind;
  state: RecordListState;
  loadedForWorkshop: string | null;
  workshopId: string;
  shown: number;
}): string | null {
  if (state === "unavailable") {
    return `These ${recordPlural(kind)} could not be loaded. The record open below is unaffected.`;
  }
  if (!workshopId.trim()) {
    if (state === "pending" || loadedForWorkshop !== workshopId) return `Loading ${recordPlural(kind)}…`;
    return shown === 0 ? `No ${recordPlural(kind)} yet.` : null;
  }
  if (state === "pending" || !recordsAreForWorkshop(loadedForWorkshop, workshopId)) {
    return "Loading this workshop's records…";
  }
  if (shown === 0) {
    return `No ${recordNoun(kind)} yet. Pick another workshop above, or file the first one.`;
  }
  return null;
}

export function recordListCut(kind: RecordKind, loaded: number, total: number, workshopId?: string): ListCut | null {
  return cutOf(loaded, total, workshopId === undefined ? recordNoun(kind) : recordNounFor(kind, workshopId));
}

export const SEARCH_DEBOUNCE_MS = 350;

export function shouldSearchServer(cut: ListCut | null, query: string): boolean {
  return cut !== null && query.trim().length > 0;
}

export function RecordSwitcher({
  kind,
  currentId,
  currentWorkshopId,
  onNavigate,
  className
}: {
  kind: RecordKind;
  currentId: string;
  currentWorkshopId?: string | null;
  onNavigate?: (href: string) => void;
  className?: string;
}) {
  const router = useRouter();
  const endpoint = recordEndpoint(kind);
  const intercept = useLeaveInterceptor();
  const readerId = useAuth().user?.id ?? null;

  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [workshopsLoading, setWorkshopsLoading] = useState(true);
  const [workshopId, setWorkshopId] = useState("");
  useEffect(() => {
    let cancelled = false;
    loadWorkshopList(readerId)
      .then((result) => {
        if (!cancelled) setWorkshops(sortWorkshopsByOccurrence(result.items));
      })
      .catch(() => {
        if (!cancelled) setWorkshops([]);
      })
      .finally(() => {
        if (!cancelled) setWorkshopsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [readerId]);
  useEffect(() => {
    setWorkshopId((selected) =>
      recordSwitchWorkshopFor(selected, currentWorkshopId, workshops.map((row) => row.id))
    );
  }, [currentWorkshopId, workshops]);

  const [records, setRecords] = useState<SwitchableRecord[]>([]);
  const [state, setState] = useState<RecordListState>("pending");
  const [loadedForWorkshop, setLoadedForWorkshop] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");

  useEffect(() => {
    let cancelled = false;
    setState("pending");
    setRecords([]);
    setLoadedForWorkshop(null);
    setCut(null);
    listResource<SwitchableRecord>(endpoint, { workshopId, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setRecords(result.items);
        setCut(recordListCut(kind, result.items.length, result.total, workshopId));
        setLoadedForWorkshop(workshopId);
        setState("loaded");
      })
      .catch(() => {
        if (!cancelled) setState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, kind, workshopId]);

  useEffect(() => {
    if (!shouldSearchServer(cut, query)) {
      setApplied("");
      return;
    }
    const timer = setTimeout(() => setApplied(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [cut, query]);

  useEffect(() => {
    if (!applied) return;
    let cancelled = false;
    listResource<SwitchableRecord>(endpoint, { workshopId, search: applied, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (!cancelled) setRecords((previous) => mergeById(previous, result.items));
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, workshopId, applied]);

  const workshopOptions = useMemo(
    () => recordSwitchWorkshopOptions(workshops.map((row) => ({ value: row.id, label: workshopScopeLabel(row) }))),
    [workshops]
  );

  const recordOptions = useMemo(
    () => records.map((record) => ({ value: record.id, label: recordOptionLabel(kind, record) })),
    [kind, records]
  );

  const showsCurrentRecord = recordOptions.some((option) => option.value === currentId);

  const message = recordListMessage({
    kind,
    state,
    loadedForWorkshop,
    workshopId,
    shown: recordOptions.length
  });

  const open = useCallback(
    (nextId: string) => {
      if (!nextId || nextId === currentId) return;
      const href = recordEditHref(kind, nextId);
      if (onNavigate) {
        onNavigate(href);
        return;
      }
      if (intercept()) return;
      router.push(href);
    },
    [currentId, intercept, kind, onNavigate, router]
  );

  const singular = recordSingular(kind);

  const canJumpToRecordWorkshop = Boolean(
    currentWorkshopId && currentWorkshopId !== workshopId && workshopOptions.some((o) => o.value === currentWorkshopId)
  );

  return (
    <section
      className={className ?? "panel grid gap-3 p-4 md:grid-cols-2"}
      aria-label={`Open a different ${singular} for editing`}
    >
      <div className="flex items-center gap-2 text-sm font-medium text-ink-900 md:col-span-2">
        <PencilLine className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        <span>{recordSwitcherTitle(kind)}</span>
      </div>

      <div className="grid min-w-0 content-start gap-1.5">
        <span className="field-label inline-flex items-center gap-1.5">
          <CalendarRange className="h-3.5 w-3.5" aria-hidden />
          Workshop
        </span>
        <Dropdown
          value={workshopId}
          onChange={setWorkshopId}
          options={workshopOptions}
          ariaLabel="Workshop to list records from"
          placeholder={workshopsLoading ? "Loading workshops…" : ALL_WORKSHOPS_LABEL}
          advanceOnSelect={false}
        />
        {canJumpToRecordWorkshop ? (
          <button
            type="button"
            onClick={() => setWorkshopId(currentWorkshopId as string)}
            className="justify-self-start text-[11px] font-semibold text-purple-700 dark:text-purple-300 hover:underline"
          >
            Show this {singular}&apos;s own workshop
          </button>
        ) : null}
      </div>

      <div className="grid min-w-0 content-start gap-1.5">
        <span className="field-label">{`${singular[0].toUpperCase()}${singular.slice(1)}`}</span>
        <ComboBox
          value={showsCurrentRecord ? currentId : ""}
          onChange={open}
          onSearch={setQuery}
          options={recordOptions}
          ariaLabel={`${singular} to open for editing`}
          placeholder={`Search ${singular} records…`}
          emptyLabel={workshopId ? `No ${singular} records in this workshop` : `No ${singular} records`}
          advanceOnSelect={false}
          disabled={state === "unavailable"}
        />
        {message ? <p className="text-xs leading-5 text-ink-500">{message}</p> : null}
        <CappedListNotice cuts={[cut]} reach="search" />
      </div>
    </section>
  );
}
