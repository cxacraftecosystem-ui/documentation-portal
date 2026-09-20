"""AN EDIT MAY FOLD, AS A CREATE ALREADY DOES — explicitly, never silently.

THE SITUATION THESE TESTS ARE ABOUT. Two researchers recorded one artisan set as two sittings
titled by the sections they covered — "D Black Pottery" and an "F" one. (`section_codes_from_title`
documents that practice: researchers titled interviews "Section K & L", "section F".) The F sitting
had missed one artisan. Adding that artisan makes F's `artisanSetKey` equal D's, and
`replace_interview_artisans` answered 409 with prose a client could only print: it never said WHICH
interview held the set, so the client could not offer "D Black Pottery already covers this set —
move this interview's sections and recordings into it?", and the researcher had no way forward.

A create for a taken set has always folded (`merge_into_interview`). An edit could not fold at all.
So: the 409 now NAMES the holder, and `POST /interviews/{id}/merge-into/{targetId}` performs the
move — only when a client asks for it, and never when the two rows disagree about an answer.

These drive the REAL router over HTTP with `db` replaced by the shared in-memory fake, so what is
asserted is what the handler actually did to the rows. NOTHING TOUCHES A DATABASE: `backend/.env`
carries a placeholder DSN and this suite never opens a connection.

THE FAKE CARRIES THE TWO UNIQUE INDEXES THE BEHAVIOUR RESTS ON —
`@@unique([questionnaireId, artisanSetKey])` on the interview (schema.prisma:1461) and
`@@unique([interviewId, questionId])` on the response (schema.prisma:1496). Without the first,
`test_a_colliding_edit_is_refused_by_name` passes over a route that never refuses; without the
second, the merge's "two rows for one question cannot both land" reconciliation is untested because
nothing would ever have stopped them landing.
"""

import asyncio
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, install
from app.api.router import api_router
from app.core import deps

W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"

OWNER = "u-owner"
STRANGER = "u-stranger"


class _Db:
    """`db`, auto-vivifying an empty table for anything asked of it.

    The same shape `tests/test_questionnaire_scope.py` uses and for the same reason: the interview
    handlers hydrate six relations apiece, and a strict fake would turn every one of those into a
    per-test registration exercise that tests nothing.
    """

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


# --- the corpus these tests run against -----------------------------------------------------------
#
# D holds three artisans and is titled by the section it covers. F holds two of the same three — the
# missing one is the whole bug — and is titled by ITS section. Both sittings are on one instrument,
# because that is the only way their set keys can ever collide.


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
    _CURRENT["user"] = _user(OWNER)
    yield fake
    _CURRENT["user"] = None


def _ids(rows: list[Any], attr: str = "id") -> list[str]:
    return sorted(getattr(row, attr) for row in rows)


# --- 1. THE REFUSAL NAMES THE HOLDER --------------------------------------------------------------


def test_a_colliding_edit_is_refused_by_name(db: _Db) -> None:
    """Adding the missing artisan to F still refuses — AND now says which interview holds the set.

    The refusal is the unchanged half of this change. What is new is that the body carries the
    holder's `id` and `title` under stable keys, which is the whole difference between a client that
    can offer "D Black Pottery already covers this set. Move this interview's sections and
    recordings into it?" and a client that can only print a sentence.
    """
    _CURRENT["user"] = _user(OWNER, role="ADMIN")

    response = _call("PATCH", "/questionnaire/interviews/iv-f", {"artisanIds": ["a1", "a2", "a3"]})

    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert isinstance(detail, dict), "the body still carries only prose"
    assert detail["holder"] == {"id": "iv-d", "title": "D Black Pottery"}, detail
    assert detail["code"] == "artisan_set_taken"
    # The human half did not go away when the machine half arrived.
    assert "single shared entry" in detail["message"]


def test_the_refused_edit_leaves_the_artisan_links_exactly_as_they_were(db: _Db) -> None:
    """The 409 is still a refusal, not a half-applied edit. `replace_interview_artisans` raises from
    the `artisanSetKey` update, ABOVE its own delete-then-insert of the links — so a refusal that
    had drifted below that point would answer 409 having already wiped the set of artisans a
    researcher picked for a group sitting, and the join rows are the only record of who was there.
    """
    _CURRENT["user"] = _user(OWNER, role="ADMIN")

    _call("PATCH", "/questionnaire/interviews/iv-f", {"artisanIds": ["a1", "a2", "a3"]})

    links = [row for row in db.questionnaireinterviewartisan.rows if row.interviewId == "iv-f"]
    assert _ids(links, "artisanId") == ["a1", "a2"]
    survivor = next(row for row in db.questionnaireinterview.rows if row.id == "iv-f")
    assert survivor.artisanSetKey == "a1,a2"


# --- 2. A CLEAN MERGE -----------------------------------------------------------------------------


def test_a_clean_merge_moves_the_answers_and_the_recordings_and_removes_the_source(db: _Db) -> None:
    """The move the owner ruled for: F's sections and recordings land on D, and F is gone.

    MEDIA IS ASSERTED AS HARD AS THE ANSWERS. On this instrument the recordings ARE the interview —
    the clips carry their section in the filename and are the completion signal for a sitting whose
    answers were spoken rather than typed — so a merge that moved only responses would move almost
    nothing. Worse: `MediaFile.questionnaireInterviewId` is `onDelete: SetNull`, so deleting the
    source without repointing them first would have ORPHANED every clip rather than moving it.
    """
    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text

    # The source is gone.
    assert _ids(db.questionnaireinterview.rows) == ["iv-d"]

    # Both sittings' answers are on the survivor, and F's answer kept its own row identity.
    moved = [row for row in db.questionnaireresponse.rows if row.interviewId == "iv-d"]
    assert _ids(moved) == ["r-d-clay", "r-f-fire"]
    fired = next(row for row in moved if row.id == "r-f-fire")
    assert fired.answerText == "Fired in an open pit for nine hours."
    assert fired.answeredById == OWNER, "the answer changed hands on the way across"

    # Every recording moved, and none was orphaned.
    assert _ids(db.mediafile.rows) == ["m-d-1", "m-f-1", "m-f-2"]
    assert {row.questionnaireInterviewId for row in db.mediafile.rows} == {"iv-d"}

    # The surviving interview comes back hydrated, so the client can redraw from it.
    body = response.json()
    assert body["id"] == "iv-d"
    assert body["title"] == "D Black Pottery"
    assert sorted(node["id"] for node in body["media"]) == ["m-d-1", "m-f-1", "m-f-2"]
    assert sorted(node["id"] for node in body["responses"]) == ["r-d-clay", "r-f-fire"]


def test_an_identical_answer_on_both_sides_is_not_a_conflict(db: _Db) -> None:
    """"Identical text is not a conflict" — but the two rows still cannot both land, because
    `@@unique([interviewId, questionId])` allows one row per question per interview. The source's
    row is dropped, since it says nothing the survivor does not, and the merge proceeds."""
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
    """"An answer only one side has is not a conflict" — including when the survivor holds an EMPTY
    row for that question. The empty row is discarded and the real one travels whole, so the
    researcher who actually gave the answer stays named as its author."""
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


# --- 3. THE CONFLICTING-ANSWERS REFUSAL -----------------------------------------------------------


def test_two_different_answers_to_one_question_refuse_and_name_the_questions(db: _Db) -> None:
    """The single most important refusal here.

    Silently picking a winner would destroy a researcher's words under a 200 — the worst outcome
    available on this route. The refusal NAMES every question, by section code and prompt, because
    "3 answers disagree" is not something a researcher can act on and "Section D — Where is the clay
    dug from?" is.
    """
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
    """A refusal that had already moved the media, or already deleted the source, would be the same
    destruction the 409 exists to prevent — arriving under a status code that says it did not
    happen. Every read above the first write is what makes this assertable."""
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
    # And nobody's words were rewritten.
    clay = next(row for row in db.questionnaireresponse.rows if row.id == "r-d-clay")
    assert clay.answerText == "Dug from the riverbank below the village."
    # Not even the ledger, since the refusal happens above `guard_record_edit`.
    assert db.recordrevision.rows == []


def test_every_conflicting_question_is_named_not_merely_the_first(db: _Db) -> None:
    """"Name EVERY conflicting question." A refusal that stops at the first sends the researcher
    round the loop once per disagreement, and each trip is another 409 they cannot plan around."""
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


# --- 4. THE CROSS-INSTRUMENT REFUSAL --------------------------------------------------------------


def test_two_interviews_on_different_instruments_may_not_be_merged(db: _Db) -> None:
    """The 2nd and 3rd workshops' instruments run overlapping section codes and mean different
    things by them. Moving an answer across would file it under a question the sitting was never
    asked — and the unique index the fold exists to satisfy is scoped to one instrument anyway
    (`@@unique([questionnaireId, artisanSetKey])`, migration 20260913100000), so a cross-instrument
    collision is not a thing that can happen."""
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
    """Otherwise the row is deleted after its own rows are moved onto it."""
    response = _call("POST", "/questionnaire/interviews/iv-d/merge-into/iv-d")

    assert response.status_code == 422, response.text
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]


def test_an_unknown_interview_on_either_side_is_a_404(db: _Db) -> None:
    assert _call("POST", "/questionnaire/interviews/iv-nope/merge-into/iv-d").status_code == 404
    assert _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-nope").status_code == 404


# --- 5. THE GATE ----------------------------------------------------------------------------------


def test_the_merge_is_gated_exactly_as_an_edit_of_the_source_is(db: _Db) -> None:
    """NO NEW PRIVILEGE. The route reuses `record_edit_privilege` + `assert_can_contribute_relation`
    — the same pair, in the same order, that `update_interview` applies to `artisanIds`.

    Asserted as PARITY rather than as a bare 403: the same stranger is refused by the merge and by
    an ordinary edit of the same interview's populated relation, with the same message. A merge that
    had invented its own gate could still answer 403 here and be wrong about who it lets through.
    """
    _CURRENT["user"] = _user(STRANGER)

    merge = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")
    edit = _call("PATCH", "/questionnaire/interviews/iv-f", {"artisanIds": ["a1"]})

    assert merge.status_code == 403, merge.text
    assert edit.status_code == 403, edit.text
    # ONE HELPER PRODUCED BOTH SENTENCES, which is the parity. They differ in exactly the relation
    # each names — the merge is about the answers it would move, the edit was about the artisan
    # links it would rewrite — and in nothing else, because both come from
    # `deps.assert_can_contribute_relation` rather than from a refusal this route wrote for itself.
    prefix = "Only the original contributor or an admin can change populated relation: "
    assert merge.json()["detail"] == prefix + "responses"
    assert edit.json()["detail"] == prefix + "artisanIds"
    # Refused above the first write, as every refusal on this route is.
    assert _ids(db.questionnaireinterview.rows) == ["iv-d", "iv-f"]
    assert db.recordrevision.rows == []


def test_the_source_interviews_own_author_may_fold_it(db: _Db) -> None:
    """The other half of "no new privilege": the gate did not become stricter either. The owner is
    an ordinary RESEARCHER — not an admin, not a professor — and folding their own sitting is
    exactly the edit they may already make."""
    _CURRENT["user"] = _user(OWNER)

    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text
    assert _ids(db.questionnaireinterview.rows) == ["iv-d"]


def test_the_fold_leaves_a_ledger_entry_naming_where_the_interview_went(db: _Db) -> None:
    """No silent deletion. The row is about to stop existing, so the revision naming the move is the
    only trace of it that outlives the merge."""
    _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    entries = db.recordrevision.rows
    assert len(entries) == 1, entries
    assert entries[0].recordId == "iv-f"
    assert entries[0].recordType == "questionnaire"
    assert entries[0].editedById == OWNER


# --- 6. MEDIA COMES BACK WITH ITS URLs ------------------------------------------------------------


def test_a_saved_interview_comes_back_with_playable_recordings(db: _Db) -> None:
    """Every questionnaire route encoded with `public_encode(interview)` and NO VIEWER, and no
    viewer means "withhold every URL" — so `url`, `publicUrl` and `objectKey` were stripped from
    every media node and a client could not draw the recordings it had just been handed. The viewer
    is passed the way `routes/artisans.py` passes it."""
    response = _call("GET", "/questionnaire/interviews/iv-d")

    assert response.status_code == 200, response.text
    clip = next(node for node in response.json()["media"] if node["id"] == "m-d-1")
    assert clip["url"] == "https://cdn.example.test/uploads/m-d-1.m4a"


def test_the_surviving_interview_from_a_merge_is_playable_too(db: _Db) -> None:
    """The point of the move is that the recordings end up on this row. An answer that listed them
    without a URL is a screen the researcher cannot tell apart from the merge having lost them."""
    response = _call("POST", "/questionnaire/interviews/iv-f/merge-into/iv-d")

    assert response.status_code == 200, response.text
    nodes = response.json()["media"]
    # WHICH nodes, before what is on them. Asserting only "every node has a url" passes over a
    # survivor carrying just its own single clip — which is exactly what a broken media repoint
    # produces, so the loop alone goes green through the bug it is named after.
    assert sorted(node["id"] for node in nodes) == ["m-d-1", "m-f-1", "m-f-2"]
    for node in nodes:
        assert node["url"], node


def test_a_stranger_still_does_not_get_another_uploaders_urls(db: _Db) -> None:
    """Passing the viewer is not the same as passing ALL_MEDIA_URLS. The encoder's own derivation
    still applies — a plain researcher gets their OWN uploads' URLs and nobody else's — so opening
    these routes to the viewer did not turn the interview list into an index of download links."""
    _CURRENT["user"] = _user(STRANGER)

    response = _call("GET", "/questionnaire/interviews/iv-d")

    assert response.status_code == 200, response.text
    clip = next(node for node in response.json()["media"] if node["id"] == "m-d-1")
    assert "url" not in clip, clip
    assert "objectKey" not in clip, clip
