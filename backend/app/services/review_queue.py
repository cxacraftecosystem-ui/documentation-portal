"""WHO MAY REVIEW WHOSE RECORDS, as a query: the predicate behind the review queue.

Two readers share it and must never disagree: the queue itself (``GET /api/review/pending`` in
``app/api/routes/review.py``) and the Monday summary's "Records to review" count
(``app/services/weekly_digest.py``). A count that used a different rule from the list it links to
would promise a reviewer work they cannot open.

The designer portal has a module of the same name with the same two functions.
"""

from __future__ import annotations

from typing import Any

from app.core.deps import ROLE_RANK, is_master_admin, role_rank

#: The record types the queue holds, by Prisma delegate name. Media is left out, as in the queue.
PENDING_RECORD_DELEGATES: tuple[tuple[str, str], ...] = (
    ("artisan", "artisan"),
    ("workshop", "workshop"),
    ("product", "productdocumentation"),
    ("tool", "tooldocumentation"),
    ("process", "process"),
    ("questionnaire", "questionnaireinterview"),
)


def reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    """The creator roles this reviewer may act on, or None for "everyone" (the master admin).

    The peer-review ladder as DATA, so it can go into the query. The queue used to read the newest
    200 pending rows per record type and drop the unreviewable ones in Python afterwards: a
    professor whose researchers' submissions all sat behind 200 newer admin-created rows opened an
    EMPTY queue, and ``total`` counted rows they had no authority to touch. Filtering in the
    database makes the take apply to rows the caller can actually review.

    Same comparison ``can_review_record`` makes — ranks STRICTLY below the reviewer — expressed
    against the creator's role column. The two cannot drift apart over a missing role either: every
    reviewable model has a required ``createdById``, and ``UserRole`` is NOT NULL with a default, so
    the "no creator role on file" case ``can_review_record`` defends against does not exist in the
    rows this query walks.
    """
    if is_master_admin(reviewer):
        return None
    rank = role_rank(reviewer)
    return [role for role, value in ROLE_RANK.items() if value < rank]


def pending_where(reviewer: Any) -> dict[str, Any] | None:
    """The ``where`` for the PENDING records this reviewer may act on, or None when there are none
    by construction (review access with nobody beneath them on the ladder)."""
    roles = reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        return None
    where: dict[str, Any] = {"status": "PENDING"}
    if roles is not None:
        where["createdBy"] = {"is": {"role": {"in": roles}}}
    return where
