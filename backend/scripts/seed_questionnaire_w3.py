"""Seed the 3rd Craft Toolkit Workshop instrument — 22 sections coded A..V, 81 questions.

It is a SECOND instrument standing alongside the 2nd workshop's, not a replacement for it. Every
one of the 22 new section codes already exists in the 2nd workshop's corpus (RESP, A..W), and
before migration 20260913100000 ``QuestionnaireSection.code`` was ``@unique`` GLOBALLY — so running
anything like this against the old schema would have RETITLED 22 live sections rather than creating
22 new ones, leaving 285 questions and every answer under headings that no longer described them.
That migration is a hard prerequisite and this script will fail loudly without it.

The instrument is created INACTIVE AS THE DEFAULT. Seeding it changes nothing for anybody until an
admin binds it to a workshop (``PUT /api/workshops/{id}/questionnaire``) or makes it the default
(``PUT /api/questionnaires/{id}/default``). A seeder that flipped the default would silently re-file
every old Android build's submissions, because those clients send no questionnaireId at all.

    cd backend
    .\\.venv\\Scripts\\Activate.ps1
    python scripts/seed_questionnaire_w3.py
"""

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
