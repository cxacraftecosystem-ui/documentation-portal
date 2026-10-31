"use client";

import { SlidersHorizontal, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { questionnaireApi, type FilterOption, type FilterOptions } from "@/lib/questionnaire/api";
import { chipDay, type ChipFilter, type DisplayChip } from "@/lib/questionnaire/chips";
import {
  activeCount,
  applyChip,
  cleanFilters,
  KIND_VALUES,
  SOURCE_VALUES,
  TRANSCRIPT_VALUES,
  type FilterKey,
  type Filters,
  type MultiKey
} from "@/lib/questionnaire/filters";
import { languageLabel } from "@/lib/questionnaire/languages";
import { qs, type QsKey } from "@/lib/questionnaire/qs";

/**
 * The search box and filters every questionnaire list shares (owner, 31 Oct 2026): questionnaire,
 * section or question, artisans, researcher, workshop, dates, kind, source, transcript and language.
 * The list itself filters on the server; this only edits the `Filters` its page keeps in the URL
 * (`useUrlFilters`). A filter set by tapping a chip shows here as a removable pill, under the chip's
 * own words.
 *
 * `hide` leaves out the controls a screen fixes (a form's own sessions hide the questionnaire) or
 * cannot use (the forms library has no transcript).
 */

/** Labels of values chosen by tapping a chip, so their pills read as the chip did. */
const remembered = new Map<string, string>();

export function rememberFilterLabel(filter: ChipFilter, label: string): void {
  remembered.set(`${filter.key}=${filter.value}`, label);
}

/** The `onChip` of a list: note the chip's words and toggle its filter. */
export function chipToggler(filters: Filters, setFilters: (next: Filters) => void) {
  return (filter: ChipFilter, chip: DisplayChip) => {
    rememberFilterLabel(filter, chip.label);
    setFilters(applyChip(filters, filter));
  };
}

/**
 * No chips under the triggers (the active filters are drawn once, as pills above) and no "Select
 * all" (a filter spells "everything" by choosing nothing). Spread rather than named because the two
 * portals' select primitives spell these props differently; each reads its own and ignores the rest.
 */
const QUIET = { chips: false, showChips: false, bulk: false } as Record<never, never>;

const KIND_LABEL: Record<string, QsKey> = { WORKSHOP_INTERVIEW: "kindWorkshopInterview", MARKET_SURVEY: "kindMarketSurvey", NONE: "kindNotStated" };
const SOURCE_LABEL: Record<string, QsKey> = { APP: "sourceApp", UPLOAD: "sourceUpload", HANDOFF: "sourceHandoff" };
const TRANSCRIPT_LABEL: Record<string, QsKey> = { HAS: "transcriptHas", NONE: "transcriptNone", PENDING: "transcriptPending", FAILED: "transcriptFailed" };

type Pill = { key: MultiKey | "date"; value: string; label: string };

export type FilterBarProps = {
  filters: Filters;
  onChange: (next: Filters) => void;
  /** The search box's words (who and what it searches). */
  placeholder: string;
  hide?: FilterKey[];
  /** Pre-filled labels for values the page knows (a fixed form's title …). */
  labels?: Record<string, string>;
  /** False where the page keeps its own search box (the forms library); the filters still apply. */
  search?: boolean;
};

export function FilterBar({ filters, onChange, placeholder, hide = [], labels = {}, search = true }: FilterBarProps) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(filters.q ?? "");
  const [options, setOptions] = useState<FilterOptions>({});
  const latest = useRef(filters);
  useEffect(() => {
    latest.current = filters;
  }, [filters]);
  const shows = (key: FilterKey) => !hide.includes(key);

  // The box follows the URL (Back, a chip clearing it) without fighting the reader's typing.
  useEffect(() => setText(filters.q ?? ""), [filters.q]);
  useEffect(() => {
    if (!search || (filters.q ?? "") === text.trim()) return;
    const timer = window.setTimeout(() => onChange(cleanFilters({ ...latest.current, q: text.trim() || undefined })), 300);
    return () => window.clearTimeout(timer);
  }, [text, filters.q, onChange, search]);

  const chosenForms = filters.questionnaireId ?? [];
  const formsKey = chosenForms.join(",");
  useEffect(() => {
    let cancelled = false;
    questionnaireApi
      .filterOptions(null, null, formsKey ? formsKey.split(",") : [], 200)
      .then((answer) => {
        if (!cancelled) setOptions(answer ?? {});
      })
      .catch(() => {
        if (!cancelled) setOptions({});
      });
    return () => {
      cancelled = true;
    };
  }, [formsKey]);

  const opt = (list: FilterOption[] | undefined, prefix = "") =>
    (list ?? []).map((o) => ({ value: `${prefix}${o.id}`, label: o.label, hint: o.count !== undefined ? String(o.count) : undefined }));

  const sectionOptions = useMemo(
    () => [
      ...(options.sections ?? []).map((o) => ({ value: `s:${o.id}`, label: o.label, group: qs("filterSection") })),
      ...(options.questions ?? []).map((o) => ({ value: `q:${o.id}`, label: o.label, group: qs("fieldInstrument") }))
    ],
    [options.sections, options.questions]
  );
  const workshopOptions = useMemo(
    () => (options.workshops ?? []).map((o) => ({ value: `${o.type === "DESIGN" ? "d" : "w"}:${o.id}`, label: o.label })),
    [options.workshops]
  );
  const artisanBase = options.artisans ?? [];
  const artisanSeen = new Set(artisanBase.map((o) => o.id));
  const artisanOptions = opt([
    ...(filters.artisanId ?? []).filter((id) => !artisanSeen.has(id)).map((id) => ({ id, label: labelOf("artisanId", id) })),
    ...artisanBase
  ]);

  function labelOf(key: string, value: string): string {
    const known = labels[`${key}=${value}`] ?? remembered.get(`${key}=${value}`);
    if (known) return known;
    const lists: Partial<Record<string, FilterOption[] | undefined>> = {
      questionnaireId: options.questionnaires, sectionId: options.sections, questionLineageId: options.questions,
      artisanId: options.artisans, researcherId: options.researchers, workshopId: options.workshops, designWorkshopId: options.workshops
    };
    const hit = lists[key]?.find((o) => o.id === value);
    if (hit) return hit.label;
    if (key === "kind") return qs(KIND_LABEL[value] ?? "kindNotStated");
    if (key === "source") return SOURCE_LABEL[value] ? qs(SOURCE_LABEL[value]) : value;
    if (key === "transcript") return TRANSCRIPT_LABEL[value] ? qs(TRANSCRIPT_LABEL[value]) : value;
    if (key === "languageCode") return languageLabel(value) || value;
    return value;
  }

  const set = (key: MultiKey, values: string[]) => onChange(cleanFilters({ ...filters, [key]: values }));
  const toggle = (key: MultiKey, value: string) => {
    const list = filters[key] ?? [];
    set(key, list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);
  };

  const pills: Pill[] = [];
  const clean = cleanFilters(filters);
  for (const key of ["questionnaireId", "sectionId", "questionLineageId", "artisanId", "researcherId", "workshopId", "designWorkshopId", "kind", "source", "transcript", "languageCode"] as MultiKey[]) {
    if (!shows(key)) continue;
    for (const value of clean[key] ?? []) pills.push({ key, value, label: labelOf(key, value) });
  }
  if (shows("dateFrom") && (clean.dateFrom || clean.dateTo)) {
    const from = clean.dateFrom ? chipDay(clean.dateFrom) : "…";
    const to = clean.dateTo ? chipDay(clean.dateTo) : "…";
    pills.push({ key: "date", value: "", label: clean.dateFrom && clean.dateFrom === clean.dateTo ? from : qs("filterDateRange", { from, to }) });
  }
  const removePill = (pill: Pill) => {
    if (pill.key === "date") onChange(cleanFilters({ ...filters, dateFrom: undefined, dateTo: undefined }));
    else set(pill.key, (filters[pill.key] ?? []).filter((v) => v !== pill.value));
  };
  const count = activeCount(Object.fromEntries(Object.entries(clean).filter(([key]) => shows(key as FilterKey))) as Filters);

  const pillGroup = (key: MultiKey, values: readonly string[], label: (v: string) => string, title: QsKey) => (
    <div className="grid gap-1">
      <span className="field-label">{qs(title)}</span>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label={qs(title)}>
        {values.map((v) => {
          const on = (filters[key] ?? []).includes(v);
          return (
            <button
              key={v}
              type="button"
              aria-pressed={on}
              onClick={() => toggle(key, v)}
              className={`rounded-full border px-3 py-1 text-xs transition ${
                on ? "border-purple-300 bg-purple-50 text-purple-800" : "border-line-200 bg-card text-ink-700 hover:border-purple-300"
              }`}
              data-filter-pill={`${key}=${v}`}
            >
              {label(v)}
            </button>
          );
        })}
      </div>
    </div>
  );

  return (
    <div className="mb-4 grid gap-2" data-filter-bar>
      <div className={`flex items-center gap-2 ${search ? "" : "justify-end"}`}>
        {search ? (
          <div className="min-w-0 flex-1">
            <SearchInput value={text} onChange={setText} placeholder={placeholder} />
          </div>
        ) : null}
        <button
          type="button"
          className="field-button-secondary shrink-0"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
          data-filters-toggle
        >
          <SlidersHorizontal className="h-4 w-4" aria-hidden />
          {count ? qs("filterCount", { count }) : qs("filtersTitle")}
        </button>
      </div>

      {pills.length ? (
        <ul className="flex flex-wrap items-center gap-1.5" data-active-filters>
          {pills.map((pill) => (
            <li key={`${pill.key}=${pill.value}`}>
              <button
                type="button"
                onClick={() => removePill(pill)}
                className="inline-flex items-center gap-1 rounded-full border border-purple-300 bg-purple-50 px-2.5 py-0.5 text-xs text-purple-800 hover:bg-purple-100"
                aria-label={`${pill.label} ×`}
                data-active-filter={pill.key === "date" ? "date" : `${pill.key}=${pill.value}`}
              >
                <span className="max-w-[14rem] truncate">{pill.label}</span>
                <X className="h-3 w-3" aria-hidden />
              </button>
            </li>
          ))}
          <li>
            <button type="button" className="px-1 text-xs text-ink-500 underline-offset-2 hover:underline" onClick={() => onChange(cleanFilters({ q: filters.q }))} data-clear-filters>
              {qs("filterClearAll")}
            </button>
          </li>
        </ul>
      ) : null}

      {open ? (
        <div className="panel grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-3" data-filters-panel>
          {shows("questionnaireId") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterQuestionnaire")}</span>
              <SearchableMultiSelect
                values={filters.questionnaireId ?? []}
                onChange={(v) => set("questionnaireId", v)}
                options={opt(options.questionnaires)}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterQuestionnaire")}
                searchable
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("sectionId") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterSection")}</span>
              <SearchableMultiSelect
                values={[...(filters.sectionId ?? []).map((v) => `s:${v}`), ...(filters.questionLineageId ?? []).map((v) => `q:${v}`)]}
                onChange={(v) =>
                  onChange(
                    cleanFilters({
                      ...filters,
                      sectionId: v.filter((x) => x.startsWith("s:")).map((x) => x.slice(2)),
                      questionLineageId: v.filter((x) => x.startsWith("q:")).map((x) => x.slice(2))
                    })
                  )
                }
                options={sectionOptions}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterSection")}
                searchable
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("artisanId") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterArtisans")}</span>
              <SearchableMultiSelect
                values={filters.artisanId ?? []}
                onChange={(v) => set("artisanId", v)}
                options={artisanOptions}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterArtisans")}
                searchable
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("researcherId") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterResearcher")}</span>
              <SearchableMultiSelect
                values={filters.researcherId ?? []}
                onChange={(v) => set("researcherId", v)}
                options={opt(options.researchers)}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterResearcher")}
                searchable
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("workshopId") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterWorkshop")}</span>
              <SearchableMultiSelect
                values={[...(filters.workshopId ?? []).map((v) => `w:${v}`), ...(filters.designWorkshopId ?? []).map((v) => `d:${v}`)]}
                onChange={(v) =>
                  onChange(
                    cleanFilters({
                      ...filters,
                      workshopId: v.filter((x) => x.startsWith("w:")).map((x) => x.slice(2)),
                      designWorkshopId: v.filter((x) => x.startsWith("d:")).map((x) => x.slice(2))
                    })
                  )
                }
                options={workshopOptions}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterWorkshop")}
                searchable
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("languageCode") ? (
            <div className="grid gap-1">
              <span className="field-label">{qs("filterLanguage")}</span>
              <SearchableMultiSelect
                values={filters.languageCode ?? []}
                onChange={(v) => set("languageCode", v)}
                options={(options.languages ?? []).map((o) => ({ value: o.id, label: languageLabel(o.id) || o.id }))}
                placeholder={qs("filterAny")}
                ariaLabel={qs("filterLanguage")}
                confirmOnSelect={false}
                {...QUIET}
              />
            </div>
          ) : null}
          {shows("dateFrom") ? (
            <div className="grid gap-2 sm:col-span-2 sm:grid-cols-2">
              <CalendarField
                label={qs("filterFrom")}
                value={filters.dateFrom ?? ""}
                max={filters.dateTo || undefined}
                onChange={(iso) => onChange(cleanFilters({ ...filters, dateFrom: iso || undefined }))}
              />
              <CalendarField
                label={qs("filterTo")}
                value={filters.dateTo ?? ""}
                min={filters.dateFrom || undefined}
                onChange={(iso) => onChange(cleanFilters({ ...filters, dateTo: iso || undefined }))}
              />
            </div>
          ) : null}
          {shows("kind") ? pillGroup("kind", KIND_VALUES, (v) => qs(KIND_LABEL[v]), "filterKind") : null}
          {shows("source") ? pillGroup("source", SOURCE_VALUES, (v) => qs(SOURCE_LABEL[v]), "filterSource") : null}
          {shows("transcript") ? pillGroup("transcript", TRANSCRIPT_VALUES, (v) => qs(TRANSCRIPT_LABEL[v]), "filterTranscript") : null}
        </div>
      ) : null}
    </div>
  );
}
