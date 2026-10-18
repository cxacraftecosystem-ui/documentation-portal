/**
 * One naming convention for every name either portal generates (owner correction, 18 Oct 2026).
 *
 * The web half of `backend/app/services/naming.py`; `android/.../data/Naming.kt` is the third. All
 * three reproduce `shared/naming-golden.json` row for row (`e2e/naming-unit.spec.ts`). This file is
 * byte-identical in designer-portal and documentation-portal; the portal word comes from
 * `namingPortal.ts`.
 *
 *   File names:      Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext
 *   Display titles:  Entity_Date_Place_Craft_Subject   (generated defaults only)
 *
 * `_` separates fields, `-` joins the words inside one, an unknown field is left out, file names are
 * ASCII and at most 150 characters (titles 120, original script allowed). Subject is cut first (to
 * 40), then Craft, then Place.
 */
import anyAscii from "any-ascii";

import { PORTAL } from "@/lib/namingPortal";

export const MAX_FILE_NAME = 150;
export const MAX_TITLE = 120;
export const MIN_SUBJECT = 40;

const RESERVED_STEMS = new Set<string>([
  "CON",
  "PRN",
  "AUX",
  "NUL",
  ...Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`)
]);
const DOUBLE_EXTENSIONS = [".sql.gz", ".tar.gz"];
const VERSION = /^v\d+(?:\.\d+)*$/;
const APOSTROPHES = /['’ʼ`]/g;
const KEEP_FORMAT = new Set(["‌", "‍"]);
const IST_OFFSET_MS = 330 * 60 * 1000;

// ── Transliteration ─────────────────────────────────────────────────────────────────────────────
// anyascii maps a Brahmic consonant to its bare letter (प्रसाद -> "prsad"). The inherent "a" is put
// back after a consonant with no vowel sign or virama, and dropped at the end of a word in the
// northern scripts. Same rule as naming.py and Naming.kt.

const INDIC_FIRST = 0x0900;
const INDIC_LAST = 0x0d7f;
const FINAL_SCHWA_DROPS_BELOW = 0x0b00;
const EXTRA_CONSONANTS = new Set([0x09f0, 0x09f1]);
const NASALS = new Set([0x0a70]);
const NUKTA_FORMS = new Map<number, number>([
  [0x0915, 0x0958], [0x0916, 0x0959], [0x0917, 0x095a], [0x091c, 0x095b], [0x0921, 0x095c],
  [0x0922, 0x095d], [0x092b, 0x095e], [0x092f, 0x095f], [0x09a1, 0x09dc], [0x09a2, 0x09dd],
  [0x09af, 0x09df], [0x0a16, 0x0a59], [0x0a17, 0x0a5a], [0x0a1c, 0x0a5b], [0x0a2b, 0x0a5e],
  [0x0a32, 0x0a33], [0x0a38, 0x0a36], [0x0b21, 0x0b5c], [0x0b22, 0x0b5d]
]);

function indicOffset(cp: number | undefined): number | null {
  if (cp === undefined || cp < INDIC_FIRST || cp > INDIC_LAST) return null;
  return (cp - INDIC_FIRST) % 0x80;
}

function isConsonant(cp: number | undefined): boolean {
  const off = indicOffset(cp);
  return off !== null && ((off >= 0x15 && off <= 0x39) || (off >= 0x58 && off <= 0x5f) || EXTRA_CONSONANTS.has(cp!));
}

function isNasal(cp: number | undefined): boolean {
  const off = indicOffset(cp);
  return off === 0x01 || off === 0x02 || (cp !== undefined && NASALS.has(cp));
}

function isSign(cp: number | undefined): boolean {
  const off = indicOffset(cp);
  return (
    off !== null &&
    ((off >= 0x3a && off <= 0x3b) || (off >= 0x3e && off <= 0x4d) || (off >= 0x55 && off <= 0x57) || (off >= 0x62 && off <= 0x63))
  );
}

function isIndicLetter(cp: number | undefined): boolean {
  const off = indicOffset(cp);
  return off !== null && ((off >= 0x01 && off <= 0x63) || EXTRA_CONSONANTS.has(cp!) || NASALS.has(cp!));
}

function codePoints(text: string): number[] {
  return Array.from(text, (ch) => ch.codePointAt(0)!);
}

function composeNukta(cps: number[]): number[] {
  const out: number[] = [];
  for (const cp of cps) {
    const last = out[out.length - 1];
    if (out.length && indicOffset(cp) === 0x3c && NUKTA_FORMS.has(last)) {
      out[out.length - 1] = NUKTA_FORMS.get(last)!;
    } else {
      out.push(cp);
    }
  }
  return out;
}

/** ASCII for any script, with the Brahmic inherent vowel kept where it is spoken. */
export function transliterate(text: string | null | undefined): string {
  const cps = composeNukta(codePoints((text ?? "").normalize("NFC")));
  const out: string[] = [];
  let wordHasVowel = false;
  for (let i = 0; i < cps.length; i++) {
    const cp = cps[i];
    const off = indicOffset(cp);
    if (off === 0x3c || off === 0x3d) out.push("");
    else out.push(isNasal(cp) ? "n" : anyAscii(String.fromCodePoint(cp)));
    if (!isIndicLetter(cp)) {
      wordHasVowel = false;
      continue;
    }
    if (!isConsonant(cp)) {
      if (off !== 0x4d && !isNasal(cp)) wordHasVowel = true;
      continue;
    }
    let j = i + 1;
    while (j < cps.length && indicOffset(cps[j]) === 0x3c) j++;
    const next = j < cps.length ? cps[j] : undefined;
    if (isSign(next)) {
      if (indicOffset(next) !== 0x4d) wordHasVowel = true;
      continue;
    }
    const wordEnd = !isIndicLetter(next) || (isNasal(next) && !isIndicLetter(j + 1 < cps.length ? cps[j + 1] : undefined));
    const drops = cp < FINAL_SCHWA_DROPS_BELOW && wordHasVowel;
    if (!(wordEnd && drops)) {
      out.push("a");
      wordHasVowel = true;
    }
  }
  return out.join("");
}

// ── Fields ──────────────────────────────────────────────────────────────────────────────────────

function cpLength(text: string): number {
  return Array.from(text).length;
}

function cpSlice(text: string, end: number): string {
  return Array.from(text).slice(0, end).join("");
}

function isUpperWord(word: string): boolean {
  return word === word.toUpperCase() && word !== word.toLowerCase();
}

function titleWord(word: string): string {
  if (!word || /\p{N}/u.test(word)) return word;
  const chars = Array.from(word);
  if (isUpperWord(word)) return chars.length <= 3 ? word : chars[0] + chars.slice(1).join("").toLowerCase();
  return chars[0].toUpperCase() + chars.slice(1).join("");
}

function splitWords(text: string, asciiOnly: boolean): string[] {
  const words: string[] = [];
  let current = "";
  for (const ch of text.replace(APOSTROPHES, "")) {
    const keep = asciiOnly ? /^[A-Za-z0-9]$/.test(ch) : /^[\p{L}\p{N}\p{M}]$/u.test(ch) || KEEP_FORMAT.has(ch);
    if (keep) current += ch;
    else if (current) {
      words.push(current);
      current = "";
    }
  }
  if (current) words.push(current);
  return words.map(titleWord);
}

/** One field: words Title-Cased and joined by `-`. ASCII unless `asciiOnly` is false. */
export function field(value: unknown, asciiOnly = true): string {
  if (value === null || value === undefined) return "";
  let text = String(value).normalize("NFC").trim();
  if (!text) return "";
  if (asciiOnly) text = transliterate(text);
  return splitWords(text, asciiOnly).join("-");
}

export type PlaceParts = { locality?: unknown; district?: unknown; state?: unknown };

/** `Locality-District`, else `District`, else `State`. A locality alone is not a place. */
export function placeField(place: PlaceParts, asciiOnly = true): string {
  const dist = field(place.district, asciiOnly);
  const loc = field(place.locality, asciiOnly);
  if (dist) return loc && loc.toLowerCase() !== dist.toLowerCase() ? `${loc}-${dist}` : dist;
  return field(place.state, asciiOnly);
}

function two(n: number): string {
  return String(Math.trunc(n)).padStart(2, "0");
}

/** `D-Q03`, `D-Q03-T2` (a solo take at position 2 or more), `D-Q03-P2` (part 2). */
export function questionSequence(
  section: unknown,
  question: unknown,
  opts: { take?: number | null; part?: number | null } = {}
): string {
  const sec = field(section).toUpperCase();
  if (!sec || question === null || question === undefined || String(question).trim() === "") return "";
  let out = `${sec}-Q${two(Number(question))}`;
  if (opts.part && opts.part >= 2) out += `-P${Math.trunc(opts.part)}`;
  if (opts.take && opts.take >= 2) out += `-T${Math.trunc(opts.take)}`;
  return out;
}

/** `D-All`: a recording that covers a whole section. */
export function sectionSequence(section: unknown): string {
  const sec = field(section).toUpperCase();
  return sec ? `${sec}-All` : "";
}

function sequenceField(value: unknown): string {
  const text = String(value ?? "").trim();
  return VERSION.test(text) ? text : field(text);
}

// ── Dates (IST) ─────────────────────────────────────────────────────────────────────────────────

export type When = Date | string | null | undefined;

type IstMoment = { date: string; time: string | null };

/** The IST calendar day and HHMM of a moment; a bare `YYYY-MM-DD` has no time. Naive = UTC. */
export function asIst(value: When): IstMoment | null {
  if (value === null || value === undefined || value === "") return null;
  let ms: number;
  if (typeof value === "string") {
    const text = value.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return { date: text, time: null };
    const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text) ? text : `${text}Z`;
    ms = Date.parse(zoned);
  } else {
    ms = value.getTime();
  }
  if (Number.isNaN(ms)) return null;
  const iso = new Date(ms + IST_OFFSET_MS).toISOString();
  return { date: iso.slice(0, 10), time: iso.slice(11, 13) + iso.slice(14, 16) };
}

// ── Assembly and truncation ─────────────────────────────────────────────────────────────────────

type Cuttable = { place: string; craft: string; subject: string };

function dropLastWord(text: string): string {
  const at = text.lastIndexOf("-");
  return at >= 0 ? text.slice(0, at) : "";
}

function fit(parts: Cuttable, render: (p: Cuttable) => string, limit: number): string {
  let name = render(parts);
  const order: [keyof Cuttable, number][] = [
    ["subject", MIN_SUBJECT],
    ["craft", 0],
    ["place", 0],
    ["subject", 0]
  ];
  for (const [key, floor] of order) {
    while (cpLength(name) > limit && cpLength(parts[key]) > floor) {
      let shorter = dropLastWord(parts[key]);
      if (floor && !shorter) shorter = cpSlice(parts[key], floor);
      parts[key] = shorter;
      name = render(parts);
    }
  }
  return name;
}

function extension(ext: string | null | undefined): string {
  let text = String(ext ?? "").trim().toLowerCase().replace(/^\.+/, "");
  text = text.replace(/[^a-z0-9.]/g, "").replace(/\.{2,}/g, ".").replace(/^\.+|\.+$/g, "");
  return text ? `.${text}` : "";
}

export type FileNameInput = {
  entity?: unknown;
  what?: unknown;
  ext?: string;
  portal?: string | null;
  date?: When;
  time?: boolean;
  period?: unknown;
  place?: unknown;
  craft?: unknown;
  subject?: unknown;
  sequence?: unknown;
  n?: number | null;
};

/** `Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext`, ASCII, <= 150. */
export function fileName(input: FileNameInput): string {
  const moment = input.period ? null : asIst(input.date);
  const fixed = {
    portal: field(input.portal || PORTAL),
    entity: field(input.entity),
    date: input.period ? field(input.period) : (moment?.date ?? ""),
    time: input.time && !input.period ? (moment?.time ?? "") : "",
    what: field(input.what),
    sequence: sequenceField(input.sequence),
    n: input.n && input.n >= 2 ? String(Math.trunc(input.n)) : ""
  };
  const ext = extension(input.ext);
  const parts: Cuttable = { place: field(input.place), craft: field(input.craft), subject: field(input.subject) };
  const render = (p: Cuttable) =>
    [fixed.portal, fixed.entity, fixed.date, fixed.time, p.place, p.craft, p.subject, fixed.what, fixed.sequence, fixed.n]
      .filter(Boolean)
      .join("_") + ext;
  return fit(parts, render, MAX_FILE_NAME);
}

export type ExportInput = Omit<FileNameInput, "date" | "time" | "entity" | "what" | "ext"> & {
  at?: When;
  withTime?: boolean;
  dated?: boolean;
};

/** A download, export, report or backup, dated the day it is generated (IST). */
export function exportFilename(entity: unknown, what: unknown, ext: string, opts: ExportInput = {}): string {
  const { at, withTime = false, dated = true, ...rest } = opts;
  const when = dated && !rest.period ? (at ?? new Date()) : null;
  return fileName({ ...rest, entity, what, ext, date: when, time: withTime });
}

export type RecordingInput = Omit<FileNameInput, "date" | "time" | "entity" | "ext"> & {
  capturedAt?: When;
  timeKnown?: boolean;
};

/** A capture, dated and timed when it was captured. An estimated time is left out. */
export function recordingFilename(entity: unknown, ext: string, opts: RecordingInput = {}): string {
  const { capturedAt, timeKnown = true, what = "Recording", ...rest } = opts;
  return fileName({ ...rest, entity, ext, what, date: capturedAt, time: timeKnown });
}

export type WorkshopNameFields = {
  workshopCode?: string | null;
  title?: string | null;
  clusterName?: string | null;
  district?: string | null;
  state?: string | null;
  craftName?: string | null;
};

/**
 * A design workshop's report, cards or list: `..._Workshop_Date_Place_Craft_Code_What.ext`. The
 * server, this client's offline report and the handset name one report identically through this.
 */
export function workshopFilename(
  workshop: WorkshopNameFields,
  what: unknown,
  ext: string,
  opts: { at?: When; dated?: boolean; n?: number | null; portal?: string | null } = {}
): string {
  return exportFilename("Workshop", what, ext, {
    ...opts,
    place: placeField({ locality: workshop.clusterName, district: workshop.district, state: workshop.state }),
    craft: workshop.craftName,
    subject: workshop.workshopCode || workshop.title
  });
}

export type Participant = string | { name?: string | null; romanisedName?: string | null; nameEnglish?: string | null };

export type TitleFields = {
  participants?: Participant[] | null;
  participantCount?: number | null;
  date?: When;
  place?: unknown;
  craft?: unknown;
  entity?: string | null;
  subject?: unknown;
};

function participantName(person: Participant): string {
  if (person && typeof person === "object") return String(person.romanisedName || person.nameEnglish || person.name || "");
  return String(person ?? "");
}

/** `Entity_Date_Place_Craft_Subject` for a generated interview, session or workshop title. */
export function sessionTitle(fields: TitleFields): string {
  const people = (fields.participants ?? []).filter((p) => participantName(p));
  const count = people.length || Math.trunc(Number(fields.participantCount ?? 0)) || 0;
  const entity = fields.entity || (count >= 2 ? "Group-Interview" : "Interview");
  let subject = fields.subject;
  if (!subject) {
    if (count >= 2) subject = `${count} Artisans`;
    else if (people.length) subject = participantName(people[0]);
  }
  const fixedEntity = field(entity);
  const fixedDate = asIst(fields.date)?.date ?? "";
  const parts: Cuttable = {
    place: field(fields.place, false),
    craft: field(fields.craft, false),
    subject: field(subject, false)
  };
  const render = (p: Cuttable) => [fixedEntity, fixedDate, p.place, p.craft, p.subject].filter(Boolean).join("_");
  return fit(parts, render, MAX_TITLE);
}

// ── Leaves and collisions ───────────────────────────────────────────────────────────────────────

export function splitExtension(name: string): [string, string] {
  const lower = name.toLowerCase();
  for (const double of DOUBLE_EXTENSIONS) {
    if (lower.endsWith(double) && name.length > double.length) {
      return [name.slice(0, -double.length), name.slice(-double.length)];
    }
  }
  const at = name.lastIndexOf(".");
  if (at > 0) {
    const ext = name.slice(at + 1);
    if (ext.length > 0 && ext.length <= 10 && /^[A-Za-z0-9]+$/.test(ext)) return [name.slice(0, at), `.${ext}`];
  }
  return [name, ""];
}

/** Any name made safe as an S3 leaf or a download name: ASCII `[A-Za-z0-9._-]`, <= 150. */
export function asciiLeaf(name: unknown): string {
  let text = String(name ?? "").normalize("NFC").trim();
  text = text.replace(/\\/g, "/");
  text = text.slice(text.lastIndexOf("/") + 1);
  const [rawStem, rawExt] = splitExtension(text);
  const ext = extension(rawExt);
  let stem = transliterate(rawStem).replace(APOSTROPHES, "");
  stem = stem.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/-{2,}/g, "-").replace(/_{2,}/g, "_");
  stem = stem.replace(/^[-._]+|[-._]+$/g, "");
  if (!stem) stem = "File";
  if (RESERVED_STEMS.has(stem.split(".")[0].toUpperCase())) stem = `${stem}-File`;
  const room = MAX_FILE_NAME - ext.length;
  if (stem.length > room) stem = stem.slice(0, room).replace(/[-._]+$/, "") || "File";
  return stem + ext;
}

/** The second and later copies of a name get `_2`, `_3`, in the order given (createdAt, then id). */
export function dedupeNames(names: Iterable<string>): string[] {
  const used = new Set<string>();
  const out: string[] = [];
  for (const name of names) {
    const [stem, ext] = splitExtension(name);
    let candidate = name;
    let n = 1;
    while (used.has(candidate.toLowerCase())) {
      n += 1;
      candidate = `${stem}_${n}${ext}`;
    }
    used.add(candidate.toLowerCase());
    out.push(candidate);
  }
  return out;
}
