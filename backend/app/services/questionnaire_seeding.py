import json
from pathlib import Path
from typing import Any

from app.core.db import db
from app.services import cross_portal_codes

DATA_DIR = Path(__file__).resolve().parents[1] / "data"


class SeedRefused(RuntimeError):
    pass


def load_corpus(path: Path) -> list[dict[str, Any]]:
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
    owner = await db.user.find_first(where={"role": "MASTER_ADMIN"}, order={"createdAt": "asc"})
    if owner is None:
        owner = await db.user.find_first(where={"role": "ADMIN"}, order={"createdAt": "asc"})
    return owner.id if owner else None


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
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
            data={"description": description, "isActive": True},
        )
        print(f"refreshing questionnaire {record.id} — {title!r}")

    stored_sections = await db.questionnairesection.find_many(where={"questionnaireId": record.id})
    section_by_code = {row.code: row for row in stored_sections}
    corpus_codes = {str(s["code"]).strip() for s in sections}

    desired: dict[str, int] = {str(s["code"]).strip(): index for index, s in enumerate(sections, start=1)}
    absent = sorted(
        (row for row in stored_sections if row.code not in corpus_codes),
        key=lambda row: (row.sortOrder, row.code),
    )
    for offset, row in enumerate(absent, start=1):
        desired[row.code] = len(sections) + offset

    negated: set[str] = set()
    if any(row.sortOrder != desired.get(row.code) for row in stored_sections):
        for offset, row in enumerate(stored_sections, start=1):
            await db.questionnairesection.update(where={"id": row.id}, data={"sortOrder": -offset})
            negated.add(row.id)
            counts["sections_reordered"] += 1

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

    await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=record.id)

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
