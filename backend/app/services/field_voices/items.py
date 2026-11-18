from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from app.services.field_voices import contract, people, scrub
from app.services.field_voices.sources import Sources, day_text
from app.services.field_voices.stages import StagePlacement
from app.transcription import ledger as consent_ledger

__all__ = [
    "Labels",
    "Outcome",
    "Subjects",
    "crafts_for",
    "refused_items",
    "scrubbing_block",
    "workshop_for",
]

PUBLICATION = consent_ledger.PUBLICATION
CROSS_PORTAL_SHARING = consent_ledger.CROSS_PORTAL_SHARING

WITHHELD_REFUSED = "refused"
WITHHELD_NO_CRAFT = "noCraft"
WITHHELD_IDENTITY = "identity"
WITHHELD_NO_TRANSCRIPT = "noTranscript"
WITHHELD_NO_PLACEMENT = "noPlacement"
WITHHELD_REMOVED = "removed"
WITHHELD_ARTISAN_GONE = "artisanGone"


@dataclass
class Outcome:
    item_id: str
    payload: dict[str, Any] | None = None
    reason: str | None = None
    successor: str | None = None
    copy_key: str | None = None
    copy_of: str | None = None
    withheld: str | None = None
    subjects: dict[str, list[str]] | None = None

    @property
    def live(self) -> bool:
        return self.payload is not None


def gone(item_id: str, reason: str, withheld: str, successor: str | None = None) -> Outcome:
    return Outcome(item_id=item_id, reason=reason, withheld=withheld, successor=successor)


SUBJECT_KINDS = ("media", "artisans", "workshops", "users")


@dataclass
class Subjects:
    media: set[str] = field(default_factory=set)
    artisans: set[str] = field(default_factory=set)
    workshops: set[str] = field(default_factory=set)
    users: set[str] = field(default_factory=set)

    def stored(self) -> dict[str, list[str]]:
        return {kind: sorted(str(i) for i in getattr(self, kind) if i) for kind in SUBJECT_KINDS}

    def joined(self, other: Subjects) -> Subjects:
        return Subjects(*(getattr(self, kind) | getattr(other, kind) for kind in SUBJECT_KINDS))

    @classmethod
    def from_stored(cls, value: Any) -> Subjects:
        if not isinstance(value, dict):
            return cls()
        return cls(*({str(i) for i in value.get(kind) or [] if i} for kind in SUBJECT_KINDS))


Labels = scrub.Labels


def scrubbing_block(counts: dict[str, int], nulled: Iterable[str], *more: dict[str, int]) -> dict[str, Any]:
    return {
        "version": scrub.SCRUB_VERSION,
        "replaced": [
            {"kind": kind, "count": int(counts.get(kind, 0)) + sum(int(m.get(kind, 0)) for m in more)}
            for kind in scrub.KINDS
        ],
        "nulled": sorted(set(nulled)),
    }


def _ends(sources: Sources, craft_ids: Iterable[str | None]) -> list[str]:
    out: list[str] = []
    for craft_id in craft_ids:
        end = sources.craft_end(craft_id)
        if end and end not in out:
            out.append(end)
    return out


def crafts_for(
    sources: Sources,
    *,
    own: Iterable[str | None],
    records: Iterable[str | None],
    context: Iterable[Iterable[str | None]],
    artisans: Iterable[str | None],
) -> list[str]:
    for tier in (own, records):
        found = _ends(sources, tier)
        if found:
            return found
    for group in context:
        found = _ends(sources, group)
        if len(found) == 1:
            return found
    return _ends(sources, artisans)


def product_crafts(sources: Sources, product_id: str | None) -> list[str | None]:
    product = sources.products.get(product_id or "")
    return [product.get("craftId")] if product else []


def tool_crafts(sources: Sources, tool_id: str | None) -> list[str | None]:
    tool = sources.tools.get(tool_id or "")
    if not tool:
        return []
    return [tool.get("craftId"), *sources.tool_crafts.get(tool["id"], [])]


def process_product(sources: Sources, process_id: str | None) -> str | None:
    process = sources.processes.get(process_id or "")
    return process.get("productId") if process else None


def step_process(sources: Sources, step_id: str | None) -> str | None:
    step = sources.steps.get(step_id or "")
    return step.get("processId") if step else None


def product_artisans(sources: Sources, product_id: str | None) -> list[str | None]:
    product = sources.products.get(product_id or "")
    return [product.get("artisanId")] if product else []


def record_artisans(sources: Sources, kind: str, record_id: str | None) -> list[str | None]:
    if kind == "PRODUCT":
        return product_artisans(sources, record_id)
    if kind == "TOOL":
        tool = sources.tools.get(record_id or "") or {}
        return [tool.get("artisanId"), *sources.tool_artisans.get(record_id or "", [])]
    if kind == "PROCESS":
        return product_artisans(sources, process_product(sources, record_id))
    if kind == "PROCESS_STEP":
        return product_artisans(sources, process_product(sources, step_process(sources, record_id)))
    return []


def stage_artisans(sources: Sources, placement: StagePlacement) -> list[str | None]:
    found: list[str | None] = list(placement.artisan_ids)
    for product_id in placement.product_ids:
        found += product_artisans(sources, product_id)
    return found


def _area(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if not row:
        return None
    return people.area(row.get("state"), row.get("district"))


def design_workshop_object(sources: Sources, dw_id: str, labels: Labels) -> dict[str, Any] | None:
    row = sources.design_workshops.get(dw_id)
    if not row or row.get("deletedAt"):
        return None
    return {
        "kind": "DESIGN_WORKSHOP",
        "id": row["id"],
        "code": row.get("workshopCode"),
        "title": labels.screen("workshop.title", row.get("title")),
        "startsOn": day_text(row.get("startDate")),
        "endsOn": day_text(row.get("endDate")),
        "area": people.area(row.get("state"), row.get("district")),
    }


def plain_workshop_object(sources: Sources, workshop_id: str, labels: Labels) -> dict[str, Any] | None:
    row = sources.workshops.get(workshop_id)
    if not row:
        return None
    return {
        "kind": "WORKSHOP",
        "id": row["id"],
        "code": None,
        "title": labels.screen("workshop.title", row.get("title")),
        "startsOn": day_text(row.get("startDate") or row.get("date")),
        "endsOn": day_text(row.get("endDate")),
        "area": _area(row),
    }


def workshop_for(
    sources: Sources, *, design_ids: Iterable[str | None], workshop_ids: Iterable[str | None], labels: Labels
) -> dict[str, Any] | None:
    for dw_id in design_ids:
        if dw_id:
            found = design_workshop_object(sources, dw_id, labels)
            if found:
                return found
    for workshop_id in workshop_ids:
        if workshop_id:
            found = plain_workshop_object(sources, workshop_id, labels)
            if found:
                return found
    return None


def area_of(row: dict[str, Any] | None) -> dict[str, Any] | None:
    return _area(row)


async def _refused(client: Any, subject_type: str, ids: set[str], purpose: str) -> set[str]:
    if not ids:
        return set()
    states = await consent_ledger.current_states(client, subject_type, sorted(ids), purpose=purpose)
    return {sid for sid, row in states.items() if str(row.get("state")) == consent_ledger.REFUSED}


async def refused_items(client: Any, adapter: Any, subjects: dict[str, Subjects]) -> set[str]:
    media = {m for s in subjects.values() for m in s.media}
    artisans = {a for s in subjects.values() for a in s.artisans}
    workshops = {w for s in subjects.values() for w in s.workshops}
    users = {u for s in subjects.values() for u in s.users}
    refused_media = await _refused(client, consent_ledger.RECORDING, media, PUBLICATION)
    refused_artisans = await _refused(client, consent_ledger.ARTISAN, artisans, PUBLICATION)
    refused_artisans |= await _refused(client, consent_ledger.ARTISAN, artisans, CROSS_PORTAL_SHARING)
    refused_artisans |= set(await adapter.extra_refusals(client, sorted(artisans)))
    refused_workshops = await _refused(client, consent_ledger.WORKSHOP, workshops, PUBLICATION)
    refused_users = await _refused(client, consent_ledger.USER, users, PUBLICATION)
    return {
        item_id
        for item_id, found in subjects.items()
        if found.media & refused_media
        or found.artisans & refused_artisans
        or found.workshops & refused_workshops
        or found.users & refused_users
    }


def artisan_craft(sources: Sources, directory: people.Directory, artisan_id: str) -> dict[str, Any] | None:
    facts = directory.artisans.get(artisan_id)
    end = sources.craft_end(facts.craft_id) if facts else None
    return sources.craft_object(end) if end else None


def missing_artisans(directory: people.Directory, artisan_ids: Iterable[str]) -> bool:
    return any(directory.survivor(a) not in directory.artisans for a in artisan_ids if a)


def held_for(directory: people.Directory, *, artisans: Iterable[str], users: Iterable[str]) -> scrub.HeldBack:
    terms = people.held_terms(directory, artisans, users)
    return scrub.held_back(numbers=terms["numbers"], emails=terms["emails"])


def assert_payload(payload: dict[str, Any]) -> dict[str, Any]:
    contract.assert_sendable(payload)
    return payload
