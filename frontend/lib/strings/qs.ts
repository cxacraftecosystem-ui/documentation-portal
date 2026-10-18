/**
 * `qs(key, vars)` — every questionnaire v2 string on the web.
 *
 * The words come from `lib/strings/questionnaire.ts`, which `scripts/generate_shared.py` generates
 * from `shared/strings/questionnaire.json` (BUILD_PLAN §0.1), so web and Android print the same copy.
 * `WEB_ONLY_STRINGS` holds the few web labels the shared table does not carry yet (table headings,
 * the sessions list chrome, grid state words); each is listed in the hand-off for the BACKEND stream
 * to fold into the shared table, after which it moves out of here. A key in both is a spec failure
 * (`e2e/shared-strings-unit.spec.ts`).
 */

import { questionnaireStrings } from "@/lib/strings/questionnaire";

export const WEB_ONLY_STRINGS = {
  loading: "Loading…",
  listDescription: "Interview sessions, newest first.",
  searchSessions: "Search sessions",
  listLoadError: "Couldn't load sessions.",
  listCapped: "Showing the latest {count}.",
  showMore: "Show more",
  builder: "Builder",
  colResearchers: "Researchers",
  colCoverage: "Coverage",
  colStatus: "Status",
  listCoverage: "{sections} sections · {takes} takes",
  fieldArtisan: "Artisan",
  startNeeds: "Choose the questionnaire, date and artisans.",
  consentMore: "More",
  consentSaved: "Consent recorded.",
  consentRefusedSaved: "Recorded as refused.",
  coverageTitle: "Coverage",
  coverageDescription: "Sections recorded per artisan.",
  inboxEmpty: "Nothing to sort out.",
  sessionLoadError: "Couldn't load the session.",
  legend: "● recorded  ●2 twice  ◐ partial  ◎ uploading  ⚠ needs decision  ○ none",
  stateNotRecorded: "Not recorded",
  stateRecorded: "Recorded",
  statePartial: "Partial",
  stateViaSection: "Via section take",
  stateUploading: "Uploading",
  stateMarkedComplete: "Marked complete",
  stateNeedsRedo: "Needs redo",
  download: "Download",
  transcriptQueued: "Queued",
  transcriptionFailed: "Transcription failed",
  flagsNoEstimate: "{repeats} repeats · {decisions} needs a decision",
  inboxUnnamedClip: "Unnamed clip",
  inboxDecisionRow: "Takes for {names}, {when}",
  inboxNoOneNamed: "no one named",
  inboxLegacyRow: "Recorded {date}. The question has changed since.",
  inboxSetDatesRow: "{count} legacy sessions",
  inboxNoArtisansRow: "Session of {date} has no artisans",
  inboxEmptyRow: "Empty session, approved {date}",
  inboxPrimaryRow: "A main take changed after a late upload, {date}",
  inboxPickArtisans: "Who was there",
  inboxBadge: "{count} in the inbox",
  returnNoteLabel: "What needs changing?",
  returnNeedsNote: "Say what needs changing.",
  continueSplit: "Sessions"
} as const;

type SharedKey = keyof typeof questionnaireStrings;
type WebKey = keyof typeof WEB_ONLY_STRINGS;
export type QsKey = SharedKey | WebKey;
type Vars = Record<string, string | number>;

export function qs(key: QsKey, vars: Vars = {}): string {
  const shared = (questionnaireStrings as unknown as Record<string, string | ((v: Vars) => string)>)[key];
  if (typeof shared === "function") return shared(vars);
  const text = typeof shared === "string" ? shared : WEB_ONLY_STRINGS[key as WebKey];
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

export const PURPOSE_KEY = { FIRST_SITTING: "purposeFirstSitting", FOLLOW_UP: "purposeFollowUp", REDO: "purposeRedo" } as const satisfies Record<string, QsKey>;
export const REASON_KEY = { REDO: "reasonRedo", FOLLOW_UP: "reasonFollowUp", RE_INTERVIEW: "reasonReInterview", NEW_SETTING: "reasonNewSetting" } as const satisfies Record<string, QsKey>;
