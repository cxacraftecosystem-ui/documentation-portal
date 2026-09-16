"""One documented tool, several crafts — and the single `craftId` column still means what it did.

WHAT THIS PINS. `ToolDocumentation` carried ONE `craftId` and one free-text `craftName`, so a chisel
used by a block printer and by a dyer had to be documented twice and the two rows drifted apart on
every later correction. Migration 20260915100000 added `ToolCraft`, the twin of the `ToolArtisan`
table that already solved the same problem for artisans, and the record body gained `craftIds` and
`artisanIds` beside the singular columns.

THE COLUMNS ARE NOT RETIRED AND THAT IS THE WHOLE RISK. `craftId` still holds the FIRST selected
craft and `craftName` the selected names joined ", " in the same order, because every filter, index,
report, carry-forward and data-browser branch in this product reads one of the two. A change that
"tidied" either into the join table would empty a craft-scoped count with nothing on screen to say
why — this repository's most repeated defect. So the derivation is asserted here field by field.

THREE ANSWERS, AND TELLING THEM APART IS THE CONTRACT:

  * absent  -> leave the stored links alone (every client that predates the keys lands here);
  * ``[]``  -> no links; on a PATCH this DELETES every link row;
  * a list  -> exactly these links, in this order.

``null`` is a 422 at the schema, because ``clean_data`` drops a ``None`` for anything outside
``CLEARABLE_KEYS`` and these are not columns — so an explicit null could not be told from an absent
key by the time the route sees it, and "clear the links" would silently become "leave them alone".

NO DATABASE. Every delegate is a recording stub; the routes, the clean, the provenance merge and the
ordering are the real ones. There is no Postgres on a machine running this suite, so a DB-backed
version of this could not be run and therefore could not be trusted.
"""

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
    """A stored record answering ``None`` for any column a test did not set."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


class _Delegate:
    """One Prisma model delegate, recording every read and write aimed at it."""

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
        # ``{"toolId": ...}`` is how the link delegates are read — the route asks each of them for
        # one tool's rows. Honoured rather than ignored so a test can put two tools' links in one
        # delegate and still see the route read only its own.
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
    """The fake Prisma client. Only the delegates a driven route touches are provided."""


class _Payload(SimpleNamespace):
    """A stand-in for a record schema: attribute access plus ``model_dump``."""

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
    """Run ``create_tool`` against recording stubs and hand back the fake client."""
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
    """Stored ``ToolCraft``/``ToolArtisan`` rows, oldest first, one second apart.

    THE TIMESTAMPS ARE SPACED HERE AND NOT IN LIFE, deliberately: a real link set goes in as one
    ``create_many`` and every row of it shares one ``createdAt``. Spacing them is how a test can tell
    an ordering that reads ``createdAt`` from one that does not.
    """
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
    """Run ``update_tool`` against recording stubs and hand back the fake client.

    ``stored_crafts``/``stored_artisans`` ARE THE POINT OF THIS HELPER NOW. The route no longer asks
    "is this relation populated" with a row count — it reads the stored ids and compares them with
    what the caller sent, because both clients re-send an unchanged list on every save and refusing
    that is refusing the ordinary edit. A driver that could not seed the stored set could not tell
    the two apart.
    """
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
    # ``guard=`` runs the REAL field guard instead of the stub, for the one test whose subject is
    # which of the two guards refuses a given request.
    monkeypatch.setattr(tools, "guard_record_edit", guard or _guard)
    monkeypatch.setattr(tools, "apply_status_policy_update", _no_status_policy)
    monkeypatch.setattr(tools, "attach_location", _identity)
    monkeypatch.setattr(tools, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(tools, "hydrate_relations", _no_relations)
    monkeypatch.setattr(tools, "public_encode", lambda row, *_a, **_kw: dict(vars(row)))

    answer = asyncio.run(tools.update_tool("tol_1", _Payload(**fields), user or _user()))
    return client, answer


# --------------------------------------------------------------------------------------
# The wire contract: absent, [] and a list are three different answers
# --------------------------------------------------------------------------------------


def test_an_explicit_null_link_list_is_refused_by_name():
    """**THE ONE DISTINCTION THE WHOLE CONTRACT RESTS ON.**

    ``clean_data`` drops a ``None`` for any key outside ``CLEARABLE_KEYS``, and neither of these is a
    column so ``tools._CLEARABLE_COLUMNS`` cannot carry them either. A null would therefore arrive at
    the route as an ABSENT key — "leave the links alone" — and a client meaning "clear them" would
    get a 200 that did nothing. The refusal has to happen at the schema, and it has to say what to
    send instead.
    """
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
    """The route reads the three answers off the DUMPED body, so the distinction has to survive
    pydantic. ``exclude_unset`` is what carries it on the PATCH — the same precondition
    ``_CLEARABLE_COLUMNS`` rests on, asserted here for the two keys that are NOT columns."""
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
    """ORDER IS THE CONTRACT — element 0 becomes ``craftId`` — so de-duplication must preserve first
    occurrence and nothing may sort. A list that is empty AFTER cleaning stays ``[]`` and does not
    become ``None``: "every id I sent was blank" is a caller bug and must not be answered with
    "leave the stored links alone"."""
    from app.api.routes import tools

    assert tools._clean_link_ids(raw) == expected


# --------------------------------------------------------------------------------------
# CREATE
# --------------------------------------------------------------------------------------


def test_a_create_writes_one_link_row_per_craft_and_derives_both_columns(monkeypatch):
    """THE DERIVATION, FIELD BY FIELD. ``craftId`` is the FIRST ticked craft, because that is what
    every existing filter reads; ``craftName`` is every ticked craft's name joined ", " in the same
    order, because that string is the only ordinal the join table has — see ``_order_craft_links``.
    """
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
    """THE ACCEPTED COST, PINNED SO NOBODY RE-LITIGATES IT BY ACCIDENT. A hand correction typed into
    the craft-name box is lost while crafts are ticked, because the server must be able to re-derive
    ``craftName`` from ``craftIds`` alone — a body composed offline and replayed a fortnight later has
    to store a name that agrees with its links. The clients mitigate it by writing the joined value
    into the box on every selection change. The honest future fix is an explicit override flag, not a
    heuristic that guesses from the previous value."""
    client, _ = _drive_create(
        monkeypatch, _create_payload(craftIds=["crf_bandhani"], craftName="Bandhani Of Kutch")
    )

    assert client.tooldocumentation.creates[0]["craftName"] == "Bandhani"


def test_an_absent_list_writes_no_links_and_leaves_the_typed_columns_alone(monkeypatch):
    """EVERY CLIENT THAT PREDATES THESE KEYS LANDS HERE and must behave exactly as it did before."""
    client, _ = _drive_create(
        monkeypatch, _create_payload(craftName="Bandhani", craftId="crf_bandhani")
    )

    assert client.toolcraft.inserted == []
    assert client.toolartisan.inserted == []
    written = client.tooldocumentation.creates[0]
    assert written["craftId"] == "crf_bandhani"
    assert written["craftName"] == "Bandhani"


def test_an_empty_list_on_a_create_writes_no_links_and_derives_nothing(monkeypatch):
    """``[]`` means "no links", and on a CREATE there is nothing to delete — so the scalar columns
    come from the body exactly as they always did. The two answers only diverge on a PATCH."""
    client, _ = _drive_create(
        monkeypatch,
        _create_payload(craftIds=[], artisanIds=[], craftName="Bandhani", craftId="crf_bandhani"),
    )

    assert client.toolcraft.inserted == []
    assert client.tooldocumentation.creates[0]["craftName"] == "Bandhani"


def test_an_unknown_craft_is_a_404_before_a_single_row_is_written(monkeypatch):
    """VALIDATION COVERS THE WHOLE BATCH BEFORE ANYTHING IS WRITTEN — the rule
    ``assign_tool_artisans`` already states for its own batch. A refusal raised after the tool row
    had committed would leave a tool the researcher never gets an answer about."""
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
    """``artisanName`` AND ``place`` ARE NOT OVERRIDDEN, unlike ``craftName``, and the asymmetry is
    deliberate. Both are NOT NULL, both are already populated on every row, and both are things a
    researcher legitimately corrects by hand — "A. Khatri" to "Abdul Khatri". The client fills them
    from the first artisan's record on selection, exactly as the single-select always did, so the
    body's values stand."""
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
    """The foreign-artisan gate is ``_may_manage_tool_links``, which answers yes for the tool's owner
    — and on a CREATE the caller IS the owner, by the assignment two lines below the call. Asking the
    database would cost a round trip for a question with one answer."""
    client, _ = _drive_create(monkeypatch, _create_payload(artisanIds=["art_stranger"]))

    assert client.toolartisan.inserted == [[{"toolId": "tol_new", "artisanId": "art_stranger"}]]


# --------------------------------------------------------------------------------------
# PATCH
# --------------------------------------------------------------------------------------


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
    """DELETE THEN INSERT, NEVER A DIFF. ``ToolCraft`` carries nothing beyond the pair and its
    ``createdAt``, and ``_order_craft_links`` reads the order out of ``craftName`` rather than out of
    ``createdAt``, so a recreated row is indistinguishable from a kept one. A diff would cost a read
    to compute and buy nothing."""
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
    """``[]`` DELETES THE LINKS AND TOUCHES NO COLUMN. ``craftName`` is NOT NULL and untying the
    links is not the same act as forgetting which craft the tool documents — the name on the record
    is the last readable trace of it, exactly as ``artisanName`` survives a deleted artisan."""
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

    # AND ``[]`` AGAINST A TOOL THAT HAS NO LINKS IS NOT A DELETE, because there is nothing to
    # delete: the route now compares the request with what is stored, so "no links" asking for "no
    # links" sends no statement at all. Both clients send the key on every save, so this is the
    # common shape rather than a corner.
    already_empty, _ = _drive_update(monkeypatch, {"craftIds": [], "artisanIds": []}, _stored_tool())
    assert already_empty.toolcraft.deletes == []
    assert already_empty.toolartisan.deletes == []


def test_an_absent_list_on_a_patch_leaves_the_stored_links_untouched(monkeypatch):
    """AN UNSENT KEY IS STILL "LEAVE IT ALONE", which is the same rule ``_CLEARABLE_COLUMNS`` rests
    on. A save correcting a measurement must not silently untie a tool from its crafts."""
    client, _ = _drive_update(monkeypatch, {"remarks": "Rehandled in 2019"}, _stored_tool())

    assert client.toolcraft.deletes == []
    assert client.toolcraft.inserted == []
    assert client.toolartisan.deletes == []


def test_an_unknown_craft_on_a_patch_is_refused_above_the_edit_ledger(monkeypatch):
    """THE ORDERING THAT MATTERS IN A BACKEND WITH NO TRANSACTIONS. ``guard_record_edit`` ends in a
    COMMITTED ``RecordRevision`` row and there is nothing here to roll one back, so a 404 raised
    after it would leave a permanent ledger entry asserting an edit that was then turned down — the
    same argument ``assert_expected_updated_at`` is placed by."""
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
    """The gate is ``_may_manage_tool_links`` — admin, the tool's owner, a professor outranking its
    author, or an EDIT-tier grantee — and it is the SAME gate ``POST /tools/{id}/artisans`` applies,
    spelled once in ``_FOREIGN_ARTISAN_REFUSAL`` so a client cannot meet two different sentences for
    one rule.

    THE TIER LOOKUP IS STUBBED WHERE IT LIVES, not on this module, because ``_may_manage_tool_links``
    no longer keeps its own copy of the rule — it asks ``access.record_edit_privilege``, the same
    function ``guard_record_edit`` asks. That is the fix for a Professor being privileged for the
    tool's ROW and refused on its LINKS; a stub on ``tools`` would have hidden the delegation."""
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
    """THE RELATION-SHAPED HALF OF ``assert_can_contribute_fields``, and the rule
    ``update_workshop`` already applies to its two rosters. Without it, "linked crafts" would be the
    one thing on this form a stranger could wipe, by sending ``[]`` — a populated relation replaced
    by nobody's edit.

    AND THE REFUSAL HAS TO ARRIVE BEFORE THE SAVE DOES, which is what the last assertion is for and
    what this test used to leave open. The guard sat BELOW ``db.tooldocumentation.update``, so a
    refused contributor was told the save failed while the columns — and a ``RecordRevision`` row
    claiming the edit — had already committed. In a backend with no transactions, permanently.
    """
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
    """The same refusal, asserted on what it did NOT write. Separated from the test above because
    "403" and "nothing committed" are two claims, and the second is the one that was false."""
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
    """THE OTHER HALF OF THE SAME RULE: a contributor may add what nobody has answered yet. Only the
    RELATION guard is exercised here; the FIELD guard inside ``guard_record_edit`` is a separate
    question and is stubbed out, because deriving ``craftName`` from a tick puts a populated column
    in the payload and ``assert_can_contribute_fields`` would answer that one on its own terms."""
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
    """**THE DEFECT THIS GUARD SHIPPED WITH, PINNED AS FIXED.**

    ``assert_can_contribute_relation`` takes a BOOLEAN, and what the route handed it was
    ``db.toolcraft.count(...) > 0`` — "is this relation populated" — never "is this caller CHANGING
    it". Both tool clients send ``craftIds`` and ``artisanIds`` on every save, unchanged: the Android
    ``ToolCreateRequest`` carries no ``extraMetadata`` to be refused earlier, so an ordinary
    contributor filling in an empty Remarks box on somebody else's tool sailed through the field
    guard, committed the revision and the row, and then met a 403 about a craft picker they had never
    opened. Every retry did the same. They could never save that form again.

    A LIST THAT MATCHES WHAT IS STORED IS NOT A REPLACEMENT — the join tables carry nothing but the
    pair, so the same ids are the same rows.
    """
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
    # AND IT IS NOT REWRITTEN EITHER. The stored ids had to be read to answer the guard, so knowing
    # the set is unchanged is free — and deleting and re-inserting the same rows restamps every
    # ``createdAt``, which is the only thing ``_assigned_artisans``' "oldest first" has to go on.
    assert client.toolcraft.deletes == []
    assert client.toolcraft.inserted == []
    assert client.toolartisan.deletes == []
    assert client.toolartisan.inserted == []


def test_a_reordered_list_is_refused_by_the_guard_that_owns_what_changed(monkeypatch):
    """SETS, NOT LISTS — and this is what keeps that from being a hole. Re-ordering a populated
    selection changes ``craftId`` and ``craftName``, which are populated COLUMNS, so
    ``assert_can_contribute_fields`` inside ``guard_record_edit`` refuses it. The relation guard lets
    it past because the ROWS are the same rows; the field guard stops it because the values are not
    the same values. Driven with the REAL field guard, since that is the half doing the work."""
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
    """READ OFF THE ROUTE'S OWN SOURCE, as ``test_record_update_precondition`` does for the
    ``expectedUpdatedAt`` precondition and for the same reason: in a backend with no transactions the
    ORDER of these calls is the whole of the safety, and it is a one-line edit to lose.

    ``guard_record_edit`` ends in a COMMITTED ``RecordRevision`` and ``db.tooldocumentation.update``
    commits the columns. Neither may precede a refusal this route can still raise."""
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
    """ONE ANSWER TO ONE QUESTION. The relation guard has to know whether the caller is privileged
    BEFORE ``guard_record_edit`` runs, because that call commits the ledger row — so the route asks
    ``access.record_edit_privilege`` first and passes the answer down. Asking twice would be two
    cross-region round trips; deciding it locally would be a second implementation of the rule
    ``services/access`` exists to hold once."""
    import inspect

    from app.api.routes import tools
    from app.services import access

    source = inspect.getsource(tools.update_tool)
    assert source.count("record_edit_privilege(") == 1
    assert "privileged=privileged" in source, (
        "the early answer is not handed to guard_record_edit, so the two ask the database the same "
        "question twice — and could disagree"
    )
    # And the shared helper really is what guard_record_edit falls back on, so every route that does
    # NOT ask early keeps exactly the behaviour it always had.
    assert "record_edit_privilege(record, user, record_type)" in inspect.getsource(
        access.guard_record_edit
    )


def test_a_privileged_editor_pays_for_no_count_they_cannot_be_refused_by(monkeypatch):
    """NO COUNT IS READ ON THIS PATH AT ALL ANY MORE, by anybody. What the guard needs is the stored
    IDS — to tell an unchanged re-send from a replacement — and ``_resolve_tool_links`` reads those
    in the SAME parallel wave as the craft and artisan lookups it was already making. A count on top
    would be a cross-region round trip bought for a question that wave has already answered.
    Asserted rather than left to review, because "it is only one query" is how a save comes to cost
    six."""
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


# --------------------------------------------------------------------------------------
# The foreign-artisan gate: what is being ADDED, and who counts as privileged
# --------------------------------------------------------------------------------------


def test_re_sending_an_already_linked_foreign_artisan_is_not_an_assignment(monkeypatch):
    """**THE SECOND HALF OF THE SAME SHIPPED DEFECT, AND THE MORE SEVERE ONE.**

    The gate used to judge every id in ``artisanIds``, so a tool linked to an artisan the caller had
    not created refused EVERY save by anyone who was not its owner, an admin or an EDIT-grantee —
    including a Professor editing a junior researcher's record, which the rest of this codebase
    deliberately permits. Both clients send the list unchanged on every save, so fixing a typo in the
    remarks met a 403 about an artisan picker that was never opened, on every retry, for ever.

    KEEPING A LINK IS NOT ASSIGNING ONE. The assignment happened when the row was written, by
    somebody who was allowed to write it.
    """
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
    """The narrowing is to the ids being ADDED, not a repeal. An id that is not already linked is an
    assignment and is judged exactly as it always was."""
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
    """**A PROFESSOR WAS PRIVILEGED FOR THE ROW AND UNPRIVILEGED FOR ITS LINKS.**

    ``_may_manage_tool_links`` kept its own three-clause copy of "who may change what somebody else
    populated" — admin, owner, EDIT-grant — and left out the fourth: ``may_edit_lower_ranked_record``,
    "a professor may edit the data of anyone ranked below them", which ``guard_record_edit`` has
    honoured since it was written. Its own docstring claimed the two agreed. They did not, and a
    Professor could not save a junior researcher's tool at all once it was linked to an artisan they
    had not created. It now DELEGATES, which is the only way two answers to one question stay one
    answer."""
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


# --------------------------------------------------------------------------------------
# The replay, and the children a create can commit without
# --------------------------------------------------------------------------------------


def test_a_replayed_create_repairs_empty_links_and_never_touches_populated_ones(monkeypatch):
    """THE TRADE-OFF BETWEEN TWO SILENT FAILURES, now true of this route as it already was of
    workshops and processes. ``create_tool`` commits the tool row and THEN writes the links, and this
    backend has no transactions — so a create that died in between leaves a tool whose craft picker
    is empty, and every later replay would answer 201 from the row while the links stayed gone.

    The other direction is worse, which is why the repair is conditional: rewriting a POPULATED link
    set would reinstate what the queued entry believed a fortnight ago over whatever a PATCH has since
    made true. So an empty set is repaired and a populated one is never touched, EVEN IF THE IDS
    DIFFER — a difference means the links were edited after the create, and the edit is newer.
    """
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

    # Links missing -> repaired.
    client.toolcraft.count_value = 0
    asyncio.run(tools.create_tool(payload, _user()))
    assert client.tooldocumentation.creates == [], "the replay wrote a SECOND tool row"
    assert client.toolcraft.inserted == [[{"toolId": "tol_1", "craftId": "crf_bandhani"}]]

    # Links already there -> left exactly as they are.
    client.toolcraft.inserted.clear()
    client.toolcraft.count_value = 3
    asyncio.run(tools.create_tool(payload, _user()))
    assert client.toolcraft.inserted == []
    assert client.toolcraft.deletes == [], (
        "the repair path deleted links it was only allowed to add to"
    )


def test_a_repair_drops_an_id_whose_record_has_since_been_deleted(monkeypatch):
    """THE ONE PLACE THE REPAIR DEPARTS FROM THE CREATE PATH'S 404, and it is deliberate both ways.
    A craft deleted since the first landing would have taken its link row with it
    (``onDelete: Cascade``), so writing it back would be wrong; and 404-ing a replay would park a
    filled-in form in an outbox for ever, re-sent into the same refusal on every pass."""
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


# --------------------------------------------------------------------------------------
# Reading them back
# --------------------------------------------------------------------------------------


def test_the_tool_relations_carry_the_craft_links_with_their_craft(monkeypatch):
    """A picker cannot tick a box from an id alone — it needs the name — so the nested ``craft`` is
    part of the contract, and ``INCLUDE`` is derived from the same tuple so a write answers with the
    same shape a read does."""
    from app.api.routes import tools

    assert [rel.field for rel in tools._LINK_RELATIONS] == ["artisanLinks", "craftLinks"]
    craft_links = next(rel for rel in tools.RELATIONS if rel.field == "craftLinks")
    assert (craft_links.model, craft_links.key, craft_links.many) == ("toolcraft", "toolId", True)
    assert craft_links.include == {"craft": True}
    assert tools.INCLUDE["craftLinks"] == {"include": {"craft": True}}


def test_craft_links_come_back_in_the_order_the_craft_name_records():
    """THE JOIN TABLE HAS NO ORDINAL and the order is what makes ``craftId`` predictable: element 0
    of the list the client sent is the column every filter reads. ``craftName`` is written from the
    same list in the same order, so it IS the ordinal."""
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
    """A craft RENAMED since the save, or a row written by a client that predates this rule, is not
    findable in the string. It goes last, in ``createdAt asc, id asc`` — stated rather than left to
    whatever order the database returned, because "the leftovers come last, oldest first" is
    something a client can render and "whatever Postgres felt like" is not."""
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
    """Every tool in this repository predates the join table, so "no links at all" and "a link whose
    craft relation did not load" are the ORDINARY shapes, not edge cases. A sort that threw on one of
    them would take down ``GET /tools`` for the whole corpus."""
    from app.api.routes import tools

    tools._order_craft_links(dict(tool))


# --------------------------------------------------------------------------------------
# The name the SERVER writes, and the cap the schemas will accept back
# --------------------------------------------------------------------------------------


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
    """**THE CAP THAT REFUSED WHAT THE ROUTE ITSELF HAD WRITTEN.**

    ``craftName`` was bounded at 180 on ``ToolCreate``/``ToolUpdate`` — the bound a SINGLE craft name
    carries (``CraftCreate.name``, same file) — while the route replaced whatever the body sent with
    every linked craft's name joined ", ". Two long craft names overflow 180 on their own and about
    seven ordinary ones do, so a researcher ticking a realistic selection met
    ``craftName: String should have at most 180 characters`` on a box they had not typed in. Worse,
    a body that slipped past it (a hand-shortened name, or any client sending ``craftIds`` with a
    short ``craftName``) stored the full join anyway — and from then on EVERY later PATCH of that
    tool 422'd on a value the researcher never wrote.

    Seven names of 26-ish characters is the repository's own shape: "Ajrakh Hand Block Printing".
    """
    from app.schemas.records import TOOL_CRAFT_NAME_MAX, ToolUpdate

    client, _ = _drive_create_with_crafts(
        monkeypatch, _many_crafts(7, "Ajrakh Hand Block Printing"), [f"crf_{i}" for i in range(7)]
    )

    stored = client.tooldocumentation.creates[0]["craftName"]
    assert len(stored) > 180, "the example no longer exercises the old cap — lengthen the names"
    assert len(stored) <= TOOL_CRAFT_NAME_MAX
    # THE PROPERTY THAT MATTERS, and the one the two literals used to break: what the route WRITES
    # must be something the schema will take back, or the record can never be edited again.
    assert ToolUpdate(craftName=stored).craftName == stored


def test_what_the_route_can_derive_and_what_the_schemas_accept_are_one_number():
    """A CAP ON THE WIRE AND A CAP ON THE DERIVATION ARE THE SAME CAP, imported rather than retyped.
    Two literals is how these drifted the first time; a test that only checked today's value would
    let them drift again in step."""
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
    """A BOUND HAS TO REFUSE SOMETHING, and what it refuses must be refused BEFORE the write — the
    same rule as the unknown-craft 404 beside it. Truncating instead would store a craft list that
    disagrees with the links, and letting it through would put the record back in the state this
    whole fix is about. The message names the count and the length, because "shorten the craft name"
    is not advice anybody can act on for a box the server fills in."""
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


# --------------------------------------------------------------------------------------
# artisanLinks: the order, and the column it decides
# --------------------------------------------------------------------------------------


def test_artisan_links_lead_with_the_one_the_artisan_id_column_names():
    """**THE READ THAT SILENTLY RE-POINTED A COLUMN.**

    ``artisanLinks`` had no ordering at all: ``hydrate_relations`` issues ``find_many`` with no
    ``order``, and Postgres returns rows in an UNSPECIFIED order — index order on one plan, physical
    heap order on another, and after ``_replace_artisan_links``' delete-then-insert the heap order can
    be anything free space allowed. Both tool forms seed their artisan multi-select from this list and
    send it back as ``artisanIds``; ``_resolve_tool_links`` derives ``artisanId`` from element 0. So a
    researcher who opened a tool, fixed a typo in the remarks and saved could re-point ``artisanId`` at
    a different person — while ``artisanName`` and ``place``, which this route deliberately does not
    re-derive, kept naming the original. Every report keyed on the id then disagreed with every sheet
    cell printing the name.

    ``createdAt asc`` ALONE COULD NOT FIX IT, which is why the pin comes first: a link set goes in as
    ONE ``create_many``, so every row of one save shares one ``createdAt`` and the tie falls to a
    random cuid.
    """
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
    # WHICH IS THE WHOLE POINT: re-sending what was read back derives the SAME column.
    assert tool["artisanLinks"][0]["artisanId"] == tool["artisanId"]


def test_the_rest_of_the_artisan_links_are_oldest_first_then_by_id():
    """Stated rather than left to the database, for the reason ``_order_craft_links`` states its own
    leftovers rule: "the others follow, oldest first" is something a client can render, and "whatever
    Postgres felt like" is not. ``id`` is the tiebreak that makes it total, because one save's links
    all share one timestamp."""
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
        # A TOOL WHOSE ``artisanId`` NAMES NO LINK AT ALL — the ordinary shape for every tool
        # recorded before the record body carried ``artisanIds``, and the shape migration
        # 20260916090000 exists to retire. It must fall through to the stated order, not throw.
        {"artisanId": "art_never_linked", "artisanLinks": [{"id": "l1", "artisanId": "art_2"}]},
    ],
)
def test_the_artisan_ordering_survives_every_shape_a_tool_can_actually_have(tool):
    from app.api.routes import tools

    tools._order_artisan_links(dict(tool))


def test_both_link_lists_are_ordered_on_every_read_path():
    """LIST, DETAIL, POST AND PATCH ALL GO THROUGH THESE TWO FUNCTIONS, which is why the ordering
    lives in them rather than at four call sites. A read path that skipped one would hand a client a
    different "first artisan" from the one the save it just made stored."""
    import inspect

    from app.api.routes import tools

    for encoder in (tools._tool_payload, tools._tool_page):
        source = inspect.getsource(encoder)
        assert "_order_craft_links(" in source
        assert "_order_artisan_links(" in source


def test_the_assigned_artisans_endpoint_has_a_total_order():
    """"Oldest first" is not an order while a whole link set shares one ``createdAt`` — which it
    always does, because ``create_many`` is one statement. The second key does not make the order
    meaningful; it makes it the SAME on every call, which is what a caller diffing two responses
    needs."""
    import inspect

    from app.api.routes import tools

    source = inspect.getsource(tools._assigned_artisans)
    assert 'order=[{"createdAt": "asc"}, {"id": "asc"}]' in source


def test_reading_a_tool_back_and_saving_it_unchanged_keeps_its_artisan(monkeypatch):
    """THE ROUND TRIP, END TO END, because the two halves of this fix only work together: the read
    orders the list, and the save derives the column from element 0 of what the client sends back."""
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

    # What the form seeds its multi-select with, and sends straight back on the next save.
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


# --------------------------------------------------------------------------------------
# GET /artisans?craftIds= — the multi-craft picker's roster
# --------------------------------------------------------------------------------------


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
    """REPEATED PARAMETERS AND ONE COMMA-JOINED VALUE, because the web and Android assemble query
    strings differently — the same reason ``resolve_workshop_ids`` beside it takes both. A scope that
    quietly covered everything because it was spelled the other way would look exactly like the
    control not working.

    ``None`` for absent/empty/all-blank means DO NOT FILTER, and that is deliberately distinct from
    an empty selection: the default state of a picker is "all crafts" and must not be spelled the
    same way as a mistake."""
    from app.services.record_filters import resolve_craft_ids

    assert resolve_craft_ids(raw) == expected


def test_the_plural_craft_scope_has_no_unassigned_sentinel():
    """``UNASSIGNED_WORKSHOP`` exists because a record legitimately belongs to no workshop and a
    reader wants to find those. ``craftIds`` serves ONE caller — the tool form's multi-craft artisan
    picker — whose question is "who practises these crafts", and ``ArtisanCreate`` demands a craft, so
    "artisans linked to no craft" is not an answer that picker can offer. Inventing a second spelling
    of a sentinel nothing sends is how two filters come to disagree about what it means."""
    from app.services.record_filters import UNASSIGNED_WORKSHOP, resolve_craft_ids

    assert resolve_craft_ids([UNASSIGNED_WORKSHOP]) == [UNASSIGNED_WORKSHOP], (
        "'none' is not reserved here — it is read as an ordinary craft id, which matches nothing"
    )


def test_the_plural_craft_filter_never_overwrites_the_singular_one():
    """INTO ``and_filters`` AND NEVER ``where["craftId"]``. The singular filter assigns that key
    directly, so a second assignment would silently discard whichever was written first — and the
    two are documented as BOTH NARROWING when both are sent. Read off the route's source because the
    bug is a one-line edit that no behavioural test without a database would catch."""
    source = (BACKEND / "app" / "api" / "routes" / "artisans.py").read_text(encoding="utf-8")
    body = source.split("async def list_artisans(")[1].split("\nasync def ")[0]

    assert 'and_filters.append({"craftId": {"in": resolved_crafts}})' in body
    assert body.count('where["craftId"] = craftId') == 1
    assert 'where["craftId"] = {"in"' not in body, (
        "the plural craft scope is assigning `where[\"craftId\"]`, which the singular filter above "
        "already assigns — one of the two is being silently discarded"
    )


# --------------------------------------------------------------------------------------
# The migration, and the schema it has to agree with
# --------------------------------------------------------------------------------------


def _migration_sql(*, statements_only: bool = False) -> str:
    path = BACKEND / "prisma" / "migrations" / "20260915100000_tool_craft_links" / "migration.sql"
    assert path.exists(), "the ToolCraft migration is missing"
    sql = path.read_text(encoding="utf-8")
    if not statements_only:
        return sql
    # The comment banner names the rollback (``DROP TABLE "ToolCraft"``) and quotes what the file
    # deliberately does NOT do, so the destructive-statement sweep has to read the STATEMENTS. This
    # repository's migrations carry more prose than SQL and that is the house style, not an accident.
    kept = [line for line in sql.splitlines() if not line.lstrip().startswith("--")]
    return "\n".join(kept)


def _schema() -> str:
    return (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8")


def test_the_migration_is_additive_and_touches_no_existing_table():
    """IT MUST BE SAFE TO RUN AGAINST A POPULATED PRODUCTION DATABASE. Not one column added, dropped
    or retyped on an existing table, not one constraint relaxed — the only ALTER is the one that adds
    this new table's own foreign keys."""
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
    """WITHOUT THE BACKFILL, the first save of any existing tool through the new multi-select finds
    ``craftLinks`` empty, ticks nothing, and the researcher's obvious repair — pick the craft again —
    is the one action that writes the link. A tool whose craft "disappeared" is indistinguishable, on
    screen, from a tool that never had one."""
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
    """A FILE THAT IS NOT WHAT ``prisma migrate diff`` WOULD EMIT is a database the schema disagrees
    with, and every later diff tries to "fix" it. The unique, the single secondary index and both
    cascades have to match the model text exactly."""
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
    """TWO JOIN TABLES OFF ONE PARENT THAT DISAGREE ABOUT THEIR OWN SHAPE is how a later reader comes
    to believe one of them means something the other does not. Same four columns, same unique, same
    single secondary index, same cascade on both sides."""
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
    """``Artisan`` already spells its ``ToolArtisan`` back-relation ``toolLinks``, so ``Craft`` spells
    its ``ToolCraft`` one the same way. A relation is unusable from Prisma without both ends, and a
    second name for the same kind of thing is a reader's problem for ever."""
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
    """**THE HALF 20260915100000 MISSED.**

    That file backfilled ``ToolCraft`` from ``ToolDocumentation.craftId`` and there was no equivalent
    for artisans, because ``ToolArtisan`` had only ever been written by the ADDITIVE
    ``POST /tools/{id}/artisans`` — which its own docstring says "never touches artisanId/
    artisanName/place". So a tool whose ``artisanId`` came from the old single-select and whose links
    came from that endpoint had a column naming one artisan and a link list that did not contain
    them. Both forms tick the LINKS, so the first PATCH of such a tool — even one that only fixes a
    typo — re-pointed ``artisanId`` at whichever artisan led the list. No ordering fix can reach
    that: the id genuinely is not in the list until this runs.
    """
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
    """ONE INSERT AND NOTHING ELSE. It runs against a table that has existed since 20260618150000,
    so there is no DDL here to argue about — which is also why it has nothing to say about
    CONCURRENTLY."""
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
