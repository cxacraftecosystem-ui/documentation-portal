"use client";

import { KeyRound, Send } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import {
  AccessLevelBadge,
  AccessLevelLegend,
  AccessStatusBadge,
  DEFAULT_ACCESS_LEVEL,
  levelOptions,
  requestableOptions,
  sortWorkshopsByOccurrence,
  useWorkshopAccessLevels,
  workshopLabel,
  type RequestableWorkshop,
  type WorkshopAccessLevel,
  type WorkshopAccessRequestResult,
  type WorkshopAccessRow
} from "@/components/settings/workshopAccess";
import { Field } from "@/components/FormControls";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";

/**
 * "Request workshop access" — the ONE panel on /settings that everybody sees, whatever their role.
 *
 * Multi-select because that is how the need arrives: a researcher joining a project wants the same
 * access to a whole season of workshops, and filing them one at a time produces a queue nobody works
 * through. The backend is idempotent per workshop, so pressing this twice is safe — it reports back
 * per workshop whether the row was created, re-requested, already pending, or already granted, and
 * that summary is what the toast repeats.
 *
 * The picker is fed by `GET /workshops/requestable`, NOT by `GET /workshops`. That distinction is the
 * whole feature: the plain list is visibility-filtered and, below professor, returns only workshops
 * the caller CREATED — so for a newly onboarded researcher or volunteer, who has created none, this
 * panel showed "No workshops to ask about" and there was no way to ask for anything. The people the
 * panel exists for were the only people it did not work for. `requestable` is unfiltered but carries
 * only the identifying fields plus the caller's own standing, so naming a workshop never leaks what
 * is inside it.
 *
 * Below the form is the caller's own history — held, waiting, and refused in one place, which is the
 * difference between "ask again" and "stop asking".
 */

const OUTCOME_TEXT: Record<WorkshopAccessRequestResult["outcomes"][number]["outcome"], string> = {
  CREATED: "requested",
  RE_REQUESTED: "requested again",
  ALREADY_PENDING: "already waiting for a decision",
  ALREADY_GRANTED: "already granted"
};

export function WorkshopAccessRequestPanel() {
  const { toast } = useToast();
  const levels = useWorkshopAccessLevels();

  // null = still loading. Distinguished from [] so the picker can say "loading" rather than the far
  // more discouraging "no workshops to ask about" while the fetch is in flight.
  const [workshops, setWorkshops] = useState<RequestableWorkshop[] | null>(null);
  const [mine, setMine] = useState<WorkshopAccessRow[] | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [level, setLevel] = useState<WorkshopAccessLevel>(DEFAULT_ACCESS_LEVEL);
  const [note, setNote] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadMine = useCallback(async () => {
    try {
      setMine(await apiFetch<WorkshopAccessRow[]>("/workshops/access-requests/mine"));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load your requests");
      setMine([]);
    }
  }, []);

  const loadWorkshops = useCallback(async () => {
    try {
      const rows = await apiFetch<RequestableWorkshop[]>("/workshops/requestable");
      setWorkshops(sortWorkshopsByOccurrence(rows));
    } catch (err) {
      // Surfaced rather than swallowed: an empty picker that is empty because the call failed reads
      // exactly like an empty picker that is empty because there is nothing to ask about.
      setError(err instanceof Error ? err.message : "Unable to load the workshops you can ask about");
      setWorkshops([]);
    }
  }, []);

  useEffect(() => {
    void loadWorkshops();
    void loadMine();
  }, [loadMine, loadWorkshops]);

  const options = useMemo(() => requestableOptions(workshops ?? []), [workshops]);

  // What is already settled, said once under the picker. Both counts are things a user would
  // otherwise discover only by asking and being told nothing happened.
  const standingNote = useMemo(() => {
    const held = (workshops ?? []).filter((workshop) => workshop.accessStatus === "GRANTED").length;
    const open = (workshops ?? []).filter((workshop) => !workshop.restricted && !workshop.accessStatus).length;
    const parts: string[] = [];
    if (held) parts.push(`You already have access to ${held} of these — they are listed but cannot be selected.`);
    if (open) {
      parts.push(
        `${open} ${open === 1 ? "is" : "are"} open to everyone: you can already add records there, so only ask if you need Edit.`
      );
    }
    return parts.join(" ");
  }, [workshops]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!selected.length || sending) return;
    setSending(true);
    setError(null);
    try {
      const result = await apiFetch<WorkshopAccessRequestResult>("/workshops/access-requests", {
        method: "POST",
        body: JSON.stringify({ workshopIds: selected, accessLevel: level, note: note.trim() || null })
      });
      // Group the per-workshop outcomes so the toast reads "2 requested, 1 already granted" rather
      // than repeating a line per workshop.
      const tally = new Map<string, number>();
      result.outcomes.forEach((outcome) => tally.set(outcome.outcome, (tally.get(outcome.outcome) ?? 0) + 1));
      const summary = Array.from(tally.entries())
        .map(([outcome, count]) => `${count} ${OUTCOME_TEXT[outcome as keyof typeof OUTCOME_TEXT] ?? outcome.toLowerCase()}`)
        .join(", ");
      toast({
        title: "Request sent",
        description: `${summary}. An admin decides — you will see the outcome below.`,
        tone: "success"
      });
      setSelected([]);
      setNote("");
      // Reload the picker too, not just the history: the rows just filed are now PENDING, and an
      // option that still offers itself as un-asked invites the same request twice.
      await Promise.all([loadMine(), loadWorkshops()]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to send the request");
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <KeyRound className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">Request workshop access</h2>
      </div>
      <p className="mt-1.5 text-sm leading-6 text-ink-500">
        Some workshops are restricted to the researchers assigned to them. Pick every workshop you need and ask once — an
        admin approves or declines, and the outcome appears below.
      </p>

      {error ? (
        <div className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}

      <form className="mt-4 grid gap-3" onSubmit={submit}>
        <div className="grid gap-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <div className="grid content-start gap-1.5">
            <Field label="Workshops">
              <MultiSelectDropdown
                confirmLabel="Confirm workshops"
                emptyLabel="No workshops have been recorded yet"
                onChange={setSelected}
                options={options}
                placeholder={workshops === null ? "Loading workshops…" : "Select one or more workshops"}
                values={selected}
              />
            </Field>
            {standingNote ? <p className="text-xs leading-5 text-ink-500">{standingNote}</p> : null}
          </div>
          <Field label="Access level you need">
            <Dropdown
              ariaLabel="Access level you need"
              onChange={(next) => setLevel(next as WorkshopAccessLevel)}
              options={levelOptions(levels)}
              value={level}
            />
          </Field>
        </div>

        <Field label="Note for the admin (optional)">
          <textarea
            className="field-input"
            onChange={(event) => setNote(event.target.value)}
            placeholder="Why you need access — e.g. joining the Kutch documentation team."
            rows={2}
            value={note}
          />
        </Field>

        <AccessLevelLegend levels={levels} />

        <div>
          <button className="field-button" disabled={!selected.length || sending} type="submit">
            <Send className="h-4 w-4" aria-hidden />
            {sending ? "Sending…" : selected.length ? `Request access to ${selected.length} workshop${selected.length > 1 ? "s" : ""}` : "Request access"}
          </button>
        </div>
      </form>

      <div className="mt-6">
        <h3 className="font-display text-sm font-bold text-ink-900">Your requests</h3>
        {!mine ? (
          <p className="mt-2 text-sm text-ink-500">Loading…</p>
        ) : mine.length === 0 ? (
          <p className="mt-2 text-sm text-ink-500">You have not asked for access to any workshop yet.</p>
        ) : (
          <ul className="mt-2 grid gap-2">
            {mine.map((row) => (
              <li className="rounded-md border border-line-200 bg-surface-50 p-3" key={row.id}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-medium text-ink-900">{workshopLabel(row.workshop)}</span>
                  <span className="flex items-center gap-2">
                    <AccessLevelBadge level={row.accessLevel} />
                    <AccessStatusBadge status={row.status} />
                  </span>
                </div>
                <p className="mt-1 text-xs text-ink-500">
                  {row.status === "PENDING"
                    ? `Asked ${formatDateTime(row.createdAt)} — waiting for an admin.`
                    : `Decided ${formatDateTime(row.decidedAt ?? row.updatedAt)}.`}
                </p>
                {row.requestNote ? <p className="mt-1 text-xs italic leading-5 text-ink-500">“{row.requestNote}”</p> : null}
                {row.decisionNote ? (
                  <p className="mt-1 text-xs leading-5 text-ink-700">
                    <span className="font-medium">Admin:</span> {row.decisionNote}
                  </p>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
