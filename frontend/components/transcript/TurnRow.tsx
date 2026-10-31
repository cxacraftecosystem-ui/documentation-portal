"use client";

import { memo, useState } from "react";
import { Copy, Languages, Pencil } from "lucide-react";

import type { CopyTrack, TranscriptTurn, ViewTab } from "@/lib/transcript/contract";
import { clock } from "@/lib/transcript/format";
import { pieces } from "@/lib/transcript/karaoke";
import { ts } from "@/lib/transcript/strings";

import { Actions, Badge, ICON_BUTTON, MenuButton, PRIMARY, SECONDARY, TimeChip } from "./parts";

type Props = {
  turn: TranscriptTurn;
  tab: ViewTab;
  /** The audio is inside this turn. */
  active: boolean;
  /** The word being spoken, when `active`. */
  activeWord: number | null;
  selecting: boolean;
  selected: boolean;
  canEdit: boolean;
  onToggle: (turnId: string) => void;
  onSeek: (ms: number) => void;
  onCopy: (turn: TranscriptTurn, track: CopyTrack) => void;
  onSave: (turn: TranscriptTurn, change: { text?: string; english?: string }) => Promise<boolean>;
  onRetranslate: (turn: TranscriptTurn) => void;
  retranslating: boolean;
  /** This turn is a marked point on the audio timeline (off until ticked). */
  marked?: boolean;
  onMark?: (turnId: string, marked: boolean) => void;
};

const FIELD =
  "min-h-20 w-full rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900 focus:border-purple-600 focus:outline-hidden focus:ring-4 focus:ring-purple-600/15";

/**
 * One turn: **Speaker** · `00:03:12` (the clock plays from there), its copy menu, and its text in the
 * chosen view. The original is drawn piece by piece from the word timings: the spoken word is lit,
 * an unsure stretch is faintly underlined, and tapping a word plays from it. Turn text is plain text
 * (what was said), so it is drawn as text and never parsed as markup.
 */
export const TurnRow = memo(function TurnRow(props: Props) {
  const { turn, tab, active, activeWord, selecting, selected, canEdit } = props;
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(turn.text);
  const [english, setEnglish] = useState(turn.translation.text ?? "");
  const [saving, setSaving] = useState(false);
  const time = clock(turn.startMs);
  const speaker = turn.speakerName;
  const alreadyEnglish = turn.translation.status === "ALREADY_ENGLISH";

  function startEdit() {
    setText(turn.text);
    setEnglish(turn.translation.text ?? "");
    setEditing(true);
  }

  async function save() {
    const change: { text?: string; english?: string } = {};
    if (text.trim() !== turn.text) change.text = text.trim();
    if (!alreadyEnglish && english.trim() !== (turn.translation.text ?? "")) change.english = english.trim();
    if (!Object.keys(change).length) {
      setEditing(false);
      return;
    }
    setSaving(true);
    const ok = await props.onSave(turn, change);
    setSaving(false);
    if (ok) setEditing(false);
  }

  const originalPane = (
    <div className="min-w-0" lang={turn.language} data-pane="original">
      <OriginalText turn={turn} activeWord={active ? activeWord : null} onSeek={props.onSeek} />
      <span className="mt-1 inline-flex">
        <Badge tone={turn.textSource === "EDITED" ? "accent" : "neutral"}>{turn.textSource === "EDITED" ? ts("sourceEdited") : ts("sourceMachine")}</Badge>
      </span>
    </div>
  );

  const status = turn.translation.status;
  const englishPane = (
    <div
      className={`min-w-0 ${tab === "side" ? "border-l-2 pl-3 max-md:mt-2" : ""} ${active ? "border-purple-500" : "border-line-200"}`}
      lang="en"
      data-pane="english"
      data-active-english={active ? "true" : undefined}
    >
      {alreadyEnglish ? (
        <>
          <p className="whitespace-pre-wrap text-sm leading-7 text-ink-900">{turn.text}</p>
          <Badge>{ts("alreadyEnglish")}</Badge>
        </>
      ) : turn.translation.text ? (
        <>
          <p className="whitespace-pre-wrap text-sm leading-7 text-ink-900">{turn.translation.text}</p>
          <span className="mt-1 inline-flex flex-wrap gap-1.5">
            <Badge tone={turn.translation.source === "EDITED" ? "accent" : "neutral"}>
              {turn.translation.source === "EDITED" ? ts("sourceEdited") : ts("sourceMachine")}
            </Badge>
            {status === "STALE" ? <Badge tone="warn">{ts("translationStale")}</Badge> : null}
          </span>
        </>
      ) : (
        <p className="text-sm italic text-ink-500">{status === "FAILED" ? ts("translationFailed") : ts("translationPending")}</p>
      )}
      {canEdit && (status === "STALE" || status === "FAILED") ? (
        <div className="mt-2">
          <button type="button" className={SECONDARY} disabled={props.retranslating} onClick={() => props.onRetranslate(turn)} data-retranslate={turn.id}>
            <Languages className="h-4 w-4" aria-hidden />
            {props.retranslating ? ts("saving") : ts("retranslateTurn")}
          </button>
        </div>
      ) : null}
    </div>
  );

  return (
    <li
      id={`turn-${turn.id}`}
      data-turn-id={turn.id}
      data-active={active ? "true" : undefined}
      className={`scroll-mt-28 rounded-lg border px-3 py-3 transition-colors sm:px-4 ${
        active ? "border-purple-300 bg-purple-50/60 dark:border-purple-800 dark:bg-purple-950/30" : "border-transparent"
      } ${selected ? "ring-2 ring-purple-600/30" : ""}`}
    >
      <div className="flex items-start gap-2">
        {selecting ? (
          <input
            type="checkbox"
            className="mt-1.5 h-5 w-5 shrink-0 accent-purple-700"
            checked={selected}
            onChange={() => props.onToggle(turn.id)}
            aria-label={ts("selectTurn", { speaker, time })}
            data-select-turn={turn.id}
          />
        ) : null}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-semibold text-ink-900" data-speaker>
              {speaker}
            </span>
            <span className="text-ink-300" aria-hidden>
              ·
            </span>
            <TimeChip ms={turn.startMs} label={time} onSeek={props.onSeek} />
            {props.onMark ? (
              <label className="inline-flex min-h-9 cursor-pointer items-center gap-1.5 text-xs text-ink-500 max-sm:min-h-11" title={ts("markOnTimeline")}>
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-purple-700"
                  checked={Boolean(props.marked)}
                  onChange={(event) => props.onMark?.(turn.id, event.target.checked)}
                  data-mark-turn={turn.id}
                />
                <span className="max-sm:sr-only">{ts("markOnTimeline")}</span>
              </label>
            ) : null}
            <span className="ml-auto inline-flex items-center gap-0.5">
              {canEdit && !editing ? (
                <button type="button" className={ICON_BUTTON} onClick={startEdit} aria-label={ts("editTurnLabel", { speaker, time })} title={ts("editTurn")}>
                  <Pencil className="h-4 w-4" aria-hidden />
                </button>
              ) : null}
              <MenuButton
                ariaLabel={ts("copyTurnLabel", { speaker, time })}
                icon={<Copy className="h-4 w-4" aria-hidden />}
                className={ICON_BUTTON}
                align="end"
                testId={`copy-turn-${turn.id}`}
                items={[
                  { id: "original", label: ts("copyOriginal"), onSelect: () => props.onCopy(turn, "original") },
                  { id: "english", label: ts("copyEnglish"), onSelect: () => props.onCopy(turn, "english") },
                  { id: "both", label: ts("copyBoth"), onSelect: () => props.onCopy(turn, "both") }
                ]}
              />
            </span>
          </div>

          {editing ? (
            <div className="mt-2 grid gap-3" data-turn-editor>
              <label className="grid gap-1">
                <span className="text-xs font-medium uppercase tracking-wide text-ink-500">{ts("editOriginal")}</span>
                <textarea className={FIELD} value={text} lang={turn.language} onChange={(event) => setText(event.target.value)} />
              </label>
              {alreadyEnglish ? null : (
                <label className="grid gap-1">
                  <span className="text-xs font-medium uppercase tracking-wide text-ink-500">{ts("editEnglish")}</span>
                  <textarea className={FIELD} value={english} lang="en" onChange={(event) => setEnglish(event.target.value)} />
                </label>
              )}
              <Actions>
                <button type="button" className={SECONDARY} onClick={() => setEditing(false)} disabled={saving}>
                  {ts("cancel")}
                </button>
                <button type="button" className={PRIMARY} onClick={save} disabled={saving || !text.trim()}>
                  {saving ? ts("saving") : ts("save")}
                </button>
              </Actions>
            </div>
          ) : (
            <div className={`mt-1 ${tab === "side" ? "grid gap-x-6 md:grid-cols-2" : ""}`}>
              {tab === "english" ? englishPane : originalPane}
              {tab === "side" ? englishPane : null}
            </div>
          )}
        </div>
      </div>
    </li>
  );
});

/**
 * The original, in pieces. One click handler on the paragraph reads the word's start from
 * `data-ms`, so a long turn is not hundreds of stops in the tab order; the turn's clock is the
 * keyboard's way to the same moment.
 */
function OriginalText({ turn, activeWord, onSeek }: { turn: TranscriptTurn; activeWord: number | null; onSeek: (ms: number) => void }) {
  const parts = pieces(turn);
  return (
    <p
      className="whitespace-pre-wrap text-sm leading-7 text-ink-900"
      data-karaoke={turn.words.length ? "true" : undefined}
      onClick={(event) => {
        const target = (event.target as HTMLElement).closest<HTMLElement>("[data-ms]");
        if (target) onSeek(Number(target.dataset.ms));
      }}
    >
      {parts.map((piece, index) => {
        const word = piece.word === null ? null : turn.words[piece.word];
        const current = piece.word !== null && piece.word === activeWord;
        if (!word && !piece.lowConfidence) return <span key={index}>{piece.text}</span>;
        return (
          <span
            key={index}
            data-ms={word ? word.startMs : undefined}
            data-word={piece.word ?? undefined}
            data-current={current ? "true" : undefined}
            data-low-confidence={piece.lowConfidence ? "true" : undefined}
            title={piece.lowConfidence ? ts("lowConfidenceHint") : undefined}
            className={`rounded-sm transition-colors ${word ? "cursor-pointer" : ""} ${
              current ? "bg-purple-200 text-purple-950 dark:bg-purple-800 dark:text-white" : ""
            } ${piece.lowConfidence ? "underline decoration-ink-300 decoration-dotted decoration-1 underline-offset-4" : ""}`}
          >
            {piece.text}
          </span>
        );
      })}
    </p>
  );
}
