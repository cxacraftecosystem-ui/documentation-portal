"""``GET /records/completeness-rules`` — the completeness rules file, for clients to cache.

The web bundle and the Android app each ship a copy of ``shared/record-completeness.json`` and judge
records with it offline; this route hands them the live copy so a newer rules version reaches them
without a release. The answer is the file's bytes and an ETag, and a client that already holds that
version gets a bodiless 304.
"""

from typing import Any

from fastapi import APIRouter, Depends, Request, Response

from app.core.deps import get_current_user
from app.services import completeness

router = APIRouter(prefix="/records", tags=["records"])


def _matches(header: str | None, etag: str) -> bool:
    if not header:
        return False
    tags = [tag.strip() for tag in header.split(",")]
    return "*" in tags or etag in tags or f"W/{etag}" in tags


@router.get("/completeness-rules")
async def completeness_rules(request: Request, _: Any = Depends(get_current_user)) -> Response:
    headers = {
        "ETag": completeness.RULES_ETAG,
        # Stored by the client and re-validated on every use: a 304 costs one round trip and no body.
        "Cache-Control": "private, no-cache",
    }
    if _matches(request.headers.get("if-none-match"), completeness.RULES_ETAG):
        return Response(status_code=304, headers=headers)
    return Response(content=completeness.RULES_BYTES, media_type="application/json", headers=headers)
