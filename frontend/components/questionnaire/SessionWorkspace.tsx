"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { AlreadyRecordedDialog, type P2Choice } from "@/components/questionnaire/AlreadyRecordedDialog";
import { AnswerBox } from "@/components/questionnaire/AnswerBox";
import { ConsentQuestion } from "@/components/questionnaire/ConsentQuestion";
import { CoverageGrid } from "@/components/questionnaire/CoverageGrid";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { activeRecording, Recorder, recorderClock, type FinishedTake, type RecorderTarget } from "@/components/questionnaire/Recorder";
import { SessionHeader } from "@/components/questionnaire/SessionHeader";
import { TakeSheet } from "@/components/questionnaire/TakeSheet";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { enqueue, onRecordingLanded, onSessionLanded, useSessionQueue } from "@/components/questionnaire/useSessionQueue";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Toggle } from "@/components/ui/Toggle";
import { ApiError, clockOffset } from "@/lib/api";
import { loadInstrumentQuestions } from "@/components/questionnaire/instrumentQuestions";
import { offlineCheck, preselectReason, questionKey, sectionKey, shortName } from "@/lib/coverageCache";
import { sha256Hex } from "@/lib/hashFile";
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
} from "@/lib/questionnaireApi";
import { prompts, shortDate, type P2Input } from "@/lib/questionnairePrompts";
import { takesFor } from "@/lib/questionnaireView";
import type { ClipStep } from "@/lib/questionnaireQueue";
import { assembleTake, recorderStore, recoverable, recoveredMinutes, type TakeChunk, type TakeDraft } from "@/lib/recorderStore";
import { qs } from "@/lib/strings/qs";
import type { QuestionnaireSection } from "@/lib/types";

type Target = { sectionId: string } | { questionLineageId: string };
type Pending = { targets: Target[]; label: string; code: string; file?: File | null };

function localSession(body: import("@/lib/questionnaireApi").SessionCreate, title: string): Session {
  return {
    id: body.clientKey,
    clientKey: body.clientKey,
    version: 0,
    title,
    note: body.note,
    legacyTitle: null,
    questionnaire: { id: body.questionnaireId, title: "" },
    workshop: body.workshopId ? { id: body.workshopId, name: "" } : null,
    designWorkshopId: null,
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
    counts: { sections: 0, takes: 0, needsDecision: 0 }
  };
}

function gridFromSections(sections: QuestionnaireSection[], byId: Record<string, GridQuestion[]>): Grid {
  return { sections: sections.map((s) => ({ id: s.id, code: s.code, title: s.title, questions: byId[s.id] })), cells: [] };
}

/**
 * The capture workspace (DESIGN §4.3–4.5, §6.2): the session's coverage grid, the take sheet, the
 * recorder and every prompt. A session started offline lives in the outbox under its clientKey and
 * works the same way; its route swaps to the server id once it lands.
 */
export function SessionWorkspace({ id }: { id: string }) {
  const router = useRouter();
  const { user } = useAuth();
  // Removing a take is a delete: content admins only, admin view or not (owner, 2026-10-21).
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
  const [recording, setRecording] = useState<{ target: RecorderTarget; pending: Pending; reasons: Record<string, RepeatReason>; acked: string[]; replaces?: string | null; continuation?: string | null } | null>(null);
  const [p3, setP3] = useState<Pending | null>(null);
  const [p5, setP5] = useState<{ spec: ReturnType<typeof prompts.p5> } | null>(null);
  const [p13, setP13] = useState<{ draft: TakeDraft; chunks: TakeChunk[] }[]>([]);
  const [finishOpen, setFinishOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [consentFor, setConsentFor] = useState<{ artisanId: string; name: string } | null>(null);
  const [removeTake, setRemoveTake] = useState<Recording | null>(null);
  const [p7, setP7] = useState<{ run: () => void; position: number; target: string } | null>(null);
  const [p10, setP10] = useState<{ artisanId: string; name: string; count: number } | null>(null);
  const queue = useSessionQueue(detail?.clientKey ?? id);
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
          if (mine === generation.current) setGrid(toGridView(d.grid, q.byId));
        })
        .catch(() => undefined);
    } catch (error) {
      if (mine !== generation.current) return;
      const unit = queue.units.find((u) => u.id === id);
      if (unit?.sessionId) {
        router.replace(`/questionnaire/sessions/${encodeURIComponent(unit.sessionId)}`);
        return;
      }
      const first = unit?.steps.find((s) => s.kind === "session");
      if (first && first.kind === "session") {
        setDetail({ ...localSession(first.body, ""), recordings: [], answers: [], grid: { questionnaireId: first.body.questionnaireId, artisans: [], sections: [] } });
        try {
          const q = await loadInstrumentQuestions(first.body.questionnaireId);
          setGrid(gridFromSections(q.sections, q.byId));
        } catch {
          setGrid({ sections: [], cells: [] });
        }
        return;
      }
      setLoadError(error instanceof ApiError && error.status === 404 ? qs("sessionLoadError") : qs("sessionLoadError"));
    }
  }, [id, queue.units, router]);

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

  // P13: a take this browser started for this session and never stopped.
  useEffect(() => {
    if (!detail) return;
    let cancelled = false;
    void Promise.all([recorderStore.drafts(), recorderStore.chunks()])
      .then(([drafts, chunks]) => {
        if (cancelled) return;
        setP13(recoverable(drafts.filter((d) => d.sessionKey === detail.clientKey && !activeRecording()), chunks));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [detail]);

  const pendingCount = queue.pendingClips.length;
  useLeaveGuard(pendingCount > 0, () => setLeaveOpen(true));
  useEffect(() => {
    if (pendingCount === 0) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [pendingCount]);

  // Overlay: takes still in the outbox show as uploading on the grid.
  const shownGrid = useMemo<Grid | null>(() => {
    if (!grid) return null;
    const cells = [...grid.cells];
    for (const step of queue.pendingClips as ClipStep[]) {
      for (const subject of step.recording.subjects) {
        if (subject.role !== "ANSWERED") continue;
        for (const t of step.recording.targets) {
          const key = "sectionId" in t ? sectionKey(t.sectionId) : null;
          if (!key) continue;
          const existing = cells.findIndex((c) => c.artisanId === subject.artisanId && c.targetKey === key);
          const state = navigator.onLine === false ? "ON_DEVICE" : "UPLOADING";
          if (existing < 0) cells.push({ artisanId: subject.artisanId, targetKey: key, state, count: 1 });
          else if (cells[existing].state === "NOT_RECORDED") cells[existing] = { ...cells[existing], state };
        }
      }
    }
    return { ...grid, cells };
  }, [grid, queue.pendingClips]);

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
  const roster = session.participants.filter((p) => !p.leftAt).map((p) => p.artisanId);
  const names = Object.fromEntries(session.participants.map((p) => [p.artisanId, p.name]));
  const sectionOf = (lineageId: string) => shownGrid.sections.find((s) => s.questions?.some((q) => q.lineageId === lineageId))?.id ?? null;
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
    const request = { questionnaireId: session.questionnaire.id, artisanIds: roster, targets: pending.targets, sessionId: session.id, contentSha256: sha };
    try {
      if (navigator.onLine === false) throw new Error("offline");
      return await questionnaireApi.checkCoverage(request);
    } catch {
      return offlineCheck(null, [], request, { sectionOf, labelOf, sessionId: session.id, me: user?.id ?? null });
    }
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

  async function begin(pending: Pending) {
    const live = activeRecording();
    if (live) {
      setP3(pending);
      return;
    }
    const sha = pending.file ? await sha256Hex(pending.file) : null;
    const answer = await check(pending, sha);
    if (pending.file && answer.identical) {
      const other = answer.identical;
      setP5({
        spec: prompts.p5({
          name: names[roster[0]]?.split(" ")[0] ?? "",
          date: shortDate(session.sessionDate),
          case: other.sameSession ? "SAME_SESSION" : "OTHER",
          addName: names[roster[0]]?.split(" ")[0] ?? ""
        })
      });
      return;
    }
    const covered = answer.targets[0]?.perArtisan.some((p) => p.takes.length > 0);
    if (covered) {
      const earlier = answer.targets[0].perArtisan.flatMap((p) => p.takes).sort((a, b) => b.capturedAt.localeCompare(a.capturedAt))[0] ?? null;
      const last = earlier;
      setP2({
        pending: { ...pending },
        check: answer,
        reason: preselectReason({ purpose: session.purpose, rosterCount: roster.length, earlier, me: user?.id ?? null, suggested: answer.suggestedReason }),
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
      void queueClip(
        {
          takeKey: mintKey("file"),
          clientClipKey: mintKey("clip"),
          blob: pending.file,
          mimeType: pending.file.type || "audio/mp4",
          extension: (pending.file.name.split(".").pop() ?? "m4a").toLowerCase(),
          durationMs: 0,
          deviceCapturedAt: pending.file.lastModified ? new Date(pending.file.lastModified).toISOString() : new Date().toISOString()
        },
        pending,
        reasons,
        acked,
        replaces,
        continuation,
        sha,
        pending.file.lastModified ? "FILE_METADATA" : "SESSION_DATE"
      );
      return;
    }
    setRecording({
      target: { label: pending.label, targets: pending.targets, roster, sessionKey: session.clientKey },
      pending,
      reasons,
      acked,
      replaces,
      continuation
    });
  }

  async function queueClip(
    take: FinishedTake,
    pending: Pending,
    reasons: Record<string, RepeatReason>,
    acked: string[],
    replaces: string | null,
    continuation: string | null,
    knownSha: string | null,
    source = "DEVICE"
  ) {
    const sha = knownSha ?? (await sha256Hex(take.blob));
    const offset = clockOffset();
    const step: ClipStep = {
      kind: "clip",
      status: "PENDING",
      clientClipKey: take.clientClipKey,
      blobKey: take.takeKey,
      label: pending.label,
      sizeBytes: take.blob.size,
      contentType: take.mimeType.split(";")[0] || "audio/webm",
      extension: take.extension,
      presign: { contentSha256: sha, targets: pending.targets, subjectArtisanIds: roster, deviceCapturedAt: take.deviceCapturedAt, clockOffsetMs: offset?.offsetMs ?? null },
      recording: {
        targets: pending.targets,
        subjects: roster.map((artisanId) => ({ artisanId, role: "ANSWERED", named: false, repeatReason: reasons[artisanId] ?? null })),
        deviceCapturedAt: take.deviceCapturedAt,
        clockOffsetMs: offset?.offsetMs ?? null,
        capturedAtSource: source,
        durationMs: take.durationMs || null,
        rosterCount: roster.length,
        contentSha256: sha,
        continuationOfId: continuation,
        replacesRecordingId: replaces,
        ackedRecordingIds: acked
      }
    };
    await enqueue({ id: session.id, clientKey: session.clientKey }, step, { key: take.takeKey, blob: take.blob });
    void recorderStore.discard(take.takeKey).catch(() => undefined);
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

  return (
    <div className="grid gap-4" data-session-workspace>
      <SessionHeader
        session={session}
        pendingTakes={pendingCount}
        onFinish={() => setFinishOpen(true)}
        onPresence={(artisanId, present) => {
          const at = new Date().toISOString();
          void enqueue({ id: session.id, clientKey: session.clientKey }, { kind: "participant", status: "PENDING", op: present ? "join" : "leave", artisanId, at, idempotencyKey: mintKey("part") });
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
      />

      {(queue.unit?.steps ?? [])
        .filter((s): s is ClipStep => s.kind === "clip" && s.error === "MISSING_FILE")
        .map((s) => (
          <p key={s.clientClipKey} role="status" className="panel border-error-600/30 p-3 text-sm text-error-600">
            {qs("missingOutboxFile", { target: s.label ?? qs("wholeSession"), date: shortDate(s.recording.deviceCapturedAt) })}
          </p>
        ))}

      {p13.length ? (
        <PromptDialog
          spec={prompts.p13({ minutes: recoveredMinutes(p13[0].draft, p13[0].chunks), target: p13[0].draft.targetLabel })}
          open
          onChoose={(choice) => {
            const [first, ...rest] = p13;
            setP13(rest);
            if (choice === "keep") {
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
                { targets: first.draft.targets, label: first.draft.targetLabel, code: "" },
                {},
                [],
                null,
                null,
                null
              );
            } else {
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
              void queueClip(take, r.pending, r.reasons, r.acked, r.replaces ?? null, r.continuation ?? null, null);
            }}
          />
        </div>
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
        {shownGrid.sections.length ? (
          <CoverageGrid
            grid={shownGrid}
            artisans={session.participants.map((p) => ({ id: p.artisanId, name: p.name }))}
            activeSectionId={activeSection}
            onOpen={(sectionId, artisanId) => setSheet({ sectionId, artisanId })}
          />
        ) : (
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => void begin({ targets: [], label: qs("wholeSession"), code: "" })}>
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
            <button
              type="button"
              className="field-button"
              onClick={() => void begin({ targets: [{ sectionId: activeGridSection.id }], label: `Section ${activeGridSection.code}`, code: activeGridSection.code })}
            >
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
                  </p>
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() => void begin({ targets: [{ questionLineageId: q.lineageId }], label: q.displayCode, code: q.displayCode })}
                  >
                    {qs("record")}
                  </button>
                </div>
                {typeAnswers ? (
                  <AnswerBox
                    session={session}
                    lineageId={q.lineageId}
                    code={q.displayCode}
                    participants={session.participants.filter((p) => !p.leftAt)}
                    answers={detail.answers.filter((a) => a.questionLineageId === q.lineageId)}
                    recordings={detail.recordings}
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
            void begin({ targets: [{ sectionId: sheetSection.id }], label: `Section ${sheetSection.code}`, code: sheetSection.code });
          }}
          onUpload={(file) => {
            setSheet(null);
            void begin({ targets: [{ sectionId: sheetSection.id }], label: `Section ${sheetSection.code}`, code: sheetSection.code, file });
          }}
          onRemove={canDeleteRecords ? (r) => setRemoveTake(r) : undefined}
          onRecordConsent={(r) => {
            const subject = r.subjects.find((s) => s.role === "ANSWERED");
            if (subject) setConsentFor({ artisanId: subject.artisanId, name: names[subject.artisanId] ?? "" });
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

      {p5 ? <PromptDialog spec={p5.spec} open onChoose={() => setP5(null)} /> : null}

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
          spec={prompts.finish({ sections: session.counts.sections, artisans: session.participantCount, pending: pendingCount })}
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
          spec={prompts.leave({ count: pendingCount })}
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
