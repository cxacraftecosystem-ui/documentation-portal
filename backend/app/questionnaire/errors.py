"""The package's errors, in the contract's ``{"detail": {"code", "message", ...}}`` shape.

Both portals serve the package through FastAPI, so an error is raised as FastAPI's
``HTTPException`` and each portal's routes pass it through unchanged. This is the ONE module of the
package that imports ``fastapi`` (``tests/test_questionnaire_parity.py``), and it imports it only
when an error is raised: the queue worker imports the package for the nightly session close and must
not load FastAPI for an exception it never raises (``tests/test_import_footprint.py``).
"""

from __future__ import annotations

from typing import Any, NoReturn


def http_error(status_code: int, detail: Any = None, headers: dict[str, str] | None = None) -> Exception:
    from fastapi import HTTPException

    return HTTPException(status_code=status_code, detail=detail, headers=headers)


def http_error_type() -> type[Exception]:
    """The exception class :func:`http_error` builds, for an ``except`` clause."""
    from fastapi import HTTPException

    return HTTPException


def raise_http(status_code: int, detail: Any = None, headers: dict[str, str] | None = None) -> NoReturn:
    raise http_error(status_code, detail, headers)


class QuestionnaireError(Exception):
    """A rule broken inside a pure helper (no HTTP in sight); routes turn it into :func:`http_error`
    through ``common.fail`` with the same ``code``."""

    def __init__(self, code: str, message: str | None = None, **extra: Any) -> None:
        super().__init__(message or code)
        self.code = code
        self.message = message
        self.extra = extra
