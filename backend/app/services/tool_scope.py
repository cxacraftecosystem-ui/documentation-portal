from __future__ import annotations

from collections.abc import Iterable, Iterator
from typing import Any

from app.core.db import db

_ARTISAN_WORKSHOPS: dict[str, Any] = {
    "workshop": True,
    "workshops": {"include": {"workshop": True}},
}

TOOL_SCOPE_INCLUDE: dict[str, Any] = {
    "workshop": True,
    "artisan": {"include": _ARTISAN_WORKSHOPS},
    "artisanLinks": {"include": {"artisan": {"include": _ARTISAN_WORKSHOPS}}},
}


def with_tool_scope(include: dict[str, Any]) -> dict[str, Any]:
    return {**include, **TOOL_SCOPE_INCLUDE}


def tool_artisan_ids(tool: Any) -> list[str]:
    ids: list[str] = []
    primary = getattr(tool, "artisanId", None)
    if primary:
        ids.append(primary)
    for link in getattr(tool, "artisanLinks", None) or []:
        artisan_id = getattr(link, "artisanId", None)
        if artisan_id and artisan_id not in ids:
            ids.append(artisan_id)
    return ids


def _linked_artisans(tool: Any) -> Iterator[Any]:
    seen: set[str] = set()
    links = getattr(tool, "artisanLinks", None) or []
    primary = getattr(tool, "artisan", None)
    for artisan in [primary, *(getattr(link, "artisan", None) for link in links)]:
        artisan_id = getattr(artisan, "id", None)
        if artisan is None or not artisan_id or artisan_id in seen:
            continue
        seen.add(artisan_id)
        yield artisan


def _artisan_workshops(artisan: Any) -> Iterator[Any]:
    own = getattr(artisan, "workshop", None)
    if own is not None:
        yield own
    for link in getattr(artisan, "workshops", None) or []:
        workshop = getattr(link, "workshop", None)
        if workshop is not None:
            yield workshop


def tool_artisan_names(tool: Any) -> str | None:
    names: list[str] = []
    primary_id = getattr(tool, "artisanId", None)
    linked_ids = set()
    for link in getattr(tool, "artisanLinks", None) or []:
        artisan = getattr(link, "artisan", None)
        if artisan is None:
            continue
        linked_ids.add(getattr(artisan, "id", None))
    primary_name = (getattr(tool, "artisanName", None) or "").strip()
    if primary_name and (not primary_id or primary_id not in linked_ids):
        names.append(primary_name)
    for link in getattr(tool, "artisanLinks", None) or []:
        name = (getattr(getattr(link, "artisan", None), "name", None) or "").strip()
        if name and name not in names:
            names.append(name)
    return ", ".join(names) or None


def tool_workshop_titles(tool: Any) -> str | None:
    titles: list[str] = []
    seen: set[str] = set()
    workshops: list[Any] = []
    own = getattr(tool, "workshop", None)
    if own is not None:
        workshops.append(own)
    for artisan in _linked_artisans(tool):
        workshops.extend(_artisan_workshops(artisan))
    for workshop in workshops:
        workshop_id = getattr(workshop, "id", None)
        title = (getattr(workshop, "title", None) or "").strip()
        if not title or (workshop_id and workshop_id in seen):
            continue
        if workshop_id:
            seen.add(workshop_id)
        titles.append(title)
    return ", ".join(titles) or None


def artisan_workshop_ids(artisan: Any) -> set[str]:
    ids: set[str] = set()
    own = getattr(artisan, "workshopId", None)
    if own:
        ids.add(own)
    for link in getattr(artisan, "workshops", None) or []:
        workshop_id = getattr(link, "workshopId", None)
        if workshop_id:
            ids.add(workshop_id)
    return ids


def tool_fits(tool: Any, workshop_id: str, artisan_id: str, members: set[str]) -> bool:
    if artisan_id not in tool_artisan_ids(tool):
        return False
    own = getattr(tool, "workshopId", None)
    return own is None or own == workshop_id or artisan_id in members


async def hide_unseen(tools: Iterable[Any], records_where: dict[str, Any]) -> None:
    rows = [tool for tool in tools if tool is not None]
    if not records_where or not rows:
        return
    artisan_ids: set[str] = set()
    for tool in rows:
        artisan_ids.update(
            getattr(getattr(link, "artisan", None), "id", None) or ""
            for link in getattr(tool, "artisanLinks", None) or []
        )
        primary = getattr(getattr(tool, "artisan", None), "id", None)
        if primary:
            artisan_ids.add(primary)
    artisan_ids.discard("")
    seen_artisans: set[str] = set()
    if artisan_ids:
        visible = await db.artisan.find_many(
            where={"AND": [{"id": {"in": sorted(artisan_ids)}}, records_where]}
        )
        seen_artisans = {artisan.id for artisan in visible}
    for tool in rows:
        links = getattr(tool, "artisanLinks", None)
        if links:
            tool.artisanLinks = [
                link
                for link in links
                if getattr(getattr(link, "artisan", None), "id", None) in seen_artisans
            ]
        primary = getattr(tool, "artisan", None)
        if primary is not None and primary.id not in seen_artisans:
            tool.artisan = None
    workshop_ids: set[str] = set()
    for tool in rows:
        for artisan in _linked_artisans(tool):
            workshop_ids.update(
                getattr(workshop, "id", None) or "" for workshop in _artisan_workshops(artisan)
            )
    workshop_ids.discard("")
    if not workshop_ids:
        return
    visible_workshops = await db.workshop.find_many(
        where={"AND": [{"id": {"in": sorted(workshop_ids)}}, records_where]}
    )
    seen_workshops = {workshop.id for workshop in visible_workshops}
    for tool in rows:
        for artisan in _linked_artisans(tool):
            own = getattr(artisan, "workshop", None)
            if own is not None and own.id not in seen_workshops:
                artisan.workshop = None
            links = getattr(artisan, "workshops", None)
            if links:
                artisan.workshops = [
                    link for link in links if getattr(link, "workshopId", None) in seen_workshops
                ]
