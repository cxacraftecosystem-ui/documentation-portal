"""The role ladder and the record-field comparisons: pure functions of a user (or a value), with no
FastAPI, no database and no settings.

They lived in app/core/deps.py, which imports FastAPI, the security module and the database client
because it also holds the request dependencies. The queue worker (app/worker.py) needs these helpers
through the services it runs (records, review_queue, weekly_digest, questionnaire sessions) and none
of the web layer, so they moved here on 2026-10-19 to keep FastAPI and Starlette out of the worker's
import graph (backend/tests/test_import_footprint.py pins that). deps.py imports every name back, so
``from app.core.deps import is_admin`` still works and returns the same function.
"""

from decimal import Decimal, InvalidOperation
from typing import Any


def get_value(obj: Any, field: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(field)
    return getattr(obj, field, None)


def role_value(user: Any) -> str:
    role = get_value(user, "role")
    return str(getattr(role, "value", role))


# The six-tier role ladder, strictly ordered. Higher rank inherits every power of the ranks below
# it; grantable capability booleans can additionally lift a specific power for a lower tier.
ROLE_RANK: dict[str, int] = {
    "CROWDSOURCE_VOLUNTEER": 10,
    "FIELD_CONTRIBUTOR": 20,
    "RESEARCHER": 30,
    "PROFESSOR": 40,
    "ADMIN": 50,
    "MASTER_ADMIN": 60,
}

ROLE_LABELS: dict[str, str] = {
    "CROWDSOURCE_VOLUNTEER": "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR": "Field Contributor",
    "RESEARCHER": "Researcher",
    "PROFESSOR": "Professor",
    "ADMIN": "Admin",
    "MASTER_ADMIN": "Master Admin",
}


def role_rank(user_or_role: Any) -> int:
    """Rank of a user object or a bare role string; unknown roles rank lowest."""
    role = user_or_role if isinstance(user_or_role, str) else role_value(user_or_role)
    return ROLE_RANK.get(str(role), 0)


def has_rank(user: Any, role: str) -> bool:
    return role_rank(user) >= ROLE_RANK[role]


def is_admin(user: Any) -> bool:
    return role_value(user) in {"MASTER_ADMIN", "ADMIN"}


def is_master_admin(user: Any) -> bool:
    return role_value(user) == "MASTER_ADMIN"


#: The CONTENT admins (owner's ruling, 2026-10-20): they add, edit and delete every record type, every
#: media file and every other managed content type, wherever it is managed. Every other role adds and
#: edits within its existing privileges and never deletes.
#:
#: A SET AND NOT A FLOOR, and deliberately NOT ``is_admin``, which keeps its two members for accounts
#: and governance. This portal has no MINISTRY_ADMIN tier; the set lists it by name so the rule holds
#: the day the two ladders merge. The designer-portal spells this constant and
#: :func:`is_content_admin` identically in ``app/core/deps.py``; ``frontend/lib/permissions.ts`` and
#: the handset's ``FieldPermissions.isContentAdmin`` carry the same three names, and
#: ``tests/test_content_admin_parity.py`` holds them together.
CONTENT_ADMIN_ROLES = frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"})


def is_content_admin(user: Any) -> bool:
    """May this account delete records and media, and edit every record whoever made it?"""
    return role_value(user) in CONTENT_ADMIN_ROLES


#: The one refusal every delete gives a role outside :data:`CONTENT_ADMIN_ROLES`.
CONTENT_DELETE_REFUSAL = "Only an admin can delete this."


def can_manage_questionnaire(user: Any) -> bool:
    """Edit the questionnaire structure: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canManageQuestionnaire"))


def can_manage_crafts(user: Any) -> bool:
    """Create or update a craft: Professor and above, RANK ALONE.

    The ``canManageCrafts`` column is deliberately no longer read. It was a per-user grant a master
    admin could hand to a researcher or below, which is the one clause that let the taxonomy itself
    be written by someone the permission matrix places under it — and a grant that widens a rank
    floor is invisible in the role column, so nobody auditing the user table could see who held it.
    The column stays in the schema (dropping it is neither safe nor reversible, and no live account
    below Professor holds it), simply unread; restoring the old behaviour is putting the clause back.
    """
    return has_rank(user, "PROFESSOR")


def can_manage_workshops(user: Any) -> bool:
    """Create or update a workshop: Professor and above, RANK ALONE — see ``can_manage_crafts`` for
    why the ``canManageWorkshops`` grant is no longer consulted."""
    return has_rank(user, "PROFESSOR")


def can_review_record(reviewer: Any, creator_role: Any) -> bool:
    """The peer-review hierarchy: the master admin reviews EVERYONE's work; everyone else may only
    review records whose creator ranks STRICTLY below them (admin reviews everyone beneath,
    professor reviews researchers and below, researcher reviews field contributors and volunteers,
    field contributor reviews volunteers). A record with no creator role on file is treated as a
    researcher's work."""
    if is_master_admin(reviewer):
        return True
    role = getattr(creator_role, "value", creator_role)
    if not role:
        role = "RESEARCHER"
    return role_rank(reviewer) > ROLE_RANK.get(str(role), ROLE_RANK["RESEARCHER"])


def can_edit_others_record(user: Any, creator_role: Any) -> bool:
    """May *user* edit a record created by *creator_role* as fully as its own author can?

    "A professor may edit the data of anyone ranked below them." The comparison for "below" is
    ``can_review_record``'s and nothing else's: a second hand-rolled rank test that disagreed with
    the review ladder by one tier is exactly the shape a privilege bug hides in. It is then narrowed
    to Professor and above, because the review ladder deliberately reaches further down than editing
    is meant to — a field contributor reviews a volunteer's work, but does not get to rewrite it.
    (``services/records.apply_status_policy_update`` composes the same two halves for status
    changes, which is where this pairing comes from.)
    """
    return has_rank(user, "PROFESSOR") and can_review_record(user, creator_role)


def can_access_review(user: Any) -> bool:
    """May open the review queue: Field Contributor and above (everyone who has someone beneath
    them on the ladder), or an explicit review grant. Which specific records they may act on is
    decided per record by can_review_record."""
    return has_rank(user, "FIELD_CONTRIBUTOR") or bool(get_value(user, "canReview"))


def can_review(user: Any) -> bool:
    """Back-compat alias for can_access_review — page-level review access, not per-record."""
    return can_access_review(user)


def can_download_dataset(user: Any) -> bool:
    """May download the entire dataset: Professor and above, or an explicit grant."""
    return has_rank(user, "PROFESSOR") or bool(get_value(user, "canDownloadDataset"))


def can_create_records(user: Any) -> bool:
    """May create core records (artisans, products, tools, processes, interviews): Researcher and
    above.

    The two tiers below — Field Contributor and Crowdsource Volunteer — POPULATE records rather than
    open them: attaching media to an existing artisan, answering questions in an existing interview,
    and commenting. Those three paths are the reason the tiers exist and none of them passes through
    here; they are gated by ``get_current_user`` on /media, /questionnaire's response arms, and
    /data-access/comments respectively."""
    return has_rank(user, "RESEARCHER")


def is_empty_value(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, tuple, set, dict)):
        return len(value) == 0
    return False


def enum_or_raw(value: Any) -> Any:
    return getattr(value, "value", value)


def values_match(current_value: Any, next_value: Any) -> bool:
    current_value = enum_or_raw(current_value)
    next_value = enum_or_raw(next_value)
    if current_value == next_value:
        return True
    try:
        return Decimal(str(current_value)) == Decimal(str(next_value))
    except (InvalidOperation, ValueError):
        return str(current_value) == str(next_value)
