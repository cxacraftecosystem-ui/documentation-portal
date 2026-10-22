"""The readers' view of the shared questionnaire on Field Repository (UNIFY_2026-10-21.md C10).

Task progress, record completeness and the dataset export read the legacy interview answers
(``QuestionnaireResponse``). With ``QUESTIONNAIRE_V2`` on, the same facts live in sessions, takes
(``questionnaire_take_v``, live sessions only) and answers (``QuestionnaireAnswer`` and its current
revision), and each reader adds them through the helpers here. A soft-deleted session is invisible
to every one of them (the live filter, owner decision 3).

While the switch is off every helper answers empty WITHOUT A QUERY, so the readers behave and cost
exactly as they did before.

The helpers are the shared package's (``app/questionnaire/readers.py``, the same in both portals),
scoped here by ``workshopId``.
"""

from __future__ import annotations

from app.questionnaire.readers import (  # noqa: F401 - the readers call them here
    _db,
    answer_counts,
    covered_pairs,
    enabled,
    session_answers,
    session_counts,
)
