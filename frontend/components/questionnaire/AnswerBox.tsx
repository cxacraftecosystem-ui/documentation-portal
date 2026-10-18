"use client";

import { useState } from "react";

import { PromptDialog } from "@/components/questionnaire/Prompts";
import { enqueue } from "@/components/questionnaire/useSessionQueue";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { shortName } from "@/lib/coverageCache";
import { questionnaireApi, type Answer, type AnswerItem, type AnswerResult, type Recording, type Session, type SessionParticipant } from "@/lib/questionnaireApi";
import { prompts, shortDate } from "@/lib/questionnairePrompts";
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

/**
 * One question's answer box (DESIGN §3.4, §3.6): "Answer for: All 3" by default in a group, a
 * per-artisan switch, and per-item results — a CONFLICT asks P9 (another device) or Keep · Replace
 * (an answer that already exists), and never blocks the other artisans' saves.
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
  const [conflict, setConflict] = useState<AnswerResult | null>(null);
  const [existing, setExisting] = useState<Answer[]>(answers);
  // P8: answers that came from a take wait for "Replace" before they are overwritten.
  const [p8, setP8] = useState<{ artisanId: string; sourceRecordingId: string }[]>([]);
  const [confirmed, setConfirmed] = useState<Set<string>>(() => new Set());
  const [skipped, setSkipped] = useState<Set<string>>(() => new Set());

  async function save(override?: Answer[], approved: Set<string> = confirmed, skip: Set<string> = skipped) {
    const items = answerItems({ sessionId: session.id, lineageId, text, forWhom, participants, existing: override ?? existing }).filter(
      (i) => !skip.has(i.artisanId)
    );
    if (!items.length || !text.trim()) return;
    const fromTakes = answersFromTakes(override ?? existing, items.map((i) => i.artisanId), text).filter((a) => !approved.has(a.artisanId));
    if (fromTakes.length) {
      setP8(fromTakes);
      return;
    }
    if (navigator.onLine === false || session.version === 0) {
      await enqueue({ id: session.id, clientKey: session.clientKey }, {
        kind: "answers",
        status: "PENDING",
        // The queue fills `sessionId` when it sends: an offline session has no server id yet.
        items: items.map((item) => {
          const rest: Partial<AnswerItem> = { ...item };
          delete rest.sessionId;
          return rest as Omit<AnswerItem, "sessionId">;
        })
      });
      setSaved("✓");
      return;
    }
    const { results } = await questionnaireApi.putAnswers(items);
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
    const clash = results.find((r) => r.status === "CONFLICT");
    if (clash) setConflict(clash);
    setSaved(results.some((r) => r.status === "SAVED" || r.status === "UNCHANGED") ? "✓" : null);
  }

  const options = [
    { value: ALL, label: qs("answerForAll", { count: participants.length }) },
    ...participants.map((p) => ({ value: p.artisanId, label: p.name }))
  ];
  const conflictName = conflict ? shortName(participants.find((p) => p.artisanId === conflict.artisanId)?.name ?? "") : "";
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
            // Replace: overwrite this artisan's answer. Keep current: leave it; the others still save.
            if (choice === "replace") approved.add(first.artisanId);
            else {
              skip.add(first.artisanId);
              if (forWhom !== ALL) setText(existing.find((a) => a.artisanId === first.artisanId)?.text ?? text);
            }
            setConfirmed(approved);
            setSkipped(skip);
            setP8(rest);
            if (!rest.length) void save(undefined, approved, skip);
          }}
        />
      ) : null}
      {conflict && conflict.current ? (
        <PromptDialog
          spec={
            conflict.current.editedBy
              ? prompts.p9({
                  researcher: conflict.current.editedBy.name,
                  name: conflictName,
                  code,
                  time: conflict.current.editedAt
                    ? new Date(conflict.current.editedAt).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Kolkata" })
                    : ""
                })
              : {
                  id: "ANSWER_EXISTS",
                  title: qs("answerExists", { name: conflictName }),
                  body: conflict.current.text,
                  buttons: [
                    { id: "theirs", label: qs("keep"), tone: "secondary" },
                    { id: "mine", label: qs("replace"), tone: "primary" }
                  ]
                }
          }
          open
          onChoose={(choice) => {
            const c = conflict;
            setConflict(null);
            if (!c?.current) return;
            const current = { ...c.current, artisanId: c.artisanId };
            const others = existing.filter((a) => a.artisanId !== c.artisanId);
            setExisting([...others, current]);
            if (choice === "theirs") setText(current.text);
            else void save([...others, current]);
          }}
        />
      ) : null}
    </div>
  );
}
