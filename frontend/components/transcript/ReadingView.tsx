"use client";

import { memo } from "react";

import type { CopyTrack, TranscriptTurn } from "@/lib/transcript/contract";
import { englishOf, hasEnglish } from "@/lib/transcript/markdown";
import { resolveColorKey, SPEAKER_TEXT, speakerStyle } from "@/lib/transcript/speakers";

type Block = { key: string; speakerKey: string | null; speaker: string; colorKey?: string; turns: TranscriptTurn[] };

/** Consecutive turns of one speaker, read as one paragraph of theirs. */
export function readingBlocks(turns: readonly TranscriptTurn[]): Block[] {
  const blocks: Block[] = [];
  for (const turn of turns) {
    const last = blocks[blocks.length - 1];
    if (last && last.speakerKey === turn.speakerKey && last.speaker === turn.speakerName) last.turns.push(turn);
    else blocks.push({ key: turn.id, speakerKey: turn.speakerKey, speaker: turn.speakerName, colorKey: turn.colorKey, turns: [turn] });
  }
  return blocks;
}

/**
 * READING MODE (owner, 1 Nov 2026): the conversation as one long transcript — speaker-labelled,
 * flowing paragraphs, no timestamps — in Original, English or Both. Both prints a speaker's words,
 * then their English beneath where there is a translation. The line being spoken is lit, and each
 * turn carries `data-follow-turn` so auto-follow can keep it in view. Turn text is what was said,
 * drawn as text and never parsed as markup.
 */
export const ReadingView = memo(function ReadingView({ turns, track, activeTurnId }: { turns: TranscriptTurn[]; track: CopyTrack; activeTurnId: string | null }) {
  return (
    <div className="grid gap-5 rounded-lg border border-line-200 bg-card px-4 py-4 sm:px-6 sm:py-5" data-reading={track}>
      {readingBlocks(turns).map((block) => {
        const colorKey = resolveColorKey(block.colorKey, block.speakerKey, block.speaker);
        const english = block.turns.filter(hasEnglish);
        return (
          <section key={block.key} className="grid gap-1" data-reading-block>
            <p className={`text-sm font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(colorKey)} data-speaker data-color-key={colorKey}>
              {block.speaker}
            </p>
            {track !== "english" ? (
              <p className="whitespace-pre-wrap text-[0.9375rem] leading-7 text-ink-900" lang={block.turns[0].language} data-reading-original>
                {block.turns.map((turn, index) => (
                  <span key={turn.id}>
                    {index > 0 ? " " : null}
                    <Spoken turn={turn} active={turn.id === activeTurnId} text={turn.text} />
                  </span>
                ))}
              </p>
            ) : null}
            {track === "english" ? (
              <p className="whitespace-pre-wrap text-[0.9375rem] leading-7 text-ink-900" lang="en" data-reading-english>
                {block.turns.map((turn, index) => (
                  <span key={turn.id}>
                    {index > 0 ? " " : null}
                    <Spoken turn={turn} active={turn.id === activeTurnId} text={englishOf(turn)} />
                  </span>
                ))}
              </p>
            ) : null}
            {track === "both" && english.length ? (
              <p className="whitespace-pre-wrap border-l-2 border-line-200 pl-3 text-sm italic leading-7 text-ink-700" lang="en" data-reading-english>
                {english.map((turn, index) => (
                  <span key={turn.id}>
                    {index > 0 ? " " : null}
                    {turn.translation.text}
                  </span>
                ))}
              </p>
            ) : null}
          </section>
        );
      })}
    </div>
  );
});

function Spoken({ turn, active, text }: { turn: TranscriptTurn; active: boolean; text: string }) {
  return (
    <span
      data-follow-turn={turn.id}
      data-active={active ? "true" : undefined}
      className={`scroll-mt-28 rounded-sm transition-colors ${active ? "bg-purple-100 text-purple-950 dark:bg-purple-900/60 dark:text-white" : ""}`}
    >
      {text}
    </span>
  );
}
