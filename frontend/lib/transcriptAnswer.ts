/**
 * A7 — "Use as answer": a transcribed clip's words, as an answer box's text.
 *
 * Transcripts are Markdown ("**Speaker 1:** …", `---` between turns) and an answer box is plain
 * text, so the bold markers and rules come out and nothing else changes. The server's
 * `transcript_answer_text` (`backend/app/api/routes/questionnaire.py`) is the same rule, and both run
 * `shared/transcript-answer-vectors.json`.
 */

/** Where a filled answer's words came from, as the API stores it on the response. */
export const TRANSCRIPT_SOURCE = "TRANSCRIPT";

export function transcriptAnswerText(transcript: string | null | undefined): string {
  return (transcript ?? "")
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/^\s*-{3,}\s*$/gm, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/**
 * The answer a clip offers to its question, or null when it must not be offered: no usable
 * transcript, or the box already holds a typed answer (a typed answer is never overwritten).
 */
export function transcriptOffer(currentAnswer: string | null | undefined, transcript: string | null | undefined): string | null {
  if ((currentAnswer ?? "").trim()) return null;
  const text = transcriptAnswerText(transcript);
  return text || null;
}

/**
 * Fill every empty answer from its question's transcript, in one step ("Use all transcripts").
 * Returns the new answers and the ids it filled; a question that already has words is left alone.
 */
export function applyTranscripts(
  answers: Record<string, string>,
  transcripts: Record<string, string>
): { answers: Record<string, string>; filled: string[] } {
  const next = { ...answers };
  const filled: string[] = [];
  for (const [questionId, transcript] of Object.entries(transcripts)) {
    const offer = transcriptOffer(next[questionId], transcript);
    if (offer === null) continue;
    next[questionId] = offer;
    filled.push(questionId);
  }
  return { answers: next, filled };
}
