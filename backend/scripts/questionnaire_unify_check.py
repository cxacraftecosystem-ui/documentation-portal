"""Check Field Repository's half of the questionnaire unification backfill (UNIFY_2026-10-21.md 3.1, U-M1).

Field Repository needs no data script: the U0 migration (20261107090000_questionnaire_unify_additive)
backfills in SQL. This READ-ONLY check proves it did, and is the figure the owner approves (spec
section 3.3, item 7):

* every instrument is shared with everyone and its author is its creator;
* every workshop with ``Workshop.questionnaireId`` has exactly one PRIMARY binding, to that form;
* no workshop has a primary binding its mirror column disagrees with, and no workshop has two.

Before the U0 release it reports what the migration WILL create. It runs in a verified READ ONLY
transaction, refuses a host not named in ``--allow-host`` (``scripts/qv2_guard.py``, shared) and
prints counts only. Exit status 1 when a check fails.

    python scripts/questionnaire_unify_check.py --allow-host <db host>
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import timedelta
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts import qv2_guard


async def counts(client: Any) -> dict[str, Any]:
    q = client.query_raw
    tables = {str(r["t"]) for r in await q("SELECT table_name AS t FROM information_schema.tables WHERE table_schema = current_schema()")}
    cols = {str(r["c"]) for r in await q(
        "SELECT column_name AS c FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'Questionnaire'")}
    u0 = "QuestionnaireBinding" in tables and "isShared" in cols

    async def one(sql: str) -> int:
        return int((await q(sql))[0]["n"])

    out: dict[str, Any] = {
        "u0": u0,
        "instruments": await one('SELECT count(*) AS n FROM "Questionnaire"'),
        "boundWorkshops": await one('SELECT count(*) AS n FROM "Workshop" WHERE "questionnaireId" IS NOT NULL'),
    }
    if not u0:
        out.update({"bindingsToCreate": out["boundWorkshops"], "instrumentsToShare": out["instruments"]})
        out["failures"] = 0
        return out
    out.update({
        "instrumentsShared": await one('SELECT count(*) AS n FROM "Questionnaire" WHERE "isShared"'),
        "instrumentsOwnerIsCreator": await one('SELECT count(*) AS n FROM "Questionnaire" WHERE "ownerId" IS NOT DISTINCT FROM "createdById"'),
        "primaryBindings": await one('SELECT count(*) AS n FROM "QuestionnaireBinding" WHERE "isPrimary" AND "workshopId" IS NOT NULL'),
        "boundWithoutPrimary": await one(
            'SELECT count(*) AS n FROM "Workshop" w WHERE w."questionnaireId" IS NOT NULL AND NOT EXISTS ('
            'SELECT 1 FROM "QuestionnaireBinding" b WHERE b."workshopId" = w."id" AND b."isPrimary" AND b."questionnaireId" = w."questionnaireId")'),
        "primaryDisagreesWithMirror": await one(
            'SELECT count(*) AS n FROM "QuestionnaireBinding" b JOIN "Workshop" w ON w."id" = b."workshopId" '
            'WHERE b."isPrimary" AND b."questionnaireId" IS DISTINCT FROM w."questionnaireId"'),
        "workshopsWithTwoPrimaries": await one(
            'SELECT count(*) AS n FROM (SELECT "workshopId" FROM "QuestionnaireBinding" WHERE "isPrimary" AND "workshopId" IS NOT NULL '
            'GROUP BY "workshopId" HAVING count(*) > 1) d'),
        "sharedTitleClashes": await one(
            'SELECT count(*) AS n FROM (SELECT "title" FROM "Questionnaire" WHERE "isShared" AND "deletedAt" IS NULL '
            'GROUP BY "title" HAVING count(*) > 1) d'),
    })
    out["failures"] = (
        (out["instruments"] - out["instrumentsShared"]) + (out["instruments"] - out["instrumentsOwnerIsCreator"])
        + out["boundWithoutPrimary"] + out["primaryDisagreesWithMirror"] + out["workshopsWithTwoPrimaries"] + out["sharedTitleClashes"]
    )
    return out


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    client = await qv2_guard.connect(url)
    try:
        async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=120)) as tx:
            await qv2_guard.assert_read_only(tx)
            out = await counts(tx)
    finally:
        await client.disconnect()
    print(json.dumps(out, indent=2))
    print("CHECK: read only; nothing was written.")
    return 1 if out["failures"] else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--database-url", default=None, help="defaults to $DATABASE_URL; the host must be in --allow-host")
    parser.add_argument("--allow-host", action="append", default=[], help="a database host this run may read (repeatable)")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
