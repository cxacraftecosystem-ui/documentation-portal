"""Cross-researcher tiered data access + edit auditing.

A DataAccessGrant lets one researcher (the GRANTEE) act on another researcher's (the OWNER's) uploaded
records at a tier: DOWNLOAD (download/export) < COMMENT (+ comment) < EDIT (+ change fields). Admins and
a record's own creator always have full (EDIT) access. Every field-changing edit is captured in a
RecordRevision so an admin can reconstruct the original values and every subsequent edit, with author.
"""
from typing import Any

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder
from prisma import Json

from app.core.db import db

# Strictly increasing privilege. A tier includes every action of the tiers below it.
TIER_ORDER = {"DOWNLOAD": 1, "COMMENT": 2, "EDIT": 3}

# Human-readable, shown in UIs so a user knows exactly what each tier confers when requesting/granting.
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
    """The (owner, grantee) DataAccessGrant row, with its subset items, or None."""
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
    """The highest tier `user` holds over one record owned by `owner_id`.

    Admins and the record's own creator implicitly have EDIT. Otherwise it's the tier of an active
    GRANTED grant from the owner to the user that covers this record (all-data, or the subset list).
    Returns None when the user has no access beyond plain viewing.
    """
    from app.core.deps import get_value, is_admin

    if is_admin(user):
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


# Infrastructural fields whose churn should not be logged as a meaningful edit.
REVISION_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "updatedAt",
    "createdAt",
    "createdById",
    "recordedAt",
    "recordedTimezone",
}


async def record_revision(record: Any, user: Any, data: dict[str, Any], record_type: str) -> None:
    """Append an immutable RecordRevision for whichever fields in `data` actually change `record`.

    Call with the cleaned update payload BEFORE field-provenance is merged in, so provenance bookkeeping
    is not mistaken for a content edit. No-op when nothing meaningful changed.
    """
    from app.core.deps import get_value, values_match

    changes: dict[str, Any] = {}
    for field, new_value in data.items():
        if field in REVISION_SKIP_FIELDS:
            continue
        old_value = get_value(record, field)
        if not values_match(old_value, new_value):
            changes[field] = {"old": jsonable_encoder(old_value), "new": jsonable_encoder(new_value)}
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


async def guard_record_edit(record: Any, user: Any, data: dict[str, Any], record_type: str) -> bool:
    """Authorize a field-changing edit and audit it. Returns True if the user is privileged (admin,
    owner, or EDIT-tier grantee) and may change any populated field/relation; False for an ordinary
    contributor (who may only fill empty fields — enforced here, raising 403 on a locked field). Always
    records a revision of the fields that change. Pass the cleaned `data` before provenance is merged.
    """
    from app.core.deps import assert_can_contribute_fields, get_value, is_admin

    owner_id = get_value(record, "createdById")
    uid = get_value(user, "id")
    privileged = is_admin(user) or (owner_id is not None and uid == owner_id)
    if not privileged:
        tier = await effective_tier_for_record(user, owner_id, record_type, get_value(record, "id"))
        if tier == "EDIT":
            privileged = True
        else:
            assert_can_contribute_fields(record, user, data)
    await record_revision(record, user, data, record_type)
    return privileged


async def assert_can_comment(user: Any, owner_id: str | None, record_type: str, record_id: str) -> None:
    """COMMENT tier (or owner/admin) required to comment on a record."""
    tier = await effective_tier_for_record(user, owner_id, record_type, record_id)
    if not tier_at_least(tier, "COMMENT"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need comment access to this researcher's data. Request it from them.",
        )


async def owner_download_scope(user: Any, owner_id: str) -> dict[str, set[str]] | None:
    """Authorize downloading `owner_id`'s data and return what is covered.

    Returns None when ALL of the owner's data is allowed (admin, the global dataset-download
    permission, the owner themselves, or an active all-data DOWNLOAD+ grant). Returns
    {recordType: {recordIds}} when only a subset grant applies. Raises 403 when there is no access.
    """
    from app.core.deps import can_download_dataset, get_value, is_admin

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
