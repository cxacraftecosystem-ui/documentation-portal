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


_WIRED_ROUTES = ("artisans", "products", "tools", "processes", "workshops")


def test_all_five_wired_update_schemas_accept_the_precondition_and_default_it_to_absent():
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
    from app.services.records import take_expected_updated_at

    stamp = datetime(2026, 9, 13, 10, 0, tzinfo=UTC)
    data = {"name": "Kamla Devi", "expectedUpdatedAt": stamp}

    taken = take_expected_updated_at(data)

    assert taken == stamp
    assert "expectedUpdatedAt" not in data
    assert data == {"name": "Kamla Devi"}


def test_none_passes_and_that_is_the_whole_compatibility_story():
    from app.services.records import assert_expected_updated_at

    assert_expected_updated_at(_record(datetime.now(UTC)), None) is None
    assert_expected_updated_at(_record(None), datetime.now(UTC)) is None


def test_a_matching_precondition_within_the_tolerance_passes():
    from app.services.records import EXPECTED_UPDATED_AT_TOLERANCE, assert_expected_updated_at

    stored = datetime(2026, 9, 13, 10, 0, 0, tzinfo=UTC)
    assert EXPECTED_UPDATED_AT_TOLERANCE == timedelta(seconds=1)

    assert_expected_updated_at(_record(stored), stored + timedelta(milliseconds=999))
    assert_expected_updated_at(_record(stored), stored - timedelta(milliseconds=999))

    with pytest.raises(HTTPException):
        assert_expected_updated_at(_record(stored), stored + timedelta(milliseconds=1500))


def test_a_naive_datetime_is_read_as_utc_rather_than_refused():
    from app.services.records import assert_expected_updated_at

    stored = datetime(2026, 9, 13, 10, 0, 0, tzinfo=UTC)
    assert_expected_updated_at(_record(stored), datetime(2026, 9, 13, 10, 0, 0))


def test_a_stale_precondition_is_a_409_carrying_one_quotable_sentence_and_both_times():
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
    assert len(_call_line_numbers(route, "take_expected_updated_at")) == 1, (
        f"{module}.{route.__name__} does not pop the precondition exactly once"
    )
    assert len(_call_line_numbers(route, "assert_expected_updated_at")) == 1


@pytest.mark.parametrize(("module", "route"), _route_functions())
def test_every_update_route_asks_before_it_writes_the_audit_row(module, route):
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
