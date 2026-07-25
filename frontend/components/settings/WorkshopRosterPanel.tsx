"use client";

import { UserPlus, Users } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import { EmptyState } from "@/components/EmptyState";
import { Field } from "@/components/FormControls";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  AccessLevelLegend,
  AccessStatusBadge,
  DEFAULT_ACCESS_LEVEL,
  levelOptions,
  personName,
  sortWorkshopsByOccurrence,
  useWorkshopAccessLevels,
  workshopLabel,
  type WorkshopAccessLevel,
  type WorkshopAccessRow
} from "@/components/settings/workshopAccess";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { apiFetch, listResource } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/permissions";
import type { User, Workshop } from "@/lib/types";

/**
 * One workshop's roster: everybody with a row on it, at what level, in what state.
 *
 * Two things about the backend shape drive this table and are easy to get wrong:
 *
 * - the list is NOT "the assigned researchers". Every row on the workshop comes back — pending
 *   requests, denials and revocations included — and only `status === "GRANTED"` confers access.
 * - revoking does not delete. The row moves to REVOKED and stays, because a deleted row is
 *   indistinguishable from "never asked" and the history of who granted or withdrew access is the
 *   whole audit trail. A revoked person can be put straight back with Grant again.
 */

type DirectoryUser = Pick<User, "id" | "name" | "email" | "role">;

export function WorkshopRosterPanel({ refreshToken, onChanged }: { refreshToken: number; onChanged: () => void }) {
  const { toast } = useToast();
  const levels = useWorkshopAccessLevels();

  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [workshopId, setWorkshopId] = useState("");
  const [roster, setRoster] = useState<WorkshopAccessRow[] | null>(null);
  const [directory, setDirectory] = useState<DirectoryUser[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [confirmRevoke, setConfirmRevoke] = useState<string | null>(null);

  const [grantUserId, setGrantUserId] = useState("");
  const [grantLevel, setGrantLevel] = useState<WorkshopAccessLevel>(DEFAULT_ACCESS_LEVEL);
  const [grantNote, setGrantNote] = useState("");

  useEffect(() => {
    listResource<Workshop>("/workshops", { pageSize: 100 })
      .then((result) => {
        const sorted = sortWorkshopsByOccurrence(result.items);
        setWorkshops(sorted);
        setWorkshopId((current) => current || sorted[0]?.id || "");
      })
      .catch(() => setWorkshops([]));
    apiFetch<DirectoryUser[]>("/users/directory")
      .then(setDirectory)
      .catch(() => setDirectory([]));
  }, []);

  const loadRoster = useCallback(async () => {
    if (!workshopId) {
      setRoster(null);
      return;
    }
    setError(null);
    try {
      setRoster(await apiFetch<WorkshopAccessRow[]>(`/workshops/${workshopId}/assignments`));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load this workshop's roster");
      setRoster([]);
    }
  }, [workshopId]);

  useEffect(() => {
    void loadRoster();
  }, [loadRoster, refreshToken]);

  const workshopOptions = useMemo(
    () => workshops.map((workshop) => ({ value: workshop.id, label: workshopLabel(workshop) })),
    [workshops]
  );

  // Anybody already GRANTED belongs in the table, where their level is changed — offering them in the
  // "add somebody" picker too would give two different controls for one piece of state.
  const grantableUsers = useMemo(() => {
    const granted = new Set((roster ?? []).filter((row) => row.status === "GRANTED").map((row) => row.userId));
    return directory
      .filter((person) => !granted.has(person.id))
      .map((person) => ({ value: person.id, label: `${personName(person)} · ${roleLabel(person.role)}` }));
  }, [directory, roster]);

  const curated = (roster ?? []).some((row) => row.status === "GRANTED");

  async function grant(event: React.FormEvent) {
    event.preventDefault();
    if (!grantUserId || !workshopId) return;
    setBusy(`grant:${grantUserId}`);
    setError(null);
    try {
      await apiFetch<WorkshopAccessRow>(`/workshops/${workshopId}/assignments`, {
        method: "POST",
        body: JSON.stringify({ userId: grantUserId, accessLevel: grantLevel, note: grantNote.trim() || null })
      });
      toast({ title: "Access granted", description: "They can work in this workshop from now on.", tone: "success" });
      setGrantUserId("");
      setGrantNote("");
      onChanged(); // bumps the shared token; both panels re-read through their effects
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to grant access");
    } finally {
      setBusy(null);
    }
  }

  async function changeLevel(row: WorkshopAccessRow, level: WorkshopAccessLevel) {
    setBusy(row.id);
    setError(null);
    try {
      await apiFetch<WorkshopAccessRow>(`/workshops/${workshopId}/assignments/${row.userId}`, {
        method: "PATCH",
        body: JSON.stringify({ accessLevel: level })
      });
      onChanged(); // bumps the shared token; both panels re-read through their effects
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to change that level");
    } finally {
      setBusy(null);
    }
  }

  async function revoke(row: WorkshopAccessRow) {
    setBusy(row.id);
    setError(null);
    try {
      await apiFetch<WorkshopAccessRow>(`/workshops/${workshopId}/assignments/${row.userId}`, { method: "DELETE" });
      toast({
        title: "Access revoked",
        description: `${personName(row.user)} can no longer work in this workshop. The row is kept as a record.`,
        tone: "info"
      });
      setConfirmRevoke(null);
      onChanged(); // bumps the shared token; both panels re-read through their effects
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to revoke that access");
    } finally {
      setBusy(null);
    }
  }

  /**
   * Turn a non-GRANTED row back on. The two cases are deliberately different calls:
   *
   * - PENDING is somebody's own request, so it is ANSWERED with PATCH — which records `decidedBy` but
   *   leaves `assignedById` alone. Answering one request must not silently curate an open workshop
   *   and 403 the team already working in it.
   * - DENIED/REVOKED is reopened with POST, the act of an admin deliberately putting them back on the
   *   roster; that one is supposed to mark the workshop as curated.
   */
  async function restore(row: WorkshopAccessRow) {
    setBusy(row.id);
    setError(null);
    try {
      if (row.status === "PENDING") {
        await apiFetch<WorkshopAccessRow>(`/workshops/${workshopId}/assignments/${row.userId}`, {
          method: "PATCH",
          body: JSON.stringify({ status: "GRANTED", accessLevel: row.accessLevel })
        });
      } else {
        await apiFetch<WorkshopAccessRow>(`/workshops/${workshopId}/assignments`, {
          method: "POST",
          body: JSON.stringify({ userId: row.userId, accessLevel: row.accessLevel })
        });
      }
      toast({
        title: row.status === "PENDING" ? "Request approved" : "Access restored",
        description: `${personName(row.user)} can work in this workshop.`,
        tone: "success"
      });
      onChanged(); // bumps the shared token; both panels re-read through their effects
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore that access");
    } finally {
      setBusy(null);
    }
  }

  function origin(row: WorkshopAccessRow): string {
    if (row.assignedBy) return `Put on the roster by ${personName(row.assignedBy)}`;
    if (row.requestedBy) return "Asked for it themselves";
    return "Assigned";
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <Users className="h-4 w-4" aria-hidden />
        </span>
        <div>
          <h2 className="font-display font-bold text-ink-900">Workshop roster</h2>
          <p className="text-xs leading-5 text-ink-500">Who may work inside one workshop, and at what level.</p>
        </div>
      </div>

      <div className="mt-4 max-w-xl">
        <Field label="Workshop">
          <ComboBox
            onChange={(next) => {
              setWorkshopId(next);
              setConfirmRevoke(null);
              setGrantUserId("");
            }}
            options={workshopOptions}
            placeholder={workshops.length ? "Select or type to search" : "Loading workshops…"}
            value={workshopId}
          />
        </Field>
      </div>

      {error ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}

      {workshopId ? (
        <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
          {curated
            ? "This workshop is restricted: only the people granted below (and admins) may work in it."
            : "This workshop is open — anyone may work in it until somebody is put on the roster. Granting the first person restricts it to the roster."}
        </p>
      ) : null}

      <div className="mt-4">
        <AccessLevelLegend levels={levels} />
      </div>

      {!workshopId ? (
        <p className="mt-4 text-sm text-ink-500">Pick a workshop to see its roster.</p>
      ) : !roster ? (
        <p className="mt-4 text-sm text-ink-500">Loading…</p>
      ) : roster.length === 0 ? (
        <div className="mt-4">
          <EmptyState title="Nobody on this roster" body="No one has been granted access and nobody has asked. Add somebody below." />
        </div>
      ) : (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[860px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <ResizableTh>Person</ResizableTh>
                <ResizableTh>Level</ResizableTh>
                <ResizableTh>Status</ResizableTh>
                <ResizableTh>How they got here</ResizableTh>
                <ResizableTh className="text-right">Actions</ResizableTh>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {roster.map((row) => (
                <tr className="align-top" key={row.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-ink-900">{personName(row.user)}</div>
                    <div className="text-xs text-ink-500">
                      {row.user?.email}
                      {row.user?.role ? ` · ${roleLabel(row.user.role)}` : ""}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="w-40">
                      {/* Changes the row in place — focus must stay on the control being adjusted. */}
                      <Dropdown
                        advanceOnSelect={false}
                        ariaLabel={`Access level for ${personName(row.user)}`}
                        disabled={busy === row.id}
                        onChange={(next) => changeLevel(row, next as WorkshopAccessLevel)}
                        options={levelOptions(levels)}
                        value={row.accessLevel}
                      />
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <AccessStatusBadge status={row.status} />
                    {row.decidedAt ? (
                      <div className="mt-1 text-[0.6875rem] leading-4 text-ink-500">{formatDateTime(row.decidedAt)}</div>
                    ) : null}
                  </td>
                  <td className="px-4 py-3">
                    <div className="text-xs text-ink-700">{origin(row)}</div>
                    {row.requestNote ? (
                      <div className="mt-1 max-w-xs text-[0.6875rem] italic leading-4 text-ink-500">“{row.requestNote}”</div>
                    ) : null}
                    {row.decisionNote ? (
                      <div className="mt-1 max-w-xs text-[0.6875rem] leading-4 text-ink-500">{row.decisionNote}</div>
                    ) : null}
                  </td>
                  <td className="px-4 py-3">
                    <RowActions>
                      {row.status === "GRANTED" ? (
                        <button
                          className={rowAction("danger")}
                          disabled={busy === row.id}
                          onClick={() => setConfirmRevoke(row.id)}
                          type="button"
                        >
                          Revoke
                        </button>
                      ) : (
                        <button className={rowAction("edit")} disabled={busy === row.id} onClick={() => restore(row)} type="button">
                          {row.status === "PENDING" ? "Approve" : "Grant again"}
                        </button>
                      )}
                    </RowActions>
                    {confirmRevoke === row.id ? (
                      <div className="mt-2 rounded-md border border-red-200 bg-error-100 p-2 text-left">
                        <p className="text-xs text-ink-700">
                          Revoke {personName(row.user)}? They lose access to this workshop; the row is kept as a record.
                        </p>
                        <div className="mt-2 flex justify-end gap-2">
                          <button className={rowAction("neutral")} onClick={() => setConfirmRevoke(null)} type="button">
                            Cancel
                          </button>
                          <button
                            className={rowAction("danger")}
                            disabled={busy === row.id}
                            onClick={() => revoke(row)}
                            type="button"
                          >
                            {busy === row.id ? "Revoking…" : "Revoke"}
                          </button>
                        </div>
                      </div>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {workshopId ? (
        <form className="mt-5 rounded-md border border-line-200 bg-surface-50 p-4" onSubmit={grant}>
          <h3 className="font-display text-sm font-bold text-ink-900">Grant access to somebody new</h3>
          <div className="mt-3 grid gap-3 md:grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,1.2fr)]">
            <Field label="Person">
              <ComboBox
                onChange={setGrantUserId}
                options={grantableUsers}
                placeholder="Select or type to search"
                value={grantUserId}
              />
            </Field>
            <Field label="Level">
              <Dropdown
                ariaLabel="Level to grant"
                onChange={(next) => setGrantLevel(next as WorkshopAccessLevel)}
                options={levelOptions(levels)}
                value={grantLevel}
              />
            </Field>
            <Field label="Note (optional)">
              <input
                className="field-input"
                onChange={(event) => setGrantNote(event.target.value)}
                placeholder="Why they are being added"
                type="text"
                value={grantNote}
              />
            </Field>
          </div>
          <button className="field-button mt-3" disabled={!grantUserId || busy === `grant:${grantUserId}`} type="submit">
            <UserPlus className="h-4 w-4" aria-hidden />
            {busy === `grant:${grantUserId}` ? "Granting…" : "Grant access"}
          </button>
        </form>
      ) : null}
    </section>
  );
}
