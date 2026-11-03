import asyncio
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.records import ToolCreate, ToolUpdate

BACKEND = Path(__file__).resolve().parents[1]


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _Delegate:
    def __init__(self, rows: list[Any] | None = None, created: Any = None):
        self.rows = rows or []
        self.created_row = created
        self.creates: list[Any] = []
        self.inserted: list[list[dict[str, Any]]] = []
        self.deletes: list[dict[str, Any]] = []
        self.updates: list[tuple[Any, Any]] = []
        self.count_value = 0
        self.counted: list[dict[str, Any]] = []

    async def find_unique(self, where, **_kwargs):
        return next((row for row in self.rows if row.id == where.get("id")), None)

    async def find_many(self, where=None, **_kwargs):
        rows = list(self.rows)
        tool_id = (where or {}).get("toolId")
        if tool_id is not None:
            rows = [row for row in rows if row.toolId == tool_id]
        wanted = ((where or {}).get("id") or {}).get("in")
        if wanted is None:
            return rows
        return [row for row in rows if row.id in wanted]

    async def create(self, data, **_kwargs):
        self.creates.append(data)
        return self.created_row if self.created_row is not None else _Row(id="tol_new", **data)

    async def update(self, where, data, **_kwargs):
        self.updates.append((where, data))
        return _Row(id=where["id"], **data)

    async def create_many(self, data, **_kwargs):
        self.inserted.append(list(data))

    async def delete_many(self, where=None, **_kwargs):
        self.deletes.append(where or {})

    async def count(self, where=None, **_kwargs):
        self.counted.append(where or {})
        return self.count_value


class _Client(SimpleNamespace):
    pass


class _Payload(SimpleNamespace):
    def model_dump(self, **kwargs):
        excluded = kwargs.get("exclude") or set()
        if kwargs.get("exclude_unset"):
            return {k: v for k, v in self.__dict__.items() if k not in excluded}
        return {k: v for k, v in self.__dict__.items() if k not in excluded}


def _user(user_id: str = "usr_7"):
    return _Row(id=user_id, name="R. Menon", role="RESEARCHER")


async def _identity(data):
    return data


async def _no_workshop_check(_user, _workshop_id):
    return None


async def _no_relations(_rows, _relations):
    return None


async def _no_status_policy(_user, _record, _data):
    return None


def _crafts() -> _Delegate:
    return _Delegate(
        rows=[
            _Row(id="crf_bandhani", name="Bandhani", createdById="usr_9"),
            _Row(id="crf_block", name="Block Printing", createdById="usr_9"),
            _Row(id="crf_zardozi", name="Zardozi", createdById="usr_9"),
        ]
    )


def _artisans() -> _Delegate:
    return _Delegate(
        rows=[
            _Row(id="art_1", name="A. Khatri", place="Bhuj", createdById="usr_7"),
            _Row(id="art_2", name="B. Khatri", place="Bhuj", createdById="usr_7"),
            _Row(id="art_stranger", name="C. Rao", place="Bagru", createdById="usr_99"),
        ]
    )


def _create_payload(**overrides) -> _Payload:
    fields = dict(
        craftName="Bandhani",
        place="Bhuj",
        artisanName="A. Khatri",
        toolkitName="Dyeing Vat",
        craftId=None,
        artisanId=None,
        craftIds=None,
        artisanIds=None,
        workshopId=None,
        clientKey=None,
        location=None,
        extraMetadata=None,
        status="PENDING",
    )
    fields.update(overrides)
    return _Payload(**fields)


def _drive_create(monkeypatch, payload: _Payload, *, user=None, tools_db=None):
    from app.api.routes import tools

    client = tools_db or _Client(
        tooldocumentation=_Delegate(),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )
    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    answer = asyncio.run(tools.create_tool(payload, user or _user()))
    return client, answer


def _link_rows(prefix: str, column: str, ids, tool_id: str = "tol_1") -> list[_Row]:
    return [
        _Row(
            id=f"{prefix}_{index}",
            toolId=tool_id,
            createdAt=f"2026-01-0{index + 1}T00:00:00",
            **{column: value},
        )
        for index, value in enumerate(ids)
    ]


def _drive_update(
    monkeypatch,
    fields: dict[str, Any],
    stored: _Row,
    *,
    privileged=True,
    user=None,
    stored_crafts=(),
    stored_artisans=(),
    guard=None,
):
    from app.api.routes import tools

    client = _Client(
        tooldocumentation=_Delegate(),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(rows=_link_rows("tcr", "craftId", stored_crafts)),
        toolartisan=_Delegate(rows=_link_rows("tar", "artisanId", stored_artisans)),
    )

    async def _require_record(_delegate, _record_id):
        return stored

    async def _privilege(_record, _user, _kind):
        return privileged

    async def _guard(_record, _user, _data, _kind, **_kwargs):
        return privileged

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "record_edit_privilege", _privilege)
    monkeypatch.setattr(tools, "guard_record_edit", guard or _guard)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    answer = asyncio.run(tools.update_tool("tol_1", _Payload(**fields), user or _user()))
    return client, answer



def test_an_explicit_null_link_list_is_refused_by_name():
    body = dict(
        craftName="Bandhani",
        place="Bhuj",
        artisanName="A. Khatri",
        toolkitName="Dyeing vat",
        location={"latitude": 23.24, "longitude": 69.66, "state": "Gujarat", "district": "Kachchh"},
    )
    for key in ("craftIds", "artisanIds"):
        with pytest.raises(ValidationError) as refusal:
            ToolCreate(**body, **{key: None})
        assert key in {str(error["loc"][0]) for error in refusal.value.errors()}
        assert "send []" in str(refusal.value)

        with pytest.raises(ValidationError):
            ToolUpdate(**{key: None})


def test_absent_and_empty_stay_distinguishable_through_the_dump():
    assert "craftIds" not in ToolUpdate(remarks="Rehandled").model_dump(exclude_unset=True)
    assert ToolUpdate(craftIds=[]).model_dump(exclude_unset=True)["craftIds"] == []
    assert ToolUpdate(craftIds=["a"]).model_dump(exclude_unset=True)["craftIds"] == ["a"]


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, None),
        ([], []),
        (["a", "b"], ["a", "b"]),
        (["b", "a"], ["b", "a"]),
        (["a", "a", "b", "a"], ["a", "b"]),
        (["  a  ", "b"], ["a", "b"]),
        (["", "   ", "a"], ["a"]),
        (["", "  "], []),
    ],
)
def test_the_id_list_is_cleaned_without_ever_being_reordered(raw, expected):
    from app.api.routes import tools

    assert tools._clean_link_ids(raw) == expected



def test_a_create_writes_one_link_row_per_craft_and_derives_both_columns(monkeypatch):
    client, answer = _drive_create(
        monkeypatch,
        _create_payload(craftIds=["crf_block", "crf_bandhani"], craftName="Whatever The Box Said"),
    )

    written = client.tooldocumentation.creates[0]
    assert written["craftId"] == "crf_block"
    assert written["craftName"] == "Block Printing, Bandhani"
    assert "craftIds" not in written, (
        "`craftIds` reached `db.tooldocumentation.create(data=...)`. It is not a column, so this is a "
        "500 on the save rather than anything a researcher could act on."
    )
    assert client.toolcraft.inserted == [
        [
            {"toolId": "tol_new", "craftId": "crf_block"},
            {"toolId": "tol_new", "craftId": "crf_bandhani"},
        ]
    ]
    assert answer["craftName"] == "Block Printing, Bandhani"


def test_the_derived_craft_name_overrides_whatever_the_body_typed(monkeypatch):
    client, _ = _drive_create(
        monkeypatch, _create_payload(craftIds=["crf_bandhani"], craftName="Bandhani Of Kutch")
    )

    assert client.tooldocumentation.creates[0]["craftName"] == "Bandhani"


def test_an_absent_list_writes_no_links_and_leaves_the_typed_columns_alone(monkeypatch):
    client, _ = _drive_create(
        monkeypatch, _create_payload(craftName="Bandhani", craftId="crf_bandhani")
    )

    assert client.toolcraft.inserted == []
    assert client.toolartisan.inserted == []
    written = client.tooldocumentation.creates[0]
    assert written["craftId"] == "crf_bandhani"
    assert written["craftName"] == "Bandhani"


def test_an_empty_list_on_a_create_writes_no_links_and_derives_nothing(monkeypatch):
    client, _ = _drive_create(
        monkeypatch,
        _create_payload(craftIds=[], artisanIds=[], craftName="Bandhani", craftId="crf_bandhani"),
    )

    assert client.toolcraft.inserted == []
    assert client.tooldocumentation.creates[0]["craftName"] == "Bandhani"


def test_an_unknown_craft_is_a_404_before_a_single_row_is_written(monkeypatch):
    from app.api.routes import tools

    client = _Client(
        tooldocumentation=_Delegate(),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )
    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            tools.create_tool(
                _create_payload(craftIds=["crf_bandhani", "crf_gone"]), _user()
            )
        )

    assert refusal.value.status_code == 404
    assert refusal.value.detail == "Record not found"
    assert client.tooldocumentation.creates == [], (
        "the tool row was written before the ids were checked"
    )
    assert client.toolcraft.inserted == []


def test_the_artisan_list_fills_the_id_and_never_the_name_or_the_place(monkeypatch):
    client, _ = _drive_create(
        monkeypatch,
        _create_payload(
            artisanIds=["art_2", "art_1"], artisanName="Abdul Khatri", place="Bhuj, Kutch"
        ),
    )

    written = client.tooldocumentation.creates[0]
    assert written["artisanId"] == "art_2"
    assert written["artisanName"] == "Abdul Khatri"
    assert written["place"] == "Bhuj, Kutch"
    assert "artisanIds" not in written
    assert client.toolartisan.inserted == [
        [{"toolId": "tol_new", "artisanId": "art_2"}, {"toolId": "tol_new", "artisanId": "art_1"}]
    ]


def test_a_creator_may_link_any_artisan_because_they_own_the_row_they_are_creating(monkeypatch):
    client, _ = _drive_create(monkeypatch, _create_payload(artisanIds=["art_stranger"]))

    assert client.toolartisan.inserted == [[{"toolId": "tol_new", "artisanId": "art_stranger"}]]



def _stored_tool(**overrides) -> _Row:
    fields = dict(
        id="tol_1",
        createdById="usr_7",
        status="PENDING",
        workshopId=None,
        extraMetadata={},
        craftId="crf_bandhani",
        craftName="Bandhani",
        artisanId="art_1",
        artisanName="A. Khatri",
        place="Bhuj",
    )
    fields.update(overrides)
    return _Row(**fields)


def test_a_patch_replaces_the_whole_link_set_rather_than_diffing_it(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {"craftIds": ["crf_zardozi", "crf_block"]},
        _stored_tool(),
    )

    assert client.toolcraft.deletes == [{"toolId": "tol_1"}]
    assert client.toolcraft.inserted == [
        [
            {"toolId": "tol_1", "craftId": "crf_zardozi"},
            {"toolId": "tol_1", "craftId": "crf_block"},
        ]
    ]
    written = client.tooldocumentation.updates[0][1]
    assert written["craftId"] == "crf_zardozi"
    assert written["craftName"] == "Zardozi, Block Printing"


def test_an_empty_list_on_a_patch_deletes_every_link_and_leaves_the_columns_alone(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {"craftIds": [], "artisanIds": []},
        _stored_tool(),
        stored_crafts=["crf_bandhani"],
        stored_artisans=["art_1"],
    )

    assert client.toolcraft.deletes == [{"toolId": "tol_1"}]
    assert client.toolcraft.inserted == []
    assert client.toolartisan.deletes == [{"toolId": "tol_1"}]
    assert client.toolartisan.inserted == []
    written = client.tooldocumentation.updates[0][1]
    assert "craftName" not in written
    assert "craftId" not in written
    assert "artisanName" not in written

    already_empty, _ = _drive_update(monkeypatch, {"craftIds": [], "artisanIds": []}, _stored_tool())
    assert already_empty.toolcraft.deletes == []
    assert already_empty.toolartisan.deletes == []


def test_an_absent_list_on_a_patch_leaves_the_stored_links_untouched(monkeypatch):
    client, _ = _drive_update(monkeypatch, {"remarks": "Rehandled in 2019"}, _stored_tool())

    assert client.toolcraft.deletes == []
    assert client.toolcraft.inserted == []
    assert client.toolartisan.deletes == []


def test_an_unknown_craft_on_a_patch_is_refused_above_the_edit_ledger(monkeypatch):
    from app.api.routes import tools

    guarded: list[str] = []

    async def _guard(_record, _user, _data, _kind, **_kwargs):
        guarded.append("ran")
        return True

    client = _Client(
        tooldocumentation=_Delegate(),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )

    async def _require_record(_delegate, _record_id):
        return _stored_tool()

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "guard_record_edit", _guard)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(tools.update_tool("tol_1", _Payload(craftIds=["crf_gone"]), _user()))

    assert refusal.value.status_code == 404
    assert guarded == [], "the edit ledger was written before the craft ids were checked"
    assert client.tooldocumentation.updates == []


def test_linking_a_strangers_artisan_is_refused_for_a_caller_with_no_standing(monkeypatch):
    from app.api.routes import tools
    from app.services import access

    monkeypatch.setattr(access, "effective_tier_for_record", _no_tier)

    with pytest.raises(HTTPException) as refusal:
        _drive_update(
            monkeypatch,
            {"artisanIds": ["art_stranger"]},
            _stored_tool(createdById="usr_99"),
            user=_user("usr_7"),
            privileged=False,
        )

    assert refusal.value.status_code == 403
    assert refusal.value.detail == tools._FOREIGN_ARTISAN_REFUSAL
    assert "you may only assign it to your own artisans" in refusal.value.detail


async def _no_tier(_user, _owner_id, _record_type, _record_id):
    return "VIEW"


def test_an_ordinary_contributor_cannot_blank_a_populated_link_set(monkeypatch):
    with pytest.raises(HTTPException) as refusal:
        _drive_update(
            monkeypatch,
            {"craftIds": []},
            _stored_tool(createdById="usr_99"),
            user=_user("usr_7"),
            privileged=False,
            stored_crafts=["crf_bandhani", "crf_block"],
        )

    assert refusal.value.status_code == 403
    assert "craftIds" in refusal.value.detail


def test_the_blanking_refusal_lands_before_the_row_and_the_links_are_touched(monkeypatch):
    from app.api.routes import tools

    client = _Client(
        tooldocumentation=_Delegate(),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(rows=_link_rows("tcr", "craftId", ["crf_bandhani"])),
        toolartisan=_Delegate(),
    )

    async def _require_record(_delegate, _record_id):
        return _stored_tool(createdById="usr_99")

    async def _privilege(_record, _user, _kind):
        return False

    ledger: list[str] = []

    async def _guard(_record, _user, _data, _kind, **_kwargs):
        ledger.append("RecordRevision committed")
        return False

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "require_record", _require_record)
    monkeypatch.setattr(tools, "record_edit_privilege", _privilege)
    monkeypatch.setattr(tools, "guard_record_edit", _guard)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    with pytest.raises(HTTPException):
        asyncio.run(tools.update_tool("tol_1", _Payload(craftIds=[]), _user("usr_7")))

    assert client.tooldocumentation.updates == [], (
        "the tool row was written before the relation guard ran — the contributor is told the save "
        "failed, and the columns they changed are stored anyway"
    )
    assert ledger == [], (
        "the edit ledger was written before the relation guard ran, so RecordRevision now holds a "
        "permanent claim about an edit that was refused"
    )
    assert client.toolcraft.deletes == [], "the links were deleted before the refusal"


def test_an_empty_relation_is_still_fillable_by_an_ordinary_contributor(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {"craftIds": ["crf_block"]},
        _stored_tool(createdById="usr_99"),
        user=_user("usr_7"),
        privileged=False,
        stored_crafts=(),
    )

    assert client.toolcraft.inserted == [[{"toolId": "tol_1", "craftId": "crf_block"}]]


def test_an_unchanged_link_list_is_not_a_change_and_is_not_refused(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {
            "remarks": "Rehandled in 2024",
            "craftIds": ["crf_bandhani"],
            "artisanIds": ["art_1"],
        },
        _stored_tool(createdById="usr_99"),
        user=_user("usr_7"),
        privileged=False,
        stored_crafts=["crf_bandhani"],
        stored_artisans=["art_1"],
    )

    assert client.tooldocumentation.updates[0][1]["remarks"] == "Rehandled in 2024"
    assert client.toolcraft.deletes == []
    assert client.toolcraft.inserted == []
    assert client.toolartisan.deletes == []
    assert client.toolartisan.inserted == []


def test_a_reordered_list_is_refused_by_the_guard_that_owns_what_changed(monkeypatch):
    from app.core.deps import assert_can_contribute_fields

    async def _real_field_guard(record, user, data, _kind, **_kwargs):
        assert_can_contribute_fields(record, user, data)
        return False

    with pytest.raises(HTTPException) as refusal:
        _drive_update(
            monkeypatch,
            {"craftIds": ["crf_block", "crf_bandhani"]},
            _stored_tool(createdById="usr_99", craftName="Bandhani, Block Printing"),
            user=_user("usr_7"),
            privileged=False,
            stored_crafts=["crf_bandhani", "crf_block"],
            guard=_real_field_guard,
        )

    assert refusal.value.status_code == 403
    assert "craftName" in refusal.value.detail


def test_the_relation_guard_is_raised_above_both_writes():
    import ast
    import inspect
    import textwrap

    from app.api.routes import tools

    tree = ast.parse(textwrap.dedent(inspect.getsource(tools.update_tool)))
    lines: dict[str, list[int]] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            name = getattr(node.func, "attr", getattr(node.func, "id", None))
            lines.setdefault(name, []).append(node.lineno)

    assert len(lines["assert_can_contribute_relation"]) == 2, (
        "both link relations must be guarded, and each exactly once"
    )
    refusals = lines["assert_can_contribute_relation"] + lines["_resolve_tool_links"]
    assert max(refusals) < min(lines["guard_record_edit"]), (
        "a link refusal is raised after guard_record_edit, which has already COMMITTED a "
        "RecordRevision claiming the edit"
    )
    assert max(refusals) < min(lines["update"]), (
        "a link refusal is raised after the tool row is written — the contributor is told the save "
        "failed and their edit is stored anyway"
    )


def test_the_route_asks_for_the_edit_privilege_once_and_hands_it_on():
    import inspect

    from app.api.routes import tools
    from app.services import access

    source = inspect.getsource(tools.update_tool)
    assert source.count("record_edit_privilege(") == 1
    assert "privileged=privileged" in source, (
        "the early answer is not handed to guard_record_edit, so the two ask the database the same "
        "question twice — and could disagree"
    )
    assert "record_edit_privilege(record, user, record_type)" in inspect.getsource(
        access.guard_record_edit
    )


def test_a_privileged_editor_pays_for_no_count_they_cannot_be_refused_by(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {"craftIds": ["crf_block"], "artisanIds": ["art_1"]},
        _stored_tool(),
        stored_crafts=["crf_bandhani"],
        stored_artisans=["art_2"],
    )

    assert client.toolcraft.counted == []
    assert client.toolartisan.counted == []
    assert client.toolcraft.inserted == [[{"toolId": "tol_1", "craftId": "crf_block"}]]



def test_re_sending_an_already_linked_foreign_artisan_is_not_an_assignment(monkeypatch):
    client, _ = _drive_update(
        monkeypatch,
        {"remarks": "Rehandled in 2024", "artisanIds": ["art_stranger"]},
        _stored_tool(createdById="usr_99", artisanId="art_stranger"),
        user=_user("usr_7"),
        privileged=False,
        stored_artisans=["art_stranger"],
    )

    assert client.tooldocumentation.updates[0][1]["remarks"] == "Rehandled in 2024"
    assert client.toolartisan.deletes == []


def test_adding_a_new_foreign_artisan_is_still_refused(monkeypatch):
    from app.api.routes import tools
    from app.services import access

    monkeypatch.setattr(access, "effective_tier_for_record", _no_tier)

    with pytest.raises(HTTPException) as refusal:
        _drive_update(
            monkeypatch,
            {"artisanIds": ["art_stranger", "art_1"]},
            _stored_tool(createdById="usr_99"),
            user=_user("usr_7"),
            privileged=False,
            stored_artisans=["art_1"],
        )

    assert refusal.value.status_code == 403
    assert refusal.value.detail == tools._FOREIGN_ARTISAN_REFUSAL


def test_the_link_gate_is_the_same_function_guard_record_edit_asks(monkeypatch):
    import inspect

    from app.api.routes import tools

    source = inspect.getsource(tools._may_manage_tool_links)
    assert "record_edit_privilege(tool, current_user" in source
    assert "effective_tier_for_record" not in source, (
        "the gate is re-listing the privilege clauses instead of asking for them — that is exactly "
        "how the professor clause came to be missing from one of the two lists"
    )
    assert "professor" in tools._FOREIGN_ARTISAN_REFUSAL, (
        "the refusal still names three of the four people the gate admits"
    )



def test_a_replayed_create_repairs_empty_links_and_never_touches_populated_ones(monkeypatch):
    from app.api.routes import tools

    stored = _Row(id="tol_1", createdById="usr_7", clientKey="key-1", craftName="Bandhani")
    client = _Client(
        tooldocumentation=_Delegate(rows=[stored]),
        craft=_crafts(),
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )

    async def _find_unique(where, **_kwargs):
        return stored if where.get("clientKey") == "key-1" else None

    client.tooldocumentation.find_unique = _find_unique

    async def _no_gate(*_args, **_kwargs):
        raise AssertionError("a create gate ran on the replay path")

    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "attach_location", _no_gate)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_gate)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    payload = _create_payload(clientKey="key-1", craftIds=["crf_bandhani"])

    client.toolcraft.count_value = 0
    asyncio.run(tools.create_tool(payload, _user()))
    assert client.tooldocumentation.creates == [], "the replay wrote a SECOND tool row"
    assert client.toolcraft.inserted == [[{"toolId": "tol_1", "craftId": "crf_bandhani"}]]

    client.toolcraft.inserted.clear()
    client.toolcraft.count_value = 3
    asyncio.run(tools.create_tool(payload, _user()))
    assert client.toolcraft.inserted == []
    assert client.toolcraft.deletes == [], (
        "the repair path deleted links it was only allowed to add to"
    )


def test_a_repair_drops_an_id_whose_record_has_since_been_deleted(monkeypatch):
    from app.api.routes import tools

    client = _Client(
        craft=_crafts(), artisan=_artisans(), toolcraft=_Delegate(), toolartisan=_Delegate()
    )
    monkeypatch.setattr(tools, "db", client)

    wrote = asyncio.run(
        tools._repair_empty_links("tol_1", ["crf_gone", "crf_block"], None)
    )

    assert wrote is True
    assert client.toolcraft.inserted == [[{"toolId": "tol_1", "craftId": "crf_block"}]]



def test_the_tool_relations_carry_the_craft_links_with_their_craft(monkeypatch):
    from app.api.routes import tools

    assert [rel.field for rel in tools._LINK_RELATIONS] == ["artisanLinks", "craftLinks"]
    craft_links = next(rel for rel in tools.RELATIONS if rel.field == "craftLinks")
    assert (craft_links.model, craft_links.key, craft_links.many) == ("toolcraft", "toolId", True)
    assert craft_links.include == {"craft": True}
    assert tools.INCLUDE["craftLinks"] == {"include": {"craft": True}}


def test_craft_links_come_back_in_the_order_the_craft_name_records():
    from app.api.routes import tools

    tool = {
        "craftName": "Zardozi, Bandhani, Block Printing",
        "craftLinks": [
            {"id": "c1", "createdAt": "2026-01-01", "craft": {"name": "Bandhani"}},
            {"id": "c2", "createdAt": "2026-01-02", "craft": {"name": "Block Printing"}},
            {"id": "c3", "createdAt": "2026-01-03", "craft": {"name": "Zardozi"}},
        ],
    }

    tools._order_craft_links(tool)

    assert [link["id"] for link in tool["craftLinks"]] == ["c3", "c1", "c2"]


def test_a_link_the_craft_name_does_not_mention_is_appended_oldest_first():
    from app.api.routes import tools

    tool = {
        "craftName": "Bandhani",
        "craftLinks": [
            {"id": "c9", "createdAt": "2026-03-01", "craft": {"name": "Renamed Since"}},
            {"id": "c2", "createdAt": "2026-01-02", "craft": {"name": "Bandhani"}},
            {"id": "c7", "createdAt": "2026-02-01", "craft": {"name": "Also Renamed"}},
        ],
    }

    tools._order_craft_links(tool)

    assert [link["id"] for link in tool["craftLinks"]] == ["c2", "c7", "c9"]


@pytest.mark.parametrize(
    "tool",
    [
        {},
        {"craftName": None, "craftLinks": None},
        {"craftName": "", "craftLinks": []},
        {"craftName": "Bandhani", "craftLinks": [{"id": "c1"}]},
    ],
)
def test_the_ordering_survives_every_shape_a_tool_can_actually_have(tool):
    from app.api.routes import tools

    tools._order_craft_links(dict(tool))



def _many_crafts(count: int, name: str) -> _Delegate:
    return _Delegate(
        rows=[_Row(id=f"crf_{i}", name=f"{name} {i}", createdById="usr_9") for i in range(count)]
    )


def _drive_create_with_crafts(monkeypatch, crafts: _Delegate, ids: list[str]):
    client = _Client(
        tooldocumentation=_Delegate(),
        craft=crafts,
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )
    return _drive_create(monkeypatch, _create_payload(craftIds=ids), tools_db=client)


def test_a_craft_selection_that_overflows_one_name_is_still_saveable(monkeypatch):
    from app.schemas.records import TOOL_CRAFT_NAME_MAX, ToolUpdate

    client, _ = _drive_create_with_crafts(
        monkeypatch, _many_crafts(7, "Ajrakh Hand Block Printing"), [f"crf_{i}" for i in range(7)]
    )

    stored = client.tooldocumentation.creates[0]["craftName"]
    assert len(stored) > 180, "the example no longer exercises the old cap — lengthen the names"
    assert len(stored) <= TOOL_CRAFT_NAME_MAX
    assert ToolUpdate(craftName=stored).craftName == stored


def test_what_the_route_can_derive_and_what_the_schemas_accept_are_one_number():
    import inspect

    from app.api.routes import tools
    from app.schemas.records import TOOL_CRAFT_NAME_MAX, ToolCreate, ToolUpdate

    for schema in (ToolCreate, ToolUpdate):
        bound = next(
            meta.max_length
            for meta in schema.model_fields["craftName"].metadata
            if getattr(meta, "max_length", None) is not None
        )
        assert bound == TOOL_CRAFT_NAME_MAX

    source = inspect.getsource(tools._resolve_tool_links)
    assert "len(joined) > TOOL_CRAFT_NAME_MAX" in source, (
        "the route no longer bounds the name it derives, so it can store a craftName its own update "
        "schema will refuse on the next save"
    )


def test_a_join_too_long_to_store_is_refused_before_anything_is_written(monkeypatch):
    from app.api.routes import tools
    from app.schemas.records import TOOL_CRAFT_NAME_MAX

    client = _Client(
        tooldocumentation=_Delegate(),
        craft=_many_crafts(12, "X" * 176),
        artisan=_artisans(),
        toolcraft=_Delegate(),
        toolartisan=_Delegate(),
    )
    monkeypatch.setattr(tools, "db", client)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(
            tools.create_tool(
                _create_payload(craftIds=[f"crf_{i}" for i in range(12)]), _user()
            )
        )

    assert refusal.value.status_code == 422
    assert "12 linked crafts" in refusal.value.detail
    assert str(TOOL_CRAFT_NAME_MAX) in refusal.value.detail
    assert client.tooldocumentation.creates == [], "the tool was written with a name it cannot keep"
    assert client.toolcraft.inserted == []



def test_artisan_links_lead_with_the_one_the_artisan_id_column_names():
    from app.api.routes import tools

    tool = {
        "artisanId": "art_zoya",
        "artisanName": "Zoya",
        "artisanLinks": [
            {"id": "l1", "artisanId": "art_amit", "createdAt": "2026-01-01T00:00:00"},
            {"id": "l2", "artisanId": "art_zoya", "createdAt": "2026-01-01T00:00:00"},
        ],
    }

    tools._order_artisan_links(tool)

    assert [link["artisanId"] for link in tool["artisanLinks"]] == ["art_zoya", "art_amit"]
    assert tool["artisanLinks"][0]["artisanId"] == tool["artisanId"]


def test_the_rest_of_the_artisan_links_are_oldest_first_then_by_id():
    from app.api.routes import tools

    tool = {
        "artisanId": "art_zoya",
        "artisanLinks": [
            {"id": "l9", "artisanId": "art_c", "createdAt": "2026-03-01T00:00:00"},
            {"id": "l3", "artisanId": "art_b", "createdAt": "2026-01-01T00:00:00"},
            {"id": "l1", "artisanId": "art_a", "createdAt": "2026-01-01T00:00:00"},
            {"id": "l7", "artisanId": "art_zoya", "createdAt": "2026-09-01T00:00:00"},
        ],
    }

    tools._order_artisan_links(tool)

    assert [link["id"] for link in tool["artisanLinks"]] == ["l7", "l1", "l3", "l9"]


@pytest.mark.parametrize(
    "tool",
    [
        {},
        {"artisanId": None, "artisanLinks": None},
        {"artisanId": "art_1", "artisanLinks": []},
        {"artisanId": "art_never_linked", "artisanLinks": [{"id": "l1", "artisanId": "art_2"}]},
    ],
)
def test_the_artisan_ordering_survives_every_shape_a_tool_can_actually_have(tool):
    from app.api.routes import tools

    tools._order_artisan_links(dict(tool))


def test_both_link_lists_are_ordered_on_every_read_path():
    import inspect

    from app.api.routes import tools

    for encoder in (tools._tool_payload, tools._tool_page):
        source = inspect.getsource(encoder)
        assert "_order_craft_links(" in source
        assert "_order_artisan_links(" in source


def test_the_assigned_artisans_endpoint_has_a_total_order():
    import inspect

    from app.api.routes import tools

    source = inspect.getsource(tools._assigned_artisans)
    assert 'order=[{"createdAt": "asc"}, {"id": "asc"}]' in source


def test_reading_a_tool_back_and_saving_it_unchanged_keeps_its_artisan(monkeypatch):
    from app.api.routes import tools

    encoded = {
        "id": "tol_1",
        "artisanId": "art_2",
        "artisanName": "B. Khatri",
        "craftName": "Bandhani",
        "craftLinks": [],
        "artisanLinks": [
            {"id": "l1", "artisanId": "art_1", "createdAt": "2026-01-01T00:00:00"},
            {"id": "l2", "artisanId": "art_2", "createdAt": "2026-01-01T00:00:00"},
        ],
    }
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(row))
    payload = tools._tool_payload(encoded)

    seeded = [link["artisanId"] for link in payload["artisanLinks"]]
    client, _ = _drive_update(
        monkeypatch,
        {"artisanIds": seeded},
        _stored_tool(artisanId="art_2", artisanName="B. Khatri"),
        stored_artisans=["art_1", "art_2"],
    )

    assert seeded[0] == "art_2", (
        "the list does not lead with the artisan the column names, so the next save re-points it"
    )
    assert client.tooldocumentation.updates[0][1]["artisanId"] == "art_2"
    assert client.toolartisan.deletes == [], "an unchanged set was deleted and re-inserted"



@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, None),
        ([], None),
        ([""], None),
        (["  "], None),
        (["a"], ["a"]),
        (["a", "b"], ["a", "b"]),
        (["a,b"], ["a", "b"]),
        (["a, b", "c"], ["a", "b", "c"]),
        (["a", "a"], ["a"]),
        (["a,,b"], ["a", "b"]),
    ],
)
def test_the_plural_craft_scope_accepts_both_spellings_a_client_builds(raw, expected):
    from app.services.record_filters import resolve_craft_ids

    assert resolve_craft_ids(raw) == expected


def test_the_plural_craft_scope_has_no_unassigned_sentinel():
    from app.services.record_filters import UNASSIGNED_WORKSHOP, resolve_craft_ids

    assert resolve_craft_ids([UNASSIGNED_WORKSHOP]) == [UNASSIGNED_WORKSHOP], (
        "'none' is not reserved here — it is read as an ordinary craft id, which matches nothing"
    )


def test_the_plural_craft_filter_never_overwrites_the_singular_one():
    source = (BACKEND / "app" / "api" / "routes" / "artisans.py").read_text(encoding="utf-8")
    body = source.split("async def list_artisans(")[1].split("\nasync def ")[0]

    assert 'and_filters.append({"craftId": {"in": resolved_crafts}})' in body
    assert body.count('where["craftId"] = craftId') == 1
    assert 'where["craftId"] = {"in"' not in body, (
        "the plural craft scope is assigning `where[\"craftId\"]`, which the singular filter above "
        "already assigns — one of the two is being silently discarded"
    )



def _migration_sql(*, statements_only: bool = False) -> str:
    path = BACKEND / "prisma" / "migrations" / "20260915100000_tool_craft_links" / "migration.sql"
    assert path.exists(), "the ToolCraft migration is missing"
    sql = path.read_text(encoding="utf-8")
    if not statements_only:
        return sql
    kept = [line for line in sql.splitlines() if not line.lstrip().startswith("--")]
    return "\n".join(kept)


def _schema() -> str:
    return (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8")


def test_the_migration_is_additive_and_touches_no_existing_table():
    sql = _migration_sql(statements_only=True)

    assert re.search(r'CREATE TABLE IF NOT EXISTS "ToolCraft"', sql)
    for forbidden in ("DROP TABLE", "DROP COLUMN", "ALTER COLUMN", "DROP CONSTRAINT", "DROP INDEX"):
        assert forbidden not in sql, f"{forbidden} in an additive migration"
    for statement in re.findall(r'ALTER TABLE "(\w+)"', sql):
        assert statement == "ToolCraft", f'the migration alters "{statement}", which already exists'
    assert "CONCURRENTLY" not in sql, (
        "prisma sends a migration as one multi-statement query inside an implicit transaction, so "
        "CREATE INDEX CONCURRENTLY fails with PG 25001 / P3018 and blocks every later migration — "
        "20260726200000 sets that out at length"
    )


def test_the_migration_backfills_every_tool_that_already_names_a_craft():
    sql = _migration_sql()

    assert 'INSERT INTO "ToolCraft"' in sql
    assert 'FROM "ToolDocumentation" t' in sql
    assert 'WHERE t."craftId" IS NOT NULL' in sql
    assert 'ON CONFLICT ("toolId", "craftId") DO NOTHING' in sql, (
        "a re-run of a half-applied migration would mint a second row for the same pair"
    )
    assert "md5(" in sql, (
        "the backfilled ids are random rather than derived from the pair, so a re-run cannot be "
        "idempotent on an index build that had not landed"
    )
    assert 't."createdAt"' in sql, (
        "the backfill stamps CURRENT_TIMESTAMP, so every link shares one instant and 'oldest first' "
        "over the whole table is arbitrary"
    )


def test_the_migration_and_the_prisma_model_describe_the_same_table():
    sql = _migration_sql()
    schema = _schema()
    model = schema.split("model ToolCraft {")[1].split("\n}")[0]

    assert "@@unique([toolId, craftId])" in model
    assert '"ToolCraft_toolId_craftId_key" ON "ToolCraft"("toolId", "craftId")' in sql
    assert "@@index([craftId])" in model
    assert '"ToolCraft_craftId_idx" ON "ToolCraft"("craftId")' in sql
    assert '"ToolCraft_toolId_idx"' not in sql, (
        "the redundant toolId index from 20260618150000 has been reproduced here — schema.prisma "
        "does not declare it, so `prisma migrate diff` would report drift for ever"
    )
    assert model.count("onDelete: Cascade") == 2
    assert sql.count("ON DELETE CASCADE ON UPDATE CASCADE") == 2


def test_the_join_table_mirrors_the_artisan_one_column_for_column():
    schema = _schema()
    craft = schema.split("model ToolCraft {")[1].split("\n}")[0]
    artisan = schema.split("model ToolArtisan {")[1].split("\n}")[0]

    def _columns(model: str) -> list[str]:
        rows = [
            line.strip()
            for line in model.splitlines()
            if line.strip() and not line.strip().startswith(("//", "@@"))
        ]
        return [row.split()[0] for row in rows][:4]

    assert _columns(craft) == ["id", "toolId", "craftId", "createdAt"]
    assert _columns(artisan) == ["id", "toolId", "artisanId", "createdAt"]
    assert "@default(cuid())" in craft and "@default(now())" in craft


def test_both_back_relations_are_declared_and_named_after_the_existing_one():
    schema = _schema()

    tool = schema.split("model ToolDocumentation {")[1].split("\n}")[0]
    assert "artisanLinks ToolArtisan[]" in tool
    assert "craftLinks   ToolCraft[]" in tool

    craft = schema.split("model Craft {")[1].split("\n}")[0]
    assert "toolLinks ToolCraft[]" in craft
    assert "tools     ToolDocumentation[]" in craft, (
        "the `craftId` column's own back-relation is gone — every existing craft-scoped query reads "
        "through it"
    )

    artisan = schema.split("model Artisan {")[1].split("\n}")[0]
    assert "toolLinks ToolArtisan[]" in artisan


def _artisan_backfill_sql() -> str:
    path = (
        BACKEND
        / "prisma"
        / "migrations"
        / "20260916090000_tool_artisan_primary_backfill"
        / "migration.sql"
    )
    assert path.exists(), "the ToolArtisan backfill migration is missing"
    return path.read_text(encoding="utf-8")


def test_the_artisan_backfill_gives_every_tool_its_own_artisan_a_link_row():
    sql = _artisan_backfill_sql()

    assert 'INSERT INTO "ToolArtisan"' in sql
    assert 'FROM "ToolDocumentation" t' in sql
    assert 'WHERE t."artisanId" IS NOT NULL' in sql
    assert 'ON CONFLICT ("toolId", "artisanId") DO NOTHING' in sql, (
        "a link the assignment endpoint already wrote would be duplicated, or the whole migration "
        "would fail on the unique index"
    )
    assert "md5(" in sql, (
        "the backfilled ids are random rather than derived from the pair, so a re-run cannot be "
        "idempotent and the rollback below cannot name its own rows"
    )
    assert 't."createdAt"' in sql, (
        "the backfill stamps CURRENT_TIMESTAMP, which would make the tool's OWN artisan the NEWEST "
        "of its links — the opposite of what `_assigned_artisans`' 'oldest first' means"
    )


def test_the_artisan_backfill_changes_no_structure_at_all():
    sql = "\n".join(
        line for line in _artisan_backfill_sql().splitlines() if not line.lstrip().startswith("--")
    )

    for forbidden in (
        "CREATE TABLE",
        "ALTER TABLE",
        "DROP TABLE",
        "DROP COLUMN",
        "ALTER COLUMN",
        "CREATE INDEX",
        "DROP INDEX",
        "UPDATE ",
        "DELETE ",
        "CONCURRENTLY",
    ):
        assert forbidden not in sql, f"{forbidden} in a migration that claims to be one INSERT"
    assert sql.count("INSERT INTO") == 1
