"""Field Repository's side of the shared questionnaire package's ports (``app/questionnaire/ports.py``).

The package is byte-identical in both portals and never imports portal code; it reaches this module
by its well-known name instead. Every function looks its target up at call time, so a test that
patches ``app.core.db.db`` or a service module is seen by the package.
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

PORTAL = "documentation-portal"

_MODULES = {
    "consent_gate": "app.services.consent_gate",
    "naming": "app.services.naming",
    "s3": "app.services.s3",
    "transcription": "app.services.transcription_enqueue",
    "workshop_access": "app.services.workshop_access",
    "records": "app.services.records",
    "answer_drafts": "app.services.answer_drafts",
    "codes": "app.services.cross_portal_codes",
}


def db() -> Any:
    from app.core import db as core_db

    return core_db.db


def get_settings() -> Any:
    from app.core.config import get_settings as settings

    return settings()


def has_rank(user: Any, role: str) -> bool:
    from app.core import roles

    return roles.has_rank(user, role)


def is_admin(user: Any) -> bool:
    from app.core import roles

    return roles.is_admin(user)


def is_content_admin(user: Any) -> bool:
    from app.core import roles

    return roles.is_content_admin(user)


def can_review(user: Any) -> bool:
    from app.core import roles

    return roles.can_review(user)


def can_create_records(user: Any) -> bool:
    from app.core import roles

    return roles.can_create_records(user)


def can_manage_questionnaire(user: Any) -> bool:
    from app.core import roles

    return roles.can_manage_questionnaire(user)


def module(name: str) -> ModuleType:
    return importlib.import_module(_MODULES[name])
