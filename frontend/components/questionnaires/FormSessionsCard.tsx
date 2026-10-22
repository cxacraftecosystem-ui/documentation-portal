"use client";

/**
 * The sessions recorded on one form, and — for content admins — the deleted ones with Restore
 * (UNIFY U8, A14: a session delete is a restorable soft delete, content admins only).
 */

import { RotateCcw } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { RecordTitleLink } from "@/components/records/OpenableRow";
import { RowActions, rowAction } from "@/components/RowActions";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { useToast } from "@/components/ui/Toast";
import { formSessions, libraryError, sourceLabel, type FormSession } from "@/lib/questionnaireLibrary";
import { NEEDS_CONNECTION } from "@/components/questionnaires/useOnline";

export function FormSessionsCard({
  questionnaireId,
  canSeeDeleted,
  online
}: {
  questionnaireId: string;
  canSeeDeleted: boolean;
  online: boolean;
}) {
  const { toast } = useToast();
  const [view, setView] = useState<"live" | "deleted">("live");
  const [rows, setRows] = useState<FormSession[] | null>(null);
  const [more, setMore] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const answer = await formSessions.list(questionnaireId, view === "deleted");
      setRows(answer.items);
      setMore(Boolean(answer.nextCursor));
    } catch (err) {
      setRows([]);
      toast({ tone: "error", title: "Couldn't load the sessions", description: libraryError(err) });
    }
  }, [questionnaireId, view, toast]);

  useEffect(() => {
    void load();
  }, [load]);

  async function restore(row: FormSession) {
    setBusy(row.id);
    try {
      await formSessions.restore(row.id);
      toast({ tone: "success", title: "Session restored", description: row.title });
      await load();
    } catch (err) {
      toast({ tone: "error", title: "Not restored", description: libraryError(err) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="sessions-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="sessions-heading" className="font-display text-lg font-bold text-ink-900">
          Sessions
        </h2>
        {canSeeDeleted ? (
          <SegmentedChoice
            label="Sessions"
            value={view}
            options={[
              { value: "live", label: "Live" },
              { value: "deleted", label: "Deleted" }
            ]}
            onChange={(next) => {
              setRows(null);
              setView(next as "live" | "deleted");
            }}
          />
        ) : null}
      </div>
      {rows === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-ink-500">{view === "deleted" ? "Nothing deleted." : "No sessions on this form yet."}</p>
      ) : (
        <ul className="divide-y divide-line-200 rounded-md border border-line-200" data-testid={`sessions-${view}`}>
          {rows.map((row) => (
            <li key={row.id} className="flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="min-w-0">
                {view === "live" ? (
                  <RecordTitleLink href={`/questionnaire/sessions/${encodeURIComponent(row.id)}`}>{row.title || "Session"}</RecordTitleLink>
                ) : (
                  <span className="font-medium text-ink-900">{row.title || "Session"}</span>
                )}
                <p className="mt-0.5 text-xs text-ink-500">
                  {row.sessionDate ?? ""}
                  {sourceLabel(row.source) ? ` · ${sourceLabel(row.source)}` : ""}
                </p>
              </div>
              {view === "deleted" ? (
                <RowActions>
                  <button
                    type="button"
                    className={rowAction("neutral")}
                    disabled={!online || busy === row.id}
                    title={online ? undefined : NEEDS_CONNECTION}
                    onClick={() => void restore(row)}
                  >
                    <RotateCcw className="h-4 w-4" aria-hidden /> Restore
                  </button>
                </RowActions>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {more ? <p className="text-xs text-ink-500">Showing the latest 50.</p> : null}
    </section>
  );
}
