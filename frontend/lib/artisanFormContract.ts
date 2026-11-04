export const ARTISAN_FORM_CONTRACT_KEY = "formContract";
export const ARTISAN_FORM_CONTRACT = 2;

export const ARTISAN_REQUIRED_DATES = [
  { field: "dateOfBirth", label: "Date of birth", words: "date of birth" },
  { field: "craftStartDate", label: "Craft start date", words: "craft start date" }
] as const;

export type ArtisanRequiredDateField = (typeof ARTISAN_REQUIRED_DATES)[number]["field"];

export function missingArtisanDatesSentence(
  values: Partial<Record<ArtisanRequiredDateField, string | null | undefined>>
): string | null {
  const missing = ARTISAN_REQUIRED_DATES.filter(({ field }) => !values[field]?.trim()).map(({ words }) => words);
  return missing.length ? `Enter the ${missing.join(" and the ")}.` : null;
}

export const EXPERIENCE_NOT_STATED = "Not stated";

export function experienceMonthsLabel(months: number): string {
  return months === 1 ? "1 month" : `${months} months`;
}

export const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, month) => month);
