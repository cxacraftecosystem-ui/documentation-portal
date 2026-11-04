from __future__ import annotations

from typing import Any, NoReturn


def http_error(status_code: int, detail: Any = None, headers: dict[str, str] | None = None) -> Exception:
    from fastapi import HTTPException

    return HTTPException(status_code=status_code, detail=detail, headers=headers)


def http_error_type() -> type[Exception]:
    from fastapi import HTTPException

    return HTTPException


def raise_http(status_code: int, detail: Any = None, headers: dict[str, str] | None = None) -> NoReturn:
    raise http_error(status_code, detail, headers)


class QuestionnaireError(Exception):
    def __init__(self, code: str, message: str | None = None, **extra: Any) -> None:
        super().__init__(message or code)
        self.code = code
        self.message = message
        self.extra = extra
