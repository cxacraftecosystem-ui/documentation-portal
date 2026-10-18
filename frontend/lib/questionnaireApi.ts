/**
 * Typed client for the questionnaire v2 API (BUILD_PLAN §1, the frozen contract, contractVersion 1).
 *
 * Paths are relative to `/api` (what `apiFetch` adds); the questionnaire routes sit under
 * `/questionnaire`, consent under `/artisans`, media under `/media`. Every POST that creates sends an
 * `Idempotency-Key`; `X-Client-Time` and `X-App-Version` are added by `apiFetch` for every call.
 *
 * Nothing here names a file: an uploaded clip's name is minted by the server at presign, and a
 * download uses the server's `downloadName`.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import {
  RepeatReasonValues,
  SessionPurposeValues,
  type AnswerAttribution,
  type AnswerOrigin,
  type ConsentBasis,
  type ConsentState,
  type ConsentVerdict,
  type RepeatReason,
  type SessionPurpose,
  type SubjectRole
} from "@/lib/shared/enums";

export const CONTRACT_VERSION = 1;
const Q = "/questionnaire";

export type ErrorCode =
  | "VALIDATION_FAILED"
  | "NOT_FOUND"
  | "FORBIDDEN"
  | "STALE_VERSION"
  | "INSTRUMENT_LOCKED"
  | "PARTICIPANT_HAS_TAKES"
  | "HAS_RECORDINGS"
  | "TITLE_IS_GENERATED"
  | "TARGET_OUTSIDE_INSTRUMENT"
  | "JOIN_CODE_UNKNOWN"
  | "MERGE_INSTRUMENT_MISMATCH"
  | "SESSION_UNKNOWN"
  | "TRANSCRIPTION_PAUSED"
  | "CONSENT_REFUSED"
  | "CONSENT_NOT_RECORDED"
  | "APPROVE_BLOCKED";

export type ContractErrorDetail = {
  code: ErrorCode;
  message: string;
  current?: unknown;
  diff?: Record<string, unknown>;
  count?: number;
};

/** The `{detail:{code,...}}` of a contract error, or null for any other failure. */
export function contractError(error: unknown): ContractErrorDetail | null {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload as { detail?: unknown } | null;
  const detail = payload && typeof payload === "object" ? payload.detail : null;
  if (detail && typeof detail === "object" && "code" in detail && typeof (detail as { code: unknown }).code === "string") {
    return detail as ContractErrorDetail;
  }
  return null;
}

export type QuestionnaireConfig = {
  contractVersion: number;
  v2Enabled: boolean;
  transcriptionPaused: boolean;
  noteMaxChars: number;
  multipartThresholdBytes: number;
  partSizeBytes: number;
  snapshotPollSeconds: number;
  joinCodeLength: number;
  minAppVersionForCreate: string | null;
};

/** What the web assumes when `/config` cannot be read: the old screens, and transcription paused. */
export const CONFIG_FALLBACK: QuestionnaireConfig = {
  contractVersion: CONTRACT_VERSION,
  v2Enabled: false,
  transcriptionPaused: true,
  noteMaxChars: 80,
  multipartThresholdBytes: 5 * 1024 * 1024,
  partSizeBytes: 8 * 1024 * 1024,
  snapshotPollSeconds: 60,
  joinCodeLength: 6,
  minAppVersionForCreate: null
};

// The vocabularies are the generated ones (shared/enums.json via scripts/generate_shared.py).
export type { AnswerOrigin, ConsentBasis, ConsentState, ConsentVerdict, RepeatReason, SessionPurpose, SubjectRole } from "@/lib/shared/enums";
export type Attribution = AnswerAttribution;
export const SESSION_PURPOSES: readonly SessionPurpose[] = SessionPurposeValues;
export const REPEAT_REASONS: readonly RepeatReason[] = RepeatReasonValues;

export type Person = { id: string; name: string };

export type SessionCreate = {
  clientKey: string;
  questionnaireId: string;
  workshopId: string | null;
  designWorkshopId: string | null;
  sessionDate: string;
  startedAt: string;
  purpose: SessionPurpose;
  place: string | null;
  languageCode: string | null;
  languageOther: string | null;
  note: string | null;
  participants: { artisanId: string }[];
  researchers?: { userId: string; role: string }[];
};

export type SessionPatch = {
  version: number;
  place?: string | null;
  languageCode?: string | null;
  languageOther?: string | null;
  note?: string | null;
  sessionDate?: string;
  workshopId?: string | null;
  designWorkshopId?: string | null;
  purpose?: SessionPurpose;
};

export type SessionParticipant = {
  artisanId: string;
  name: string;
  joinedAt: string | null;
  leftAt: string | null;
  consent: { state: ConsentState; source: string | null } | null;
};

export type Session = {
  id: string;
  clientKey: string;
  version: number;
  title: string;
  note: string | null;
  legacyTitle: string | null;
  questionnaire: { id: string; title: string };
  workshop: { id: string; name: string } | null;
  designWorkshopId: string | null;
  sessionDate: string | null;
  sessionDateEstimated: boolean;
  startedAt: string | null;
  endedAt: string | null;
  closedAt: string | null;
  state: "OPEN" | "CLOSED";
  purpose: SessionPurpose;
  origin: "NATIVE" | "LEGACY" | string;
  mode: "INDIVIDUAL" | "GROUP";
  participantCount: number;
  place: string | null;
  languageCode: string | null;
  languageOther: string | null;
  joinCode: string | null;
  joinQr: string | null;
  participants: SessionParticipant[];
  researchers: { userId: string; name: string; role: string }[];
  status: string;
  submittedAt: string | null;
  reviewReasons: string[];
  counts: { sections: number; takes: number; needsDecision: number };
};

export type RecordingTarget = {
  targetKey: string;
  sectionId: string | null;
  questionLineageId: string | null;
  askedQuestionId: string | null;
  displayCode: string | null;
  label: string;
};

export type RecordingSubject = {
  artisanId: string;
  role: SubjectRole;
  named: boolean;
  repeatReason: RepeatReason | null;
  flag: string | null;
  position: number | null;
};

export type TranscriptState = {
  status: string;
  paused: boolean;
  text: string | null;
  language: string | null;
};

export type Recording = {
  id: string;
  mediaFileId: string;
  clientClipKey: string;
  sessionId: string;
  version: number;
  capturedAt: string;
  capturedAtSource: string;
  estimated: boolean;
  durationMs: number | null;
  rosterCount: number;
  recordedBy: Person;
  targets: RecordingTarget[];
  subjects: RecordingSubject[];
  continuationOfId: string | null;
  filing: "FILED" | "NEEDS_DECISION" | string;
  decisionKind: string | null;
  provenance: string;
  languageCode: string | null;
  removedAt: string | null;
  downloadName: string;
  transcript: TranscriptState;
  consent: { verdict: ConsentVerdict; source: string | null };
};

export type Answer = {
  id: string;
  version: number;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  pinned: boolean;
  sourceRecordingId: string | null;
  artisanId?: string;
  questionLineageId?: string;
};

export type CoverageState =
  | "NOT_RECORDED"
  | "RECORDED"
  | "PARTIAL"
  | "VIA_SECTION"
  | "UPLOADING"
  | "ON_DEVICE"
  | "NEEDS_DECISION"
  | "MARKED_COMPLETE"
  | "NEEDS_REDO";

/** `GET /coverage` and `SessionDetail.grid`, as the contract sends it. */
export type CoverageGrid = {
  questionnaireId: string;
  artisans: { artisanId: string; name: string }[];
  sections: {
    sectionId: string;
    code: string;
    title: string;
    cells: {
      artisanId: string;
      state: CoverageState;
      takes: number;
      covered: number | null;
      total: number | null;
      needsDecision: boolean;
      override: string | null;
    }[];
  }[];
};

/** The screens' view of a grid: sections (with their questions, where known) and flat cells. */
export type CoverageGridCell = {
  artisanId: string;
  targetKey: string;
  state: CoverageState;
  count: number;
  partial?: { done: number; of: number } | null;
};
export type GridQuestion = { lineageId: string; displayCode: string; prompt: string };
export type GridView = {
  sections: { id: string; code: string; title: string; questions?: GridQuestion[] }[];
  cells: CoverageGridCell[];
};

/** Contract grid → view grid; the questions come from the instrument's sections where loaded. */
export function toGridView(grid: CoverageGrid | null | undefined, questions: Record<string, GridQuestion[]> = {}): GridView {
  const sections = grid?.sections ?? [];
  return {
    sections: sections.map((s) => ({ id: s.sectionId, code: s.code, title: s.title, questions: questions[s.sectionId] })),
    cells: sections.flatMap((s) =>
      s.cells.map((c) => ({
        artisanId: c.artisanId,
        targetKey: `S:${s.sectionId}`,
        state: c.needsDecision ? "NEEDS_DECISION" : c.override === "MARKED_COMPLETE" || c.override === "NEEDS_REDO" ? (c.override as CoverageState) : c.state,
        count: c.takes,
        partial: c.covered !== null && c.total !== null && c.state === "PARTIAL" ? { done: c.covered, of: c.total } : null
      }))
    )
  };
}

export type OpenMatchItem = { id: string; title: string; startedAt: string | null; researcher: Person | null; artisanNames: string[] };

export type SessionDetail = Session & { recordings: Recording[]; answers: Answer[]; grid: CoverageGrid };
export type SessionList = { items: Session[]; nextCursor: string | null; capped: boolean };

export type CoverageTake = {
  recordingId: string;
  position: number;
  capturedAt: string;
  estimated: boolean;
  durationMs: number | null;
  recordedBy: Person;
  session: { id: string; date: string | null; rosterCount: number };
  sameSession: boolean;
  mine: boolean;
  repeatReason: RepeatReason | null;
};

export type CoverageCheckRequest = {
  questionnaireId: string;
  artisanIds: string[];
  targets: ({ sectionId: string } | { questionLineageId: string })[];
  sessionId?: string | null;
  contentSha256?: string | null;
};

export type CoverageCheckResponse = {
  asOf: string;
  identical: { recordingId: string; sessionId: string; sameSession: boolean } | null;
  targets: {
    targetKey: string;
    label: string;
    perArtisan: { artisanId: string; state: CoverageState; viaSection: boolean; takes: CoverageTake[] }[];
  }[];
  suggestedReason: RepeatReason | null;
};

export type SnapshotTake = {
  recordingId: string;
  sessionId: string;
  artisanId: string;
  targetKey: string;
  role: SubjectRole;
  capturedAt: string;
  estimated: boolean;
  filing: string;
  position: number;
};
export type Snapshot = { asOfSeq: string; takes: SnapshotTake[]; removedRecordingIds: string[] };

export type PresignRequest = {
  linkedRecordType: "questionnaire_session";
  sessionId: string;
  clientClipKey: string;
  sizeBytes: number;
  contentType: string;
  extension: string;
  contentSha256: string | null;
  targets: ({ sectionId: string } | { questionLineageId: string })[];
  subjectArtisanIds: string[];
  deviceCapturedAt: string;
  clockOffsetMs: number | null;
};
export type PresignResponse = { objectKey: string; url: string; headers: Record<string, string>; expiresAt: string };
export type MultipartCreateResponse = { objectKey: string; uploadId: string; partSize: number; partsDone: UploadPart[] };
export type UploadPart = { partNumber: number; etag: string };
export type UploadState = {
  objectKey: string;
  multipartUploadId: string | null;
  partSize: number;
  partsDone: UploadPart[];
  closed: boolean;
};

export type CompleteRecording = {
  targets: ({ sectionId: string } | { questionLineageId: string })[];
  subjects: { artisanId: string; role: SubjectRole; named: boolean; repeatReason: RepeatReason | null }[];
  deviceCapturedAt: string;
  clockOffsetMs: number | null;
  capturedAtSource: string;
  durationMs: number | null;
  rosterCount: number;
  contentSha256: string | null;
  continuationOfId: string | null;
  replacesRecordingId: string | null;
  ackedRecordingIds: string[];
};
export type CompleteRequest = {
  objectKey: string;
  clientClipKey: string;
  linkedRecordType: "questionnaire_session";
  linkedRecordId: string;
  recording: CompleteRecording;
};

export type AnswerItem = {
  artisanId: string;
  questionLineageId: string;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  sessionId: string;
  sourceRecordingId: string | null;
  baseVersion?: number;
};
export type AnswerResult = {
  artisanId: string;
  questionLineageId: string;
  status: "SAVED" | "UNCHANGED" | "CONFLICT" | "FORBIDDEN";
  answer: Answer | null;
  current: (Answer & { editedBy?: Person; editedAt?: string }) | null;
};

export type ConsentRecord = {
  artisanId: string;
  purpose: "EXTERNAL_PROCESSING";
  state: ConsentState;
  basis: ConsentBasis | null;
  source: string | null;
  recordedAt: string | null;
  recordedBy: Person | null;
};
export type ConsentPost = {
  purpose: "EXTERNAL_PROCESSING";
  state: "GRANTED" | "REFUSED";
  basis: ConsentBasis;
  note: string | null;
  evidenceMediaId: string | null;
  clientKey: string;
};

export type InboxItem = { id: string; label: string; sessionId?: string | null; createdAt?: string; candidates?: { id: string; label: string }[] };
export type Inbox = {
  toFile: InboxItem[];
  needsDecision: InboxItem[];
  legacy: InboxItem[];
  setDates: InboxItem[];
  noParticipants: InboxItem[];
  emptySessions: InboxItem[];
  primaryChanged: InboxItem[];
  counts: { total: number; olderThan24h: number };
};

export type RecordSession = { session: Session; recordings: Recording[]; uncertain: Recording[] };
export type RecordQuestion = {
  displayCode: string;
  prompt?: string;
  answer: Answer | null;
  primaryRecordingId: string | null;
  otherRecordingIds: string[];
  accountsDiffer: boolean;
  recordedUnderEarlierWording: boolean;
};
export type ArtisanRecord = {
  artisanId: string;
  instruments: {
    questionnaireId: string;
    title: string;
    summary: { sectionsCovered: number; sectionsTotal: number; sessions: number; repeats: number; open: string[] };
    sessions: RecordSession[];
    document: { sectionCode: string; questions: RecordQuestion[] }[];
    retired: RecordQuestion[];
  }[];
};

/** A fresh key for `clientKey`, `clientClipKey` and `Idempotency-Key`. */
export function mintKey(prefix: string): string {
  const random =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `${prefix}-${random}`;
}

function json(method: string, body: unknown, idempotencyKey?: string): RequestInit {
  const headers: Record<string, string> = {};
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  return { method, body: body === undefined ? undefined : JSON.stringify(body), headers };
}

export const questionnaireApi = {
  config: () => apiFetch<QuestionnaireConfig>(`${Q}/config`),

  openMatch: (params: { questionnaireId: string; workshopId?: string | null; artisanIds: string[]; date: string }) =>
    apiFetch<{ items: OpenMatchItem[] }>(
      `${Q}/sessions/open-match${buildQuery({
        questionnaireId: params.questionnaireId,
        workshopId: params.workshopId ?? undefined,
        artisanIds: params.artisanIds.join(","),
        date: params.date
      })}`
    ),
  /** Always creates; the body's `clientKey` doubles as the Idempotency-Key, so a replay lands once. */
  createSession: (body: SessionCreate) => apiFetch<Session>(`${Q}/sessions`, json("POST", body, body.clientKey)),
  listSessions: (params: Record<string, string | number | undefined | null>) =>
    apiFetch<SessionList>(`${Q}/sessions${buildQuery(params)}`),
  getSession: (id: string) => apiFetch<SessionDetail>(`${Q}/sessions/${encodeURIComponent(id)}`),
  patchSession: (id: string, body: SessionPatch) => apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}`, json("PATCH", body)),
  addParticipant: (id: string, artisanId: string, key = mintKey("part")) =>
    apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}/participants`, json("POST", { artisanId }, key)),
  patchParticipant: (id: string, artisanId: string, body: { joinedAt?: string | null; leftAt?: string | null }) =>
    apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}/participants/${encodeURIComponent(artisanId)}`, json("PATCH", body)),
  removeParticipant: (id: string, artisanId: string, detach = false) =>
    apiFetch<Session>(
      `${Q}/sessions/${encodeURIComponent(id)}/participants/${encodeURIComponent(artisanId)}${detach ? "?detach=true" : ""}`,
      { method: "DELETE" }
    ),
  addResearcher: (id: string, body: { userId: string; role: string }) =>
    apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}/researchers`, json("POST", body, mintKey("res"))),
  join: (joinCode: string) => apiFetch<Session>(`${Q}/sessions/join`, json("POST", { joinCode })),
  finish: (id: string) => apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}/finish`, json("POST", {})),
  mergeInto: (id: string, targetId: string) =>
    apiFetch<Session>(`${Q}/sessions/${encodeURIComponent(id)}/merge-into/${encodeURIComponent(targetId)}`, json("POST", {})),
  continueSessions: (body: { fromSessionId: string; artisanIds: string[]; split: "ONE" | "EACH"; sessionDate: string }) =>
    apiFetch<{ sessions: Session[]; firstGap: Record<string, string | null> }>(`${Q}/sessions/continue`, json("POST", body, mintKey("cont"))),
  deleteSession: (id: string) => apiFetch<void>(`${Q}/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }),
  insights: (id: string) => apiFetch<{ items: unknown[] }>(`${Q}/sessions/${encodeURIComponent(id)}/insights`),

  checkCoverage: (body: CoverageCheckRequest) => apiFetch<CoverageCheckResponse>(`${Q}/coverage/check`, json("POST", body)),
  coverage: (params: { questionnaireId: string; workshopId?: string; artisanIds?: string[] }) =>
    apiFetch<CoverageGrid>(
      `${Q}/coverage${buildQuery({
        questionnaireId: params.questionnaireId,
        workshopId: params.workshopId,
        artisanIds: params.artisanIds?.join(",")
      })}`
    ),
  snapshot: (params: { questionnaireId: string; workshopId?: string | null; sinceSeq?: string | null }) =>
    apiFetch<Snapshot>(
      `${Q}/coverage/snapshot${buildQuery({
        questionnaireId: params.questionnaireId,
        workshopId: params.workshopId ?? undefined,
        sinceSeq: params.sinceSeq ?? undefined
      })}`
    ),

  patchRecording: (id: string, body: Record<string, unknown> & { version: number }) =>
    apiFetch<Recording>(`${Q}/recordings/${encodeURIComponent(id)}`, json("PATCH", body)),
  removeRecording: (id: string) => apiFetch<Recording>(`${Q}/recordings/${encodeURIComponent(id)}`, { method: "DELETE" }),
  restoreRecording: (id: string) => apiFetch<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/restore`, json("POST", {})),
  decide: (id: string, body: { action: "KEEP_BOTH" | "KEEP_THIS" | "CONTINUATION"; otherRecordingId?: string }) =>
    apiFetch<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/decide`, json("POST", body)),
  makePrimary: (id: string, body: { artisanId: string; targetKey: string }) =>
    apiFetch<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/make-primary`, json("POST", body)),
  answerProposals: (id: string) =>
    apiFetch<{ proposals: { questionLineageId: string; artisanIds: string[] | null; text: string; origin: AnswerOrigin; existing: Answer[] }[] }>(
      `${Q}/recordings/${encodeURIComponent(id)}/answer-proposals`,
      json("POST", {})
    ),
  /** While paused the server answers 202 `{status:"QUEUED",paused:true}`; the UI never offers it then. */
  transcribe: (id: string) =>
    apiFetch<{ status: string; paused: boolean }>(`${Q}/recordings/${encodeURIComponent(id)}/transcribe`, json("POST", {})),
  /** The server 302s to a signed URL whose Content-Disposition is the recording's `downloadName`. */
  downloadHref: (id: string) => `/api${Q}/recordings/${encodeURIComponent(id)}/download`,

  putAnswers: (items: AnswerItem[]) => apiFetch<{ results: AnswerResult[] }>(`${Q}/answers`, json("PUT", { items })),
  inbox: () => apiFetch<Inbox>(`${Q}/inbox`),

  getConsent: (artisanId: string) => apiFetch<ConsentRecord>(`/artisans/${encodeURIComponent(artisanId)}/consent`),
  postConsent: (artisanId: string, body: ConsentPost) =>
    apiFetch<ConsentRecord>(`/artisans/${encodeURIComponent(artisanId)}/consent`, json("POST", body, body.clientKey)),
  artisanRecord: (artisanId: string, questionnaireId?: string) =>
    apiFetch<ArtisanRecord>(`/artisans/${encodeURIComponent(artisanId)}/record${buildQuery({ questionnaireId })}`),
  artisanRecordCsvHref: (artisanId: string, questionnaireId?: string) =>
    `/api/artisans/${encodeURIComponent(artisanId)}/record.csv${buildQuery({ questionnaireId })}`,

  presign: (body: PresignRequest) => apiFetch<PresignResponse>(`/media/presign`, json("POST", body)),
  multipartCreate: (body: PresignRequest) => apiFetch<MultipartCreateResponse>(`/media/multipart/create`, json("POST", body)),
  multipartPresignParts: (body: { objectKey: string; uploadId: string; partNumbers: number[] }) =>
    apiFetch<{ parts: { partNumber: number; url: string }[] }>(`/media/multipart/presign-parts`, json("POST", body)),
  multipartComplete: (body: { objectKey: string; uploadId: string; parts: UploadPart[] }) =>
    apiFetch<{ objectKey: string }>(`/media/multipart/complete`, json("POST", body)),
  /** 404 (an ApiError) when the server holds no intent for this clip. */
  uploadState: (clientClipKey: string) => apiFetch<UploadState>(`/media/uploads/${encodeURIComponent(clientClipKey)}`),
  /** Idempotent on `clientClipKey`: 201 the first time, 200 on a replay, the same Recording either way. */
  complete: (body: CompleteRequest) => apiFetch<Recording>(`/media/complete`, json("POST", body, body.clientClipKey))
};

export type QuestionnaireApi = typeof questionnaireApi;
