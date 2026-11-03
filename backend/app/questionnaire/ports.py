from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

PORTS_MODULE = "app.services.questionnaire_ports"

MODULES = (
    "consent_gate",
    "naming",
    "s3",
    "transcription",
    "workshop_access",
    "records",
    "answer_drafts",
    "codes",
)

REQUIRED = (
    "db", "get_settings", "has_rank", "is_admin", "is_content_admin", "can_review", "can_create_records",
    "can_manage_questionnaire", "module", "PORTAL",
)


class PortUnavailable(RuntimeError):
    pass


def impl() -> ModuleType:
    return importlib.import_module(PORTS_MODULE)


def portal() -> str:
    return str(impl().PORTAL)


def db() -> Any:
    return impl().db()


def get_settings() -> Any:
    return impl().get_settings()


def setting(name: str, default: Any = None) -> Any:
    return getattr(get_settings(), name, default)


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


class _Module:
    def __init__(self, name: str) -> None:
        object.__setattr__(self, "_name", name)

    def _target(self) -> ModuleType:
        return impl().module(object.__getattribute__(self, "_name"))

    def __getattr__(self, attr: str) -> Any:
        return getattr(self._target(), attr)

    def __setattr__(self, attr: str, value: Any) -> None:
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
