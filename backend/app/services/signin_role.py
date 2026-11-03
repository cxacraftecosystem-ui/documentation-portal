from __future__ import annotations

import logging
from typing import Any

from app.core.deps import ROLE_LABELS, ROLE_RANK

logger = logging.getLogger(__name__)

NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users."

ROLE_KEPT_DETAIL = "{email} already has an account, as {current}. Its role stays; change it on Manage users."

ROLE_AUDIT_PREFIX = "AUDIT account role changed"


def _plain(role: Any) -> str:
    return str(getattr(role, "value", role) or "")


def role_for_new_account(floor: Any, listed: Any) -> str:
    floor_role, listed_role = _plain(floor), _plain(listed)
    if listed_role and ROLE_RANK.get(listed_role, 0) > ROLE_RANK.get(floor_role, 0):
        return listed_role
    return floor_role


def role_kept_sentence(email: Any, current: Any, listed: Any) -> str | None:
    current_role, listed_role = _plain(current), _plain(listed)
    if not current_role or not listed_role or current_role == listed_role:
        return None
    return ROLE_KEPT_DETAIL.format(email=str(email or ""), current=ROLE_LABELS.get(current_role, current_role))


def audit_role_change(*, user_id: Any, actor_id: Any, before: Any, after: Any, via: str) -> bool:
    old, new = _plain(before), _plain(after)
    if not new or old == new:
        return False
    logger.warning(
        "%s: user=%s by=%s from=%s to=%s via=%s", ROLE_AUDIT_PREFIX, user_id, actor_id, old or "-", new, via
    )
    return True
