import anyAscii from "any-ascii";

import { PORTAL } from "@/lib/namingPortal";

export { PORTAL };
export const TITLE_MAX = 120;
export const FILE_MAX = 150;
export const SUBJECT_MIN = 40;
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
const CODE_GAP = /[ \t\r\n._-]+/g;
const NUMBER_TAIL = /^[A-Z]*([0-9]+)$/;
const VERSION = /^v\d+(?:\.\d+)*$/;
const DOUBLE_EXTENSIONS = [".sql.gz", ".tar.gz"];
const INDIC_BLOCKS = [0x0900, 0x0980, 0x0a00, 0x0a80, 0x0b00, 0x0b80, 0x0c00, 0x0c80, 0x0d00];
const WORD_CHAR = /[\p{L}\p{M}\p{Nd}]/u;
const DIGIT = /\p{Nd}/u;

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

export function toAscii(value: string | null | undefined): string {
  if (!value) return "";
  return anyAscii(indicInherentVowels(String(value).normalize("NFC")));
}

export const transliterate = toAscii;

const text = (value: unknown): string => (value === null || value === undefined ? "" : String(value));

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

export function field(value: unknown, asciiOnly = true): string {
  return joinField(fieldWords(value, asciiOnly));
}

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

export function placeField(place: PlaceParts, asciiOnly = true): string {
  return field(placeText(place.locality, place.district, place.state), asciiOnly);
}

export type Person = { name?: string | null; localName?: string | null; craft?: string | null };
export type TitleWorkshop = { locality?: string | null; district?: string | null; state?: string | null; craft?: string | null };
export type TitleInput = {
  sessionDate?: string | null;
  place?: string | null;
  workshop?: TitleWorkshop | null;
  participants?: Person[];
  entity?: string | null;
  subject?: string | null;
  participantCount?: number | null;
};

export type Participant =
  | string
  | { name?: string | null; romanisedName?: string | null; nameEnglish?: string | null; localName?: string | null; craft?: string | null };

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
      for (const candidate of [person.name, person.localName]) {
        if (candidate && candidate.trim() && /^[\x00-\x7f]*$/.test(candidate)) return candidate;
      }
    }
    return person.name || person.localName || null;
  }
  return null;
}

type Render = (place: string[], craft: string[], subject: string[]) => string;

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

export type When = Date | string | null | undefined;

type IstMoment = { date: string; time: string | null };

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

export function istParts(iso: string): { date: string; time: string } | null {
  const m = asIst(iso);
  return m && m.time ? { date: m.date, time: m.time } : null;
}

export function istToday(now: Date = new Date()): string {
  return asIst(now)!.date;
}

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
  day?: When;
  time?: string | boolean | null;
  place?: unknown;
  craft?: unknown;
  subject?: unknown;
  sequence?: unknown;
  n?: number | null;
  portal?: string | null;
  date?: When;
  period?: unknown;
};

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

export type TargetRef = { sectionCode: string; displayCode?: string | null; questionNumber?: number | null };

const compactCode = (text: unknown) => String(text ?? "").trim().replace(CODE_GAP, "").toUpperCase();

export function questionNumber(sectionCode: string, displayCode: string): number | null {
  const code = compactCode(displayCode);
  const sec = compactCode(sectionCode);
  const rest = sec && code.startsWith(sec) ? code.slice(sec.length) : "";
  if (/^[0-9]+$/.test(rest)) return Number(rest);
  const m = NUMBER_TAIL.exec(code);
  return m ? Number(m[1]) : null;
}

export function recordingSequence(
  targets: TargetRef[],
  opts: { part?: number | null; position?: number | null; rosterCount?: number | null } = {}
): string | null {
  const bySection = new Map<string, { all: boolean; numbers: number[] }>();
  for (const t of targets) {
    let code = t.sectionCode ? joinField(fieldWords(t.sectionCode, true)).toUpperCase() : "";
    if (!code) continue;
    if (!bySection.has(code)) bySection.set(code, { all: false, numbers: [] });
    const entry = bySection.get(code)!;
    const hasNumber = t.questionNumber !== null && t.questionNumber !== undefined;
    if (hasNumber || t.displayCode) {
      const n = hasNumber ? Math.trunc(Number(t.questionNumber)) : questionNumber(t.sectionCode, t.displayCode ?? "");
      if (n !== null && Number.isFinite(n)) entry.numbers.push(n);
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

export function questionSequence(
  section: unknown,
  question: unknown,
  opts: { take?: number | null; part?: number | null } = {}
): string {
  const sec = joinField(fieldWords(section, true)).toUpperCase();
  if (!sec || question === null || question === undefined || String(question).trim() === "") return "";
  const target = { sectionCode: sec, questionNumber: Math.trunc(Number(question)) };
  return recordingSequence([target], { part: opts.part, position: opts.take, rosterCount: 1 }) ?? "";
}

export function sectionSequence(section: unknown): string {
  const sec = joinField(fieldWords(section, true)).toUpperCase();
  return sec ? (recordingSequence([{ sectionCode: sec }]) ?? "") : "";
}

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

export type RecordingInput = Omit<FileNameInput, "date" | "day" | "time" | "entity" | "ext"> & {
  capturedAt?: When;
  capturedAtSource?: string | null;
  timeKnown?: boolean;
};

function recordingMoment(capturedAt: When, estimated: boolean): { day: string | null; time: string | null } {
  const m = asIst(capturedAt);
  return { day: m?.date ?? null, time: m && m.time && !estimated ? m.time : null };
}

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

export type ExportNameInput = ExportInput & { entity: string; what: string; ext: string };

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

export function withN(name: string, n: number): string {
  if (n <= 1) return name;
  if (name.endsWith(".sql.gz")) return `${name.slice(0, -7)}_${n}.sql.gz`;
  const dot = name.lastIndexOf(".");
  return dot >= 0 ? `${name.slice(0, dot)}_${n}${name.slice(dot)}` : `${name}_${n}`;
}

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

export function formatPhone(countryCode: string | null | undefined, digits: string | null | undefined): string {
  const national = (digits ?? "").replace(/\D/g, "");
  if (!national) return "";
  const code = (countryCode ?? "+91").trim() || "+91";
  const prefix = code.startsWith("+") ? code : `+${code}`;
  if (prefix === "+91" && national.length === 10) return `${prefix} ${national.slice(0, 5)} ${national.slice(5)}`;
  return `${prefix} ${national}`;
}
