export type TranscriptStatus =
  | "WAITING_FOR_CONSENT"
  | "QUEUED"
  | "TRANSCRIBING"
  | "TRANSLATING"
  | "READY"
  | "NEEDS_ATTENTION";

export type QueueReason = "PAUSED" | "BUDGET_PAUSED" | "WINDOW" | "WAITING" | null;

export type TranslationStatus = "TRANSLATED" | "ALREADY_ENGLISH" | "STALE" | "PENDING" | "FAILED";

export type TextSource = "MACHINE" | "EDITED";

export type SpeakerRole = "RESEARCHER" | "ARTISAN" | "OTHER";

export type TranscriptLanguage = { code: string; label: string };

export type TranscriptBadge = { kind: "stt" | "translation"; provider: string; model: string; label: string };

export type TranscriptSpeaker = {
  speakerKey: string;
  label: string;
  name: string | null;
  role: SpeakerRole | null;
  personId: string | null;
  suggested: boolean;
  sample?: { startMs: number; endMs: number } | null;
  colorKey?: string;
};

export type TranscriptWord = {
  text: string;
  startMs: number;
  endMs: number;
  offset: number;
};

export type TranscriptTurn = {
  id: string;
  index: number;
  speakerKey: string | null;
  speakerName: string;
  colorKey?: string;
  startMs: number;
  endMs: number;
  language: string;
  text: string;
  textSource: TextSource;
  lowConfidence: [number, number][];
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
  addEnglish?: boolean;
  retranscribe?: boolean;
};

export type SpokenLanguageView = {
  chosen: string;
  chosenLabel: string;
  requested: string | null;
  requestedLabel: string | null;
  detected: string | null;
  detectedLabel: string | null;
  probability: number | null;
  uncertain: boolean;
  retranscribing: boolean;
  estimateMinutes: number;
  estimateUsd: number | null;
};

export type SpokenLanguageSuggestion = {
  suggested: { code: string; label: string; source: "artisan" | "researcher" } | null;
  uncertainBelow?: number;
};

export type EnglishNotice = "DECLINED" | "UNAVAILABLE" | null;

export type Transcript = {
  mediaId: string;
  title: string;
  status: TranscriptStatus;
  queueReason: QueueReason;
  statusMessage: string | null;
  version: number | null;
  languages: TranscriptLanguage[];
  spokenLanguage?: SpokenLanguageView | null;
  durationMs: number | null;
  speakerCount: number;
  badges: TranscriptBadge[];
  speakers: TranscriptSpeaker[];
  turns: TranscriptTurn[];
  summary: string[];
  quotes: { turnId: string | null; original: string; english: string; speaker: string | null }[];
  cost: { sttUsd: number; translationUsd: number; totalUsd: number } | null;
  audio: { url: string | null; durationMs: number | null; cleaned?: boolean; originalUrl?: string | null; video?: boolean };
  permissions: TranscriptPermissions;
  englishNotice?: EnglishNotice;
  manualEnglish?: string | null;
  readOnly: { code: "NOT_EDITOR" | "HELD_POST"; message: string } | null;
  revision: number;
  updatedAt: string;
};

export type PreviewLine = { speaker: string | null; speakerKey: string | null; colorKey: string; text: string };

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

export type CopyTrack = "original" | "english" | "both";

export type ViewTab = "original" | "english" | "side";

export type DownloadFormat = "md" | "docx" | "srt" | "vtt";
