"""The sign-in gate's behaviour: the two distinct refusals, the Google allow-list, and the bounds.

WHAT THIS FILE IS DEFENDING. The product owner ruled that "wrong password" and "awaiting approval"
must be DIFFERENT answers, and that Google sign-in is subject to the allow-list like everything else.
Both rulings are easy to lose to a well-meaning "let's not leak whether the account exists" edit, so
the exact sentences and the exact status codes are asserted here, not paraphrased.

The other half is the part that makes the first half survivable in production: a PENDING row is
written by an UNAUTHENTICATED caller, so the tests below pin down what it costs an attacker (proof
of ownership of the address), how big it can get (one row per address, up to a ceiling), and what it
can be made to contain (the address, and nothing else).

Nothing here touches a database — see ``tests/access_roster_fakes.py``. The real routers are mounted
and driven over HTTP, so the assertions are about the bytes a client receives, which is what the
frontend and Android lanes have to render.
"""

import asyncio
from datetime import datetime
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from access_roster_fakes import FakeDb, install
from app.api.router import api_router
from app.api.routes import auth as auth_routes
from app.core.config import get_settings
from app.core.security import hash_password
from app.services.access_roster import (
    ACCESS_PENDING_DETAIL,
    ACCESS_REJECTED_DETAIL,
    ACCESS_SUSPENDED_DETAIL,
    INVALID_CREDENTIALS_DETAIL,
)

PASSWORD = "correct-horse-battery"
MASTER_EMAIL = "master@example.org"

_APP = FastAPI()
_APP.include_router(api_router)


def _post(path: str, body: dict[str, Any]) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://gate.test") as client:
            return await client.post(path, json=body)

    return asyncio.run(run())


def _login(email: str, password: str = PASSWORD) -> httpx.Response:
    return _post("/api/auth/login", {"email": email, "password": password})


def _google(token: str = "a-verified-token") -> httpx.Response:
    return _post("/api/auth/login", {"googleIdToken": token})


def _detail(response: httpx.Response) -> Any:
    return response.json().get("detail")


def _account(email: str, role: str = "RESEARCHER", **extra: Any) -> dict[str, Any]:
    return {
        "id": f"id-{email}",
        "email": email,
        "name": "Existing Person",
        "role": role,
        "passwordHash": hash_password(PASSWORD),
        **extra,
    }


def _roster(email: str, status: str, **extra: Any) -> dict[str, Any]:
    return {"id": f"roster-{email}", "email": email, "status": status, **extra}


@pytest.fixture(autouse=True)
def settings_under_test(monkeypatch: pytest.MonkeyPatch) -> Any:
    """Pin the two settings the gate reads, so a developer's ``.env`` cannot change the outcome."""
    settings = get_settings()
    monkeypatch.setattr(settings, "master_admin_email", MASTER_EMAIL)
    monkeypatch.setattr(settings, "access_roster_enforced", True)
    monkeypatch.setattr(settings, "access_roster_max_pending", 2000)
    monkeypatch.setattr(settings, "default_signup_role", "CROWDSOURCE_VOLUNTEER")
    return settings


@pytest.fixture
def google(monkeypatch: pytest.MonkeyPatch):
    """Stand in for Google's verifier. The token is assumed valid; this file is about what happens
    AFTER a verified identity, which is where the allow-list now sits."""

    def issue(email: str, name: str = "Google Display Name", picture: str = "https://pic") -> None:
        monkeypatch.setattr(
            auth_routes,
            "verify_google_token",
            lambda token: {
                "email": email,
                "email_verified": True,
                "name": name,
                "picture": picture,
            },
        )

    return issue


# --- THE TWO REFUSALS ------------------------------------------------------------------------------


def test_pending_and_wrong_password_are_different_answers(monkeypatch: pytest.MonkeyPatch) -> None:
    """THE RULING, as an assertion. If these two ever collapse into one answer, this fails.

    A person waiting on an administrator is TOLD they are waiting. Do not "fix" this by returning
    401 for both: the enumeration it costs was weighed and accepted, and the alternative is somebody
    resetting a password that was never wrong for a week.
    """
    install(
        monkeypatch,
        FakeDb(
            users=[_account("waiting@example.org")],
            roster=[_roster("waiting@example.org", "PENDING")],
        ),
    )

    pending = _login("waiting@example.org")
    wrong = _login("waiting@example.org", "not-the-password")

    assert pending.status_code == 403
    assert _detail(pending) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert wrong.status_code == 401
    assert _detail(wrong) == INVALID_CREDENTIALS_DETAIL
    assert _detail(pending) != _detail(wrong)


def test_the_pending_refusal_leaks_nothing_beyond_the_wait(monkeypatch: pytest.MonkeyPatch) -> None:
    """The distinct answer is allowed to say "you are waiting" and nothing else.

    No name, no role, no other account, no statement about whether a password was ever set. The
    enumeration widened on purpose; it must not widen any further by accident.
    """
    install(
        monkeypatch,
        FakeDb(
            users=[_account("waiting@example.org", role="ADMIN")],
            roster=[
                _roster("waiting@example.org", "PENDING", fullName="Priya Sharma", grantedRole="ADMIN")
            ],
        ),
    )
    body = _login("waiting@example.org").text
    for leak in ("Priya", "Sharma", "ADMIN", "password hash", "waiting@example.org"):
        assert leak not in body, f"The pending refusal leaked {leak!r}: {body}"


def test_rejected_and_suspended_each_get_their_own_words(monkeypatch: pytest.MonkeyPatch) -> None:
    """Three refusals, three sentences. Telling a rejected person they are "awaiting approval" is a
    lie that has them waiting forever; telling a suspended person they are "not approved" hides a
    revocation they might get reversed today."""
    install(
        monkeypatch,
        FakeDb(
            users=[_account("no@example.org"), _account("stopped@example.org")],
            roster=[
                _roster("no@example.org", "REJECTED"),
                _roster("stopped@example.org", "SUSPENDED"),
            ],
        ),
    )
    rejected = _login("no@example.org")
    suspended = _login("stopped@example.org")

    assert rejected.status_code == 403
    assert _detail(rejected) == {"code": "ACCESS_REJECTED", "message": ACCESS_REJECTED_DETAIL}
    assert suspended.status_code == 403
    assert _detail(suspended) == {"code": "ACCESS_SUSPENDED", "message": ACCESS_SUSPENDED_DETAIL}
    assert ACCESS_REJECTED_DETAIL != ACCESS_SUSPENDED_DETAIL != ACCESS_PENDING_DETAIL


# --- EXISTENCE IS NOT ADMISSION ---------------------------------------------------------------------


@pytest.mark.parametrize("refused_status", ["PENDING", "REJECTED", "SUSPENDED"])
def test_only_an_active_row_admits(monkeypatch: pytest.MonkeyPatch, refused_status: str) -> None:
    """The bypass this whole design is arranged around: a row EXISTS for people who were refused,
    because the refusal itself writes one. Any check that tests for a row rather than for ACTIVE is
    an authentication bypass an attacker can arrange on their own address."""
    install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", refused_status)],
        ),
    )
    assert _login("person@example.org").status_code == 403


def test_an_active_row_admits(monkeypatch: pytest.MonkeyPatch) -> None:
    install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", "ACTIVE")],
        ),
    )
    response = _login("person@example.org")
    assert response.status_code == 200
    assert response.json()["accessToken"]


def test_the_first_sign_in_is_stamped_once(monkeypatch: pytest.MonkeyPatch) -> None:
    """``firstSeenAt`` answers "which approvals were ever taken up", so it records the FIRST sign-in
    and is never moved by a later one."""
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("person@example.org")],
            roster=[_roster("person@example.org", "ACTIVE")],
        ),
    )
    assert _login("person@example.org").status_code == 200
    first = fake.accessroster.rows[0].firstSeenAt
    assert isinstance(first, datetime)
    assert _login("person@example.org").status_code == 200
    assert fake.accessroster.rows[0].firstSeenAt == first


# --- MASTER_ADMIN IS NEVER GATED ---------------------------------------------------------------------


def test_the_master_admin_signs_in_with_no_roster_row_at_all(monkeypatch: pytest.MonkeyPatch) -> None:
    """THE BREAK-GLASS. The roster is a table only an administrator can edit, so an administrator
    locked out by it is an outage with nobody left able to fix it."""
    install(
        monkeypatch,
        FakeDb(users=[_account(MASTER_EMAIL, role="MASTER_ADMIN")], roster=[]),
    )
    assert _login(MASTER_EMAIL).status_code == 200


def test_the_master_admin_signs_in_through_a_suspended_row(monkeypatch: pytest.MonkeyPatch) -> None:
    """Even an actively hostile roster entry cannot shut the break-glass."""
    install(
        monkeypatch,
        FakeDb(
            users=[_account(MASTER_EMAIL, role="MASTER_ADMIN")],
            roster=[_roster(MASTER_EMAIL, "SUSPENDED")],
        ),
    )
    assert _login(MASTER_EMAIL).status_code == 200


def test_a_second_master_admin_is_exempt_by_role(monkeypatch: pytest.MonkeyPatch) -> None:
    """A master admin minted through ``POST /users`` does not hold the configured address, so the
    role has to exempt them too — otherwise the institution can still lock itself out."""
    install(
        monkeypatch,
        FakeDb(
            users=[_account("second.master@example.org", role="MASTER_ADMIN")],
            roster=[_roster("second.master@example.org", "REJECTED")],
        ),
    )
    assert _login("second.master@example.org").status_code == 200


# --- GOOGLE IS GATED ----------------------------------------------------------------------------------


def test_an_unknown_google_address_gets_no_account_and_no_token(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    """THE BIGGEST BEHAVIOURAL CHANGE IN THIS FEATURE.

    Before the gate, this exact request created a ``User`` row and returned 200 with a bearer token
    — for any verified Google address on earth. Now it creates a PENDING roster entry and refuses.
    """
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")

    response = _google()

    assert response.status_code == 403
    assert _detail(response) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert fake.user.rows == [], "Google self-provisioning must be dead: no account may be created."
    assert [row.email for row in fake.accessroster.rows] == ["stranger@example.org"]
    assert fake.accessroster.rows[0].status == "PENDING"


def test_the_pending_row_stores_the_address_and_nothing_else(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    """The pending queue is the ONE screen in this product where a stranger can cause content to
    appear in front of an administrator. The display name and picture Google hands us are chosen by
    the account holder, so neither is stored."""
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org", name="<script>alert(1)</script>", picture="https://evil/x.png")

    _google()

    row = fake.accessroster.rows[0]
    assert row.fullName is None
    assert row.notes is None
    assert "script" not in str(row.__dict__)
    assert row.grantedRole == "CROWDSOURCE_VOLUNTEER", (
        "An unapproved address must sit at the lowest rung until an admin promotes it on the roster."
    )


def test_an_admitted_google_address_is_provisioned_at_its_granted_tier(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    """The other half of "keyed by email": an admin admits somebody who has never opened the app,
    and the account provisions itself, at the tier the admin chose, on first sign-in."""
    fake = install(
        monkeypatch,
        FakeDb(users=[], roster=[_roster("invited@example.org", "ACTIVE", grantedRole="RESEARCHER")]),
    )
    google("invited@example.org")

    response = _google()

    assert response.status_code == 200
    assert len(fake.user.rows) == 1
    assert fake.user.rows[0].role == "RESEARCHER"


def test_a_stale_roster_tier_never_demotes_an_existing_account(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    """``grantedRole`` is a provisioning floor, not a live authority. An admin promoted through the
    users screen must not be knocked back down by their own next Google sign-in — a demotion that
    would be invisible in the login response and would change who may review whose work."""
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("promoted@example.org", role="ADMIN")],
            roster=[
                _roster("promoted@example.org", "ACTIVE", grantedRole="CROWDSOURCE_VOLUNTEER")
            ],
        ),
    )
    google("promoted@example.org")

    assert _google().status_code == 200
    assert fake.user.rows[0].role == "ADMIN"


def test_a_refused_google_sign_in_does_not_touch_the_existing_account(
    monkeypatch: pytest.MonkeyPatch, google
) -> None:
    """The gate runs BEFORE the write. The Google branch used to overwrite name, avatar and auth
    provider on every sign-in; a suspended person must not be able to rewrite their own account row
    with an unadmitted token."""
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("stopped@example.org", name="Real Name", avatarUrl=None)],
            roster=[_roster("stopped@example.org", "SUSPENDED")],
        ),
    )
    google("stopped@example.org", name="Rewritten", picture="https://evil/x.png")

    assert _google().status_code == 403
    assert fake.user.rows[0].name == "Real Name"
    assert fake.user.rows[0].avatarUrl is None


def test_the_google_wrapper_endpoint_is_gated_too(monkeypatch: pytest.MonkeyPatch, google) -> None:
    """``POST /auth/google`` delegates to ``login``; this proves the delegation has not been
    bypassed, because it is the endpoint the Android client actually calls."""
    install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")
    response = _post("/api/auth/google", {"googleIdToken": "a-verified-token"})
    assert response.status_code == 403


# --- THE UNAUTHENTICATED WRITE, AND ITS BOUNDS ---------------------------------------------------------


def test_a_wrong_password_writes_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    """PROOF OF OWNERSHIP COMES FIRST. An unknown address with a guessed password has proved
    nothing, so it is told nothing and it writes nothing — otherwise the administrators' queue would
    be writable at line rate by an anonymous script and the notification would drown in it."""
    fake = install(monkeypatch, FakeDb(users=[_account("real@example.org")], roster=[]))

    # Long enough to pass ``LoginRequest``'s min_length; a shorter one would 422 before the handler
    # and this test would be measuring pydantic rather than the gate.
    assert _login("real@example.org", "wrong-password-entirely").status_code == 401
    assert _login("nobody@example.org", "anything-at-all").status_code == 401
    assert fake.accessroster.rows == []


def test_one_person_hammering_the_form_is_one_row(monkeypatch: pytest.MonkeyPatch, google) -> None:
    """Dedupe by address is what makes the unauthenticated write bounded. Ten attempts, one row, a
    counter an administrator can read."""
    fake = install(monkeypatch, FakeDb(users=[], roster=[]))
    google("stranger@example.org")

    for _ in range(10):
        assert _google().status_code == 403

    assert len(fake.accessroster.rows) == 1
    row = fake.accessroster.rows[0]
    assert row.requestCount == 10
    assert row.lastRequestedAt >= row.firstRequestedAt


def test_a_rejected_person_does_not_re_queue(monkeypatch: pytest.MonkeyPatch, google) -> None:
    """THE DECISION STAYS A DECISION. Re-opening on re-attempt would make a rejection a timer: the
    same request lands back in the queue every day and the administrator has no way to stop it. The
    counter is how their persistence stays visible."""
    fake = install(
        monkeypatch,
        FakeDb(users=[], roster=[_roster("no@example.org", "REJECTED", requestCount=3)]),
    )
    google("no@example.org")

    response = _google()

    assert _detail(response)["code"] == "ACCESS_REJECTED"
    row = fake.accessroster.rows[0]
    assert row.status == "REJECTED"
    assert row.requestCount == 4


def test_at_the_ceiling_a_new_address_is_refused_without_a_row(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    """The cap is a backstop against a flood. At it, the person still reads the identical sentence —
    nothing about the ceiling is observable to a caller — and no row is stored."""
    monkeypatch.setattr(settings_under_test, "access_roster_max_pending", 1)
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("first@example.org", "PENDING")]))
    google("second@example.org")

    response = _google()

    assert response.status_code == 403
    assert _detail(response) == {"code": "ACCESS_PENDING", "message": ACCESS_PENDING_DETAIL}
    assert [row.email for row in fake.accessroster.rows] == ["first@example.org"]


def test_the_ceiling_does_not_stop_an_existing_row_from_counting(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    """Bumping a counter grows nothing, so an address already on the roster keeps working at the
    cap. A cap that froze existing rows would lose the one signal an admin has about who is waiting
    hardest."""
    monkeypatch.setattr(settings_under_test, "access_roster_max_pending", 1)
    fake = install(monkeypatch, FakeDb(users=[], roster=[_roster("first@example.org", "PENDING")]))
    google("first@example.org")

    assert _google().status_code == 403
    assert fake.accessroster.rows[0].requestCount == 2


# --- THE KILL SWITCH ------------------------------------------------------------------------------------


def test_the_kill_switch_restores_the_previous_behaviour(
    monkeypatch: pytest.MonkeyPatch, google, settings_under_test
) -> None:
    """If the back-fill ever turns out to have missed somebody in production, the remedy has to be
    faster than a deploy. Off, the gate admits everybody exactly as this application did before it
    existed — including Google self-provisioning, which is why it is not a feature flag."""
    monkeypatch.setattr(settings_under_test, "access_roster_enforced", False)
    fake = install(
        monkeypatch,
        FakeDb(
            users=[_account("locked.out@example.org")],
            roster=[_roster("locked.out@example.org", "PENDING")],
        ),
    )

    assert _login("locked.out@example.org").status_code == 200

    google("stranger@example.org")
    assert _google().status_code == 200
    assert len(fake.user.rows) == 2
