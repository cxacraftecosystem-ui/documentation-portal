import type { QueueReason, Transcript, TranscriptStatus } from "./contract";
import { ts, type TranscriptStringKey } from "./strings";

/**
 * A status chip and its ONE action (contract enums.pipelineStatus): QUEUED offers Transcribe now to
 * an admin, NEEDS_ATTENTION offers Retry to whoever may edit. Nothing else gets a button.
 */
export type StatusTone = "neutral" | "progress" | "done" | "error";

const LABEL: Record<TranscriptStatus, TranscriptStringKey> = {
  WAITING_FOR_CONSENT: "statusWaitingForConsent",
  QUEUED: "statusQueued",
  TRANSCRIBING: "statusTranscribing",
  TRANSLATING: "statusTranslating",
  READY: "statusReady",
  NEEDS_ATTENTION: "statusNeedsAttention"
};

const TONE: Record<TranscriptStatus, StatusTone> = {
  WAITING_FOR_CONSENT: "neutral",
  QUEUED: "neutral",
  TRANSCRIBING: "progress",
  TRANSLATING: "progress",
  READY: "done",
  NEEDS_ATTENTION: "error"
};

const QUEUE: Record<Exclude<QueueReason, null>, TranscriptStringKey> = {
  PAUSED: "queuePaused",
  BUDGET_PAUSED: "queueBudgetPaused",
  WINDOW: "queueWindow",
  WAITING: "queueWaiting"
};

export type StatusAction = "retry" | "transcribeNow" | null;

export const statusLabel = (status: TranscriptStatus) => ts(LABEL[status]);
export const statusTone = (status: TranscriptStatus) => TONE[status];

/** The one line under the chip: the server's sentence, else why it is queued. */
export function statusNote(transcript: Pick<Transcript, "status" | "queueReason" | "statusMessage">): string | null {
  if (transcript.statusMessage) return transcript.statusMessage;
  if (transcript.status === "QUEUED" && transcript.queueReason) return ts(QUEUE[transcript.queueReason]);
  if (transcript.status === "WAITING_FOR_CONSENT") return ts("consentRefused");
  return null;
}

export function statusAction(transcript: Pick<Transcript, "status" | "permissions">): StatusAction {
  if (transcript.status === "NEEDS_ATTENTION" && transcript.permissions.retry) return "retry";
  if (transcript.status === "QUEUED" && transcript.permissions.transcribeNow) return "transcribeNow";
  return null;
}

/** Still moving: the viewer polls while this is true. */
export const inFlight = (status: TranscriptStatus) => status === "QUEUED" || status === "TRANSCRIBING" || status === "TRANSLATING";
