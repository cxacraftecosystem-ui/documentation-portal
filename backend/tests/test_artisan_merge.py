"""Merging duplicate artisans (C24): the preview, the field pick, every child re-pointed, the undo.

These drive the REAL router over HTTP with ``db`` replaced by the shared in-memory fake, so what is
asserted is what the handlers did to the rows. Nothing touches a database.

THE FAKE CARRIES THE UNIQUE INDEXES THE BEHAVIOUR RESTS ON: Aadhaar and Pehchan on the artisan (the
survivor taking the duplicate's number only works because the duplicate's copy is freed first), the
composite keys of the four link tables (folding), and ``(questionnaireId, artisanSetKey)`` on the
interview (the collision a merge must refuse).
"""

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeTable, install
from app.api.router import api_router
from app.core import deps
from app.services import artisan_merge
from app.services.artisan_merge import (
    find_candidates,
    redirect_merged_artisans,
    resolve_picks,
    scrub_expired_merge_snapshots,
    unjson,
)

ADMIN = "u-admin"
RESEARCHER = "u-researcher"
QNR = "qnr-2"


def _unwrap_set(value: Any) -> Any:
    """``{"set": [...]}`` is how a scalar-list column is written on an update; Postgres stores the
    list. Local to this module: the shared fake records the raw shape, and other suites assert it."""
    if isinstance(value, dict) and set(value) == {"set"}:
        return value["set"]
    return value


class _Table(FakeTable):
    async def update(self, where: dict[str, Any], data: dict[str, Any], **kwargs: Any) -> Any:
        return await super().update(where, {k: _unwrap_set(v) for k, v in data.items()}, **kwargs)

    async def update_many(self, where: dict[str, Any], data: dict[str, Any], **kwargs: Any) -> int:
        return await super().update_many(where, {k: _unwrap_set(v) for k, v in data.items()}, **kwargs)

    async def create_many(self, data: list[dict[str, Any]], **kwargs: Any) -> int:
        """Row by row, so the fake's unique indexes still refuse a clash as Postgres would."""
        for row in data:
            await self.create(data=row)
        return len(data)


class _Db:
    UNIQUE_KEYS: dict[str, tuple[tuple[str, ...], ...]] = {
        "artisan": (("aadhaarNumber",), ("aadhaarFingerprint",), ("pehchanCardNumber",)),
        "questionnaireinterview": (("questionnaireId", "artisanSetKey"),),
        "workshopartisan": (("workshopId", "artisanId"),),
        "toolartisan": (("toolId", "artisanId"),),
        "questionnaireinterviewartisan": (("interviewId", "artisanId"),),
        "questionnairesectionstatus": (("artisanId", "sectionId"),),
        "dataaccessscopeitem": (("grantId", "recordType", "recordId"),),
    }

    def __init__(self, **seeds: list[dict[str, Any]]) -> None:
        object.__setattr__(self, "_tables", {})
        for name, rows in seeds.items():
            self._tables[name] = _Table({}, rows, self.UNIQUE_KEYS.get(name, ()))

    def __getattr__(self, name: str) -> Any:
        tables = object.__getattribute__(self, "_tables")
        if name not in tables:
            tables[name] = _Table({}, [], type(self).UNIQUE_KEYS.get(name, ()))
        return tables[name]


def _user(user_id: str, role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name=user_id,
        role=role,
        canManageCrafts=False,
        canManageWorkshops=False,
        canManageQuestionnaire=False,
        canDownloadDataset=False,
        canReview=False,
        canViewProvenance=False,
    )


_CURRENT: dict[str, Any] = {"user": None}
_APP = FastAPI()
_APP.include_router(api_router)
_APP.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]


def _call(method: str, path: str, body: dict[str, Any] | None = None, **params: Any) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://merge.test") as client:
            return await client.request(method, f"/api{path}", json=body, params=params or None)

    return asyncio.run(run())


AADHAAR = "234123412346"


def _artisan(artisan_id: str, name: str, **extra: Any) -> dict[str, Any]:
    row = {
        "id": artisan_id,
        "name": name,
        "localName": None,
        "gender": None,
        "phone": None,
        "email": None,
        "place": "Bagru",
        "aadhaarNumber": None,
        "pehchanCardAvailable": True,
        "pehchanCardNumber": None,
        "address": None,
        "notes": None,
        "dateOfBirth": None,
        "craftStartDate": None,
        "experienceYears": None,
        "experienceMonths": None,
        "dos": None,
        "donts": None,
        "status": "APPROVED",
        "extraMetadata": None,
        "craftId": None,
        "locationId": None,
        "workshopId": None,
        "createdById": RESEARCHER,
        "createdAt": datetime(2026, 6, 17, tzinfo=UTC),
    }
    row.update(extra)
    return row


@pytest.fixture()
def db(monkeypatch: pytest.MonkeyPatch):
    artisan_merge.forget_redirects()
    fake = _Db(
        user=[
            {"id": ADMIN, "name": "Admin", "role": "ADMIN"},
            {"id": RESEARCHER, "name": "Researcher", "role": "RESEARCHER"},
        ],
        artisan=[
            _artisan("keep", "Ramesh Kumar", phone="98290 11111", notes="Block printer."),
            _artisan(
                "dup",
                "Ramesh  kumar",
                aadhaarNumber=AADHAAR,
                dateOfBirth=datetime(1971, 3, 4, tzinfo=UTC),
                notes="Uses indigo.",
                createdAt=datetime(2026, 7, 1, tzinfo=UTC),
            ),
            _artisan("other", "Sita Devi"),
        ],
        workshop=[{"id": "w1", "title": "2nd Toolkit Workshop"}, {"id": "w2", "title": "3rd"}],
        workshopartisan=[
            {"workshopId": "w1", "artisanId": "keep", "role": None},
            {"workshopId": "w1", "artisanId": "dup", "role": "lead"},
            {"workshopId": "w2", "artisanId": "dup", "role": None},
        ],
        productdocumentation=[
            {"id": "p1", "artisanId": "dup", "artisanName": "Ramesh  kumar"},
            {"id": "p2", "artisanId": "dup", "artisanName": "R. K. (typed)"},
            {"id": "p3", "artisanId": "keep", "artisanName": "Ramesh Kumar"},
        ],
        tooldocumentation=[{"id": "t1", "artisanId": "dup", "artisanName": "Ramesh  kumar"}],
        toolartisan=[{"id": "ta1", "toolId": "t1", "artisanId": "dup"}],
        mediafile=[
            {"id": "m1", "artisanId": "dup", "linkedRecordType": None, "linkedRecordId": None},
            {"id": "m2", "artisanId": None, "linkedRecordType": "artisan", "linkedRecordId": "dup"},
        ],
        questionnaireinterview=[
            {"id": "iv1", "title": "Sitting one", "questionnaireId": QNR, "artisanSetKey": "dup,other"},
        ],
        questionnaireinterviewartisan=[
            {"interviewId": "iv1", "artisanId": "dup"},
            {"interviewId": "iv1", "artisanId": "other"},
        ],
        questionnairesectionstatus=[
            {"id": "ss1", "artisanId": "dup", "sectionId": "A", "status": "COMPLETED", "setById": ADMIN},
        ],
        entrycomment=[{"id": "c1", "recordType": "artisan", "recordId": "dup", "body": "Check the DOB"}],
        recordrevision=[{"id": "rv1", "recordType": "artisan", "recordId": "dup", "changes": {}}],
        reviewlog=[{"id": "rl1", "recordType": "ARTISAN", "recordId": "dup", "status": "APPROVED"}],
        assignedtask=[
            {"id": "task1", "artisanIds": ["dup", "keep"], "recordType": None, "recordId": None},
        ],
    )
    install(monkeypatch, fake)
    _CURRENT["user"] = _user(ADMIN, "ADMIN")
    yield fake
    artisan_merge.forget_redirects()


def _row(fake: _Db, table: str, row_id: str) -> Any:
    return next((row for row in getattr(fake, table).rows if getattr(row, "id", None) == row_id), None)


# --- who may ------------------------------------------------------------------------------------


def test_only_admins_reach_the_tool(db: _Db) -> None:
    _CURRENT["user"] = _user(RESEARCHER, "RESEARCHER")
    assert _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="dup").status_code == 403
    assert _call("POST", "/artisans/merge", {"keepId": "keep", "mergeId": "dup"}).status_code == 403
    assert _call("GET", "/artisans/merge/history").status_code == 403
    assert _call("GET", "/artisans/merge/candidates").status_code == 403
    assert _call("POST", "/artisans/merge/x/undo").status_code == 403
    assert _row(db, "artisan", "dup") is not None


def test_merging_an_artisan_into_itself_is_refused(db: _Db) -> None:
    response = _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="keep")
    assert response.status_code == 422


# --- the preview ----------------------------------------------------------------------------------


def test_the_preview_lays_both_records_side_by_side_and_counts_the_moves(db: _Db) -> None:
    response = _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="dup")
    assert response.status_code == 200, response.text
    body = response.json()
    fields = {field["key"]: field for field in body["fields"]}
    # Aadhaar only on the duplicate: offered, defaulting to the record that has it, and MASKED.
    assert fields["aadhaarNumber"]["keep"] is None
    assert fields["aadhaarNumber"]["merge"] == "XXXX XXXX 2346"
    assert fields["aadhaarNumber"]["default"] == "merge"
    # Both have notes: the kept one's by default, and "both" is offered.
    assert fields["notes"]["default"] == "keep" and fields["notes"]["allowBoth"] is True
    assert fields["dateOfBirth"]["merge"] == "1971-03-04"
    assert "gender" not in fields  # empty on both sides: nothing to choose
    moves = {move["key"]: move for move in body["moves"]}
    assert moves["products"]["count"] == 2
    assert moves["workshopArtisan"]["count"] == 2 and moves["workshopArtisan"]["folded"] == 1
    assert moves["mediaTagged"]["count"] == 1
    assert body["canMerge"] is True and body["blockers"] == []
    assert body["undoDays"] == 30
    # A read: nothing changed.
    assert _row(db, "artisan", "dup") is not None
    assert not db.artisanmerge.rows


def test_resolve_picks_refuses_an_unknown_field_and_a_both_on_a_short_field() -> None:
    keep, merge = {"name": "A"}, {"name": "B"}
    with pytest.raises(Exception):
        resolve_picks(keep, merge, {"nonsense": "keep"})
    with pytest.raises(Exception):
        resolve_picks(keep, merge, {"name": "both"})
    chosen, changes = resolve_picks(keep, merge, {"name": "merge"})
    assert chosen["name"] == "merge" and changes == {"name": "B"}


# --- the merge ----------------------------------------------------------------------------------


def _merge(picks: dict[str, str] | None = None) -> httpx.Response:
    return _call("POST", "/artisans/merge", {"keepId": "keep", "mergeId": "dup", "picks": picks or {}})


def test_a_merge_takes_the_picked_fields_and_moves_every_child(db: _Db) -> None:
    response = _merge({"notes": "both"})
    assert response.status_code == 200, response.text
    summary = response.json()
    assert summary["status"] == "DONE" and summary["canUndo"] is True

    keep = _row(db, "artisan", "keep")
    assert _row(db, "artisan", "dup") is None
    # The duplicate's Aadhaar moved to the survivor — possible only because its copy was freed first.
    assert keep.aadhaarNumber == AADHAAR
    assert keep.dateOfBirth == datetime(1971, 3, 4, tzinfo=UTC)
    assert keep.name == "Ramesh Kumar"  # the kept spelling, by default
    assert keep.notes == "Block printer.\nUses indigo."
    assert keep.extraMetadata.data["mergedArtisans"][0]["artisanId"] == "dup"

    assert {row.artisanId for row in db.productdocumentation.rows} == {"keep"}
    # A denormalised name that spelled the duplicate follows; one somebody typed differently stays.
    assert _row(db, "productdocumentation", "p1").artisanName == "Ramesh Kumar"
    assert _row(db, "productdocumentation", "p2").artisanName == "R. K. (typed)"
    assert _row(db, "tooldocumentation", "t1").artisanId == "keep"
    assert [row.artisanId for row in db.toolartisan.rows] == ["keep"]
    assert _row(db, "mediafile", "m1").artisanId == "keep"
    assert _row(db, "mediafile", "m2").linkedRecordId == "keep"
    # The roster: w1 held both, so it FOLDS to one row; w2 moves.
    roster = sorted((row.workshopId, row.artisanId) for row in db.workshopartisan.rows)
    assert roster == [("w1", "keep"), ("w2", "keep")]
    # The sitting now covers the survivor, under a recomputed set key.
    assert {row.artisanId for row in db.questionnaireinterviewartisan.rows} == {"keep", "other"}
    assert _row(db, "questionnaireinterview", "iv1").artisanSetKey == "keep,other"
    assert _row(db, "questionnairesectionstatus", "ss1").artisanId == "keep"
    assert _row(db, "entrycomment", "c1").recordId == "keep"
    assert _row(db, "recordrevision", "rv1").recordId == "keep"
    assert _row(db, "reviewlog", "rl1").recordId == "keep"
    assert _row(db, "assignedtask", "task1").artisanIds == ["keep"]
    # The audit row.
    merge = db.artisanmerge.rows[0]
    assert merge.status == "DONE" and merge.mergedById == ADMIN
    assert unjson(merge.mergedSnapshot)["aadhaarNumber"] == AADHAAR
    # The survivor's own ledger names the fields that changed.
    ledger = [row for row in db.recordrevision.rows if row.recordId == "keep" and row.id != "rv1"]
    assert set(unjson(ledger[0].changes)) >= {"aadhaarNumber", "dateOfBirth", "notes"}


def test_a_merge_that_would_make_two_interviews_one_is_refused_before_anything_moves(db: _Db) -> None:
    db.questionnaireinterview.rows.append(
        SimpleNamespace(id="iv2", title="Sitting two", questionnaireId=QNR, artisanSetKey="keep,other")
    )
    db.questionnaireinterviewartisan.rows.extend(
        [SimpleNamespace(interviewId="iv2", artisanId="keep"), SimpleNamespace(interviewId="iv2", artisanId="other")]
    )
    preview = _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="dup").json()
    assert preview["canMerge"] is False
    assert preview["blockers"][0]["code"] == "interview_set_collision"
    response = _merge()
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "merge_blocked"
    assert _row(db, "artisan", "dup") is not None and not db.artisanmerge.rows


def test_an_unfinished_merge_must_be_undone_before_another(db: _Db) -> None:
    db.artisanmerge.rows.append(
        SimpleNamespace(id="old", status="IN_PROGRESS", survivorId="keep", mergedId="zzz", createdAt=datetime.now(UTC))
    )
    assert _merge().status_code == 409


# --- the undo -----------------------------------------------------------------------------------


def test_undo_puts_everything_back(db: _Db) -> None:
    merge_id = _merge({"notes": "both", "name": "merge"}).json()["id"]
    response = _call("POST", f"/artisans/merge/{merge_id}/undo")
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "UNDONE" and response.json()["kept"] == []

    dup = _row(db, "artisan", "dup")
    keep = _row(db, "artisan", "keep")
    assert dup is not None and dup.aadhaarNumber == AADHAAR and dup.name == "Ramesh  kumar"
    assert dup.dateOfBirth == datetime(1971, 3, 4, tzinfo=UTC)
    assert keep.aadhaarNumber is None and keep.dateOfBirth is None
    assert keep.notes == "Block printer." and keep.name == "Ramesh Kumar"
    assert "mergedArtisans" not in (unjson(keep.extraMetadata) or {})

    assert _row(db, "productdocumentation", "p1").artisanId == "dup"
    assert _row(db, "productdocumentation", "p1").artisanName == "Ramesh  kumar"
    assert _row(db, "productdocumentation", "p3").artisanId == "keep"
    assert _row(db, "mediafile", "m2").linkedRecordId == "dup"
    roster = sorted((row.workshopId, row.artisanId) for row in db.workshopartisan.rows)
    assert roster == [("w1", "dup"), ("w1", "keep"), ("w2", "dup")]
    folded_back = next(row for row in db.workshopartisan.rows if row.workshopId == "w1" and row.artisanId == "dup")
    assert folded_back.role == "lead"
    assert _row(db, "questionnaireinterview", "iv1").artisanSetKey == "dup,other"
    assert _row(db, "assignedtask", "task1").artisanIds == ["dup", "keep"]
    assert _row(db, "entrycomment", "c1").recordId == "dup"
    assert db.artisanmerge.rows[0].status == "UNDONE"
    # Twice is refused.
    assert _call("POST", f"/artisans/merge/{merge_id}/undo").status_code == 409


def test_undo_leaves_an_edit_made_after_the_merge_and_says_so(db: _Db) -> None:
    merge_id = _merge().json()["id"]
    _row(db, "artisan", "keep").dateOfBirth = datetime(1972, 1, 1, tzinfo=UTC)
    body = _call("POST", f"/artisans/merge/{merge_id}/undo").json()
    assert "Date of birth" in body["kept"]
    assert _row(db, "artisan", "keep").dateOfBirth == datetime(1972, 1, 1, tzinfo=UTC)


def test_undo_closes_after_thirty_days(db: _Db) -> None:
    merge_id = _merge().json()["id"]
    db.artisanmerge.rows[0].createdAt = datetime.now(UTC) - timedelta(days=31)
    response = _call("POST", f"/artisans/merge/{merge_id}/undo")
    assert response.status_code == 409
    history = _call("GET", "/artisans/merge/history").json()
    assert history["items"][0]["canUndo"] is False
    assert history["items"][0]["mergedByName"] == "Admin"


def test_snapshots_past_the_window_stop_holding_the_raw_number(db: _Db) -> None:
    _merge()
    asyncio.run(scrub_expired_merge_snapshots(datetime.now(UTC) + timedelta(days=31)))
    snap = unjson(db.artisanmerge.rows[0].mergedSnapshot)
    assert snap["aadhaarNumber"] == "XXXX XXXX 2346"


# --- the redirect -------------------------------------------------------------------------------


def test_a_queued_write_naming_the_merged_artisan_lands_on_the_survivor(db: _Db) -> None:
    _merge()
    payload = SimpleNamespace(artisanId="dup", artisanIds=["dup", "keep", "other"], linkedRecordType="Artisan", linkedRecordId="dup")
    asyncio.run(redirect_merged_artisans(payload))
    assert payload.artisanId == "keep"
    assert payload.artisanIds == ["keep", "other"]
    assert payload.linkedRecordId == "keep"
    # An old link opens the survivor.
    response = _call("GET", "/artisans/dup")
    assert response.status_code == 200 and response.json()["id"] == "keep"


def test_no_redirect_once_undone(db: _Db) -> None:
    merge_id = _merge().json()["id"]
    _call("POST", f"/artisans/merge/{merge_id}/undo")
    payload = SimpleNamespace(artisanId="dup")
    asyncio.run(redirect_merged_artisans(payload))
    assert payload.artisanId == "dup"


# --- likely duplicates --------------------------------------------------------------------------


def test_candidates_pair_the_same_phone_or_the_same_name_in_the_same_place() -> None:
    def row(row_id: str, name: str, place: str, phone: str | None = None, day: int = 1) -> SimpleNamespace:
        return SimpleNamespace(id=row_id, name=name, place=place, phone=phone, createdAt=datetime(2026, 6, day, tzinfo=UTC))

    pairs, truncated = find_candidates(
        [
            row("b", "Ramesh  KUMAR.", "bagru", day=2),
            row("a", "Ramesh Kumar", "Bagru", day=1),
            row("c", "Ramesh Kumar", "Sanganer"),
            row("d", "Sita", "Jaipur", phone="+91 98290 22222"),
            row("e", "Sita Devi", "Jaipur", phone="9829022222", day=3),
        ]
    )
    found = {(pair["keepId"], pair["mergeId"], pair["reason"]) for pair in pairs}
    assert ("a", "b", "Same name and place") in found  # older record first
    assert ("d", "e", "Same phone number") in found
    assert not any("c" in (keep, merge) for keep, merge, _ in found)
    assert truncated is False


def test_the_candidates_route_answers(db: _Db) -> None:
    body = _call("GET", "/artisans/merge/candidates").json()
    assert body["items"][0]["keepId"] == "keep" and body["items"][0]["mergeId"] == "dup"


# --- queued replays through every route the phone queues -----------------------------------------


OTHER = "u-other"


def _merged_away_from_the_researcher(db: _Db) -> None:
    """Merge ``dup`` (the researcher's) into ``keep``, then make ``keep`` someone else's record."""
    assert _merge().status_code == 200
    _row(db, "artisan", "keep").createdById = OTHER


def test_a_queued_workshop_patch_naming_the_merged_artisan_keeps_the_roster(db: _Db) -> None:
    _merge()
    response = _call("PATCH", "/workshops/w1", {"artisanIds": ["dup", "other"]})
    assert response.status_code == 200, response.text
    roster = sorted(row.artisanId for row in db.workshopartisan.rows if row.workshopId == "w1")
    assert roster == ["keep", "other"]


def test_a_queued_workshop_create_naming_the_merged_artisan_links_the_survivor(db: _Db) -> None:
    _merge()
    response = _call("POST", "/workshops", {"title": "4th Workshop", "place": "Bagru", "artisanIds": ["dup"], "clientKey": "ck-1",
         "location": {"latitude": 26.81, "longitude": 75.54, "state": "Rajasthan", "district": "Jaipur"}})
    assert response.status_code == 201, response.text
    created = response.json()["id"]
    assert [row.artisanId for row in db.workshopartisan.rows if row.workshopId == created] == ["keep"]


def test_a_queued_tool_assignment_naming_the_merged_artisan_lands_on_the_survivor(
    db: _Db, monkeypatch: pytest.MonkeyPatch
) -> None:
    from app.api.routes import tools as tool_routes

    async def assigned(tool_id: str) -> list[dict[str, str]]:  # the fake carries no ``include``
        return [{"id": row.artisanId} for row in db.toolartisan.rows if row.toolId == tool_id]

    monkeypatch.setattr(tool_routes, "_assigned_artisans", assigned)
    _merged_away_from_the_researcher(db)
    db.tooldocumentation.rows.append(SimpleNamespace(id="t2", artisanId=None, artisanName=None, createdById=OTHER))
    _CURRENT["user"] = _user(RESEARCHER, "RESEARCHER")
    response = _call("POST", "/tools/t2/artisans", {"artisanIds": ["dup"]})
    assert response.status_code == 200, response.text
    assert response.json() == [{"id": "keep"}]


def test_a_queued_comment_on_the_merged_artisan_lands_on_the_survivor(
    db: _Db, monkeypatch: pytest.MonkeyPatch
) -> None:
    from app.api.routes import data_access

    # The route's owner table binds its delegates at import, before the fake is installed.
    monkeypatch.setitem(data_access.RECORD_DELEGATES, "artisan", (db.artisan, "createdById"))
    _merge()
    response = _call("POST", "/data-access/comments", {"recordType": "artisan", "recordId": "dup", "body": "Seen"})
    assert response.status_code == 201, response.text
    assert response.json()["recordId"] == "keep"


def test_a_queued_delete_of_the_merged_artisan_is_already_done(db: _Db) -> None:
    _merge()
    assert _call("DELETE", "/artisans/dup").status_code == 204
    assert _row(db, "artisan", "keep") is not None


def test_the_researcher_who_recorded_the_merged_artisan_may_still_edit_it(db: _Db) -> None:
    _merged_away_from_the_researcher(db)
    _CURRENT["user"] = _user(RESEARCHER, "RESEARCHER")
    response = _call("PATCH", "/artisans/dup", {"notes": "Moved to Sanganer."})
    assert response.status_code == 200, response.text
    assert _row(db, "artisan", "keep").notes == "Moved to Sanganer."


def test_anyone_else_gets_no_allowance_through_the_old_id(db: _Db) -> None:
    _merged_away_from_the_researcher(db)
    db.user.rows.append(SimpleNamespace(id="u-r2", name="Second", role="RESEARCHER"))
    _CURRENT["user"] = _user("u-r2", "RESEARCHER")
    assert _call("PATCH", "/artisans/dup", {"notes": "Not mine."}).status_code == 403
    # And the researcher gets none through the survivor's own id.
    _CURRENT["user"] = _user(RESEARCHER, "RESEARCHER")
    assert _call("PATCH", "/artisans/keep", {"notes": "Not mine."}).status_code == 403


# --- merging a survivor -------------------------------------------------------------------------


def test_merging_away_a_survivor_warns_and_the_old_undo_says_why_it_cannot(db: _Db) -> None:
    first = _merge().json()["id"]
    preview = _call("GET", "/artisans/merge/preview", keepId="other", mergeId="keep").json()
    assert any("can no longer be undone" in warning for warning in preview["warnings"])
    db.questionnaireinterview.rows.clear()
    db.questionnaireinterviewartisan.rows.clear()
    assert _call("POST", "/artisans/merge", {"keepId": "other", "mergeId": "keep", "picks": {}}).status_code == 200
    response = _call("POST", f"/artisans/merge/{first}/undo")
    assert response.status_code == 409
    assert "merged into Sita Devi" in response.json()["detail"]


# --- the Aadhaar fingerprint ----------------------------------------------------------------------

FINGERPRINT = "ab" * 32


def _fingerprint_the_duplicate(db: _Db) -> None:
    """The duplicate as the fingerprint release stores it: no full number, a fingerprint and the last four."""
    dup = _row(db, "artisan", "dup")
    dup.aadhaarNumber = None
    dup.aadhaarFingerprint = FINGERPRINT
    dup.aadhaarLast4 = "2346"
    keep = _row(db, "artisan", "keep")
    keep.aadhaarFingerprint = None
    keep.aadhaarLast4 = None


def test_the_preview_masks_a_fingerprinted_aadhaar(db: _Db) -> None:
    _fingerprint_the_duplicate(db)
    body = _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="dup").json()
    fields = {field["key"]: field for field in body["fields"]}
    assert fields["aadhaarNumber"]["merge"] == "XXXX XXXX 2346"
    assert fields["aadhaarNumber"]["default"] == "merge"
    assert FINGERPRINT not in str(body)


def test_two_different_fingerprints_warn(db: _Db) -> None:
    _fingerprint_the_duplicate(db)
    keep = _row(db, "artisan", "keep")
    keep.aadhaarFingerprint = "cd" * 32
    keep.aadhaarLast4 = "1111"
    body = _call("GET", "/artisans/merge/preview", keepId="keep", mergeId="dup").json()
    assert any("Aadhaar numbers differ" in warning for warning in body["warnings"])


def test_a_merge_moves_a_fingerprinted_aadhaar_whole_and_records_no_value(db: _Db) -> None:
    _fingerprint_the_duplicate(db)
    response = _merge()
    assert response.status_code == 200, response.text
    keep = _row(db, "artisan", "keep")
    # Possible only because the duplicate's unique fingerprint was freed first.
    assert keep.aadhaarFingerprint == FINGERPRINT and keep.aadhaarLast4 == "2346"
    ledger = [row for row in db.recordrevision.rows if row.recordId == "keep" and row.id != "rv1"]
    entry = unjson(ledger[0].changes)
    assert "aadhaarFingerprint" not in entry and "aadhaarLast4" not in entry
    assert entry["aadhaarNumber"]["redacted"] is True
    assert FINGERPRINT not in str(entry) and "2346" not in str(entry)


def test_undo_gives_the_fingerprint_back(db: _Db) -> None:
    _fingerprint_the_duplicate(db)
    merge_id = _merge().json()["id"]
    response = _call("POST", f"/artisans/merge/{merge_id}/undo")
    assert response.status_code == 200, response.text
    assert response.json()["kept"] == []
    dup = _row(db, "artisan", "dup")
    keep = _row(db, "artisan", "keep")
    assert dup.aadhaarFingerprint == FINGERPRINT and dup.aadhaarLast4 == "2346"
    assert keep.aadhaarFingerprint is None and keep.aadhaarLast4 is None
    undo_ledger = unjson(db.recordrevision.rows[-1].changes)
    assert undo_ledger["aadhaarNumber"]["redacted"] is True
