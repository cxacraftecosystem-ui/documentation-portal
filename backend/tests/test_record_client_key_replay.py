"""A queued create that lands twice writes one row, and the second landing repairs nothing it should
not touch.

THE DUPLICATE THIS CLOSES. A queued create is POSTed, the server writes the row, and the answer is
lost on the way back — a tunnel, a captive portal, the process killed while the request was in
flight. The client learned nothing, so the entry is still queued and the next pass sends it again.
Both outboxes guard the case they can SEE (`createdId` on the web, `PendingEntry.createdId` on
Android) and neither can guard an answer that never arrived, because both are records of a REPLY.
`frontend/lib/offline.ts` names the missing piece itself: *"a few milliseconds of IndexedDB is as
small as that window gets without idempotency keys on the API."*

THE PART THAT IS EASY TO GET WRONG, AND WHICH HALF THIS FILE SPENDS ITS LENGTH ON. This backend has
NO transactions. A workshop commits its row and then writes two rosters; a process commits its row
and then writes its steps. So a create can half-succeed, and once a `clientKey` is on the committed
row every later replay would answer 200 from it while the children stay missing and the outbox
reports success — a quieter failure than the duplicate the key was added to prevent. The replay
branches therefore REPAIR EMPTY CHILDREN AND NEVER TOUCH POPULATED ONES, and both directions are
pinned below, because each has its own catastrophe:

  * re-running the roster writes unconditionally would ``delete_many`` a roster edited through PATCH
    while the entry sat in a queue for a fortnight;
  * re-running the step sync on a populated process would mint FRESH ProcessStep ids (a create body
    carries none, so every step lands in ``to_create`` and the originals are deleted), orphaning
    every step-linked MediaFile through the FK-less ``linkedRecordId``.

NO DATABASE. The delegates are recording stubs; the routes are the real ones.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException


class _Row:
    """A stored record answering ``None`` for any column a test did not set."""

    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _Delegate:
    """One Prisma model delegate, recording every read and write aimed at it."""

    def __init__(self, existing: Any = None, created: Any = None):
        self.existing = existing
        self.created_row = created
        self.find_unique_calls: list[dict[str, Any]] = []
        self.creates: list[Any] = []
        self.counted: list[dict[str, Any]] = []
        self.count_value = 0

    async def find_unique(self, **kwargs):
        self.find_unique_calls.append(kwargs)
        return self.existing

    async def create(self, data, **_kwargs):
        self.creates.append(data)
        return self.created_row if self.created_row is not None else _Row(id="new_1", **data)

    async def count(self, where=None, **_kwargs):
        self.counted.append(where or {})
        return self.count_value

    async def find_many(self, **_kwargs):
        return []

    async def delete_many(self, **_kwargs):
        return None

    async def create_many(self, **_kwargs):
        return None


class _Client(SimpleNamespace):
    pass


class _DatabaseTouched(Exception):
    """Something read a delegate this test declared unreachable."""


class _Tripwire:
    """Stands in for ``db`` where a test's whole assertion is that NOTHING was read.

    The same shape ``tests/test_permission_matrix`` uses: reading any delegate off it raises, unless
    the test explicitly hands that one over with ``preload``.
    """

    def __init__(self) -> None:
        object.__setattr__(self, "touched", False)
        object.__setattr__(self, "_preloaded", {})

    def preload(self, name: str, delegate: Any) -> None:
        self._preloaded[name] = delegate

    def __getattr__(self, name: str) -> Any:
        preloaded = object.__getattribute__(self, "_preloaded")
        if name in preloaded:
            return preloaded[name]
        object.__setattr__(self, "touched", True)
        raise _DatabaseTouched(name)


def _user(user_id: str = "usr_7"):
    return _Row(id=user_id, name="R. Menon", role="RESEARCHER")


class _Payload(SimpleNamespace):
    """A stand-in for a create schema: attribute access plus ``model_dump``."""

    def model_dump(self, **kwargs):
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self.__dict__.items() if k not in excluded}


async def _no_relations(_rows, _relations):
    return None


async def _no_workshop_check(_user, _workshop_id):
    return None


async def _identity(data):
    return data


def _product_payload(**overrides) -> _Payload:
    fields = dict(
        craftName="Bandhani",
        place="Bhuj",
        artisanName="A. Khatri",
        productName="Odhani",
        clientKey="key-1",
        workshopId=None,
        location=None,
        extraMetadata=None,
    )
    fields.update(overrides)
    return _Payload(**fields)


# --------------------------------------------------------------------------------------
# The absent key costs nothing at all
# --------------------------------------------------------------------------------------


def test_an_absent_key_costs_not_even_a_read(monkeypatch):
    """EVERY CLIENT SHIPPED TO DATE SENDS NO KEY, and must take exactly the path it took before this
    branch existed — not a cheaper one, not a slower one, the same one.

    Driven with a tripwire rather than with a counter: a test that merely counted ``find_unique``
    calls would pass if the route read some OTHER delegate on the way, and "no extra work at all" is
    the claim being made here. ``productdocumentation`` is preloaded so the create itself can run;
    the assertion is that the replay branch read nothing before it.
    """
    from app.api.routes import products

    delegate = _Delegate()
    tripwire = _Tripwire()
    tripwire.preload("productdocumentation", delegate)

    monkeypatch.setattr(products, "db", tripwire)
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    asyncio.run(products.create_product(_product_payload(clientKey=None), _user()))

    assert delegate.find_unique_calls == [], (
        "a create carrying no clientKey paid for a lookup it can never answer with — the absent-key "
        "branch in `client_key_replay` must return before the read"
    )
    assert len(delegate.creates) == 1


def test_an_empty_string_is_treated_as_absent(monkeypatch):
    """AN EMPTY STRING IS NOT AN IDENTITY. A row carrying ``""`` has no proof of anything, and two
    clients that both sent one would collide on the unique index over a value neither chose."""
    from app.api.routes import products

    delegate = _Delegate()
    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate))
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    asyncio.run(products.create_product(_product_payload(clientKey=""), _user()))

    assert delegate.find_unique_calls == []


# --------------------------------------------------------------------------------------
# The replay itself
# --------------------------------------------------------------------------------------


def test_the_product_route_answers_a_replay_without_reaching_a_single_gate(monkeypatch):
    """THE REPLAY IS ANSWERED ABOVE EVERY GATE AND EVERY WRITE, and that ordering is the point.

    On a replay the row already exists, so re-asking ``enforce_workshop_submission`` could only turn
    a create that SUCCEEDED into a 403 for a researcher whose workshop assignment was withdrawn in
    the meantime — and ``attach_location`` would mint a second, unreferenced ``Location`` row on
    every pass. Both are replaced here with functions that raise, so reaching either is the failure.
    """
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="usr_7", productName="Odhani", clientKey="key-1")
    delegate = _Delegate(existing=stored)

    async def _no_gate(*_args, **_kwargs):
        raise AssertionError("a gate ran on the replay path")

    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate))
    monkeypatch.setattr(products, "attach_location", _no_gate)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_gate)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    answer = asyncio.run(products.create_product(_product_payload(), _user()))

    assert answer is stored
    assert delegate.creates == [], "the replay wrote a SECOND product row"
    assert delegate.find_unique_calls[0]["where"] == {"clientKey": "key-1"}
    # The replay reads through the same include the create answers with, so the two responses are
    # byte-comparable — a client must not be able to tell a replay from a first landing.
    assert delegate.find_unique_calls[0]["include"] is products.INCLUDE


def test_another_accounts_key_is_a_403_and_never_their_record(monkeypatch):
    """A KEY IS UNGUESSABLE, SO THIS IS NOT A DEFENCE AGAINST AN ATTACKER — it is a defence against
    ANSWERING WITH THE WRONG PERSON'S RECORD if a key is ever copied between accounts or collides.

    201 would hand over a stranger's fieldwork; 409 would invite a retry that can only fetch the same
    answer. 403 is the honest one, and the row must not appear in the response at all.
    """
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="somebody_else", productName="Someone else's odhani")
    delegate = _Delegate(existing=stored)

    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate))
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    with pytest.raises(HTTPException) as refusal:
        asyncio.run(products.create_product(_product_payload(), _user()))

    assert refusal.value.status_code == 403
    assert "another account" in refusal.value.detail
    assert "Someone else's odhani" not in str(refusal.value.detail)
    assert delegate.creates == []


def test_a_lost_race_on_the_index_is_answered_from_the_row_that_won(monkeypatch):
    """THE CASE THE PRE-READ CANNOT CLOSE: two drains of the same queue in flight at once, each
    finding no row and each planning an INSERT. Only the index can settle that, so the create is
    wrapped and the violation is re-read rather than surfaced as a 500."""
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="usr_7", productName="Odhani")

    class _RacingDelegate(_Delegate):
        async def create(self, data, **_kwargs):
            self.creates.append(data)
            raise RuntimeError(
                'Unique constraint failed on the fields: (`clientKey`)'
            )

    delegate = _RacingDelegate()
    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate))
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    # The first read finds nothing (this pass lost the race); the second, after the violation, finds
    # the winner's row.
    reads = {"n": 0}

    async def _find_unique(**kwargs):
        delegate.find_unique_calls.append(kwargs)
        reads["n"] += 1
        return None if reads["n"] == 1 else stored

    delegate.find_unique = _find_unique

    answer = asyncio.run(products.create_product(_product_payload(), _user()))

    assert answer is stored
    assert len(delegate.creates) == 1, "the losing pass must not retry the insert"


def test_a_violation_on_another_column_still_raises():
    """A UNIQUE VIOLATION ON SOME OTHER COLUMN IS SOMEBODY ELSE'S PROBLEM ARRIVING THROUGH THIS DOOR.

    Treating every exception from a create as a possible replay would answer 201 with a row that has
    nothing to do with the failure — so the sniffer has to be narrow, and both halves of it are
    asserted: the word "unique" is not enough on its own, and neither is the column name.
    """
    from app.services.records import is_client_key_violation

    assert is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`clientKey`)")
    )
    assert not is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`name`)")
    )
    assert not is_client_key_violation(RuntimeError("connection reset by peer"))
    # A non-unique error that happens to mention the column must not be read as a replay either.
    assert not is_client_key_violation(RuntimeError("null value in column clientKey"))


# --------------------------------------------------------------------------------------
# The process replay, which is the one with children
# --------------------------------------------------------------------------------------


def _drive_process_replay(monkeypatch, stored, payload_steps, *, sync_allowed: bool):
    from app.api.routes import processes

    delegate = _Delegate(existing=stored)
    calls: list[Any] = []

    async def _sync_steps(process_id, steps):
        if not sync_allowed:
            raise AssertionError(
                "_sync_steps ran on a replay whose stored process already had steps — every step id "
                "would be reminted and every step-linked media file orphaned"
            )
        calls.append((process_id, list(steps)))

    async def _hydrate(row):
        return {"id": row.id, "steps": [{"id": s.id} for s in (row.steps or [])]}

    monkeypatch.setattr(processes, "db", _Client(process=delegate))
    monkeypatch.setattr(processes, "_sync_steps", _sync_steps)
    monkeypatch.setattr(processes, "_hydrate", _hydrate)
    monkeypatch.setattr(processes, "hydrate_relations", _no_relations)
    monkeypatch.setattr(processes, "require_record", _no_gate_require)
    monkeypatch.setattr(processes, "enforce_workshop_submission", _no_workshop_check)

    payload = _Payload(
        name="Dyeing",
        productId="prd_1",
        clientKey="key-1",
        steps=payload_steps,
        workshopId=None,
        extraMetadata=None,
    )
    answer = asyncio.run(processes.create_process(payload, _user()))
    return answer, calls, delegate


async def _no_gate_require(_delegate, _record_id):
    raise AssertionError("require_record ran on the replay path, below the branch that answers it")


def test_the_process_route_answers_a_replay_and_leaves_existing_steps_alone(monkeypatch):
    """A REPLAY AGAINST A PROCESS THAT ALREADY HAS STEPS MUST RETURN THOSE STEPS, WITH THEIR OWN IDS.

    THE DEFECT THIS GUARDS IS ID CHURN, NOT DUPLICATION, and the difference matters because it
    decides what the guard has to assert. ``ProcessStepInput.id`` is None on a create body, so
    ``_sync_steps`` would put every step in ``to_create``, keep nothing, and ``delete_many`` the
    originals: the same names in the same order, and every id NEW. ``MediaFile.linkedRecordId``
    addresses those ids with NO foreign key, and ``_hydrate`` matches a step's media against the
    CURRENT ids — so every photograph and clip captured against a step becomes an orphan: still in
    the bucket, still a row, attached to nothing, and undetectable afterwards.

    So the id equality below is the real assertion. A test that only counted the steps would pass
    against the broken version.
    """
    stored = _Row(
        id="prc_1",
        createdById="usr_7",
        clientKey="key-1",
        steps=[_Row(id="stp_1", name="Dyeing"), _Row(id="stp_2", name="Washing")],
    )

    answer, calls, delegate = _drive_process_replay(
        monkeypatch,
        stored,
        payload_steps=[_Row(id=None, name="Dyeing"), _Row(id=None, name="Washing")],
        sync_allowed=False,
    )

    assert [step["id"] for step in answer["steps"]] == ["stp_1", "stp_2"], (
        "the replay handed back different step ids — every media file linked to the old ones is now "
        "an orphan, because linkedRecordId carries no foreign key"
    )
    assert calls == []
    assert delegate.creates == [], "the replay wrote a SECOND process row"


def test_the_process_replay_repairs_a_process_whose_steps_never_landed(monkeypatch):
    """THE OTHER HALF, AND IT IS NOT SYMMETRY FOR ITS OWN SAKE.

    There is no transaction here: ``db.process.create`` commits, and a ``create_many`` that failed
    after it leaves a process with NO steps at all. Once a ``clientKey`` is on that row, a replay
    that refused to write steps would answer 200 from it for ever — the record exists, the API says
    yes, the outbox says sent, and the researcher's step list is simply gone.

    A stored process with zero steps is the only shape that failure leaves behind, and it is also the
    one shape in which writing the steps cannot churn an id, because there is none to churn.
    """
    stored = _Row(id="prc_1", createdById="usr_7", clientKey="key-1", steps=[])

    answer, calls, delegate = _drive_process_replay(
        monkeypatch,
        stored,
        payload_steps=[_Row(id=None, name="Dyeing")],
        sync_allowed=True,
    )

    assert len(calls) == 1, "a step-less replayed process was left step-less for ever"
    assert calls[0][0] == "prc_1"
    assert [step.name for step in calls[0][1]] == ["Dyeing"], (
        "the repair wrote something other than the steps the queued create was carrying"
    )
    assert delegate.creates == [], "the repair wrote a second process row"
    assert isinstance(answer, dict)


def test_a_replay_with_no_steps_in_the_payload_writes_nothing(monkeypatch):
    """AN EMPTY STORED LIST AND AN EMPTY PAYLOAD IS NOT A REPAIR, it is a process that genuinely has
    no steps — and calling the sync with an empty list would be a pointless round trip that also
    reads as though something were wrong."""
    stored = _Row(id="prc_1", createdById="usr_7", clientKey="key-1", steps=[])

    _answer, calls, _delegate = _drive_process_replay(
        monkeypatch, stored, payload_steps=[], sync_allowed=True
    )

    assert calls == []


# --------------------------------------------------------------------------------------
# The workshop replay, which has two rosters
# --------------------------------------------------------------------------------------


def _drive_workshop_replay(monkeypatch, stored, *, artisan_ids, craft_ids, roster_rows):
    from app.api.routes import workshops

    delegate = _Delegate(existing=stored)
    artisan_links = _Delegate()
    craft_links = _Delegate()
    artisan_links.count_value = roster_rows
    craft_links.count_value = roster_rows
    replaced: list[tuple[str, list[str]]] = []

    async def _replace_artisans(workshop_id, ids):
        replaced.append(("artisans", list(ids)))

    async def _replace_crafts(workshop_id, ids):
        replaced.append(("crafts", list(ids)))

    monkeypatch.setattr(
        workshops,
        "db",
        _Client(workshop=delegate, workshopartisan=artisan_links, workshopcraft=craft_links),
    )
    monkeypatch.setattr(workshops, "replace_workshop_artisans", _replace_artisans)
    monkeypatch.setattr(workshops, "replace_workshop_crafts", _replace_crafts)
    monkeypatch.setattr(workshops, "hydrate_relations", _no_relations)
    monkeypatch.setattr(workshops, "attach_location", _identity)
    monkeypatch.setattr(workshops, "public_encode", lambda row, *_a, **_kw: row)

    payload = _Payload(
        title="Bhuj toolkit workshop",
        place="Bhuj",
        date=None,
        startDate=None,
        endDate=None,
        artisanIds=artisan_ids,
        craftIds=craft_ids,
        clientKey="key-1",
        location=None,
        extraMetadata=None,
    )
    answer = asyncio.run(workshops.create_workshop(payload, _user()))
    return answer, replaced, delegate


def test_the_workshop_replay_repairs_empty_rosters_and_never_touches_populated_ones(monkeypatch):
    """BOTH DIRECTIONS, BECAUSE EACH HAS ITS OWN CATASTROPHE.

    ``replace_workshop_artisans`` and ``replace_workshop_crafts`` are ``delete_many`` +
    ``create_many``. Re-running one unconditionally would silently WIPE a roster edited through PATCH
    while the queued entry sat on a handset for a fortnight, and reinstate whatever that entry
    believed — a save the researcher did not make today, overwriting one they did. Never running it
    leaves a workshop whose create committed the row and then failed on the roster with an empty
    "Artisans attending" for ever, answered 200 from on every replay.

    So: repaired when empty, untouched when populated, EVEN IF THE PAYLOAD'S IDS DIFFER. A difference
    means the roster was edited after the create, and the edit is newer than the queue entry.
    """
    stored = _Row(id="wsh_1", createdById="usr_7", clientKey="key-1")

    _answer, repaired, delegate = _drive_workshop_replay(
        monkeypatch, stored, artisan_ids=["art_1"], craft_ids=["crf_1"], roster_rows=0
    )
    assert repaired == [("artisans", ["art_1"]), ("crafts", ["crf_1"])]
    assert delegate.creates == [], "the replay wrote a SECOND workshop row"

    _answer, untouched, _delegate = _drive_workshop_replay(
        monkeypatch,
        stored,
        artisan_ids=["art_9"],  # deliberately different from whatever the roster now holds
        craft_ids=["crf_9"],
        roster_rows=3,
    )
    assert untouched == [], (
        "a populated roster was rewritten from a queued create's ids — a roster edited through PATCH "
        "in the meantime has just been wiped"
    )


# --------------------------------------------------------------------------------------
# The wire contract
# --------------------------------------------------------------------------------------


def test_the_four_create_schemas_accept_a_key_and_default_it_to_none():
    """FOUR AND NOT SIX. ``Artisan.aadhaarNumber`` and ``Craft.name`` are @unique already, each with
    a pre-write 409 naming the row that holds the value, so a replayed create of either is refused by
    the dedup key that exists for exactly that purpose. A second mechanism beside either would be two
    guards that can disagree about what a duplicate is."""
    from app.schemas.records import (
        ArtisanCreate,
        CraftCreate,
        ProcessCreate,
        ProductCreate,
        ToolCreate,
        WorkshopCreate,
    )

    for schema in (WorkshopCreate, ProductCreate, ToolCreate, ProcessCreate):
        field = schema.model_fields["clientKey"]
        assert field.default is None, f"{schema.__name__}.clientKey must default to None"

    for schema in (ArtisanCreate, CraftCreate):
        assert "clientKey" not in schema.model_fields, (
            f"{schema.__name__} gained a clientKey — it is already idempotent under a better key, "
            "and two guards that can disagree about what a duplicate is are worse than one"
        )


def test_the_update_schemas_refuse_a_key_outright():
    """A CORRECTION CARRYING A KEY IS A 422 AN OUTBOX WOULD RE-ATTEMPT FOR EVER.

    ``APIModel`` is ``extra="forbid"``, so this is already true — and it is asserted rather than
    trusted, because the one-line change that would break it (declaring the field on an Update
    schema "for symmetry") looks harmless and is not: on Android the SAME ``*CreateRequest`` class is
    the PATCH body, so the day a key reaches a correction the queue starts re-attempting a refusal
    once per app run, on a prepaid connection.
    """
    from pydantic import ValidationError

    from app.schemas.records import (
        ArtisanUpdate,
        CraftUpdate,
        ProcessUpdate,
        ProductUpdate,
        ToolUpdate,
        WorkshopUpdate,
    )

    for schema in (
        ArtisanUpdate,
        CraftUpdate,
        WorkshopUpdate,
        ProductUpdate,
        ProcessUpdate,
        ToolUpdate,
    ):
        with pytest.raises(ValidationError):
            schema(**{"clientKey": "key-1"})


def test_the_key_is_never_attributed_to_the_researcher_as_a_field_they_filled_in():
    """IT IS BOOKKEEPING ABOUT A SEND, NOT A FIELD ANYBODY TYPED.

    ``merge_field_provenance`` stamps every non-empty key it is handed, and the web client's "Field
    contributions" panel builds its rows from whatever that object holds — so without the skip entry
    every replayable record would list a ``clientKey`` row attributing a v4 UUID to the researcher.
    It goes into a JSON column copied forward on every later edit, so it accumulates and is not
    trivially removable.
    """
    from app.services.records import CLIENT_KEY_FIELD, PROVENANCE_SKIP_FIELDS, merge_field_provenance

    assert CLIENT_KEY_FIELD in PROVENANCE_SKIP_FIELDS

    data = {"clientKey": "5f3b9c1e-0000-4000-8000-000000000000", "productName": "Odhani"}
    merge_field_provenance(data, _Row(id="usr_7", name="R. Menon"), previous=None)

    metadata = data.get("extraMetadata")
    provenance = {}
    if metadata is not None:
        # ``merge_field_provenance`` wraps the column in ``prisma.Json``; read through whichever
        # shape it used rather than asserting on the wrapper.
        raw = getattr(metadata, "data", metadata)
        provenance = (raw or {}).get("fieldProvenance", {})
    assert "clientKey" not in provenance
    assert "productName" in provenance, "the skip list swallowed a field that WAS typed"
