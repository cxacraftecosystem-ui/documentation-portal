"use client";

import { useState } from "react";
import { Check } from "lucide-react";

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
import { AnswerTextEditor, answerEditorValue, answerParts } from "@/lib/questionnairePortal";

const ALL = "__all__";

export type AnswerSubject = { key: string; artisanId: string | null; respondentId: string | null; name: string };

export function sessionSubjects(session: Pick<Session, "participants" | "respondents">): AnswerSubject[] {
  return [
    ...session.participants.filter((p) => !p.leftAt).map((p) => ({ key: `A:${p.artisanId}`, artisanId: p.artisanId, respondentId: null, name: p.name })),
    ...(session.respondents ?? [])
      .filter((r) => !r.artisanId)
      .map((r) => ({ key: `R:${r.id}`, artisanId: null, respondentId: r.id, name: r.name }))
  ];
}

export type AnswerProposal = { text: string; origin: AnswerOrigin; sourceRecordingId: string; subjectKeys: string[] | null };

export function answerItems(input: {
  sessionId: string;
  lineageId: string;
  text: string;
  richText?: string | null;
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
      ...(input.richText ? { richText: input.richText } : {}),
      origin: input.origin ?? "TYPED",
      attribution: group ? "GROUP_SHARED" : "INDIVIDUAL",
      sessionId: input.sessionId,
      sourceRecordingId: input.sourceRecordingId ?? null,
      ...(input.note !== undefined ? { note: input.note } : {}),
      ...(current ? { baseVersion: current.version } : {})
    };
  });
}

export function answerLocked(current: Pick<Answer, "text" | "actorId"> | null | undefined, me: string | null, mayOverride: boolean): boolean {
  if (!current || !current.text.trim() || mayOverride) return false;
  return Boolean(current.actorId) && current.actorId !== me;
}

function forQueue(items: AnswerItem[]): Omit<AnswerItem, "sessionId">[] {
  return items.map((item) => {
    const rest: Partial<AnswerItem> = { ...item };
    delete rest.sessionId;
    return rest as Omit<AnswerItem, "sessionId">;
  });
}

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
  recordings?: Recording[];
  me: string | null;
  mayOverride?: boolean;
  proposal?: AnswerProposal | null;
  required?: boolean;
}) {
  const [forWhom, setForWhom] = useState(subjects.length > 1 ? ALL : (subjects[0]?.key ?? ALL));
  const [existing, setExisting] = useState<Answer[]>(answers);
  const current = (key: string) => existing.find((a) => ownerKey(a) === key) ?? null;
  const focus = forWhom === ALL ? (subjects.length === 1 ? subjects[0].key : null) : forWhom;
  const focused = focus ? current(focus) : (existing[0] ?? null);
  const [text, setTextState] = useState(() => answerEditorValue(focused));
  const [editorKey, setEditorKey] = useState(0);
  const setText = (value: string) => {
    setTextState(value);
    setEditorKey((k) => k + 1);
  };
  const plain = answerParts(text).text;
  const [note, setNote] = useState(() => focused?.note ?? "");
  const [noteOpen, setNoteOpen] = useState(Boolean(focused?.note));
  const [origin, setOrigin] = useState<{ origin: AnswerOrigin; sourceRecordingId: string | null } | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lockedFor, setLockedFor] = useState<string[]>([]);
  const [history, setHistory] = useState<AnswerRevision[] | null>(null);
  const [p8, setP8] = useState<{ artisanId: string; sourceRecordingId: string }[]>([]);
  const [confirmed, setConfirmed] = useState<Set<string>>(() => new Set());
  const [skipped, setSkipped] = useState<Set<string>>(() => new Set());

  const nameOf = (key: string) => subjects.find((s) => s.key === key)?.name ?? "";
  const researcherName = (id: string | null | undefined) =>
    session.researchers.find((r) => r.userId === id)?.name ?? (id && id === me ? qs("you") : qs("someoneElse"));
  const locked = focus ? answerLocked(current(focus), me, mayOverride) : subjects.every((s) => answerLocked(current(s.key), me, mayOverride));
  const lockedAnswer = focus ? current(focus) : existing.find((a) => answerLocked(a, me, mayOverride));
  const lockedBy = lockedAnswer?.actorId;
  const lockedByName = session.researchers.find((r) => r.userId === lockedBy)?.name ?? lockedAnswer?.actorName ?? researcherName(lockedBy);

  async function keep(step: AnswersStep) {
    await enqueue({ id: session.id, clientKey: session.clientKey }, step);
  }

  function pick(next: string) {
    setForWhom(next);
    const a = next === ALL ? null : current(next);
    setText(answerEditorValue(a));
    setNote(a?.note ?? "");
    setNoteOpen(Boolean(a?.note));
    setSaved(false);
    setLockedFor([]);
  }

  async function save(approved: Set<string> = confirmed, skip: Set<string> = skipped) {
    const open = subjects.filter((s) => !answerLocked(current(s.key), me, mayOverride));
    const items = answerItems({
      sessionId: session.id,
      lineageId,
      text: plain,
      richText: answerParts(text).richText,
      note: noteOpen ? note.trim() || null : undefined,
      forWhom,
      subjects: open,
      existing,
      origin: origin?.origin,
      sourceRecordingId: origin?.sourceRecordingId ?? null
    }).filter((i) => !skip.has(ownerKey(i)));
    if (!items.length || !plain.trim()) return;
    const fromTakes = answersFromTakes(
      existing.filter((a) => a.artisanId).map((a) => ({ artisanId: a.artisanId ?? undefined, text: a.text, sourceRecordingId: a.sourceRecordingId })),
      items.filter((i) => i.artisanId).map((i) => i.artisanId as string),
      plain
    ).filter((a) => !approved.has(`A:${a.artisanId}`));
    if (fromTakes.length && !origin) {
      setP8(fromTakes);
      return;
    }
    setError(null);
    try {
      if (navigator.onLine === false || session.version === 0) {
        await keep({ kind: "answers", status: "PENDING", items: forQueue(items) });
        setSaved(true);
        return;
      }
      let results: AnswerResult[];
      try {
        ({ results } = await questionnaireApi.putAnswers(items));
      } catch (failure) {
        const step = answerFallback(items, { error: failure });
        if (!step) throw failure;
        await keep(step);
        setSaved(true);
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
      setSaved(results.some((r) => r.status === "SAVED" || r.status === "UNCHANGED"));
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
  const offer = proposal && proposal.text.trim() && proposal.text.trim() !== plain.trim() && !locked ? proposal : null;

  return (
    <div className="mt-2 grid gap-2" data-answer-box={lineageId}>
      {subjects.length > 1 ? (
        <div className="flex flex-wrap items-center gap-2 text-sm text-ink-700">
          <span>{qs("answerFor")}</span>
          <SearchableSelect value={forWhom} onChange={pick} options={options} ariaLabel={qs("answerFor")} advanceOnSelect={false} />
        </div>
      ) : null}
      <AnswerTextEditor
        key={`${forWhom}|${editorKey}`}
        value={text}
        ariaLabel={required ? `${code} *` : code}
        required={required}
        readOnly={locked}
        onChange={(value) => {
          setTextState(value);
          setOrigin(null);
          setSaved(false);
          setConfirmed(new Set());
          setSkipped(new Set());
        }}
        onCommit={() => void (locked ? undefined : save())}
      />
      {focused?.origin === "PREFILL" && plain === (focused.text ?? "") ? (
        <p className="text-xs text-ink-500" data-prefilled>
          {qs("prefilledFromRecord")}
        </p>
      ) : null}
      {locked ? (
        <p className="text-xs text-ink-500" data-p15>
          {qs("p15Locked", { name: shortName(lockedByName) })}
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
              setSaved(false);
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
            setSaved(false);
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
        {saved ? <Check className="h-3.5 w-3.5 text-success-600" aria-hidden data-answer-saved /> : null}
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
            if (choice === "replace") approved.add(key);
            else {
              skip.add(key);
              if (forWhom !== ALL) setText(current(key) ? answerEditorValue(current(key)) : text);
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
