"""The one consent store: the same model, migration and rule in both portals (19 Oct 2026).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal (listed in
``shared/transcription.sha256``), so each portal's CI proves its own copy of the shared rule, the
shared migration and its own ``schema.prisma`` against the same expectations. No database.
"""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from app.transcription import ledger

BACKEND = Path(__file__).resolve().parents[1]
MIGRATION = BACKEND / "prisma" / "migrations" / "20261103090000_one_consent_store" / "migration.sql"
T0 = datetime(2026, 10, 18, 0, 0, tzinfo=UTC)


def _row(state: str, at: datetime | None = None) -> dict[str, Any]:
    return {"state": state, "recordedAt": at}


# ── The rule ──────────────────────────────────────────────────────────────────────────────────


def test_no_answer_is_consent_assumed_by_default_and_is_sent() -> None:
    """Owner decision, 19 Oct 2026: "the consent should be assumed by default, people can revoke it
    if required". Nothing recorded, and a recording with no subject at all, are sent."""
    assert ledger.decide(None, []) == ledger.ALLOW
    assert ledger.decide(None, [None]) == ledger.ALLOW
    assert ledger.decide(None, [_row("GRANTED"), None]) == ledger.ALLOW
    assert ledger.decide(None, ["NOT_RECORDED", None]) == ledger.ALLOW


def test_the_rule_never_answers_awaiting() -> None:
    states = (None, "NOT_RECORDED", "GRANTED", "REFUSED")
    for own in states:
        for a in states:
            for b in states:
                assert ledger.decide(own, [a, b]) != ledger.AWAITING


def test_any_refusal_refuses_and_a_later_grant_allows_again() -> None:
    assert ledger.decide(None, [_row("GRANTED"), "GRANTED"]) == ledger.ALLOW
    assert ledger.decide(None, [_row("GRANTED"), _row("REFUSED")]) == ledger.REFUSED
    assert ledger.decide(None, [None, _row("REFUSED")]) == ledger.REFUSED, "one refusal among the unanswered"
    # Each subject is read at its newest row: a grant recorded after the refusal replaces it.
    assert ledger.decide(None, [None, _row("GRANTED", T0 + timedelta(days=1))]) == ledger.ALLOW


def test_the_recordings_own_refusal_wins_over_every_grant() -> None:
    assert ledger.decide(_row("REFUSED", T0), [_row("GRANTED", T0)]) == ledger.REFUSED
    assert ledger.decide("REFUSED", []) == ledger.REFUSED


def test_the_recordings_own_grant_allows_unless_a_subject_refused_after_it() -> None:
    later = T0 + timedelta(days=2)
    earlier = T0 - timedelta(days=2)
    assert ledger.decide(_row("GRANTED", T0), []) == ledger.ALLOW
    assert ledger.decide(_row("GRANTED", T0), [_row("REFUSED", earlier)]) == ledger.ALLOW
    assert ledger.decide(_row("GRANTED", T0), [_row("REFUSED", later)]) == ledger.REFUSED
    # Raw-query timestamps arrive as text; a bare state carries no moment and cannot be later.
    assert ledger.decide(_row("GRANTED", T0), [_row("REFUSED", later.isoformat())]) == ledger.REFUSED
    assert ledger.decide("GRANTED", ["REFUSED"]) == ledger.ALLOW


def test_the_verdict_reads_as_the_consent_words() -> None:
    assert ledger.state_for(ledger.ALLOW) == "GRANTED"
    assert ledger.state_for(ledger.REFUSED) == "REFUSED"
    assert ledger.state_for(ledger.AWAITING) == "NOT_RECORDED"


# ── The one write ─────────────────────────────────────────────────────────────────────────────


class _Ledger:
    """The ``Consent`` table's two unique keys, in memory."""

    def __init__(self) -> None:
        self.rows: list[tuple[Any, ...]] = []

    async def execute_raw(self, _sql: str, *args: Any) -> int:
        natural = (args[1], args[2], args[3], args[6], args[11])
        migrated = args[12]
        if any(row[:5] == natural for row in self.rows) or (
            migrated is not None and any(row[5] == migrated for row in self.rows)
        ):
            return 0
        self.rows.append((*natural, migrated, args[4]))
        return 1

    async def query_raw(self, _sql: str, migrated: str) -> list[dict[str, int]]:
        return [{"x": 1}] if any(row[5] == migrated for row in self.rows) else []


def _append(client: _Ledger, **kwargs: Any) -> Any:
    base = {
        "subject_type": ledger.ARTISAN,
        "subject_id": "a-1",
        "state": ledger.GRANTED,
        "basis": ledger.VERBAL_RECORDED,
        "actor_id": "u-1",
        "recorded_at": T0,
    }
    return asyncio.run(ledger.append(client, **{**base, **kwargs}))


def test_two_answers_in_one_millisecond_are_both_kept() -> None:
    client = _Ledger()
    first = _append(client)
    second = _append(client, state=ledger.REFUSED)
    assert first["recordedAt"] == T0
    assert second["recordedAt"] == T0 + timedelta(milliseconds=1)
    assert [row[6] for row in client.rows] == ["GRANTED", "REFUSED"]


def test_a_copied_row_is_copied_once() -> None:
    client = _Ledger()
    key = ledger.legacy_key("RecordingConsent", "rc-1")
    assert key == "RecordingConsent:rc-1"
    assert _append(client, migrated_from=key) is not None
    assert _append(client, migrated_from=key, recorded_at=T0 + timedelta(hours=1)) is None
    assert len(client.rows) == 1


@pytest.mark.parametrize(
    "bad",
    [
        {"state": "NOT_RECORDED"},
        {"subject_type": "VILLAGE"},
        {"purpose": "MARKETING"},
        {"basis": "GUESSED"},
        {"actor_id": ""},
        {"subject_id": " "},
    ],
)
def test_a_row_that_says_nothing_is_refused(bad: dict[str, Any]) -> None:
    with pytest.raises(ValueError):
        _append(_Ledger(), **bad)


# ── The migration and the model, identical in both portals ──────────────────────────────────


def test_the_migration_is_additive_and_safe_to_run_twice() -> None:
    sql = MIGRATION.read_text(encoding="utf-8")
    statements = [line for line in sql.splitlines() if line.strip() and not line.startswith("--")]
    assert not re.search(r"\b(DROP|RENAME|TRUNCATE|DELETE FROM|UPDATE)\b", "\n".join(statements), re.IGNORECASE)
    assert sql.count("EXCEPTION WHEN duplicate_object THEN NULL") == 4
    assert 'CREATE TABLE IF NOT EXISTS "Consent"' in sql
    assert 'ADD COLUMN IF NOT EXISTS "migratedFrom"' in sql
    assert sql.count("CREATE INDEX IF NOT EXISTS") + sql.count("CREATE UNIQUE INDEX IF NOT EXISTS") == 4
    assert "CREATE OR REPLACE VIEW consent_current_v" in sql


def _block(schema: str, kind: str, name: str) -> list[str]:
    match = re.search(rf"^{kind} {name} \{{\n(.*?)^\}}", schema, re.MULTILINE | re.DOTALL)
    assert match, f"{kind} {name} is missing from schema.prisma"
    return [
        " ".join(line.split())
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith("//")
    ]


def test_the_schema_declares_the_one_store_exactly() -> None:
    schema = (BACKEND / "prisma" / "schema.prisma").read_text(encoding="utf-8").replace("\r\n", "\n")
    assert _block(schema, "enum", "ConsentState") == list(ledger.STATES)
    assert _block(schema, "enum", "ConsentSubjectType") == list(ledger.SUBJECT_TYPES)
    assert _block(schema, "enum", "ConsentPurpose") == list(ledger.PURPOSES)
    assert _block(schema, "enum", "ConsentBasis") == list(ledger.BASES)
    assert _block(schema, "model", "Consent") == [
        "id String @id @default(cuid())",
        "subjectType ConsentSubjectType",
        "subjectId String",
        "purpose ConsentPurpose",
        "state ConsentState",
        "basis ConsentBasis",
        "source String",
        "version Int @default(1)",
        "note String?",
        "evidenceMediaId String?",
        "actorId String",
        "recordedAt DateTime",
        "createdAt DateTime @default(now())",
        'migratedFrom String? @unique(map: "Consent_migratedFrom_key")',
        '@@unique([subjectType, subjectId, purpose, source, recordedAt], map: "Consent_idem_key")',
        '@@index([subjectType, subjectId, purpose, recordedAt], map: "Consent_subject_idx")',
        '@@index([actorId], map: "Consent_actorId_idx")',
    ]
