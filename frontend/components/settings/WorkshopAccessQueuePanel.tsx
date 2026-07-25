"use client";

import { Check, Inbox, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { Field } from "@/components/FormControls";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  AccessStatusBadge,
  levelOptions,
  personName,
  useWorkshopAccessLevels,
  workshopLabel,
  type WorkshopAccessLevel,
  type WorkshopAccessRow
} from "@/components/settings/workshopAccess";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";

/**
 * The cross-workshop approval queue (`GET /workshops/access-requests`, admin only).
 *
 * Deliberately ONE list rather than a tab per workshop: opening each workshop's roster in turn is how
 * requests sit unanswered for a week. Approving lets the admin change the level being handed out —
 * somebody who asked for EDIT can be given VIEW — and both decisions take an optional note, which is
 * the only explanation the requester ever sees.
 */

const FILTERS = [
  { value: "PENDING", label: "Waiting for a decision" },
  { value: "ALL", label: "All requests (history)" }
];

export function WorkshopAccessQueuePanel({ refreshToken, onChanged }: { refreshToken: number; onChanged: () => void }) {
  const { toast } = useToast();
  const levels = useWorkshopAccessLevels();

  const [statusFilter, setStatusFilter] = useState("PENDING");
  const [rows, setRows] = useState<WorkshopAccessRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  /** Per-row decision draft: the level to grant and the note to send with it. */
  const [drafts, setDrafts] = useState<Record<string, { level: WorkshopAccessLevel; note: string }>>({});

  const load = useCallback(async () => {
    setError(null);
    try {
      setRows(await apiFetch<WorkshopAccessRow[]>(`/workshops/access-requests?statusFilter=${statusFilter}`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load the request queue");
      setRows([]);
    }
  }, [statusFilter]);

  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  function draftFor(row: WorkshopAccessRow) {
    return drafts[row.id] ?? { level: row.accessLevel, note: "" };
  }

  function setDraft(row: WorkshopAccessRow, patch: Partial<{ level: WorkshopAccessLevel; note: string }>) {
    setDrafts((current) => ({ ...current, [row.id]: { ...draftFor(row), ...patch } }));
  }

  async function decide(row: WorkshopAccessRow, decision: "GRANTED" | "DENIED") {
    const draft = draftFor(row);
    setBusy(row.id);
    setError(null);
    try {
      await apiFetch<WorkshopAccessRow>(`/workshops/access-requests/${row.id}/decide`, {
        method: "POST",
        body: JSON.stringify({
          status: decision,
          accessLevel: decision === "GRANTED" ? draft.level : null,
          note: draft.note.trim() || null
        })
      });
      toast({
        title: decision === "GRANTED" ? "Access granted" : "Request denied",
        description: `${personName(row.user)} — ${workshopLabel(row.workshop)}`,
        tone: decision === "GRANTED" ? "success" : "info"
      });
      setDrafts((current) => {
        const next = { ...current };
        delete next[row.id];
        return next;
      });
      // Bumping the shared token is what re-reads BOTH panels — this one included, via its effect —
      // so a decision made here never leaves the roster below showing the pre-decision state.
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to record that decision");
    } finally {
      setBusy(null);
    }
  }

  const pendingCount = rows?.filter((row) => row.status === "PENDING").length ?? 0;

  return (
    <section className="panel p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
            <Inbox className="h-4 w-4" aria-hidden />
          </span>
          <div>
            <h2 className="font-display font-bold text-ink-900">Access requests</h2>
            <p className="text-xs leading-5 text-ink-500">
              Everything people have asked for, across every workshop.
              {statusFilter === "PENDING" && rows ? ` ${pendingCount} waiting.` : ""}
            </p>
          </div>
        </div>
        <div className="w-full sm:w-64">
          {/* Filters the list in place, so focus must stay on the control being adjusted. */}
          <Dropdown
            advanceOnSelect={false}
            ariaLabel="Filter requests"
            onChange={setStatusFilter}
            options={FILTERS}
            value={statusFilter}
          />
        </div>
      </div>

      {error ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}

      {!rows ? (
        <p className="mt-4 text-sm text-ink-500">Loading…</p>
      ) : rows.length === 0 ? (
        <div className="mt-4">
          <EmptyState
            title={statusFilter === "PENDING" ? "Nothing waiting" : "No requests yet"}
            body={
              statusFilter === "PENDING"
                ? "Every workshop-access request has been answered."
                : "Nobody has asked for access to a workshop."
            }
          />
        </div>
      ) : (
        <ul className="mt-4 grid gap-3">
          {rows.map((row) => {
            const draft = draftFor(row);
            const decidable = row.status === "PENDING";
            return (
              <li className="rounded-md border border-line-200 bg-surface-50 p-4" key={row.id}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-medium text-ink-900">{personName(row.user)}</div>
                    <div className="text-xs text-ink-500">
                      {row.user?.email}
                      {row.user?.role ? ` · ${roleLabel(row.user.role)}` : ""}
                    </div>
                    <div className="mt-1 text-sm text-ink-700">{workshopLabel(row.workshop)}</div>
                    <div className="text-xs text-ink-500">
                      Asked {formatDateTime(row.createdAt)} for {draft.level.toLowerCase()} access
                    </div>
                  </div>
                  <AccessStatusBadge status={row.status} />
                </div>

                {row.requestNote ? (
                  <p className="mt-2 border-l-2 border-purple-300 pl-3 text-xs italic leading-5 text-ink-700">
                    “{row.requestNote}”
                  </p>
                ) : null}

                {decidable ? (
                  <div className="mt-3 grid gap-3 md:grid-cols-[minmax(0,12rem)_minmax(0,1fr)]">
                    <Field label="Grant at level">
                      <Dropdown
                        ariaLabel={`Access level for ${personName(row.user)}`}
                        onChange={(next) => setDraft(row, { level: next as WorkshopAccessLevel })}
                        options={levelOptions(levels)}
                        value={draft.level}
                      />
                    </Field>
                    <Field label="Note back to them (optional)">
                      <input
                        className="field-input"
                        onChange={(event) => setDraft(row, { note: event.target.value })}
                        placeholder="Why you approved or declined"
                        type="text"
                        value={draft.note}
                      />
                    </Field>
                  </div>
                ) : (
                  <p className="mt-2 text-xs text-ink-500">
                    Decided {formatDateTime(row.decidedAt ?? row.updatedAt)}
                    {row.decidedBy ? ` by ${personName(row.decidedBy)}` : ""}
                    {row.decisionNote ? ` — “${row.decisionNote}”` : "."}
                  </p>
                )}

                {decidable ? (
                  <div className="mt-3">
                    <RowActions>
                      <button
                        className={rowAction("danger")}
                        disabled={busy === row.id}
                        onClick={() => decide(row, "DENIED")}
                        type="button"
                      >
                        <X className="h-3.5 w-3.5" aria-hidden />
                        Deny
                      </button>
                      <button
                        className={rowAction("positive")}
                        disabled={busy === row.id}
                        onClick={() => decide(row, "GRANTED")}
                        type="button"
                      >
                        <Check className="h-3.5 w-3.5" aria-hidden />
                        {busy === row.id ? "Saving…" : "Approve"}
                      </button>
                    </RowActions>
                  </div>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
