"""Field Repository's half of the unification backfill (UNIFY_2026-10-21.md 3.1 and 3.3 item 7).

On an EMPTY database of its own (``QUESTIONNAIRE_UNIFY_DATABASE_URL``; CI's "Questionnaire unify
backfill" job creates it): migrate to today's production schema (everything before the U0 release),
seed instruments and workshops bound and unbound, check that ``scripts/questionnaire_unify_check.py``
reports what U0 will create and writes nothing, apply the U0 migration, check every bound workshop
has exactly one primary binding to its form and every instrument is shared with its creator as
author, keep the mirror in step both ways, then apply the deferred migrations (``prisma/deferred``)
and check the title key: unique among shared live forms only.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

import pytest

BACKEND = Path(__file__).resolve().parents[1]
URL = os.environ.get("QUESTIONNAIRE_UNIFY_DATABASE_URL", "").strip()
U0 = "20261107090000_questionnaire_unify_additive"
SPEC = importlib.util.spec_from_file_location("questionnaire_unify_check", BACKEND / "scripts" / "questionnaire_unify_check.py")
check = importlib.util.module_from_spec(SPEC)
sys.modules["questionnaire_unify_check"] = check
SPEC.loader.exec_module(check)

pytestmark = pytest.mark.skipif(
    not URL or urlsplit(URL).hostname not in ("127.0.0.1", "localhost"),
    reason="QUESTIONNAIRE_UNIFY_DATABASE_URL is not set to an empty LOCAL database of its own (CI: the 'Questionnaire unify backfill' job)",
)


def _deploy(tmp: Path, names: list[str]) -> None:
    root = tmp / "prisma"
    (root / "migrations").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(BACKEND / "prisma" / "schema.prisma", root / "schema.prisma")
    shutil.copyfile(BACKEND / "prisma" / "migrations" / "migration_lock.toml", root / "migrations" / "migration_lock.toml")
    for name in names:
        src = BACKEND / "prisma" / "migrations" / name
        if not src.exists():
            src = BACKEND / "prisma" / "deferred" / name
        if not (root / "migrations" / name).exists():
            shutil.copytree(src, root / "migrations" / name)
    out = subprocess.run([sys.executable, "-m", "prisma", "migrate", "deploy", "--schema", str(root / "schema.prisma")],
                         cwd=BACKEND, env={**os.environ, "DATABASE_URL": URL}, capture_output=True, text=True, timeout=900, check=False)
    assert out.returncode == 0, out.stdout[-3000:] + out.stderr[-3000:]


def _migrations(before: str | None = None) -> list[str]:
    names = sorted(p.name for p in (BACKEND / "prisma" / "migrations").iterdir() if p.is_dir())
    return [n for n in names if before is None or n < before]


def _db(fn):
    async def go():
        from prisma import Prisma

        client = Prisma(datasource={"url": URL})
        await client.connect()
        try:
            return await fn(client)
        finally:
            await client.disconnect()

    return asyncio.run(go())


def _check(capfd) -> tuple[int, dict]:
    try:
        code = check.main(["--database-url", URL, "--allow-host", urlsplit(URL).hostname])
    except SystemExit as exc:
        code = int(exc.code or 0)
    out = capfd.readouterr().out
    match = re.search(r"^\{\n.*?^\}$", out, re.MULTILINE | re.DOTALL)
    assert match and "nothing was written" in out, out
    return code, json.loads(match.group(0))


SEED = [
    ("INSERT INTO \"User\" (\"id\", \"email\", \"name\", \"role\", \"updatedAt\") VALUES ('u1', 'u1@x.test', 'Ankur', 'MASTER_ADMIN', now()), "
    "('u2', 'u2@x.test', 'Nandini', 'PROFESSOR', now())"),
    ("INSERT INTO \"Questionnaire\" (\"id\", \"title\", \"sortOrder\", \"createdById\", \"updatedAt\") VALUES "
    "('q1', '2nd Craft Toolkit', 1, 'u1', now()), ('q2', 'Market survey', 2, 'u2', now())"),
    ("INSERT INTO \"Workshop\" (\"id\", \"title\", \"date\", \"place\", \"createdById\", \"questionnaireId\", \"updatedAt\") VALUES "
    "('w1', 'One', '2026-06-19', 'Kolkata', 'u1', 'q1', now()), ('w2', 'Two', '2026-06-20', 'Kolkata', 'u1', 'q1', now()), "
    "('w3', 'Three', '2026-06-21', 'Bagru', 'u2', 'q2', now()), ('w4', 'Unbound', '2026-06-22', 'Bagru', 'u2', NULL, now())"),
]


def test_the_backfill_binds_every_workshop_and_shares_every_instrument(tmp_path: Path, capfd) -> None:
    _deploy(tmp_path, _migrations(before=U0))

    async def seed(client):
        for sql in SEED:
            await client.execute_raw(sql)

    async def instruments(client):  # the migrations seed the standard instrument(s) themselves
        return int((await client.query_raw('SELECT count(*) AS n FROM "Questionnaire"'))[0]["n"])

    base = _db(instruments)
    _db(seed)
    code, before = _check(capfd)
    assert code == 0 and before == {"u0": False, "instruments": base + 2, "boundWorkshops": 3, "bindingsToCreate": 3,
                                    "instrumentsToShare": base + 2, "failures": 0}

    _deploy(tmp_path, _migrations())
    code, after = _check(capfd)
    assert code == 0, after
    assert (after["instrumentsShared"], after["instrumentsOwnerIsCreator"], after["primaryBindings"]) == (base + 2, base + 2, 3)
    assert after["boundWithoutPrimary"] == after["primaryDisagreesWithMirror"] == after["workshopsWithTwoPrimaries"] == 0

    async def bindings(client):
        rows = await client.query_raw('SELECT "questionnaireId", "workshopId", "isPrimary" FROM "QuestionnaireBinding" ORDER BY "workshopId"')
        owners = await client.query_raw('SELECT "id", "ownerId" FROM "Questionnaire" WHERE "id" = ANY($1::text[]) ORDER BY "id"', ["q1", "q2"])
        return [(r["questionnaireId"], r["workshopId"], r["isPrimary"]) for r in rows], [(o["id"], o["ownerId"]) for o in owners]

    rows, owners = _db(bindings)
    assert rows == [("q1", "w1", True), ("q1", "w2", True), ("q2", "w3", True)]
    assert owners == [("q1", "u1"), ("q2", "u2")]

    async def rebind(client):  # the mirror both ways: the old admin route, then a binding route
        await client.execute_raw("UPDATE \"Workshop\" SET \"questionnaireId\" = 'q2' WHERE \"id\" = 'w1'")
        await client.execute_raw("INSERT INTO \"QuestionnaireBinding\" (\"id\", \"questionnaireId\", \"workshopId\", \"isPrimary\") VALUES ('b4', 'q1', 'w4', true)")
        return [(r["id"], r["questionnaireId"]) for r in await client.query_raw('SELECT "id", "questionnaireId" FROM "Workshop" ORDER BY "id"')]

    assert _db(rebind) == [("w1", "q2"), ("w2", "q1"), ("w3", "q2"), ("w4", "q1")]
    code, moved = _check(capfd)
    assert code == 0 and moved["primaryBindings"] == 4 and moved["failures"] == 0

    folder = BACKEND / "prisma" / "deferred"  # empty once the switch-on commit moved them in
    deferred = sorted(p.name for p in folder.iterdir() if p.is_dir()) if folder.is_dir() else []
    _deploy(tmp_path, _migrations() + deferred)

    async def titles(client):
        names = {str(r["n"]) for r in await client.query_raw("SELECT indexname AS n FROM pg_indexes WHERE tablename = 'Questionnaire'")}
        await client.execute_raw("INSERT INTO \"Questionnaire\" (\"id\", \"title\", \"sortOrder\", \"isShared\", \"updatedAt\") VALUES "
                                 "('p1', 'Mine', 3, false, now()), ('p2', 'Mine', 4, false, now())")
        refused = False
        try:
            await client.execute_raw("INSERT INTO \"Questionnaire\" (\"id\", \"title\", \"sortOrder\", \"isShared\", \"updatedAt\") VALUES "
                                     "('s3', '2nd Craft Toolkit', 5, true, now())")
        except Exception:  # noqa: BLE001 - the unique violation is the assertion
            refused = True
        origins = {str(r["v"]) for r in await client.query_raw(
            "SELECT e.enumlabel AS v FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'AnswerOrigin'")}
        return names, refused, origins

    names, refused, origins = _db(titles)
    assert "Questionnaire_title_key" not in names and "Questionnaire_shared_title_key" in names
    assert refused, "two shared live forms took one title"
    assert "WORKBOOK" in origins
