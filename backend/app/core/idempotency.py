"""``Idempotency-Key`` on POST: a create that lands twice writes once.

THE DUPLICATE THIS CLOSES. A client POSTs a create, this server writes the row, and the answer is
lost on the way back (a tunnel, a captive portal, the app killed mid-request). The client learned
nothing, so it sends the same create again and a second record appears. The Android outbox now
moves such a create into its queue and replays it, so the replay has to be safe.

THE CONTRACT. A POST to one of the CREATE routes in ``CREATE_ROUTES`` carrying
``Idempotency-Key: <8-128 of [A-Za-z0-9_-]>``:

* first time: runs normally. A 2xx answer is stored for 30 days under (caller, key). Thirty and not
  one: a researcher offline in the field for a week is normal, and a key that has expired by the
  time the queue drains protects nothing.
* again within 30 days, same method, path and body: the stored answer is returned as it was, with
  ``Idempotent-Replayed: true``. The route does not run, so nothing is written twice.
* again while the first is still running: ``409`` with ``Idempotency-Status: in-progress`` and
  ``Retry-After``. The client retries later; it must not treat this as a refusal. A first request
  still marked running after ``STALE_PENDING`` (15 minutes, longer than the handset's 12-minute call
  timeout and CloudFront's origin timeout on the slow box) died with its process, and exactly ONE
  repeat may take its place: the takeover is a conditional update on the marker it read, so two
  repeats racing for it cannot both run the route.
* again with a different method, path or body: ``422``. One key names one request.
* a non-2xx answer or an exception is not stored, so a failed create can be retried.

It is ADDITIVE AND NARROW. A request without the header, a request that is not a POST, a request to
any route outside ``CREATE_ROUTES`` (sign-in, two-step, dataset tokens, secrets and AI keys among
them, whose answers must never be written to a table) and a request with no valid bearer token (the
route answers 401 itself) pass straight through and nothing is stored.

Keys are scoped to the caller's user id from the bearer token, so one account can never be answered
with another account's stored response, and a re-issued token (sign out, sign in) still finds its
own keys.

This sits alongside the older ``clientKey`` body field (``app/services/records.py``), which four
record routes still honour. A key in the header covers every create route, including artisans,
crafts, questionnaire interviews and comments, which ``clientKey`` never did.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.security import decode_access_token

logger = logging.getLogger(__name__)

HEADER = b"idempotency-key"
TTL = timedelta(days=30)
# A first request still marked running after this long was killed with the process; a repeat may
# take its place instead of waiting forever. Above the handset's 12-minute call timeout, so a slow
# but live first request is never overtaken.
STALE_PENDING = timedelta(minutes=15)

# The create routes a client sends a key with: an allow-list, so a key on any other route (sign-in,
# /auth/*, /datasets/token, /secrets, /me/ai-keys, ...) is ignored and its answer never stored.
CREATE_ROUTES = frozenset(
    {
        "/api/artisans",
        "/api/crafts",
        "/api/workshops",
        "/api/products",
        "/api/tools",
        "/api/processes",
        "/api/questionnaire/interviews",
        "/api/data-access/comments",
    }
)


def is_create_route(path: str) -> bool:
    return path.rstrip("/") in CREATE_ROUTES
_KEY_RE = re.compile(r"^[A-Za-z0-9_-]{8,128}$")
# Creates answer small JSON. A body larger than this is passed through and not stored.
MAX_STORED_BODY = 256 * 1024


@dataclass
class StoredAnswer:
    fingerprint: str
    status_code: int | None
    body: bytes | None
    content_type: str | None
    created_at: datetime


class IdempotencyStore(Protocol):
    async def get(self, user_id: str, key: str) -> StoredAnswer | None: ...

    async def claim(self, user_id: str, key: str, fingerprint: str) -> bool:
        """Insert a running marker; False when (user_id, key) already exists."""

    async def take_over(self, user_id: str, key: str, fingerprint: str, seen_created_at: datetime) -> bool:
        """Reclaim a stale running marker, but only the one that was read (created at
        ``seen_created_at`` and still unanswered). True when this caller won it."""

    async def complete(self, user_id: str, key: str, status_code: int, body: bytes, content_type: str | None) -> None: ...

    async def release(self, user_id: str, key: str) -> None: ...


class PrismaIdempotencyStore:
    """The production store: the ``IdempotencyKey`` table, unique on (userId, key)."""

    def __init__(self, client: Any = None) -> None:
        self._client = client

    @property
    def _table(self) -> Any:
        if self._client is None:
            from app.core.db import db

            return db.idempotencykey
        return self._client.idempotencykey

    async def get(self, user_id: str, key: str) -> StoredAnswer | None:
        row = await self._table.find_unique(where={"userId_key": {"userId": user_id, "key": key}})
        if row is None:
            return None
        if row.createdAt <= datetime.now(UTC) - TTL:
            await self._table.delete_many(where={"userId": user_id, "key": key})
            return None
        return StoredAnswer(
            fingerprint=row.fingerprint,
            status_code=row.statusCode,
            body=row.responseBody.encode() if row.responseBody is not None else None,
            content_type=row.contentType,
            created_at=row.createdAt,
        )

    async def claim(self, user_id: str, key: str, fingerprint: str) -> bool:
        from prisma.errors import UniqueViolationError

        now = datetime.now(UTC)
        # Opportunistic sweep, one range delete on the createdAt index: the table only ever holds
        # TTL worth of keys.
        await self._table.delete_many(where={"createdAt": {"lt": now - TTL}})
        try:
            await self._table.create(
                data={
                    "userId": user_id,
                    "key": key,
                    "fingerprint": fingerprint,
                    "createdAt": now,
                }
            )
        except UniqueViolationError:
            return False
        return True

    async def take_over(self, user_id: str, key: str, fingerprint: str, seen_created_at: datetime) -> bool:
        # Conditional on the exact marker that was read: still unanswered, still the same createdAt.
        # Two repeats racing for one stale marker both match it, but the first update moves
        # createdAt, so the second changes no row and is told to wait.
        changed = await self._table.update_many(
            where={"userId": user_id, "key": key, "statusCode": None, "createdAt": seen_created_at},
            data={"fingerprint": fingerprint, "createdAt": datetime.now(UTC)},
        )
        return changed == 1

    async def complete(self, user_id: str, key: str, status_code: int, body: bytes, content_type: str | None) -> None:
        await self._table.update(
            where={"userId_key": {"userId": user_id, "key": key}},
            data={
                "statusCode": status_code,
                "responseBody": body.decode("utf-8", errors="replace"),
                "contentType": content_type,
            },
        )

    async def release(self, user_id: str, key: str) -> None:
        await self._table.delete_many(where={"userId": user_id, "key": key})


def _header(scope: Scope, name: bytes) -> str | None:
    for header_name, value in scope.get("headers") or []:
        if header_name.lower() == name:
            return value.decode("latin-1")
    return None


def _caller(scope: Scope) -> str | None:
    auth = _header(scope, b"authorization")
    if not auth or not auth.lower().startswith("bearer "):
        return None
    try:
        claims = decode_access_token(auth[7:].strip())
    except ValueError:
        return None
    sub = claims.get("sub")
    return str(sub) if sub else None


def _fingerprint(method: str, path: str, query: bytes, body: bytes) -> str:
    digest = hashlib.sha256()
    for part in (method.encode(), path.encode(), query, body):
        digest.update(len(part).to_bytes(8, "big"))
        digest.update(part)
    return digest.hexdigest()


async def _send_json(send: Send, status_code: int, payload: dict[str, Any], extra: list[tuple[bytes, bytes]]) -> None:
    body = json.dumps(payload).encode()
    headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()), *extra]
    await send({"type": "http.response.start", "status": status_code, "headers": headers})
    await send({"type": "http.response.body", "body": body})


class IdempotencyMiddleware:
    """Pure-ASGI, so it adds nothing to requests it does not act on."""

    def __init__(self, app: ASGIApp, store: IdempotencyStore | None = None) -> None:
        self.app = app
        self.store: IdempotencyStore = store or PrismaIdempotencyStore()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") != "POST" or not is_create_route(scope.get("path", "")):
            await self.app(scope, receive, send)
            return
        key = _header(scope, HEADER)
        if key is None:
            await self.app(scope, receive, send)
            return
        key = key.strip()
        if not _KEY_RE.match(key):
            await _send_json(
                send, 422, {"detail": "Idempotency-Key must be 8 to 128 letters, digits, '-' or '_'."}, []
            )
            return
        user_id = _caller(scope)
        if user_id is None:
            # No valid session: the route answers 401 itself, and there is nobody to scope a key to.
            await self.app(scope, receive, send)
            return

        # Read the whole body once, so it can be fingerprinted and then handed to the route.
        chunks: list[bytes] = []
        more = True
        while more:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunks.append(message.get("body", b""))
            more = message.get("more_body", False)
        body = b"".join(chunks)
        fingerprint = _fingerprint("POST", scope.get("path", ""), scope.get("query_string", b""), body)

        if not await self.store.claim(user_id, key, fingerprint):
            existing = await self.store.get(user_id, key)
            if existing is None:
                # Expired between the two reads; this request becomes the first.
                if not await self.store.claim(user_id, key, fingerprint):
                    await self._in_progress(send)
                    return
            elif existing.fingerprint != fingerprint:
                await _send_json(
                    send, 422, {"detail": "This Idempotency-Key was already used for a different request."}, []
                )
                return
            elif existing.status_code is not None:
                headers = [
                    (b"content-type", (existing.content_type or "application/json").encode()),
                    (b"content-length", str(len(existing.body or b"")).encode()),
                    (b"idempotent-replayed", b"true"),
                ]
                await send({"type": "http.response.start", "status": existing.status_code, "headers": headers})
                await send({"type": "http.response.body", "body": existing.body or b""})
                return
            elif datetime.now(UTC) - existing.created_at < STALE_PENDING:
                await self._in_progress(send)
                return
            elif not await self.store.take_over(user_id, key, fingerprint, existing.created_at):
                # Another repeat reclaimed it first and is running the route now.
                await self._in_progress(send)
                return

        delivered = False

        async def replay_body() -> Message:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        status_code = 500
        content_type: str | None = None
        captured: list[bytes] = []
        too_large = False

        async def capture(message: Message) -> None:
            nonlocal status_code, content_type, too_large
            if message["type"] == "http.response.start":
                status_code = message["status"]
                for name, value in message.get("headers") or []:
                    if name.lower() == b"content-type":
                        content_type = value.decode("latin-1")
            elif message["type"] == "http.response.body" and not too_large:
                captured.append(message.get("body", b""))
                if sum(len(c) for c in captured) > MAX_STORED_BODY:
                    too_large = True
            await send(message)

        try:
            await self.app(scope, replay_body, capture)
        except BaseException:
            await self._release_quietly(user_id, key)
            raise
        if 200 <= status_code < 300 and not too_large:
            try:
                await self.store.complete(user_id, key, status_code, b"".join(captured), content_type)
            except Exception:  # noqa: BLE001 - the answer is already sent; losing the record only loses dedup
                logger.exception("Could not store the answer for an Idempotency-Key")
        else:
            await self._release_quietly(user_id, key)

    async def _release_quietly(self, user_id: str, key: str) -> None:
        try:
            await self.store.release(user_id, key)
        except Exception:  # noqa: BLE001 - a stale marker is taken over after STALE_PENDING
            logger.exception("Could not release an Idempotency-Key")

    @staticmethod
    async def _in_progress(send: Send) -> None:
        await _send_json(
            send,
            409,
            {"detail": "The same request is still being processed. Try again shortly.", "code": "IDEMPOTENCY_IN_PROGRESS"},
            [(b"idempotency-status", b"in-progress"), (b"retry-after", b"5")],
        )
