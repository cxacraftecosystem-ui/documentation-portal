"""Recording chips and the shared filters, without a database (owner, 31 Oct 2026).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (shared/questionnaire.sha256).
The chips object the server builds (``app/questionnaire/chips.py``), the filter parameters every
list takes (``app/questionnaire/filters.py``), and ``shared/questionnaire-chips.json``, the
vectors both clients draw their chips and encode their filters from.
"""

from __future__ import annotations

import json
from datetime import UTC, date, datetime
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.questionnaire import chips, filters

SHARED = Path(__file__).resolve().parents[2] / "shared"
VECTORS = json.loads((SHARED / "questionnaire-chips.json").read_text(encoding="utf-8"))
META = {
    "session": {"id": "s1", "title": "Interview_2026-10-22_Bagru"},
    "questionnaire": {"id": "f1", "title": "2nd Craft Toolkit", "kind": "WORKSHOP_INTERVIEW"},
    "sessionDate": "2026-10-22", "sessionDateEstimated": False,
    "workshop": {"id": "dw1", "name": "Bagru Design Workshop", "type": "DESIGN"},
    "source": "APP", "languageCode": "hi",
}


def _build(**over):
    args = {
        "meta": META,
        "targets": [{"sectionId": "secA", "scode": "A", "stitle": "Materials"}],
        "subjects": [{"artisanId": "a2", "name": "Meena", "role": "PRESENT"}, {"artisanId": "a1", "name": "Giriraj", "role": "ANSWERED"}],
        "respondents": [],
        "captured_at": datetime(2026, 10, 22, 5, 0, tzinfo=UTC), "captured_at_source": "DEVICE", "language_code": None,
        "duration_ms": 1524000, "recorded_by": {"id": "u1", "name": "Nandini"},
    }
    args.update(over)
    return chips.build(**args)


def test_the_chips_name_the_section_the_form_the_people_the_day_and_the_workshop() -> None:
    out = _build()
    assert out == VECTORS["cases"][0]["chips"], "the server's object is the first vector, field for field"


def test_who_answered_comes_before_who_was_present_and_a_question_names_its_section() -> None:
    out = _build(targets=[{"questionLineageId": "q1", "displayCode": "F1", "prompt": "Which tools?", "qsec": "F", "qstitle": "Tools", "qsecid": "secF"}])
    assert [a["role"] for a in out["artisans"]] == ["ANSWERED", "PRESENT"]
    assert out["targets"] == [{"kind": "QUESTION", "questionLineageId": "q1", "displayCode": "F1", "prompt": "Which tools?",
                               "sectionId": "secF", "sectionCode": "F", "sectionTitle": "Tools"}]


def test_the_capture_day_is_shown_only_when_it_is_not_the_session_day_and_is_read_in_india_time() -> None:
    # 20:00 UTC on the 22nd is 01:30 on the 23rd in India.
    late = _build(captured_at=datetime(2026, 10, 22, 20, 0, tzinfo=UTC))
    assert late["capturedOn"] == "2026-10-23"
    same = _build(captured_at=datetime(2026, 10, 22, 12, 0, tzinfo=UTC))
    assert same["capturedOn"] is None
    estimated = _build(captured_at_source="UPLOAD_TIME")
    assert estimated["captureEstimated"] is True


def test_a_take_with_no_session_and_no_targets_still_has_chips() -> None:
    out = chips.build(meta=None, targets=[], subjects=[], respondents=[{"respondentId": "r1", "name": "Stall owner", "role": "ANSWERED"}],
                      captured_at=None, captured_at_source=None, language_code=None, duration_ms=None, recorded_by=None)
    assert out["targets"] == [] and out["questionnaire"] is None and out["sessionDate"] is None
    assert out["respondents"] == [{"id": "r1", "name": "Stall owner", "role": "ANSWERED"}]


def test_every_vector_carries_exactly_the_servers_fields() -> None:
    keys = set(_build())
    for case in VECTORS["cases"]:
        assert set(case["chips"]) == keys, case["name"]
        kinds = {c["kind"] for c in case["expect"]}
        assert kinds <= set(VECTORS["chipKinds"]), case["name"]
        for chip in case["expect"]:
            if chip["filter"]:
                assert chip["filter"]["key"] in set(VECTORS["filterKeys"]) | {"date"}, chip


def test_the_vectors_filter_keys_are_the_servers() -> None:
    assert VECTORS["filterKeys"] == ["q", *[k for k in VECTORS["filterKeys"] if k != "q"]]
    assert set(VECTORS["multiKeys"]) == set(filters.MULTI)
    assert set(VECTORS["filterKeys"]) == set(filters.MULTI) | {"q", "dateFrom", "dateTo"}
    assert VECTORS["values"]["source"] == list(filters.SOURCES)
    assert VECTORS["values"]["transcript"] == list(filters.TRANSCRIPT)
    assert set(VECTORS["values"]["kind"]) == set(filters.KINDS)


@pytest.mark.parametrize("case", VECTORS["queryCases"], ids=lambda c: c["query"] or "empty")
def test_each_query_vector_parses_back_to_its_filters(case: dict) -> None:
    from urllib.parse import parse_qs

    parsed = filters.parse(dict(parse_qs(case["query"])))
    for key, value in case["filters"].items():
        got = getattr(parsed, key)
        if isinstance(got, tuple):
            assert list(got) == value, key
        elif isinstance(got, date):
            assert got.isoformat() == value, key
        else:
            assert got == value, key


def test_repeated_and_comma_separated_values_are_one_list() -> None:
    f = filters.parse({"artisanId": ["a1,a2", "a3", "a1"], "source": "upload", "languageCode": "HI"})
    assert f.artisanId == ("a1", "a2", "a3") and f.source == ("UPLOAD",) and f.languageCode == ("hi",)
    assert filters.parse({"search": "loom"}).q == "loom", "the sessions list's old name for q"
    swapped = filters.parse({"dateFrom": "2026-10-31", "dateTo": "2026-10-01"})
    assert (swapped.dateFrom, swapped.dateTo) == (date(2026, 10, 1), date(2026, 10, 31))


@pytest.mark.parametrize("params", [{"source": "FAX"}, {"transcript": "MAYBE"}, {"kind": "NOVEL"}, {"dateFrom": "31/10/2026"},
                                    {"artisanId": ",".join(f"a{i}" for i in range(51))}])
def test_a_bad_value_is_a_validation_failure(params: dict) -> None:
    with pytest.raises(HTTPException) as caught:
        filters.parse(params)
    assert caught.value.status_code == 422


def test_like_escapes_its_own_characters() -> None:
    assert filters.like("50%_off\\") == "%50\\%\\_off\\\\%"


def test_a_session_filter_binds_every_value_and_never_inlines_one() -> None:
    f = filters.parse({"q": "x'; DROP TABLE", "artisanId": "a1,a2", "sectionId": "s1", "transcript": "NONE,HAS", "kind": "NONE"})
    b = filters.Sql()
    sql = " AND ".join(filters.session_where(f, b))
    assert "DROP" not in sql and "a1" not in sql
    assert ["a1", "a2"] in b.params and "%x'; DROP TABLE%" in b.params


def test_what_narrows_a_form_and_what_narrows_its_sessions() -> None:
    assert filters.parse({}).empty()
    assert not filters.parse({"kind": "MARKET_SURVEY"}).session_level()
    assert filters.parse({"artisanId": "a1"}).session_level()
    assert filters.parse({"q": "loom", "dateFrom": "2026-10-01"}).without("dateFrom").echo() == {"q": "loom"}
    assert not filters.media_filtered(filters.parse({"q": "loom"})), "a media list searches its own free text"
