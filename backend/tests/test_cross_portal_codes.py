"""SHARED CRAFT AND QUESTION CODES (C26) — one file, both portals, and the migration that seeded it.

This file is identical in the designer portal and the documentation portal. It holds:

* the shared file to itself: codes unique and slug-shaped, no spelling claimed by two crafts, no two
  questions with one section and wording, and every question of the corpora each portal seeds from;
* the Python folding to the SQL folding the backfill migration runs, on every spelling and prompt;
* the migration to the file: every code, spelling and question code the file holds is in the SQL;
* the service's lookups and an administrator's mapping, against a fake client;
* the write paths to the codes, read from source: a craft's code is set AFTER its provenance stamp;
* the two repositories to each other (and to the cxa-cms public site's craft slugs) when the
  sibling checkouts are present.

No database.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
from types import SimpleNamespace
from typing import Any

import pytest

from app.services import cross_portal_codes as cpc

pytestmark = pytest.mark.anyio

BACKEND = pathlib.Path(__file__).resolve().parents[1]
ROOT = BACKEND.parent
DATA = BACKEND / "app" / "data"
MIGRATION = BACKEND / "prisma" / "migrations" / "20261021090000_cross_portal_codes" / "migration.sql"
IS_DESIGNER_PORTAL = any(ROOT.glob("android/app/src/main/java/com/designprototype"))


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def raw() -> dict[str, Any]:
    return json.loads(cpc.CODES_FILE.read_text(encoding="utf-8"))


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The file
# ──────────────────────────────────────────────────────────────────────────────────────────────


def test_craft_codes_are_unique_and_slug_shaped():
    codes = [c["code"] for c in raw()["crafts"]]
    assert len(codes) >= 40, "a codes file that failed to load would pass vacuously"
    assert len(codes) == len(set(codes))
    for code in codes:
        assert re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", code), code


def test_no_spelling_is_claimed_by_two_crafts():
    owner: dict[str, str] = {}
    for craft in raw()["crafts"]:
        for spelling in (craft["name"], *craft["spellings"]):
            key = cpc.normalise(spelling)
            assert key, spelling
            assert owner.setdefault(key, craft["code"]) == craft["code"], (
                f"{spelling!r} is claimed by {owner[key]} and {craft['code']}"
            )


def test_question_codes_are_unique_and_one_per_section_and_wording():
    questions = cpc.shared().questions
    assert len(questions) >= 360
    assert len({q.code for q in questions}) == len(questions)
    keys = [(q.section, cpc.normalise(q.prompt)) for q in questions]
    assert len(set(keys)) == len(keys)
    for q in questions:
        assert re.fullmatch(r"CTW[0-9]+\.[A-Z]+\.[0-9]{2}", q.code), q.code
        assert q.code.split(".")[1] == q.section


@pytest.mark.parametrize(
    "corpus",
    [
        "questionnaire_questions.json",
        "craft_toolkit_workshop_2_questions.json",
        "craft_toolkit_workshop_3_questions.json",
        "questionnaire_questions_w3.json",
    ],
)
def test_every_question_this_portal_seeds_from_has_a_code(corpus):
    path = DATA / corpus
    if not path.is_file():
        pytest.skip(f"{corpus} is not one of this portal's corpora")
    sections = json.loads(path.read_text(encoding="utf-8-sig"))
    missing = [
        (s["code"], q["prompt"])
        for s in sections
        for q in s["questions"]
        if cpc.question_code(s["code"], q["prompt"]) is None
    ]
    assert missing == []


def test_the_sql_folding_matches_the_python_folding_on_everything_in_the_file():
    shared = cpc.shared()
    texts = [s for c in shared.crafts for s in (c.name, *c.spellings)] + [
        q.prompt for q in shared.questions
    ]
    disagree = [t for t in texts if cpc.normalise(t) != cpc.sql_normalise_emulation(t)]
    assert disagree == []


def test_folding_examples():
    assert cpc.normalise("  Block-PRINTING ") == "block printing"
    assert cpc.normalise("Kashmir papier-mâché") == "kashmir papier mache"
    assert cpc.normalise("Artisan’s name") == "artisan s name"
    assert cpc.normalise(None) == ""
    assert cpc.shared_craft_code("BANDHEJ") == "bandhani"
    assert cpc.shared_craft_code("Dabu hand block printing") == "dabu"
    assert cpc.shared_craft_code("Something nobody wrote") is None
    assert cpc.question_code("RESP", "Name of Artisan") == "CTW2.RESP.01"
    assert cpc.question_code("RESP", "  name of ARTISAN ") == "CTW2.RESP.01"
    assert cpc.question_code("A", "Name of Artisan") is None


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The migration
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _sql_literal(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def test_the_migration_seeds_exactly_what_the_file_holds():
    sql = MIGRATION.read_text(encoding="utf-8")
    shared = cpc.shared()
    for craft in shared.crafts:
        assert f"({_sql_literal(craft.code)}, {_sql_literal(craft.name)}, CURRENT_TIMESTAMP)" in sql
        for spelling in (craft.name, *craft.spellings):
            assert f"({_sql_literal(cpc.normalise(spelling))}, " in sql, spelling
    for q in shared.questions:
        row = f"({_sql_literal(q.section)}, {_sql_literal(cpc.normalise(q.prompt))}, {_sql_literal(q.code)})"
        assert row in sql, q.code
    assert cpc.SQL_ACCENTS_FROM in sql and cpc.SQL_ACCENTS_TO in sql


def test_the_migration_is_additive_and_guarded():
    sql = MIGRATION.read_text(encoding="utf-8")
    statements = [s.strip() for s in sql.split(";") if s.strip()]
    for statement in statements:
        body = "\n".join(line for line in statement.splitlines() if not line.startswith("--"))
        upper = body.upper()
        assert "DROP COLUMN" not in upper and "DELETE FROM" not in upper, body[:120]
        if upper.startswith("UPDATE"):
            assert '"questionCode" IS NULL' in body or '"craftCode" IS NULL' in body, body[:200]
        if upper.startswith("ALTER TABLE") and "ADD COLUMN" in upper:
            assert "IF NOT EXISTS" in upper
    assert ("QuestionnaireFormQuestion" in sql) == IS_DESIGNER_PORTAL


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The service, against a fake client
# ──────────────────────────────────────────────────────────────────────────────────────────────


class _Table:
    def __init__(self, key: str, rows: list[dict[str, Any]] | None = None):
        self.key = key
        self.rows = [dict(r) for r in rows or []]

    def _match(self, row: dict[str, Any], where: dict[str, Any]) -> bool:
        return all(row.get(k) == v for k, v in where.items())

    async def find_unique(self, where):
        hit = next((r for r in self.rows if self._match(r, where)), None)
        return SimpleNamespace(**hit) if hit else None

    async def find_many(self, where=None, include=None, order=None):
        return [SimpleNamespace(**r) for r in self.rows if self._match(r, where or {})]

    async def create(self, data):
        self.rows.append(dict(data))
        return SimpleNamespace(**data)

    async def update(self, where, data):
        for row in self.rows:
            if self._match(row, where):
                row.update(data)
                return SimpleNamespace(**row)
        raise AssertionError(f"no row {where}")


def _client(**tables: _Table) -> SimpleNamespace:
    defaults = {
        "craftcode": _Table("code"),
        "craftspelling": _Table("spelling"),
        "craft": _Table("id"),
        "questionnairequestion": _Table("id"),
    }
    defaults.update(tables)
    return SimpleNamespace(**defaults)


async def test_a_new_craft_with_a_shared_spelling_gets_its_code_and_the_code_row():
    client = _client()
    assert await cpc.code_for_new_craft(client, "Ajrak") == "ajrakh"
    assert [r["code"] for r in client.craftcode.rows] == ["ajrakh"]
    # Second time: the row exists, nothing is written again.
    assert await cpc.code_for_new_craft(client, "Ajrakh printing") == "ajrakh"
    assert len(client.craftcode.rows) == 1


async def test_an_admin_spelling_resolves_and_an_unknown_one_does_not():
    client = _client(
        craftspelling=_Table(
            "spelling",
            [{"spelling": "kota doria", "label": "Kota Doria", "craftCode": "ikat", "source": "ADMIN"}],
        )
    )
    assert await cpc.resolve_craft_code(client, "KOTA-DORIA") == "ikat"
    assert await cpc.resolve_craft_code(client, "Not a craft anyone wrote") is None
    assert await cpc.resolve_craft_code(client, "") is None


async def test_mapping_remembers_the_spelling_and_maps_its_twins():
    client = _client(
        craft=_Table(
            "id",
            [
                {"id": "c1", "name": "Kota Doria", "craftCode": None},
                {"id": "c2", "name": "kota-doria", "craftCode": None},
                {"id": "c3", "name": "Something else", "craftCode": None},
            ],
        )
    )
    craft = SimpleNamespace(id="c1", name="Kota Doria")
    out = await cpc.map_craft(client, craft=craft, code="ikat", remember_spelling=True, user_id="u1")
    assert out == {"craftId": "c1", "craftCode": "ikat", "alsoMapped": 1}
    by_id = {r["id"]: r["craftCode"] for r in client.craft.rows}
    assert by_id == {"c1": "ikat", "c2": "ikat", "c3": None}
    assert client.craftspelling.rows[0]["source"] == cpc.SOURCE_ADMIN
    assert client.craftspelling.rows[0]["spelling"] == "kota doria"


async def test_mapping_never_writes_a_shared_spelling_and_refuses_an_unknown_code():
    client = _client(craft=_Table("id", [{"id": "c1", "name": "Bandhej", "craftCode": None}]))
    craft = SimpleNamespace(id="c1", name="Bandhej")
    # An admin may map a shared spelling elsewhere on THIS craft, but the shared row is not touched.
    await cpc.map_craft(client, craft=craft, code="patola", remember_spelling=True, user_id="u1")
    assert client.craftspelling.rows == []
    with pytest.raises(ValueError):
        await cpc.map_craft(client, craft=craft, code="no-such-code", remember_spelling=False, user_id=None)


async def test_reconcile_sets_clears_and_leaves_alone():
    client = _client(
        questionnairequestion=_Table(
            "id",
            [
                {"id": "q1", "sectionCode": "RESP", "prompt": "Name of Artisan", "questionCode": None},
                {"id": "q2", "sectionCode": "RESP", "prompt": "Reworded by a builder", "questionCode": "CTW2.RESP.02"},
                {"id": "q3", "sectionCode": "RESP", "prompt": "Craft", "questionCode": "CTW2.RESP.02"},
            ],
        )
    )
    assert await cpc.reconcile_question_codes(client) == 2
    codes = {r["id"]: r["questionCode"] for r in client.questionnairequestion.rows}
    assert codes == {"q1": "CTW2.RESP.01", "q2": None, "q3": "CTW2.RESP.02"}
    assert await cpc.reconcile_question_codes(client) == 0


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The write paths, read from source
# ──────────────────────────────────────────────────────────────────────────────────────────────


def _src(rel: str) -> str:
    return (BACKEND / rel).read_text(encoding="utf-8")


def test_a_new_crafts_code_is_set_after_its_provenance_stamp():
    src = _src("app/api/routes/crafts.py")
    create = src[src.index("async def create_craft") :]
    create = create[: create.index("\n@router")]
    assert create.index("merge_field_provenance(") < create.index("code_for_new_craft(")
    assert "code_for_new_craft(" in src[src.index("async def update_craft") :]


def test_every_craft_minted_by_name_gets_a_code():
    path = "app/services/records.py" if IS_DESIGNER_PORTAL else "app/api/routes/artisans.py"
    src = _src(path)
    for match in re.finditer(r"db\.craft\.create\(", src):
        window = src[match.start() : match.start() + 400]
        assert "code_for_new_craft" in window, path


def test_question_writes_reconcile_or_stamp_the_code():
    src = _src("app/api/routes/questionnaire.py")
    assert src.count("cross_portal_codes.question_code(") >= 2
    assert "reconcile_question_codes(" in src
    if IS_DESIGNER_PORTAL:
        forms = _src("app/api/routes/questionnaire_forms.py")
        assert forms.count("reconcile_form_question_codes(") >= 8


# ──────────────────────────────────────────────────────────────────────────────────────────────
# The two repositories, and the public site
# ──────────────────────────────────────────────────────────────────────────────────────────────

SHARED_FILES = (
    "backend/app/data/cross_portal_codes.json",
    "backend/app/services/cross_portal_codes.py",
    "backend/app/api/routes/craft_codes.py",
    "backend/tests/test_cross_portal_codes.py",
)


def _sibling() -> pathlib.Path | None:
    other = "documentation-portal" if IS_DESIGNER_PORTAL else "designer-portal"
    worktree_twin = (
        ROOT.name.replace("dp-", "doc-", 1) if IS_DESIGNER_PORTAL else ROOT.name.replace("doc-", "dp-", 1)
    )
    candidates = [
        os.environ.get("SIBLING_REPO"),
        ROOT.parent / worktree_twin,
        ROOT.parent / other,
        pathlib.Path("C:/dev/IIT") / other,
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / SHARED_FILES[0]).is_file():
            return pathlib.Path(candidate)
    return None


def test_the_sibling_portal_carries_the_same_codes_and_implementation():
    sibling = _sibling()
    if sibling is None:
        pytest.skip("no sibling checkout carrying the codes (CI has one repository)")
    for rel in SHARED_FILES:
        mine = (ROOT / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
        theirs = (sibling / rel).read_text(encoding="utf-8").replace("\r\n", "\n")
        assert mine == theirs, f"{rel} differs from {sibling / rel}"


def test_every_public_site_craft_slug_is_a_shared_code():
    corpus = None
    for base in (ROOT.parent, ROOT.parent.parent, pathlib.Path("C:/dev/IIT")):
        path = base / "cxa-cms" / "prisma" / "corpus" / "crafts.ts"
        if path.is_file():
            corpus = path
            break
    if corpus is None:
        pytest.skip("no cxa-cms checkout beside this one")
    text = corpus.read_text(encoding="utf-8")
    crafts_block = text[text.index("export const CRAFTS") :]
    slugs = re.findall(r'^\s{4}slug:\s*"([^"]+)"', crafts_block, flags=re.M)
    assert len(slugs) >= 40
    codes = {c.code for c in cpc.shared().crafts}
    assert [s for s in slugs if s not in codes] == []
