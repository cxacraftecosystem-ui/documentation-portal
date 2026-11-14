from __future__ import annotations

import asyncio
import json
import os
import sys
import uuid
from urllib.parse import urlsplit

HERE = os.getcwd()
sys.path.insert(0, HERE)

SECTIONS = (
    ("A", "Origin, history, place and personal journey", 5),
    ("B", "Family, community, gender and knowledge transfer", 4),
    ("C", "Migration, mobility and changing livelihoods", 1),
    ("D", "Raw materials, procurement and resource security", 9),
    ("E", "Process of making and workflow", 10),
    ("F", "Tools, equipment, ergonomics and technological improvement", 5),
    ("G", "Science, indigenous knowledge and technology", 8),
    ("H", "Economics, market, sales, profit and livelihood", 7),
    ("I", "Marketing, value chains and digital commerce", 4),
    ("J", "Digital technology, AI and future platforms", 4),
    ("K", "Sustainability, environment, pollution and waste", 6),
    ("L", "Rituals, beliefs and intangible heritage", 2),
    ("M", "Health, occupational hazards and reasons for leaving", 3),
    ("N", "Design, form and aesthetics", 4),
    ("O", "Quality, standards and certification", 4),
    ("P", "Training, apprenticeship and skill upgrading", 4),
    ("Q", "Institutions, schemes and support", 4),
    ("R", "Finance, credit and savings", 4),
    ("S", "Collective action and cooperatives", 4),
    ("T", "Women in the craft", 4),
    ("U", "Youth and the next generation", 4),
    ("V", "Innovation and new products", 4),
    ("W", "Aspirations and the future of the craft", 4),
)

PROMPTS = {
    "E1": "Please describe the complete process from raw material to finished product, including the stages that contribute most to quality.",
    "E2": "Which stages require the highest level of skill, precision or are hardest for beginners to learn, and why?",
    "E3": "Which part of the work takes the most time or creates the greatest difficulty, and what change in the tool or process would make it easier, faster or safer?",
    "E4": "Which stage causes the most wastage, and what happens to the waste generated there?",
    "E5": "How do experienced artisans identify and correct mistakes during the making process?",
}

ARTISANS = ("Meera Devi", "Ramesh Maharana", "Sushila Sahoo", "Bhagirathi Nayak", "Lalita Behera")


def _refuse_remote() -> None:
    host = (urlsplit(os.environ.get("DATABASE_URL", "")).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("seed_interview_stack.py writes; it runs only against a loopback CI database.")


async def main() -> dict:
    _refuse_remote()
    import app  # noqa: F401
    from app.core.db import db
    from app.core.security import create_access_token

    await db.connect()
    tag = uuid.uuid4().hex[:8]
    try:
        people = {"lead": ("RESEARCHER", "Nandini Rao"), "other": ("RESEARCHER", "Priya Shah"), "admin": ("ADMIN", "Ankit Kumar")}
        users = {}
        for key, (role, name) in people.items():
            uid = f"iv{key}{tag}"
            await db.execute_raw(
                'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
                uid, f"{uid}@example.test", name, role,
            )
            users[key] = {"id": uid, "name": name, "email": f"{uid}@example.test", "role": role}
        craft = f"ivc{tag}"
        await db.execute_raw('INSERT INTO "Craft" ("id", "name", "createdById", "updatedAt") VALUES ($1, $2, $3, now())',
                             craft, "Stone carving", users["admin"]["id"])
        location = f"ivl{tag}"
        await db.execute_raw(
            'INSERT INTO "Location" ("id", "latitude", "longitude", "village", "district", "state", "updatedAt") '
            "VALUES ($1, 19.8135, 85.8312, 'Puri', 'Puri', 'Odisha', now())", location,
        )
        workshop = f"ivw{tag}"
        await db.execute_raw(
            'INSERT INTO "Workshop" ("id", "title", "date", "startDate", "endDate", "place", "createdById", "locationId", "status", "updatedAt") '
            "VALUES ($1, '4th Craft Toolkit Workshop', now(), now() - interval '1 day', now() + interval '10 days', 'Puri', $2, $3, 'APPROVED', now())",
            workshop, users["admin"]["id"], location,
        )
        await db.execute_raw('INSERT INTO "WorkshopCraft" ("workshopId", "craftId") VALUES ($1, $2)', workshop, craft)
        form = f"ivf{tag}"
        await db.execute_raw(
            'INSERT INTO "Questionnaire" ("id", "title", "sortOrder", "updatedAt") VALUES ($1, $2, 1, now())', form, "4th Craft Toolkit"
        )
        await db.execute_raw(
            'INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary", "createdById") VALUES ($1, $2, $3, true, $4)',
            f"ivb{tag}", form, workshop, users["admin"]["id"],
        )
        sections: dict[str, str] = {}
        for order, (code, title, count) in enumerate(SECTIONS, start=1):
            sid = f"ivs{code}{tag}"
            await db.execute_raw(
                'INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "questionnaireId", "updatedAt") '
                "VALUES ($1, $2, $3, $4, $5, now())",
                sid, code, title, order, form,
            )
            sections[code] = sid
            for n in range(1, count + 1):
                prompt = PROMPTS.get(f"{code}{n}", f"{title}: question {n}?")
                await db.execute_raw(
                    'INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", '
                    '"questionnaireId", "isRequired", "updatedAt") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now())',
                    f"ivq{code}{n}{tag}", code, title, prompt, n, sid, form, n == 1,
                )
        artisans: dict[str, str] = {}
        for n, name in enumerate(ARTISANS):
            aid = f"iva{n}{tag}"
            await db.execute_raw(
                'INSERT INTO "Artisan" ("id", "name", "place", "status", "createdById", "updatedAt", "submittedAt", "craftId") '
                "VALUES ($1, $2, 'Puri', 'APPROVED', $3, now(), now(), $4)",
                aid, name, users["admin"]["id"], craft,
            )
            artisans[name] = aid
        rows = await db.query_raw(
            'SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1 ORDER BY "displayCode"', form
        )
        payload = {
            "portal": "documentation-portal",
            "questionnaireId": form,
            "workshopField": "workshopId",
            "workshopId": workshop,
            "place": "Puri",
            "sections": sections,
            "sectionTitles": {code: title for code, title, _ in SECTIONS},
            "questions": {str(r["displayCode"]): str(r["lineageId"]) for r in rows},
            "artisans": artisans,
            "users": users,
            "tokens": {key: create_access_token(u["id"]) for key, u in users.items()},
        }
    finally:
        await db.disconnect()
    print(f"seeded {form} with {len(rows)} questions in {len(SECTIONS)} sections")
    return payload


if __name__ == "__main__":
    seeded = asyncio.run(main())
    with open(sys.argv[1], "w", encoding="utf-8") as handle:
        json.dump(seeded, handle)
