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
    "This saved key can't be read. Paste it again to fix it. Nothing is using it meanwhile."
)


@dataclass(frozen=True)
class AiCredential:
    provider: AiProvider
    api_key: str
    model: str
    source: str

    @property
    def is_user_supplied(self) -> bool:
        return self.source == SOURCE_USER



async def _row(user_id: str, provider: AiProvider) -> Any | None:
    try:
        return await db.useraicredential.find_unique(
            where={"userId_provider": {"userId": user_id, "provider": provider.value}}
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not read the AI credential for %s/%s (%s)", user_id, provider, exc)
        return None


async def _rows(user_id: str) -> list[Any]:
    try:
        return await db.useraicredential.find_many(where={"userId": user_id})
    except Exception as exc:  # noqa: BLE001
        logger.warning("Could not list AI credentials for %s (%s)", user_id, exc)
        return []


async def resolve(user_id: str | None, task: AiTask) -> AiCredential | None:
    if user_id:
        own = await _resolve_user_credential(user_id, task)
        if own is not None:
            return own
    return await _resolve_app_credential(task)


def _model_for_task(provider: AiProvider, chosen: str | None, task: AiTask) -> str | None:
    if chosen and ai_providers.supports(provider, chosen, task):
        return chosen
    capable = ai_providers.models_for(provider, task)
    return capable[0].id if capable else None


async def _resolve_user_credential(user_id: str, task: AiTask) -> AiCredential | None:
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
            await _flag_undecryptable(row)
            continue
        return AiCredential(
            provider=AiProvider(provider.value), api_key=plaintext, model=model, source=SOURCE_USER
        )
    return None


async def _resolve_app_credential(task: AiTask) -> AiCredential | None:
    if task is not AiTask.TRANSCRIBE:
        from app.core.config import get_settings

        chosen = ai_providers.app_text_provider(getattr(get_settings(), "ai_text_provider", None))
        chosen_spec = ai_providers.PROVIDERS[chosen]
        chosen_model = _model_for_task(chosen, chosen_spec.default_model, task)
        if not chosen_model:
            return None
        chosen_value = await managed_secrets.get_secret(chosen_spec.managed_key)
        if not chosen_value:
            return None
        return AiCredential(provider=chosen, api_key=chosen_value, model=chosen_model, source=SOURCE_APP)
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



def _hint(value: str | None) -> str | None:
    if not value:
        return None
    return value[-4:] if len(value) > 8 else "…"


def describe(provider: AiProvider, row: Any | None) -> dict[str, Any]:
    spec = ai_providers.PROVIDERS[provider]
    stored = row is not None
    readable = stored and managed_secrets.decrypt(row.valueEnc) is not None
    model = (row.model if stored else None) or spec.default_model
    chosen = spec.model(model)
    return {
        "provider": provider.value,
        "label": spec.label,
        "configured": readable,
        "unreadable": stored and not readable,
        "hint": row.hint if stored else None,
        "model": model,
        "modelKnown": chosen is not None,
        "lastStatus": row.lastStatus if stored else _STATUS_UNKNOWN,
        "lastCheckedAt": row.lastCheckedAt if stored else None,
        "lastError": row.lastError if stored else None,
        "updatedAt": row.updatedAt if stored else None,
    }


async def list_for_user(user_id: str) -> list[dict[str, Any]]:
    rows = {row.provider: row for row in await _rows(user_id)}
    return [describe(provider, rows.get(provider.value)) for provider in ai_providers.PROVIDERS]



async def set_key(
    user_id: str, provider: AiProvider, value: str, model: str | None
) -> dict[str, Any]:
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
    logger.info("AUDIT personal AI key set: user=%s provider=%s", user_id, provider.value)
    return describe(provider, await _row(user_id, provider))


async def set_model(user_id: str, provider: AiProvider, model: str | None) -> dict[str, Any] | None:
    row = await _row(user_id, provider)
    if row is None:
        return None
    await db.useraicredential.update(where={"id": row.id}, data={"model": model})
    return describe(provider, await _row(user_id, provider))


async def delete_key(user_id: str, provider: AiProvider) -> dict[str, Any]:
    await db.useraicredential.delete_many(
        where={"userId": user_id, "provider": provider.value}
    )
    logger.info("AUDIT personal AI key cleared: user=%s provider=%s", user_id, provider.value)
    return describe(provider, None)



def _probe(provider: AiProvider, value: str) -> tuple[bool, str | None]:
    probes = {
        AiProvider.OPENAI: managed_secrets._probe_openai,  # noqa: SLF001
        AiProvider.GEMINI: managed_secrets._probe_gemini,  # noqa: SLF001
        AiProvider.ANTHROPIC: managed_secrets._probe_anthropic,  # noqa: SLF001
    }
    try:
        ok, error = probes[provider](value)
    except Exception as exc:  # noqa: BLE001
        return False, f"Could not be tested: {type(exc).__name__}"
    return ok, error


async def test_key(user_id: str, provider: AiProvider) -> dict[str, Any] | None:
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
