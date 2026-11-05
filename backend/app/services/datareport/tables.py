from __future__ import annotations

import asyncio
import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any, Literal

from app.services.datareport import exclusions, schema
from app.services.datareport.contract import Column, Link, ReportContext, Sheet, register

CHUNK = 1000
TABLE_CAP = 50000
DW_GROUP = "designWorkshop"

NAME_FIELDS: dict[str, tuple[str, ...]] = {
    "User": ("name", "email"),
    "Artisan": ("name",),
    "Workshop": ("title",),
    "Craft": ("name",),
    "CraftCategory": ("name",),
    "CraftSubCategory": ("name",),
    "ProductDocumentation": ("productName", "craftName"),
    "ToolDocumentation": ("toolkitName", "englishName"),
    "Process": ("name",),
    "ProcessStep": ("name",),
    "QuestionnaireInterview": ("title", "legacyTitle"),
    "MediaFile": ("originalFilename",),
    "Location": ("placeName", "village", "district", "address"),
    "Questionnaire": ("title",),
    "QuestionnaireSection": ("title", "code"),
    "QuestionnaireQuestion": ("prompt",),
    "QuestionnaireFormSection": ("title",),
    "QuestionnaireFormQuestion": ("prompt",),
    "QuestionnaireFormEntry": ("title",),
    "QuestionnaireRespondent": ("name",),
    "QuestionnaireRecording": ("clientClipKey",),
    "DesignWorkshop": ("title",),
    "DwCustomSection": ("title",),
    "DwStageEntry": ("entityKey",),
    "DwAiLayer": ("kind",),
    "DwReportExport": ("fileName",),
    "TranscriptGlossaryTerm": ("term",),
    "TranscriptVersion": ("version",),
    "SanctionOrder": ("sanctionOrderNo",),
}

OPEN_KINDS = {
    "Artisan": "artisan",
    "Workshop": "workshop",
    "Craft": "craft",
    "ProductDocumentation": "product",
    "Process": "process",
    "ToolDocumentation": "tool",
    "QuestionnaireInterview": "interview",
    "MediaFile": "media",
    "DesignWorkshop": "designWorkshop",
}

MEDIA_OWNERS = {
    "Workshop": "workshopId",
    "Craft": "craftId",
    "Artisan": "artisanId",
    "ProductDocumentation": "productId",
    "ToolDocumentation": "toolId",
    "QuestionnaireInterview": "questionnaireInterviewId",
    "Process": None,
    "ProcessStep": None,
    "DesignWorkshop": "designWorkshopId",
}

_USER_IDS = {"actorId", "userId", "ownerId", "authorId", "assigneeId", "reviewerId", "recipientId", "uploaderId", "granteeId", "inspectorId", "designerUserId"}
_BY_NAME = {
    "artisanId": "Artisan",
    "artisanAId": "Artisan",
    "artisanBId": "Artisan",
    "survivorId": "Artisan",
    "craftId": "Craft",
    "linkedCraftId": "Craft",
    "mediaFileId": "MediaFile",
    "sourceMediaId": "MediaFile",
    "evidenceMediaId": "MediaFile",
    "photoMediaId": "MediaFile",
    "signatureMediaId": "MediaFile",
    "cvMediaId": "MediaFile",
    "measurementImageId": "MediaFile",
    "interviewId": "QuestionnaireInterview",
    "sessionId": "QuestionnaireInterview",
    "questionnaireInterviewId": "QuestionnaireInterview",
    "questionnaireId": "Questionnaire",
    "workshopId": "Workshop",
    "designWorkshopId": "DesignWorkshop",
    "respondentId": "QuestionnaireRespondent",
    "recordingId": "QuestionnaireRecording",
    "sourceRecordingId": "QuestionnaireRecording",
    "continuationOfId": "QuestionnaireRecording",
    "replacesRecordingId": "QuestionnaireRecording",
    "productId": "ProductDocumentation",
    "toolId": "ToolDocumentation",
    "processId": "Process",
    "locationId": "Location",
}


LABELS = {
    "sizeBytes": "Size",
    "mimeType": "File format",
    "originalFilename": "File name",
    "sha256": "SHA-256",
    "contentSha256": "SHA-256 checksum",
    "shaVerifiedAt": "Checksum verified on",
    "checksum": "Checksum",
    "recordedTimezone": "Time zone",
    "extraMetadata": "More details",
    "durationMs": "Duration",
}


def target_of(f: schema.Field) -> str | None:
    if f.fk:
        return f.fk
    if f.type != "String" or f.is_id or not f.name.endswith("Id"):
        return None
    if f.name.endswith("ById") or f.name in _USER_IDS:
        return "User"
    return _BY_NAME.get(f.name)


@dataclass(frozen=True)
class Load:
    kind: Literal["records", "children", "refs", "subjects", "unbound"]
    key: str = ""
    fk: str = ""
    parent: tuple[str, ...] = ()
    fields: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class Table:
    key: str
    title: str
    model: str
    order: int
    loads: tuple[Load, ...]
    description: str = ""
    color: str = "762CB1"
    design_workshop: bool = False
    spend: bool = False
    transcript: bool = False
    first: tuple[str, ...] = ()
    portals: tuple[str, ...] = ("designer", "fieldRepository")


def R(key: str) -> Load:
    return Load("records", key=key)


def C(fk: str, *parent: str) -> Load:
    return Load("children", fk=fk, parent=parent)


def F(*fields: tuple[str, str]) -> Load:
    return Load("refs", fields=fields)


def S(fk: str) -> Load:
    return Load("subjects", fk=fk)


def U(fk: str) -> Load:
    return Load("unbound", fk=fk)


WORKSHOP = "0F766E"
CRAFT = "B45309"
ARTISAN = "762CB1"
PRODUCT = "1D4ED8"
TOOL = "BE185D"
SITTING = "4D7C0F"
MEDIA = "334155"
REVIEW = "6B5B7B"
DW = "0F766E"

TABLES: tuple[Table, ...] = (
    Table("workshops", "Workshops", "Workshop", 10, (R("workshops"),), "Every workshop in this folder.", WORKSHOP),
    Table("crafts", "Crafts", "Craft", 20, (R("crafts"),), "Every craft in this folder.", CRAFT),
    Table("artisans", "Artisans", "Artisan", 30, (R("artisans"),), "Every artisan in this folder.", ARTISAN),
    Table("products", "Products", "ProductDocumentation", 40, (R("products"),), "Every product documented.", PRODUCT),
    Table("processes", "Processes", "Process", 45, (R("processes"),), "Every making process.", PRODUCT),
    Table("processSteps", "Process steps", "ProcessStep", 46, (C("processId", "Process"),), "Each step of each process, in order.", PRODUCT, first=("processId", "sortOrder", "name")),
    Table("tools", "Tools", "ToolDocumentation", 50, (R("tools"),), "Every tool documented.", TOOL),
    Table("toolArtisans", "Tool artisans", "ToolArtisan", 51, (C("toolId", "ToolDocumentation"),), "Which artisans use each tool.", TOOL),
    Table("toolCrafts", "Tool crafts", "ToolCraft", 52, (C("toolId", "ToolDocumentation"),), "Which crafts use each tool.", TOOL),
    Table("workshopArtisans", "Workshop artisans", "WorkshopArtisan", 11, (C("workshopId", "Workshop"),), "Artisans taking part in each workshop.", WORKSHOP),
    Table("workshopCrafts", "Workshop crafts", "WorkshopCraft", 12, (C("workshopId", "Workshop"),), "Crafts covered by each workshop.", WORKSHOP),
    Table("workshopTeam", "Workshop team", "WorkshopAssignment", 13, (C("workshopId", "Workshop"),), "Who works on each workshop, and with what access.", WORKSHOP),
    Table("tasks", "Tasks", "AssignedTask", 14, (C("workshopId", "Workshop"),), "Tasks assigned within these workshops.", WORKSHOP),
    Table("sectionStatus", "Section status", "QuestionnaireSectionStatus", 31, (C("artisanId", "Artisan"),), "Each artisan's questionnaire section marked complete or not.", ARTISAN),
    Table("artisanCards", "Artisan cards", "ArtisanCard", 32, (C("artisanId", "Artisan"),), "Artisan identity cards issued.", ARTISAN),
    Table("artisanCardEvents", "Artisan card events", "ArtisanCardEvent", 33, (C("artisanId", "Artisan"),), "Card scans, prints and shares.", ARTISAN),
    Table("crossPortalLinks", "Cross-portal links", "CrossPortalArtisanLink", 34, (C("artisanId", "Artisan"),), "Artisans linked to the same person in the other portal.", ARTISAN),
    Table("sharingConsent", "Sharing consent", "ArtisanSharingConsent", 35, (C("artisanId", "Artisan"),), "Each artisan's consent to share with the other portal.", ARTISAN, portals=("fieldRepository",)),
    Table("sittings", "Questionnaire sittings", "QuestionnaireInterview", 60, (R("interviews"),), "Every questionnaire sitting (interview session).", SITTING),
    Table("sittingArtisans", "Sitting artisans", "QuestionnaireInterviewArtisan", 61, (C("interviewId", "QuestionnaireInterview"),), "Artisans present at each sitting.", SITTING),
    Table("sittingResearchers", "Sitting researchers", "QuestionnaireInterviewResearcher", 62, (C("interviewId", "QuestionnaireInterview"),), "Researchers at each sitting.", SITTING),
    Table("respondents", "Respondents", "QuestionnaireRespondent", 63, (C("sessionId", "QuestionnaireInterview"),), "People who answered at each sitting.", SITTING),
    Table("responses", "Answers (sittings)", "QuestionnaireResponse", 64, (C("interviewId", "QuestionnaireInterview"),), "Answers typed during sittings, one row per question.", SITTING),
    Table("answerDrafts", "Answer drafts", "QuestionnaireAnswerDraft", 65, (C("interviewId", "QuestionnaireInterview"),), "Drafted answers waiting for a decision.", SITTING),
    Table("answerRecords", "Answers (by artisan)", "QuestionnaireAnswer", 66, (C("artisanId", "Artisan"), C("respondentId", "QuestionnaireRespondent")), "Each artisan's current answer to each question.", SITTING),
    Table("answerRevisions", "Answer history", "QuestionnaireAnswerRevision", 67, (C("answerId", "QuestionnaireAnswer"),), "Every version of every answer, with who and when.", SITTING),
    Table("recordings", "Recordings", "QuestionnaireRecording", 70, (C("mediaFileId", "MediaFile"),), "Questionnaire recordings and what they cover.", SITTING),
    Table("recordingTargets", "Recording targets", "QuestionnaireRecordingTarget", 71, (C("recordingId", "QuestionnaireRecording"),), "The sections and questions each recording answers.", SITTING),
    Table("recordingSubjects", "Recording subjects", "QuestionnaireRecordingSubject", 72, (C("recordingId", "QuestionnaireRecording"),), "The artisans heard in each recording.", SITTING),
    Table("recordingRespondents", "Recording respondents", "QuestionnaireRecordingRespondent", 73, (C("recordingId", "QuestionnaireRecording"),), "The respondents heard in each recording.", SITTING),
    Table("recordingEvents", "Recording events", "QuestionnaireRecordingEvent", 74, (C("recordingId", "QuestionnaireRecording"),), "Changes made to each recording.", SITTING),
    Table("recordingConsent", "Recording consent", "RecordingConsent", 75, (C("mediaFileId", "MediaFile"),), "Consent given for each recording.", SITTING, portals=("fieldRepository",)),
    Table("takeDrafts", "Take drafts", "QuestionnaireTakeDraft", 76, (C("recordingId", "QuestionnaireRecording"),), "Answers drafted from a recording.", SITTING),
    Table("draftCitations", "Draft citations", "TranscriptDraftCitation", 77, (C("recordingId", "QuestionnaireRecording"),), "Where in a recording each drafted answer came from.", SITTING, transcript=True),
    Table("transcriptVersions", "Transcript versions", "TranscriptVersion", 81, (C("mediaFileId", "MediaFile"),), "Each transcript version: languages, length, models and translation state.", MEDIA, transcript=True),
    Table("transcriptSpeakers", "Transcript speakers", "TranscriptSpeaker", 82, (C("mediaFileId", "MediaFile"),), "Who each speaker is in each recording.", MEDIA, transcript=True),
    Table("transcriptEdits", "Transcript edits", "TranscriptTurnEdit", 83, (C("turn.mediaFileId", "MediaFile"),), "Every edit to a transcript line.", MEDIA, transcript=True),
    Table("transcriptInsights", "Transcript summaries", "TranscriptInsight", 84, (C("mediaFileId", "MediaFile"),), "The summary, quotes and translation note of each transcript.", MEDIA, transcript=True),
    Table("glossarySightings", "Glossary sightings", "TranscriptGlossarySighting", 85, (C("mediaFileId", "MediaFile"),), "Where each craft term is heard.", MEDIA, transcript=True),
    Table("glossaryTerms", "Glossary terms", "TranscriptGlossaryTerm", 86, (F(("TranscriptGlossarySighting", "termId")),), "Craft terms heard in these transcripts.", MEDIA, transcript=True),
    Table("transcriptSpend", "Transcript spend", "TranscriptSpend", 87, (C("mediaFileId", "MediaFile"),), "What each transcript cost (visible to content admins).", MEDIA, spend=True, transcript=True),
    Table("designWorkshops", "Design workshops", "DesignWorkshop", 200, (R("designWorkshops"),), "Every design and prototype workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwStageEntries", "Stage entries", "DwStageEntry", 201, (C("designWorkshopId", "DesignWorkshop"),), "Every stage entry, with its raw field data.", DW, design_workshop=True, portals=("designer",)),
    Table("dwCustomSections", "Custom sections", "DwCustomSection", 202, (C("designWorkshopId", "DesignWorkshop"),), "Custom sections added to each workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwCustomFields", "Custom fields", "DwCustomField", 203, (C("sectionId", "DwCustomSection"),), "Fields of each custom section.", DW, design_workshop=True, portals=("designer",)),
    Table("dwAiLayers", "AI layers", "DwAiLayer", 204, (C("designWorkshopId", "DesignWorkshop"),), "AI drafts, translations and summaries attached to a workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwAiDecisions", "AI decisions", "DwAiLayerDecision", 205, (C("layerId", "DwAiLayer"),), "Accept and reject decisions on AI layers.", DW, design_workshop=True, portals=("designer",)),
    Table("dwRatings", "Review ratings", "DwReviewRating", 206, (C("designWorkshopId", "DesignWorkshop"),), "Reviewers' scores and suggestions.", DW, design_workshop=True, portals=("designer",)),
    Table("dwInspection", "Inspection feedback", "DwInspectionFeedback", 207, (C("designWorkshopId", "DesignWorkshop"),), "Inspection notes and send-backs.", DW, design_workshop=True, portals=("designer",)),
    Table("dwClearances", "Inspection clearances", "DwInspectionClearance", 208, (C("designWorkshopId", "DesignWorkshop"),), "When each inspector cleared a round.", DW, design_workshop=True, portals=("designer",)),
    Table("dwConsent", "Workshop consent", "DwWorkshopConsentDecision", 209, (C("designWorkshopId", "DesignWorkshop"),), "Workshop-level consent decisions.", DW, design_workshop=True, portals=("designer",)),
    Table("dwExports", "Report exports", "DwReportExport", 210, (C("designWorkshopId", "DesignWorkshop"),), "Workshop reports generated.", DW, design_workshop=True, portals=("designer",)),
    Table("dwDeliveries", "Report deliveries", "DwReportDelivery", 211, (C("designWorkshopId", "DesignWorkshop"),), "Workshop reports sent to offices.", DW, design_workshop=True, portals=("designer",)),
    Table("dwImports", "Artisan imports", "DwArtisanImport", 212, (C("designWorkshopId", "DesignWorkshop"),), "Artisan lists imported into workshops.", DW, design_workshop=True, portals=("designer",)),
    Table("dwRounds", "Review rounds", "DwRoundSnapshot", 213, (C("designWorkshopId", "DesignWorkshop"),), "Each review round of each workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwViewers", "Workshop viewers", "DesignWorkshopViewer", 214, (C("designWorkshopId", "DesignWorkshop"),), "Who may view each workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwOversight", "Workshop oversight", "DesignWorkshopOversight", 216, (C("designWorkshopId", "DesignWorkshop"),), "Officers overseeing each workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwAccessRequests", "Access requests", "DesignWorkshopAccessRequest", 217, (C("designWorkshopId", "DesignWorkshop"),), "Requests to join a workshop.", DW, design_workshop=True, portals=("designer",)),
    Table("dwProvisional", "Provisional members", "DesignWorkshopProvisionalMember", 218, (C("designWorkshopId", "DesignWorkshop"),), "Members admitted provisionally by scanning a code.", DW, design_workshop=True, portals=("designer",)),
    Table("sanctionOrders", "Sanction orders", "SanctionOrder", 219, (C("designWorkshopId", "DesignWorkshop"),), "Sanction orders of these workshops.", DW, design_workshop=True, portals=("designer",)),
    Table("sanctionDesigners", "Sanctioned designers", "SanctionOrderDesigner", 220, (C("sanctionOrderId", "SanctionOrder"),), "Designers named in each sanction order.", DW, design_workshop=True, portals=("designer",)),
    Table("questionnaires", "Questionnaires", "Questionnaire", 90, (F(("QuestionnaireInterview", "questionnaireId"), ("QuestionnaireAnswer", "questionnaireId"), ("Workshop", "questionnaireId")), C("designWorkshopId", "DesignWorkshop")), "The questionnaires these sittings use.", SITTING),
    Table("questionnaireSections", "Questionnaire sections", "QuestionnaireSection", 91, (C("questionnaireId", "Questionnaire"), U("questionnaireId"), F(("QuestionnaireSectionStatus", "sectionId"), ("QuestionnaireRecordingTarget", "sectionId"))), "Sections of each questionnaire.", SITTING),
    Table("questions", "Questions", "QuestionnaireQuestion", 92, (C("questionnaireId", "Questionnaire"), U("questionnaireId"), F(("QuestionnaireResponse", "questionId"), ("QuestionnaireAnswerDraft", "questionId"))), "Every question of each questionnaire, in every version.", SITTING),
    Table("formSections", "Form sections", "QuestionnaireFormSection", 93, (C("questionnaireId", "Questionnaire"),), "Sections of each custom form.", SITTING),
    Table("formQuestions", "Form questions", "QuestionnaireFormQuestion", 94, (C("sectionId", "QuestionnaireFormSection"),), "Questions of each custom form.", SITTING),
    Table("formEntries", "Form entries", "QuestionnaireFormEntry", 95, (C("questionnaireId", "Questionnaire"),), "Each filled-in custom form.", SITTING),
    Table("formAnswers", "Form answers", "QuestionnaireFormAnswer", 96, (C("entryId", "QuestionnaireFormEntry"),), "Answers in each custom form.", SITTING),
    Table("questionnaireBindings", "Questionnaire bindings", "QuestionnaireBinding", 97, (C("questionnaireId", "Questionnaire"),), "Which workshops use which questionnaire.", SITTING),
    Table("questionnaireDefaults", "Questionnaire defaults", "QuestionnaireTypeDefault", 98, (C("questionnaireId", "Questionnaire"),), "Default questionnaire per workshop type.", SITTING),
    Table("craftSubCategories", "Craft sub-categories", "CraftSubCategory", 21, (F(("Craft", "subCategoryId")),), "Sub-categories of these crafts.", CRAFT),
    Table("craftCategories", "Craft categories", "CraftCategory", 22, (F(("CraftSubCategory", "categoryId")),), "Categories of these crafts.", CRAFT),
    Table("reviews", "Review log", "ReviewLog", 100, (S("recordId"),), "Every approval, rejection and send-back of these records.", REVIEW),
    Table("comments", "Comments", "EntryComment", 101, (S("recordId"),), "Comments left on these records.", REVIEW),
    Table("revisions", "Edit history", "RecordRevision", 102, (S("recordId"),), "Every saved change to these records.", REVIEW),
    Table("consents", "Consent ledger", "Consent", 103, (S("subjectId"),), "Consent recorded for these people and recordings.", REVIEW),
    Table("locations", "Locations", "Location", 105, (F(*((m, "locationId") for m in ("Artisan", "Workshop", "ProductDocumentation", "ToolDocumentation", "MediaFile", "QuestionnaireInterview", "DesignerProfile"))),), "Every place recorded, with coordinates.", REVIEW),
    Table("people", "People", "User", 106, (), "Everyone who recorded, reviewed or edited anything here.", REVIEW),
    Table("designers", "Designer profiles", "DesignerProfile", 107, (C("userId", "User"),), "Profiles of the designers named here.", REVIEW, portals=("designer",)),
)

BY_KEY = {t.key: t for t in TABLES}
BY_MODEL = {t.model: t for t in TABLES}
MATERIAL_MODELS = {
    "workshops": "Workshop",
    "crafts": "Craft",
    "artisans": "Artisan",
    "products": "ProductDocumentation",
    "processes": "Process",
    "tools": "ToolDocumentation",
    "interviews": "QuestionnaireInterview",
    "media": "MediaFile",
    "designWorkshops": "DesignWorkshop",
}


def tables_for(portal: str) -> list[Table]:
    models = schema.load(portal)
    return [t for t in TABLES if portal in t.portals and t.model in models]


def _value(row: Any, name: str) -> Any:
    if isinstance(row, dict):
        return row.get(name)
    return getattr(row, name, None)


def _id(row: Any) -> str | None:
    value = _value(row, "id")
    return str(value) if value else None


def display(model: str, row: Any) -> str:
    for name in NAME_FIELDS.get(model, ()):
        value = _value(row, name)
        if value not in (None, ""):
            return str(value)
    return ""


@dataclass
class Store:
    rows: dict[str, list[Any]] = field(default_factory=dict)
    names: dict[str, dict[str, str]] = field(default_factory=dict)
    capped: set[str] = field(default_factory=set)
    withheld: set[str] = field(default_factory=set)
    media_index: dict[str, list[Any]] = field(default_factory=dict)
    _index: dict[str, dict[str, Any]] = field(default_factory=dict)

    def ids(self, model: str) -> list[str]:
        return [i for i in (_id(r) for r in self.rows.get(model, ())) if i]

    def add(self, model: str, rows: list[Any]) -> None:
        bucket = self.rows.setdefault(model, [])
        seen = {_id(r) for r in bucket}
        names = self.names.setdefault(model, {})
        for row in rows:
            rid = _id(row)
            if rid is not None and rid in seen:
                continue
            if rid is not None:
                seen.add(rid)
                label = display(model, row)
                if label:
                    names[rid] = label
            bucket.append(row)
            if len(bucket) >= TABLE_CAP:
                self.capped.add(model)
                break

    def get(self, model: str, rid: Any) -> Any:
        if rid in (None, ""):
            return None
        index = self._index.get(model)
        if index is None or len(index) != len(self.rows.get(model, ())):
            index = {str(_id(r)): r for r in self.rows.get(model, ()) if _id(r)}
            self._index[model] = index
        return index.get(str(rid))

    def name(self, model: str, rid: Any) -> str:
        if rid in (None, ""):
            return ""
        return self.names.get(model, {}).get(str(rid), "")


def _delegate(db: Any, model: str) -> Any:
    return getattr(db, model.lower(), None)


async def _find(db: Any, model: str, where_field: str, ids: list[str]) -> list[Any]:
    delegate = _delegate(db, model)
    if delegate is None or not ids:
        return []
    out: list[Any] = []
    unique = sorted(set(ids))
    for start in range(0, len(unique), CHUNK):
        chunk = unique[start : start + CHUNK]
        if "." in where_field:
            relation, column = where_field.split(".", 1)
            where: dict[str, Any] = {relation: {"is": {column: {"in": chunk}}}}
        else:
            where = {where_field: {"in": chunk}}
        out.extend(await delegate.find_many(where=where, take=TABLE_CAP))
        if len(out) >= TABLE_CAP:
            break
    return out


def allowed(ctx: ReportContext, table: Table) -> bool:
    if ctx.portal not in table.portals:
        return False
    if table.spend and not ctx.sees_spend:
        return False
    return not (table.design_workshop and not bool(getattr(ctx.scope, "design_workshop_downloads", False)))


async def load_store(ctx: ReportContext, db: Any) -> Store:
    store = Store()
    records = ctx.material.get("records") or {}
    for key, model in MATERIAL_MODELS.items():
        if model == "DesignWorkshop" and not bool(getattr(ctx.scope, "design_workshop_downloads", False)):
            continue
        store.add(model, list(records.get(key) or []))
    for m in store.rows.get("MediaFile", ()):
        if _value(m, "transcriptWithheld"):
            store.withheld.add(str(_value(m, "id")))
    for m in store.rows.get("MediaFile", ()):
        for owner in _owners(m):
            store.media_index.setdefault(owner, []).append(m)
    record_ids = [i for model in ("Workshop", "Craft", "Artisan", "ProductDocumentation", "Process", "ToolDocumentation", "QuestionnaireInterview", "MediaFile", "DesignWorkshop") for i in store.ids(model)]
    users_done = False
    for table in tables_for(ctx.portal):
        if not allowed(ctx, table) or table.model == "User":
            continue
        if not users_done and any("User" in load.parent for load in table.loads):
            await _load_users(ctx, db, store)
            users_done = True
        for load in table.loads:
            if load.kind == "records":
                continue
            if load.kind == "children":
                parents = [i for p in load.parent for i in store.ids(p)]
                if table.transcript:
                    parents = [i for i in parents if i not in store.withheld]
                found = await _find(db, table.model, load.fk, parents)
            elif load.kind == "subjects":
                found = await _find(db, table.model, load.fk, record_ids)
            elif load.kind == "unbound":
                delegate = _delegate(db, table.model)
                found = await delegate.find_many(where={load.fk: None}, take=TABLE_CAP) if delegate is not None else []
            else:
                wanted: list[str] = []
                for model, name in load.fields:
                    wanted.extend(str(v) for v in (_value(r, name) for r in store.rows.get(model, ())) if v)
                found = await _find(db, table.model, "id", wanted)
            if table.transcript and store.withheld:
                found = [r for r in found if str(_value(r, "mediaFileId") or "") not in store.withheld]
            store.add(table.model, found)
    await _load_users(ctx, db, store)
    return store


async def _load_users(ctx: ReportContext, db: Any, store: Store) -> None:
    users: set[str] = set()
    models = schema.load(ctx.portal)
    for model, rows in store.rows.items():
        spec = models.get(model)
        if spec is None:
            continue
        user_fields = [f.name for f in spec.fields if target_of(f) == "User"]
        for row in rows:
            for name in user_fields:
                value = _value(row, name)
                if value:
                    users.add(str(value))
    if users:
        delegate = _delegate(db, "User")
        found: list[Any] = []
        ordered = sorted(users)
        for start in range(0, len(ordered), CHUNK):
            found.extend(await delegate.find_many(where={"id": {"in": ordered[start : start + CHUNK]}}))
        store.add("User", found)


def _owners(m: Any) -> list[str]:
    out: list[str] = []
    tag = str(_value(m, "linkedRecordType") or "").lower()
    linked = _value(m, "linkedRecordId")
    if linked and tag:
        out.append(str(linked))
    for fk in ("productId", "toolId", "questionnaireInterviewId", "artisanId", "workshopId", "craftId", "designWorkshopId"):
        value = _value(m, fk)
        if value and str(value) not in out:
            out.append(str(value))
    return out


def _kind_of(f: schema.Field) -> str:
    low = f.name.lower()
    if f.type in ("Int", "BigInt") and low.endswith("bytes"):
        return "bytes"
    if f.type in ("Int", "BigInt") and low.endswith("ms") and ("duration" in low or low in ("startms", "endms", "audioms")):
        return "duration_ms"
    if f.type == "String" and not f.list and low in ("notes", "description", "remarks", "comment", "body", "text", "biography", "dos", "donts", "reviewnotes", "note", "answertext", "drafttext", "machinetext", "editedtext", "machineenglish", "editedenglish", "translationtext", "sourcetext", "decisionnote", "requestnote", "suggestion", "details", "english"):
        return "longtext"
    if f.type == "DateTime" and ("date" in low and not low.endswith("at")):
        return "date"
    return f.kind


def _describe(table: Table, f: schema.Field, label: str) -> str:
    return f"{label} of the {table.title.lower().rstrip('s')} ({table.model}.{f.name})."


@dataclass(frozen=True)
class Plan:
    columns: list[Column]
    fields: list[tuple[str, str, str | None, str]]


def plan(table: Table, portal: str, skip: tuple[str, ...] = ()) -> Plan:
    model = schema.load(portal).get(table.model)
    columns: list[Column] = []
    fields: list[tuple[str, str, str | None, str]] = []
    if model is None:
        return Plan(columns, fields)
    kind = OPEN_KINDS.get(table.model)
    if kind:
        columns.append(Column("open", "Open", "link", "Opens this record in the portal after sign-in.", width=12))
    if table.model == "MediaFile":
        columns.append(Column("download", "Download", "link", "Downloads the file through the portal after sign-in; the link never expires.", width=12))
    ordered = list(model.fields)
    if table.first:
        head = [f for name in table.first for f in ordered if f.name == name]
        ordered = head + [f for f in ordered if f not in head]
    ids: list[Column] = []
    for f in ordered:
        if exclusions.excluded(table.model, f.name) or f.name in skip:
            continue
        label = LABELS.get(f.name) or schema.humanize(f.name)
        if f.is_id and f.name == "id":
            ids.append(Column("id", f"{table.title} ID".replace("s ID", " ID"), "id", f"The record's identifier ({table.model}.id).", hidden=True, group="ids", source=f.source))
            fields.append(("id", "id", None, "id"))
            continue
        target = target_of(f)
        if target and target in NAME_FIELDS:
            base = f.name.removesuffix("Id")
            name_label = schema.humanize(base)
            columns.append(Column(f"{f.name}Name", name_label, "text", f"Name of the linked {schema.humanize(target).lower()}.", source=f.source))
            fields.append((f"{f.name}Name", f.name, target, "name"))
            ids.append(Column(f.name, f"{name_label} ID", "id", f"Identifier of the linked {schema.humanize(target).lower()} ({f.source}).", hidden=True, group="ids", source=f.source))
            fields.append((f.name, f.name, None, "id"))
            continue
        ckind = "id" if (f.is_id or (f.type == "String" and f.name.endswith("Id"))) else _kind_of(f)
        hidden = ckind == "id"
        total = "sum" if ckind in ("bytes", "duration_ms") and table.model == "MediaFile" else None
        columns.append(Column(f.name, label, ckind, _describe(table, f, label), hidden=hidden, group="ids" if hidden else None, total=total, source=f.source, wrap=ckind in ("longtext", "json") or None))
        fields.append((f.name, f.name, None, "enum" if f.enum and ckind in ("text", "status") else ckind))
    if table.model in MEDIA_OWNERS:
        columns.append(Column("files", "Files", "int", "Derived: number of files attached to this record.", total="sum"))
        columns.append(Column("filesBytes", "Files size", "bytes", "Derived: total size of the files attached to this record.", total="sum"))
    return Plan(columns + ids, fields)


def convert(value: Any, kind: str) -> Any:
    if value is None:
        return None
    if kind == "enum":
        return schema.enum_label(value)
    if isinstance(value, Enum):
        return schema.enum_label(value)
    if kind == "json":
        if isinstance(value, str):
            return value
        try:
            return json.dumps(value, ensure_ascii=False, default=str, separators=(", ", ": "))
        except (TypeError, ValueError):
            return str(value)
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (list, tuple)):
        return ", ".join(schema.enum_label(v) if isinstance(v, Enum) else str(v) for v in value)
    if isinstance(value, (datetime, date, bool, int, float)):
        return value
    if kind == "status":
        return schema.enum_label(value)
    return value if isinstance(value, str) else str(value)


def table_rows(ctx: ReportContext, table: Table, store: Store, p: Plan) -> Iterator[dict[str, Any]]:
    kind = OPEN_KINDS.get(table.model)
    wanted = None
    if ctx.options.rows == "filtered":
        from app.services.datareport.contract import query_words

        wanted = query_words(ctx.options.q) or None
    links = ctx.options.links
    for row in store.rows.get(table.model, ()):
        out: dict[str, Any] = {}
        rid = _id(row)
        if kind and rid and links:
            out["open"] = Link(ctx.t("link.openRecord", "Open"), ctx.links.open_record(kind, rid))
        if table.model == "MediaFile" and rid and links:
            out["download"] = Link(ctx.t("link.download", "Download"), ctx.links.download(rid))
        for key, name, target, ckind in p.fields:
            value = _value(row, name)
            if target:
                out[key] = store.name(target, value) or (str(value) if value else None)
            else:
                out[key] = convert(value, ckind)
        if table.model in MEDIA_OWNERS and rid:
            attached = store.media_index.get(rid, [])
            out["files"] = len(attached)
            out["filesBytes"] = sum(int(_value(m, "sizeBytes") or 0) for m in attached)
        if wanted and not row_matches(out, wanted):
            continue
        yield out


def row_matches(row: dict[str, Any], wanted: list[str]) -> bool:
    text = " ".join(str(v.text if isinstance(v, Link) else v) for k, v in row.items() if not k.startswith("_") and v is not None).lower()
    return all(w in text for w in wanted)


def media_of(table: Table, store: Store) -> tuple[list[str], int]:
    if table.model == "MediaFile":
        rows = store.rows.get("MediaFile", ())
        return [str(_value(m, "id")) for m in rows], sum(int(_value(m, "sizeBytes") or 0) for m in rows)
    if table.model not in MEDIA_OWNERS:
        return [], 0
    seen: dict[str, int] = {}
    for rid in store.ids(table.model):
        for m in store.media_index.get(rid, []):
            seen[str(_value(m, "id"))] = int(_value(m, "sizeBytes") or 0)
    return list(seen), sum(seen.values())


_STORE_LOCK = "__tables_lock"


async def shared_store(ctx: ReportContext) -> Store:
    found = ctx.shared.get("store")
    if found is not None:
        return found
    lock = ctx.shared.setdefault(_STORE_LOCK, asyncio.Lock())
    async with lock:
        found = ctx.shared.get("store")
        if found is None:
            db = ctx.shared.get("db")
            if db is None:
                from app.core.db import db as default_db

                db = default_db
            found = await load_store(ctx, db)
            ctx.shared["store"] = found
    return found


@dataclass
class TablePlugin:
    table: Table
    key: str = ""
    title: str = ""
    order: int = 0
    option: str | None = None
    portals: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        self.key = self.table.key
        self.title = self.table.title
        self.order = self.table.order
        self.portals = self.table.portals

    def catalog(self) -> list[tuple[str, list[Column], str]]:
        out: list[tuple[str, list[Column], str]] = []
        for portal in self.portals:
            if self.table.model in schema.load(portal):
                out.append((self.table.key, plan(self.table, portal).columns, self.table.title))
                break
        return out

    async def build(self, ctx: ReportContext) -> list[Sheet]:
        if not allowed(ctx, self.table) or self.table.model not in schema.load(ctx.portal):
            return []
        store = await shared_store(ctx)
        p = plan(self.table, ctx.portal)
        rows = list(table_rows(ctx, self.table, store, p))
        media_ids, media_bytes = media_of(self.table, store)
        note = None
        if self.table.model in store.capped:
            note = ctx.t("banner.capped", "Capped at {n} rows — the full data set has more.").format(n=f"{TABLE_CAP:,}")
        return [
            Sheet(
                key=self.table.key,
                name=self.table.title,
                columns=p.columns,
                rows=rows,
                color=self.table.color,
                note=note,
                description=self.table.description,
                media_ids=media_ids,
                media_bytes=media_bytes,
                group=DW_GROUP if self.table.design_workshop else None,
                count=len(rows),
                databar_columns=["filesBytes"] if self.table.model in MEDIA_OWNERS else [],
            )
        ]


def register_all() -> None:
    for table in TABLES:
        register(TablePlugin(table))
