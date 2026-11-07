import asyncio
import importlib
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import transcripts as routes
from app.transcription.transcript.schema import AskBody


@pytest.fixture(autouse=True)
def _fresh_counters(monkeypatch):
    for name in (
        "PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR",
        "PAID_AI_TRANSCRIPT_TOTAL_PER_HOUR",
        "PAID_AI_MEASUREMENT_PER_USER_PER_HOUR",
        "PAID_AI_MEASUREMENT_TOTAL_PER_HOUR",
    ):
        monkeypatch.delenv(name, raising=False)
    try:
        allowance = importlib.import_module("app.services.paid_ai_allowance")
        allowance.reset()
        monkeypatch.setattr(allowance, "STORE", getattr(allowance, "MEMORY", None), raising=False)
    except ImportError:
        pass
    yield
    try:
        importlib.import_module("app.services.paid_ai_allowance").reset()
    except ImportError:
        pass


def _user(user_id: str = "usr_reader"):
    return SimpleNamespace(id=user_id, role="CROWDSOURCE_VOLUNTEER", email=f"{user_id}@example.test")


@pytest.fixture
def paid(monkeypatch):
    seen = SimpleNamespace(asks=[], drafts=[], requeues=[], refuse_with=None)

    async def _media(media_id, user):
        return SimpleNamespace(id=media_id, transcriptText="dummy words")

    async def _ask(media, question):
        if seen.refuse_with is not None:
            raise seen.refuse_with
        seen.asks.append((media.id, question))
        return {"covered": True, "answer": "dummy", "citations": [], "model": "dummy"}

    async def _autodraft(media, user):
        seen.drafts.append(media.id)
        return {"drafts": []}

    async def _guard_write(user, media):
        return None

    monkeypatch.setattr(routes, "_media", _media)
    monkeypatch.setattr(routes.pipeline, "ask", _ask)
    monkeypatch.setattr(routes.pipeline, "autodraft", _autodraft)
    monkeypatch.setattr(routes.transcript_portal, "guard_write", _guard_write)
    return seen


def _ask_once(user, media_id: str = "med_1"):
    return asyncio.run(routes.ask(media_id, AskBody(question="what was dyed?"), user))


def _outcomes(calls):
    results = []
    for call in calls:
        try:
            call()
            results.append("ok")
        except HTTPException as exc:
            results.append(exc.status_code)
    return results


def test_one_reader_cannot_ask_past_their_hourly_allowance(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "2")
    reader = _user()
    results = _outcomes([lambda: _ask_once(reader)] * 3)
    assert results == ["ok", "ok", 429]
    assert len(paid.asks) == 2, "the third question still reached the paid model"


def test_the_refusal_says_when_to_try_again(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "1")
    reader = _user()
    _ask_once(reader)
    with pytest.raises(HTTPException) as caught:
        _ask_once(reader)
    assert caught.value.status_code == 429
    assert caught.value.detail["code"] == "RATE_LIMITED"
    assert caught.value.detail["message"].endswith(".")
    assert int(caught.value.headers["Retry-After"]) > 0


def test_one_reader_spending_their_allowance_leaves_another_reader_able_to_ask(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "1")
    first, second = _user("usr_a"), _user("usr_b")
    assert _outcomes([lambda: _ask_once(first), lambda: _ask_once(first)]) == ["ok", 429]
    assert _outcomes([lambda: _ask_once(second)]) == ["ok"]


def test_a_portal_wide_ceiling_bounds_many_accounts_together(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_TOTAL_PER_HOUR", "3")
    results = _outcomes([lambda n=n: _ask_once(_user(f"usr_{n}")) for n in range(5)])
    assert results == ["ok", "ok", "ok", 429, 429]
    assert len(paid.asks) == 3


def test_drafting_counts_against_the_same_allowance(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "2")
    editor = _user("usr_editor")

    def draft():
        return asyncio.run(routes.autodraft("med_1", editor))

    results = _outcomes([lambda: _ask_once(editor), draft, draft])
    assert results == ["ok", "ok", 429]
    assert paid.drafts == ["med_1"]


def test_a_question_refused_before_any_spend_is_not_counted(paid, monkeypatch):
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "1")
    reader = _user()
    paid.refuse_with = routes.pipeline.PipelineRefused("NOT_READY", "This recording has no transcript yet.")
    assert _outcomes([lambda: _ask_once(reader)]) == [409]
    paid.refuse_with = None
    assert _outcomes([lambda: _ask_once(reader)]) == ["ok"]


def _measure(ai, user_id):
    return asyncio.run(
        ai.analyze_measurement_image_bytes(
            b"\xff\xd8dummy", "dummy.jpg", "image/jpeg", SimpleNamespace(), user_id=user_id
        )
    )


@pytest.fixture
def measuring(monkeypatch):
    from app.services import ai

    posts = []

    async def _fresh():
        return None

    def _post(content, mime_type, settings, dimension=None):
        posts.append(len(content))
        return {"available": True, "status": "COMPLETED", "analysis": {}}

    monkeypatch.setattr(ai.managed_secrets, "refresh_if_stale", _fresh)
    monkeypatch.setattr(ai.managed_secrets, "gemini_key_pool", lambda: ["dummy-key"])
    monkeypatch.setattr(ai, "_post_gemini_measurement", _post)
    return SimpleNamespace(ai=ai, posts=posts)


def _attempt(call):
    try:
        call()
        return "ok"
    except Exception as exc:  # noqa: BLE001
        return type(exc).__name__


def test_one_account_cannot_run_measurement_past_its_hourly_allowance(measuring, monkeypatch):
    monkeypatch.setenv("PAID_AI_MEASUREMENT_PER_USER_PER_HOUR", "2")
    results = [_attempt(lambda: _measure(measuring.ai, "usr_volunteer")) for _ in range(3)]
    assert len(measuring.posts) == 2, results
    assert results[:2] == ["ok", "ok"]
    assert results[2] == "AllowanceSpent"


def test_measurement_has_a_portal_wide_ceiling(measuring, monkeypatch):
    monkeypatch.setenv("PAID_AI_MEASUREMENT_TOTAL_PER_HOUR", "2")
    results = [_attempt(lambda n=n: _measure(measuring.ai, f"usr_{n}")) for n in range(4)]
    assert len(measuring.posts) == 2, results


def test_a_queued_measurement_over_the_allowance_waits_instead_of_spending(monkeypatch):
    from app.services import media_queue, paid_ai_allowance

    updates = []

    class _Jobs:
        async def update(self, **kwargs):
            updates.append(kwargs)

    async def _spent(*_args, **_kwargs):
        raise paid_ai_allowance.AllowanceSpent("You have reached your hourly limit for grid measurements.", 120)

    async def _must_not_apply(job, result):
        raise AssertionError("a refused measurement was recorded as a result")

    monkeypatch.setattr(media_queue, "db", SimpleNamespace(mediaprocessingjob=_Jobs()))
    monkeypatch.setattr(media_queue, "get_object_bytes", lambda _key, **_kw: b"\xff\xd8dummy")
    monkeypatch.setattr(media_queue, "head_object", lambda _key: None, raising=False)
    monkeypatch.setattr(media_queue, "analyze_measurement_image_bytes", _spent)
    monkeypatch.setattr(media_queue, "_apply_measurement_result", _must_not_apply)
    job = SimpleNamespace(
        id="job-9",
        jobType=media_queue.MEASUREMENT,
        mediaFileId="m-9",
        attempts=1,
        maxAttempts=3,
        requestedById="usr_dummy",
        mediaFile=SimpleNamespace(
            id="m-9", objectKey="media/usr_dummy/grid.jpg", originalFilename="grid.jpg",
            mimeType="image/jpeg", sizeBytes=1024, uploadedById="usr_dummy",
        ),
    )
    asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    assert len(updates) == 1
    written = updates[0]["data"]
    assert written["status"] == media_queue.QUEUED
    assert written["attempts"] == 0
    assert written["runAfter"] > datetime.now(UTC)


class _SharedLedger:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, str]] = []

    async def query_raw(self, sql: str, bucket: str, who: str, window: int) -> list[dict[str, int]]:
        from app.services import paid_ai_allowance

        assert sql == paid_ai_allowance._COUNT_SQL and window == int(paid_ai_allowance.WINDOW_SECONDS)
        live = [row for row in self.rows if row[1] == bucket]
        return [{"total": len(live), "mine": sum(1 for row in live if row[2] == who), "totalWait": 900, "mineWait": 600}]

    async def execute_raw(self, sql: str, *args) -> int:
        from app.services import paid_ai_allowance

        if sql == paid_ai_allowance._SPEND_SQL:
            self.rows.append(tuple(args))
            return 1
        if sql == paid_ai_allowance._REFUND_SQL:
            mine = [row for row in self.rows if row[1] == args[0] and row[2] == args[1]]
            if mine:
                self.rows.remove(mine[-1])
            return len(mine[-1:])
        assert sql == paid_ai_allowance._PRUNE_SQL
        return 0


@pytest.fixture
def shared_ledger(monkeypatch):
    from app.services import paid_ai_allowance

    ledger = _SharedLedger()
    monkeypatch.setattr(paid_ai_allowance, "STORE", getattr(paid_ai_allowance, "DATABASE", None), raising=False)
    monkeypatch.setattr(paid_ai_allowance, "_client", lambda: ledger, raising=False)
    return ledger


def test_a_restart_does_not_hand_back_a_spent_allowance(shared_ledger, monkeypatch):
    from app.services import paid_ai_allowance

    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "2")
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.TRANSCRIPT, "usr_a"))
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.TRANSCRIPT, "usr_a"))
    paid_ai_allowance.reset()
    with pytest.raises(paid_ai_allowance.AllowanceSpent) as caught:
        asyncio.run(paid_ai_allowance.take(paid_ai_allowance.TRANSCRIPT, "usr_a"))
    assert caught.value.retry_after == 600
    assert len(shared_ledger.rows) == 2


def test_the_queue_worker_and_the_api_spend_one_portal_wide_count(shared_ledger, monkeypatch):
    from app.services import paid_ai_allowance

    monkeypatch.setenv("PAID_AI_MEASUREMENT_TOTAL_PER_HOUR", "2")
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.MEASUREMENT, "usr_api"))
    paid_ai_allowance.reset()
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.MEASUREMENT, "usr_worker"))
    with pytest.raises(paid_ai_allowance.AllowanceSpent) as caught:
        asyncio.run(paid_ai_allowance.take(paid_ai_allowance.MEASUREMENT, "usr_third"))
    assert caught.value.retry_after == 900
    asyncio.run(paid_ai_allowance.refund(paid_ai_allowance.MEASUREMENT, "usr_worker"))
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.MEASUREMENT, "usr_third"))
    assert [row[2] for row in shared_ledger.rows] == ["usr_api", "usr_third"]


def test_an_unreachable_store_still_counts_in_the_process(monkeypatch):
    from app.services import paid_ai_allowance

    class _Down:
        async def query_raw(self, *_args):
            raise ConnectionError("database is down")

        async def execute_raw(self, *_args):
            raise ConnectionError("database is down")

    monkeypatch.setattr(paid_ai_allowance, "STORE", getattr(paid_ai_allowance, "DATABASE", None), raising=False)
    monkeypatch.setattr(paid_ai_allowance, "_client", _Down, raising=False)
    monkeypatch.setenv("PAID_AI_TRANSCRIPT_PER_USER_PER_HOUR", "1")
    asyncio.run(paid_ai_allowance.take(paid_ai_allowance.TRANSCRIPT, "usr_b"))
    with pytest.raises(paid_ai_allowance.AllowanceSpent):
        asyncio.run(paid_ai_allowance.take(paid_ai_allowance.TRANSCRIPT, "usr_b"))
