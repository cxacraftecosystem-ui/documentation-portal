"use client";

/**
 * C28 — draft one section's unanswered questions from the interview's transcript, then accept or
 * edit them together. Drawn only while the server says the feature is on (`/questionnaire/
 * answer-drafts/status`). Nothing is an answer until "Accept section"; every accepted answer is
 * stored and shown as machine-suggested. The rules live in `backend/app/services/answer_drafts.py`.
 */
import { useEffect, useId, useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { Dropdown } from "@/components/ui/Dropdown";
import { ApiError, apiFetch } from "@/lib/api";
import type { QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";

export const MACHINE_SUGGESTED_LABEL = "Machine-suggested";

export type AnswerDraft = {
  id: string;
  questionId: string;
  sectionId: string | null;
  draftText: string;
  status: "PENDING" | "ACCEPTED" | "EDITED" | "DISMISSED";
  provider: string;
  model: string;
};

type DraftList = { items: AnswerDraft[]; transcripts: Array<{ id: string; name: string }> };

let statusPromise: Promise<boolean> | null = null;

export function answerDraftsEnabled(): Promise<boolean> {
  if (!statusPromise) {
    statusPromise = apiFetch<{ enabled: boolean }>("/questionnaire/answer-drafts/status")
      .then((answer) => Boolean(answer?.enabled))
      .catch(() => {
        statusPromise = null;
        return false;
      });
  }
  return statusPromise;
}

export function isMachineSuggested(origin: string | null | undefined): boolean {
  return origin === "MACHINE_SUGGESTED" || origin === "MACHINE_SUGGESTED_EDITED";
}

/** The small label beside a machine-suggested answer. Words, not only colour. */
export function MachineSuggestedBadge({ edited = false }: { edited?: boolean }) {
  return (
    <span className="ml-2 inline-flex items-center rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5 text-[11px] font-medium normal-case tracking-normal text-purple-800 dark:border-purple-700 dark:bg-purple-950/40 dark:text-purple-200">
      {MACHINE_SUGGESTED_LABEL}
      {edited ? " · edited" : ""}
    </span>
  );
}

function detail(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const d = (error.payload as { detail?: unknown } | undefined)?.detail;
    if (typeof d === "string" && d.trim()) return d;
  }
  return fallback;
}

export function AnswerDraftsPanel({
  interviewId,
  section,
  questions,
  onAccepted
}: {
  interviewId: string;
  section: QuestionnaireSection;
  questions: QuestionnaireQuestion[];
  /** questionId → the words kept, so the page's answer boxes show them at once. */
  onAccepted: (answers: Record<string, string>) => void;
}) {
  const baseId = useId();
  const [enabled, setEnabled] = useState(false);
  const [list, setList] = useState<DraftList | null>(null);
  const [source, setSource] = useState("");
  const [pasted, setPasted] = useState("");
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void answerDraftsEnabled().then(async (on) => {
      if (cancelled || !on) return;
      try {
        const answer = await apiFetch<DraftList>(`/questionnaire/interviews/${encodeURIComponent(interviewId)}/drafts`);
        if (cancelled) return;
        setEnabled(true);
        setList(answer);
        if (answer.transcripts[0]) setSource(answer.transcripts[0].id);
      } catch {
        /* Off, or not readable here: draw nothing. */
      }
    });
    return () => {
      cancelled = true;
    };
  }, [interviewId]);

  if (!enabled || !list) return null;

  const prompts = new Map(questions.map((q) => [q.id, q]));
  const pending = list.items.filter((d) => d.sectionId === section.id && d.status === "PENDING" && prompts.has(d.questionId));

  async function draft() {
    setBusy(true);
    setNote(null);
    try {
      const body = source ? { sectionId: section.id, mediaId: source } : { sectionId: section.id, transcript: pasted };
      const answer = await apiFetch<{ drafted: number; asked: number; items: AnswerDraft[] }>(
        `/questionnaire/interviews/${encodeURIComponent(interviewId)}/drafts`,
        { method: "POST", body: JSON.stringify(body) }
      );
      setList((current) =>
        current
          ? { ...current, items: [...current.items.filter((d) => !answer.items.some((n) => n.questionId === d.questionId)), ...answer.items] }
          : current
      );
      setEdits({});
      if (answer.asked === 0) setNote("Every question here is already answered.");
      else if (answer.drafted === 0) setNote("The transcript doesn't answer these questions.");
    } catch (error) {
      setNote(detail(error, "Drafting failed. Try again, or type the answers."));
    } finally {
      setBusy(false);
    }
  }

  async function decide(kind: "accept" | "dismiss") {
    setBusy(true);
    setNote(null);
    try {
      const kept: Record<string, string> = {};
      if (kind === "accept") {
        const answers = pending.map((d) => ({ questionId: d.questionId, answerText: edits[d.questionId] ?? d.draftText }));
        await apiFetch(`/questionnaire/interviews/${encodeURIComponent(interviewId)}/drafts/accept`, {
          method: "POST",
          body: JSON.stringify({ sectionId: section.id, answers })
        });
        for (const a of answers) if (a.answerText.trim()) kept[a.questionId] = a.answerText.trim();
        onAccepted(kept);
      } else {
        await apiFetch(`/questionnaire/interviews/${encodeURIComponent(interviewId)}/drafts/dismiss`, {
          method: "POST",
          body: JSON.stringify({ sectionId: section.id })
        });
      }
      setList((current) =>
        current
          ? {
              ...current,
              items: current.items.map((d) =>
                pending.some((p) => p.id === d.id) ? { ...d, status: kind === "accept" ? "ACCEPTED" : "DISMISSED" } : d
              )
            }
          : current
      );
      setNote(kind === "accept" ? `Accepted ${Object.keys(kept).length} answer${Object.keys(kept).length === 1 ? "" : "s"}.` : "Drafts dismissed.");
    } catch (error) {
      setNote(detail(error, "Couldn't save. Try again."));
    } finally {
      setBusy(false);
    }
  }

  const options = [
    ...list.transcripts.map((t) => ({ value: t.id, label: t.name })),
    { value: "", label: "Paste a transcript" }
  ];

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
      {pending.length === 0 ? (
        <>
          <div className="grid gap-2 md:grid-cols-[minmax(0,1fr)_auto] md:items-end">
            <div className="grid gap-1">
              <span className="field-label" id={`${baseId}-source`}>
                Draft from transcript
              </span>
              <Dropdown
                value={source}
                onChange={setSource}
                options={options}
                ariaLabel="Transcript to draft from"
                advanceOnSelect={false}
              />
            </div>
            <ButtonRow align="end">
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy || (!source && !pasted.trim())}
                onClick={() => void draft()}
              >
                {busy ? "Drafting…" : "Draft answers"}
              </button>
            </ButtonRow>
          </div>
          {!source ? (
            <textarea
              aria-labelledby={`${baseId}-source`}
              className="field-input min-h-24"
              placeholder="Paste the interview transcript"
              value={pasted}
              onChange={(event) => setPasted(event.target.value)}
              onInput={(event) => event.stopPropagation()}
            />
          ) : null}
        </>
      ) : (
        <>
          <p className="text-sm font-medium text-ink-900">
            {pending.length} {MACHINE_SUGGESTED_LABEL.toLowerCase()} answer{pending.length === 1 ? "" : "s"} — check each, then accept
          </p>
          {pending.map((d) => {
            const question = prompts.get(d.questionId)!;
            const boxId = `${baseId}-${d.questionId}`;
            return (
              <div key={d.id} className="grid gap-1">
                <label htmlFor={boxId} className="field-label">
                  {question.sortOrder}. {question.prompt}
                  <MachineSuggestedBadge />
                </label>
                <textarea
                  id={boxId}
                  className="field-input min-h-20"
                  value={edits[d.questionId] ?? d.draftText}
                  onChange={(event) => setEdits((current) => ({ ...current, [d.questionId]: event.target.value }))}
                  onInput={(event) => event.stopPropagation()}
                />
              </div>
            );
          })}
          <ButtonRow align="end">
            <button type="button" className="field-button" disabled={busy} onClick={() => void decide("accept")}>
              Accept section
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void decide("dismiss")}>
              Dismiss
            </button>
          </ButtonRow>
        </>
      )}
      {note ? (
        <p role="status" className="text-xs text-ink-muted">
          {note}
        </p>
      ) : null}
    </div>
  );
}
