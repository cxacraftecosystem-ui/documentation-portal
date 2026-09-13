"""The questionnaire seeder: what it refuses to do, and what "idempotent" actually means.

The old seeder (`scripts/seed_questionnaire.py` before 2026-09-13) reported one number — "Seeded
questionnaire questions: 284" — whether it had created 284 rows or rewritten 284 prompts, so the
only property anybody could check was that it did not crash twice. These tests assert the property
that matters instead: THE SECOND RUN CHANGES NOTHING, stated as zero creates, zero updates, zero
reorderings and zero rewordings off the structured counts `seed_instrument` returns.

THE FAKE ENFORCES THE UNIQUE INDEXES, and that is load-bearing rather than polish. The seeder's
whole reordering design exists because `@@unique([questionnaireId, sortOrder])` refuses a collision
mid-corpus, in a backend with zero `db.tx()` call sites to roll one back — so against a fake with no
uniqueness the UNSOUND version of that design (the "only negate the movers" optimisation, and the
"kept but absent sections are not moved" rule) passes cleanly. See `access_roster_fakes.FakeTable`.

NOTHING HERE TOUCHES A DATABASE. `backend/.env` points at the live production pooler.
"""

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from access_roster_fakes import FakeDb, install
from app.services import questionnaire_seeding as seeding
from app.services.questionnaire_instruments import W2_ID, W2_TITLE, W3_ID, W3_TITLE
from app.services.questionnaire_seeding import SeedRefused, seed_instrument

BACKEND_ROOT = Path(__file__).resolve().parents[1]
W3_CORPUS = BACKEND_ROOT / "app" / "data" / "questionnaire_questions_w3.json"


def _write_corpus(tmp_path: Path, sections: list[dict[str, Any]]) -> Path:
    path = tmp_path / "corpus.json"
    path.write_text(json.dumps(sections), encoding="utf-8")
    return path


def _corpus(*specs: tuple[str, str, int]) -> list[dict[str, Any]]:
    """`(code, title, question_count)` triples as a corpus, with contiguous sortOrders."""
    return [
        {
            "code": code,
            "title": title,
            "questions": [{"sortOrder": n, "prompt": f"{code}{n}?"} for n in range(1, count + 1)],
        }
        for code, title, count in specs
    ]


def _seed(db: FakeDb, corpus_path: Path, *, questionnaire_id: str = W3_ID, title: str = W3_TITLE):
    return asyncio.run(
        seed_instrument(
            questionnaire_id=questionnaire_id,
            title=title,
            description="An instrument",
            corpus_path=corpus_path,
        )
    )


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch) -> FakeDb:
    return install(monkeypatch, FakeDb())


# --- The corpus file itself -----------------------------------------------------------------------


def test_the_third_workshop_corpus_is_twenty_two_sections_and_eighty_one_questions() -> None:
    """The committed file, not a fixture. It is a shipped artefact of a parse nobody is going to
    re-run, so its shape is asserted here rather than trusted."""
    raw = W3_CORPUS.read_bytes()
    # NO BOM. The seeder reads with `utf-8-sig` so a future re-export that adds one still loads —
    # but it should be CAUGHT, not silently tolerated, because a BOM is the sign the file went
    # through a tool nobody meant it to.
    assert raw[:3] != b"\xef\xbb\xbf"

    sections = json.loads(W3_CORPUS.read_text(encoding="utf-8-sig"))
    assert len(sections) == 22
    assert [s["code"] for s in sections] == [chr(ord("A") + i) for i in range(22)]
    assert sum(len(s["questions"]) for s in sections) == 81
    for section in sections:
        orders = [q["sortOrder"] for q in section["questions"]]
        assert orders == list(range(1, len(orders) + 1)), section["code"]
        assert set(section) == {"code", "title", "questions"}
        for question in section["questions"]:
            assert set(question) == {"sortOrder", "prompt"}

    blob = json.dumps(sections, ensure_ascii=False)
    assert blob.count("—") == 6, "the six em-dashes must survive the read as U+2014"


def test_both_committed_corpora_seed_into_one_database_without_colliding(db: FakeDb) -> None:
    """THE WHOLE REASON FOR THE CONTAINER, as one assertion.

    Every one of the 3rd workshop's 22 codes already exists in the 2nd workshop's corpus, and the
    2nd's RESP holds sortOrder 1 — which the 3rd's section A also wants. Under the old global
    `code @unique` and `@@unique([sortOrder])` this pair of runs could not both succeed: the first
    upsert of the second corpus either retitled a live section or raised. Scoped per instrument they
    are two independent 1..n numberings that never see each other, and the fake enforces both
    composite uniques, so this really is the collision being tested rather than its absence.
    """
    w2_corpus = BACKEND_ROOT / "app" / "data" / "questionnaire_questions.json"
    _, w2 = _seed(db, w2_corpus, questionnaire_id=W2_ID, title=W2_TITLE)
    _, w3 = _seed(db, W3_CORPUS)

    assert (w2["sections_created"], w2["questions_created"]) == (24, 284)
    assert (w3["sections_created"], w3["questions_created"]) == (22, 81)
    by_instrument: dict[str, set[str]] = {}
    for row in db.questionnairesection.rows:
        by_instrument.setdefault(row.questionnaireId, set()).add(row.code)
    assert len(by_instrument[W2_ID] & by_instrument[W3_ID]) == 22, "the codes really do collide"
    # Each instrument numbers its own sections 1..n, and neither renumbered the other.
    for instrument_id, count in ((W2_ID, 24), (W3_ID, 22)):
        orders = sorted(
            row.sortOrder for row in db.questionnairesection.rows
            if row.questionnaireId == instrument_id
        )
        assert orders == list(range(1, count + 1)), instrument_id


def test_section_v_questions_are_seeded_as_plain_prompts(db: FakeDb) -> None:
    """D6, pinned as a test. The 3rd instrument's section V asks for repeating lists — designers,
    suppliers, buyers — and they ship as PROSE, so shipping prose is a decision the suite defends
    rather than an omission somebody will "fix" with a question-kind column."""
    _seed(db, W3_CORPUS)

    stored = [q for q in db.questionnairequestion.rows if q.sectionCode == "V"]
    # 1..6 is the PER-SECTION sortOrder. "Questions 76-81" is their GLOBAL position, which is how
    # the prose in the schema and the seeder refers to them and is NOT what is stored.
    assert sorted(q.sortOrder for q in stored) == [1, 2, 3, 4, 5, 6]

    corpus = json.loads(W3_CORPUS.read_text(encoding="utf-8-sig"))
    expected = {q["prompt"] for q in next(s for s in corpus if s["code"] == "V")["questions"]}
    assert {q.prompt for q in stored} == expected

    # No question row anywhere carries a `kind`: there is no question-type vocabulary and none is
    # being smuggled in.
    assert all(not hasattr(row, "kind") for row in db.questionnairequestion.rows)


# --- Idempotence ----------------------------------------------------------------------------------


def test_the_second_run_creates_nothing_updates_nothing_and_reorders_nothing(db: FakeDb) -> None:
    _, first = _seed(db, W3_CORPUS)
    assert (first["sections_created"], first["questions_created"]) == (22, 81)

    _, second = _seed(db, W3_CORPUS)

    for field in (
        "sections_created",
        "sections_updated",
        # In the list because the negative pass is CONDITIONAL. If somebody makes it unconditional
        # to "simplify" it, this is the assertion that says so.
        "sections_reordered",
        "questions_created",
        "questions_updated",
        "questions_left_alone",
    ):
        assert second[field] == 0, f"{field} was {second[field]} on the second run"


def test_seeding_the_third_instrument_does_not_touch_the_second(db: FakeDb, tmp_path: Path) -> None:
    w2 = _write_corpus(tmp_path, _corpus(("RESP", "Respondent", 2), ("A", "Personal details", 3)))
    _seed(db, w2, questionnaire_id=W2_ID, title=W2_TITLE)

    def snapshot() -> list[tuple]:
        return sorted(
            (row.id, row.code, row.title, row.sortOrder, row.questionnaireId)
            for row in db.questionnairesection.rows
            if row.questionnaireId == W2_ID
        ) + sorted(
            (row.id, row.prompt, row.sortOrder, row.sectionCode, row.questionnaireId)
            for row in db.questionnairequestion.rows
            if row.questionnaireId == W2_ID
        )

    before = snapshot()
    _seed(db, W3_CORPUS)

    assert snapshot() == before
    assert len([r for r in db.questionnairesection.rows if r.questionnaireId == W3_ID]) == 22


# --- An answered question is never reworded -------------------------------------------------------


def test_an_answered_question_is_never_reworded_and_says_so(
    db: FakeDb, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    original = _write_corpus(tmp_path, _corpus(("A", "Origin", 1)))
    _seed(db, original)
    question = db.questionnairequestion.rows[0]
    asyncio.run(
        db.questionnaireresponse.create(
            data={"interviewId": "iv-1", "questionId": question.id, "answerText": "Twelve."}
        )
    )

    reworded = _write_corpus(
        tmp_path, [{"code": "A", "title": "Origin",
                    "questions": [{"sortOrder": 1, "prompt": "How many weavers work with you?"}]}]
    )
    _, counts = _seed(db, reworded)

    assert question.prompt == "A1?", "an answered question was reworded out from under its answer"
    assert counts["questions_left_alone"] == 1
    assert counts["questions_updated"] == 0
    out = capsys.readouterr().out
    assert "left alone (has answers)" in out
    assert "How many weavers work with you?" in out


def test_an_unanswered_question_is_reworded(db: FakeDb, tmp_path: Path) -> None:
    original = _write_corpus(tmp_path, _corpus(("A", "Origin", 1)))
    _seed(db, original)

    reworded = _write_corpus(
        tmp_path, [{"code": "A", "title": "Origin",
                    "questions": [{"sortOrder": 1, "prompt": "A better wording?"}]}]
    )
    _, counts = _seed(db, reworded)

    assert db.questionnairequestion.rows[0].prompt == "A better wording?"
    assert counts["questions_updated"] == 1
    assert counts["questions_left_alone"] == 0


# --- Sections the corpus no longer mentions -------------------------------------------------------


def test_a_section_removed_from_the_corpus_is_kept_active_and_parked_above_the_corpus(
    db: FakeDb, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A section dropped from a corpus may hold a fortnight of somebody's fieldwork, so it is never
    deleted and never deactivated. It cannot stay where it is either: the W2 corpus opens with RESP
    at slot 1, so a survivor sitting on slot 1 makes the first write of the corpus pass a
    UniqueViolationError. Parking it above the corpus is the only rule that keeps both promises —
    and with the unique keys registered on the fake, this test actually exercises that."""
    asyncio.run(
        db.questionnairesection.create(
            data={"questionnaireId": W3_ID, "code": "X", "title": "A retired section",
                  "sortOrder": 1, "isActive": True}
        )
    )
    asyncio.run(
        db.questionnaire.create(data={"id": W3_ID, "title": W3_TITLE, "sortOrder": 1})
    )

    corpus = _write_corpus(tmp_path, _corpus(("A", "One", 1), ("B", "Two", 1), ("C", "Three", 1)))
    _, counts = _seed(db, corpus)

    stored = {row.code: row for row in db.questionnairesection.rows}
    assert stored["X"].isActive is True
    assert stored["X"].sortOrder == 4, "parked immediately above the three the corpus describes"
    assert [stored[code].sortOrder for code in ("A", "B", "C")] == [1, 2, 3]
    assert counts["sections_not_in_corpus"] == 1
    out = capsys.readouterr().out
    assert "section X" in out
    assert "parked at sortOrder 4" in out


# --- Reordering -----------------------------------------------------------------------------------


def test_reordering_the_corpus_does_not_collide_on_the_scoped_sort_order_unique(
    db: FakeDb, tmp_path: Path
) -> None:
    first = _write_corpus(tmp_path, _corpus(("A", "One", 1), ("B", "Two", 1), ("C", "Three", 1)))
    _seed(db, first)

    swapped = _write_corpus(tmp_path, _corpus(("A", "One", 1), ("C", "Three", 1), ("B", "Two", 1)))
    _, counts = _seed(db, swapped)

    stored = {row.code: row.sortOrder for row in db.questionnairesection.rows}
    assert stored == {"A": 1, "C": 2, "B": 3}
    # ALL of them, not a subset. This is the assertion that pins the all-or-nothing rule against a
    # reintroduced "movers only" optimisation, whose counterexample is the test below.
    assert counts["sections_reordered"] == len(db.questionnairesection.rows) == 3


def test_the_movers_only_counterexample_does_not_raise(db: FakeDb, tmp_path: Path) -> None:
    """Stored B@1, C@2; corpus [A, B, C].

    Under "negate only the sections whose target slot is occupied by somebody else", B is a mover
    (target 2, held by C) and C is not (target 3, free). PASS 1 then creates A@1 and updates B->2,
    colliding with the C nobody moved. This is that exact scenario, written as a test so the
    counterexample cannot be lost along with the comment that records it.
    """
    asyncio.run(db.questionnaire.create(data={"id": W3_ID, "title": W3_TITLE, "sortOrder": 1}))
    for code, order in (("B", 1), ("C", 2)):
        asyncio.run(
            db.questionnairesection.create(
                data={"questionnaireId": W3_ID, "code": code, "title": code, "sortOrder": order,
                      "isActive": True}
            )
        )

    corpus = _write_corpus(tmp_path, _corpus(("A", "One", 1), ("B", "Two", 1), ("C", "Three", 1)))
    _seed(db, corpus)

    stored = {row.code: row.sortOrder for row in db.questionnairesection.rows}
    assert stored == {"A": 1, "B": 2, "C": 3}


# --- Refusals -------------------------------------------------------------------------------------


def test_a_duplicate_code_in_the_corpus_is_refused_before_the_first_write(
    db: FakeDb, tmp_path: Path
) -> None:
    corpus = _write_corpus(tmp_path, _corpus(("A", "One", 1), ("A", "Also A", 1)))

    with pytest.raises(SeedRefused) as excinfo:
        _seed(db, corpus)

    assert "appears twice" in str(excinfo.value)
    # NOTHING was written. A seeder that discovers a duplicate halfway through has already created
    # rows it cannot roll back, because this backend has no transaction idiom.
    for table in (
        db.questionnaire,
        db.questionnairesection,
        db.questionnairequestion,
        db.questionnaireresponse,
        db.questionnaireinterview,
    ):
        assert table.writes == []


def test_a_corpus_with_a_gap_in_its_sort_orders_is_refused(db: FakeDb, tmp_path: Path) -> None:
    """(sectionId, sortOrder) is this seeder's identity for a question, so a gap makes a re-run
    CREATE a duplicate instead of matching the row it meant to refresh."""
    corpus = _write_corpus(
        tmp_path,
        [{"code": "A", "title": "One",
          "questions": [{"sortOrder": 1, "prompt": "a"}, {"sortOrder": 3, "prompt": "b"}]}],
    )

    with pytest.raises(SeedRefused) as excinfo:
        _seed(db, corpus)

    assert "contiguous from 1" in str(excinfo.value)


def test_the_seeder_never_sets_is_default(db: FakeDb, tmp_path: Path) -> None:
    """A seeder that flipped the default would re-file every old client's submissions as a side
    effect of a wording correction: those clients send no questionnaireId at all."""
    w2 = _write_corpus(tmp_path, _corpus(("RESP", "Respondent", 1)))
    _seed(db, w2, questionnaire_id=W2_ID, title=W2_TITLE)
    asyncio.run(db.questionnaire.update(where={"id": W2_ID}, data={"isDefault": True}))

    _seed(db, W3_CORPUS)

    defaults = [row.id for row in db.questionnaire.rows if row.isDefault]
    assert defaults == [W2_ID]
    assert seeding is not None  # the module under test, imported for the name in the failure trace
