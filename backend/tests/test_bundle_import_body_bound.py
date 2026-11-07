from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import HTTPException, Response

from app.api.routes import questionnaire as routes
from app.services import questionnaire_library as library

pytestmark = pytest.mark.anyio

MIB = 1024 * 1024
MANAGER = SimpleNamespace(id="u1", role="ADMIN")


@pytest.fixture
def anyio_backend():
    return "asyncio"


class _Request:
    def __init__(self, chunks: int, content_length: int | None = None) -> None:
        self.headers = {"content-type": "application/octet-stream"}
        if content_length is not None:
            self.headers["content-length"] = str(content_length)
        self._chunk = b"\0" * MIB
        self._chunks = chunks
        self.consumed = 0

    async def stream(self):
        for _ in range(self._chunks):
            self.consumed += len(self._chunk)
            yield self._chunk

    async def body(self) -> bytes:
        return b"".join([chunk async for chunk in self.stream()])


async def test_a_raw_bundle_body_is_cut_off_at_the_bundle_limit():
    limit = library.BUNDLE_LIMITS["maxUncompressedBytes"]
    request = _Request(chunks=48)
    with pytest.raises(HTTPException) as caught:
        await routes.import_questionnaire_bundle(request, Response(), current_user=MANAGER)
    assert caught.value.status_code in (413, 422)
    assert request.consumed <= limit + MIB


async def test_a_declared_oversized_body_is_refused_before_it_is_read():
    request = _Request(chunks=48, content_length=48 * MIB)
    with pytest.raises(HTTPException) as caught:
        await routes.import_questionnaire_bundle(request, Response(), current_user=MANAGER)
    assert caught.value.status_code in (413, 422)
    assert request.consumed == 0
