from __future__ import annotations

import logging
from collections import OrderedDict
from typing import Any

import httpx

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

DIMENSIONS = 768

QUERY_TIMEOUT_SECONDS = 1.5
BATCH_TIMEOUT_SECONDS = 30.0

GEMINI_DEFAULT_MODEL = "gemini-embedding-001"

_column_present: bool | None = None
_query_cache: OrderedDict[str, list[float]] = OrderedDict()
_QUERY_CACHE_SIZE = 256


class EmbeddingError(RuntimeError):
    pass


def _provider(settings: Any) -> str:
    return (getattr(settings, "search_embedding_provider", "openai") or "openai").strip().lower()


def _key(settings: Any) -> str | None:
    if _provider(settings) == "gemini":
        keys = list(getattr(settings, "gemini_api_keys", []) or [])
        return keys[0] if keys else None
    return (getattr(settings, "openai_api_key", None) or "").strip() or None


def ai_master_on() -> bool:
    try:
        from app.ai_features.settings import get_ai_settings

        return bool(get_ai_settings().enabled)
    except Exception:
        logger.warning("search embeddings: AI settings unreadable; text mode", exc_info=True)
        return False


def configured(settings: Any | None = None) -> bool:
    settings = settings or get_settings()
    if not getattr(settings, "search_embeddings_enabled", False) or not _key(settings):
        return False
    return ai_master_on()


async def column_present() -> bool:
    global _column_present
    if _column_present is None:
        rows = await db.query_raw(
            """SELECT 1 AS present FROM information_schema.columns
                WHERE table_name = 'SearchDocument' AND column_name = 'embedding'"""
        )
        _column_present = bool(rows)
    return _column_present


def reset_cache() -> None:
    global _column_present
    _column_present = None
    _query_cache.clear()


async def available(settings: Any | None = None) -> bool:
    settings = settings or get_settings()
    if not configured(settings):
        return False
    try:
        return await column_present()
    except Exception:
        logger.warning("search embeddings: column probe failed", exc_info=True)
        return False


async def _openai(settings: Any, texts: list[str], timeout: float) -> list[list[float]]:
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            "https://api.openai.com/v1/embeddings",
            headers={"Authorization": f"Bearer {_key(settings)}"},
            json={
                "model": settings.search_embedding_model,
                "input": texts,
                "dimensions": DIMENSIONS,
            },
        )
    if response.status_code != 200:
        raise EmbeddingError(f"openai embeddings answered {response.status_code}")
    data = sorted(response.json().get("data") or [], key=lambda item: item.get("index", 0))
    return [list(map(float, item["embedding"])) for item in data]


async def _gemini(settings: Any, texts: list[str], timeout: float) -> list[list[float]]:
    model = settings.search_embedding_model
    if not model or model.startswith("text-embedding-3"):
        model = GEMINI_DEFAULT_MODEL
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents",
            headers={"x-goog-api-key": _key(settings) or ""},
            json={
                "requests": [
                    {
                        "model": f"models/{model}",
                        "content": {"parts": [{"text": text}]},
                        "outputDimensionality": DIMENSIONS,
                    }
                    for text in texts
                ]
            },
        )
    if response.status_code != 200:
        raise EmbeddingError(f"gemini embeddings answered {response.status_code}")
    return [list(map(float, item["values"])) for item in response.json().get("embeddings") or []]


async def embed(
    texts: list[str], *, settings: Any | None = None, timeout: float = BATCH_TIMEOUT_SECONDS
) -> list[list[float]]:
    settings = settings or get_settings()
    if not texts:
        return []
    try:
        if _provider(settings) == "gemini":
            vectors = await _gemini(settings, texts, timeout)
        else:
            vectors = await _openai(settings, texts, timeout)
    except EmbeddingError:
        raise
    except Exception as exc:  # noqa: BLE001 - the class only; an httpx message carries the request
        raise EmbeddingError(type(exc).__name__) from None
    if len(vectors) != len(texts) or any(len(v) != DIMENSIONS for v in vectors):
        raise EmbeddingError("provider answered the wrong shape")
    return vectors


async def embed_query(q: str, *, settings: Any | None = None) -> list[float] | None:
    key = q.strip().lower()
    if key in _query_cache:
        _query_cache.move_to_end(key)
        return _query_cache[key]
    try:
        vectors = await embed([q], settings=settings, timeout=QUERY_TIMEOUT_SECONDS)
    except EmbeddingError as exc:
        logger.info("search embeddings: query fell back to text (%s)", exc)
        return None
    _query_cache[key] = vectors[0]
    while len(_query_cache) > _QUERY_CACHE_SIZE:
        _query_cache.popitem(last=False)
    return vectors[0]


def vector_literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{value:.7g}" for value in vector) + "]"
