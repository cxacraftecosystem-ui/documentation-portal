"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { loadInstrumentQuestions } from "@/components/questionnaire/instrumentQuestions";
import { takeRow } from "@/components/questionnaire/TakeSheet";
import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { ApiError, listResource } from "@/lib/api";
import { questionnaireApi, type Recording, type Session, type SessionDetail } from "@/lib/questionnaireApi";
import {
  decisionLabel,
  decisionPartner,
  fileItBody,
  fileItDefaults,
  INBOX_ORDER,
  inboxLabel,
  inboxRowKey,
  readDismissed,
  visibleInbox,
  writeDismissed,
  type Inbox,
  type InboxDecision,
  type InboxEmptySession,
  type InboxLegacy,
  type InboxNoParticipants,
  type InboxSectionKey,
  type InboxSetDates,
  type InboxToFile
} from "@/lib/questionnaireInbox";
import { qs, type QsKey } from "@/lib/strings/qs";
import type { Artisan } from "@/lib/types";

export const INBOX_TITLES: Record<InboxSectionKey, QsKey> = {
  toFile: "inboxToFile",
  needsDecision: "inboxNeedsDecision",
  legacy: "inboxLegacy",
  setDates: "inboxSetDates",
  noParticipants: "inboxWhoWasThere",
  emptySessions: "inboxEmptySession",
  primaryChanged: "inboxPrimaryChanged"
};

const sessionHref = (id: string) => `/questionnaire/sessions/${encodeURIComponent(id)}`;
type Run = (key: string, work: () => Promise<unknown>) => Promise<void>;

/** PATCH needs the row's version; the session detail carries it for the session and its takes. */
async function recordingVersion(sessionId: string | null, recordingId: string): Promise<number | null> {
  if (!sessionId) return null;
  const detail = await questionnaireApi.getSession(sessionId);
  return detail.recordings.find((r) => r.id === recordingId)?.version ?? null;
}

/**
 * The inbox (DESIGN §4.7): every row carries its own action, in place. A decision is made here —
 * Keep both, Keep one, Part 2 — and a clip is filed here, never by sending the reader to the session
 * (audit H13, H14). "Keep" on an empty session and "OK" on a primary change only acknowledge, so
 * they are remembered on this device; "Not needed" on a clip is stored on the server.
 */
export function InboxList() {
  const [raw, setRaw] = useState<Inbox | null>(null);
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set());
  const [error, setError] = useState<string | null>(null);
  const [rowError, setRowError] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [filing, setFiling] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRaw(await questionnaireApi.inbox());
    } catch {
      setError(qs("listLoadError"));
    }
  }, []);
  useEffect(() => {
    setDismissed(readDismissed());
    void load();
  }, [load]);

  const inbox = useMemo(() => (raw ? visibleInbox(raw, dismissed) : null), [raw, dismissed]);

  const run: Run = async (key, work) => {
    setBusy(key);
    setRowError((e) => ({ ...e, [key]: "" }));
    try {
      await work();
      setFiling(null);
      await load();
    } catch (err) {
      setRowError((e) => ({ ...e, [key]: err instanceof Error ? err.message : String(err) }));
    } finally {
      setBusy(null);
    }
  };

  function dismiss(key: string) {
    const next = new Set(dismissed);
    next.add(key);
    writeDismissed(next);
    setDismissed(next);
  }

  if (error)
    return (
      <div className="panel grid gap-3 p-6 text-center">
        <p className="text-sm text-ink-700">{error}</p>
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => void load()}>
            {qs("tryAgain")}
          </button>
        </ButtonRow>
      </div>
    );
  if (!inbox) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
  if (inbox.counts.total === 0) return <EmptyState title={qs("inboxEmpty")} />;

  return (
    <div className="grid gap-4">
      {INBOX_ORDER.filter((k) => inbox[k].length > 0).map((section) => (
        <section key={section} className="panel grid gap-2 p-4" data-inbox-section={section}>
          <h2 className="display-title text-base">
            {qs(INBOX_TITLES[section])} <span className="text-ink-500">({inbox[section].length})</span>
          </h2>
          <ul className="grid gap-2">
            {(inbox[section] as Record<string, unknown>[]).map((row) => {
              const key = inboxRowKey(section, row);
              const sessionId = typeof row.sessionId === "string" ? row.sessionId : null;
              const failure = rowError[key] ? (
                <p role="alert" className="text-xs text-error-600">
                  {rowError[key]}
                </p>
              ) : null;
              if (section === "needsDecision") {
                return (
                  <li key={key} className="grid gap-2 rounded-md border border-line-200 p-3" data-inbox-row={key}>
                    <DecisionRow row={row as unknown as InboxDecision} busy={busy === key} run={(work) => run(key, work)} />
                    {failure}
                  </li>
                );
              }
              return (
                <li key={key} className="grid gap-2 rounded-md border border-line-200 p-3" data-inbox-row={key}>
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    {sessionId && section !== "toFile" ? (
                      <Link className="text-sm text-purple-700 underline" href={sessionHref(sessionId)}>
                        {inboxLabel(section, row)}
                      </Link>
                    ) : (
                      <span className="text-sm text-ink-900">{inboxLabel(section, row)}</span>
                    )}
                    <RowActions
                      section={section}
                      row={row}
                      rowKey={key}
                      busy={busy === key}
                      run={run}
                      dismiss={dismiss}
                      filing={filing === key}
                      onFile={() => setFiling(filing === key ? null : key)}
                    />
                  </div>
                  {section === "toFile" && filing === key ? (
                    <FileItPicker
                      row={row as unknown as InboxToFile}
                      busy={busy === key}
                      onFile={(body) => void run(key, () => questionnaireApi.fileClip((row as unknown as InboxToFile).mediaId, body))}
                    />
                  ) : null}
                  {section === "legacy" ? <LegacyPick row={row as unknown as InboxLegacy} busy={busy === key} onConfirm={(lineage) => void run(key, () => confirmLegacy(row as unknown as InboxLegacy, lineage))} /> : null}
                  {section === "setDates" ? <SetDates row={row as unknown as InboxSetDates} busy={busy === key} onSet={(date) => void run(key, () => setDates(row as unknown as InboxSetDates, date))} /> : null}
                  {section === "noParticipants" ? (
                    <WhoWasThere busy={busy === key} onSave={(ids) => void run(key, () => addArtisans((row as unknown as InboxNoParticipants).sessionId, ids))} />
                  ) : null}
                  {failure}
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}

async function confirmLegacy(row: InboxLegacy, lineageId: string) {
  const version = await recordingVersion(row.sessionId, row.recordingId);
  if (version === null) throw new Error(qs("sessionLoadError"));
  await questionnaireApi.patchRecording(row.recordingId, { version, targets: [{ questionLineageId: lineageId }] });
  // Filing the take settles the decision: KEEP_BOTH with no other take files just this one.
  await questionnaireApi.decide(row.recordingId, { action: "KEEP_BOTH" });
}

async function setDates(row: InboxSetDates, date: string) {
  for (const id of row.sessionIds) {
    const detail = await questionnaireApi.getSession(id);
    await questionnaireApi.patchSession(id, { version: detail.version, sessionDate: date });
  }
}

async function addArtisans(sessionId: string, ids: string[]) {
  for (const artisanId of ids) await questionnaireApi.addParticipant(sessionId, artisanId);
}

/** "10:42 · Nandini · 14 min": how the two takes of a decision are told apart. */
function takeChoiceLabel(recording: Recording): string {
  const at = new Date(recording.capturedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" });
  return [at, takeRow(recording, null)].filter(Boolean).join(" · ");
}

/**
 * One "Needs a decision" row, decided in place (DESIGN §3.10, §4.7; audit H13): Keep both, Keep one…
 * (pick the take that stays; the other is removed, restorably), and Part 2 under More. The other
 * take is the one the server names, else found in the session.
 */
function DecisionRow({ row, busy, run }: { row: InboxDecision; busy: boolean; run: (work: () => Promise<unknown>) => Promise<void> }) {
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [choosing, setChoosing] = useState(false);
  const [more, setMore] = useState(false);
  const [kept, setKept] = useState<string>("");

  useEffect(() => {
    let cancelled = false;
    if (!row.sessionId) return;
    void questionnaireApi
      .getSession(row.sessionId)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [row.sessionId]);

  const self = detail?.recordings.find((r) => r.id === row.recordingId) ?? null;
  const other = detail ? decisionPartner(row, detail.recordings) : null;
  const label = self && other ? decisionLabel(row, self, other) : inboxLabel("needsDecision", row);
  const [earlier, later] = self && other ? [self, other].sort((a, b) => a.capturedAt.localeCompare(b.capturedAt)) : [null, null];

  return (
    <div className="grid gap-2" data-decision-row={row.recordingId}>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <span className="text-sm text-ink-900">{label}</span>
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button
            type="button"
            className="field-button-secondary"
            disabled={busy || (!other && !loadFailed && Boolean(row.sessionId))}
            onClick={() => void run(() => questionnaireApi.decide(row.recordingId, other ? { action: "KEEP_BOTH", otherRecordingId: other.id } : { action: "KEEP_BOTH" }))}
          >
            {qs("keepBoth")}
          </button>
          {other ? (
            <button
              type="button"
              className="field-button"
              aria-expanded={choosing}
              disabled={busy}
              onClick={() => {
                setChoosing(!choosing);
                setKept("");
              }}
            >
              {qs("keepOne")}
            </button>
          ) : null}
        </ButtonRow>
      </div>

      {self && other ? (
        <ul className="grid gap-2">
          {[self, other]
            .sort((a, b) => a.capturedAt.localeCompare(b.capturedAt))
            .map((r) => (
              <li key={r.id} className="grid gap-1 rounded-md bg-surface-50 p-2">
                <p className="text-xs text-ink-700">{takeChoiceLabel(r)}</p>
                <AudioPlayer src={questionnaireApi.downloadHref(r.id)} />
              </li>
            ))}
        </ul>
      ) : null}

      {choosing && self && other ? (
        <div className="grid gap-2" data-keep-one>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={qs("keepOne")}>
            {[earlier!, later!].map((r) => (
              <button
                key={r.id}
                type="button"
                role="radio"
                aria-checked={kept === r.id}
                className={`rounded-full border px-3 py-1 text-sm ${kept === r.id ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700"}`}
                onClick={() => setKept(r.id)}
              >
                {takeChoiceLabel(r)}
              </button>
            ))}
          </div>
          <ButtonRow size="sm" fill>
            <button
              type="button"
              className="field-button"
              disabled={!kept || busy}
              onClick={() => {
                const gone = kept === self.id ? other.id : self.id;
                void run(() => questionnaireApi.decide(kept, { action: "KEEP_THIS", otherRecordingId: gone }));
              }}
            >
              {qs("keep")}
            </button>
          </ButtonRow>
        </div>
      ) : null}

      {earlier && later ? (
        <div className="grid gap-2">
          <button type="button" className="justify-self-start text-xs font-semibold text-purple-700 dark:text-purple-300" aria-expanded={more} onClick={() => setMore(!more)}>
            {qs("moreOptions")}
          </button>
          {more ? (
            <ButtonRow size="sm" align="start">
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy}
                onClick={() => void run(() => questionnaireApi.decide(later.id, { action: "CONTINUATION", otherRecordingId: earlier.id }))}
              >
                {qs("continueAsPart2")}
              </button>
            </ButtonRow>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/**
 * "File it" (DESIGN §4.7, audit H14): session, target and subjects, with the candidate preselected.
 * Sessions are searched on the server; the target list is the chosen session's instrument.
 */
function FileItPicker({ row, busy, onFile }: { row: InboxToFile; busy: boolean; onFile: (body: ReturnType<typeof fileItBody>) => void }) {
  const start = fileItDefaults(row);
  const [sessionId, setSessionId] = useState(start.sessionId);
  const [targetKey, setTargetKey] = useState(start.targetKey);
  const [subjects, setSubjects] = useState<string[]>(start.subjectArtisanIds);
  const [search, setSearch] = useState("");
  const [sessions, setSessions] = useState<Session[]>([]);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [targets, setTargets] = useState<{ value: string; label: string }[]>([]);

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void questionnaireApi
        .listSessions({ search: search || undefined })
        .then((page) => {
          if (!cancelled) setSessions(page.items);
        })
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search]);

  useEffect(() => {
    let cancelled = false;
    if (!sessionId) {
      setDetail(null);
      setTargets([]);
      return;
    }
    void questionnaireApi
      .getSession(sessionId)
      .then(async (d) => {
        if (cancelled) return;
        setDetail(d);
        // Everyone in the sitting answered unless the candidate says otherwise.
        setSubjects((current) => (current.length ? current : d.participants.filter((p) => !p.leftAt).map((p) => p.artisanId)));
        const q = await loadInstrumentQuestions(d.questionnaire.id);
        if (cancelled) return;
        setTargets(
          q.sections.flatMap((s) => [
            { value: `S:${s.id}`, label: `Section ${s.code} · ${s.title}` },
            ...(q.byId[s.id] ?? []).map((x) => ({ value: `Q:${x.lineageId}`, label: `${x.displayCode} ${x.prompt}` }))
          ])
        );
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  const sessionOptions = [
    ...(detail && !sessions.some((s) => s.id === detail.id) ? [detail] : []),
    ...sessions
  ].map((s) => ({ value: s.id, label: s.title, hint: s.participants.map((p) => p.name).join(", ") }));
  const subjectOptions = (detail?.participants ?? []).map((p) => ({ value: p.artisanId, label: p.name }));

  return (
    <div className="grid gap-3 rounded-md bg-surface-50 p-3" data-file-it>
      <div className="grid gap-1">
        <span className="field-label">{qs("sessionsTitle")}</span>
        <SearchableSelect
          value={sessionId}
          onChange={(v) => {
            setSessionId(v);
            setTargetKey("");
            setSubjects([]);
          }}
          onSearch={setSearch}
          options={sessionOptions}
          searchable
          advanceOnSelect={false}
          ariaLabel={qs("sessionsTitle")}
        />
      </div>
      <div className="grid gap-1">
        <span className="field-label">{qs("fileItTarget")}</span>
        <SearchableSelect value={targetKey} onChange={setTargetKey} options={targets} searchable advanceOnSelect={false} ariaLabel={qs("fileItTarget")} />
      </div>
      <div className="grid gap-1">
        <span className="field-label">{qs("answeredBy")}</span>
        <SearchableMultiSelect values={subjects} onChange={setSubjects} options={subjectOptions} ariaLabel={qs("answeredBy")} />
      </div>
      <ButtonRow size="sm" fill>
        <button
          type="button"
          className="field-button"
          disabled={busy || !sessionId || !targetKey}
          onClick={() => onFile(fileItBody({ sessionId, targetKey, subjectArtisanIds: subjects }))}
        >
          {qs("fileIt")}
        </button>
      </ButtonRow>
    </div>
  );
}

function RowActions({
  section,
  row,
  rowKey,
  busy,
  run,
  dismiss,
  filing,
  onFile
}: {
  section: InboxSectionKey;
  row: Record<string, unknown>;
  rowKey: string;
  busy: boolean;
  run: Run;
  dismiss: (key: string) => void;
  filing: boolean;
  onFile: () => void;
}) {
  const secondary = "field-button-secondary";
  const primary = "field-button";
  // Deleting a session is a content admin's act, admin view or not (owner, 2026-10-21).
  const { canDeleteRecords } = useAdminView();
  const confirm = useConfirm();
  switch (section) {
    case "toFile": {
      const r = row as unknown as InboxToFile;
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button
            type="button"
            className={secondary}
            disabled={busy}
            onClick={() =>
              void run(rowKey, () =>
                questionnaireApi.dismissClip(r.mediaId).catch((error: unknown) => {
                  // A server without the route yet: remember it on this device, as before.
                  if (error instanceof ApiError && (error.status === 404 || error.status === 405)) dismiss(rowKey);
                  else throw error;
                })
              )
            }
          >
            {qs("notNeeded")}
          </button>
          <button type="button" className={primary} aria-expanded={filing} disabled={busy} onClick={onFile}>
            {qs("fileIt")}
          </button>
        </ButtonRow>
      );
    }
    case "emptySessions": {
      const r = row as unknown as InboxEmptySession;
      const removeEmptySession = async () => {
        if (!(await confirm(deleteConfirm("Delete this empty session?", "This can’t be undone.")))) return;
        await run(rowKey, () => questionnaireApi.deleteSession(r.sessionId));
      };
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button type="button" className={secondary} disabled={busy} onClick={() => dismiss(rowKey)}>
            {qs("keep")}
          </button>
          {canDeleteRecords ? (
            <button type="button" className={`${secondary} field-danger`} disabled={busy} onClick={() => void removeEmptySession()}>
              {qs("delete")}
            </button>
          ) : null}
        </ButtonRow>
      );
    }
    case "primaryChanged":
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button type="button" className={primary} onClick={() => dismiss(rowKey)}>
            {qs("ok")}
          </button>
        </ButtonRow>
      );
    default:
      return null;
  }
}

function LegacyPick({ row, busy, onConfirm }: { row: InboxLegacy; busy: boolean; onConfirm: (lineageId: string) => void }) {
  const [pick, setPick] = useState(row.candidates.length === 1 ? row.candidates[0].questionLineageId : "");
  if (!row.candidates.length) return null;
  return (
    <div className="grid gap-2">
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={qs("inboxLegacy")}>
        {row.candidates.map((c) => (
          <button
            key={c.questionLineageId}
            type="button"
            role="radio"
            aria-checked={pick === c.questionLineageId}
            className={`rounded-full border px-3 py-1 text-sm ${pick === c.questionLineageId ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card text-ink-700"}`}
            onClick={() => setPick(c.questionLineageId)}
          >
            {c.prompt || c.questionLineageId}
          </button>
        ))}
      </div>
      <ButtonRow size="sm" fill>
        <button type="button" className="field-button" disabled={!pick || busy} onClick={() => onConfirm(pick)}>
          {qs("confirm")}
        </button>
      </ButtonRow>
    </div>
  );
}

function SetDates({ row, busy, onSet }: { row: InboxSetDates; busy: boolean; onSet: (date: string) => void }) {
  const [date, setDate] = useState(row.from ?? "");
  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
      <CalendarField className="sm:max-w-xs" value={date} onChange={setDate} ariaLabel={qs("fieldDate")} required />
      <ButtonRow size="sm" align="end">
        <button type="button" className="field-button" disabled={!date || busy} onClick={() => onSet(date)}>
          {qs("setDate")}
        </button>
      </ButtonRow>
    </div>
  );
}

function WhoWasThere({ busy, onSave }: { busy: boolean; onSave: (ids: string[]) => void }) {
  const [ids, setIds] = useState<string[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  useEffect(() => {
    let cancelled = false;
    void listResource<Artisan>("/artisans", { pageSize: 200 })
      .then((page) => {
        if (!cancelled) setArtisans(page.items ?? []);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  const options = artisans.map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }));
  return (
    <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
      <div className="min-w-0 flex-1">
        <SearchableMultiSelect values={ids} onChange={setIds} options={options} searchable ariaLabel={qs("inboxPickArtisans")} />
      </div>
      <ButtonRow size="sm" align="end">
        <button type="button" className="field-button" disabled={!ids.length || busy} onClick={() => onSave(ids)}>
          {qs("save")}
        </button>
      </ButtonRow>
    </div>
  );
}
