import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException


class _Row:
    def __init__(self, **columns):
        self.__dict__.update(columns)

    def __getattr__(self, name):
        return None


class _Delegate:
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
    pass


class _Tripwire:
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
    def model_dump(self, **kwargs):
        excluded = kwargs.get("exclude") or set()
        return {k: v for k, v in self.__dict__.items() if k not in excluded}


async def _no_relations(_rows, _relations):
    return None


async def _no_workshop_check(_user, _workshop_id):
    return None


async def _identity(data):
    return data


async def _nothing(*_args, **_kwargs):
    return None


async def _none_of_them(*_args, **_kwargs):
    return []


_NO_CRAFTS = SimpleNamespace(find_unique=_nothing, find_first=_nothing, find_many=_none_of_them)


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



def test_an_absent_key_costs_not_even_a_read(monkeypatch):
    from app.api.routes import products

    delegate = _Delegate()
    tripwire = _Tripwire()
    tripwire.preload("productdocumentation", delegate)
    tripwire.preload("craft", _NO_CRAFTS)
    tripwire.preload("craftspelling", _NO_CRAFTS)

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
    from app.api.routes import products

    delegate = _Delegate()
    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate, craft=_NO_CRAFTS, craftspelling=_NO_CRAFTS))
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    asyncio.run(products.create_product(_product_payload(clientKey=""), _user()))

    assert delegate.find_unique_calls == []



def test_the_product_route_answers_a_replay_without_reaching_a_single_gate(monkeypatch):
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="usr_7", productName="Odhani", clientKey="key-1")
    delegate = _Delegate(existing=stored)

    async def _no_gate(*_args, **_kwargs):
        raise AssertionError("a gate ran on the replay path")

    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate, craft=_NO_CRAFTS, craftspelling=_NO_CRAFTS))
    monkeypatch.setattr(products, "attach_location", _no_gate)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_gate)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

    answer = asyncio.run(products.create_product(_product_payload(), _user()))

    assert answer is stored
    assert delegate.creates == [], "the replay wrote a SECOND product row"
    assert delegate.find_unique_calls[0]["where"] == {"clientKey": "key-1"}
    assert delegate.find_unique_calls[0]["include"] is products.INCLUDE


def test_another_accounts_key_is_a_403_and_never_their_record(monkeypatch):
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="somebody_else", productName="Someone else's odhani")
    delegate = _Delegate(existing=stored)

    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate, craft=_NO_CRAFTS, craftspelling=_NO_CRAFTS))
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
    from app.api.routes import products

    stored = _Row(id="prd_1", createdById="usr_7", productName="Odhani")

    class _RacingDelegate(_Delegate):
        async def create(self, data, **_kwargs):
            self.creates.append(data)
            raise RuntimeError(
                'Unique constraint failed on the fields: (`clientKey`)'
            )

    delegate = _RacingDelegate()
    monkeypatch.setattr(products, "db", _Client(productdocumentation=delegate, craft=_NO_CRAFTS, craftspelling=_NO_CRAFTS))
    monkeypatch.setattr(products, "attach_location", _identity)
    monkeypatch.setattr(products, "enforce_workshop_submission", _no_workshop_check)
    monkeypatch.setattr(products, "public_encode", lambda row, *_a, **_kw: row)

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
    from app.services.records import is_client_key_violation

    assert is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`clientKey`)")
    )
    assert not is_client_key_violation(
        RuntimeError("Unique constraint failed on the fields: (`name`)")
    )
    assert not is_client_key_violation(RuntimeError("connection reset by peer"))
    assert not is_client_key_violation(RuntimeError("null value in column clientKey"))



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
    stored = _Row(id="prc_1", createdById="usr_7", clientKey="key-1", steps=[])

    _answer, calls, _delegate = _drive_process_replay(
        monkeypatch, stored, payload_steps=[], sync_allowed=True
    )

    assert calls == []



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
    stored = _Row(id="wsh_1", createdById="usr_7", clientKey="key-1")

    _answer, repaired, delegate = _drive_workshop_replay(
        monkeypatch, stored, artisan_ids=["art_1"], craft_ids=["crf_1"], roster_rows=0
    )
    assert repaired == [("artisans", ["art_1"]), ("crafts", ["crf_1"])]
    assert delegate.creates == [], "the replay wrote a SECOND workshop row"

    _answer, untouched, _delegate = _drive_workshop_replay(
        monkeypatch,
        stored,
        artisan_ids=["art_9"],
        craft_ids=["crf_9"],
        roster_rows=3,
    )
    assert untouched == [], (
        "a populated roster was rewritten from a queued create's ids — a roster edited through PATCH "
        "in the meantime has just been wiped"
    )



def test_the_four_create_schemas_accept_a_key_and_default_it_to_none():
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
    from app.services.records import CLIENT_KEY_FIELD, PROVENANCE_SKIP_FIELDS, merge_field_provenance

    assert CLIENT_KEY_FIELD in PROVENANCE_SKIP_FIELDS

    data = {"clientKey": "5f3b9c1e-0000-4000-8000-000000000000", "productName": "Odhani"}
    merge_field_provenance(data, _Row(id="usr_7", name="R. Menon"), previous=None)

    metadata = data.get("extraMetadata")
    provenance = {}
    if metadata is not None:
        raw = getattr(metadata, "data", metadata)
        provenance = (raw or {}).get("fieldProvenance", {})
    assert "clientKey" not in provenance
    assert "productName" in provenance, "the skip list swallowed a field that WAS typed"
