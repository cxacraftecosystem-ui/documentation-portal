"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { AlreadyRecordedDialog, type P2Choice } from "@/components/questionnaire/AlreadyRecordedDialog";
import { AnswerBox, sessionSubjects, type AnswerProposal } from "@/components/questionnaire/AnswerBox";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { CoverageGrid } from "@/components/questionnaire/CoverageGrid";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { activeRecording, Recorder, recorderClock, takesInHand, type FinishedTake, type RecorderTarget } from "@/components/questionnaire/Recorder";
import { SessionHeader } from "@/components/questionnaire/SessionHeader";
import { TakeSheet } from "@/components/questionnaire/TakeSheet";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { enqueue, onRecordingLanded, onSessionLanded, resolveQueuedAnswer, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError, clockOffset, listResource } from "@/lib/api";
import { loadInstrumentQuestions, requiredUnanswered, type InstrumentQuestion, type InstrumentSection } from "@/components/questionnaire/instrumentQuestions";
import { SessionActions } from "@/components/questionnaire/SessionActions";
import {
  offlineCheck,
  overlayFromUnits,
  preselectReason,
  questionKey,
  readSnapshot,
  refreshSnapshot,
  sectionKey,
  shortName,
  takesFromRecordings,
  withTakes
} from "@/lib/questionnaire/coverageCache";
import { sha256Hex } from "@/lib/questionnaire/hashFile";
import { prefillItems } from "@/lib/questionnaire/prefill";
import {
  contractError,
  mintKey,
  questionnaireApi,
  type CoverageCheckResponse,
  toGridView,
  type GridQuestion,
  type GridView as Grid,
  type Recording,
  type RepeatReason,
  type Session,
  type SessionDetail
} from "@/lib/questionnaire/api";
import { prompts, shortDate, type P2Input, type PromptSpec } from "@/lib/questionnaire/prompts";
import { clipUploadState, sessionUnitKey, type ClipStep, type SessionUnit } from "@/lib/questionnaire/queue";
import { consentSubject, pickedFileMeta, rosterArtisans, takesFor, takeSubjects } from "@/lib/questionnaire/view";
import { assembleTake, recorderStore, recoverable, recoveredMinutes, type TakeChunk, type TakeDraft } from "@/lib/questionnaire/recorderStore";
import { OutboxTray } from "@/components/questionnaire/OutboxTray";
import { qs } from "@/lib/questionnaire/qs";
import { artisanFilter } from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

type Target = { sectionId: string } | { questionLineageId: string };
/** What a Record press or file pick is for; the roster is snapshotted at the press (DESIGN §3.4). */
type Pending = { targets: Target[]; label: string; code: string; file?: File | null; roster: string[] };
/** Everything a take carries from the press to the outbox. */
type TakeContext = {
  pending: Pending;
  reasons: Record<string, RepeatReason>;
  acked: string[];
  replaces: string | null;
  continuation: string | null;
  sha: string | null;
  source: string;
};

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
    respondents: (body.respondents ?? []).map((r, i) => ({ id: `local-${i}`, sessionId: body.clientKey, name: r.name, kind: r.kind, notes: r.notes ?? null, userId: null }))
  };
}

function gridFromSections(sections: InstrumentSection[], byId: Record<string, GridQuestion[]>): Grid {
  return { sections: sections.map((s) => ({ id: s.id, code: s.code, title: s.title, questions: byId[s.id] })), cells: [] };
}

/** The clip keys already in the outbox: a draft whose take reached it needs no P13. */
function queuedClipKeys(units: SessionUnit[]): Set<string> {
  return new Set(units.flatMap((u) => u.steps.filter((s): s is ClipStep => s.kind === "clip").map((s) => s.clientClipKey)));
}

/**
 * The capture workspace (DESIGN §4.3–4.5, §6.2): the session's coverage grid, the take sheet, the
 * recorder and every prompt. A session started offline lives in the outbox under its clientKey and
 * works the same way; its route swaps to the server id once it lands. A rebuilt session with no
 * clientKey is queued under its id (audit C4).
 */
export function SessionWorkspace({ id }: { id: string }) {
  const router = useRouter();
  const { user } = useAuth();
  // Removing a take, and merging sessions, are content admins' acts, admin view or not (owner, 2026-10-21).
  const { canDeleteRecords } = useAdminView();
  const config = useQuestionnaireConfig();
  const paused = config?.transcriptionPaused ?? true;
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [grid, setGrid] = useState<Grid | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [sheet, setSheet] = useState<{ sectionId: string; artisanId: string | null } | null>(null);
  const [activeSection, setActiveSection] = useState<string | null>(null);
  const [typeAnswers, setTypeAnswers] = useState(false);
  const [p2, setP2] = useState<{ pending: Pending; input: P2Input; reason: RepeatReason; check: CoverageCheckResponse } | null>(null);
  const [recording, setRecording] = useState<{ target: RecorderTarget; context: TakeContext } | null>(null);
  const [answering, setAnswering] = useState<{ take: FinishedTake; context: TakeContext; chosen: string[] } | null>(null);
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
  // The form's questions with their required marks (A19), and the transcripts offered as answers (B22).
  const [questions, setQuestions] = useState<Record<string, InstrumentQuestion[]>>({});
  const [proposals, setProposals] = useState<Record<string, AnswerProposal>>({});
  const [proposalNote, setProposalNote] = useState<string | null>(null);
  const queue = useSessionQueue(detail ?? id);
  const unitsRef = useRef<SessionUnit[]>(queue.units);
  useEffect(() => {
    unitsRef.current = queue.units;
  }, [queue.units]);
  const generation = useRef(0);

  const load = useCallback(async () => {
    const mine = (generation.current += 1);
    setLoadError(null);
    try {
      const d = await questionnaireApi.getSession(id);
      if (mine !== generation.current) return;
      setDetail(d);
      setGrid(toGridView(d.grid));
      void loadInstrumentQuestions(d.questionnaire.id)
        .then((q) => {
          if (mine !== generation.current) return;
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

  // The workshop's coverage snapshot, kept fresh while the workspace is open, so P2 asks the same
  // question offline (audit M4). A failure keeps the held one.
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

  /**
   * P13: a take this browser started for this session and never handed to the outbox. A take that
   * reached the outbox before the draft was cleared is cleared now; one being handed over in this tab
   * is left alone (audit H17).
   */
  const checkRecovery = useCallback(async () => {
    if (!sessionKey || activeRecording()) return;
    try {
      const [drafts, chunks] = await Promise.all([recorderStore.drafts(), recorderStore.chunks()]);
      const queued = queuedClipKeys(unitsRef.current);
      for (const d of drafts) if (queued.has(d.clientClipKey)) void recorderStore.discard(d.takeKey).catch(() => undefined);
      setP13(recoverable(drafts.filter((d) => d.sessionKey === sessionKey && !takesInHand.has(d.takeKey) && !queued.has(d.clientClipKey)), chunks));
    } catch {
      // No IndexedDB (a private window): nothing was kept to recover.
    }
  }, [sessionKey]);

  useEffect(() => {
    void checkRecovery();
  }, [checkRecovery]);

  // B24: questions mapped to an artisan column start with that artisan's value (origin PREFILL),
  // written once per open by a researcher of the session; never over an answer somebody wrote.
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
        // Nothing is lost: the next open offers the same values again.
      }
    });
  }, [detail, questions, userId, load]);

  const pendingCount = queue.pendingClips.length;
  // A take in progress, or waiting for "Answered by", is unsaved work too (audit, leave guard).
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

  // Overlay: takes still in the outbox show as uploading on the grid.
  const shownGrid = useMemo<Grid | null>(() => {
    if (!grid) return null;
    const cells = [...grid.cells];
    for (const step of queue.pendingClips as ClipStep[]) {
      for (const subject of step.recording.subjects) {
        if (subject.role !== "ANSWERED") continue;
        // The grid column: the artisan's id, or `R:<id>` for a guest on the take.
        const column = subject.artisanId ?? (subject.respondentId ? `R:${subject.respondentId}` : null);
        if (!column) continue;
        for (const t of step.recording.targets) {
          const key = "sectionId" in t ? sectionKey(t.sectionId) : null;
          if (!key) continue;
          const existing = cells.findIndex((c) => c.artisanId === column && c.targetKey === key);
          const state = queue.online ? "UPLOADING" : "ON_DEVICE";
          if (existing < 0) cells.push({ artisanId: column, targetKey: key, state, count: 1 });
          else if (cells[existing].state === "NOT_RECORDED") cells[existing] = { ...cells[existing], state };
        }
      }
    }
    return { ...grid, cells };
  }, [grid, queue.pendingClips, queue.online]);

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
  if (!detail || !shownGrid) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;

  const session = detail;
  const unitSession = { id: session.id, clientKey: session.clientKey };
  const guests = (session.respondents ?? []).filter((r) => !r.artisanId);
  // The people in the room at a press: present artisans, then guests (`R:<id>`, owner decision 1).
  const roster = [...session.participants.filter((p) => !p.leftAt).map((p) => p.artisanId), ...guests.map((g) => `R:${g.id}`)];
  const names: Record<string, string> = Object.fromEntries([
    ...session.participants.map((p) => [p.artisanId, p.name] as const),
    ...guests.map((g) => [`R:${g.id}`, g.name] as const)
  ]);
  const subjects = sessionSubjects(session);
  const me = user?.id ?? null;
  // A LEAD researcher of the session, or an admin, may change anyone's answer (the lock, U9).
  const mayOverride = Boolean(user && (canDeleteRecords || session.researchers.some((r) => r.userId === user.id && r.role === "LEAD")));
  const unanswered = requiredUnanswered(questions, detail.answers.map((a) => ({ ...a, text: a.text ?? "" })), subjects.map((s) => s.key), detail.recordings);
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

  /**
   * `coverage/check`, or — offline OR on any failure — the held workshop snapshot with this session's
   * own takes and the outbox laid over it (audit M4). A failed check is never read as "not covered".
   */
  async function check(pending: Pending, sha: string | null): Promise<CoverageCheckResponse> {
    const request = { questionnaireId: session.questionnaire.id, artisanIds: rosterArtisans(pending.roster), targets: pending.targets, sessionId: session.id, contentSha256: sha };
    if (navigator.onLine !== false) {
      try {
        return await questionnaireApi.checkCoverage(request);
      } catch {
        // Fall through to what this browser holds.
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

  /** P10: a participant with takes leaves only with `detach`; the server's count wins over ours. */
  async function dropParticipant(artisanId: string, detach: boolean) {
    try {
      await questionnaireApi.removeParticipant(session.id, artisanId, detach);
      void load();
    } catch (error) {
      const code = contractError(error);
      if (code?.code === "PARTICIPANT_HAS_TAKES") setP10({ artisanId, name: shortName(names[artisanId] ?? ""), count: code.count ?? 1 });
    }
  }

  /** "+ Add artisan" (audit M22): through the outbox, so it works offline and in order. */
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

  /**
   * P5, from the session the identical file is on (audit M22): this session → add the artisans the
   * take is missing; another session of the same sitting → merge (content admins); otherwise OK.
   */
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
            // Everyone already on the take stays as they were; the missing artisans join it as answering.
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
          filePick: Boolean(pending.file),
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
    if (pending.file) {
      // A picked file is dated the session day, never by its copy time; its type names a missing extension.
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

  /** After Stop or a pick: a group asks "Answered by" first (all ticked, the section's last choice kept). */
  function finishTake(take: FinishedTake, context: TakeContext) {
    if (context.pending.roster.length > 1) {
      const remembered = lastAnswered[sectionOfTargets(context.pending.targets)]?.filter((a) => context.pending.roster.includes(a));
      setAnswering({ take, context, chosen: remembered?.length ? remembered : context.pending.roster });
      return;
    }
    void queueClip(take, context, new Set(context.pending.roster));
  }

  /**
   * Into the outbox. The recorder's draft is cleared only once the outbox holds the take (audit H17);
   * if the write fails, the draft stays and P13 offers the take back at once.
   */
  async function queueClip(take: FinishedTake, context: TakeContext, answered: ReadonlySet<string>) {
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
          ackedRecordingIds: context.acked
        }
      };
      await enqueue(unitSession, step, { key: take.takeKey, blob: take.blob });
      await recorderStore.discard(take.takeKey).catch(() => undefined);
    } catch {
      setTakeError(qs("takeNotSaved"));
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
      // P7 first: the earlier take is removed (restorably) once this one uploads.
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

  /**
   * "Use as answer" (B22, C2) and AI drafts (B23, C3): a take's transcript, and drafts where the
   * switch is on, proposed per question. Offered in the answer box, never imposed; P8 still asks
   * before a take-made answer is replaced. The server sends only what consent allows.
   */
  async function offerAnswers(recording: Recording) {
    setProposalNote(null);
    try {
      const { proposals: found } = await questionnaireApi.answerProposals(recording.id);
      const next = { ...proposals };
      for (const p of found) {
        next[p.questionLineageId] = {
          text: p.text,
          origin: p.origin,
          sourceRecordingId: recording.id,
          subjectKeys: p.artisanIds ? p.artisanIds.map((a) => `A:${a}`) : null
        };
      }
      setProposals(next);
      setTypeAnswers(true);
      setProposalNote(found.length ? qs("proposalsReady", { count: found.length }) : qs("noProposals"));
      const first = found[0] ? sectionOf(found[0].questionLineageId) : null;
      if (first) setActiveSection(first);
    } catch (error) {
      setProposalNote(error instanceof Error ? error.message : String(error));
    }
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

  return (
    <div className="grid gap-4" data-session-workspace>
      <OutboxTray sessionId={session.id} />
      <SessionHeader
        session={session}
        pendingTakes={pendingCount}
        onFinish={() => setFinishOpen(true)}
        onPresence={(artisanId, present) => {
          const at = new Date().toISOString();
          void enqueue(unitSession, { kind: "participant", status: "PENDING", op: present ? "join" : "leave", artisanId, at, idempotencyKey: mintKey("part") });
          setDetail({ ...detail, participants: detail.participants.map((p) => (p.artisanId === artisanId ? { ...p, leftAt: present ? null : at } : p)) });
        }}
        onRemove={
          session.version > 0
            ? (artisanId) => {
                const count = takesFor(detail.recordings, artisanId);
                const name = shortName(names[artisanId] ?? "");
                if (count > 0) setP10({ artisanId, name, count });
                else void dropParticipant(artisanId, false);
              }
            : undefined
        }
        onAddArtisan={session.state === "OPEN" ? () => setAdding(!adding) : undefined}
      />

      {adding ? (
        <AddArtisan exclude={session.participants.map((p) => p.artisanId)} scope={session.designWorkshopId ?? session.workshop?.id ?? null} onPick={(a) => void addArtisan(a)} />
      ) : null}

      {session.version > 0 ? <SessionActions session={session} onChanged={() => void load()} /> : null}

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

      {recording ? (
        <div className="panel p-4">
          <Recorder
            target={recording.target}
            onCancel={() => setRecording(null)}
            onFinished={(take) => {
              const r = recording;
              setRecording(null);
              finishTake(take, r.context);
            }}
          />
        </div>
      ) : null}

      {answering ? (
        <section className="panel grid gap-3 p-4" data-answered-by>
          <h2 className="field-label">{qs("answeredBy")}</h2>
          <div className="flex flex-wrap gap-2" role="group" aria-label={qs("answeredBy")}>
            {answering.context.pending.roster.map((artisanId) => {
              const on = answering.chosen.includes(artisanId);
              return (
                <button
                  key={artisanId}
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  data-answered={artisanId}
                  className={`min-h-10 rounded-full border px-3 text-sm ${on ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700"}`}
                  onClick={() =>
                    setAnswering({ ...answering, chosen: on ? answering.chosen.filter((a) => a !== artisanId) : [...answering.chosen, artisanId] })
                  }
                >
                  {shortName(names[artisanId] ?? "")}
                </button>
              );
            })}
          </div>
          <ButtonRow>
            <button
              type="button"
              className="field-button"
              disabled={answering.chosen.length === 0}
              onClick={() => {
                const current = answering;
                setAnswering(null);
                setLastAnswered({ ...lastAnswered, [sectionOfTargets(current.context.pending.targets)]: current.chosen });
                void queueClip(current.take, current.context, new Set(current.chosen));
              }}
            >
              {qs("saveTake")}
            </button>
          </ButtonRow>
        </section>
      ) : null}

      <section className="panel grid gap-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          {shownGrid.sections.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-pressed={activeSection === s.id}
              className={`min-h-10 min-w-10 rounded-md border px-2 text-sm font-semibold ${activeSection === s.id ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700"}`}
              onClick={() => setActiveSection(activeSection === s.id ? null : s.id)}
            >
              {s.code}
            </button>
          ))}
          {pendingCount ? <span className="ml-auto text-xs text-purple-700">{qs("takesUploading", { count: pendingCount })}</span> : null}
        </div>
        {myClips.length ? (
          <ul className="grid gap-1" data-upload-states>
            {myClips
              .filter((s) => s.error !== "MISSING_FILE")
              .map((s) => {
                const state = clipUploadState(s, queue.progress[s.clientClipKey], queue.online);
                const words =
                  state.kind === "UPLOADING"
                    ? qs("uploadingPercent", { percent: state.percent })
                    : state.kind === "WAITING"
                      ? qs("waitingForSignal")
                      : state.kind === "UPLOADED"
                        ? qs("uploaded")
                        : qs("uploadFailedRetrying");
                const bad = state.kind === "RETRYING" || state.kind === "FAILED";
                return (
                  <li key={s.clientClipKey} className="flex flex-wrap items-center justify-between gap-2 text-xs" data-upload-state={state.kind}>
                    <span className="text-ink-700">{[s.label ?? qs("wholeSession"), shortDate(s.recording.deviceCapturedAt)].filter(Boolean).join(" · ")}</span>
                    <span className={bad ? "text-error-600" : "text-ink-500"}>{words}</span>
                    {bad && queue.online ? (
                      <button type="button" className="text-xs font-semibold text-purple-700 underline dark:text-purple-300" onClick={queue.retry}>
                        {qs("retry")}
                      </button>
                    ) : null}
                  </li>
                );
              })}
          </ul>
        ) : null}
        {shownGrid.sections.length ? (
          <CoverageGrid
            grid={shownGrid}
            artisans={[...session.participants.map((p) => ({ id: p.artisanId, name: p.name })), ...guests.map((g) => ({ id: `R:${g.id}`, name: g.name }))]}
            activeSectionId={activeSection}
            onOpen={(sectionId, artisanId) => setSheet({ sectionId, artisanId })}
          />
        ) : (
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => press([], qs("wholeSession"), "")}>
              {qs("record")}
            </button>
          </ButtonRow>
        )}
      </section>

      {activeGridSection ? (
        <section className="panel grid gap-3 p-4" data-section-body={activeGridSection.code}>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="display-title text-lg">
              {activeGridSection.code} {activeGridSection.title}
            </h2>
            <Toggle label={qs("typeAnswers")} checked={typeAnswers} onChange={setTypeAnswers} />
          </div>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => press([{ sectionId: activeGridSection.id }], `Section ${activeGridSection.code}`, activeGridSection.code)}>
              {qs("recordSection")}
            </button>
          </ButtonRow>
          <ol className="grid gap-3">
            {(activeGridSection.questions ?? []).map((q) => (
              <li key={q.lineageId} className="rounded-md border border-line-200 p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <p className="text-sm text-ink-900">
                    <span className="mr-2 font-semibold">{q.displayCode}</span>
                    {q.prompt}
                    {questions[activeGridSection.id]?.find((x) => x.lineageId === q.lineageId)?.required ? (
                      <span className="ml-1 text-error-600" aria-label={qs("required")}>
                        *
                      </span>
                    ) : null}
                  </p>
                  <button type="button" className="field-button-secondary" onClick={() => press([{ questionLineageId: q.lineageId }], q.displayCode, q.displayCode)}>
                    {qs("record")}
                  </button>
                </div>
                {typeAnswers ? (
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
                    required={Boolean(questions[activeGridSection.id]?.find((x) => x.lineageId === q.lineageId)?.required)}
                  />
                ) : null}
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {sheet && sheetSection ? (
        <TakeSheet
          open
          title={`Section ${sheetSection.code}${sheet.artisanId ? ` · ${names[sheet.artisanId]?.split(" ")[0] ?? ""}` : ""}`}
          recordings={sheetRecordings}
          artisanId={sheet.artisanId}
          paused={paused}
          canRecord={session.state === "OPEN"}
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
            // The artisan who refused, not simply the first one on the take (audit M22).
            const artisanId = consentSubject(r, session.participants);
            if (artisanId) setConsentFor({ artisanId, name: names[artisanId] ?? "" });
          }}
        />
      ) : null}

      {consentFor ? (
        <div className="panel p-4">
          <ConsentQuestion artisanId={consentFor.artisanId} name={consentFor.name} refused onRecorded={() => setConsentFor(null)} onDismiss={() => setConsentFor(null)} />
        </div>
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
    </div>
  );
}

/**
 * P9 for an answer held in the outbox: "Changed on another device" when the server says who edited
 * it, "Giriraj already has an answer. Keep · Replace" when one simply exists, and a pinned answer's
 * refusal with OK (which drops the typed text).
 */
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

/** A19 on Finish: "3 required questions unanswered" under the summary, never blocking. */
export function withRequired(spec: PromptSpec, unanswered: number): PromptSpec {
  if (unanswered <= 0) return spec;
  return { ...spec, body: `${spec.body} ${qs("requiredUnanswered", { count: unanswered })}`.trim() };
}

/** "+ Add artisan": artisans searched on the server (the session's workshop first), its own left out. */
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
