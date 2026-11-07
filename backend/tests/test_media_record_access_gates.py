import asyncio
import os
import uuid
from datetime import UTC, datetime

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

SENTINEL = "SENTINEL-DELETED-ANSWER"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _person(stamp: str, key: str, role: str):
    return await db.user.create(data={
        "email": f"gate-{key}-{stamp}@example.org", "name": f"Gate {key.title()}",
        "role": role, "passwordHash": hash_password("unused"),
    })


async def _photo(uploader_id: str, stamp: str, name: str, **links):
    key = f"media/{uploader_id}/{stamp}{uuid.uuid4().hex[:6]}/{name}"
    return await db.mediafile.create(data={
        "originalFilename": name, "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 4096,
        "bucket": "test-bucket", "objectKey": key, "url": f"https://cdn.example.test/{key}",
        "uploadedById": uploader_id, **links,
    })


async def _seed_env():
    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        sharer = await _person(stamp, "sharer", "RESEARCHER")
        grantee = await _person(stamp, "grantee", "RESEARCHER")
        stranger = await _person(stamp, "stranger", "RESEARCHER")
        volunteer = await _person(stamp, "volunteer", "CROWDSOURCE_VOLUNTEER")
        reviewer = await _person(stamp, "reviewer", "RESEARCHER")
        admin = await _person(stamp, "admin", "ADMIN")
        assigned = await _person(stamp, "assigned", "RESEARCHER")

        in_scope = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": f"Granted yardage {stamp}", "createdById": sharer.id,
        })
        out_of_scope = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Sita Devi",
            "productName": f"Private yardage {stamp}", "createdById": sharer.id,
        })
        granted_photo = await _photo(sharer.id, stamp, f"granted-{stamp}.jpg", productId=in_scope.id)
        private_photo = await _photo(sharer.id, stamp, f"private-{stamp}.jpg", productId=out_of_scope.id)
        grant = await db.dataaccessgrant.create(data={
            "ownerId": sharer.id, "granteeId": grantee.id, "tier": "DOWNLOAD", "status": "GRANTED", "allData": False,
        })
        await db.dataaccessscopeitem.create(data={"grantId": grant.id, "recordType": "product", "recordId": in_scope.id})

        pending = await db.artisan.create(data={
            "name": f"Pending Artisan {stamp}", "place": "Bagru", "createdById": volunteer.id, "status": "PENDING",
        })

        curated = await db.workshop.create(data={
            "title": f"Curated field workshop {stamp}", "date": datetime(2026, 6, 20, tzinfo=UTC),
            "place": "Bagru", "createdById": admin.id,
        })
        await db.workshopassignment.create(data={
            "workshopId": curated.id, "userId": assigned.id, "status": "GRANTED", "accessLevel": "CONTRIBUTE",
            "assignedById": admin.id,
        })
        curated_artisan = await db.artisan.create(data={
            "name": f"Curated Artisan {stamp}", "place": "Bagru", "createdById": admin.id, "workshopId": curated.id,
        })

        sitter = await db.artisan.create(data={
            "name": f"Sitting Artisan {stamp}", "place": "Bagru", "createdById": sharer.id,
        })
        form = await db.questionnaire.create(data={"title": f"Practice survey {stamp}", "sortOrder": 1})
        question = await db.questionnairequestion.create(data={
            "questionnaireId": form.id, "sectionCode": "B", "sectionTitle": "Practice",
            "prompt": "Which dyes do you use?", "sortOrder": 1,
        })
        deleted = await db.questionnaireinterview.create(data={
            "title": f"Deleted sitting {stamp}", "createdById": sharer.id, "questionnaireId": form.id,
            "deletedAt": datetime.now(UTC),
        })
        await db.questionnaireinterviewartisan.create(data={"interviewId": deleted.id, "artisanId": sitter.id})
        await db.questionnaireresponse.create(data={
            "interviewId": deleted.id, "questionId": question.id, "answeredById": sharer.id, "answerText": SENTINEL,
        })
    finally:
        await db.disconnect()
    return locals()


@pytest.fixture(scope="module")
def env():
    from fastapi.testclient import TestClient

    from app.main import app

    seeded = asyncio.run(_seed_env())
    people = ("sharer", "grantee", "stranger", "volunteer", "reviewer", "admin")
    with TestClient(app) as client:
        yield {
            "client": client,
            "stamp": seeded["stamp"],
            "tokens": {name: create_access_token(subject=seeded[name].id) for name in people},
            "ids": {
                "sharer": seeded["sharer"].id, "stranger": seeded["stranger"].id,
                "granted_photo": seeded["granted_photo"].id, "private_photo": seeded["private_photo"].id,
                "private_name": seeded["private_photo"].originalFilename,
                "pending": seeded["pending"].id, "curated_artisan": seeded["curated_artisan"].id,
                "sitter": seeded["sitter"].id, "deleted": seeded["deleted"].id,
            },
        }


def _as(env, who: str) -> dict:
    return {"Authorization": f"Bearer {env['tokens'][who]}"}


def test_a_subset_grant_does_not_release_the_owners_other_media(env):
    client = env["client"]
    private = client.get(f"/api/media/{env['ids']['private_photo']}", headers=_as(env, "grantee"))
    assert private.status_code == 200, private.text
    assert not (set(private.json()) & {"url", "publicUrl", "objectKey"}), "a one-product grant released an out-of-scope photo"
    granted = client.get(f"/api/media/{env['ids']['granted_photo']}", headers=_as(env, "grantee"))
    assert granted.status_code == 200, granted.text
    assert granted.json().get("url"), "the photo the grant names must still be served"


def test_the_owner_export_under_a_subset_grant_leaves_out_the_owners_other_media(env):
    response = env["client"].get(
        f"/api/export/dataset?ownerId={env['ids']['sharer']}", headers=_as(env, "grantee")
    )
    assert response.status_code == 200, response.text
    assert env["ids"]["private_name"] not in response.text


def test_a_decided_record_is_not_re_decided_by_a_lower_reviewer(env):
    client = env["client"]
    record = env["ids"]["pending"]
    approved = client.post(f"/api/review/artisan/{record}/approve", json={"notes": "Checked."}, headers=_as(env, "admin"))
    assert approved.status_code == 200, approved.text
    overturned = client.post(f"/api/review/artisan/{record}/reject", json={"notes": "No."}, headers=_as(env, "reviewer"))
    assert overturned.status_code == 409, overturned.text
    stands = client.get(f"/api/artisans/{record}", headers=_as(env, "admin"))
    assert stands.status_code == 200, stands.text
    assert stands.json()["status"] == "APPROVED"


def test_media_filed_under_a_curated_workshops_artisan_is_checked_against_that_workshop(env):
    name = f"gate-{uuid.uuid4().hex[:8]}.jpg"
    body = {
        "originalFilename": name, "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 2048,
        "objectKey": f"media/{env['ids']['stranger']}/{uuid.uuid4().hex}/{name}",
        "linkedRecordType": "artisan", "linkedRecordId": env["ids"]["curated_artisan"],
    }
    response = env["client"].post("/api/media/complete", json=body, headers=_as(env, "stranger"))
    assert response.status_code == 403, response.text


def test_a_client_chosen_media_url_is_not_stored(env):
    name = f"gate-{uuid.uuid4().hex[:8]}.jpg"
    body = {
        "originalFilename": name, "mediaType": "IMAGE", "mimeType": "image/jpeg", "sizeBytes": 2048,
        "objectKey": f"media/{env['ids']['stranger']}/{uuid.uuid4().hex}/{name}",
        "url": "javascript:alert(document.cookie)",
    }
    response = env["client"].post("/api/media/complete", json=body, headers=_as(env, "stranger"))
    assert response.status_code == 201, response.text
    read = env["client"].get(f"/api/media/{response.json()['id']}", headers=_as(env, "admin"))
    assert "javascript:" not in read.text


def test_a_deleted_session_is_gone_from_the_ordinary_readers(env):
    client = env["client"]
    by_id = client.get(f"/api/questionnaire/interviews/{env['ids']['deleted']}", headers=_as(env, "volunteer"))
    assert by_id.status_code == 404, by_id.text
    artisan = client.get(f"/api/artisans/{env['ids']['sitter']}/questionnaire", headers=_as(env, "volunteer"))
    assert artisan.status_code == 200, artisan.text
    assert SENTINEL not in artisan.text and env["ids"]["deleted"] not in artisan.text
    listed = client.get(f"/api/questionnaire/interviews?artisanId={env['ids']['sitter']}", headers=_as(env, "volunteer"))
    assert listed.status_code == 200, listed.text
    assert env["ids"]["deleted"] not in listed.text


def test_a_deleted_session_is_gone_from_the_owner_export(env):
    response = env["client"].get(
        f"/api/export/dataset?ownerId={env['ids']['sharer']}", headers=_as(env, "sharer")
    )
    assert response.status_code == 200, response.text
    assert SENTINEL not in response.text and env["ids"]["deleted"] not in response.text
