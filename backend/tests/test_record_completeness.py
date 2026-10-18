"""RECORD COMPLETENESS — one rules file, three evaluators, one list filter (feature 8, 2026-10-18).

``shared/record-completeness.json`` declares which fields each record kind should carry. This file
holds:

* the server's evaluator and sentence to the file's own vectors;
* THE KEY TEST: the Prisma clause behind ``completeness=`` agrees with the evaluator row for row, for
  every kind and every mode, over generated rows run through a small interpreter of the clause;
* the rules to their limits: a ``required`` rule is only something the artisan form already refuses
  to save without, and no rule names a prose column, a consent-based personal column or a column the
  schema does not have;
* the page counter to one ``group_by`` per count source;
* the copies (backend, web, Android, the sibling portal) to the same bytes;
* the routes: the filter on every list, 422 on a bad value, the block on GET / POST / PATCH, the same
  block for a masked viewer, and the rules endpoint's ETag.

No database: the routes run against in-memory delegates that answer ``where`` clauses with the same
interpreter. ``test_the_clauses_run_on_postgres`` runs the clauses on a real database when
``RECORD_COMPLETENESS_DATABASE_URL`` names a throwaway one.
"""

from __future__ import annotations

import asyncio
import itertools
import json
import os
import pathlib
import random
import re
import sys
from types import SimpleNamespace
from typing import Any, Callable

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.core import deps
from app.services import artisan_form_contract as afc
from app.services import completeness as rc

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "shared" / "record-completeness.json"
COPIES = (
    ROOT / "backend" / "app" / "services" / "record-completeness.json",
    ROOT / "frontend" / "lib" / "generated" / "record-completeness.json",
    ROOT / "android" / "app" / "src" / "main" / "assets" / "record-completeness.json",
)


def _text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The evaluator and the sentence
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_the_shared_vectors_pass_through_evaluate_and_sentence():
    vectors = rc.RULES["vectors"]
    assert vectors, "a vector table that failed to load would pass vacuously"
    for vector in vectors:
        block = rc.evaluate(vector["kind"], vector["record"], vector["counts"])
        expect = vector["expect"]
        assert block["filled"] == expect["filled"]
        assert block["total"] == expect["total"]
        assert [item["key"] for item in block["missing"]] == expect["missing"]
        assert block["requiredMissing"] == expect["requiredMissing"]
        assert rc.sentence(block) == expect["sentence"]


#: Extra vectors this suite runs on all three ports (web and Android carry the same table).
EXTRA_VECTORS = [
    ("artisan", {}, {}, 0, ["dateOfBirth", "craftStartDate", "craft", "dos", "donts", "location", "photo"],
     "7 fields left: date of birth, craft start date, craft, +4 more"),
    ("artisan", {"dateOfBirth": "1971-03-04", "craftStartDate": "1994-06-01", "craftId": "c", "dos": "a",
                 "donts": "b", "locationId": "l"}, {"photos": 2}, 100, [], "Complete"),
    ("artisan", {"dateOfBirth": "1971-03-04", "craftStartDate": "  ", "craftId": "c", "dos": "a",
                 "donts": "b", "locationId": "l"}, {"photos": 1}, 85, ["craftStartDate"],
     "1 field left: craft start date"),
    ("tool", {"artisanId": "a", "localName": "x", "material": "iron", "lengthInches": 0, "maker": "ARTISAN",
              "traditionType": "UNKNOWN", "replacementCost": "120.00"}, {"photos": 1}, 87, ["traditionType"],
     "1 field left: tradition"),
    ("product", {"artisanId": "a", "marketDemand": "UNKNOWN"}, {"photos": 0}, 14,
     ["sellingPrice", "costOfMaking", "timeTaken", "dimensions", "marketDemand", "photo"],
     "6 fields left: product price, cost of making, time taken, +3 more"),
    ("interview", {"interviewDate": "2026-10-01", "place": "Bagru", "language": "Hindi"},
     {"artisans": 1, "answers": 0, "recordings": 1}, 83, ["answers"], "1 field left: answers"),
    ("workshop", {"startDate": "2026-10-01", "endDate": None}, {"artisans": 0, "crafts": 3, "photos": 1}, 60,
     ["endDate", "artisans"], "2 fields left: end date, artisans"),
]


@pytest.mark.parametrize("kind,record,counts,percent,missing,text", EXTRA_VECTORS)
def test_extra_vectors(kind, record, counts, percent, missing, text):
    block = rc.evaluate(kind, record, counts)
    assert block["percent"] == percent
    assert [item["key"] for item in block["missing"]] == missing
    assert rc.sentence(block) == text
    assert block["rulesVersion"] == rc.RULES_VERSION


def test_percent_rounds_down_as_both_portals_and_their_clients_do():
    # Integer division in every port and in the designer portal's module: 7 of 8 is 87, never 88,
    # so one record never reads 87% in one portal and 88% in the other.
    assert rc.percent_of(1, 8) == 12
    assert rc.percent_of(7, 8) == 87
    assert rc.percent_of(5, 7) == 71
    assert rc.percent_of(0, 7) == 0
    assert rc.percent_of(7, 7) == 100


def test_required_items_come_first_then_file_order():
    block = rc.evaluate("artisan", {"craftId": None, "dos": "a", "donts": "b"}, {"photos": 0})
    keys = [item["key"] for item in block["missing"]]
    assert keys == ["dateOfBirth", "craftStartDate", "craft", "location", "photo"]
    assert [item["level"] for item in block["missing"]][:3] == ["required"] * 3


def test_the_block_carries_names_never_values():
    secret = "SECRET-VALUE-9012"
    record = {"aadhaarNumber": secret, "phone": secret, "dos": secret, "craftId": secret}
    block = rc.evaluate("artisan", record, {})
    assert secret not in json.dumps(block)
    for item in block["missing"]:
        assert set(item) == {"key", "label", "words", "level"}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The filter agrees with the evaluator, row for row
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _cmp_value(value: Any) -> Any:
    return getattr(value, "value", value)


def matches(row: dict[str, Any], where: dict[str, Any] | list[Any]) -> bool:
    """A small, SQL-faithful interpreter of the Prisma ``where`` shapes the module emits."""
    if isinstance(where, list):
        return all(matches(row, part) for part in where)
    for key, cond in where.items():
        if key == "AND":
            parts = cond if isinstance(cond, list) else [cond]
            if not all(matches(row, part) for part in parts):
                return False
        elif key == "OR":
            if not any(matches(row, part) for part in cond):
                return False
        elif key == "NOT":
            parts = cond if isinstance(cond, list) else [cond]
            if any(matches(row, part) for part in parts):
                return False
        elif isinstance(cond, dict) and ({"none", "some", "every"} & set(cond)):
            children = row.get(key) or []
            if "none" in cond and any(matches(child, cond["none"]) for child in children):
                return False
            if "some" in cond and not any(matches(child, cond["some"]) for child in children):
                return False
        elif cond is None:
            if row.get(key) is not None:
                return False
        elif isinstance(cond, dict):
            value = _cmp_value(row.get(key))
            for op, arg in cond.items():
                if value is None:  # SQL: NULL compared with anything is not true
                    return False
                if op == "in" and value not in arg:
                    return False
                if op == "not" and value == arg:
                    return False
                if op == "equals" and value != arg:
                    return False
        else:
            value = _cmp_value(row.get(key))
            if value is None or value != cond:
                return False
    return True


def counts_of(kind: str, row: dict[str, Any]) -> dict[str, int]:
    """The ``counts`` section of the rules file, computed from a row's relations — independently of
    the module, so a wrong relation clause cannot agree with itself."""
    media = row.get("media") or []
    live = [m for m in media if m.get("status") != "REJECTED"]
    counts = {"photos": sum(1 for m in live if m.get("mediaType") == "IMAGE")}
    if kind == "interview":
        counts["recordings"] = sum(1 for m in live if m.get("mediaType") in ("AUDIO", "VIDEO"))
        counts["artisans"] = len(row.get("artisans") or [])
        counts["answers"] = len(row.get("responses") or [])
    if kind == "workshop":
        counts["artisans"] = max(len(row.get("artisans") or []), len(row.get("artisansLinked") or []))
        counts["crafts"] = max(len(row.get("crafts") or []), len(row.get("craftsLinked") or []))
    return counts


MEDIA_VARIANTS = [
    [],
    [{"mediaType": "IMAGE", "status": "REJECTED"}],
    [{"mediaType": "IMAGE", "status": "PENDING"}],
    [{"mediaType": "AUDIO", "status": "APPROVED"}],
    [{"mediaType": "VIDEO", "status": "REJECTED"}, {"mediaType": "VIDEO", "status": "DRAFT"}],
    [{"mediaType": "PDF", "status": "APPROVED"}],
]


def _variants(kind: str, rule: dict[str, Any]) -> list[dict[str, Any]]:
    check = rule["check"]
    if check == "present":
        path = rule["path"]
        if (kind, path) in rc.STRING_COLUMNS:
            return [{path: None}, {path: ""}, {path: "x"}]
        return [{path: None}, {path: "2026-01-01"}, {path: 0}]
    if check == "notIn":
        return [{rule["path"]: value} for value in rule["empty"]] + [{rule["path"]: "OTHER"}]
    if check == "anyPresent":
        out = [{p: None for p in rule["paths"]}]
        for p in rule["paths"]:
            out.append({**{q: None for q in rule["paths"]}, p: 1.5})
        return out
    of = rule["of"]
    if of in ("photos", "recordings"):
        return [{"media": v} for v in MEDIA_VARIANTS]
    if kind == "workshop" and of in ("artisans", "crafts"):
        linked = f"{of}Linked"
        return [{of: a, linked: b} for a, b in itertools.product([[], [{}]], [[], [{}]])]
    relation = "responses" if of == "answers" else of
    return [{relation: []}, {relation: [{}]}]


def _rows(kind: str, n: int = 600) -> list[dict[str, Any]]:
    rng = random.Random(f"completeness-{kind}")
    choices = [_variants(kind, rule) for rule in rc.rules_for(kind)]
    rows = []
    for _ in range(n):
        row: dict[str, Any] = {"id": f"r{len(rows)}"}
        for options in choices:
            pick = rng.choice(options)
            for key, value in pick.items():
                if key == "media" and "media" in row:
                    row["media"] = row["media"] + value
                else:
                    row[key] = value
        rows.append(row)
    # The two ends, explicitly.
    rows.append({"id": "empty"})
    full: dict[str, Any] = {"id": "full"}
    for options in choices:
        full.update(options[-1])
    full["media"] = [{"mediaType": "IMAGE", "status": "APPROVED"}, {"mediaType": "AUDIO", "status": "APPROVED"}]
    rows.append(full)
    return rows


@pytest.mark.parametrize("kind", rc.KINDS)
@pytest.mark.parametrize("mode", rc.MODES)
def test_the_filter_agrees_with_the_meter_row_for_row(kind, mode):
    clause = rc.missing_where(kind, mode)
    seen = set()
    for row in _rows(kind):
        block = rc.evaluate(kind, row, counts_of(kind, row))
        expected = {
            "incomplete": bool(block["missing"]),
            "required": block["requiredMissing"] > 0,
            "complete": not block["missing"],
        }[mode]
        assert matches(row, clause) == expected, (kind, mode, row, block)
        seen.add(expected)
    if mode != "required" or any(r["level"] == "required" for r in rc.rules_for(kind)):
        assert seen == {True, False}, "the generated rows never exercised both answers"


def test_required_on_a_kind_without_required_rules_matches_nothing():
    assert not matches({"id": "x"}, rc.missing_where("product", "required"))


def test_a_rejected_image_is_not_a_photo_in_either_half():
    row = {"id": "a", "media": [{"mediaType": "IMAGE", "status": "REJECTED"}]}
    block = rc.evaluate("artisan", row, counts_of("artisan", row))
    assert "photo" in [item["key"] for item in block["missing"]]
    assert matches(row, rc.COUNT_CLAUSES[("artisan", "photos")])


def test_an_unknown_mode_is_refused():
    with pytest.raises(ValueError):
        rc.missing_where("artisan", "nearly")
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as caught:
        rc.parse_mode("nearly")
    assert caught.value.status_code == 422
    assert rc.parse_mode(None) is None and rc.parse_mode("") is None


def test_add_filter_joins_the_and_list_and_never_replaces_it():
    where: dict[str, Any] = {"AND": [{"vis": True}], "OR": [{"title": "x"}]}
    rc.add_filter(where, "tool", "incomplete")
    assert where["AND"][0] == {"vis": True} and len(where["AND"]) == 2
    assert where["OR"] == [{"title": "x"}]
    bare: dict[str, Any] = {}
    rc.add_filter(bare, "tool", None)
    assert bare == {}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The rules' limits
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_every_required_rule_is_something_the_artisan_form_already_refuses():
    contract = json.loads(_text(ROOT / "shared" / "artisan-form-contract.json"))
    contract_dates = {d["field"] for d in contract["requiredDates"]}
    assert contract_dates == {field for field, _ in afc.REQUIRED_DATES}
    schemas = _text(ROOT / "backend" / "app" / "schemas" / "records.py")
    web_form = _text(ROOT / "frontend" / "components" / "forms" / "ArtisanForm.tsx")
    android = "\n".join(
        _text(p) for p in (ROOT / "android" / "app" / "src" / "main" / "java").rglob("MainActivity.kt")
    )
    validators = {
        "craftId": "def require_craft(" in schemas,
        "dos": 'requiredText(form, "dos")' in web_form and "RequiredCheck(dosText.isBlank()" in android,
        "donts": 'requiredText(form, "donts")' in web_form and "RequiredCheck(dontsText.isBlank()" in android,
    }
    for kind in rc.KINDS:
        for rule in rc.rules_for(kind):
            if rule["level"] != "required":
                continue
            assert kind == "artisan", f"{kind}.{rule['key']} is required but no {kind} form refuses it"
            path = rule["path"]
            if rule.get("source") == "artisan-form-contract":
                assert path in contract_dates, rule
            else:
                assert validators.get(path), f"artisan.{path} is required but no validator refuses it"


PROSE = {"notes", "rawMaterialsUsed", "mainToolsUsed", "processUsedIn", "remarks", "description"}
PERSONAL = {"aadhaarNumber", "pehchanCardNumber", "pehchanCardAvailable", "phone", "email", "address", "gender"}
MODEL = {
    "artisan": "Artisan",
    "product": "ProductDocumentation",
    "tool": "ToolDocumentation",
    "interview": "QuestionnaireInterview",
    "workshop": "Workshop",
}


def _columns(model: str) -> dict[str, str]:
    schema = _text(ROOT / "backend" / "prisma" / "schema.prisma")
    body = re.search(r"^model " + model + r" \{\n(.*?)^\}", schema, re.S | re.M)
    assert body, model
    columns = {}
    for line in body.group(1).splitlines():
        parts = line.split()
        if len(parts) >= 2 and not parts[0].startswith(("//", "@@")):
            columns[parts[0]] = parts[1]
    return columns


@pytest.mark.parametrize("kind", rc.KINDS)
def test_no_rule_names_prose_personal_or_missing_columns(kind):
    columns = _columns(MODEL[kind])
    for rule in rc.rules_for(kind):
        paths = rule.get("paths") or ([rule["path"]] if "path" in rule else [])
        for path in paths:
            assert path not in PROSE, f"{kind}.{path} is a prose column"
            assert path not in PERSONAL, f"{kind}.{path} is consent-based personal data"
            assert path in columns, f"{kind}.{path} is not a column of {MODEL[kind]}"
            is_string = columns[path].rstrip("?") == "String" and not path.endswith("Id")
            if rule["check"] == "present":
                assert ((kind, path) in rc.STRING_COLUMNS) == is_string, f"{kind}.{path} string arm"
        if rule["check"] == "count":
            assert rule["min"] == 1
            assert (kind, rule["of"]) in rc.COUNT_CLAUSES
            assert (kind, rule["of"]) in rc.COUNT_SOURCES
            assert rule["of"] in rc.RULES["counts"]
        assert rule["check"] in rc.RULES["checks"]


def test_relation_clauses_name_real_relations():
    for (kind, _), clause in rc.COUNT_CLAUSES.items():
        columns = _columns(MODEL[kind])
        for part in clause.get("AND", [clause]):
            for relation in part:
                assert relation in columns, f"{MODEL[kind]} has no relation {relation}"


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Counting a page
# ──────────────────────────────────────────────────────────────────────────────────────────────


class _Grouping:
    def __init__(self, rows: list[dict[str, Any]], calls: list[Any]) -> None:
        self.rows = rows
        self.calls = calls

    async def group_by(self, by: list[str], where: dict[str, Any], count: bool = False) -> list[dict]:
        self.calls.append((by, where))
        key = by[0]
        tally: dict[str, int] = {}
        for row in self.rows:
            if row.get(key) is not None and matches(row, where):
                tally[row[key]] = tally.get(row[key], 0) + 1
        return [{key: k, "_count": {"_all": v}} for k, v in tally.items()]


def _fake_db(monkeypatch: pytest.MonkeyPatch, **delegates: Any) -> SimpleNamespace:
    fake = SimpleNamespace(**delegates)
    real = core_db.db
    monkeypatch.setattr(core_db, "db", fake)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real:
            monkeypatch.setattr(module, "db", fake)
    return fake


@pytest.mark.parametrize("kind", rc.KINDS)
def test_counts_for_issues_one_query_per_source_for_a_page_of_100(kind, monkeypatch):
    calls: list[Any] = []
    media = [
        {"artisanId": "id0", "productId": "id0", "toolId": "id0", "workshopId": "id0",
         "questionnaireInterviewId": "id0", "mediaType": "IMAGE", "status": "APPROVED"},
        {"artisanId": "id1", "productId": "id1", "toolId": "id1", "workshopId": "id1",
         "questionnaireInterviewId": "id1", "mediaType": "IMAGE", "status": "REJECTED"},
        {"questionnaireInterviewId": "id0", "mediaType": "AUDIO", "status": "PENDING"},
    ]
    links = [{"interviewId": "id0", "workshopId": "id0"}]
    _fake_db(
        monkeypatch,
        mediafile=_Grouping(media, calls),
        questionnaireinterviewartisan=_Grouping(links, calls),
        questionnaireresponse=_Grouping(links, calls),
        workshopartisan=_Grouping([], calls),
        artisan=_Grouping(links, calls),
        workshopcraft=_Grouping(links, calls),
        craft=_Grouping([], calls),
    )
    ids = [f"id{i}" for i in range(100)]
    counts = asyncio.run(rc.counts_for(kind, ids))
    sources = sum(len(rc.COUNT_SOURCES[(kind, name)]) for name in rc.count_names(kind))
    assert len(calls) == sources
    assert set(counts) == set(ids)
    if "photos" in rc.count_names(kind):
        assert counts["id0"]["photos"] == 1
        assert counts["id1"]["photos"] == 0  # the REJECTED image
    if kind == "interview":
        assert counts["id0"] == {"artisans": 1, "answers": 1, "recordings": 1}
    if kind == "workshop":
        assert counts["id0"]["artisans"] == 1 and counts["id0"]["crafts"] == 1


def test_attach_counts_from_a_loaded_relation_without_a_query(monkeypatch):
    calls: list[Any] = []
    _fake_db(monkeypatch, mediafile=_Grouping([], calls))
    items = [{"id": "p1", "media": [{"mediaType": "IMAGE", "status": "APPROVED"}], "marketDemand": "HIGH"}]
    asyncio.run(rc.attach("product", items))
    assert calls == []
    assert "photo" not in [m["key"] for m in items[0]["completeness"]["missing"]]


def test_attach_reads_through_the_routes_client_and_never_fails_the_answer():
    calls: list[Any] = []
    item = {"id": "a1", "craftId": "c1"}
    asyncio.run(rc.attach("artisan", item, client=SimpleNamespace(mediafile=_Grouping([], calls))))
    assert calls and item["completeness"]["total"] == 7

    class _Down:
        async def group_by(self, **_: Any) -> Any:
            raise RuntimeError("engine gone")

    written = {"id": "a2", "craftId": "c1"}
    assert asyncio.run(rc.attach("artisan", written, client=SimpleNamespace(mediafile=_Down()))) is written
    assert "completeness" not in written


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The copies
# ──────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("copy", COPIES, ids=lambda p: str(p.relative_to(ROOT)))
def test_every_copy_is_the_shared_file(copy):
    assert copy.is_file(), f"{copy} is missing"
    assert _text(copy) == _text(SHARED), f"{copy.relative_to(ROOT)} differs from shared/"


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if any(ROOT.glob("android/app/src/main/java/com/designprototype")) else "designer-portal"
    short = "doc" if other == "documentation-portal" else "dp"
    candidates = [
        os.environ.get("SIBLING_REPO"),
        ROOT.parent / other,
        pathlib.Path("C:/dev/IIT") / other,
        pathlib.Path("C:/dev/IIT/wt") / f"{short}-f-complete",
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / "shared" / "record-completeness.json").is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_rules():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the rules (CI has one repository)")
    assert _text(sibling / "shared" / "record-completeness.json") == _text(SHARED)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The routes
# ──────────────────────────────────────────────────────────────────────────────────────────────

_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(method: str, url: str, **kwargs: Any) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://complete.test") as client:
            return await client.request(method, url, **kwargs)

    return asyncio.run(run())


class _Table:
    """An in-memory delegate: rows as dicts, answered with the interpreter above."""

    def __init__(self, rows: list[dict[str, Any]] | None = None, relations: Callable[[dict], dict] | None = None):
        self.rows = rows or []
        self.relations = relations or (lambda row: {})
        self.wheres: list[dict] = []

    def _view(self, row: dict[str, Any]) -> dict[str, Any]:
        return {**row, **self.relations(row)}

    def _filter(self, where: dict | None) -> list[dict[str, Any]]:
        self.wheres.append(where or {})
        return [row for row in self.rows if matches(self._view(row), where or {})]

    async def count(self, where: dict | None = None) -> int:
        return len(self._filter(where))

    async def find_many(self, where: dict | None = None, skip: int = 0, take: int | None = None, **_: Any) -> list:
        found = self._filter(where)[skip:]
        return [SimpleNamespace(**row) for row in (found[:take] if take else found)]

    async def find_unique(self, where: dict, **_: Any) -> Any:
        found = self._filter(where)
        return SimpleNamespace(**found[0]) if found else None

    async def find_first(self, where: dict | None = None, **_: Any) -> Any:
        found = self._filter(where)
        return SimpleNamespace(**found[0]) if found else None

    async def create(self, data: dict, **_: Any) -> Any:
        row = {"id": f"new{len(self.rows)}", **ARTISAN_DEFAULTS, **data}
        self.rows.append(row)
        return SimpleNamespace(**row)

    async def update(self, where: dict, data: dict, **_: Any) -> Any:
        row = self._filter(where)[0]
        row.update(data)
        return SimpleNamespace(**row)

    async def group_by(self, by: list[str], where: dict, count: bool = False) -> list[dict]:
        key = by[0]
        tally: dict[str, int] = {}
        for row in self.rows:
            if row.get(key) is not None and matches(row, where):
                tally[row[key]] = tally.get(row[key], 0) + 1
        return [{key: k, "_count": {"_all": v}} for k, v in tally.items()]


AADHAAR = "234567890124"
ARTISAN_DEFAULTS = {
    "name": "Kalu Ram", "localName": None, "gender": "Male", "phone": "9876543210", "email": None,
    "place": "Bagru", "aadhaarNumber": None, "pehchanCardAvailable": False, "pehchanCardNumber": None,
    "address": None, "notes": None, "dateOfBirth": None, "craftStartDate": None, "experienceYears": None,
    "experienceMonths": None, "dos": None, "donts": None, "status": "APPROVED", "extraMetadata": {},
    "craftId": None, "locationId": None, "createdById": "owner", "workshopId": None, "createdAt": None,
    "updatedAt": None,
}


@pytest.fixture
def artisans(monkeypatch: pytest.MonkeyPatch):
    media = _Table([
        {"id": "m1", "artisanId": "complete", "mediaType": "IMAGE", "status": "APPROVED"},
        {"id": "m2", "artisanId": "rejected", "mediaType": "IMAGE", "status": "REJECTED"},
    ])
    done = {"dateOfBirth": "1971-03-04T00:00:00", "craftStartDate": "1994-06-01T00:00:00", "craftId": "c1",
            "dos": "Greet first", "donts": "No flash", "locationId": "l1"}
    rows = [
        {**ARTISAN_DEFAULTS, "id": "complete", **done, "aadhaarNumber": AADHAAR},
        {**ARTISAN_DEFAULTS, "id": "nodate", **done, "craftStartDate": None},
        {**ARTISAN_DEFAULTS, "id": "rejected", **done},
    ]
    table = _Table(rows, lambda row: {"media": [m for m in media.rows if m["artisanId"] == row["id"]]})
    grant = SimpleNamespace(status="GRANTED", tier="EDIT", allData=True, scopeItems=[])

    async def find_grant(**_: Any) -> Any:
        return grant

    _fake_db(
        monkeypatch,
        artisan=table,
        mediafile=media,
        craft=_Table(),
        location=_Table(),
        user=_Table(),
        workshop=_Table(),
        workshopartisan=_Table(),
        recordrevision=_Table(),
        dataaccessgrant=SimpleNamespace(find_unique=find_grant),
    )
    _CURRENT["user"] = SimpleNamespace(
        id="owner", email="owner@example.test", name="Owner", role="ADMIN",
        canDownloadDataset=True, canReview=True,
    )
    yield table
    _CURRENT["user"] = None


def _by_id(response: httpx.Response) -> dict[str, dict]:
    assert response.status_code == 200, response.text
    return {item["id"]: item for item in response.json()["items"]}


def test_an_artisan_missing_craft_start_date_is_listed_under_required(artisans):
    required = _by_id(_call("GET", "/api/artisans", params={"completeness": "required"}))
    assert set(required) == {"nodate"}
    assert required["nodate"]["completeness"]["missing"][0]["key"] == "craftStartDate"
    complete = _by_id(_call("GET", "/api/artisans", params={"completeness": "complete"}))
    assert set(complete) == {"complete"}
    incomplete = _by_id(_call("GET", "/api/artisans", params={"completeness": "incomplete"}))
    assert set(incomplete) == {"nodate", "rejected"}


def test_a_rejected_image_does_not_count_as_a_photo(artisans):
    items = _by_id(_call("GET", "/api/artisans"))
    assert [m["key"] for m in items["rejected"]["completeness"]["missing"]] == ["photo"]
    assert items["complete"]["completeness"]["missing"] == []
    assert items["complete"]["completeness"]["percent"] == 100


def test_completeness_rides_on_get_post_and_patch(artisans):
    got = _call("GET", "/api/artisans/nodate")
    assert got.status_code == 200 and got.json()["completeness"]["requiredMissing"] == 1
    patched = _call("PATCH", "/api/artisans/nodate", json={"craftStartDate": "1990-01-01T00:00:00"})
    assert patched.status_code == 200, patched.text
    assert patched.json()["completeness"]["requiredMissing"] == 0
    created = _call(
        "POST",
        "/api/artisans",
        json={
            "name": "Asha", "place": "Bagru", "craftId": "c1", "aadhaarNumber": "345678901238",
            "dos": "Greet first", "donts": "No flash",
            "location": {"latitude": 26.82, "longitude": 75.54, "state": "Rajasthan", "district": "Jaipur"},
        },
    )
    assert created.status_code == 201, created.text
    block = created.json()["completeness"]
    assert block["requiredMissing"] == 2  # both dates: a body without formContract is the old shape
    assert block["total"] == 7


def test_a_masked_viewer_gets_the_same_block_and_no_values(artisans):
    as_admin = _by_id(_call("GET", "/api/artisans"))
    _CURRENT["user"] = SimpleNamespace(
        id="someone", email="r@example.test", name="R", role="RESEARCHER", canDownloadDataset=False,
        canReview=False,
    )
    as_researcher = _by_id(_call("GET", "/api/artisans"))
    assert as_researcher["complete"]["aadhaarNumber"] != AADHAAR  # masked for this viewer
    for record_id in as_admin:
        assert as_admin[record_id]["completeness"] == as_researcher[record_id]["completeness"]
        assert AADHAAR not in json.dumps(as_admin[record_id]["completeness"])


LIST_ROUTES = {
    "/api/artisans": ("artisan", "artisan"),
    "/api/products": ("productdocumentation", "product"),
    "/api/tools": ("tooldocumentation", "tool"),
    "/api/workshops": ("workshop", "workshop"),
    "/api/questionnaire/interviews": ("questionnaireinterview", "interview"),
}


@pytest.fixture
def empty_lists(monkeypatch: pytest.MonkeyPatch):
    tables = {name: _Table() for name, _ in LIST_ROUTES.values()}
    _fake_db(monkeypatch, **tables)
    _CURRENT["user"] = SimpleNamespace(id="u", email="u@example.test", name="U", role="RESEARCHER",
                                       canDownloadDataset=False, canReview=False)
    yield tables
    _CURRENT["user"] = None


@pytest.mark.parametrize("path", list(LIST_ROUTES))
def test_every_list_route_takes_the_filter_and_refuses_a_bad_value(empty_lists, path):
    table_name, kind = LIST_ROUTES[path]
    bad = _call("GET", path, params={"completeness": "mostly"})
    assert bad.status_code == 422
    for mode in rc.MODES:
        ok = _call("GET", path, params={"completeness": mode})
        assert ok.status_code == 200, ok.text
        where = empty_lists[table_name].wheres[-1]
        assert rc.missing_where(kind, mode) in where.get("AND", []), where


def test_the_rules_endpoint_answers_304_on_a_matching_etag():
    _CURRENT["user"] = SimpleNamespace(id="u", role="RESEARCHER")
    try:
        first = _call("GET", "/api/records/completeness-rules")
        assert first.status_code == 200
        assert first.content == rc.RULES_BYTES
        etag = first.headers["etag"]
        assert re.fullmatch(r'"1-[0-9a-f]{8}"', etag)
        again = _call("GET", "/api/records/completeness-rules", headers={"If-None-Match": etag})
        assert again.status_code == 304 and again.content == b""
        stale = _call("GET", "/api/records/completeness-rules", headers={"If-None-Match": '"0-deadbeef"'})
        assert stale.status_code == 200
    finally:
        _CURRENT["user"] = None


def test_the_rules_endpoint_requires_sign_in():
    signed_out = FastAPI()
    signed_out.include_router(api_router)

    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=signed_out)
        async with httpx.AsyncClient(transport=transport, base_url="http://complete.test") as client:
            return await client.get("/api/records/completeness-rules")

    assert asyncio.run(run()).status_code in (401, 403)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# On a real database, when one is named
# ──────────────────────────────────────────────────────────────────────────────────────────────

DB_URL = os.environ.get("RECORD_COMPLETENESS_DATABASE_URL")


@pytest.mark.skipif(not DB_URL, reason="set RECORD_COMPLETENESS_DATABASE_URL to a throwaway database")
def test_the_clauses_run_on_postgres():
    """Every clause and every count source, executed by Prisma against a migrated, throwaway DB."""
    from prisma import Prisma

    async def run() -> None:
        client = Prisma(datasource={"url": DB_URL})
        await client.connect()
        try:
            delegates = {
                "artisan": client.artisan,
                "product": client.productdocumentation,
                "tool": client.tooldocumentation,
                "workshop": client.workshop,
                "interview": client.questionnaireinterview,
            }
            for kind, delegate in delegates.items():
                total = await delegate.count()
                split = []
                for mode in rc.MODES:
                    split.append(await delegate.count(where={"AND": [rc.missing_where(kind, mode)]}))
                incomplete, _required, complete = split
                assert incomplete + complete == total, kind
            for kind in rc.KINDS:
                await rc.counts_for(kind, ["nothing-here"], client=client)
        finally:
            await client.disconnect()

    asyncio.run(run())
