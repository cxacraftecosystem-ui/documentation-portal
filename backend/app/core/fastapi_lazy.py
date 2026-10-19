"""Two FastAPI names for the services the queue worker runs, with FastAPI imported on first use.

Importing anything under ``fastapi`` runs ``fastapi/__init__``, which loads the whole framework and
Starlette: about 9 MB that app/worker.py would hold from start-up for one encoder and one exception
class it reaches only when a job runs (media_queue, records, artisan_merge, access). These hand back
exactly what FastAPI's own names do: the same encoder's result, and a real
``fastapi.HTTPException``, so routes, handlers and tests see no difference. Only the import moves.
``status`` is Starlette's constants module, which imports nothing else.
backend/tests/test_import_footprint.py pins the worker's import graph.
"""

from typing import Any

from starlette import status

__all__ = ["HTTPException", "jsonable_encoder", "status"]


def jsonable_encoder(obj: Any, *args: Any, **kwargs: Any) -> Any:
    from fastapi.encoders import jsonable_encoder as encode

    return encode(obj, *args, **kwargs)


def HTTPException(  # noqa: N802 - stands in for the class at its call sites: `raise HTTPException(...)`
    status_code: int, detail: Any = None, headers: dict[str, str] | None = None
) -> Exception:
    """A ``fastapi.HTTPException``, built on first use. For ``raise`` only: catch the real class."""
    from fastapi import HTTPException as FastAPIHTTPException

    return FastAPIHTTPException(status_code=status_code, detail=detail, headers=headers)
