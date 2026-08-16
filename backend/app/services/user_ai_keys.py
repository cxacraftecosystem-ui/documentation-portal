"""A designer's OWN provider key: stored, tested, and resolved ahead of the deployment's.

WHAT THIS ADDS, AND WHAT IT DELIBERATELY DOES NOT CHANGE
-------------------------------------------------------
Until now every AI verb in this backend ran on ONE key per provider — the deployment's, managed by
the master admin in the Settings hub (``services/managed_secrets``). That stays exactly as it is and
remains the fallback for everybody. What this module adds is a second, higher-priority source: a key
a designer supplied for themselves, used only for work that designer personally asks for, billed to
their own account at their provider.

THE RESOLUTION ORDER, WHICH IS THE WHOLE FEATURE
------------------------------------------------
:func:`resolve` answers "which credential runs this job for this person", in this order:

1. **The caller's own key for a provider whose chosen model can do this task.** Their key, their
   model, their bill.
2. **The deployment's key**, exactly as before this module existed.
3. **Nothing** — and the caller says so in the sentence it already had.

Step 1 has two conditions and both are load-bearing. The task check is why a Claude key does not
capture the transcription job: no Claude model accepts audio (see ``ai_providers``), so a designer
with only an Anthropic key still transcribes through the server's key rather than getting an error.
The model check is why a stored choice that has since been retired by the provider falls back
instead of failing: :func:`ai_providers.supports` fails closed on an unknown model id.

**A DESIGNER'S KEY IS NEVER USED FOR SOMEBODY ELSE'S WORK.** :func:`resolve` takes a user id and
reads only that user's row. The queue worker, which processes uploads on behalf of whoever created
the job, passes the job's requester — so a designer who supplies a key pays for their own recordings
and nobody else's. A background task with no person attached passes ``None`` and gets the
deployment's key, which is the correct and only safe default.

ENCRYPTION IS BORROWED, NOT REIMPLEMENTED
-----------------------------------------
Ciphertext here is written and read with ``managed_secrets.encrypt`` / ``decrypt`` — the same Fernet,
derived from the same source. A second encryption scheme would mean a second key to rotate, a second
way to lose data on a ``JWT_SECRET`` rotation, and a second place to get it wrong; the rotation
caveat documented at the head of ``managed_secrets`` applies verbatim to these rows, and a designer
whose key becomes undecryptable is told to re-enter it exactly as an admin is.

**THERE IS NO REVEAL ENDPOINT AND THERE MUST NEVER BE ONE.** A ManagedSecret can be revealed to the
master admin because it is the organisation's own credential. These are personal credentials, and no
administrator has any business reading one — so the plaintext leaves this module in exactly one
direction: into a provider HTTP call made on the owner's behalf.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services import ai_providers, managed_secrets
from app.services.ai_providers import AiProvider, AiTask

logger = logging.getLogger(__name__)

_STATUS_UNKNOWN = "UNKNOWN"
_STATUS_OK = "OK"
_STATUS_FAILED = "FAILED"

SOURCE_USER = "user"
SOURCE_APP = "app"

_UNDECRYPTABLE_ERROR = (
    "This key could not be decrypted — the server's encryption key changed after it was saved. "
    "Paste it again to fix it. Nothing is using it meanwhile."
)


@dataclass(frozen=True)
class AiCredential:
    """One resolved credential: who to call, with what, on whose behalf, and where it came from."""

    provider: AiProvider
    api_key: str
    model: str
    #: ``"user"`` when this is the caller's own key, ``"app"`` for the deployment's. Travels with the
    #: answer so a verb can record which it used — a designer needs to be able to tell whether the
    #: call they are about to make lands on their bill.
    source: str

    @property
    def is_user_supplied(self) -> bool:
        return self.source == SOURCE_USER


# --- Reading ----------------------------------------------------------------------------------


async def _row(user_id: str, provider: AiProvider) -> Any | None:
    try:
        return await db.useraicredential.find_unique(
            where={"userId_provider": {"userId": user_id, "provider": provider.value}}
        )
    except Exception as exc:  # noqa: BLE001 - a personal key must never break the shared path
        logger.warning("Could not read the AI credential for %s/%s (%s)", user_id, provider, exc)
        return None


async def _rows(user_id: str) -> list[Any]:
    try:
        return await db.useraicredential.find_many(where={"userId": user_id})
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not list AI credentials for %s (%s)", user_id, exc)
        return []


async def resolve(user_id: str | None, task: AiTask) -> AiCredential | None:
    """The credential that should run *task* for *user_id*. See the module docstring for the order.

    Never raises. Every failure path — no row, unreadable ciphertext, a model the catalogue no longer
    recognises, a database hiccup — degrades to the deployment's key, which is the behaviour this
    backend had before personal keys existed.
    """
    if user_id:
        own = await _resolve_user_credential(user_id, task)
        if own is not None:
            return own
    return await _resolve_app_credential(task)


def _model_for_task(provider: AiProvider, chosen: str | None, task: AiTask) -> str | None:
    """The model this provider should run for *task*, given what the owner picked. None if it cannot.

    ── WHY A PREFERENCE IS NOT A RESTRICTION ─────────────────────────────────────────────────────
    The picked model wins whenever it can do the job. When it cannot, this returns the provider's
    first model that can, rather than giving up — and that is the difference between a designer's key
    paying for their transcription and the organisation paying for it silently.

    A designer supplies a key to say *"use my account for my AI work"*, and picks a model to say
    *"prefer this one"*. Those are different statements. Somebody who picked GPT-5.6 Terra has said
    nothing about transcription, because Terra does not transcribe and the picker's transcription
    models are a separate group — so honouring the preference literally would mean their recordings
    quietly went on the organisation's bill while every proofread went on theirs. Reaching for
    ``gpt-transcribe`` **on their own key** is what they meant.

    THIS IS ALSO WHAT KEEPS THE ANTHROPIC RULE HONEST. It falls back within one provider and never
    across providers, so a Claude key still cannot transcribe — Anthropic has no audio model to fall
    back to, this returns None, and the deployment's key takes the job. The rule is enforced by the
    catalogue rather than by a special case here, which is why adding an audio model to a family
    would make it work with no edit to this function.
    """
    if chosen and ai_providers.supports(provider, chosen, task):
        return chosen
    capable = ai_providers.models_for(provider, task)
    return capable[0].id if capable else None


async def _resolve_user_credential(user_id: str, task: AiTask) -> AiCredential | None:
    """The caller's own key, if they have one that can do this job. None otherwise — never an error.

    ITERATES THEIR PROVIDERS RATHER THAN PICKING ONE, because a designer may hold two or three keys
    and only some of them can do the task at hand: somebody with an Anthropic key for proofreading
    and a Gemini key for everything else must get the Gemini one when a recording needs transcribing.
    Catalogue order decides between two that both qualify, so the choice is stable rather than
    dependent on which row the database returned first.
    """
    rows = {row.provider: row for row in await _rows(user_id)}
    if not rows:
        return None
    for provider in ai_providers.PROVIDERS:
        row = rows.get(provider.value)
        if row is None:
            continue
        model = _model_for_task(provider, row.model, task)
        if not model:
            continue
        plaintext = managed_secrets.decrypt(row.valueEnc)
        if plaintext is None:
            # The rotation casualty. Flagged so the owner is told to re-enter it, then skipped — the
            # deployment's key takes over, which is a working app rather than a broken verb.
            await _flag_undecryptable(row)
            continue
        return AiCredential(
            provider=AiProvider(provider.value), api_key=plaintext, model=model, source=SOURCE_USER
        )
    return None


async def _resolve_app_credential(task: AiTask) -> AiCredential | None:
    """The deployment's key for the first provider in the catalogue that can do *task*.

    The model comes from :func:`_model_for_task` seeded with the provider's catalogue default —
    a preference, resolved the same way a designer's is — rather than from anything an individual
    chose, because an app-level key belongs to the deployment and no one person's taste should
    decide what it spends.

    ── THIS USED THE DEFAULT MODEL ALONE AND THAT WAS A BUG ──────────────────────────────────────
    It asked only whether the provider's DEFAULT model could do the task, which is false of exactly
    the case that matters: OpenAI's default is a chat model, so a deployment holding a perfectly good
    ``OPENAI_API_KEY`` resolved to NOTHING for transcription — the provider has ``gpt-transcribe``
    and ``whisper-1`` and neither was ever considered. Transcription would have reported "no key
    configured" on a server that had one. `test_a_claude_key_does_not_capture_transcription` caught
    it, which is why that test asserts the app-level answer is present and not merely non-personal.
    """
    for provider, spec in ai_providers.PROVIDERS.items():
        model = _model_for_task(provider, spec.default_model, task)
        if not model:
            continue
        value = await managed_secrets.get_secret(spec.managed_key)
        if value:
            return AiCredential(provider=provider, api_key=value, model=model, source=SOURCE_APP)
    return None


async def _flag_undecryptable(row: Any) -> None:
    if row.lastStatus == _STATUS_FAILED and row.lastError == _UNDECRYPTABLE_ERROR:
        return
    try:
        await db.useraicredential.update(
            where={"id": row.id},
            data={
                "lastStatus": _STATUS_FAILED,
                "lastError": _UNDECRYPTABLE_ERROR,
                "lastCheckedAt": datetime.now(UTC),
            },
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not flag AI credential %s as undecryptable (%s)", row.id, exc)


# --- Description (what the API returns) ---------------------------------------------------------


def _hint(value: str | None) -> str | None:
    if not value:
        return None
    return value[-4:] if len(value) > 8 else "…"


def describe(provider: AiProvider, row: Any | None) -> dict[str, Any]:
    """The value-free projection of one provider row. NEVER add the plaintext to this dict."""
    spec = ai_providers.PROVIDERS[provider]
    stored = row is not None
    readable = stored and managed_secrets.decrypt(row.valueEnc) is not None
    model = (row.model if stored else None) or spec.default_model
    chosen = spec.model(model)
    return {
        "provider": provider.value,
        "label": spec.label,
        "configured": readable,
        # True only for the rotation casualty: a row exists and its ciphertext is dead. The client
        # renders this as "paste this key again"; without it the row reads as simply absent and the
        # owner never learns why their key stopped being used.
        "unreadable": stored and not readable,
        "hint": row.hint if stored else None,
        "model": model,
        # A model the provider has since retired, or one saved by an older build, is reported rather
        # than silently corrected — `supports` fails closed on it, so the designer's key is being
        # skipped and they are entitled to know which choice is doing that.
        "modelKnown": chosen is not None,
        "lastStatus": row.lastStatus if stored else _STATUS_UNKNOWN,
        "lastCheckedAt": row.lastCheckedAt if stored else None,
        "lastError": row.lastError if stored else None,
        "updatedAt": row.updatedAt if stored else None,
    }


async def list_for_user(user_id: str) -> list[dict[str, Any]]:
    """Every provider, in catalogue order, with this person's row where there is one."""
    rows = {row.provider: row for row in await _rows(user_id)}
    return [describe(provider, rows.get(provider.value)) for provider in ai_providers.PROVIDERS]


# --- Writes -----------------------------------------------------------------------------------


async def set_key(
    user_id: str, provider: AiProvider, value: str, model: str | None
) -> dict[str, Any]:
    """Store or rotate this person's key for one provider. Returns the value-free description.

    ``lastStatus`` resets to UNKNOWN because a previous verdict describes the OLD key and sitting
    next to a freshly pasted one it is actively misleading.
    """
    plaintext = value.strip()
    payload = {
        "valueEnc": managed_secrets.encrypt(plaintext),
        "hint": _hint(plaintext),
        "model": model,
        "lastStatus": _STATUS_UNKNOWN,
        "lastCheckedAt": None,
        "lastError": None,
    }
    await db.useraicredential.upsert(
        where={"userId_provider": {"userId": user_id, "provider": provider.value}},
        data={
            "create": {"userId": user_id, "provider": provider.value, **payload},
            "update": payload,
        },
    )
    # No value, no hint, no model — an audit line about a personal credential says that one changed
    # and nothing about what it is.
    logger.info("AUDIT personal AI key set: user=%s provider=%s", user_id, provider.value)
    return describe(provider, await _row(user_id, provider))


async def set_model(user_id: str, provider: AiProvider, model: str | None) -> dict[str, Any] | None:
    """Change the chosen model without re-pasting the key. None when there is no key to change.

    The verdict is NOT reset here, and that is the difference from :func:`set_key`: the verdict is
    about the key, the key has not changed, and clearing it would make a designer re-test after
    every model change for no reason.
    """
    row = await _row(user_id, provider)
    if row is None:
        return None
    await db.useraicredential.update(where={"id": row.id}, data={"model": model})
    return describe(provider, await _row(user_id, provider))


async def delete_key(user_id: str, provider: AiProvider) -> dict[str, Any]:
    """Remove this person's key so the deployment's applies again."""
    await db.useraicredential.delete_many(
        where={"userId": user_id, "provider": provider.value}
    )
    logger.info("AUDIT personal AI key cleared: user=%s provider=%s", user_id, provider.value)
    return describe(provider, None)


# --- Probing ------------------------------------------------------------------------------------


def _probe(provider: AiProvider, value: str) -> tuple[bool, str | None]:
    """Reachability, reusing the Settings hub's probes so one key is judged one way everywhere.

    A probe must never raise — it is a button — so anything unexpected becomes a readable verdict.
    """
    probes = {
        AiProvider.OPENAI: managed_secrets._probe_openai,  # noqa: SLF001 - one probe per provider,
        AiProvider.GEMINI: managed_secrets._probe_gemini,  # noqa: SLF001   deliberately shared with
        AiProvider.ANTHROPIC: managed_secrets._probe_anthropic,  # noqa: SLF001  the Settings hub.
    }
    try:
        ok, error = probes[provider](value)
    except Exception as exc:  # noqa: BLE001
        return False, f"Could not be tested: {type(exc).__name__}"
    return ok, error


async def test_key(user_id: str, provider: AiProvider) -> dict[str, Any] | None:
    """Probe this person's stored key now and persist the verdict. None when they have none."""
    row = await _row(user_id, provider)
    if row is None:
        return None
    plaintext = managed_secrets.decrypt(row.valueEnc)
    checked_at = datetime.now(UTC)
    if plaintext is None:
        await _flag_undecryptable(row)
        return describe(provider, await _row(user_id, provider))

    ok, error = await asyncio.to_thread(_probe, provider, plaintext)
    await db.useraicredential.update(
        where={"id": row.id},
        data={
            "lastStatus": _STATUS_OK if ok else _STATUS_FAILED,
            "lastCheckedAt": checked_at,
            "lastError": None if ok else error,
        },
    )
    return describe(provider, await _row(user_id, provider))
