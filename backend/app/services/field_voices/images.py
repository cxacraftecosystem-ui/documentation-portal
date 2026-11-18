from __future__ import annotations

import asyncio
import hashlib
import io
import logging
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from typing import Any

from app.services import naming
from app.services.field_voices import contract, items, people, scrub
from app.services.field_voices.items import Labels, Outcome, Subjects
from app.services.field_voices.sources import Sources, iso_moment, ist_day, load_sources, plain_json
from app.services.field_voices.stages import StagePlacement

__all__ = [
    "CANDIDATES_SQL",
    "Copier",
    "ImageCopy",
    "Undecodable",
    "candidates",
    "copy_of",
    "evaluate",
    "groups",
    "neutral_copy",
]

logger = logging.getLogger(__name__)

PRODUCT = "PRODUCT"
TOOL = "TOOL"
PROCESS = "PROCESS"
PROCESS_STEP = "PROCESS_STEP"
CRAFT = "CRAFT"
DESIGN_STAGE = "DESIGN_STAGE"
ARTISAN = "ARTISAN"
SESSION = "SESSION"
WORKSHOP = "WORKSHOP"
DESIGN_WORKSHOP = "DESIGN_WORKSHOP"
DESIGNER = "DESIGNER"

LONG_EDGE = 2400
MAX_COPY_BYTES = 8 * 1024 * 1024
MAX_SOURCE_BYTES = 50 * 1024 * 1024
MAX_SOURCE_PIXELS = 60_000_000
JPEG_QUALITIES = (85, 75, 65, 55)
JPEG = ("jpg", "image/jpeg")
PNG = ("png", "image/png")
IDENTITY_METADATA_KEY = "identityPhotograph"
DEFERRED = "deferred"
UNDECODABLE = "undecodable"
OTHER_LINK_COLUMNS = ("artisanId", "workshopId", "productId", "toolId", "questionnaireInterviewId")
ARTISAN_ENTITIES = frozenset({"participant"})
ARTISAN_LINKS = frozenset({"artisan", "questionnaire", "questionnaireinterview", "questionnaire_session"})

CANDIDATES_SQL = (
    'SELECT m."id", m."createdAt", CASE WHEN m."shaVerifiedAt" IS NOT NULL THEN m."contentSha256" END AS "sha" '
    'FROM "MediaFile" m WHERE m."mediaType"::text = \'IMAGE\' AND m."removedAt" IS NULL '
    'AND m."status"::text NOT IN (\'DRAFT\', \'REJECTED\') ORDER BY m."id"'
)


class Undecodable(Exception):
    pass


@dataclass(frozen=True)
class ImageCopy:
    data: bytes
    ext: str
    mime_type: str
    width: int
    height: int

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.data).hexdigest()

    def meta(self) -> dict[str, Any]:
        return {
            "mimeType": self.mime_type,
            "width": self.width,
            "height": self.height,
            "bytes": len(self.data),
            "sha256": self.sha256,
        }


def _has_alpha(image: Any) -> bool:
    if image.mode in ("RGBA", "LA", "PA"):
        return image.getchannel("A").getextrema()[0] < 255
    return image.mode == "P" and "transparency" in image.info


def _encode(image: Any, *, alpha: bool, quality: int) -> bytes:
    buffer = io.BytesIO()
    if alpha:
        image.save(buffer, format="PNG", optimize=True)
    else:
        image.save(buffer, format="JPEG", quality=quality, optimize=True, progressive=True, exif=b"")
    return buffer.getvalue()


def _clean(image: Any, *, alpha: bool) -> Any:
    from PIL import Image

    mode = "RGBA" if alpha else "RGB"
    converted = image.convert(mode)
    blank = Image.new(mode, converted.size)
    blank.paste(converted)
    return blank


def neutral_copy(data: bytes) -> ImageCopy:
    from PIL import Image, ImageOps, UnidentifiedImageError

    try:
        with Image.open(io.BytesIO(data)) as source:
            width, height = source.size
            if width * height > MAX_SOURCE_PIXELS:
                raise Undecodable(f"{width}x{height} is over {MAX_SOURCE_PIXELS} pixels")
            source.load()
            upright = ImageOps.exif_transpose(source)
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as exc:
        raise Undecodable(str(exc)) from exc
    upright.thumbnail((LONG_EDGE, LONG_EDGE))
    alpha = _has_alpha(upright)
    image = _clean(upright, alpha=alpha)
    ext, mime = PNG if alpha else JPEG
    for quality in JPEG_QUALITIES:
        encoded = _encode(image, alpha=alpha, quality=quality)
        if len(encoded) <= MAX_COPY_BYTES:
            return ImageCopy(encoded, ext, mime, image.width, image.height)
        if alpha:
            image = image.resize((max(1, image.width * 3 // 4), max(1, image.height * 3 // 4)))
    raise Undecodable("the copy stays over 8 MiB")


def copy_of(media: dict[str, Any]) -> str:
    marker = media.get("contentSha256") or media.get("sizeBytes")
    return f"{media['objectKey']} {marker}"


def _fetch(object_key: str) -> bytes:
    from app.services import s3

    return s3.get_object_bytes(object_key, max_bytes=MAX_SOURCE_BYTES)


def _store(key: str, data: bytes, mime_type: str) -> None:
    from app.services import s3

    s3.put_object_bytes(key, data, mime_type=mime_type)


@dataclass
class Copier:
    budget: int = contract.COPIES_PER_REFRESH
    fetch: Callable[[str], bytes] = _fetch
    store: Callable[[str, bytes, str], None] = _store
    undecodable: set[str] = field(default_factory=set)
    made: int = 0
    stored: int = 0
    failed: int = 0
    skipped: int = 0
    refused: int = 0
    known: dict[str, tuple[str, dict[str, Any]]] = field(default_factory=dict)

    def remember(self, source: str, key: str, meta: dict[str, Any]) -> None:
        self.known[source] = (key, meta)

    def _make(self, object_key: str) -> tuple[str, dict[str, Any]]:
        from app.services import s3

        try:
            data = self.fetch(object_key)
        except s3.ObjectTooLarge as exc:
            raise Undecodable(str(exc)) from exc
        made = neutral_copy(data)
        key = naming.field_voice_copy_key(made.sha256, made.ext)
        self.store(key, made.data, made.mime_type)
        return key, made.meta()

    async def copy(self, media: dict[str, Any]) -> tuple[str, dict[str, Any]] | str:
        source = copy_of(media)
        if source in self.known:
            return self.known[source]
        if source in self.undecodable:
            return UNDECODABLE
        if self.made >= self.budget:
            self.skipped += 1
            return DEFERRED
        self.made += 1
        try:
            key, meta = await asyncio.to_thread(self._make, str(media["objectKey"]))
        except Undecodable as exc:
            logger.info("field voices: image %s is not listed: %s", media["id"], exc)
            self.undecodable.add(source)
            self.refused += 1
            return UNDECODABLE
        except Exception:
            self.failed += 1
            logger.exception("field voices: could not copy image %s", media["id"])
            return DEFERRED
        self.known[source] = (key, meta)
        self.stored += 1
        return key, meta


async def candidates(client: Any) -> list[dict[str, Any]]:
    rows = {str(r["id"]): dict(r) for r in await client.query_raw(CANDIDATES_SQL)}
    return [rows[k] for k in sorted(rows)]


def groups(rows: Iterable[dict[str, Any]]) -> list[list[str]]:
    by_sha: dict[str, list[dict[str, Any]]] = {}
    alone: list[list[str]] = []
    for row in rows:
        if row.get("sha"):
            by_sha.setdefault(str(row["sha"]), []).append(row)
        else:
            alone.append([str(row["id"])])
    for members in by_sha.values():
        ordered = sorted(members, key=lambda r: (str(r.get("createdAt") or ""), str(r["id"])))
        alone.append([str(r["id"]) for r in ordered])
    return sorted(alone, key=lambda g: g[0])


@dataclass(frozen=True)
class Placement:
    kind: str
    record_id: str | None = None
    stage: StagePlacement | None = None


def _linked(media: dict[str, Any], kind: str) -> str | None:
    if str(media.get("linkedRecordType") or "").lower() == kind and media.get("linkedRecordId"):
        return str(media["linkedRecordId"])
    return None


def _craft_only(media: dict[str, Any], sources: Sources) -> bool:
    if not (media.get("craftId") or _linked(media, "craft")):
        return False
    if any(media.get(column) for column in OTHER_LINK_COLUMNS):
        return False
    link = str(media.get("linkedRecordType") or "").lower()
    if link and link != "craft":
        return False
    return str(media["id"]) not in sources.media_design_workshops


def _filed_on(sources: Sources, media: dict[str, Any]) -> list[Placement]:
    found: list[Placement] = []
    artisan = media.get("artisanId") or _linked(media, "artisan")
    if artisan:
        found.append(Placement(ARTISAN, str(artisan)))
    session = sources.session_end(media.get("questionnaireInterviewId"))
    if session is not None and not session.get("deletedAt"):
        found.append(Placement(SESSION, str(session["id"])))
    workshop = media.get("workshopId") or _linked(media, "workshop")
    if workshop and str(workshop) in sources.workshops:
        found.append(Placement(WORKSHOP, str(workshop)))
    design_id = sources.media_design_workshops.get(str(media["id"]))
    design = sources.design_workshops.get(design_id or "")
    if not found and design_id and design and not design.get("deletedAt"):
        found.append(Placement(DESIGN_WORKSHOP, design_id))
    return found


def own_placements(
    sources: Sources, media: dict[str, Any], staged: list[StagePlacement], designer_photos: dict[str, str] | None = None
) -> list[Placement]:
    out: list[Placement] = []
    for product_id in (media.get("productId"), _linked(media, "product"), *sources.measured.get(str(media["id"]), [])):
        if product_id and product_id in sources.products:
            out.append(Placement(PRODUCT, str(product_id)))
    for tool_id in (media.get("toolId"), _linked(media, "tool")):
        if tool_id and tool_id in sources.tools:
            out.append(Placement(TOOL, str(tool_id)))
    process_id = _linked(media, "process")
    if process_id and process_id in sources.processes:
        out.append(Placement(PROCESS, process_id))
    step_id = _linked(media, "processstep")
    if step_id and step_id in sources.steps:
        out.append(Placement(PROCESS_STEP, step_id))
    out += [Placement(DESIGN_STAGE, p.row_id, p) for p in staged if p.listed]
    owner = (designer_photos or {}).get(str(media["id"]))
    if owner:
        out.append(Placement(DESIGNER, owner))
    if not out:
        out += _filed_on(sources, media)
    if not out and _craft_only(media, sources):
        out.append(Placement(CRAFT))
    unique: list[Placement] = []
    for placement in out:
        if placement not in unique:
            unique.append(placement)
    return unique


def never_listed(
    sources: Sources, media: dict[str, Any], staged: list[StagePlacement], never_media: set[str]
) -> bool:
    media_id = str(media["id"])
    meta = plain_json(media.get("extraMetadata"))
    return (
        any(not p.listed for p in staged)
        or (isinstance(meta, dict) and bool(meta.get(IDENTITY_METADATA_KEY)))
        or media_id in never_media
        or media_id in sources.evidence
        or scrub.identity_image(media.get("caption"), media.get("originalFilename"), *(p.caption for p in staged))
    )


def artisan_linked(media: dict[str, Any], staged: list[StagePlacement]) -> bool:
    return (
        bool(media.get("artisanId") or media.get("questionnaireInterviewId"))
        or str(media.get("linkedRecordType") or "").lower() in ARTISAN_LINKS
        or any(p.entity_key in ARTISAN_ENTITIES for p in staged)
    )


def placement_artisans(sources: Sources, placement: Placement) -> list[str | None]:
    if placement.kind == ARTISAN:
        return [placement.record_id]
    if placement.kind == SESSION:
        return list(sources.session_artisans.get(placement.record_id or "", []))
    if placement.kind in (PRODUCT, TOOL, PROCESS, PROCESS_STEP):
        return items.record_artisans(sources, placement.kind, placement.record_id)
    if placement.kind == DESIGN_STAGE and placement.stage:
        return items.stage_artisans(sources, placement.stage)
    return []


def placement_crafts(sources: Sources, placement: Placement) -> list[str | None]:
    if placement.kind == PRODUCT:
        return items.product_crafts(sources, placement.record_id)
    if placement.kind == TOOL:
        return items.tool_crafts(sources, placement.record_id)
    if placement.kind == PROCESS:
        return items.product_crafts(sources, items.process_product(sources, placement.record_id))
    if placement.kind == PROCESS_STEP:
        process = items.step_process(sources, placement.record_id)
        return items.product_crafts(sources, items.process_product(sources, process))
    if placement.kind == DESIGN_STAGE and placement.stage:
        found: list[str | None] = []
        for product_id in placement.stage.product_ids:
            found += items.product_crafts(sources, product_id)
        for tool_id in placement.stage.tool_ids:
            found += items.tool_crafts(sources, tool_id)
        design = sources.design_workshops.get(placement.stage.design_workshop_id) or {}
        return [*found, design.get("craftId")]
    return []


def placement_workshops(sources: Sources, placement: Placement) -> list[str | None]:
    table = {PRODUCT: sources.products, TOOL: sources.tools, PROCESS: sources.processes}.get(placement.kind)
    if table is not None:
        return [(table.get(placement.record_id or "") or {}).get("workshopId")]
    if placement.kind == PROCESS_STEP:
        process = sources.processes.get(items.step_process(sources, placement.record_id) or "") or {}
        return [process.get("workshopId")]
    if placement.kind == SESSION:
        return [(sources.sessions.get(placement.record_id or "") or {}).get("workshopId")]
    if placement.kind == WORKSHOP:
        return [placement.record_id]
    return []


def _filed_belongs(
    sources: Sources, directory: people.Directory, placement: Placement, labels: Labels, path: str
) -> dict[str, Any]:
    record_id = placement.record_id or ""
    if placement.kind == ARTISAN:
        survivor = directory.survivor(record_id)
        facts = directory.artisans.get(survivor)
        return {"kind": ARTISAN, "id": survivor,
                "name": labels.screen(f"{path}.name", facts.name if facts else None),
                "localName": labels.screen(f"{path}.localName", facts.local_name if facts else None)}
    if placement.kind == DESIGNER:
        user = directory.users.get(record_id)
        return {"kind": DESIGNER, "id": record_id, "name": labels.screen(f"{path}.name", user.name if user else None)}
    table = {SESSION: sources.sessions, WORKSHOP: sources.workshops, DESIGN_WORKSHOP: sources.design_workshops}
    row = table[placement.kind].get(record_id) or {}
    return {"kind": placement.kind, "id": record_id, "name": labels.screen(f"{path}.name", row.get("title"))}


def _belongs(
    sources: Sources, directory: people.Directory, placement: Placement, labels: Labels, index: int
) -> dict[str, Any]:
    path = f"belongsTo[{index}]"
    if placement.kind in (ARTISAN, DESIGNER, SESSION, WORKSHOP, DESIGN_WORKSHOP):
        return _filed_belongs(sources, directory, placement, labels, path)
    if placement.kind == PRODUCT:
        row = sources.products[placement.record_id or ""]
        return {"kind": PRODUCT, "id": row["id"], "name": labels.screen(f"{path}.name", row.get("productName")),
                "localName": labels.screen(f"{path}.localName", row.get("localName")),
                "productType": row.get("productType")}
    if placement.kind == TOOL:
        row = sources.tools[placement.record_id or ""]
        return {"kind": TOOL, "id": row["id"], "name": labels.screen(f"{path}.name", row.get("toolkitName")),
                "localName": labels.screen(f"{path}.localName", row.get("localName")),
                "englishName": labels.screen(f"{path}.englishName", row.get("englishName"))}
    if placement.kind == PROCESS:
        row = sources.processes[placement.record_id or ""]
        return {"kind": PROCESS, "id": row["id"], "name": labels.screen(f"{path}.name", row.get("name")),
                "productId": row.get("productId")}
    if placement.kind == PROCESS_STEP:
        row = sources.steps[placement.record_id or ""]
        process = sources.processes.get(str(row.get("processId") or "")) or {}
        return {"kind": PROCESS_STEP, "id": row["id"], "processId": row.get("processId"),
                "process": labels.screen(f"{path}.process", process.get("name")),
                "name": labels.screen(f"{path}.name", row.get("name")), "order": row.get("sortOrder")}
    if placement.kind == DESIGN_STAGE and placement.stage:
        stage = placement.stage
        return {"kind": DESIGN_STAGE, "stage": {"key": stage.stage_key, "title": stage.stage_title},
                "entity": {"key": stage.entity_key, "label": stage.entity_title},
                "row": {"id": stage.row_id, "label": labels.screen(f"{path}.row.label", stage.row_label)},
                "field": {"key": stage.field_key, "label": stage.field_label},
                "prototypeId": stage.prototype_id}
    return {"kind": CRAFT}


@dataclass
class ImageBatch:
    sources: Sources
    directory: people.Directory
    placements: dict[str, list[StagePlacement]]
    designers: dict[str, list[str]]
    never_media: set[str]
    refused: set[str]
    rights_holder: str
    designer_photos: dict[str, str] = field(default_factory=dict)


@dataclass
class Group:
    primary: str
    members: list[str]
    placements: list[Placement]
    staged: list[StagePlacement]


def _group(batch: ImageBatch, ids: list[str]) -> Group:
    found: list[Placement] = []
    staged: list[StagePlacement] = []
    for media_id in ids:
        media = batch.sources.media.get(media_id)
        if media is None:
            continue
        mine = batch.placements.get(media_id, [])
        staged += mine
        for placement in own_placements(batch.sources, media, mine, batch.designer_photos):
            if placement not in found:
                found.append(placement)
    return Group(ids[0], ids, found, staged)


def _design_ids(batch: ImageBatch, group: Group) -> list[str]:
    ids = [batch.sources.media_design_workshops.get(m) for m in group.members]
    ids += [p.stage.design_workshop_id for p in group.placements if p.stage]
    ids += [p.record_id for p in group.placements if p.kind == DESIGN_WORKSHOP]
    return [i for i in dict.fromkeys(ids) if i]


def _artisans(batch: ImageBatch, group: Group) -> list[str]:
    found: list[str | None] = [(batch.sources.media.get(m) or {}).get("artisanId") for m in group.members]
    for placement in group.placements:
        found += placement_artisans(batch.sources, placement)
    return [a for a in dict.fromkeys(found) if a]


def _designers(batch: ImageBatch, group: Group) -> list[str]:
    out: list[str] = []
    for placement in group.placements:
        found: list[str] = []
        if placement.stage and placement.stage.credits_designers:
            found = batch.designers.get(placement.stage.design_workshop_id, [])
        elif placement.kind == DESIGNER and placement.record_id:
            found = [placement.record_id]
        for user_id in found:
            if user_id not in out and user_id in batch.directory.users:
                out.append(user_id)
    return out


def _workshop_ids(batch: ImageBatch, group: Group) -> list[str | None]:
    ids: list[str | None] = [(batch.sources.media.get(m) or {}).get("workshopId") for m in group.members]
    for placement in group.placements:
        ids += placement_workshops(batch.sources, placement)
    return ids


def subjects_of(batch: ImageBatch, group: Group) -> Subjects:
    artisans = _artisans(batch, group)
    return Subjects(
        media=set(group.members),
        artisans=set(artisans) | {batch.directory.survivor(a) for a in artisans},
        workshops={w for w in _workshop_ids(batch, group) if w} | set(_design_ids(batch, group)),
        users=set(_designers(batch, group)),
    )


def _caption(media: dict[str, Any], group: Group, labels: Labels) -> str | None:
    if (media.get("caption") or "").strip():
        return labels.screen("caption", media.get("caption"))
    captions = [p.stage.caption for p in group.placements if p.stage and (p.stage.caption or "").strip()]
    return labels.screen("caption", captions[0]) if len(captions) == 1 else None


def _crafts(batch: ImageBatch, group: Group, artisans: list[str]) -> list[str]:
    sources = batch.sources
    records: list[str | None] = []
    for placement in group.placements:
        records += placement_crafts(sources, placement)
    context = [sources.workshop_crafts.get(str(w), []) for w in _workshop_ids(batch, group) if w]
    context += [[(sources.design_workshops.get(d) or {}).get("craftId")] for d in _design_ids(batch, group)]
    craft_ids = [(sources.media.get(m) or {}).get("craftId") for m in group.members]
    craft_ids += [_linked(sources.media.get(m) or {}, "craft") for m in group.members]
    artisan_crafts = [batch.directory.artisans[a].craft_id for a in artisans if a in batch.directory.artisans]
    return items.crafts_for(sources, own=craft_ids, records=records, context=context, artisans=artisan_crafts)


def _screen_check(batch: ImageBatch, group: Group) -> Outcome | None:
    media = batch.sources.media[group.primary]
    if media.get("removedAt") or str(media.get("status")) == "REJECTED":
        return items.gone(group.primary, contract.DELETED, items.WITHHELD_REMOVED)
    if str(media.get("status")) == "DRAFT" or str(media.get("mediaType")) != "IMAGE":
        return items.gone(group.primary, contract.UNLISTED, items.WITHHELD_REMOVED)
    if any(
        never_listed(batch.sources, batch.sources.media[m], batch.placements.get(m, []), batch.never_media)
        for m in group.members
        if m in batch.sources.media
    ):
        return items.gone(group.primary, contract.UNLISTED, items.WITHHELD_IDENTITY)
    if not group.placements:
        return items.gone(group.primary, contract.UNLISTED, items.WITHHELD_NO_PLACEMENT)
    if group.primary in batch.refused:
        return items.gone(group.primary, contract.WITHDRAWN, items.WITHHELD_REFUSED)
    if items.missing_artisans(batch.directory, _artisans(batch, group)):
        return items.gone(group.primary, contract.DELETED, items.WITHHELD_ARTISAN_GONE)
    return None


def build(batch: ImageBatch, group: Group) -> tuple[Outcome, dict[str, Any] | None]:
    refusal = _screen_check(batch, group)
    if refusal is not None:
        return refusal, None
    sources, directory = batch.sources, batch.directory
    media = sources.media[group.primary]
    survivors = list(dict.fromkeys(directory.survivor(a) for a in _artisans(batch, group)))
    crafts = _crafts(batch, group, survivors)
    if not crafts:
        return items.gone(group.primary, contract.UNLISTED, items.WITHHELD_NO_CRAFT), None
    designers = _designers(batch, group)
    labels = Labels(items.held_for(directory, artisans=survivors, users=designers))
    caption = _caption(media, group, labels)
    if scrub.identity_image(caption):
        return items.gone(group.primary, contract.UNLISTED, items.WITHHELD_IDENTITY), None
    workshop = items.workshop_for(sources, design_ids=_design_ids(batch, group),
                                  workshop_ids=_workshop_ids(batch, group), labels=labels)
    on = ist_day(media.get("recordedAt") or media.get("createdAt"))
    persons = [
        people.artisan_person(
            directory, a, on=on, labels=labels, path=f"people[{index}]",
            craft=items.artisan_craft(sources, directory, a),
        )
        for index, a in enumerate(survivors)
    ]
    persons += [
        people.user_person(directory, u, kind=people.DESIGNER, labels=labels, path=f"people[{len(survivors) + index}]")
        for index, u in enumerate(designers)
    ]
    payload = {
        "id": group.primary,
        "takenAt": iso_moment(media.get("recordedAt"), media.get("recordedTimezone")),
        "caption": caption,
        "artisanLinked": any(
            artisan_linked(sources.media[m], batch.placements.get(m, [])) for m in group.members if m in sources.media
        ),
        "crafts": [sources.craft_object(c) for c in crafts],
        "belongsTo": [_belongs(sources, directory, p, labels, i) for i, p in enumerate(group.placements)],
        "workshop": workshop,
        "people": persons,
        "credits": [{"personId": a, "as": people.ARTISAN} for a in survivors]
        + [{"personId": u, "as": people.DESIGNER} for u in designers],
        "permission": {
            "statement": contract.PERMISSION_STATEMENT,
            "rightsHolder": batch.rights_holder,
            "licence": None,
        },
    }
    payload["scrubbing"] = items.scrubbing_block(labels.counts, labels.nulled)
    return Outcome(item_id=group.primary, payload=payload), media


async def _with_copy(copier: Copier, outcome: Outcome, media: dict[str, Any], ledger_row: dict[str, Any] | None) -> Outcome:
    source = copy_of(media)
    stored = (ledger_row or {}).get("payload") or {}
    if ledger_row and ledger_row.get("copyOf") == source and ledger_row.get("copyKey") and stored.get("image"):
        copier.remember(source, str(ledger_row["copyKey"]), dict(stored["image"]))
    made = await copier.copy(media)
    if made == DEFERRED:
        return Outcome(item_id=outcome.item_id, withheld=DEFERRED)
    if made == UNDECODABLE:
        return items.gone(outcome.item_id, contract.UNLISTED, UNDECODABLE)
    key, meta = made
    payload = dict(outcome.payload or {})
    payload["image"] = dict(meta)
    return Outcome(item_id=outcome.item_id, payload=items.assert_payload(payload), copy_key=key, copy_of=source)


async def evaluate(
    client: Any, adapter: Any, id_groups: list[list[str]], *, crafts: dict[str, dict[str, Any]],
    placements: dict[str, list[StagePlacement]], ledger_rows: dict[str, dict[str, Any]], copier: Copier,
    rights_holder: str,
) -> list[Outcome]:
    media_ids = [m for g in id_groups for m in g]
    stage = [p for m in media_ids for p in placements.get(m, [])]
    sources = await load_sources(
        client, adapter, media_ids, crafts=crafts,
        extra_products=[x for p in stage for x in p.product_ids],
        extra_tools=[x for p in stage for x in p.tool_ids],
        extra_design_workshops=[p.design_workshop_id for p in stage],
    )
    designers = await adapter.designers(client, sorted(sources.design_workshops))
    photos = await adapter.designer_photos(client)
    shell = ImageBatch(sources, people.Directory(adapter.PORTAL), placements, designers,
                       await adapter.never_media(client), set(), rights_holder, photos)
    grouped = [_group(shell, [m for m in g if m in sources.media] or g) for g in id_groups]
    artisan_ids = {a for g in grouped for a in _artisans(shell, g)}
    user_ids = {u for ids in designers.values() for u in ids} | {photos[m] for m in media_ids if m in photos}
    shell.directory = await people.load_directory(client, adapter, artisan_ids=artisan_ids, user_ids=user_ids)
    subjects = {g.primary: subjects_of(shell, g) for g in grouped if g.primary in sources.media}
    shell.refused = await items.refused_items(client, adapter, subjects)
    out: list[Outcome] = []
    for group in grouped:
        if group.primary not in sources.media:
            out.append(items.gone(group.primary, contract.DELETED, items.WITHHELD_REMOVED))
            continue
        outcome, media = build(shell, group)
        if media is not None:
            outcome = await _with_copy(copier, outcome, media, ledger_rows.get(group.primary))
        if outcome.live:
            outcome.subjects = subjects[group.primary].stored()
        out.append(outcome)
        out += [_member(outcome, group.primary, m) for m in group.members[1:]]
    return out


def _member(outcome: Outcome, primary: str, member: str) -> Outcome:
    if outcome.live:
        return Outcome(item_id=member, reason=contract.MERGED, successor=primary, withheld="merged")
    if outcome.withheld == DEFERRED:
        return Outcome(item_id=member, withheld=DEFERRED)
    return items.gone(member, outcome.reason or contract.UNLISTED, outcome.withheld or "merged", outcome.successor)
