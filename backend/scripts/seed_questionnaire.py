import asyncio

from app.core.db import connect_db, disconnect_db
from app.services.questionnaire_instruments import W2_ID, W2_TITLE
from app.services.questionnaire_seeding import DATA_DIR, print_counts, seed_instrument

DESCRIPTION = (
    "The instrument used at the 2nd Craft Toolkit Workshop: 24 sections, 284 questions, from "
    '"2nd Workshop_Interview Questions.docx". Every interview recorded before 2026-09-13 belongs '
    "to it."
)


async def main() -> None:
    await connect_db()
    try:
        questionnaire_id, counts = await seed_instrument(
            questionnaire_id=W2_ID,
            title=W2_TITLE,
            description=DESCRIPTION,
            corpus_path=DATA_DIR / "questionnaire_questions.json",
        )
        print_counts(W2_TITLE, questionnaire_id, counts)
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
