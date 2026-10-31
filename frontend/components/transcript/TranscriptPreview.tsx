"use client";

import type { TranscriptPreview as Preview } from "@/lib/transcript/contract";
import { previewMarkdown } from "@/lib/transcript/preview";
import { resolveColorKey, SPEAKER_TEXT, speakerStyle } from "@/lib/transcript/speakers";

/**
 * A transcript in a list row, read rather than shown raw (owner, 1 Nov 2026): each speaker's name
 * (the mapped name when one is set) as a label in that speaker's colour, their words after it, and
 * one clean clamp at `lines` (two or three). The server's `preview` when the row carries one, else
 * the same preview built here from the stored Markdown. Never Markdown punctuation, never a clock,
 * never a "Transcript" prefix of its own. Draws nothing for a transcript with no words.
 */
export function TranscriptPreview({
  preview,
  text,
  lines = 3,
  className = ""
}: {
  preview?: Preview | null;
  text?: string | null;
  lines?: 2 | 3;
  className?: string;
}) {
  const shown = preview && preview.lines?.length ? preview : previewMarkdown(text);
  if (!shown) return null;
  return (
    <p
      className={`${lines === 2 ? "line-clamp-2" : "line-clamp-3"} text-sm leading-6 text-ink-700 ${className}`}
      title={shown.text}
      data-transcript-preview
    >
      {shown.lines.map((line, index) => {
        const key = resolveColorKey(line.colorKey, line.speakerKey, line.speaker);
        return (
          <span key={index}>
            {index > 0 ? " " : null}
            {line.speaker ? (
              <span className={`mr-1 font-semibold ${SPEAKER_TEXT}`} style={speakerStyle(key)} data-preview-speaker data-color-key={key}>
                {line.speaker}
              </span>
            ) : null}
            <span data-preview-text>{line.text}</span>
          </span>
        );
      })}
    </p>
  );
}
