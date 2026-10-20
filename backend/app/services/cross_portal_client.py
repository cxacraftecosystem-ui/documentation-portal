"""One portal's calls to the other over the signed channel (C27, and search), and nothing else.

Both portals carry this module under the same name, byte for byte; change both. The designer
portal calls the Field Repository at FIELD_REPOSITORY_API_URL, and the Field Repository calls the
designer portal at DESIGNER_PORTAL_API_URL (``cross_portal.peer_api_url``).

ONE function, :func:`peer_call`, and it refuses to hand back a body the other server did not sign
FOR THIS REQUEST (``services/cross_portal.signature_matches``: the method, the peer path and a fresh
nonce sent with the call are in the signed material, so an answer captured from one call cannot be
passed off as the answer to another). It also names the account that asked
(``X-Cross-Portal-Requested-By``) for the other server's access log. Every failure — not configured,
unreachable, a timeout, a bad signature, a body that is not JSON — becomes :class:`PeerUnavailable`
with a sentence the person asking can read; the route turns it into a 503 and nothing on this side
is written.

``requests`` in a thread, with a short timeout: a designer is waiting with the artisan in front of
them, and a slow answer is no answer.
"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
from collections.abc import Callable
from typing import Any

import requests

from app.services import cross_portal

logger = logging.getLogger(__name__)

TIMEOUT_SECONDS = 10.0

#: Search waits less: a slow peer must never hold back this portal's own results.
SEARCH_TIMEOUT_SECONDS = 2.5
#: Opening one remote record.
RECORD_TIMEOUT_SECONDS = 5.0

UNAVAILABLE = "The Field Repository can't be reached right now. Try again, or fill the form yourself."
#: The Field Repository's sentence when it is the caller.
UNAVAILABLE_DESIGNER = "The designer portal can't be reached right now."


class PeerUnavailable(RuntimeError):
    """The documentation portal could not be asked, or its answer could not be trusted."""


def unavailable_sentence(settings: Any) -> str:
    """The sentence for this portal's callers: it names the OTHER portal."""
    if (getattr(settings, "field_repository_api_url", None) or "").strip():
        return UNAVAILABLE
    return UNAVAILABLE_DESIGNER


def _url(settings: Any, path: str) -> str:
    return f"{cross_portal.peer_api_url(settings)}{cross_portal.PEER_PREFIX}{path}"


def _call_sync(
    settings: Any,
    method: str,
    path: str,
    body: Any | None,
    params: dict[str, str] | None,
    requested_by: str | None,
    timeout: float = TIMEOUT_SECONDS,
) -> tuple[int, Any]:
    token = settings.cross_portal_token
    unavailable = unavailable_sentence(settings)
    nonce = secrets.token_hex(16)
    headers = {
        "Authorization": f"Bearer {token.strip()}",
        "Content-Type": "application/json",
        cross_portal.NONCE_HEADER: nonce,
    }
    if requested_by:
        headers[cross_portal.REQUESTED_BY_HEADER] = str(requested_by)[:64]
    try:
        response = requests.request(
            method,
            _url(settings, path),
            headers=headers,
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            params=params,
            timeout=timeout,
        )
    except requests.RequestException as exc:
        # The exception CLASS only: a requests message carries the prepared request, headers included.
        logger.warning("cross-portal %s %s failed: %s", method, path, type(exc).__name__)
        raise PeerUnavailable(unavailable) from None
    if response.status_code in (401, 403):
        logger.error("cross-portal %s %s refused our token (%s)", method, path, response.status_code)
        raise PeerUnavailable(unavailable)
    if not cross_portal.signature_matches(
        token,
        response.content,
        response.headers.get(cross_portal.SIGNATURE_HEADER),
        method=method,
        path=f"{cross_portal.PEER_PREFIX}{path}",
        nonce=nonce,
    ):
        logger.error("cross-portal %s %s: answer not signed by the peer (%s)", method, path, response.status_code)
        raise PeerUnavailable(unavailable)
    try:
        payload = json.loads(response.content.decode("utf-8") or "null")
    except ValueError:
        raise PeerUnavailable(unavailable) from None
    return response.status_code, payload


async def peer_call(
    settings: Any,
    method: str,
    path: str,
    *,
    body: Any | None = None,
    params: dict[str, str] | None = None,
    requested_by: str | None = None,
    timeout: float = TIMEOUT_SECONDS,
    search: bool = False,
    gate: Callable[[Any], bool] | None = None,
) -> tuple[int, Any]:
    """``(status, signed JSON body)`` from the other portal, or :class:`PeerUnavailable`.

    ``search=True`` gates on ``cross_portal.search_active`` instead of the identity switch. ``gate``
    is any other switch a call is behind: the coverage counts pass their own, which needs neither the
    identity flag nor the Aadhaar key, because no artisan data crosses on that call.
    """
    if gate is not None:
        ready = gate(settings)
    elif search:
        ready = cross_portal.search_active(settings)
    else:
        ready = cross_portal.active(settings, needs_url=True)
    if not ready:
        raise PeerUnavailable(unavailable_sentence(settings))
    return await asyncio.to_thread(
        _call_sync, settings, method, path, body, params, requested_by, timeout
    )
