from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any, NamedTuple

from app.core.fastapi_lazy import HTTPException, jsonable_encoder, status
from prisma import Json

from app.core.db import db
from app.core.user_fields import SECRET_USER_FIELDS
from app.services import aadhaar_fingerprint
from app.services.artisan_identity import mask_aadhaar
from app.services.concurrency import gather_reads
from app.services.measurement_provenance import MARKER_BODY_KEY, method_stamps
from app.services.text_format import title_case_fields

_SENSITIVE_KEYS = frozenset(SECRET_USER_FIELDS)

_IDENTITY_KEYS = ("aadhaarNumber", "pehchanCardNumber")

_MEDIA_URL_KEYS = ("url", "publicUrl", "objectKey", "cleanedObjectKey")

_MEDIA_MARKER = "objectKey"

ALL_MEDIA_URLS = None


def presigned_read_ttl() -> int | None:
    from app.core.config import get_settings

    try:
        settings = get_settings()
    except Exception:
        return None
    if not getattr(settings, "media_presigned_reads", True):
        return None
    ttl = int(getattr(settings, "media_presigned_read_ttl_seconds", 0) or 0)
    return ttl if ttl > 0 else None


def signed_media_url(object_key: Any, stored: Any, mime_type: Any, filename: Any) -> Any:
    ttl = presigned_read_ttl()
    if ttl is None or not object_key or not isinstance(stored, str) or not stored:
        return stored
    from app.services import s3
    from app.services.media_mime import signed_read_headers

    signed_mime, disposition = signed_read_headers(mime_type)
    try:
        return s3.presign_get_url(
            str(object_key),
            filename=str(filename or object_key),
            mime_type=signed_mime,
            expires_in=ttl,
            disposition=disposition,
        )
    except Exception:
        return stored


def _sign_media_urls(value: Any) -> Any:
    if isinstance(value, dict):
        if _MEDIA_MARKER in value and isinstance(value.get("url"), str) and value.get("url"):
            value["url"] = signed_media_url(
                value.get(_MEDIA_MARKER), value["url"], value.get("mimeType"), value.get("originalFilename")
            )
        for nested in value.values():
            _sign_media_urls(nested)
    elif isinstance(value, list):
        for item in value:
            _sign_media_urls(item)
    return value


class _Unset:
    def __repr__(self) -> str:  # pragma: no cover
        return "<unset>"


_UNSET = _Unset()


def derive_age(date_of_birth: Any, *, on: datetime | None = None) -> int | None:
    if not date_of_birth:
        return None
    if isinstance(date_of_birth, str):
        try:
            date_of_birth = datetime.fromisoformat(date_of_birth.replace("Z", "+00:00"))
        except ValueError:
            return None
    born = getattr(date_of_birth, "date", lambda: date_of_birth)()
    today = (on or datetime.now(UTC)).date()
    years = today.year - born.year - ((today.month, today.day) < (born.month, born.day))
    return years if 0 <= years <= 130 else None


def derive_experience_years(craft_start: Any, *, on: datetime | None = None) -> int | None:
    if not craft_start:
        return None
    if isinstance(craft_start, str):
        try:
            craft_start = datetime.fromisoformat(craft_start.replace("Z", "+00:00"))
        except ValueError:
            return None
    started = getattr(craft_start, "date", lambda: craft_start)()
    today = (on or datetime.now(UTC)).date()
    years = today.year - started.year - ((today.month, today.day) < (started.month, started.day))
    return years if 0 <= years <= 90 else None


def mask_identity_number(value: Any) -> Any:
    return mask_aadhaar(value)


def _redact_sensitive(
    value: Any,
    viewer_id: str | None,
    unmasked: bool,
    media_urls: set[str] | None = None,
) -> Any:
    if isinstance(value, dict):
        for key in _SENSITIVE_KEYS:
            value.pop(key, None)
        if (
            aadhaar_fingerprint.FINGERPRINT_FIELD in value
            or aadhaar_fingerprint.LAST4_FIELD in value
        ) and not isinstance(
            value.get(aadhaar_fingerprint.LAST4_FIELD, value.get(aadhaar_fingerprint.FINGERPRINT_FIELD)),
            (dict, list),
        ):
            aadhaar_fingerprint.public_view(value)
        history = value.get(aadhaar_fingerprint.NUMBER_FIELD)
        if isinstance(history, dict):
            view = aadhaar_fingerprint.ledger_view(history)
            value[aadhaar_fingerprint.NUMBER_FIELD] = view if view is not None else history
        if not unmasked and not (viewer_id and value.get("createdById") == viewer_id):
            for key in _IDENTITY_KEYS:
                if key in value and not isinstance(value[key], (dict, list)):
                    value[key] = mask_identity_number(value[key])
        if media_urls is not None and _MEDIA_MARKER in value:
            if value.get("uploadedById") not in media_urls:
                for key in _MEDIA_URL_KEYS:
                    value.pop(key, None)
        for nested in value.values():
            _redact_sensitive(nested, viewer_id, unmasked, media_urls)
    elif isinstance(value, list):
        for item in value:
            _redact_sensitive(item, viewer_id, unmasked, media_urls)
    return value


def public_encode(obj: Any, viewer: Any = None, *, media_urls: Any = _UNSET) -> Any:
    from app.core.roles import can_download_dataset, get_value, has_rank

    encoded = jsonable_encoder(obj)
    if viewer is None:
        allowed: set[str] | None = set() if media_urls is _UNSET else media_urls
        return _sign_media_urls(_redact_sensitive(encoded, viewer_id=None, unmasked=False, media_urls=allowed))

    viewer_id = get_value(viewer, "id")
    if media_urls is _UNSET:
        if has_rank(viewer, "PROFESSOR") or can_download_dataset(viewer):
            allowed = ALL_MEDIA_URLS
        else:
            allowed = {viewer_id} if viewer_id else set()
    else:
        allowed = media_urls
    return _sign_media_urls(
        _redact_sensitive(
            encoded,
            viewer_id=viewer_id,
            unmasked=has_rank(viewer, "PROFESSOR"),
            media_urls=allowed,
        )
    )


async def media_url_owners(viewer: Any) -> set[str] | None:
    from app.core.db import db
    from app.core.roles import can_download_dataset, get_value, has_rank

    if has_rank(viewer, "PROFESSOR") or can_download_dataset(viewer):
        return ALL_MEDIA_URLS
    viewer_id = get_value(viewer, "id")
    if not viewer_id:
        return set()
    grants = await db.dataaccessgrant.find_many(
        where={"granteeId": viewer_id, "status": "GRANTED"}
    )
    return {viewer_id, *(grant.ownerId for grant in grants)}


def to_json(value: Any) -> Any:
    if isinstance(value, (dict, list)):
        return Json(value)
    return value


def jsonify_metadata(data: dict[str, Any], *fields: str) -> dict[str, Any]:
    keys = fields or ("extraMetadata", "measurementAnalysis", "result")
    for key in keys:
        if key in data and isinstance(data[key], (dict, list)):
            data[key] = Json(data[key])
    return data

CLEARABLE_KEYS = frozenset(
    {
        "workshopId",
        "craftId",
        "artisanId",
        "productId",
        "toolId",
        "processId",
        "locationId",
        "questionnaireInterviewId",
        "aadhaarNumber",
        "pehchanCardNumber",
    }
)


TITLE_CASE_FIELDS = frozenset(
    {
        "name",
        "craftName",
        "artisanName",
        "productName",
        "toolkitName",
        "englishName",
        "title",
        "place",
        "placeName",
        "village",
        "district",
        "state",
    }
)


def clean_data(
    data: dict[str, Any],
    *,
    title_case: bool = True,
    clearable: Sequence[str] | frozenset[str] = (),
) -> dict[str, Any]:
    allowed = CLEARABLE_KEYS | frozenset(clearable) if clearable else CLEARABLE_KEYS
    cleaned = {key: value for key, value in data.items() if value is not None or key in allowed}
    return title_case_fields(cleaned, TITLE_CASE_FIELDS) if title_case else cleaned


def decimal_to_string(data: dict[str, Any]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, Decimal):
            converted[key] = str(value)
        elif isinstance(value, dict):
            converted[key] = decimal_to_string(value)
        else:
            converted[key] = value
    return converted


_STATED_ADDRESS_FROM_META: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("district", ("district",)),
    ("village", ("village",)),
    ("subjectLatitude", ("subjectLatitude", "artisanLatitude")),
    ("subjectLongitude", ("subjectLongitude", "artisanLongitude")),
)


def lift_stated_address(location_data: dict[str, Any]) -> dict[str, Any]:
    meta = location_data.get("extraMetadata")
    if not isinstance(meta, dict):
        return location_data
    for column, keys in _STATED_ADDRESS_FROM_META:
        if location_data.get(column) not in (None, ""):
            continue
        for key in keys:
            value = meta.get(key)
            if value not in (None, ""):
                location_data[column] = value
                break
    return location_data


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        location_data = lift_stated_address(location_data)
        created = await db.location.create(data=jsonify_metadata(clean_data(location_data)))
        data["locationId"] = created.id
    return data


_UNSEARCHABLE = {c: None for c in range(32) if c not in (9, 10, 13)}


def contains(value: str) -> dict[str, Any]:
    return {"contains": value.translate(_UNSEARCHABLE), "mode": "insensitive"}


def plain(value: str | None) -> str:
    return (value or "").translate(_UNSEARCHABLE)



async def viewable_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    return {}


async def owned_or_granted_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    from app.core.roles import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return {}
    uid = get_value(user, "id")
    owner_relation = owner_field[:-2] if owner_field.endswith("Id") else owner_field
    return {
        "OR": [
            {owner_field: uid},
            {
                owner_relation: {
                    "is": {"dataAccessAsOwner": {"some": {"granteeId": uid, "status": "GRANTED"}}}
                }
            },
        ]
    }


async def own_rows_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    from app.core.roles import get_value

    return {owner_field: get_value(user, "id")}


def apply_status_policy_create(user: Any, data: dict[str, Any]) -> dict[str, Any]:
    from app.core.roles import has_rank

    if has_rank(user, "PROFESSOR"):
        data.setdefault("status", "APPROVED")
    else:
        data["status"] = "PENDING"
    return data


async def apply_status_policy_update(user: Any, record: Any, data: dict[str, Any]) -> dict[str, Any]:
    from app.core.roles import can_review_record, enum_or_raw, get_value, has_rank

    if "status" not in data:
        return data
    new_status = str(enum_or_raw(data["status"]))
    current = str(enum_or_raw(get_value(record, "status")))
    if new_status != current and has_rank(user, "PROFESSOR"):
        creator_id = get_value(record, "createdById")
        if creator_id is not None and creator_id == get_value(user, "id"):
            return data
        creator = await db.user.find_unique(where={"id": creator_id}) if creator_id else None
        if can_review_record(user, get_value(creator, "role") if creator else None):
            return data
    data.pop("status", None)
    return data


def add_date_range(where: dict[str, Any], field: str, date_from: datetime | None, date_to: datetime | None) -> None:
    range_filter: dict[str, Any] = {}
    if date_from:
        range_filter["gte"] = date_from
    if date_to:
        range_filter["lte"] = date_to
    if range_filter:
        where[field] = range_filter


async def require_record(delegate: Any, record_id: str) -> Any:
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record



class Relation(NamedTuple):
    field: str
    model: str
    key: str
    many: bool = False
    include: dict[str, Any] | None = None
    where: dict[str, Any] | None = None


def include_of(relations: Sequence[Relation]) -> dict[str, Any]:
    return {rel.field: _include_arg(rel) for rel in relations}


def _include_arg(rel: Relation) -> Any:
    arg: dict[str, Any] = {}
    if rel.include:
        arg["include"] = rel.include
    if rel.where and rel.many:
        arg["where"] = rel.where
    return arg or True


async def hydrate_relations(rows: Sequence[Any], relations: Sequence[Relation]) -> None:
    if not rows or not relations:
        return

    planned: list[tuple[Relation, Any]] = []
    for rel in relations:
        if rel.many:
            ids = {row.id for row in rows}
        else:
            ids = {getattr(row, rel.key, None) for row in rows} - {None}
        if not ids:
            for row in rows:
                setattr(row, rel.field, [] if rel.many else None)
            continue
        args: dict[str, Any] = {"where": {(rel.key if rel.many else "id"): {"in": sorted(ids)}}}
        if rel.many and rel.where:
            args["where"] = {"AND": [args["where"], rel.where]}
        if rel.include:
            args["include"] = rel.include
        planned.append((rel, getattr(db, rel.model).find_many(**args)))

    if not planned:
        return
    fetched = await gather_reads(*(coro for _, coro in planned))

    for (rel, _), children in zip(planned, fetched):
        if rel.many:
            grouped: dict[Any, list[Any]] = {}
            for child in children:
                grouped.setdefault(getattr(child, rel.key, None), []).append(child)
            for row in rows:
                setattr(row, rel.field, grouped.get(row.id, []))
        else:
            by_id = {child.id: child for child in children}
            for row in rows:
                setattr(row, rel.field, by_id.get(getattr(row, rel.key, None)))


async def count_and_page(
    delegate: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
    order: Any,
    relations: Sequence[Relation] = (),
) -> tuple[int, list[Any]]:
    total, items = await gather_reads(
        delegate.count(where=where),
        delegate.find_many(where=where, skip=skip, take=take, order=order),
    )
    await hydrate_relations(items, relations)
    return total, items


CLIENT_KEY_FIELD = "clientKey"


def is_client_key_violation(error: Exception) -> bool:
    text = str(error)
    return "unique" in text.lower() and CLIENT_KEY_FIELD in text


async def client_key_replay(
    delegate: Any,
    client_key: str | None,
    *,
    user_id: str,
    include: dict[str, Any] | None = None,
) -> Any | None:
    if not client_key:
        return None
    kwargs: dict[str, Any] = {"where": {CLIENT_KEY_FIELD: client_key}}
    if include:
        kwargs["include"] = include
    existing = await delegate.find_unique(**kwargs)
    if existing is None:
        return None
    if getattr(existing, "createdById", None) != user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="That record belongs to another account.",
        )
    return existing


async def client_key_replay_after_violation(
    delegate: Any,
    client_key: str | None,
    error: Exception,
    *,
    user_id: str,
    include: dict[str, Any] | None = None,
) -> Any | None:
    if not client_key or not is_client_key_violation(error):
        return None
    return await client_key_replay(delegate, client_key, user_id=user_id, include=include)


EXPECTED_UPDATED_AT_FIELD = "expectedUpdatedAt"

EXPECTED_UPDATED_AT_TOLERANCE = timedelta(seconds=1)


def take_expected_updated_at(data: dict[str, Any]) -> datetime | None:
    value = data.pop(EXPECTED_UPDATED_AT_FIELD, None)
    return value if isinstance(value, datetime) else None


def assert_expected_updated_at(record: Any, expected: datetime | None) -> None:
    if expected is None:
        return
    stored = getattr(record, "updatedAt", None)
    if not isinstance(stored, datetime):
        return
    if expected.tzinfo is None:
        expected = expected.replace(tzinfo=UTC)
    if stored.tzinfo is None:
        stored = stored.replace(tzinfo=UTC)
    if abs(stored - expected) <= EXPECTED_UPDATED_AT_TOLERANCE:
        return
    raise HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail={
            "code": "record_changed",
            "message": "Someone else changed this record after this edit was composed.",
            "expectedUpdatedAt": expected.isoformat(),
            "currentUpdatedAt": stored.isoformat(),
        },
    )


PROVENANCE_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "createdById",
    "createdAt",
    "updatedAt",
    "reviewedById",
    "reviewNotes",
    "reviewedAt",
    "submittedAt",
    "recordedAt",
    "recordedTimezone",
    "measurementAnalysis",
    "measurementAnalysisStatus",
    "measurementImageId",
    CLIENT_KEY_FIELD,
    MARKER_BODY_KEY,
    aadhaar_fingerprint.LAST4_FIELD,
}


def merge_field_provenance(new_data: dict[str, Any], user: Any, previous: Any | None = None) -> None:
    from app.core.roles import get_value, is_empty_value, values_match

    incoming_extra = new_data.get("extraMetadata")
    base_extra: dict[str, Any] = dict(incoming_extra) if isinstance(incoming_extra, dict) else {}

    stamps = method_stamps(new_data.pop(MARKER_BODY_KEY, None), fields=new_data.keys())

    provenance: dict[str, Any] = {}
    if previous is not None:
        previous_extra = get_value(previous, "extraMetadata")
        if isinstance(previous_extra, dict) and isinstance(previous_extra.get("fieldProvenance"), dict):
            provenance = dict(previous_extra["fieldProvenance"])

    stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": datetime.now(UTC).isoformat(),
    }

    for field, value in new_data.items():
        if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value):
            continue
        previous_value = get_value(previous, field) if previous is not None else None
        if previous is None or is_empty_value(previous_value) or not values_match(previous_value, value):
            key = (
                aadhaar_fingerprint.NUMBER_FIELD
                if field == aadhaar_fingerprint.FINGERPRINT_FIELD
                else field
            )
            provenance[key] = stamp | stamps.get(field, {})

    if provenance:
        base_extra["fieldProvenance"] = provenance
    if base_extra:
        new_data["extraMetadata"] = Json(base_extra)
    else:
        new_data.pop("extraMetadata", None)


REOPENED_BY_CREATOR_EDIT = frozenset({"NEEDS_REVISION", "REJECTED"})


def resubmit_status(record: Any, user: Any, data: dict[str, Any]) -> dict[str, Any]:
    from app.core.roles import get_value

    current = get_value(record, "status")
    current_value = str(getattr(current, "value", current)) if current is not None else None
    if "status" in data:
        wanted = data["status"]
        if str(getattr(wanted, "value", wanted)) == "PENDING" and current_value not in (None, "PENDING"):
            data["submittedAt"] = datetime.now(UTC)
        return data
    if current_value not in REOPENED_BY_CREATOR_EDIT:
        return data
    creator_id = get_value(record, "createdById")
    if creator_id is None or creator_id != get_value(user, "id"):
        return data
    data["status"] = "PENDING"
    data["submittedAt"] = datetime.now(UTC)
    return data


def review_update(status_value: str, notes: str | None, reviewer_id: str) -> dict[str, Any]:
    return {
        "status": status_value,
        "reviewNotes": notes,
        "reviewedById": reviewer_id,
        "reviewedAt": datetime.now(UTC),
    }


def relation_filter(field: str, value: str | None) -> dict[str, Any]:
    return {field: value} if value else {}


def media_relation_data(record_type: str | None, record_id: str | None) -> dict[str, Any]:
    if not record_type or not record_id:
        return {}
    normalized = record_type.lower()
    field_map = {
        "artisan": "artisanId",
        "craft": "craftId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
        "questionnaire": "questionnaireInterviewId",
        "questionnaireinterview": "questionnaireInterviewId",
        "questionnaire_session": "questionnaireInterviewId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}
