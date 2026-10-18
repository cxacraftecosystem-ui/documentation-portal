"""Every table that can name an artisan is handled by the merge, or is listed as deliberately not.

Parses ``prisma/schema.prisma``: a model with a field typed ``Artisan``/``Artisan?``, or with both a
``recordType`` and a ``recordId`` column, must appear in ``artisan_merge.MERGE_REPOINT``,
``SPECIAL_REPOINT`` or ``NOT_REPOINTED``. A table added later (another branch's notifications, say)
fails here until somebody decides what a merge does to its rows.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.services import artisan_merge

SCHEMA = (Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma").read_text(encoding="utf-8")


def _models() -> dict[str, list[tuple[str, str]]]:
    models: dict[str, list[tuple[str, str]]] = {}
    for match in re.finditer(r"^model (\w+) \{(.*?)^\}", SCHEMA, re.S | re.M):
        fields = []
        for line in match.group(2).splitlines():
            line = line.strip()
            if not line or line.startswith(("//", "@@")):
                continue
            parts = line.split()
            if len(parts) >= 2:
                fields.append((parts[0], parts[1]))
        models[match.group(1)] = fields
    return models


def _handled() -> set[str]:
    tables = {spec.table for spec in artisan_merge.MERGE_REPOINT}
    tables |= {key.split(".")[0] for key in artisan_merge.SPECIAL_REPOINT}
    tables |= set(artisan_merge.NOT_REPOINTED)
    return tables


def test_every_table_that_names_an_artisan_is_handled():
    models = _models()
    referencing: set[str] = set()
    for name, fields in models.items():
        if name == "Artisan":
            continue
        names = {f for f, _ in fields}
        types = {t.rstrip("?") for _, t in fields}
        if "Artisan" in types:
            referencing.add(name)
        if {"recordType", "recordId"} <= names:
            referencing.add(name)
        if "artisanIds" in names:
            referencing.add(name)
    missing = sorted(referencing - _handled())
    assert not missing, f"a merge does not know what to do with: {missing}"


def test_every_repoint_names_a_real_table_and_column():
    models = _models()
    for spec in artisan_merge.MERGE_REPOINT:
        assert spec.table in models, spec.table
        columns = {f for f, _ in models[spec.table]}
        assert spec.column in columns, f"{spec.table}.{spec.column}"
        for col in (*spec.key, *spec.unique_with):
            assert col in columns, f"{spec.table}.{col}"
        if spec.name_column:
            assert spec.name_column in columns


def test_every_merge_field_is_an_artisan_column():
    columns = {f for f, _ in _models()["Artisan"]}
    for group in artisan_merge.FIELD_GROUPS:
        for col in group.columns:
            assert col in columns, col
    keys = [g.key for g in artisan_merge.FIELD_GROUPS]
    for key in ("name", "localName", "gender", "phone", "email", "place", "dateOfBirth", "address",
                "notes", "dos", "donts", "experience", "craftId", "location", "workshopId", "status",
                "aadhaar", "pehchan"):
        assert key in keys
    assert "designWorkshopId" not in keys  # designer portal only
    locked = {g.key for g in artisan_merge.FIELD_GROUPS if g.locked}
    assert locked == {"aadhaar", "pehchan"}
    both = {g.key for g in artisan_merge.FIELD_GROUPS if g.both}
    assert both == {"address", "notes", "dos", "donts"}


def test_the_legacy_aadhaar_number_is_never_written_by_a_merge_choice():
    for group in artisan_merge.FIELD_GROUPS:
        assert "aadhaarNumber" not in group.columns
