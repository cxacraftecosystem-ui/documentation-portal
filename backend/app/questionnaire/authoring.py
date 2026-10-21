"""WHO MAY CHANGE WHAT: the one authoring rule of both portals (owner, 21 Oct 2026, amended 22 Oct).

Pure functions over a user and a row; no database, no portal import. Both portals' routes call these,
so the rule is written once.

* **A form and everything in it** (title, description, kind, sections, questions, a workbook
  re-upload, retiring or deleting a question, deleting the form): its AUTHOR or a CONTENT ADMIN
  (Admin, Master Admin, Ministry Admin). A form with no recorded author is a content admin's alone.
  Everyone else is refused 403 and the clients hide the controls (owner, 22 Oct: "the author should
  be able to edit/delete the questionnaire apart from the admins").
* **Restoring a deleted form**: content admins.
* **Sessions and recordings** (delete, restore a session): content admins only. Uploaders and the
  session's researchers keep take restore, as before.
* **Sharing a form with everyone, the default form, a workshop type's default form, binding a form to
  a workshop**: admin acts (content admins). On designer-portal Android these controls are not
  offered (ministry/admin authoring is web-only there); Field Repository serves them on Android.
* **Creating your own form**: designers (DR) and Professor and above (FR), as before
  (``ports.can_manage_questionnaire``); whoever creates a form is its author.
"""

from __future__ import annotations

from typing import Any

#: The content admins. The same three names as ``CONTENT_ADMIN_ROLES`` in DR ``app/core/deps.py`` and
#: FR ``app/core/roles.py`` (``tests/test_questionnaire_core.py`` holds them together).
CONTENT_ADMIN_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})

#: The one refusal every content-admin-only delete gives.
CONTENT_DELETE_REFUSAL = "Only an admin can delete this."
#: The refusal for a change to someone else's form.
FORM_CHANGE_REFUSAL = "Only the form's author or an admin can change it."
#: The refusal for sharing, defaults and bindings.
ADMIN_ACT_REFUSAL = "Only an admin can do this."

PORTAL_DR = "designer-portal"
PORTAL_FR = "documentation-portal"

#: Acts that are ministry/admin authoring: web-only on designer-portal, everywhere on Field Repository.
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
    """The form's author: ``ownerId`` (designer-portal's column, the unified one), else FR's
    ``createdById``. None when nobody is recorded."""
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
    """Edit or delete a form or anything in it: its author or a content admin."""
    return is_content_admin(user) or (form is not None and is_form_author(form, user))


may_edit_form = may_change_form
may_delete_form = may_change_form


def may_restore_form(user: Any) -> bool:
    return is_content_admin(user)


def may_delete_session(user: Any) -> bool:
    return is_content_admin(user)


may_restore_session = may_delete_session
may_list_deleted_sessions = may_delete_session


def may_delete_recording(user: Any) -> bool:
    return is_content_admin(user)


def may_admin_act(user: Any) -> bool:
    """Share with everyone, set the default form or a type default, bind a form to a workshop."""
    return is_content_admin(user)


def offered_on(portal: str, platform: str, act: str) -> bool:
    """Whether a client offers an authoring act at all. Only ministry/admin authoring on
    designer-portal Android is withheld (owner decision 5); everything else is served everywhere."""
    if act in ADMIN_AUTHORING_ACTS and portal == PORTAL_DR and platform == "android":
        return False
    return True
