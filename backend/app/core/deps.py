import asyncio
import time
from collections import OrderedDict
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import get_settings
from app.core.db import db
from app.core.security import decode_access_token

bearer_scheme = HTTPBearer(auto_error=False)


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


# The six-tier role ladder, strictly ordered. Higher rank inherits every power of the ranks below
# it; grantable capability booleans can additionally lift a specific power for a lower tier.
ROLE_RANK: dict[str, int] = {
    "CROWDSOURCE_VOLUNTEER": 10,
    "FIELD_CONTRIBUTOR": 20,
    "RESEARCHER": 30,
    "PROFESSOR": 40,
    "ADMIN": 50,
    "MASTER_ADMIN": 60,
}

ROLE_LABELS: dict[str, str] = {
    "CROWDSOURCE_VOLUNTEER": "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR": "Field Contributor",
    "RESEARCHER": "Researcher",
    "PROFESSOR": "Professor",
    "ADMIN": "Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_rank(user_or_role: Any) -> int:
    """Rank of a user object or a bare role string; unknown roles rank lowest."""
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


def can_manage_questionnaire(user: Any) -> bool:
    """Edit the questionnaire structure: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    """Create or update a craft: Professor and above, RANK ALONE.

    The ``canManageCrafts`` column is deliberately no longer read. It was a per-user grant a master
    admin could hand to a researcher or below, which is the one clause that let the taxonomy itself
    be written by someone the permission matrix places under it — and a grant that widens a rank
    floor is invisible in the role column, so nobody auditing the user table could see who held it.
    The column stays in the schema (dropping it is neither safe nor reversible, and no live account
    below Professor holds it), simply unread; restoring the old behaviour is putting the clause back.
    """
    return has_rank(user, "PROFESSOR")


def can_manage_workshops(user: Any) -> bool:
    """Create or update a workshop: Professor and above, RANK ALONE — see ``can_manage_crafts`` for
    why the ``canManageWorkshops`` grant is no longer consulted."""
    return has_rank(user, "PROFESSOR")


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    """The peer-review hierarchy: the master admin reviews EVERYONE's work; everyone else may only
    review records whose creator ranks STRICTLY below them (admin reviews everyone beneath,
    professor reviews researchers and below, researcher reviews field contributors and volunteers,
    field contributor reviews volunteers). A record with no creator role on file is treated as a
    researcher's work."""
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_edit_others_record(user: Any, creator_role: Any) -> bool:
    """May *user* edit a record created by *creator_role* as fully as its own author can?

    "A professor may edit the data of anyone ranked below them." The comparison for "below" is
    ``can_review_record``'s and nothing else's: a second hand-rolled rank test that disagreed with
    the review ladder by one tier is exactly the shape a privilege bug hides in. It is then narrowed
    to Professor and above, because the review ladder deliberately reaches further down than editing
    is meant to — a field contributor reviews a volunteer's work, but does not get to rewrite it.
    (``services/records.apply_status_policy_update`` composes the same two halves for status
    changes, which is where this pairing comes from.)
    """
    return has_rank(user, "PROFESSOR") and can_review_record(user, creator_role)


async def may_edit_lower_ranked_record(user: Any, creator_id: str | None) -> bool:
    """``can_edit_others_record`` with the creator's role looked up. Costs one query, and only for a
    Professor+ who is neither the record's author nor an admin — everyone else is refused before it."""
    if not creator_id or not has_rank(user, "PROFESSOR"):
        return False
    creator = await db.user.find_unique(where={"id": creator_id})
    return can_edit_others_record(user, get_value(creator, "role") if creator else None)


def can_access_review(user: Any) -> bool:
    """May open the review queue: Field Contributor and above (everyone who has someone beneath
    them on the ladder), or an explicit review grant. Which specific records they may act on is
    decided per record by can_review_record."""
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    """Back-compat alias for can_access_review — page-level review access, not per-record."""
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    """May download the entire dataset: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    """May create core records (artisans, products, tools, processes, interviews): Researcher and
    above.

    The two tiers below — Field Contributor and Crowdsource Volunteer — POPULATE records rather than
    open them: attaching media to an existing artisan, answering questions in an existing interview,
    and commenting. Those three paths are the reason the tiers exist and none of them passes through
    here; they are gated by ``get_current_user`` on /media, /questionnaire's response arms, and
    /data-access/comments respectively."""
    return has_rank(user, "RESEARCHER")


# --- The authenticated-identity cache -------------------------------------------------------------
#
# WHY IT EXISTS. ``get_current_user`` runs on every authenticated request, and its one
# ``find_unique`` is one of only three sequential database waves left on a list endpoint like
# ``GET /artisans``. The database is cross-region: that round trip costs 200-400ms before any of the
# request's real work begins, and one dashboard page load pays it once per request it fires in
# parallel. Removing it is roughly a third of the remaining latency of every authenticated endpoint.
#
# WHY THE ROW IS FETCHED AT ALL, WHICH IS THE SAME REASON THE CACHE IS DANGEROUS. The access token
# already carries the user id in ``sub``, and ``create_access_token`` even puts ``email`` and
# ``role`` in it — so a "cache" that simply trusted the token would be free. That is exactly the
# shortcut this must not take. Tokens live for seven days (JWT_EXPIRES_MINUTES) and are never
# revoked, so a role claim minted before a demotion stays valid for a week, and a deleted account
# keeps a working token until it expires. The database read IS the revocation check. Caching it
# shortens the revocation window; trusting the token would remove it.
#
# SO THE WINDOW IS KEPT SHORT AT BOTH ENDS:
#   1. EXPLICIT INVALIDATION. Every write that changes a user's authority or identity calls
#      ``invalidate_cached_user`` — users.py (create/update/delete), auth.py (the Google sign-in
#      upsert, which can set MASTER_ADMIN), scripts/seed_admin.py. In-process, a demotion or a
#      deletion takes effect on the very next request, with no window at all.
#   2. A FIVE-SECOND TTL, which is only the backstop for writes this process cannot see: a psql
#      session, the seed script running as its own process, a second uvicorn worker's memory. Five
#      seconds is chosen to span the burst of parallel requests one page load makes (and the
#      follow-on requests a user makes while reading it) and nothing more; the value is a few
#      seconds rather than a few minutes because every second of it is a second a demoted account
#      keeps a privilege after such a write. AUTH_USER_CACHE_TTL_SECONDS tunes it and
#      AUTH_USER_CACHE_ENABLED=false removes it entirely without a deploy.
#
# A MISS IS NEVER CACHED. "No such user" stays a fresh query every time, so a deleted account 401s
# on every request rather than for a TTL and then not.
#
# SINGLE-FLIGHT, LOCALLY. N requests arriving together on a cold entry run ONE query; the rest await
# it. app/scale/singleflight.py does this well, but that whole package is flag-gated and off by
# default, and importing it here would put an always-on authentication path behind a dormant
# feature (and drag app.scale.flags onto the import graph of every request). Twenty lines that need
# no flag are the cheaper dependency.

_USER_CACHE_INFLIGHT_WAIT_SECONDS = 5.0

# id -> (monotonic expiry, user row). Ordered so eviction is LRU: the oldest touched identity goes
# first when the cap is reached.
_user_cache: "OrderedDict[str, tuple[float, Any]]" = OrderedDict()
# id -> (event loop, future). The loop is stored because a future can only be awaited on the loop
# that created it, and the test suite runs each case in its own ``asyncio.run``.
_user_cache_inflight: dict[str, tuple[Any, "asyncio.Future[Any]"]] = {}
# Bumped by every invalidation. A query that was already in flight when someone revoked a role would
# otherwise be free to write the pre-revocation row back into the cache and undo the invalidation;
# comparing this before and after the query closes that race by declining to store the result.
_user_cache_epoch = 0


class _LeaderGone(Exception):
    """The request that was loading this identity went away before it produced a row."""


def invalidate_cached_user(user_id: str | None) -> None:
    """Forget *user_id*, so the next request re-reads the row. Call after ANY write that changes a
    user's role, grants, email or existence — see the call-site list above."""
    global _user_cache_epoch
    _user_cache_epoch += 1
    if user_id:
        _user_cache.pop(user_id, None)


def clear_user_cache() -> None:
    """Forget every identity. For tests, and for a caller that has changed users in bulk."""
    global _user_cache_epoch
    _user_cache_epoch += 1
    _user_cache.clear()


def _cached_user(user_id: str) -> Any | None:
    entry = _user_cache.get(user_id)
    if entry is None:
        return None
    expires_at, user = entry
    if expires_at <= time.monotonic():
        del _user_cache[user_id]
        return None
    _user_cache.move_to_end(user_id)
    return user


def _store_cached_user(user_id: str, user: Any, ttl: float) -> None:
    _user_cache[user_id] = (time.monotonic() + ttl, user)
    _user_cache.move_to_end(user_id)
    max_entries = max(1, get_settings().auth_user_cache_max_entries)
    while len(_user_cache) > max_entries:
        _user_cache.popitem(last=False)


async def _load_user(user_id: str, ttl: float) -> Any:
    """One ``find_unique`` per identity per cold window, however many requests are waiting on it."""
    loop = asyncio.get_running_loop()
    pending = _user_cache_inflight.get(user_id)
    if pending is not None and pending[0] is loop:
        try:
            # shield: a follower that times out, or whose client hangs up, must not cancel the query
            # the leader is running on everyone's behalf.
            return await asyncio.wait_for(asyncio.shield(pending[1]), _USER_CACHE_INFLIGHT_WAIT_SECONDS)
        except (_LeaderGone, TimeoutError):
            pass  # load it ourselves — exactly what we would have done with no dedupe at all

    future: "asyncio.Future[Any]" = loop.create_future()
    # Retrieve the outcome on completion so a leader whose followers all went away leaves no
    # "exception was never retrieved" behind. The leader still raises for itself.
    future.add_done_callback(lambda done: done.cancelled() or done.exception())
    _user_cache_inflight[user_id] = (loop, future)
    epoch_at_start = _user_cache_epoch
    try:
        user = await db.user.find_unique(where={"id": user_id})
    except asyncio.CancelledError:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(_LeaderGone())
        raise
    except BaseException as exc:
        _user_cache_inflight.pop(user_id, None)
        if not future.done():
            future.set_exception(exc)
        raise
    _user_cache_inflight.pop(user_id, None)
    if user is not None and epoch_at_start == _user_cache_epoch:
        _store_cached_user(user_id, user, ttl)
    if not future.done():
        future.set_result(user)
    return user


async def resolve_user(user_id: str) -> Any:
    """The user row for *user_id*, from the identity cache when it is warm and enabled."""
    settings = get_settings()
    if not settings.auth_user_cache_enabled:
        return await db.user.find_unique(where={"id": user_id})
    cached = _cached_user(user_id)
    if cached is not None:
        return cached
    return await _load_user(user_id, settings.auth_user_cache_ttl_seconds)


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> Any:
    if not credentials:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    try:
        payload = decode_access_token(credentials.credentials)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=str(exc)) from exc

    user_id = payload.get("sub")
    if not user_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token subject")

    user = await resolve_user(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User no longer exists")
    return user


async def require_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    return current_user


async def require_master_admin(current_user: Any = Depends(get_current_user)) -> Any:
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Master admin access required",
        )
    return current_user


async def require_reviewer(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_access_review(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Review access required")
    return current_user


async def require_professor(current_user: Any = Depends(get_current_user)) -> Any:
    if not has_rank(current_user, "PROFESSOR"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Professor access or above required",
        )
    return current_user


async def require_dataset_downloader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_download_dataset(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Dataset download access required. Ask an admin to grant it.",
        )
    return current_user


def assert_can_create_records(user: Any) -> None:
    """The body-callable half of ``require_record_creator``, for the one route that cannot decide
    from its signature: POST /questionnaire/interviews opens a NEW interview or folds into an
    EXISTING one depending on the artisan set, and only the first of those is a create."""
    if not can_create_records(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Creating records requires Researcher access or above. Field contributors and "
                "volunteers can add media, questionnaire answers, and comments to existing records."
            ),
        )


async def require_record_creator(current_user: Any = Depends(get_current_user)) -> Any:
    assert_can_create_records(current_user)
    return current_user


async def require_questionnaire_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_questionnaire(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Questionnaire management access required",
        )
    return current_user


async def require_craft_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates POST and PATCH /crafts. DELETE is stricter still — ``assert_can_delete``, admin only."""
    if not can_manage_crafts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Adding or editing a craft requires Professor access or above. Ask a professor or "
                "an admin to add it, then link your records to it."
            ),
        )
    return current_user


async def require_workshop_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """Gates POST and PATCH /workshops. DELETE is admin-only (``assert_can_delete``)."""
    if not can_manage_workshops(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Adding or editing a workshop requires Professor access or above. Ask a professor "
                "or an admin to set it up, then request access to submit records to it."
            ),
        )
    return current_user


def is_empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, tuple, set, dict)):
        return len(value) == 0
    return False


def enum_or_raw(value: Any) -> Any:
    return getattr(value, "value", value)


def values_match(current_value: Any, next_value: Any) -> bool:
    current_value = enum_or_raw(current_value)
    next_value = enum_or_raw(next_value)
    if current_value == next_value:
        return True
    try:
        return Decimal(str(current_value)) == Decimal(str(next_value))
    except (InvalidOperation, ValueError):
        return str(current_value) == str(next_value)


def assert_can_contribute_fields(record: Any, user: Any, data: dict[str, Any], owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return

    # A populated field is locked to non-privileged editors whether they try to CHANGE it or CLEAR it.
    # (The earlier version skipped an incoming empty value, which let anyone blank out a populated field.)
    locked_fields = [
        field
        for field, next_value in data.items()
        if not is_empty_value(get_value(record, field))
        and (is_empty_value(next_value) or not values_match(get_value(record, field), next_value))
    ]
    if locked_fields:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change or clear populated field(s): {', '.join(sorted(locked_fields))}",
        )


def assert_can_contribute_relation(record: Any, user: Any, populated: bool, field_name: str, owner_field: str = "createdById") -> None:
    if is_admin(user) or get_value(record, owner_field) == get_value(user, "id"):
        return
    if populated:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Only the original contributor or an admin can change populated relation: {field_name}",
        )


def assert_owner_or_admin(record: Any, user: Any, owner_field: str = "createdById") -> None:
    if is_admin(user):
        return
    if get_value(record, owner_field) != get_value(user, "id"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only access records you created",
        )


def assert_admin_or_owner(record: Any, user: Any, owner_field: str = "createdById") -> None:
    assert_owner_or_admin(record, user, owner_field)


def assert_can_delete(user: Any) -> None:
    if not is_admin(user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required to delete records")
