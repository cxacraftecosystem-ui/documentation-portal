/**
 * THE TRANSCRIPT, AS BOTH PORTALS' WEB CLIENTS READ IT: `shared/transcript-contract.json`
 * (contract "transcript-v1-api", owned by the backend stream), typed.
 *
 * `frontend/lib/transcript/**` and `frontend/components/transcript/**` are byte-identical in
 * designer-portal and Field Repository (`shared/transcript-web.sha256`, synced from designer-portal by
 * `scripts/sync_transcript_web.py`). `e2e/transcript-contract-unit.spec.ts` holds the contract's
 * example to these types and its `fixtures` to the copy renderer in `markdown.ts`, byte for byte.
 *
 * Times are integer MILLISECONDS from the start of the audio. Every route answers 404 NOT_FOUND
 * while the pipeline switch is off; the viewer then draws the legacy plain transcript.
 */

export type TranscriptStatus =
  | "WAITING_FOR_CONSENT"
  | "QUEUED"
  | "TRANSCRIBING"
  | "TRANSLATING"
  | "READY"
  | "NEEDS_ATTENTION";

export type QueueReason = "PAUSED" | "BUDGET_PAUSED" | "WINDOW" | "WAITING" | null;

export type TranslationStatus = "TRANSLATED" | "ALREADY_ENGLISH" | "STALE" | "PENDING" | "FAILED";

/** Who wrote a piece of text: the pipeline, or a researcher's correction (the machine text is kept). */
export type TextSource = "MACHINE" | "EDITED";

export type SpeakerRole = "RESEARCHER" | "ARTISAN" | "OTHER";

export type TranscriptLanguage = { code: string; label: string };

export type TranscriptBadge = { kind: "stt" | "translation"; provider: string; model: string; label: string };

export type TranscriptSpeaker = {
  speakerKey: string;
  /** The mapped name ("Nandini (researcher)"), else "Speaker 1". */
  label: string;
  name: string | null;
  role: SpeakerRole | null;
  personId: string | null;
  /** The label comes from a suggestion nobody confirmed. */
  suggested: boolean;
  /** A few seconds of this speaker (their longest turn, at most 8 s), to play when naming them. */
  sample?: { startMs: number; endMs: number } | null;
  /** Contract v2: the `speakerPalette` slot ("s0".."s7", "neutral"); from the key only, so a rename never moves it. */
  colorKey?: string;
};

export type TranscriptWord = {
  text: string;
  startMs: number;
  endMs: number;
  /** The character index of the word in the turn's `text`. */
  offset: number;
};

export type TranscriptTurn = {
  id: string;
  index: number;
  speakerKey: string | null;
  /** As the speaker's label; the copy uses this. */
  speakerName: string;
  /** Contract v2: as the speaker's `colorKey`; "neutral" with no speaker. */
  colorKey?: string;
  startMs: number;
  endMs: number;
  language: string;
  /** The current original (edited if edited, else machine). */
  text: string;
  textSource: TextSource;
  /** [start, end) code-point offsets into `text` where Scribe was unsure; [] once edited. */
  lowConfidence: [number, number][];
  /** Karaoke word timings; [] once edited. */
  words: TranscriptWord[];
  translation: { text: string | null; status: TranslationStatus; source: TextSource; model: string | null };
  revision: number;
};

export type TranscriptPermissions = {
  edit: boolean;
  retry: boolean;
  transcribeNow: boolean;
  ask: boolean;
  autodraft: boolean;
  quoteCard: boolean;
  mapSpeakers: boolean;
};

export type Transcript = {
  mediaId: string;
  /** "Interview · Giriraj Prasad Chhipa · 24 Jun 2026" — the markdown's first heading. */
  title: string;
  status: TranscriptStatus;
  queueReason: QueueReason;
  /** One calm sentence for NEEDS_ATTENTION / WAITING_FOR_CONSENT; null otherwise. */
  statusMessage: string | null;
  /** Null before the first run. */
  version: number | null;
  languages: TranscriptLanguage[];
  durationMs: number | null;
  speakerCount: number;
  badges: TranscriptBadge[];
  speakers: TranscriptSpeaker[];
  turns: TranscriptTurn[];
  summary: string[];
  quotes: { turnId: string | null; original: string; english: string; speaker: string | null }[];
  /** Admins only, else null. */
  cost: { sttUsd: number; translationUsd: number; totalUsd: number } | null;
  /** `url` is the Voice Isolator copy when `cleaned`; `originalUrl` is then the recording as made. */
  audio: { url: string | null; durationMs: number | null; cleaned?: boolean; originalUrl?: string | null };
  permissions: TranscriptPermissions;
  /** Why this reader may read but not change it (edit, speakers, re-translate, retry, auto-draft); null when they may. */
  readOnly: { code: "NOT_EDITOR" | "HELD_POST"; message: string } | null;
  /** Bumps on every edit or speaker change; sent back as baseRevision. */
  revision: number;
  updatedAt: string;
};

/** One line of a list row's preview (contract v2): plain text, the speaker a separate label. */
export type PreviewLine = { speaker: string | null; speakerKey: string | null; colorKey: string; text: string };

/** A list row's transcript preview (contract v2): 1..3 lines, and the same on one line for a label. */
export type TranscriptPreview = { text: string; lines: PreviewLine[] };

export type SpeakerCandidate = { name: string; role: SpeakerRole; personId: string | null; reason: "roster" | "researcher" | "uploader" | "most_speech" };
export type SpeakerSuggestion = { speakerKey: string; candidates: SpeakerCandidate[] };

export type SpeakerMapping = { speakerKey: string; name: string | null; role: SpeakerRole | null; personId: string | null };

export type Citation = { turnId: string; startMs: number; quote: string | null };

export type AskAnswer = { covered: boolean; answer: string; citations: Citation[]; model: string };

export type AutoDraftResult = {
  drafted: { questionId: string; answerOriginal: string; answerEnglish: string; citations: Citation[] }[];
  skipped: { questionId: string; reason: "LOCKED" | "RECORDING_PREFERRED" | "NOT_COVERED" | "ALREADY_ANSWERED" }[];
  draftsUrl: string;
};

export type QuoteCard = {
  turnIds: string[];
  original: string;
  english: string;
  speakerName: string;
  artisanName: string | null;
  craft: string | null;
  /** "24 Jun 2026" */
  date: string;
  language: TranscriptLanguage;
  clip: { startMs: number; endMs: number; url: string | null };
  markdown: string;
};

export type GlossaryTerm = {
  id: string;
  term: string;
  gloss: string | null;
  craftId: string | null;
  craftName: string | null;
  occurrences: number;
  /** At most five. */
  sightings: { mediaId: string; turnId: string | null; startMs: number | null }[];
};

export type BudgetState = "OK" | "ALERT" | "PAUSED" | "UNSET";

export type BudgetMeter = {
  month: string;
  monthlyUsd: number | null;
  spentUsd: number;
  sttUsd: number;
  translationUsd: number;
  percent: number | null;
  state: BudgetState;
  alertAt: number;
  pauseAt: number;
  pausedByBudget: boolean;
};

/** What a copy or a download carries ("copyTrack"). */
export type CopyTrack = "original" | "english" | "both";

export type ViewTab = "original" | "english" | "side";

export type DownloadFormat = "md" | "docx" | "srt" | "vtt";
