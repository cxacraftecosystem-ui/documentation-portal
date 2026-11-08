from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import gc
import hashlib
import importlib
import json
import logging
import os
import time
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from fastapi import HTTPException, status
from fastapi.responses import FileResponse

from app.services.data_explorer import report_cache

logger = logging.getLogger(__name__)

REPORT = "report"
PACK = "pack"
KINDS = (REPORT, PACK)

QUEUED = "queued"
RUNNING = "running"
DONE = "done"
FAILED = "failed"

ENV_DIR = "REPORT_STORE_DIR"
WAIT_SECONDS = 55.0
POLL_SECONDS = 0.5
SEEN_EVERY_SECONDS = 5.0
ABANDON_SECONDS = 300.0
BUILD_TIMEOUT_SECONDS = 900.0
STALE_RUNNING_SECONDS = BUILD_TIMEOUT_SECONDS + 120.0
REUSE_SECONDS = float(report_cache.CACHE_TTL_SECONDS)
FAILED_SHOWN_SECONDS = 180.0
KEEP_SECONDS = 24 * 3600.0
SWEEP_EVERY_SECONDS = 60.0
IDLE_SECONDS = 1.0
DEPTH = report_cache.QUEUE_DEPTH

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
ZIP_MIME = "application/zip"
NOT_BUILT = "The report could not be built. Try again."
TOO_LONG = "The report took too long to build. Pick fewer sheets or a smaller folder."
NO_ACCESS = "Dataset download access required. Ask an admin to grant it."

ROOT: Path | None = None

_clock = time.time


def root() -> Path:
    if ROOT is not None:
        where = ROOT
    else:
        configured = str(os.environ.get(ENV_DIR, "")).strip()
        where = Path(configured).expanduser() if configured else Path.home() / ".cache" / "data-report-store"
    where.mkdir(parents=True, exist_ok=True, mode=0o700)
    return where


def _record_path(job_id: str) -> Path:
    return root() / f"{job_id}.json"


def _file_path(job_id: str) -> Path:
    return root() / f"{job_id}.bin"


def _seen_path(job_id: str) -> Path:
    return root() / f"{job_id}.seen"


def _v(row: Any, name: str) -> Any:
    return row.get(name) if isinstance(row, dict) else getattr(row, name, None)


def job_id(kind: str, user_id: str, key: str, params: dict[str, Any]) -> str:
    text = json.dumps([kind, user_id, key, params], sort_keys=True, default=str, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def read(job: str) -> dict[str, Any] | None:
    try:
        found = json.loads(_record_path(job).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return found if isinstance(found, dict) else None


def _temp_beside(path: Path) -> Path:
    return path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")


def _write(job: str, record: dict[str, Any]) -> None:
    path = _record_path(job)
    tmp = _temp_beside(path)
    tmp.write_text(json.dumps(record, default=str), encoding="utf-8")
    os.replace(tmp, path)


def _create(job: str, record: dict[str, Any]) -> bool:
    path = _record_path(job)
    tmp = _temp_beside(path)
    tmp.write_text(json.dumps(record, default=str), encoding="utf-8")
    try:
        os.link(tmp, path)
        return True
    except FileExistsError:
        return False
    except OSError:
        try:
            handle = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            return False
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            out.write(json.dumps(record, default=str))
        return True
    finally:
        with contextlib.suppress(OSError):
            tmp.unlink()


def clear(job: str) -> None:
    for path in (_record_path(job), _file_path(job), _seen_path(job)):
        with contextlib.suppress(OSError):
            path.unlink()


def _touch(job: str) -> None:
    with contextlib.suppress(OSError):
        _seen_path(job).touch()


def _last_seen(job: str, record: dict[str, Any]) -> float:
    seen = float(record.get("createdAt") or 0)
    with contextlib.suppress(OSError):
        seen = max(seen, _seen_path(job).stat().st_mtime)
    return seen


def records() -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    for path in root().glob("*.json"):
        if path.name.startswith("."):
            continue
        record = read(path.stem)
        if record is not None and record.get("id") == path.stem:
            found.append(record)
    return found


def pending() -> int:
    return sum(1 for record in records() if record.get("state") in (QUEUED, RUNNING))


def _stale(record: dict[str, Any], now: float) -> bool:
    state = record.get("state")
    if state == DONE:
        return now - float(record.get("finishedAt") or 0) > REUSE_SECONDS or not _file_path(str(record["id"])).is_file()
    if state == FAILED:
        return bool(record.get("reported")) or now - float(record.get("finishedAt") or 0) > FAILED_SHOWN_SECONDS
    return False


def _running_dead(record: dict[str, Any], now: float) -> bool:
    return record.get("state") == RUNNING and now - float(record.get("startedAt") or 0) > STALE_RUNNING_SECONDS


def options_params(options: Any) -> dict[str, Any]:
    if options is None:
        return {}
    raw = dataclasses.asdict(options) if dataclasses.is_dataclass(options) else dict(vars(options))
    return {key: list(value) if isinstance(value, tuple) else value for key, value in raw.items()}


def report_params(
    path: str,
    options: Any,
    *,
    name: str | None = None,
    sheet: str | None = None,
    filters: dict[str, str] | None = None,
) -> dict[str, Any]:
    out = {
        "path": path or "",
        "options": options_params(options),
        "name": name,
        "sheet": (sheet or "").strip() or None,
    }
    if filters:
        out["filters"] = dict(filters)
    return out


def _explorer_filter(params: dict[str, Any] | None) -> Any:
    from app.services.data_explorer import filters as explorer_filters

    raw = (params or {}).get("filters")
    return explorer_filters.parse(raw) if isinstance(raw, dict) and raw else None


def _browser() -> Any:
    return importlib.import_module("app.api.routes.data_browser")


def _db(browser: Any) -> Any:
    found = getattr(browser, "db", None)
    if found is not None:
        return found
    from app.core.db import db

    return db


async def _scope(browser: Any, user: Any, status_filter: str | None) -> Any:
    from app.services.datareport import portal as portals

    if portals.current().status_filter:
        normalise = getattr(browser, "data_status", None)
        wanted = normalise(status_filter) if callable(normalise) else status_filter
        return await browser._scope_for(user, wanted)
    return await browser._scope_for(user)


async def scope_key(user: Any, path: str, status_filter: str | None, flt: Any = None) -> str:
    from app.services import transcript_portal
    from app.services.data_explorer import filters as explorer_filters

    browser = _browser()
    scope = explorer_filters.narrow(await _scope(browser, user, status_filter), flt)
    norm = browser._norm(path)
    sees_spend = bool(transcript_portal.may_see_spend(user))
    version = await report_cache.data_version(_db(browser))
    return str(browser._report_key(norm, scope, sees_spend, version))


def _refusal(record: dict[str, Any]) -> HTTPException:
    error = record.get("error") or {}
    code = int(error.get("status") or status.HTTP_500_INTERNAL_SERVER_ERROR)
    headers = error.get("headers") or None
    return HTTPException(status_code=code, detail=error.get("detail") or NOT_BUILT, headers=headers)


def _serve(record: dict[str, Any]) -> FileResponse:
    from app.services import naming

    job = str(record["id"])
    headers = {
        "Content-Disposition": naming.content_disposition(str(record.get("fileName") or "Report.xlsx")),
        "Cache-Control": "no-store",
        **{str(k): str(v) for k, v in (record.get("headers") or {}).items()},
    }
    return FileResponse(_file_path(job), media_type=str(record.get("mime") or XLSX_MIME), headers=headers)


async def enqueue(user: Any, kind: str, params: dict[str, Any], *, status_filter: str | None = None) -> str:
    if kind not in KINDS:
        raise ValueError(kind)
    user_id = str(_v(user, "id") or "")
    key = await scope_key(user, str(params.get("path") or ""), status_filter, _explorer_filter(params))
    job = job_id(kind, user_id, key, {**params, "status": status_filter})
    now = _clock()
    found = read(job)
    if found is not None and (_stale(found, now) or _running_dead(found, now)):
        clear(job)
        found = None
    if found is None:
        if pending() >= DEPTH:
            raise report_cache.ReportBusy()
        record = {
            "id": job,
            "kind": kind,
            "userId": user_id,
            "params": params,
            "status": status_filter,
            "state": QUEUED,
            "createdAt": now,
        }
        _create(job, record)
    _touch(job)
    return job


async def wait_for(job: str, user: Any, *, wait: float | None = None, poll: float | None = None) -> FileResponse:
    user_id = str(_v(user, "id") or "")
    limit = WAIT_SECONDS if wait is None else wait
    step = POLL_SECONDS if poll is None else poll
    started = time.monotonic()
    touched = started
    while True:
        record = read(job)
        if record is None or str(record.get("userId") or "") != user_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="This report is not available.")
        state = record.get("state")
        if state == DONE and _file_path(job).is_file():
            return _serve(record)
        if state == FAILED:
            if not record.get("reported"):
                _write(job, {**record, "reported": True})
            raise _refusal(record)
        if _running_dead(record, _clock()):
            clear(job)
            raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=NOT_BUILT)
        now = time.monotonic()
        if now - started >= limit:
            raise report_cache.ReportBusy()
        if now - touched >= SEEN_EVERY_SECONDS:
            _touch(job)
            touched = now
        await asyncio.sleep(step)


async def fetch(
    user: Any,
    kind: str,
    params: dict[str, Any],
    *,
    status_filter: str | None = None,
    wait: float | None = None,
) -> FileResponse:
    job = await enqueue(user, kind, params, status_filter=status_filter)
    return await wait_for(job, user, wait=wait)


async def load_user(user_id: str) -> Any:
    from app.core.db import db

    return await db.user.find_unique(where={"id": user_id})


def may_download(user: Any) -> bool:
    deps = importlib.import_module("app.core.deps")
    check = getattr(deps, "can_download_dataset", None)
    return bool(user is not None and callable(check) and check(user))


def _picked(raw: dict[str, Any]) -> Any:
    from app.services.data_explorer import report_options

    fields = {f.name for f in dataclasses.fields(report_options.ReportOptions)}
    values = {k: tuple(v) if isinstance(v, list) else v for k, v in (raw or {}).items() if k in fields}
    return report_options.ReportOptions(**values)


async def _build_report_file(record: dict[str, Any], user: Any, into: Path) -> tuple[str, str, dict[str, str]]:
    from app.services.datareport import build

    params = record.get("params") or {}
    chosen = build.from_parsed(_picked(params.get("options") or {}), name=params.get("name"))
    sheet = params.get("sheet")
    if sheet and chosen.sheets is None:
        chosen.sheets = [str(sheet)]
    built = await build.build_report(
        user, str(params.get("path") or ""), chosen, status=record.get("status"), flt=_explorer_filter(params)
    )
    for hook in built.ctx.delivered:
        await hook()
    payload, name, mime = build.deliverable(built)
    del built
    await asyncio.to_thread(into.write_bytes, payload)
    return name, mime, {}


async def _build_pack_file(record: dict[str, Any], user: Any, into: Path) -> tuple[str, str, dict[str, str]]:
    from app.services.datareport import pack

    raw = dict(record.get("params") or {})
    fields = {f.name for f in dataclasses.fields(pack.PackParams)}
    params = pack.PackParams(**{k: v for k, v in raw.items() if k in fields})
    prepared, body = await pack.response_parts(user, params)
    with into.open("wb") as out:
        async for chunk in body:
            await asyncio.to_thread(out.write, chunk)
    return prepared.zip_name, pack.ZIP_MIME, {"X-Report-Transcripts": str(len(prepared.transcripts))}


BUILDERS: dict[str, Callable[[dict[str, Any], Any, Path], Awaitable[tuple[str, str, dict[str, str]]]]] = {
    REPORT: _build_report_file,
    PACK: _build_pack_file,
}


def _trim_heap() -> None:
    gc.collect()
    with contextlib.suppress(Exception):
        trim = getattr(_browser(), "_trim_heap", None)
        if callable(trim):
            trim()


def _failed(record: dict[str, Any], code: int, detail: Any, headers: dict[str, str] | None = None) -> dict[str, Any]:
    return {
        **record,
        "state": FAILED,
        "finishedAt": _clock(),
        "error": {"status": code, "detail": detail, "headers": headers or None},
    }


async def run_job(record: dict[str, Any], *, timeout: float | None = None) -> dict[str, Any]:
    job = str(record["id"])
    limit = BUILD_TIMEOUT_SECONDS if timeout is None else timeout
    into = _temp_beside(_file_path(job))
    try:
        user = await load_user(str(record.get("userId") or ""))
        if not may_download(user):
            finished = _failed(record, status.HTTP_403_FORBIDDEN, NO_ACCESS)
            _write(job, finished)
            return finished
        running = {**record, "state": RUNNING, "startedAt": _clock(), "pid": os.getpid()}
        _write(job, running)
        builder = BUILDERS[str(record.get("kind"))]
        try:
            name, mime, headers = await asyncio.wait_for(builder(running, user, into), limit)
        except TimeoutError:
            finished = _failed(running, status.HTTP_504_GATEWAY_TIMEOUT, TOO_LONG)
        except report_cache.ReportBusy as busy:
            finished = _failed(
                running,
                status.HTTP_503_SERVICE_UNAVAILABLE,
                report_cache.REPORT_BUSY,
                {"Retry-After": str(busy.retry_after)},
            )
        except HTTPException as refused:
            finished = _failed(running, refused.status_code, refused.detail, dict(refused.headers or {}) or None)
        except Exception:
            logger.exception("report job %s failed", job)
            finished = _failed(running, status.HTTP_500_INTERNAL_SERVER_ERROR, NOT_BUILT)
        else:
            os.replace(into, _file_path(job))
            finished = {
                **running,
                "state": DONE,
                "finishedAt": _clock(),
                "fileName": name,
                "mime": mime,
                "headers": headers,
                "size": _file_path(job).stat().st_size,
            }
        _write(job, finished)
        return finished
    finally:
        with contextlib.suppress(OSError):
            into.unlink()
        _trim_heap()


def next_job(now: float | None = None) -> dict[str, Any] | None:
    now = _clock() if now is None else now
    queued = sorted(
        (r for r in records() if r.get("state") == QUEUED),
        key=lambda r: float(r.get("createdAt") or 0),
    )
    for record in queued:
        job = str(record["id"])
        if now - _last_seen(job, record) > ABANDON_SECONDS:
            logger.info("report job %s dropped: nobody asked for it in %.0fs", job, ABANDON_SECONDS)
            clear(job)
            continue
        return record
    return None


def recover() -> int:
    lost = 0
    for record in records():
        if record.get("state") == RUNNING:
            _write(str(record["id"]), _failed(record, status.HTTP_500_INTERNAL_SERVER_ERROR, NOT_BUILT))
            lost += 1
    return lost


def sweep(now: float | None = None) -> int:
    now = _clock() if now is None else now
    removed = 0
    for path in root().iterdir():
        try:
            age = now - path.stat().st_mtime
        except OSError:
            continue
        temp = path.name.startswith(".") and path.name.endswith(".tmp")
        if age > KEEP_SECONDS or (temp and age > STALE_RUNNING_SECONDS):
            with contextlib.suppress(OSError):
                path.unlink()
                removed += 1
    for record in records():
        if record.get("state") == FAILED and now - float(record.get("finishedAt") or 0) > KEEP_SECONDS:
            clear(str(record["id"]))
    return removed


async def run_lane(
    stop: asyncio.Event,
    *,
    idle_seconds: float = IDLE_SECONDS,
    between: Callable[[], Awaitable[Any]] | None = None,
) -> None:
    with contextlib.suppress(Exception):
        if recover():
            logger.warning("report lane: marked builds cut off by the last restart as failed")
    last_sweep = float("-inf")
    while not stop.is_set():
        busy = False
        try:
            if time.monotonic() - last_sweep >= SWEEP_EVERY_SECONDS:
                last_sweep = time.monotonic()
                await asyncio.to_thread(sweep)
            record = next_job()
            if record is not None:
                await run_job(record)
                busy = True
            elif between is not None:
                await between()
        except Exception:
            logger.exception("Report lane pass failed; backing off")
        if not busy:
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(stop.wait(), timeout=idle_seconds)
