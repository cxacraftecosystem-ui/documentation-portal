"""Seed the 2nd Craft Toolkit Workshop instrument — 24 sections (RESP, A..W), 284 questions.

REWRITTEN 2026-09-13, and it had to be. The previous version upserted sections on
``where={"code": ...}`` (this file, before that date, lines 18-19). Migration
20260913100000_questionnaire_instruments dropped ``QuestionnaireSection.code @unique``, which removes
``code`` from the generated ``QuestionnaireSectionWhereUniqueInput`` — so that call does not merely
behave differently, it stops existing the moment ``prisma generate`` runs. The old version also wrote
``sortOrder = the section's index in the JSON array`` in a single pass against a globally unique
sortOrder, and rewrote the prompt of any question whose (sectionId, sortOrder) matched, answers and
all. All three are fixed by ``app.services.questionnaire_seeding.seed_instrument``, which this file
now calls; the argument for each rule is in that module's docstring.

ON AN EXISTING DATABASE THIS IS A NO-OP REFRESH. The migration already created the instrument row and
adopted every section, question and interview into it, so running this changes nothing unless the
corpus file itself has changed. The live database holds one question the corpus does not (285 rows
against the file's 284); it is reported as "kept, not in the corpus" and is NOT touched.

The invocation is unchanged, because README.md tells an operator to run exactly this:

    cd backend
    .\\.venv\\Scripts\\Activate.ps1
    python scripts/seed_questionnaire.py
"""

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
