import asyncio
import inspect

import pytest
from fastapi import HTTPException

from app.api.routes import media


def _probes(monkeypatch: pytest.MonkeyPatch, answers: list[bool | None]) -> tuple[list[str], list[float]]:
    asked: list[str] = []
    slept: list[float] = []
    queue = list(answers)

    def probe(key: str) -> bool | None:
        asked.append(key)
        return queue.pop(0) if queue else answers[-1]

    async def nap(seconds: float) -> None:
        slept.append(seconds)

    monkeypatch.setattr(media, "object_exists", probe)
    monkeypatch.setattr(media.asyncio, "sleep", nap)
    return asked, slept


def test_an_upload_that_is_already_in_storage_completes_on_the_first_look(monkeypatch):
    asked, slept = _probes(monkeypatch, [True])
    asyncio.run(media._assert_upload_landed("media/u/devotee.jpg"))
    assert asked == ["media/u/devotee.jpg"]
    assert slept == []


def test_an_upload_that_lands_a_moment_late_is_waited_for(monkeypatch):
    asked, slept = _probes(monkeypatch, [False, False, True])
    asyncio.run(media._assert_upload_landed("media/u/devotee.jpg"))
    assert len(asked) == 3
    assert slept == list(media.UPLOAD_LANDING_WAITS[1:3])


def test_an_upload_that_never_reached_storage_is_refused_so_the_client_sends_it_again(monkeypatch):
    asked, slept = _probes(monkeypatch, [False])
    with pytest.raises(HTTPException) as refused:
        asyncio.run(media._assert_upload_landed("media/u/devotee.jpg"))
    assert refused.value.status_code == 409
    assert refused.value.detail == "The file didn't reach storage. Upload it again."
    assert (refused.value.headers or {}).get(media.UPLOAD_MISSING_HEADER) == "1"
    assert len(asked) == len(media.UPLOAD_LANDING_WAITS)
    assert sum(slept) <= 5


def test_storage_that_cannot_answer_is_a_retryable_refusal_never_a_record(monkeypatch):
    _probes(monkeypatch, [None])
    with pytest.raises(HTTPException) as refused:
        asyncio.run(media._assert_upload_landed("media/u/devotee.jpg"))
    assert refused.value.status_code == 503


def test_completion_checks_storage_before_it_creates_the_media_record():
    source = inspect.getsource(media._complete_old)
    landed = source.find("_assert_upload_landed(payload.objectKey)")
    assert landed != -1
    assert landed < source.find("db.mediafile.create(")
    assert source.find("_finish_pending_media(existing") < landed


def test_a_storage_error_while_checking_reads_as_cannot_answer(monkeypatch):
    def broken(_key: str) -> bool:
        raise RuntimeError("endpoint unreachable")

    monkeypatch.setattr(media, "object_exists", broken)
    assert media._probe_upload("media/u/devotee.jpg") is None
