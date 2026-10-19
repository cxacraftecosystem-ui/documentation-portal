"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { enqueueUnit, onSessionLanded } from "@/components/questionnaire/useSessionQueue";
import { CalendarField } from "@/components/ui/CalendarField";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { apiFetch, listResource } from "@/lib/api";
import { coverageLine, type ArtisanCoverage } from "@/lib/coverageCache";
import { LANGUAGES } from "@/lib/languages";
import { sessionTitle } from "@/lib/naming";
import { mintKey, questionnaireApi, SESSION_PURPOSES, type OpenMatchItem, type Session, type SessionCreate, type SessionPurpose } from "@/lib/questionnaireApi";
import { prompts } from "@/lib/questionnairePrompts";
import { newSessionUnit } from "@/lib/questionnaireQueue";
import { PURPOSE_KEY, qs } from "@/lib/strings/qs";
import type { Artisan, Questionnaire, Workshop } from "@/lib/types";

export const FIELD_VISIT = "__field_visit__";

export type StartSheetState = {
  workshopId: string;
  questionnaireId: string;
  sessionDate: string;
  mode: "INDIVIDUAL" | "GROUP";
  artisanIds: string[];
  purpose: SessionPurpose;
  place: string;
  languageCode: string;
  languageOther: string;
  note: string;
};

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Start is allowed once the instrument, the date and at least one artisan are set (DESIGN test 36). */
export function startBlocked(form: Pick<StartSheetState, "questionnaireId" | "sessionDate" | "artisanIds">): boolean {
  return !form.questionnaireId || !form.sessionDate || form.artisanIds.length === 0;
}

/** Individual keeps one artisan; switching from Group trims the pick to its first. */
export function constrainArtisans(mode: StartSheetState["mode"], ids: string[]): string[] {
  return mode === "INDIVIDUAL" ? ids.slice(0, 1) : ids;
}

/** The create body. Never a title: the server generates it (TITLE_IS_GENERATED otherwise). */
export function sessionCreateBody(form: StartSheetState, clientKey: string, now = new Date()): SessionCreate {
  const note = form.note.trim().slice(0, 80);
  return {
    clientKey,
    questionnaireId: form.questionnaireId,
    workshopId: form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null,
    designWorkshopId: null,
    sessionDate: form.sessionDate,
    startedAt: now.toISOString(),
    purpose: form.purpose,
    place: form.place.trim() || null,
    languageCode: form.languageCode || null,
    languageOther: form.languageCode === "other" ? form.languageOther.trim() || null : null,
    note: note || null,
    participants: form.artisanIds.map((artisanId) => ({ artisanId }))
  };
}

/**
 * The start sheet (DESIGN §4.2). No title field: the generated title is previewed above Start as it
 * will be saved. The consent notice shows inline for a chosen artisan who has not agreed explicitly
 * (consent is assumed by default, with Switch off beside it) or who refused, and never blocks Start. The session is created through the outbox, so Start works offline.
 */
export function SessionStartSheet({ initialArtisanIds = [], onStarted }: { initialArtisanIds?: string[]; onStarted?: (key: string) => void }) {
  const router = useRouter();
  const { user } = useAuth();
  const ids = { workshop: useId(), instrument: useId(), date: useId(), artisans: useId(), place: useId(), language: useId(), other: useId(), note: useId() };
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [search, setSearch] = useState("");
  const [form, setForm] = useState<StartSheetState>({
    workshopId: "",
    questionnaireId: "",
    sessionDate: todayLocal(),
    mode: initialArtisanIds.length > 1 ? "GROUP" : "INDIVIDUAL",
    artisanIds: initialArtisanIds,
    purpose: "FIRST_SITTING",
    place: "",
    languageCode: "",
    languageOther: "",
    note: ""
  });
  const [consentAsked, setConsentAsked] = useState<Record<string, "ASK" | "REFUSED" | "DONE">>({});
  const [coverage, setCoverage] = useState<ArtisanCoverage[]>([]);
  const [openMatch, setOpenMatch] = useState<OpenMatchItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = <K extends keyof StartSheetState>(key: K, value: StartSheetState[K]) => setForm((f) => ({ ...f, [key]: value }));

  useEffect(() => {
    let cancelled = false;
    void Promise.all([
      listResource<Workshop>("/workshops", { pageSize: 100 }).catch(() => ({ items: [] as Workshop[] })),
      apiFetch<Questionnaire[]>("/questionnaires").catch(() => [] as Questionnaire[])
    ]).then(([w, q]) => {
      if (cancelled) return;
      setWorkshops(w.items ?? []);
      setInstruments(q);
      setForm((f) => (f.questionnaireId ? f : { ...f, questionnaireId: q.find((i) => i.isDefault)?.id ?? q[0]?.id ?? "" }));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void listResource<Artisan>("/artisans", { pageSize: 80, search: search || undefined, workshopId: form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : undefined })
        .then((page) => {
          if (!cancelled) setArtisans((prev) => mergeById(prev.filter((a) => form.artisanIds.includes(a.id)), page.items ?? []));
        })
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, form.workshopId, form.artisanIds]);

  // The workshop's bound instrument is the default.
  useEffect(() => {
    const w = workshops.find((x) => x.id === form.workshopId);
    if (w?.questionnaireId) setForm((f) => ({ ...f, questionnaireId: w.questionnaireId! }));
  }, [form.workshopId, workshops]);

  // Consent: tell the researcher for each chosen artisan with no answer (assumed, can be switched off) or a refusal.
  useEffect(() => {
    for (const id of form.artisanIds) {
      if (consentAsked[id]) continue;
      void questionnaireApi
        .getConsent(id)
        .then((c) => setConsentAsked((m) => ({ ...m, [id]: c.state === "GRANTED" ? "DONE" : c.state === "REFUSED" ? "REFUSED" : "ASK" })))
        .catch(() => setConsentAsked((m) => ({ ...m, [id]: "DONE" })));
    }
  }, [form.artisanIds, consentAsked]);

  // Coverage line under the artisan field.
  useEffect(() => {
    if (!form.questionnaireId || form.artisanIds.length === 0) {
      setCoverage([]);
      return;
    }
    let cancelled = false;
    void questionnaireApi
      .coverage({ questionnaireId: form.questionnaireId, artisanIds: form.artisanIds })
      .then((grid) => {
        if (cancelled) return;
        const total = grid.sections.length;
        const cells = grid.sections.flatMap((s) => s.cells);
        setCoverage(
          form.artisanIds.map((id) => ({
            name: artisans.find((a) => a.id === id)?.name ?? grid.artisans?.find((a) => a.artisanId === id)?.name ?? "",
            total,
            covered: cells.filter((c) => c.artisanId === id && (c.state === "RECORDED" || (c.state === "PARTIAL" && c.total !== null && c.covered === c.total))).length
          }))
        );
      })
      .catch(() => setCoverage([]));
    return () => {
      cancelled = true;
    };
  }, [form.questionnaireId, form.artisanIds, artisans]);

  const workshop = workshops.find((w) => w.id === form.workshopId) ?? null;
  const chosen = form.artisanIds.map((id) => artisans.find((a) => a.id === id)).filter((a): a is Artisan => Boolean(a));
  const preview = useMemo(
    () =>
      sessionTitle({
        sessionDate: form.sessionDate,
        place: form.place || null,
        workshop: workshop ? { district: workshop.place } : null,
        participants: chosen.map((a) => ({ name: a.name }))
      }),
    [form.sessionDate, form.place, workshop, chosen]
  );

  const workshopOptions = [{ value: FIELD_VISIT, label: qs("fieldVisitNoWorkshop") }, ...workshops.map((w) => ({ value: w.id, label: w.title }))];
  const instrumentOptions = instruments.map((i) => ({ value: i.id, label: i.title }));
  const artisanOptions = artisans.map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }));
  const languageOptions = LANGUAGES.map((l) => ({ value: l.code, label: l.label }));

  async function start(skipMatch = false) {
    if (startBlocked(form)) return;
    setError(null);
    setBusy(true);
    try {
      if (!skipMatch && navigator.onLine !== false) {
        const match = await questionnaireApi
          .openMatch({ questionnaireId: form.questionnaireId, workshopId: form.workshopId === FIELD_VISIT ? null : form.workshopId || null, artisanIds: form.artisanIds, date: form.sessionDate })
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
            {qs("fieldWorkshop")}
          </label>
          <SearchableSelect id={ids.workshop} value={form.workshopId} onChange={(v) => set("workshopId", v)} options={workshopOptions} searchable ariaLabel={qs("fieldWorkshop")} />
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
              { value: "GROUP", label: qs("modeGroup") }
            ]}
            onChange={(v) => setForm((f) => ({ ...f, mode: v as StartSheetState["mode"], artisanIds: constrainArtisans(v as StartSheetState["mode"], f.artisanIds) }))}
          />
        </div>
      </div>

      <div className="grid gap-1">
        <label htmlFor={ids.artisans} className="field-label">
          {form.mode === "GROUP" ? qs("fieldArtisans") : qs("fieldArtisan")}
        </label>
        {form.mode === "GROUP" ? (
          <SearchableMultiSelect values={form.artisanIds} onChange={(v) => set("artisanIds", v)} options={artisanOptions} searchable ariaLabel={qs("fieldArtisans")} />
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
            {coverageLine(coverage)}
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
