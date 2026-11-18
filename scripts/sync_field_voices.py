from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/field-voices.sha256")
PACKAGE = "backend/app/services/field_voices"
SHARED_EXTRA = (
    "backend/app/api/routes/cross_portal_field_voices.py",
    "backend/app/api/routes/field_voices_admin.py",
    "backend/app/scripts/refresh_field_voices.py",
    "backend/prisma/migrations/20261218100000_field_voices/migration.sql",
    "backend/tests/test_field_voices_units.py",
    "backend/tests/test_field_voices_parity.py",
    "backend/tests/test_cross_portal_field_voices.py",
    "shared/field-voices.prisma",
    "shared/field-voices-scrub-vectors.json",
    "docs/FIELD_VOICES.md",
    "scripts/sync_field_voices.py",
    "frontend/lib/fieldVoices/strings.json",
    "frontend/lib/fieldVoices/withdrawals.ts",
    "frontend/components/fieldVoices/FieldVoicesPanel.tsx",
    "frontend/e2e/field-voices-unit.spec.ts",
)


def shared_files(root: Path) -> list[str]:
    package = sorted(
        path.relative_to(root).as_posix()
        for path in (root / PACKAGE).rglob("*.py")
        if "__pycache__" not in path.parts
    )
    return package + list(SHARED_EXTRA)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def manifest_text(root: Path) -> str:
    return "".join(f"{digest(root / rel)}  {rel}\n" for rel in shared_files(root))


def check(root: Path) -> int:
    expected = (root / MANIFEST).read_text(encoding="utf-8").replace("\r\n", "\n")
    if expected != manifest_text(root):
        print("shared/field-voices.sha256 is out of date; run scripts/sync_field_voices.py --to", file=sys.stderr)
        return 1
    print("field voices manifest OK")
    return 0


def copy_to(target: Path) -> int:
    text = manifest_text(ROOT)
    (ROOT / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
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
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog="sync_field_voices.py")
    parser.add_argument("--to", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        return check(ROOT)
    if args.to:
        return copy_to(args.to.resolve())
    (ROOT / MANIFEST).write_text(manifest_text(ROOT), encoding="utf-8", newline="\n")
    print("manifest rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
