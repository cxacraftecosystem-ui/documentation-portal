"""What one record looks like in the search index: one builder per type (docs/SEARCH.md).

``build(entity_type, entity_id)`` reads one record and returns the ``SearchDocument`` row for it, or
``None`` when the record is gone or has nothing to index (the indexer then deletes the document).
The indexer (``services/search_indexer.py``) writes what this returns; nothing else decides what
goes into the index.

EVERY BUILDER IS AN ALLOWLIST. A field is in the document only because it is named below. Aadhaar,
Pehchan, phone, e-mail, date of birth and the stated address are never named, and :func:`scrub`
removes anything shaped like an identity number or an e-mail address from the free text that is, so
a phone number typed into a remark does not become searchable either.

Every document here is ``audience = 'ALL'``: on the Field Repository every signed-in account may
read every record (``records.viewable_where``). Clusters, design workshops and reports are designer
portal types and are never built here.

``shareable`` says whether the record may be shown to the designer portal at all: it names no
artisan, or every artisan it names has granted sharing consent (``ArtisanSharingConsent``) and is
not rejected. A record that names an artisan only as free text (an ``artisanName`` with no id) is
never shareable. The peer route re-checks consent live in SQL as well, so a withdrawal takes effect
before the indexer catches up.
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Awaitable, Callable
from datetime import datetime
from typing import Any
from urllib.parse import quote

from app.core.db import db

#: Longest body kept. Far below the 1 MB tsvector ceiling even for a long transcript.
BODY_LIMIT = 100_000

AUDIENCE_ALL = "ALL"

#: Where each type's read-only view lives on the web. ``{id}`` is the entity id, URL-quoted.
OPEN_PATHS: dict[str, str] = {
    "artisans": "/artisans/{id}",
    "crafts": "/crafts/{id}",
    "workshops": "/workshops/{id}",
    "interviews": "/questionnaire/interviews/{id}",
    "transcripts": "/media/{id}",
    "products": "/products/{id}",
    "tools": "/tools/{id}",
    "processes": "/processes/{id}",
    "media": "/media/{id}",
}

#: Questionnaire prompts whose answers are identity, never indexed.
_IDENTITY_PROMPT = re.compile(
    r"aadha+r|pehchan|phone|mobile|e-?mail|date of birth|\bdob\b|address|bank|account|ifsc",
    re.IGNORECASE,
)
#: Identity-shaped text inside free text: e-mail addresses and runs of ten or more digits (phone,
#: Aadhaar, card numbers), spaces and dashes allowed between the digits.
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_LONG_NUMBER = re.compile(r"(?<!\d)(?:\d[ \-]?){9,}\d(?!\d)")


def open_path(entity_type: str, entity_id: str) -> str:
    return OPEN_PATHS[entity_type].format(id=quote(entity_id, safe=""))


def scrub(text: str | None) -> str:
    """Free text with e-mail addresses and identity-length numbers taken out."""
    if not text:
        return ""
    return _LONG_NUMBER.sub(" ", _EMAIL.sub(" ", str(text))).strip()


def _join(*parts: Any, sep: str = " · ") -> str:
    return sep.join(str(p).strip() for p in parts if p is not None and str(p).strip())


def _body(*parts: Any) -> str:
    return scrub("\n".join(str(p).strip() for p in parts if p and str(p).strip()))[:BODY_LIMIT]


def _ids(*values: Any) -> list[str]:
    out: list[str] = []
    for value in values:
        if value and str(value) not in out:
            out.append(str(value))
    return out


def _status(row: Any) -> str:
    value = getattr(row, "status", None)
    return str(getattr(value, "value", value) or "")


def content_hash(doc: dict[str, Any]) -> str:
    material = "\x1f".join(
        (doc.get("title") or "", doc.get("subtitle") or "", doc.get("body") or "")
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


async def _all_consenting(artisan_ids: list[str]) -> bool:
    """Every named artisan has granted sharing consent and is not rejected."""
    if not artisan_ids:
        return True
    granted = await db.artisansharingconsent.find_many(
        where={"artisanId": {"in": artisan_ids}, "granted": True}
    )
    ok = {c.artisanId for c in granted}
    if ok != set(artisan_ids):
        return False
    rejected = await db.artisan.count(where={"id": {"in": artisan_ids}, "status": "REJECTED"})
    return rejected == 0


async def _shareable(
    artisan_ids: list[str], *, free_text_artisan: bool = False, rejected: bool = False
) -> bool:
    if free_text_artisan or rejected:
        return False
    return await _all_consenting(artisan_ids)


def _place_of(record: Any) -> str | None:
    location = getattr(record, "location", None)
    parts = [getattr(record, "place", None)]
    if location is not None:
        parts += [location.village, location.district, location.state]
    seen: list[str] = []
    for part in parts:
        if part and str(part).strip() and str(part).strip() not in seen:
            seen.append(str(part).strip())
    return ", ".join(seen) or None


def _doc(entity_type: str, entity_id: str, **fields: Any) -> dict[str, Any]:
    doc: dict[str, Any] = {
        "entityType": entity_type,
        "entityId": entity_id,
        "subtitle": None,
        "body": "",
        "place": None,
        "craftIds": [],
        "craftCodes": [],
        "workshopIds": [],
        "artisanIds": [],
        "designWorkshopId": None,
        "audience": AUDIENCE_ALL,
        "shareable": False,
        "openPath": open_path(entity_type, entity_id),
        "recordDate": None,
    }
    doc.update(fields)
    doc["title"] = (scrub(doc.get("title")) or "Untitled")[:500]
    if doc.get("subtitle"):
        doc["subtitle"] = scrub(doc["subtitle"])[:500] or None
    if doc.get("place"):
        doc["place"] = scrub(doc["place"])[:500] or None
    doc["body"] = (doc.get("body") or "")[:BODY_LIMIT]
    return doc


def _codes(*crafts: Any) -> list[str]:
    return _ids(*(getattr(c, "craftCode", None) for c in crafts if c is not None))


def _free_text_artisan(row: Any, artisan_ids: list[str]) -> bool:
    return bool((getattr(row, "artisanName", None) or "").strip()) and not artisan_ids


async def _artisan(entity_id: str) -> dict[str, Any] | None:
    row = await db.artisan.find_unique(
        where={"id": entity_id},
        include={"craft": True, "location": True, "workshops": True, "sharingConsent": True},
    )
    if row is None:
        return None
    craft = row.craft
    consent = row.sharingConsent
    return _doc(
        "artisans",
        row.id,
        title=row.name,
        subtitle=_join(craft.name if craft else None, row.place),
        body=_body(
            row.localName, craft.name if craft else None, craft.localName if craft else None
        ),
        place=_place_of(row),
        craftIds=_ids(row.craftId),
        craftCodes=_codes(craft),
        workshopIds=_ids(row.workshopId, *(link.workshopId for link in row.workshops or [])),
        artisanIds=[row.id],
        shareable=bool(consent and consent.granted) and _status(row) != "REJECTED",
        recordDate=row.recordedAt or row.createdAt,
    )


async def _craft(entity_id: str) -> dict[str, Any] | None:
    row = await db.craft.find_unique(where={"id": entity_id})
    if row is None:
        return None
    return _doc(
        "crafts",
        row.id,
        title=row.name,
        subtitle=_join(row.localName, row.category),
        body=_body(row.localName, row.description),
        place=row.place,
        craftIds=[row.id],
        craftCodes=_ids(row.craftCode),
        workshopIds=_ids(row.workshopId),
        shareable=True,
        recordDate=row.recordedAt or row.createdAt,
    )


async def _workshop(entity_id: str) -> dict[str, Any] | None:
    row = await db.workshop.find_unique(
        where={"id": entity_id}, include={"location": True, "crafts": {"include": {"craft": True}}}
    )
    if row is None:
        return None
    kind = (
        str(getattr(row.workshopType, "value", row.workshopType)).replace("_", " ").title()
        if row.workshopType
        else None
    )
    crafts = [link.craft for link in row.crafts or [] if link.craft is not None]
    return _doc(
        "workshops",
        row.id,
        title=row.title,
        subtitle=_join(kind, row.place),
        body=_body(row.description, *(c.name for c in crafts)),
        place=_place_of(row),
        craftIds=_ids(*(c.id for c in crafts)),
        craftCodes=_codes(*crafts),
        workshopIds=[row.id],
        shareable=_status(row) != "REJECTED",
        recordDate=row.startDate or row.date,
    )


async def _interview(entity_id: str) -> dict[str, Any] | None:
    row = await db.questionnaireinterview.find_unique(
        where={"id": entity_id},
        include={"artisans": True, "location": True, "responses": {"include": {"question": True}}},
    )
    if row is None:
        return None
    answers: list[str] = []
    for response in row.responses or []:
        prompt = response.question.prompt if response.question else ""
        if _IDENTITY_PROMPT.search(prompt or "") or not (response.answerText or "").strip():
            continue
        answers.append(response.answerText.strip())
    artisan_ids = _ids(*(link.artisanId for link in row.artisans or []))
    return _doc(
        "interviews",
        row.id,
        title=row.title,
        subtitle=_join(row.language, row.place),
        body=_body(row.notes, *answers),
        place=_place_of(row),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        shareable=await _shareable(artisan_ids, rejected=_status(row) == "REJECTED"),
        recordDate=row.interviewDate or row.createdAt,
    )


def _media_kind(row: Any) -> str:
    value = getattr(row.mediaType, "value", row.mediaType)
    return str(value).split(".")[-1].title() if value else "File"


async def _media_common(row: Any) -> dict[str, Any]:
    artisan_ids = _ids(row.artisanId)
    return {
        "place": _place_of(row),
        "craftIds": _ids(row.craftId),
        "craftCodes": _codes(getattr(row, "craft", None)),
        "workshopIds": _ids(row.workshopId),
        "artisanIds": artisan_ids,
        "shareable": await _shareable(artisan_ids, rejected=_status(row) == "REJECTED"),
        "recordDate": row.recordedAt or row.createdAt,
    }


async def _media(entity_id: str) -> dict[str, Any] | None:
    row = await db.mediafile.find_unique(
        where={"id": entity_id}, include={"location": True, "craft": True}
    )
    if row is None:
        return None
    return _doc(
        "media",
        row.id,
        title=row.caption or row.originalFilename,
        subtitle=_join(_media_kind(row), row.originalFilename if row.caption else None),
        body=_body(row.originalFilename, row.caption, _media_kind(row)),
        **await _media_common(row),
    )


async def _transcript(entity_id: str) -> dict[str, Any] | None:
    row = await db.mediafile.find_unique(
        where={"id": entity_id}, include={"location": True, "craft": True}
    )
    if row is None or not (row.transcriptText or "").strip():
        return None
    return _doc(
        "transcripts",
        row.id,
        title=row.caption or row.originalFilename,
        subtitle=_join("Transcript", _media_kind(row)),
        body=_body(row.originalFilename, row.caption, row.transcriptSummary, row.transcriptText),
        **await _media_common(row),
    )


async def _product(entity_id: str) -> dict[str, Any] | None:
    row = await db.productdocumentation.find_unique(
        where={"id": entity_id}, include={"craft": True, "location": True}
    )
    if row is None:
        return None
    artisan_ids = _ids(row.artisanId)
    return _doc(
        "products",
        row.id,
        title=row.productName,
        subtitle=_join(row.craftName, row.artisanName),
        body=_body(row.localName, row.craftName, row.artisanName, row.remarks),
        place=_place_of(row),
        craftIds=_ids(row.craftId),
        craftCodes=_codes(row.craft),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        shareable=await _shareable(
            artisan_ids,
            free_text_artisan=_free_text_artisan(row, artisan_ids),
            rejected=_status(row) == "REJECTED",
        ),
        recordDate=row.recordedAt or row.createdAt,
    )


async def _tool(entity_id: str) -> dict[str, Any] | None:
    row = await db.tooldocumentation.find_unique(
        where={"id": entity_id},
        include={
            "craft": True,
            "location": True,
            "artisanLinks": True,
            "craftLinks": {"include": {"craft": True}},
        },
    )
    if row is None:
        return None
    artisan_ids = _ids(row.artisanId, *(link.artisanId for link in row.artisanLinks or []))
    linked_crafts = [link.craft for link in row.craftLinks or [] if link.craft is not None]
    return _doc(
        "tools",
        row.id,
        title=row.toolkitName,
        subtitle=_join(row.englishName, row.craftName, row.artisanName),
        body=_body(
            row.localName, row.englishName, row.craftName, row.artisanName, row.processUsedIn,
            row.material, row.remarks,
        ),
        place=_place_of(row),
        craftIds=_ids(row.craftId, *(c.id for c in linked_crafts)),
        craftCodes=_codes(row.craft, *linked_crafts),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        shareable=await _shareable(
            artisan_ids,
            free_text_artisan=_free_text_artisan(row, artisan_ids),
            rejected=_status(row) == "REJECTED",
        ),
        recordDate=row.recordedAt or row.createdAt,
    )


async def _process(entity_id: str) -> dict[str, Any] | None:
    row = await db.process.find_unique(
        where={"id": entity_id}, include={"steps": True, "product": {"include": {"craft": True}}}
    )
    if row is None:
        return None
    steps = sorted(row.steps or [], key=lambda s: s.sortOrder)
    product = row.product
    artisan_ids = _ids(product.artisanId if product else None)
    return _doc(
        "processes",
        row.id,
        title=row.name,
        subtitle=_join(
            product.productName if product else None, f"{len(steps)} steps" if steps else None
        ),
        body=_body(row.notes, *(_join(s.name, s.notes, sep=": ") for s in steps)),
        craftIds=_ids(product.craftId if product else None),
        craftCodes=_codes(product.craft if product else None),
        workshopIds=_ids(row.workshopId),
        artisanIds=artisan_ids,
        shareable=await _shareable(
            artisan_ids,
            free_text_artisan=bool(product) and _free_text_artisan(product, artisan_ids),
            rejected=_status(row) == "REJECTED",
        ),
        recordDate=row.recordedAt or row.createdAt,
    )


BUILDERS: dict[str, Callable[[str], Awaitable[dict[str, Any] | None]]] = {
    "artisans": _artisan,
    "crafts": _craft,
    "workshops": _workshop,
    "interviews": _interview,
    "transcripts": _transcript,
    "products": _product,
    "tools": _tool,
    "processes": _process,
    "media": _media,
}


async def build(entity_type: str, entity_id: str) -> dict[str, Any] | None:
    """The document for one record, or ``None`` (delete it). Unknown types build nothing."""
    builder = BUILDERS.get(entity_type)
    if builder is None:
        return None
    doc = await builder(entity_id)
    if doc is None:
        return None
    doc["contentHash"] = content_hash(doc)
    if isinstance(doc.get("recordDate"), datetime):
        doc["recordDate"] = doc["recordDate"].isoformat()
    return doc


async def dependents_of_artisan(artisan_id: str) -> list[tuple[str, str]]:
    """The records whose ``shareable`` depends on this artisan's consent."""
    products = await db.productdocumentation.find_many(where={"artisanId": artisan_id})
    tools = await db.tooldocumentation.find_many(
        where={"OR": [{"artisanId": artisan_id}, {"artisanLinks": {"some": {"artisanId": artisan_id}}}]}
    )
    interviews = await db.questionnaireinterviewartisan.find_many(where={"artisanId": artisan_id})
    media = await db.mediafile.find_many(where={"artisanId": artisan_id})
    out: list[tuple[str, str]] = [("products", p.id) for p in products]
    if products:
        processes = await db.process.find_many(where={"productId": {"in": [p.id for p in products]}})
        out += [("processes", p.id) for p in processes]
    out += [("tools", t.id) for t in tools]
    out += [("interviews", i.interviewId) for i in interviews]
    for m in media:
        out += [("media", m.id), ("transcripts", m.id)]
    return out
