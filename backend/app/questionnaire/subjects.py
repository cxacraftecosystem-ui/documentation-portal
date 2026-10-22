"""THE SUBJECT KEY (UNIFY_2026-10-21.md section 2.3, rule 1): who a take, an answer or a coverage
cell is about.

``A:<artisanId>`` for a registered artisan, ``R:<respondentId>`` for a guest respondent (a named
person who is not a registered artisan, or the signed-in designer answering their own form). Guests
appear on a session's grid under their key; they have no artisan record. When a guest is registered
as an artisan (``respondents.register``) their rows move to the artisan and the key becomes ``A:``.

``questionnaire_take_v`` carries the same key as ``"subjectKey"``, built by :data:`SUBJECT_KEY_SQL`.
"""

from __future__ import annotations

from typing import Any

ARTISAN = "A"
RESPONDENT = "R"
PREFIXES = (ARTISAN, RESPONDENT)


def subject_key(artisan_id: str | None = None, respondent_id: str | None = None) -> str:
    """The key of exactly one owner; anything else is a ``ValueError``."""
    if bool(artisan_id) == bool(respondent_id):
        raise ValueError("A subject is exactly one of an artisan or a respondent.")
    return f"{ARTISAN}:{artisan_id}" if artisan_id else f"{RESPONDENT}:{respondent_id}"


def parse_subject_key(key: str) -> tuple[str, str]:
    """``("A", id)`` or ``("R", id)``; a ``ValueError`` for anything else."""
    prefix, sep, rest = (key or "").partition(":")
    if not sep or prefix not in PREFIXES or not rest:
        raise ValueError(f"{key!r} is not a subject key (A:<artisanId> or R:<respondentId>).")
    return prefix, rest


def is_subject_key(key: Any) -> bool:
    try:
        parse_subject_key(str(key))
    except ValueError:
        return False
    return True


def subject_of(row: Any) -> str:
    """The key of a row carrying ``artisanId`` and/or ``respondentId`` (a dict or an object)."""

    def get(name: str) -> Any:
        return row.get(name) if isinstance(row, dict) else getattr(row, name, None)

    return subject_key(get("artisanId") or None, get("respondentId") or None)


def artisan_ids(keys: list[str]) -> list[str]:
    return [rest for prefix, rest in (parse_subject_key(k) for k in keys) if prefix == ARTISAN]


def respondent_ids(keys: list[str]) -> list[str]:
    return [rest for prefix, rest in (parse_subject_key(k) for k in keys) if prefix == RESPONDENT]


def subject_key_sql(alias: str) -> str:
    """The SQL expression of the key over a row ``alias`` with ``artisanId``/``respondentId``."""
    return (
        f"CASE WHEN {alias}.\"artisanId\" IS NOT NULL THEN 'A:' || {alias}.\"artisanId\" "
        f"ELSE 'R:' || {alias}.\"respondentId\" END"
    )


SUBJECT_KEY_SQL = subject_key_sql("s")
