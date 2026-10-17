/**
 * The artisan form contract — the web half (owner decision, 2026-10-17).
 *
 * Both portals draw the same artisan form: Date of birth and Craft start date are required on every
 * new save, and experience is two optional boxes, years (0–90) and months (0–11, "Not stated" when
 * unanswered). `shared/artisan-form-contract.json` declares it once; this file, the server's
 * `services/artisan_form_contract.py` and Android's `data/ArtisanFormContract.kt` all run its
 * sentence vectors, in both repositories, and this file is identical in both.
 *
 * `formContract` goes in the BODY of every save the form makes. The outbox stores the body when it
 * queues, so an entry queued by an older bundle (no key) replays as the old shape the server still
 * accepts, while every save from this form is held to the rule server-side as well.
 */

/** The body key and its value. The server requires both dates when the value is 2 or more. */
export const ARTISAN_FORM_CONTRACT_KEY = "formContract";
export const ARTISAN_FORM_CONTRACT = 2;

/** The required dates, in form order, with the words the sentence uses. */
export const ARTISAN_REQUIRED_DATES = [
  { field: "dateOfBirth", label: "Date of birth", words: "date of birth" },
  { field: "craftStartDate", label: "Craft start date", words: "craft start date" }
] as const;

export type ArtisanRequiredDateField = (typeof ARTISAN_REQUIRED_DATES)[number]["field"];

/**
 * "Enter the date of birth and the craft start date." for whichever dates are empty, or null when
 * both are given. The server's refusal is the same sentence, word for word.
 */
export function missingArtisanDatesSentence(
  values: Partial<Record<ArtisanRequiredDateField, string | null | undefined>>
): string | null {
  const missing = ARTISAN_REQUIRED_DATES.filter(({ field }) => !values[field]?.trim()).map(({ words }) => words);
  return missing.length ? `Enter the ${missing.join(" and the ")}.` : null;
}

/** The empty option on the experience months box. */
export const EXPERIENCE_NOT_STATED = "Not stated";

/** "1 month", "6 months" — the rows of the months box. */
export function experienceMonthsLabel(months: number): string {
  return months === 1 ? "1 month" : `${months} months`;
}

/**
 * 0..11 and not 0..12: the months are a REMAINDER on top of the years, and the column carries
 * `CHECK ("experienceMonths" BETWEEN 0 AND 11)`.
 */
export const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, month) => month);
