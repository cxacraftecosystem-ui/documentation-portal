from __future__ import annotations

from pathlib import Path

import pytest

from app.services.datareport import coverage, exclusions, schema
from app.services.datareport.sheets import core_bank

REPO = Path(__file__).resolve().parents[2]
SCHEMA = REPO / "backend" / "prisma" / "schema.prisma"
AUDIT = REPO / "docs" / "data-explorer" / "XLSX_AUDIT.md"
PORTALS = ("designer", "fieldRepository")


def _here() -> str:
    text = (REPO / "backend" / "app" / "services" / "naming_portal.py").read_text(encoding="utf-8")
    return "fieldRepository" if '"FieldRepository"' in text else "designer"


def test_the_schema_snapshot_is_this_portals_schema() -> None:
    portal = _here()
    parsed = schema.parse_prisma(SCHEMA.read_text(encoding="utf-8"))
    stored = schema.schema_file(portal).read_text(encoding="utf-8").replace("\r\n", "\n")
    assert stored == schema.dump(parsed), (
        "backend/prisma/schema.prisma changed; run `python scripts/sync_data_report.py --schema` (and "
        "--to ../documentation-portal from designer-portal) so the report and its audit see the new fields."
    )


@pytest.mark.parametrize("portal", PORTALS)
def test_every_field_is_exported_or_excluded(portal: str) -> None:
    if not schema.load(portal):
        pytest.skip(f"no schema snapshot for {portal}")
    assert coverage.problems(portal) == []


@pytest.mark.parametrize("portal", PORTALS)
def test_every_model_has_a_place(portal: str) -> None:
    models = schema.load(portal)
    found = coverage.collect(portal)
    exported = {source.split(".", 1)[0] for source in found.exported}
    homeless = sorted(m for m in models if m not in exported and not exclusions.excluded(m))
    assert homeless == [], f"add these models to datareport/tables.py or exclusions.MODELS: {homeless}"


def test_secrets_never_reach_a_sheet() -> None:
    for portal in PORTALS:
        found = coverage.collect(portal)
        for source in found.exported:
            model, _, name = source.partition(".")
            assert not exclusions.excluded(model, name), source
            low = name.lower()
            assert not any(word in low for word in ("password", "secret", "recoverycode", "valueenc", "fingerprint")) or source in exclusions.SENSITIVE_ALLOWED, source


def test_every_questionnaire_definition_is_in_the_question_bank() -> None:
    files = core_bank.definition_files()
    assert files, "no questionnaire definition files found"
    unknown = [p.name for p in files if p.name not in core_bank.DEFINITIONS]
    assert unknown == [], f"name these definition files in core_bank.DEFINITIONS: {unknown}"
    import json

    rows = {(name, code, prompt) for _label, code, _title, _n, prompt, name in core_bank.bank()}
    for path in files:
        for section in json.loads(path.read_text(encoding="utf-8")):
            for question in section["questions"]:
                assert (path.name, section["code"], question["prompt"]) in rows


def test_the_question_bank_snapshot_is_this_portals_definitions() -> None:
    stored = core_bank.snapshot_file(_here()).read_text(encoding="utf-8").replace("\r\n", "\n")
    assert stored == core_bank.dump(core_bank.bank()), "a questionnaire definition changed; run `python scripts/sync_data_report.py --schema`"


def test_the_audit_document_is_current() -> None:
    assert AUDIT.is_file(), "docs/data-explorer/XLSX_AUDIT.md is missing; run `python scripts/sync_data_report.py --schema`"
    assert AUDIT.read_text(encoding="utf-8").replace("\r\n", "\n") == coverage.document(), (
        "docs/data-explorer/XLSX_AUDIT.md is out of date; run `python scripts/sync_data_report.py --schema`."
    )
