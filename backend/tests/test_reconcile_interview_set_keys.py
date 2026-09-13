"""``scripts/reconcile_interview_set_keys`` — the healer that was about to delete a workshop.

================================================================================================
WHY THIS FILE EXISTS: A RECONCILIATION SCRIPT THAT OUTLIVED THE INDEX IT RECONCILES
================================================================================================

``20260913100000_questionnaire_instruments`` dropped the GLOBAL ``@unique`` on
``QuestionnaireInterview.artisanSetKey`` and replaced it with
``@@unique([questionnaireId, artisanSetKey])`` (prisma/schema.prisma:1411). The same artisan set
sitting for a second instrument stopped being a duplicate and became the entire point: the five
artisans who sat for the 2nd Craft Toolkit Workshop's instrument sit again for the 3rd, and under
the old index the second sitting folded into the first and the 3rd workshop's answers landed on
the 2nd workshop's interview.

``scripts/reconcile_interview_set_keys.py`` predates that migration and grouped by
``artisanSetKey`` ALONE. So on the day the migration landed the script began classifying two
legitimate sittings as a duplicate pair, and every phase acted on it: the dry run printed
``consolidate set …: keep <id>, fold [<id>]`` in a confident sentence — which is what an operator
reads in order to decide whether to pass ``--execute`` — and ``--execute`` folded the media, moved
the non-colliding responses and DELETED THE LOSER. ``QuestionnaireResponse.interview`` is
``onDelete: Cascade`` (schema.prisma:1445), so every question BOTH sittings answered went with the
row. That is one instrument's fieldwork, deleted by the maintenance script, with nothing in the
output to say a second instrument had ever existed.

THESE TESTS ARE THE ONLY THING THAT CAN CATCH THE REGRESSION. The defect is invisible on a
single-instrument database — which is every database that existed before 2026-09-13, and every
developer machine that has not seeded the 3rd workshop — and its blast radius only appears when
somebody runs a script by hand on production. There is no request to replay and no endpoint to
call. So the grouping is exercised here directly, against a fake database that RECORDS EVERY WRITE,
because "it did not delete anything" has to be asserted as a fact about the calls made rather than
inferred from a row count that a fake could report any way it liked.

THE FAKE IS DELIBERATELY DUMB. It answers queries from canned lists and appends every mutating call
to one ordered log. What is under test is which rows the script decides are duplicates and what it
writes into ``artisanSetKey`` — not Prisma, and not Postgres, which cannot be reached from a unit
test and would in any case happily store the wrong thing if the script asked it to.
"""

import asyncio
import sys
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

import scripts.reconcile_interview_set_keys as reconcile

#: The two instruments, by the deterministic ids the migration and the seeders write down. Spelled
#: out rather than imported, for the reason ``tests/test_questionnaire_consolidation.py`` gives: a
#: test that imports the constant agrees with the constants module instead of testing the document.
W2 = "qnr_2nd_craft_toolkit_workshop"
W3 = "qnr_3rd_craft_toolkit_workshop"


def _interview(iv_id: str, questionnaire_id: str, artisan_ids: list[str], *, day: int = 1,
               stored_key: str | None = None) -> SimpleNamespace:
    """One ``QuestionnaireInterview`` row as the script reads it: links included, key denormalised."""
    return SimpleNamespace(
        id=iv_id,
        questionnaireId=questionnaire_id,
        artisanSetKey=stored_key,
        createdAt=datetime(2026, 6, day, 9, 0, tzinfo=UTC),
        artisans=[SimpleNamespace(artisanId=aid) for aid in artisan_ids],
    )


class _InterviewDelegate:
    def __init__(self, rows: list, log: list) -> None:
        self._rows = rows
        self._log = log

    async def find_many(self, **_kwargs):
        return list(self._rows)

    async def update_many(self, where=None, data=None):
        self._log.append(("interview.update_many", where, data))
        return len(self._rows)

    async def update(self, where=None, data=None):
        self._log.append(("interview.update", where, data))
        return None

    async def delete(self, where=None):
        self._log.append(("interview.delete", where))
        return None


class _MediaDelegate:
    def __init__(self, counts: dict[str, int], log: list) -> None:
        self._counts = counts
        self._log = log

    async def count(self, where=None):
        return self._counts.get((where or {}).get("questionnaireInterviewId"), 0)

    async def update_many(self, where=None, data=None):
        self._log.append(("media.update_many", where, data))
        return 0


class _ResponseDelegate:
    def __init__(self, rows: list, log: list) -> None:
        self._rows = rows
        self._log = log

    async def find_many(self, where=None, **_kwargs):
        interview_id = (where or {}).get("interviewId")
        return [r for r in self._rows if r.interviewId == interview_id]

    async def find_first(self, where=None, **_kwargs):
        for row in self._rows:
            if row.interviewId == (where or {}).get("interviewId") and row.questionId == (
                where or {}
            ).get("questionId"):
                return row
        return None

    async def update(self, where=None, data=None):
        self._log.append(("response.update", where, data))
        return None


class _Db:
    def __init__(self, interviews: list, *, media_counts=None, responses=None) -> None:
        self.log: list = []
        self.questionnaireinterview = _InterviewDelegate(interviews, self.log)
        self.mediafile = _MediaDelegate(media_counts or {}, self.log)
        self.questionnaireresponse = _ResponseDelegate(responses or [], self.log)


def _run(monkeypatch, interviews: list, *, execute: bool, **db_kwargs) -> _Db:
    """Run ``main()`` end to end against a fake database and hand back its call log."""
    fake = _Db(interviews, **db_kwargs)

    async def _noop() -> None:
        return None

    monkeypatch.setattr(reconcile, "db", fake)
    monkeypatch.setattr(reconcile, "connect_db", _noop)
    monkeypatch.setattr(reconcile, "disconnect_db", _noop)
    argv = ["reconcile_interview_set_keys"] + (["--execute"] if execute else [])
    monkeypatch.setattr(sys, "argv", argv)
    asyncio.run(reconcile.main())
    return fake


def _written_keys(fake: _Db) -> dict[str, object]:
    """``interview id -> the value written into artisanSetKey`` by phase 3 only.

    Phase 1's blanket ``update_many`` is excluded on purpose: it is a ``where={}`` clear, carries no
    id, and lumping it in here would let a test that asserts "this row got NULL" pass on the clear
    while phase 3 never ran at all.
    """
    return {
        call[1]["id"]: call[2]["artisanSetKey"]
        for call in fake.log
        if call[0] == "interview.update"
    }


def _deleted_ids(fake: _Db) -> list[str]:
    return [call[1]["id"] for call in fake.log if call[0] == "interview.delete"]


# --------------------------------------------------------------------------------------
# 1. The defect this file was written for
# --------------------------------------------------------------------------------------


def test_reconcile_groups_by_instrument(monkeypatch):
    """THE NAMED TEST, AND THE WHOLE POINT. The same artisan set on two instruments is TWO
    legitimate sittings — that is what the 2026-09-13 migration made legal — so nothing is folded
    and nothing is deleted. Grouping by ``artisanSetKey`` alone made this pair look like a
    duplicate, and ``--execute`` would have deleted one of the two interviews outright, taking every
    answer it shared with its sibling down the ``onDelete: Cascade`` on QuestionnaireResponse."""
    second = _interview("iv-w2", W2, ["a2", "a1"], day=1)
    third = _interview("iv-w3", W3, ["a1", "a2"], day=8)
    fake = _run(monkeypatch, [second, third], execute=True)

    assert _deleted_ids(fake) == []
    assert not [c for c in fake.log if c[0] == "media.update_many"]
    assert _written_keys(fake) == {"iv-w2": "a1,a2", "iv-w3": "a1,a2"}


def test_two_sittings_on_two_instruments_are_not_even_reported_as_duplicates(monkeypatch, capsys):
    """THE DRY RUN IS HALF THE DEFECT AND GETS ITS OWN TEST. An operator passes ``--execute``
    because the dry run told them what it would do; a dry run that prints "consolidate set …" over
    two legitimate sittings is a recommendation to delete fieldwork, and it is wrong before a single
    row is touched."""
    _run(
        monkeypatch,
        [_interview("iv-w2", W2, ["a1"]), _interview("iv-w3", W3, ["a1"])],
        execute=False,
    )
    out = capsys.readouterr().out
    assert "duplicate_set_groups=0" in out
    assert "consolidate set" not in out


def test_a_real_duplicate_on_one_instrument_is_still_consolidated(monkeypatch):
    """THE OTHER HALF OF THE FIX: narrowing the grouping must not turn the script into a no-op. Two
    rows sharing an instrument AND a set are the stale-key collision this script exists to heal —
    editing either one recomputes the key, hits ``@@unique([questionnaireId, artisanSetKey])`` and
    answers 409 — so the loser is still folded into the winner and deleted."""
    keeper = _interview("iv-keep", W3, ["a1", "a2"], day=1)
    loser = _interview("iv-drop", W3, ["a2", "a1"], day=2)
    fake = _run(
        monkeypatch,
        [keeper, loser],
        execute=True,
        media_counts={"iv-keep": 4, "iv-drop": 1},
    )

    assert _deleted_ids(fake) == ["iv-drop"]
    assert _written_keys(fake) == {"iv-keep": "a1,a2"}
    moved = [c for c in fake.log if c[0] == "media.update_many"]
    assert moved, "the loser's media must be re-pointed at the survivor before it is deleted"
    assert all(c[2].get("questionnaireInterviewId") == "iv-keep" or
               c[2].get("linkedRecordId") == "iv-keep" for c in moved)


# --------------------------------------------------------------------------------------
# 2. What actually reaches the TEXT column
# --------------------------------------------------------------------------------------


def test_the_write_back_puts_a_string_in_the_text_column_not_the_slot_tuple(monkeypatch):
    """KEYING BY A TUPLE AND THEN WRITING THE TUPLE IS THE SECOND BUG HIDING INSIDE THE FIRST.
    ``artisanSetKey`` is TEXT. A ``('qnr_x', 'a1,a2')`` written into it is either refused by the
    driver or stored as the repr of a Python tuple — a value ``artisan_set_key`` never produces, no
    client ever computes and the composite index therefore never matches, so every interview would
    read as permanently stale and every edit would recompute a key disagreeing with the stored one.
    The slot is unpacked at the write-back; only its second half is written."""
    fake = _run(monkeypatch, [_interview("iv-1", W3, ["b", "a"])], execute=True)
    written = _written_keys(fake)["iv-1"]
    assert written == "a,b"
    assert isinstance(written, str)


def test_the_instrument_is_never_written_back_onto_the_interview(monkeypatch):
    """WHICH INSTRUMENT A SITTING WAS TAKEN ON IS NOT THIS SCRIPT'S FACT TO CHANGE. It reconciles a
    denormalised key against the artisan links and nothing else; ``questionnaireId`` is NOT NULL,
    is resolved once at create and never moves (schema.prisma:1397-1404). A reconciliation that
    also rewrote it would silently move fieldwork between instruments."""
    fake = _run(monkeypatch, [_interview("iv-1", W3, ["a"])], execute=True)
    for call in fake.log:
        if call[0] in ("interview.update", "interview.update_many"):
            assert set((call[2] or {}).keys()) == {"artisanSetKey"}


def test_an_interview_with_no_artisans_is_left_out_of_the_slots_entirely(monkeypatch):
    """``artisan_set_key`` returns None for an empty set and NULLs are exempt from the unique index,
    so artisan-less interviews are not deduped and must not acquire a key here. They are cleared by
    phase 1 and never written back — which is the correct value, not an oversight."""
    fake = _run(
        monkeypatch,
        [_interview("iv-empty", W3, []), _interview("iv-real", W3, ["a"])],
        execute=True,
    )
    assert _deleted_ids(fake) == []
    assert _written_keys(fake) == {"iv-real": "a"}


# --------------------------------------------------------------------------------------
# 3. The dry run touches nothing
# --------------------------------------------------------------------------------------


def test_a_dry_run_writes_nothing_at_all(monkeypatch):
    """Including phase 1's blanket ``where={}`` clear. A "dry run" that NULLed every key in the
    table would leave the composite index constraining nothing until somebody noticed and re-ran
    with ``--execute``, which is the exact window in which ``create_interview`` creates a second
    sitting for a set that already has one."""
    keeper = _interview("iv-keep", W3, ["a1"], day=1)
    loser = _interview("iv-drop", W3, ["a1"], day=2)
    fake = _run(monkeypatch, [keeper, loser], execute=False, media_counts={"iv-keep": 2})
    assert fake.log == []


# --------------------------------------------------------------------------------------
# 4. The grouping on its own, without the machinery around it
# --------------------------------------------------------------------------------------


def test_the_slot_is_the_exact_column_pair_the_database_enforces(monkeypatch):
    """Asserted on the grouping function directly, because this is the one decision the whole
    script turns on: a healer for ``@@unique([questionnaireId, artisanSetKey])`` must group by both
    of those columns. Grouping by a subset is how it starts deleting rows the index was happy to
    keep; grouping by a superset would make it stop healing anything."""
    _correct, by_slot = reconcile.group_by_slot(
        [
            _interview("iv-w2", W2, ["a1", "a2"]),
            _interview("iv-w3", W3, ["a2", "a1"]),
            _interview("iv-w3b", W3, ["a1", "a2"]),
        ]
    )
    assert set(by_slot) == {(W2, "a1,a2"), (W3, "a1,a2")}
    assert by_slot[(W2, "a1,a2")] == ["iv-w2"]
    assert by_slot[(W3, "a1,a2")] == ["iv-w3", "iv-w3b"]


def test_a_missing_instrument_raises_rather_than_folding_every_row_into_one_slot(monkeypatch):
    """THE DEFENSIVE BRANCH THAT IS DELIBERATELY ABSENT. ``questionnaireId`` is NOT NULL, so a row
    without one is a schema regression and must stop the script at the top. A
    ``getattr(iv, "questionnaireId", None)`` would instead put every affected interview into one
    ``(None, key)`` slot — the instrument-blind grouping this file exists to prevent, arriving back
    silently through the code that was supposed to be careful."""
    row = SimpleNamespace(
        id="iv-1",
        artisanSetKey=None,
        createdAt=datetime(2026, 6, 1, tzinfo=UTC),
        artisans=[SimpleNamespace(artisanId="a1")],
    )
    with pytest.raises(AttributeError):
        reconcile.group_by_slot([row])
