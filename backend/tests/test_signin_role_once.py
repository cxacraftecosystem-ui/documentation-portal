"""The sign-in list rule at documentation-portal's own doors (``services/signin_role``, 2026-10-18).

``tests/test_signin_role_contract.py`` holds the shared rule; this file drives the doors that apply
it here, over the contract's vectors, against fakes:

* the provider sign-in (``auth._sign_in_verified_address``) creates a new account at the list's role
  and never writes the role of an existing one, while the configured master admin address is still
  restored to MASTER_ADMIN;
* the approval (``access_roster._role_kept``) never writes an account and says when one keeps its
  role;
* ``PATCH /api/users/{id}`` is the door that changes a role, and it writes the audit entry.

designer-portal's file of the same name drives its own doors over the same vectors.
"""

from __future__ import annotations

import inspect
import json
import pathlib
from types import SimpleNamespace
from typing import Any

import pytest

from app.api.routes import access_roster as access_routes
from app.api.routes import auth as auth_routes
from app.api.routes import users as users_routes
from app.core.config import get_settings
from app.services import signin_role

pytestmark = pytest.mark.anyio

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / "shared" / "signin-role-contract.json").read_text(encoding="utf-8"))
EMAIL = "person@example.org"


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


class _Users:
    """``db.user`` over at most one account, recording every write."""

    def __init__(self, account: Any | None):
        self.account = account
        self.updates: list[dict[str, Any]] = []
        self.creates: list[dict[str, Any]] = []

    async def find_unique(self, where):
        if self.account is not None and where in ({"email": self.account.email}, {"id": self.account.id}):
            return self.account
        return None

    async def update(self, where, data):
        self.updates.append(data)
        return SimpleNamespace(**{**vars(self.account), **data})

    async def create(self, data):
        self.creates.append(data)
        return SimpleNamespace(id="new-account", **data)


def _account(role: str, *, email: str = EMAIL) -> Any:
    return SimpleNamespace(
        id="acct-1", email=email, role=role, passwordHash=None, authProvider="GOOGLE", name="Person"
    )


def _wire(monkeypatch, users: _Users, *, listed: str | None) -> None:
    row = SimpleNamespace(grantedRole=listed, status="ACTIVE")

    async def _admitted(_email, _user=None):
        return None

    async def _row(_email):
        return row

    monkeypatch.setattr(auth_routes, "db", SimpleNamespace(user=users))
    monkeypatch.setattr(auth_routes, "assert_access_admitted", _admitted)
    monkeypatch.setattr(auth_routes, "roster_row", _row)
    monkeypatch.setattr(auth_routes, "invalidate_cached_user", lambda _id: None)


async def _sign_in(email: str = EMAIL) -> Any:
    return await auth_routes._sign_in_verified_address(
        email, display_name="Person", avatar_url=None, provider="GOOGLE"
    )


@pytest.mark.parametrize(
    "vector", CONTRACT["existingAccountAtSignIn"], ids=lambda v: f"{v['current']}+{v['listed']}"
)
async def test_an_existing_account_signs_in_at_its_own_role(monkeypatch, vector):
    users = _Users(_account(vector["current"]))
    _wire(monkeypatch, users, listed=vector["listed"])
    signed_in = await _sign_in()
    assert signed_in.role == vector["expect"]
    assert all("role" not in data for data in users.updates), users.updates
    assert users.creates == []


@pytest.mark.parametrize("vector", CONTRACT["newAccountRole"], ids=lambda v: f"{v['floor']}+{v['listed']}")
async def test_a_new_account_is_created_at_the_lists_role(monkeypatch, vector):
    monkeypatch.setattr(get_settings(), "default_signup_role", vector["floor"])
    users = _Users(None)
    _wire(monkeypatch, users, listed=vector["listed"])
    created = await _sign_in()
    assert created.role == vector["expect"]
    assert users.updates == []


async def test_the_master_admin_address_is_still_restored(monkeypatch):
    """The break-glass is not the list: its sign-in writes MASTER_ADMIN, as it always did."""
    master = get_settings().master_admin_email or "master@example.org"
    monkeypatch.setattr(get_settings(), "master_admin_email", master)
    users = _Users(_account("ADMIN", email=master.lower()))
    _wire(monkeypatch, users, listed=None)
    assert (await _sign_in(master.lower())).role == "MASTER_ADMIN"


@pytest.mark.parametrize("vector", CONTRACT["roleKeptSentence"], ids=lambda v: f"{v['current']}+{v['listed']}")
async def test_an_approval_never_writes_an_account_and_says_when_it_keeps_its_role(monkeypatch, vector):
    users = _Users(_account(vector["current"], email=vector["email"]) if vector["current"] else None)
    monkeypatch.setattr(access_routes, "db", SimpleNamespace(user=users))
    said = await access_routes._role_kept(vector["email"], vector["listed"])
    assert said == vector["expect"]
    assert (users.updates, users.creates) == ([], [])


def test_the_users_patch_is_the_door_that_audits_a_change_of_role():
    source = inspect.getsource(users_routes.update_user)
    assert "signin_role.audit_role_change(" in source
    assert "before=role_before" in source
    assert 'via="users"' in source


def test_no_sign_in_door_writes_a_role_from_the_list():
    """Source guard: the only role the provider sign-in writes onto an existing account is the
    break-glass MASTER_ADMIN. A future ``data["role"] = granted`` would be the rule undone."""
    source = inspect.getsource(auth_routes._sign_in_verified_address)
    writes = [line.strip() for line in source.splitlines() if 'data["role"]' in line]
    assert writes == ['data["role"] = "MASTER_ADMIN"'], writes
    assert "signin_role.role_for_new_account(" in source
    assert signin_role.NEW_ACCOUNTS_ONLY


@pytest.mark.parametrize("vector", CONTRACT["roleKeptSentence"], ids=lambda v: f"{v['current']}+{v['listed']}")
async def test_adding_an_address_never_writes_an_account_and_says_when_it_keeps_its_role(monkeypatch, vector):
    """Adding an address by hand admits it like an approval, so it answers the same sentence."""
    from app.schemas.access_roster import AccessRosterCreate

    users = _Users(_account(vector["current"], email=vector["email"]) if vector["current"] else None)

    class _Roster:
        async def find_unique(self, where):
            return None

    async def _admit(email, *, granted_role, full_name, notes, decided_by_id):
        return SimpleNamespace(email=email, grantedRole=granted_role)

    monkeypatch.setattr(access_routes, "db", SimpleNamespace(user=users, accessroster=_Roster()))
    monkeypatch.setattr(access_routes, "ensure_admitted", _admit)
    monkeypatch.setattr(access_routes, "roster_payload", lambda row, **_kw: {"email": row.email})
    monkeypatch.setattr(access_routes, "assert_role", lambda *_args: None)
    actor = SimpleNamespace(id="admin-1", role="MASTER_ADMIN")

    answer = await access_routes.add_to_roster(
        AccessRosterCreate(email=vector["email"], grantedRole=vector["listed"]), current_user=actor
    )
    assert answer["accountPromotionHeld"] == vector["expect"]
    assert (users.updates, users.creates) == ([], [])
