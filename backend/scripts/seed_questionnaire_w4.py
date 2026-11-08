import asyncio

from app.core.db import connect_db, disconnect_db
from app.services.questionnaire_instruments import W4_ID, W4_TITLE
from app.services.questionnaire_seeding import DATA_DIR, print_counts, seed_instrument

OWNER_ID = "cmqg22xf6000018qdavy2jvel"

CORPUS = DATA_DIR / "questionnaire_questions_w4.json"

DESCRIPTION = (
    "The instrument used at the 4th Craft Toolkit Workshop (stone crafts): 23 sections (A-W), "
    "108 questions. Section V's questions ask for lists — designers, suppliers, buyers, digital, "
    "financial and institutional connections — and are answered as prose, like every other "
    "question here. Section W covers stone production, transportation and installation."
)


async def main() -> None:
    await connect_db()
    try:
        questionnaire_id, counts = await seed_instrument(
            questionnaire_id=W4_ID,
            title=W4_TITLE,
            description=DESCRIPTION,
            corpus_path=CORPUS,
            created_by_id=OWNER_ID,
        )
        print_counts(W4_TITLE, questionnaire_id, counts)
        print(
            "\nNOT made the default and NOT bound to any workshop. Bind it deliberately:\n"
            "  PUT /api/workshops/{workshopId}/questionnaire   (admin)\n"
            "  python scripts/bind_workshop_questionnaire.py --workshop \"4th\" --instrument w4"
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
