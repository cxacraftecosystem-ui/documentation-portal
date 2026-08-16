"""**WHOSE KEY PAYS FOR THIS CALL** — the one question designer-supplied keys must never get wrong.

Every other property of this feature is a convenience. This one is money: a designer who pastes a
personal OpenAI key is putting their own card behind every proofread they run, and the two ways that
goes wrong are both silent.

  * **Charging the wrong person.** A resolution that reached for *a* personal key rather than *the
    caller's* would bill one designer for another's work, or bill a designer for a queue drain
    nobody asked them for. Nothing in the product would look different.
  * **Charging the organisation when a designer meant to pay.** The reverse — a personal key quietly
    skipped in favour of the deployment's — is the failure that makes the feature pointless, and it
    is what happens naturally if any of the fallback paths is too eager.

So the tests below are almost entirely about resolution order and its edges, and they run against a
fake ``db`` rather than Postgres: the logic under test is a decision, not a query, and a decision is
worth asserting on every developer machine rather than only where a database happens to be running.

THE ENCRYPTION IS REAL, NOT FAKED. Rows are written through ``managed_secrets.encrypt`` exactly as
the service writes them, so the decrypt path — including the undecryptable case, which is produced
by storing genuine ciphertext from a DIFFERENT Fernet key rather than by writing garbage — is the
same code that runs in production. A test that stubbed decryption would pass against a service that
had stopped decrypting anything.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass, field
from typing import Any

import pytest
from cryptography.fernet import Fernet

from app.services import ai_providers, managed_secrets, user_ai_keys
from app.services.ai_providers import AiProvider, AiTask

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


# --------------------------------------------------------------------------------------------
# A fake UserAiCredential table: rows in, rows out, and nothing else this module reads.
# --------------------------------------------------------------------------------------------


@dataclass
class _Row:
    id: str
    userId: str
    provider: str
    valueEnc: str
    hint: str | None = None
    model: str | None = None
    lastStatus: str = "UNKNOWN"
    lastCheckedAt: Any = None
    lastError: str | None = None
    updatedAt: Any = None


@dataclass
class _Table:
    rows: list[_Row] = field(default_factory=list)
    #: Set to raise on the next read, to prove a database hiccup degrades rather than propagates.
    fail_reads: bool = False

    async def find_unique(self, where: dict[str, Any]) -> _Row | None:
        key = where["userId_provider"]
        return next(
            (r for r in self.rows if r.userId == key["userId"] and r.provider == key["provider"]),
            None,
        )

    async def find_many(self, where: dict[str, Any]) -> list[_Row]:
        if self.fail_reads:
            raise RuntimeError("database is unreachable")
        return [r for r in self.rows if r.userId == where["userId"]]

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> None:
        row = next((r for r in self.rows if r.id == where["id"]), None)
        if row is not None:
            for name, value in data.items():
                setattr(row, name, value)


class _Db:
    def __init__(self, table: _Table) -> None:
        self.useraicredential = table


@pytest.fixture
def table(monkeypatch) -> _Table:
    fake = _Table()
    monkeypatch.setattr(user_ai_keys, "db", _Db(fake))
    return fake


@pytest.fixture
def no_app_keys(monkeypatch):
    """No deployment key for any provider, so a resolution can only come from a person."""

    async def _none(_key: str) -> str | None:
        return None

    monkeypatch.setattr(managed_secrets, "get_secret", _none)


def _app_key(monkeypatch, **by_key: str):
    async def _get(key: str) -> str | None:
        return by_key.get(key)

    monkeypatch.setattr(managed_secrets, "get_secret", _get)


def _store(table: _Table, user: str, provider: AiProvider, key: str, model: str | None) -> _Row:
    row = _Row(
        id=f"{user}-{provider.value}",
        userId=user,
        provider=provider.value,
        valueEnc=managed_secrets.encrypt(key),
        model=model,
    )
    table.rows.append(row)
    return row


# --------------------------------------------------------------------------------------------
# 1. The resolution order
# --------------------------------------------------------------------------------------------


async def test_a_designers_own_key_wins_over_the_deployments(table, monkeypatch):
    """The whole feature in one assertion: their key, their model, their bill."""
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", "gpt-5.6-sol")

    resolved = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)

    assert resolved is not None
    assert resolved.api_key == "sk-hers"
    assert resolved.model == "gpt-5.6-sol"
    assert resolved.source == user_ai_keys.SOURCE_USER
    assert resolved.is_user_supplied


async def test_a_designer_with_no_key_of_their_own_runs_on_the_deployments(table, monkeypatch):
    """The behaviour this backend had before personal keys existed, unchanged."""
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")

    resolved = await user_ai_keys.resolve("designer-2", AiTask.PROOFREAD)

    assert resolved is not None
    assert resolved.api_key == "sk-the-organisations-key"
    assert resolved.source == user_ai_keys.SOURCE_APP
    assert not resolved.is_user_supplied


async def test_work_with_no_person_attached_never_reaches_a_personal_key(table, monkeypatch):
    """A queue drain bills the organisation, and this is the assertion that keeps it that way.

    ``user_id=None`` is what a background job passes — nobody asked for it personally, so nobody's
    card may be behind it. A resolution that scanned the table for "any key that can do this" would
    pass every other test in this file and fail here.
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", "gpt-5.6-sol")

    resolved = await user_ai_keys.resolve(None, AiTask.PROOFREAD)

    assert resolved is not None
    assert resolved.api_key == "sk-the-organisations-key"
    assert resolved.source == user_ai_keys.SOURCE_APP


async def test_one_designers_key_is_never_reached_for_another_designers_work(table, monkeypatch):
    """Two people, two keys, and neither may cross."""
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", None)
    _store(table, "designer-2", AiProvider.OPENAI, "sk-his", None)

    assert (await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)).api_key == "sk-hers"
    assert (await user_ai_keys.resolve("designer-2", AiTask.PROOFREAD)).api_key == "sk-his"


async def test_nothing_configured_anywhere_resolves_to_nothing(table, no_app_keys):
    """The caller then prints the sentence it already had, rather than calling a provider with None."""
    assert await user_ai_keys.resolve("designer-3", AiTask.PROOFREAD) is None


# --------------------------------------------------------------------------------------------
# 2. Capability — the reason a Claude key does not capture transcription
# --------------------------------------------------------------------------------------------


async def test_a_claude_key_does_not_capture_transcription(table, monkeypatch):
    """**No Claude model accepts audio**, so a designer holding only an Anthropic key must still
    transcribe through the server's key rather than receive an error.

    This is the single most valuable assertion in the file after the billing ones: the failure it
    prevents does not appear until somebody is standing in a courtyard with a recording they cannot
    re-take, and it would look like a broken app rather than a wrong setting.
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.ANTHROPIC, "sk-ant-hers", "claude-opus-5")

    transcription = await user_ai_keys.resolve("designer-1", AiTask.TRANSCRIBE)
    assert transcription is not None
    assert transcription.source == user_ai_keys.SOURCE_APP, "Claude cannot transcribe audio"

    # …and the same key still wins for everything it CAN do, which is what makes this a narrowing
    # rather than the key being ignored.
    proofread = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)
    assert proofread.source == user_ai_keys.SOURCE_USER
    assert proofread.provider is AiProvider.ANTHROPIC


async def test_a_designer_holding_two_keys_gets_the_one_that_can_do_the_job(table, no_app_keys):
    """Anthropic for the prose, Gemini for the recording — chosen per task, not per person."""
    _store(table, "designer-1", AiProvider.ANTHROPIC, "sk-ant-hers", "claude-opus-5")
    _store(table, "designer-1", AiProvider.GEMINI, "AIzaHers", "gemini-3.7-flash")

    assert (await user_ai_keys.resolve("designer-1", AiTask.TRANSCRIBE)).provider is AiProvider.GEMINI
    # Catalogue order decides between two that both qualify, so this is stable rather than dependent
    # on which row the database happened to return first.
    assert (await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)).provider is AiProvider.GEMINI


async def test_a_picked_model_is_a_preference_and_not_a_restriction(table, monkeypatch):
    """Whisper cannot proofread — so proofreading runs on ANOTHER OpenAI model, on the SAME key.

    The tempting alternative is to honour the pick literally and fall back to the deployment's key
    for anything it cannot do. That is wrong in the direction that costs somebody money without
    telling them: a designer who picked a transcription model has said nothing about proofreading,
    and giving their proofreads to the organisation's key is not what "use my account" meant. The
    fallback stays inside the provider they chose, which is the part they did state.
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", "whisper-1")

    transcribe = await user_ai_keys.resolve("designer-1", AiTask.TRANSCRIBE)
    assert transcribe.source == user_ai_keys.SOURCE_USER
    assert transcribe.model == "whisper-1", "the pick wins for what it can actually do"

    proofread = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)
    assert proofread.source == user_ai_keys.SOURCE_USER, "still their key, still their bill"
    assert proofread.api_key == "sk-hers"
    assert AiTask.PROOFREAD in ai_providers.spec_for(AiProvider.OPENAI).model(proofread.model).tasks


async def test_the_deployments_openai_key_can_still_transcribe(table, no_app_keys, monkeypatch):
    """The bug the Claude test found: OpenAI's DEFAULT model is a chat model, but OpenAI transcribes.

    Resolving the app-level key through the provider default alone answered "nothing configured" for
    transcription on a server holding a working ``OPENAI_API_KEY`` — the provider's own transcription
    models were never considered. See ``_resolve_app_credential``.
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")

    resolved = await user_ai_keys.resolve(None, AiTask.TRANSCRIBE)
    assert resolved is not None, "a server with an OpenAI key can transcribe"
    assert resolved.source == user_ai_keys.SOURCE_APP
    assert AiTask.TRANSCRIBE in ai_providers.spec_for(AiProvider.OPENAI).model(resolved.model).tasks


# --------------------------------------------------------------------------------------------
# 3. The edges that must fail towards a working app
# --------------------------------------------------------------------------------------------


async def test_a_model_the_catalogue_no_longer_knows_falls_back_within_the_same_key(
    table, monkeypatch
):
    """A provider retires a model, or an older build saved one. The stored choice is now unknown.

    ``ai_providers.supports`` fails CLOSED on an unrecognised id, which is the property that matters:
    the request is never sent naming a model the provider will reject. What replaces it is a CURRENT
    model **from the same provider on the same key** — the designer keeps paying for their own work,
    which is what they asked for by supplying the key; only the stale preference is dropped.

    (This assertion used to expect the deployment's key, and that expectation was written before
    ``_model_for_task`` existed — when a preference the catalogue could not honour meant the whole
    key was skipped. Moving somebody's bill to the organisation because a provider retired a model
    is a worse answer than running the current model they would have picked anyway.)
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", "gpt-4-vintage-retired")

    resolved = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)
    assert resolved.source == user_ai_keys.SOURCE_USER
    assert resolved.api_key == "sk-hers"
    assert resolved.model != "gpt-4-vintage-retired", "the retired id must not reach the provider"
    assert ai_providers.spec_for(AiProvider.OPENAI).model(resolved.model) is not None


async def test_a_key_with_no_model_chosen_runs_the_providers_default(table, no_app_keys):
    """Somebody who pasted a key and pressed Save gets a working default, not a refusal."""
    _store(table, "designer-1", AiProvider.ANTHROPIC, "sk-ant-hers", None)

    resolved = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)
    assert resolved.model == ai_providers.default_model_for(AiProvider.ANTHROPIC)


async def test_an_undecryptable_key_is_flagged_and_stepped_over(table, monkeypatch):
    """The rotation casualty: real ciphertext, written under a Fernet key this process does not have.

    Produced the honest way — encrypted with a DIFFERENT key — rather than by storing garbage, so
    the path under test is the same decrypt that runs in production. The designer must be told to
    paste it again, and meanwhile the app must keep working on the deployment's key.
    """
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    stranger = Fernet(base64.urlsafe_b64encode(b"x" * 32))
    row = _Row(
        id="rotated",
        userId="designer-1",
        provider=AiProvider.OPENAI.value,
        valueEnc=stranger.encrypt(b"sk-hers").decode("ascii"),
        model="gpt-5.6-terra",
    )
    table.rows.append(row)

    resolved = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)

    assert resolved.source == user_ai_keys.SOURCE_APP, "a dead key must not take the app down"
    assert row.lastStatus == "FAILED"
    assert "paste it again" in (row.lastError or "").lower()


async def test_a_database_hiccup_degrades_to_the_deployments_key(table, monkeypatch):
    """A personal key is a convenience; it may never be the reason a verb stops working."""
    _app_key(monkeypatch, OPENAI_API_KEY="sk-the-organisations-key")
    _store(table, "designer-1", AiProvider.OPENAI, "sk-hers", None)
    table.fail_reads = True

    resolved = await user_ai_keys.resolve("designer-1", AiTask.PROOFREAD)
    assert resolved.source == user_ai_keys.SOURCE_APP


# --------------------------------------------------------------------------------------------
# 4. The catalogue's honesty, which every capability decision above rests on
# --------------------------------------------------------------------------------------------


def test_no_claude_model_claims_it_can_transcribe():
    """Asserted over the whole family rather than the one model the resolver happens to pick.

    If a future row is added to the Anthropic list with the default task set copied from a Gemini
    row, every capability test above still passes — the resolver would just pick a different model —
    and transcription would start being routed to a provider that cannot do it. This is the line
    that catches that.
    """
    for model in ai_providers.models_for(AiProvider.ANTHROPIC):
        assert AiTask.TRANSCRIBE not in model.tasks, model.id


def test_every_provider_offers_at_least_one_model_for_every_task_it_claims():
    """A provider that can do a task must have a model that can, or the narrowing returns nothing."""
    for provider, spec in ai_providers.PROVIDERS.items():
        offered = {task for model in spec.models for task in model.tasks}
        for task in offered:
            assert ai_providers.models_for(provider, task), f"{provider}/{task}"


def test_every_providers_default_model_is_in_its_own_list_and_can_do_the_text_verbs():
    """A default that is not in the list resolves to nothing; one that cannot proofread is useless."""
    for provider, spec in ai_providers.PROVIDERS.items():
        model = spec.model(spec.default_model)
        assert model is not None, f"{provider} defaults to a model it does not offer"
        assert ai_providers.TEXT_TASKS <= model.tasks, f"{provider}'s default cannot do the text verbs"


def test_the_shape_check_catches_the_wrong_providers_key_and_admits_the_right_one():
    """The common paste error, caught where it happens rather than as an auth failure days later."""
    assert ai_providers.looks_like_key(AiProvider.ANTHROPIC, "sk-ant-abc123")
    assert not ai_providers.looks_like_key(AiProvider.ANTHROPIC, "sk-abc123")
    assert not ai_providers.looks_like_key(AiProvider.OPENAI, "AIzaSyAbc")
    assert not ai_providers.looks_like_key(AiProvider.GEMINI, "sk-abc123")
    # Never stricter than the provider itself: length and character set are deliberately unchecked,
    # because a rule tighter than the provider's is a rule that eventually refuses a valid key.
    assert ai_providers.looks_like_key(AiProvider.OPENAI, "sk-" + "z" * 400)


def test_every_managed_provider_key_the_catalogue_names_actually_exists_in_the_settings_hub():
    """The two halves of this feature must name the same keys, or the app-level fallback is dead.

    Each ``ProviderSpec.managed_key`` is the row the Settings hub manages for that provider. A typo
    here would not fail anywhere near this line: ``get_secret`` would simply return None forever and
    the deployment's key would look unconfigured, on a screen where an admin can see it is set.
    """
    for spec in ai_providers.PROVIDERS.values():
        assert managed_secrets.is_managed(spec.managed_key), spec.managed_key
