"""An edit that says what it was composed against, and a refusal that arrives before the audit row.

WHAT THIS CLOSES, WHICH IS NOT SPECULATIVE IN THIS PRODUCT. ``frontend/lib/offline.ts`` types a
queued write's method as ``"POST" | "PATCH"`` and every record form calls ``saveOrQueue`` with
``method: initial ? "PATCH" : "POST"`` and a WHOLE create-shaped body. So a correction composed in a
courtyard and drained hours later overwrites, field for field, whatever anybody else changed in the
meantime — and nobody is told. With ``expectedUpdatedAt`` sent, the server answers 409
``record_changed`` carrying both timestamps instead.

``None`` PASSES AND THAT IS THE WHOLE COMPATIBILITY STORY: every client shipped to date sends no
precondition and is unrefusable by this change, which is what makes the server half safe to deploy
alone and why the client halves can be deferred indefinitely.

THE ORDERING IS THE PART WORTH A TEST OF ITS OWN. This backend has no transactions.
``guard_record_edit`` ends in ``record_revision``, which COMMITS a ledger row asserting a change, and
``workshops.update_workshop`` calls ``record_revision`` DIRECTLY as well. A refusal raised after
either would leave a permanent, undeletable claim about an edit that was then turned down, with
nothing to roll it back. So the last test here walks each route's AST and asserts the question is
asked before either call, in source order.
"""

import ast
import inspect
import textwrap
from datetime import UTC, datetime, timedelta

import pytest
from fastapi import HTTPException


def _record(updated_at: datetime | None):
    class _Row:
        def __init__(self):
            self.updatedAt = updated_at

    return _Row()


#: Every update route the server half of this feature is wired into, with the record variable each
#: one holds the stored row in. FIVE, NOT SIX: ``crafts.update_craft`` belongs to another workstream's
#: files and could not be edited here, so ``CraftUpdate`` deliberately does not declare the field
#: either — see the test at the bottom of this module, which pins that pairing in both directions.
_WIRED_ROUTES = ("artisans", "products", "tools", "processes", "workshops")


def test_all_five_wired_update_schemas_accept_the_precondition_and_default_it_to_absent():
    """Optional on every one of them, because a client that opts in has to be able to opt in
    everywhere it can PATCH — a precondition honoured on four routes out of five is a guard a client
    author cannot reason about."""
    from app.schemas.records import (
        ArtisanUpdate,
        ProcessUpdate,
        ProductUpdate,
        ToolUpdate,
        WorkshopUpdate,
    )

    for schema in (ArtisanUpdate, WorkshopUpdate, ProductUpdate, ProcessUpdate, ToolUpdate):
        field = schema.model_fields["expectedUpdatedAt"]
        assert field.default is None
        assert not field.is_required()


def test_the_precondition_is_popped_out_of_the_body_before_anything_reads_it():
    """IT IS A QUESTION, NOT A COLUMN. Everything between the clean and the write reads ``data``:
    ``guard_record_edit`` diffs it into a ``RecordRevision``, ``merge_field_provenance`` stamps a
    contributor against every key it holds, and Prisma is finally handed it as columns. A key that
    survived into any one of those would be an audit entry for an edit nobody made, a provenance
    stamp on a field that does not exist, or a 500 naming a column this table has never had."""
    from app.services.records import take_expected_updated_at

    stamp = datetime(2026, 9, 13, 10, 0, tzinfo=UTC)
    data = {"name": "Kamla Devi", "expectedUpdatedAt": stamp}

    taken = take_expected_updated_at(data)

    assert taken == stamp
    assert "expectedUpdatedAt" not in data
    assert data == {"name": "Kamla Devi"}


def test_none_passes_and_that_is_the_whole_compatibility_story():
    """Every fielded APK and every cached web bundle sends no precondition. None of them can be
    refused by this function, on a record of any age."""
    from app.services.records import assert_expected_updated_at

    assert_expected_updated_at(_record(datetime.now(UTC)), None) is None
    # And a row with no ``updatedAt`` at all — a shape only a stub or a very old row has — is not a
    # reason to invent a refusal from the server's own gap.
    assert_expected_updated_at(_record(None), datetime.now(UTC)) is None


def test_a_matching_precondition_within_the_tolerance_passes():
    """A SECOND, AND THE SIZE IS CHOSEN BY WHICH MISTAKE IT MAKES. Too tight and a TRUE match is
    reported as a conflict — a researcher's queued correction parked behind a comparison they cannot
    see, cannot fix and did not cause. Too loose and a competing write inside the tolerance passes
    unnoticed, which is precisely today's behaviour and therefore not a regression."""
    from app.services.records import EXPECTED_UPDATED_AT_TOLERANCE, assert_expected_updated_at

    stored = datetime(2026, 9, 13, 10, 0, 0, tzinfo=UTC)
    assert EXPECTED_UPDATED_AT_TOLERANCE == timedelta(seconds=1)

    assert_expected_updated_at(_record(stored), stored + timedelta(milliseconds=999))
    assert_expected_updated_at(_record(stored), stored - timedelta(milliseconds=999))

    with pytest.raises(HTTPException):
        assert_expected_updated_at(_record(stored), stored + timedelta(milliseconds=1500))


def test_a_naive_datetime_is_read_as_utc_rather_than_refused():
    """422-ING A CORRECTION OVER A MISSING "Z" WOULD LOSE FIELDWORK TO PUNCTUATION. Every value this
    can be compared against was produced by this API, which encodes UTC."""
    from app.services.records import assert_expected_updated_at

    stored = datetime(2026, 9, 13, 10, 0, 0, tzinfo=UTC)
    assert_expected_updated_at(_record(stored), datetime(2026, 9, 13, 10, 0, 0))


def test_a_stale_precondition_is_a_409_carrying_one_quotable_sentence_and_both_times():
    """THE BODY IS PART OF THE CONTRACT. A client embeds ``message`` verbatim between its own
    clauses, so it has to read as one self-contained sentence in the middle of a paragraph — not as a
    heading, and not as an instruction competing with the remedy the client already gives. Both
    timestamps travel so the person reading the warning can see how far apart the two versions are.
    """
    from app.services.records import assert_expected_updated_at

    stored = datetime(2026, 9, 13, 12, 0, 0, tzinfo=UTC)
    expected = datetime(2026, 9, 13, 10, 0, 0, tzinfo=UTC)

    with pytest.raises(HTTPException) as conflict:
        assert_expected_updated_at(_record(stored), expected)

    assert conflict.value.status_code == 409
    detail = conflict.value.detail
    assert detail["code"] == "record_changed"
    assert detail["message"] == "Someone else changed this record after this edit was composed."
    assert detail["expectedUpdatedAt"] == expected.isoformat()
    assert detail["currentUpdatedAt"] == stored.isoformat()


def _route_functions():
    from app.api.routes import artisans, processes, products, tools, workshops

    return (
        ("artisans", artisans.update_artisan),
        ("products", products.update_product),
        ("tools", tools.update_tool),
        ("processes", processes.update_process),
        ("workshops", workshops.update_workshop),
    )


def _call_line_numbers(route, name: str) -> list[int]:
    tree = ast.parse(textwrap.dedent(inspect.getsource(route)))
    return [
        node.lineno
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and getattr(node.func, "attr", getattr(node.func, "id", None)) == name
    ]


@pytest.mark.parametrize(("module", "route"), _route_functions())
def test_every_update_route_takes_the_key_out_of_the_body(module, route):
    """Exactly once, and read off the route's own source rather than inferred from behaviour: a
    second ``take_expected_updated_at`` would silently return None for the second caller, which is a
    guard that is present and does nothing."""
    assert len(_call_line_numbers(route, "take_expected_updated_at")) == 1, (
        f"{module}.{route.__name__} does not pop the precondition exactly once"
    )
    assert len(_call_line_numbers(route, "assert_expected_updated_at")) == 1


@pytest.mark.parametrize(("module", "route"), _route_functions())
def test_every_update_route_asks_before_it_writes_the_audit_row(module, route):
    """IN A BACKEND WITH NO TRANSACTIONS THIS ORDERING IS THE WHOLE OF THE SAFETY.

    ``guard_record_edit`` ends in ``record_revision``, which COMMITS a ledger row asserting a change.
    A 409 raised after it would leave a permanent, undeletable claim in ``RecordRevision`` about an
    edit that was then refused, and there is no rollback here to take it back.

    ``workshops.update_workshop`` is the route that makes the ``record_revision`` half of this
    assertion necessary rather than redundant: an EDIT-level assignee takes a branch that calls
    ``record_revision`` DIRECTLY, without passing through ``guard_record_edit`` at all.
    """
    asked = _call_line_numbers(route, "assert_expected_updated_at")[0]
    writes = _call_line_numbers(route, "guard_record_edit") + _call_line_numbers(
        route, "record_revision"
    )
    assert writes, f"{module}.{route.__name__} writes no audit row at all — has it been rewritten?"
    assert asked < min(writes), (
        f"{module}.{route.__name__} asks the precondition AFTER an audit row is committed. A refused "
        "edit would leave a permanent RecordRevision claiming a change that never happened."
    )


def test_crafts_is_named_here_as_the_route_this_workstream_could_not_reach():
    """THE SIXTH UPDATE ROUTE, AND WHY ITS SCHEMA DELIBERATELY DOES NOT DECLARE THE FIELD.

    ``routes/crafts.py`` belongs to another workstream's files, and the two halves of this feature
    only make sense together: a ``CraftUpdate.expectedUpdatedAt`` the route never pops would survive
    ``clean_data`` unread and be handed to Prisma as a column ``Craft`` has never had — a bare 500 on
    an edit that is a clean 422 today (``APIModel`` is ``extra="forbid"``, so a client sending it now
    is told so by name). A guard that is present and does nothing is bad; one that breaks the save is
    worse.

    So this test pins the PAIR, in both directions, and fails the day either half moves alone.
    """
    from pydantic import ValidationError

    from app.api.routes import crafts
    from app.schemas.records import CraftUpdate

    assert "expectedUpdatedAt" not in CraftUpdate.model_fields
    with pytest.raises(ValidationError):
        CraftUpdate(**{"expectedUpdatedAt": "2026-09-13T10:00:00Z"})
    assert "take_expected_updated_at" not in inspect.getsource(crafts.update_craft), (
        "crafts.update_craft now pops the precondition — declare `expectedUpdatedAt` on CraftUpdate, "
        "add ('crafts', crafts.update_craft) to _route_functions, and delete this test"
    )
