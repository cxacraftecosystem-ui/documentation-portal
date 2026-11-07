from __future__ import annotations

import contextlib
import os
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

pytestmark = [
    pytest.mark.anyio,
    pytest.mark.skipif(
        os.environ.get("W4_APPLY_DB_TESTS") != "1",
        reason="needs W4_APPLY_DB_TESTS=1 and a throwaway DATABASE_URL",
    ),
]

W3_ASSIGNMENT_LEVELS = ["EDIT"] * 2 + ["CONTRIBUTE"] * 24


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _cleanup(db: Any, w4: Any, made: dict[str, list[str]]) -> None:
    workshops = await db.workshop.find_many(where={"title": w4.WORKSHOP_TITLE})
    ids = [w.id for w in workshops]
    steps = (
        lambda: db.artisan.delete_many(where={"workshopId": {"in": ids}}),
        lambda: db.workshop.delete_many(where={"id": {"in": ids}}),
        lambda: db.craft.delete_many(where={"name": {"in": [w4.MEGHALAYA, w4.MYSURU]}}),
        lambda: db.questionnairequestion.delete_many(where={"questionnaireId": w4.W4_ID}),
        lambda: db.questionnairesection.delete_many(where={"questionnaireId": w4.W4_ID}),
        lambda: db.questionnaire.delete_many(where={"id": w4.W4_ID}),
        lambda: db.workshop.delete_many(where={"id": {"in": made["workshops"]}}),
        lambda: db.accessroster.delete_many(where={"email": {"in": list(w4.APPROVE_EMAILS)}}),
        lambda: db.user.delete_many(where={"id": {"in": made["users"]}}),
    )
    for step in steps:
        with contextlib.suppress(Exception):
            await step()


@pytest.fixture
async def world():
    from app.core.db import db
    from scripts import w4_apply as w4

    url = os.environ.get("DATABASE_URL", "")
    assert "supabase" not in url and "pooler" not in url, "refusing to run against a hosted database"
    if not db.is_connected():
        await db.connect(timeout=timedelta(seconds=180))
    made: dict[str, list[str]] = {"users": [], "workshops": []}
    try:
        if await db.user.find_unique(where={"id": w4.MASTER_ADMIN_ID}) is None:
            await db.user.create(
                data={
                    "id": w4.MASTER_ADMIN_ID,
                    "email": "w4-master-admin@example.invalid",
                    "name": "Ankit Kumar",
                    "role": "MASTER_ADMIN",
                }
            )
            made["users"].append(w4.MASTER_ADMIN_ID)
        location = await db.location.create(
            data={
                "latitude": 22.3137632,
                "longitude": 87.3139709,
                "accuracy": 94.0,
                "state": "West Bengal",
                "district": "Paschim Medinipur",
                "village": "Kharagpur",
                "pincode": "721302",
                "subjectLatitude": 22.3218727,
                "subjectLongitude": 87.3093738,
            }
        )
        await db.workshop.create(
            data={
                "id": w4.W3_WORKSHOP_ID,
                "title": "Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition",
                "workshopType": "OTHER",
                "status": "APPROVED",
                "date": datetime(2026, 9, 14, tzinfo=UTC),
                "startDate": datetime(2026, 9, 14, tzinfo=UTC),
                "endDate": datetime(2026, 9, 23, 23, 59, 59, 999000, tzinfo=UTC),
                "place": "Rajendra Mishra School of Engineering & Entrepreneurship",
                "locationId": location.id,
                "createdById": w4.MASTER_ADMIN_ID,
            }
        )
        made["workshops"].append(w4.W3_WORKSHOP_ID)
        for index, level in enumerate(W3_ASSIGNMENT_LEVELS + ["CONTRIBUTE"]):
            user = await db.user.create(
                data={"email": f"w4-roster-{index}@example.invalid", "name": f"Roster {index}"}
            )
            made["users"].append(user.id)
            await db.workshopassignment.create(
                data={
                    "workshopId": w4.W3_WORKSHOP_ID,
                    "userId": user.id,
                    "accessLevel": level,
                    "status": "GRANTED" if index < 26 else "REVOKED",
                }
            )
        contributor = await db.user.create(
            data={"email": w4.CONTRIBUTOR_EMAIL, "name": "Subhadeep", "role": "RESEARCHER"}
        )
        made["users"].append(contributor.id)
        for email in w4.APPROVE_EMAILS:
            await db.accessroster.create(
                data={"email": email, "status": "PENDING", "grantedRole": "CROWDSOURCE_VOLUNTEER"}
            )
        yield db, w4, made
    finally:
        await _cleanup(db, w4, made)
        await db.disconnect()


def _outcomes(report: Any, step: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for s, outcome, _, _ in report.rows:
        if s == step:
            out[outcome] = out.get(outcome, 0) + 1
    return out


async def test_dry_run_plans_everything_and_writes_nothing_then_apply_is_idempotent(world):
    db, w4, made = world
    from app.services.questionnaire_instruments import W4_ID

    before = await w4.table_counts()
    report, proof = await w4.dry_run()

    assert proof["readOnly"] and proof["countsUnchanged"] and proof["writeRefused"]
    assert await w4.table_counts() == before
    assert report.conflicts == 0
    assert _outcomes(report, "questionnaire") == {w4.PLANNED: 1}
    assert _outcomes(report, "crafts") == {w4.PLANNED: 2}
    assert _outcomes(report, "workshop") == {w4.PLANNED: 1}
    assert _outcomes(report, "binding") == {w4.WOULD_UPDATE: 1}
    assert _outcomes(report, "assignments") == {w4.PLANNED: 27}
    assert _outcomes(report, "artisans") == {w4.PLANNED: 13}
    assert _outcomes(report, "roster") == {w4.WOULD_UPDATE: 2}
    assert _outcomes(report, "after-signin") == {w4.DEFERRED: 2}

    applied = await w4.apply_run()

    assert applied.conflicts == 0
    assert _outcomes(applied, "questionnaire") == {w4.CREATED: 1}
    assert _outcomes(applied, "crafts") == {w4.CREATED: 2}
    assert _outcomes(applied, "workshop") == {w4.CREATED: 1}
    assert _outcomes(applied, "binding") == {w4.UPDATED: 1}
    assert _outcomes(applied, "assignments") == {w4.CREATED: 27}
    assert _outcomes(applied, "artisans") == {w4.CREATED: 13}
    assert _outcomes(applied, "roster") == {w4.UPDATED: 2}
    assert _outcomes(applied, "after-signin") == {w4.DEFERRED: 2}

    (workshop,) = await db.workshop.find_many(where={"title": w4.WORKSHOP_TITLE})
    made["workshops"].append(workshop.id)
    assert workshop.questionnaireId == W4_ID
    assert getattr(workshop.status, "value", workshop.status) == "APPROVED"
    assert workshop.startDate == w4.WORKSHOP_START
    assert workshop.endDate == w4.WORKSHOP_END
    assert workshop.createdById == w4.MASTER_ADMIN_ID
    assert workshop.description == w4.DESCRIPTION
    location = await db.location.find_unique(where={"id": workshop.locationId})
    assert (location.state, location.district, location.pincode) == ("West Bengal", "Paschim Medinipur", "721302")
    binding = await db.questionnairebinding.find_first(
        where={"workshopId": workshop.id, "isPrimary": True}
    )
    assert binding is not None and binding.questionnaireId == W4_ID

    questionnaire = await db.questionnaire.find_unique(where={"id": W4_ID})
    assert questionnaire.createdById == w4.MASTER_ADMIN_ID
    assert not questionnaire.isDefault
    questions = await db.questionnairequestion.find_many(where={"questionnaireId": W4_ID})
    assert len(questions) == 81
    assert all((q.questionCode or "").startswith("CTW3.") for q in questions)
    assert await db.questionnairesection.count(where={"questionnaireId": W4_ID}) == 22

    crafts = await db.craft.find_many(where={"name": {"in": [w4.MEGHALAYA, w4.MYSURU]}})
    assert {c.name: (c.stateOfOrigin, c.stateOfPractice, getattr(c.giStatus, "value", c.giStatus)) for c in crafts} == {
        w4.MEGHALAYA: (["Meghalaya"], ["Meghalaya"], "NON_GI"),
        w4.MYSURU: (["Karnataka"], ["Karnataka"], "NON_GI"),
    }

    assignments = await db.workshopassignment.find_many(where={"workshopId": workshop.id})
    assert len(assignments) == 27
    assert all(a.status == "GRANTED" for a in assignments)
    assert sorted(a.accessLevel for a in assignments) == sorted(W3_ASSIGNMENT_LEVELS + ["CONTRIBUTE"])

    artisans = await db.artisan.find_many(where={"workshopId": workshop.id})
    assert len(artisans) == 13
    by_name = {a.name: a for a in artisans}
    assert set(by_name) == {spec.name for spec in w4.ARTISANS}
    for artisan in artisans:
        assert getattr(artisan.status, "value", artisan.status) == "APPROVED"
        assert artisan.createdById == w4.MASTER_ADMIN_ID
        assert artisan.extraMetadata["fieldProvenance"]["name"]["by"] == w4.MASTER_ADMIN_ID
        assert artisan.dos == artisan.donts == w4.DOS_DONTS
    sealed = {a.name for a in artisans if a.aadhaarFingerprint}
    assert sealed == {"Jamesdonald Kharsyntiew", "Mathivanan", "Sandesh P"}
    assert all(a.aadhaarNotShared for a in artisans if a.name not in sealed)
    assert by_name["Draupadi Gedi"].phone == "7751838284"
    assert by_name["Draupadi Gedi"].phoneCountryCode == "+91"
    assert "Second phone: +91 9090746226" in by_name["Draupadi Gedi"].notes.splitlines()
    assert by_name["Jamesdonald Kharsyntiew"].pehchanCardNumber == "NERMGH543884"
    assert by_name["Jamesdonald Kharsyntiew"].dateOfBirth is None
    assert "Age: 29" in by_name["Jamesdonald Kharsyntiew"].notes.splitlines()
    assert by_name["Khargeswar"].craftId == w4.STONE_OTHERS
    assert by_name["Sandesh P"].craftId == next(c.id for c in crafts if c.name == w4.MYSURU)
    assert await db.workshopartisan.count(where={"workshopId": workshop.id}) == 13

    for email in w4.APPROVE_EMAILS:
        row = await db.accessroster.find_unique(where={"email": email})
        assert getattr(row.status, "value", row.status) == "ACTIVE"
        assert getattr(row.grantedRole, "value", row.grantedRole) == "RESEARCHER"

    settled = await w4.table_counts()
    again, again_proof = await w4.dry_run()
    assert again_proof["readOnly"] and again_proof["countsUnchanged"] and again_proof["writeRefused"]
    assert again.pending_writes == 0 and again.conflicts == 0
    assert _outcomes(again, "artisans") == {w4.EXISTS: 13}
    assert _outcomes(again, "assignments") == {w4.EXISTS: 27}

    rerun = await w4.apply_run()
    assert rerun.count(outcome=w4.CREATED) == rerun.count(outcome=w4.UPDATED) == 0
    assert await w4.table_counts() == settled

    newcomer = await db.user.create(
        data={"email": w4.APPROVE_EMAILS[0], "name": "Durba", "role": "RESEARCHER"}
    )
    made["users"].append(newcomer.id)
    later = await w4.apply_run()
    assert _outcomes(later, "after-signin") == {w4.CREATED: 1, w4.DEFERRED: 1}
    grant = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop.id, "userId": newcomer.id}}
    )
    assert grant.accessLevel == "CONTRIBUTE" and grant.status == "GRANTED"
    final, _ = await w4.dry_run()
    assert final.pending_writes == 0
