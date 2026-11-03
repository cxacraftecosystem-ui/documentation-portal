from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from app.services import questionnaire_consolidation as qc
from app.services.questionnaire_consolidation import (
    CSV_COLUMNS,
    GROUP,
    RECORDED,
    SOLE,
    consolidated_rows,
)

ARTISAN_ID = "artisan-subject"
OTHER_ID = "artisan-other"

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"


def _dt(day: int) -> datetime:
    return datetime(2026, 6, day, 9, 0, tzinfo=UTC)


class _Delegate:
    def __init__(self, rows):
        self._rows = rows

    async def find_many(self, **_kwargs):
        return list(self._rows)

    async def find_unique(self, **_kwargs):
        return self._rows[0] if self._rows else None


class _Db:
    def __init__(self, **delegates):
        for name, rows in delegates.items():
            setattr(self, name, _Delegate(rows))


def _instrument(iid, title, order):
    return SimpleNamespace(id=iid, title=title, sortOrder=order, isActive=True)


def _section_row(sid, code, title, order, questionnaire_id=W2):
    return SimpleNamespace(
        id=sid,
        code=code,
        title=title,
        sortOrder=order,
        isActive=True,
        questionnaireId=questionnaire_id,
    )


def _question(qid, section_id, order, prompt, questionnaire_id=W2):
    return SimpleNamespace(
        id=qid,
        sectionId=section_id,
        sortOrder=order,
        prompt=prompt,
        questionnaireId=questionnaire_id,
    )


def _interview(iid, title, day, questionnaire_id=W2):
    return SimpleNamespace(
        id=iid,
        title=title,
        interviewDate=None,
        recordedAt=_dt(day),
        createdAt=_dt(day),
        status="APPROVED",
        questionnaireId=questionnaire_id,
        workshop=SimpleNamespace(title="Almora workshop"),
    )


def _response(rid, iid, qid, text, notes=None, by="Field worker"):
    return SimpleNamespace(
        id=rid,
        interviewId=iid,
        questionId=qid,
        answerText=text,
        notes=notes,
        answeredBy=SimpleNamespace(name=by),
    )


def _link(iid, aid, name):
    return SimpleNamespace(interviewId=iid, artisanId=aid, artisan=SimpleNamespace(name=name))


def _media(mid, iid, filename, day, extra=None, transcript="spoken answer"):
    return SimpleNamespace(
        id=mid,
        questionnaireInterviewId=iid,
        originalFilename=filename,
        mediaType="AUDIO",
        url=f"https://example.invalid/{mid}",
        transcriptText=transcript,
        transcriptStatus="COMPLETED",
        extraMetadata=extra,
        recordedAt=_dt(day),
        createdAt=_dt(day),
    )


def _build_db():
    sections = [
        _section_row("sec-a", "A", "Origin", 1),
        _section_row("sec-b", "B", "Family", 2),
    ]
    questions = [
        _question("q1", "sec-a", 1, "How did you learn the craft?"),
        _question("q2", "sec-a", 2, "Who taught you?"),
        _question("q3", "sec-b", 1, "Does your family practise it?"),
    ]
    interviews = [_interview("iv-solo", "Vikram alone", 20), _interview("iv-group", "Group of three", 24)]
    links = [
        _link("iv-solo", ARTISAN_ID, "Subject Artisan"),
        _link("iv-group", ARTISAN_ID, "Subject Artisan"),
        _link("iv-group", OTHER_ID, "Other Artisan"),
        _link("iv-group", "artisan-third", "Third Artisan"),
    ]
    responses = [
        _response("r1", "iv-solo", "q1", "From my father, at eleven."),
        _response("r2", "iv-group", "q1", "From my uncle, after school."),
        _response("r3", "iv-group", "q2", "My uncle.", notes="hesitant"),
    ]
    media = [
        _media("m1", "iv-solo", "B_SEC_SUBJECT_000102_2006.mp3", 20),
        _media("m2", "iv-group", "clip.mp3", 24, extra={"questionId": "q3"}),
        _media("m3", "iv-group", "QUESTIONNAIRE_nosection_AUD_1.mp3", 24),
    ]
    return _Db(
        artisan=[SimpleNamespace(id=ARTISAN_ID, name="Subject Artisan", place="Almora",
                                craft=SimpleNamespace(name="Cane and Bamboo"))],
        questionnaire=[_instrument(W2, "2nd Craft Toolkit Workshop", 1)],
        questionnairesection=sections,
        questionnairequestion=questions,
        questionnaireinterview=interviews,
        questionnaireinterviewartisan=links,
        questionnaireresponse=responses,
        mediafile=media,
    )


@pytest.fixture()
def payload(monkeypatch):
    monkeypatch.setattr(qc, "db", _build_db())
    user = SimpleNamespace(id="viewer", role="MASTER_ADMIN")
    import asyncio

    return asyncio.run(qc.consolidate_for_artisan(ARTISAN_ID, user))


def _section(payload, code):
    return next(s for s in payload["sections"] if s["code"] == code)


def _question_row(payload, code, qid):
    return next(q for q in _section(payload, code)["questions"] if q["id"] == qid)



def test_sections_and_questions_read_in_questionnaire_order_not_by_interview(payload):
    assert [s["code"] for s in payload["sections"]] == ["A", "B"]
    assert [q["sortOrder"] for q in _section(payload, "A")["questions"]] == [1, 2]


def test_answers_from_every_interview_the_artisan_belongs_to_are_present(payload):
    assert payload["summary"]["interviewCount"] == 2
    assert payload["summary"]["typedAnswerCount"] == 3



def test_same_question_answered_differently_keeps_both_and_flags_the_conflict(payload):
    q1 = _question_row(payload, "A", "q1")
    assert q1["conflict"] is True
    assert len(q1["answers"]) == 2
    assert {a["answerText"] for a in q1["answers"]} == {
        "From my father, at eleven.",
        "From my uncle, after school.",
    }
    assert payload["summary"]["conflictCount"] == 1


def test_conflicting_answers_are_ordered_most_recent_first(payload):
    q1 = _question_row(payload, "A", "q1")
    assert [a["interviewId"] for a in q1["answers"]] == ["iv-group", "iv-solo"]


def test_one_answer_repeated_verbatim_is_not_a_conflict(monkeypatch):
    db = _build_db()
    db.questionnaireresponse = _Delegate(
        [
            _response("r1", "iv-solo", "q1", "From my father."),
            _response("r2", "iv-group", "q1", "  from my  FATHER. "),
        ]
    )
    monkeypatch.setattr(qc, "db", db)
    import asyncio

    result = asyncio.run(
        qc.consolidate_for_artisan(ARTISAN_ID, SimpleNamespace(id="v", role="MASTER_ADMIN"))
    )
    assert _question_row(result, "A", "q1")["conflict"] is False
    assert len(_question_row(result, "A", "q1")["answers"]) == 2



def test_solo_sitting_answer_is_attributed_to_the_artisan(payload):
    solo = next(a for a in _question_row(payload, "A", "q1")["answers"] if a["interviewId"] == "iv-solo")
    assert solo["attribution"] == SOLE
    assert solo["artisanCount"] == 1
    assert solo["coParticipants"] == []


def test_group_sitting_answer_is_not_attributed_to_the_artisan(payload):
    group = next(a for a in _question_row(payload, "A", "q1")["answers"] if a["interviewId"] == "iv-group")
    assert group["attribution"] == GROUP
    assert group["artisanCount"] == 3
    assert group["coParticipants"] == ["Other Artisan", "Third Artisan"]


def test_attribution_is_a_function_of_the_sitting_size_only():
    assert qc._attribution(0) == SOLE
    assert qc._attribution(1) == SOLE
    assert qc._attribution(2) == GROUP
    assert qc._attribution(5) == GROUP



def test_every_answer_row_names_its_source_and_date(payload):
    rows = [
        answer
        for section in payload["sections"]
        for question in section["questions"]
        for answer in question["answers"]
    ] + [r for section in payload["sections"] for r in section["recordings"]] + payload["unfiled"]
    assert rows
    for row in rows:
        assert row["interviewId"]
        assert row["interviewTitle"]
        assert row["interviewDate"]
        assert row["attribution"] in {SOLE, GROUP}
        assert isinstance(row["artisanCount"], int)


def test_date_basis_is_reported_when_interview_date_is_absent(payload):
    assert {i["dateBasis"] for i in payload["interviews"]} == {"recordedAt"}


def test_interview_date_wins_when_present():
    interview = SimpleNamespace(interviewDate=_dt(1), recordedAt=_dt(9), createdAt=_dt(9))
    assert qc._interview_date(interview) == (_dt(1), "interviewDate")
    assert qc._interview_date(SimpleNamespace(interviewDate=None, recordedAt=_dt(9), createdAt=_dt(2))) == (
        _dt(9),
        "recordedAt",
    )



def test_clip_naming_a_question_lands_on_that_question(payload):
    q3 = _question_row(payload, "B", "q3")
    assert [a["kind"] for a in q3["answers"]] == [RECORDED]
    assert q3["answers"][0]["mediaId"] == "m2"


def test_clip_naming_only_a_section_lands_on_the_section_not_a_question(payload):
    section_b = _section(payload, "B")
    assert [r["mediaId"] for r in section_b["recordings"]] == ["m1"]


def test_clip_naming_nothing_is_listed_as_unfiled_rather_than_dropped(payload):
    assert [r["mediaId"] for r in payload["unfiled"]] == ["m3"]
    assert payload["summary"]["unfiledRecordingCount"] == 1



def test_csv_has_one_row_per_answer_so_a_conflict_stays_legible(payload):
    rows = consolidated_rows(payload)
    prompt_index = CSV_COLUMNS.index("Question")
    learned = [r for r in rows if r[prompt_index] == "How did you learn the craft?"]
    assert len(learned) == 2
    conflict_index = CSV_COLUMNS.index("Conflicting answers on this question")
    assert all(r[conflict_index] == "Yes" for r in learned)


def test_csv_spells_out_unattributable_answers(payload):
    rows = consolidated_rows(payload)
    column = CSV_COLUMNS.index("Attributable to this artisan")
    values = {r[column] for r in rows}
    assert "No - group sitting, speaker not recorded" in values
    assert "Yes" in values


def test_csv_row_width_matches_the_header(payload):
    rows = consolidated_rows(payload)
    assert rows
    assert all(len(row) == len(CSV_COLUMNS) for row in rows)


def test_csv_carries_recordings_and_their_transcripts(payload):
    rows = consolidated_rows(payload)
    kind = CSV_COLUMNS.index("Answer kind")
    answer = CSV_COLUMNS.index("Answer")
    recorded = [r for r in rows if r[kind] == "Recording"]
    assert len(recorded) == 3
    assert all(r[answer] == "spoken answer" for r in recorded)



def test_payload_carries_no_identity_numbers(payload):
    import json

    blob = json.dumps(payload).lower()
    assert "aadhaar" not in blob
    assert "pehchan" not in blob


def test_unknown_artisan_yields_none(monkeypatch):
    db = _build_db()
    db.artisan = _Delegate([])
    monkeypatch.setattr(qc, "db", db)
    import asyncio

    assert (
        asyncio.run(qc.consolidate_for_artisan("nope", SimpleNamespace(id="v", role="MASTER_ADMIN")))
        is None
    )



def _build_two_instrument_db():
    sections = [
        _section_row("w2-a", "A", "Personal details of the artisan", 1, W2),
        _section_row("w3-a", "A", "Origin, history, place and personal journey", 1, W3),
    ]
    questions = [
        _question("w2-q1", "w2-a", 1, "What is your name?", W2),
        _question("w3-q1", "w3-a", 1, "Where did the craft reach your family from?", W3),
    ]
    interviews = [
        _interview("iv-w2", "Second workshop sitting", 20, W2),
        _interview("iv-w3", "Third workshop sitting", 24, W3),
    ]
    links = [_link("iv-w2", ARTISAN_ID, "Subject Artisan"), _link("iv-w3", ARTISAN_ID, "Subject Artisan")]
    responses = [
        _response("r-w2", "iv-w2", "w2-q1", "Vikram."),
        _response("r-w3", "iv-w3", "w3-q1", "From my grandmother's village."),
    ]
    media = [
        _media("m-w2", "iv-w2", "clip.mp3", 20, extra={"sectionCode": "A"}),
        _media("m-none", "iv-w3", "nothing.mp3", 24),
    ]
    return _Db(
        artisan=[SimpleNamespace(id=ARTISAN_ID, name="Subject Artisan", place="Almora",
                                 craft=SimpleNamespace(name="Cane and Bamboo"))],
        questionnaire=[
            _instrument(W2, "2nd Craft Toolkit Workshop", 1),
            _instrument(W3, "3rd Craft Toolkit Workshop", 2),
        ],
        questionnairesection=sections,
        questionnairequestion=questions,
        questionnaireinterview=interviews,
        questionnaireinterviewartisan=links,
        questionnaireresponse=responses,
        mediafile=media,
    )


@pytest.fixture()
def two_instrument_payload(monkeypatch):
    monkeypatch.setattr(qc, "db", _build_two_instrument_db())
    import asyncio

    return asyncio.run(
        qc.consolidate_for_artisan(ARTISAN_ID, SimpleNamespace(id="viewer", role="MASTER_ADMIN"))
    )


def test_a_consolidated_document_groups_by_instrument_before_section(two_instrument_payload):
    sections = two_instrument_payload["sections"]
    assert len(sections) == 2, "two sections both coded A must never be merged into one"
    assert [s["questionnaireId"] for s in sections] == [W2, W3]
    assert [s["code"] for s in sections] == ["A", "A"]
    assert [s["title"] for s in sections] == [
        "Personal details of the artisan",
        "Origin, history, place and personal journey",
    ]


def test_the_csv_header_and_every_row_have_the_same_width(two_instrument_payload):
    assert len(CSV_COLUMNS) == 23
    rows = consolidated_rows(two_instrument_payload)
    assert len(rows) >= 4
    for row in rows:
        assert len(row) == len(CSV_COLUMNS), row


def test_the_questionnaire_column_carries_the_instrument_title(two_instrument_payload):
    rows = consolidated_rows(two_instrument_payload)
    column = CSV_COLUMNS.index("Questionnaire")
    interview_column = CSV_COLUMNS.index("Interview")
    titled = {row[column] for row in rows if row[interview_column]}
    assert titled == {"2nd Craft Toolkit Workshop", "3rd Craft Toolkit Workshop", ""}
    unfiled = [row for row in rows if row[CSV_COLUMNS.index("Section")] == "(unfiled)"]
    assert unfiled and all(row[column] == "" for row in unfiled)


def test_a_clip_tagged_with_a_section_code_resolves_within_its_own_instrument(two_instrument_payload):
    by_instrument = {s["questionnaireId"]: s for s in two_instrument_payload["sections"]}
    assert [r["mediaId"] for r in by_instrument[W2]["recordings"]] == ["m-w2"]
    assert by_instrument[W3]["recordings"] == []
