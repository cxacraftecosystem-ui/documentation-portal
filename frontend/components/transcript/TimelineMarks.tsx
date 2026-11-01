"use client";

import { useCallback, useId, useRef, useState, type RefObject } from "react";

import type { TranscriptTurn } from "@/lib/transcript/contract";
import { clock } from "@/lib/transcript/format";
import { englishOf } from "@/lib/transcript/markdown";
import { resolveColorKey, SPEAKER_FILL, SPEAKER_TEXT, speakerStyle } from "@/lib/transcript/speakers";
import { ts } from "@/lib/transcript/strings";

const BUBBLE_MAX = 288;
const EDGE = 8;

/**
 * The marked turns as points on the audio timeline (owner, 1 Nov 2026).
 *
 * Each mark sits on the bar's own vertical centre (the timeline is exactly the range input's height
 * and the mark is centred in it), is filled in its speaker's colour, and seeks there on a press.
 * Hovering it, or reaching it with the keyboard, opens a chat bubble with what was said at that
 * moment: the speaker, then the turn's words, and its English when there is a translation. The
 * bubble is kept inside the player row, so it never runs off a phone's edge.
 */
export function TimelineMarks({
  turns,
  durationMs,
  onSeek,
  rowRef
}: {
  turns: TranscriptTurn[];
  durationMs: number;
  onSeek: (ms: number) => void;
  /** The player row: the bubble's horizontal bounds. */
  rowRef: RefObject<HTMLElement | null>;
}) {
  const [shown, setShown] = useState<{ turn: TranscriptTurn; left: number; width: number } | null>(null);
  const wrapRef = useRef<HTMLSpanElement | null>(null);
  const bubbleId = useId();

  const show = useCallback(
    (turn: TranscriptTurn, mark: HTMLElement) => {
      const wrap = wrapRef.current?.parentElement;
      const row = rowRef.current ?? wrap;
      if (!wrap || !row) return;
      const wrapBox = wrap.getBoundingClientRect();
      const rowBox = row.getBoundingClientRect();
      const markBox = mark.getBoundingClientRect();
      const width = Math.min(BUBBLE_MAX, rowBox.width - 2 * EDGE);
      const centre = markBox.left + markBox.width / 2 - wrapBox.left;
      const min = rowBox.left - wrapBox.left + EDGE;
      const max = rowBox.right - wrapBox.left - width - EDGE;
      setShown({ turn, width, left: Math.max(min, Math.min(centre - width / 2, max)) });
    },
    [rowRef]
  );
  const hide = useCallback(() => setShown(null), []);

  if (durationMs <= 0 || turns.length === 0) return <span ref={wrapRef} hidden />;

  const english = shown && shown.turn.translation.status !== "ALREADY_ENGLISH" && shown.turn.translation.text ? englishOf(shown.turn) : null;
  const shownKey = shown ? resolveColorKey(shown.turn.colorKey, shown.turn.speakerKey, shown.turn.speakerName) : null;

  return (
    <>
      <span ref={wrapRef} hidden />
      {turns.map((turn) => {
        const markKey = resolveColorKey(turn.colorKey, turn.speakerKey, turn.speakerName);
        const open = shown?.turn.id === turn.id;
        return (
          <button
            key={turn.id}
            type="button"
            className={`absolute top-1/2 z-[1] h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow-sm transition-transform before:absolute before:-inset-3 before:content-[''] hover:scale-125 focus-visible:scale-125 dark:border-ink-900 ${SPEAKER_FILL}`}
            style={{ ...speakerStyle(markKey), left: `${Math.min(100, (100 * turn.startMs) / durationMs)}%` }}
            aria-label={ts("jumpTo", { time: clock(turn.startMs) })}
            aria-describedby={open ? bubbleId : undefined}
            onClick={() => onSeek(turn.startMs)}
            onMouseEnter={(event) => show(turn, event.currentTarget)}
            onMouseLeave={hide}
            onFocus={(event) => show(turn, event.currentTarget)}
            onBlur={hide}
            onKeyDown={(event) => {
              if (event.key === "Escape" && open) {
                event.stopPropagation();
                hide();
              }
            }}
            data-timeline-mark={turn.id}
            data-color-key={markKey}
          />
        );
      })}
      {shown && shownKey ? (
        <span
          id={bubbleId}
          role="tooltip"
          className="pointer-events-none absolute top-full z-20 mt-3 grid gap-1 rounded-lg border border-line-200 bg-card px-3 py-2 text-left text-sm shadow-lg"
          style={{ left: shown.left, width: shown.width }}
          data-mark-bubble={shown.turn.id}
        >
          <span className="flex items-baseline justify-between gap-2">
            <span className={`font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(shownKey)} data-bubble-speaker>
              {shown.turn.speakerName}
            </span>
            <span className="font-mono text-xs text-ink-500">{clock(shown.turn.startMs)}</span>
          </span>
          <span className="line-clamp-4 text-ink-900" lang={shown.turn.language} data-bubble-original>
            {shown.turn.text}
          </span>
          {english ? (
            <span className="line-clamp-4 italic text-ink-700" lang="en" data-bubble-english>
              {english}
            </span>
          ) : null}
        </span>
      ) : null}
    </>
  );
}
