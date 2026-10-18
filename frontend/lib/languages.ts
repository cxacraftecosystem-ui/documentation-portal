/**
 * The interview language list (DESIGN §4.2): codes stored, English labels shown, "Bengali" the only
 * label for `bn`. The list is the generated one from `shared/languages.json` (owned by
 * `feature/s-standardise`, copied verbatim from `feature/q-backend`).
 */

import { LanguageAliases, Languages } from "@/lib/shared/enums";

export const LANGUAGES = Languages;

export function languageLabel(code: string | null | undefined, other?: string | null): string {
  if (!code) return "";
  if (code === "other") return other?.trim() || "Other";
  return LANGUAGES.find((l) => l.code === code)?.label ?? code;
}

/** "Bangla" / "hindi" typed by hand → the stored code, where the shared aliases know it. */
export function languageFromAlias(text: string): string | null {
  const trimmed = text.trim();
  return LanguageAliases[trimmed] ?? LANGUAGES.find((l) => l.label.toLowerCase() === trimmed.toLowerCase())?.code ?? null;
}
