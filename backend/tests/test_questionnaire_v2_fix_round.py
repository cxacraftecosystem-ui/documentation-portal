"""The verifier's backend findings of 18 Oct, pinned without a database.

* the web client's Approve / Return post ``questionnaire_session``: it resolves to the interview;
* Return with a coded reason and no comments is accepted (the reason's label is logged);
* dictation sends no audio out while transcription is paused;
* ``--sha-only`` is a dry run unless ``--apply`` is given, and ``--apply`` of the backfill is refused
  while any clip has no checksum (copies would be undercounted).
"""

from __future__ import annotations

import argparse
import asyncio
from typing import Any

import pytest
from fastapi import HTTPException


def test_questionnaire_session_is_the_interview_for_review() -> None:
    from app.api.routes import review

    class Client:
        questionnaireinterview = object()

    assert review.canonical_type("questionnaire_session") == "questionnaire"
    assert review.canonical_type("Questionnaire") == "questionnaire"
    delegate, log_type, relation = review.delegate_for("questionnaire_session", Client())
    assert delegate is Client.questionnaireinterview and log_type == "QUESTIONNAIRE" and relation == "createdBy"


def test_return_with_a_coded_reason_needs_no_comments(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import review

    seen: dict[str, Any] = {}

    async def fake_set(record_type, record_id, new_status, payload, reviewer, **_kw):
        seen.update(type=record_type, status=new_status, notes=payload.notes, reason=payload.reason)
        return {"ok": True}

    monkeypatch.setattr(review, "set_review_status", fake_set)
    action = review.ReviewAction(notes=None, reason="MISSING_DETAILS")
    asyncio.run(review.send_record_for_revision("questionnaire_session", "s1", action, object()))
    assert seen == {"type": "questionnaire_session", "status": "NEEDS_REVISION", "notes": "Missing details", "reason": "MISSING_DETAILS"}

    for bad in (review.ReviewAction(notes=None), review.ReviewAction(notes="  ", reason="OTHER")):
        with pytest.raises(HTTPException) as refused:
            asyncio.run(review.send_record_for_revision("questionnaire_session", "s1", bad, object()))
        assert refused.value.status_code == 422


def test_dictation_sends_nothing_while_paused(monkeypatch: pytest.MonkeyPatch) -> None:
    from app import transcription
    from app.api.routes import media

    async def paused(*_a, **_k) -> bool:
        return True

    async def provider(*_a, **_k):
        raise AssertionError("audio left the server while paused")

    async def read(*_a, **_k):
        raise AssertionError("the body was read while paused")

    monkeypatch.setattr(transcription, "is_paused", paused)
    monkeypatch.setattr(media, "transcribe_audio_bytes", provider)
    monkeypatch.setattr(media, "read_upload_bounded", read)
    with pytest.raises(HTTPException) as refused:
        asyncio.run(media.transcribe_media_audio(request=None, file=None, current_user=object()))
    assert refused.value.status_code == 409 and refused.value.detail["code"] == "TRANSCRIPTION_PAUSED"


def _args(**kw: Any) -> argparse.Namespace:
    base = {"database_url": "postgresql://u:p@throwaway.invalid/db", "allow_host": ["throwaway.invalid"],
            "apply": False, "approved_report": None, "out": ".", "run_id": None, "undo": False, "sha_only": False}
    return argparse.Namespace(**{**base, **kw})


class _Client:
    def __init__(self, unhashed: int) -> None:
        self.rows = [{"id": f"m{i}", "objectKey": f"k{i}"} for i in range(unhashed)]
        self.writes = 0

    async def query_raw(self, *_a, **_k):
        return self.rows

    async def execute_raw(self, *_a, **_k):
        self.writes += 1
        return 1

    async def disconnect(self) -> None:
        return None


def _wire(monkeypatch: pytest.MonkeyPatch, client: _Client) -> Any:
    from scripts import questionnaire_v2_backfill as backfill

    async def connect(_url):
        return client

    monkeypatch.setattr(backfill.qv2_guard, "connect", connect)
    return backfill


def test_sha_only_is_a_dry_run_unless_applied(monkeypatch: pytest.MonkeyPatch, capsys) -> None:
    client = _Client(unhashed=3)
    backfill = _wire(monkeypatch, client)

    def no_hash(_key):
        raise AssertionError("an object was read on a dry run")

    monkeypatch.setattr(backfill, "sha_only", lambda *_a, **_k: no_hash(None))
    assert asyncio.run(backfill.run(_args(sha_only=True))) == 0
    assert client.writes == 0 and "3 objects would be hashed" in capsys.readouterr().out


def test_apply_is_refused_while_clips_have_no_checksum(monkeypatch: pytest.MonkeyPatch, capsys) -> None:
    client = _Client(unhashed=2)
    backfill = _wire(monkeypatch, client)

    async def plan(_client):
        return None

    monkeypatch.setattr(backfill, "dry_plan", plan)
    monkeypatch.setattr(backfill, "report_csv", lambda _p: "")
    monkeypatch.setattr(backfill, "report_md", lambda _p, _c: "")
    with pytest.raises(backfill.qv2_guard.Refused):
        asyncio.run(backfill.run(_args(apply=True, run_id="r1")))
    assert client.writes == 0 and "no checksum" in capsys.readouterr().err
