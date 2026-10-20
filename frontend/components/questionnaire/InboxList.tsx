"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { EmptyState } from "@/components/EmptyState";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { listResource } from "@/lib/api";
import { questionnaireApi } from "@/lib/questionnaireApi";
import {
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

/** PATCH needs the row's version; the session detail carries it for the session and its takes. */
async function recordingVersion(sessionId: string | null, recordingId: string): Promise<number | null> {
  if (!sessionId) return null;
  const detail = await questionnaireApi.getSession(sessionId);
  return detail.recordings.find((r) => r.id === recordingId)?.version ?? null;
}

/**
 * The inbox (DESIGN §4.7): every row carries its own action, in place. "Keep" on an empty session
 * and "OK" on a primary change only acknowledge, so they are remembered on this device.
 */
export function InboxList() {
  const [raw, setRaw] = useState<Inbox | null>(null);
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set());
  const [error, setError] = useState<string | null>(null);
  const [rowError, setRowError] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

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

  async function run(key: string, work: () => Promise<unknown>) {
    setBusy(key);
    setRowError((e) => ({ ...e, [key]: "" }));
    try {
      await work();
      await load();
    } catch (err) {
      setRowError((e) => ({ ...e, [key]: err instanceof Error ? err.message : String(err) }));
    } finally {
      setBusy(null);
    }
  }

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
              return (
                <li key={key} className="grid gap-2 rounded-md border border-line-200 p-3" data-inbox-row={key}>
                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    {sessionId ? (
                      <Link className="text-sm text-purple-700 underline" href={sessionHref(sessionId)}>
                        {inboxLabel(section, row)}
                      </Link>
                    ) : (
                      <span className="text-sm text-ink-900">{inboxLabel(section, row)}</span>
                    )}
                    <RowActions section={section} row={row} rowKey={key} busy={busy === key} run={run} dismiss={dismiss} />
                  </div>
                  {section === "legacy" ? <LegacyPick row={row as unknown as InboxLegacy} busy={busy === key} onConfirm={(lineage) => void run(key, () => confirmLegacy(row as unknown as InboxLegacy, lineage))} /> : null}
                  {section === "setDates" ? <SetDates row={row as unknown as InboxSetDates} busy={busy === key} onSet={(date) => void run(key, () => setDates(row as unknown as InboxSetDates, date))} /> : null}
                  {section === "noParticipants" ? (
                    <WhoWasThere busy={busy === key} onSave={(ids) => void run(key, () => addArtisans((row as unknown as InboxNoParticipants).sessionId, ids))} />
                  ) : null}
                  {rowError[key] ? (
                    <p role="alert" className="text-xs text-error-600">
                      {rowError[key]}
                    </p>
                  ) : null}
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

function RowActions({
  section,
  row,
  rowKey,
  busy,
  run,
  dismiss
}: {
  section: InboxSectionKey;
  row: Record<string, unknown>;
  rowKey: string;
  busy: boolean;
  run: (key: string, work: () => Promise<unknown>) => Promise<void>;
  dismiss: (key: string) => void;
}) {
  const secondary = "field-button-secondary";
  const primary = "field-button";
  // Deleting a session is a content admin's act, in admin view (owner's ruling, 2026-10-20).
  const { adminMode } = useAdminView();
  switch (section) {
    case "toFile": {
      const r = row as unknown as InboxToFile;
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button type="button" className={secondary} disabled={busy} onClick={() => dismiss(rowKey)}>
            {qs("notNeeded")}
          </button>
          {r.sessionId ? (
            <Link className={primary} href={sessionHref(r.sessionId)}>
              {qs("fileIt")}
            </Link>
          ) : null}
        </ButtonRow>
      );
    }
    case "needsDecision": {
      const r = row as unknown as InboxDecision;
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button type="button" className={secondary} disabled={busy} onClick={() => void run(rowKey, () => questionnaireApi.decide(r.recordingId, { action: "KEEP_BOTH" }))}>
            {qs("keepBoth")}
          </button>
          {r.sessionId ? (
            <Link className={primary} href={sessionHref(r.sessionId)}>
              {qs("keepOne")}
            </Link>
          ) : null}
        </ButtonRow>
      );
    }
    case "emptySessions": {
      const r = row as unknown as InboxEmptySession;
      return (
        <ButtonRow size="sm" align="end" className="max-sm:w-full">
          <button type="button" className={secondary} disabled={busy} onClick={() => dismiss(rowKey)}>
            {qs("keep")}
          </button>
          {adminMode ? (
            <button type="button" className={`${secondary} field-danger`} disabled={busy} onClick={() => void run(rowKey, () => questionnaireApi.deleteSession(r.sessionId))}>
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
      <ButtonRow size="sm">
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
