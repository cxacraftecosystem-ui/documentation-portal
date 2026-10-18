"""Merging two artisan records and undoing it, against a throwaway Postgres.

Opt-in (``ARTISAN_DUPLICATE_DB_TESTS=1``, see ``tests/_duplicates_db.py``). One fixture builds a
pair that touches every table in ``artisan_merge.MERGE_REPOINT``, including a collision on each
unique key, and the round-trip test asserts that a merge followed by its undo leaves every one of
those rows exactly as it was.
"""

from __future__ import annotations

import json
import secrets
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from prisma import Json

import _duplicates_db as dbt
from app.services import artisan_duplicates as dup
from app.services import artisan_merge as am

pytestmark = [pytest.mark.anyio, dbt.skip_without_db]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def conn():
    db = await dbt.connect()
    yield db
    await dbt.disconnect()


async def _pair(db, a_id: str, b_id: str) -> Any:
    first, second = dup.ordered(a_id, b_id)
    result = dup.Score(0.95, dup.BAND_VERY_LIKELY, ((dup.REASON_NAME, "Names 100% alike"),))
    pair_id = await dup.upsert_within(first, second, result, source="SCAN")
    return await db.artisanduplicatecandidate.find_unique(where={"id": pair_id})


def _letter(pair: Any, artisan_id: str) -> str:
    return "A" if pair.artisanAId == artisan_id else "B"


@pytest.fixture
async def world(conn):
    """Keep ``keep`` and fold ``gone`` into it. Every registry table holds rows for ``gone``."""
    db = conn
    admin = await dbt.make_user(db, "ADMIN")
    other = await dbt.make_user(db, "RESEARCHER")
    craft = await db.craft.create(data={"name": f"Craft {dbt.word()}", "createdById": admin.id})
    name = dbt.person()
    number = dbt.valid_aadhaar()
    keep = await dbt.make_artisan(
        db, admin, name=name, phone="9825012345", notes="Met at the fair", craftId=craft.id,
        extraMetadata=Json({"source": "form", "fieldProvenance": {"name": {"by": admin.id}}}),
    )
    gone = await dbt.make_artisan(
        db, other, name=name + "a", phone="9825012345", notes="Prefers mornings", aadhaar=number,
        pehchanCardNumber=dbt.pehchan(), dateOfBirth="1971-04-02", craftId=craft.id,
        extraMetadata=Json({"legacyAge": 52, "fieldProvenance": {"name": {"by": other.id}}}),
    )
    ids = SimpleNamespace(products=[], tools=[], media=[], workshops=[], interviews=[], sections=[], grants=[],
                          tasks=[], remotes=[])
    # Plain foreign keys.
    for artisan in (keep, gone):
        product = await db.productdocumentation.create(data={
            "craftName": craft.name, "place": "Bhuj", "artisanName": artisan.name, "productName": "Dupatta",
            "artisanId": artisan.id, "createdById": admin.id})
        ids.products.append(product.id)
    tool = await db.tooldocumentation.create(data={
        "craftName": craft.name, "place": "Bhuj", "artisanName": gone.name, "toolkitName": "Blocks",
        "artisanId": gone.id, "createdById": admin.id})
    ids.tools.append(tool.id)
    shared_tool = await db.tooldocumentation.create(data={
        "craftName": craft.name, "place": "Bhuj", "artisanName": keep.name, "toolkitName": "Dyes",
        "artisanId": keep.id, "createdById": admin.id})
    ids.tools.append(shared_tool.id)
    # ToolArtisan: one only for gone, one for both (collision).
    await db.toolartisan.create(data={"toolId": tool.id, "artisanId": gone.id})
    await db.toolartisan.create(data={"toolId": shared_tool.id, "artisanId": gone.id})
    await db.toolartisan.create(data={"toolId": shared_tool.id, "artisanId": keep.id})
    for linked in (False, True):
        media = await db.mediafile.create(data={
            "originalFilename": "a.jpg", "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 10,
            "bucket": "b", "objectKey": f"media/{secrets.token_hex(8)}.jpg", "uploadedById": admin.id,
            "artisanId": None if linked else gone.id,
            "linkedRecordType": "artisan" if linked else None, "linkedRecordId": gone.id if linked else None})
        ids.media.append(media.id)
    # Workshops: one only gone attended, one both attended (collision).
    for both in (False, True):
        workshop = await db.workshop.create(data={
            "title": f"Workshop {dbt.word()}", "date": datetime(2026, 1, 5, tzinfo=UTC), "place": "Bhuj",
            "createdById": admin.id})
        ids.workshops.append(workshop.id)
        await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": gone.id})
        if both:
            await db.workshopartisan.create(data={"workshopId": workshop.id, "artisanId": keep.id})
    # An interview with gone alone; a section status for both (collision) and one for gone alone.
    questionnaire = await db.questionnaire.create(data={"title": f"Instrument {dbt.word()}", "sortOrder": 900})
    interview = await db.questionnaireinterview.create(data={
        "title": "Sitting", "createdById": admin.id, "questionnaireId": questionnaire.id,
        "artisanSetKey": gone.id})
    await db.questionnaireinterviewartisan.create(data={"interviewId": interview.id, "artisanId": gone.id})
    ids.interviews.append(interview.id)
    for code, both in (("A", True), ("B", False)):
        section = await db.questionnairesection.create(data={
            "questionnaireId": questionnaire.id, "code": code, "title": code, "sortOrder": ord(code)})
        ids.sections.append(section.id)
        await db.questionnairesectionstatus.create(data={
            "artisanId": gone.id, "sectionId": section.id, "status": "NEEDS_REVIEW", "setById": admin.id})
        if both:
            await db.questionnairesectionstatus.create(data={
                "artisanId": keep.id, "sectionId": section.id, "status": "COMPLETED", "setById": admin.id})
    # Polymorphic rows.
    await db.recordrevision.create(data={"recordType": "artisan", "recordId": gone.id, "editedById": other.id,
                                         "changes": Json({"phone": {"old": None, "new": "x"}})})
    await db.reviewlog.create(data={"recordType": "ARTISAN", "recordId": gone.id, "status": "APPROVED",
                                    "reviewerId": admin.id})
    await db.entrycomment.create(data={"recordType": "artisan", "recordId": gone.id, "authorId": admin.id,
                                       "body": "Check the village"})
    grant = await db.dataaccessgrant.create(data={"ownerId": admin.id, "granteeId": other.id, "allData": False})
    ids.grants.append(grant.id)
    await db.dataaccessscopeitem.create(data={"grantId": grant.id, "recordType": "artisan", "recordId": gone.id})
    await db.dataaccessscopeitem.create(data={"grantId": grant.id, "recordType": "artisan", "recordId": keep.id})
    task = await db.assignedtask.create(data={
        "title": "Record tools", "assigneeId": other.id, "createdById": admin.id, "recordType": "artisan",
        "recordId": gone.id, "artisanIds": [gone.id, keep.id, "someone-else"]})
    ids.tasks.append(task.id)
    # Designer-portal links: one remote both hold (collision), one only gone holds. Consent on both,
    # gone's the more recent.
    for remote, both in ((f"dp-{dbt.word()}", True), (f"dp-{dbt.word()}", False)):
        ids.remotes.append(remote)
        await db.crossportalartisanlink.create(data={"artisanId": gone.id, "remoteArtisanId": remote, "matchedBy": "PEHCHAN"})
        if both:
            await db.crossportalartisanlink.create(data={"artisanId": keep.id, "remoteArtisanId": remote, "matchedBy": "AADHAAR"})
    await db.artisansharingconsent.create(data={"artisanId": keep.id, "granted": True,
                                                "recordedAt": datetime(2026, 3, 1, tzinfo=UTC)})
    await db.artisansharingconsent.create(data={"artisanId": gone.id, "granted": True, "note": "Agreed again",
                                                "recordedAt": datetime(2026, 5, 1, tzinfo=UTC)})
    pair = await _pair(db, keep.id, gone.id)
    return SimpleNamespace(db=db, admin=admin, other=other, craft=craft, keep=keep, gone=gone, pair=pair,
                           ids=ids, number=number, questionnaire=questionnaire)


async def _rows(db, sql: str, *params: Any) -> list[dict[str, Any]]:
    rows = await db.query_raw(sql, *params)
    out = [am.jsonish(r["row"]) for r in rows]
    return sorted(out, key=lambda r: json.dumps(r, sort_keys=True, default=str))


async def state(w) -> dict[str, Any]:
    """Every row the pair touches, as stored. Merge and undo revisions are left out."""
    db, ids = w.db, [w.keep.id, w.gone.id]
    return {
        "Artisan": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "Artisan" t WHERE t."id" = ANY($1::text[])', ids),
        "ProductDocumentation": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "ProductDocumentation" t WHERE t."id" = ANY($1::text[])', w.ids.products),
        "ToolDocumentation": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "ToolDocumentation" t WHERE t."id" = ANY($1::text[])', w.ids.tools),
        "ToolArtisan": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "ToolArtisan" t WHERE t."toolId" = ANY($1::text[])', w.ids.tools),
        "MediaFile": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "MediaFile" t WHERE t."id" = ANY($1::text[])', w.ids.media),
        "WorkshopArtisan": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "WorkshopArtisan" t WHERE t."workshopId" = ANY($1::text[])', w.ids.workshops),
        "QuestionnaireInterview": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "QuestionnaireInterview" t WHERE t."id" = ANY($1::text[])', w.ids.interviews),
        "QuestionnaireInterviewArtisan": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "QuestionnaireInterviewArtisan" t WHERE t."interviewId" = ANY($1::text[])', w.ids.interviews),
        "QuestionnaireSectionStatus": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "QuestionnaireSectionStatus" t WHERE t."sectionId" = ANY($1::text[])', w.ids.sections),
        "RecordRevision": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "RecordRevision" t WHERE t."recordId" = ANY($1::text[]) AND NOT jsonb_exists(t."changes", \'merge\') AND NOT jsonb_exists(t."changes", \'mergeUndone\')', ids),
        "ReviewLog": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "ReviewLog" t WHERE t."recordId" = ANY($1::text[])', ids),
        "EntryComment": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "EntryComment" t WHERE t."recordId" = ANY($1::text[])', ids),
        "DataAccessScopeItem": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "DataAccessScopeItem" t WHERE t."grantId" = ANY($1::text[])', w.ids.grants),
        "AssignedTask": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "AssignedTask" t WHERE t."id" = ANY($1::text[])', w.ids.tasks),
        "ArtisanSharingConsent": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "ArtisanSharingConsent" t WHERE t."artisanId" = ANY($1::text[])', ids),
        "CrossPortalArtisanLink": await _rows(db, 'SELECT to_jsonb(t) AS "row" FROM "CrossPortalArtisanLink" t WHERE t."remoteArtisanId" = ANY($1::text[])', w.ids.remotes),
    }


async def _merge(w, **over) -> dict[str, Any]:
    survivor = over.pop("survivor", _letter(w.pair, w.keep.id))
    choices = over.pop("choices", {})
    return await am.merge(w.pair, survivor=survivor, choices=choices, expected_updated_at=over.pop("expected", None),
                          reason=over.pop("reason", "Same person"), user=w.admin)


async def test_merge_moves_every_registry_table_and_undo_restores_every_row(world):
    w = world
    before = await state(w)
    gone_letter = _letter(w.pair, w.gone.id)
    result = await _merge(w, choices={"name": gone_letter, "notes": "BOTH"})
    keep_id, gone_id = w.keep.id, w.gone.id
    assert result["merge"]["survivorId"] == keep_id and result["merge"]["mergedId"] == gone_id
    db = w.db
    assert await db.artisan.find_unique(where={"id": gone_id}) is None
    survivor = await db.artisan.find_unique(where={"id": keep_id})
    assert survivor.name == w.gone.name  # chosen from the merged record
    assert survivor.notes == "Met at the fair\nPrefers mornings" or survivor.notes == "Prefers mornings\nMet at the fair"
    # The locked identity came from the only side that had it, after its unique key was freed.
    assert survivor.aadhaarFingerprint == w.gone.aadhaarFingerprint
    assert survivor.pehchanCardNumber == w.gone.pehchanCardNumber
    meta = survivor.extraMetadata if isinstance(survivor.extraMetadata, dict) else json.loads(survivor.extraMetadata)
    assert meta["source"] == "form" and meta["legacyAge"] == 52
    assert meta["fieldProvenance"]["name"]["by"] == w.other.id
    # Every reference now names the survivor.
    for table, column in (("ProductDocumentation", "artisanId"), ("ToolDocumentation", "artisanId"),
                          ("MediaFile", "artisanId"), ("ToolArtisan", "artisanId"),
                          ("WorkshopArtisan", "artisanId"), ("QuestionnaireInterviewArtisan", "artisanId"),
                          ("QuestionnaireSectionStatus", "artisanId"), ("CrossPortalArtisanLink", "artisanId"),
                          ("ArtisanSharingConsent", "artisanId")):
        left = await db.query_raw(f'SELECT count(*)::int AS "n" FROM "{table}" WHERE "{column}" = $1', gone_id)
        assert left[0]["n"] == 0, table
    for table in ("RecordRevision", "ReviewLog", "EntryComment", "DataAccessScopeItem", "AssignedTask"):
        left = await db.query_raw(f'SELECT count(*)::int AS "n" FROM "{table}" WHERE "recordId" = $1', gone_id)
        assert left[0]["n"] == 0, table
    media = await db.mediafile.find_many(where={"id": {"in": w.ids.media}})
    assert {m.artisanId for m in media if m.linkedRecordType is None} == {keep_id}
    assert {m.linkedRecordId for m in media if m.linkedRecordType} == {keep_id}
    task = await db.assignedtask.find_unique(where={"id": w.ids.tasks[0]})
    assert task.artisanIds == [keep_id, "someone-else"]
    interview = await db.questionnaireinterview.find_unique(where={"id": w.ids.interviews[0]})
    assert interview.artisanSetKey == keep_id
    product = await db.productdocumentation.find_unique(where={"id": w.ids.products[1]})
    assert product.artisanId == keep_id and product.artisanName == survivor.name
    # Collisions: the survivor's row wins and the merged record's is snapshotted.
    record = await db.artisanmerge.find_unique(where={"id": result["merge"]["id"]})
    moves = am.jsonish(record.moves)
    for label in ("ToolArtisan.artisanId", "WorkshopArtisan.artisanId", "QuestionnaireSectionStatus.artisanId",
                  "DataAccessScopeItem.recordId", "CrossPortalArtisanLink.artisanId"):
        assert moves["deleted"].get(label), label
    statuses = await db.questionnairesectionstatus.find_many(where={"artisanId": keep_id, "sectionId": w.ids.sections[0]})
    assert [s.status for s in statuses] == ["COMPLETED"]
    # Consent: gone's decision was the more recent, and is the survivor's now.
    consent = await db.artisansharingconsent.find_unique(where={"artisanId": keep_id})
    assert consent.note == "Agreed again"
    snapshot = am.jsonish(record.snapshot)
    assert "aadhaarNumber" not in snapshot["merged"]
    assert snapshot["consent"]["merged"]["note"] == "Agreed again"
    assert am.jsonish(record.choices)["name"] == gone_letter
    # The revision names the merge and the side taken, never a value.
    revisions = await db.recordrevision.find_many(where={"recordType": "artisan", "recordId": keep_id})
    merge_rev = [r for r in revisions if "merge" in (r.changes if isinstance(r.changes, dict) else json.loads(r.changes))]
    assert merge_rev
    changes = merge_rev[0].changes if isinstance(merge_rev[0].changes, dict) else json.loads(merge_rev[0].changes)
    assert changes["merge"]["mergedId"] == gone_id and changes["merge"]["fields"]["name"] == gone_letter
    # The redirect.
    assert await am.resolve(gone_id) == keep_id
    with pytest.raises(HTTPException) as caught:
        from app.api.routes import artisans as artisan_routes

        await artisan_routes.get_artisan(gone_id, current_user=w.admin)
    assert caught.value.status_code == 404 and caught.value.detail["code"] == "artisan_merged"
    assert caught.value.detail["survivorId"] == keep_id

    undone = await am.undo(result["merge"]["id"], user=w.admin)
    assert undone["restoredArtisanId"] == gone_id
    assert undone["keptSurvivorEdits"] == []
    assert undone["skippedRows"] == []
    after = await state(w)
    for table in before:
        assert after[table] == before[table], table
    assert await am.resolve(gone_id) is None


async def test_a_product_saved_with_a_merged_id_lands_on_the_survivor(world):
    from app.api.routes import products
    from app.schemas.records import ProductCreate

    w = world
    await _merge(w)
    created = await products.create_product(
        ProductCreate(craftName=w.craft.name, place="Bhuj", artisanName=w.gone.name, productName="Stole",
                      artisanId=w.gone.id, craftId=w.craft.id,
                      location={"latitude": 23.25, "longitude": 69.67, "state": "Gujarat", "district": "Kachchh"}),
        current_user=w.admin,
    )
    assert created["artisanId"] == w.keep.id


async def test_an_undo_keeps_a_survivor_edit_made_since(world):
    w = world
    gone_letter = _letter(w.pair, w.gone.id)
    result = await _merge(w, choices={"name": gone_letter})
    await w.db.artisan.update(where={"id": w.keep.id}, data={"name": "Edited after the merge"})
    undone = await am.undo(result["merge"]["id"], user=w.admin)
    assert "name" in undone["keptSurvivorEdits"]
    survivor = await w.db.artisan.find_unique(where={"id": w.keep.id})
    assert survivor.name == "Edited after the merge"
    assert await w.db.artisan.find_unique(where={"id": w.gone.id}) is not None


async def test_an_undo_keeps_a_whole_field_group_when_one_of_its_columns_was_edited(world):
    w = world
    gone_letter = _letter(w.pair, w.gone.id)
    await w.db.artisan.update(where={"id": w.gone.id}, data={"status": "NEEDS_REVISION", "reviewNotes": "Check the spelling"})
    result = await _merge(w, choices={"status": gone_letter})
    await w.db.artisan.update(where={"id": w.keep.id}, data={"status": "APPROVED", "reviewedAt": datetime.now(UTC)})
    undone = await am.undo(result["merge"]["id"], user=w.admin)
    assert "status" in undone["keptSurvivorEdits"]
    survivor = await w.db.artisan.find_unique(where={"id": w.keep.id})
    # The group stays as the survivor has it now, not half put back.
    assert survivor.status == "APPROVED" and survivor.reviewNotes == "Check the spelling"


async def test_a_pair_merges_again_after_an_undo(world):
    w = world
    first = await _merge(w)
    await am.undo(first["merge"]["id"], user=w.admin)
    w.pair = await _pair(w.db, w.keep.id, w.gone.id)
    second = await _merge(w)
    assert second["merge"]["id"] != first["merge"]["id"]
    assert await am.resolve(w.gone.id) == w.keep.id
    rows = await w.db.artisanmerge.find_many(where={"mergedId": w.gone.id})
    assert sorted(r.undoneAt is None for r in rows) == [False, True]


async def test_undo_refuses_when_expired_chained_or_conflicting(world):
    w = world
    result = await _merge(w)
    merge_id = result["merge"]["id"]
    # Chained: the survivor was merged into a third record.
    third = await dbt.make_artisan(w.db, w.admin, name=w.keep.name)
    pair2 = await _pair(w.db, w.keep.id, third.id)
    second = await am.merge(pair2, survivor=_letter(pair2, third.id), choices={}, expected_updated_at=None,
                            reason=None, user=w.admin)
    with pytest.raises(HTTPException) as caught:
        await am.undo(merge_id, user=w.admin)
    assert caught.value.detail["code"] == "merge_undo_later_first"
    assert await am.resolve(w.gone.id) == third.id  # two hops
    await am.undo(second["merge"]["id"], user=w.admin)
    # Conflict: somebody else now holds the merged record's Pehchan number.
    await w.db.artisan.update(where={"id": w.keep.id}, data={"pehchanCardNumber": None})
    await dbt.make_artisan(w.db, w.admin, pehchanCardNumber=w.gone.pehchanCardNumber)
    with pytest.raises(HTTPException) as caught:
        await am.undo(merge_id, user=w.admin)
    assert caught.value.detail["code"] == "merge_undo_conflict"
    # Expired.
    await w.db.artisanmerge.update(where={"id": merge_id}, data={"undoUntil": datetime.now(UTC) - timedelta(days=1)})
    with pytest.raises(HTTPException) as caught:
        await am.undo(merge_id, user=w.admin)
    assert caught.value.detail["code"] == "merge_undo_expired"
    record = await w.db.artisanmerge.find_unique(where={"id": merge_id})
    assert am.can_undo(record) is False


async def test_the_purge_clears_the_snapshot_and_keeps_the_redirect(world):
    w = world
    result = await _merge(w)
    merge_id = result["merge"]["id"]
    await w.db.artisanmerge.update(where={"id": merge_id}, data={"undoUntil": datetime.now(UTC) - timedelta(minutes=1)})
    assert await am.purge() >= 1
    record = await w.db.artisanmerge.find_unique(where={"id": merge_id})
    assert record.snapshot is None and record.moves is None and record.snapshotPurgedAt is not None
    assert am.merged_name(record) is None
    assert await am.resolve(w.gone.id) == w.keep.id
    with pytest.raises(HTTPException) as caught:
        await am.undo(merge_id, user=w.admin)
    assert caught.value.detail["code"] == "merge_undo_expired"


async def _blocked(w, pair, survivor="A") -> list[str]:
    with pytest.raises(HTTPException) as caught:
        await am.merge(pair, survivor=survivor, choices={}, expected_updated_at=None, reason=None, user=w.admin)
    assert caught.value.status_code == 409
    assert caught.value.detail["code"] == "merge_blocked"
    return [b["code"] for b in caught.value.detail["blockers"]]


async def test_each_blocker_refuses_the_merge(world):
    w = world
    a = await dbt.make_artisan(w.db, w.admin, aadhaar=dbt.valid_aadhaar())
    b = await dbt.make_artisan(w.db, w.admin, aadhaar=dbt.valid_aadhaar())
    assert "different_aadhaar" in await _blocked(w, await _pair(w.db, a.id, b.id))
    c = await dbt.make_artisan(w.db, w.admin, pehchanCardNumber=dbt.pehchan())
    d = await dbt.make_artisan(w.db, w.admin, pehchanCardNumber=dbt.pehchan())
    assert "different_pehchan" in await _blocked(w, await _pair(w.db, c.id, d.id))
    e = await dbt.make_artisan(w.db, w.admin)
    f = await dbt.make_artisan(w.db, w.admin)
    await w.db.execute_raw('UPDATE "Artisan" SET "aadhaarNumber" = $2 WHERE "id" = $1', f.id, dbt.valid_aadhaar())
    assert "aadhaar_backfill_pending" in await _blocked(w, await _pair(w.db, e.id, f.id))
    await w.db.execute_raw('UPDATE "Artisan" SET "aadhaarNumber" = NULL WHERE "id" = $1', f.id)


async def test_an_interview_set_collision_blocks_until_the_interviews_are_merged(world):
    from app.api.routes import questionnaire

    w = world
    x = await dbt.make_artisan(w.db, w.admin)
    first = await w.db.questionnaireinterview.create(data={
        "title": "With keep", "createdById": w.admin.id, "questionnaireId": w.questionnaire.id,
        "artisanSetKey": questionnaire.artisan_set_key([w.keep.id, x.id])})
    await w.db.questionnaireinterviewartisan.create(data={"interviewId": first.id, "artisanId": w.keep.id})
    await w.db.questionnaireinterviewartisan.create(data={"interviewId": first.id, "artisanId": x.id})
    second = await w.db.questionnaireinterview.create(data={
        "title": "With gone", "createdById": w.admin.id, "questionnaireId": w.questionnaire.id,
        "artisanSetKey": questionnaire.artisan_set_key([w.gone.id, x.id])})
    await w.db.questionnaireinterviewartisan.create(data={"interviewId": second.id, "artisanId": w.gone.id})
    await w.db.questionnaireinterviewartisan.create(data={"interviewId": second.id, "artisanId": x.id})
    preview = await am.preview(w.pair)
    blocker = next(b for b in preview["blockers"] if b["code"] == "interview_set_collision")
    assert blocker["action"]["kind"] == "merge_interviews"
    survivor = _letter(w.pair, w.keep.id)
    assert "interview_set_collision" in await _blocked(w, w.pair, survivor)
    await questionnaire.merge_interview_into(blocker["action"]["sourceId"], blocker["action"]["targetId"],
                                             current_user=w.admin)
    result = await _merge(w)
    assert result["merge"]["survivorId"] == w.keep.id


async def test_a_stale_preview_and_a_locked_choice_are_refused(world):
    w = world
    with pytest.raises(HTTPException) as caught:
        await _merge(w, expected={"a": "2000-01-01T00:00:00Z", "b": None})
    assert caught.value.status_code == 409 and caught.value.detail["code"] == "record_changed"
    keep_letter = _letter(w.pair, w.keep.id)
    with pytest.raises(HTTPException) as caught:
        await _merge(w, choices={"aadhaar": keep_letter})  # only the merged record holds one
    assert caught.value.status_code == 422 and caught.value.detail["code"] == "merge_locked_choice"
    with pytest.raises(HTTPException) as caught:
        await _merge(w, choices={"name": "BOTH"})
    assert caught.value.status_code == 422
    with pytest.raises(HTTPException) as caught:
        await _merge(w, choices={"favouriteColour": "A"})
    assert caught.value.status_code == 422


async def test_the_preview_offers_defaults_counts_and_the_consent_that_wins(world):
    w = world
    body = await am.preview(w.pair)
    assert body["defaultSurvivor"] in ("A", "B")
    fields = {f["key"]: f for f in body["fields"]}
    assert fields["aadhaar"]["locked"] is True
    assert fields["aadhaar"]["options"] == [_letter(w.pair, w.gone.id)]
    assert fields["aadhaar"][_letter(w.pair, w.gone.id).lower()].startswith("XXXX XXXX ")
    assert fields["pehchan"][_letter(w.pair, w.gone.id).lower()] == "Card number on file"
    assert fields["notes"]["options"] == ["A", "B", "BOTH"] and fields["notes"]["default"] == "BOTH"
    assert set(body["related"]["a"]) == set(am.RELATED_KEYS)
    assert body["consent"]["result"] == "GRANTED" and body["consent"]["from"] == _letter(w.pair, w.gone.id)
    assert body["linkedFromDesignerPortal"][_letter(w.pair, w.gone.id).lower()] == 2
    text = json.dumps(body, default=str)
    assert w.number not in text and w.gone.pehchanCardNumber not in text
    assert w.gone.aadhaarFingerprint not in text


async def test_a_more_recent_withdrawal_wins_and_removes_the_links(world):
    w = world
    await w.db.artisansharingconsent.update(
        where={"artisanId": w.gone.id}, data={"granted": False, "recordedAt": datetime(2026, 6, 1, tzinfo=UTC)})
    before = await state(w)
    result = await _merge(w)
    consent = await w.db.artisansharingconsent.find_unique(where={"artisanId": w.keep.id})
    assert consent.granted is False
    assert await w.db.crossportalartisanlink.count(where={"artisanId": w.keep.id}) == 0
    await am.undo(result["merge"]["id"], user=w.admin)
    after = await state(w)
    assert after["CrossPortalArtisanLink"] == before["CrossPortalArtisanLink"]
    assert after["ArtisanSharingConsent"] == before["ArtisanSharingConsent"]


async def test_the_merges_list_names_both_records_until_the_purge(world):
    from app.api.routes import artisan_duplicates as routes

    w = world
    result = await _merge(w)
    page = await routes.list_merges(_=w.admin, page=1, pageSize=50)
    item = next(i for i in page["items"] if i["id"] == result["merge"]["id"])
    assert item["survivor"]["id"] == w.keep.id and item["merged"]["name"] == w.gone.name
    assert item["canUndo"] is True and item["mergedBy"]["id"] == w.admin.id
