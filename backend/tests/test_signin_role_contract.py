"""THE SIGN-IN LIST RULE CONTRACT — one rule in both portals (convergence decision 2, 2026-10-18).

An entry on the sign-in list sets an account's role only when the account is created; it never
raises or lowers an existing account at sign-in or on approval; a later change of role is an
administrator's explicit act on Manage users, with an audit entry. ``shared/signin-role-contract.json``
declares it once and is byte-identical in both repositories. This file is identical in both too, and
it holds:

* ``services/signin_role`` to the contract's copy and vectors;
* the audit entry: written for a real change, never for an echo;
* each client's admin screen to the copy, read from source, and none of the retired promise;
* the two repositories to each other, when the sibling checkout is present.

Each portal's own ``tests/test_signin_role_once.py`` drives its sign-in and approval doors over the
contract's ``existingAccountAtSignIn`` vectors. No database here.
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
from typing import Any

import pytest

from app.services import signin_role

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_FILE = ROOT / "shared" / "signin-role-contract.json"
PORTAL = (
    "designer-portal"
    if any(ROOT.glob("android/app/src/main/java/com/designprototype"))
    else "documentation-portal"
)

SHARED_FILES = (
    "shared/signin-role-contract.json",
    "backend/app/services/signin_role.py",
    "backend/tests/test_signin_role_contract.py",
)

#: Words the admin screens used to say and must not say again: the list no longer promotes anybody.
RETIRED_COPY = (
    "raised to match",
    "Existing accounts are raised",
)


def contract() -> dict[str, Any]:
    return json.loads(CONTRACT_FILE.read_text(encoding="utf-8"))


def _text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def test_the_module_speaks_the_contracts_copy():
    copy = contract()["copy"]
    assert copy["newAccountsOnly"] == signin_role.NEW_ACCOUNTS_ONLY
    assert copy["roleKept"] == signin_role.ROLE_KEPT_DETAIL
    assert copy["auditPrefix"] == signin_role.ROLE_AUDIT_PREFIX


@pytest.mark.parametrize("vector", contract()["newAccountRole"], ids=lambda v: f"{v['floor']}+{v['listed']}")
def test_a_new_account_takes_the_lists_role_never_below_the_floor(vector):
    assert signin_role.role_for_new_account(vector["floor"], vector["listed"]) == vector["expect"]


@pytest.mark.parametrize("vector", contract()["roleKeptSentence"], ids=lambda v: f"{v['current']}+{v['listed']}")
def test_an_approval_says_when_an_existing_account_keeps_its_role(vector):
    assert signin_role.role_kept_sentence(vector["email"], vector["current"], vector["listed"]) == vector["expect"]


def test_the_vectors_at_sign_in_never_move_an_existing_account():
    """The table each portal's sign-in test drives: the expected role is always the current one."""
    for vector in contract()["existingAccountAtSignIn"]:
        assert vector["expect"] == vector["current"], vector


def test_a_change_of_role_writes_one_audit_entry_and_an_echo_writes_none(caplog):
    caplog.set_level(logging.WARNING, logger=signin_role.logger.name)
    assert signin_role.audit_role_change(
        user_id="u1", actor_id="a1", before="RESEARCHER", after="PROFESSOR", via="users"
    )
    assert not signin_role.audit_role_change(
        user_id="u1", actor_id="a1", before="PROFESSOR", after="PROFESSOR", via="users"
    )
    lines = [record.getMessage() for record in caplog.records if record.name == signin_role.logger.name]
    assert lines == [
        "AUDIT account role changed: user=u1 by=a1 from=RESEARCHER to=PROFESSOR via=users"
    ]


@pytest.mark.parametrize("rel", contract()["clientsCarryingTheCopy"][PORTAL])
def test_every_admin_screen_says_new_accounts_only(rel):
    source = _text(ROOT / rel)
    assert signin_role.NEW_ACCOUNTS_ONLY in source, f"{rel} lacks the new-accounts-only copy"
    for retired in RETIRED_COPY:
        assert retired not in source, f"{rel} still says {retired!r}"


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if PORTAL == "designer-portal" else "designer-portal"
    candidates = [
        os.environ.get("SIBLING_REPO"),
        ROOT.parent / other,
        pathlib.Path("C:/dev/IIT") / other,
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / CONTRACT_FILE.relative_to(ROOT)).is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_rule():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the contract (CI has one repository)")
    for rel in SHARED_FILES:
        theirs = sibling / rel
        assert theirs.is_file(), f"{sibling} lacks {rel}"
        assert _text(ROOT / rel) == _text(theirs), f"{rel} differs from {theirs}"
