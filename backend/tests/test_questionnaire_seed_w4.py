import asyncio
import json
from pathlib import Path

import pytest

from access_roster_fakes import FakeDb, install
from app.services.questionnaire_instruments import W3_ID, W3_TITLE, W4_ID, W4_TITLE
from app.services.questionnaire_seeding import load_corpus, seed_instrument

BACKEND_ROOT = Path(__file__).resolve().parents[1]
W3_CORPUS = BACKEND_ROOT / "app" / "data" / "questionnaire_questions_w3.json"
W4_CORPUS = BACKEND_ROOT / "app" / "data" / "questionnaire_questions_w4.json"
OWNER_ID = "cmqg22xf6000018qdavy2jvel"
SECTION_CODES = list("ABCDEFGHIJKLMNOPQRSTUVW")
SECTION_SIZES = [5, 4, 1, 9, 10, 5, 8, 7, 4, 4, 6, 2, 3, 4, 2, 7, 5, 1, 5, 3, 2, 6, 5]


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch) -> FakeDb:
    return install(monkeypatch, FakeDb())


def _seed(questionnaire_id: str, title: str, corpus: Path = W4_CORPUS, created_by_id: str | None = None):
    return asyncio.run(
        seed_instrument(
            questionnaire_id=questionnaire_id,
            title=title,
            description="An instrument",
            corpus_path=corpus,
            created_by_id=created_by_id,
        )
    )


def _rows(db: FakeDb, questionnaire_id: str) -> dict[tuple[str, int], object]:
    return {
        (q.sectionCode, q.sortOrder): q
        for q in db.questionnairequestion.rows
        if q.questionnaireId == questionnaire_id
    }


def test_the_fourth_corpus_parses_into_23_sections_and_108_questions() -> None:
    sections = load_corpus(W4_CORPUS)

    assert [s["code"] for s in sections] == SECTION_CODES
    assert [len(s["questions"]) for s in sections] == SECTION_SIZES
    assert sum(SECTION_SIZES) == 108
    for section in sections:
        orders = [q["sortOrder"] for q in section["questions"]]
        assert orders == list(range(1, len(orders) + 1))
        assert all(q["prompt"] == q["prompt"].strip() and q["prompt"] for q in section["questions"])
    titles = {s["code"]: s["title"] for s in sections}
    assert titles["S"] == "SCIENCE BEHIND THE CRAFT — PROBING QUESTIONS"
    assert titles["V"] == "NETWORK / ECOSYSTEM MAPPING — STRUCTURED FIELDS"
    assert titles["W"] == "STONE CRAFT PRODUCTION, TRANSPORTATION AND INSTALLATION"


def test_the_fourth_corpus_keeps_every_third_section_and_appends_its_stone_questions() -> None:
    w3 = {s["code"]: s for s in json.loads(W3_CORPUS.read_text(encoding="utf-8-sig"))}
    w4 = {s["code"]: s for s in load_corpus(W4_CORPUS)}
    reworded = []
    for code, old in w3.items():
        assert w4[code]["title"] == old["title"]
        assert len(w4[code]["questions"]) >= len(old["questions"])
        for before, after in zip(old["questions"], w4[code]["questions"]):
            if before["prompt"] != after["prompt"]:
                reworded.append(f"{code}{before['sortOrder']}")
    assert reworded == ["G2", "G4", "S1", "S3", "V2"]


def test_the_fourth_instrument_seeds_108_questions_with_the_ctw3_codes_it_shares(db: FakeDb) -> None:
    _seed(W3_ID, W3_TITLE, W3_CORPUS)
    _, counts = _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)

    assert (counts["sections_created"], counts["questions_created"]) == (23, 108)
    w3 = {key: row.questionCode for key, row in _rows(db, W3_ID).items()}
    w4 = _rows(db, W4_ID)
    assert len(w4) == 108
    shared = {key: row.questionCode for key, row in w4.items() if row.questionCode}
    assert len(shared) == 76
    assert all(w3[key] == code and code.startswith("CTW3.") for key, code in shared.items())


def test_reseeding_the_fourth_instrument_changes_nothing(db: FakeDb) -> None:
    _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)
    before = sorted(
        (q.id, q.sectionCode, q.sortOrder, q.prompt, q.questionCode, q.isActive)
        for q in db.questionnairequestion.rows
    )
    _, again = _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)

    after = sorted(
        (q.id, q.sectionCode, q.sortOrder, q.prompt, q.questionCode, q.isActive)
        for q in db.questionnairequestion.rows
    )
    assert after == before
    assert {k: v for k, v in again.items() if v} == {}


def test_moving_the_fourth_from_the_third_corpus_updates_rows_in_place(db: FakeDb) -> None:
    _seed(W4_ID, W4_TITLE, W3_CORPUS, OWNER_ID)
    old = {key: (row.id, row.prompt, row.questionCode) for key, row in _rows(db, W4_ID).items()}

    _, counts = _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)

    assert counts["sections_created"] == 1
    assert (counts["questions_created"], counts["questions_updated"]) == (27, 5)
    assert counts["questions_not_in_corpus"] == counts["sections_not_in_corpus"] == 0
    new = _rows(db, W4_ID)
    assert len(new) == 108 and all(row.isActive for row in new.values())
    for key, (row_id, prompt, code) in old.items():
        assert new[key].id == row_id
        if new[key].prompt == prompt:
            assert new[key].questionCode == code


def test_the_fourth_instrument_is_owned_by_the_named_master_admin(db: FakeDb) -> None:
    _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)

    stored = next(q for q in db.questionnaire.rows if q.id == W4_ID)
    assert stored.createdById == OWNER_ID
    assert stored.title == W4_TITLE
    assert not stored.isDefault


def test_seeding_the_fourth_leaves_the_third_untouched(db: FakeDb) -> None:
    _seed(W3_ID, W3_TITLE, W3_CORPUS)
    before = sorted(
        (q.id, q.prompt, q.questionCode, q.questionnaireId)
        for q in db.questionnairequestion.rows
        if q.questionnaireId == W3_ID
    )
    _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)
    _, again = _seed(W4_ID, W4_TITLE, created_by_id=OWNER_ID)

    after = sorted(
        (q.id, q.prompt, q.questionCode, q.questionnaireId)
        for q in db.questionnairequestion.rows
        if q.questionnaireId == W3_ID
    )
    assert after == before
    assert again["questions_created"] == 0 and again["sections_created"] == 0


def test_the_seeder_and_the_data_load_describe_the_instrument_identically() -> None:
    from scripts import seed_questionnaire_w4, w4_apply

    assert seed_questionnaire_w4.DESCRIPTION == w4_apply.QUESTIONNAIRE_DESCRIPTION
    assert seed_questionnaire_w4.DESCRIPTION.startswith(
        "The instrument used at the 4th Craft Toolkit Workshop (stone crafts): 23 sections (A-W), "
        "108 questions."
    )
    assert seed_questionnaire_w4.CORPUS == w4_apply.QUESTIONNAIRE_CORPUS == W4_CORPUS
    assert seed_questionnaire_w4.OWNER_ID == w4_apply.MASTER_ADMIN_ID == OWNER_ID
    assert w4_apply.W4_ID == W4_ID
