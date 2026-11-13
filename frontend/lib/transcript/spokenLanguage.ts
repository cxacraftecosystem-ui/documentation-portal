import { apiFetch } from "@/lib/api";

import type { SpokenLanguageSuggestion, Transcript } from "./contract";
import SPOKEN from "./spokenLanguages.json";
import { ts } from "./strings";

export const SPOKEN_AUTO = SPOKEN.auto;
export const SPOKEN_MIXED = SPOKEN.mixed;
export const UNCERTAIN_BELOW = SPOKEN.uncertainBelow;

export type SpokenLanguageOption = { value: string; label: string };

const LABELS: ReadonlyMap<string, string> = new Map(SPOKEN.languages.map((language) => [language.code, language.label]));

export const SPOKEN_LANGUAGE_OPTIONS: readonly SpokenLanguageOption[] = [
  { value: SPOKEN_AUTO, label: ts("spokenLanguageAuto") },
  { value: SPOKEN_MIXED, label: ts("spokenLanguageMixed") },
  ...SPOKEN.languages.filter((language) => language.featured).map((language) => ({ value: language.code, label: language.label })),
  ...SPOKEN.languages.filter((language) => !language.featured).map((language) => ({ value: language.code, label: language.label }))
];

export function isSpokenChoice(value: unknown): boolean {
  return typeof value === "string" && (value === SPOKEN_AUTO || value === SPOKEN_MIXED || LABELS.has(value));
}

export function spokenChoiceOf(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const key = value.trim().toLowerCase();
  if (isSpokenChoice(key)) return key;
  const short = key.replace(/_/g, "-").split("-")[0];
  if (LABELS.has(short)) return short;
  for (const [code, label] of LABELS) if (label.toLowerCase() === key) return code;
  return null;
}

export function spokenLanguageLabel(value: string | null | undefined): string {
  if (value === SPOKEN_MIXED) return ts("spokenLanguageMixed");
  if (value && LABELS.has(value)) return LABELS.get(value) as string;
  return ts("spokenLanguageAuto");
}

export function suggestionHint(suggestion: SpokenLanguageSuggestion["suggested"]): string | null {
  if (!suggestion) return null;
  return suggestion.source === "artisan" ? ts("spokenLanguageFromArtisan") : ts("spokenLanguageFromResearcher");
}

export function defaultSpokenLanguage(
  suggestion: SpokenLanguageSuggestion["suggested"],
  ...fallbacks: (string | null | undefined)[]
): string {
  const suggested = spokenChoiceOf(suggestion?.code);
  if (suggested) return suggested;
  for (const fallback of fallbacks) {
    const found = spokenChoiceOf(fallback);
    if (found) return found;
  }
  return SPOKEN_AUTO;
}

export function languageSentence(transcript: Pick<Transcript, "spokenLanguage">): string | null {
  const spoken = transcript.spokenLanguage;
  if (!spoken) return null;
  if (spoken.requested && spoken.requested !== SPOKEN_AUTO) {
    return ts("transcribedAs", { language: spoken.requestedLabel ?? spokenLanguageLabel(spoken.requested) });
  }
  if (spoken.detectedLabel) return ts("detectedLanguage", { language: spoken.detectedLabel });
  return null;
}

export function languageUncertain(transcript: Pick<Transcript, "spokenLanguage" | "version">): boolean {
  const spoken = transcript.spokenLanguage;
  if (!spoken || transcript.version === null) return false;
  if (spoken.uncertain) return true;
  return typeof spoken.probability === "number" && spoken.probability < UNCERTAIN_BELOW;
}

export function retranscribeEstimate(transcript: Pick<Transcript, "spokenLanguage">): string[] {
  const spoken = transcript.spokenLanguage;
  if (!spoken) return [];
  const minutes = Math.max(0.1, spoken.estimateMinutes);
  const lines = [ts("retranscribeMinutes", { minutes: minutes < 10 ? minutes.toFixed(1) : String(Math.round(minutes)) })];
  if (typeof spoken.estimateUsd === "number") lines.push(ts("retranscribeCost", { usd: spoken.estimateUsd.toFixed(2) }));
  return lines;
}

export function suggestionPath(artisanIds: readonly string[]): string {
  const ids = [...new Set(artisanIds.filter(Boolean))].slice(0, 50);
  return ids.length ? `/transcripts/spoken-language?artisanIds=${encodeURIComponent(ids.join(","))}` : "/transcripts/spoken-language";
}

export const fetchSpokenLanguageSuggestion = (artisanIds: readonly string[]) =>
  apiFetch<SpokenLanguageSuggestion>(suggestionPath(artisanIds));

export const retranscribe = (mediaId: string, spokenLanguage: string) =>
  apiFetch<Transcript>(`/transcripts/${encodeURIComponent(mediaId)}/retranscribe`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ spokenLanguage })
  });

const SESSION_KEY = "spoken_language_session:";

export function rememberSessionSpokenLanguage(sessionKey: string, value: string): void {
  try {
    window.localStorage.setItem(SESSION_KEY + sessionKey, value);
  } catch {
    return;
  }
}

export function sessionSpokenLanguage(...sessionKeys: (string | null | undefined)[]): string | null {
  for (const key of sessionKeys) {
    if (!key) continue;
    try {
      const found = spokenChoiceOf(window.localStorage.getItem(SESSION_KEY + key));
      if (found) return found;
    } catch {
      return null;
    }
  }
  return null;
}
