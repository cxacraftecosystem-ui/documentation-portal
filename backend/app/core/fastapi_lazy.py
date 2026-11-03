from typing import Any

from starlette import status

__all__ = ["HTTPException", "jsonable_encoder", "status"]


def jsonable_encoder(obj: Any, *args: Any, **kwargs: Any) -> Any:
    from fastapi.encoders import jsonable_encoder as encode

    return encode(obj, *args, **kwargs)


def HTTPException(  # noqa: N802
    status_code: int, detail: Any = None, headers: dict[str, str] | None = None
) -> Exception:
    from fastapi import HTTPException as FastAPIHTTPException

    return FastAPIHTTPException(status_code=status_code, detail=detail, headers=headers)
