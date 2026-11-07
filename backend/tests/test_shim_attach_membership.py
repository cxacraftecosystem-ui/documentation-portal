from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.questionnaire import shims_fr

SESSION = "ses_1"


class _Client:
    def __init__(self, row: dict[str, Any] | None, researchers: list[str]) -> None:
        self.row = row
        self.researchers = researchers
        self.writes: list[str] = []

        async def find_unique(where: dict[str, Any], **_: Any) -> Any:
            return SimpleNamespace(id=where["id"])

        self.questionnaireinterview = SimpleNamespace(find_unique=find_unique)

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if '"QuestionnaireInterviewResearcher"' in sql:
            return [{"userId": user_id} for user_id in self.researchers]
        if 'FROM "QuestionnaireInterview" i WHERE i."id" = $1' in sql:
            return [self.row] if self.row is not None else []
        raise AssertionError(f"unexpected query: {sql}")

    async def execute_raw(self, sql: str, *args: Any) -> int:
        self.writes.append(sql)
        return 1


def _attach(monkeypatch: pytest.MonkeyPatch, client: _Client, user_id: str, role: str = "RESEARCHER") -> Any:
    monkeypatch.setattr(shims_fr.ports, "db", lambda: client)
    user = SimpleNamespace(id=user_id, role=role)
    payload = SimpleNamespace(responses=[])
    return asyncio.run(shims_fr.attach_to_session(SESSION, payload, user, "q1"))


def _session(created_by: str = "usr_owner", deleted: bool = False) -> dict[str, Any]:
    return {"id": SESSION, "createdById": created_by, "deletedAt": "2026-11-01" if deleted else None}


def test_a_researcher_cannot_attach_to_a_session_they_do_not_belong_to(monkeypatch):
    for role in ("RESEARCHER", "CROWDSOURCE_VOLUNTEER"):
        client = _Client(_session(), ["usr_owner"])
        assert _attach(monkeypatch, client, "usr_stranger", role) is None
        assert not any("QuestionnaireInterviewResearcher" in sql for sql in client.writes)


def test_the_sessions_creator_and_its_researchers_still_attach(monkeypatch):
    owner = _Client(_session(), [])
    assert _attach(monkeypatch, owner, "usr_owner").id == SESSION
    member = _Client(_session(), ["usr_member"])
    assert _attach(monkeypatch, member, "usr_member").id == SESSION
    assert owner.writes == [] and member.writes == []


def test_a_deleted_or_missing_session_is_never_attached(monkeypatch):
    assert _attach(monkeypatch, _Client(_session(created_by="usr_owner", deleted=True), []), "usr_owner") is None
    assert _attach(monkeypatch, _Client(None, []), "usr_owner") is None
