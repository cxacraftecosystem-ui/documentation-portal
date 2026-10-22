"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { questionnaireApi, type RecordSession } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The session "Continue with…" carries on from: the artisan's latest one on this instrument. */
export function latestSession(sessions: RecordSession[]): RecordSession | null {
  return [...sessions].sort((a, b) => String(b.session.sessionDate ?? "").localeCompare(String(a.session.sessionDate ?? "")))[0] ?? null;
}

/** The body `POST /sessions/continue` takes; a single artisan is always one session. */
export function continueBody(input: { fromSessionId: string; artisanIds: string[]; split: "ONE" | "EACH"; sessionDate: string }) {
  return { ...input, split: input.artisanIds.length > 1 ? input.split : ("ONE" as const) };
}

/**
 * "Continue with…" (DESIGN §3.7): the participant picker prefilled with this artisan, the people
 * from the last sitting on offer, One session · One each, and today's date. The server opens the
 * session(s) and the workspace lands on the first.
 */
export function ContinueWithDialog({
  artisanId,
  artisanName,
  sessions,
  open,
  onClose
}: {
  artisanId: string;
  artisanName: string;
  sessions: RecordSession[];
  open: boolean;
  onClose: () => void;
}) {
  const router = useRouter();
  const last = latestSession(sessions);
  const people = new Map<string, string>([[artisanId, artisanName]]);
  for (const p of last?.session.participants ?? []) if (!people.has(p.artisanId)) people.set(p.artisanId, p.name);
  const [ids, setIds] = useState<string[]>([artisanId]);
  const [split, setSplit] = useState<"ONE" | "EACH">("ONE");
  const [date, setDate] = useState(todayLocal);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function start() {
    if (!last || !ids.length || !date) return;
    setBusy(true);
    setError(null);
    try {
      const out = await questionnaireApi.continueSessions(continueBody({ fromSessionId: last.session.id, artisanIds: ids, split, sessionDate: date }));
      const first = out.sessions[0];
      if (first) router.push(`/questionnaire/sessions/${encodeURIComponent(first.id)}`);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog open={open} onClose={onClose} title={qs("continueWith", { name: artisanName.split(" ")[0] })} busy={busy}>
      <div className="grid gap-3" data-continue-with>
        <div>
          <span className="field-label">{qs("fieldArtisans")}</span>
          <SearchableMultiSelect
            values={ids}
            onChange={setIds}
            options={[...people].map(([value, label]) => ({ value, label }))}
            searchable
            ariaLabel={qs("fieldArtisans")}
          />
        </div>
        {ids.length > 1 ? (
          <SegmentedChoice
            label={qs("continueSplit")}
            value={split}
            options={[
              { value: "ONE", label: qs("continueOneSession") },
              { value: "EACH", label: qs("continueOneEach") }
            ]}
            onChange={(v) => setSplit(v as "ONE" | "EACH")}
          />
        ) : null}
        <div>
          <span className="field-label">{qs("fieldDate")}</span>
          <CalendarField value={date} onChange={setDate} ariaLabel={qs("fieldDate")} required />
        </div>
        {error ? (
          <p role="alert" className="text-xs text-error-600">
            {error}
          </p>
        ) : null}
        <ButtonRow className="mt-2">
          <button type="button" className="field-button-secondary" onClick={onClose}>
            {qs("cancel")}
          </button>
          <button type="button" className="field-button" disabled={busy || !ids.length || !date || !last} onClick={() => void start()}>
            {qs("startSession")}
          </button>
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}
