import asyncio
from types import SimpleNamespace
from typing import Any

import pytest


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _Writes:
    def __init__(self, row: Any = None):
        self.updated: list[tuple[Any, Any]] = []
        self.created: list[Any] = []
        self.row = row

    async def update(self, where, data, **_kwargs):
        self.updated.append((where, data))
        return self.row if self.row is not None else _Row(id=where["id"], **data)

    async def create(self, data, **_kwargs):
        self.created.append(data)
        return self.row if self.row is not None else _Row(id="new_1", **data)

    async def find_unique(self, where, **_kwargs):
        return self.row if self.row is not None else _Row(id=where.get("id", "rec_1"))

    async def find_many(self, **_kwargs):
        return []

    async def count(self, **_kwargs):
        return 0


class _Client(SimpleNamespace):
    pass


class _Payload:
    def __init__(self, fields: dict[str, Any], **attributes):
        self._fields = dict(fields)
        self.dumped_with: dict[str, Any] = {}
        self.model_fields_set = set(fields)
        for name, value in attributes.items():
            setattr(self, name, value)

    def __getattr__(self, name):
        return self._fields.get(name)

    def model_dump(self, **kwargs):
        self.dumped_with = kwargs
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self._fields.items() if k not in excluded}


def _editor():
    return _Row(id="usr_7", name="R. Menon", role="RESEARCHER")


async def _privileged(_record, _user, _data, _kind, **_kwargs):
    return True


async def _no_status_policy(_user, _record, _data):
    return None


async def _no_relations(_rows, _relations):
    return None


async def _no_workshop_check(_user, _workshop_id):
    return None



def _drive_artisan(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import artisans

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _resolve_craft_id(data, _user, **_kwargs):
        return data

    async def _attach_location(data):
        return data

    async def _link(_workshop_id, _artisan_id):
        return None

    monkeypatch.setattr(artisans, "db", _Client(artisan=writes))
    monkeypatch.setattr(artisans, "require_record", _require_record)
    monkeypatch.setattr(artisans, "guard_record_edit", _privileged)
    monkeypatch.setattr(artisans, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(artisans, "resolve_craft_id", _resolve_craft_id)
    monkeypatch.setattr(artisans, "attach_location", _attach_location)
    monkeypatch.setattr(artisans, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(artisans, "link_workshop_artisan", _link)
    monkeypatch.setattr(artisans, "public_encode", lambda row, _viewer=None, **_kw: row)
    monkeypatch.setattr(artisans, "_mask_artisan_identity", lambda payload, _user, _row: payload)

    payload = _Payload(fields)
    asyncio.run(artisans.update_artisan("art_1", payload, _editor()))
    return writes, payload


def _drive_product(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import products

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _attach_location(data):
        return data

    monkeypatch.setattr(products, "db", _Client(productdocumentation=writes))
    monkeypatch.setattr(products, "require_record", _require_record)
    monkeypatch.setattr(products, "guard_record_edit", _privileged)
    monkeypatch.setattr(products, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(products, "attach_location", _attach_location)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, _viewer=None, **_kw: row)

    payload = _Payload(fields)
    asyncio.run(products.update_product("prd_1", payload, _editor()))
    return writes, payload


def _drive_tool(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import tools

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _attach_location(data):
        return data

    monkeypatch.setattr(tools, "db", _Client(tooldocumentation=writes))
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "guard_record_edit", _privileged)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "attach_location", _attach_location)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(
        tools, "public_encode", lambda row, _viewer=None, **_kw: dict(vars(row))
    )

    payload = _Payload(fields)
    asyncio.run(tools.update_tool("tol_1", payload, _editor()))
    return writes, payload


def _drive_process(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import processes

    writes = _Writes(row=stored)

    async def _require_record(_delegate, _record_id):
        return stored

    async def _hydrate(row):
        return row

    monkeypatch.setattr(processes, "db", _Client(process=writes))
    monkeypatch.setattr(processes, "require_record", _require_record)
    monkeypatch.setattr(processes, "guard_record_edit", _privileged)
    monkeypatch.setattr(processes, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(processes, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(processes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(processes, "_hydrate", _hydrate)

    payload = _Payload(fields, steps=None)
    asyncio.run(processes.update_process("prc_1", payload, _editor()))
    return writes, payload


def _record_routes():
    from app.api.routes import artisans, processes, products, tools

    return (
        ("artisan", _drive_artisan, artisans._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("product", _drive_product, products._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("tool", _drive_tool, tools._CLEARABLE_COLUMNS, {"status": "PENDING"}),
        ("process", _drive_process, processes._CLEARABLE_COLUMNS, {"status": "PENDING"}),
    )


def _record_cases():
    return [
        pytest.param(driver, column, id=f"{name}.{column}")
        for name, driver, columns, _untouched in _record_routes()
        for column in columns
    ]


@pytest.mark.parametrize(("driver", "column"), _record_cases())
def test_an_explicit_null_on_a_record_patch_actually_clears_the_column(monkeypatch, driver, column):
    stored = _Row(
        id="rec_1",
        createdById="usr_7",
        status="PENDING",
        workshopId=None,
        extraMetadata={},
        **{column: "the value the subject asked to have removed"},
    )

    writes, _payload = driver(monkeypatch, {column: None}, stored)

    assert len(writes.updated) == 1, f"{column}: the PATCH wrote nothing at all"
    written = writes.updated[0][1]
    assert column in written, (
        f"the null for {column!r} was stripped before the update — this route no longer names it in "
        "`clean_data(..., clearable=...)`, so clearing it is a 200 that does nothing"
    )
    assert written[column] is None


@pytest.mark.parametrize(
    ("name", "driver"),
    [(name, driver) for name, driver, _columns, _untouched in _record_routes()],
)
def test_a_key_the_client_never_sent_is_not_nulled(monkeypatch, name, driver):
    _name, _driver, columns, untouched = next(
        entry for entry in _record_routes() if entry[0] == name
    )
    stored = _Row(
        id="rec_1",
        createdById="usr_7",
        status="PENDING",
        workshopId=None,
        extraMetadata={},
        **dict.fromkeys(columns, "still on the row"),
    )

    writes, _payload = driver(monkeypatch, dict(untouched), stored)

    written = writes.updated[0][1]
    for column in columns:
        assert column not in written, (
            f"{column!r} reached the update on a PATCH that never mentioned it — an unsent key is "
            "being written as NULL over a stored value"
        )


@pytest.mark.parametrize(
    ("name", "driver", "untouched"),
    [(name, driver, untouched) for name, driver, _columns, untouched in _record_routes()],
)
def test_the_record_patch_dumps_with_exclude_unset(monkeypatch, name, driver, untouched):
    stored = _Row(id="rec_1", createdById="usr_7", status="PENDING", extraMetadata={})

    _writes, payload = driver(monkeypatch, dict(untouched), stored)

    assert payload.dumped_with.get("exclude_unset") is True, (
        f"{name}: the PATCH no longer dumps with exclude_unset=True, so its `clearable=` tuple has "
        "become a licence to NULL every field the client did not send"
    )


def test_clearing_a_populated_field_is_still_refused_for_a_non_author(monkeypatch):
    from fastapi import HTTPException

    from app.api.routes import artisans
    from app.core.deps import assert_can_contribute_fields

    stored = _Row(id="art_1", createdById="somebody_else", phone="+91 98200 00000")
    stranger = _Row(id="usr_7", name="R. Menon", role="RESEARCHER")

    with pytest.raises(HTTPException) as refusal:
        assert_can_contribute_fields(stored, stranger, {"phone": None})

    assert refusal.value.status_code == 403
    assert "phone" in str(refusal.value.detail)
    assert "phone" in artisans._CLEARABLE_COLUMNS


def test_the_record_routes_do_not_share_one_clearable_list():
    from app.api.routes import artisans, processes, products, tools

    tuples = [
        artisans._CLEARABLE_COLUMNS,
        products._CLEARABLE_COLUMNS,
        tools._CLEARABLE_COLUMNS,
        processes._CLEARABLE_COLUMNS,
    ]
    assert len({id(t) for t in tuples}) == 4, "two routes are sharing one tuple object"
    assert len({tuple(t) for t in tuples}) == 4, "two routes declare identical contents"

    assert "heightInches" in products._CLEARABLE_COLUMNS
    assert "heightInches" in tools._CLEARABLE_COLUMNS
    assert "thickness" in tools._CLEARABLE_COLUMNS
    assert "thickness" not in products._CLEARABLE_COLUMNS
    assert "sellingPrice" in products._CLEARABLE_COLUMNS
    assert "sellingPrice" not in tools._CLEARABLE_COLUMNS
    assert not set(tools._CLEARABLE_COLUMNS) <= set(products._CLEARABLE_COLUMNS)
    assert not set(products._CLEARABLE_COLUMNS) <= set(tools._CLEARABLE_COLUMNS)
    assert "phone" in artisans._CLEARABLE_COLUMNS
    assert "phone" not in products._CLEARABLE_COLUMNS

    from app.services.records import CLEARABLE_KEYS

    for module in (artisans, products, tools, processes):
        assert not (set(module._CLEARABLE_COLUMNS) & CLEARABLE_KEYS)



def _clearable_argument_of(route) -> tuple[str, ...]:
    import ast
    import inspect
    import textwrap

    tree = ast.parse(textwrap.dedent(inspect.getsource(route)))
    found = [
        tuple(ast.literal_eval(keyword.value))
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and getattr(node.func, "attr", getattr(node.func, "id", None)) == "clean_data"
        for keyword in node.keywords
        if keyword.arg == "clearable" and isinstance(keyword.value, (ast.Tuple, ast.List))
    ]
    assert len(found) == 1, (
        f"{route.__name__}: expected exactly one `clean_data(..., clearable=<literal>)` call to "
        f"read, found {len(found)}"
    )
    return found[0]


def _nullable_columns(model: str) -> set[str]:
    import re
    from pathlib import Path

    text = (Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(
        encoding="utf-8"
    )
    models = set(re.findall(r"^model (\w+) \{", text, re.MULTILINE))
    start = text.index(f"model {model} {{")
    body = text[start : text.index("\n}", start)]

    columns = set()
    for line in body.splitlines():
        statement = line.split("///")[0].strip()
        if not statement or statement.startswith(("//", "@@", "model ")):
            continue
        parts = statement.split()
        if len(parts) < 2 or not parts[1].endswith("?"):
            continue
        if parts[1].rstrip("?") in models:
            continue
        columns.add(parts[0])
    return columns


_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN = {
    "Process": frozenset({"productId"}),
}


def test_a_process_patch_refuses_to_clear_the_product_it_belongs_to(monkeypatch):
    from fastapi import HTTPException

    stored = _Row(id="prc_1", createdById="usr_7", status="PENDING", productId="prd_1")

    with pytest.raises(HTTPException) as refusal:
        _drive_process(monkeypatch, {"productId": None}, stored)

    assert refusal.value.status_code == 422
    assert "product" in refusal.value.detail.lower()


def test_moving_a_process_to_another_product_still_checks_that_product_exists(monkeypatch):
    from app.api.routes import processes

    looked_up: list[Any] = []
    stored = _Row(id="prc_1", createdById="usr_7", status="PENDING", productId="prd_1")
    writes = _Writes(row=stored)

    async def _require_record(_delegate, record_id):
        looked_up.append(record_id)
        return stored

    async def _hydrate(row):
        return row

    monkeypatch.setattr(processes, "db", _Client(process=writes, productdocumentation=_Writes()))
    monkeypatch.setattr(processes, "require_record", _require_record)
    monkeypatch.setattr(processes, "guard_record_edit", _privileged)
    monkeypatch.setattr(processes, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(processes, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(processes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(processes, "_hydrate", _hydrate)

    payload = _Payload({"productId": "prd_2"}, steps=None)
    asyncio.run(processes.update_process("prc_1", payload, _editor()))

    assert "prd_2" in looked_up, "the new product id was never looked up before the process moved"
    assert writes.updated[0][1]["productId"] == "prd_2"


def test_every_nullable_column_a_client_can_send_is_either_clearable_or_exempt():
    from app.api.routes import artisans, crafts, processes, products, questionnaire, tools, workshops
    from app.schemas.questionnaire import QuestionnaireInterviewUpdate
    from app.schemas.records import (
        ArtisanUpdate,
        CraftUpdate,
        ProcessUpdate,
        ProductUpdate,
        ToolUpdate,
        WorkshopUpdate,
    )
    from app.services.records import CLEARABLE_KEYS

    system_managed = {
        "extraMetadata",
        "measurementImageId",
        "measurementAnalysis",
        "measurementAnalysisStatus",
        "expectedUpdatedAt",
    }

    cases = (
        ("Artisan", ArtisanUpdate, artisans._CLEARABLE_COLUMNS),
        ("ProductDocumentation", ProductUpdate, products._CLEARABLE_COLUMNS),
        ("ToolDocumentation", ToolUpdate, tools._CLEARABLE_COLUMNS),
        ("Process", ProcessUpdate, processes._CLEARABLE_COLUMNS),
        ("Craft", CraftUpdate, crafts._CLEARABLE_COLUMNS),
        ("Workshop", WorkshopUpdate, workshops._CLEARABLE_COLUMNS),
        (
            "QuestionnaireInterview",
            QuestionnaireInterviewUpdate,
            _clearable_argument_of(questionnaire.update_interview),
        ),
    )
    for model, schema, declared in cases:
        nullable = _nullable_columns(model)
        sendable = {field.alias or name for name, field in schema.model_fields.items()} | set(
            schema.model_fields
        )
        sendable -= {"aadhaarNumber"}
        refused = {"subCategoryId", "category"} if model == "Craft" else set()
        expected = (nullable & sendable) - (CLEARABLE_KEYS & nullable) - system_managed - refused

        missing = sorted(expected - set(declared))
        assert not missing, (
            f"{model}: {missing} are nullable columns the update schema accepts, and no route "
            "declares them clearable — sending null for one is a 200 that does nothing. Add them to "
            "the route's `_CLEARABLE_COLUMNS`, or add an exemption here saying why the null cannot "
            "reach the database anyway."
        )
        stray = sorted(set(declared) - expected)
        assert not stray, (
            f"{model}: {stray} are declared clearable but are not nullable columns this update "
            "schema can send. Clearing a NOT NULL column is a 500, not a retraction."
        )
        misrouted = sorted(
            ((sendable & CLEARABLE_KEYS) - nullable)
            - _GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN.get(model, frozenset())
        )
        assert not misrouted, (
            f"{model}: {misrouted} are in the GLOBAL `records.CLEARABLE_KEYS` and this update schema "
            f"accepts them, but they are NOT NULL on {model}, so an explicit null survives "
            "`clean_data` and reaches the write. Either stop the update schema accepting a null for "
            "them, or refuse it in the route and record the refusal in "
            "`_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN` with a test that drives it."
        )


def test_the_new_columns_are_not_silently_title_cased():
    from app.services.records import TITLE_CASE_FIELDS

    for column in (
        "craftStartDate",
        "experienceMonths",
        "heightInches",
        "workshopType",
        "clientKey",
        "transcriptEditedAt",
        "transcriptEditedById",
        "expectedUpdatedAt",
    ):
        assert column not in TITLE_CASE_FIELDS
