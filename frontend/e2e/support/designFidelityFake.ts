import type { BrowserContext, Route } from "@playwright/test";

import { DataExplorerFake, PNG } from "./dataExplorerFake";

type Json = Record<string, unknown>;

type Answer = { status: number; json?: unknown; raw?: Buffer; contentType?: string; headers?: Record<string, string> };

const MB = 1024 * 1024;

export const WORKSHOP = "workshops/3rd";
export const GIRIRAJ = "workshops/3rd/giriraj";
export const AUDIO_ID = "aud-d";
export const VIDEO_ID = "vid-dabu";
export const AUDIO_MS = 2_462_000;
export const VIDEO_MS = 1_458_000;

type Seed = { name: string; mediaId: string; mediaType: string; bytes: number; capturedAt: string; transcript?: boolean };

const BOARD_FILES: Seed[] = [
  { name: "Interview_Dabu-mud-resist_Take-1.mp4", mediaId: VIDEO_ID, mediaType: "VIDEO", bytes: 701 * MB, capturedAt: "2026-09-22T10:00:00Z", transcript: true },
  { name: "Block-carving_close-up_03.jpg", mediaId: "img-carve-03", mediaType: "IMAGE", bytes: Math.round(3.4 * MB), capturedAt: "2026-09-23T11:00:00Z" },
  { name: "Section-D_Materials_Group-of-3.m4a", mediaId: AUDIO_ID, mediaType: "AUDIO", bytes: Math.round(14.2 * MB), capturedAt: "2026-09-22T10:00:00Z", transcript: true },
  { name: "Consent-form_signed.pdf", mediaId: "pdf-consent", mediaType: "PDF", bytes: 412 * 1024, capturedAt: "2026-09-21T10:00:00Z" }
];

function fillers(): Seed[] {
  const out: Seed[] = [];
  const images = 21;
  for (let i = 0; i < images; i += 1) out.push({ name: `Block-carving_detail_${String(i + 4).padStart(2, "0")}.jpg`, mediaId: `img-g${i}`, mediaType: "IMAGE", bytes: Math.round(2.8 * MB), capturedAt: "2026-09-23T12:00:00Z" });
  for (let i = 0; i < 3; i += 1) out.push({ name: `Dyeing_vat_${String(i + 1).padStart(2, "0")}.mp4`, mediaId: `vid-g${i}`, mediaType: "VIDEO", bytes: 93 * MB, capturedAt: "2026-09-23T13:00:00Z" });
  for (let i = 0; i < 13; i += 1) out.push({ name: `Section-${String.fromCharCode(65 + (i % 8))}_Answers_${String(i + 1).padStart(2, "0")}.m4a`, mediaId: `aud-g${i}`, mediaType: "AUDIO", bytes: Math.round(14 * MB), capturedAt: "2026-09-22T11:00:00Z", transcript: true });
  for (let i = 0; i < 7; i += 1) out.push({ name: `Field-notes_page_${i + 1}.pdf`, mediaId: `pdf-g${i}`, mediaType: "PDF", bytes: 380 * 1024, capturedAt: "2026-09-21T11:00:00Z" });
  return out;
}

function giraFiles(): Seed[] {
  const all = [...BOARD_FILES, ...fillers()];
  const audio = all.filter((file) => file.mediaType === "AUDIO");
  const others = all.filter((file) => file.mediaType !== "AUDIO");
  const ordered = [...BOARD_FILES, ...others.filter((file) => !BOARD_FILES.includes(file)), ...audio.filter((file) => !BOARD_FILES.includes(file))];
  return ordered;
}

function longWav(seconds: number): Buffer {
  const rate = 3000;
  const samples = Math.round(rate * seconds);
  const out = Buffer.alloc(44 + samples);
  out.write("RIFF", 0);
  out.writeUInt32LE(36 + samples, 4);
  out.write("WAVE", 8);
  out.write("fmt ", 12);
  out.writeUInt32LE(16, 16);
  out.writeUInt16LE(1, 20);
  out.writeUInt16LE(1, 22);
  out.writeUInt32LE(rate, 24);
  out.writeUInt32LE(rate, 28);
  out.writeUInt16LE(1, 32);
  out.writeUInt16LE(8, 34);
  out.write("data", 36);
  out.writeUInt32LE(samples, 40);
  out.fill(128, 44);
  return out;
}

let audioWav: Buffer | null = null;

function boardAudio(): Buffer {
  audioWav ??= longWav(AUDIO_MS / 1000);
  return audioWav;
}

type Line = { at: string; key: string; original: string; english: string };

const SPEAKERS: Record<string, string> = { speaker_0: "Interviewer", speaker_1: "Sunita", speaker_2: "Kamla", speaker_3: "Meera" };

const BOARD_LINES: Line[] = [
  { at: "17:42", key: "speaker_0", original: "छापने से पहले मिट्टी में क्या मिलाते हो?", english: "What goes into the mud before you print?" },
  { at: "17:49", key: "speaker_1", original: "तालाब की काळी माटी, चूनो, बबूल रो गोंद अर गेहूं रो भूसो।", english: "Black clay from the pond, lime, gum from the babool tree and wheat husk." },
  { at: "18:14", key: "speaker_1", original: "चूना बिना सूखतां ही फाट जावै, इण वास्ते रात नै भिगो देवां।", english: "Without the lime it cracks when it dries, so we soak it the night before." },
  { at: "18:36", key: "speaker_2", original: "म्हारी मां कम गोंद लगावती। धुपै तो सोरो पण किनारो इतो तीखो कोनी।", english: "My mother used less gum. It washes out easier but the edge is not as sharp." },
  { at: "18:58", key: "speaker_0", original: "चूना कहाँ से खरीदते हो?", english: "Where do you buy the lime?" },
  { at: "19:03", key: "speaker_1", original: "सांगानेर रै बजार सूं, महीना में एक बार।", english: "From the market in Sanganer, once a month." }
];

const LANES: [string, number, number][] = [
  ["speaker_0", 0.02, 0.06],
  ["speaker_1", 0.09, 0.1],
  ["speaker_0", 0.2, 0.04],
  ["speaker_2", 0.25, 0.05],
  ["speaker_1", 0.3, 0.1],
  ["speaker_0", 0.47, 0.03],
  ["speaker_1", 0.5, 0.12],
  ["speaker_0", 0.63, 0.04],
  ["speaker_2", 0.68, 0.16],
  ["speaker_0", 0.85, 0.06],
  ["speaker_3", 0.91, 0.08]
];

const FILLER: Record<string, [string, string][]> = {
  speaker_0: [["अगला सवाल रंग के बारे में है।", "The next question is about the colour."], ["यह कितने दिन चलता है?", "How many days does it take?"]],
  speaker_1: [["नील रा कुंड में तीन बार डुबोवां।", "We dip it in the indigo vat three times."], ["धूप में सुखावां, फेर धोवां।", "We dry it in the sun, then wash it."]],
  speaker_2: [["गरमी में माटी जल्दी सूखै।", "In summer the mud dries quickly."], ["सर्दी में माटी गाढ़ी राखां।", "In winter we keep the mud thicker."]],
  speaker_3: [["म्हैं ठप्पा धोऊं।", "I wash the blocks."], ["बच्चा भी सीखै है।", "The children learn too."]]
};

function ms(clock: string): number {
  const [m, s] = clock.split(":").map(Number);
  return (m * 60 + s) * 1000;
}

function turn(index: number, startMs: number, endMs: number, key: string, original: string, english: string) {
  return {
    id: `d${index}`,
    index,
    speakerKey: key,
    speakerName: SPEAKERS[key],
    startMs,
    endMs,
    language: "mwr",
    text: original,
    textSource: "MACHINE",
    lowConfidence: [],
    words: [],
    translation: { text: english, status: "TRANSLATED", source: "MACHINE", model: null },
    revision: 1
  };
}

function boardTurns() {
  const raw: { startMs: number; endMs: number; key: string; original: string; english: string }[] = [];
  const boardStart = ms(BOARD_LINES[0].at);
  const boardEnd = ms("19:20");
  for (const [key, from, width] of LANES) {
    let at = Math.round(from * AUDIO_MS);
    const end = Math.round((from + width) * AUDIO_MS);
    let n = 0;
    while (at < end) {
      const stop = Math.min(end, at + 24_000);
      if (stop <= boardStart || at >= boardEnd) {
        const pick = FILLER[key][n % FILLER[key].length];
        raw.push({ startMs: at, endMs: stop - 1000, key, original: pick[0], english: pick[1] });
      }
      at = stop;
      n += 1;
    }
  }
  BOARD_LINES.forEach((line, i) => {
    const startMs = ms(line.at);
    const endMs = i + 1 < BOARD_LINES.length ? ms(BOARD_LINES[i + 1].at) - 500 : startMs + 15_000;
    raw.push({ startMs, endMs, key: line.key, original: line.original, english: line.english });
  });
  raw.push({ startMs: ms("27:30"), endMs: ms("27:48"), key: "speaker_2", original: "चूनो कम हुवै तो छाप फैल जावै।", english: "With too little lime the print spreads." });
  return raw.sort((a, b) => a.startMs - b.startMs).map((line, index) => turn(index, line.startMs, line.endMs, line.key, line.original, line.english));
}

export function boardTranscript(): Json {
  return {
    mediaId: AUDIO_ID,
    title: "Section D · Materials · Group of 3",
    status: "READY",
    queueReason: null,
    statusMessage: null,
    version: 1,
    languages: [{ code: "mwr", label: "Marwari" }, { code: "en", label: "English" }],
    durationMs: AUDIO_MS,
    speakerCount: 4,
    badges: [],
    speakers: [
      { speakerKey: "speaker_0", label: "Speaker 1", name: "Interviewer", role: "RESEARCHER", personId: null, suggested: false },
      { speakerKey: "speaker_1", label: "Speaker 2", name: "Sunita Devi", role: "ARTISAN", personId: null, suggested: false },
      { speakerKey: "speaker_2", label: "Speaker 3", name: "Kamla", role: "ARTISAN", personId: null, suggested: false },
      { speakerKey: "speaker_3", label: "Speaker 4", name: "Meera", role: "ARTISAN", personId: null, suggested: false }
    ],
    turns: boardTurns(),
    summary: [],
    quotes: [],
    cost: null,
    audio: { url: null, durationMs: AUDIO_MS },
    permissions: { edit: false, retry: true, transcribeNow: false, ask: false, autodraft: false, quoteCard: false, mapSpeakers: false },
    readOnly: null,
    revision: 1,
    updatedAt: "2026-09-22T10:00:00Z"
  };
}

const VIDEO_LINES: Line[] = [
  { at: "11:32", key: "speaker_0", original: "कपड़े पर लगाने से पहले मिट्टी में क्या मिलाते हैं?", english: "What do you mix into the mud before it goes on the cloth?" },
  { at: "11:41", key: "speaker_1", original: "चूना, गोंद और गेहूं का भूसा। रात भर रखना पड़ता है।", english: "Lime, gum and the husk of wheat. It has to sit overnight." },
  { at: "12:04", key: "speaker_1", original: "फिर जहाँ ठप्पा दबाते हैं वहाँ मिट्टी नील को रोकती है, तो डिज़ाइन हल्का रहता है।", english: "Then the mud resists the indigo where we press the block, so the pattern stays pale." },
  { at: "12:31", key: "speaker_0", original: "नील के कुंड में कितनी बार डुबोते हैं?", english: "How many dips in the indigo vat?" },
  { at: "12:38", key: "speaker_1", original: "तीन से सात, खरीदार को कितना गहरा चाहिए उस पर।", english: "Three to seven, depending on how dark the buyer wants it." }
];

export function videoTranscript(): Json {
  const turns = VIDEO_LINES.map((line, i) => {
    const startMs = ms(line.at);
    const endMs = i + 1 < VIDEO_LINES.length ? ms(VIDEO_LINES[i + 1].at) - 500 : startMs + 20_000;
    return { ...turn(i, startMs, endMs, line.key, line.original, line.english), speakerName: line.key === "speaker_0" ? "Interviewer" : "Giriraj", language: "hi" };
  });
  return {
    ...boardTranscript(),
    mediaId: VIDEO_ID,
    title: "Interview_Dabu-mud-resist_Take-1",
    languages: [{ code: "hi", label: "Hindi" }, { code: "en", label: "English" }],
    durationMs: VIDEO_MS,
    speakerCount: 2,
    speakers: [
      { speakerKey: "speaker_0", label: "Speaker 1", name: "Interviewer", role: "RESEARCHER", personId: null, suggested: false },
      { speakerKey: "speaker_1", label: "Speaker 2", name: "Giriraj", role: "ARTISAN", personId: null, suggested: false }
    ],
    turns,
    audio: { url: null, durationMs: VIDEO_MS, video: true }
  };
}

function peaks(): number[] {
  return Array.from({ length: 1600 }, (_, i) => {
    const wave = 0.55 + 0.3 * Math.sin(i / 7) * Math.cos(i / 19) + 0.15 * Math.sin(i / 3.3);
    return Math.max(0.12, Math.min(1, wave));
  });
}

type Folder = { name: string; path: string; recordType: string; count: number; bytes: number };

const WORKSHOPS: Folder[] = [
  { name: "3rd Craft Toolkit Workshop", path: WORKSHOP, recordType: "workshop", count: 926, bytes: 7_150_000_000 },
  { name: "2nd Craft Toolkit Workshop", path: "workshops/2nd", recordType: "workshop", count: 1104, bytes: 8_200_000_000 },
  { name: "Jaipur Blue Pottery", path: "workshops/pottery", recordType: "workshop", count: 612, bytes: 4_100_000_000 },
  { name: "No workshop", path: "workshops/none", recordType: "category", count: 770, bytes: 3_300_000_000 }
];

const ARTISANS: Folder[] = [
  { name: "Giriraj Prasad Chhipa", path: GIRIRAJ, recordType: "artisan", count: 48, bytes: 1_331_439_861 },
  { name: "Manisha Verma", path: `${WORKSHOP}/manisha`, recordType: "artisan", count: 37, bytes: 980_000_000 },
  { name: "Nandkishor Kumawat", path: `${WORKSHOP}/nandkishor`, recordType: "artisan", count: 29, bytes: 610_000_000 },
  { name: "Deepak Prajapati", path: `${WORKSHOP}/deepak`, recordType: "artisan", count: 41, bytes: 870_000_000 }
];

export class DesignFidelityFake extends DataExplorerFake {
  constructor() {
    super();
    this.transcriptStatus = { ...this.transcriptStatus, [AUDIO_ID]: "READY", [VIDEO_ID]: "READY" };
  }

  private entryOf(seed: Seed): Json {
    return {
      kind: "file",
      name: seed.name,
      path: `${GIRIRAJ}/${seed.name}`,
      capturedAt: seed.capturedAt,
      mediaId: seed.mediaId,
      mediaType: seed.mediaType,
      sizeBytes: seed.bytes,
      url: `/e2e-files/design-${seed.mediaId}`,
      transcriptAvailable: Boolean(seed.transcript),
      transcriptPath: seed.transcript ? `/data/media/${seed.mediaId}/transcript` : undefined,
      thumbnailPath: seed.mediaType === "IMAGE" || seed.mediaType === "VIDEO" ? `/data/media/${seed.mediaId}/thumbnail` : undefined,
      previewPath: `/data/media/${seed.mediaId}/preview`,
      downloadPath: `/data/media/${seed.mediaId}/download`
    };
  }

  private folderEntry(folder: Folder): Json {
    return { kind: "folder", name: folder.name, path: folder.path, recordType: folder.recordType, count: folder.count, bytes: folder.bytes };
  }

  private designTree(path: string): Json | null {
    const crumbs = (names: [string, string][]) => names.map(([name, at]) => ({ name, path: at }));
    const taxonomies = [
      { id: "workshops", name: "Workshop", path: "workshops", description: "Each workshop and its records.", default: true },
      { id: "crafts", name: "Craft", path: "crafts", description: "Each craft and its records.", default: false },
      { id: "types", name: "Record type", path: "types", description: "Each kind of record.", default: false }
    ];
    if (path === "workshops") {
      return { path, crumbs: crumbs([["All workshops", "workshops"]]), entries: WORKSHOPS.map((folder) => this.folderEntry(folder)), nextCursor: null, total: WORKSHOPS.length, truncated: false, info: null, taxonomies, taxonomy: "workshops" };
    }
    if (path === WORKSHOP) {
      return {
        path,
        crumbs: crumbs([["All workshops", "workshops"], ["3rd Craft Toolkit Workshop", WORKSHOP]]),
        entries: ARTISANS.map((folder) => this.folderEntry(folder)),
        nextCursor: null,
        total: ARTISANS.length,
        truncated: false,
        info: { title: "3rd Craft Toolkit Workshop", fields: [{ label: "Place", value: "Bagru, Rajasthan" }, { label: "Dates", value: "14–22 Sep 2026" }] },
        taxonomies,
        taxonomy: "workshops"
      };
    }
    if (path === GIRIRAJ) {
      return {
        path,
        crumbs: crumbs([["All workshops", "workshops"], ["3rd Craft Toolkit Workshop", WORKSHOP], ["Giriraj Prasad Chhipa", GIRIRAJ]]),
        entries: giraFiles().map((seed) => this.entryOf(seed)),
        nextCursor: null,
        total: 48,
        truncated: false,
        info: {
          title: "Giriraj Prasad Chhipa",
          fields: [
            { label: "Craft", value: "Dabu hand block printing" },
            { label: "Category", value: "Textiles › Printing" },
            { label: "Location", value: "Bagru, Jaipur, Rajasthan" },
            { label: "Workshops", value: "3rd Craft Toolkit Workshop" },
            { label: "Recorded fields", value: "34 of 40" },
            { label: "Review", value: "Approved" }
          ]
        },
        taxonomies,
        taxonomy: "workshops"
      };
    }
    return null;
  }

  private designStats(path: string): Json | null {
    if (path !== GIRIRAJ) return null;
    const byType: Record<string, { files: number; bytes: number }> = {
      images: { files: 22, bytes: 61 * MB },
      videos: { files: 4, bytes: 980 * MB },
      audios: { files: 14, bytes: 196 * MB },
      documents: { files: 8, bytes: 3 * MB }
    };
    return { files: 48, bytes: 1_331_439_861, unknownSizeFiles: 0, byType, truncated: false };
  }

  override answer(method: string, path: string, query: URLSearchParams, body: Json | null): Answer | null {
    if (path === "/data/tree") {
      const tree = this.designTree(query.get("path") ?? "");
      if (tree) return { status: 200, json: tree };
    }
    if (path === "/data/stats" && method === "GET") {
      const stats = this.designStats(query.get("path") ?? "");
      if (stats) return { status: 200, json: stats };
    }
    if (path === `/transcripts/${AUDIO_ID}` && method === "GET") return { status: 200, json: boardTranscript() };
    if (path === `/transcripts/${VIDEO_ID}` && method === "GET") return { status: 200, json: videoTranscript() };
    if (path === `/data/media/${AUDIO_ID}/peaks`) return { status: 200, json: { peaks: peaks(), durationMs: AUDIO_MS, isolated: true, canIsolate: false } };
    if (path === `/data/media/${AUDIO_ID}/preview` || path === `/data/media/${AUDIO_ID}/isolated`) return { status: 200, raw: boardAudio(), contentType: "audio/wav", headers: { "accept-ranges": "bytes" } };
    const media = /^\/data\/media\/([^/]+)\/thumbnail$/.exec(path);
    if (media && (media[1].startsWith("img-") || media[1].startsWith("vid-"))) return { status: 200, raw: PNG, contentType: "image/png" };
    return super.answer(method, path, query, body);
  }

  override async install(context: BrowserContext, theme: "light" | "dark" = "light"): Promise<void> {
    await super.install(context, theme);
    await context.route(
      (url) => url.pathname.startsWith("/api/data/media/") && url.pathname.endsWith("/preview") && url.pathname.includes(AUDIO_ID),
      async (route: Route) => {
        const whole = boardAudio();
        const range = /^bytes=(\d+)-(\d*)$/.exec(route.request().headers()["range"] ?? "");
        if (!range) return route.fulfill({ status: 200, contentType: "audio/wav", headers: { "accept-ranges": "bytes" }, body: whole });
        const from = Number(range[1]);
        const to = range[2] ? Math.min(Number(range[2]), whole.length - 1) : whole.length - 1;
        return route.fulfill({ status: 206, contentType: "audio/wav", headers: { "accept-ranges": "bytes", "content-range": `bytes ${from}-${to}/${whole.length}` }, body: whole.subarray(from, to + 1) });
      }
    );
  }
}
