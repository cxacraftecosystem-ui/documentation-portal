import { LanguageAliases, Languages } from "@/lib/questionnaire/enums";

export const LANGUAGES = Languages;

export function languageLabel(code: string | null | undefined, other?: string | null): string {
  if (!code) return "";
  if (code === "other") return other?.trim() || "Other";
  return LANGUAGES.find((l) => l.code === code)?.label ?? code;
}

export function languageFromAlias(text: string): string | null {
  const trimmed = text.trim();
  return LanguageAliases[trimmed] ?? LANGUAGES.find((l) => l.label.toLowerCase() === trimmed.toLowerCase())?.code ?? null;
}
