"use client";

import { ChevronDown, ChevronUp, Mic } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { AlreadyRecordedDialog, type P2Choice } from "@/components/questionnaire/AlreadyRecordedDialog";
import { AnsweredBySheet, answeredByCaption } from "@/components/questionnaire/AnsweredBySheet";
import { AnswerBox, sessionSubjects, type AnswerProposal } from "@/components/questionnaire/AnswerBox";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { QuestionOverflow, type OverflowAction } from "@/components/questionnaire/QuestionOverflow";
import { activeRecording, Recorder, recorderClock, takesInHand, type FinishedTake, type RecorderTarget } from "@/components/questionnaire/Recorder";
import { RecordUploadPair } from "@/components/questionnaire/RecordUploadPair";
import { ReviewFinish, stillToAsk, type FailedUpload } from "@/components/questionnaire/ReviewFinish";
import { SavePill, saveState } from "@/components/questionnaire/SavePill";
import { SessionHeader } from "@/components/questionnaire/SessionHeader";
import { TakeSheet } from "@/components/questionnaire/TakeSheet";
import { UploadSheet, type UploadTarget } from "@/components/questionnaire/UploadSheet";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { enqueue, onRecordingLanded, onSessionLanded, queueRecordBy, resolveQueuedAnswer, trayAct, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { useSpokenLanguageChoice } from "@/components/transcript/SpokenLanguagePicker";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ApiError, clockOffset, listResource } from "@/lib/api";
import { loadInstrumentQuestions, requiredUnanswered, type InstrumentQuestion, type InstrumentSection } from "@/components/questionnaire/instrumentQuestions";
import { SessionActions, SessionPeople } from "@/components/questionnaire/SessionActions";
import { OutboxTray } from "@/components/questionnaire/OutboxTray";
import {
  offlineCheck,
  overlayFromUnits,
  pendingTakes,
  preselectReason,
  questionKey,
  questionProgress,
  readSnapshot,
  refreshSnapshot,
  sectionKey,
  shortName,
  takesFromRecordings,
  withTakes,
  type QuestionMark,
  type SectionQuestionProgress
} from "@/lib/questionnaire/coverageCache";
import { sha256Hex } from "@/lib/questionnaire/hashFile";
import { prefillItems } from "@/lib/questionnaire/prefill";
import {
  contractError,
  mintKey,
  questionnaireApi,
  type CoverageCheckResponse,
  toGridView,
  type AnswerItem,
  type GridQuestion,
  type GridView as Grid,
  type RecordBy,
  type Recording,
  type RepeatReason,
  type Session,
  type SessionDetail,
  type SessionReview
} from "@/lib/questionnaire/api";
import { prompts, shortDate, type P2Input, type PromptSpec } from "@/lib/questionnaire/prompts";
import { clipUploadState, isPermanent, queuedRecordBy, sendDetails, sessionUnitKey, stepKey, type ClipStep, type SessionUnit } from "@/lib/questionnaire/queue";
import { consentSubject, pickedFileMeta, rosterArtisans, takesFor, takeSubjects } from "@/lib/questionnaire/view";
import { assembleTake, recorderStore, recoverable, recoveredMinutes, type TakeChunk, type TakeDraft } from "@/lib/questionnaire/recorderStore";
import { settleBatch, type UploadRow } from "@/lib/questionnaire/uploads";
import { qs } from "@/lib/questionnaire/qs";
import { sessionSpokenLanguage } from "@/lib/transcript/spokenLanguage";
import { artisanFilter } from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

type Target = { sectionId: string } | { questionLineageId: string };
type Pending = { targets: Target[]; label: string; code: string; file?: File | null; files?: UploadRow[]; roster: string[] };
type TakeContext = {
  pending: Pending;
  reasons: Record<string, RepeatReason>;
  acked: string[];
  replaces: string | null;
  continuation: string | null;
  sha: string | null;
  source: string;
};
type HeldTake = { take: FinishedTake; context: TakeContext };

export const SECTIONS_SHOWN = 12;

function localSession(body: import("@/lib/questionnaire/api").SessionCreate, title: string): Session {
  return {
    id: body.clientKey,
    clientKey: body.clientKey,
    version: 0,
    title,
    note: body.note,
    legacyTitle: null,
    questionnaire: { id: body.questionnaireId, title: "" },
    workshop: body.workshopId ? { id: body.workshopId, name: "" } : null,
    designWorkshopId: body.designWorkshopId ?? null,
    sessionDate: body.sessionDate,
    sessionDateEstimated: false,
    startedAt: body.startedAt,
    endedAt: null,
    closedAt: null,
    state: "OPEN",
    purpose: body.purpose,
    origin: "NATIVE",
    mode: body.participants.length > 1 ? "GROUP" : "INDIVIDUAL",
    participantCount: body.participants.length,
    place: body.place,
    languageCode: body.languageCode,
    languageOther: body.languageOther,
    joinCode: null,
    joinQr: null,
    participants: body.participants.map((p) => ({ artisanId: p.artisanId, name: "", joinedAt: null, leftAt: null, consent: null })),
    researchers: [],
    status: "DRAFT",
    submittedAt: null,
    reviewReasons: [],
    counts: { sections: 0, takes: 0, needsDecision: 0 },
    recordBy: body.recordBy ?? "SECTION",
    respondents: (body.respondents ?? []).map((r, i) => ({ id: `local-${i}`, sessionId: body.clientKey, name: r.name, kind: r.kind, notes: r.notes ?? null, userId: null }))
  };
}

function gridFromSections(sections: InstrumentSection[], byId: Record<string, GridQuestion[]>): Grid {
  return { sections: sections.map((s) => ({ id: s.id, code: s.code, title: s.title, questions: byId[s.id] })), cells: [] };
}

function queuedClipKeys(units: SessionUnit[]): Set<string> {
  return new Set(units.flatMap((u) => u.steps.filter((s): s is ClipStep => s.kind === "clip").map((s) => s.clientClipKey)));
}

export function profileFillItems(
  detail: Pick<SessionDetail, "id" | "participants" | "answers">,
  lineageId: string
): AnswerItem[] {
  return detail.participants.flatMap((p) => {
    const current = detail.answers.find((a) => a.artisanId === p.artisanId && a.questionLineageId === lineageId);
    if (current && (current.text ?? "").trim() && current.origin !== "PREFILL") return [];
    const item: AnswerItem = {
      sessionId: detail.id,
      artisanId: p.artisanId,
      questionLineageId: lineageId,
      text: "",
      origin: "PREFILL",
      attribution: "INDIVIDUAL",
      sourceRecordingId: null,
      ...(current ? { baseVersion: current.version } : {})
    };
    return [item];
  });
}

export function questionCountLabel(count: number): string {
  return count === 1 ? qs("questionCountOne") : qs("questionCount", { count });
}

export function sectionSubline(p: Pick<SectionQuestionProgress, "answered" | "total" | "inSectionTake" | "typedOnly"> | undefined): string {
  if (!p || p.total === 0) return questionCountLabel(0);
  if (p.answered === 0) return questionCountLabel(p.total);
  const base = qs("answeredOfTotal", { answered: p.answered, total: p.total });
  if (p.inSectionTake) return `${base} · ${qs("inSectionTake")}`;
  if (p.typedOnly) return `${base} · ${qs("stateTyped")}`;
  return base;
}

export type SectionTone = "done" | "started" | "current" | "blank";

export function sectionTone(p: Pick<SectionQuestionProgress, "answered" | "total"> | undefined, open: boolean): SectionTone {
  if (open) return "current";
  if (!p || p.answered === 0) return "blank";
  return p.total > 0 && p.answered >= p.total ? "done" : "started";
}

const TILE: Record<SectionTone, string> = {
  done: "bg-success-100 text-success-600 dark:bg-success-600/25 dark:text-green-400",
  started: "bg-field-100 text-ink-900",
  current: "bg-purple-700 text-white",
  blank: "border border-line-200 bg-card text-ink-700"
};

const SUB: Record<SectionTone, string> = {
  done: "text-success-600 dark:text-green-400",
  started: "text-ink-700",
  current: "text-purple-800 dark:text-purple-200",
  blank: "text-ink-500"
};

export function sectionsToShow(count: number, openIndex: number, all: boolean): number {
  if (all) return count;
  return Math.min(count, Math.max(SECTIONS_SHOWN, openIndex + 1));
}

export function scrollSectionIntoView(node: { scrollIntoView: (options: ScrollIntoViewOptions) => void } | null, reduce: boolean) {
  node?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
}

export function SessionWorkspace({ id }: { id: string }) {
  const router = useRouter();
  const { user } = useAuth();
  const { canDeleteRecords } = useAdminView();
  const config = useQuestionnaireConfig();
  const reduce = useAppReducedMotion();
  const paused = config?.transcriptionPaused ?? true;
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [grid, setGrid] = useState<Grid | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sheet, setSheet] = useState<{ sectionId: string; artisanId: string | null } | null>(null);
  const [activeSection, setActiveSection] = useState<string | null>(null);
  const [scrollTo, setScrollTo] = useState<{ id: string; at: number } | null>(null);
  const [allSections, setAllSections] = useState(false);
  const [typing, setTyping] = useState<Record<string, true>>({});
  const [view, setView] = useState<"session" | "review">("session");
  const [review, setReview] = useState<SessionReview | null>(null);
  const [uploadFor, setUploadFor] = useState<UploadTarget | null>(null);
  const [trayOpen, setTrayOpen] = useState(false);
  const [micOff, setMicOff] = useState<UploadTarget | null>(null);
  const [recordByLocal, setRecordByLocal] = useState<RecordBy | null>(null);
  const [p2, setP2] = useState<{ pending: Pending; input: P2Input; reason: RepeatReason; check: CoverageCheckResponse } | null>(null);
  const [recording, setRecording] = useState<{ target: RecorderTarget; context: TakeContext } | null>(null);
  const [answering, setAnswering] = useState<{ takes: HeldTake[]; chosen: string[]; caption: string } | null>(null);
  const [lastAnswered, setLastAnswered] = useState<Record<string, string[]>>({});
  const [p3, setP3] = useState<Pending | null>(null);
  const [p5, setP5] = useState<{ spec: PromptSpec; act: ((choice: string) => Promise<void>) | null } | null>(null);
  const [p13, setP13] = useState<{ draft: TakeDraft; chunks: TakeChunk[] }[]>([]);
  const [takeError, setTakeError] = useState<string | null>(null);
  const [finishOpen, setFinishOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [consentFor, setConsentFor] = useState<{ artisanId: string; name: string } | null>(null);
  const [removeTake, setRemoveTake] = useState<Recording | null>(null);
  const [p7, setP7] = useState<{ run: () => void; position: number; target: string } | null>(null);
  const [p10, setP10] = useState<{ artisanId: string; name: string; count: number } | null>(null);
  const [adding, setAdding] = useState(false);
  const [questions, setQuestions] = useState<Record<string, InstrumentQuestion[]>>({});
  const profileFields = useMemo(() => new Set(Object.values(questions).flat().filter((q) => q.artisanField).map((q) => q.lineageId)), [questions]);
  const [proposals, setProposals] = useState<Record<string, AnswerProposal>>({});
  const [proposalNote, setProposalNote] = useState<string | null>(null);
  const [revealLineage, setRevealLineage] = useState<string | null>(null);
  const [dockHeight, setDockHeight] = useState(0);
  const dockRef = useRef<HTMLDivElement | null>(null);
  const wantedSection = useRef<string | null>(null);

  const openSection = useCallback((sectionId: string | null) => {
    setActiveSection(sectionId);
    if (sectionId) setScrollTo({ id: sectionId, at: Date.now() });
  }, []);

  useEffect(() => {
    if (!revealLineage || !grid) return;
    const section = grid.sections.find((s) => s.questions?.some((q) => q.lineageId === revealLineage));
    if (!section) return;
    openSection(section.id);
    setRevealLineage(null);
  }, [revealLineage, grid, openSection]);

  useEffect(() => {
    if (!scrollTo || view !== "session") return;
    const frame = requestAnimationFrame(() => scrollSectionIntoView(document.getElementById(`section-${scrollTo.id}`), reduce));
    return () => cancelAnimationFrame(frame);
  }, [scrollTo, reduce, view]);

  useEffect(() => {
    if (typeof window === "undefined") return;
    wantedSection.current = new URLSearchParams(window.location.search).get("section");
  }, []);

  useEffect(() => {
    const wanted = wantedSection.current;
    if (!wanted || !grid) return;
    const match = grid.sections.find((s) => s.id === wanted || s.code === wanted);
    if (!match) return;
    wantedSection.current = null;
    openSection(match.id);
  }, [grid, openSection]);

  const queue = useSessionQueue(detail ?? id);
  const unitsRef = useRef<SessionUnit[]>(queue.units);
  useEffect(() => {
    unitsRef.current = queue.units;
  }, [queue.units]);
  const generation = useRef(0);
  const knownQuestions = useRef<{ questionnaireId: string; byId: Record<string, InstrumentQuestion[]> } | null>(null);

  const load = useCallback(async () => {
    const mine = (generation.current += 1);
    setLoadError(null);
    try {
      const d = await questionnaireApi.getSession(id);
      if (mine !== generation.current) return;
      setDetail(d);
      const held = knownQuestions.current?.questionnaireId === d.questionnaire.id ? knownQuestions.current.byId : undefined;
      setGrid(toGridView(d.grid, held));
      void loadInstrumentQuestions(d.questionnaire.id)
        .then((q) => {
          if (mine !== generation.current) return;
          knownQuestions.current = { questionnaireId: d.questionnaire.id, byId: q.byId };
          setGrid(toGridView(d.grid, q.byId));
          setQuestions(q.byId);
        })
        .catch(() => undefined);
    } catch (error) {
      if (mine !== generation.current) return;
      const unit = unitsRef.current.find((u) => u.id === id);
      if (unit?.sessionId && unit.sessionId !== id) {
        router.replace(`/questionnaire/sessions/${encodeURIComponent(unit.sessionId)}`);
        return;
      }
      const first = unit?.steps.find((s) => s.kind === "session");
      if (first && first.kind === "session") {
        setDetail({ ...localSession(first.body, ""), recordings: [], answers: [], grid: { questionnaireId: first.body.questionnaireId, artisans: [], sections: [] } });
        try {
          const q = await loadInstrumentQuestions(first.body.questionnaireId);
          knownQuestions.current = { questionnaireId: first.body.questionnaireId, byId: q.byId };
          setGrid(gridFromSections(q.sections, q.byId));
          setQuestions(q.byId);
        } catch {
          setGrid({ sections: [], cells: [] });
        }
        return;
      }
      setLoadError(error instanceof ApiError && error.status === 404 ? qs("sessionLoadError") : qs("sessionLoadError"));
    }
  }, [id, router]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const offR = onRecordingLanded((r) => {
      if (detail && r.sessionId === detail.id) void load();
    });
    const offS = onSessionLanded((s) => {
      if (s.clientKey === id) router.replace(`/questionnaire/sessions/${encodeURIComponent(s.id)}`);
    });
    return () => {
      offR();
      offS();
    };
  }, [detail, id, load, router]);

  const questionnaireId = detail?.questionnaire.id ?? null;
  const workshopId = detail?.workshop?.id ?? null;
  useEffect(() => {
    if (!questionnaireId) return;
    const refresh = () => {
      if (navigator.onLine !== false) void refreshSnapshot(questionnaireApi, questionnaireId, workshopId);
    };
    refresh();
    const timer = window.setInterval(refresh, Math.max(15, config?.snapshotPollSeconds ?? 60) * 1000);
    return () => window.clearInterval(timer);
  }, [questionnaireId, workshopId, config?.snapshotPollSeconds]);

  const sessionKey = detail ? sessionUnitKey(detail) : null;

  const checkRecovery = useCallback(async () => {
    if (!sessionKey || activeRecording()) return;
    try {
      const [drafts, chunks] = await Promise.all([recorderStore.drafts(), recorderStore.chunks()]);
      const queued = queuedClipKeys(unitsRef.current);
      for (const d of drafts) if (queued.has(d.clientClipKey)) void recorderStore.discard(d.takeKey).catch(() => undefined);
      setP13(recoverable(drafts.filter((d) => d.sessionKey === sessionKey && !takesInHand.has(d.takeKey) && !queued.has(d.clientClipKey)), chunks));
    } catch {
    }
  }, [sessionKey]);

  useEffect(() => {
    void checkRecovery();
  }, [checkRecovery]);

  const prefilledFor = useRef<string | null>(null);
  const userId = user?.id ?? null;
  useEffect(() => {
    if (!detail || detail.version === 0 || prefilledFor.current === detail.id) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    if (!userId || !detail.researchers.some((r) => r.userId === userId)) return;
    const mapped = Object.values(questions).flat().filter((q) => q.artisanField);
    if (!mapped.length) return;
    prefilledFor.current = detail.id;
    const answered = new Set(detail.answers.filter((a) => a.artisanId).map((a) => `${a.artisanId}|${a.questionLineageId}`));
    void Promise.all(detail.participants.map((p) => questionnaireApi.artisan(p.artisanId).catch(() => null))).then(async (rows) => {
      const items = prefillItems({
        sessionId: detail.id,
        questions: mapped,
        artisans: rows.filter((r): r is NonNullable<typeof r> => r !== null),
        answered,
        context: { sessionDate: detail.sessionDate, interviewer: detail.researchers.find((r) => r.role === "LEAD")?.name ?? null }
      });
      if (!items.length) return;
      try {
        await questionnaireApi.putAnswers(items);
        void load();
      } catch {
      }
    });
  }, [detail, questions, userId, load]);

  const reviewFor = view === "review" && detail && detail.version > 0 ? detail.id : null;
  useEffect(() => {
    if (!reviewFor) return;
    let cancelled = false;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    questionnaireApi
      .review(reviewFor)
      .then((r) => {
        if (!cancelled) setReview(r && Array.isArray(r.stillToAsk) ? r : null);
      })
      .catch(() => {
        if (!cancelled) setReview(null);
      });
    return () => {
      cancelled = true;
    };
  }, [reviewFor]);

  const pendingCount = queue.pendingClips.length;
  const inHand = recording !== null || answering !== null ? 1 : 0;
  const guarded = pendingCount + inHand;
  useLeaveGuard(guarded > 0, () => setLeaveOpen(true));
  useEffect(() => {
    if (guarded === 0) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [guarded]);

  const showDock = Boolean(recording) || Boolean(activeSection && detail?.state === "OPEN" && (recordByLocal ?? queuedRecordBy(queue.unit) ?? detail?.recordBy ?? "SECTION") === "SECTION");
  useEffect(() => {
    const node = dockRef.current;
    if (!node || typeof ResizeObserver === "undefined") {
      setDockHeight(0);
      return;
    }
    const set = () => setDockHeight(Math.ceil(node.getBoundingClientRect().height));
    set();
    const observer = new ResizeObserver(set);
    observer.observe(node);
    return () => observer.disconnect();
  }, [showDock, view]);

  const progress = useMemo(
    () =>
      questionProgress({
        sections: grid?.sections ?? [],
        recordings: detail?.recordings ?? [],
        answers: detail?.answers ?? [],
        pending: pendingTakes(queue.unit ? [queue.unit] : [])
      }),
    [grid, detail, queue.unit]
  );

  const spoken = useSpokenLanguageChoice(detail?.participants.map((p) => p.artisanId) ?? [], {
    preset: sessionSpokenLanguage(detail?.clientKey, detail?.id, id),
    fallback: detail?.languageCode ?? null
  });

  if (loadError) {
    return (
      <div className="panel grid gap-3 p-6 text-center">
        <p className="text-sm text-ink-700">{loadError}</p>
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => void load()}>
            {qs("tryAgain")}
          </button>
        </ButtonRow>
      </div>
    );
  }
  if (!detail || !grid) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;

  const shownGrid = grid;
  const session = detail;
  const live = session.state === "OPEN";
  const recordBy: RecordBy = recordByLocal ?? queuedRecordBy(queue.unit) ?? session.recordBy ?? "SECTION";
  const unitSession = { id: session.id, clientKey: session.clientKey };
  const guests = (session.respondents ?? []).filter((r) => !r.artisanId);
  const roster = [...session.participants.filter((p) => !p.leftAt).map((p) => p.artisanId), ...guests.map((g) => `R:${g.id}`)];
  const names: Record<string, string> = Object.fromEntries([
    ...session.participants.map((p) => [p.artisanId, p.name] as const),
    ...guests.map((g) => [`R:${g.id}`, g.name] as const)
  ]);
  const subjects = sessionSubjects(session);
  const me = user?.id ?? null;
  const mayOverride = Boolean(user && (canDeleteRecords || session.researchers.some((r) => r.userId === user.id && r.role === "LEAD")));
  const unanswered = requiredUnanswered(questions, detail.answers.map((a) => ({ ...a, text: a.text ?? "" })), subjects.map((s) => s.key), detail.recordings);
  const soloArtisan = session.participants.length === 1 ? session.participants[0].artisanId : null;
  const sectionOf = (lineageId: string) => shownGrid.sections.find((s) => s.questions?.some((q) => q.lineageId === lineageId))?.id ?? null;
  const sectionOfTargets = (targets: Target[]) => {
    const t = targets[0];
    if (!t) return "whole";
    return "sectionId" in t ? t.sectionId : sectionOf(t.questionLineageId) ?? "whole";
  };
  const labelOf = (key: string) => {
    const [kind, ref] = [key.slice(0, 1), key.slice(2)];
    if (kind === "S") return `Section ${shownGrid.sections.find((s) => s.id === ref)?.code ?? ""}`.trim();
    for (const s of shownGrid.sections) {
      const q = s.questions?.find((x) => x.lineageId === ref);
      if (q) return q.displayCode;
    }
    return key;
  };

  async function check(pending: Pending, sha: string | null): Promise<CoverageCheckResponse> {
    const request = { questionnaireId: session.questionnaire.id, artisanIds: rosterArtisans(pending.roster), targets: pending.targets, sessionId: session.id, contentSha256: sha };
    if (navigator.onLine !== false) {
      try {
        return await questionnaireApi.checkCoverage(request);
      } catch {
      }
    }
    const held = readSnapshot(session.questionnaire.id, session.workshop?.id ?? null);
    const snapshot = withTakes(held?.snapshot ?? null, takesFromRecordings(session.recordings));
    const recordedBy = new Map(session.recordings.map((r) => [r.id, r.recordedBy] as const));
    return offlineCheck(snapshot, overlayFromUnits(queue.units), request, {
      sectionOf,
      labelOf,
      sessionId: session.id,
      me: user?.id ?? null,
      recordedBy: (rid) => recordedBy.get(rid) ?? (user ? { id: user.id, name: user.name } : null),
      sessionInfo: (sid) => (sid === session.id || sid === sessionKey ? { date: session.sessionDate, rosterCount: roster.length } : null)
    });
  }

  async function dropParticipant(artisanId: string, detach: boolean) {
    try {
      await questionnaireApi.removeParticipant(session.id, artisanId, detach);
      void load();
    } catch (error) {
      const code = contractError(error);
      if (code?.code === "PARTICIPANT_HAS_TAKES") setP10({ artisanId, name: shortName(names[artisanId] ?? ""), count: code.count ?? 1 });
    }
  }

  async function addArtisan(artisan: { id: string; name: string }) {
    setAdding(false);
    if (session.participants.some((p) => p.artisanId === artisan.id)) return;
    await enqueue(unitSession, { kind: "participant", status: "PENDING", op: "add", artisanId: artisan.id, at: new Date().toISOString(), idempotencyKey: mintKey("part") });
    setDetail({
      ...detail!,
      participantCount: detail!.participantCount + 1,
      participants: [...detail!.participants, { artisanId: artisan.id, name: artisan.name, joinedAt: new Date().toISOString(), leftAt: null, consent: null }]
    });
  }

  async function changeRecordBy(next: RecordBy) {
    if (next === recordBy) return;
    setRecordByLocal(next);
    const online = typeof navigator === "undefined" || navigator.onLine !== false;
    if (online && session.version > 0) {
      try {
        const saved = await sendDetails(questionnaireApi, session.id, { version: session.version, recordBy: next });
        setDetail((current) => (current ? { ...current, recordBy: saved.recordBy ?? next, version: saved.version ?? current.version } : current));
        return;
      } catch (error) {
        if (isPermanent(error)) {
          setRecordByLocal(null);
          setTakeError(error instanceof Error ? error.message : String(error));
          return;
        }
      }
    }
    await queueRecordBy(unitSession, next, session.version).catch(() => undefined);
  }

  async function identicalPrompt(other: NonNullable<CoverageCheckResponse["identical"]>, pending: Pending) {
    if (other.sameSession) {
      const take = session.recordings.find((r) => r.id === other.recordingId);
      const answered = new Set(take?.subjects.filter((s) => s.role === "ANSWERED").map((s) => s.artisanId) ?? []);
      const missing = pending.roster.filter((a) => !answered.has(a));
      const onIt = take?.subjects.find((s) => s.role === "ANSWERED")?.artisanId ?? pending.roster[0];
      const base = { name: shortName(names[onIt] ?? ""), date: shortDate(take?.capturedAt ?? session.sessionDate) };
      if (take && missing.length) {
        setP5({
          spec: prompts.p5({ ...base, case: "SAME_SESSION", addName: missing.map((a) => shortName(names[a] ?? "")).join(", ") }),
          act: async (choice) => {
            if (choice !== "add") return;
            const kept = take.subjects.map((s) => ({ artisanId: s.artisanId, role: missing.includes(s.artisanId) ? "ANSWERED" : s.role }));
            const added = missing.filter((a) => !take.subjects.some((s) => s.artisanId === a)).map((artisanId) => ({ artisanId, role: "ANSWERED" }));
            await questionnaireApi.patchRecording(take.id, { version: take.version, subjects: [...kept, ...added] });
            void load();
          }
        });
      } else setP5({ spec: prompts.p5({ ...base, case: "OTHER" }), act: null });
      return;
    }
    let elsewhere: SessionDetail | null = null;
    try {
      elsewhere = await questionnaireApi.getSession(other.sessionId);
    } catch {
      elsewhere = null;
    }
    const base = {
      name: shortName(elsewhere?.participants[0]?.name ?? ""),
      date: shortDate(elsewhere?.sessionDate ?? elsewhere?.startedAt ?? null)
    };
    const sameSitting = Boolean(
      elsewhere && elsewhere.sessionDate === session.sessionDate && elsewhere.participants.some((p) => pending.roster.includes(p.artisanId))
    );
    if (elsewhere && sameSitting && canDeleteRecords && session.version > 0) {
      const target = elsewhere.id;
      setP5({
        spec: prompts.p5({ ...base, case: "OTHER_SESSION" }),
        act: async (choice) => {
          if (choice !== "merge") return;
          await questionnaireApi.mergeInto(session.id, target);
          router.replace(`/questionnaire/sessions/${encodeURIComponent(target)}`);
        }
      });
    } else setP5({ spec: prompts.p5({ ...base, case: "OTHER" }), act: null });
  }

  async function begin(pending: Pending) {
    const live = activeRecording();
    if (live) {
      setP3(pending);
      return;
    }
    const sha = pending.file ? await sha256Hex(pending.file) : null;
    const answer = await check(pending, sha);
    if (pending.file && answer.identical) {
      await identicalPrompt(answer.identical, pending);
      return;
    }
    const covered = answer.targets[0]?.perArtisan.some((p) => p.takes.length > 0);
    if (covered) {
      const earlier = answer.targets[0].perArtisan.flatMap((p) => p.takes).sort((a, b) => b.capturedAt.localeCompare(a.capturedAt))[0] ?? null;
      const last = earlier;
      setP2({
        pending: { ...pending },
        check: answer,
        reason: preselectReason({ purpose: session.purpose, rosterCount: pending.roster.length, earlier, me: user?.id ?? null, suggested: answer.suggestedReason }),
        input: {
          check: answer,
          names,
          targetLabel: pending.label,
          code: pending.code,
          filePick: Boolean(pending.file || pending.files?.length),
          part2Allowed: Boolean(last && last.sameSession),
          replaceAllowed: Boolean(last && last.sameSession && last.mine)
        }
      });
      return;
    }
    proceed(pending, {}, ackedOf(answer), null, null, sha);
  }

  function ackedOf(answer: CoverageCheckResponse): string[] {
    return [...new Set(answer.targets.flatMap((t) => t.perArtisan.flatMap((p) => p.takes.map((k) => k.recordingId))))];
  }

  function proceed(pending: Pending, reasons: Record<string, RepeatReason>, acked: string[], replaces: string | null, continuation: string | null, sha: string | null) {
    if (pending.files?.length) {
      finishTakes(
        pending.files.map((row) => {
          const meta = pickedFileMeta(row.file, session.sessionDate);
          return {
            take: {
              takeKey: mintKey("file"),
              clientClipKey: mintKey("clip"),
              blob: row.file,
              mimeType: meta.contentType,
              extension: meta.extension,
              durationMs: row.durationMs ?? 0,
              deviceCapturedAt: row.recordedAt
            },
            context: { pending: { ...pending, files: undefined }, reasons, acked, replaces, continuation, sha: row.sha, source: row.source }
          };
        })
      );
      return;
    }
    if (pending.file) {
      const meta = pickedFileMeta(pending.file, session.sessionDate);
      finishTake(
        {
          takeKey: mintKey("file"),
          clientClipKey: mintKey("clip"),
          blob: pending.file,
          mimeType: meta.contentType,
          extension: meta.extension,
          durationMs: 0,
          deviceCapturedAt: meta.deviceCapturedAt
        },
        { pending, reasons, acked, replaces, continuation, sha, source: meta.capturedAtSource }
      );
      return;
    }
    setRecording({
      target: { label: pending.label, targets: pending.targets, roster: pending.roster, sessionKey: sessionUnitKey(session) },
      context: { pending, reasons, acked, replaces, continuation, sha: null, source: "DEVICE" }
    });
  }

  function finishTake(take: FinishedTake, context: TakeContext) {
    finishTakes([{ take, context }]);
  }

  function finishTakes(takes: HeldTake[]) {
    const first = takes[0];
    if (!first) return;
    if (first.context.pending.roster.length > 1) {
      const remembered = lastAnswered[sectionOfTargets(first.context.pending.targets)]?.filter((a) => first.context.pending.roster.includes(a));
      setAnswering({
        takes,
        chosen: remembered?.length ? remembered : first.context.pending.roster,
        caption: answeredByCaption(first.context.pending.label, takes.reduce((n, t) => n + (t.take.durationMs || 0), 0))
      });
      return;
    }
    void queueTakes(takes, new Set(first.context.pending.roster));
  }

  async function queueTakes(takes: HeldTake[], answered: ReadonlySet<string>) {
    const files = takes.map((t) => (t.take.blob instanceof File ? t.take.blob : new File([t.take.blob], t.take.takeKey)));
    const outcome = await settleBatch(files, async () => {
      const uploaded: string[] = [];
      const failed: { name: string; error: string }[] = [];
      for (const [index, held] of takes.entries()) {
        if (await queueClip(held.take, held.context, answered)) uploaded.push(held.take.takeKey);
        else failed.push({ name: files[index].name, error: qs("takeNotSaved") });
      }
      return { uploaded, failed };
    });
    if (takes.length > 1 && outcome.left.length) setTakeError(`${qs("takeNotSaved")} ${outcome.left.map((f) => f.name).join(", ")}`);
  }

  async function queueClip(take: FinishedTake, context: TakeContext, answered: ReadonlySet<string>): Promise<boolean> {
    setTakeError(null);
    try {
      const sha = context.sha ?? (await sha256Hex(take.blob));
      const offset = clockOffset();
      const roster = context.pending.roster;
      const step: ClipStep = {
        kind: "clip",
        status: "PENDING",
        clientClipKey: take.clientClipKey,
        blobKey: take.takeKey,
        label: context.pending.label,
        sizeBytes: take.blob.size,
        contentType: take.mimeType.split(";")[0] || "audio/webm",
        extension: take.extension,
        presign: { contentSha256: sha, targets: context.pending.targets, subjectArtisanIds: rosterArtisans(roster), deviceCapturedAt: take.deviceCapturedAt, clockOffsetMs: offset?.offsetMs ?? null },
        recording: {
          targets: context.pending.targets,
          subjects: takeSubjects(roster, answered, context.reasons),
          deviceCapturedAt: take.deviceCapturedAt,
          clockOffsetMs: offset?.offsetMs ?? null,
          capturedAtSource: context.source,
          durationMs: take.durationMs || null,
          rosterCount: roster.length,
          contentSha256: sha,
          continuationOfId: context.continuation,
          replacesRecordingId: context.replaces,
          ackedRecordingIds: context.acked,
          spokenLanguage: spoken.value
        }
      };
      await enqueue(unitSession, step, { key: take.takeKey, blob: take.blob });
      await recorderStore.discard(take.takeKey).catch(() => undefined);
      return true;
    } catch {
      setTakeError(qs("takeNotSaved"));
      return false;
    } finally {
      takesInHand.delete(take.takeKey);
      void checkRecovery();
    }
  }

  function chooseP2(choice: P2Choice) {
    const current = p2;
    setP2(null);
    if (!current || choice.kind === "cancel") return;
    const coveredIds = current.check.targets[0]?.perArtisan.filter((p) => p.takes.length > 0).map((p) => p.artisanId) ?? [];
    const acked = ackedOf(current.check);
    const lastTake = current.check.targets[0]?.perArtisan.flatMap((p) => p.takes).sort((a, b) => b.capturedAt.localeCompare(a.capturedAt))[0];
    if (choice.kind === "confirm") {
      proceed(current.pending, Object.fromEntries(coveredIds.map((id) => [id, choice.reason])), acked, null, null, null);
    } else if (choice.kind === "part2") {
      proceed(current.pending, {}, acked, null, lastTake?.recordingId ?? null, null);
    } else if (choice.kind === "replace") {
      setP7({
        position: lastTake?.position ?? 1,
        target: current.pending.label,
        run: () => proceed(current.pending, Object.fromEntries(coveredIds.map((id) => [id, "REDO" as RepeatReason])), acked, lastTake?.recordingId ?? null, null, null)
      });
    } else if (choice.kind === "different") {
      const [kind, ref] = [choice.targetValue.slice(0, 1), choice.targetValue.slice(2)];
      const targets: Target[] = kind === "S" ? [{ sectionId: ref }] : [{ questionLineageId: ref }];
      void begin({ ...current.pending, targets, label: labelOf(choice.targetValue), code: labelOf(choice.targetValue).replace(/^Section /, "") });
    }
  }

  const press = (targets: Target[], label: string, code: string, file?: File) => void begin({ targets, label, code, file, roster });

  function targetOf(upload: UploadTarget): { targets: Target[]; label: string; code: string } {
    if (upload.kind === "SECTION") {
      const s = shownGrid.sections.find((x) => x.id === upload.sectionId);
      return { targets: [{ sectionId: upload.sectionId }], label: `Section ${s?.code ?? ""}`.trim(), code: s?.code ?? "" };
    }
    if (upload.kind === "QUESTION") {
      const code = labelOf(questionKey(upload.lineageId));
      return { targets: [{ questionLineageId: upload.lineageId }], label: code, code };
    }
    return { targets: [], label: qs("wholeSession"), code: "" };
  }

  function uploadFiles(rows: UploadRow[], upload: UploadTarget) {
    setUploadFor(null);
    if (!rows.length) return;
    const t = targetOf(upload);
    void begin({ ...t, roster, files: rows });
  }

  async function offerAnswers(recording: Recording) {
    setProposalNote(null);
    try {
      const { proposals: found } = await questionnaireApi.answerProposals(recording.id);
      setProposals((held) => {
        const next = { ...held };
        for (const p of found) {
          next[p.questionLineageId] = {
            text: p.text,
            origin: p.origin,
            sourceRecordingId: recording.id,
            subjectKeys: p.artisanIds ? p.artisanIds.map((a) => `A:${a}`) : null
          };
        }
        return next;
      });
      setTyping((held) => ({ ...held, ...Object.fromEntries(found.map((p) => [p.questionLineageId, true] as const)) }));
      setProposalNote(found.length ? qs("proposalsReady", { count: found.length }) : qs("noProposals"));
      if (found[0]) setRevealLineage(found[0].questionLineageId);
    } catch (error) {
      setProposalNote(error instanceof Error ? error.message : String(error));
    }
  }

  async function fillFromProfile(lineageId: string) {
    if (!detail || detail.version === 0) return;
    const items = profileFillItems(detail, lineageId);
    if (!items.length) return;
    try {
      await questionnaireApi.putAnswers(items);
      void load();
    } catch (error) {
      setTakeError(error instanceof Error ? error.message : String(error));
    }
  }

  function questionAction(sectionId: string, q: GridQuestion, action: OverflowAction) {
    if (action === "record") press([{ questionLineageId: q.lineageId }], q.displayCode, q.displayCode);
    else if (action === "upload") setUploadFor({ kind: "QUESTION", lineageId: q.lineageId });
    else if (action === "prefill") void fillFromProfile(q.lineageId);
    else setTyping((held) => ({ ...held, [q.lineageId]: true }));
    if (activeSection !== sectionId) openSection(sectionId);
  }

  const sheetSection = sheet ? shownGrid.sections.find((s) => s.id === sheet.sectionId) ?? null : null;
  const sheetRecordings = sheet
    ? detail.recordings.filter(
        (r) =>
          r.targets.some((t) => t.sectionId === sheet.sectionId || (t.questionLineageId && sectionOf(t.questionLineageId) === sheet.sectionId)) &&
          (!sheet.artisanId || r.subjects.some((s) => s.artisanId === sheet.artisanId))
      )
    : [];
  const otherTargets = shownGrid.sections.flatMap((s) => [
    { value: sectionKey(s.id), label: `Section ${s.code} · ${s.title}` },
    ...(s.questions ?? []).map((q) => ({ value: questionKey(q.lineageId), label: `${q.displayCode} ${q.prompt}` }))
  ]);
  const activeGridSection = activeSection ? shownGrid.sections.find((s) => s.id === activeSection) ?? null : null;
  const myClips = (queue.unit?.steps ?? []).filter((s): s is ClipStep => s.kind === "clip" && s.status !== "DONE");
  const conflict = queue.conflicts[0] ?? null;
  const save = saveState(queue.unit?.steps ?? [], queue.progress, queue.online);
  const queuedShas: Record<string, string> = Object.fromEntries(
    queue.units.flatMap((u) => u.steps.filter((s): s is ClipStep => s.kind === "clip").map((s) => [s.presign.contentSha256 ?? "", s.recording.deviceCapturedAt] as const)).filter(([sha]) => sha)
  );
  const openIndex = activeSection ? shownGrid.sections.findIndex((s) => s.id === activeSection) : -1;
  const shownCount = sectionsToShow(shownGrid.sections.length, openIndex, allSections);
  const uploadSections = shownGrid.sections.map((s) => ({ id: s.id, code: s.code, title: s.title, questions: s.questions ?? [] }));
  const typedText = (lineageId: string) => detail.answers.find((a) => a.questionLineageId === lineageId && (a.text ?? "").trim())?.text?.trim() ?? null;
  const recordingHeading = recording
    ? recording.context.pending.targets.length === 1 && "sectionId" in recording.context.pending.targets[0]
      ? qs("recordingSection", { code: recording.context.pending.code })
      : qs("recordingTarget", { target: recording.context.pending.label })
    : "";
  const failedUploads: FailedUpload[] = myClips
    .filter((s) => s.error !== "MISSING_FILE" && (s.status === "FAILED" || Boolean(s.error)))
    .map((s) => ({
      key: s.clientClipKey,
      code: s.label ?? qs("wholeSession"),
      onRetry: () => {
        if (s.status === "FAILED" && queue.unit) void trayAct(queue.unit.id, stepKey(s), "again");
        else queue.retry();
      }
    }));
  const decisions = detail.recordings.filter((r) => !r.removedAt && r.filing === "NEEDS_DECISION");
  const awayPeople = session.participants.filter((p) => p.leftAt);

  const dialogs = (
    <>
      {p13.length ? (
        <PromptDialog
          spec={prompts.p13({ minutes: recoveredMinutes(p13[0].draft, p13[0].chunks), target: p13[0].draft.targetLabel })}
          open
          onChoose={(choice) => {
            const [first, ...rest] = p13;
            setP13(rest);
            if (choice === "keep") {
              takesInHand.add(first.draft.takeKey);
              const blob = assembleTake(first.chunks, first.draft.mimeType);
              void queueClip(
                {
                  takeKey: first.draft.takeKey,
                  clientClipKey: first.draft.clientClipKey,
                  blob,
                  mimeType: first.draft.mimeType,
                  extension: first.draft.mimeType.includes("mp4") ? "m4a" : "webm",
                  durationMs: Math.max(0, (first.chunks.at(-1)?.at ?? first.draft.startedAt) - first.draft.startedAt),
                  deviceCapturedAt: first.draft.deviceCapturedAt
                },
                {
                  pending: { targets: first.draft.targets, label: first.draft.targetLabel, code: "", roster: first.draft.roster },
                  reasons: {},
                  acked: [],
                  replaces: null,
                  continuation: null,
                  sha: null,
                  source: "DEVICE"
                },
                new Set(first.draft.roster)
              );
            } else if (choice === "discard") {
              void recorderStore.discard(first.draft.takeKey);
            }
          }}
        />
      ) : null}

      {answering ? (
        <AnsweredBySheet
          people={[
            ...answering.takes[0].context.pending.roster.map((a) => ({ id: a, name: names[a] ?? "", away: false })),
            ...awayPeople.filter((p) => !answering.takes[0].context.pending.roster.includes(p.artisanId)).map((p) => ({ id: p.artisanId, name: p.name, away: true }))
          ]}
          chosen={answering.chosen}
          caption={answering.caption}
          onToggle={(who) => setAnswering({ ...answering, chosen: answering.chosen.includes(who) ? answering.chosen.filter((a) => a !== who) : [...answering.chosen, who] })}
          onLater={() => {
            const current = answering;
            setAnswering(null);
            void queueTakes(current.takes, new Set());
          }}
          onSave={() => {
            const current = answering;
            setAnswering(null);
            const first = current.takes[0].context.pending;
            setLastAnswered({ ...lastAnswered, [sectionOfTargets(first.targets)]: current.chosen });
            const extra = current.chosen.filter((a) => !first.roster.includes(a));
            const takes = extra.length ? current.takes.map((t) => ({ ...t, context: { ...t.context, pending: { ...t.context.pending, roster: [...t.context.pending.roster, ...extra] } } })) : current.takes;
            void queueTakes(takes, new Set(current.chosen));
          }}
        />
      ) : null}

      {sheet && sheetSection ? (
        <TakeSheet
          open
          title={`Section ${sheetSection.code}${sheet.artisanId ? ` · ${names[sheet.artisanId]?.split(" ")[0] ?? ""}` : ""}`}
          recordings={sheetRecordings}
          artisanId={sheet.artisanId}
          paused={paused}
          canRecord={live}
          onClose={() => setSheet(null)}
          onRecord={() => {
            setSheet(null);
            press([{ sectionId: sheetSection.id }], `Section ${sheetSection.code}`, sheetSection.code);
          }}
          onUpload={(file) => {
            setSheet(null);
            press([{ sectionId: sheetSection.id }], `Section ${sheetSection.code}`, sheetSection.code, file);
          }}
          onRemove={canDeleteRecords ? (r) => setRemoveTake(r) : undefined}
          onUseAsAnswer={(r) => {
            setSheet(null);
            void offerAnswers(r);
          }}
          sessionId={session.version > 0 ? session.id : undefined}
          onChanged={() => void load()}
          override={shownGrid.cells.find((c) => c.targetKey === `S:${sheetSection.id}` && c.artisanId === sheet.artisanId)?.override ?? null}
          onOverride={
            canDeleteRecords && sheet.artisanId && !sheet.artisanId.startsWith("R:")
              ? async (status) => {
                  await questionnaireApi.setSectionOverride({ artisanId: sheet.artisanId!, sectionId: sheetSection.id, status });
                  void load();
                }
              : undefined
          }
          onRecordConsent={(r) => {
            const artisanId = consentSubject(r, session.participants);
            if (artisanId) setConsentFor({ artisanId, name: names[artisanId] ?? "" });
          }}
        />
      ) : null}

      {consentFor ? (
        <FieldDialog open onClose={() => setConsentFor(null)} title={consentFor.name}>
          <ConsentQuestion artisanId={consentFor.artisanId} name={consentFor.name} refused onRecorded={() => setConsentFor(null)} onDismiss={() => setConsentFor(null)} />
        </FieldDialog>
      ) : null}

      {uploadFor ? (
        <UploadSheet
          sessionId={session.id}
          sessionDate={session.sessionDate}
          sections={uploadSections}
          sectionId={uploadFor.kind === "SECTION" ? uploadFor.sectionId : uploadFor.kind === "QUESTION" ? sectionOf(uploadFor.lineageId) : activeSection}
          defaultTarget={uploadFor}
          queuedShas={queuedShas}
          onClose={() => setUploadFor(null)}
          onUpload={uploadFiles}
        />
      ) : null}

      {micOff ? (
        <PromptDialog
          spec={prompts.micOff()}
          open
          onChoose={(choice) => {
            const target = micOff;
            setMicOff(null);
            if (choice === "upload") setUploadFor(target);
          }}
        />
      ) : null}

      {trayOpen ? (
        <FieldDialog open onClose={() => setTrayOpen(false)} title={qs("outboxTitle")}>
          <div className="grid gap-3" data-tray-dialog>
            {myClips.length ? (
              <ul className="grid gap-1.5" data-upload-states>
                {myClips.map((s) => {
                  const state = clipUploadState(s, queue.progress[s.clientClipKey], queue.online);
                  const words =
                    s.error === "MISSING_FILE"
                      ? qs("missingOutboxFile", { target: s.label ?? qs("wholeSession"), date: shortDate(s.recording.deviceCapturedAt) })
                      : state.kind === "UPLOADING"
                        ? qs("uploadingPercent", { percent: state.percent })
                        : state.kind === "WAITING"
                          ? qs("waitingForSignal")
                          : state.kind === "UPLOADED"
                            ? qs("uploaded")
                            : qs("uploadFailedRetrying");
                  const bad = state.kind === "RETRYING" || state.kind === "FAILED";
                  return (
                    <li key={s.clientClipKey} className="flex flex-wrap items-center justify-between gap-2 text-sm" data-upload-state={state.kind}>
                      <span className="text-ink-700">{[s.label ?? qs("wholeSession"), shortDate(s.recording.deviceCapturedAt)].filter(Boolean).join(" · ")}</span>
                      <span className={bad ? "text-error-600" : "text-ink-500"}>{words}</span>
                      {bad && queue.online ? (
                        <button type="button" className="min-h-11 text-sm font-semibold text-purple-700 underline dark:text-purple-300" onClick={queue.retry}>
                          {qs("retry")}
                        </button>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="text-sm text-ink-500">{qs("saved")}</p>
            )}
            <OutboxTray sessionId={session.id} />
          </div>
        </FieldDialog>
      ) : null}

      {p2 ? <AlreadyRecordedDialog open input={p2.input} preselected={p2.reason} otherTargets={otherTargets} onChoose={chooseP2} /> : null}

      {p3 ? (
        <PromptDialog
          spec={prompts.p3({ target: activeRecording()?.label ?? "", elapsed: recorderClock(activeRecording()?.elapsed() ?? 0), code: p3.code || p3.label })}
          open
          onChoose={async (choice) => {
            const next = p3;
            setP3(null);
            if (choice === "stop") {
              await activeRecording()?.stop();
              void begin(next);
            }
          }}
        />
      ) : null}

      {p5 ? (
        <PromptDialog
          spec={p5.spec}
          open
          onChoose={(choice) => {
            const current = p5;
            setP5(null);
            void current.act?.(choice).catch((error: unknown) => setTakeError(error instanceof Error ? error.message : String(error)));
          }}
        />
      ) : null}

      {p7 ? (
        <PromptDialog
          spec={prompts.p7({ position: p7.position, target: p7.target })}
          open
          onChoose={(choice) => {
            const next = p7;
            setP7(null);
            if (choice === "replace") next.run();
          }}
        />
      ) : null}

      {p10 ? (
        <PromptDialog
          spec={prompts.p10({ name: p10.name, count: p10.count })}
          open
          onChoose={(choice) => {
            const target = p10;
            setP10(null);
            if (choice === "remove") void dropParticipant(target.artisanId, true);
          }}
        />
      ) : null}

      {conflict && queue.unit ? (
        <PromptDialog
          spec={conflictSpec(conflict.result, shortName(names[conflict.item.artisanId ?? `R:${conflict.item.respondentId ?? ""}`] ?? ""), labelOf(questionKey(conflict.item.questionLineageId)))}
          open
          onChoose={(choice) => {
            if (choice !== "mine" && choice !== "theirs") return;
            void resolveQueuedAnswer(queue.unit!.id, conflict.stepKey, conflict.item, choice).then(() => load());
          }}
        />
      ) : null}

      {removeTake ? (
        <PromptDialog
          spec={prompts.p7r({ position: removeTake.subjects[0]?.position ?? 1, target: removeTake.targets[0]?.label ?? "" })}
          open
          onChoose={async (choice) => {
            const r = removeTake;
            setRemoveTake(null);
            if (choice === "remove") {
              await questionnaireApi.removeRecording(r.id).catch(() => undefined);
              void load();
            }
          }}
        />
      ) : null}

      {finishOpen ? (
        <PromptDialog
          spec={withRequired(prompts.finish({ sections: session.counts.sections, artisans: session.participantCount, pending: pendingCount }), unanswered)}
          open
          onChoose={async (choice) => {
            setFinishOpen(false);
            if (choice === "finish") {
              await questionnaireApi.finish(session.id).catch(() => undefined);
              setView("session");
              void load();
            }
          }}
        />
      ) : null}

      {leaveOpen ? (
        <PromptDialog
          spec={prompts.leave({ count: guarded })}
          open
          onChoose={(choice) => {
            setLeaveOpen(false);
            if (choice === "leave") router.push("/questionnaire");
          }}
        />
      ) : null}
    </>
  );

  if (view === "review") {
    return (
      <div className="grid gap-4" data-session-workspace>
        <ReviewFinish
          answered={review ? review.answered : progress.answered}
          total={review ? review.total : progress.total}
          sectionsDone={progress.sectionsDone}
          names={session.participants.map((p) => p.name.trim()).filter(Boolean).join(", ")}
          pending={pendingCount}
          decisions={decisions}
          failed={failedUploads}
          still={stillToAsk(shownGrid.sections, progress, review)}
          open={live}
          onChoose={(r) => {
            const sectionId = r.targets[0]?.sectionId ?? (r.targets[0]?.questionLineageId ? sectionOf(r.targets[0].questionLineageId) : null);
            if (sectionId) setSheet({ sectionId, artisanId: soloArtisan });
          }}
          onOpenSection={(sectionId) => {
            setView("session");
            openSection(sectionId);
          }}
          onKeepGoing={() => setView("session")}
          onFinish={() => setFinishOpen(true)}
        />
        {dialogs}
      </div>
    );
  }

  const statusLine = (sectionId: string, q: GridQuestion, mark: QuestionMark | undefined) => {
    const text = typedText(q.lineageId);
    const openTakes = () => setSheet({ sectionId, artisanId: soloArtisan });
    return (
      <>
        {mark?.kind === "SECTION_TAKE" ? (
          <button type="button" className="min-h-8 w-fit text-left text-[0.8125rem] font-semibold text-success-600 dark:text-green-400" onClick={openTakes} data-question-state="SECTION_TAKE">
            {qs("inSectionTake")}
          </button>
        ) : null}
        {mark?.kind === "QUESTION_TAKE" ? (
          <button
            type="button"
            className="flex min-h-8 w-fit items-center gap-1.5 text-left text-[0.8125rem] font-semibold text-success-600 dark:text-green-400"
            onClick={openTakes}
            data-question-state="QUESTION_TAKE"
          >
            <Mic className="h-4 w-4" aria-hidden />
            {qs("questionTake", { duration: mark.durationMs ? recorderClock(mark.durationMs) : "—" })}
          </button>
        ) : null}
        {text && !typing[q.lineageId] ? (
          <button
            type="button"
            aria-label={qs("openTypedAnswer", { code: q.displayCode })}
            className="flex min-h-11 max-w-full items-center text-left text-sm text-ink-500"
            onClick={() => setTyping((held) => ({ ...held, [q.lineageId]: true }))}
            data-typed-preview={q.lineageId}
          >
            <span className="min-w-0 truncate">
              <span className="font-semibold text-success-600 dark:text-green-400">{qs("stateTyped")}</span>
              {qs("typedPreview", { text }).slice(qs("stateTyped").length)}
            </span>
          </button>
        ) : null}
        {!mark || mark.kind === "NONE" ? (
          !text ? (
            <span className="text-[0.8125rem] text-ink-500" data-question-state="NONE">
              {qs("notAsked")}
            </span>
          ) : null
        ) : null}
      </>
    );
  };

  return (
    <div className="grid gap-4" data-session-workspace>
      <SessionHeader
        session={session}
        answered={progress.answered}
        total={progress.total}
        sectionsDone={progress.sectionsDone}
        sectionsTotal={shownGrid.sections.length}
        savePill={<SavePill state={save} onOpen={() => setTrayOpen(true)} onRetry={queue.retry} />}
        onReview={() => setView("review")}
        menu={
          <SessionActions
            session={session}
            onChanged={() => void load()}
            recordBy={recordBy}
            onRecordBy={(next) => void changeRecordBy(next)}
            spoken={spoken}
            onRemoveArtisan={
              session.version > 0
                ? (artisanId) => {
                    const count = takesFor(detail.recordings, artisanId);
                    const name = shortName(names[artisanId] ?? "");
                    if (count > 0) setP10({ artisanId, name, count });
                    else void dropParticipant(artisanId, false);
                  }
                : undefined
            }
          />
        }
      />

      <SessionPeople
        session={session}
        onChanged={() => void load()}
        onPresence={(artisanId, present) => {
          const at = new Date().toISOString();
          void enqueue(unitSession, { kind: "participant", status: "PENDING", op: present ? "join" : "leave", artisanId, at, idempotencyKey: mintKey("part") });
          setDetail({ ...detail, participants: detail.participants.map((p) => (p.artisanId === artisanId ? { ...p, leftAt: present ? null : at } : p)) });
        }}
        onAddArtisan={session.state === "OPEN" ? () => setAdding(!adding) : undefined}
      />

      {adding ? (
        <AddArtisan exclude={session.participants.map((p) => p.artisanId)} scope={session.designWorkshopId ?? session.workshop?.id ?? null} onPick={(a) => void addArtisan(a)} />
      ) : null}

      {!live ? (
        <p role="status" className="rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm font-medium text-ink-700" data-finished-banner>
          {qs("finishedReadOnly", { date: shortDate(session.closedAt ?? session.endedAt ?? session.sessionDate) })}
        </p>
      ) : null}

      {proposalNote ? (
        <p role="status" className="panel p-3 text-sm text-ink-700" data-proposal-note>
          {proposalNote}
        </p>
      ) : null}

      {myClips.filter((s) => s.error === "MISSING_FILE").map((s) => (
        <p key={s.clientClipKey} role="status" className="panel border-error-600/30 p-3 text-sm text-error-600">
          {qs("missingOutboxFile", { target: s.label ?? qs("wholeSession"), date: shortDate(s.recording.deviceCapturedAt) })}
        </p>
      ))}

      {takeError ? (
        <p role="alert" className="panel border-error-600/30 p-3 text-sm text-error-600">
          {takeError}
        </p>
      ) : null}

      {shownGrid.sections.length ? (
        <div className="mx-auto grid w-full max-w-[55rem] gap-2" data-sections>
          {shownGrid.sections.slice(0, shownCount).map((s) => {
            const p = progress.sections[s.id];
            const open = activeSection === s.id;
            const tone = sectionTone(p, open);
            const sub = open && p && p.answered > 0 ? qs("answeredOfTotal", { answered: p.answered, total: p.total }) : sectionSubline(p);
            const required = (lineageId: string) => Boolean(questions[s.id]?.find((x) => x.lineageId === lineageId)?.required);
            return (
              <section
                key={s.id}
                id={`section-${s.id}`}
                className={`scroll-mt-[var(--session-header-h,0px)] lg:scroll-mt-[calc(var(--session-header-h,0px)+4.25rem)] ${
                  open ? "overflow-hidden rounded-lg border border-purple-300 bg-card shadow-sm dark:border-purple-700/60" : ""
                }`}
                data-section={s.code}
                data-section-open={open ? "" : undefined}
              >
                <button
                  type="button"
                  id={`section-head-${s.id}`}
                  aria-expanded={open}
                  aria-controls={`section-body-${s.id}`}
                  aria-label={open ? `${s.code}, ${s.title}, ${sub}. ${qs("closeSection")}` : `${qs("chipSection", { code: s.code })}, ${s.title}, ${sub}`}
                  className={`flex w-full items-center gap-3.5 text-left transition ${
                    open
                      ? "min-h-18 bg-purple-100 px-4 py-3 dark:bg-purple-700/22"
                      : "min-h-16 rounded-lg border border-line-200 bg-card px-4 py-2.5 hover:border-purple-300"
                  }`}
                  onClick={() => openSection(open ? null : s.id)}
                >
                  <span aria-hidden className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-md font-display text-[0.9375rem] font-bold ${TILE[tone]}`}>
                    {s.code}
                  </span>
                  <span
                    className={`min-w-0 flex-1 ${open ? "font-display text-lg font-bold leading-6 text-purple-950 sm:text-xl sm:leading-7 dark:text-ink-900" : "text-[0.9375rem] font-semibold leading-5 text-ink-900"}`}
                  >
                    {s.title}
                  </span>
                  <span className={`shrink-0 text-[0.8125rem] leading-[1.125rem] ${open ? "text-sm font-semibold" : ""} ${SUB[tone]}`}>{sub}</span>
                  {open ? (
                    <ChevronUp className="h-4.5 w-4.5 shrink-0 text-purple-800 dark:text-purple-200" aria-hidden />
                  ) : (
                    <ChevronDown className="h-4.5 w-4.5 shrink-0 text-ink-500" aria-hidden />
                  )}
                </button>
                {open ? (
                  <div id={`section-body-${s.id}`} className="grid grid-cols-[minmax(0,1fr)] gap-4 px-3 pb-2 pt-5 sm:px-6" data-section-body={s.code}>
                    {live && recordBy === "SECTION" && !recording ? (
                      <RecordUploadPair
                        recordLabel={qs("recordSection")}
                        onRecord={() => press([{ sectionId: s.id }], `Section ${s.code}`, s.code)}
                        onUpload={() => setUploadFor({ kind: "SECTION", sectionId: s.id })}
                      />
                    ) : null}
                    <ol className="grid grid-cols-[minmax(0,1fr)] border-t border-line-200">
                      {(s.questions ?? []).map((q) => {
                        const mark = p?.marks[q.lineageId];
                        const done = Boolean(mark && mark.kind !== "NONE");
                        return (
                          <li key={q.lineageId} className="flex gap-3 border-b border-line-200 py-4 sm:gap-4" data-question={q.displayCode}>
                            <span
                              aria-hidden
                              className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-md font-display text-sm font-bold ${
                                done ? TILE.done : TILE.blank
                              }`}
                            >
                              {q.displayCode}
                            </span>
                            <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)] gap-2">
                              <p className="text-base leading-6 text-ink-900">
                                {q.prompt}
                                {required(q.lineageId) ? (
                                  <span className="ml-1 text-error-600" aria-label={qs("required")}>
                                    *
                                  </span>
                                ) : null}
                              </p>
                              {statusLine(s.id, q, mark)}
                              {live && recordBy === "QUESTION" && !recording ? (
                                <RecordUploadPair
                                  size="sm"
                                  recordLabel={qs("record")}
                                  onRecord={() => press([{ questionLineageId: q.lineageId }], q.displayCode, q.displayCode)}
                                  onUpload={() => setUploadFor({ kind: "QUESTION", lineageId: q.lineageId })}
                                />
                              ) : null}
                              {typing[q.lineageId] ? (
                                <AnswerBox
                                  key={`${q.lineageId}:${proposals[q.lineageId]?.sourceRecordingId ?? ""}`}
                                  session={session}
                                  lineageId={q.lineageId}
                                  code={q.displayCode}
                                  subjects={subjects}
                                  answers={detail.answers.filter((a) => a.questionLineageId === q.lineageId)}
                                  recordings={detail.recordings}
                                  me={me}
                                  mayOverride={mayOverride}
                                  proposal={proposals[q.lineageId] ?? null}
                                  required={required(q.lineageId)}
                                  onDone={() => {
                                    setTyping((held) => {
                                      const next = { ...held };
                                      delete next[q.lineageId];
                                      return next;
                                    });
                                    void load();
                                  }}
                                />
                              ) : null}
                            </div>
                            {live ? (
                              <QuestionOverflow
                                code={q.displayCode}
                                recordBy={recordBy}
                                hide={recording ? ["record", "upload"] : []}
                                extra={profileFields.has(q.lineageId) && session.participants.length > 0 ? ["prefill"] : []}
                                onAction={(action) => questionAction(s.id, q, action)}
                              />
                            ) : null}
                          </li>
                        );
                      })}
                    </ol>
                  </div>
                ) : null}
              </section>
            );
          })}
          {shownCount < shownGrid.sections.length ? (
            <div className="flex flex-wrap items-center justify-center gap-x-3.5 gap-y-2 pt-2" data-sections-capped>
              <span className="text-[0.8125rem] text-ink-500">{qs("showingSections", { shown: shownCount, total: shownGrid.sections.length })}</span>
              <button type="button" className="field-button-secondary min-h-11 font-semibold" onClick={() => setAllSections(true)}>
                {qs("showAll", { count: shownGrid.sections.length })}
              </button>
            </div>
          ) : null}
        </div>
      ) : live ? (
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => press([], qs("wholeSession"), "")}>
            {qs("record")}
          </button>
        </ButtonRow>
      ) : null}

      {showDock ? (
        <>
          <div aria-hidden style={{ height: dockHeight }} />
          <div
            ref={dockRef}
            className="pointer-events-none fixed inset-x-0 z-40 pl-4 pr-[calc(1rem+var(--nav-scroll-gutter,0px))]"
            style={{ bottom: "var(--bottom-nav-h, 0px)" }}
            data-dock
          >
            <div className="pointer-events-auto mx-auto grid max-w-[55rem] gap-2.5 rounded-t-xl border border-b-0 border-line-200 bg-card px-4 pb-[max(env(safe-area-inset-bottom),1rem)] pt-3 shadow-lg">
              {recording ? (
                <Recorder
                  target={recording.target}
                  autoStart
                  heading={recordingHeading}
                  onCancel={() => setRecording(null)}
                  onMicOff={() => {
                    const r = recording;
                    setRecording(null);
                    const t = r.context.pending.targets[0];
                    setMicOff(!t ? { kind: "SESSION" } : "sectionId" in t ? { kind: "SECTION", sectionId: t.sectionId } : { kind: "QUESTION", lineageId: t.questionLineageId });
                  }}
                  onFinished={(take) => {
                    const r = recording;
                    setRecording(null);
                    finishTake(take, r.context);
                  }}
                />
              ) : activeGridSection ? (
                <div role="region" aria-label={qs("sectionActions", { code: activeGridSection.code })} className="grid gap-2.5">
                  <span className="text-[0.8125rem] font-medium text-ink-500">{qs("chipSection", { code: activeGridSection.code })}</span>
                  <RecordUploadPair
                    recordLabel={qs("recordSection")}
                    onRecord={() => press([{ sectionId: activeGridSection.id }], `Section ${activeGridSection.code}`, activeGridSection.code)}
                    onUpload={() => setUploadFor({ kind: "SECTION", sectionId: activeGridSection.id })}
                  />
                </div>
              ) : null}
            </div>
          </div>
        </>
      ) : null}

      {dialogs}
    </div>
  );
}

export function conflictSpec(result: import("@/lib/questionnaire/api").AnswerResult, name: string, code: string): PromptSpec {
  const current = result.current;
  if (result.status === "FORBIDDEN") {
    return { id: "ANSWER_PINNED", title: qs("answerPinned", { name, code }), body: current?.text ?? "", buttons: [{ id: "theirs", label: qs("ok"), tone: "primary" }], dismiss: null };
  }
  if (current?.editedBy) {
    return prompts.p9({
      researcher: current.editedBy.name,
      name,
      code,
      time: current.editedAt
        ? new Date(current.editedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" })
        : ""
    });
  }
  return {
    id: "ANSWER_EXISTS",
    title: qs("answerExists", { name }),
    body: current?.text ?? "",
    buttons: [
      { id: "theirs", label: qs("keep"), tone: "secondary" },
      { id: "mine", label: qs("replace"), tone: "primary" }
    ],
    dismiss: null
  };
}

export function withRequired(spec: PromptSpec, unanswered: number): PromptSpec {
  if (unanswered <= 0) return spec;
  return { ...spec, body: `${spec.body} ${qs("requiredUnanswered", { count: unanswered })}`.trim() };
}

function AddArtisan({ exclude, scope, onPick }: { exclude: string[]; scope: string | null; onPick: (artisan: { id: string; name: string }) => void }) {
  const [search, setSearch] = useState("");
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void listResource<Artisan>("/artisans", { pageSize: 80, search: search || undefined, ...(search ? {} : artisanFilter(scope)) })
        .then((page) => {
          if (!cancelled) setArtisans(page.items ?? []);
        })
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, scope]);
  const options = artisans.filter((a) => !exclude.includes(a.id)).map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }));
  return (
    <div className="panel grid gap-1 p-4" data-add-artisan>
      <span className="field-label">{qs("addArtisan")}</span>
      <SearchableSelect
        value=""
        onChange={(value) => {
          const artisan = artisans.find((a) => a.id === value);
          if (artisan) onPick({ id: artisan.id, name: artisan.name });
        }}
        onSearch={setSearch}
        options={options}
        searchable
        advanceOnSelect={false}
        ariaLabel={qs("addArtisan")}
      />
    </div>
  );
}
