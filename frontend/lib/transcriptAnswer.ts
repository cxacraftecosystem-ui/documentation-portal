export const TRANSCRIPT_SOURCE = "TRANSCRIPT";

export function transcriptAnswerText(transcript: string | null | undefined): string {
  return (transcript ?? "")
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/^\s*-{3,}\s*$/gm, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function transcriptOffer(currentAnswer: string | null | undefined, transcript: string | null | undefined): string | null {
  if ((currentAnswer ?? "").trim()) return null;
  const text = transcriptAnswerText(transcript);
  return text || null;
}

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
