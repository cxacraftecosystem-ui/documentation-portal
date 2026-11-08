from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response

from app.core.deps import require_dataset_downloader
from app.services.data_explorer import filters as explorer_filters, report_cache, report_options
from app.services.datareport import jobs

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
    flt: explorer_filters.Filter = Depends(explorer_filters.from_request),
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    try:
        report_options.parse(sheets=sheets, answers=answers, rows=rows, links=links, q=q)
    except report_options.OptionsInvalid as bad:
        raise HTTPException(status_code=422, detail=str(bad)) from None
    params = {
        "path": path,
        "q": q,
        "sheets": sheets,
        "answers": answers,
        "rows": rows,
        "links": links,
        "name": name,
        "status": status_value,
        "extra": {k: v for k, v in (("thumbnails", thumbnails), ("locale", locale), ("extras", extras)) if v is not None},
    }
    chosen = explorer_filters.as_query(flt)
    if chosen:
        params["filters"] = chosen
    try:
        return await jobs.fetch(current_user, jobs.PACK, params, status_filter=status_value)
    except report_cache.ReportBusy as busy:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=report_cache.REPORT_BUSY,
            headers={"Retry-After": str(busy.retry_after)},
        ) from None
