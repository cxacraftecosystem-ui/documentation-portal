/**
 * B8 — a question can say which artisan field answers it, so the interview fills it from the picked
 * artisan instead of guessing from the prompt's words.
 *
 * The keys are the server's `ARTISAN_FIELD_KEYS` (`backend/app/schemas/questionnaire.py`), spelled
 * as the Artisan columns; `e2e/web-capture-unit.spec.ts` holds the two lists to each other.
 */
import { craftIndex, peekCraftCatalogue } from "@/lib/craftCatalogue";
import type { Artisan } from "@/lib/types";
import { phoneOf } from "@/lib/standardValues";

export const ARTISAN_FIELD_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: "name", label: "Name" },
  { value: "localName", label: "Local name" },
  { value: "craft", label: "Craft" },
  { value: "place", label: "Place" },
  { value: "gender", label: "Gender" },
  { value: "phone", label: "Phone" },
  { value: "email", label: "Email" },
  { value: "dateOfBirth", label: "Date of birth" },
  { value: "experienceYears", label: "Experience (years)" },
  { value: "address", label: "Address" },
  { value: "interviewDate", label: "Today's date" },
  { value: "interviewer", label: "Interviewer" }
];

export function artisanFieldLabel(value: string | null | undefined): string | null {
  return ARTISAN_FIELD_OPTIONS.find((option) => option.value === value)?.label ?? null;
}

/** The master list's name for a craft id, when this browser holds the list. */
function craftNameFromList(craftId: string | null | undefined): string | null {
  const catalogue = craftId ? peekCraftCatalogue() : null;
  return (catalogue && craftId && craftIndex(catalogue).byId.get(craftId)?.name) || null;
}

type Interviewer = { name?: string | null; email?: string | null } | null | undefined;

/** What one mapped field reads for this artisan, or "" when the record holds nothing for it. */
export function artisanFieldValue(field: string, artisan: Artisan, interviewer: Interviewer, today = new Date()): string {
  switch (field) {
    case "name":
      return artisan.name ?? "";
    case "localName":
      return artisan.localName ?? "";
    case "craft":
      // The master list's name for the craft, when this device holds the list.
      return craftNameFromList(artisan.craftId) ?? artisan.craft?.name ?? "";
    case "place":
      return artisan.place ?? "";
    case "gender":
      return artisan.gender ?? "";
    case "phone":
      return phoneOf(artisan) ?? "";
    case "email":
      return artisan.email ?? "";
    case "dateOfBirth":
      return artisan.dateOfBirth ? artisan.dateOfBirth.slice(0, 10) : "";
    case "experienceYears":
      return artisan.experienceYears === null || artisan.experienceYears === undefined ? "" : String(artisan.experienceYears);
    case "address":
      return artisan.address ?? "";
    case "interviewDate":
      return today.toLocaleDateString("en-IN");
    case "interviewer":
      return interviewer?.name ?? interviewer?.email ?? "";
    default:
      return "";
  }
}

/**
 * The older keyword guess, kept ONLY for a respondent ("RESP") question nobody has mapped yet — an
 * instrument imported before the builder had the setting still fills as it always did.
 */
export function guessedArtisanField(prompt: string): string | null {
  const text = prompt.toLowerCase();
  if (text.includes("name")) return "name";
  if (text.includes("craft")) return "craft";
  if (text.includes("state") || text.includes("district") || text.includes("village")) return "place";
  if (text.includes("gender")) return "gender";
  if (text.includes("contact")) return "phone";
  if (text.includes("date")) return "interviewDate";
  if (text.includes("interviewer")) return "interviewer";
  return null;
}

type MappableQuestion = { id: string; prompt: string; sectionCode: string; artisanField?: string | null };

/**
 * The answers the picked artisan supplies: every MAPPED question, plus the respondent block's
 * unmapped ones through the keyword guess. "contact" falls back from phone to email, as before.
 */
export function artisanPrefill(
  questions: readonly MappableQuestion[],
  artisan: Artisan,
  interviewer: Interviewer,
  today = new Date()
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const question of questions) {
    const field = question.artisanField || (question.sectionCode === "RESP" ? guessedArtisanField(question.prompt) : null);
    if (!field) continue;
    let value = artisanFieldValue(field, artisan, interviewer, today);
    if (!value && field === "phone" && !question.artisanField) value = artisan.email ?? "";
    if (value) out[question.id] = value;
  }
  return out;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "Ramesh Chhipa · Craft questionnaire · 17 Oct" — the server's automatic title, drawn ahead of it. */
export function automaticInterviewTitle(
  artisanName: string | null | undefined,
  artisanCount: number,
  questionnaireTitle: string | null | undefined,
  when = new Date()
): string {
  // A fixed table, never `toLocaleString`: en-GB writes September "Sept" on newer ICU builds, and the
  // server's `strftime("%b")` writes "Sep".
  const month = MONTHS[when.getMonth()];
  const who = artisanName?.trim() ? `${artisanName.trim()}${artisanCount > 1 ? " and others" : ""}` : "";
  return [who, questionnaireTitle?.trim() ?? "", `${when.getDate()} ${month}`].filter(Boolean).join(" · ");
}
