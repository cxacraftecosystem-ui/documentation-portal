from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path("shared/data-explorer-web.sha256")
TREES = ("frontend/lib/dataExplorer", "frontend/components/dataExplorer")
EXTRA = (
    "frontend/public/download-stream-sw.js",
    "shared/data-explorer-web-imports.json",
    "scripts/sync_data_explorer.py",
    "frontend/e2e/data-explorer-web-imports-unit.spec.ts",
    "frontend/e2e/data-explorer-engine-unit.spec.ts",
    "frontend/e2e/data-explorer.spec.ts",
    "frontend/e2e/support/dataExplorerFake.ts",
)


def shared_files(root: Path) -> list[str]:
    tree = sorted(
        path.relative_to(root).as_posix()
        for name in TREES
        for path in (root / name).rglob("*")
        if path.is_file() and path.suffix in (".ts", ".tsx", ".json")
    )
    return tree + list(EXTRA)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def manifest_text(root: Path) -> str:
    return "".join(f"{digest(root / rel)}  {rel}\n" for rel in shared_files(root))


def main() -> int:
    parser = argparse.ArgumentParser(description="Copy the shared web data explorer to the other portal and rewrite both manifests.")
    parser.add_argument("--to", type=Path, help="the other portal's repository root")
    parser.add_argument("--check", action="store_true", help="verify this repository's manifest only")
    args = parser.parse_args()

    if args.check:
        expected = (ROOT / MANIFEST).read_text(encoding="utf-8").replace("\r\n", "\n")
        if expected != manifest_text(ROOT):
            print("shared/data-explorer-web.sha256 is out of date; run with --to to resync", file=sys.stderr)
            return 1
        print("data explorer web manifest OK")
        return 0

    text = manifest_text(ROOT)
    (ROOT / MANIFEST).write_text(text, encoding="utf-8", newline="\n")
    if args.to:
        target = args.to.resolve()
        for name in TREES:
            if (target / name).exists():
                shutil.rmtree(target / name)
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
        print("manifest rewritten")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
