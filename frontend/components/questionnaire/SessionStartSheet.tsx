"use client";

import { useRouter } from "next/navigation";
import { Link as LinkIcon, X } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { loadInstrumentQuestions } from "@/components/questionnaire/instrumentQuestions";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { placeFromLocation, SessionPlaceField, sessionLocationFromForm } from "@/components/questionnaire/SessionPlaceField";
import { enqueueUnit, onSessionLanded, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { SearchInput } from "@/components/SearchInput";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { CalendarField } from "@/components/ui/CalendarField";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { listResource } from "@/lib/api";
import { agoLabel, coveredSectionsOffline, coverageLine, overlayFromUnits, readSnapshot, withTakes, type ArtisanCoverage } from "@/lib/questionnaire/coverageCache";
import { LANGUAGES } from "@/lib/questionnaire/languages";
import { sessionTitle } from "@/lib/naming";
import {
  mintKey,
  questionnaireApi,
  RECORD_BY,
  SESSION_PURPOSES,
  type FormFor,
  type OpenMatchItem,
  type RecordBy,
  type RespondentKind,
  type Session,
  type SessionCreate,
  type SessionLocation,
  type SessionPurpose
} from "@/lib/questionnaire/api";
import { prompts } from "@/lib/questionnaire/prompts";
import { newSessionUnit } from "@/lib/questionnaire/queue";
import { readStartLists, searchCachedArtisans, titleWorkshop, trimArtisan, trimWorkshop, writeStartLists } from "@/lib/questionnaire/startSheetCache";
import { PURPOSE_KEY, qs } from "@/lib/questionnaire/qs";
import { SpokenLanguagePicker, useSpokenLanguageChoice } from "@/components/transcript/SpokenLanguagePicker";
import { rememberSessionSpokenLanguage, spokenChoiceOf } from "@/lib/transcript/spokenLanguage";
import {
  artisanFilter,
  defaultWorkshopId,
  listForms,
  listWorkshops,
  NewArtisanDialog,
  NO_WORKSHOP_LABEL,
  WORKSHOP_LABEL,
  workshopScope,
  type PortalForm,
  type PortalWorkshop
} from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

export const FIELD_VISIT = "__field_visit__";

export type StartMode = "INDIVIDUAL" | "GROUP";

export type StartGuest = { localId: string; name: string; kind: RespondentKind; notes: string };

export type StartSheetState = {
  workshopId: string;
  questionnaireId: string;
  sessionDate: string;
  mode: StartMode;
  artisanIds: string[];
  guests?: StartGuest[];
  recordBy?: RecordBy;
  purpose: SessionPurpose;
  place: string;
  location?: SessionLocation | null;
  crafts?: string[];
  craftsByHand?: string[];
  languageCode: string;
  languageOther: string;
  note: string;
};

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function guestNamed(guest: StartGuest): boolean {
  return guest.kind === "SELF" || guest.name.trim().length > 0;
}

export function startBlocked(form: Pick<StartSheetState, "questionnaireId" | "sessionDate" | "artisanIds"> & Partial<Pick<StartSheetState, "guests">>): boolean {
  if (!form.questionnaireId || !form.sessionDate) return true;
  return form.artisanIds.length === 0 && !(form.guests ?? []).some(guestNamed);
}

export function constrainArtisans(mode: StartMode, ids: string[]): string[] {
  return mode === "INDIVIDUAL" ? ids.slice(0, 1) : ids;
}

type Subjects = Pick<StartSheetState, "mode" | "artisanIds" | "guests">;

export function withMode<T extends Subjects>(form: T, mode: StartMode): T {
  const artisanIds = constrainArtisans(mode, form.artisanIds);
  const guests = mode === "INDIVIDUAL" && form.artisanIds.length > 0 ? [] : form.guests ?? [];
  return { ...form, mode, artisanIds, guests };
}

export function withGuest<T extends Subjects>(form: T, guest: StartGuest): T {
  if (form.mode === "GROUP") return { ...form, guests: [...(form.guests ?? []), guest] };
  return { ...form, artisanIds: [], guests: [guest] };
}

export function withArtisans<T extends Subjects>(form: T, ids: string[]): T {
  if (form.mode === "GROUP") return { ...form, artisanIds: ids };
  return { ...form, artisanIds: ids.slice(0, 1), guests: ids.length ? [] : form.guests ?? [] };
}

export function withNewArtisan<T extends Subjects>(form: T, id: string): T {
  if (form.mode === "GROUP") return { ...form, artisanIds: [...form.artisanIds.filter((a) => a !== id), id] };
  return { ...form, artisanIds: [id], guests: [] };
}

export function respondentsFor(guests: StartGuest[], meName: string | null = null): NonNullable<SessionCreate["respondents"]> {
  return guests.filter(guestNamed).map((g) => {
    const raw = g.kind === "SELF" ? g.name.trim() || (meName ?? "").trim() : g.name.trim();
    const notes = g.notes.trim();
    return { name: raw.replace(/\s+/g, " ").slice(0, 120), kind: g.kind, notes: notes || null };
  });
}

export function sessionCreateBody(form: StartSheetState, clientKey: string, now = new Date(), meName: string | null = null): SessionCreate {
  const note = form.note.trim().slice(0, 80);
  return {
    clientKey,
    questionnaireId: form.questionnaireId,
    ...workshopScope(form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null),
    sessionDate: form.sessionDate,
    startedAt: now.toISOString(),
    purpose: form.purpose,
    place: placeFromLocation(form.place, form.location ?? null) || null,
    ...(form.location ? { location: form.location } : {}),
    crafts: form.crafts ?? [],
    craftsByHand: form.craftsByHand ?? [],
    languageCode: form.languageCode || null,
    languageOther: form.languageCode === "other" ? form.languageOther.trim() || null : null,
    note: note || null,
    participants: form.artisanIds.map((artisanId) => ({ artisanId })),
    respondents: respondentsFor(form.guests ?? [], meName),
    recordBy: form.recordBy ?? "SECTION"
  };
}

export type Provenance = { questionnaire: boolean; place: boolean; crafts: string | null; spoken: string | null };

export function workshopPlace(workshop: Pick<PortalWorkshop, "locality" | "district"> | null | undefined): string {
  return (workshop?.locality ?? "").trim() || (workshop?.district ?? "").trim();
}

const BOUND_REASONS = new Set(["PRIMARY_BINDING", "ONLY_BINDING"]);

export function provenanceOf(input: {
  workshopChosen: boolean;
  resolved: Pick<FormFor, "questionnaireId" | "reason"> | null;
  questionnaireId: string;
  questionnaireTouched: boolean;
  place: string;
  placeAuto: string | null;
  crafts: string[];
  craftsByHand: string[];
  craftSources: { name: string; craft: string | null | undefined }[];
  spoken: { value: string; code: string | null; source: "artisan" | "researcher" | null };
}): Provenance {
  const questionnaire =
    input.workshopChosen &&
    !input.questionnaireTouched &&
    Boolean(input.resolved?.questionnaireId) &&
    input.resolved?.questionnaireId === input.questionnaireId &&
    BOUND_REASONS.has(input.resolved?.reason ?? "");
  const place = Boolean(input.placeAuto) && input.place === input.placeAuto;
  const auto = craftsFor(input.craftSources.map((s) => s.craft), []);
  const lower = (list: string[]) => list.map((c) => c.toLowerCase()).join("|");
  const sameCrafts = auto.length > 0 && input.craftsByHand.length === 0 && lower(auto) === lower(input.crafts);
  const owners = [...new Set(input.craftSources.filter((s) => (s.craft ?? "").trim() && s.name.trim()).map((s) => s.name.trim()))];
  const crafts = sameCrafts && owners.length ? joinNames(owners) : null;
  const names = [...new Set(input.craftSources.map((s) => s.name.trim()).filter(Boolean))];
  const spokenFromRecord = input.spoken.source === "artisan" && input.spoken.code !== null && input.spoken.code === input.spoken.value;
  const spoken = spokenFromRecord && names.length ? joinNames(names) : null;
  return { questionnaire, place, crafts, spoken };
}

export const ALL_ARTISANS = "__all_records__";
export const WORKSHOP_ARTISANS = "__workshop_only__";

export function artisanScopeOption(workshopChosen: boolean, showAll: boolean): { value: string; label: string } | null {
  if (!workshopChosen) return null;
  return showAll ? { value: WORKSHOP_ARTISANS, label: qs("showWorkshopOnly") } : { value: ALL_ARTISANS, label: qs("showAllRecords") };
}

export function craftsFor(artisanCrafts: (string | null | undefined)[], byHand: string[]): string[] {
  const seen = new Map<string, string>();
  for (const name of [...artisanCrafts, ...byHand]) {
    const clean = (name ?? "").trim();
    if (clean && !seen.has(clean.toLowerCase())) seen.set(clean.toLowerCase(), clean);
  }
  return [...seen.values()];
}

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

type CoverageGrid = Awaited<ReturnType<typeof questionnaireApi.coverage>>;
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
  initialQuestionnaireId?: string | null;
  initialWorkshopId?: string | null;
  onStarted?: (key: string) => void;
}) {
  const router = useRouter();
  const { user } = useAuth();
  const queue = useSessionQueue(null);
  const ids = { workshop: useId(), instrument: useId(), date: useId(), artisans: useId(), place: useId(), language: useId(), other: useId(), note: useId(), tag: useId() };
  const [workshops, setWorkshops] = useState<PortalWorkshop[]>([]);
  const [instruments, setInstruments] = useState<PortalForm[]>([]);
  const [resolved, setResolved] = useState<FormFor | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [search, setSearch] = useState("");
  const [showAll, setShowAll] = useState(false);
  const [craftNames, setCraftNames] = useState<string[]>([]);
  const formRef = useRef<HTMLFormElement | null>(null);
  const [form, setForm] = useState<StartSheetState>({
    workshopId: initialWorkshopId ?? "",
    questionnaireId: initialQuestionnaireId ?? "",
    sessionDate: todayLocal(),
    mode: initialArtisanIds.length > 1 ? "GROUP" : "INDIVIDUAL",
    artisanIds: initialArtisanIds,
    guests: [],
    recordBy: "SECTION",
    purpose: "FIRST_SITTING",
    place: "",
    languageCode: "",
    languageOther: "",
    note: ""
  });
  const [consentAsked, setConsentAsked] = useState<Record<string, "ASK" | "REFUSED" | "DONE">>({});
  const [coverageGrid, setCoverageGrid] = useState<{ ids: string[]; grid: CoverageGrid } | null>(null);
  const [coverageHeld, setCoverageHeld] = useState<{ ids: string[]; total: number; covered: Record<string, number>; age: string } | null>(null);
  const [openMatch, setOpenMatch] = useState<OpenMatchItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [questionnaireTouched, setQuestionnaireTouched] = useState(Boolean(initialQuestionnaireId));
  const [placeAuto, setPlaceAuto] = useState<string | null>(null);
  const placeAutoRef = useRef<string | null>(null);
  const [adding, setAdding] = useState<null | "artisan" | "guest">(null);

  const set = <K extends keyof StartSheetState>(key: K, value: StartSheetState[K]) => setForm((f) => ({ ...f, [key]: value }));
  const spoken = useSpokenLanguageChoice(form.artisanIds, { fallback: form.languageCode || null });

  useEffect(() => {
    let cancelled = false;
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

  const artisanIdsRef = useRef<readonly string[]>(form.artisanIds);
  const artisanPage = useRef<Artisan[] | null>(null);
  useEffect(() => {
    let cancelled = false;
    const workshopId = form.workshopId && form.workshopId !== FIELD_VISIT ? form.workshopId : null;
    const timer = window.setTimeout(() => {
      void listResource<Artisan>("/artisans", { pageSize: 80, search: search || undefined, ...(showAll ? {} : artisanFilter(workshopId)) })
        .then((page) => {
          const items = page.items ?? [];
          writeStartLists({ artisans: items.map((a) => trimArtisan({ ...a, workshopId })) });
          if (cancelled) return;
          artisanPage.current = items;
          setArtisans((prev) => mergeById(prev.filter((a) => artisanIdsRef.current.includes(a.id)), items));
        })
        .catch(() => {
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
  }, [search, form.workshopId, showAll]);

  useEffect(() => {
    let cancelled = false;
    void listResource<{ id: string; name: string }>("/crafts", { pageSize: 200 })
      .then((page) => {
        if (!cancelled) setCraftNames((page.items ?? []).map((c) => c.name).filter(Boolean));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  const artisanKey = form.artisanIds.join(",");
  useEffect(() => {
    const selected = artisanKey ? artisanKey.split(",") : [];
    artisanIdsRef.current = selected;
    const held = artisanPage.current;
    if (!held) return;
    setArtisans((prev) => mergeById(prev.filter((a) => selected.includes(a.id)), held));
  }, [artisanKey]);

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

  useEffect(() => {
    if (!form.questionnaireId) return;
    const questionnaireId = form.questionnaireId;
    void loadInstrumentQuestions(questionnaireId)
      .then((q) => writeStartLists({ sections: { [questionnaireId]: Object.fromEntries(q.sections.map((s) => [s.id, (q.byId[s.id] ?? []).map((x) => x.lineageId)])) } }))
      .catch(() => undefined);
  }, [form.questionnaireId]);

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
  const autoPlace = workshopPlace(workshop);
  useEffect(() => {
    const previous = placeAutoRef.current;
    placeAutoRef.current = autoPlace || null;
    setPlaceAuto(autoPlace || null);
    setForm((f) => (f.place.trim() && f.place !== previous ? f : f.place === autoPlace ? f : { ...f, place: autoPlace }));
  }, [autoPlace]);
  const chosen = form.artisanIds.map((id) => artisans.find((a) => a.id === id)).filter((a): a is Artisan => Boolean(a));
  const preview = useMemo(
    () => sessionTitle(titlePreviewInput({ sessionDate: form.sessionDate, place: form.place, workshop, artisans: chosen })),
    [form.sessionDate, form.place, workshop, chosen]
  );

  const workshopOptions = [{ value: FIELD_VISIT, label: NO_WORKSHOP_LABEL }, ...workshops.map((w) => ({ value: w.id, label: w.title }))];
  const instrumentOptions = formOptions(instruments, resolved);
  const workshopChosen = Boolean(form.workshopId && form.workshopId !== FIELD_VISIT);
  const scopeOption = artisanScopeOption(workshopChosen, showAll);
  const artisanOptions = [
    ...(scopeOption ? [scopeOption] : []),
    ...artisans.map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }))
  ];
  const autoCrafts = craftsFor(chosen.map((a) => a.craft?.name), []);
  const craftKey = autoCrafts.join("|");
  useEffect(() => {
    const fromArtisans = craftKey ? craftKey.split("|") : [];
    setForm((f) => {
      const next = craftsFor(fromArtisans, f.craftsByHand ?? []);
      return next.join("|") === (f.crafts ?? []).join("|") ? f : { ...f, crafts: next };
    });
  }, [craftKey]);
  const craftOptions = craftsFor([...craftNames, ...autoCrafts], form.crafts ?? []).map((name) => ({ value: name, label: name }));
  function pickArtisans(values: string[]) {
    if (values.includes(ALL_ARTISANS) || values.includes(WORKSHOP_ARTISANS)) {
      setShowAll(values.includes(ALL_ARTISANS));
      const rest = values.filter((v) => v !== ALL_ARTISANS && v !== WORKSHOP_ARTISANS);
      setForm((f) => (f.mode === "GROUP" ? { ...f, artisanIds: rest } : f));
      return;
    }
    setForm((f) => withArtisans(f, values));
  }
  const guests = form.guests ?? [];
  const hasSelf = guests.some((g) => g.kind === "SELF");
  const provenance = provenanceOf({
    workshopChosen,
    resolved,
    questionnaireId: form.questionnaireId,
    questionnaireTouched,
    place: form.place,
    placeAuto,
    crafts: form.crafts ?? [],
    craftsByHand: form.craftsByHand ?? [],
    craftSources: chosen.map((a) => ({ name: a.name, craft: a.craft?.name })),
    spoken: { value: spoken.value, code: spokenChoiceOf(spoken.suggestion?.code), source: spoken.suggestion?.source ?? null }
  });
  const languageOptions = LANGUAGES.map((l) => ({ value: l.code, label: l.label }));

  async function start(skipMatch = false) {
    if (startBlocked(form)) return;
    setError(null);
    setBusy(true);
    try {
      if (!skipMatch && form.artisanIds.length > 0 && navigator.onLine !== false) {
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
      rememberSessionSpokenLanguage(clientKey, spoken.value);
      const location = formRef.current ? sessionLocationFromForm(new FormData(formRef.current)) : null;
      const unit = newSessionUnit(sessionCreateBody({ ...form, location }, clientKey, new Date(), user?.name ?? null));
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
      ref={formRef}
      className="panel grid gap-4 p-4"
      data-start-sheet
      onSubmit={(event) => {
        event.preventDefault();
        void start();
      }}
    >
      <div className="grid gap-4 md:grid-cols-2">
        <div className="grid content-start gap-1">
          <label htmlFor={ids.workshop} className="field-label">
            {WORKSHOP_LABEL}
          </label>
          <SearchableSelect id={ids.workshop} value={form.workshopId} onChange={(v) => set("workshopId", v)} options={workshopOptions} searchable ariaLabel={WORKSHOP_LABEL} />
        </div>
        <div className="grid content-start gap-1">
          <label htmlFor={ids.instrument} className="field-label">
            {qs("fieldInstrument")}
          </label>
          <SearchableSelect
            id={ids.instrument}
            value={form.questionnaireId}
            onChange={(v) => {
              setQuestionnaireTouched(true);
              set("questionnaireId", v);
            }}
            options={instrumentOptions}
            searchable
            ariaLabel={qs("fieldInstrument")}
            describedBy={provenance.questionnaire ? `${ids.tag}-q` : undefined}
          />
          {provenance.questionnaire ? <ProvenanceTag id={`${ids.tag}-q`} text={qs("fromThisWorkshop")} /> : null}
        </div>
        <div className="grid content-start gap-1">
          <label htmlFor={ids.date} className="field-label">
            {qs("fieldDate")}
          </label>
          <CalendarField id={ids.date} value={form.sessionDate} onChange={(v) => set("sessionDate", v)} required clearable={false} />
        </div>
        <div className="grid content-start gap-1">
          <span className="field-label">{qs("fieldMode")}</span>
          <SegmentedChoice
            label={qs("fieldMode")}
            value={form.mode}
            options={[
              { value: "INDIVIDUAL", label: qs("modeIndividual") },
              { value: "GROUP", label: qs("modeGroup") }
            ]}
            onChange={(v) => setForm((f) => withMode(f, v as StartMode))}
          />
        </div>
      </div>

      <div className="grid gap-1">
        <label htmlFor={ids.artisans} className="field-label">
          {form.mode === "GROUP" ? qs("fieldArtisans") : qs("fieldArtisan")}
        </label>
        {form.mode === "GROUP" ? (
          <div className="grid gap-2">
            <SearchInput value={search} onChange={setSearch} placeholder={qs("fieldArtisans")} />
            <SearchableMultiSelect values={form.artisanIds} onChange={pickArtisans} options={artisanOptions} searchable={false} ariaLabel={qs("fieldArtisans")} />
          </div>
        ) : (
          <SearchableSelect
            id={ids.artisans}
            value={form.artisanIds[0] ?? ""}
            onChange={(v) => pickArtisans(v ? [v] : [])}
            options={artisanOptions}
            searchable
            onSearch={setSearch}
            ariaLabel={qs("fieldArtisan")}
          />
        )}
        {guests.length ? (
          <ul className="mt-1 flex flex-wrap gap-1.5" aria-label={qs("modeSomeoneElse")} data-start-guests>
            {guests.map((g) => {
              const label = g.kind === "SELF" ? qs("myself") : g.name.trim();
              return (
                <li
                  key={g.localId}
                  className="inline-flex items-center gap-0.5 rounded-full border border-purple-200 bg-purple-50 pl-3 text-sm font-medium text-purple-800 dark:border-purple-800 dark:bg-purple-950/40 dark:text-purple-200"
                  data-start-guest={g.kind}
                >
                  {label}
                  <button
                    type="button"
                    className="inline-flex h-11 w-11 items-center justify-center rounded-full text-purple-800 hover:bg-purple-100 dark:text-purple-200 dark:hover:bg-purple-900/60"
                    aria-label={qs("removeNamed", { name: label })}
                    onClick={() => setForm((f) => ({ ...f, guests: (f.guests ?? []).filter((x) => x.localId !== g.localId) }))}
                  >
                    <X className="h-3.5 w-3.5" aria-hidden />
                  </button>
                </li>
              );
            })}
          </ul>
        ) : null}
        <div className="flex flex-wrap gap-2" data-start-subject-actions>
          <button type="button" className={SUBJECT_ACTION} onClick={() => setAdding("artisan")} data-new-artisan>
            {qs("newArtisan")}
          </button>
          <button type="button" className={SUBJECT_ACTION} onClick={() => setAdding("guest")} data-someone-else>
            {qs("modeSomeoneElse")}
          </button>
          {hasSelf ? null : (
            <button
              type="button"
              className={SUBJECT_ACTION}
              data-myself
              onClick={() => setForm((f) => withGuest(f, { localId: mintKey("g"), name: user?.name ?? "", kind: "SELF", notes: "" }))}
            >
              {qs("myself")}
            </button>
          )}
        </div>
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

      <div className="grid gap-1">
        <span className="field-label">{qs("fieldPurpose")}</span>
        <SegmentedChoice
          label={qs("fieldPurpose")}
          value={form.purpose}
          options={SESSION_PURPOSES.map((p) => ({ value: p, label: qs(PURPOSE_KEY[p]) }))}
          onChange={(v) => set("purpose", v as SessionPurpose)}
        />
      </div>

      <div className="grid gap-1" data-session-crafts-field>
        <span className="field-label">{qs("fieldCrafts")}</span>
        <SearchableMultiSelect
          values={form.crafts ?? []}
          onChange={(values) => setForm((f) => ({ ...f, crafts: values, craftsByHand: values.filter((v) => !autoCrafts.some((a) => a.toLowerCase() === v.toLowerCase())) }))}
          options={craftOptions}
          searchable
          ariaLabel={qs("fieldCrafts")}
        />
        {provenance.crafts ? <ProvenanceTag id={`${ids.tag}-c`} text={qs("fromRecordOf", { name: provenance.crafts })} /> : null}
      </div>

      <SessionPlaceField id={ids.place} place={form.place} onPlace={(v) => set("place", v)} provenance={provenance.place ? qs("fromThisWorkshop") : null} />

      <div className="grid gap-4 md:grid-cols-2">
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
        <div className="grid min-w-0 gap-1">
          <SpokenLanguagePicker value={spoken.value} onChange={spoken.choose} suggestion={spoken.suggestion?.source === "artisan" ? null : spoken.suggestion} />
          {provenance.spoken ? <ProvenanceTag id={`${ids.tag}-s`} text={qs("fromRecordOf", { name: provenance.spoken })} /> : null}
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

      <div className="grid gap-1" data-record-by>
        <span className="field-label">{qs("fieldRecordBy")}</span>
        <SegmentedChoice
          label={qs("fieldRecordBy")}
          value={form.recordBy ?? "SECTION"}
          options={RECORD_BY.map((v) => ({ value: v, label: qs(v === "QUESTION" ? "recordByQuestion" : "recordBySection") }))}
          onChange={(v) => set("recordBy", v as RecordBy)}
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

      {adding === "artisan" ? (
        <NewArtisanDialog
          title={qs("registerArtisan")}
          onClose={() => setAdding(null)}
          onCreated={(artisan) => {
            setAdding(null);
            setArtisans((prev) => mergeById(prev, [artisan]));
            setForm((f) => withNewArtisan(f, artisan.id));
          }}
        />
      ) : null}
      {adding === "guest" ? (
        <GuestDialog
          onClose={() => setAdding(null)}
          onSave={(name, notes) => {
            setAdding(null);
            setForm((f) => withGuest(f, { localId: mintKey("g"), name, kind: "GUEST", notes }));
          }}
        />
      ) : null}

      {p1 && openMatch ? (
        <PromptDialog
          spec={p1}
          open
          onChoose={async (choice) => {
            const match = openMatch;
            setOpenMatch(null);
            if (choice === "join" && match) {
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

const SUBJECT_ACTION =
  "inline-flex min-h-11 items-center rounded-md px-3 text-sm font-semibold text-purple-700 hover:bg-purple-50 dark:text-purple-300 dark:hover:bg-purple-950/40";

function ProvenanceTag({ id, text }: { id: string; text: string }) {
  return (
    <span id={id} className="flex items-center gap-1 text-xs text-ink-500" data-provenance>
      <LinkIcon className="h-3 w-3 shrink-0" aria-hidden />
      {text}
    </span>
  );
}

function GuestDialog({ onClose, onSave }: { onClose: () => void; onSave: (name: string, notes: string) => void }) {
  const [name, setName] = useState("");
  const [notes, setNotes] = useState("");
  const nameId = useId();
  const notesId = useId();
  return (
    <FieldDialog open onClose={onClose} title={qs("modeSomeoneElse")}>
      <div className="grid gap-3">
        <div className="grid gap-1">
          <label htmlFor={nameId} className="field-label">
            {qs("fieldGuestName")}
          </label>
          <input
            id={nameId}
            className="field-input"
            value={name}
            maxLength={120}
            autoComplete="off"
            placeholder={qs("guestNamePlaceholder")}
            onChange={(e) => setName(e.target.value)}
            data-guest-name
          />
        </div>
        <div className="grid gap-1">
          <label htmlFor={notesId} className="field-label">
            {qs("guestNotes")}
          </label>
          <textarea id={notesId} className="field-input min-h-16" value={notes} maxLength={2000} onChange={(e) => setNotes(e.target.value)} />
        </div>
      </div>
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={!name.trim()} onClick={() => onSave(name.trim(), notes.trim())} data-guest-save>
          {qs("save")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

function mergeById<T extends { id: string }>(a: T[], b: T[]): T[] {
  const seen = new Map<string, T>();
  for (const x of [...a, ...b]) seen.set(x.id, x);
  return [...seen.values()];
}
