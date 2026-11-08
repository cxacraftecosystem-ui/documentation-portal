"""Copy the shared data-report builder to the other portal and rewrite both manifests.

The builder (backend/app/services/datareport) is ONE package kept byte-identical in
designer-portal and documentation-portal; portal differences come from datareport/portal.py and
each portal's generated schema_<portal>.json. Edit it here, in designer-portal, then run from this
repository's root:

    python scripts/sync_data_report.py --to ../documentation-portal   # schemas, audit, copy, manifests
    python scripts/sync_data_report.py --schema                       # this portal's schema + audit only
    python scripts/sync_data_report.py --check                        # verify this repo's manifest

Digests are taken over LF-normalised text, so a Windows checkout (CRLF) and CI (LF) agree.
backend/tests/test_data_report_parity.py fails when any listed file differs from shared/data-report.sha256.
This script is itself one of the listed files, so the two copies, and the file list in them, cannot drift.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/data-report.sha256")
PACKAGE = "backend/app/services/datareport"
AUDIT = "docs/data-explorer/XLSX_AUDIT.md"
SHARED_EXTRA = (
    "backend/app/api/routes/data_report.py",
    "backend/prisma/migrations/20261211100000_data_bundles/migration.sql",
    "backend/tests/report_fixtures.py",
    "backend/tests/test_report_xlsx.py",
    "backend/tests/test_report_budget.py",
    "backend/tests/test_report_coverage.py",
    "backend/tests/test_report_i18n.py",
    "backend/tests/test_report_pack_hindi.py",
    "backend/tests/test_data_report_parity.py",
    "backend/tests/test_data_report_go.py",
    "backend/tests/report_worker.py",
    "backend/tests/test_report_worker.py",
    "scripts/sync_data_report.py",
    AUDIT,
    "docs/data-explorer/DATA_REPORT.md",
)
PORTALS = {"DesignWorkshop": "designer", "FieldRepository": "fieldRepository"}


def portal_of(root: Path) -> str:
    text = (root / "backend/app/services/naming_portal.py").read_text(encoding="utf-8")
    for name, key in PORTALS.items():
        if f'"{name}"' in text:
            return key
    raise SystemExit(f"cannot tell which portal {root} is")


def shared_files(root: Path) -> list[str]:
    package = sorted(
        path.relative_to(root).as_posix()
        for path in (root / PACKAGE).rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix in (".py", ".json", ".ttf", ".txt")
    )
    extra = [rel for rel in SHARED_EXTRA if (root / rel).is_file()]
    return package + extra


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def manifest_text(root: Path) -> str:
    return "\n".join(f"{digest(root / rel)}  {rel}" for rel in shared_files(root)) + "\n"


def _import(root: Path):
    sys.path.insert(0, str(root / "backend"))
    from app.services.datareport import coverage, schema

    return schema, coverage


def write_schema(root: Path, into: Path) -> None:
    schema, _ = _import(ROOT)
    parsed = schema.parse_prisma((root / "backend/prisma/schema.prisma").read_text(encoding="utf-8"))
    target = into / PACKAGE / f"schema_{portal_of(root)}.json"
    target.write_text(schema.dump(parsed), encoding="utf-8", newline="\n")
    schema.load.cache_clear()
    schema.enums.cache_clear()
    from app.services.datareport.sheets import core_bank

    original = core_bank.DATA
    core_bank.DATA = root / "backend/app/data"
    core_bank.bank.cache_clear()
    try:
        rows = core_bank.bank()
    finally:
        core_bank.DATA = original
        core_bank.bank.cache_clear()
    (into / PACKAGE / f"bank_{portal_of(root)}.json").write_text(core_bank.dump(rows), encoding="utf-8", newline="\n")
    core_bank.snapshot.cache_clear()


def write_audit(root: Path) -> None:
    _, coverage = _import(ROOT)
    path = root / AUDIT
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(coverage.document(), encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--to", type=Path, help="the other portal's repository root")
    parser.add_argument("--schema", action="store_true", help="regenerate this portal's schema JSON and the audit")
    parser.add_argument("--check", action="store_true", help="verify this repository's manifest only")
    args = parser.parse_args()

    if args.check:
        expected = (ROOT / MANIFEST).read_text(encoding="utf-8").replace("\r\n", "\n")
        if expected != manifest_text(ROOT):
            print("shared/data-report.sha256 is out of date; run with --to to resync", file=sys.stderr)
            return 1
        print("data-report manifest OK")
        return 0

    write_schema(ROOT, ROOT)
    if args.to:
        write_schema(args.to.resolve(), ROOT)
    write_audit(ROOT)
    text = manifest_text(ROOT)
    (ROOT / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    if args.to:
        target = args.to.resolve()
        package = target / PACKAGE
        if package.exists():
            shutil.rmtree(package)
        for rel in shared_files(ROOT):
            destination = target / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, destination)
        (target / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
        if manifest_text(target) != text:
            print("the copy does not match after syncing", file=sys.stderr)
            return 1
        print(f"synced {len(shared_files(ROOT))} files to {target}")
    else:
        print("schema, audit and manifest rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
