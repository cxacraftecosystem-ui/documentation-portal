export function formatDate(value?: string | null) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric"
  }).format(new Date(value));
}

export function formatDateTime(value?: string | null) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

export function bytes(value: number | string | undefined | null) {
  if (value === undefined || value === null) return "-";
  const size = Number(value);
  if (!Number.isFinite(size)) return "-";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / (1024 * 1024)).toFixed(1)} MB`;
  return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

export function blankToNull(value: FormDataEntryValue | null) {
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
}

export function numberOrNull(value: FormDataEntryValue | null) {
  const raw = blankToNull(value);
  if (raw === null || raw === undefined || typeof raw !== "string") return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

/* ------------------------------------------------------------------------------------------------
 * Title casing — the web mirror of `backend/app/services/text_format.py` (and of the Android copy in
 * `android/.../data/TextFormat.kt`).
 *
 * WHY this lives in the client at all
 * -----------------------------------
 * The API title-cases every name-like column on WRITE (`services.records.clean_data`), so "kutch",
 * "KUTCH " and "Kutch" all land as one value and the exact-match craft lookup, the exports and the
 * .xlsx workbook stop splitting one village across three spellings. Android already SHOWS the
 * researcher what will be stored before they save; the web did not, so a web user typed
 * "kutch-bhuj", saw it unchanged, saved, and the value silently became "Kutch-Bhuj". Showing the
 * result up front is the difference between a rule and a surprise.
 *
 * It is a faithful port, not an approximation — a preview the server then contradicts is worse than
 * no preview at all:
 *
 * 1. Capitalise the first letter of every word.
 * 2. EXCEPT the conventional small words (SMALL_WORDS), which stay lowercase unless they are the
 *    FIRST or the LAST word ("salt of the earth" -> "Salt of the Earth").
 * 3. Intentional casing is never destroyed. A word is left EXACTLY as typed when it contains a digit
 *    ("PMV001", "A4" — identifiers, not prose) or an uppercase letter anywhere after its first
 *    character ("ABC", "McDonald", "O'Brien", "iPhone").
 * 3a. UNLESS the whole value is shouting — see `isShouting`. Rule 3 alone preserved "ZARI WORK"
 *    verbatim while "zari work" became "Zari Work", which is how one craft became two.
 * 4. Hyphens and slashes separate words ("kutch-bhuj" -> "Kutch-Bhuj"); apostrophes belong to the
 *    word ("don'ts" -> "Don'ts", never "Don'Ts"), except a single-letter particle before an
 *    apostrophe, which is a name prefix ("o'brien" -> "O'Brien").
 * 5. Non-Latin scripts are returned untouched — `localName` is stored in Devanagari and Gujarati,
 *    where "capitalising" is meaningless. The guard tests for Latin letters rather than listing
 *    scripts, so cased non-Latin scripts (Greek, Cyrillic) are left alone too.
 * 6. Idempotent: `titleCase(titleCase(x)) === titleCase(x)` for every input.
 *
 * Surrounding whitespace is stripped as part of the rule: "Bhuj " is the same name, and the stray
 * space is exactly what breaks the exact-match lookup.
 * ---------------------------------------------------------------------------------------------- */

/** The conventional English title-case exceptions, byte-for-byte the server's `SMALL_WORDS`. */
export const TITLE_CASE_SMALL_WORDS: ReadonlySet<string> = new Set([
  "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor", "of", "on", "or",
  "over", "per", "the", "to", "up", "via", "vs", "with"
]);

/**
 * The columns the API title-cases on write (`services.records.TITLE_CASE_FIELDS`). Deliberately does
 * NOT include notes/description/remarks/address/dos/donts/localName or any identifier — casing those
 * would damage meaning rather than tidy it. Exported so a form can ask "is this field one of them?"
 * instead of each form keeping its own list.
 */
export const TITLE_CASE_FIELDS: ReadonlySet<string> = new Set([
  "name", "craftName", "artisanName", "productName", "toolkitName", "englishName", "title",
  "place", "placeName", "village", "district", "state"
]);

// Straight and typographic apostrophes both occur — a phone keyboard produces U+2019 while the web
// form produces U+0027, and the same artisan's name must normalise identically from both.
const APOSTROPHES = "'’";

// Latin letters incl. Latin-1 Supplement + Latin Extended-A/B, matching the server's "A-Za-zÀ-ɏ".
const LATIN_RANGE = "A-Za-zÀ-ɏ";

// A "word" is a run of letters/digits/apostrophes. Everything else (spaces, hyphens, slashes,
// commas, brackets, ampersands, Devanagari, Gujarati) is a separator copied through verbatim —
// which is what makes hyphens and slashes behave as word boundaries without special-casing them.
const WORD_RE = new RegExp(`[0-9${LATIN_RANGE}${APOSTROPHES}]+`, "gu");
const LATIN_RE = new RegExp(`[${LATIN_RANGE}]`, "u");
// Python's str.isupper()/islower() are Unicode-aware, so the mirror tests general categories rather
// than an ASCII range. Titlecase letters (Lt) count as upper, exactly as they do in Python.
const UPPER_RE = /\p{Lu}|\p{Lt}/u;
const LOWER_RE = /\p{Ll}/u;
// Only ASCII digits can appear inside a word (see WORD_RE), so this is the server's isdigit() check.
const DIGIT_RE = /[0-9]/;

// The longest single all-caps token still read as an acronym rather than caps-lock. Real acronyms in
// this domain are short (GI, NGO, SEWA, KVIC); a longer all-caps single word — "BANDHANI" — is
// somebody's caps-lock. Only consulted for a ONE-word value.
const MAX_ACRONYM_LENGTH = 4;

/**
 * Upper-case the first letter, leaving the (already lower-cased) tail alone.
 *
 * A ONE-letter particle before an apostrophe is a name prefix (O', D', L'), never a contraction —
 * "don'ts" has its apostrophe at index 3 and is untouched by this.
 */
function capitaliseWord(word: string): string {
  if (word.length > 2 && APOSTROPHES.includes(word[1])) {
    return word[0].toUpperCase() + word[1] + word[2].toUpperCase() + word.slice(3);
  }
  return word[0].toUpperCase() + word.slice(1);
}

function caseWord(word: string, isFirst: boolean, isLast: boolean, shouting: boolean): string {
  // Devanagari / Gujarati / digits-only: there is no meaningful capitalisation to apply.
  if (!LATIN_RE.test(word)) return word;
  // An identifier, not prose: PMV001, A4, GI2019. Reshaping it would corrupt a real id.
  if (DIGIT_RE.test(word)) return word;
  // Deliberate interior capital: ABC, PMV, McDonald, O'Brien, iPhone. Left verbatim — which is also
  // what makes the whole function idempotent for everything it has already cased. Skipped when the
  // WHOLE value is shouting: there an all-caps word is caps-lock, not an acronym.
  if (!shouting && UPPER_RE.test(word.slice(1))) return word;
  const lowered = word.toLowerCase();
  if (TITLE_CASE_SMALL_WORDS.has(lowered) && !isFirst && !isLast) return lowered;
  return capitaliseWord(lowered);
}

/**
 * Is this whole value caps-lock rather than a deliberate acronym?
 *
 * An acronym is one token inside an otherwise normally-cased value; caps-lock shouts the ENTIRE
 * value. So a value is "shouting" when it has at least one cased letter, no lowercase letter
 * anywhere, and is either multi-word or a single word longer than the longest plausible acronym:
 * "ZARI WORK" -> "Zari Work" and "BANDHANI" -> "Bandhani", while "ABC", "SEWA" and "SEWA weaving"
 * keep their capitals. Without this, "ZARI WORK" and "zari work" became two separate crafts — the
 * exact duplication title-casing exists to prevent.
 */
function isShouting(text: string): boolean {
  if (LOWER_RE.test(text)) return false;
  if (!UPPER_RE.test(text)) return false;
  const words = text.match(WORD_RE) ?? [];
  if (words.length > 1) return true;
  return words.length === 1 && words[0].length > MAX_ACRONYM_LENGTH;
}

/** Title-case a name-like value exactly as the API will when it stores it. */
export function titleCase(value: string): string {
  const text = value.trim();
  // Nothing to do, or the whole value is in a script without case (an Indic `localName`).
  if (!text || !LATIN_RE.test(text)) return text;

  const matches = [...text.matchAll(WORD_RE)];
  if (!matches.length) return text;

  // Decided once for the whole value: an all-caps word means something different in "ZARI WORK"
  // than it does in "SEWA weaving".
  const shouting = isShouting(text);

  let out = "";
  let cursor = 0;
  const lastIndex = matches.length - 1;
  matches.forEach((match, index) => {
    const start = match.index ?? 0;
    out += text.slice(cursor, start);
    out += caseWord(match[0], index === 0, index === lastIndex, shouting);
    cursor = start + match[0].length;
  });
  return out + text.slice(cursor);
}

/**
 * The normalised value to SHOW the user, or null when there is nothing worth showing — the field is
 * empty, or what they typed is already exactly what will be stored. Callers render a "will be saved
 * as …" hint only for a non-null result, so a correctly-typed name adds no noise to the form.
 */
export function titleCasePreview(value: string | null | undefined): string | null {
  if (!value) return null;
  const normalised = titleCase(value);
  return normalised && normalised !== value ? normalised : null;
}
