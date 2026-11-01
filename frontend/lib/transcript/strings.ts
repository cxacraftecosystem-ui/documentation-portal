import SHARED from "./strings.json";

/**
 * The viewer's words. `strings.json` is a byte-identical copy of `shared/strings/transcript.json`
 * (the backend stream's file; Android copies the same keys), held equal by
 * `e2e/transcript-contract-unit.spec.ts`. `WEB` holds the few words only a web page needs (a menu
 * button's spoken name, a reload, a disclosure); a key here never shadows a shared one.
 */
const WEB = {
  transcript: "Transcript",
  tabsLabel: "Transcript view",
  copyFailed: "Couldn't copy. Select the text and copy it instead.",
  copyTurnLabel: "Copy {speaker} at {time}",
  selectTurn: "Select {speaker} at {time}",
  editTurnLabel: "Edit {speaker} at {time}",
  doneSelecting: "Done",
  downloadFailed: "The download didn't finish. Try again.",
  play: "Play",
  pause: "Pause",
  saving: "Saving…",
  speakerOther: "Someone else",
  speakerNamePlaceholder: "Name",
  loading: "Loading transcript…",
  loadFailed: "Couldn't load the transcript.",
  tryAgain: "Try again",
  noTurns: "No speech in this recording.",
  showTranscript: "Show transcript",
  askThinking: "Reading the transcript…",
  autodraftWorking: "Drafting answers…",
  autodraftReview: "Review in the form",
  dismiss: "Dismiss",
  quotePreview: "Quote card",
  close: "Close",
  glossaryEmpty: "No craft terms heard yet.",
  glossaryMore: "Show more",
  viewModeLabel: "Layout",
  viewTurns: "Turns",
  viewReading: "Reading",
  copyView: "Copy this view",
  markAll: "Mark all",
  clearMarks: "Clear marks",
  followAlong: "Follow along"
} as const;

export type TranscriptStringKey = keyof typeof SHARED.strings | keyof typeof WEB;

export function ts(key: TranscriptStringKey, vars?: Record<string, string | number>): string {
  const shared = SHARED.strings as Record<string, string>;
  const template: string = key in shared ? shared[key] : (WEB as Record<string, string>)[key];
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

export const WEB_STRINGS: Readonly<Record<string, string>> = WEB;
