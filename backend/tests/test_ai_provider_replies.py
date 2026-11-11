from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
import requests

from app.core.config import Settings
from app.services import ai, managed_secrets, user_ai_keys
from app.services import transcript_insights as ti
from app.services.ai_providers import (
    AiProvider,
    AiTask,
    ModelDeclined,
    default_model_for,
    gemini_reply_text,
    openai_message_text,
    openai_sampling,
    supports,
)
from app.services.user_ai_keys import SOURCE_APP, AiCredential


class _Response:
    def __init__(self, status: int, payload: Any = None) -> None:
        self.status_code = status
        self._payload = payload
        self.text = ""

    def json(self) -> Any:
        return self._payload

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)


def test_the_deployment_defaults_are_the_approved_current_models() -> None:
    assert Settings.model_fields["gemini_measurement_model"].default == "gemini-3.5-flash-lite"
    assert Settings.model_fields["openai_chat_model"].default == default_model_for(AiProvider.OPENAI)
    assert supports(AiProvider.OPENAI, Settings.model_fields["openai_chat_model"].default, AiTask.TRANSLATE)
    assert supports(AiProvider.GEMINI, Settings.model_fields["gemini_measurement_model"].default, AiTask.CAPTION)


def test_reasoning_openai_models_are_sent_no_temperature() -> None:
    assert openai_sampling("gpt-6-luna", 0.2) == {}
    assert openai_sampling("gpt-5.6-terra", 0.0) == {}
    assert openai_sampling("o4-mini", 0.2) == {}
    assert openai_sampling("gpt-4.1", 0.2) == {"temperature": 0.2}


def test_an_openai_refusal_is_a_decline_and_a_null_reply_is_empty_not_none() -> None:
    with pytest.raises(ModelDeclined):
        openai_message_text({"choices": [{"message": {"content": None, "refusal": "I can't help with that."}}]})
    assert openai_message_text({"choices": [{"message": {"content": None}}]}) == ""
    assert openai_message_text({"choices": [{"message": {"content": " Done. "}}]}) == "Done."


def test_a_gemini_block_is_a_decline_and_thoughts_are_not_the_answer() -> None:
    with pytest.raises(ModelDeclined):
        gemini_reply_text({"promptFeedback": {"blockReason": "PROHIBITED_CONTENT"}})
    with pytest.raises(ModelDeclined):
        gemini_reply_text({"candidates": [{"finishReason": "SAFETY", "content": {"parts": []}}]})
    reply = {"candidates": [{"content": {"parts": [{"text": "plan", "thought": True}, {"text": "Answer"}]}}]}
    assert gemini_reply_text(reply) == "Answer"


def test_a_blocked_summary_is_declined_and_not_retried(monkeypatch) -> None:
    blocked = {"promptFeedback": {"blockReason": "PROHIBITED_CONTENT"}}
    monkeypatch.setattr(ti.requests, "post", lambda *_a, **_k: _Response(200, blocked))

    async def resolve(_user_id: Any, _task: Any) -> AiCredential:
        return AiCredential(provider=AiProvider.GEMINI, api_key="k", model="gemini-3.8-flash", source=SOURCE_APP)

    monkeypatch.setattr(user_ai_keys, "resolve", resolve)
    with pytest.raises(ti.InsightsFailed) as failed:
        asyncio.run(ti._chat("s", "u", AiTask.SUMMARISE, None))
    assert str(failed.value) == ti.MODEL_DECLINED and failed.value.retry is False


def test_measurement_moves_to_the_fallback_model_when_the_first_is_missing(monkeypatch) -> None:
    calls: list[str] = []
    answers = [_Response(404), _Response(200, {"candidates": [{"content": {"parts": [{"text": '{"lengthInches": 4}'}]}}]})]

    def post(url: str, **_kwargs: Any) -> _Response:
        calls.append(url)
        return answers.pop(0)

    monkeypatch.setattr(managed_secrets, "gemini_key_pool", lambda: ["k1"])
    monkeypatch.setattr(ai.requests, "post", post)
    settings = SimpleNamespace(gemini_measurement_model="gemini-3.5-flash-lite")
    result = ai._post_gemini_measurement(b"image", "image/jpeg", settings)
    assert "gemini-3.5-flash-lite" in calls[0] and "gemini-3.7-flash" in calls[1]
    assert result["status"] == "COMPLETED"
    assert "gemini-3.7-flash" in str(result)


def test_a_text_verb_moves_to_the_fallback_gemini_model_on_404() -> None:
    from app.services.ai_providers import gemini_models, post_gemini

    assert gemini_models(None) == ("gemini-3.8-flash", "gemini-3.7-flash")
    assert gemini_models("gemini-3.7-flash") == ("gemini-3.7-flash",)
    seen: list[str] = []
    answers = [_Response(404), _Response(200, {"candidates": []})]

    def post(url: str, **_kwargs: Any) -> _Response:
        seen.append(url)
        return answers.pop(0)

    model, response = post_gemini(post, "gemini-3.8-flash", json={}, timeout=1)
    assert model == "gemini-3.7-flash" and response.status_code == 200
    assert "gemini-3.8-flash" in seen[0] and "gemini-3.7-flash" in seen[1]


def test_refinement_runs_on_gemini_even_where_an_openai_key_exists(monkeypatch) -> None:
    async def secret(name: str) -> str | None:
        return {"OPENAI_API_KEY": "sk-org", "GEMINI_API_KEY": "AIza-org"}.get(name)

    sent: list[str] = []

    def post(url: str, **_kwargs: Any) -> _Response:
        sent.append(url)
        return _Response(200, {"candidates": [{"content": {"parts": [{"text": "**Interviewer:** Hello"}]}}]})

    monkeypatch.setattr(managed_secrets, "get_secret", secret)
    monkeypatch.setattr(ai.requests, "post", post)
    result = asyncio.run(ai.refine_transcript_text("hello there", False, SimpleNamespace(openai_chat_model="x")))
    assert result["status"] == "COMPLETED" and result["refined"] == "**Interviewer:** Hello"
    assert result["model"] == "gemini-3.8-flash"
    assert sent and all("generativelanguage" in url for url in sent)


@pytest.mark.parametrize(("role", "sees"), [("ADMIN", True), ("MASTER_ADMIN", True), ("MINISTRY_ADMIN", True), ("RESEARCHER", False)])
def test_version_cost_and_badges_follow_one_admin_rule(monkeypatch, role, sees) -> None:
    from app.api.routes import transcripts
    from app.services import transcript_store

    version = SimpleNamespace(version=1, createdAt=None, supersededAt=None, turnCount=2, durationMs=10, sttCostUsd=0.1, translationCostUsd=0.02)

    async def media(_media_id: str, _user: Any) -> Any:
        return SimpleNamespace(id="m1")

    async def versions(_media_id: str) -> list[Any]:
        return [version]

    monkeypatch.setattr(transcripts, "_media", media)
    monkeypatch.setattr(transcript_store, "versions", versions)
    monkeypatch.setattr(transcripts.pipeline, "_badges", lambda _v: ["Scribe v3"])
    item = asyncio.run(transcripts.list_versions("m1", SimpleNamespace(role=role)))["items"][0]
    assert (item["costUsd"] == 0.12) is sees and (item["badges"] == ["Scribe v3"]) is sees
    assert sees or (item["costUsd"] is None and item["badges"] == [])
