from __future__ import annotations

from collections.abc import Iterator
from datetime import datetime
from typing import Any

from app.services.datareport import exclusions
from app.services.datareport.contract import (
    ROW_FULL,
    Banner,
    Column,
    Link,
    ReportContext,
    Sheet,
    register,
)
from app.services.datareport.sheets.extras_common import (
    KIND_LABELS,
    KINDS,
    MODELS,
    Plugin,
    clip,
    g,
    ident,
    is_secret,
    json_text,
    label,
    open_record,
    records,
    redact,
    say,
    scalar,
)

ROWS_MAX = 1_000_000
_SKIP = frozenset({"id"})


def field_names(rec: Any) -> list[str]:
    if isinstance(rec, dict):
        return list(rec)
    fields = getattr(type(rec), "model_fields", None)
    if isinstance(fields, dict) and fields:
        return list(fields)
    return list(getattr(rec, "__dict__", {}))


def _is_model(value: Any) -> bool:
    return hasattr(type(value), "model_fields") or (
        hasattr(value, "__dict__") and not isinstance(value, type)
    )


def cell(value: Any) -> tuple[bool, Any]:
    if value is None:
        return True, None
    if isinstance(value, dict):
        return True, clip(json_text(redact(value)))
    if isinstance(value, (list, tuple)):
        if any(_is_model(v) or isinstance(v, dict) for v in value):
            return (True, clip(json_text(redact(list(value))))) if all(isinstance(v, dict) for v in value) else (False, None)
        return True, ", ".join(str(scalar(v) if scalar(v) is not None else v) for v in value)
    plain = scalar(value)
    if plain is not None:
        return True, plain
    if _is_model(value):
        return False, None
    return True, str(value)


def _row(kind: str, rec: Any, model: str, field: str, value: Any, section: str | None = None, full: str | None = None) -> dict[str, Any]:
    out: dict[str, Any] = {
        ROW_FULL: full,
        "type": KIND_LABELS.get(kind, kind),
        "recordId": ident(rec),
        "recordName": label(kind, rec),
        "section": section,
        "field": field,
        "source": f"{model}.{field}" if model else None,
        "value": None,
        "number": None,
        "date": None,
    }
    if isinstance(value, bool):
        out["value"] = "Yes" if value else "No"
    elif isinstance(value, (int, float)):
        out["number"] = value
        out["value"] = str(value)
    elif isinstance(value, datetime):
        out["date"] = value
        out["value"] = value.isoformat()
    else:
        out["value"] = clip(value)
    return out


def _responses(kind: str, rec: Any, full: str | None) -> Iterator[dict[str, Any]]:
    for response in g(rec, "responses") or []:
        question = g(response, "question")
        prompt = str(g(question, "prompt") or g(response, "questionId") or "")
        section = g(question, "sectionTitle") or g(question, "sectionCode")
        answer = g(response, "answerText")
        if answer is None or not str(answer).strip():
            continue
        row = _row(kind, rec, "QuestionnaireResponse", "answerText", str(answer), section=section, full=full)
        row["field"] = prompt
        yield row


def _steps(kind: str, rec: Any, full: str | None) -> Iterator[dict[str, Any]]:
    for step in g(rec, "steps") or []:
        position = g(step, "sortOrder")
        name = g(step, "name")
        if name:
            yield _row(kind, rec, "ProcessStep", "name", str(name), section=f"Step {position}", full=full)


def flatten(ctx: ReportContext) -> Iterator[dict[str, Any]]:
    count = 0
    for kind in KINDS:
        model = MODELS.get(kind, "")
        for rec in records(ctx, kind):
            opened = open_record(ctx, kind, rec)
            full = opened.url if isinstance(opened, Link) else None
            transcript = ctx.links.transcript_file(ident(rec), "docx") if kind == "media" and full and ident(rec) else None
            for name in field_names(rec):
                if name in _SKIP or is_secret(name) or (model and exclusions.excluded(model, name)):
                    continue
                keep, value = cell(exclusions.identity_view(ctx.user, rec, name, g(rec, name)))
                if not keep or value is None or value == "":
                    continue
                yield _row(kind, rec, model, name, value, full=transcript if transcript and name.startswith("transcript") else full)
                count += 1
                if count >= ROWS_MAX:
                    return
            if kind == "media" and ctx.options.links and ident(rec):
                yield _row(kind, rec, model, "download", ctx.links.download(ident(rec)), full=full)
            extra = _responses(kind, rec, full) if kind == "interviews" else _steps(kind, rec, full) if kind == "processes" else ()
            for row in extra:
                yield row
                count += 1
                if count >= ROWS_MAX:
                    return


COLUMNS = [
    Column("type", "Record type", "text", "What the record is.", width=16),
    Column("recordId", "Record ID", "text", "The record's id; every row of one record shares it, and the record's own sheet holds its link.", width=26, tech=False),
    Column("recordName", "Record", "text", "The record's name or title.", width=26),
    Column("section", "Section", "text", "Questionnaire section or process step, where it applies.", width=18),
    Column("field", "Field", "text", "The field, or the question for an answer.", width=26),
    Column("source", "Source", "text", "Where the value is stored, as Model.field.", width=26, tech=False),
    Column("value", "Value", "longtext", "The value as text.", width=40),
    Column("number", "Number", "number", "The value when it is a number, for sums and averages."),
    Column("date", "Date", "datetime", "The value when it is a date."),
]


async def build(ctx: ReportContext) -> list[Sheet]:
    return [
        Sheet(
            key="everything",
            name="Everything",
            columns=COLUMNS,
            rows=flatten(ctx),
            banners=[Banner(say(ctx, "banner.pivot", "One flat table of all the data, ready for PivotTables."))],
            note=say(ctx, "note.everythingAll", "Every field of every record, one row each, including identifiers and stamps. Long values end with a link to the full text."),
        )
    ]


register(
    Plugin(
        "extras_everything",
        "Everything",
        160,
        "everything",
        build,
        lambda: [("everything", COLUMNS, "Everything")],
    )
)
