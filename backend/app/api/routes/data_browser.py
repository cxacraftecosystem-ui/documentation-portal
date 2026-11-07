import asyncio
import contextlib
import ctypes
import functools
import json
import sys
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse, Response, StreamingResponse

from app.core.db import db
from app.core.deps import require_dataset_downloader
from app.questionnaire.live import live_media_where, live_session_where
from app.services import (
    answer_drafts,
    naming,
    transcript_export,
    transcript_pipeline,
    transcript_portal,
)
from app.services.concurrency import gather_reads
from app.services.data_explorer import (
    counts as explorer_counts,
)
from app.services.data_explorer import (
    report_cache,
    report_options,
    report_tools,
    slim,
)
from app.services.data_explorer import (
    tree as explorer_tree,
)
from app.services.data_explorer import (
    walk as explorer_walk,
)
from app.services.media_naming import (
    RESERVED_NAMES as _RESERVED_NAMES,
)
from app.services.media_naming import (
    clip as _clip,
)
from app.services.media_naming import (
    folder_order,
    interview_record,
    transcript_filename,
    unique_display_filename,
    unique_display_stem,
    unique_name,
)
from app.services.media_naming import (
    safe_chars as _safe_chars,
)
from app.services.record_fields import (
    MEDIA_COLOR,
    MEDIA_COLUMNS,
    OVERVIEW_COLOR,
    PROVENANCE_COLUMNS,
    SPECS,
    TRANSCRIPT_COLOR,
    artisan_names,
    info_panel,
    media_download_link,
    media_row,
    provenance_row,
    sheet_columns,
    sheet_row,
)
from app.services.record_fields import (
    cell as _cell,
)
from app.services.record_fields import (
    date_str as _date,
)
from app.services.record_fields import (
    enum_label as _enum_label,
)
from app.services.record_fields import (
    ev as _ev,
)
from app.services.record_fields import (
    human_size as _human_size,
)
from app.services.record_fields import (
    info_text as _info_text,
)
from app.services.record_fields import (
    interview_label as _interview_label,
)
from app.services.record_filters import tools_in_workshop_clause
from app.services.records import owned_or_granted_where
from app.services.tool_scope import (
    TOOL_SCOPE_INCLUDE,
    hide_unseen,
    tool_artisan_ids,
    with_tool_scope,
)
from app.services.transcript_format import transcript_cell
from app.services.xlsx_report import XLSX_MIME

router = APIRouter(
    prefix="/data",
    tags=["data-browser"],
    dependencies=[Depends(require_dataset_downloader)],
)

TAKE = 500
MAX_MANIFEST_FILES = 20000
MAX_WALK_DEPTH = 16
_WALK_SEM = asyncio.Semaphore(5)
REPORT_TAKE = 5000


async def _every(find: Any, *, order: Any = None, **kwargs: Any) -> list[Any]:
    orders = list(order) if isinstance(order, list) else ([order] if order else [])
    if not any("id" in o for o in orders):
        orders.append({"id": "asc"})
    rows: list[Any] = []
    after: str | None = None
    while True:
        window = {"cursor": {"id": after}, "skip": 1} if after else {}
        batch = list(await find(**kwargs, order=orders, take=TAKE, **window))
        rows.extend(batch)
        last = str(batch[-1].id) if batch else None
        if len(batch) < TAKE or last is None or last == after:
            return rows
        after = last
PREVIEW_ROWS = 200
REPORT_QUEUE = report_cache.QUEUE
REPORT_CACHE = report_cache.CACHE
REPORT_BUSY = report_cache.REPORT_BUSY
REPORT_RETRY_AFTER_SECONDS = report_cache.RETRY_AFTER_SECONDS
NO_CRAFT = "_none"


_MAX_SEG_CHARS = 80
_MAX_SEG_BYTES = 200

_TYPE_TOKEN = {
    "IMAGE": "images",
    "VIDEO": "videos",
    "AUDIO": "audios",
    "PDF": "documents",
    "DOCUMENT": "documents",
    "OTHER": "other",
}

_TYPED_TAGS = [
    "artisan",
    "product",
    "process",
    "processstep",
    "tool",
    "workshop",
    "questionnaire",
    "questionnaireinterview",
]

_USER_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "artisans": {"linkedRecordType": "artisan"},
    "products": {"linkedRecordType": {"in": ["product", "process", "processstep"]}},
    "tools": {"linkedRecordType": "tool"},
    "workshops": {"linkedRecordType": "workshop"},
    "questionnaire": {"linkedRecordType": {"in": ["questionnaire", "questionnaireinterview"]}},
    "misc": {
        "OR": [
            {"linkedRecordType": None},
            {"linkedRecordType": {"not_in": _TYPED_TAGS}},
        ]
    },
}

_MEDIA_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "images": {"mediaType": "IMAGE"},
    "videos": {"mediaType": "VIDEO"},
    "audios": {"mediaType": "AUDIO"},
    "documents": {"mediaType": {"in": ["PDF", "DOCUMENT"]}},
    "other": {"mediaType": "OTHER"},
}

TAXONOMIES: list[dict[str, Any]] = [
    {
        "id": "by-workshop",
        "name": "By workshop",
        "path": "by-workshop",
        "description": (
            "Workshop, then craft, then artisan, then that artisan's products, tools and "
            "questionnaires. Products open into their processes. A workshop's loose media "
            "sits in Miscellaneous, one level under the workshop."
        ),
        "default": True,
    },
    {
        "id": "by-uploader",
        "name": "By uploader",
        "path": "by-uploader",
        "description": (
            "Workshop, then the researcher who uploaded, then everything they recorded — "
            "their entries with the fields they filled in, and their media by type."
        ),
        "default": False,
    },
    {
        "id": "by-type",
        "name": "By media type",
        "path": "by-type",
        "description": (
            "Every file grouped purely by what it is: audios, videos, images, transcripts, "
            "documents."
        ),
        "default": False,
    },
]

_CATEGORY_LABEL: dict[str, str] = {
    "by-workshop": "By workshop",
    "by-uploader": "By uploader",
    "by-type": "By media type",
    "transcripts": "Transcripts",
    "media": "Media",
    "workshops": "Workshops",
    "users": "Users",
    "media-types": "Media types",
    "crafts": "Crafts",
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "processes": "Processes",
    "questionnaire": "Questionnaire",
    "misc": "Miscellaneous",
    "_misc": "Miscellaneous",
    "images": "Images",
    "videos": "Videos",
    "audios": "Audios",
    "documents": "Documents",
    "other": "Other",
}



def _seg(value: str | None, fallback: str) -> str:
    cleaned = _safe_chars((value or "").strip()).strip(" .")
    cleaned = _clip(cleaned, _MAX_SEG_CHARS, _MAX_SEG_BYTES).strip(" .")

    if not cleaned:
        return fallback
    if cleaned.split(".")[0].upper() in _RESERVED_NAMES:
        return f"{cleaned}_"
    return cleaned


def _content_disposition(name: str) -> str:
    return naming.content_disposition(name)


def _join(parent: str, name: str) -> str:
    return f"{parent}/{name}" if parent else name


def _norm(path: str) -> str:
    return "/".join(s for s in (path or "").split("/") if s)


_LEGACY_TAXONOMY = {"workshops": "by-workshop", "users": "by-uploader", "media-types": "by-type"}


def _taxonomy_of(norm: str) -> str | None:
    head = norm.split("/", 1)[0] if norm else ""
    if not head:
        return None
    if head in _LEGACY_TAXONOMY:
        return _LEGACY_TAXONOMY[head]
    return head if any(t["id"] == head for t in TAXONOMIES) else None



@dataclass(frozen=True)
class Scope:
    records: dict[str, Any]
    media: dict[str, Any]
    visible: dict[str, Any] | None = None

    @property
    def readable(self) -> dict[str, Any]:
        return self.records if self.visible is None else self.visible

    @property
    def restricted(self) -> bool:
        return bool(self.records or self.media)


DATA_STATUSES = ("APPROVED", "PENDING", "NEEDS_REVISION", "REJECTED")


def data_status(value: str | None) -> str | None:
    if value is None or not value.strip() or value.strip().upper() == "ALL":
        return None
    wanted = value.strip().upper()
    if wanted not in DATA_STATUSES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"status must be one of {', '.join(DATA_STATUSES)} or ALL",
        )
    return wanted


async def _scope_for(user: Any, status_filter: str | None = None) -> Scope:
    visible = await owned_or_granted_where(user)
    records = visible
    media = await owned_or_granted_where(user, owner_field="uploadedById")
    if status_filter:
        records = _and(records, {"status": status_filter})
        media = _and(media, {"status": status_filter})
    return Scope(records=records, media=media, visible=visible)


def _and(where: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
    if not extra:
        return where
    if not where:
        return dict(extra)
    return {"AND": [where, extra]}


async def _visible_only(delegate: Any, records: list[Any], scope_where: dict[str, Any]) -> list[Any]:
    if not scope_where or not records:
        return records
    rows = await delegate.find_many(
        where={"AND": [{"id": {"in": [r.id for r in records]}}, scope_where]}
    )
    allowed = {r.id for r in rows}
    return [r for r in records if r.id in allowed]


def _uniq(name: str, used: set[str]) -> str:
    key = name.lower()
    if key not in used:
        used.add(key)
        return name
    head, slash, leaf = name.rpartition("/")
    stem, dot, ext = leaf.rpartition(".")
    n = 2
    while True:
        if dot:
            candidate = f"{head}{slash}{stem} ({n}).{ext}"
        else:
            candidate = f"{name} ({n})"
        if candidate.lower() not in used:
            used.add(candidate.lower())
            return candidate
        n += 1


_COMPOUND_EXTENSIONS = (".transcript.md",)


def _split_leaf(name: str) -> tuple[str, str]:
    for compound in _COMPOUND_EXTENSIONS:
        if name.lower().endswith(compound):
            return name[: -len(compound)], name[-len(compound) :]
    stem, dot, ext = name.rpartition(".")
    return (stem, f".{ext}") if dot and stem else (name, "")


def _folder(name: str, path: str, record_type: str = "category") -> dict[str, Any]:
    return {"name": name, "path": path, "kind": "folder", "recordType": record_type}


def _text(parent: str, name: str, content: str | None) -> dict[str, Any] | None:
    if not (content or "").strip():
        return None
    return {"name": name, "path": _join(parent, name), "kind": "file", "content": content}


def _media_entries(
    parent: str,
    media: list[Any],
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
) -> list[dict[str, Any]]:
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        name = unique_display_filename(
            m,
            used,
            record_type=record_type,
            record_name=record_name,
            step_number=step_number,
            step_name=step_name,
            position=position,
            fallback=m.id,
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "mediaType": str(_ev(m.mediaType)),
                "mediaId": m.id,
                "url": f"/api/data/media/{m.id}/download",
                "sizeBytes": int(m.sizeBytes) if m.sizeBytes is not None else None,
                "transcriptAvailable": slim.has_transcript(m),
                "_transcriptText": getattr(m, "transcriptText", None),
                "_transcriptName": transcript_filename(
                    m,
                    record_type=record_type,
                    record_name=record_name,
                    step_number=step_number,
                    position=position,
                ),
                "_media": m,
                "_nameKw": {
                    "record_type": record_type,
                    "record_name": record_name,
                    "step_number": step_number,
                    "position": position,
                },
            }
        )
    return entries


_NAMING_INCLUDE: dict[str, Any] = {
    "artisan": True,
    "craft": True,
    "workshop": True,
    "product": True,
    "tool": True,
    "questionnaireInterview": {"include": {"artisans": {"include": {"artisan": True}}}},
}


def _memo_key(*parts: Any) -> str:
    return json.dumps(parts, sort_keys=True, default=str)


async def _media(where: dict[str, Any], scope: Scope, *, named: bool = False) -> list[Any]:
    kwargs: dict[str, Any] = {}
    if named:
        kwargs["include"] = _NAMING_INCLUDE

    async def load() -> list[Any]:
        return await _every(
            slim.find_media,
            where=slim.live(_and(where, scope.media)), order={"createdAt": "asc"}, **kwargs
        )

    return await explorer_walk.memo(_memo_key("media", where, scope.media, named), load)


async def _workshop_misc_media(wid: str, scope: Scope, *, named: bool = False) -> list[Any]:
    return await _media(
        {
            "AND": [
                {"artisanId": None},
                {"productId": None},
                {"toolId": None},
                {"questionnaireInterviewId": None},
                _record_media_where("workshopId", wid, ["workshop"]),
            ]
        },
        scope,
        named=named,
    )


async def _artisan_own_media(aid: str, scope: Scope) -> list[Any]:
    return await _media(
        {
            "OR": [
                {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
            ]
        },
        scope,
    )


async def _artisan_display_name(aid: str) -> str | None:
    artisan = await db.artisan.find_unique(where={"id": aid})
    return (getattr(artisan, "name", None) or "").strip() or None


def _record_media_where(fk_field: str, rec_id: str, tags: list[str]) -> dict[str, Any]:
    return {
        "OR": [
            {fk_field: rec_id},
            {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": rec_id}]},
        ]
    }



def _workshop_info(ws: Any) -> dict[str, Any]:
    return info_panel("workshop", ws)


def _craft_info(c: Any) -> dict[str, Any]:
    return info_panel("craft", c)


def _artisan_info(a: Any) -> dict[str, Any]:
    return info_panel("artisan", a)


def _product_info(p: Any) -> dict[str, Any]:
    return info_panel("product", p)


def _tool_info(t: Any) -> dict[str, Any]:
    return info_panel("tool", t)


def _process_info(pr: Any) -> dict[str, Any]:
    return info_panel("process", pr)


def _artisan_names(interview: Any) -> list[str]:
    return artisan_names(interview)


def _interview_info(interview: Any) -> dict[str, Any]:
    return info_panel("interview", interview)


def _interview_answers(interview: Any, info: dict[str, Any]) -> str:
    header = _info_text(info)
    responses = sorted(
        interview.responses or [],
        key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
    )
    answers = []
    for r in responses:
        q = getattr(r, "question", None)
        prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
        code = getattr(q, "sectionCode", "") if q else ""
        text = answer_drafts.marked_answer(r.answerText, getattr(r, "answerOrigin", None))
        answers.append(f"[{code}] {prompt}\n  -> {text}\n")
    return header + "\n\n" + "".join(answers)


async def _require(
    delegate: Any,
    rec_id: str,
    what: str,
    include: dict[str, Any] | None = None,
    scope_where: dict[str, Any] | None = None,
) -> Any:
    kwargs: dict[str, Any] = {"where": {"id": rec_id}}
    if include:
        kwargs["include"] = include

    async def load() -> Any:
        if scope_where:
            kwargs["where"] = {"AND": [{"id": rec_id}, scope_where]}
            return await delegate.find_first(**kwargs)
        return await delegate.find_unique(**kwargs)

    record = await explorer_walk.memo(
        _memo_key("require", id(delegate), rec_id, include, scope_where), load
    )
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"{what} not found")
    return record



async def _artisan_crumb_names(tail: list[str]) -> list[str]:
    names: list[str] = []
    if not tail:
        return names
    artisan = await db.artisan.find_unique(where={"id": tail[0]})
    names.append(_seg(getattr(artisan, "name", None), tail[0]))
    if len(tail) >= 2 and tail[1] in ("products", "tools", "questionnaire", "misc"):
        sub = tail[1]
        names.append(_CATEGORY_LABEL[sub])
        if len(tail) >= 3:
            rid = tail[2]
            if sub == "products":
                p = await db.productdocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(p, "productName", None), rid))
                if len(tail) >= 4 and tail[3] == "processes":
                    names.append("Processes")
                    if len(tail) >= 5:
                        pr = await db.process.find_unique(where={"id": tail[4]})
                        names.append(_seg(getattr(pr, "name", None), tail[4]))
                    if len(tail) >= 6:
                        st = await db.processstep.find_unique(where={"id": tail[5]})
                        names.append(_seg(getattr(st, "name", None), tail[5]))
            elif sub == "tools":
                t = await db.tooldocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(t, "toolkitName", None), rid))
            elif sub == "questionnaire":
                i = await db.questionnaireinterview.find_unique(
                    where={"id": rid},
                    include={"artisans": {"include": {"artisan": True}}},
                )
                names.append(_seg(_interview_label(i), rid) if i else rid)
    return names


async def _crumb_names(segs: list[str]) -> list[str]:
    names: list[str] = []
    if not segs:
        return names
    head = segs[0]

    if head == "by-uploader":
        names.append(_CATEGORY_LABEL["by-uploader"])
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            u = await db.user.find_unique(where={"id": segs[2]})
            names.append(_seg(getattr(u, "name", None), segs[2]))
        if len(segs) >= 4:
            names.append(_UPLOADER_BRANCHES.get(segs[3], _CATEGORY_LABEL.get(segs[3], segs[3])))
        if len(segs) >= 5:
            names.append(_CATEGORY_LABEL.get(segs[4], segs[4]))
    elif head == "by-type":
        names.append(_CATEGORY_LABEL["by-type"])
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))
    elif head in ("workshops", "by-workshop"):
        names.append(_CATEGORY_LABEL["by-workshop"] if head == "by-workshop" else "Workshops")
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            if segs[2] == "_misc":
                names.append(_CATEGORY_LABEL["_misc"])
            elif segs[2] == "crafts":
                names.append(_CATEGORY_LABEL["crafts"])
                if len(segs) >= 4:
                    if segs[3] == NO_CRAFT:
                        names.append("No craft")
                    else:
                        craft = await db.craft.find_unique(where={"id": segs[3]})
                        names.append(_seg(getattr(craft, "name", None), segs[3]))
                if len(segs) >= 5 and segs[4] == "artisans":
                    names.append(_CATEGORY_LABEL["artisans"])
                    names.extend(await _artisan_crumb_names(segs[5:]))
            elif segs[2] == "artisans":
                names.append(_CATEGORY_LABEL["artisans"])
                names.extend(await _artisan_crumb_names(segs[3:]))
    elif head == "users":
        names.append("Users")
        if len(segs) >= 2:
            u = await db.user.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(u, "name", None), segs[1]))
        if len(segs) >= 3:
            names.append(_CATEGORY_LABEL.get(segs[2], segs[2]))
    elif head == "media-types":
        names.append("Media types")
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))

    while len(names) < len(segs):
        names.append(segs[len(names)])
    return names


async def _resolve_crumbs(norm: str) -> list[dict[str, str]]:
    segs = [s for s in norm.split("/") if s]
    crumbs = [{"name": "Repository", "path": ""}]
    names = await _crumb_names(segs)
    path = ""
    for seg, name in zip(segs, names):
        path = _join(path, seg)
        crumbs.append({"name": name, "path": path})
    return crumbs


Level = tuple[list[dict[str, Any]], dict[str, Any] | None]


async def _list_level(path: str, scope: Scope) -> Level:
    segs = [s for s in path.split("/") if s]
    parent = "/".join(segs)

    if not segs:
        return [
            _folder(t["name"], t["path"], "taxonomy") for t in TAXONOMIES
        ], None

    head = segs[0]

    if head in ("by-workshop", "workshops"):
        return await _list_workshops_level(segs, parent, scope)
    if head == "by-uploader":
        return await _list_uploader_level(segs, parent, scope)
    if head in ("by-type", "media-types"):
        return await _list_media_types_level(segs, parent, scope)
    if head == "users":
        return await _list_users_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _filed_tool_artisan_ids(wid: str, reached: list[str], scope: Scope) -> list[str]:
    filed: list[dict[str, Any]] = [{"workshopId": wid}]
    if reached:
        filed.extend(
            [
                {"OR": [{"artisanId": None}, {"artisanId": {"not_in": reached}}]},
                {"artisanLinks": {"none": {"artisanId": {"in": reached}}}},
            ]
        )
    tools = await _every(
        db.tooldocumentation.find_many,
        where=_and({"AND": filed}, scope.records),
        include={"artisanLinks": True},
    )
    ids: list[str] = []
    for tool in tools:
        for artisan_id in tool_artisan_ids(tool):
            if artisan_id not in ids:
                ids.append(artisan_id)
    return ids


async def _linked_artisans(ws: Any, scope: Scope) -> list[Any]:
    return await explorer_walk.memo(
        _memo_key("linked", ws.id, scope.records), lambda: _load_linked_artisans(ws, scope)
    )


async def _load_linked_artisans(ws: Any, scope: Scope) -> list[Any]:
    reach = workshop_artisan_reach(ws)
    reached = await _every(
        db.artisan.find_many,
        where=_and({"OR": reach}, scope.records),
        include={"craft": True},
        order={"name": "asc"},
    )
    orphans = await _filed_tool_artisan_ids(ws.id, [a.id for a in reached], scope)
    if not orphans:
        return reached
    return await _every(
        db.artisan.find_many,
        where=_and({"OR": [*reach, {"id": {"in": orphans}}]}, scope.records),
        include={"craft": True},
        order={"name": "asc"},
    )


def workshop_artisan_reach(ws: Any) -> list[dict[str, Any]]:
    ors: list[dict[str, Any]] = [{"workshopId": ws.id}]
    linked_ids = [link.artisanId for link in ws.artisans or [] if getattr(link, "artisanId", None)]
    if linked_ids:
        ors.append({"id": {"in": linked_ids}})
    craft_ids = [link.craftId for link in ws.crafts or [] if getattr(link, "craftId", None)]
    if craft_ids:
        ors.append({"craftId": {"in": craft_ids}})
    return ors


def workshop_reaches_artisan(ws: Any, artisan: Any) -> bool:
    for term in workshop_artisan_reach(ws):
        for field, condition in term.items():
            value = getattr(artisan, field, None)
            if value is None:
                break
            if isinstance(condition, dict):
                if value not in condition["in"]:
                    break
            elif value != condition:
                break
        else:
            return True
    return False


def _craft_folder_entries(ws: Any, base: str, artisans: list[Any]) -> list[dict[str, Any]]:
    crafts: dict[str, Any] = {}
    for link in ws.crafts or []:
        craft = getattr(link, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
    no_craft = False
    for artisan in artisans:
        craft = getattr(artisan, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
        elif not artisan.craftId:
            no_craft = True
    used: set[str] = set()
    entries = [
        _folder(_uniq(_seg(c.name, "Craft"), used), f"{base}/{c.id}", "craft")
        for c in crafts.values()
    ]
    if no_craft:
        entries.append(_folder(_uniq("No craft", used), f"{base}/{NO_CRAFT}"))
    return entries


async def _workshop_craft_artisans(wid: str, cid: str, scope: Scope) -> list[Any]:
    ws = await _require(
        db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.readable
    )
    reachable = await _linked_artisans(ws, scope)
    if cid == NO_CRAFT:
        return [a for a in reachable if not a.craftId]
    return [a for a in reachable if a.craftId == cid]


_WS_CRAFTS_INCLUDE = {
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "crafts": {"include": {"craft": True}},
}


async def _list_workshops_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        workshops = await _every(
            db.workshop.find_many,
            where=scope.records, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.readable
        )
        info = _workshop_info(ws)
        entries = _craft_folder_entries(ws, f"{parent}/crafts", await _linked_artisans(ws, scope))
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        entries.extend(
            _media_entries(
                parent,
                await _workshop_misc_media(wid, scope),
                record_type="Workshop",
                record_name=ws.title,
            )
        )
        return entries, info

    if segs[2] == "crafts":
        if len(segs) == 3:
            ws = await _require(
                db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.readable
            )
            return _craft_folder_entries(ws, parent, await _linked_artisans(ws, scope)), None

        cid = segs[3]

        if len(segs) == 4:
            info = None
            if cid != NO_CRAFT:
                craft = await _require(db.craft, cid, "Craft")
                info = _craft_info(craft)
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            entries = [
                _folder(_uniq(_seg(a.name, "Artisan"), used), f"{parent}/artisans/{a.id}", "artisan")
                for a in artisans
            ]
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            if cid != NO_CRAFT:
                entries.extend(
                    _media_entries(
                        parent,
                        await _media(_record_media_where("craftId", cid, ["craft"]), scope),
                        record_type="Craft",
                        record_name=getattr(craft, "name", None),
                    )
                )
            return entries, info

        if len(segs) == 5 and segs[4] == "artisans":
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            return [
                _folder(_uniq(_seg(a.name, "Artisan"), used), f"{parent}/{a.id}", "artisan")
                for a in artisans
            ], None

        if len(segs) >= 6 and segs[4] == "artisans":
            remapped = [segs[0], wid, "artisans", *segs[5:]]
            return await _list_artisan_level(remapped, parent, scope)

        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) == 3 and segs[2] == "_misc":
        return _media_entries(parent, await _workshop_misc_media(wid, scope, named=True)), None

    if len(segs) == 3 and segs[2] == "artisans":
        ws = await _require(
            db.workshop,
            wid,
            "Workshop",
            include={"artisans": {"include": {"artisan": True}}},
            scope_where=scope.readable,
        )
        entries = []
        used = set()
        for artisan in await _linked_artisans(ws, scope):
            name = _uniq(_seg(artisan.name, "Artisan"), used)
            entries.append(_folder(name, f"{parent}/{artisan.id}", "artisan"))
        return entries, None

    if len(segs) >= 4 and segs[2] == "artisans":
        return await _list_artisan_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_artisan_level(segs: list[str], parent: str, scope: Scope) -> Level:
    wid, aid = segs[1], segs[3]

    if len(segs) == 4:
        artisan = await _require(
            db.artisan,
            aid,
            "Artisan",
            include={"craft": True, "location": True},
            scope_where=scope.readable,
        )
        info = _artisan_info(artisan)
        if info is not None:
            info["counts"] = {
                "tools": await db.tooldocumentation.count(where=_and(tools_in_workshop_clause(wid, [aid]), scope.records))
            }
        entries = [
            _folder("Products", f"{parent}/products"),
            _folder("Tools", f"{parent}/tools"),
            _folder("Questionnaire", f"{parent}/questionnaire"),
        ]
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        entries.extend(
            _media_entries(
                parent,
                await _artisan_own_media(aid, scope),
                record_type="Artisan",
                record_name=artisan.name,
            )
        )
        return entries, info

    sub = segs[4]

    if sub == "products":
        return await _list_products_level(segs, parent, wid, aid, scope)

    if sub == "tools":
        if len(segs) == 5:
            tools = await _every(
                db.tooldocumentation.find_many,
                where=_and(tools_in_workshop_clause(wid, [aid]), scope.records),
                order={"createdAt": "asc"},
            )
            used: set[str] = set()
            return [
                _folder(_uniq(_seg(t.toolkitName, "Tool"), used), f"{parent}/{t.id}", "tool")
                for t in tools
            ], None
        if len(segs) == 6:
            tool = await _require(
                db.tooldocumentation,
                segs[5],
                "Tool",
                include=TOOL_SCOPE_INCLUDE,
                scope_where=scope.readable,
            )
            await hide_unseen([tool], scope.readable)
            info = _tool_info(tool)
            entries = []
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            media = await _media(_record_media_where("toolId", tool.id, ["tool"]), scope)
            entries.extend(
                _media_entries(
                    parent, media, record_type="Tool", record_name=tool.toolkitName
                )
            )
            return entries, info

    if sub == "questionnaire":
        if len(segs) == 5:
            interviews = await _every(
                db.questionnaireinterview.find_many,
                where=live_session_where(_and({"artisans": {"some": {"artisanId": aid}}}, scope.records)),
                order={"createdAt": "asc"},
                include={"artisans": {"include": {"artisan": True}}},
            )
            used = set()
            return [
                _folder(
                    _uniq(_seg(_interview_label(i), "Interview"), used),
                    f"{parent}/{i.id}",
                    "interview",
                )
                for i in interviews
            ], None
        if len(segs) == 6:
            interview = await _require(
                db.questionnaireinterview,
                segs[5],
                "Interview",
                include={
                    "responses": {"include": {"question": True}},
                    "artisans": {"include": {"artisan": True}},
                },
                scope_where=live_session_where(scope.readable),
            )
            info = _interview_info(interview)
            entries = []
            answers = _text(parent, "answers.txt", _interview_answers(interview, info))
            if answers:
                entries.append(answers)
            media = await _media(
                _record_media_where(
                    "questionnaireInterviewId",
                    interview.id,
                    ["questionnaire", "questionnaireinterview"],
                ),
                scope,
            )
            interview_type, interview_name = interview_record(interview)
            entries.extend(
                _media_entries(
                    parent, media, record_type=interview_type, record_name=interview_name
                )
            )
            return entries, info

    if sub == "misc" and len(segs) == 5:
        return _media_entries(
            parent,
            await _artisan_own_media(aid, scope),
            record_type="Artisan",
            record_name=await _artisan_display_name(aid),
        ), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_products_level(
    segs: list[str], parent: str, wid: str, aid: str, scope: Scope
) -> Level:
    if len(segs) == 5:
        products = await _every(
            db.productdocumentation.find_many,
            where=_and(
                {
                    "AND": [
                        {"artisanId": aid},
                        {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                    ]
                },
                scope.records,
            ),
            order={"createdAt": "asc"},
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(p.productName, "Product"), used), f"{parent}/{p.id}", "product")
            for p in products
        ], None

    pid = segs[5]

    if len(segs) == 6:
        product = await _require(
            db.productdocumentation, pid, "Product", scope_where=scope.readable
        )
        info = _product_info(product)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(_record_media_where("productId", pid, ["product"]), scope)
        entries.extend(
            _media_entries(
                parent, media, record_type="Product", record_name=product.productName
            )
        )
        if await db.process.count(where=_and({"productId": pid}, scope.records)) > 0:
            entries.append(_folder("Processes", f"{parent}/processes"))
        return entries, info

    if len(segs) == 7 and segs[6] == "processes":
        processes = await _every(
            db.process.find_many,
            where=_and({"productId": pid}, scope.records), order={"createdAt": "asc"}
        )
        used = set()
        return [
            _folder(_uniq(_seg(pr.name, "Process"), used), f"{parent}/{pr.id}", "process")
            for pr in processes
        ], None

    if len(segs) == 8 and segs[6] == "processes":
        process = await _require(
            db.process, segs[7], "Process", include={"steps": True}, scope_where=scope.readable
        )
        info = _process_info(process)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(
            {"AND": [{"linkedRecordType": "process"}, {"linkedRecordId": process.id}]}, scope
        )
        entries.extend(
            _media_entries(parent, media, record_type="Process", record_name=process.name)
        )
        used = set()
        for step in sorted(process.steps or [], key=lambda s: s.sortOrder):
            entries.append(
                _folder(_uniq(_seg(step.name, "Step"), used), f"{parent}/{step.id}", "process")
            )
        return entries, info

    if len(segs) == 9 and segs[6] == "processes":
        step_scope: dict[str, Any] | None = None
        if scope.records:
            await _require(db.process, segs[7], "Process", scope_where=scope.readable)
            step_scope = {"processId": segs[7]}
        step = await _require(
            db.processstep,
            segs[8],
            "Process step",
            include={"process": True},
            scope_where=step_scope,
        )
        entries = []
        notes = _text(parent, "notes.txt", step.notes)
        if notes:
            entries.append(notes)
        media = await _media(
            {"AND": [{"linkedRecordType": "processstep"}, {"linkedRecordId": step.id}]}, scope
        )
        entries.extend(
            _media_entries(
                parent,
                media,
                record_type="Process",
                record_name=getattr(getattr(step, "process", None), "name", None),
                step_number=step.sortOrder,
                step_name=step.name,
            )
        )
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_users_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        uploaders = await _every(
            db.user.find_many,
            where={"media": {"some": _and({}, scope.media)}}, order={"name": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in uploaders
        ], None

    uid = segs[1]

    if len(segs) == 2:
        await _require(db.user, uid, "User")
        return [
            _folder(_CATEGORY_LABEL[slug], f"{parent}/{slug}")
            for slug in ("artisans", "products", "tools", "workshops", "questionnaire", "misc")
        ], None

    if len(segs) == 3 and segs[2] in _USER_TYPE_WHERE:
        media = await _media(
            {"AND": [{"uploadedById": uid}, _USER_TYPE_WHERE[segs[2]]]}, scope, named=True
        )
        return _media_entries(parent, media), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


def _transcript_entries(parent: str, media: list[Any]) -> list[dict[str, Any]]:
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        if not slim.has_transcript(m):
            continue
        name = unique_display_stem(
            m, used, extension=".transcript.md", position=position, fallback=m.id
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "content": getattr(m, "transcriptText", None),
                "mediaId": m.id,
                "_txMedia": m.id,
                "_media": m,
                "_nameKw": {"position": position},
            }
        )
    return entries


async def _list_media_types_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        return [
            _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
            for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
        ], None
    if len(segs) == 2 and segs[1] == "transcripts":
        media = await _media({"NOT": {"transcriptText": None}}, scope, named=True)
        return _transcript_entries(parent, media), None
    if len(segs) == 2 and segs[1] in _MEDIA_TYPE_WHERE:
        media = await _media(_MEDIA_TYPE_WHERE[segs[1]], scope, named=True)
        return _media_entries(parent, media), None
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


_UPLOADER_BRANCHES: dict[str, str] = {
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "questionnaire": "Questionnaire",
    "media": "Media",
}


async def _uploader_records(
    branch: str, wid: str, uid: str, scope: Scope
) -> tuple[str, list[Any]]:
    if branch == "artisans":
        return "artisan", await _every(
            db.artisan.find_many,
            where=_and(
                {"AND": [{"createdById": uid}, {"workshops": {"some": {"workshopId": wid}}}]},
                scope.records,
            ),
            order={"createdAt": "asc"},
            include=_ARTISAN_INCLUDE,
        )
    if branch == "products":
        return "product", await _every(
            db.productdocumentation.find_many,
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            order={"createdAt": "asc"},
            include=_PRODUCT_INCLUDE,
        )
    if branch == "tools":
        return "tool", await _every(
            db.tooldocumentation.find_many,
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            order={"createdAt": "asc"},
            include=_TOOL_INCLUDE,
        )
    if branch == "questionnaire":
        return "interview", await _every(
            db.questionnaireinterview.find_many,
            where=_and(
                {
                    "AND": [
                        {"createdById": uid},
                        {
                            "artisans": {
                                "some": {"artisan": {"workshops": {"some": {"workshopId": wid}}}}
                            }
                        },
                        live_session_where(),
                    ]
                },
                scope.records,
            ),
            order={"createdAt": "asc"},
            include=_INTERVIEW_INCLUDE,
        )
    return "", []


async def _list_uploader_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        workshops = await _every(
            db.workshop.find_many,
            where=scope.records, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.readable
        )
        uploaders = await _every(
            db.user.find_many,
            where={"media": {"some": _and({"workshopId": wid}, scope.media)}},
            order={"name": "asc"},
        )
        authors = await _every(
            db.user.find_many,
            where={
                "OR": [
                    {"products": {"some": _and({"workshopId": wid}, scope.records)}},
                    {"tools": {"some": _and({"workshopId": wid}, scope.records)}},
                ]
            },
            order={"name": "asc"},
        )
        seen: dict[str, Any] = {}
        for u in [*uploaders, *authors]:
            seen.setdefault(u.id, u)
        used: set[str] = set()
        entries = [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in sorted(seen.values(), key=lambda u: (u.name or "").lower())
        ]
        return entries, _workshop_info(ws)

    uid = segs[2]

    if len(segs) == 3:
        user = await _require(db.user, uid, "User")
        entries = [
            _folder(label, _join(parent, slug)) for slug, label in _UPLOADER_BRANCHES.items()
        ]
        return entries, {
            "title": (user.name or "").strip() or "User",
            "fields": [
                f
                for f in [
                    {"label": "Email", "value": _cell(user.email)},
                    {"label": "Role", "value": _cell(_enum_label(user.role))},
                ]
                if f["value"]
            ],
        }

    branch = segs[3]

    if branch == "media":
        if len(segs) == 4:
            return [
                _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
                for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
            ], None
        if len(segs) == 5:
            owned = [{"uploadedById": uid}, {"workshopId": wid}]
            if segs[4] == "transcripts":
                media = await _media(
                    {"AND": [*owned, {"NOT": {"transcriptText": None}}]}, scope, named=True
                )
                return _transcript_entries(parent, media), None
            if segs[4] in _MEDIA_TYPE_WHERE:
                media = await _media(
                    {"AND": [*owned, _MEDIA_TYPE_WHERE[segs[4]]]}, scope, named=True
                )
                return _media_entries(parent, media), None
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if branch in _UPLOADER_BRANCHES and len(segs) == 4:
        kind, records = await _uploader_records(branch, wid, uid, scope)
        if not kind:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        spec = SPECS[kind]
        used: set[str] = set()
        entries: list[dict[str, Any]] = []
        for record in records:
            stem = naming.file_name(
                entity=None if spec.label == "Craft" else spec.label,
                craft=spec.title(record) if spec.label == "Craft" else None,
                subject=None if spec.label == "Craft" else spec.title(record),
                what="Details",
            )
            name = unique_name(stem, ".txt", used)
            details = _text(parent, name, _info_text(info_panel(kind, record)))
            if details:
                entries.append(details)
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")




async def _workshop_for_artisan(artisan: Any, scope: Scope) -> Any | None:
    if artisan.workshopId:
        found = await db.workshop.find_first(
            where=_and({"id": artisan.workshopId}, scope.records)
        )
        if found:
            return found
    link = await db.workshopartisan.find_first(
        where={"artisanId": artisan.id}, include={"workshop": True}
    )
    if link is not None and getattr(link, "workshop", None) is not None:
        if not scope.records or await db.workshop.find_first(
            where=_and({"id": link.workshopId}, scope.records)
        ):
            return link.workshop
    if artisan.craftId:
        craft_link = await db.workshopcraft.find_first(
            where={"craftId": artisan.craftId}, include={"workshop": True}
        )
        if craft_link is not None and getattr(craft_link, "workshop", None) is not None:
            if not scope.records or await db.workshop.find_first(
                where=_and({"id": craft_link.workshopId}, scope.records)
            ):
                return craft_link.workshop
    return None


async def _artisan_path(aid: str, scope: Scope) -> str | None:
    artisan = await db.artisan.find_first(where=_and({"id": aid}, scope.records))
    if artisan is None:
        return None
    workshop = await _workshop_for_artisan(artisan, scope)
    if workshop is None:
        return None
    craft = artisan.craftId or NO_CRAFT
    return f"by-workshop/{workshop.id}/crafts/{craft}/artisans/{artisan.id}"


async def _record_fallback(kind: str, record_id: str, scope: Scope) -> dict[str, str] | None:
    if kind == "craft":
        found = await db.craft.find_unique(where={"id": record_id})
    elif kind == "artisan":
        found = await db.artisan.find_first(where=_and({"id": record_id}, scope.records))
    else:
        return None
    return {"kind": kind, "id": str(found.id)} if found else None


async def _locate_path(record_type: str, record_id: str, scope: Scope) -> str | None:
    kind = (record_type or "").strip().lower()

    if kind == "workshop":
        found = await db.workshop.find_first(where=_and({"id": record_id}, scope.records))
        return f"by-workshop/{found.id}" if found else None

    if kind == "artisan":
        return await _artisan_path(record_id, scope)

    if kind == "craft":
        link = await db.workshopcraft.find_first(where={"craftId": record_id})
        if link is None:
            return None
        if scope.records and not await db.workshop.find_first(
            where=_and({"id": link.workshopId}, scope.records)
        ):
            return None
        return f"by-workshop/{link.workshopId}/crafts/{record_id}"

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        record = await delegate.find_first(where=_and({"id": record_id}, scope.records))
        if record is None:
            return None
        owner = record.artisanId
        if owner is None and kind == "tool":
            link = await db.toolartisan.find_first(where={"toolId": record_id})
            owner = link.artisanId if link else None
        if owner is None:
            return None
        base = await _artisan_path(owner, scope)
        return f"{base}/{kind}s/{record_id}" if base else None

    if kind == "process":
        process = await db.process.find_first(where=_and({"id": record_id}, scope.records))
        if process is None or not process.productId:
            return None
        product_path = await _locate_path("product", process.productId, scope)
        return f"{product_path}/processes/{record_id}" if product_path else None

    if kind in {"interview", "questionnaire", "questionnaireinterview"}:
        link = await db.questionnaireinterviewartisan.find_first(
            where={"interviewId": record_id, "interview": {"is": live_session_where()}}
        )
        if link is None:
            return None
        base = await _artisan_path(link.artisanId, scope)
        return f"{base}/questionnaire/{record_id}" if base else None

    if kind in {"media", "mediafile"}:
        media = await db.mediafile.find_first(where=slim.live(_and({"id": record_id}, scope.media)))
        if media is None:
            return None
        for owner_kind, owner_id in (
            ("interview", media.questionnaireInterviewId),
            ("product", media.productId),
            ("tool", media.toolId),
            ("artisan", media.artisanId),
            ("craft", media.craftId),
            ("workshop", media.workshopId),
        ):
            if owner_id:
                found = await _locate_path(owner_kind, owner_id, scope)
                if found:
                    return found
        bucket = _TYPE_TOKEN.get(str(_ev(media.mediaType)).upper())
        return f"by-type/{bucket}" if bucket else "by-type"

    return None


@router.get("/locate")
async def data_locate(
    type: str = Query(..., description="workshop | craft | artisan | product | tool | process | interview | media"),
    id: str = Query(..., min_length=1),
    status_value: str | None = Query(None, alias="status"),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    scope = await _scope_for(current_user, data_status(status_value))
    path = await _locate_path(type, id, scope)
    kind = (type or "").strip().lower()
    record = None if path else await _record_fallback(kind, id, scope)
    return {"path": path, "locatable": path is not None, "record": record}


@router.get("/tree")
async def data_tree(
    path: str = "",
    status_value: str | None = Query(None, alias="status"),
    lean: bool = Query(False, alias="slim"),
    cursor: str | None = None,
    limit: int | None = Query(None, ge=1, le=explorer_tree.TREE_PAGE_MAX),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any]:
    scope = await _scope_for(current_user, data_status(status_value))
    norm = _norm(path)
    entries, info = await _list_level(norm, scope)
    entries = await transcript_export.settle_tree(entries, pipeline_on=transcript_pipeline.enabled())
    await explorer_counts.fill(norm, entries, scope)
    crumbs = await _resolve_crumbs(norm)
    public, total, next_cursor = await explorer_tree.shape(
        entries, lean=lean is True, cursor=cursor, limit=limit
    )
    return {
        "path": norm,
        "crumbs": crumbs,
        "entries": public,
        "info": info,
        "truncated": False,
        "total": total,
        "nextCursor": next_cursor,
        "taxonomies": TAXONOMIES,
        "taxonomy": _taxonomy_of(norm),
    }


async def _walk(
    path: str,
    rel: str,
    include: set[str] | None,
    files: list[dict[str, Any]],
    depth: int,
    seen_media: set[str],
    state: dict[str, bool],
    scope: Scope,
) -> None:
    walker = explorer_walk.Walk(_list_level, scope, include)
    walker.files = files
    walker.seen_media = seen_media
    await walker.run(path, rel, depth)
    if walker.truncated:
        state["truncated"] = True


MANIFEST_NDJSON_MEDIA_TYPE = "application/x-ndjson; charset=utf-8"
MANIFEST_TOTAL_HEADER = "X-Dataset-Total"
MANIFEST_MEDIA_HEADER = "X-Dataset-Media"
MANIFEST_TRUNCATED_HEADER = "X-Dataset-Truncated"
_MANIFEST_STREAM_CHUNK = 200


def manifest_ndjson_response(
    files: list[dict[str, Any]],
    total_media: int,
    truncated: bool,
    filename: str,
) -> StreamingResponse:
    total_files = len(files)

    async def body() -> AsyncIterator[bytes]:
        for start in range(0, total_files, _MANIFEST_STREAM_CHUNK):
            lines: list[str] = []
            for index in range(start, min(start + _MANIFEST_STREAM_CHUNK, total_files)):
                entry = files[index]
                files[index] = None  # type: ignore[call-overload]
                lines.append(json.dumps(entry, ensure_ascii=False))
            yield ("\n".join(lines) + "\n").encode("utf-8")

    return StreamingResponse(
        body(),
        media_type=MANIFEST_NDJSON_MEDIA_TYPE,
        headers={
            MANIFEST_TOTAL_HEADER: str(total_files),
            MANIFEST_MEDIA_HEADER: str(total_media),
            MANIFEST_TRUNCATED_HEADER: "true" if truncated else "false",
            "Content-Disposition": f'attachment; filename="{filename}"',
        },
    )


@router.get("/manifest", response_model=None)
async def data_manifest(
    path: str = "",
    include: str | None = None,
    stream: bool = False,
    status_value: str | None = Query(None, alias="status"),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    from app.api.routes import data_explorer

    include_set: set[str] | None = None
    if include is not None and include.strip():
        include_set = {t.strip().lower() for t in include.split(",") if t.strip()}
    scope = await _scope_for(current_user, data_status(status_value))
    files, truncated = await explorer_walk.manifest_files(
        _list_level, scope, [(_norm(path), "")], include_set, fill=not stream
    )
    return await data_explorer.manifest_response(files, truncated, stream=stream)


_ARTISAN_INCLUDE = {
    "craft": True,
    "createdBy": True,
    "workshops": {"include": {"workshop": True}},
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True, "createdBy": True}
_PROCESS_INCLUDE = {
    "steps": True,
    "product": {"include": {"workshop": True}},
    "createdBy": True,
}
_TOOL_INCLUDE = with_tool_scope({"createdBy": True})
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
    "createdBy": True,
}
_REPORT_MEDIA_COLUMNS = (
    'm."id"',
    'm."originalFilename"',
    'm."mediaType"::text AS "mediaType"',
    'm."sizeBytes"',
    'm."url"',
    'm."linkedRecordType"',
    'm."linkedRecordId"',
    'm."recordedAt"',
    'm."createdAt"',
    'm."uploadedById"',
    'm."artisanId"',
    'm."craftId"',
    'm."workshopId"',
    'm."productId"',
    'm."toolId"',
    'm."questionnaireInterviewId"',
)
_HAS_TRANSCRIPT = (
    '(m."transcriptText" IS NOT NULL AND m."transcriptText" <> \'\') AS "hasTranscript"'
)
_REPORT_MEDIA_SQL = (
    "SELECT " + ", ".join((*_REPORT_MEDIA_COLUMNS, _HAS_TRANSCRIPT))
    + ' FROM "MediaFile" m WHERE m."id" = ANY($1::text[])'
)

_REPORT_KEYS = (
    "workshops",
    "crafts",
    "artisans",
    "products",
    "processes",
    "tools",
    "interviews",
    "media",
)


def _tag_where(tags: list[str], ids: list[str]) -> dict[str, Any]:
    return {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}


async def _report_media(where: dict[str, Any] | None, scope: Scope) -> list[Any]:
    kwargs: dict[str, Any] = {
        "by": ["id", "createdAt"],
        "take": REPORT_TAKE,
        "order": {"createdAt": "desc"},
    }
    scoped = live_media_where(_and(where or {}, scope.media))
    if scoped:
        kwargs["where"] = scoped
    picked = await db.mediafile.group_by(**kwargs)
    return await _media_rows([str(row["id"]) for row in picked])


def _moment(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value)
        except ValueError:
            return value
    return value


async def _by_ids(
    delegate: Any, ids: set[str], include: dict[str, Any] | None = None
) -> dict[str, Any]:
    if not ids:
        return {}
    kwargs: dict[str, Any] = {"where": {"id": {"in": sorted(ids)}}}
    if include:
        kwargs["include"] = include
    return {row.id: row for row in await delegate.find_many(**kwargs)}


async def _media_rows(ids: list[str]) -> list[Any]:
    if not ids:
        return []
    found = {str(row["id"]): row for row in await db.query_raw(_REPORT_MEDIA_SQL, ids)}
    rows = [found[i] for i in ids if i in found]

    def wanted(key: str) -> set[str]:
        return {str(row[key]) for row in rows if row.get(key)}

    users, products, tools, artisans, workshops, crafts, interviews = await gather_reads(
        _by_ids(db.user, wanted("uploadedById")),
        _by_ids(db.productdocumentation, wanted("productId")),
        _by_ids(db.tooldocumentation, wanted("toolId")),
        _by_ids(db.artisan, wanted("artisanId"), {"craft": True}),
        _by_ids(db.workshop, wanted("workshopId")),
        _by_ids(db.craft, wanted("craftId")),
        _by_ids(db.questionnaireinterview, wanted("questionnaireInterviewId")),
    )
    out = []
    for row in rows:
        m = SimpleNamespace(**row)
        m.sizeBytes = int(m.sizeBytes) if m.sizeBytes is not None else None
        m.recordedAt = _moment(m.recordedAt)
        m.createdAt = _moment(m.createdAt)
        m.hasTranscript = bool(m.hasTranscript)
        m.uploadedBy = users.get(m.uploadedById)
        m.product = products.get(m.productId)
        m.tool = tools.get(m.toolId)
        m.artisan = artisans.get(m.artisanId)
        m.workshop = workshops.get(m.workshopId)
        m.craft = crafts.get(m.craftId)
        m.questionnaireInterview = interviews.get(m.questionnaireInterviewId)
        out.append(m)
    return out


async def _report_records(segs: list[str], scope: Scope) -> dict[str, list[Any]]:
    data: dict[str, list[Any]] = {key: [] for key in _REPORT_KEYS}

    if segs:
        if segs[0] == "by-workshop":
            segs = ["workshops", *segs[1:]]
        elif segs[0] == "by-type":
            segs = ["media-types", *segs[1:]]
        elif segs[0] == "by-uploader":
            if len(segs) >= 3:
                wid, uid = segs[1], segs[2]
                owned: list[dict[str, Any]] = [{"uploadedById": uid}, {"workshopId": wid}]
                if len(segs) >= 5 and segs[3] == "media" and segs[4] in _MEDIA_TYPE_WHERE:
                    owned.append(_MEDIA_TYPE_WHERE[segs[4]])
                data["media"] = await _report_media({"AND": owned}, scope)
                for branch in ("artisans", "products", "tools", "questionnaire"):
                    if len(segs) >= 4 and segs[3] not in (branch, "media"):
                        continue
                    kind, records = await _uploader_records(branch, wid, uid, scope)
                    if kind == "artisan":
                        data["artisans"] = records
                    elif kind == "product":
                        data["products"] = records
                    elif kind == "tool":
                        data["tools"] = records
                    elif kind == "interview":
                        data["interviews"] = records
                return data
            segs = ["workshops", *segs[1:]]

    if segs and segs[0] == "users":
        where: dict[str, Any] = {"uploadedById": segs[1]} if len(segs) >= 2 else {}
        if len(segs) >= 3 and segs[2] in _USER_TYPE_WHERE:
            where = {"AND": [where, _USER_TYPE_WHERE[segs[2]]]}
        data["media"] = await _report_media(where, scope)
        return data

    if segs and segs[0] == "media-types":
        type_where = _MEDIA_TYPE_WHERE.get(segs[1]) if len(segs) >= 2 else None
        if len(segs) >= 2 and type_where is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        data["media"] = await _report_media(type_where, scope)
        return data

    if segs and segs[0] != "workshops":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) <= 1:
        order = [{"createdAt": "desc"}, {"id": "desc"}]
        (
            data["workshops"],
            data["crafts"],
            data["artisans"],
            data["products"],
            data["processes"],
            data["tools"],
            data["interviews"],
            data["media"],
        ) = await gather_reads(
            db.workshop.find_many(
                where=scope.records, take=REPORT_TAKE, order=order, include={"createdBy": True}
            ),
            db.craft.find_many(
                take=REPORT_TAKE,
                order=order,
                include={"workshops": {"include": {"workshop": True}}},
            ),
            db.artisan.find_many(
                where=scope.records, take=REPORT_TAKE, order=order, include=_ARTISAN_INCLUDE
            ),
            db.productdocumentation.find_many(
                where=scope.records, take=REPORT_TAKE, order=order, include=_PRODUCT_INCLUDE
            ),
            db.process.find_many(
                where=scope.records, take=REPORT_TAKE, order=order, include=_PROCESS_INCLUDE
            ),
            db.tooldocumentation.find_many(
                where=scope.records, take=REPORT_TAKE, order=order, include=_TOOL_INCLUDE
            ),
            db.questionnaireinterview.find_many(
                where=live_session_where(scope.records), take=REPORT_TAKE, order=order, include=_INTERVIEW_INCLUDE
            ),
            _report_media(None, scope),
        )
        return data

    wid = segs[1]
    ws = await _require(
        db.workshop,
        wid,
        "Workshop",
        include={
            "createdBy": True,
            "crafts": {"include": {"craft": True}},
            "artisans": {"include": {"artisan": True}},
        },
        scope_where=scope.readable,
    )
    data["workshops"] = [ws]
    linked = await _linked_artisans(ws, scope)

    cid: str | None = None
    aid: str | None = None
    rest: list[str] = []
    if len(segs) >= 3:
        if segs[2] == "_misc":
            data["media"] = await _report_media(
                {
                    "AND": [
                        {"artisanId": None},
                        {"productId": None},
                        {"toolId": None},
                        {"questionnaireInterviewId": None},
                        _record_media_where("workshopId", wid, ["workshop"]),
                    ]
                },
                scope,
            )
            return data
        if segs[2] == "crafts":
            cid = segs[3] if len(segs) >= 4 else None
            if len(segs) >= 6 and segs[4] == "artisans":
                aid = segs[5]
                rest = list(segs[6:])
        elif segs[2] == "artisans":
            aid = segs[3] if len(segs) >= 4 else None
            rest = list(segs[4:]) if len(segs) >= 5 else []
        else:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if aid is not None:
        artisan = await _require(
            db.artisan, aid, "Artisan", include=_ARTISAN_INCLUDE, scope_where=scope.readable
        )
        scoped = [artisan]
    else:
        if cid == NO_CRAFT:
            ids = [a.id for a in linked if not a.craftId]
        elif cid:
            ids = [a.id for a in linked if a.craftId == cid]
        else:
            ids = [a.id for a in linked]
        scoped = (
            await db.artisan.find_many(
                where=_and({"id": {"in": ids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_ARTISAN_INCLUDE,
            )
            if ids
            else []
        )
    data["artisans"] = scoped
    aids = [a.id for a in scoped]

    if aid is not None:
        craft_ids = [a.craftId for a in scoped if a.craftId]
    elif cid == NO_CRAFT:
        craft_ids = []
    elif cid:
        craft_ids = [cid]
    else:
        craft_ids = sorted(
            {link.craftId for link in ws.crafts or []} | {a.craftId for a in scoped if a.craftId}
        )
    data["crafts"] = (
        await db.craft.find_many(
            where={"id": {"in": craft_ids}},
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include={"workshops": {"include": {"workshop": True}}},
        )
        if craft_ids
        else []
    )

    branch = rest[0] if rest else None
    if branch is not None and branch not in ("products", "tools", "questionnaire", "misc"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
    rid = rest[1] if len(rest) >= 2 else None
    proc_id = rest[3] if len(rest) >= 4 and rest[2] == "processes" else None

    products: list[Any] = []
    if branch in (None, "products") and aids:
        if branch == "products" and rid:
            products = [
                await _require(
                    db.productdocumentation,
                    rid,
                    "Product",
                    include=_PRODUCT_INCLUDE,
                    scope_where=scope.readable,
                )
            ]
        else:
            products = await db.productdocumentation.find_many(
                where=_and(
                    {
                        "AND": [
                            {"artisanId": {"in": aids}},
                            {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                        ]
                    },
                    scope.records,
                ),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PRODUCT_INCLUDE,
            )
    data["products"] = products
    pids = [p.id for p in products]

    processes: list[Any] = []
    if pids:
        if proc_id:
            proc = await db.process.find_first(
                where=_and({"id": proc_id}, scope.records), include=_PROCESS_INCLUDE
            )
            processes = [proc] if proc is not None else []
        else:
            processes = await db.process.find_many(
                where=_and({"productId": {"in": pids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PROCESS_INCLUDE,
            )
    data["processes"] = processes

    tools: list[Any] = []
    if branch in (None, "tools") and aids:
        if branch == "tools" and rid:
            tools = [
                await _require(
                    db.tooldocumentation,
                    rid,
                    "Tool",
                    include=_TOOL_INCLUDE,
                    scope_where=scope.readable,
                )
            ]
        else:
            tools = await db.tooldocumentation.find_many(
                where=_and(tools_in_workshop_clause(wid, aids), scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_TOOL_INCLUDE,
            )
    data["tools"] = tools

    interviews: list[Any] = []
    if branch in (None, "questionnaire") and aids:
        if branch == "questionnaire" and rid:
            interviews = [
                await _require(
                    db.questionnaireinterview,
                    rid,
                    "Interview",
                    include=_INTERVIEW_INCLUDE,
                    scope_where=live_session_where(scope.readable),
                )
            ]
        else:
            interviews = await db.questionnaireinterview.find_many(
                where=live_session_where(_and({"artisans": {"some": {"artisanId": {"in": aids}}}}, scope.records)),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_INTERVIEW_INCLUDE,
            )
    data["interviews"] = interviews

    if branch == "misc" and aid is not None:
        data["media"] = await _report_media(
            {
                "OR": [
                    {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                    {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
                ]
            },
            scope,
        )
        return data

    ors: list[dict[str, Any]] = []
    if cid is None and aid is None and branch is None:
        ors.append(_record_media_where("workshopId", wid, ["workshop"]))
    if aids and branch is None:
        ors.append({"artisanId": {"in": aids}})
        ors.append(_tag_where(["artisan"], aids))
    if pids:
        ors.append({"productId": {"in": pids}})
        ors.append(_tag_where(["product"], pids))
    if processes:
        proc_ids = [p.id for p in processes]
        step_ids = [s.id for p in processes for s in (p.steps or [])]
        ors.append(_tag_where(["process"], proc_ids))
        if step_ids:
            ors.append(_tag_where(["processstep"], step_ids))
    if tools:
        tool_ids = [t.id for t in tools]
        ors.append({"toolId": {"in": tool_ids}})
        ors.append(_tag_where(["tool"], tool_ids))
    if interviews:
        interview_ids = [i.id for i in interviews]
        ors.append({"questionnaireInterviewId": {"in": interview_ids}})
        ors.append(_tag_where(["questionnaire", "questionnaireinterview"], interview_ids))
    data["media"] = await _report_media({"OR": ors}, scope) if ors else []
    return data


def _sheet(
    name: str,
    color: str,
    columns: list[str],
    rows: list[list[Any]],
    truncated: bool = False,
    prose: list[int] | None = None,
) -> dict[str, Any]:
    capped = truncated or len(rows) > REPORT_TAKE
    if capped:
        rows = rows[:REPORT_TAKE]
        note = (
            f"Note: this report shows the first {REPORT_TAKE} entries; download a narrower folder "
            "for the rest."
        )
        rows = [*rows, [note] + [""] * (len(columns) - 1)]
    return {
        "name": name,
        "color": color,
        "columns": columns,
        "rows": rows,
        "truncated": capped,
        "prose": prose or [],
        "noteRow": capped,
    }


_OWNER_TAGS = ("processstep", "process")
_OWNER_FKS = (
    "productId",
    "toolId",
    "questionnaireInterviewId",
    "artisanId",
    "workshopId",
    "craftId",
)


def _media_owner_id(m: Any) -> str | None:
    tag = (getattr(m, "linkedRecordType", None) or "").strip().lower()
    if tag in _OWNER_TAGS and getattr(m, "linkedRecordId", None):
        return m.linkedRecordId
    for fk in _OWNER_FKS:
        value = getattr(m, fk, None)
        if value:
            return value
    return getattr(m, "linkedRecordId", None)


def _index_media_by_record(media: list[Any]) -> dict[str, list[Any]]:
    index: dict[str, list[Any]] = {}
    for m in media:
        owner = _media_owner_id(m)
        if owner:
            index.setdefault(owner, []).append(m)
    return index


def _hierarchy_of(kind: str, record: Any) -> tuple[str, str, str]:
    if kind == "workshop":
        return _cell(record.title), "", ""
    if kind == "craft":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return _cell(", ".join(t for t in titles if t)), _cell(record.name), ""
    if kind == "artisan":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return (
            _cell(", ".join(t for t in titles if t)),
            _cell(getattr(getattr(record, "craft", None), "name", None)),
            _cell(record.name),
        )
    if kind in ("product", "tool"):
        return (
            _cell(getattr(getattr(record, "workshop", None), "title", None)),
            _cell(record.craftName),
            _cell(record.artisanName),
        )
    if kind == "process":
        product = getattr(record, "product", None)
        return (
            _cell(getattr(getattr(product, "workshop", None), "title", None)),
            _cell(getattr(product, "craftName", None)),
            _cell(getattr(product, "artisanName", None)),
        )
    if kind == "interview":
        return "", "", _cell(", ".join(artisan_names(record)))
    return "", "", ""


def _media_link_label(m: Any, proc_names: dict[str, str], step_names: dict[str, str]) -> str:
    tag = (m.linkedRecordType or "").strip().lower()
    if tag == "process":
        name = proc_names.get(m.linkedRecordId or "")
        return f"Process: {name}" if name else "Process"
    if tag == "processstep":
        name = step_names.get(m.linkedRecordId or "")
        return f"Process step: {name}" if name else "Process step"
    product = getattr(m, "product", None)
    if product is not None:
        return f"Product: {_cell(product.productName) or 'Product'}"
    tool = getattr(m, "tool", None)
    if tool is not None:
        return f"Tool: {_cell(tool.toolkitName) or 'Tool'}"
    interview = getattr(m, "questionnaireInterview", None)
    if interview is not None:
        return f"Interview: {_cell(interview.title) or 'Interview'}"
    artisan = getattr(m, "artisan", None)
    if artisan is not None:
        return f"Artisan: {_cell(artisan.name) or 'Artisan'}"
    workshop = getattr(m, "workshop", None)
    if workshop is not None:
        return f"Workshop: {_cell(workshop.title) or 'Workshop'}"
    craft = getattr(m, "craft", None)
    if craft is not None:
        return f"Craft: {_cell(craft.name) or 'Craft'}"
    return "Miscellaneous"


def _media_context(
    m: Any, proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, str]:
    product = getattr(m, "product", None)
    tool = getattr(m, "tool", None)
    artisan = getattr(m, "artisan", None)
    workshop = getattr(m, "workshop", None)
    craft = getattr(m, "craft", None)

    workshop_name = _cell(getattr(workshop, "title", None))
    craft_name = _cell(getattr(craft, "name", None))
    artisan_name = _cell(getattr(artisan, "name", None))

    if product is not None:
        craft_name = craft_name or _cell(product.craftName)
        artisan_name = artisan_name or _cell(product.artisanName)
    if tool is not None:
        craft_name = craft_name or _cell(tool.craftName)
        artisan_name = artisan_name or _cell(tool.artisanName)
    if artisan is not None and not craft_name:
        craft_name = _cell(getattr(getattr(artisan, "craft", None), "name", None))

    label = _media_link_label(m, proc_names, step_names)
    attached_kind, _, attached_name = label.partition(": ")
    return {
        "workshop": workshop_name,
        "craft": craft_name,
        "artisan": artisan_name,
        "attachedTo": attached_kind,
        "record": attached_name or "",
        "label": label,
    }


def _media_facts(m: Any) -> dict[str, str]:
    return {
        "file": _cell(m.originalFilename) or _cell(m.id),
        "type": _cell(str(_ev(m.mediaType)).title()),
        "size": _human_size(m.sizeBytes),
        "uploadedBy": _cell(getattr(getattr(m, "uploadedBy", None), "name", None)),
        "uploadedOn": _cell(_date(m.createdAt)),
        "recordedOn": _cell(_date(m.recordedAt or m.createdAt)),
        "transcript": "Yes" if transcript_export.has_text(m) else "",
        "url": media_download_link(m.id),
    }



def _record_sheet(
    kind: str, records: list[Any], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    spec = SPECS[kind]
    rows = [sheet_row(kind, r, media_index.get(r.id, [])) for r in records]
    return _sheet(
        spec.plural, spec.color, sheet_columns(kind), rows, truncated=len(records) >= REPORT_TAKE
    )


def _process_step_sheet(
    processes: list[Any], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    columns = [
        "Step",
        "Process",
        "Product",
        "Artisan",
        "Step #",
        "Step type",
        "Notes",
        *MEDIA_COLUMNS,
    ]
    rows: list[list[Any]] = []
    for pr in processes:
        product = getattr(pr, "product", None)
        for step in sorted(getattr(pr, "steps", None) or [], key=lambda s: s.sortOrder):
            rows.append(
                [
                    _cell(step.name),
                    _cell(pr.name),
                    _cell(getattr(product, "productName", None)),
                    _cell(getattr(product, "artisanName", None)),
                    step.sortOrder,
                    _cell(_enum_label(step.stepType)),
                    _cell(step.notes),
                    *media_row(media_index.get(step.id, [])),
                ]
            )
    return _sheet("Process steps", SPECS["process"].color, columns, rows)


def _questionnaire_answer_sheet(interviews: list[Any]) -> dict[str, Any]:
    columns = ["Interview", "Artisans", "Section", "Question", "Answer", "Notes", "Origin"]
    rows: list[list[Any]] = []
    for interview in interviews:
        label = _cell(_interview_label(interview))
        names = _cell(", ".join(artisan_names(interview)))
        responses = sorted(
            interview.responses or [],
            key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
        )
        for r in responses:
            q = getattr(r, "question", None)
            section = (
                (getattr(q, "sectionTitle", None) or getattr(q, "sectionCode", None)) if q else None
            )
            rows.append(
                [
                    label,
                    names,
                    _cell(section),
                    _cell((getattr(q, "prompt", None) if q else None) or r.questionId),
                    _cell(r.answerText),
                    _cell(r.notes),
                    _cell(answer_drafts.origin_label(getattr(r, "answerOrigin", None)) or None),
                ]
            )
    return _sheet("Questionnaire answers", SPECS["interview"].color, columns, rows)


def _all_records_sheet(
    data: dict[str, list[Any]], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Record type",
        "Record",
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
        "All fields",
    ]
    order = (
        ("workshop", "workshops"),
        ("craft", "crafts"),
        ("artisan", "artisans"),
        ("product", "products"),
        ("process", "processes"),
        ("tool", "tools"),
        ("interview", "interviews"),
    )
    rows: list[list[Any]] = []
    for kind, key in order:
        spec = SPECS[kind]
        for record in data.get(key) or []:
            workshop, craft, artisan = _hierarchy_of(kind, record)
            rows.append(
                [
                    workshop,
                    craft,
                    artisan,
                    spec.label,
                    _cell(spec.title(record)),
                    *provenance_row(record),
                    *media_row(media_index.get(record.id, [])),
                    _info_text(info_panel(kind, record)),
                ]
            )
    return _sheet("All records", OVERVIEW_COLOR, columns, rows)


def _transcript_sheet(
    media: list[Any],
    proc_names: dict[str, str],
    step_names: dict[str, str],
    texts: dict[str, str] | None = None,
) -> dict[str, Any]:
    texts = texts or {}
    columns = [
        "File",
        "Type",
        "Workshop",
        "Craft",
        "Artisan",
        "Linked record",
        "Uploaded by",
        "Recorded on",
        "Transcript",
    ]
    rows = []
    for m in media:
        if not transcript_export.has_text(m):
            continue
        ctx = _media_context(m, proc_names, step_names)
        facts = _media_facts(m)
        rows.append(
            [
                facts["file"],
                facts["type"],
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["label"],
                facts["uploadedBy"],
                facts["recordedOn"],
                _cell(texts.get(str(m.id))),
            ]
        )
    return _sheet(
        "Transcripts", TRANSCRIPT_COLOR, columns, rows, prose=[columns.index("Transcript")]
    )


def _media_by_hierarchy_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[2].lower(), r[5].lower()))
    return _sheet("Media by hierarchy", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_uploader_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    columns = [
        "Uploaded by",
        "Workshop",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                f["uploadedBy"],
                ctx["workshop"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[4].lower()))
    return _sheet("Media by uploader", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_type_sheet(
    media: list[Any], proc_names: dict[str, str], step_names: dict[str, str]
) -> dict[str, Any]:
    columns = [
        "Type",
        "File",
        "Workshop",
        "Artisan",
        "Attached to",
        "Record",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names)
        f = _media_facts(m)
        rows.append(
            [
                f["type"],
                f["file"],
                ctx["workshop"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower()))
    return _sheet("Media by type", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _report_sheets(
    data: dict[str, list[Any]], texts: dict[str, str] | None = None
) -> list[dict[str, Any]]:
    media = data["media"]
    proc_names = {p.id: _cell(p.name) for p in data["processes"]}
    step_names = {s.id: _cell(s.name) for p in data["processes"] for s in (p.steps or [])}
    media_index = _index_media_by_record(media)

    return [
        _all_records_sheet(data, media_index),
        _record_sheet("workshop", data["workshops"], media_index),
        _record_sheet("craft", data["crafts"], media_index),
        _record_sheet("artisan", data["artisans"], media_index),
        _record_sheet("product", data["products"], media_index),
        _record_sheet("process", data["processes"], media_index),
        _process_step_sheet(data["processes"], media_index),
        _record_sheet("tool", data["tools"], media_index),
        _record_sheet("interview", data["interviews"], media_index),
        _questionnaire_answer_sheet(data["interviews"]),
        _transcript_sheet(media, proc_names, step_names, texts),
        _media_by_hierarchy_sheet(media, proc_names, step_names),
        _media_by_uploader_sheet(media, proc_names, step_names),
        _media_by_type_sheet(media, proc_names, step_names),
    ]


def _rendered(sheets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rendered = []
    for sheet in sheets:
        prose = sheet["prose"]
        if not prose:
            rendered.append(sheet)
            continue
        rows = [
            [transcript_cell(value) if idx in prose else value for idx, value in enumerate(row)]
            for row in sheet["rows"]
        ]
        rendered.append({**sheet, "rows": rows})
    return rendered


@router.get("/report")
async def data_report(
    path: str = "",
    format: str | None = None,
    status_value: str | None = Query(None, alias="status"),
    q: str | None = None,
    sheet: str | None = None,
    sheets: str | None = None,
    answers: str | None = None,
    rows: str | None = None,
    links: str | None = None,
    name: str | None = None,
    thumbnails: str | None = None,
    locale: str | None = None,
    extras: str | None = None,
    parts: str | None = None,
    offset: int = Query(0, ge=0, le=REPORT_TAKE),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    offset = offset if isinstance(offset, int) else 0
    fmt = (format or "xlsx").strip().lower()
    if fmt not in ("json", "xlsx", "csv"):
        raise HTTPException(status_code=422, detail="format must be 'json', 'xlsx' or 'csv'")
    if fmt == "csv" and not (sheet or "").strip():
        raise HTTPException(status_code=422, detail="Name the sheet to download as CSV.")
    status_filter = data_status(status_value)
    try:
        options = report_options.parse(
            sheets=sheets, answers=answers, rows=rows, links=links, q=q,
            thumbnails=thumbnails, locale=locale, extras=extras,
        )
    except report_options.OptionsInvalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    try:
        return await _build_report(
            path, fmt, status_filter, current_user, q=q, sheet=sheet, options=options,
            file_name=report_options.file_name(name), offset=offset,
            parts=(parts or "").strip().lower() == "zip",
        )
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None


async def _queued(work: Any) -> Any:
    async with REPORT_QUEUE.slot():
        try:
            return await work()
        finally:
            _trim_heap()


def _preview(
    sheets: list[dict[str, Any]], uncounted: set[int], offset: int = 0
) -> list[dict[str, Any]]:
    start = max(0, offset)
    end = start + PREVIEW_ROWS
    out = []
    for index, sheet in enumerate(sheets):
        rows = sheet.get("rows") or []
        note = rows[-1:] if sheet.get("noteRow") else []
        data = rows[:-1] if note else rows
        shown = data[start:end]
        known = index not in uncounted
        cut = len(data) > end or (not known and bool(sheet.get("truncated")))
        capped = bool(sheet.get("truncated")) and known
        noted = capped and (start == 0 or end >= len(data))
        entry = {k: v for k, v in sheet.items() if k not in ("rows", "noteRow")}
        numbers = list(range(start + 1, start + len(shown) + 1))
        entry.update(
            rows=[*shown, *note] if noted else shown,
            rowNumbers=[*numbers, *([0] * len(note))] if noted else numbers,
            truncated=cut or capped,
            preview=cut,
            capped=capped,
            totalRows=len(data) if known else None,
            offset=start,
        )
        out.append(entry)
    return out


@functools.cache
def _malloc_trim() -> Any:
    if not sys.platform.startswith("linux"):
        return None
    try:
        return ctypes.CDLL("libc.so.6").malloc_trim
    except (OSError, AttributeError):
        return None


def _trim_heap() -> None:
    trim = _malloc_trim()
    if trim is None:
        return
    with contextlib.suppress(Exception):
        trim(0)


async def _report_material(norm: str, scope: Scope, sees_spend: bool) -> dict[str, Any]:
    segs = [s for s in norm.split("/") if s]
    records = await _report_records(segs, scope)
    await hide_unseen(records["tools"], scope.readable)
    await transcript_export.withhold_refused(records["media"])
    transcribed = [str(m.id) for m in records["media"] if transcript_export.has_text(m)]
    texts = await transcript_export.transcript_texts(transcribed)
    sheets = _report_sheets(records, texts)
    del texts
    sheets += await transcript_export.report_sheets(
        transcribed,
        sees_spend=sees_spend,
        sheet=_sheet,
        cap=REPORT_TAKE,
        color=TRANSCRIPT_COLOR,
    )
    del records
    crumbs = await _resolve_crumbs(norm)
    level_name = crumbs[-1]["name"] if crumbs else "Repository"
    return {"sheets": sheets, "level": level_name, "builtAt": datetime.now().astimezone().isoformat()}


def _report_key(norm: str, scope: Scope, sees_spend: bool, version: str) -> str:
    return REPORT_CACHE.key("report", norm, scope.records, scope.media, scope.visible, sees_spend, version)


async def _material(
    norm: str, scope: Scope, sees_spend: bool
) -> tuple[str, dict[str, Any], bool]:
    version = await report_cache.data_version(db)
    key = _report_key(norm, scope, sees_spend, version)
    found = REPORT_CACHE.get(key)
    if found is not None:
        return key, found, True

    async def build() -> dict[str, Any]:
        cached = REPORT_CACHE.get(key)
        if cached is not None:
            return cached
        made = await _queued(lambda: _report_material(norm, scope, sees_spend))
        REPORT_CACHE.put(key, made)
        REPORT_CACHE.builds += 1
        return made

    made = await REPORT_CACHE.once(key, build)
    return key, REPORT_CACHE.get(key) or made, False


async def _build_report(
    path: str,
    fmt: str,
    status_filter: str | None,
    current_user: Any,
    *,
    q: str | None = None,
    sheet: str | None = None,
    options: report_options.ReportOptions | None = None,
    file_name: str | None = None,
    offset: int = 0,
    parts: bool = False,
) -> Response:
    options = options or report_options.ReportOptions()
    scope = await _scope_for(current_user, status_filter)
    searching = fmt == "json" and bool(report_tools.words(q))
    norm = _norm(path)
    sees_spend = transcript_portal.may_see_spend(current_user)
    _key, material, cached = await _material(norm, scope, sees_spend)
    sheets: list[dict[str, Any]] = material["sheets"]
    level_name: str = material["level"]
    if sheet and sheet.strip() and fmt != "xlsx":
        sheets = report_tools.pick(sheets, sheet)
        if not sheets:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such sheet")
    if fmt == "csv":
        chosen = _rendered(sheets[:1])[0]
        del sheets, material
        csv_name = naming.export_filename(
            "Dataset",
            "-".join(str(chosen["name"]).split()) or "Sheet",
            "csv",
            subject="All Records" if level_name == "Repository" else level_name,
        )
        return StreamingResponse(
            report_tools.csv_chunks(chosen),
            media_type="text/csv; charset=utf-8",
            headers={"Content-Disposition": _content_disposition(csv_name)},
        )
    if fmt == "json":
        shown = (
            report_tools.search(sheets, q, offset=offset)
            if searching
            else _preview(sheets, set(), offset)
        )
        report_tools.cap_prose(shown)
        return JSONResponse(
            {
                "sheets": shown,
                "query": q if searching else None,
                "previewRows": PREVIEW_ROWS,
                "builtAt": material.get("builtAt"),
                "cached": cached,
            }
        )

    del sheets, material
    from app.services.datareport import build as report_build

    chosen = report_build.from_parsed(options, name=file_name)
    if sheet and sheet.strip() and chosen.sheets is None:
        chosen.sheets = [sheet.strip()]
    built = await report_build.build_report(current_user, path, chosen, status=status_filter)
    for hook in built.ctx.delivered:
        await hook()
    payload, delivered_name, mime = report_build.deliverable(built, parts=parts)
    return Response(
        content=payload,
        media_type=mime,
        headers={"Content-Disposition": _content_disposition(delivered_name)},
    )
