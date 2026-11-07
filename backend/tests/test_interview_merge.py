import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, PassThroughTx, install
from app.api.router import api_router
from app.core import deps

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

OWNER = "u-owner"
STRANGER = "u-stranger"


class _Db(PassThroughTx):
    UNIQUE_KEYS: dict[str, tuple[tuple[str, ...], ...]] = {
        "questionnaireinterview": (("questionnaireId", "artisanSetKey"),),
        "questionnaireresponse": (("interviewId", "questionId"),),
    }

    def __init__(self, **seeds: list[dict[str, Any]]) -> None:
        object.__setattr__(self, "_tables", {})
        for name, rows in seeds.items():
            self._tables[name] = FakeTable({}, rows, self.UNIQUE_KEYS.get(name, ()))

    def __getattr__(self, name: str) -> Any:
        tables = object.__getattribute__(self, "_tables")
        if name not in tables:
            tables[name] = FakeTable({}, [], type(self).UNIQUE_KEYS.get(name, ()))
        return tables[name]


def _user(user_id: str, role: str = "RESEARCHER", **grants: Any) -> SimpleNamespace:
    flags = {
        "canManageCrafts": False,
        "canManageWorkshops": False,
        "canManageQuestionnaire": False,
        "canDownloadDataset": False,
        "canReview": False,
        "canViewProvenance": False,
    }
    flags.update(grants)
    return SimpleNamespace(
        id=user_id, email=f"{user_id}@example.test", name=user_id, role=role, **flags
    )


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


def _call(method: str, path: str, body: dict[str, Any] | None = None) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://merge.test") as client:
            return await client.request(method, f"/api{path}", json=body)

    return asyncio.run(run())



def _interview(interview_id: str, title: str, set_key: str | None, **extra: Any) -> dict[str, Any]:
    return {
        "id": interview_id,
        "title": title,
        "questionnaireId": W2,
        "artisanSetKey": set_key,
        "createdById": OWNER,
        "status": "APPROVED",
        "locationId": None,
        "workshopId": None,
        "notes": None,
        "place": None,
        "language": None,
        "interviewDate": None,
        "extraMetadata": None,
        "reviewNotes": None,
        "reviewedById": None,
        "reviewedAt": None,
        **extra,
    }


def _response(row_id: str, interview_id: str, question_id: str, text: str | None, **extra: Any):
    return {
        "id": row_id,
        "interviewId": interview_id,
        "questionId": question_id,
        "answerText": text,
        "notes": None,
        "answeredById": OWNER,
        **extra,
    }


def _clip(media_id: str, interview_id: str, filename: str, **extra: Any) -> dict[str, Any]:
    return {
        "id": media_id,
        "questionnaireInterviewId": interview_id,
        "originalFilename": filename,
        "objectKey": f"uploads/{media_id}.m4a",
        "url": f"https://cdn.example.test/uploads/{media_id}.m4a",
        "uploadedById": OWNER,
        "extraMetadata": None,
        "transcript": None,
        **extra,
    }


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch):
    fake = _Db(
        user=[
            {"id": OWNER, "email": "owner@example.test", "name": "Owner", "role": "RESEARCHER"},
            {"id": STRANGER, "email": "s@example.test", "name": "S", "role": "RESEARCHER"},
        ],
        artisan=[{"id": "a1"}, {"id": "a2"}, {"id": "a3"}],
        questionnaire=[
            {"id": W2, "title": "2nd Craft Toolkit Workshop"},
            {"id": W3, "title": "3rd Craft Toolkit Workshop"},
        ],
        questionnairequestion=[
            {"id": "q-clay", "questionnaireId": W2, "sectionCode": "D",
             "prompt": "Where is the clay dug from?"},
            {"id": "q-fire", "questionnaireId": W2, "sectionCode": "F",
             "prompt": "How is the ware fired?"},
            {"id": "q-kiln", "questionnaireId": W2, "sectionCode": "F",
             "prompt": "Who built the kiln?"},
        ],
        questionnaireinterview=[
            _interview("iv-d", "D Black Pottery", "a1,a2,a3"),
            _interview("iv-f", "Section F", "a1,a2"),
        ],
        questionnaireinterviewartisan=[
            {"id": "l1", "interviewId": "iv-d", "artisanId": "a1"},
            {"id": "l2", "interviewId": "iv-d", "artisanId": "a2"},
            {"id": "l3", "interviewId": "iv-d", "artisanId": "a3"},
            {"id": "l4", "interviewId": "iv-f", "artisanId": "a1"},
            {"id": "l5", "interviewId": "iv-f", "artisanId": "a2"},
        ],
        questionnaireresponse=[
            _response("r-d-clay", "iv-d", "q-clay", "Dug from the riverbank below the village."),
            _response("r-f-fire", "iv-f", "q-fire", "Fired in an open pit for nine hours."),
        ],
        mediafile=[
            _clip("m-f-1", "iv-f", "F_q-fire_ivf_0912_20260714.m4a"),
            _clip("m-f-2", "iv-f", "F_q-kiln_ivf_0430_20260714.m4a"),
            _clip("m-d-1", "iv-d", "D_q-clay_ivd_1130_20260712.m4a"),
        ],
    )
    install(monkeypatch, fake)
    _CURRENT["user"] = _user(OWNER, role="ADMIN")
    yield fake
    _CURRENT["user"] = None


def _ids(rows: list[Any], attr: str = "id") -> list[str]:
    return sorted(getattr(row, attr) for row in rows)



def test_a_colliding_edit_is_refused_by_name(db: _Db) -> None:
    _CURRENT["user"] = _user(OWNER, role="ADMIN")

    response = _call("PATCH", "/questionnaire/interviews/iv-f", {"artisanIds": ["a1", "a2", "a3"]})

    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, dict), "the body still carries only prose"
    assert detail["holder"] == {"id": "iv-d", "title": "D Black Pottery"}, detail
    assert detail["code"] == "artisan_set_taken"
    assert "single shared entry" in detail["message"]


def test_the_refused_edit_leaves_the_artisan_links_exactly_as_they_were(db: _Db) -> None:
    _CURRENT["user"] = _user(OWNER, role="ADMIN")

    _call("PATCH", "/questionnaire/interviews/iv-f", {"artisanIds": ["a1", "a2", "a3"]})

    links = [row for row in db.questionnaireinterviewartisan.rows if row.interviewId == "iv-f"]
    assert _ids(links, "artisanId") == ["a1", "a2"]
    survivor = next(row for row in db.questionnaireinterview.rows if row.id == "iv-f")
    assert survivor.artisanSetKey == "a1,a2"



def test_a_clean_merge_moves_the_answers_and_the_recordings_and_removes_the_source(db: _Db) -> None:
    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text

    assert _ids(db.questionnaireinterview.rows) == ["iv-d"]

    moved = [row for row in db.questionnaireresponse.rows if row.interviewId == "iv-d"]
    assert _ids(moved) == ["r-d-clay", "r-f-fire"]
    fired = next(row for row in moved if row.id == "r-f-fire")
    assert fired.answerText == "Fired in an open pit for nine hours."
    assert fired.answeredById == OWNER, "the answer changed hands on the way across"

    assert _ids(db.mediafile.rows) == ["m-d-1", "m-f-1", "m-f-2"]
    assert {row.questionnaireInterviewId for row in db.mediafile.rows} == {"iv-d"}

    body = response.json()
    assert body["id"] == "iv-d"
    assert body["title"] == "D Black Pottery"
    assert sorted(node["id"] for node in body["media"]) == ["m-d-1", "m-f-1", "m-f-2"]
    assert sorted(node["id"] for node in body["responses"]) == ["r-d-clay", "r-f-fire"]


def test_an_identical_answer_on_both_sides_is_not_a_conflict(db: _Db) -> None:
    asyncio.run(
        db.questionnaireresponse.create(
            data=_response(
                "r-f-clay", "iv-f", "q-clay", "Dug from the riverbank below the village."
            )
        )
    )

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text
    clay = [row for row in db.questionnaireresponse.rows if row.questionId == "q-clay"]
    assert _ids(clay) == ["r-d-clay"], "two rows for one question, or the wrong one survived"
    assert clay[0].answerText == "Dug from the riverbank below the village."


def test_an_answer_only_one_side_gave_travels_rather_than_being_dropped(db: _Db) -> None:
    asyncio.run(
        db.questionnaireresponse.create(data=_response("r-d-fire", "iv-d", "q-kiln", None))
    )
    asyncio.run(
        db.questionnaireresponse.create(
            data=_response("r-f-kiln", "iv-f", "q-kiln", "Her husband built it in 1998.",
                           answeredById=STRANGER)
        )
    )

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text
    kiln = [row for row in db.questionnaireresponse.rows if row.questionId == "q-kiln"]
    assert _ids(kiln) == ["r-f-kiln"], "the empty row won, or both survived"
    assert kiln[0].answerText == "Her husband built it in 1998."
    assert kiln[0].answeredById == STRANGER, "authorship was rewritten by the move"
    assert kiln[0].interviewId == "iv-d"



def test_two_different_answers_to_one_question_refuse_and_name_the_questions(db: _Db) -> None:
    asyncio.run(
        db.questionnaireresponse.create(
            data=_response("r-f-clay", "iv-f", "q-clay", "Dug from the hillside at Kanthal.")
        )
    )

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "merge_answer_conflict"
    assert [item["questionId"] for item in detail["questions"]] == ["q-clay"]
    assert detail["questions"][0]["sectionCode"] == "D"
    assert detail["questions"][0]["prompt"] == "Where is the clay dug from?"
    assert detail["questions"][0]["fields"] == ["answerText"]


def test_the_conflicting_merge_writes_absolutely_nothing(db: _Db) -> None:
    asyncio.run(
        db.questionnaireresponse.create(
            data=_response("r-f-clay", "iv-f", "q-clay", "Dug from the hillside at Kanthal.")
        )
    )

    _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"], "the source was removed"
    assert {row.id for row in db.mediafile.rows if row.questionnaireInterviewId == "iv-f"} == {
        "m-f-1",
        "m-f-2",
    }, "the recordings moved under a refusal"
    survivors = {row.id: row.interviewId for row in db.questionnaireresponse.rows}
    assert survivors == {
        "r-d-clay": "iv-d",
        "r-f-fire": "iv-f",
        "r-f-clay": "iv-f",
    }, survivors
    clay = next(row for row in db.questionnaireresponse.rows if row.id == "r-d-clay")
    assert clay.answerText == "Dug from the riverbank below the village."
    assert db.recordrevision.rows == []


def test_every_conflicting_question_is_named_not_merely_the_first(db: _Db) -> None:
    asyncio.run(
        db.questionnaireresponse.create(
            data=_response("r-f-clay", "iv-f", "q-clay", "Dug from the hillside at Kanthal.")
        )
    )
    asyncio.run(
        db.questionnaireresponse.create(data=_response("r-d-fire", "iv-d", "q-fire", "In a kiln."))
    )

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 409, response.text
    named = response.json()["detail"]["questions"]
    assert [item["questionId"] for item in named] == ["q-clay", "q-fire"]
    assert [item["sectionCode"] for item in named] == ["D", "F"]



def test_two_interviews_on_different_instruments_may_not_be_merged(db: _Db) -> None:
    asyncio.run(
        db.questionnaireinterview.update(
            where={"id": "iv-f"}, data={"questionnaireId": W3, "artisanSetKey": None}
        )
    )

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 422, response.text
    assert "different questionnaires" in response.json()["detail"]
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]


def test_an_interview_cannot_be_merged_into_itself(db: _Db) -> None:
    response = _call("POST", "/questionnaire/interviews/iv-d/merge-into/iv-d")

    assert response.status_code == 422, response.text
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]


def test_an_unknown_interview_on_either_side_is_a_404(db: _Db) -> None:
    assert _call("POST", "/questionnaire/interviews/iv-nope/merge-into/iv-d").status_code == 404
    assert _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-nope").status_code == 404



def test_a_stranger_may_not_merge_and_is_refused_before_any_write(db: _Db) -> None:
    _CURRENT["user"] = _user(STRANGER)

    merge = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert merge.status_code == 403, merge.text
    assert merge.json()["detail"] == "Only an admin can delete this."
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]
    assert db.recordrevision.rows == []


def test_the_source_interviews_own_author_may_no_longer_fold_it(db: _Db) -> None:
    _CURRENT["user"] = _user(OWNER)

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 403, response.text
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]


def test_the_fold_leaves_a_ledger_entry_naming_where_the_interview_went(db: _Db) -> None:
    _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    entries = db.recordrevision.rows
    assert len(entries) == 1, entries
    assert entries[0].recordId == "iv-f"
    assert entries[0].recordType == "questionnaire"
    assert entries[0].editedById == OWNER



def test_a_saved_interview_comes_back_with_playable_recordings(db: _Db) -> None:
    response = _call("GET", "/questionnaire/interviews/iv-d")

    assert response.status_code == 200, response.text
    clip = next(node for node in response.json()["media"] if node["id"] == "m-d-1")
    assert "X-Amz-Signature=" in clip["url"]
    assert clip["url"] != "https://cdn.example.test/uploads/m-d-1.m4a"


def test_the_surviving_interview_from_a_merge_is_playable_too(db: _Db) -> None:
    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text
    nodes = response.json()["media"]
    assert sorted(node["id"] for node in nodes) == ["m-d-1", "m-f-1", "m-f-2"]
    for node in nodes:
        assert node["url"], node


def test_a_stranger_still_does_not_get_another_uploaders_urls(db: _Db) -> None:
    _CURRENT["user"] = _user(STRANGER)

    response = _call("GET", "/questionnaire/interviews/iv-d")

    assert response.status_code == 200, response.text
    clip = next(node for node in response.json()["media"] if node["id"] == "m-d-1")
    assert "url" not in clip, clip
    assert "objectKey" not in clip, clip
