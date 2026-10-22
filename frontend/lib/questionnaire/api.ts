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
} from "@/lib/questionnaire/enums";
import { normaliseInbox, type Inbox } from "@/lib/questionnaire/inbox";

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
  | "APPROVE_BLOCKED"
  // The unification's codes (UNIFY 2.2).
  | "ANSWER_LOCKED"
  | "FORM_DELETED"
  | "RESPONDENT_HAS_ROWS"
  | "BUNDLE_VERSION";

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
  /** Which portal answered (UNIFY 4.1). */
  portal?: "designer-portal" | "documentation-portal";
  /** The create cutoff per platform; a lower build number gets 503 and the update banner (P14). */
  minVersionCode?: { android: number | null; web: number | null };
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
export type { AnswerOrigin, ConsentBasis, ConsentState, ConsentVerdict, RepeatReason, SessionPurpose, SubjectRole } from "@/lib/questionnaire/enums";
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
  /** Guests answering without an artisan record (owner decision 1); participants may then be empty. */
  respondents?: { name: string; kind: RespondentKind; notes?: string | null }[];
};

export type RespondentKind = "GUEST" | "SELF";
/** A guest respondent: a named person who is not a registered artisan, or the signed-in user (SELF). */
export type Respondent = {
  id: string;
  sessionId: string;
  name: string;
  kind: RespondentKind;
  notes: string | null;
  userId: string | null;
  artisanId?: string | null;
  registeredAt?: string | null;
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
  /** Null on every session the backfill rebuilt; the outbox keys those on `id` (audit C4). */
  clientKey: string | null;
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
  /** Guests on this session (owner decision 1). Absent on servers that predate them. */
  respondents?: Respondent[];
  source?: "APP" | "UPLOAD" | "HANDOFF" | string;
  /** Set on a session in the content admins' Deleted list. */
  deletedAt?: string | null;
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

export type RecordingGuest = { respondentId: string; name: string; role: SubjectRole; named: boolean; subjectKey: string };

export type Recording = {
  /** Guest respondents on the take (owner decision 1). */
  respondents?: RecordingGuest[];
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
  artisanId?: string | null;
  /** A guest's answer (owner decision 1). */
  respondentId?: string | null;
  questionLineageId?: string;
  /** Who wrote the current revision: the lock's "Answered by" (P15). */
  actorId?: string | null;
  /** The answer note (A20). */
  note?: string | null;
};

/** Whose answer: an artisan's, or a guest respondent's (exactly one). */
export type AnswerOwner = { artisanId?: string | null; respondentId?: string | null };

/** The subject key the server uses (UNIFY 2.3 rule 1): `A:<artisanId>` or `R:<respondentId>`. */
export function ownerKey(owner: AnswerOwner): string {
  return owner.artisanId ? `A:${owner.artisanId}` : `R:${owner.respondentId ?? ""}`;
}

/** One revision of an answer, newest first (B21). */
export type AnswerRevision = {
  id: string;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  sessionId: string | null;
  sourceRecordingId: string | null;
  status: string | null;
  actor: { id: string | null; name: string };
  createdAt: string | null;
  current: boolean;
};

/** `GET /questionnaire/form-for`: the start sheet's form, by the shared order (UNIFY 2.3 rule 5). */
export type FormFor = {
  questionnaireId: string | null;
  reason: "EXPLICIT" | "PRIMARY_BINDING" | "ONLY_BINDING" | "TYPE_DEFAULT" | "GLOBAL_DEFAULT" | "PICKER" | string;
  bound: { questionnaireId: string; isPrimary: boolean; kind: string | null }[];
  boundByKind: { kind: string | null; questionnaireIds: string[] }[];
};

export type Binding = { workshopId: string | null; designWorkshopId: string | null; isPrimary: boolean };

/** `GET /questionnaire/forms/{id}/structure`: sections and questions with lineages, codes and required marks. */
export type FormQuestion = {
  id: string;
  prompt: string;
  helpText: string | null;
  sortOrder: number;
  isActive: boolean;
  isRequired: boolean;
  lineageId: string;
  displayCode: string | null;
  artisanField: string | null;
};
export type FormStructure = {
  questionnaireId: string;
  title: string;
  kind: string | null;
  isActive: boolean;
  version: number;
  sections: { id: string; code: string; title: string; sortOrder: number; questions: FormQuestion[] }[];
};

/** `GET /questionnaire/annexure`: the forms bound to a workshop grouped by kind, with live sittings (A12). */
export type Annexure = {
  designWorkshopId: string | null;
  groups: {
    kind: string | null;
    label: string;
    forms: {
      questionnaireId: string;
      title: string;
      kind: string | null;
      version: number;
      questionCount: number;
      answeredTotal: number;
      emptySittings: number;
      sittings: {
        id: string;
        label: string;
        source: string;
        recordedAt: string | null;
        recordedBy: string | null;
        notes: string | null;
        answers: { prompt: string; answer: string; notes: string | null; sectionCode: string | null; retired: boolean }[];
      }[];
    }[];
  }[];
};

/** A take's history (B40). */
export type RecordingEvent = { id: string; kind: string; before: unknown; after: unknown; actor: { id: string | null; name: string }; at: string | null };

export type CoverageState =
  | "NOT_RECORDED"
  | "RECORDED"
  | "PARTIAL"
  | "VIA_SECTION"
  | "UPLOADING"
  | "ON_DEVICE"
  | "NEEDS_DECISION"
  | "MARKED_COMPLETE"
  | "NEEDS_REVIEW"
  | "NEEDS_REDO";

/** The admin override per section and subject (owner, amendment 2 point 1: three states). */
export type SectionOverride = "MARKED_COMPLETE" | "NEEDS_REVIEW" | "NEEDS_REDO";
export const SECTION_OVERRIDES: readonly SectionOverride[] = ["MARKED_COMPLETE", "NEEDS_REVIEW", "NEEDS_REDO"];

/** Where a covered cell's coverage comes from: a recording (preferred), or a typed answer only (✎). */
export type CoverageSource = "RECORDED" | "TYPED";

type ContractCell = {
  state: CoverageState;
  takes: number;
  covered: number | null;
  total: number | null;
  needsDecision: boolean;
  override: string | null;
  /** `A:<artisanId>` or `R:<respondentId>` (UNIFY 2.3 rule 1). */
  subjectKey?: string;
  source?: CoverageSource | null;
  recordedCount?: number;
  typedOnlyCount?: number;
  /** A typed-only cell: offer "Add a recording?", never blocking (owner, amendment 2 point 4). */
  addRecording?: boolean;
};

/** `GET /coverage` and `SessionDetail.grid`, as the contract sends it. */
export type CoverageGrid = {
  questionnaireId: string;
  artisans: { artisanId: string; name: string }[];
  /** A session's guest respondents, each a column of its own (`R:<id>`). */
  respondents?: { respondentId: string; name: string; subjectKey: string }[];
  sections: {
    sectionId: string;
    code: string;
    title: string;
    cells: (ContractCell & { artisanId: string })[];
    guestCells?: (ContractCell & { respondentId: string })[];
  }[];
};

/** The screens' view of a grid: sections (with their questions, where known) and flat cells. */
export type CoverageGridCell = {
  /** The column: an artisan's id, or `R:<respondentId>` for a guest. */
  artisanId: string;
  targetKey: string;
  state: CoverageState;
  count: number;
  partial?: { done: number; of: number } | null;
  source?: CoverageSource | null;
  addRecording?: boolean;
  /** The admin override, shown beside the data-derived state. */
  override?: SectionOverride | null;
};

/** The grid column id for a guest respondent. */
export function guestColumn(respondentId: string): string {
  return `R:${respondentId}`;
}
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
      [
        ...s.cells.map((c) => ({ ...c, column: c.artisanId })),
        ...(s.guestCells ?? []).map((c) => ({ ...c, column: guestColumn(c.respondentId) }))
      ].map((c) => {
        const override = SECTION_OVERRIDES.includes(c.override as SectionOverride) ? (c.override as SectionOverride) : null;
        return {
          artisanId: c.column,
          targetKey: `S:${s.sectionId}`,
          state: c.needsDecision ? "NEEDS_DECISION" : override ? (override as CoverageState) : c.state,
          count: c.takes,
          partial: c.covered !== null && c.total !== null && c.state === "PARTIAL" ? { done: c.covered, of: c.total } : null,
          source: c.source ?? null,
          addRecording: Boolean(c.addRecording),
          override
        };
      })
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
/** `POST /media/multipart/presign-parts`: part number (as a string key) → signed URL (audit C3). */
export type MultipartPartUrls = { urls: Record<string, string> };
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
  /** An artisan (`artisanId`) or a guest respondent (`respondentId`), exactly one. */
  subjects: { artisanId?: string; respondentId?: string; role: SubjectRole; named: boolean; repeatReason: RepeatReason | null }[];
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
  /** Exactly one owner: an artisan, or a guest respondent of the session. */
  artisanId?: string | null;
  respondentId?: string | null;
  questionLineageId: string;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  sessionId: string;
  sourceRecordingId: string | null;
  baseVersion?: number;
  /** The answer note (A20). */
  note?: string | null;
};
export type AnswerResult = {
  artisanId: string | null;
  respondentId?: string | null;
  questionLineageId: string;
  status: "SAVED" | "UNCHANGED" | "CONFLICT" | "FORBIDDEN";
  /** `ANSWER_LOCKED` on a FORBIDDEN item: someone else's answer with text (P15). */
  code?: "ANSWER_LOCKED" | string;
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

// The inbox rows are typed where they are labelled: lib/questionnaireInbox.ts.
export type { Inbox } from "@/lib/questionnaire/inbox";

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

/** The JSON fetcher the client is built over: `apiFetch` in the browser, a plain one in the stack spec. */
export type JsonFetcher = <T>(path: string, init?: RequestInit) => Promise<T>;

/**
 * The client over any fetcher. The browser uses `questionnaireApi` (over `apiFetch`); the stack spec
 * (`e2e/questionnaire-v2-stack.spec.ts`) builds the SAME client over a fetcher pointed at a real local
 * backend, so a shape the server does not send fails there rather than hiding behind a fake.
 */
export function questionnaireClient(fetchJson: JsonFetcher) {
  return {
    config: () => fetchJson<QuestionnaireConfig>(`${Q}/config`),

    openMatch: (params: { questionnaireId: string; workshopId?: string | null; designWorkshopId?: string | null; artisanIds: string[]; date: string }) =>
      fetchJson<{ items: OpenMatchItem[] }>(
        `${Q}/sessions/open-match${buildQuery({
          questionnaireId: params.questionnaireId,
          workshopId: params.workshopId ?? undefined,
          artisanIds: params.artisanIds.join(","),
          date: params.date
        })}`
      ),
    /** Always creates; the body's `clientKey` doubles as the Idempotency-Key, so a replay lands once. */
    createSession: (body: SessionCreate) => fetchJson<Session>(`${Q}/sessions`, json("POST", body, body.clientKey)),
    listSessions: (params: Record<string, string | number | undefined | null>) =>
      fetchJson<SessionList>(`${Q}/sessions${buildQuery(params)}`),
    getSession: (id: string) => fetchJson<SessionDetail>(`${Q}/sessions/${encodeURIComponent(id)}`),
    patchSession: (id: string, body: SessionPatch) => fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}`, json("PATCH", body)),
    addParticipant: (id: string, artisanId: string, key = mintKey("part")) =>
      fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/participants`, json("POST", { artisanId }, key)),
    patchParticipant: (id: string, artisanId: string, body: { joinedAt?: string | null; leftAt?: string | null }) =>
      fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/participants/${encodeURIComponent(artisanId)}`, json("PATCH", body)),
    removeParticipant: (id: string, artisanId: string, detach = false) =>
      fetchJson<Session>(
        `${Q}/sessions/${encodeURIComponent(id)}/participants/${encodeURIComponent(artisanId)}${detach ? "?detach=true" : ""}`,
        { method: "DELETE" }
      ),
    addResearcher: (id: string, body: { userId: string; role: string }) =>
      fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/researchers`, json("POST", body, mintKey("res"))),
    join: (joinCode: string) => fetchJson<Session>(`${Q}/sessions/join`, json("POST", { joinCode })),
    joinByKey: (clientKey: string) => fetchJson<Session>(`${Q}/sessions/join-by-key`, json("POST", { clientKey })),
    finish: (id: string) => fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/finish`, json("POST", {})),
    mergeInto: (id: string, targetId: string) =>
      fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/merge-into/${encodeURIComponent(targetId)}`, json("POST", {})),
    continueSessions: (body: { fromSessionId: string; artisanIds: string[]; split: "ONE" | "EACH"; sessionDate: string }) =>
      fetchJson<{ sessions: Session[]; firstGap: Record<string, string | null> }>(`${Q}/sessions/continue`, json("POST", body, mintKey("cont"))),
    /** A restorable soft delete; content admins only (owner decision 3). */
    deleteSession: (id: string) => fetchJson<void>(`${Q}/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }),
    restoreSession: (id: string) => fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/restore`, json("POST", {})),
    /** The content admins' Deleted list. */
    deletedSessions: (params: Record<string, string | number | undefined | null> = {}) =>
      fetchJson<SessionList>(`${Q}/sessions${buildQuery({ ...params, deleted: "true" })}`),
    addRespondents: (id: string, respondents: { name: string; kind: RespondentKind; notes?: string | null }[]) =>
      fetchJson<{ respondents: Respondent[] }>(`${Q}/sessions/${encodeURIComponent(id)}/respondents`, json("POST", { respondents }, mintKey("resp"))),
    /** A17: edit a guest's name and notes; an empty name retracts it ("Respondent"). */
    patchRespondent: (id: string, respondentId: string, body: { name?: string | null; notes?: string | null }) =>
      fetchJson<Respondent>(`${Q}/sessions/${encodeURIComponent(id)}/respondents/${encodeURIComponent(respondentId)}`, json("PATCH", body)),
    removeRespondent: (id: string, respondentId: string) =>
      fetchJson<void>(`${Q}/sessions/${encodeURIComponent(id)}/respondents/${encodeURIComponent(respondentId)}`, { method: "DELETE" }),
    /** Register a guest as an artisan: their answers and takes move to the artisan in one transaction. */
    registerRespondent: (respondentId: string, artisanId: string) =>
      fetchJson<Respondent>(`${Q}/respondents/${encodeURIComponent(respondentId)}/register`, json("POST", { artisanId })),

    formFor: (params: { workshopId?: string | null; designWorkshopId?: string | null; questionnaireId?: string | null }) =>
      fetchJson<FormFor>(
        `${Q}/form-for${buildQuery({
          workshopId: params.workshopId ?? undefined,
          designWorkshopId: params.designWorkshopId ?? undefined,
          questionnaireId: params.questionnaireId ?? undefined
        })}`
      ),
    formStructure: (id: string, includeRetired = false) =>
      fetchJson<FormStructure>(`${Q}/forms/${encodeURIComponent(id)}/structure${buildQuery({ includeRetired: includeRetired ? "true" : undefined })}`),
    bindings: (id: string) => fetchJson<{ questionnaireId: string; bindings: Binding[] }>(`${Q}/forms/${encodeURIComponent(id)}/bindings`),
    /** Admins: which workshops this form is bound to, and where it is the primary form. */
    putBindings: (id: string, bindings: Binding[]) =>
      fetchJson<{ questionnaireId: string; bindings: Binding[] }>(`${Q}/forms/${encodeURIComponent(id)}/bindings`, json("PUT", { bindings })),
    /** Admins: the default form for a workshop type (null clears it). */
    putTypeDefault: (body: { questionnaireId: string | null; workshopType?: string | null; designWorkshopKind?: string | null }) =>
      fetchJson<{ questionnaireId: string | null }>(`${Q}/type-defaults`, json("PUT", body)),
    /** B13, D1: content admins set or clear one artisan's override for one section (three states). */
    setSectionOverride: (body: { artisanId: string; sectionId: string; status: SectionOverride | null }) =>
      fetchJson<unknown>(`${Q}/completion`, json("PUT", body)),
    annexure: (params: { workshopId?: string | null; designWorkshopId?: string | null }) =>
      fetchJson<Annexure>(`${Q}/annexure${buildQuery({ workshopId: params.workshopId ?? undefined, designWorkshopId: params.designWorkshopId ?? undefined })}`),
    insights: (id: string) => fetchJson<{ items: unknown[] }>(`${Q}/sessions/${encodeURIComponent(id)}/insights`),

    checkCoverage: (body: CoverageCheckRequest) => fetchJson<CoverageCheckResponse>(`${Q}/coverage/check`, json("POST", body)),
    coverage: (params: { questionnaireId: string; workshopId?: string; artisanIds?: string[] }) =>
      fetchJson<CoverageGrid>(
        `${Q}/coverage${buildQuery({
          questionnaireId: params.questionnaireId,
          workshopId: params.workshopId,
          artisanIds: params.artisanIds?.join(",")
        })}`
      ),
    snapshot: (params: { questionnaireId: string; workshopId?: string | null; sinceSeq?: string | null }) =>
      fetchJson<Snapshot>(
        `${Q}/coverage/snapshot${buildQuery({
          questionnaireId: params.questionnaireId,
          workshopId: params.workshopId ?? undefined,
          sinceSeq: params.sinceSeq ?? undefined
        })}`
      ),

    patchRecording: (id: string, body: Record<string, unknown> & { version: number }) =>
      fetchJson<Recording>(`${Q}/recordings/${encodeURIComponent(id)}`, json("PATCH", body)),
    removeRecording: (id: string) => fetchJson<Recording>(`${Q}/recordings/${encodeURIComponent(id)}`, { method: "DELETE" }),
    restoreRecording: (id: string) => fetchJson<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/restore`, json("POST", {})),
    decide: (id: string, body: { action: "KEEP_BOTH" | "KEEP_THIS" | "CONTINUATION"; otherRecordingId?: string }) =>
      fetchJson<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/decide`, json("POST", body)),
    makePrimary: (id: string, body: { artisanId: string; targetKey: string }) =>
      fetchJson<Recording>(`${Q}/recordings/${encodeURIComponent(id)}/make-primary`, json("POST", body)),
    answerProposals: (id: string) =>
      fetchJson<{ proposals: { questionLineageId: string; artisanIds: string[] | null; text: string; origin: AnswerOrigin; existing: Answer[] }[] }>(
        `${Q}/recordings/${encodeURIComponent(id)}/answer-proposals`,
        json("POST", {})
      ),
    /** While paused the server answers 202 `{status:"QUEUED",paused:true}`; the UI never offers it then. */
    transcribe: (id: string) =>
      fetchJson<{ status: string; paused: boolean }>(`${Q}/recordings/${encodeURIComponent(id)}/transcribe`, json("POST", {})),
    /** The server 302s to a signed URL whose Content-Disposition is the recording's `downloadName`. */
    downloadHref: (id: string) => `/api${Q}/recordings/${encodeURIComponent(id)}/download`,

    putAnswers: (items: AnswerItem[]) => fetchJson<{ results: AnswerResult[] }>(`${Q}/answers`, json("PUT", { items })),
    /** B21: every revision of one answer, newest first. */
    answerRevisions: (answerId: string) => fetchJson<{ answerId: string; revisions: AnswerRevision[] }>(`${Q}/answers/${encodeURIComponent(answerId)}/revisions`),
    /** B40: removed takes of a session or an artisan, newest first. */
    removedTakes: (params: { sessionId?: string; artisanId?: string }) =>
      fetchJson<{ items: Recording[] }>(`${Q}/recordings/removed${buildQuery({ sessionId: params.sessionId, artisanId: params.artisanId })}`),
    recordingEvents: (id: string) => fetchJson<{ recordingId: string; events: RecordingEvent[] }>(`${Q}/recordings/${encodeURIComponent(id)}/events`),
    inbox: () => fetchJson<Partial<Inbox>>(`${Q}/inbox`).then(normaliseInbox),
    /**
     * "File it" (DESIGN §4.7, audit H14): a clip in "To file" becomes a recording of the session,
     * target and subjects picked in the inbox (`POST /questionnaire/media/{mediaId}/file`).
     */
    fileClip: (
      mediaId: string,
      body: { sessionId: string; targets: ({ sectionId: string } | { questionLineageId: string })[]; subjects: { artisanId: string; role: string }[] }
    ) => fetchJson<Recording>(`${Q}/media/${encodeURIComponent(mediaId)}/file`, json("POST", body)),
    /** "Not needed" on a clip in "To file", stored on the server so every device agrees (audit M22). */
    dismissClip: (mediaId: string) => fetchJson<void>(`${Q}/media/${encodeURIComponent(mediaId)}/not-needed`, json("POST", {})),

    getConsent: (artisanId: string) => fetchJson<ConsentRecord>(`/artisans/${encodeURIComponent(artisanId)}/consent`),
    postConsent: (artisanId: string, body: ConsentPost) =>
      fetchJson<ConsentRecord>(`/artisans/${encodeURIComponent(artisanId)}/consent`, json("POST", body, body.clientKey)),
    artisanRecord: (artisanId: string, questionnaireId?: string) =>
      fetchJson<ArtisanRecord>(`/artisans/${encodeURIComponent(artisanId)}/record${buildQuery({ questionnaireId })}`),
    artisanRecordCsvHref: (artisanId: string, questionnaireId?: string) =>
      `/api/artisans/${encodeURIComponent(artisanId)}/record.csv${buildQuery({ questionnaireId })}`,

    presign: (body: PresignRequest) => fetchJson<PresignResponse>(`/media/presign`, json("POST", body)),
    multipartCreate: (body: PresignRequest) => fetchJson<MultipartCreateResponse>(`/media/multipart/create`, json("POST", body)),
    multipartPresignParts: (body: { objectKey: string; uploadId: string; partNumbers: number[] }) =>
      fetchJson<MultipartPartUrls>(`/media/multipart/presign-parts`, json("POST", body)),
    multipartComplete: (body: { objectKey: string; uploadId: string; parts: UploadPart[] }) =>
      fetchJson<{ objectKey: string }>(`/media/multipart/complete`, json("POST", body)),
    /** 404 (an ApiError) when the server holds no intent for this clip. */
    uploadState: (clientClipKey: string) => fetchJson<UploadState>(`/media/uploads/${encodeURIComponent(clientClipKey)}`),
    /** Idempotent on `clientClipKey`: 201 the first time, 200 on a replay, the same Recording either way. */
    complete: (body: CompleteRequest) => fetchJson<Recording>(`/media/complete`, json("POST", body, body.clientClipKey))
  };
}

export const questionnaireApi = questionnaireClient(apiFetch);

export type QuestionnaireApi = typeof questionnaireApi;
