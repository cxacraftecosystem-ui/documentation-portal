"use client";

import { useId, useState } from "react";
import { Languages, PenLine } from "lucide-react";

import { englishPlan } from "@/lib/transcript/api";
import type { Transcript } from "@/lib/transcript/contract";
import { ts } from "@/lib/transcript/strings";

import { Actions, Badge, PRIMARY, SECONDARY } from "./parts";

const FIELD =
  "min-h-40 w-full rounded-md border border-line-200 bg-card px-3 py-2 text-sm leading-6 text-ink-900 placeholder:text-ink-300 focus:border-purple-600 focus:outline-hidden focus:ring-4 focus:ring-purple-600/15";

export function EnglishNotice({
  transcript,
  busy,
  onSave
}: {
  transcript: Transcript;
  busy: boolean;
  onSave: (text: string) => Promise<boolean>;
}) {
  const [writing, setWriting] = useState(false);
  const [text, setText] = useState("");
  const fieldId = useId();
  const hintId = useId();
  const notice = transcript.englishNotice ?? null;
  if (notice === null) return null;

  if (notice === "UNAVAILABLE") {
    return (
      <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" data-english-notice="UNAVAILABLE">
        {ts("englishUnavailable")}
      </p>
    );
  }

  const canAdd = Boolean(transcript.permissions.addEnglish);
  const plan = englishPlan(transcript.turns, text);
  const planLine =
    plan.paragraphs === 0
      ? null
      : plan.aligned
        ? ts("addEnglishAligned", { count: plan.paragraphs })
        : ts("addEnglishWhole", { paragraphs: plan.paragraphs, turns: plan.turns });

  return (
    <section
      className="grid gap-3 rounded-md border border-purple-200 bg-purple-50 px-4 py-3 dark:border-purple-800 dark:bg-purple-950/40"
      aria-label={ts("englishDeclined")}
      data-english-notice="DECLINED"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-md bg-card text-purple-700 dark:text-purple-300" aria-hidden>
          <Languages className="h-4 w-4" />
        </span>
        <div className="grid gap-0.5">
          <p className="text-sm font-semibold text-ink-900">{ts("englishDeclined")}</p>
          {canAdd ? <p className="text-sm text-ink-700">{ts("englishDeclinedHint")}</p> : null}
        </div>
      </div>
      {canAdd && !writing ? (
        <Actions align="center">
          <button type="button" className={PRIMARY} onClick={() => setWriting(true)} data-testid="add-english-open">
            <PenLine className="h-4 w-4" aria-hidden />
            {ts("addEnglish")}
          </button>
        </Actions>
      ) : null}
      {canAdd && writing ? (
        <form
          className="grid gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            if (!text.trim() || busy) return;
            void onSave(text).then((saved) => {
              if (saved) {
                setText("");
                setWriting(false);
              }
            });
          }}
          data-add-english
        >
          <label htmlFor={fieldId} className="text-xs font-medium uppercase tracking-wide text-ink-500">
            {ts("addEnglishTitle")}
          </label>
          <textarea
            id={fieldId}
            className={FIELD}
            value={text}
            lang="en"
            placeholder={ts("addEnglishPlaceholder")}
            aria-describedby={hintId}
            onChange={(event) => setText(event.target.value)}
            data-testid="add-english-text"
          />
          <p id={hintId} className="text-xs text-ink-500" aria-live="polite" data-english-plan={plan.aligned ? "turns" : "whole"}>
            {planLine ?? ts("addEnglishHint")}
          </p>
          <Actions align="end">
            <button type="button" className={SECONDARY} onClick={() => setWriting(false)} disabled={busy}>
              {ts("cancel")}
            </button>
            <button type="submit" className={PRIMARY} disabled={busy || !text.trim()} data-testid="add-english-save">
              {busy ? ts("saving") : ts("addEnglishSave")}
            </button>
          </Actions>
        </form>
      ) : null}
    </section>
  );
}

export function ManualEnglish({ text }: { text: string }) {
  const paragraphs = text.split(/\n\s*\n/).filter((part) => part.trim() !== "");
  return (
    <section className="grid gap-2 rounded-md border border-line-200 bg-card px-4 py-3" lang="en" data-manual-english>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold text-ink-900">{ts("manualEnglishTitle")}</h3>
        <Badge>{ts("manualEnglishSource")}</Badge>
      </div>
      {paragraphs.map((paragraph, index) => (
        <p key={index} className="whitespace-pre-line text-sm leading-6 text-ink-700">
          {paragraph.trim()}
        </p>
      ))}
    </section>
  );
}
