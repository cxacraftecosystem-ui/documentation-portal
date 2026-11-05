from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import StreamingResponse

from app.core.deps import require_dataset_downloader
from app.services import naming
from app.services.data_explorer import report_cache
from app.services.datareport import pack

router = APIRouter(
    prefix="/data",
    tags=["data-browser"],
    dependencies=[Depends(require_dataset_downloader)],
)


@router.get("/report/pack", response_model=None)
async def data_report_pack(
    path: str = "",
    q: str | None = None,
    sheets: str | None = None,
    answers: str | None = None,
    rows: str | None = None,
    links: str | None = None,
    name: str | None = None,
    thumbnails: str | None = None,
    locale: str | None = None,
    extras: str | None = None,
    status_value: str | None = Query(None, alias="status"),
    current_user: Any = Depends(require_dataset_downloader),
) -> StreamingResponse:
    params = pack.PackParams(
        path=path,
        q=q,
        sheets=sheets,
        answers=answers,
        rows=rows,
        links=links,
        name=name,
        status=status_value,
        extra={k: v for k, v in (("thumbnails", thumbnails), ("locale", locale), ("extras", extras)) if v is not None},
    )
    try:
        prepared, body = await pack.response_parts(current_user, params)
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=report_cache.REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None
    return StreamingResponse(
        body,
        media_type=pack.ZIP_MIME,
        headers={
            "Content-Disposition": naming.content_disposition(prepared.zip_name),
            "Cache-Control": "no-store",
            "X-Report-Transcripts": str(len(prepared.transcripts)),
        },
    )
