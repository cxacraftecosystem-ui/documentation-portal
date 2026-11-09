import type { TranscriptPreview } from "@/lib/transcript/contract";
import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { RecordingChips } from "@/lib/questionnaire/chips";
import { filtersToQuery, type Filters } from "@/lib/questionnaire/filters";
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
  portal?: "designer-portal" | "documentation-portal";
  minVersionCode?: { android: number | null; web: number | null };
};

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

export type { AnswerOrigin, ConsentBasis, ConsentState, ConsentVerdict, RepeatReason, SessionPurpose, SubjectRole } from "@/lib/questionnaire/enums";
export type Attribution = AnswerAttribution;
export const SESSION_PURPOSES: readonly SessionPurpose[] = SessionPurposeValues;
export const REPEAT_REASONS: readonly RepeatReason[] = RepeatReasonValues;

export type Person = { id: string; name: string };

export type CoverageScope = { workshopId?: string; designWorkshopId?: string; artisanIds?: string[] };

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
  respondents?: { name: string; kind: RespondentKind; notes?: string | null }[];
  location?: SessionLocation | null;
  crafts?: string[];
  craftsByHand?: string[];
};

export type SessionLocation = {
  latitude: number;
  longitude: number;
  accuracy?: number | null;
  altitude?: number | null;
  placeName?: string | null;
  address?: string | null;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  pincode?: string | null;
  subjectLatitude?: number | null;
  subjectLongitude?: number | null;
  capturedAt?: string | null;
};

export type RespondentKind = "GUEST" | "SELF";
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
  location?: SessionLocation | null;
  crafts?: string[];
  craftsByHand?: string[];
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
  location?: SessionLocation | null;
  crafts?: string[];
  craftsByHand?: string[];
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
  respondents?: Respondent[];
  source?: "APP" | "UPLOAD" | "HANDOFF" | string;
  deletedAt?: string | null;
  chips?: RecordingChips | null;
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
  preview?: TranscriptPreview | null;
};

export type RecordingGuest = { respondentId: string; name: string; role: SubjectRole; named: boolean; subjectKey: string };

export type Recording = {
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
  chips?: RecordingChips | null;
};

export type RecordingList = { items: Recording[]; nextCursor: string | null; filters?: Record<string, unknown> };

export type FilterOption = {
  id: string;
  label: string;
  count?: number;
  kind?: string | null;
  type?: "FIELD" | "DESIGN" | string;
  code?: string;
  title?: string;
  questionnaireId?: string | null;
  sectionId?: string | null;
  displayCode?: string | null;
};
export type FilterField = "questionnaires" | "sections" | "questions" | "artisans" | "researchers" | "workshops" | "languages";
export type FilterOptions = Partial<Record<FilterField, FilterOption[]>> & { kinds?: string[]; sources?: string[]; transcript?: string[] };

export type Answer = {
  id: string;
  version: number;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  pinned: boolean;
  sourceRecordingId: string | null;
  artisanId?: string | null;
  respondentId?: string | null;
  questionLineageId?: string;
  actorId?: string | null;
  actorName?: string | null;
  note?: string | null;
  richText?: string | null;
};

export type AnswerOwner = { artisanId?: string | null; respondentId?: string | null };

export function ownerKey(owner: AnswerOwner): string {
  return owner.artisanId ? `A:${owner.artisanId}` : `R:${owner.respondentId ?? ""}`;
}

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

export type FormFor = {
  questionnaireId: string | null;
  reason: "EXPLICIT" | "PRIMARY_BINDING" | "ONLY_BINDING" | "TYPE_DEFAULT" | "GLOBAL_DEFAULT" | "PICKER" | string;
  bound: { questionnaireId: string; isPrimary: boolean; kind: string | null }[];
  boundByKind: { kind: string | null; questionnaireIds: string[] }[];
};

export type Binding = { workshopId: string | null; designWorkshopId: string | null; isPrimary: boolean };

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

export type Annexure = {
  workshopId?: string | null;
  designWorkshopId: string | null;
  workshopTitle?: string | null;
  groups: {
    kind: string | null;
    label: string;
    stage?: string | null;
    forms: {
      questionnaireId: string;
      title: string;
      kind: string | null;
      version: number;
      sessionCount?: number;
      questionCount: number;
      answeredTotal: number;
      emptySittings: number;
      sections?: { code: string; title: string; questions: { displayCode: string; prompt: string; answers: { subject: string; text: string }[] }[] }[];
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

export type RecordingEvent = { id: string; kind: string; before: unknown; after: unknown; actor: { id: string | null; name: string }; at: string | null };

export type CoverageState =
  | "NOT_RECORDED"
  | "RECORDED"
  | "PARTIAL"
  | "VIA_SECTION"
  | "UPLOADING"
  | "ON_DEVICE"
  | "NEEDS_DECISION"
  | "COMPLETED"
  | "NEEDS_REVIEW"
  | "NEEDS_REDO";

export type SectionOverride = "COMPLETED" | "NEEDS_REVIEW" | "NEEDS_REDO";
export const SECTION_OVERRIDES: readonly SectionOverride[] = ["COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"];
const SECTION_OVERRIDE_ALIASES: Record<string, SectionOverride> = { MARKED_COMPLETE: "COMPLETED" };

export function readSectionOverride(value: string | null | undefined): SectionOverride | null {
  if (!value) return null;
  const token = SECTION_OVERRIDE_ALIASES[value] ?? value;
  return SECTION_OVERRIDES.includes(token as SectionOverride) ? (token as SectionOverride) : null;
}

export type CoverageRecording = {
  recordingId: string;
  mediaFileId: string | null;
  sessionId: string | null;
  capturedAt: string;
  durationMs: number | null;
  recordedBy: { id: string | null; name: string };
  needsDecision: boolean;
};

export type CoverageSource = "RECORDED" | "TYPED";

type ContractCell = {
  state: CoverageState;
  takes: number;
  covered: number | null;
  total: number | null;
  needsDecision: boolean;
  override: string | null;
  subjectKey?: string;
  source?: CoverageSource | null;
  recordedCount?: number;
  typedOnlyCount?: number;
  addRecording?: boolean;
  recordings?: CoverageRecording[];
};

export type CoverageGrid = {
  questionnaireId: string;
  artisans: { artisanId: string; name: string }[];
  respondents?: { respondentId: string; name: string; subjectKey: string }[];
  sections: {
    sectionId: string;
    code: string;
    title: string;
    cells: (ContractCell & { artisanId: string })[];
    guestCells?: (ContractCell & { respondentId: string })[];
  }[];
};

export type CoverageGridCell = {
  artisanId: string;
  targetKey: string;
  state: CoverageState;
  count: number;
  partial?: { done: number; of: number } | null;
  source?: CoverageSource | null;
  addRecording?: boolean;
  override?: SectionOverride | null;
  recordings?: CoverageRecording[];
};

export function guestColumn(respondentId: string): string {
  return `R:${respondentId}`;
}
export type GridQuestion = { lineageId: string; displayCode: string; prompt: string };
export type GridView = {
  sections: { id: string; code: string; title: string; questions?: GridQuestion[] }[];
  cells: CoverageGridCell[];
};

export function toGridView(grid: CoverageGrid | null | undefined, questions: Record<string, GridQuestion[]> = {}): GridView {
  const sections = grid?.sections ?? [];
  return {
    sections: sections.map((s) => ({ id: s.sectionId, code: s.code, title: s.title, questions: questions[s.sectionId] })),
    cells: sections.flatMap((s) =>
      [
        ...s.cells.map((c) => ({ ...c, column: c.artisanId })),
        ...(s.guestCells ?? []).map((c) => ({ ...c, column: guestColumn(c.respondentId) }))
      ].map((c) => {
        const override = readSectionOverride(c.override);
        return {
          artisanId: c.column,
          targetKey: `S:${s.sectionId}`,
          state: c.needsDecision ? "NEEDS_DECISION" : override ? (override as CoverageState) : c.state,
          count: c.takes,
          partial: c.covered !== null && c.total !== null && c.state === "PARTIAL" ? { done: c.covered, of: c.total } : null,
          source: c.source ?? null,
          addRecording: Boolean(c.addRecording),
          recordings: c.recordings,
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
  artisanId?: string | null;
  respondentId?: string | null;
  questionLineageId: string;
  text: string;
  origin: AnswerOrigin;
  attribution: Attribution;
  sessionId: string;
  sourceRecordingId: string | null;
  baseVersion?: number;
  note?: string | null;
  richText?: unknown;
};
export type AnswerResult = {
  artisanId: string | null;
  respondentId?: string | null;
  questionLineageId: string;
  status: "SAVED" | "UNCHANGED" | "CONFLICT" | "FORBIDDEN";
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

export type JsonFetcher = <T>(path: string, init?: RequestInit) => Promise<T>;

function withQuery(filters: string, cursor?: string | null, limit?: number, extra = ""): string {
  const parts = [filters, cursor ? `cursor=${encodeURIComponent(cursor)}` : "", limit ? `limit=${limit}` : "", extra].filter(Boolean);
  return parts.length ? `?${parts.join("&")}` : "";
}

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
    createSession: (body: SessionCreate) => fetchJson<Session>(`${Q}/sessions`, json("POST", body, body.clientKey)),
    listSessions: (params: Record<string, string | number | undefined | null>) =>
      fetchJson<SessionList>(`${Q}/sessions${buildQuery(params)}`),
    sessions: (filters: Filters, extra: { cursor?: string | null; limit?: number; deleted?: boolean } = {}) =>
      fetchJson<SessionList>(`${Q}/sessions${withQuery(filtersToQuery(filters), extra.cursor, extra.limit, extra.deleted ? "deleted=true" : "")}`),
    listRecordings: (filters: Filters, cursor?: string | null, sessionId?: string | null, limit?: number) =>
      fetchJson<RecordingList>(
        `${Q}/recordings${withQuery(filtersToQuery(filters), cursor, limit, sessionId ? `sessionId=${encodeURIComponent(sessionId)}` : "")}`
      ),
    filterOptions: (field?: FilterField | null, q?: string | null, questionnaireIds: string[] = [], limit?: number) =>
      fetchJson<FilterOptions>(
        `${Q}/filters${buildQuery({ field: field ?? undefined, q: q || undefined, questionnaireId: questionnaireIds.length ? questionnaireIds.join(",") : undefined, limit })}`
      ),
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
    deleteSession: (id: string) => fetchJson<void>(`${Q}/sessions/${encodeURIComponent(id)}`, { method: "DELETE" }),
    restoreSession: (id: string) => fetchJson<Session>(`${Q}/sessions/${encodeURIComponent(id)}/restore`, json("POST", {})),
    deletedSessions: (params: Record<string, string | number | undefined | null> = {}) =>
      fetchJson<SessionList>(`${Q}/sessions${buildQuery({ ...params, deleted: "true" })}`),
    addRespondents: (id: string, respondents: { name: string; kind: RespondentKind; notes?: string | null }[]) =>
      fetchJson<{ respondents: Respondent[] }>(`${Q}/sessions/${encodeURIComponent(id)}/respondents`, json("POST", { respondents }, mintKey("resp"))),
    patchRespondent: (id: string, respondentId: string, body: { name?: string | null; notes?: string | null }) =>
      fetchJson<Respondent>(`${Q}/sessions/${encodeURIComponent(id)}/respondents/${encodeURIComponent(respondentId)}`, json("PATCH", body)),
    removeRespondent: (id: string, respondentId: string) =>
      fetchJson<void>(`${Q}/sessions/${encodeURIComponent(id)}/respondents/${encodeURIComponent(respondentId)}`, { method: "DELETE" }),
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
    putBindings: (id: string, bindings: Binding[]) =>
      fetchJson<{ questionnaireId: string; bindings: Binding[] }>(`${Q}/forms/${encodeURIComponent(id)}/bindings`, json("PUT", { bindings })),
    putTypeDefault: (body: { questionnaireId: string | null; workshopType?: string | null; designWorkshopKind?: string | null }) =>
      fetchJson<{ questionnaireId: string | null }>(`${Q}/type-defaults`, json("PUT", body)),
    setSectionOverride: (body: { artisanId: string; sectionId: string; status: SectionOverride | null }) =>
      fetchJson<unknown>(`${Q}/completion`, json("PUT", body)),
    annexure: (params: { workshopId?: string | null; designWorkshopId?: string | null }) =>
      fetchJson<Annexure>(`${Q}/annexure${buildQuery({ workshopId: params.workshopId ?? undefined, designWorkshopId: params.designWorkshopId ?? undefined })}`),
    insights: (id: string) => fetchJson<{ items: unknown[] }>(`${Q}/sessions/${encodeURIComponent(id)}/insights`),

    checkCoverage: (body: CoverageCheckRequest) => fetchJson<CoverageCheckResponse>(`${Q}/coverage/check`, json("POST", body)),
    coverage: (params: { questionnaireId: string } & CoverageScope) =>
      fetchJson<CoverageGrid>(
        `${Q}/coverage${buildQuery({
          questionnaireId: params.questionnaireId,
          workshopId: params.workshopId,
          designWorkshopId: params.designWorkshopId,
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
    transcribe: (id: string) =>
      fetchJson<{ status: string; paused: boolean }>(`${Q}/recordings/${encodeURIComponent(id)}/transcribe`, json("POST", {})),
    downloadHref: (id: string) => `/api${Q}/recordings/${encodeURIComponent(id)}/download`,

    putAnswers: (items: AnswerItem[]) => fetchJson<{ results: AnswerResult[] }>(`${Q}/answers`, json("PUT", { items })),
    answerRevisions: (answerId: string) => fetchJson<{ answerId: string; revisions: AnswerRevision[] }>(`${Q}/answers/${encodeURIComponent(answerId)}/revisions`),
    removedTakes: (params: { sessionId?: string; artisanId?: string }) =>
      fetchJson<{ items: Recording[] }>(`${Q}/recordings/removed${buildQuery({ sessionId: params.sessionId, artisanId: params.artisanId })}`),
    recordingEvents: (id: string) => fetchJson<{ recordingId: string; events: RecordingEvent[] }>(`${Q}/recordings/${encodeURIComponent(id)}/events`),
    inbox: () => fetchJson<Partial<Inbox>>(`${Q}/inbox`).then(normaliseInbox),
    fileClip: (
      mediaId: string,
      body: { sessionId: string; targets: ({ sectionId: string } | { questionLineageId: string })[]; subjects: { artisanId: string; role: string }[] }
    ) => fetchJson<Recording>(`${Q}/media/${encodeURIComponent(mediaId)}/file`, json("POST", body)),
    dismissClip: (mediaId: string) => fetchJson<void>(`${Q}/media/${encodeURIComponent(mediaId)}/not-needed`, json("POST", {})),

    artisan: (artisanId: string) => fetchJson<Record<string, unknown> & { id: string }>(`/artisans/${encodeURIComponent(artisanId)}`),
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
    uploadState: (clientClipKey: string) => fetchJson<UploadState>(`/media/uploads/${encodeURIComponent(clientClipKey)}`),
    complete: (body: CompleteRequest) => fetchJson<Recording>(`/media/complete`, json("POST", body, body.clientClipKey))
  };
}

export const questionnaireApi = questionnaireClient(apiFetch);

export type QuestionnaireApi = typeof questionnaireApi;
