"""Machine-drafted answers from a take's transcript go to an AI model, so the one consent store
decides: a withdrawal stops the next draft (``questionnaire_v2/answers.proposals``).

No database and no provider: the take, the consent verdict and the drafting call are stand-ins, and
the database fake answers only the two statements ``proposals`` makes for a section target.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import answer_drafts, consent_gate
from app.services.questionnaire_v2 import answers, recordings


class _Db:
    def __init__(self) -> None:
        self.drafts: list[tuple[Any, ...]] = []

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if '"QuestionnaireQuestion"' in sql:
            return [{"lineageId": "lin-1", "prompt": "How did you learn?"}]
        if '"QuestionnaireAnswer"' in sql:
            return []
        raise AssertionError(f"unexpected statement: {sql[:80]}")

    async def execute_raw(self, sql: str, *args: Any) -> int:
        assert sql.startswith('INSERT INTO "QuestionnaireTakeDraft"'), sql[:80]
        self.drafts.append(args)
        return 1


@pytest.fixture
def take(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    state = SimpleNamespace(verdict=consent_gate.ALLOW, asked=[], db=_Db())
    serialized = {
        "mediaFileId": "m1",
        "transcript": {"text": "From my mother."},
        "subjects": [{"role": "ANSWERED", "artisanId": "a1", "named": True}],
        "targets": [{"questionLineageId": None, "sectionId": "s1"}],
    }

    async def require(_db: Any, recording_id: str) -> Any:
        return SimpleNamespace(id=recording_id)

    async def serialize(_db: Any, _rows: Any, _user: Any) -> list[dict[str, Any]]:
        return [serialized]

    async def verdict(media_id: Any, *, client: Any = None) -> str:
        assert media_id == "m1"
        return state.verdict

    async def draft(questions: Any, transcript: str, _settings: Any, *, user_id: Any) -> Any:
        state.asked.append(transcript)
        return {1: "Since I was small."}, "anthropic", "model"

    monkeypatch.setattr(recordings, "require_recording", require)
    monkeypatch.setattr(recordings, "serialize_recordings", serialize)
    monkeypatch.setattr(consent_gate, "verdict", verdict)
    monkeypatch.setattr(answer_drafts, "draft_answers", draft)
    monkeypatch.setattr(answers, "get_settings", lambda: SimpleNamespace(ai_answer_drafts_enabled=True))
    return state


def _proposals(take: SimpleNamespace) -> dict[str, Any]:
    return asyncio.run(answers.proposals("r1", SimpleNamespace(id="u1"), client=take.db))


def test_a_withdrawn_consent_stops_the_next_machine_draft(take: SimpleNamespace):
    first = _proposals(take)
    assert take.asked == ["From my mother."]
    assert [p["origin"] for p in first["proposals"]] == ["MACHINE_SUGGESTED"]

    take.verdict = consent_gate.REFUSED
    second = _proposals(take)
    assert take.asked == ["From my mother."], "the withdrawal must stop the next send"
    assert second["proposals"] == []


def test_no_machine_draft_while_consent_is_awaited(take: SimpleNamespace):
    take.verdict = consent_gate.AWAITING
    assert _proposals(take)["proposals"] == []
    assert take.asked == []
