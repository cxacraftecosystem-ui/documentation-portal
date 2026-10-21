"""Seed a THROWAWAY CI database for the web outbox's real-stack spec (audit 2026-10-21).

Run from ``backend/`` by the ``web-v2-stack`` job in ``.github/workflows/checks.yml``, against the
job's own Postgres service container, never against any other database: it refuses a DATABASE_URL
whose host is not loopback. It reuses the backend's own questionnaire v2 fixtures
(``backend/tests/qv2_fixtures.py``) so the seed has the shape the backend's DB suites use, and adds
one session as the backfill rebuilt them: with a NULL clientKey (audit C4).

Writes a JSON file (the path is the first argument) the spec reads: ids, question lineages, and
short-lived bearer tokens for two researchers and a content admin. The tokens are for that throwaway
database and die with the runner.

Usage:  python ../scripts/ci/seed_qv2_stack.py "$RUNNER_TEMP/qv2-seed.json"
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
from urllib.parse import urlsplit

HERE = os.getcwd()
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tests"))


def _refuse_remote() -> None:
    host = (urlsplit(os.environ.get("DATABASE_URL", "")).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("seed_qv2_stack.py writes; it runs only against a loopback CI database.")


async def main(out_path: str) -> None:
    _refuse_remote()
    import app  # noqa: F401  (loads settings and the Prisma client the way the API does)
    from app.core.db import db
    from app.core.security import create_access_token
    from app.services.questionnaire_v2 import sessions
    from qv2_fixtures import seed, session_create

    await db.connect()
    try:
        ids = await seed(db, artisans=3)
        rebuilt, _ = await sessions.create_session(session_create(ids, [ids.artisans[0]]), ids.lead, client=db)
        await db.execute_raw('UPDATE "QuestionnaireInterview" SET "clientKey" = NULL WHERE "id" = $1', str(rebuilt["id"]))
        rows = await db.query_raw(
            'SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1 ORDER BY "displayCode"', ids.q
        )
        payload = {
            "questionnaireId": ids.q,
            "workshopId": ids.workshop,
            "sections": ids.sections,
            "questions": {str(r["displayCode"]): str(r["lineageId"]) for r in rows},
            "artisans": ids.artisans,
            "rebuiltSessionId": str(rebuilt["id"]),
            "tokens": {"lead": create_access_token(ids.lead.id), "other": create_access_token(ids.other.id),
                       "admin": create_access_token(ids.admin.id)},
        }
    finally:
        await db.disconnect()
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle)
    print(f"Seeded questionnaire {payload['questionnaireId']} with {len(payload['questions'])} questions.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    asyncio.run(main(sys.argv[1]))
