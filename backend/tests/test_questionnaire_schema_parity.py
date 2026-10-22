"""The questionnaire tables are the same in both portals (UNIFY_2026-10-21.md, U3).

THIS FILE IS IDENTICAL IN designer-portal AND documentation-portal. ``shared/questionnaire.prisma``
holds every questionnaire ``enum`` and ``model`` block both schemas must carry word for word, and,
for the tables still converging (designer-portal's forms and global instrument become one table in
U5), the fields each must carry with the same base type. No database: it reads text.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SHARED = (REPO / "shared" / "questionnaire.prisma").read_text(encoding="utf-8").replace("\r\n", "\n")
SCHEMA = (REPO / "backend" / "prisma" / "schema.prisma").read_text(encoding="utf-8").replace("\r\n", "\n")

_BLOCK = re.compile(r"((?:^///[^\n]*\n)*)^(model|enum) (\w+) \{\n.*?^\}\n", re.MULTILINE | re.DOTALL)


def _blocks(text: str) -> dict[tuple[str, str], str]:
    return {(m.group(2), m.group(3)): m.group(0) for m in _BLOCK.finditer(text)}


def _fields(block: str) -> dict[str, str]:
    out = {}
    for raw in block.splitlines()[1:]:
        line = raw.split("//")[0].strip()
        if not line or line.startswith("@@") or line == "}":
            continue
        parts = line.split()
        if len(parts) >= 2:
            out[parts[0]] = parts[1].rstrip("?").replace("[]", "")
    return out


def test_the_shared_reference_has_blocks() -> None:
    assert len(_blocks(SHARED)) >= 25


def test_every_shared_block_is_in_the_schema_word_for_word() -> None:
    mine = _blocks(SCHEMA)
    for key, block in _blocks(SHARED).items():
        assert key in mine, f"schema.prisma has no {key[0]} {key[1]} (shared/questionnaire.prisma)"
        assert mine[key] == block, f"{key[0]} {key[1]} differs from shared/questionnaire.prisma"


def test_converging_tables_carry_the_shared_fields() -> None:
    mine = _blocks(SCHEMA)
    lines = [ln for ln in SHARED.splitlines() if ln.startswith("// converging: ")]
    assert lines, "no converging tables listed"
    for line in lines:
        name, *specs = line[len("// converging: "):].split()
        assert ("model", name) in mine, f"schema.prisma has no model {name}"
        fields = _fields(mine[("model", name)])
        for spec in specs:
            field, kind = spec.split(":")
            assert field in fields, f"{name}.{field} is missing (shared/questionnaire.prisma, converging)"
            assert fields[field] == kind, f"{name}.{field} is {fields[field]}, shared says {kind}"


def test_converging_enums_carry_the_shared_values() -> None:
    mine = _blocks(SCHEMA)
    for line in (ln for ln in SHARED.splitlines() if ln.startswith("// converging-enum: ")):
        name, *values = line[len("// converging-enum: "):].split()
        have = {line.split("//")[0].strip() for line in mine[("enum", name)].splitlines()[1:]}
        for value in values:
            assert value in have, f"enum {name} lacks {value}"


def test_the_migration_of_the_unification_exists_in_this_portal() -> None:
    sql = REPO / "backend" / "prisma" / "migrations" / "20261107090000_questionnaire_unify_additive" / "migration.sql"
    text = sql.read_text(encoding="utf-8")
    for table in ("QuestionnaireRespondent", "QuestionnaireRecordingRespondent", "QuestionnaireBinding", "QuestionnaireTypeDefault"):
        assert f'CREATE TABLE "{table}"' in text, table
    assert "CREATE VIEW questionnaire_take_v" in text and 'i."deletedAt" IS NULL' in text
