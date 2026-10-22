"use client";

import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { PromptDialog } from "@/components/questionnaire/Prompts";
import { enqueue } from "@/components/questionnaire/useSessionQueue";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ApiError } from "@/lib/api";
import { shortName } from "@/lib/questionnaire/coverageCache";
import {
  ownerKey,
  questionnaireApi,
  type Answer,
  type AnswerItem,
  type AnswerOrigin,
  type AnswerResult,
  type AnswerRevision,
  type Recording,
  type Session
} from "@/lib/questionnaire/api";
import { prompts, shortDate } from "@/lib/questionnaire/prompts";
import { heldAnswerResults, isPermanent, type AnswersStep } from "@/lib/questionnaire/queue";
import { answersFromTakes } from "@/lib/questionnaire/view";
import { qs } from "@/lib/questionnaire/qs";

const ALL = "__all__";

/**
 * Someone an answer can belong to: an artisan on the session, or a guest respondent (owner decision
 * 1). `key` is the server's subject key, `A:<artisanId>` or `R:<respondentId>`.
 */
export type AnswerSubject = { key: string; artisanId: string | null; respondentId: string | null; name: string };

/** The session's answerable people: its present participants, then its guests not yet registered. */
export function sessionSubjects(session: Pick<Session, "participants" | "respondents">): AnswerSubject[] {
  return [
    ...session.participants.filter((p) => !p.leftAt).map((p) => ({ key: `A:${p.artisanId}`, artisanId: p.artisanId, respondentId: null, name: p.name })),
    ...(session.respondents ?? [])
      .filter((r) => !r.artisanId)
      .map((r) => ({ key: `R:${r.id}`, artisanId: null, respondentId: r.id, name: r.name }))
  ];
}

/** A take's transcript offered as an answer ("Use as answer", B22): never imposed (P8 still asks). */
export type AnswerProposal = { text: string; origin: AnswerOrigin; sourceRecordingId: string; subjectKeys: string[] | null };

/** The items one save sends: a shared group answer is one GROUP_SHARED item per person (B20). */
export function answerItems(input: {
  sessionId: string;
  lineageId: string;
  text: string;
  note?: string | null;
  forWhom: string;
  subjects: Pick<AnswerSubject, "key" | "artisanId" | "respondentId">[];
  existing: Answer[];
  origin?: AnswerOrigin;
  sourceRecordingId?: string | null;
}): AnswerItem[] {
  const chosen = input.forWhom === ALL ? input.subjects : input.subjects.filter((s) => s.key === input.forWhom);
  const group = input.forWhom === ALL && chosen.length > 1;
  return chosen.map((subject) => {
    const current = input.existing.find((a) => ownerKey(a) === subject.key);
    return {
      ...(subject.artisanId ? { artisanId: subject.artisanId } : { respondentId: subject.respondentId }),
      questionLineageId: input.lineageId,
      text: input.text,
      origin: input.origin ?? "TYPED",
      attribution: group ? "GROUP_SHARED" : "INDIVIDUAL",
      sessionId: input.sessionId,
      sourceRecordingId: input.sourceRecordingId ?? null,
      ...(input.note !== undefined ? { note: input.note } : {}),
      ...(current ? { baseVersion: current.version } : {})
    };
  });
}

/**
 * THE ONE ANSWER-LOCK RULE (owner decision 2, U9), as the server applies it: an answer that has text
 * may be changed only by the person who wrote it, a lead researcher of the session, or an admin.
 * Everyone else sees it read-only, with who answered (P15). The server refuses the same item with
 * `ANSWER_LOCKED` whatever the screen showed.
 */
export function answerLocked(current: Pick<Answer, "text" | "actorId"> | null | undefined, me: string | null, mayOverride: boolean): boolean {
  if (!current || !current.text.trim() || mayOverride) return false;
  return Boolean(current.actorId) && current.actorId !== me;
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
 * to the outbox as it was typed; a CONFLICT is kept in the outbox for P9 rather than dropped. A
 * locked item (`ANSWER_LOCKED`) is shown, not queued: no retry will cure it. Null when nothing needs
 * keeping. A 4xx that no retry will cure is the caller's error.
 */
export function answerFallback(
  items: AnswerItem[],
  outcome: { error: unknown } | { results: Parameters<typeof heldAnswerResults>[1] }
): AnswersStep | null {
  if ("error" in outcome) {
    if (isPermanent(outcome.error)) return null;
    return { kind: "answers", status: "PENDING", items: forQueue(items) };
  }
  const held = heldAnswerResults(items, outcome.results).filter((r) => r.code !== "ANSWER_LOCKED");
  if (!held.length) return null;
  const kept = items.filter((item) => held.some((r) => ownerKey(r) === ownerKey(item) && r.questionLineageId === item.questionLineageId));
  return { kind: "answers", status: "FAILED", error: "CONFLICT", items: forQueue(kept), conflicts: held };
}

/**
 * One question's answer box (DESIGN §3.4, §3.6): "Answer for: All 3" by default in a group (B20), a
 * per-person switch, an answer note (A20), the lock (A11, P15), the History (B21) and "Use as answer"
 * (B22). Nothing typed is lost (audit H18): offline, on a new session, or when the save fails for a
 * reason that may pass, the answer goes to the outbox; a CONFLICT waits there for the workspace's P9.
 */
export function AnswerBox({
  session,
  lineageId,
  code,
  subjects,
  answers,
  recordings = [],
  me,
  mayOverride = false,
  proposal = null,
  required = false
}: {
  session: Session;
  lineageId: string;
  code: string;
  subjects: AnswerSubject[];
  answers: Answer[];
  /** The session's takes, so P8 can name whose take an answer came from. */
  recordings?: Recording[];
  /** The signed-in user's id, for the lock. */
  me: string | null;
  /** A lead researcher of the session, or an admin: may change anyone's answer. */
  mayOverride?: boolean;
  /** A take's transcript offered for this question ("Use as answer"). */
  proposal?: AnswerProposal | null;
  /** Marked `*`; Finish counts the unanswered ones and never blocks (A19). */
  required?: boolean;
}) {
  const [forWhom, setForWhom] = useState(subjects.length > 1 ? ALL : (subjects[0]?.key ?? ALL));
  const [existing, setExisting] = useState<Answer[]>(answers);
  const current = (key: string) => existing.find((a) => ownerKey(a) === key) ?? null;
  const focus = forWhom === ALL ? (subjects.length === 1 ? subjects[0].key : null) : forWhom;
  const focused = focus ? current(focus) : (existing[0] ?? null);
  const [text, setText] = useState(() => focused?.text ?? "");
  const [note, setNote] = useState(() => focused?.note ?? "");
  const [noteOpen, setNoteOpen] = useState(Boolean(focused?.note));
  const [origin, setOrigin] = useState<{ origin: AnswerOrigin; sourceRecordingId: string | null } | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lockedFor, setLockedFor] = useState<string[]>([]);
  const [history, setHistory] = useState<AnswerRevision[] | null>(null);
  // P8: answers that came from a take wait for "Replace" before they are overwritten.
  const [p8, setP8] = useState<{ artisanId: string; sourceRecordingId: string }[]>([]);
  const [confirmed, setConfirmed] = useState<Set<string>>(() => new Set());
  const [skipped, setSkipped] = useState<Set<string>>(() => new Set());

  const nameOf = (key: string) => subjects.find((s) => s.key === key)?.name ?? "";
  const researcherName = (id: string | null | undefined) =>
    session.researchers.find((r) => r.userId === id)?.name ?? (id && id === me ? qs("you") : qs("someoneElse"));
  const locked = focus ? answerLocked(current(focus), me, mayOverride) : subjects.every((s) => answerLocked(current(s.key), me, mayOverride));
  const lockedBy = focus ? current(focus)?.actorId : existing.find((a) => answerLocked(a, me, mayOverride))?.actorId;

  async function keep(step: AnswersStep) {
    await enqueue({ id: session.id, clientKey: session.clientKey }, step);
  }

  function pick(next: string) {
    setForWhom(next);
    const a = next === ALL ? null : current(next);
    setText(a?.text ?? "");
    setNote(a?.note ?? "");
    setNoteOpen(Boolean(a?.note));
    setSaved(null);
    setLockedFor([]);
  }

  async function save(approved: Set<string> = confirmed, skip: Set<string> = skipped) {
    // Locked people are left out here; the server would refuse them item by item anyway.
    const open = subjects.filter((s) => !answerLocked(current(s.key), me, mayOverride));
    const items = answerItems({
      sessionId: session.id,
      lineageId,
      text,
      note: noteOpen ? note.trim() || null : undefined,
      forWhom,
      subjects: open,
      existing,
      origin: origin?.origin,
      sourceRecordingId: origin?.sourceRecordingId ?? null
    }).filter((i) => !skip.has(ownerKey(i)));
    if (!items.length || !text.trim()) return;
    const fromTakes = answersFromTakes(
      existing.filter((a) => a.artisanId).map((a) => ({ artisanId: a.artisanId ?? undefined, text: a.text, sourceRecordingId: a.sourceRecordingId })),
      items.filter((i) => i.artisanId).map((i) => i.artisanId as string),
      text
    ).filter((a) => !approved.has(`A:${a.artisanId}`));
    if (fromTakes.length && !origin) {
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
          const key = ownerKey(r);
          const at = next.findIndex((a) => ownerKey(a) === key);
          const value: Answer = { ...r.answer, artisanId: r.artisanId ?? null, respondentId: r.respondentId ?? null, actorId: r.answer.actorId ?? me };
          if (at >= 0) next[at] = value;
          else next.push(value);
        }
      }
      setExisting(next);
      setLockedFor(results.filter((r) => r.code === "ANSWER_LOCKED").map((r) => ownerKey(r)));
      const held = answerFallback(items, { results });
      if (held) await keep(held);
      setSaved(results.some((r) => r.status === "SAVED" || r.status === "UNCHANGED") ? "✓" : null);
    } catch (failure) {
      setError(failure instanceof ApiError || failure instanceof Error ? failure.message : String(failure));
    }
  }

  async function openHistory() {
    const target = focused;
    if (!target) return;
    try {
      setHistory((await questionnaireApi.answerRevisions(target.id)).revisions);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    }
  }

  const options = [
    { value: ALL, label: qs("answerForAll", { count: subjects.length }) },
    ...subjects.map((s) => ({ value: s.key, label: s.name }))
  ];
  const p8Take = p8[0] ? recordings.find((r) => r.id === p8[0].sourceRecordingId) : undefined;
  const offer = proposal && proposal.text.trim() && proposal.text.trim() !== text.trim() && !locked ? proposal : null;

  return (
    <div className="mt-2 grid gap-2" data-answer-box={lineageId}>
      {subjects.length > 1 ? (
        <div className="flex flex-wrap items-center gap-2 text-sm text-ink-700">
          <span>{qs("answerFor")}</span>
          <SearchableSelect value={forWhom} onChange={pick} options={options} ariaLabel={qs("answerFor")} advanceOnSelect={false} />
        </div>
      ) : null}
      <textarea
        className="field-input min-h-20"
        aria-label={required ? `${code} *` : code}
        aria-required={required || undefined}
        value={text}
        readOnly={locked}
        aria-readonly={locked || undefined}
        data-locked={locked || undefined}
        onChange={(e) => {
          setText(e.target.value);
          setOrigin(null);
          setSaved(null);
          setConfirmed(new Set());
          setSkipped(new Set());
        }}
        onBlur={() => void (locked ? undefined : save())}
      />
      {locked ? (
        <p className="text-xs text-ink-500" data-p15>
          {qs("p15Locked", { name: shortName(researcherName(lockedBy)) })}
        </p>
      ) : null}
      {lockedFor.length ? (
        <p className="text-xs text-ink-500" data-p15>
          {qs("p15LockedFor", { names: lockedFor.map((k) => shortName(nameOf(k))).join(", ") })}
        </p>
      ) : null}
      {offer ? (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-purple-200 bg-purple-50 p-2 text-sm dark:border-purple-800 dark:bg-purple-950" data-use-as-answer>
          <span className="min-w-0 flex-1 text-ink-700">{offer.text}</span>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              setText(offer.text);
              setOrigin({ origin: offer.origin, sourceRecordingId: offer.sourceRecordingId });
              setSaved(null);
            }}
          >
            {qs("useAsAnswer")}
          </button>
        </div>
      ) : null}
      {noteOpen ? (
        <input
          className="field-input text-sm"
          aria-label={qs("answerNote")}
          placeholder={qs("answerNote")}
          value={note}
          maxLength={2000}
          readOnly={locked}
          onChange={(e) => {
            setNote(e.target.value);
            setSaved(null);
          }}
          onBlur={() => void (locked ? undefined : save())}
          data-answer-note
        />
      ) : null}
      <div className="flex flex-wrap items-center gap-3 text-xs">
        {!noteOpen && !locked ? (
          <button type="button" className="font-medium text-purple-700 underline dark:text-purple-300" onClick={() => setNoteOpen(true)}>
            {qs("addNote")}
          </button>
        ) : null}
        {focused ? (
          <button type="button" className="font-medium text-purple-700 underline dark:text-purple-300" onClick={() => void openHistory()} data-history>
            {qs("history")}
          </button>
        ) : null}
        {saved ? <span className="text-success-600">{saved}</span> : null}
      </div>
      {error ? (
        <p role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
      {history ? (
        <FieldDialog open onClose={() => setHistory(null)} title={`${qs("history")} · ${code}`}>
          <ol className="grid gap-2" data-answer-history>
            {history.map((h) => (
              <li key={h.id} className="rounded-md border border-line-200 p-2 text-sm">
                <p className="text-ink-900">{h.text || "—"}</p>
                <p className="mt-1 text-xs text-ink-500">
                  {[h.actor.name || researcherName(h.actor.id), shortDate(h.createdAt), h.current ? qs("currentAnswer") : null].filter(Boolean).join(" · ")}
                </p>
              </li>
            ))}
          </ol>
          <ButtonRow>
            <button type="button" className="field-button" onClick={() => setHistory(null)}>
              {qs("ok")}
            </button>
          </ButtonRow>
        </FieldDialog>
      ) : null}
      {p8[0] ? (
        <PromptDialog
          spec={prompts.p8({
            name: shortName(nameOf(`A:${p8[0].artisanId}`)),
            code,
            researcher: shortName(p8Take?.recordedBy.name ?? ""),
            date: shortDate(p8Take?.capturedAt)
          })}
          open
          onChoose={(choice) => {
            const [first, ...rest] = p8;
            const approved = new Set(confirmed);
            const skip = new Set(skipped);
            const key = `A:${first.artisanId}`;
            // Replace: overwrite this artisan's answer. Keep current (or a dismissal): leave it; the others still save.
            if (choice === "replace") approved.add(key);
            else {
              skip.add(key);
              if (forWhom !== ALL) setText(current(key)?.text ?? text);
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
