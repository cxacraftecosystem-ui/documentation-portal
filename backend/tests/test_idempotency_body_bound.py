import asyncio
from types import SimpleNamespace

from app.core import deps, idempotency
from app.core.idempotency import IdempotencyMiddleware
from app.core.security import create_access_token


class CountingStore:
    def __init__(self) -> None:
        self.claims = 0

    async def get(self, user_id, key):
        return None

    async def claim(self, user_id, key, fingerprint):
        self.claims += 1
        return True

    async def take_over(self, user_id, key, fingerprint, seen_created_at):
        return False

    async def complete(self, user_id, key, status_code, body, content_type):
        return None

    async def release(self, user_id, key):
        return None


def test_an_oversized_body_streams_to_the_route_instead_of_being_buffered(monkeypatch):
    async def resolve(user_id):
        return SimpleNamespace(
            id=user_id, email="a@example.invalid", sessionsValidFrom=None, passwordHash=None, mustChangePassword=False
        )

    monkeypatch.setattr(deps, "resolve_user", resolve)
    chunk = b"x" * (256 * 1024)
    total_chunks = (idempotency.MAX_FINGERPRINT_BODY // len(chunk)) * 4
    sent = {"n": 0}
    seen = {"bytes": 0}

    async def receive():
        sent["n"] += 1
        more = sent["n"] < total_chunks
        return {"type": "http.request", "body": chunk, "more_body": more}

    async def app(scope, receive_inner, send):
        more = True
        while more:
            message = await receive_inner()
            seen["bytes"] += len(message.get("body", b""))
            more = message.get("more_body", False)
        await send({"type": "http.response.start", "status": 201, "headers": []})
        await send({"type": "http.response.body", "body": b"{}"})

    store = CountingStore()
    middleware = IdempotencyMiddleware(app, store=store)
    token = create_access_token("user_1")
    scope = {
        "type": "http",
        "method": "POST",
        "path": "/api/crafts",
        "query_string": b"",
        "headers": [(b"authorization", f"Bearer {token}".encode()), (b"idempotency-key", b"body-bound-key-0001")],
    }
    responses: list[dict] = []

    async def send(message):
        responses.append(message)

    asyncio.run(middleware(scope, receive, send))
    assert seen["bytes"] == total_chunks * len(chunk)
    assert store.claims == 0
    assert responses[0]["status"] == 201
