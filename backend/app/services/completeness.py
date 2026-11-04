from __future__ import annotations

import hashlib
import json
import pathlib
from dataclasses import dataclass
from typing import Any, Iterable, Literal

from app.core.db import db
from app.services.concurrency import gather_reads

Kind = Literal["artisan", "product", "tool", "interview", "workshop"]
Mode = Literal["incomplete", "required", "complete"]

MODES: tuple[str, ...] = ("incomplete", "required", "complete")


def _rules_path() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().with_name("record-completeness.json")


RULES_PATH = _rules_path()
RULES_BYTES: bytes = RULES_PATH.read_bytes().replace(b"\r\n", b"\n")
RULES: dict[str, Any] = json.loads(RULES_BYTES.decode("utf-8"))
RULES_VERSION: int = int(RULES["version"])
RULES_ETAG: str = f'"{RULES_VERSION}-{hashlib.sha256(RULES_BYTES).hexdigest()[:8]}"'

KINDS: tuple[str, ...] = tuple(RULES["kinds"].keys())

STRING_COLUMNS: frozenset[tuple[str, str]] = frozenset(
    {
        ("artisan", "dos"),
        ("artisan", "donts"),
        ("product", "timeTakenToCompleteProduct"),
        ("tool", "localName"),
        ("tool", "material"),
        ("interview", "place"),
        ("interview", "language"),
    }
)

_LIVE_IMAGE = {"mediaType": "IMAGE", "status": {"not": "REJECTED"}, "removedAt": None}
_LIVE_RECORDING = {"mediaType": {"in": ["AUDIO", "VIDEO"]}, "status": {"not": "REJECTED"}, "removedAt": None}

COUNT_CLAUSES: dict[tuple[str, str], dict[str, Any]] = {
    ("artisan", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("product", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("tool", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("workshop", "photos"): {"media": {"none": _LIVE_IMAGE}},
    ("interview", "recordings"): {"media": {"none": _LIVE_RECORDING}},
    ("interview", "artisans"): {"artisans": {"none": {}}},
    ("interview", "answers"): {"responses": {"none": {}}},
    ("workshop", "artisans"): {"AND": [{"artisans": {"none": {}}}, {"artisansLinked": {"none": {}}}]},
    ("workshop", "crafts"): {"AND": [{"crafts": {"none": {}}}, {"craftsLinked": {"none": {}}}]},
}


@dataclass(frozen=True)
class CountSource:
    model: str
    fk: str
    where: dict[str, Any]
    payload_key: str | None = None


def _media_filter(kind_types: tuple[str, ...]):
    def keep(row: dict[str, Any]) -> bool:
        return row.get("mediaType") in kind_types and row.get("status") != "REJECTED" and row.get("removedAt") is None

    return keep


COUNT_SOURCES: dict[tuple[str, str], tuple[CountSource, ...]] = {
    ("artisan", "photos"): (CountSource("mediafile", "artisanId", _LIVE_IMAGE),),
    ("product", "photos"): (CountSource("mediafile", "productId", _LIVE_IMAGE, "media"),),
    ("tool", "photos"): (CountSource("mediafile", "toolId", _LIVE_IMAGE, "media"),),
    ("workshop", "photos"): (CountSource("mediafile", "workshopId", _LIVE_IMAGE),),
    ("interview", "recordings"): (
        CountSource("mediafile", "questionnaireInterviewId", _LIVE_RECORDING, "media"),
    ),
    ("interview", "artisans"): (CountSource("questionnaireinterviewartisan", "interviewId", {}, "artisans"),),
    ("interview", "answers"): (CountSource("questionnaireresponse", "interviewId", {}, "responses"),),
    ("workshop", "artisans"): (
        CountSource("workshopartisan", "workshopId", {}, "artisans"),
        CountSource("artisan", "workshopId", {}),
    ),
    ("workshop", "crafts"): (
        CountSource("workshopcraft", "workshopId", {}, "crafts"),
        CountSource("craft", "workshopId", {}),
    ),
}

_PAYLOAD_FILTERS = {
    ("product", "photos"): _media_filter(("IMAGE",)),
    ("tool", "photos"): _media_filter(("IMAGE",)),
    ("interview", "recordings"): _media_filter(("AUDIO", "VIDEO")),
}


def rules_for(kind: str) -> list[dict[str, Any]]:
    return list(RULES["kinds"][kind])


def count_names(kind: str) -> list[str]:
    names: list[str] = []
    for rule in rules_for(kind):
        if rule["check"] == "count" and rule["of"] not in names:
            names.append(rule["of"])
    return names



def is_present(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    return True


def _meets(rule: dict[str, Any], record: dict[str, Any], counts: dict[str, Any]) -> bool:
    check = rule["check"]
    if check == "present":
        return is_present(record.get(rule["path"]))
    if check == "notIn":
        value = record.get(rule["path"])
        return value not in rule["empty"]
    if check == "anyPresent":
        return any(is_present(record.get(path)) for path in rule["paths"])
    if check == "count":
        raw = counts.get(rule["of"], 0)
        number = raw if isinstance(raw, (int, float)) and not isinstance(raw, bool) else 0
        return number >= rule["min"]
    raise ValueError(f"unknown completeness check {check!r}")


def percent_of(filled: int, total: int) -> int:
    if total <= 0:
        return 100
    return filled * 100 // total


def evaluate(kind: str, record: dict[str, Any], counts: dict[str, Any] | None = None) -> dict[str, Any]:
    counts = counts or {}
    rules = rules_for(kind)
    unmet = [rule for rule in rules if not _meets(rule, record, counts)]
    ordered = [r for r in unmet if r["level"] == "required"] + [r for r in unmet if r["level"] != "required"]
    filled = len(rules) - len(unmet)
    return {
        "rulesVersion": RULES_VERSION,
        "filled": filled,
        "total": len(rules),
        "percent": percent_of(filled, len(rules)),
        "requiredMissing": sum(1 for r in unmet if r["level"] == "required"),
        "missing": [
            {"key": r["key"], "label": r["label"], "words": r["words"], "level": r["level"]} for r in ordered
        ],
    }


def sentence(block: dict[str, Any]) -> str:
    missing = block.get("missing") or []
    count = len(missing)
    if count == 0:
        return "Complete"
    words = ", ".join(item["words"] for item in missing[:3])
    more = f", +{count - 3} more" if count > 3 else ""
    return f"{count} field{'' if count == 1 else 's'} left: {words}{more}"



def _missing_clause(kind: str, rule: dict[str, Any]) -> dict[str, Any]:
    check = rule["check"]
    if check == "present":
        path = rule["path"]
        if (kind, path) in STRING_COLUMNS:
            return {"OR": [{path: None}, {path: ""}]}
        return {path: None}
    if check == "notIn":
        return {rule["path"]: {"in": list(rule["empty"])}}
    if check == "anyPresent":
        return {"AND": [{path: None} for path in rule["paths"]]}
    if check == "count":
        if rule["min"] != 1:
            raise ValueError("a relation clause can only express min 1")
        return COUNT_CLAUSES[(kind, rule["of"])]
    raise ValueError(f"unknown completeness check {check!r}")


_NOTHING = {"id": {"in": []}}


def missing_where(kind: str, mode: str) -> dict[str, Any]:
    rules = rules_for(kind)
    if mode == "required":
        rules = [r for r in rules if r["level"] == "required"]
        if not rules:
            return dict(_NOTHING)
    if mode not in MODES:
        raise ValueError(f"unknown completeness mode {mode!r}")
    incomplete = {"OR": [_missing_clause(kind, rule) for rule in rules]}
    if mode == "complete":
        return {"NOT": incomplete}
    return incomplete


def parse_mode(value: str | None) -> str | None:
    if value is None or value == "":
        return None
    if value not in MODES:
        from fastapi import HTTPException, status

        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"completeness must be one of {', '.join(MODES)}",
        )
    return value


def add_filter(where: dict[str, Any], kind: str, value: str | None) -> None:
    mode = parse_mode(value)
    if mode is None:
        return
    existing = where.get("AND")
    if existing is None:
        where["AND"] = [missing_where(kind, mode)]
    elif isinstance(existing, list):
        existing.append(missing_where(kind, mode))
    else:
        where["AND"] = [existing, missing_where(kind, mode)]



def _group_count(group: Any) -> int:
    count = group.get("_count") if isinstance(group, dict) else getattr(group, "_count", None)
    if isinstance(count, dict):
        value = count.get("_all", 0)
    else:
        value = count or 0
    return int(value or 0)


async def _query_source(client: Any, source: CountSource, ids: list[str]) -> dict[str, int]:
    where = {source.fk: {"in": ids}, **source.where}
    groups = await getattr(client, source.model).group_by(by=[source.fk], where=where, count=True)
    return {group[source.fk]: _group_count(group) for group in groups if group.get(source.fk)}


async def counts_for(kind: str, ids: Iterable[str], client: Any = None) -> dict[str, dict[str, int]]:
    client = db if client is None else client
    unique = sorted({i for i in ids if i})
    result: dict[str, dict[str, int]] = {i: {} for i in unique}
    names = count_names(kind)
    if not unique or not names:
        return result
    plan = [(name, source) for name in names for source in COUNT_SOURCES[(kind, name)]]
    fetched = await gather_reads(*(_query_source(client, source, unique) for _, source in plan))
    for (name, _), by_id in zip(plan, fetched):
        for record_id in unique:
            result[record_id][name] = max(result[record_id].get(name, 0), by_id.get(record_id, 0))
    await _add_session_answers(kind, result, client)
    return result


async def _add_session_answers(kind: str, counts: dict[str, dict[str, int]], client: Any) -> None:
    if kind != "interview" or "answers" not in count_names(kind):
        return
    from app.services import questionnaire_readers

    for session_id, n in (await questionnaire_readers.answer_counts(list(counts), client=client)).items():
        if session_id in counts:
            counts[session_id]["answers"] = counts[session_id].get("answers", 0) + n


def _count_from_payload(kind: str, name: str, item: dict[str, Any], key: str) -> int:
    rows = item.get(key) or []
    keep = _PAYLOAD_FILTERS.get((kind, name))
    return sum(1 for row in rows if isinstance(row, dict) and (keep is None or keep(row)))


async def attach(kind: str, payload: Any, client: Any = None) -> Any:
    client = db if client is None else client
    items = payload if isinstance(payload, list) else [payload]
    records = [item for item in items if isinstance(item, dict) and item.get("id")]
    if not records:
        return payload
    ids = [item["id"] for item in records]
    counts: dict[str, dict[str, int]] = {i: {} for i in ids}
    queried: list[tuple[str, CountSource]] = []
    for name in count_names(kind):
        for source in COUNT_SOURCES[(kind, name)]:
            key = source.payload_key
            if key and all(isinstance(item.get(key), list) for item in records):
                for item in records:
                    value = _count_from_payload(kind, name, item, key)
                    counts[item["id"]][name] = max(counts[item["id"]].get(name, 0), value)
            else:
                queried.append((name, source))
    if queried:
        unique = sorted(set(ids))
        try:
            fetched = await gather_reads(*(_query_source(client, source, unique) for _, source in queried))
        except Exception:  # noqa: BLE001
            return payload
        for (name, _), by_id in zip(queried, fetched):
            for record_id in ids:
                counts[record_id][name] = max(counts[record_id].get(name, 0), by_id.get(record_id, 0))
    try:
        await _add_session_answers(kind, counts, client)
    except Exception:  # noqa: BLE001
        pass
    for item in records:
        item["completeness"] = evaluate(kind, item, counts[item["id"]])
    return payload
