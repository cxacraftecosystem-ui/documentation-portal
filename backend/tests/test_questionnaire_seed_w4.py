import asyncio
from pathlib import Path

import pytest

from access_roster_fakes import FakeDb, install
from app.services.questionnaire_instruments import W3_ID, W3_TITLE, W4_ID, W4_TITLE
from app.services.questionnaire_seeding import seed_instrument

BACKEND_ROOT = Path(__file__).resolve().parents[1]
W3_CORPUS = BACKEND_ROOT / "app" / "data" / "questionnaire_questions_w3.json"
OWNER_ID = "cmqg22xf6000018qdavy2jvel"


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch) -> FakeDb:
    return install(monkeypatch, FakeDb())


def _seed(questionnaire_id: str, title: str, created_by_id: str | None = None):
    return asyncio.run(
        seed_instrument(
            questionnaire_id=questionnaire_id,
            title=title,
            description="An instrument",
            corpus_path=W3_CORPUS,
            created_by_id=created_by_id,
        )
    )


def test_the_fourth_instrument_reuses_the_third_corpus_and_the_ctw3_codes(db: FakeDb) -> None:
    _seed(W3_ID, W3_TITLE)
    _, counts = _seed(W4_ID, W4_TITLE, OWNER_ID)

    assert (counts["sections_created"], counts["questions_created"]) == (22, 81)
    w3 = {
        (q.sectionCode, q.sortOrder): q.questionCode
        for q in db.questionnairequestion.rows
        if q.questionnaireId == W3_ID
    }
    w4 = {
        (q.sectionCode, q.sortOrder): q.questionCode
        for q in db.questionnairequestion.rows
        if q.questionnaireId == W4_ID
    }
    assert len(w4) == 81
    assert w4 == w3
    assert all(code and code.startswith("CTW3.") for code in w4.values())
    assert len(set(w4.values())) == 81


def test_the_fourth_instrument_is_owned_by_the_named_master_admin(db: FakeDb) -> None:
    _seed(W4_ID, W4_TITLE, OWNER_ID)

    stored = next(q for q in db.questionnaire.rows if q.id == W4_ID)
    assert stored.createdById == OWNER_ID
    assert stored.title == W4_TITLE
    assert not stored.isDefault


def test_seeding_the_fourth_leaves_the_third_untouched(db: FakeDb) -> None:
    _seed(W3_ID, W3_TITLE)
    before = sorted(
        (q.id, q.prompt, q.questionCode, q.questionnaireId)
        for q in db.questionnairequestion.rows
        if q.questionnaireId == W3_ID
    )
    _seed(W4_ID, W4_TITLE, OWNER_ID)
    _, again = _seed(W4_ID, W4_TITLE, OWNER_ID)

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
        "The instrument used at the 4th Craft Toolkit Workshop (stone crafts): 22 sections (A-V), "
        "81 questions."
    )
    assert seed_questionnaire_w4.OWNER_ID == w4_apply.MASTER_ADMIN_ID == OWNER_ID
    assert w4_apply.W4_ID == W4_ID
