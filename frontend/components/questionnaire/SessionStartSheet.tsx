"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { loadInstrumentQuestions } from "@/components/questionnaire/instrumentQuestions";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { enqueueUnit, onSessionLanded, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { SearchInput } from "@/components/SearchInput";
import { CalendarField } from "@/components/ui/CalendarField";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { listResource } from "@/lib/api";
import { agoLabel, coveredSectionsOffline, coverageLine, overlayFromUnits, readSnapshot, withTakes, type ArtisanCoverage } from "@/lib/questionnaire/coverageCache";
import { LANGUAGES } from "@/lib/questionnaire/languages";
import { sessionTitle } from "@/lib/naming";
import { mintKey, questionnaireApi, SESSION_PURPOSES, type FormFor, type OpenMatchItem, type Session, type SessionCreate, type SessionLocation, type SessionPurpose } from "@/lib/questionnaire/api";
import { prompts } from "@/lib/questionnaire/prompts";
import { newSessionUnit } from "@/lib/questionnaire/queue";
import { readStartLists, searchCachedArtisans, titleWorkshop, trimArtisan, trimWorkshop, writeStartLists } from "@/lib/questionnaire/startSheetCache";
import { PURPOSE_KEY, qs } from "@/lib/questionnaire/qs";
import {
  artisanFilter,
  defaultWorkshopId,
  listForms,
  listWorkshops,
  NO_WORKSHOP_LABEL,
  WORKSHOP_LABEL,
  workshopScope,
  type PortalForm,
  type PortalWorkshop
} from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

export const FIELD_VISIT = "__field_visit__";

export type StartSheetState = {
  workshopId: string;
  questionnaireId: string;
  sessionDate: string;
  /** GUEST: "Someone else" — a named person who is not a registered artisan (owner decision 1). */
  mode: "INDIVIDUAL" | "GROUP" | "GUEST";
  /** The guest's name when `mode` is GUEST. */
  guestName: string;
  artisanIds: string[];
  purpose: SessionPurpose;
  place: string;
  /** C9: the device's fix, taken with "Use my location"; optional, never asked for on an edit. */
  location?: SessionLocation | null;
  languageCode: string;
  languageOther: string;
  note: string;
};

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * Start is allowed once the form, the date and someone to ask are set (DESIGN test 36): at least one
 * artisan, or the guest's name for "Someone else".
 */
export function startBlocked(form: Pick<StartSheetState, "questionnaireId" | "sessionDate" | "artisanIds"> & Partial<Pick<StartSheetState, "mode" | "guestName">>): boolean {
  if (!form.questionnaireId || !form.sessionDate) return true;
  if (form.mode === "GUEST") return !(form.guestName ?? "").trim();
  return form.artisanIds.length === 0;
}

/** Individual keeps one artisan; switching from Group trims the pick to its first; a guest has none. */
export function constrainArtisans(mode: StartSheetState["mode"], ids: string[]): string[] {
  if (mode === "GUEST") return [];
  return mode === "INDIVIDUAL" ? ids.slice(0, 1) : ids;
}

/** The create body. Never a title: the server generates it (TITLE_IS_GENERATED otherwise). */
export function sessionCreateBody(form: StartSheetState, clientKey: string, now = new Date()): SessionCreate {
  const note = form.note.trim().slice(0, 80);
  return {
    clientKey,
    questionnaireId: form.questionnaireId,
    ...workshopScope(form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null),
    sessionDate: form.sessionDate,
    startedAt: now.toISOString(),
    purpose: form.purpose,
    place: form.place.trim() || null,
    ...(form.location ? { location: form.location } : {}),
    languageCode: form.languageCode || null,
    languageOther: form.languageCode === "other" ? form.languageOther.trim() || null : null,
    note: note || null,
    participants: form.mode === "GUEST" ? [] : form.artisanIds.map((artisanId) => ({ artisanId })),
    respondents: form.mode === "GUEST" ? [{ name: form.guestName.trim().slice(0, 120), kind: "GUEST" }] : []
  };
}

/** The form picker's options: the workshop's bound forms first, grouped by kind, then every other form. */
export function formOptions(forms: PortalForm[], resolved: FormFor | null): { value: string; label: string; group?: string }[] {
  const bound = new Set((resolved?.bound ?? []).map((b) => b.questionnaireId));
  const kindLabel = (kind: string | null) => (kind ? qs(kind === "MARKET_SURVEY" ? "kindMarketSurvey" : "kindWorkshopInterview") : qs("kindNotStated"));
  const boundForms = forms.filter((f) => bound.has(f.id));
  const rest = forms.filter((f) => !bound.has(f.id));
  return [
    ...boundForms.map((f) => ({ value: f.id, label: f.title, group: `${qs("boundToWorkshop")} · ${kindLabel(f.kind)}` })),
    ...rest.map((f) => ({ value: f.id, label: f.title, group: bound.size ? qs("otherForms") : undefined }))
  ];
}

/**
 * The start sheet (DESIGN §4.2). No title field: the generated title is previewed above Start as it
 * will be saved. The consent notice shows inline for a chosen artisan who has not agreed explicitly
 * (consent is assumed by default, with Switch off beside it) or who refused, and never blocks Start. The session is created through the outbox, so Start works offline.
 */
type CoverageGrid = Awaited<ReturnType<typeof questionnaireApi.coverage>>;
/**
 * The preview's input, built as the server builds the title (audit, title preview): the workshop's
 * locality, district and single craft, and each artisan's craft — not the workshop's free-text place.
 */
export function titlePreviewInput(input: {
  sessionDate: string;
  place: string;
  workshop: PortalWorkshop | null;
  artisans: { name: string; localName?: string | null; craft?: { name: string } | null }[];
}) {
  return {
    sessionDate: input.sessionDate,
    place: input.place.trim() || null,
    workshop: titleWorkshop(input.workshop),
    participants: input.artisans.map((a) => ({ name: a.name, localName: a.localName ?? null, craft: a.craft?.name ?? null }))
  };
}

export function SessionStartSheet({
  initialArtisanIds = [],
  initialQuestionnaireId = null,
  initialWorkshopId = null,
  onStarted
}: {
  initialArtisanIds?: string[];
  /** A form picked before the sheet opened (a form's own page, an old answer link). */
  initialQuestionnaireId?: string | null;
  /** A workshop picked before the sheet opened (the workshop's own Questionnaires card). */
  initialWorkshopId?: string | null;
  onStarted?: (key: string) => void;
}) {
  const router = useRouter();
  const { user } = useAuth();
  const queue = useSessionQueue(null);
  const ids = { workshop: useId(), instrument: useId(), date: useId(), artisans: useId(), place: useId(), language: useId(), other: useId(), note: useId() };
  const [workshops, setWorkshops] = useState<PortalWorkshop[]>([]);
  const [instruments, setInstruments] = useState<PortalForm[]>([]);
  const [resolved, setResolved] = useState<FormFor | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [search, setSearch] = useState("");
  const [form, setForm] = useState<StartSheetState>({
    workshopId: initialWorkshopId ?? "",
    questionnaireId: initialQuestionnaireId ?? "",
    sessionDate: todayLocal(),
    mode: initialArtisanIds.length > 1 ? "GROUP" : "INDIVIDUAL",
    guestName: "",
    artisanIds: initialArtisanIds,
    purpose: "FIRST_SITTING",
    place: "",
    languageCode: "",
    languageOther: "",
    note: ""
  });
  const [consentAsked, setConsentAsked] = useState<Record<string, "ASK" | "REFUSED" | "DONE">>({});
  // The coverage grid and the artisan ids it was asked for; the names are laid over it on render.
  const [coverageGrid, setCoverageGrid] = useState<{ ids: string[]; grid: CoverageGrid } | null>(null);
  // No signal: the held snapshot's count per artisan, with the outbox laid over it, and its age.
  const [coverageHeld, setCoverageHeld] = useState<{ ids: string[]; total: number; covered: Record<string, number>; age: string } | null>(null);
  const [openMatch, setOpenMatch] = useState<OpenMatchItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = <K extends keyof StartSheetState>(key: K, value: StartSheetState[K]) => setForm((f) => ({ ...f, [key]: value }));

  useEffect(() => {
    let cancelled = false;
    // Offline (or on a failed read) the lists last read on this device stand in, so Start works with
    // no signal (audit M4). The held workshop rows carry every field the default-workshop rule reads.
    const held = readStartLists();
    void Promise.all([
      listWorkshops()
        .then((items) => {
          writeStartLists({ workshops: items.map(trimWorkshop) });
          return items;
        })
        .catch(() => held.workshops),
      listForms()
        .then((list) => {
          writeStartLists({ instruments: list });
          return list;
        })
        .catch(() => held.instruments)
    ]).then(async ([items, q]) => {
      if (cancelled) return;
      setWorkshops(items);
      setInstruments(q);
      // B30: the workshop most recently given to this account (else the latest one that may still be
      // recorded in) is the default; a pick already made stands. The form follows from it below.
      const recent = await defaultWorkshopId(items);
      if (cancelled) return;
      setForm((f) => ({
        ...f,
        workshopId: f.workshopId || recent || "",
        questionnaireId: f.questionnaireId || (recent ? "" : q.find((i) => i.isDefault)?.id || "")
      }));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // The artisan page is asked for again only when the search or the workshop changes. Ticking an
  // artisan re-applies the same merge to the page already held, without a request.
  const artisanIdsRef = useRef<readonly string[]>(form.artisanIds);
  const artisanPage = useRef<Artisan[] | null>(null);
  useEffect(() => {
    let cancelled = false;
    const workshopId = form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null;
    const timer = window.setTimeout(() => {
      void listResource<Artisan>("/artisans", { pageSize: 80, search: search || undefined, ...artisanFilter(workshopId) })
        .then((page) => {
          const items = page.items ?? [];
          writeStartLists({ artisans: items.map((a) => trimArtisan({ ...a, workshopId })) });
          if (cancelled) return;
          artisanPage.current = items;
          setArtisans((prev) => mergeById(prev.filter((a) => artisanIdsRef.current.includes(a.id)), items));
        })
        .catch(() => {
          // No signal: search the artisans this device has seen.
          const found = searchCachedArtisans(readStartLists().artisans, search, workshopId) as unknown as Artisan[];
          if (cancelled) return;
          artisanPage.current = found;
          setArtisans((prev) => mergeById(prev.filter((a) => artisanIdsRef.current.includes(a.id)), found));
        });
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, form.workshopId]);
  const artisanKey = form.artisanIds.join(",");
  useEffect(() => {
    const selected = artisanKey ? artisanKey.split(",") : [];
    artisanIdsRef.current = selected;
    const held = artisanPage.current;
    if (!held) return;
    setArtisans((prev) => mergeById(prev.filter((a) => selected.includes(a.id)), held));
  }, [artisanKey]);

  // The form follows the workshop, in the shared order (UNIFY 2.3 rule 5, owner amendment 2): its
  // primary binding, its only binding, its type's default, the global default; with several bound
  // and none primary the picker shows them first, grouped by kind. Any other form may still be picked.
  useEffect(() => {
    let cancelled = false;
    const workshopId = form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null;
    void questionnaireApi
      .formFor({ ...workshopScope(workshopId), questionnaireId: initialQuestionnaireId })
      .then((r) => {
        if (cancelled) return;
        setResolved(r);
        if (r.questionnaireId) setForm((f) => ({ ...f, questionnaireId: r.questionnaireId! }));
      })
      .catch(() => {
        if (!cancelled) setResolved(null);
      });
    return () => {
      cancelled = true;
    };
  }, [form.workshopId, initialQuestionnaireId]);

  // Consent: tell the researcher for each chosen artisan with no answer (assumed, can be switched off) or a refusal.
  // Asked once per artisan: an id is marked before its request goes out, so an answer landing for
  // one artisan does not re-ask for the others still in flight.
  const consentRequested = useRef(new Set<string>());
  useEffect(() => {
    for (const id of form.artisanIds) {
      if (consentAsked[id] || consentRequested.current.has(id)) continue;
      consentRequested.current.add(id);
      void questionnaireApi
        .getConsent(id)
        .then((c) => setConsentAsked((m) => ({ ...m, [id]: c.state === "GRANTED" ? "DONE" : c.state === "REFUSED" ? "REFUSED" : "ASK" })))
        .catch(() => setConsentAsked((m) => ({ ...m, [id]: "DONE" })));
    }
  }, [form.artisanIds, consentAsked]);

  // The instrument's sections and question lineages, held for the offline coverage line.
  useEffect(() => {
    if (!form.questionnaireId) return;
    const questionnaireId = form.questionnaireId;
    void loadInstrumentQuestions(questionnaireId)
      .then((q) => writeStartLists({ sections: { [questionnaireId]: Object.fromEntries(q.sections.map((s) => [s.id, (q.byId[s.id] ?? []).map((x) => x.lineageId)])) } }))
      .catch(() => undefined);
  }, [form.questionnaireId]);

  // Coverage line under the artisan field: the server's, else the held snapshot with the outbox laid
  // over it, marked "Checked … ago" (DESIGN §4.2, audit M4). Asked once per change of instrument,
  // workshop or selection; the artisan names are read from `artisans` on render.
  const unitsRef = useRef(queue.units);
  useEffect(() => {
    unitsRef.current = queue.units;
  }, [queue.units]);
  useEffect(() => {
    if (!form.questionnaireId || !artisanKey) {
      setCoverageGrid(null);
      setCoverageHeld(null);
      return;
    }
    let cancelled = false;
    const artisanIds = artisanKey.split(",");
    const questionnaireId = form.questionnaireId;
    void questionnaireApi
      .coverage({ questionnaireId, artisanIds })
      .then((grid) => {
        if (cancelled) return;
        setCoverageHeld(null);
        setCoverageGrid({ ids: artisanIds, grid });
      })
      .catch(() => {
        if (cancelled) return;
        setCoverageGrid(null);
        const workshopId = form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null;
        const held = readSnapshot(questionnaireId, workshopId);
        const sections = readStartLists().sections[questionnaireId];
        if (!held || !sections) {
          setCoverageHeld(null);
          return;
        }
        const takes = withTakes(held.snapshot, overlayFromUnits(unitsRef.current)).takes;
        setCoverageHeld({
          ids: artisanIds,
          total: Object.keys(sections).length,
          covered: Object.fromEntries(artisanIds.map((id) => [id, coveredSectionsOffline(takes, id, sections)])),
          age: agoLabel(held.at)
        });
      });
    return () => {
      cancelled = true;
    };
  }, [form.questionnaireId, form.workshopId, artisanKey]);
  const coverage = useMemo<ArtisanCoverage[]>(() => {
    const nameOf = (id: string) => artisans.find((a) => a.id === id)?.name ?? "";
    if (coverageHeld) return coverageHeld.ids.map((id) => ({ name: nameOf(id), total: coverageHeld.total, covered: coverageHeld.covered[id] ?? 0 }));
    if (!coverageGrid) return [];
    const { ids: coveredIds, grid } = coverageGrid;
    const total = grid.sections.length;
    const cells = grid.sections.flatMap((s) => s.cells);
    return coveredIds.map((id) => ({
      name: nameOf(id) || grid.artisans?.find((a) => a.artisanId === id)?.name || "",
      total,
      covered: cells.filter((c) => c.artisanId === id && (c.state === "RECORDED" || (c.state === "PARTIAL" && c.total !== null && c.covered === c.total))).length
    }));
  }, [coverageGrid, coverageHeld, artisans]);
  const coverageAge = coverageHeld?.age ?? null;

  const workshop = workshops.find((w) => w.id === form.workshopId) ?? null;
  const chosen = form.artisanIds.map((id) => artisans.find((a) => a.id === id)).filter((a): a is Artisan => Boolean(a));
  const preview = useMemo(
    () => sessionTitle(titlePreviewInput({ sessionDate: form.sessionDate, place: form.place, workshop, artisans: chosen })),
    [form.sessionDate, form.place, workshop, chosen]
  );

  const workshopOptions = [{ value: FIELD_VISIT, label: NO_WORKSHOP_LABEL }, ...workshops.map((w) => ({ value: w.id, label: w.title }))];
  const instrumentOptions = formOptions(instruments, resolved);
  const artisanOptions = artisans.map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }));
  const languageOptions = LANGUAGES.map((l) => ({ value: l.code, label: l.label }));

  async function start(skipMatch = false) {
    if (startBlocked(form)) return;
    setError(null);
    setBusy(true);
    try {
      if (!skipMatch && form.mode !== "GUEST" && navigator.onLine !== false) {
        const match = await questionnaireApi
          .openMatch({ questionnaireId: form.questionnaireId, ...workshopScope(form.workshopId === FIELD_VISIT ? null : form.workshopId || null), artisanIds: form.artisanIds, date: form.sessionDate })
          .catch(() => ({ items: [] as OpenMatchItem[] }));
        if (match.items.length) {
          setOpenMatch(match.items[0]);
          setBusy(false);
          return;
        }
      }
      const clientKey = mintKey("c");
      const unit = newSessionUnit(sessionCreateBody(form, clientKey));
      const landed = new Promise<Session | null>((resolve) => {
        const off = onSessionLanded((s) => {
          if (s.clientKey === clientKey) {
            off();
            resolve(s);
          }
        });
        window.setTimeout(() => resolve(null), 6000);
      });
      await enqueueUnit(unit);
      const session = await landed;
      onStarted?.(session?.id ?? clientKey);
      router.push(`/questionnaire/sessions/${encodeURIComponent(session?.id ?? clientKey)}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  }

  const p1 = openMatch
    ? prompts.p1({
        researcher: openMatch.researcher?.name ?? "",
        artisans: joinNames(openMatch.artisanNames),
        time: openMatch.startedAt ? new Date(openMatch.startedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" }) : ""
      })
    : null;

  return (
    <form
      className="panel grid gap-4 p-4"
      data-start-sheet
      onSubmit={(event) => {
        event.preventDefault();
        void start();
      }}
    >
      <div className="grid gap-4 md:grid-cols-2">
        <div className="grid gap-1">
          <label htmlFor={ids.workshop} className="field-label">
            {WORKSHOP_LABEL}
          </label>
          <SearchableSelect id={ids.workshop} value={form.workshopId} onChange={(v) => set("workshopId", v)} options={workshopOptions} searchable ariaLabel={WORKSHOP_LABEL} />
        </div>
        <div className="grid gap-1">
          <label htmlFor={ids.instrument} className="field-label">
            {qs("fieldInstrument")}
          </label>
          <SearchableSelect id={ids.instrument} value={form.questionnaireId} onChange={(v) => set("questionnaireId", v)} options={instrumentOptions} searchable ariaLabel={qs("fieldInstrument")} />
        </div>
        <div className="grid gap-1">
          <label htmlFor={ids.date} className="field-label">
            {qs("fieldDate")}
          </label>
          <CalendarField id={ids.date} value={form.sessionDate} onChange={(v) => set("sessionDate", v)} required clearable={false} />
        </div>
        <div className="grid gap-1">
          <span className="field-label">{qs("fieldMode")}</span>
          <SegmentedChoice
            label={qs("fieldMode")}
            value={form.mode}
            options={[
              { value: "INDIVIDUAL", label: qs("modeIndividual") },
              { value: "GROUP", label: qs("modeGroup") },
              { value: "GUEST", label: qs("modeSomeoneElse") }
            ]}
            onChange={(v) => setForm((f) => ({ ...f, mode: v as StartSheetState["mode"], artisanIds: constrainArtisans(v as StartSheetState["mode"], f.artisanIds) }))}
          />
        </div>
      </div>

      {form.mode === "GUEST" ? (
        <div className="grid gap-1">
          <label htmlFor={ids.artisans} className="field-label">
            {qs("fieldGuestName")}
          </label>
          <input
            id={ids.artisans}
            className="field-input"
            value={form.guestName}
            maxLength={120}
            autoComplete="off"
            placeholder={qs("guestNamePlaceholder")}
            onChange={(e) => set("guestName", e.target.value)}
            data-guest-name
          />
          <p className="text-xs text-ink-500">{qs("guestHint")}</p>
        </div>
      ) : (
      <div className="grid gap-1">
        <label htmlFor={ids.artisans} className="field-label">
          {form.mode === "GROUP" ? qs("fieldArtisans") : qs("fieldArtisan")}
        </label>
        {form.mode === "GROUP" ? (
          // Group mode searches the server through the box above the picker (audit M22): a filter over
          // the 80 rows in hand would answer "No matches" about artisans that exist.
          <div className="grid gap-2">
            <SearchInput value={search} onChange={setSearch} placeholder={qs("fieldArtisans")} />
            <SearchableMultiSelect values={form.artisanIds} onChange={(v) => set("artisanIds", v)} options={artisanOptions} searchable={false} ariaLabel={qs("fieldArtisans")} />
          </div>
        ) : (
          <SearchableSelect
            id={ids.artisans}
            value={form.artisanIds[0] ?? ""}
            onChange={(v) => set("artisanIds", v ? [v] : [])}
            options={artisanOptions}
            searchable
            onSearch={setSearch}
            ariaLabel={qs("fieldArtisan")}
          />
        )}
        {coverage.length ? (
          <p className="text-sm text-ink-500" data-coverage-line>
            {coverageLine(coverage, coverageAge)}
          </p>
        ) : null}
        {chosen
          .filter((a) => consentAsked[a.id] === "ASK" || consentAsked[a.id] === "REFUSED")
          .map((a) => (
            <ConsentQuestion
              key={a.id}
              artisanId={a.id}
              name={a.name}
              refused={consentAsked[a.id] === "REFUSED"}
              onDismiss={() => setConsentAsked((m) => ({ ...m, [a.id]: "DONE" }))}
            />
          ))}
      </div>
      )}

      <div className="grid gap-1">
        <span className="field-label">{qs("fieldPurpose")}</span>
        <SegmentedChoice
          label={qs("fieldPurpose")}
          value={form.purpose}
          options={SESSION_PURPOSES.map((p) => ({ value: p, label: qs(PURPOSE_KEY[p]) }))}
          onChange={(v) => set("purpose", v as SessionPurpose)}
        />
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="grid gap-1">
          <label htmlFor={ids.place} className="field-label">
            {qs("fieldPlace")}
          </label>
          <input id={ids.place} className="field-input" value={form.place} onChange={(e) => set("place", e.target.value)} autoComplete="off" />
          <DeviceLocation value={form.location ?? null} onChange={(location) => set("location", location)} />
        </div>
        <div className="grid gap-1">
          <label htmlFor={ids.language} className="field-label">
            {qs("fieldLanguage")}
          </label>
          <SearchableSelect id={ids.language} value={form.languageCode} onChange={(v) => set("languageCode", v)} options={languageOptions} clearable ariaLabel={qs("fieldLanguage")} />
          {form.languageCode === "other" ? (
            <input
              id={ids.other}
              className="field-input mt-2"
              aria-label={qs("languageOtherLabel")}
              placeholder={qs("languageOtherLabel")}
              value={form.languageOther}
              maxLength={40}
              onChange={(e) => set("languageOther", e.target.value)}
            />
          ) : null}
        </div>
      </div>

      <div className="grid gap-1">
        <label htmlFor={ids.note} className="field-label">
          {qs("fieldNote")}
        </label>
        <input
          id={ids.note}
          className="field-input"
          maxLength={80}
          placeholder={qs("notePlaceholder")}
          value={form.note}
          onChange={(e) => set("note", e.target.value)}
        />
      </div>

      <div className="grid gap-1 text-center">
        <span className="field-label">{qs("titlePreviewLabel")}</span>
        <p className="break-all font-display text-sm text-ink-900" data-title-preview>
          {preview}
        </p>
      </div>

      {error ? <p className="text-center text-sm text-error-600">{error}</p> : null}
      <ButtonRow>
        <button type="submit" className="field-button" disabled={busy || startBlocked(form)} aria-describedby={startBlocked(form) ? `${ids.note}-needs` : undefined}>
          {qs("startSession")}
        </button>
      </ButtonRow>
      {startBlocked(form) ? (
        <p id={`${ids.note}-needs`} className="text-center text-xs text-ink-500">
          {qs("startNeeds")}
        </p>
      ) : null}

      {p1 && openMatch ? (
        <PromptDialog
          spec={p1}
          open
          onChoose={async (choice) => {
            const match = openMatch;
            setOpenMatch(null);
            if (choice === "join" && match) {
              // Joining adds the reader to the session's researchers (DESIGN 3.7); a repeat changes nothing.
              if (user) await questionnaireApi.addResearcher(match.id, { userId: user.id, role: "CONTRIBUTOR" }).catch(() => undefined);
              router.push(`/questionnaire/sessions/${encodeURIComponent(match.id)}`);
            } else if (choice === "new") {
              void start(true);
            }
          }}
        />
      ) : null}
    </form>
  );
}

/** "Giriraj", "Giriraj and Meena", "Giriraj, Meena and Ravi". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

function mergeById<T extends { id: string }>(a: T[], b: T[]): T[] {
  const seen = new Map<string, T>();
  for (const x of [...a, ...b]) seen.set(x.id, x);
  return [...seen.values()];
}

/**
 * C9: "Use my location" — the device's fix, kept with the session as a Location beside the typed
 * place. Asked only here, at the start; an edit never captures one (DR audit H1). A browser that
 * cannot or will not answer says so, and the typed place still stands.
 */
export function DeviceLocation({ value, onChange }: { value: SessionLocation | null; onChange: (location: SessionLocation | null) => void }) {
  const [state, setState] = useState<"idle" | "finding" | "unavailable">("idle");
  if (value) {
    return (
      <p className="flex flex-wrap items-center gap-2 text-xs text-ink-600" data-device-location="saved">
        <span>{qs("locationCaptured", { metres: Math.max(1, Math.round(value.accuracy ?? 0)) })}</span>
        <button type="button" className="font-medium text-purple-700 underline dark:text-purple-300" onClick={() => onChange(null)}>
          {qs("locationRemove")}
        </button>
      </p>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2 text-xs" data-device-location={state}>
      <button
        type="button"
        className="field-button-secondary"
        disabled={state === "finding"}
        onClick={() => {
          if (typeof navigator === "undefined" || !navigator.geolocation) {
            setState("unavailable");
            return;
          }
          setState("finding");
          navigator.geolocation.getCurrentPosition(
            (position) => {
              setState("idle");
              onChange({
                latitude: position.coords.latitude,
                longitude: position.coords.longitude,
                accuracy: Number.isFinite(position.coords.accuracy) ? position.coords.accuracy : null,
                altitude: position.coords.altitude ?? null
              });
            },
            () => setState("unavailable"),
            { enableHighAccuracy: true, timeout: 20_000, maximumAge: 60_000 }
          );
        }}
      >
        {state === "finding" ? qs("locationFinding") : qs("useMyLocation")}
      </button>
      {state === "unavailable" ? <span className="text-ink-600">{qs("locationUnavailable")}</span> : null}
    </div>
  );
}
