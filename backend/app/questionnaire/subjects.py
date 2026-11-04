from __future__ import annotations

from typing import Any

ARTISAN = "A"
RESPONDENT = "R"
PREFIXES = (ARTISAN, RESPONDENT)


def subject_key(artisan_id: str | None = None, respondent_id: str | None = None) -> str:
    if bool(artisan_id) == bool(respondent_id):
        raise ValueError("A subject is exactly one of an artisan or a respondent.")
    return f"{ARTISAN}:{artisan_id}" if artisan_id else f"{RESPONDENT}:{respondent_id}"


def parse_subject_key(key: str) -> tuple[str, str]:
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
    def get(name: str) -> Any:
        return row.get(name) if isinstance(row, dict) else getattr(row, name, None)

    return subject_key(get("artisanId") or None, get("respondentId") or None)


def artisan_ids(keys: list[str]) -> list[str]:
    return [rest for prefix, rest in (parse_subject_key(k) for k in keys) if prefix == ARTISAN]


def respondent_ids(keys: list[str]) -> list[str]:
    return [rest for prefix, rest in (parse_subject_key(k) for k in keys) if prefix == RESPONDENT]


def subject_key_sql(alias: str) -> str:
    return (
        f"CASE WHEN {alias}.\"artisanId\" IS NOT NULL THEN 'A:' || {alias}.\"artisanId\" "
        f"ELSE 'R:' || {alias}.\"respondentId\" END"
    )


SUBJECT_KEY_SQL = subject_key_sql("s")
