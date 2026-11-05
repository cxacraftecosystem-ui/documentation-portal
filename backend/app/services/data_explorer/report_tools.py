from __future__ import annotations

import csv
import io
from collections.abc import Iterator
from typing import Any

PREVIEW_TRANSCRIPT_CHARS = 2000
SEARCH_MAX_ROWS = 200
_CSV_FLUSH_BYTES = 256_000


def _text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, dict):
        return " ".join(_text(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return " ".join(_text(v) for v in value)
    return str(value)


def words(query: str | None) -> list[str]:
    return [w for w in (query or "").lower().split() if w]


def row_matches(row: list[Any], wanted: list[str]) -> bool:
    if not wanted:
        return True
    haystack = " ".join(_text(cell) for cell in row).lower()
    return all(w in haystack for w in wanted)


def _data_rows(sheet: dict[str, Any]) -> list[list[Any]]:
    rows = sheet.get("rows") or []
    return rows[:-1] if sheet.get("noteRow") and rows else rows


def pick(sheets: list[dict[str, Any]], name: str | None) -> list[dict[str, Any]]:
    if not name:
        return sheets
    wanted = name.strip().lower()
    return [s for s in sheets if str(s.get("name") or "").strip().lower() == wanted]


def search(
    sheets: list[dict[str, Any]],
    query: str | None,
    *,
    limit: int = SEARCH_MAX_ROWS,
    offset: int = 0,
) -> list[dict[str, Any]]:
    wanted = words(query)
    start = max(0, offset)
    end = start + limit
    out: list[dict[str, Any]] = []
    for sheet in sheets:
        numbered = [(n, row) for n, row in enumerate(_data_rows(sheet), start=1) if row_matches(row, wanted)]
        matched = [row for _n, row in numbered]
        entry = {k: v for k, v in sheet.items() if k not in ("rows", "noteRow")}
        entry.update(
            rows=matched[start:end],
            rowNumbers=[n for n, _row in numbered[start:end]],
            matchedRows=len(matched),
            totalRows=len(_data_rows(sheet)),
            truncated=len(matched) > end or bool(sheet.get("truncated")),
            preview=len(matched) > end,
            capped=bool(sheet.get("truncated")),
            offset=start,
        )
        out.append(entry)
    return out


def cap_cell(value: Any, limit: int = PREVIEW_TRANSCRIPT_CHARS) -> Any:
    if isinstance(value, str) and len(value) > limit:
        return value[: limit - 1].rstrip() + "…"
    return value


def cap_prose(sheets: list[dict[str, Any]], limit: int = PREVIEW_TRANSCRIPT_CHARS) -> None:
    for sheet in sheets:
        prose = set(sheet.get("prose") or [])
        if not prose:
            continue
        sheet["rows"] = [
            [cap_cell(v, limit) if i in prose else v for i, v in enumerate(row)]
            for row in sheet.get("rows") or []
        ]


def csv_chunks(sheet: dict[str, Any], render: Any = None) -> Iterator[bytes]:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    buffer.write("﻿")
    writer.writerow([str(c) for c in sheet.get("columns") or []])
    prose = set(sheet.get("prose") or [])
    for row in sheet.get("rows") or []:
        cells = [
            render(v) if render is not None and i in prose else v for i, v in enumerate(row)
        ]
        writer.writerow(["" if v is None else _text(v) for v in cells])
        if buffer.tell() > _CSV_FLUSH_BYTES:
            yield buffer.getvalue().encode("utf-8")
            buffer.seek(0)
            buffer.truncate(0)
    tail = buffer.getvalue()
    if tail:
        yield tail.encode("utf-8")
