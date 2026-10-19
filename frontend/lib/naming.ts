/**
 * THE one naming convention for generated names (owner correction a, 18 Oct 2026).
 *
 * The web half of `backend/app/services/naming.py`, line for line; `android/.../data/Naming.kt` is
 * the third. All three reproduce two golden tables row for row (`e2e/naming-unit.spec.ts`):
 * `shared/session-title.json` (the frozen questionnaire contract: titles, recording names,
 * transliteration, exports, collisions) and `shared/naming-golden.json` (every other generated name).
 * This file is byte-identical in designer-portal and documentation-portal; the portal word comes from
 * `namingPortal.ts`.
 *
 *   title      Entity_Date_Place_Craft_Subject                                  (<= 120, any script)
 *   file name  Portal_Entity_Date[_Time]_Place_Craft_Subject_What[_Sequence][_n].ext   (<= 150, ASCII)
 *
 * `_` separates fields, `-` joins the words inside one, an unknown field is left out. The
 * questionnaire screens call the TitleInput shapes (`sessionTitle({sessionDate, workshop, ...})`,
 * `recordingFilename({session, capturedAt, ...})`, `exportFilename({entity, what, ext, day})`); the
 * rest of the client calls the plain-field shapes (`recordingFilename("Interview", "m4a", {...})`,
 * `exportFilename("Dataset", "Export", "zip")`). Both are one implementation.
 */
import anyAscii from "any-ascii";

import { PORTAL } from "@/lib/namingPortal";

export { PORTAL };
export const TITLE_MAX = 120;
export const FILE_MAX = 150;
export const SUBJECT_MIN = 40;
/** The names the rest of the client already imports. */
export const MAX_TITLE = TITLE_MAX;
export const MAX_FILE_NAME = FILE_MAX;
export const MIN_SUBJECT = SUBJECT_MIN;

const IST_OFFSET_MS = 330 * 60 * 1000;
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
const VERSION = /^v\d+(?:\.\d+)*$/;
const DOUBLE_EXTENSIONS = [".sql.gz", ".tar.gz"];
const INDIC_BLOCKS = [0x0900, 0x0980, 0x0a00, 0x0a80, 0x0b00, 0x0b80, 0x0c00, 0x0c80, 0x0d00];
const WORD_CHAR = /[\p{L}\p{M}\p{Nd}]/u;
const DIGIT = /\p{Nd}/u;

/** Length in code points, as Python counts it. */
const cpLength = (value: string) => [...value].length;

export function isEstimatedSource(source: string | null | undefined): boolean {
  return ESTIMATED_SOURCES.has((source ?? "").toUpperCase());
}

// ── Indic inherent vowel (session-title.json rules.transliteration) ───────────────────────────────
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
  return anyAscii(indicInherentVowels(String(value).normalize("NFC")));
}

export const transliterate = toAscii;

// ── words and fields ────────────────────────────────────────────────────────────────────────────
const text = (value: unknown): string => (value === null || value === undefined ? "" : String(value));

/** Words end at every character that is not a letter, a mark, a decimal digit, ZWNJ or ZWJ. */
export function splitWords(value: unknown): string[] {
  const words: string[] = [];
  let current = "";
  for (const ch of text(value).normalize("NFC")) {
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

export function fieldWords(value: unknown, asciiOnly: boolean): string[] {
  const raw = text(value);
  let words = splitWords(asciiOnly ? toAscii(raw) : raw);
  if (asciiOnly) words = words.map((w) => w.replace(/[^A-Za-z0-9]/g, "")).filter(Boolean);
  return words.map(titleCaseWord);
}

const joinField = (words: string[]) => words.join("-");
const fields = (...parts: (string | null | undefined)[]) => parts.filter(Boolean).join("_");

/** One field: words Title-Cased and joined by `-`. ASCII unless `asciiOnly` is false. */
export function field(value: unknown, asciiOnly = true): string {
  return joinField(fieldWords(value, asciiOnly));
}

/** The same words, ignoring case; also when one is written in another script (Majuli, माजुली). */
function same(a: unknown, b: unknown): boolean {
  if (splitWords(a).join(" ").toLowerCase() === splitWords(b).join(" ").toLowerCase()) return true;
  const fa = field(a).toLowerCase();
  return fa !== "" && fa === field(b).toLowerCase();
}

function placeText(locality: unknown, district: unknown, state: unknown): string | null {
  const loc = text(locality).trim();
  const dist = text(district).trim();
  const st = text(state).trim();
  if (loc && dist && !same(loc, dist)) return `${loc} ${dist}`;
  return loc || dist || st || null;
}

export type PlaceParts = { locality?: unknown; district?: unknown; state?: unknown };

/** The Place field: `Locality-District`, else `Locality`, else `District`, else `State`. */
export function placeField(place: PlaceParts, asciiOnly = true): string {
  return field(placeText(place.locality, place.district, place.state), asciiOnly);
}

// ── the inputs ──────────────────────────────────────────────────────────────────────────────────
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
  /** When only the head count is known. */
  participantCount?: number | null;
};

/** A participant in the plain-field shape: a name, or a record with a stored romanised name. */
export type Participant =
  | string
  | { name?: string | null; romanisedName?: string | null; nameEnglish?: string | null; localName?: string | null; craft?: string | null };

/** The plain-field spelling of a title's inputs; `sessionTitle` takes either. */
export type TitleFields = {
  participants?: Participant[] | null;
  participantCount?: number | null;
  date?: When;
  sessionDate?: string | null;
  place?: unknown;
  craft?: unknown;
  workshop?: TitleWorkshop | null;
  entity?: string | null;
  subject?: unknown;
};

type Input = {
  sessionDate: string | null;
  place: string | null;
  workshop: TitleWorkshop | null;
  participants: Person[];
  entity: string | null;
  subject: string | null;
  participantCount: number | null;
};

function toPerson(p: Participant | Person | null | undefined): Person | null {
  if (p && typeof p === "object") {
    const rec = p as Exclude<Participant, string>;
    const roman = rec.romanisedName || rec.nameEnglish;
    if (roman) return { name: roman, localName: rec.localName || rec.name || null, craft: rec.craft ?? null };
    if (!(rec.name || rec.localName) && !rec.craft) return null;
    return { name: rec.name ?? null, localName: rec.localName ?? null, craft: rec.craft ?? null };
  }
  const name = text(p).trim();
  return name ? { name } : null;
}

function asInput(value: TitleInput | TitleFields): Input {
  const v = value as TitleFields & TitleInput;
  const people = ((v.participants ?? []) as (Participant | Person)[]).map(toPerson).filter((p): p is Person => p !== null);
  let workshop = v.workshop ?? null;
  const craft = text(v.craft).trim();
  if (craft && !text(workshop?.craft).trim()) workshop = { ...(workshop ?? {}), craft };
  const count = Math.trunc(Number(v.participantCount ?? 0)) || 0;
  return {
    sessionDate: v.sessionDate != null ? v.sessionDate : istDay(v.date),
    place: v.place == null ? null : text(v.place),
    workshop,
    participants: people,
    entity: v.entity || null,
    subject: v.subject == null || v.subject === "" ? null : text(v.subject),
    participantCount: count || null
  };
}

function placeSource(inp: Input): string | null {
  if ((inp.place ?? "").trim()) return inp.place;
  const ws = inp.workshop ?? {};
  return placeText(ws.locality, ws.district, ws.state);
}

function craftSource(inp: Input): string | null {
  const ws = inp.workshop ?? {};
  if ((ws.craft ?? "").trim()) return ws.craft!;
  const crafts = inp.participants.map((p) => (p.craft ?? "").trim());
  if (crafts.length && crafts.every(Boolean) && crafts.every((c) => same(c, crafts[0]))) return crafts[0];
  return null;
}

const countOf = (inp: Input) => inp.participants.length || inp.participantCount || 0;

function entityOf(inp: Input): string {
  if (inp.entity) return inp.entity;
  return countOf(inp) >= 2 ? "Group-Interview" : "Interview";
}

export function sessionEntity(inp: TitleInput | TitleFields): string {
  return entityOf(asInput(inp));
}

function subjectSource(inp: Input, asciiOnly: boolean): string | null {
  if (inp.subject) return inp.subject;
  const n = countOf(inp);
  if (n >= 2) return `${n} Artisans`;
  if (n === 1 && inp.participants.length) {
    const person = inp.participants[0];
    if (asciiOnly) {
      // A stored romanised name wins over transliteration.
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

// ── titles ──────────────────────────────────────────────────────────────────────────────────────
/** `Entity_Date_Place_Craft_Subject`, at most 120 characters, original script allowed. */
export function sessionTitle(value: TitleInput | TitleFields): string {
  const inp = asInput(value);
  const entity = joinField(fieldWords(entityOf(inp), false));
  const day = inp.sessionDate ? inp.sessionDate.slice(0, 10) : null;
  return fit(
    (p, c, s) => fields(entity, day, joinField(p), joinField(c), joinField(s)),
    fieldWords(placeSource(inp), false),
    fieldWords(craftSource(inp), false),
    fieldWords(subjectSource(inp, false), false),
    TITLE_MAX
  );
}

// ── dates (IST) ─────────────────────────────────────────────────────────────────────────────────
export type When = Date | string | null | undefined;

type IstMoment = { date: string; time: string | null };

/** The IST calendar day and HHMM of a moment; a bare `YYYY-MM-DD` has no time. Naive = UTC. */
export function asIst(value: When): IstMoment | null {
  if (value === null || value === undefined || value === "") return null;
  let ms: number;
  if (typeof value === "string") {
    const t = value.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(t)) return { date: t, time: null };
    const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(t) ? t : `${t}Z`;
    ms = Date.parse(zoned);
  } else {
    ms = value.getTime();
  }
  if (Number.isNaN(ms)) return null;
  const iso = new Date(ms + IST_OFFSET_MS).toISOString();
  return { date: iso.slice(0, 10), time: iso.slice(11, 13) + iso.slice(14, 16) };
}

function istDay(value: When): string | null {
  return asIst(value)?.date ?? null;
}

/** `YYYY-MM-DD` and `HHMM` on the IST wall clock for an instant. */
export function istParts(iso: string): { date: string; time: string } | null {
  const m = asIst(iso);
  return m && m.time ? { date: m.date, time: m.time } : null;
}

export function istToday(now: Date = new Date()): string {
  return asIst(now)!.date;
}

// ── file names ──────────────────────────────────────────────────────────────────────────────────
const guard = (stem: string) => (RESERVED_STEMS.has(stem.toUpperCase()) ? `${stem}-File` : stem);

function extension(ext: unknown): string {
  return text(ext)
    .trim()
    .replace(/^\.+/, "")
    .replace(/[^A-Za-z0-9.]/g, "")
    .toLowerCase()
    .replace(/\.{2,}/g, ".")
    .replace(/^\.+|\.+$/g, "");
}

function sequenceField(value: unknown): string | null {
  const t = text(value).trim();
  if (!t) return null;
  if (VERSION.test(t)) return t;
  return joinField(fieldWords(t, true)) || null;
}

export type FileNameInput = {
  entity?: unknown;
  what?: unknown;
  ext?: unknown;
  /** `YYYY-MM-DD` (or any moment) for the Date field. */
  day?: When;
  /** `HHMM` as given, or `true` for the IST time of `date`. */
  time?: string | boolean | null;
  place?: unknown;
  craft?: unknown;
  subject?: unknown;
  sequence?: unknown;
  n?: number | null;
  portal?: string | null;
  /** A moment whose IST day (and, with `time: true`, HHMM) is used. */
  date?: When;
  /** A period such as `FY2026-27` in place of the date. */
  period?: unknown;
};

/** The NAMING §2.1 grammar, ASCII, at most 150 characters including the extension. */
export function fileName(input: FileNameInput): string {
  const moment = input.period ? null : asIst(input.date !== undefined && input.date !== null ? input.date : input.day);
  const day = input.period ? joinField(fieldWords(input.period, true)) : (moment?.date ?? null);
  let time: string | null = null;
  if (typeof input.time === "string") time = input.time || null;
  else if (input.time && !input.period) time = moment?.time ?? null;
  const ext = extension(input.ext);
  const portal = (input.portal || PORTAL).replace(/[^A-Za-z0-9-]/g, "");
  const entity = joinField(fieldWords(input.entity, true));
  const what = joinField(fieldWords(input.what, true));
  const sequence = sequenceField(input.sequence);
  const n = input.n && input.n > 1 ? String(Math.trunc(input.n)) : null;
  return fit(
    (p, c, s) => {
      const stem = guard(fields(portal, entity, day, time, joinField(p), joinField(c), joinField(s), what, sequence, n));
      return ext ? `${stem}.${ext}` : stem;
    },
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

/** `D-Q03`; `-P2` for part 2, else `-T2` for a solo take at position 2 or more. */
export function questionSequence(
  section: unknown,
  question: unknown,
  opts: { take?: number | null; part?: number | null } = {}
): string {
  const sec = joinField(fieldWords(section, true)).toUpperCase();
  if (!sec || question === null || question === undefined || String(question).trim() === "") return "";
  const target = { sectionCode: sec, displayCode: `${sec}${Math.trunc(Number(question))}` };
  return recordingSequence([target], { part: opts.part, position: opts.take, rosterCount: 1 }) ?? "";
}

/** `D-All`: a recording that covers a whole section. */
export function sectionSequence(section: unknown): string {
  const sec = joinField(fieldWords(section, true)).toUpperCase();
  return sec ? (recordingSequence([{ sectionCode: sec }]) ?? "") : "";
}

/** The questionnaire shape of a recording's inputs. */
export type RecordingNameInput = {
  session: TitleInput | TitleFields;
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

/** The plain-field shape: the Entity word and its fields passed directly. */
export type RecordingInput = Omit<FileNameInput, "date" | "day" | "time" | "entity" | "ext"> & {
  capturedAt?: When;
  capturedAtSource?: string | null;
  timeKnown?: boolean;
};

function recordingMoment(capturedAt: When, estimated: boolean): { day: string | null; time: string | null } {
  const m = asIst(capturedAt);
  return { day: m?.date ?? null, time: m && m.time && !estimated ? m.time : null };
}

/**
 * §3.13: the recording, transcript or subtitle file name of one take. Either
 * `recordingFilename({session, capturedAt, capturedAtSource, targets, ...})` or
 * `recordingFilename("Interview", "m4a", {capturedAt, timeKnown, place, craft, subject, sequence})`.
 * A time whose source is an estimate, or with `timeKnown: false`, is left out.
 */
export function recordingFilename(input: RecordingNameInput): string;
export function recordingFilename(entity: unknown, ext: string, opts?: RecordingInput): string;
export function recordingFilename(first: unknown, ext?: string, opts: RecordingInput = {}): string {
  if (first && typeof first === "object" && "session" in (first as object)) {
    const r = first as RecordingNameInput;
    const inp = asInput(r.session);
    const { day, time } = recordingMoment(r.capturedAt, isEstimatedSource(r.capturedAtSource));
    return fileName({
      entity: entityOf(inp),
      what: r.what ?? "Recording",
      ext: r.ext ?? "m4a",
      day,
      time,
      place: placeSource(inp),
      craft: craftSource(inp),
      subject: subjectSource(inp, true),
      sequence: recordingSequence(r.targets ?? [], { part: r.part, position: r.position, rosterCount: r.rosterCount }),
      n: r.n
    });
  }
  const { capturedAt, capturedAtSource, timeKnown = true, what = "Recording", ...rest } = opts;
  const { day, time } = recordingMoment(capturedAt, isEstimatedSource(capturedAtSource) || !timeKnown);
  return fileName({ ...rest, entity: first, what, ext: ext ?? "m4a", day, time });
}

/** A file about a whole session (the zip of its recordings): no time, no sequence. */
export function sessionFilename(session: TitleInput | TitleFields, what: string, ext: string): string {
  const inp = asInput(session);
  return fileName({
    entity: entityOf(inp),
    what,
    ext,
    day: inp.sessionDate ? inp.sessionDate.slice(0, 10) : null,
    place: placeSource(inp),
    craft: craftSource(inp),
    subject: subjectSource(inp, true)
  });
}

export type ExportInput = Omit<FileNameInput, "date" | "time" | "entity" | "what" | "ext"> & {
  at?: When;
  time?: string | null;
  withTime?: boolean;
  dated?: boolean;
};

/** The questionnaire shape of an export's inputs. */
export type ExportNameInput = ExportInput & { entity: string; what: string; ext: string };

/**
 * A download, export, report or backup, dated the day it is generated (IST) unless `day` or `at`
 * says otherwise. `withTime` adds HHMM for snapshots that can repeat within a day; `dated: false` is
 * for templates (pro formas), which carry no date at all.
 */
export function exportFilename(input: ExportNameInput): string;
export function exportFilename(entity: unknown, what: unknown, ext: string, opts?: ExportInput): string;
export function exportFilename(first: unknown, what?: unknown, ext?: string, opts: ExportInput = {}): string {
  let entity = first;
  let options = opts;
  if (first && typeof first === "object" && "what" in (first as object) && what === undefined) {
    const { entity: e, what: w, ext: x, ...rest } = first as ExportNameInput;
    entity = e;
    what = w;
    ext = x;
    options = rest;
  }
  const { at, day, time, withTime = false, dated = true, ...rest } = options;
  let when: When = null;
  if (!rest.period && dated) when = day != null && day !== "" ? (typeof day === "string" ? day.trim().slice(0, 10) : istDay(day)) : (at ?? new Date());
  return fileName({ ...rest, entity, what, ext, date: when, time: time || withTime });
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
    place: placeText(workshop.clusterName, workshop.district, workshop.state),
    craft: workshop.craftName,
    subject: workshop.workshopCode || workshop.title
  });
}

// ── leaves, collisions ──────────────────────────────────────────────────────────────────────────
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

/** Any file name as an ASCII leaf: `[A-Za-z0-9._-]`, at most 150 characters, never empty or reserved. */
export function asciiLeaf(filename: unknown, fallback = "upload.bin"): string {
  const raw = text(filename).replace(/\\/g, "/").split("/").pop()!.trim();
  const cleaned = toAscii(raw)
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|[-.]+$/g, "");
  if (!cleaned) return fallback;
  const dot = cleaned.lastIndexOf(".");
  let stem = dot >= 0 ? cleaned.slice(0, dot) : cleaned;
  let ext = dot >= 0 ? cleaned.slice(dot + 1) : "";
  stem = guard(stem.replace(/^[-.]+|[-.]+$/g, "") || "upload");
  ext = ext.slice(0, 16).toLowerCase();
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
export function dedupeNames(names: Iterable<string>): string[] {
  const seen = new Map<string, number>();
  const taken = new Set<string>();
  return [...names].map((name) => {
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
