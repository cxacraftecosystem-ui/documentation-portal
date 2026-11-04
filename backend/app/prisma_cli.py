from __future__ import annotations

import sys

from app import prisma_lazy_types


def main() -> None:
    prisma_lazy_types.install()
    from prisma.cli import main as prisma_main

    prisma_main(["prisma", *sys.argv[1:]])


if __name__ == "__main__":
    main()
