/**
 * The shared standard values (convergence point 10): phone, language, gender, tool tradition and
 * material family. The web port of `backend/app/services/standard_values.py`.
 *
 * `standard-values.json` beside this file is a byte copy of `backend/app/data/standard_values.json`,
 * and both, with this file, are identical in the designer portal and the documentation portal.
 * `e2e/standard-values-parity-unit.spec.ts` runs `shared/standard-values-vectors.json` through this
 * file, the server runs the same table, and so does Android. Change all of them together.
 *
 * Stored: phone digits in `phone` and the code in `phoneCountryCode` (+91 by default); a language
 * code ("bn"), shown by name ("Bengali"); the gender label; the TraditionType code; a material
 * family code.
 */
import values from "./standard-values.json";

type Entry = { code: string; label?: string; name?: string; aliases: string[] };

export type StandardOption = { value: string; label: string };

export const PHONE_MESSAGE_INDIA = "Enter a 10-digit number for +91.";
export const COUNTRY_CODE_MESSAGE = "Choose a country code, such as +91.";
export const GENDER_MESSAGE = "Choose a gender from the list.";
export const LANGUAGE_MESSAGE = "Choose a language from the list.";
export const TRADITION_MESSAGE = "Choose a tradition type from the list.";
export const MATERIAL_MESSAGE = "Choose a material type from the list.";

export const DEFAULT_COUNTRY_CODE: string = values.phone.defaultCountryCode;

// Spelled out, never `\s`: the server and Android must match the same characters.
const FOLD_SEPARATORS = /[ \t\r\n ‐-―_./,()[\]-]+/g;
const PHONE_SEPARATORS = /[ \t\r\n ‐-―().-]+/g;
const COUNTRY_CODE = /^\+[1-9][0-9]{0,3}$/;
const LANGUAGE_TAG = /^([a-z]{2,3})[-_][a-z]{2}$/;

/** Lower case, separators collapsed to one space, trimmed. */
export function fold(text: unknown): string {
  if (text === null || text === undefined) return "";
  return String(text).toLowerCase().replace(FOLD_SEPARATORS, " ").trim();
}

function blank(value: unknown): boolean {
  return value === null || value === undefined || !String(value).trim();
}

function buildIndex(entries: Entry[], stored: (entry: Entry) => string): Map<string, string> {
  const index = new Map<string, string>();
  for (const entry of entries) {
    for (const spelling of [entry.code, entry.label ?? entry.name ?? "", ...entry.aliases]) {
      index.set(fold(spelling), stored(entry));
    }
  }
  return index;
}

const LANGUAGES = values.languages as Entry[];
const GENDERS = values.genders as Entry[];
const TRADITIONS = values.traditionTypes as Entry[];
const MATERIALS = values.materialFamilies as Entry[];

const LANGUAGE_INDEX = buildIndex(LANGUAGES, (entry) => entry.code);
const GENDER_INDEX = buildIndex(GENDERS, (entry) => entry.label ?? entry.code);
const TRADITION_INDEX = buildIndex(TRADITIONS, (entry) => entry.code);
const MATERIAL_INDEX = buildIndex(MATERIALS, (entry) => entry.code);

export type Normalised = { value: string | null; error?: undefined } | { value?: undefined; error: string };

function normalise(index: Map<string, string>, message: string, value: unknown, language = false): Normalised {
  if (blank(value)) return { value: null };
  let found = index.get(fold(value));
  if (found === undefined && language) {
    const tag = LANGUAGE_TAG.exec(String(value).trim().toLowerCase());
    if (tag) found = index.get(tag[1]);
  }
  return found === undefined ? { error: message } : { value: found };
}

export const normalizeLanguage = (value: unknown) => normalise(LANGUAGE_INDEX, LANGUAGE_MESSAGE, value, true);
export const normalizeGender = (value: unknown) => normalise(GENDER_INDEX, GENDER_MESSAGE, value);
export const normalizeTradition = (value: unknown) => normalise(TRADITION_INDEX, TRADITION_MESSAGE, value);
export const normalizeMaterialFamily = (value: unknown) => normalise(MATERIAL_INDEX, MATERIAL_MESSAGE, value);

/** The name a stored language code is shown by; a legacy free-text value is shown as stored. */
export function languageName(code: string | null | undefined): string | null {
  if (blank(code)) return null;
  return LANGUAGES.find((entry) => entry.code === code)?.name ?? String(code);
}

/** The Language dropdown: code values, name labels. A stored legacy value is kept, first. */
export function languageOptions(current?: string | null): StandardOption[] {
  const base = LANGUAGES.map((entry) => ({ value: entry.code, label: entry.name ?? entry.code }));
  if (blank(current) || base.some((option) => option.value === current)) return base;
  return [{ value: String(current), label: String(current) }, ...base];
}

export const GENDER_OPTIONS: readonly string[] = GENDERS.map((entry) => entry.label ?? entry.code);

export const TRADITION_OPTIONS: readonly StandardOption[] = TRADITIONS.map((entry) => ({
  value: entry.code,
  label: entry.label ?? entry.code
}));

export const MATERIAL_FAMILY_OPTIONS: readonly StandardOption[] = MATERIALS.map((entry) => ({
  value: entry.code,
  label: entry.label ?? entry.code
}));

export function traditionLabel(code: string | null | undefined): string | null {
  return TRADITIONS.find((entry) => entry.code === code)?.label ?? (code || null);
}

export function materialFamilyLabel(code: string | null | undefined): string | null {
  return MATERIALS.find((entry) => entry.code === code)?.label ?? null;
}

/** The one family a free description names, or null when it names none or several. */
export function materialFamilyFromText(text: unknown): string | null {
  const folded = ` ${fold(text)} `;
  if (!folded.trim()) return null;
  const found = new Set<string>();
  for (const [spelling, code] of MATERIAL_INDEX) {
    if (code === "OTHER" || !spelling) continue;
    if (folded.includes(` ${spelling} `)) found.add(code);
  }
  return found.size === 1 ? [...found][0] : null;
}

// ── Phone ─────────────────────────────────────────────────────────────────────────────────────

function digitWindow(code: string): [number, number] {
  const fixed = (values.phone.nationalDigits as Record<string, number[]>)[code];
  if (fixed) return [fixed[0], fixed[1]];
  const [low, high] = values.phone.otherNationalDigits;
  return [low, Math.min(high, values.phone.e164MaxDigits - (code.length - 1))];
}

export function phoneMessage(code: string): string {
  if (code === "+91") return PHONE_MESSAGE_INDIA;
  const [low, high] = digitWindow(code);
  return `Enter a valid phone number (${low}–${high} digits).`;
}

export type PhoneResult =
  | { code: string | null; digits: string | null; error?: undefined }
  | { code?: undefined; digits?: undefined; error: string };

export function normalizeCountryCode(value: unknown): { code: string } | { error: string } {
  if (blank(value)) return { code: DEFAULT_COUNTRY_CODE };
  let text = String(value).replace(PHONE_SEPARATORS, "");
  if (text.startsWith("00")) text = `+${text.slice(2)}`;
  else if (!text.startsWith("+")) text = `+${text}`;
  return COUNTRY_CODE.test(text) ? { code: text } : { error: COUNTRY_CODE_MESSAGE };
}

/** `{code, digits}` for a number typed in any common form; both null when blank. */
export function normalizePhone(countryCode: unknown, number: unknown): PhoneResult {
  if (blank(number)) return { code: null, digits: null };
  const raw = String(number).trim();
  const parsed = normalizeCountryCode(countryCode);
  if ("error" in parsed) return { error: parsed.error };
  let code = parsed.code;
  let compact = raw.replace(PHONE_SEPARATORS, "");
  if (compact.startsWith("00") && compact.length > 2 && compact[2] !== "0") compact = `+${compact.slice(2)}`;
  if (compact.startsWith("+")) {
    if (compact.startsWith(code)) compact = compact.slice(code.length);
    else if (compact.startsWith("+91")) {
      code = "+91";
      compact = compact.slice(3);
    } else {
      const head = raw.split(/[ \t ‐-―-]/);
      const embedded = head.length >= 2 ? head[0].replace(PHONE_SEPARATORS, "") : "";
      if (!COUNTRY_CODE.test(embedded)) return { error: phoneMessage(code) };
      code = embedded;
      compact = compact.slice(embedded.length);
    }
  }
  if (!/^[0-9]+$/.test(compact)) return { error: phoneMessage(code) };
  let digits = compact;
  const codeDigits = code.slice(1);
  const [low, high] = digitWindow(code);
  if (digits.length > high && digits.startsWith(codeDigits)) digits = digits.slice(codeDigits.length);
  if (digits.length > low && digits.startsWith("0")) digits = digits.slice(1);
  if (digits.length < low || digits.length > high) return { error: phoneMessage(code) };
  return { code, digits };
}

/** "+91 98765 43210". A legacy value that does not normalise is shown as stored. */
export function formatPhone(countryCode: unknown, number: unknown): string | null {
  if (blank(number)) return null;
  const result = normalizePhone(countryCode, number);
  if (result.error !== undefined || !result.digits) return String(number).trim();
  if (result.code === "+91" && result.digits.length === 10) {
    return `${result.code} ${result.digits.slice(0, 5)} ${result.digits.slice(5)}`;
  }
  return `${result.code} ${result.digits}`;
}

/** A record's phone, shown: both columns together. */
export function phoneOf(record: { phone?: string | null; phoneCountryCode?: string | null } | null | undefined): string | null {
  return record ? formatPhone(record.phoneCountryCode, record.phone) : null;
}
