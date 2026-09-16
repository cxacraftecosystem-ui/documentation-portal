"""A subject can have their phone number removed, and the next nullable column stays removable.

``clean_data`` had no ``clearable`` argument in this repository until this wave, so EVERY nullable
scalar on EVERY record model was a 200 THAT DID NOTHING: the form showed the box empty, the save
reported success, and the stored value was still there on the next load. No error, no workaround, and
nothing anywhere that would notice. The case with no alternative path at all is retracting personal
information a subject has asked to have removed — a researcher told to delete an artisan's phone
number could not do it, and the API told them it had worked.

WHY THE ROUTES ARE DRIVEN HERE AND NOT THE HELPER. Every assertion in this module would still pass
with the ``clearable=`` deleted from the route if it were written against ``clean_data`` directly,
because such a test hands the helper its own tuple. The failure is per-NAME as well as per-route —
dropping ``email`` from the artisan tuple and leaving the other eleven would pass a test that only
checked ``phone`` — so every declared column gets its own drive.

THE OTHER HALF, AND THE ONE WAY THIS FIX COULD DO HARM. ``clearable`` is only sound on a route that
dumps with ``exclude_unset=True``: that is what makes a present key mean "the caller sent this". A
route that stopped doing it would start writing an explicit NULL over stored data for every optional
box the client merely left blank — silently, on every save. So each route's dump is asserted here as
well, by intercepting the ``model_dump`` kwargs the route actually passes.

NO DATABASE. Every collaborator that would touch Postgres is replaced with a recording stub; the
clean, the provenance merge and the workshop stamps are the real ones. There is no Postgres on a
machine running this suite, so a DB-backed version of this could not be run and therefore could not
be trusted.

TWO ROUTES ARE NAMED HERE AS NOT COVERED, and each has a test of its own saying so: ``workshops``
(no tuple, mirroring the sibling repository) and ``crafts`` (a file this workstream does not own).
A route absent from the completeness table below is a route this net cannot see, which is a
different failure from a name missing from a tuple — so the gaps are written down rather than left
to be noticed.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest


class _Row:
    """A stored record that answers ``None`` for every column a test did not set.

    The provenance merge and the field guard ask a record for whichever columns the payload carries,
    so a ``SimpleNamespace`` would raise on the ones a test does not care about.
    """

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):  # only reached for names __init__ did not set
        return None


class _Writes:
    """One Prisma model delegate, recording the ``data`` of every write aimed at it."""

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
    """The fake Prisma client. Only the delegates a driven route touches are provided."""


class _Payload:
    """A stand-in for the pydantic update model, recording how the route dumped it.

    Only what the route reads is provided. ``dumped_with`` is the point of the class: the route's own
    ``exclude_unset`` is the precondition of everything else in this module, so it is observed rather
    than assumed.
    """

    def __init__(self, fields: dict[str, Any], **attributes):
        self._fields = dict(fields)
        self.dumped_with: dict[str, Any] = {}
        self.model_fields_set = set(fields)
        for name, value in attributes.items():
            setattr(self, name, value)

    def __getattr__(self, name):  # payload attributes the route reads directly
        return self._fields.get(name)

    def model_dump(self, **kwargs):
        self.dumped_with = kwargs
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self._fields.items() if k not in excluded}


def _editor():
    """An ordinary researcher who is also the record's author, so the field guard is not the subject
    of these tests — the clean is."""
    return _Row(id="usr_7", name="R. Menon", role="RESEARCHER")


async def _privileged(_record, _user, _data, _kind, **_kwargs):
    """``guard_record_edit``'s stand-in: the author may edit, and no revision row is written.

    ``**_kwargs`` absorbs ``privileged=``, which ``routes/tools`` passes because it has to ask the
    question EARLIER than this call — its two link relations are guarded above the write, and
    ``guard_record_edit`` commits a ledger row. Every other route here omits it."""
    return True


async def _no_status_policy(_user, _record, _data):
    return None


async def _no_relations(_rows, _relations):
    return None


async def _no_workshop_check(_user, _workshop_id):
    return None


# --------------------------------------------------------------------------------------
# The four record PATCH routes this workstream wired
# --------------------------------------------------------------------------------------


def _drive_artisan(monkeypatch, fields: dict[str, Any], stored: _Row) -> tuple[Any, _Payload]:
    from app.api.routes import artisans

    writes = _Writes()

    async def _require_record(_delegate, _record_id):
        return stored

    async def _resolve_craft_id(data, _user):
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
    # A DICT AND NOT THE ROW, unlike the three drivers above, because this route post-processes
    # the ENCODED payload: ``_tool_payload`` sorts ``craftLinks`` back into ``craftName``'s order
    # once the encoder has run. Handing it a row would be handing it something the real
    # ``public_encode`` never returns, and the failure would read as a bug in the route.
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

    # ``steps`` is a relation with its own write path; ``None`` is "the client did not send a step
    # list", which is the shape every scalar-only save has.
    payload = _Payload(fields, steps=None)
    asyncio.run(processes.update_process("prc_1", payload, _editor()))
    return writes, payload


#: Every column each record route declares clearable, and the driver that exercises that route.
#: Read off the route modules rather than retyped, so a name added to or removed from a route's
#: tuple is covered (or stops being covered) without this file having to be edited in step — a
#: hand-copied list here would be the same "the test agrees with itself" trap the module docstring
#: describes.
#:
#: THE FOURTH ELEMENT IS AN EDIT THAT TOUCHES NOTHING ON THE CLEARABLE LIST, so the "an unsent key is
#: not nulled" test has something harmless to send.
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
    """**THE SHIP-BLOCKER, PINNED AS FIXED.**

    The web client sends ``null`` for an emptied box — ``lib/forms.textValue`` returns ``null`` for a
    string that trims to nothing — so this is the exact body a researcher produces by selecting a
    phone number and pressing Save.
    """
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
    """AN ABSENT KEY IS STILL "LEAVE IT ALONE", which is the whole difference ``clearable`` rests on.

    Declaring a column clearable must not make an untouched column clearable too: the body below
    carries one edit and nothing else, and a save that also wrote NULL over every other optional
    would be a far worse defect than the one this wave fixed.
    """
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
    """THE PRECONDITION ``clearable`` IS ONLY SOUND UNDER, ASSERTED RATHER THAN ASSUMED.

    ``clean_data``'s docstring states it: pass ``clearable`` only from a route that dumps with
    ``exclude_unset=True``. Without it, pydantic emits every optional the client did not send as
    ``None``, those nulls now survive the clean, and the save writes an explicit NULL over stored
    data for every box the researcher merely left blank. That is the one way this fix could do harm,
    and it would be silent — hence a test of its own, on every route that passes the argument.
    """
    stored = _Row(id="rec_1", createdById="usr_7", status="PENDING", extraMetadata={})

    _writes, payload = driver(monkeypatch, dict(untouched), stored)

    assert payload.dumped_with.get("exclude_unset") is True, (
        f"{name}: the PATCH no longer dumps with exclude_unset=True, so its `clearable=` tuple has "
        "become a licence to NULL every field the client did not send"
    )


def test_clearing_a_populated_field_is_still_refused_for_a_non_author(monkeypatch):
    """THE GUARD THAT ONLY NOW HAS ANYTHING TO GUARD.

    ``deps.assert_can_contribute_fields`` claims a populated field is locked to non-privileged
    editors whether they try to CHANGE it or CLEAR it. Until this wave the CLEAR half could not fire
    on these routes: ``clean_data`` had already dropped the null, so the guard was handed a payload
    with no such key. Now that the null survives, the refusal is reachable — and it has to actually
    happen, or making these columns clearable would have handed every signed-in account the ability
    to blank another researcher's data.
    """
    from fastapi import HTTPException

    from app.api.routes import artisans
    from app.core.deps import assert_can_contribute_fields

    stored = _Row(id="art_1", createdById="somebody_else", phone="+91 98200 00000")
    stranger = _Row(id="usr_7", name="R. Menon", role="RESEARCHER")

    with pytest.raises(HTTPException) as refusal:
        assert_can_contribute_fields(stored, stranger, {"phone": None})

    assert refusal.value.status_code == 403
    assert "phone" in str(refusal.value.detail)
    # And the null is genuinely what the guard now receives from this route, rather than a shape
    # only this test constructs.
    assert "phone" in artisans._CLEARABLE_COLUMNS


def test_the_record_routes_do_not_share_one_clearable_list():
    """A GLOBAL LIST WOULD CORRUPT THREE MODELS TO FIX ONE, which is why ``clean_data`` takes the
    names per call. The tuples must therefore stay genuinely different: each is that model's own
    nullable columns, and the moment somebody "tidies" them into one shared constant the fix starts
    writing NULL into columns that are NOT NULL on the other tables.
    """
    from app.api.routes import artisans, processes, products, tools

    tuples = [
        artisans._CLEARABLE_COLUMNS,
        products._CLEARABLE_COLUMNS,
        tools._CLEARABLE_COLUMNS,
        processes._CLEARABLE_COLUMNS,
    ]
    assert len({id(t) for t in tuples}) == 4, "two routes are sharing one tuple object"
    assert len({tuple(t) for t in tuples}) == 4, "two routes declare identical contents"

    # ``heightInches`` is the one thing the product and tool lists AGREE about, as of migration
    # 20260913120100 — before it, the tool had no such column at all and the tool form was writing
    # an inches reading into the unit-less ``height``. So the separation is asserted from both
    # directions rather than through that one column.
    assert "heightInches" in products._CLEARABLE_COLUMNS
    assert "heightInches" in tools._CLEARABLE_COLUMNS
    # A tool measures thickness, weight and radius; a product has a selling price and a size.
    assert "thickness" in tools._CLEARABLE_COLUMNS
    assert "thickness" not in products._CLEARABLE_COLUMNS
    assert "sellingPrice" in products._CLEARABLE_COLUMNS
    assert "sellingPrice" not in tools._CLEARABLE_COLUMNS
    # And the general form, which survives any column being added to either side: a shared constant
    # is only possible if one tuple contains the other, so assert that neither does.
    assert not set(tools._CLEARABLE_COLUMNS) <= set(products._CLEARABLE_COLUMNS)
    assert not set(products._CLEARABLE_COLUMNS) <= set(tools._CLEARABLE_COLUMNS)
    assert "phone" in artisans._CLEARABLE_COLUMNS
    assert "phone" not in products._CLEARABLE_COLUMNS

    # Nor may a route reach the same end by pushing its own names into the global set.
    from app.services.records import CLEARABLE_KEYS

    for module in (artisans, products, tools, processes):
        assert not (set(module._CLEARABLE_COLUMNS) & CLEARABLE_KEYS)


def test_workshop_is_named_here_as_a_route_this_net_does_not_cover():
    """``routes/workshops.update_workshop`` passes no ``clearable``, so Workshop's four nullable
    scalars (description, notes, startDate, endDate) are still a 200 that does nothing. That mirrors
    the sibling repository exactly and is a DEBT, not a blessing — this test exists so the gap is a
    named decision in the suite rather than an omission a reader has to notice.

    IT ALSO STOPS A PLAUSIBLE WRONG FIX. ``workshops`` has no ``_CLEARABLE_COLUMNS`` module constant,
    so "completing the set" by copying the five-route template into this route is not a behaviour
    change, it is a NameError at import time: the whole app fails to boot. Two of the four columns
    are the reason it is not a one-liner either — ``startDate``/``endDate`` pass through
    ``normalize_workshop_dates``, which has never been asked whether ``None`` is an instruction.
    """
    import inspect

    from app.api.routes import workshops

    assert "clearable=" not in inspect.getsource(workshops.update_workshop)
    assert not hasattr(workshops, "_CLEARABLE_COLUMNS")


def test_crafts_is_named_here_as_the_route_this_workstream_could_not_reach():
    """``routes/crafts.update_craft`` has the same defect and is NOT fixed here, because the file
    belongs to another workstream running in parallel and a cross-agent edit silently destroys work.

    So ``Craft.localName``/``category``/``description``/``place`` are still a 200 that does nothing,
    and ``Craft.place`` is the sharpest illustration in the schema of why the tuple is per model: it
    is ``String?`` there and NOT NULL on the three record models beside it, so one shared constant
    could not serve both. The fix is the same shape as the four above — a module ``_CLEARABLE_COLUMNS``
    and a ``clearable=`` at the call — and it is written into this change's handoff. This test fails
    the day somebody lands it, which is the point: the name then belongs in ``cases`` below.
    """
    import inspect

    from app.api.routes import crafts

    assert "clearable=" not in inspect.getsource(crafts.update_craft), (
        "crafts.update_craft now passes `clearable=` — add ('Craft', CraftUpdate, "
        "crafts._CLEARABLE_COLUMNS) to the completeness table below and delete this test"
    )


# --------------------------------------------------------------------------------------
# The list is COMPLETE, not merely correct — read off the schema, not off the routes
# --------------------------------------------------------------------------------------


def _clearable_argument_of(route) -> tuple[str, ...]:
    """The ``clearable=`` tuple a route hands ``clean_data``, read out of the route's OWN source.

    Every entry in ``cases`` below reads a module constant, for the reason that test's docstring
    gives: a retyped copy agrees with itself and catches nothing. This helper is here for a route
    that passes its tuple as a literal at the call site instead — none does today, and the assertion
    below is what makes that a visible fact rather than an assumption.

    Asserts it found exactly one, so the day a route grows a second ``clean_data`` call this fails
    loudly instead of silently checking the wrong one.
    """
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
    """The nullable SCALAR columns of one Prisma model, read out of ``schema.prisma``.

    Nullable RELATIONS are dropped: ``craft Craft?`` is not a column a payload can null, and the
    column behind it (``craftId``) is listed separately and is already globally clearable. Telling
    them apart by "is this type a declared model" rather than by a hand-kept exclusion list is what
    keeps a newly added relation from reading as a newly added clearable column.
    """
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
        if parts[1].rstrip("?") in models:  # a nullable relation, not a scalar column
            continue
        columns.add(parts[0])
    return columns


#: A global name that lands on a column which is NOT NULL on ONE model. ``clean_data`` unions
#: :data:`records.CLEARABLE_KEYS` with whatever the route passes and a route cannot subtract from it,
#: so for these the null survives the clean and the ROUTE has to refuse it by hand. Every entry here
#: is a debt, not a blessing, and the assertion that reads this table is the only thing keeping the
#: completeness net from going blind to the whole class: an entry may be added only together with a
#: test below that drives the route and proves the refusal.
#:
#: ``Process.productId`` — ``productId`` belongs in the global set because it is a nullable
#: back-reference on the models that merely POINT AT a product, and it is ``String`` (NOT NULL) on
#: ``Process`` because a process is documentation OF a product and cannot be orphaned.
_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN = {
    "Process": frozenset({"productId"}),
}


def test_a_process_patch_refuses_to_clear_the_product_it_belongs_to(monkeypatch):
    """THE ONE ENTRY IN ``_GLOBAL_CLEARABLE_ON_A_NOT_NULL_COLUMN``, DRIVEN RATHER THAN ASSERTED.

    ``Process.productId`` is NOT NULL, and ``productId`` is in the GLOBAL ``CLEARABLE_KEYS`` — so
    ``{"productId": null}`` keeps its key through ``clean_data`` on this route however carefully
    ``processes._CLEARABLE_COLUMNS`` leaves the name out. Before ``update_process`` grew the branch
    this test drives, that null fell into ``require_record(db.productdocumentation, None)``: a lookup
    for a product with no id, whose kindest outcome is a 404 blaming a product for not existing when
    what the caller actually asked for was to orphan a process.

    422 is the honest answer, and it has to be raised BEFORE anything is written.
    """
    from fastapi import HTTPException

    stored = _Row(id="prc_1", createdById="usr_7", status="PENDING", productId="prd_1")

    with pytest.raises(HTTPException) as refusal:
        _drive_process(monkeypatch, {"productId": None}, stored)

    assert refusal.value.status_code == 422
    assert "product" in refusal.value.detail.lower()


def test_moving_a_process_to_another_product_still_checks_that_product_exists(monkeypatch):
    """THE OTHER HALF OF THE SAME BRANCH, so the refusal above cannot have been bought by breaking
    the move. A real id must still be looked up before the row is written — the guard is a refusal of
    ``None``, not of the key."""
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
    """THE TEST THAT CATCHES THE **NEXT** ``phone``, WHICH NONE OF THE ABOVE DO.

    Everything before this point proves that the names a route DOES declare work. Not one of them
    would notice a nullable column being added to a model, wired into its update schema, and never
    added to the tuple — which is precisely how ``phone``, ``email``, ``address`` and ``notes`` came
    to be a 200 that did nothing in the first place. The gap is silent by construction: the field
    saves, the API answers 200, and only the subject who asked to be forgotten ever finds out.

    So the expected list is DERIVED — nullable scalars from ``schema.prisma``, intersected with what
    the update schema actually accepts, minus the exemptions below — and compared with what the route
    declares. A test that retyped the names would agree with itself and catch nothing.

    THE EXEMPTIONS, EACH ONE VERIFIED RATHER THAN ASSERTED BY THE ROUTE'S OWN COMMENT:

      * ``records.CLEARABLE_KEYS`` **intersected with this model's own nullable columns** — those are
        already clearable here, so naming them again would be a duplicate rather than a fix.
        Imported, not retyped, so the two cannot drift apart. THE INTERSECTION IS LOAD-BEARING:
        subtracting the global set WHOLESALE would also swallow every global name that lands on a
        column which is NOT NULL on this particular model, which is exactly the class the third
        assertion below exists to catch.
      * ``extraMetadata`` — naming it would be INERT, not merely redundant. All four routes call
        ``merge_field_provenance`` after the clean, and it ends by either assigning
        ``new_data["extraMetadata"]`` or popping the key, so a null cannot reach Prisma through it
        whatever the tuple says.
      * the measurement trio — written by ``services/media_queue``, never by a form.
        ``records.PROVENANCE_SKIP_FIELDS`` already classes all three as system-managed.
      * ``expectedUpdatedAt`` — NOT A COLUMN AT ALL. It is a question popped out of the body by
        ``records.take_expected_updated_at`` before anything reads ``data``, so there is nothing for
        a null to write. It appears in ``sendable`` only because the update schema accepts it.

    An exemption is a decision, so adding one has to be a deliberate edit HERE, with a reason, and
    not a name quietly missing from a route.

    ``Workshop`` AND ``Craft`` ARE ABSENT FROM ``cases`` AND EACH HAS A TEST SAYING SO ABOVE. A route
    missing from this list is a route this net does not cover, which is a different failure from a
    name missing from a tuple — so neither gap is left to be inferred from an absence.
    """
    from app.api.routes import artisans, processes, products, tools
    from app.schemas.records import ArtisanUpdate, ProcessUpdate, ProductUpdate, ToolUpdate
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
    )
    for model, schema, declared in cases:
        nullable = _nullable_columns(model)
        sendable = {field.alias or name for name, field in schema.model_fields.items()} | set(
            schema.model_fields
        )
        expected = (nullable & sendable) - (CLEARABLE_KEYS & nullable) - system_managed

        missing = sorted(expected - set(declared))
        assert not missing, (
            f"{model}: {missing} are nullable columns the update schema accepts, and no route "
            "declares them clearable — sending null for one is a 200 that does nothing. Add them to "
            "the route's `_CLEARABLE_COLUMNS`, or add an exemption here saying why the null cannot "
            "reach the database anyway."
        )
        # The other direction matters just as much: a name that is NOT nullable on this model would
        # turn an emptied box into a constraint violation, and one the schema does not accept is
        # dead weight a later reader would trust.
        stray = sorted(set(declared) - expected)
        assert not stray, (
            f"{model}: {stray} are declared clearable but are not nullable columns this update "
            "schema can send. Clearing a NOT NULL column is a 500, not a retraction."
        )
        # AND THE THIRD DIRECTION, WHICH THE ROUTE'S OWN TUPLE CANNOT ANSWER FOR. ``clearable`` ADDS
        # to :data:`records.CLEARABLE_KEYS` and can never subtract from it, so a global name that is
        # NOT NULL on THIS model is clearable here no matter how carefully the route's tuple leaves
        # it out. Nothing above would see that, because such a name is not in the model's nullable
        # columns and so never enters ``expected`` at all.
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
    """THE NEAR-MISS WORTH PINNING. ``clean_data`` title-cases by COLUMN NAME and does not know which
    model a payload is bound for, so a prose column spelled ``name``, ``title``, ``place``,
    ``district`` or ``state`` is title-cased on every write with no error anywhere — and
    ``title_case=False`` is the only opt-out. None of the columns this wave adds is one of those
    spellings, and this test is what keeps that true for the next one.
    """
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
