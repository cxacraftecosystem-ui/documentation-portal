"""Seed ONE named instrument: a ``Questionnaire`` row, its sections, and its questions.

WHAT THE OLD SEEDER DID, AND WHY IT COULD NOT BE KEPT
=====================================================

``scripts/seed_questionnaire.py`` was 66 lines and it upserted sections ON ``code``
(seed_questionnaire.py:18-19) against a ``code`` that was ``@unique`` GLOBALLY (schema.prisma:964),
and wrote ``sortOrder = the section's 1-based index in the JSON array``
(seed_questionnaire.py:15,24,29) against a ``sortOrder`` that was ``@@unique`` GLOBALLY
(schema.prisma:974). Both of those indexes are gone as of migration
20260913100000_questionnaire_instruments and the ``where={"code": ...}`` unique input no longer
EXISTS in the generated client, so that file stops working the moment ``prisma generate`` runs. It
is not deprecated; it is broken, and it is rewritten as a thin caller of this module.

It also had three behaviours nobody chose:

  * A question's identity was ``(sectionId, sortOrder)`` via ``find_first``
    (seed_questionnaire.py:35-37) — POSITION, not text. Re-running the seed after inserting a
    question into the middle of a section silently rewrote the prompt of an existing row while its
    ``QuestionnaireResponse`` rows stayed attached, so "12" ended up filed under a question nobody
    had been asked. This module keeps the same key (it is the only key the JSON supports) and adds
    the guard that makes it safe: A QUESTION WITH ANSWERS IS NEVER REWORDED.
  * A question or section removed from the JSON stayed ``isActive = True`` forever, silently. This
    module never deletes and never deactivates either — a section removed from the corpus may hold a
    fortnight of somebody's fieldwork, and the live database is known to hold at least one question
    that exists in no corpus file at all — but it PRINTS what it found and did not touch, which is
    the difference between a decision and an oversight.
  * It reported one number ("Seeded questionnaire questions: 284") whether it had created 284 rows
    or updated 284 rows. This module returns structured counts so a test can assert what "idempotent"
    actually means — that the SECOND run changes nothing — rather than the property a seeder that
    merely does not crash twice has.

Modelled on ``designer-portal/backend/scripts/seed_shared_questionnaire.py:117-262``, whose rules are
carried over in spirit: keyed on the title, refreshes unanswered wording, never touches an answered
question and says so on stdout, never deletes.

WHY THE NEGATIVE PASS IS ALL-OR-NOTHING
=======================================

``reorder_sections`` writes negative sort orders in a first pass and positives in a second
(routes/questionnaire.py:411-415) because ``@@unique([sortOrder])`` would otherwise collide mid-loop.
The composite unique removes the CROSS-instrument half of that problem, which was the fatal and
UNCONDITIONAL one: the new section A claiming sortOrder 1 while RESP, in the other instrument, held
it. Two instruments now hold their own 1..n and never see each other.

It does NOT remove the WITHIN-instrument half, and the obvious narrowing of it — "negate only the
sections that actually move" — IS UNSOUND. The counterexample, which is also a test
(``test_the_movers_only_counterexample_does_not_raise``):

    stored:  B@1, C@2          corpus: [A, B, C]

Under "movers only", B is a mover (its target 2 is held by C) and is negated; C is NOT a mover (its
target 3 is unoccupied) and stays at 2. PASS 1 then creates A@1, updates B->2 — and collides with the
C that was never moved out of the way. ``UniqueViolationError``, corpus half-written, and this
backend has zero ``db.tx()`` call sites to roll it back with.

So when the pass fires it negates EVERY stored section of the instrument. It is CONDITIONAL — a
fresh instrument and every idempotent re-run do not fire it at all, which is what
``sections_reordered == 0`` on the second run asserts — but when it does fire, the window in which a
concurrent reader of ``GET /questionnaire/sections`` sees the instrument ordered backwards covers the
whole instrument rather than a subset. That trade is deliberate: a brief wrong ORDER is recoverable
by reloading, a ``UniqueViolationError`` mid-corpus is not. Run the seeders outside peak field hours,
and do not "fix" this by introducing a transaction idiom this repo does not have.

QUESTIONS NEED NO SUCH PASS. ``QuestionnaireQuestion`` has NO unique index on (sectionId, sortOrder);
the old one was dropped by 20260616120000_questionnaire_sections/migration.sql:52 so questions could
move between sections. That is deliberately left alone: adding it now would force the same two-pass
into ``reorder_questions`` (routes/questionnaire.py) and into the web builder's re-parent path, which
does a PATCH followed by a reorder and would collide transiently between the two calls.
"""

import json
from pathlib import Path
from typing import Any

from app.core.db import db

DATA_DIR = Path(__file__).resolve().parents[1] / "data"


class SeedRefused(RuntimeError):
    """The corpus is malformed. Raised BEFORE the first write, so nothing is half-applied."""


def load_corpus(path: Path) -> list[dict[str, Any]]:
    """The corpus, validated.

    ``utf-8-sig`` rather than ``utf-8`` because a corpus is an EXPORT: both files here are written
    out by a parsing script rather than hand-edited, and the tooling that produces them (and the
    Windows editors that touch them afterwards) adds a UTF-8 BOM without being asked. Neither file
    carries one TODAY — checked, both start with ``[`` — and ``test_questionnaire_seed`` asserts the
    3rd-workshop file still does not. ``utf-8-sig`` costs nothing on a file without a BOM and is the
    difference between a re-export being invisible and a re-export being a ``JSONDecodeError`` on
    character 0. ``seed_questionnaire.py:11`` read them the same way for the same reason.

    Validation happens here, in full, before a single row is written — a seeder that discovers a
    duplicate code halfway through has already created eleven sections it cannot roll back, because
    this backend has no transaction idiom (zero ``db.tx()`` call sites).
    """
    sections = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(sections, list) or not sections:
        raise SeedRefused(f"{path.name}: expected a non-empty list of sections")

    seen_codes: set[str] = set()
    for index, section in enumerate(sections, start=1):
        code = str(section.get("code", "")).strip()
        title = str(section.get("title", "")).strip()
        if not code or not title:
            raise SeedRefused(f"{path.name}: section #{index} is missing a code or a title")
        if code in seen_codes:
            raise SeedRefused(
                f"{path.name}: section code {code!r} appears twice. Codes are unique within an "
                f"instrument (@@unique([questionnaireId, code])), so this corpus cannot be seeded."
            )
        seen_codes.add(code)

        questions = section.get("questions") or []
        orders = [int(q["sortOrder"]) for q in questions]
        if sorted(orders) != list(range(1, len(orders) + 1)):
            raise SeedRefused(
                f"{path.name}: section {code} has sortOrders {sorted(orders)}; they must be "
                f"contiguous from 1, because (sectionId, sortOrder) is this seeder's identity for "
                f"a question and a gap makes a re-run create a duplicate instead of matching."
            )
        for question in questions:
            if not str(question.get("prompt", "")).strip():
                raise SeedRefused(
                    f"{path.name}: section {code} #{question.get('sortOrder')} has no prompt"
                )
    return sections


async def _owner_id() -> str | None:
    """The master admin, else the oldest admin, else nobody.

    ``Questionnaire.createdById`` is NULLABLE (see its comment in schema.prisma), so "nobody" is a
    legitimate answer on a database that has not been seeded with an admin yet, and this function
    does not raise. Ownership here is provenance, not authority — ``require_questionnaire_manager``
    governs who may reword an instrument, not who created it.
    """
    owner = await db.user.find_first(where={"role": "MASTER_ADMIN"}, order={"createdAt": "asc"})
    if owner is None:
        owner = await db.user.find_first(where={"role": "ADMIN"}, order={"createdAt": "asc"})
    return owner.id if owner else None


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    """Which of ``question_ids`` already carry a response row.

    ONE query per section rather than one per question: this runs 22 times over 81 questions (and
    24 times over 284 for the older corpus), and a per-question existence check would be hundreds of
    sequential round trips to a database that on the deployed system is in another region.
    """
    if not question_ids:
        return set()
    answers = await db.questionnaireresponse.find_many(where={"questionId": {"in": question_ids}})
    return {answer.questionId for answer in answers}


async def _next_sort_order() -> int:
    rows = await db.questionnaire.find_many(order={"sortOrder": "desc"}, take=1)
    return (rows[0].sortOrder if rows else 0) + 1


async def seed_instrument(
    *,
    questionnaire_id: str,
    title: str,
    description: str,
    corpus_path: Path,
) -> tuple[str, dict[str, int]]:
    """Create or refresh one instrument from ``corpus_path``. Returns ``(id, counts)``.

    Returned rather than only printed so a test can drive this directly and assert that the SECOND
    run reports zero creates, zero updates, zero reorderings and zero rewordings — which is what
    "idempotent" means, and what a script that merely survives being run twice does not have.

    NEVER SETS ``isDefault``. Which instrument an unqualified request belongs to is an operator's
    decision made through ``PUT /api/questionnaires/{id}/default``, and a seeder that flipped it
    would re-file every old client's submissions as a side effect of a wording correction.
    """
    sections = load_corpus(corpus_path)
    counts = {
        "sections_created": 0,
        "sections_updated": 0,
        "sections_reordered": 0,
        "questions_created": 0,
        "questions_updated": 0,
        "questions_left_alone": 0,
        "sections_not_in_corpus": 0,
        "questions_not_in_corpus": 0,
    }

    # The row is found by ID FIRST and by TITLE second. The id is the literal this project writes
    # down (see app/services/questionnaire_instruments.py); the title is the fallback for a database
    # where the row was created through the API before this script ever ran, and the reason
    # `@@unique([title])` exists.
    record = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if record is None:
        record = await db.questionnaire.find_first(where={"title": title})
    if record is None:
        record = await db.questionnaire.create(
            data={
                "id": questionnaire_id,
                "title": title,
                "description": description,
                "isActive": True,
                "sortOrder": await _next_sort_order(),
                "createdById": await _owner_id(),
            }
        )
        print(f"created questionnaire {record.id} — {title!r}")
    else:
        record = await db.questionnaire.update(
            where={"id": record.id},
            # Re-asserted rather than assumed: a re-run of the seeder is an operator saying "this
            # instrument is current", which is the same instruction it was the first time. The
            # TITLE is not rewritten — it is the key this row was found by.
            data={"description": description, "isActive": True},
        )
        print(f"refreshing questionnaire {record.id} — {title!r}")

    stored_sections = await db.questionnairesection.find_many(where={"questionnaireId": record.id})
    section_by_code = {row.code: row for row in stored_sections}
    corpus_codes = {str(s["code"]).strip() for s in sections}

    # WHERE EVERY STORED SECTION IS SUPPOSED TO END UP. Corpus sections take 1..n in corpus order.
    # A section the corpus no longer mentions is PARKED IMMEDIATELY ABOVE the corpus — n+1, n+2, …
    # in a stable order — rather than left where it is.
    #
    # "Left where it is" was the first design and it is incompatible with its own neighbour: the W2
    # corpus opens with RESP at slot 1, so a stored section absent from a corpus whose first entry
    # wants slot 1 makes the very first write of PASS 1 a `UniqueViolationError` under
    # `@@unique([questionnaireId, sortOrder])`. Parking is the only rule that keeps BOTH promises —
    # the corpus reads 1..n in the order it was written, and nothing is deleted or deactivated to
    # achieve that.
    desired: dict[str, int] = {str(s["code"]).strip(): index for index, s in enumerate(sections, start=1)}
    absent = sorted(
        (row for row in stored_sections if row.code not in corpus_codes),
        key=lambda row: (row.sortOrder, row.code),
    )
    for offset, row in enumerate(absent, start=1):
        desired[row.code] = len(sections) + offset

    # ---- PASS 0: park EVERY stored section on a negative sort order ----------------------------
    # Conditional (nothing to do on a fresh instrument or an idempotent re-run) but ALL-OR-NOTHING
    # when it fires. See this module's docstring for the counterexample that killed "movers only".
    negated: set[str] = set()
    if any(row.sortOrder != desired.get(row.code) for row in stored_sections):
        for offset, row in enumerate(stored_sections, start=1):
            await db.questionnairesection.update(where={"id": row.id}, data={"sortOrder": -offset})
            negated.add(row.id)
            counts["sections_reordered"] += 1

    # ---- PASS 1: the corpus ---------------------------------------------------------------------
    for index, section in enumerate(sections, start=1):
        code = str(section["code"]).strip()
        section_title = str(section["title"]).strip()
        stored = section_by_code.get(code)
        if stored is None:
            stored = await db.questionnairesection.create(
                data={
                    "questionnaireId": record.id,
                    "code": code,
                    "title": section_title,
                    "sortOrder": index,
                    "isActive": True,
                }
            )
            counts["sections_created"] += 1
        elif (
            # `stored.id in negated` FIRST and not as an afterthought: after PASS 0 the row in the
            # database holds a negative order while this in-memory copy still holds the old one, so
            # comparing sortOrder alone would decide "no change needed" and leave the instrument
            # negative. The set is the authority on "this row has been moved out of the way".
            stored.id in negated
            or stored.title != section_title
            or stored.sortOrder != index
            or not stored.isActive
        ):
            stored = await db.questionnairesection.update(
                where={"id": stored.id},
                data={"title": section_title, "sortOrder": index, "isActive": True},
            )
            counts["sections_updated"] += 1

        stored_questions = await db.questionnairequestion.find_many(where={"sectionId": stored.id})
        # MATCHED ON sortOrder WITHIN THE SECTION — position, which is all the JSON carries.
        # Matching on the PROMPT instead would make every corrected wording look like a brand-new
        # question and leave the old one standing, which is how an 81-question instrument becomes a
        # 130-question one over three re-runs.
        question_by_order = {row.sortOrder: row for row in stored_questions}
        answered = await _answered_question_ids([row.id for row in stored_questions])
        corpus_orders = {int(q["sortOrder"]) for q in section["questions"]}

        for question in section["questions"]:
            order = int(question["sortOrder"])
            prompt = str(question["prompt"]).strip()
            stored_question = question_by_order.get(order)
            if stored_question is None:
                await db.questionnairequestion.create(
                    data={
                        "questionnaireId": record.id,
                        "sectionId": stored.id,
                        "sectionCode": code,
                        "sectionTitle": section_title,
                        "sortOrder": order,
                        "prompt": prompt,
                        "isActive": True,
                    }
                )
                counts["questions_created"] += 1
                continue
            needs_write = (
                stored_question.prompt != prompt
                or stored_question.sectionCode != code
                or stored_question.sectionTitle != section_title
                or stored_question.questionnaireId != record.id
                or not stored_question.isActive
            )
            if not needs_write:
                continue
            if stored_question.prompt != prompt and stored_question.id in answered:
                # THE ONE THING THIS SCRIPT WILL NOT DO. An answer belongs to the wording it was
                # given under: rewording an answered question leaves "twelve" sitting under "How
                # many looms do you own?" when it was said about "How many weavers work with you?".
                # Reported rather than skipped in silence, so an operator who changed the corpus
                # knows exactly which rows did not move and can retire-and-replace them through the
                # builder (PATCH /api/questionnaire/questions/{id}), which is the door built for it.
                # Superseding — keeping the answer attached to a new wording — is a future
                # workstream and is deliberately NOT attempted here.
                counts["questions_left_alone"] += 1
                print(
                    f"  left alone (has answers): {code} #{order} — "
                    f"{stored_question.prompt[:70]!r} is NOT being reworded to {prompt[:70]!r}"
                )
                continue
            await db.questionnairequestion.update(
                where={"id": stored_question.id},
                data={
                    "questionnaireId": record.id,
                    "sectionCode": code,
                    "sectionTitle": section_title,
                    "prompt": prompt,
                    "isActive": True,
                },
            )
            counts["questions_updated"] += 1

        for stored_question in stored_questions:
            if stored_question.sortOrder not in corpus_orders:
                counts["questions_not_in_corpus"] += 1
                print(
                    f"  kept, not in the corpus: {code} #{stored_question.sortOrder} — "
                    f"{stored_question.prompt[:70]!r}. NOT deactivated; use the builder if it "
                    f"should stop appearing."
                )

    # ---- PASS 2: the sections the corpus no longer mentions --------------------------------------
    # Never deleted and never deactivated. A section dropped from a corpus may hold a fortnight of
    # somebody's fieldwork, and a seeder is the wrong place to decide it should stop existing. It is
    # MOVED (to its parked slot above the corpus) and NAMED on stdout, because a silent survivor is
    # how the 2nd workshop's section W would have gone on being rendered under a 3rd-workshop
    # heading with nobody noticing.
    for row in absent:
        parked = desired[row.code]
        counts["sections_not_in_corpus"] += 1
        if row.id in negated or row.sortOrder != parked:
            await db.questionnairesection.update(where={"id": row.id}, data={"sortOrder": parked})
        print(
            f"  kept, not in the corpus: section {row.code} — {row.title[:70]!r}. "
            f"NOT deactivated; parked at sortOrder {parked}, above the {len(sections)} the corpus "
            f"describes."
        )

    return record.id, counts


def print_counts(title: str, questionnaire_id: str, counts: dict[str, int]) -> None:
    print(f"{title} -> {questionnaire_id}")
    print(
        "  sections: {sections_created} created, {sections_updated} updated, "
        "{sections_reordered} parked for reorder, {sections_not_in_corpus} kept but absent".format(
            **counts
        )
    )
    print(
        "  questions: {questions_created} created, {questions_updated} updated, "
        "{questions_left_alone} LEFT ALONE (answered), {questions_not_in_corpus} kept but absent".format(
            **counts
        )
    )
