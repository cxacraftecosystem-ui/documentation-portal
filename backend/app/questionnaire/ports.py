"""THE ONE DOOR from the shared questionnaire package to portal code (UNIFY_2026-10-21.md, U2).

``backend/app/questionnaire/`` is byte-identical in designer-portal and documentation-portal
(``shared/questionnaire.sha256``), so no module in it may import ``app.core``, ``app.api`` or
``app.services``. Everything a portal must supply is reached through this module instead, and each
portal implements it in ``app/services/questionnaire_ports.py`` (the one well-known name below).

THE PORT IS RESOLVED ON EVERY CALL, never cached into a module global of the package: a test that
monkeypatches ``app.core.db.db``, ``app.services.s3.object_exists`` or a role helper is seen by the
package exactly as it was seen when these modules imported those names directly.

What a portal module provides (``tests/test_questionnaire_parity.py`` holds both to this list):

* ``db()``                     the connected Prisma client;
* ``get_settings()``           the portal's settings object;
* ``has_rank(user, role)``, ``is_admin``, ``is_content_admin``, ``can_review``,
  ``can_create_records``, ``can_manage_questionnaire``  the role ladder;
* ``module(name)``             one of :data:`MODULES`, the portal's own implementation;
* ``PORTAL``                   ``"designer-portal"`` or ``"documentation-portal"``.

A port a portal cannot serve yet raises :class:`PortUnavailable` naming the stream that wires it.
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

#: The module each portal provides. A string, so the package never imports portal code by name.
PORTS_MODULE = "app.services.questionnaire_ports"

#: The portal modules reached through :func:`module`.
MODULES = (
    "consent_gate",      # the opt-out consent gate (verdict, ALLOW, verdict_detail ...)
    "naming",            # generated names (identical file in both portals)
    "s3",                # object storage
    "transcription",     # the transcription enqueue (pause-aware)
    "workshop_access",   # may this user submit into this workshop
    "records",           # attach_location
    "answer_drafts",     # AI answer drafting
    "codes",             # cross-portal question codes
)

REQUIRED = (
    "db", "get_settings", "has_rank", "is_admin", "is_content_admin", "can_review", "can_create_records",
    "can_manage_questionnaire", "module", "PORTAL",
)


class PortUnavailable(RuntimeError):
    """This portal does not serve the port yet (the message names the stream that wires it)."""


def impl() -> ModuleType:
    return importlib.import_module(PORTS_MODULE)


def portal() -> str:
    return str(impl().PORTAL)


# ── the database and settings ──────────────────────────────────────────────────────────────────
def db() -> Any:
    return impl().db()


def get_settings() -> Any:
    return impl().get_settings()


def setting(name: str, default: Any = None) -> Any:
    """A setting the other portal may not have yet, read without failing."""
    return getattr(get_settings(), name, default)


# ── roles ──────────────────────────────────────────────────────────────────────────────────────
def has_rank(user: Any, role: str) -> bool:
    return bool(impl().has_rank(user, role))


def is_admin(user: Any) -> bool:
    return bool(impl().is_admin(user))


def is_content_admin(user: Any) -> bool:
    return bool(impl().is_content_admin(user))


def can_review(user: Any) -> bool:
    return bool(impl().can_review(user))


def can_create_records(user: Any) -> bool:
    return bool(impl().can_create_records(user))


def can_manage_questionnaire(user: Any) -> bool:
    return bool(impl().can_manage_questionnaire(user))


# ── portal modules ─────────────────────────────────────────────────────────────────────────────
class _Module:
    """Attribute access forwarded to the portal's module, looked up afresh on every access."""

    def __init__(self, name: str) -> None:
        object.__setattr__(self, "_name", name)

    def _target(self) -> ModuleType:
        return impl().module(object.__getattribute__(self, "_name"))

    def __getattr__(self, attr: str) -> Any:
        return getattr(self._target(), attr)

    def __setattr__(self, attr: str, value: Any) -> None:
        # A test's monkeypatch through the port patches the portal's module itself.
        setattr(self._target(), attr, value)

    def __delattr__(self, attr: str) -> None:
        delattr(self._target(), attr)

    def __repr__(self) -> str:
        return f"<questionnaire port {object.__getattribute__(self, '_name')}>"


consent_gate = _Module("consent_gate")
naming = _Module("naming")
s3 = _Module("s3")
transcription = _Module("transcription")
workshop_access = _Module("workshop_access")
records = _Module("records")
answer_drafts = _Module("answer_drafts")
#: Cross-portal question codes (``reconcile_question_codes``), the same module in both portals.
codes = _Module("codes")


async def enforce_workshop_submission(*args: Any, **kwargs: Any) -> Any:
    return await workshop_access.enforce_workshop_submission(*args, **kwargs)


async def describe_workshop_submission(*args: Any, **kwargs: Any) -> Any:
    return await workshop_access.describe_workshop_submission(*args, **kwargs)


def stamp_workshop_submission(*args: Any, **kwargs: Any) -> Any:
    return workshop_access.stamp_workshop_submission(*args, **kwargs)


def pin_pending_if_late(*args: Any, **kwargs: Any) -> Any:
    return workshop_access.pin_pending_if_late(*args, **kwargs)


async def attach_location(*args: Any, **kwargs: Any) -> Any:
    return await records.attach_location(*args, **kwargs)
