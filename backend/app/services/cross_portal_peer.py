from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from fastapi import Header, HTTPException, Request, status
from fastapi.responses import Response

from app.core.config import get_settings
from app.services import cross_portal


def _admit(
    request: Request,
    authorization: str | None,
    nonce_header: str | None,
    ready: Callable[[Any], bool],
) -> Any:
    settings = get_settings()
    if not ready(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    if not cross_portal.bearer_matches(settings.cross_portal_token, authorization):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    nonce = (nonce_header or "").strip()
    if len(nonce) < cross_portal.MIN_NONCE_LENGTH or len(nonce) > 128:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authorised.")
    request.state.cross_portal_nonce = nonce
    return settings


async def require_peer(
    request: Request,
    authorization: str | None = Header(default=None),
    x_cross_portal_nonce: str | None = Header(default=None),
) -> Any:
    return _admit(request, authorization, x_cross_portal_nonce, cross_portal.active)


async def require_search_peer(
    request: Request,
    authorization: str | None = Header(default=None),
    x_cross_portal_nonce: str | None = Header(default=None),
) -> Any:
    return _admit(request, authorization, x_cross_portal_nonce, cross_portal.search_active)


def requested_by(request: Request) -> str | None:
    return (request.headers.get(cross_portal.REQUESTED_BY_HEADER) or "").strip()[:64] or None


def signed(settings: Any, request: Request, payload: Any, status_code: int = 200) -> Response:
    body = json.dumps(payload, separators=(",", ":"), default=str).encode("utf-8")
    signature = cross_portal.sign(
        settings.cross_portal_token,
        body,
        method=request.method,
        path=request.url.path,
        nonce=getattr(request.state, "cross_portal_nonce", ""),
    )
    return Response(
        content=body,
        status_code=status_code,
        media_type="application/json",
        headers={cross_portal.SIGNATURE_HEADER: signature},
    )
