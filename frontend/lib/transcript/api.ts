import { API_BASE, ApiError, apiFetch, describeApiDetail } from "@/lib/api";

import type {
  AskAnswer,
  AutoDraftResult,
  BudgetMeter,
  CopyTrack,
  DownloadFormat,
  GlossaryTerm,
  QuoteCard,
  SpeakerMapping,
  SpeakerSuggestion,
  Transcript,
  TranscriptTurn
} from "./contract";

/**
 * Every transcript route the web calls (`shared/transcript-contract.json`, "routes"), as `apiFetch`
 * takes them: without the `/api` prefix.
 */
const base = (mediaId: string) => `/transcripts/${encodeURIComponent(mediaId)}`;

export const TRANSCRIPT_ROUTES = {
  view: base,
  speakers: (mediaId: string) => `${base(mediaId)}/speakers`,
  suggestions: (mediaId: string) => `${base(mediaId)}/speakers/suggestions`,
  turn: (mediaId: string, turnId: string) => `${base(mediaId)}/turns/${encodeURIComponent(turnId)}`,
  retranslate: (mediaId: string, turnId: string) => `${base(mediaId)}/turns/${encodeURIComponent(turnId)}/retranslate`,
  retry: (mediaId: string) => `${base(mediaId)}/retry`,
  transcribeNow: (mediaId: string) => `${base(mediaId)}/transcribe-now`,
  download: (mediaId: string, format: DownloadFormat, track: CopyTrack, timestamps = true) =>
    `${base(mediaId)}/download?format=${format}&track=${(format === "srt" || format === "vtt") && track === "both" ? "original" : track}${timestamps ? "" : "&timestamps=false"}`,
  ask: (mediaId: string) => `${base(mediaId)}/ask`,
  autodraft: (mediaId: string) => `${base(mediaId)}/autodraft`,
  quoteCard: (mediaId: string) => `${base(mediaId)}/quote-card`,
  glossary: (craftId: string, cursor?: string | null) =>
    `/transcripts/glossary?craftId=${encodeURIComponent(craftId)}&limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
  budget: "/transcripts/budget"
} as const;

/** The contract's error code (`{detail: {code, message}}`), when the server sent one. */
export function errorCode(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const detail = (error.payload as { detail?: { code?: unknown } } | null)?.detail;
  return detail && typeof detail === "object" && typeof detail.code === "string" ? detail.code : null;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body)
});

/** The current version, or null while the pipeline is off for this recording (404 NOT_FOUND). */
export async function fetchTranscript(mediaId: string): Promise<Transcript | null> {
  try {
    return await apiFetch<Transcript>(TRANSCRIPT_ROUTES.view(mediaId));
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

export const editTurn = (mediaId: string, turn: TranscriptTurn, change: { text?: string; english?: string }) =>
  apiFetch<TranscriptTurn>(TRANSCRIPT_ROUTES.turn(mediaId, turn.id), json("PATCH", { baseRevision: turn.revision, ...change }));

export const retranslateTurn = (mediaId: string, turnId: string) =>
  apiFetch<TranscriptTurn>(TRANSCRIPT_ROUTES.retranslate(mediaId, turnId), { method: "POST" });

export const mapSpeakers = (mediaId: string, baseRevision: number, mappings: SpeakerMapping[]) =>
  apiFetch<Transcript>(TRANSCRIPT_ROUTES.speakers(mediaId), json("PUT", { baseRevision, mappings }));

export const speakerSuggestions = (mediaId: string) => apiFetch<{ items: SpeakerSuggestion[] }>(TRANSCRIPT_ROUTES.suggestions(mediaId));

export const retryTranscript = (mediaId: string) => apiFetch<Transcript>(TRANSCRIPT_ROUTES.retry(mediaId), { method: "POST" });

export const transcribeNow = (mediaId: string) => apiFetch<Transcript>(TRANSCRIPT_ROUTES.transcribeNow(mediaId), { method: "POST" });

export const askTranscript = (mediaId: string, question: string) => apiFetch<AskAnswer>(TRANSCRIPT_ROUTES.ask(mediaId), json("POST", { question }));

export const autodraft = (mediaId: string) => apiFetch<AutoDraftResult>(TRANSCRIPT_ROUTES.autodraft(mediaId), { method: "POST" });

export const makeQuoteCard = (mediaId: string, turnIds: string[]) => apiFetch<QuoteCard>(TRANSCRIPT_ROUTES.quoteCard(mediaId), json("POST", { turnIds }));

export const fetchGlossary = (craftId: string, cursor?: string | null) =>
  apiFetch<{ items: GlossaryTerm[]; nextCursor: string | null }>(TRANSCRIPT_ROUTES.glossary(craftId, cursor));

export const fetchBudget = () => apiFetch<BudgetMeter>(TRANSCRIPT_ROUTES.budget);

/** GET a file the API builds (a download, a clip); same-origin, so the session cookie goes with it. */
export async function fetchTranscriptFile(path: string, fallbackName: string): Promise<{ blob: Blob; fileName: string }> {
  const url = /^https?:/i.test(path) || path.startsWith("/api/") ? path : `${API_BASE}/api${path}`;
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) {
    const type = response.headers.get("content-type") ?? "";
    const payload: unknown = type.includes("application/json") ? await response.json().catch(() => null) : null;
    const detail = payload && typeof payload === "object" && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    throw new ApiError(response.status, describeApiDetail(detail, "The download didn't finish. Try again."), payload, response.headers);
  }
  const disposition = response.headers.get("content-disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  return { blob: await response.blob(), fileName: encoded ? decodeURIComponent(encoded) : plain ?? fallbackName };
}

/** Hand a blob to the browser as a download. The URL is released a minute later, not at once (Safari). */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
