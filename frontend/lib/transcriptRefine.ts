import { apiFetch } from "@/lib/api";
import { isContentAdmin } from "@/lib/permissions";
import type { MediaFile, User } from "@/lib/types";

export type RefineResponse = {
  available?: boolean;
  status?: string;
  refined?: string | null;
  message?: string | null;
  model?: string | null;
  translated?: boolean;
};

export type RefineOutcome = { kind: "refined"; text: string } | { kind: "message"; text: string };

export const REFINE_REMINDER_KEY = "transcript_refine_cost_ack";

export const REFINE_LABEL = "Refine transcript";
export const REFINE_TRANSLATE_LABEL = "Refine & translate";
export const REFINE_COST_TITLE = "This uses AI and costs extra";
export const REFINE_BUSY = "Refining with AI…";
export const REFINE_REPLACE_QUESTION = "Replace the current transcript with this refined version?";
export const REFINE_SAVED = "Saved as the transcript";

export function refineCostSentence(translate: boolean): string {
  return `Refining${translate ? " and translating" : ""} uses an AI model at a small cost. Continue?`;
}

export function refinedHeading(translated: boolean): string {
  return translated ? "Refined conversation (English)" : "Refined conversation";
}

export function refineOutcome(response: RefineResponse): RefineOutcome {
  const text = (response.refined ?? "").trim();
  if (text) return { kind: "refined", text };
  if (response.available === false) return { kind: "message", text: response.message || "AI refinement is not configured." };
  return { kind: "message", text: response.message || "No transcript content to refine." };
}

function uploaderId(media: MediaFile): string | null {
  const row = media as MediaFile & { uploadedById?: string | null };
  return row.uploadedById ?? media.uploadedBy?.id ?? null;
}

export function mayRefine(user: User | null | undefined, media: MediaFile): boolean {
  if (!user) return false;
  if (!(media.transcriptText ?? "").trim()) return false;
  return isContentAdmin(user) || uploaderId(media) === user.id;
}

export function refineTranscript(mediaId: string, translate: boolean): Promise<RefineResponse> {
  return apiFetch<RefineResponse>(`/media/${encodeURIComponent(mediaId)}/refine-transcript`, {
    method: "POST",
    body: JSON.stringify({ translate })
  });
}

export function saveRefinedTranscript(mediaId: string, text: string): Promise<MediaFile> {
  return apiFetch<MediaFile>(`/media/${encodeURIComponent(mediaId)}/transcript`, {
    method: "POST",
    body: JSON.stringify({ text })
  });
}

export function reminderSuppressed(): boolean {
  try {
    return window.localStorage.getItem(REFINE_REMINDER_KEY) === "1";
  } catch {
    return false;
  }
}

export function suppressReminder(): void {
  try {
    window.localStorage.setItem(REFINE_REMINDER_KEY, "1");
  } catch {
    return;
  }
}
