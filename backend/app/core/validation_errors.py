import logging
import re
from collections.abc import Iterable, Mapping
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

logger = logging.getLogger("app.validation")

VALIDATION_FAILED_CODE = "validation_failed"

LOCATION_PREFIXES = frozenset({"body", "query", "path", "header", "cookie"})

FIELD_LABELS: dict[str, str] = {
    "dos": "Do's",
    "donts": "Don'ts",
    "aadhaarNumber": "Aadhaar number",
    "aadhaarNotShared": "Aadhaar not shared",
    "pehchanCardNumber": "Pehchan card number",
    "pehchanCardAvailable": "Pehchan card",
    "pincode": "PIN code",
    "subjectLatitude": "Map pin latitude",
    "subjectLongitude": "Map pin longitude",
    "extraMetadata": "Extra details",
    "expectedUpdatedAt": "Last-seen version",
    "clientKey": "Client key",
    "email": "Email",
    "url": "Link",
    "id": "ID",
    "gi": "GI",
    "giStatus": "GI status",
    "otp": "OTP",
    "totp": "Authenticator code",
}

_SENTENCE_START = re.compile(r"^[A-Z0-9\"'(]")
_CAMEL_BREAK = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_PREFIXES = ("Value error, ", "Assertion failed, ")

_TYPE_SUFFIXES = ("_type", "_parsing")
_FORMAT_TYPES = frozenset(
    {
        "json_invalid",
        "url_parsing",
        "url_syntax_violation",
        "url_scheme",
        "uuid_parsing",
        "uuid_version",
        "string_pattern_mismatch",
        "date_from_datetime_inexact",
        "date_from_datetime_parsing",
        "datetime_from_date_parsing",
        "time_delta_parsing",
        "decimal_max_digits",
        "decimal_max_places",
        "decimal_whole_digits",
        "finite_number",
        "int_from_float",
        "timezone_naive",
        "timezone_aware",
    }
)


def humanise_field_name(name: str) -> str:
    if name in FIELD_LABELS:
        return FIELD_LABELS[name]
    words = [w for w in _CAMEL_BREAK.sub(" ", name.replace("_", " ")).split() if w]
    if not words:
        return ""
    lowered = [w if (w.isupper() and len(w) > 1) else w.lower() for w in words]
    if len(lowered) > 1 and lowered[-1] == "id":
        lowered = lowered[:-1]
    elif len(lowered) > 1 and lowered[-1] == "ids":
        lowered = lowered[:-1]
        lowered[-1] = lowered[-1] + "s"
    text = " ".join(lowered)
    return text[:1].upper() + text[1:]


def field_path(loc: Iterable[Any]) -> list[Any]:
    parts = list(loc)
    if parts and isinstance(parts[0], str) and parts[0] in LOCATION_PREFIXES:
        parts = parts[1:]
    return parts


def field_label(loc: Iterable[Any]) -> str:
    for part in reversed(field_path(loc)):
        if isinstance(part, str) and part:
            return humanise_field_name(part)
    return ""


def _sentence(text: str) -> str:
    text = text.strip()
    if not text:
        return text
    text = text[:1].upper() + text[1:]
    return text if text[-1] in ".!?" else text + "."


def _number(value: Any) -> str:
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _characters(count: Any) -> str:
    return "character" if count == 1 else "characters"


def _items(count: Any) -> str:
    return "item" if count == 1 else "items"


def human_message(error: Mapping[str, Any]) -> str:
    kind = str(error.get("type") or "")
    loc = tuple(error.get("loc") or ())
    raw = str(error.get("msg") or "")
    ctx = error.get("ctx") if isinstance(error.get("ctx"), Mapping) else {}
    label = field_label(loc)
    subject = label or "This value"

    if kind in ("value_error", "assertion_error") or kind.startswith("value_error"):
        message = raw
        for prefix in _PREFIXES:
            message = message.removeprefix(prefix)
        message = message.strip()
        if not message:
            return _sentence(f"{subject} is invalid")
        if _SENTENCE_START.match(message) or not label:
            return _sentence(message)
        return _sentence(f"{label}: {message}")
    if kind == "missing":
        return f"{label} is required." if label else "The request body is required."
    if kind == "string_too_short":
        minimum = ctx.get("min_length", 1)
        if not isinstance(minimum, int) or minimum <= 1:
            return f"{subject} can't be empty."
        return f"{subject} is too short (min {minimum} {_characters(minimum)})."
    if kind == "string_too_long":
        maximum = ctx.get("max_length")
        if maximum is None:
            return f"{subject} is too long."
        return f"{subject} is too long (max {maximum} {_characters(maximum)})."
    if kind == "too_short":
        minimum = ctx.get("min_length")
        if minimum is None:
            return f"{subject} has too few items."
        return f"{subject} needs at least {minimum} {_items(minimum)}."
    if kind == "too_long":
        maximum = ctx.get("max_length")
        if maximum is None:
            return f"{subject} has too many items."
        return f"{subject} can have at most {maximum} {_items(maximum)}."
    if kind == "extra_forbidden":
        return f"{subject} isn't a field this form accepts."
    if kind == "greater_than_equal" and "ge" in ctx:
        return f"{subject} must be at least {_number(ctx['ge'])}."
    if kind == "greater_than" and "gt" in ctx:
        return f"{subject} must be more than {_number(ctx['gt'])}."
    if kind == "less_than_equal" and "le" in ctx:
        return f"{subject} must be at most {_number(ctx['le'])}."
    if kind == "less_than" and "lt" in ctx:
        return f"{subject} must be less than {_number(ctx['lt'])}."
    if kind == "multiple_of" and "multiple_of" in ctx:
        return f"{subject} must be a multiple of {_number(ctx['multiple_of'])}."
    if kind in ("literal_error", "enum"):
        expected = ctx.get("expected")
        if expected:
            return f"{subject} must be one of {expected}."
        return f"{subject} isn't one of the allowed choices."
    if kind == "json_invalid" and not label:
        return "The request body isn't valid JSON."
    if kind == "dict_type" and not label:
        return "The request body must be a JSON object."
    if kind in _FORMAT_TYPES or kind.endswith(_TYPE_SUFFIXES):
        return f"{subject} has the wrong format."
    return f"{subject} is invalid."


def human_errors(errors: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for error in errors:
        loc = list(error.get("loc") or ())
        entries.append(
            {
                "type": str(error.get("type") or ""),
                "loc": loc,
                "field": ".".join(str(part) for part in field_path(loc)),
                "label": field_label(loc),
                "msg": human_message(error),
            }
        )
    return entries


def joined_message(entries: Iterable[Mapping[str, Any]]) -> str:
    return " ".join(dict.fromkeys(str(e["msg"]) for e in entries if e.get("msg")))


def validation_body(errors: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    entries = human_errors(errors)
    return {"detail": entries, "message": joined_message(entries), "code": VALIDATION_FAILED_CODE}


def route_template(request: Request) -> str:
    route = request.scope.get("route")
    path = getattr(route, "path_format", None) or getattr(route, "path", None)
    if not path:
        return "<unmatched>"
    path = str(path)
    concrete = str(request.scope.get("path") or "").rstrip("/").split("/")
    depth = path.rstrip("/").count("/")
    prefix = "/".join(concrete[: max(len(concrete) - depth, 1)])
    return path if path.startswith(prefix + "/") else prefix + path


def logged_problems(errors: Iterable[Mapping[str, Any]]) -> str:
    return ", ".join(
        f"{'.'.join(str(part) for part in (error.get('loc') or ())) or '<root>'} "
        f"{error.get('type') or 'unknown'}"
        for error in errors
    )


async def request_validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    errors = list(exc.errors())
    logger.warning(
        "validation failed: %s %s [%s]",
        request.method,
        route_template(request),
        logged_problems(errors),
    )
    return JSONResponse(status_code=422, content=validation_body(errors))


def install_validation_handler(app: FastAPI) -> None:
    app.add_exception_handler(RequestValidationError, request_validation_handler)
