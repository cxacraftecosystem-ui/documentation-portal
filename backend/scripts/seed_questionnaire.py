import asyncio
import json
from pathlib import Path

from app.core.db import connect_db, db, disconnect_db

QUESTIONNAIRE_PATH = Path(__file__).resolve().parents[1] / "app" / "data" / "questionnaire_questions.json"


async def main() -> None:
    sections = json.loads(QUESTIONNAIRE_PATH.read_text(encoding="utf-8-sig"))
    await connect_db()
    try:
        count = 0
        for section in sections:
            section_code = section["code"]
            section_title = section["title"]
            for question in section["questions"]:
                await db.questionnairequestion.upsert(
                    where={
                        "sectionCode_sortOrder": {
                            "sectionCode": section_code,
                            "sortOrder": question["sortOrder"],
                        }
                    },
                    data={
                        "create": {
                            "sectionCode": section_code,
                            "sectionTitle": section_title,
                            "sortOrder": question["sortOrder"],
                            "prompt": question["prompt"],
                        },
                        "update": {
                            "sectionTitle": section_title,
                            "prompt": question["prompt"],
                            "isActive": True,
                        },
                    },
                )
                count += 1
        print(f"Seeded questionnaire questions: {count}")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
