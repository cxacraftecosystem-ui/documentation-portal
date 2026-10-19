"""Transport helpers shared by every adapter.

``requests.post`` is always looked up on the module at call time, so a test that replaces
``requests.post`` reaches every adapter without knowing this package exists.
"""

from __future__ import annotations

import logging
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import requests

from app.transcription.contract import (
    AudioInput,
    KeyRejected,
    OptionRejected,
    ProviderFailed,
    Throttled,
    TooLarge,
    Unreachable,
)

logger = logging.getLogger("app.transcription")

AUTH_STATUSES = frozenset({401, 403})
DEFER_STATUSES = frozenset({429, 503})
OPTION_REJECTED_STATUSES = frozenset({400, 422})


@contextmanager
def upload_body(audio: AudioInput) -> Iterator[Any]:
    """The audio as ``requests`` should be handed it: a fresh file handle per send, or the bytes.

    A fresh handle per ``with`` block matters for the one conservative retry each adapter makes: a
    handle the first POST already read to the end would upload nothing.
    """
    if audio.source_path is not None:
        with open(audio.source_path, "rb") as handle:
            yield handle
    else:
        yield audio.content or b""


def retry_after(response: Any) -> float | None:
    if response is None:
        return None
    try:
        header = response.headers.get("Retry-After")
        return float(header) if header else None
    except TypeError, ValueError, AttributeError:
        return None


def post(provider: str, url: str, **kwargs: Any) -> Any:
    """``requests.post`` with transport failures turned into :class:`Unreachable`."""
    try:
        return requests.post(url, **kwargs)
    except requests.HTTPError:
        raise
    except requests.RequestException as exc:
        raise Unreachable(provider, type(exc).__name__) from exc


def raise_for(provider: str, response: Any) -> None:
    """Map a provider's HTTP answer to the package's typed errors. 2xx returns quietly."""
    code = getattr(response, "status_code", None)
    if code is not None and code < 400:
        return
    if code in AUTH_STATUSES:
        raise KeyRejected(provider, code, response)
    if code in DEFER_STATUSES:
        raise Throttled(provider, code, response, retry_after(response))
    if code == 413:
        raise TooLarge(provider, code, response)
    if code in OPTION_REJECTED_STATUSES:
        raise OptionRejected(provider, code, response)
    raise ProviderFailed(provider, code, response)


def log_option_retry(provider: str, response: Any, what: str) -> None:
    logger.warning(
        "%s rejected the request options (HTTP %s: %s); retrying %s",
        provider,
        getattr(response, "status_code", None),
        str(getattr(response, "text", ""))[:200],
        what,
    )
