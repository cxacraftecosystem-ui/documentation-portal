"""THE SIGN-IN LIST RULE: the list sets the role of a NEW account, once (convergence decision 2, 2026-10-18).

An entry on the sign-in list (``AccessRoster``) says which role an address joins with. That role is
applied at ONE moment only: when the account is created, by its first sign-in. After that the list
never touches the account's role again:

* **NOT AT SIGN-IN.** An account that already exists signs in at the role it holds. The list does not
  raise it (an entry that says Professor does not promote a Researcher who signs in) and does not
  lower it (an entry left at the default does not demote somebody promoted since).
* **NOT ON APPROVAL.** Approving, restoring or editing an entry changes the entry. When an account
  already exists at the address with another role, the answer carries :func:`role_kept_sentence`
  for the screen to show, and the account is left as it is.
* **A LATER CHANGE IS AN ADMINISTRATOR'S EXPLICIT ACT** on Manage users (``PATCH /api/users/{id}``),
  and it writes an audit entry: :func:`audit_role_change`.

The configured master admin address (``MASTER_ADMIN_EMAIL``) is not the list and is outside this
rule: it is the break-glass, and its sign-in still restores MASTER_ADMIN in both portals.

``shared/signin-role-contract.json`` declares the rule, the copy and the vectors. This file and
``backend/tests/test_signin_role_contract.py`` are byte-identical in designer-portal and
documentation-portal, and that test compares them with the sibling checkout when it is present.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.deps import ROLE_LABELS, ROLE_RANK

logger = logging.getLogger(__name__)

#: The admin copy beside every control that picks the role an entry joins with, on the web and on
#: Android, in both portals. Copied byte for byte into each client; the contract test reads them.
NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users."

#: The approval's answer when an account already exists at the address with another role.
#: ``{email}`` is the address and ``{current}`` the account's role label (``deps.ROLE_LABELS``).
ROLE_KEPT_DETAIL = "{email} already has an account, as {current}. Its role stays; change it on Manage users."

#: The audit line's fixed prefix. Grep for it; never reword it.
ROLE_AUDIT_PREFIX = "AUDIT account role changed"


def _plain(role: Any) -> str:
    """A role as a bare string, whether Prisma handed back an enum member or a string."""
    return str(getattr(role, "value", role) or "")


def role_for_new_account(floor: Any, listed: Any) -> str:
    """The role a NEW account is created with: the list's role, never below ``floor``.

    ``floor`` is ``DEFAULT_SIGNUP_ROLE`` (the lowest rung unless configured otherwise). ``listed`` is
    the entry's role, or None when the entry leaves it at the default.
    """
    floor_role, listed_role = _plain(floor), _plain(listed)
    if listed_role and ROLE_RANK.get(listed_role, 0) > ROLE_RANK.get(floor_role, 0):
        return listed_role
    return floor_role


def role_kept_sentence(email: Any, current: Any, listed: Any) -> str | None:
    """What an approval says when it left an existing account's role alone, or None.

    None when there is no account (``current`` empty), when the entry names no role, or when the two
    already agree: in each case there is nothing the administrator could be misled about.
    """
    current_role, listed_role = _plain(current), _plain(listed)
    if not current_role or not listed_role or current_role == listed_role:
        return None
    return ROLE_KEPT_DETAIL.format(email=str(email or ""), current=ROLE_LABELS.get(current_role, current_role))


def audit_role_change(*, user_id: Any, actor_id: Any, before: Any, after: Any, via: str) -> bool:
    """Write the audit entry for an administrator's change of an account's role. True if one was written.

    Ids and role names only. Nothing is written when the role did not move: an echo of the role the
    account already holds is not a change, and an audit line claiming one would be false.
    """
    old, new = _plain(before), _plain(after)
    if not new or old == new:
        return False
    logger.warning(
        "%s: user=%s by=%s from=%s to=%s via=%s", ROLE_AUDIT_PREFIX, user_id, actor_id, old or "-", new, via
    )
    return True
