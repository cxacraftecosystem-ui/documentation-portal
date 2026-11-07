import asyncio
import os
import uuid

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


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _seed_env():
    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        owner = await db.user.create(data={
            "email": f"step-owner-{stamp}@example.org", "name": "Step Owner",
            "role": "RESEARCHER", "passwordHash": hash_password("unused"),
        })
        stranger = await db.user.create(data={
            "email": f"step-stranger-{stamp}@example.org", "name": "Step Stranger",
            "role": "RESEARCHER", "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"step-admin-{stamp}@example.org", "name": "Step Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        product = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": "Printed cotton yardage", "createdById": owner.id,
        })
    finally:
        await db.disconnect()
    return locals()


@pytest.fixture(scope="module")
def env():
    from fastapi.testclient import TestClient

    from app.main import app

    seeded = asyncio.run(_seed_env())
    with TestClient(app) as client:
        yield {
            "client": client,
            "owner": create_access_token(subject=seeded["owner"].id),
            "stranger": create_access_token(subject=seeded["stranger"].id),
            "admin": create_access_token(subject=seeded["admin"].id),
            "owner_id": seeded["owner"].id,
            "stranger_id": seeded["stranger"].id,
            "product_id": seeded["product"].id,
        }


class _As:
    def __init__(self, client, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def get(self, url: str):
        return self._client.get(url, headers=self._headers)

    def post(self, url: str, json: dict):
        return self._client.post(url, json=json, headers=self._headers)

    def patch(self, url: str, json: dict):
        return self._client.patch(url, json=json, headers=self._headers)


@pytest.fixture
def owner_client(env):
    return _As(env["client"], env["owner"])


@pytest.fixture
def stranger_client(env):
    return _As(env["client"], env["stranger"])


@pytest.fixture
def admin_client(env):
    return _As(env["client"], env["admin"])


THREE_STEPS = [
    {"name": "Wash the cloth", "stepType": "SEQUENTIAL", "sortOrder": 1},
    {"name": "Print the border", "stepType": "SEQUENTIAL", "sortOrder": 2},
    {"name": "Sun-dry", "stepType": "SEQUENTIAL", "sortOrder": 3},
]


def _process(env, client, *, notes: str | None = None) -> dict:
    response = client.post("/api/processes", json={
        "name": "Bagru printing sequence",
        "productId": env["product_id"],
        "notes": notes,
        "steps": THREE_STEPS,
    })
    assert response.status_code == 201, response.text
    body = response.json()
    assert [s["name"] for s in body["steps"]] == [s["name"] for s in THREE_STEPS]
    return body


def _step_names(client, process_id: str) -> list[str]:
    response = client.get(f"/api/processes/{process_id}")
    assert response.status_code == 200, response.text
    return [s["name"] for s in response.json()["steps"]]


def _resend(process: dict) -> list[dict]:
    return [
        {
            "id": step["id"],
            "name": step["name"],
            "stepType": step["stepType"],
            "sortOrder": step["sortOrder"],
            "notes": step["notes"],
        }
        for step in process["steps"]
    ]



def test_a_stranger_cannot_empty_the_step_list_of_a_process_they_did_not_create(
    env, owner_client, stranger_client
):
    process = _process(env, owner_client)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": []})

    assert response.status_code == 403, response.text
    assert "steps" in response.json()["detail"]
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS], (
        "the steps were deleted by an account that may not touch them"
    )


def test_a_stranger_cannot_delete_one_step_through_the_form_either(
    env, owner_client, stranger_client
):
    process = _process(env, owner_client)
    remaining = _resend(process)[:-1]

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "name": process["name"],
        "productId": env["product_id"],
        "preProcessAvailable": process["preProcessAvailable"],
        "notes": process["notes"],
        "status": process["status"],
        "steps": remaining,
    })

    assert response.status_code == 403, response.text
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS]


def test_a_stranger_cannot_rename_a_step_somebody_else_recorded(env, owner_client, stranger_client):
    process = _process(env, owner_client)
    edited = _resend(process)
    edited[1]["name"] = "Print the WHOLE cloth"

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": edited})

    assert response.status_code == 403, response.text
    assert _step_names(owner_client, process["id"])[1] == "Print the border"



def test_a_stranger_may_still_fill_an_empty_field_while_resending_the_step_list_untouched(
    env, owner_client, stranger_client
):
    process = _process(env, owner_client, notes=None)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "notes": "Observed again during the second sitting.",
        "steps": _resend(process),
    })

    assert response.status_code == 200, response.text
    assert response.json()["notes"] == "Observed again during the second sitting."
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS]


def test_a_stranger_may_append_a_step_because_a_step_that_does_not_exist_is_an_empty_field(
    env, owner_client, stranger_client
):
    process = _process(env, owner_client)
    added = _resend(process) + [
        {"name": "Fold and stack", "stepType": "SEQUENTIAL", "sortOrder": 4, "notes": None}
    ]

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": added})

    assert response.status_code == 200, response.text
    assert _step_names(owner_client, process["id"])[-1] == "Fold and stack"


def test_the_owner_may_still_delete_their_own_steps(env, owner_client):
    process = _process(env, owner_client)

    response = owner_client.patch(f"/api/processes/{process['id']}", json={"steps": []})

    assert response.status_code == 200, response.text
    assert response.json()["steps"] == []
    assert _step_names(owner_client, process["id"]) == []


def test_an_admin_may_still_rewrite_anybody_s_steps(env, owner_client, admin_client):
    process = _process(env, owner_client)

    response = admin_client.patch(f"/api/processes/{process['id']}", json={
        "steps": [{"name": "Corrected single step", "stepType": "SEQUENTIAL", "sortOrder": 1}],
    })

    assert response.status_code == 200, response.text
    assert _step_names(owner_client, process["id"]) == ["Corrected single step"]



def test_a_refused_step_deletion_does_not_leave_the_field_half_saved(
    env, owner_client, stranger_client
):
    process = _process(env, owner_client, notes=None)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "notes": "This note must not survive the refusal.",
        "steps": _resend(process)[:-1],
    })
    assert response.status_code == 403, response.text

    detail = owner_client.get(f"/api/processes/{process['id']}").json()
    assert detail["notes"] is None, "a refused request persisted the field it also carried"
    assert [s["name"] for s in detail["steps"]] == [s["name"] for s in THREE_STEPS]
