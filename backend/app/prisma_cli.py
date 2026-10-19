"""``python -m prisma <args>`` with the generated types left unloaded: ``python -m app.prisma_cli <args>``.

The Prisma CLI is a Python package, and ``python -m prisma`` imports it whole, generated client
included, before it hands the command to Node. On this schema that import is the 28 MB
``prisma/types.py`` (app/prisma_lazy_types.py says what it costs): measured 2026-10-19, a
``migrate deploy`` held 585 MB and was still importing after ten minutes in a python:3.14 container,
before it had sent one statement. On the t3.small it ran beside the live API, which is how deploys
ran out of memory. This entry point installs the lazy ``prisma.types`` first and then runs the same
``prisma.cli.main``, so the command, its arguments, its output and its exit status are unchanged.

backend/deploy/blue_green.sh runs ``migrate deploy`` through it. ``prisma generate`` does not need
it: the release's client is generated on the GitHub runner into a venv with no client yet
(.github/workflows/deploy-backend.yml), where there is nothing to load.
"""

from __future__ import annotations

import sys

from app import prisma_lazy_types


def main() -> None:
    """Exits with the Prisma CLI's own status (``prisma.cli.main`` always raises SystemExit)."""
    prisma_lazy_types.install()
    from prisma.cli import main as prisma_main

    prisma_main(["prisma", *sys.argv[1:]])


if __name__ == "__main__":
    main()
