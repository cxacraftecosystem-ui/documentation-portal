"use client";

import { useState } from "react";

import { PromptDialog } from "@/components/questionnaire/Prompts";
import { enqueue } from "@/components/questionnaire/useSessionQueue";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ApiError } from "@/lib/api";
import { shortName } from "@/lib/coverageCache";
import { questionnaireApi, type Answer, type AnswerItem, type AnswerResult, type Recording, type Session, type SessionParticipant } from "@/lib/questionnaireApi";
import { prompts, shortDate } from "@/lib/questionnairePrompts";
import { heldAnswerResults, isPermanent, type AnswersStep } from "@/lib/questionnaireQueue";
import { answersFromTakes } from "@/lib/questionnaireView";
import { qs } from "@/lib/strings/qs";

const ALL = "__all__";

/** The items one save sends: a shared group answer is one GROUP_SHARED item per artisan. */
export function answerItems(input: {
  sessionId: string;
  lineageId: string;
  text: string;
  forWhom: string;
  participants: { artisanId: string }[];
  existing: Answer[];
}): AnswerItem[] {
  const ids = input.forWhom === ALL ? input.participants.map((p) => p.artisanId) : [input.forWhom];
  const group = input.forWhom === ALL && ids.length > 1;
  return ids.map((artisanId) => {
    const current = input.existing.find((a) => a.artisanId === artisanId);
    return {
      artisanId,
      questionLineageId: input.lineageId,
      text: input.text,
      origin: "TYPED",
      attribution: group ? "GROUP_SHARED" : "INDIVIDUAL",
      sessionId: input.sessionId,
      sourceRecordingId: null,
      ...(current ? { baseVersion: current.version } : {})
    };
  });
}

/** The queue fills `sessionId` when it sends: an offline session has no server id yet. */
function forQueue(items: AnswerItem[]): Omit<AnswerItem, "sessionId">[] {
  return items.map((item) => {
    const rest: Partial<AnswerItem> = { ...item };
    delete rest.sessionId;
    return rest as Omit<AnswerItem, "sessionId">;
  });
}

/**
 * What a save becomes when it cannot simply land (audit H18): a failure that might pass later goes
 * to the outbox as it was typed; a CONFLICT or FORBIDDEN result is kept in the outbox for P9 rather
 * than dropped. Null when nothing needs keeping. A 4xx that no retry will cure is the caller's error.
 */
export function answerFallback(
  items: AnswerItem[],
  outcome: { error: unknown } | { results: Parameters<typeof heldAnswerResults>[1] }
): AnswersStep | null {
  if ("error" in outcome) {
    if (isPermanent(outcome.error)) return null;
    return { kind: "answers", status: "PENDING", items: forQueue(items) };
  }
  const held = heldAnswerResults(items, outcome.results);
  if (!held.length) return null;
  const kept = items.filter((item) => held.some((r) => r.artisanId === item.artisanId && r.questionLineageId === item.questionLineageId));
  return { kind: "answers", status: "FAILED", error: "CONFLICT", items: forQueue(kept), conflicts: held };
}

/**
 * One question's answer box (DESIGN §3.4, §3.6): "Answer for: All 3" by default in a group, a
 * per-artisan switch, and per-item results. Nothing typed is lost (audit H18): offline, on a new
 * session, or when the save fails for a reason that may pass, the answer goes to the outbox; a
 * CONFLICT (another device, or an answer that already exists) waits in the outbox for the
 * workspace's P9, and never blocks the other artisans' saves.
 */
export function AnswerBox({
  session,
  lineageId,
  code,
  participants,
  answers,
  recordings = []
}: {
  session: Session;
  lineageId: string;
  code: string;
  participants: SessionParticipant[];
  answers: Answer[];
  /** The session's takes, so P8 can name whose take an answer came from. */
  recordings?: Recording[];
}) {
  const [forWhom, setForWhom] = useState(ALL);
  const [text, setText] = useState(() => answers[0]?.text ?? "");
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [existing, setExisting] = useState<Answer[]>(answers);
  // P8: answers that came from a take wait for "Replace" before they are overwritten.
  const [p8, setP8] = useState<{ artisanId: string; sourceRecordingId: string }[]>([]);
  const [confirmed, setConfirmed] = useState<Set<string>>(() => new Set());
  const [skipped, setSkipped] = useState<Set<string>>(() => new Set());

  async function keep(step: AnswersStep) {
    await enqueue({ id: session.id, clientKey: session.clientKey }, step);
  }

  async function save(approved: Set<string> = confirmed, skip: Set<string> = skipped) {
    const items = answerItems({ sessionId: session.id, lineageId, text, forWhom, participants, existing }).filter((i) => !skip.has(i.artisanId));
    if (!items.length || !text.trim()) return;
    const fromTakes = answersFromTakes(existing, items.map((i) => i.artisanId), text).filter((a) => !approved.has(a.artisanId));
    if (fromTakes.length) {
      setP8(fromTakes);
      return;
    }
    setError(null);
    try {
      if (navigator.onLine === false || session.version === 0) {
        await keep({ kind: "answers", status: "PENDING", items: forQueue(items) });
        setSaved("✓");
        return;
      }
      let results: AnswerResult[];
      try {
        ({ results } = await questionnaireApi.putAnswers(items));
      } catch (failure) {
        const step = answerFallback(items, { error: failure });
        if (!step) throw failure;
        await keep(step);
        setSaved("✓");
        return;
      }
      const next = [...existing];
      for (const r of results) {
        if (r.answer) {
          const at = next.findIndex((a) => a.artisanId === r.artisanId);
          const value = { ...r.answer, artisanId: r.artisanId };
          if (at >= 0) next[at] = value;
          else next.push(value);
        }
      }
      setExisting(next);
      const held = answerFallback(items, { results });
      if (held) await keep(held);
      setSaved(results.some((r) => r.status === "SAVED" || r.status === "UNCHANGED") ? "✓" : null);
    } catch (failure) {
      setError(failure instanceof ApiError || failure instanceof Error ? failure.message : String(failure));
    }
  }

  const options = [
    { value: ALL, label: qs("answerForAll", { count: participants.length }) },
    ...participants.map((p) => ({ value: p.artisanId, label: p.name }))
  ];
  const p8Take = p8[0] ? recordings.find((r) => r.id === p8[0].sourceRecordingId) : undefined;

  return (
    <div className="mt-2 grid gap-2">
      {participants.length > 1 ? (
        <div className="flex flex-wrap items-center gap-2 text-sm text-ink-700">
          <span>{qs("answerFor")}</span>
          <SearchableSelect value={forWhom} onChange={setForWhom} options={options} ariaLabel={qs("answerFor")} advanceOnSelect={false} />
        </div>
      ) : null}
      <textarea
        className="field-input min-h-20"
        aria-label={code}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setSaved(null);
          setConfirmed(new Set());
          setSkipped(new Set());
        }}
        onBlur={() => void save()}
      />
      {saved ? <span className="text-xs text-success-600">{saved}</span> : null}
      {error ? (
        <p role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
      {p8[0] ? (
        <PromptDialog
          spec={prompts.p8({
            name: shortName(participants.find((p) => p.artisanId === p8[0].artisanId)?.name ?? ""),
            code,
            researcher: shortName(p8Take?.recordedBy.name ?? ""),
            date: shortDate(p8Take?.capturedAt)
          })}
          open
          onChoose={(choice) => {
            const [first, ...rest] = p8;
            const approved = new Set(confirmed);
            const skip = new Set(skipped);
            // Replace: overwrite this artisan's answer. Keep current (or a dismissal): leave it; the others still save.
            if (choice === "replace") approved.add(first.artisanId);
            else {
              skip.add(first.artisanId);
              if (forWhom !== ALL) setText(existing.find((a) => a.artisanId === first.artisanId)?.text ?? text);
            }
            setConfirmed(approved);
            setSkipped(skip);
            setP8(rest);
            if (!rest.length) void save(approved, skip);
          }}
        />
      ) : null}
    </div>
  );
}
