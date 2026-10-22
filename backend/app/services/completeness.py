"""Record completeness: which fields a record still lacks, computed on read and never stored.

── WHAT IT IS (feature 8, 2026-10-18) ───────────────────────────────────────────────────────────

Every artisan, product, tool, workshop and interview answer carries a ``completeness`` block: how many
of the rules in ``shared/record-completeness.json`` the record meets, and which ones it misses, by
field NAME only. Never a value: the block is attached after masking and reads only the columns the
rules name, none of which is an identity number, a phone, an email, an address or a gender.

── ONE FILE, THREE PORTS, ONE FILTER ────────────────────────────────────────────────────────────

The rules file is byte-identical in both portals and is copied, byte for byte, into the web bundle
(``frontend/lib/generated``) and the Android assets. The web and Android evaluators are
character-for-character ports of :func:`evaluate` and :func:`sentence` so a record judged offline
reads the same as one judged here. :func:`missing_where` turns the SAME rules into the Prisma clause
behind the lists' ``completeness=`` filter, and ``tests/test_record_completeness.py`` holds the two
to agreeing row for row. That agreement is why no rule may name a prose (rich-text) column: an
"empty" paragraph can be markup, which SQL cannot see.

── WHAT IT DOES NOT DO ──────────────────────────────────────────────────────────────────────────

It enforces nothing. A ``required`` rule only REPORTS what the artisan form already refuses to save
without (``artisan_form_contract.REQUIRED_DATES``, ``ArtisanCreate.require_craft``, the form's Do's
and Don'ts). Saves keep their own rules; old records still load and save as before.

The designer portal carries the same module under the same name; change both.
"""

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

#: The list filter's three values. Anything else is a 422 at the route.
MODES: tuple[str, ...] = ("incomplete", "required", "complete")


def _rules_path() -> pathlib.Path:
    """The backend's own copy, beside this module.

    ``shared/record-completeness.json`` is the source; the deploy ships ``backend/`` alone, so the
    API reads the byte-identical copy next to this file (``tests/test_record_completeness.py`` holds
    the two equal).
    """
    return pathlib.Path(__file__).resolve().with_name("record-completeness.json")


RULES_PATH = _rules_path()
#: The file as bytes, served by ``GET /records/completeness-rules``. Line endings are normalised to LF
#: so a Windows checkout (``* text=auto``) serves the same bytes, and the same ETag, as the box.
RULES_BYTES: bytes = RULES_PATH.read_bytes().replace(b"\r\n", b"\n")
RULES: dict[str, Any] = json.loads(RULES_BYTES.decode("utf-8"))
RULES_VERSION: int = int(RULES["version"])
#: ``"<version>-<first 8 hex of sha256>"``, quoted as an HTTP entity tag.
RULES_ETAG: str = f'"{RULES_VERSION}-{hashlib.sha256(RULES_BYTES).hexdigest()[:8]}"'

KINDS: tuple[str, ...] = tuple(RULES["kinds"].keys())

#: String columns, which also count as missing when blank. Every other ``present`` path is a date,
#: a number or a foreign key, where only NULL is empty.
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

# ``removedAt``: a soft-removed copy is not a photo or a recording the record has (audit M19).
_LIVE_IMAGE = {"mediaType": "IMAGE", "status": {"not": "REJECTED"}, "removedAt": None}
_LIVE_RECORDING = {"mediaType": {"in": ["AUDIO", "VIDEO"]}, "status": {"not": "REJECTED"}, "removedAt": None}

#: The ``count`` rules as relation clauses: each matches a record whose count is BELOW ``min`` (1).
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
    """One table a count reads from. A count with two sources is met when EITHER reaches ``min``."""

    model: str  # the Prisma delegate on ``db``
    fk: str  # the column holding the record's id
    where: dict[str, Any]
    #: The relation already on an encoded record that holds the same rows, so a page that loaded it
    #: needs no extra query. ``None`` where no route hydrates it.
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

#: How a hydrated relation row is filtered to match its source's ``where``.
_PAYLOAD_FILTERS = {
    ("product", "photos"): _media_filter(("IMAGE",)),
    ("tool", "photos"): _media_filter(("IMAGE",)),
    ("interview", "recordings"): _media_filter(("AUDIO", "VIDEO")),
}


def rules_for(kind: str) -> list[dict[str, Any]]:
    return list(RULES["kinds"][kind])


def count_names(kind: str) -> list[str]:
    """The ``of`` names a kind's rules read, in file order, without repeats."""
    names: list[str] = []
    for rule in rules_for(kind):
        if rule["check"] == "count" and rule["of"] not in names:
            names.append(rule["of"])
    return names


# ── Evaluating one record ─────────────────────────────────────────────────────────────────────


def is_present(value: Any) -> bool:
    """Not null, and not blank after trim for a string. ``0`` and ``False`` are present."""
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
    """Rounded DOWN, as the designer portal's module and both clients round: 7 of 8 is 87.

    Integer division is the one rounding all four ports spell the same way (``//``, ``Math.floor``,
    Kotlin ``Int / Int``), so a record never reads 87% here and 88% in the sibling portal.
    """
    if total <= 0:
        return 100
    return filled * 100 // total


def evaluate(kind: str, record: dict[str, Any], counts: dict[str, Any] | None = None) -> dict[str, Any]:
    """The response block for one record. ``record`` is read by the rule paths and nothing else."""
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
    """ "2 fields left: date of birth, photo" / "1 field left: photo" / "5 fields left: a, b, c, +2 more"."""
    missing = block.get("missing") or []
    count = len(missing)
    if count == 0:
        return "Complete"
    words = ", ".join(item["words"] for item in missing[:3])
    more = f", +{count - 3} more" if count > 3 else ""
    return f"{count} field{'' if count == 1 else 's'} left: {words}{more}"


# ── The list filter ───────────────────────────────────────────────────────────────────────────


def _missing_clause(kind: str, rule: dict[str, Any]) -> dict[str, Any]:
    """The Prisma clause that matches a record MISSING this rule."""
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


#: Matches nothing: what "a required item is missing" means for a kind with no required rules.
_NOTHING = {"id": {"in": []}}


def missing_where(kind: str, mode: str) -> dict[str, Any]:
    """The clause behind ``completeness=incomplete|required|complete`` on a list route."""
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
    """The route's query value, checked: ``None`` for absent, the mode, or a 422."""
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
    """AND the filter into ``where["AND"]`` — never straight into ``where``, which other keys share."""
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


# ── Counting, for a whole page at once ────────────────────────────────────────────────────────


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
    """``{id: {of: count}}`` for a page of records: one ``group_by`` per count source, all at once.

    ``client`` is the route's own Prisma client (the module global ``db`` when omitted).
    """
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
    """A shared-questionnaire session's answers count toward its ``answers`` (UNIFY C10); nothing,
    and no query, while v2 is off or for any other kind."""
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
    """Set ``completeness`` on an encoded record or a list of them, and return the same object.

    Call it AFTER ``public_encode`` (masking). A source whose relation the route already loaded is
    counted from the payload; every other source is one ``group_by`` for the whole page. ``client``
    is the route's own Prisma client, so a route and its meter read through the same connection.

    NEVER FAILS THE ANSWER IT RIDES ON. A POST or PATCH has already written by the time this runs; a
    500 over a read-only extra would make the client retry a write that landed. When a count cannot
    be read the record goes out without a block, and the clients fall back to their own evaluator.
    """
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
        except Exception:  # noqa: BLE001 - see the docstring: the meter never fails a read or a save
            return payload
        for (name, _), by_id in zip(queried, fetched):
            for record_id in ids:
                counts[record_id][name] = max(counts[record_id].get(name, 0), by_id.get(record_id, 0))
    try:
        await _add_session_answers(kind, counts, client)
    except Exception:  # noqa: BLE001 - the meter never fails a read or a save
        pass
    for item in records:
        item["completeness"] = evaluate(kind, item, counts[item["id"]])
    return payload
