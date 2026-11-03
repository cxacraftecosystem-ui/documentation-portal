from typing import Any

from fastapi import HTTPException, status
from app.core.fastapi_lazy import jsonable_encoder
from prisma import Json

from app.core.db import db
from app.services.measurement_provenance import MARKER_BODY_KEY

TIER_ORDER = {"DOWNLOAD": 1, "COMMENT": 2, "EDIT": 3}

TIER_DESCRIPTIONS = {
    "DOWNLOAD": "Minimum — download this researcher's data (the whole set, or the shared subset).",
    "COMMENT": "Medium — everything in Download, plus leave comments on their entries.",
    "EDIT": "Maximum — everything in Comment, plus edit fields on their entries (every change is "
    "tracked; admins see the original and all edits).",
}


def _enum_str(value: Any) -> str:
    return str(getattr(value, "value", value))


def tier_at_least(tier: str | None, minimum: str) -> bool:
    if not tier:
        return False
    return TIER_ORDER.get(tier, 0) >= TIER_ORDER.get(minimum, 99)


async def active_grant(grantee_id: str, owner_id: str) -> Any:
    if not grantee_id or not owner_id:
        return None
    return await db.dataaccessgrant.find_unique(
        where={"ownerId_granteeId": {"ownerId": owner_id, "granteeId": grantee_id}},
        include={"scopeItems": True},
    )


def _grant_covers(grant: Any, record_type: str, record_id: str) -> bool:
    if grant.allData:
        return True
    for item in grant.scopeItems or []:
        if item.recordType.lower() == record_type.lower() and item.recordId == record_id:
            return True
    return False


async def effective_tier_for_record(
    user: Any, owner_id: str | None, record_type: str, record_id: str | None
) -> str | None:
    from app.core.roles import get_value, is_content_admin

    if is_content_admin(user):
        return "EDIT"
    uid = get_value(user, "id")
    if owner_id and uid == owner_id:
        return "EDIT"
    grant = await active_grant(uid, owner_id) if owner_id else None
    if not grant or _enum_str(grant.status) != "GRANTED":
        return None
    if record_id is not None and not _grant_covers(grant, record_type, record_id):
        return None
    return _enum_str(grant.tier)


REVISION_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "updatedAt",
    "createdAt",
    "createdById",
    "recordedAt",
    "recordedTimezone",
    "submittedAt",
    MARKER_BODY_KEY,
}


async def record_revision(record: Any, user: Any, data: dict[str, Any], record_type: str) -> None:
    from app.core.roles import get_value, values_match

    changes: dict[str, Any] = {}
    for field, new_value in data.items():
        if field in REVISION_SKIP_FIELDS:
            continue
        old_value = get_value(record, field)
        if not values_match(old_value, new_value):
            changes[field] = {"old": jsonable_encoder(old_value), "new": jsonable_encoder(new_value)}
    aadhaar_keys = ("aadhaarFingerprint", "aadhaarLast4", "aadhaarNumber")
    if any(key in changes for key in aadhaar_keys):
        from app.services.aadhaar_fingerprint import has_aadhaar, ledger_entry

        for key in aadhaar_keys:
            changes.pop(key, None)
        has = bool(data.get("aadhaarFingerprint")) if "aadhaarFingerprint" in data else (
            bool(data.get("aadhaarNumber"))
        )
        changes["aadhaarNumber"] = ledger_entry(has_aadhaar(record), has)
    if not changes:
        return
    await db.recordrevision.create(
        data={
            "recordType": record_type.lower(),
            "recordId": get_value(record, "id"),
            "editedById": get_value(user, "id"),
            "changes": Json(changes),
        }
    )


async def record_edit_privilege(record: Any, user: Any, record_type: str) -> bool:
    from app.core.deps import get_value, is_content_admin, may_edit_lower_ranked_record

    owner_id = get_value(record, "createdById")
    uid = get_value(user, "id")
    if is_content_admin(user) or (owner_id is not None and uid == owner_id):
        return True
    if await may_edit_lower_ranked_record(user, owner_id):
        return True
    return await effective_tier_for_record(user, owner_id, record_type, get_value(record, "id")) == "EDIT"


async def guard_record_edit(
    record: Any,
    user: Any,
    data: dict[str, Any],
    record_type: str,
    *,
    privileged: bool | None = None,
) -> bool:
    from app.core.deps import assert_can_contribute_fields

    if privileged is None:
        privileged = await record_edit_privilege(record, user, record_type)
    if not privileged:
        assert_can_contribute_fields(record, user, data)
    await record_revision(record, user, data, record_type)
    return privileged


async def assert_can_comment(user: Any, owner_id: str | None, record_type: str, record_id: str) -> None:
    tier = await effective_tier_for_record(user, owner_id, record_type, record_id)
    if not tier_at_least(tier, "COMMENT"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need comment access to this researcher's data. Request it from them.",
        )


async def owner_download_scope(user: Any, owner_id: str) -> dict[str, set[str]] | None:
    from app.core.roles import can_download_dataset, get_value, is_admin

    if is_admin(user) or can_download_dataset(user) or get_value(user, "id") == owner_id:
        return None
    grant = await active_grant(get_value(user, "id"), owner_id)
    if not (grant and _enum_str(grant.status) == "GRANTED" and tier_at_least(_enum_str(grant.tier), "DOWNLOAD")):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need download access to this researcher's data. Request it from them.",
        )
    if grant.allData:
        return None
    scope: dict[str, set[str]] = {}
    for item in grant.scopeItems or []:
        scope.setdefault(item.recordType.lower(), set()).add(item.recordId)
    return scope
