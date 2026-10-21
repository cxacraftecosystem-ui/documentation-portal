"""WEB CAPTURE (2026-10-17): the server halves of A7, A12, B8, C18 and C22.

* A7  — "Use as answer": a transcript becomes answer text by one shared rule
  (``shared/transcript-answer-vectors.json``, also run by the web), a clip is matched to its ONE
  question, and ``apply-transcripts`` fills only EMPTY answers, stamped ``answerSource=TRANSCRIPT``.
* A12 — a linked product's craft name, artisan name and place come from the linked records.
* B8  — an interview may be created without a title (the server names it), and a question may name
  the artisan field it is answered from, from a closed list.
* C18 — a new artisan carries an Aadhaar or the explicit "Not shared by artisan" outcome.
* C22 — a process step may be saved without a name.

No database: the routes are driven against fakes, and the migration is read as text.
"""

from __future__ import annotations

import json
import pathlib
from datetime import datetime
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import ValidationError

from app.api.routes import artisans as artisan_routes
from app.api.routes import questionnaire as q_routes
from app.schemas.questionnaire import (
    ARTISAN_FIELD_KEYS,
    QuestionnaireInterviewCreate,
    QuestionnaireQuestionCreate,
    QuestionnaireQuestionUpdate,
    QuestionnaireResponseInput,
)
from app.schemas.records import ArtisanCreate, ArtisanUpdate, ProcessStepInput, ProductCreate
from app.services import linked_names

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
VALID_AADHAAR = "234123412346"  # passes the Verhoeff check
LOCATION = {"latitude": 26.8, "longitude": 75.5, "state": "Rajasthan", "district": "Jaipur"}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


# ──────────────────────────────────────────────────────────────────────────────────────────────
# A7 — transcripts as answers
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_transcript_answer_text_runs_the_shared_vectors():
    table = json.loads((ROOT / "shared" / "transcript-answer-vectors.json").read_text(encoding="utf-8"))
    assert len(table["vectors"]) >= 5
    for row in table["vectors"]:
        assert q_routes.transcript_answer_text(row["transcript"]) == row["answer"], row


def _clip(**kwargs: Any) -> SimpleNamespace:
    base = {"id": None, "extraMetadata": None, "caption": None, "transcriptText": None, "createdAt": datetime(2026, 10, 1)}
    base.update(kwargs)
    return SimpleNamespace(**base)


def _q(qid: str, code: str, order: int, prompt: str, lineage: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(id=qid, sectionCode=code, sortOrder=order, prompt=prompt, lineageId=lineage or qid)


QUESTIONS = [
    _q("q-d3", "D", 3, "How is the clay prepared?"),
    _q("q-d30", "D", 30, "Who sells it?"),
    _q("q-a1", "A", 1, "What is your name?"),
    _q("q-resp1", "RESP", 1, "Who answered?"),
]


def test_a_clip_finds_its_question_by_metadata_then_by_either_clients_caption():
    assert q_routes.clip_question_id(_clip(extraMetadata={"questionId": "q-a1"}), QUESTIONS) == "q-a1"
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3 - How is the clay prepared?"), QUESTIONS) == "q-d3"
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3 How is the clay prepared?"), QUESTIONS) == "q-d3"
    assert q_routes.clip_question_id(_clip(caption="Question audio: D30 - Who sells it?"), QUESTIONS) == "q-d30"


def test_a_section_take_or_a_foreign_question_answers_nothing():
    assert q_routes.clip_question_id(_clip(caption="Section audio: D RAW MATERIALS"), QUESTIONS) is None
    assert q_routes.clip_question_id(_clip(extraMetadata={"questionId": "other-instrument"}), QUESTIONS) is None
    assert q_routes.clip_question_id(_clip(caption="Field media"), QUESTIONS) is None


def test_m20_a_clip_is_placed_by_its_link_never_by_its_position():
    """Audit M20: after a reorder "D3" is another question. The caption's number must not place it."""
    reordered = [_q("q-clay", "D", 7, "How is the clay prepared?"), _q("q-kiln", "D", 3, "Who built the kiln?")]
    # The caption still says D3 (its position on the day), but its prompt names the clay question.
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3 How is the clay prepared?"), reordered) == "q-clay"
    # A caption with no prompt, or a prompt that no longer exists, is not placed by its number.
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3"), reordered) is None
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3 - A question since deleted"), reordered) is None
    # The recording's target wins over the caption, by exact wording or by lineage.
    linked = [{"askedQuestionId": None, "questionLineageId": "q-kiln"}]
    assert q_routes.clip_question_id(_clip(caption="Question audio: D3 How is the clay prepared?"), reordered, linked) == "q-kiln"
    rows = [_clip(id="m-1", caption="Question audio: D3", transcriptText="The kiln was built by my father.")]
    assert q_routes.question_clip_transcripts(rows, reordered, {"m-1": linked}) == {"q-kiln": "The kiln was built by my father."}
    assert q_routes.question_clip_transcripts(rows, reordered) == {}


def test_the_newest_transcribed_clip_wins_and_empty_transcripts_are_skipped():
    rows = [
        _clip(caption="Question audio: D3 - How is the clay prepared?", transcriptText="older", createdAt=datetime(2026, 10, 1)),
        _clip(caption="Question audio: D3 - How is the clay prepared?", transcriptText="**S:** newer", createdAt=datetime(2026, 10, 2)),
        _clip(caption="Question audio: A1 - What is your name?", transcriptText="   "),
    ]
    assert q_routes.question_clip_transcripts(rows, QUESTIONS) == {"q-d3": "S: newer"}


def test_a_response_may_say_it_came_from_the_transcript_and_nothing_else():
    assert QuestionnaireResponseInput(questionId="q", answerText="x", answerSource="TRANSCRIPT").answerSource == "TRANSCRIPT"
    assert QuestionnaireResponseInput(questionId="q", answerText="x").answerSource is None
    with pytest.raises(ValidationError):
        QuestionnaireResponseInput(questionId="q", answerText="x", answerSource="MACHINE")


class _FakeResponses:
    def __init__(self, rows: list[Any]):
        self.rows = rows
        self.created: list[dict[str, Any]] = []
        self.updated: list[tuple[str, dict[str, Any]]] = []

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return list(self.rows)

    async def create_many(self, data: list[dict[str, Any]]) -> None:
        self.created.extend(data)

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> None:
        self.updated.append((where["id"], data))


class _FakeQuestions:
    def __init__(self, rows: list[Any]):
        self.rows = rows

    async def find_many(self, where: dict[str, Any]) -> list[Any]:
        return list(self.rows)


async def test_upsert_stamps_the_source_on_a_new_answer_and_clears_it_on_a_typed_change(monkeypatch):
    stored = SimpleNamespace(id="r-1", questionId="q-2", answerText="old", notes=None, answeredById="u-1")
    responses = _FakeResponses([stored])
    questions = _FakeQuestions(
        [SimpleNamespace(id="q-1", questionnaireId="inst"), SimpleNamespace(id="q-2", questionnaireId="inst")]
    )
    monkeypatch.setattr(q_routes, "db", SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions))
    user = SimpleNamespace(id="u-1", role="RESEARCHER")
    await q_routes.upsert_responses(
        "i-1",
        [
            QuestionnaireResponseInput(questionId="q-1", answerText="from clip", answerSource="TRANSCRIPT"),
            QuestionnaireResponseInput(questionId="q-2", answerText="typed again"),
        ],
        user,
        "inst",
    )
    assert responses.created[0]["answerSource"] == "TRANSCRIPT"
    assert responses.updated == [("r-1", {"answerText": "typed again", "notes": None, "answerSource": None, "answeredById": "u-1", "answerOrigin": None})]


async def test_apply_transcripts_fills_only_the_empty_answers(monkeypatch):
    interview = SimpleNamespace(id="i-1", questionnaireId="inst")
    media = [
        _clip(caption="Question audio: D3 - How is the clay prepared?", transcriptText="**Speaker 1:** Clay from the river."),
        _clip(caption="Question audio: A1 - What is your name?", transcriptText="Typed already, do not touch"),
        _clip(caption="Section audio: D RAW", transcriptText="a whole section"),
    ]
    answered = [SimpleNamespace(questionId="q-a1", answerText="Ramesh")]
    questions = [QUESTIONS[0], QUESTIONS[2]]
    captured: dict[str, Any] = {}

    async def fake_require(model, record_id):
        return interview

    async def fake_gather(*reads):
        for read in reads:
            read.close()
        return media, answered, questions

    async def fake_upsert(interview_id, fills, user, questionnaire_id):
        captured["fills"] = [(f.questionId, f.answerText, f.answerSource) for f in fills]
        captured["instrument"] = questionnaire_id

    class _Interviews:
        async def find_unique(self, where):
            return interview

    class _Any:
        def find_many(self, where, **_):
            async def _rows():
                return []

            return _rows()

    async def fake_hydrate(rows, relations):
        return None

    async def fake_guard(record, user, data, record_type, **_):
        captured["guarded"] = (record_type, data)
        return True

    monkeypatch.setattr(q_routes, "require_record", fake_require)
    monkeypatch.setattr(q_routes, "gather_reads", fake_gather)
    monkeypatch.setattr(q_routes, "upsert_responses", fake_upsert)
    monkeypatch.setattr(q_routes, "guard_record_edit", fake_guard)
    monkeypatch.setattr(q_routes, "hydrate_relations", fake_hydrate)
    monkeypatch.setattr(q_routes, "public_encode", lambda row, viewer=None: {"id": row.id})
    monkeypatch.setattr(
        q_routes,
        "db",
        SimpleNamespace(
            questionnaireinterview=_Interviews(),
            mediafile=_Any(),
            questionnaireresponse=_Any(),
            questionnairequestion=_Any(),
            questionnairerecording=_Any(),
        ),
    )
    result = await q_routes.apply_interview_transcripts("i-1", current_user=SimpleNamespace(id="u-9", role="ADMIN"))
    assert captured["fills"] == [("q-d3", "Speaker 1: Clay from the river.", "TRANSCRIPT")]
    assert captured["instrument"] == "inst"
    assert captured["guarded"] == ("questionnaire", {"transcriptAnswers": {"q-d3": "Speaker 1: Clay from the river."}})
    assert result == {"applied": 1, "skipped": 1, "interview": {"id": "i-1"}}


def test_the_route_is_mounted_for_the_web_button():
    paths = {(route.path, tuple(sorted(route.methods))) for route in q_routes.questionnaire_router.routes}
    assert any(path.endswith("/interviews/{interview_id}/apply-transcripts") and "POST" in methods for path, methods in paths)


# ──────────────────────────────────────────────────────────────────────────────────────────────
# A12 — names from the linked records
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _product(**kwargs: Any) -> ProductCreate:
    body: dict[str, Any] = {"productName": "Dabu print stole", "location": LOCATION}
    body.update(kwargs)
    return ProductCreate(**body)


def test_a_linked_product_needs_no_typed_names_and_an_unlinked_one_still_does():
    assert _product(artisanId="a-1").artisanName is None
    assert _product(craftId="c-1", artisanName="Ramesh", place="Bagru").craftName is None
    with pytest.raises(ValidationError, match="artisan name"):
        _product(craftName="Block printing")
    with pytest.raises(ValidationError, match="craft name"):
        _product(artisanName="Ramesh", place="Bagru")
    assert _product(craftName="Block printing", artisanName="Ramesh", place="Bagru").place == "Bagru"


class _Rows:
    def __init__(self, rows: dict[str, Any]):
        self.rows = rows

    async def find_unique(self, where: dict[str, Any], include: Any = None) -> Any:
        return self.rows.get(where["id"])


def _patch_links(monkeypatch):
    artisan = SimpleNamespace(id="a-1", name="Ramesh Chhipa", place="Bagru", craftId="c-1")
    crafts = {"c-1": SimpleNamespace(id="c-1", name="Block Printing"), "c-2": SimpleNamespace(id="c-2", name="Dabu")}
    monkeypatch.setattr(linked_names, "db", SimpleNamespace(artisan=_Rows({"a-1": artisan}), craft=_Rows(crafts)))


async def test_the_server_fills_names_from_the_artisan_and_its_craft(monkeypatch):
    _patch_links(monkeypatch)
    data = await linked_names.fill_product_names({"artisanId": "a-1", "artisanName": "Ramesh Chipa", "place": "bagru"})
    assert data["artisanName"] == "Ramesh Chhipa"
    assert data["place"] == "Bagru"
    assert data["craftId"] == "c-1"
    assert data["craftName"] == "Block Printing"


async def test_an_explicit_craft_beats_the_artisans_own(monkeypatch):
    _patch_links(monkeypatch)
    data = await linked_names.fill_product_names({"artisanId": "a-1", "craftId": "c-2", "craftName": "typed"})
    assert data["craftId"] == "c-2"
    assert data["craftName"] == "Dabu"


async def test_an_unlinked_create_keeps_its_typed_names_and_an_edit_without_links_changes_nothing(monkeypatch):
    _patch_links(monkeypatch)
    typed = {"craftName": "Weaving", "artisanName": "Sita", "place": "Kutch"}
    assert await linked_names.fill_product_names(dict(typed)) == typed
    stored = SimpleNamespace(craftId="c-1", artisanId="a-1")
    assert await linked_names.fill_product_names({"remarks": "x"}, stored=stored) == {"remarks": "x"}


# ──────────────────────────────────────────────────────────────────────────────────────────────
# B8 — automatic title and the question's artisan field
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_an_interview_may_be_created_without_a_title():
    assert QuestionnaireInterviewCreate(location=LOCATION).title is None
    assert QuestionnaireInterviewCreate(title="Given", location=LOCATION).title == "Given"


def test_the_automatic_title_names_who_what_and_when():
    when = datetime(2026, 10, 17, 9, 30)
    assert q_routes.interview_title_from("Ramesh", "Craft questionnaire", when) == "Ramesh · Craft questionnaire · 17 Oct"
    assert q_routes.interview_title_from(None, None, when) == "17 Oct"


async def test_the_automatic_title_reads_the_first_artisan_and_the_instrument(monkeypatch):
    monkeypatch.setattr(
        q_routes,
        "db",
        SimpleNamespace(
            artisan=_Rows({"a-1": SimpleNamespace(name="Ramesh")}),
            questionnaire=_Rows({"inst": SimpleNamespace(title="Craft questionnaire")}),
        ),
    )
    when = datetime(2026, 10, 17)
    assert await q_routes.automatic_interview_title(["a-1"], "inst", when) == "Ramesh · Craft questionnaire · 17 Oct"
    assert await q_routes.automatic_interview_title(["a-1", "a-2"], "inst", when) == "Ramesh and others · Craft questionnaire · 17 Oct"


def test_a_question_maps_only_to_a_known_artisan_field():
    assert "place" in ARTISAN_FIELD_KEYS and "interviewer" in ARTISAN_FIELD_KEYS
    assert QuestionnaireQuestionCreate(sectionId="s", prompt="Village", artisanField="place").artisanField == "place"
    assert QuestionnaireQuestionUpdate(artisanField="").artisanField is None
    with pytest.raises(ValidationError):
        QuestionnaireQuestionCreate(sectionId="s", prompt="x", artisanField="aadhaarNumber")


def test_the_question_routes_write_and_clear_the_mapping():
    source = (ROOT / "backend" / "app" / "api" / "routes" / "questionnaire.py").read_text(encoding="utf-8")
    assert '"artisanField": payload.artisanField' in source
    assert '"artisanField" in payload.model_fields_set and payload.artisanField is None' in source


# ──────────────────────────────────────────────────────────────────────────────────────────────
# C18 — "Not shared by artisan"
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _artisan(**kwargs: Any) -> ArtisanCreate:
    body: dict[str, Any] = {
        "name": "Ramesh",
        "place": "Bagru",
        "dos": "1. Print",
        "donts": "1. Rush",
        "craftId": "c-1",
        "location": LOCATION,
    }
    body.update(kwargs)
    return ArtisanCreate(**body)


def test_a_new_artisan_needs_an_aadhaar_or_the_explicit_outcome():
    with pytest.raises(ValidationError, match="12-digit Aadhaar"):
        _artisan()
    with pytest.raises(ValidationError, match="12-digit Aadhaar"):
        _artisan(aadhaarNumber="   ")
    declined = _artisan(aadhaarNotShared=True)
    assert declined.aadhaarNumber is None and declined.aadhaarNotShared is True


def test_a_number_beside_the_outcome_wins_and_clears_the_flag():
    shared = _artisan(aadhaarNumber=VALID_AADHAAR, aadhaarNotShared=True)
    assert shared.aadhaarNumber == VALID_AADHAAR and shared.aadhaarNotShared is False
    assert _artisan(aadhaarNumber=VALID_AADHAAR).aadhaarNotShared is False


def test_a_bad_number_is_still_refused():
    with pytest.raises(ValidationError):
        _artisan(aadhaarNumber="123412341234")


def test_an_edit_keeps_the_flag_and_the_number_consistent():
    assert artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNumber": VALID_AADHAAR, "aadhaarNotShared": True}) == {
        "aadhaarNumber": VALID_AADHAAR,
        "aadhaarNotShared": False,
    }
    assert artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": True}) == {
        "aadhaarNotShared": True,
        "aadhaarNumber": None,
    }
    assert artisan_routes.reconcile_aadhaar_not_shared({"phone": "x"}) == {"phone": "x"}
    assert ArtisanUpdate(aadhaarNotShared=True).aadhaarNotShared is True


# ──────────────────────────────────────────────────────────────────────────────────────────────
# C22 — unnamed steps
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_a_process_step_may_be_saved_without_a_name():
    assert ProcessStepInput().name == ""
    assert ProcessStepInput(name="Second dip").name == "Second dip"


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The migration
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_the_migration_is_additive_and_matches_the_schema():
    sql = (ROOT / "backend" / "prisma" / "migrations" / "20261020121500_web_capture_fields" / "migration.sql").read_text(
        encoding="utf-8"
    )
    statements = [line for line in sql.splitlines() if line.strip() and not line.startswith("--")]
    assert all(line.startswith("ALTER TABLE") and " ADD COLUMN " in line for line in statements), statements
    assert '"aadhaarNotShared" BOOLEAN NOT NULL DEFAULT false' in sql
    schema = (ROOT / "backend" / "prisma" / "schema.prisma").read_text(encoding="utf-8")
    for column in ("aadhaarNotShared Boolean", "artisanField    String?", "answerSource String?"):
        assert column in schema


# ──────────────────────────────────────────────────────────────────────────────────────────────
# Review fixes (2026-10-17)
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _drive_apply(monkeypatch, interview: Any) -> dict[str, Any]:
    """Wire ``apply_interview_transcripts`` to fakes: one D3 clip, no answers. Returns what it wrote."""
    captured: dict[str, Any] = {"updates": []}
    media = [_clip(caption="Question audio: D3 - x", transcriptText="Clay from the river.")]
    questions = [SimpleNamespace(id="q-d3", sectionCode="D", sortOrder=3)]

    async def fake_require(model, record_id):
        return interview

    async def fake_gather(*reads):
        for read in reads:
            read.close()
        return media, [], questions

    async def fake_upsert(interview_id, fills, user, questionnaire_id):
        captured["fills"] = [f.questionId for f in fills]

    async def fake_guard(record, user, data, record_type, **_):
        captured["guarded"] = data
        return True

    class _Interviews:
        async def find_unique(self, where):
            return interview

        async def update(self, where, data):
            captured["updates"].append(data)

    class _Any:
        def find_many(self, where, **_):
            async def _rows():
                return []

            return _rows()

    async def fake_hydrate(rows, relations):
        return None

    monkeypatch.setattr(q_routes, "require_record", fake_require)
    monkeypatch.setattr(q_routes, "gather_reads", fake_gather)
    monkeypatch.setattr(q_routes, "upsert_responses", fake_upsert)
    monkeypatch.setattr(q_routes, "guard_record_edit", fake_guard)
    monkeypatch.setattr(q_routes, "hydrate_relations", fake_hydrate)
    monkeypatch.setattr(q_routes, "public_encode", lambda row, viewer=None: {"id": row.id})
    monkeypatch.setattr(
        q_routes,
        "db",
        SimpleNamespace(
            questionnaireinterview=_Interviews(),
            mediafile=_Any(),
            questionnaireresponse=_Any(),
            questionnairequestion=_Any(),
        ),
    )
    return captured


def _sitting(**kwargs: Any) -> SimpleNamespace:
    base = {"id": "i-1", "questionnaireId": "inst", "createdById": "u-1", "status": "PENDING", "extraMetadata": None}
    base.update(kwargs)
    return SimpleNamespace(**base)


async def test_apply_transcripts_refuses_a_volunteer_before_reading_anything(monkeypatch):
    from fastapi import HTTPException

    captured = _drive_apply(monkeypatch, _sitting())
    volunteer = SimpleNamespace(id="u-5", role="CROWDSOURCE_VOLUNTEER", canReview=False)
    with pytest.raises(HTTPException) as refused:
        await q_routes.apply_interview_transcripts("i-1", current_user=volunteer)
    assert refused.value.status_code == 403
    assert "fills" not in captured and "guarded" not in captured


async def test_apply_transcripts_runs_the_edit_gate_with_the_filled_answers(monkeypatch):
    captured = _drive_apply(monkeypatch, _sitting(status="APPROVED"))
    reviewer = SimpleNamespace(id="u-7", role="FIELD_CONTRIBUTOR", canReview=False)
    await q_routes.apply_interview_transcripts("i-1", current_user=reviewer)
    assert captured["guarded"] == {"transcriptAnswers": {"q-d3": "Clay from the river."}}
    assert captured["fills"] == ["q-d3"]
    # Not the creator, not sent back: the status is left alone.
    assert captured["updates"] == []


async def test_apply_transcripts_by_the_creator_resubmits_a_sitting_sent_back(monkeypatch):
    captured = _drive_apply(monkeypatch, _sitting(status="NEEDS_REVISION"))
    creator = SimpleNamespace(id="u-1", role="RESEARCHER", canReview=False)
    await q_routes.apply_interview_transcripts("i-1", current_user=creator)
    assert captured["updates"] == [{"status": "PENDING"}]


async def test_the_edit_gate_lets_a_contributor_fill_and_writes_the_revision(monkeypatch):
    """`guard_record_edit` itself, with the fill's key: a contributor may fill, and a revision is written."""
    from app.services import access

    written: list[dict[str, Any]] = []

    class _Revisions:
        async def create(self, data):
            written.append(data)

    async def not_privileged(record, user, record_type):
        return False

    monkeypatch.setattr(access, "db", SimpleNamespace(recordrevision=_Revisions()))
    monkeypatch.setattr(access, "record_edit_privilege", not_privileged)
    interview = SimpleNamespace(id="i-1", createdById="u-1")
    user = SimpleNamespace(id="u-7", role="FIELD_CONTRIBUTOR")
    await access.guard_record_edit(interview, user, {"transcriptAnswers": {"q-d3": "Clay"}}, "questionnaire")
    assert written and written[0]["recordType"] == "questionnaire" and written[0]["editedById"] == "u-7"


def test_a_resp_clip_finds_its_question_by_caption():
    assert q_routes.clip_question_id(_clip(caption="Question audio: RESP1 - Who answered?"), QUESTIONS) == "q-resp1"
    assert q_routes.clip_question_id(_clip(caption="Question audio: RESP 1 Who answered?"), QUESTIONS) == "q-resp1"


async def test_a_notes_only_change_keeps_the_transcript_mark(monkeypatch):
    stored = SimpleNamespace(
        id="r-1", questionId="q-1", answerText="from clip", notes=None, answeredById="u-1", answerSource="TRANSCRIPT"
    )
    responses = _FakeResponses([stored])
    questions = _FakeQuestions([SimpleNamespace(id="q-1", questionnaireId="inst")])
    monkeypatch.setattr(q_routes, "db", SimpleNamespace(questionnaireresponse=responses, questionnairequestion=questions))
    user = SimpleNamespace(id="u-1", role="RESEARCHER")
    await q_routes.upsert_responses(
        "i-1", [QuestionnaireResponseInput(questionId="q-1", answerText="from clip", notes="checked")], user, "inst"
    )
    assert responses.updated == [
        ("r-1", {"answerText": "from clip", "notes": "checked", "answerSource": "TRANSCRIPT", "answeredById": "u-1", "answerOrigin": None})
    ]


async def test_a_linked_create_the_records_cannot_name_is_refused(monkeypatch):
    from fastapi import HTTPException

    craftless = SimpleNamespace(id="a-2", name="Sita", place="", craftId=None)
    monkeypatch.setattr(linked_names, "db", SimpleNamespace(artisan=_Rows({"a-2": craftless}), craft=_Rows({})))
    with pytest.raises(HTTPException, match="craft") as refused:
        await linked_names.fill_product_names({"artisanId": "a-2", "craftName": None, "artisanName": None, "place": "Kutch"})
    assert refused.value.status_code == 422
    with pytest.raises(HTTPException, match="place"):
        await linked_names.fill_product_names({"artisanId": "a-2", "craftName": "Weaving", "artisanName": None, "place": None})
    # A typed value the record cannot supply is kept.
    kept = await linked_names.fill_product_names(
        {"artisanId": "a-2", "craftName": "Weaving", "artisanName": None, "place": "Kutch"}
    )
    assert kept["craftName"] == "Weaving" and kept["place"] == "Kutch" and kept["artisanName"] == "Sita"


def test_an_edit_may_send_a_blank_name_and_it_means_unchanged():
    from app.schemas.records import ProductUpdate

    update = ProductUpdate(craftName="", place="  ", artisanName="Ramesh")
    assert update.model_dump(exclude_unset=True) == {"artisanName": "Ramesh"}


async def test_an_unchanged_link_keeps_drifted_names_so_a_contributor_can_edit(monkeypatch):
    from app.core.deps import assert_can_contribute_fields

    _patch_links(monkeypatch)
    # Stored before the artisan was renamed to "Ramesh Chhipa".
    stored = SimpleNamespace(
        id="p-1",
        createdById="u-1",
        artisanId="a-1",
        craftId="c-1",
        artisanName="Ramesh",
        place="Bagru",
        craftName="Block Printing",
        remarks=None,
    )
    body = {
        "artisanId": "a-1",
        "craftId": "c-1",
        "artisanName": "Ramesh",
        "place": "Bagru",
        "craftName": "Block Printing",
        "remarks": "dyed twice",
    }
    data = await linked_names.fill_product_names(dict(body), stored=stored)
    assert data == body
    assert_can_contribute_fields(stored, SimpleNamespace(id="u-9", role="RESEARCHER"), data)
    # A changed link still re-derives.
    moved = await linked_names.fill_product_names({"artisanId": "a-1"}, stored=SimpleNamespace(artisanId="a-0", craftId="c-1"))
    assert moved["artisanName"] == "Ramesh Chhipa"


def test_the_outcome_cannot_wipe_a_stored_number_or_drop_without_one():
    from fastapi import HTTPException

    on_file = SimpleNamespace(aadhaarNumber=VALID_AADHAAR, aadhaarNotShared=False)
    with pytest.raises(HTTPException, match="on file") as refused:
        artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": True}, stored=on_file)
    assert refused.value.status_code == 422
    flagged = SimpleNamespace(aadhaarNumber=None, aadhaarNotShared=True)
    with pytest.raises(HTTPException, match="12-digit Aadhaar"):
        artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": False}, stored=flagged)
    with pytest.raises(HTTPException, match="12-digit Aadhaar"):
        artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": False, "aadhaarNumber": None}, stored=flagged)
    # A number arriving is the follow-up done.
    assert artisan_routes.reconcile_aadhaar_not_shared(
        {"aadhaarNumber": VALID_AADHAAR, "aadhaarNotShared": False}, stored=flagged
    ) == {"aadhaarNumber": VALID_AADHAAR, "aadhaarNotShared": False}
    # Leaving the flag alone, or re-sending it on a flagged record, is fine.
    assert artisan_routes.reconcile_aadhaar_not_shared({"phone": "x"}, stored=flagged) == {"phone": "x"}
    assert artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": True}, stored=flagged)["aadhaarNumber"] is None
    # A number on file as a fingerprint (the 2026-10-17 register) counts as on file too.
    fingerprinted = SimpleNamespace(aadhaarNumber=None, aadhaarFingerprint="ab" * 32, aadhaarNotShared=False)
    with pytest.raises(HTTPException) as refused_fp:
        artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNotShared": True}, stored=fingerprinted)
    assert refused_fp.value.status_code == 422
    # An ordinary number edit on an unflagged record is not also a flag edit.
    assert artisan_routes.reconcile_aadhaar_not_shared({"aadhaarNumber": VALID_AADHAAR}, stored=fingerprinted) == {
        "aadhaarNumber": VALID_AADHAAR
    }


def test_the_update_route_hands_the_stored_artisan_to_the_reconcile():
    source = (ROOT / "backend" / "app" / "api" / "routes" / "artisans.py").read_text(encoding="utf-8")
    assert "reconcile_aadhaar_not_shared(data, stored=artisan)" in source
