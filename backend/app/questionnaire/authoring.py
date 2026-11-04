from __future__ import annotations

from typing import Any

CONTENT_ADMIN_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})

CONTENT_DELETE_REFUSAL = "Only an admin can delete this."
FORM_CHANGE_REFUSAL = "Only the form's author or an admin can change it."
ADMIN_ACT_REFUSAL = "Only an admin can do this."

PORTAL_DR = "designer-portal"
PORTAL_FR = "documentation-portal"

ADMIN_AUTHORING_ACTS = frozenset({"share", "default", "type-default", "bind", "edit-others"})


def _get(obj: Any, name: str) -> Any:
    if obj is None:
        return None
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)


def role_of(user: Any) -> str:
    role = _get(user, "role")
    value = getattr(role, "value", role)
    return str(value or "").upper()


def user_id(user: Any) -> str | None:
    value = _get(user, "id")
    return str(value) if value else None


def is_content_admin(user: Any) -> bool:
    return role_of(user) in CONTENT_ADMIN_ROLES


def form_author_id(form: Any) -> str | None:
    for column in ("ownerId", "createdById"):
        value = _get(form, column)
        if value:
            return str(value)
    return None


def is_form_author(form: Any, user: Any) -> bool:
    author = form_author_id(form)
    me = user_id(user)
    return bool(author) and bool(me) and author == me


def may_change_form(user: Any, form: Any) -> bool:
    return is_content_admin(user) or (form is not None and is_form_author(form, user))


may_edit_form = may_change_form
may_delete_form = may_change_form


def may_restore_form(user: Any, form: Any = None) -> bool:
    return is_content_admin(user) or (form is not None and is_form_author(form, user))


def may_delete_session(user: Any) -> bool:
    return is_content_admin(user)


may_restore_session = may_delete_session
may_list_deleted_sessions = may_delete_session


def may_delete_recording(user: Any) -> bool:
    return is_content_admin(user)


def may_admin_act(user: Any) -> bool:
    return is_content_admin(user)


def offered_on(portal: str, platform: str, act: str) -> bool:
    return not (act in ADMIN_AUTHORING_ACTS and portal == PORTAL_DR and platform == "android")
