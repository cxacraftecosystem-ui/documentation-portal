from __future__ import annotations

import argparse
import fnmatch
import sys
from pathlib import Path

PATTERNS = ("test_*.py", "*_test.py")
SKIP_DIRS = ("*.egg", ".*", "_darcs", "build", "CVS", "dist", "node_modules", "venv", "{arch}", "__pycache__")


def test_files(root: Path) -> list[str]:
    found: list[str] = []
    stack = [root]
    while stack:
        here = stack.pop()
        for entry in here.iterdir():
            if entry.is_dir():
                if not any(fnmatch.fnmatch(entry.name, pattern) for pattern in SKIP_DIRS):
                    stack.append(entry)
            elif entry.is_file() and any(fnmatch.fnmatch(entry.name, pattern) for pattern in PATTERNS):
                found.append(entry.as_posix())
    return sorted(found)


def shard(files: list[str], index: int, total: int) -> list[str]:
    return [name for position, name in enumerate(files) if position % total == index - 1]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", type=int, required=True)
    parser.add_argument("--total", type=int, required=True)
    parser.add_argument("--root", default="tests")
    args = parser.parse_args(argv)
    if args.total < 1 or not 1 <= args.index <= args.total:
        print(f"shard {args.index} of {args.total} is not a shard", file=sys.stderr)
        return 2
    root = Path(args.root)
    if not root.is_dir():
        print(f"{root} is not a directory", file=sys.stderr)
        return 2
    files = test_files(root)
    if not files:
        print(f"no test files under {root}", file=sys.stderr)
        return 2
    parts = [shard(files, i, args.total) for i in range(1, args.total + 1)]
    flat = [name for part in parts for name in part]
    if sorted(flat) != files or len(set(flat)) != len(files):
        print("the shards do not partition the test files", file=sys.stderr)
        return 2
    mine = parts[args.index - 1]
    if not mine:
        print(f"shard {args.index} of {args.total} is empty; use fewer shards", file=sys.stderr)
        return 2
    print(f"shard {args.index} of {args.total}: {len(mine)} of {len(files)} test files", file=sys.stderr)
    for name in mine:
        print(name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
