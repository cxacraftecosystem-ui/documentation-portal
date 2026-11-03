import hashlib
import json
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any, Mapping

KEY_PREFIX = "fr"

KEY_FORMAT_VERSION = "v1"

PUBLIC_AUDIENCE = "all"

FAMILY_RECORDS = "records"

FAMILIES: frozenset[str] = frozenset(
    {
        "artisan",
        "craft",
        "process",
        "product",
        "tool",
        "workshop",
        "media",
        "questionnaire",
        "task",
        "user",
        "reference",
        "release",
        FAMILY_RECORDS,
    }
)

NAMESPACE_FAMILY: dict[str, str] = {
    "artisan.list": "artisan",
    "craft.list": "craft",
    "process.list": "process",
    "product.list": "product",
    "tool.list": "tool",
    "workshop.list": "workshop",
    "media.list": "media",
    "questionnaire.interviews": "questionnaire",
    "questionnaire.questions": "questionnaire",
    "task.list": "task",
    "task.batches": "task",
    "user.directory": "user",
    "reference.address": "reference",
    "workshop.assignments": "workshop",
    "app.release.latest": "release",
    "dashboard.stats": FAMILY_RECORDS,
    "search.results": FAMILY_RECORDS,
    "data.tree": FAMILY_RECORDS,
    "data.browser": FAMILY_RECORDS,
    "review.pending": FAMILY_RECORDS,
}

NAMESPACE_TTL_SECONDS: dict[str, float] = {
    "media.list": 20.0,
    "review.pending": 15.0,
    "search.results": 20.0,
    "dashboard.stats": 60.0,
    "data.tree": 60.0,
    "data.browser": 60.0,
    "user.directory": 120.0,
    "reference.address": 300.0,
    "app.release.latest": 300.0,
}

RECORD_FAMILIES: dict[str, tuple[str, ...]] = {
    "artisan": ("artisan", FAMILY_RECORDS),
    "craft": ("craft", "artisan", FAMILY_RECORDS),
    "process": ("process", FAMILY_RECORDS),
    "product": ("product", FAMILY_RECORDS),
    "tool": ("tool", FAMILY_RECORDS),
    "workshop": ("workshop", FAMILY_RECORDS),
    "media": ("media", FAMILY_RECORDS),
    "questionnaire": ("questionnaire", FAMILY_RECORDS),
    "interview": ("questionnaire", FAMILY_RECORDS),
    "task": ("task",),
    "user": ("user",),
    "workshopAssignment": ("workshop",),
    "release": ("release",),
}


def key_family(namespace: str) -> str:
    try:
        return NAMESPACE_FAMILY[namespace]
    except KeyError:
        raise KeyError(
            f"Unknown cache namespace {namespace!r}. Register it in "
            "app/scale/keys.py::NAMESPACE_FAMILY together with the family that invalidates it."
        ) from None


def ttl_for(namespace: str, default_seconds: float) -> float:
    return NAMESPACE_TTL_SECONDS.get(namespace, default_seconds)


def families_for_record(record_type: str) -> tuple[str, ...]:
    return RECORD_FAMILIES.get(record_type, ())


def audience_for(user: Any) -> str:
    identifier = user.get("id") if isinstance(user, Mapping) else getattr(user, "id", None)
    return f"u:{identifier}" if identifier else PUBLIC_AUDIENCE


def role_audience(user: Any) -> str:
    role = user.get("role") if isinstance(user, Mapping) else getattr(user, "role", None)
    return f"r:{getattr(role, 'value', role)}"


def _canonical(value: Any) -> Any:
    if value is None or isinstance(value, (str, bool, int, float)):
        return value
    if isinstance(value, Enum):
        return _canonical(value.value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (list, tuple)):
        return [_canonical(item) for item in value]
    if isinstance(value, Mapping):
        return {str(k): _canonical(v) for k, v in sorted(value.items(), key=lambda kv: str(kv[0]))}
    raise TypeError(f"{type(value).__name__} is not a cacheable query parameter")


def fingerprint(params: Mapping[str, Any]) -> str:
    canonical = {
        key: _canonical(value) for key, value in sorted(params.items()) if value is not None
    }
    encoded = json.dumps(canonical, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha1(encoded.encode("utf-8")).hexdigest()[:16]


def entry_key(namespace: str, generation: int, audience: str, digest: str) -> str:
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:{namespace}:{generation}:{audience}:{digest}"


def namespace_prefix(namespace: str) -> str:
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:{namespace}:"


def generation_key(family: str) -> str:
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:gen:{family}"


def rate_limit_key(identity: str) -> str:
    return f"{KEY_PREFIX}:{KEY_FORMAT_VERSION}:rl:{identity}"


def namespaces_in_family(family: str) -> tuple[str, ...]:
    return tuple(name for name, owner in NAMESPACE_FAMILY.items() if owner == family)
