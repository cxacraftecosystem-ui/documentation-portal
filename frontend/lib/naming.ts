/**
 * THE ONE NAMING HELPER FOR THE WEB (owner correction a, 18 Oct 2026).
 *
 * Every generated name the web shows or proposes — a session title preview, an export's download
 * name — is built here and nowhere else. The convention is `docs/conventions/NAMING_2026-10-18.md`;
 * the exact rules, and the golden rows all three runtimes reproduce byte for byte, are
 * `shared/session-title.json` (copied verbatim from `feature/q-backend`). This module is a line-by-
 * line port of `backend/app/services/naming.py`; `e2e/naming-unit.spec.ts` runs every golden row.
 *
 *   title      Entity_Date_Place_Craft_Subject                                  (<= 120, any script)
 *   file name  FieldRepository_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
 *                                                                               (<= 150, ASCII)
 *
 * The server is the authority for a stored title and for an uploaded clip's name: the web previews a
 * title before Start and never names an uploaded questionnaire file (downloads use `downloadName`).
 */

import anyAscii from "any-ascii";

export const PORTAL = "FieldRepository";
export const TITLE_MAX = 120;
export const FILE_MAX = 150;
export const SUBJECT_MIN = 40;

const IST_OFFSET_MINUTES = 330;
const ESTIMATED_SOURCES: ReadonlySet<string> = new Set(["SESSION_DATE", "UPLOAD_TIME", "UPLOAD_TIME_OUT_OF_WINDOW"]);
const RESERVED_STEMS: ReadonlySet<string> = new Set([
  "CON",
  "PRN",
  "AUX",
  "NUL",
  ...Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`)
]);
const ZW = new Set(["‌", "‍"]);
const DISPLAY_CODE = /^([A-Za-z]+)[\s.-]*0*(\d+)$/;
const INDIC_BLOCKS = [0x0900, 0x0980, 0x0a00, 0x0a80, 0x0b00, 0x0b80, 0x0c00, 0x0c80, 0x0d00];
const WORD_CHAR = /[\p{L}\p{M}\p{Nd}]/u;
const DIGIT = /\p{Nd}/u;

/** Length in code points, as Python counts it. */
const cpLength = (value: string) => [...value].length;

export function isEstimatedSource(source: string | null | undefined): boolean {
  return ESTIMATED_SOURCES.has((source ?? "").toUpperCase());
}

function indic(cp: number): [number, number] | null {
  for (const base of INDIC_BLOCKS) if (cp >= base && cp < base + 0x80) return [base, cp - base];
  return null;
}
const isConsonant = (off: number) => (off >= 0x15 && off <= 0x39) || (off >= 0x58 && off <= 0x5f);
const isVowelSignOrVirama = (off: number) => (off >= 0x3e && off <= 0x4d) || (off >= 0x55 && off <= 0x57) || off === 0x62 || off === 0x63;
const isWordLetter = (off: number) => isConsonant(off) || (off >= 0x05 && off <= 0x14) || (off >= 0x01 && off <= 0x03) || off === 0x60 || off === 0x61;

/** Drop every nukta, then insert the Indic inherent "a" that anyascii leaves out (shared rules). */
export function indicInherentVowels(value: string): string {
  const chars = [...value].filter((ch) => indic(ch.codePointAt(0)!)?.[1] !== 0x3c);
  const out: string[] = [];
  chars.forEach((ch, i) => {
    out.push(ch);
    const info = indic(ch.codePointAt(0)!);
    if (info && isConsonant(info[1]) && i + 1 < chars.length) {
      const next = indic(chars[i + 1].codePointAt(0)!);
      if (next && next[0] === info[0] && !isVowelSignOrVirama(next[1]) && isWordLetter(next[1])) out.push("a");
    }
  });
  return out.join("");
}

/** Transliterate to ASCII: the inherent-vowel pass, then any-ascii (the tables Python anyascii uses). */
export function toAscii(value: string | null | undefined): string {
  if (!value) return "";
  return anyAscii(indicInherentVowels(value.normalize("NFC")));
}

/** Words end at every character that is not a letter, a mark, a decimal digit, ZWNJ or ZWJ. */
export function splitWords(value: string | null | undefined): string[] {
  const words: string[] = [];
  let current = "";
  for (const ch of String(value ?? "").normalize("NFC")) {
    if (ZW.has(ch) || WORD_CHAR.test(ch)) current += ch;
    else if (current) {
      words.push(current);
      current = "";
    }
  }
  if (current) words.push(current);
  return words;
}

/** Title-Case one word; a word with a digit, or a 2–3 letter upper-case Latin word, keeps its case. */
export function titleCaseWord(word: string): string {
  if ([...word].some((ch) => DIGIT.test(ch))) return word;
  const n = cpLength(word);
  if (n >= 2 && n <= 3 && /^[A-Z]+$/.test(word)) return word;
  const [first, ...rest] = [...word];
  if (first && first.toUpperCase() !== first.toLowerCase()) return first.toUpperCase() + rest.join("").toLowerCase();
  return word;
}

function fieldWords(value: string | null | undefined, asciiOnly: boolean): string[] {
  const text = asciiOnly ? toAscii(value) : String(value ?? "");
  let words = splitWords(text);
  if (asciiOnly) words = words.map((w) => w.replace(/[^A-Za-z0-9]/g, "")).filter(Boolean);
  return words.map(titleCaseWord);
}

const joinField = (words: string[]) => words.join("-");
const fields = (...parts: (string | null | undefined)[]) => parts.filter(Boolean).join("_");

export type Person = { name?: string | null; localName?: string | null; craft?: string | null };
export type TitleWorkshop = { locality?: string | null; district?: string | null; state?: string | null; craft?: string | null };
export type TitleInput = {
  sessionDate?: string | null;
  place?: string | null;
  workshop?: TitleWorkshop | null;
  participants?: Person[];
  /** Override (designer portal: "Workshop"). */
  entity?: string | null;
  /** Override (a design workshop's code). */
  subject?: string | null;
};

function same(a: string | null | undefined, b: string | null | undefined): boolean {
  return splitWords(a).join(" ").toLowerCase() === splitWords(b).join(" ").toLowerCase();
}

function placeSource(inp: TitleInput): string | null {
  if ((inp.place ?? "").trim()) return inp.place!;
  const ws = inp.workshop ?? {};
  const locality = (ws.locality ?? "").trim();
  const district = (ws.district ?? "").trim();
  const state = (ws.state ?? "").trim();
  if (locality && district && !same(locality, district)) return `${locality} ${district}`;
  return locality || district || state || null;
}

function craftSource(inp: TitleInput): string | null {
  const ws = inp.workshop ?? {};
  if ((ws.craft ?? "").trim()) return ws.craft!;
  const crafts = (inp.participants ?? []).map((p) => (p.craft ?? "").trim());
  if (crafts.length && crafts.every(Boolean) && crafts.every((c) => same(c, crafts[0]))) return crafts[0];
  return null;
}

export function sessionEntity(inp: TitleInput): string {
  if (inp.entity) return inp.entity;
  return (inp.participants ?? []).length >= 2 ? "Group-Interview" : "Interview";
}

function subjectSource(inp: TitleInput, asciiOnly: boolean): string | null {
  if (inp.subject) return inp.subject;
  const people = inp.participants ?? [];
  if (people.length >= 2) return `${people.length} Artisans`;
  if (people.length === 1) {
    const person = people[0];
    if (asciiOnly) {
      for (const candidate of [person.name, person.localName]) {
        if (candidate && candidate.trim() && /^[\x00-\x7f]*$/.test(candidate)) return candidate;
      }
    }
    return person.name || person.localName || null;
  }
  return null;
}

type Render = (place: string[], craft: string[], subject: string[]) => string;

/** Subject first (to 40, at a word boundary), then Craft, Place, Subject again, drop Craft, Place, cut Subject. */
function fit(render: Render, place: string[], craft: string[], subject: string[], limit: number): string {
  const tooLong = () => cpLength(render(place, craft, subject)) > limit;
  while (tooLong() && subject.length > 1 && cpLength(joinField(subject)) > SUBJECT_MIN) subject.pop();
  while (tooLong() && craft.length > 1) craft.pop();
  while (tooLong() && place.length > 1) place.pop();
  while (tooLong() && subject.length > 1) subject.pop();
  if (tooLong() && craft.length) craft.length = 0;
  if (tooLong() && place.length) place.length = 0;
  while (tooLong() && subject.length && cpLength(subject[0]) > 1) subject[0] = [...subject[0]].slice(0, -1).join("");
  if (tooLong()) subject.length = 0;
  return render(place, craft, subject);
}

/** `Entity_Date_Place_Craft_Subject`, at most 120 characters, original script allowed. */
export function sessionTitle(inp: TitleInput): string {
  const entity = sessionEntity(inp);
  const day = inp.sessionDate ? inp.sessionDate.slice(0, 10) : null;
  return fit(
    (p, c, s) => fields(entity, day, joinField(p), joinField(c), joinField(s)),
    fieldWords(placeSource(inp), false),
    fieldWords(craftSource(inp), false),
    fieldWords(subjectSource(inp, false), false),
    TITLE_MAX
  );
}

const guard = (stem: string) => (RESERVED_STEMS.has(stem.toUpperCase()) ? `${stem}-File` : stem);

export type FileNameInput = {
  entity: string;
  what: string;
  ext: string;
  day?: string | null;
  time?: string | null;
  place?: string | null;
  craft?: string | null;
  subject?: string | null;
  sequence?: string | null;
  n?: number | null;
  portal?: string;
};

/** The NAMING §2.1 grammar, ASCII, at most 150 characters including the extension. */
export function fileName(input: FileNameInput): string {
  const ext = (input.ext ?? "").replace(/^\.+/, "").replace(/[^A-Za-z0-9.]/g, "").toLowerCase() || "bin";
  const entity = joinField(fieldWords(input.entity, true));
  const what = joinField(fieldWords(input.what, true));
  const n = input.n && input.n > 1 ? String(input.n) : null;
  return fit(
    (p, c, s) =>
      `${guard(fields(input.portal ?? PORTAL, entity, input.day ?? null, input.time ?? null, joinField(p), joinField(c), joinField(s), what, input.sequence || null, n))}.${ext}`,
    fieldWords(input.place, true),
    fieldWords(input.craft, true),
    fieldWords(input.subject, true),
    FILE_MAX
  );
}

export type TargetRef = { sectionCode: string; displayCode?: string | null };

function questionNumber(displayCode: string): [string, number] | null {
  const m = DISPLAY_CODE.exec(displayCode.trim());
  return m ? [m[1].toUpperCase(), Number(m[2])] : null;
}

/** `D-Q03`, `B-All`, `F-Q01-Q06`, plus `-P2` for a continuation or `-T2` for a solo repeat. */
export function recordingSequence(
  targets: TargetRef[],
  opts: { part?: number | null; position?: number | null; rosterCount?: number | null } = {}
): string | null {
  const bySection = new Map<string, { all: boolean; numbers: number[] }>();
  for (const t of targets) {
    let code = t.sectionCode ? joinField(fieldWords(t.sectionCode, true)).toUpperCase() : "";
    if (!code && t.displayCode) code = questionNumber(t.displayCode)?.[0] ?? "";
    if (!code) continue;
    if (!bySection.has(code)) bySection.set(code, { all: false, numbers: [] });
    const entry = bySection.get(code)!;
    if (t.displayCode) {
      const parsed = questionNumber(t.displayCode);
      if (parsed) entry.numbers.push(parsed[1]);
    } else entry.all = true;
  }
  const q = (n: number) => `Q${String(n).padStart(2, "0")}`;
  const pieces = [...bySection.entries()].map(([code, entry]) => {
    const numbers = [...new Set(entry.numbers)].sort((a, b) => a - b);
    if (entry.all || numbers.length === 0) return `${code}-All`;
    if (numbers.length === 1) return `${code}-${q(numbers[0])}`;
    return `${code}-${q(numbers[0])}-${q(numbers[numbers.length - 1])}`;
  });
  let suffix: string | null = null;
  if (opts.part && opts.part >= 2) suffix = `P${opts.part}`;
  else if (opts.rosterCount === 1 && opts.position && opts.position >= 2) suffix = `T${opts.position}`;
  let sequence = pieces.length ? pieces.join("-") : null;
  if (suffix) sequence = sequence ? `${sequence}-${suffix}` : suffix;
  return sequence;
}

/** `YYYY-MM-DD` and `HHMM` on the IST wall clock for an instant. */
export function istParts(iso: string): { date: string; time: string } | null {
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) return null;
  const shifted = new Date(instant.getTime() + IST_OFFSET_MINUTES * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`,
    time: `${pad(shifted.getUTCHours())}${pad(shifted.getUTCMinutes())}`
  };
}

export function istToday(now: Date = new Date()): string {
  return istParts(now.toISOString())!.date;
}

export type RecordingNameInput = {
  session: TitleInput;
  capturedAt: string | null;
  capturedAtSource?: string | null;
  targets?: TargetRef[];
  ext?: string;
  what?: string;
  part?: number | null;
  position?: number | null;
  rosterCount?: number | null;
  n?: number | null;
};

/** §3.13: the recording, transcript or subtitle file name of one take. */
export function recordingFilename(input: RecordingNameInput): string {
  const local = input.capturedAt ? istParts(input.capturedAt) : null;
  const estimated = isEstimatedSource(input.capturedAtSource);
  return fileName({
    entity: sessionEntity(input.session),
    what: input.what ?? "Recording",
    ext: input.ext ?? "m4a",
    day: local?.date ?? null,
    time: local && !estimated ? local.time : null,
    place: placeSource(input.session),
    craft: craftSource(input.session),
    subject: subjectSource(input.session, true),
    sequence: recordingSequence(input.targets ?? [], { part: input.part, position: input.position, rosterCount: input.rosterCount }),
    n: input.n
  });
}

/** A file about a whole session (the zip of its recordings): no time, no sequence. */
export function sessionFilename(session: TitleInput, what: string, ext: string): string {
  return fileName({
    entity: sessionEntity(session),
    what,
    ext,
    day: session.sessionDate ? session.sessionDate.slice(0, 10) : null,
    place: placeSource(session),
    craft: craftSource(session),
    subject: subjectSource(session, true)
  });
}

/** Exports and reports: the generation day (IST) unless `day` is given. */
export function exportFilename(input: { entity: string; what: string; ext: string; day?: string | null; subject?: string | null; place?: string | null; craft?: string | null; time?: string | null }): string {
  return fileName({ ...input, day: input.day ? input.day.slice(0, 10) : istToday() });
}

/** Any file name as an ASCII leaf: `[A-Za-z0-9._-]`, at most 150 characters, never empty or reserved. */
export function asciiLeaf(filename: string | null | undefined, fallback = "upload.bin"): string {
  const raw = String(filename ?? "").replace(/\\/g, "/").split("/").pop()!.trim();
  const cleaned = toAscii(raw)
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|[-.]+$/g, "");
  if (!cleaned) return fallback;
  const dot = cleaned.lastIndexOf(".");
  let stem = dot >= 0 ? cleaned.slice(0, dot) : cleaned;
  let ext = dot >= 0 ? cleaned.slice(dot + 1) : "";
  stem = guard(stem.replace(/^[-.]+|[-.]+$/g, "") || "upload");
  ext = ext.slice(0, 16);
  const budget = FILE_MAX - (ext ? ext.length + 1 : 0);
  stem = stem.slice(0, budget).replace(/[-.]+$/, "") || "upload";
  return ext ? `${stem}.${ext}` : stem;
}

/** `..._D-Q03.m4a` → `..._D-Q03_2.m4a`; `.sql.gz` counts as one extension. */
export function withN(name: string, n: number): string {
  if (n <= 1) return name;
  if (name.endsWith(".sql.gz")) return `${name.slice(0, -7)}_${n}.sql.gz`;
  const dot = name.lastIndexOf(".");
  return dot >= 0 ? `${name.slice(0, dot)}_${n}${name.slice(dot)}` : `${name}_${n}`;
}

/** `_2`, `_3` for colliding names, in the order given (callers sort by createdAt, then id). */
export function dedupeNames(names: string[]): string[] {
  const seen = new Map<string, number>();
  const taken = new Set<string>();
  return names.map((name) => {
    const key = name.toLowerCase();
    let count = (seen.get(key) ?? 0) + 1;
    seen.set(key, count);
    let candidate = withN(name, count);
    while (taken.has(candidate.toLowerCase())) {
      count += 1;
      seen.set(key, count);
      candidate = withN(name, count);
    }
    taken.add(candidate.toLowerCase());
    return candidate;
  });
}

/**
 * A phone number for display: `+91 98765 43210` (owner correction b). The country code lives in its
 * own column; the national number is digits only. Other countries print the digits after the code.
 */
export function formatPhone(countryCode: string | null | undefined, digits: string | null | undefined): string {
  const national = (digits ?? "").replace(/\D/g, "");
  if (!national) return "";
  const code = (countryCode ?? "+91").trim() || "+91";
  const prefix = code.startsWith("+") ? code : `+${code}`;
  if (prefix === "+91" && national.length === 10) return `${prefix} ${national.slice(0, 5)} ${national.slice(5)}`;
  return `${prefix} ${national}`;
}
