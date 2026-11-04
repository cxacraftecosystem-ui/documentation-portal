import asyncio

from app.core.db import connect_db, disconnect_db
from app.services.questionnaire_instruments import W3_ID, W3_TITLE
from app.services.questionnaire_seeding import DATA_DIR, print_counts, seed_instrument

DESCRIPTION = (
    "The instrument used from the 3rd Craft Toolkit Workshop on: 22 sections (A-V), 81 questions. "
    "Section V's questions ask for lists — designers, suppliers, buyers, digital, financial and "
    "institutional connections — and are answered as prose, like every other question here."
)


async def main() -> None:
    await connect_db()
    try:
        questionnaire_id, counts = await seed_instrument(
            questionnaire_id=W3_ID,
            title=W3_TITLE,
            description=DESCRIPTION,
            corpus_path=DATA_DIR / "questionnaire_questions_w3.json",
        )
        print_counts(W3_TITLE, questionnaire_id, counts)
        print(
            "\nNOT made the default and NOT bound to any workshop. Do that deliberately:\n"
            "  PUT /api/questionnaires/{id}/default            (admin)\n"
            "  PUT /api/workshops/{workshopId}/questionnaire   (admin)\n"
            "\nAND NOT BEFORE the Android release carrying questionnaireId has rolled out AND every\n"
            "handset's offline outbox has drained: a payload queued by an older build replays with no\n"
            "questionnaireId and lands on whatever the default is AT REPLAY TIME."
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
