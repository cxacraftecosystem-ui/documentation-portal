"""Questionnaire v2 request and response shapes: the frozen contract (BUILD_PLAN section 1).

Every example in ``shared/questionnaire-contract.json`` validates against the model of the same
name here (``tests/test_questionnaire_contract.py``). Requests are ``extra="forbid"``; so are the
response models, because an extra response field needs a ``contractVersion`` bump.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app import shared_vocab as V

NOTE_MAX = 80
LANGUAGE_OTHER_MAX = 40


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


def _one_of(values: tuple[str, ...]) -> Any:
    def check(value: Any) -> Any:
        if value is None:
            return value
        if value not in values:
            raise ValueError(f"must be one of {', '.join(values)}")
        return value

    return check


def _language(code: str | None) -> str | None:
    if code is None:
        return None
    if code not in V.LANGUAGE_LABELS:
        raise ValueError(f"languageCode must be one of {', '.join(V.LANGUAGE_LABELS)}")
    return code


# ── common ─────────────────────────────────────────────────────────────────────────────────────
class Ref(Strict):
    id: str
    name: str


class TitleRef(Strict):
    id: str
    title: str


class Config(Strict):
    contractVersion: int
    v2Enabled: bool
    transcriptionPaused: bool
    noteMaxChars: int
    multipartThresholdBytes: int
    partSizeBytes: int
    snapshotPollSeconds: int
    joinCodeLength: int
    minAppVersionForCreate: str | None
    #: The old-build cutoff on versionCode (audit C7), per platform. Additive: older clients ignore them.
    minAndroidVersionCodeForCreate: int | None = None
    minWebVersionCodeForCreate: int | None = None


# ── sessions ───────────────────────────────────────────────────────────────────────────────────
class ParticipantInput(Strict):
    artisanId: str = Field(min_length=1)


class ResearcherInput(Strict):
    userId: str = Field(min_length=1)
    role: str

    _role = field_validator("role")(_one_of(V.ResearcherRole))


#: Guest respondents (owner decision 1; ``app/questionnaire/respondents.py``).
RESPONDENT_KINDS = ("GUEST", "SELF")
#: Where a session came from (UNIFY U7): the app, a workbook upload, a phone-to-phone handoff.
SESSION_SOURCES = ("APP", "UPLOAD", "HANDOFF")


class RespondentInput(Strict):
    name: str | None = Field(default=None, max_length=120)
    kind: str = "GUEST"
    notes: str | None = Field(default=None, max_length=2000)

    _kind = field_validator("kind")(_one_of(RESPONDENT_KINDS))


class RespondentPatch(Strict):
    name: str | None = Field(default=None, max_length=120)
    notes: str | None = Field(default=None, max_length=2000)


class RespondentsAdd(Strict):
    respondents: list[RespondentInput] = Field(min_length=1, max_length=50)


class RespondentRegister(Strict):
    artisanId: str = Field(min_length=1)


class BindingInput(Strict):
    workshopId: str | None = None
    designWorkshopId: str | None = None
    isPrimary: bool = False

    @model_validator(mode="after")
    def _one_workshop(self) -> BindingInput:
        if bool(self.workshopId) == bool(self.designWorkshopId):
            raise ValueError("A binding names a workshopId or a designWorkshopId")
        return self


class BindingsPut(Strict):
    bindings: list[BindingInput] = Field(default_factory=list, max_length=200)


class TypeDefaultPut(Strict):
    #: None clears the type's default.
    questionnaireId: str | None = None
    workshopType: str | None = None
    designWorkshopKind: str | None = Field(default=None, max_length=64)

    _type = field_validator("workshopType")(_one_of(("DESIGN_PROTOTYPE", "OTHER")))

    @model_validator(mode="after")
    def _one_type(self) -> TypeDefaultPut:
        if bool(self.workshopType) == bool(self.designWorkshopKind):
            raise ValueError("Name a workshopType or a designWorkshopKind")
        return self


class SessionLocation(Strict):
    """Where the device was when the session started (UNIFY C9): stored as a ``Location`` row and
    linked by ``locationId``. Captured on the start sheet only, never on an edit (DR audit H1)."""

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy: float | None = Field(default=None, ge=0)
    altitude: float | None = None
    placeName: str | None = Field(default=None, max_length=200)


class SessionCreate(Strict):
    clientKey: str = Field(min_length=1, max_length=128)
    questionnaireId: str = Field(min_length=1)
    workshopId: str | None = None
    designWorkshopId: str | None = None
    sessionDate: date
    startedAt: datetime | None = None
    purpose: str = "FIRST_SITTING"
    place: str | None = Field(default=None, max_length=200)
    languageCode: str | None = None
    languageOther: str | None = Field(default=None, max_length=LANGUAGE_OTHER_MAX)
    note: str | None = Field(default=None, max_length=NOTE_MAX)
    participants: list[ParticipantInput] = Field(default_factory=list)
    researchers: list[ResearcherInput] = Field(default_factory=list)
    #: Guests answering without an artisan record; participants may then be empty (UNIFY 4.1).
    respondents: list[RespondentInput] = Field(default_factory=list, max_length=50)
    source: str = "APP"
    #: The device's fix when the session started (UNIFY C9), beside the typed ``place``. Optional.
    location: SessionLocation | None = None

    _purpose = field_validator("purpose")(_one_of(V.SessionPurpose))
    _lang = field_validator("languageCode")(_language)
    _source = field_validator("source")(_one_of(SESSION_SOURCES))

    @model_validator(mode="after")
    def _one_workshop_kind(self) -> SessionCreate:
        if self.workshopId and self.designWorkshopId:
            raise ValueError("A session links to a workshop or a design workshop, not both")
        if not self.participants and not self.respondents:
            raise ValueError("A session needs at least one artisan or respondent")
        return self


class SessionPatch(Strict):
    version: int = Field(ge=1)
    place: str | None = Field(default=None, max_length=200)
    languageCode: str | None = None
    languageOther: str | None = Field(default=None, max_length=LANGUAGE_OTHER_MAX)
    note: str | None = Field(default=None, max_length=NOTE_MAX)
    sessionDate: date | None = None
    workshopId: str | None = None
    designWorkshopId: str | None = None
    purpose: str | None = None
    questionnaireId: str | None = None

    _purpose = field_validator("purpose")(_one_of(V.SessionPurpose))
    _lang = field_validator("languageCode")(_language)


class ParticipantAdd(Strict):
    artisanId: str = Field(min_length=1)
    joinedAt: datetime | None = None


class ParticipantPatch(Strict):
    joinedAt: datetime | None = None
    leftAt: datetime | None = None


class ResearcherAdd(ResearcherInput):
    pass


class JoinRequest(Strict):
    joinCode: str = Field(min_length=4, max_length=12)


class JoinByKeyRequest(Strict):
    """A scanned session QR: join the session its clientKey names (never create it)."""

    clientKey: str = Field(min_length=1, max_length=128)


class ContinueRequest(Strict):
    fromSessionId: str = Field(min_length=1)
    artisanIds: list[str] = Field(min_length=1)
    split: Literal["ONE", "EACH"] = "ONE"
    sessionDate: date
    #: Optional. The sessions made are keyed ``<clientKey>:<n>``, so a replay returns them again.
    clientKey: str | None = Field(default=None, min_length=1, max_length=120)


class ConsentSummary(Strict):
    state: str
    source: str | None


class Participant(Strict):
    artisanId: str
    name: str
    joinedAt: datetime | None
    leftAt: datetime | None
    consent: ConsentSummary


class Researcher(Strict):
    userId: str
    name: str
    role: str


class SessionCounts(Strict):
    sections: int
    takes: int
    needsDecision: int


class Session(Strict):
    id: str
    clientKey: str | None
    version: int
    title: str
    note: str | None
    legacyTitle: str | None
    questionnaire: TitleRef
    workshop: Ref | None
    designWorkshopId: str | None
    sessionDate: date | None
    sessionDateEstimated: bool
    startedAt: datetime | None
    endedAt: datetime | None
    closedAt: datetime | None
    state: str
    purpose: str
    origin: str
    mode: str
    participantCount: int
    place: str | None
    languageCode: str | None
    languageOther: str | None
    joinCode: str | None
    joinQr: str | None
    participants: list[Participant]
    researchers: list[Researcher]
    status: str
    submittedAt: datetime | None
    reviewReasons: list[str]
    counts: SessionCounts


class SessionList(Strict):
    items: list[Session]
    nextCursor: str | None
    capped: bool


class OpenMatchItem(Strict):
    id: str
    title: str
    startedAt: datetime | None
    researcher: Ref | None
    artisanNames: list[str]


class OpenMatch(Strict):
    items: list[OpenMatchItem]


# ── recordings and coverage ────────────────────────────────────────────────────────────────────
class TargetInput(Strict):
    sectionId: str | None = None
    questionLineageId: str | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> TargetInput:
        if bool(self.sectionId) == bool(self.questionLineageId):
            raise ValueError("A target names a sectionId or a questionLineageId, exactly one")
        return self


class RecordingTarget(Strict):
    targetKey: str
    sectionId: str | None
    questionLineageId: str | None
    askedQuestionId: str | None
    displayCode: str | None
    label: str


class RecordingSubject(Strict):
    artisanId: str
    role: str
    named: bool
    repeatReason: str | None
    flag: str | None
    position: int | None


class TranscriptState(Strict):
    status: str | None
    paused: bool
    text: str | None
    language: str | None


class ConsentVerdictOut(Strict):
    verdict: str
    source: str | None


class Recording(Strict):
    id: str
    mediaFileId: str
    clientClipKey: str
    sessionId: str | None
    version: int
    capturedAt: datetime
    capturedAtSource: str
    estimated: bool
    durationMs: int | None
    rosterCount: int
    recordedBy: Ref
    targets: list[RecordingTarget]
    subjects: list[RecordingSubject]
    #: Guest respondents on the take (owner decision 1): {respondentId, name, role, named, subjectKey}.
    respondents: list[dict[str, Any]] = Field(default_factory=list)
    continuationOfId: str | None
    filing: str
    decisionKind: str | None
    provenance: str
    languageCode: str | None
    removedAt: datetime | None
    downloadName: str
    transcript: TranscriptState
    consent: ConsentVerdictOut


class SubjectPatch(Strict):
    artisanId: str
    role: str

    _role = field_validator("role")(_one_of(V.SubjectRole))


class RecordingPatch(Strict):
    version: int = Field(ge=1)
    sessionId: str | None = None
    targets: list[TargetInput] | None = None
    subjects: list[SubjectPatch] | None = None
    capturedAt: datetime | None = None
    repeatReasons: dict[str, str] | None = None

    @field_validator("repeatReasons")
    @classmethod
    def _reasons(cls, value: dict[str, str] | None) -> dict[str, str] | None:
        for reason in (value or {}).values():
            if reason not in V.RepeatReason:
                raise ValueError(f"repeat reasons must be one of {', '.join(V.RepeatReason)}")
        return value


class DecideRequest(Strict):
    action: str
    otherRecordingId: str | None = None

    _action = field_validator("action")(_one_of(V.DecideAction))


class FileRecordingRequest(Strict):
    """"File it" for a take the server could not place (audit H14)."""

    targets: list[TargetInput] = Field(default_factory=list)
    subjects: list[SubjectPatch] | None = None
    sessionId: str | None = None


class FileMediaRequest(Strict):
    """"File it" for an inbox "To file" clip that has no recording yet (audit H14)."""

    sessionId: str = Field(min_length=1)
    targets: list[TargetInput] = Field(default_factory=list)
    subjects: list[SubjectPatch] | None = None


class MakePrimaryRequest(Strict):
    artisanId: str
    targetKey: str


class TranscribeResponse(Strict):
    status: str
    paused: bool


class Proposal(Strict):
    questionLineageId: str
    artisanIds: list[str] | None
    text: str
    origin: str
    existing: list[dict[str, Any]]


class AnswerProposals(Strict):
    proposals: list[Proposal]


class CoverageCheckRequest(Strict):
    questionnaireId: str
    artisanIds: list[str] = Field(min_length=1)
    targets: list[TargetInput] = Field(min_length=1)
    sessionId: str | None = None
    contentSha256: str | None = None


class TakeSession(Strict):
    id: str
    date: date | None
    rosterCount: int


class CoverageTake(Strict):
    recordingId: str
    position: int | None
    capturedAt: datetime
    estimated: bool
    durationMs: int | None
    recordedBy: Ref
    session: TakeSession
    sameSession: bool
    mine: bool
    repeatReason: str | None


class PerArtisan(Strict):
    artisanId: str
    state: str
    viaSection: bool
    takes: list[CoverageTake]


class CoverageTarget(Strict):
    targetKey: str
    label: str
    perArtisan: list[PerArtisan]


class Identical(Strict):
    recordingId: str
    sessionId: str | None
    sameSession: bool


class CoverageCheckResponse(Strict):
    asOf: datetime
    identical: Identical | None
    targets: list[CoverageTarget]
    suggestedReason: str | None


class GridCell(Strict):
    artisanId: str
    state: str
    takes: int
    covered: int
    total: int
    needsDecision: bool
    override: str | None


class GridSection(Strict):
    sectionId: str
    code: str
    title: str
    cells: list[GridCell]


class GridArtisan(Strict):
    artisanId: str
    name: str


class CoverageGrid(Strict):
    questionnaireId: str
    artisans: list[GridArtisan]
    sections: list[GridSection]


class SnapshotTake(Strict):
    recordingId: str
    sessionId: str | None
    artisanId: str
    targetKey: str
    role: str
    capturedAt: datetime
    estimated: bool
    filing: str
    position: int | None


class Snapshot(Strict):
    asOfSeq: str
    takes: list[SnapshotTake]
    removedRecordingIds: list[str]
    #: Every recording changed since ``sinceSeq`` (audit M3). Additive: older clients ignore it.
    changedRecordingIds: list[str] = Field(default_factory=list)


# ── media ──────────────────────────────────────────────────────────────────────────────────────
class PresignRequest(Strict):
    linkedRecordType: Literal["questionnaire_session"]
    sessionId: str = Field(min_length=1)
    clientClipKey: str = Field(min_length=1, max_length=128)
    sizeBytes: int = Field(gt=0)
    contentType: str = Field(min_length=1, max_length=180)
    extension: str = Field(min_length=1, max_length=12, pattern=r"^[A-Za-z0-9]+$")
    contentSha256: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    targets: list[TargetInput] = Field(default_factory=list)
    subjectArtisanIds: list[str] = Field(default_factory=list)
    deviceCapturedAt: datetime | None = None
    clockOffsetMs: int | None = None
    #: When the device measured ``clockOffsetMs``: an offset older than 7 days marks the time estimated.
    clockOffsetMeasuredAt: datetime | None = None
    #: The take this one continues (Part 2), so the minted name ends ``-P2``.
    continuationOfId: str | None = None


class MultipartCreateRequest(PresignRequest):
    pass


class PresignResponse(Strict):
    objectKey: str
    url: str
    headers: dict[str, str]
    expiresAt: datetime


class PartDone(Strict):
    partNumber: int
    etag: str


class MultipartCreateResponse(Strict):
    objectKey: str
    uploadId: str
    partSize: int
    partsDone: list[PartDone]


class UploadState(Strict):
    objectKey: str
    multipartUploadId: str | None
    partSize: int
    partsDone: list[PartDone]
    closed: bool


class SubjectInput(Strict):
    #: An artisan, or a guest respondent of the session (owner decision 1; subject key R:<id>).
    artisanId: str | None = Field(default=None, min_length=1)
    respondentId: str | None = Field(default=None, min_length=1)
    role: str = "ANSWERED"
    named: bool = False
    repeatReason: str | None = None

    _role = field_validator("role")(_one_of(V.SubjectRole))
    _reason = field_validator("repeatReason")(_one_of(V.RepeatReason))

    @model_validator(mode="after")
    def _one_subject(self) -> SubjectInput:
        if bool(self.artisanId) == bool(self.respondentId):
            raise ValueError("A subject is exactly one of an artisanId or a respondentId")
        return self


class RecordingInput(Strict):
    targets: list[TargetInput] = Field(default_factory=list)
    subjects: list[SubjectInput] = Field(default_factory=list)
    deviceCapturedAt: datetime | None = None
    clockOffsetMs: int | None = None
    clockOffsetMeasuredAt: datetime | None = None
    capturedAtSource: str = "DEVICE"
    durationMs: int | None = Field(default=None, ge=0)
    rosterCount: int | None = Field(default=None, ge=0)
    contentSha256: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    continuationOfId: str | None = None
    replacesRecordingId: str | None = None
    ackedRecordingIds: list[str] = Field(default_factory=list)
    languageCode: str | None = None

    _source = field_validator("capturedAtSource")(_one_of(V.CapturedAtSource))
    _lang = field_validator("languageCode")(_language)


class CompleteRequest(Strict):
    objectKey: str = Field(min_length=1)
    clientClipKey: str = Field(min_length=1, max_length=128)
    linkedRecordType: Literal["questionnaire_session"]
    linkedRecordId: str = Field(min_length=1)
    recording: RecordingInput


# ── answers, consent, inbox, record, corpus ────────────────────────────────────────────────────
class AnswerItem(Strict):
    #: Exactly one owner: an artisan, or a guest respondent of the session (owner decision 1).
    artisanId: str | None = Field(default=None, min_length=1)
    respondentId: str | None = Field(default=None, min_length=1)
    questionLineageId: str = Field(min_length=1)
    text: str = Field(max_length=20000)
    origin: str = "TYPED"
    attribution: str = "INDIVIDUAL"
    sessionId: str = Field(min_length=1)
    sourceRecordingId: str | None = None
    baseVersion: int | None = Field(default=None, ge=1)
    #: The answer note (A20), kept on the revision.
    note: str | None = Field(default=None, max_length=2000)
    #: Decision C8: the answer as a rich-text document (designer-portal's stored format), beside the
    #: canonical plain ``text``. Sent alone (``text`` empty), it is flattened into the text.
    richText: dict[str, Any] | list[Any] | str | None = None

    _origin = field_validator("origin")(_one_of(V.AnswerOrigin))
    _attr = field_validator("attribution")(_one_of(V.AnswerAttribution))

    @model_validator(mode="after")
    def _one_owner(self) -> AnswerItem:
        if bool(self.artisanId) == bool(self.respondentId):
            raise ValueError("An answer belongs to exactly one of an artisanId or a respondentId")
        return self


class AnswersPut(Strict):
    items: list[AnswerItem] = Field(min_length=1, max_length=500)


class AnswerOut(Strict):
    id: str
    version: int
    text: str
    origin: str
    attribution: str
    pinned: bool
    sourceRecordingId: str | None


class AnswerResult(Strict):
    artisanId: str
    questionLineageId: str
    status: str
    answer: AnswerOut | None
    current: AnswerOut | None
    #: ``ANSWER_LOCKED`` on a FORBIDDEN item that someone else's text holds (the owner's answer lock,
    #: 21 Oct 2026). Additive: older clients ignore it and read the FORBIDDEN they already know.
    code: str | None = None


class AnswersPutResponse(Strict):
    results: list[AnswerResult]


class ConsentGet(Strict):
    artisanId: str
    purpose: str
    state: str
    basis: str | None
    source: str | None
    recordedAt: datetime | None
    recordedBy: Ref | None


class ConsentPost(Strict):
    purpose: str = "EXTERNAL_PROCESSING"
    state: str
    basis: str = "VERBAL_RECORDED"
    note: str | None = Field(default=None, max_length=500)
    evidenceMediaId: str | None = None
    clientKey: str | None = Field(default=None, max_length=128)

    _purpose = field_validator("purpose")(_one_of(V.ConsentPurpose))
    _state = field_validator("state")(_one_of(("GRANTED", "REFUSED")))
    _basis = field_validator("basis")(_one_of(("VERBAL_RECORDED", "VERBAL_WITNESSED", "SIGNED_FORM")))


class InboxCounts(Strict):
    total: int
    olderThan24h: int


class Inbox(Strict):
    toFile: list[dict[str, Any]]
    needsDecision: list[dict[str, Any]]
    legacy: list[dict[str, Any]]
    setDates: list[dict[str, Any]]
    noParticipants: list[dict[str, Any]]
    emptySessions: list[dict[str, Any]]
    primaryChanged: list[dict[str, Any]]
    counts: InboxCounts


class RecordSummary(Strict):
    sectionsCovered: int
    sectionsTotal: int
    sessions: int
    repeats: int
    open: list[str]


class DocQuestion(Strict):
    displayCode: str | None
    answer: dict[str, Any] | None
    primaryRecordingId: str | None
    otherRecordingIds: list[str]
    accountsDiffer: bool
    recordedUnderEarlierWording: bool


class DocSection(Strict):
    sectionCode: str
    questions: list[DocQuestion]


class RecordInstrument(Strict):
    questionnaireId: str
    title: str
    summary: RecordSummary
    sessions: list[dict[str, Any]]
    document: list[DocSection]
    retired: list[dict[str, Any]]


class ArtisanRecord(Strict):
    artisanId: str
    instruments: list[RecordInstrument]


class Insights(Strict):
    items: list[dict[str, Any]]


class CorpusMeta(Strict):
    schemaVersion: int
    files: list[str]


#: contract example name -> model, read by tests/test_questionnaire_contract.py
CONTRACT_MODELS: dict[str, type[BaseModel]] = {
    "Config": Config,
    "SessionCreate": SessionCreate,
    "SessionPatch": SessionPatch,
    "Session": Session,
    "SessionList": SessionList,
    "OpenMatch": OpenMatch,
    "JoinRequest": JoinRequest,
    "JoinByKeyRequest": JoinByKeyRequest,
    "ContinueRequest": ContinueRequest,
    "ParticipantAdd": ParticipantAdd,
    "ParticipantPatch": ParticipantPatch,
    "ResearcherAdd": ResearcherAdd,
    "Recording": Recording,
    "RecordingPatch": RecordingPatch,
    "DecideRequest": DecideRequest,
    "MakePrimaryRequest": MakePrimaryRequest,
    "TranscribeResponse": TranscribeResponse,
    "AnswerProposals": AnswerProposals,
    "CoverageCheckRequest": CoverageCheckRequest,
    "CoverageCheckResponse": CoverageCheckResponse,
    "CoverageGrid": CoverageGrid,
    "Snapshot": Snapshot,
    "PresignRequest": PresignRequest,
    "PresignResponse": PresignResponse,
    "MultipartCreateRequest": MultipartCreateRequest,
    "MultipartCreateResponse": MultipartCreateResponse,
    "UploadState": UploadState,
    "CompleteRequest": CompleteRequest,
    "AnswersPut": AnswersPut,
    "AnswersPutResponse": AnswersPutResponse,
    "ConsentGet": ConsentGet,
    "ConsentPost": ConsentPost,
    "Inbox": Inbox,
    "ArtisanRecord": ArtisanRecord,
    "Insights": Insights,
    "CorpusMeta": CorpusMeta,
}
