from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

APPROVAL = re.compile(r"^Approved-by:\s+(\S+@\S+)\s+(\d{4}-\d{2}-\d{2})\s*$", re.MULTILINE)
REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "docs" / "ops"


class Refused(SystemExit):
    def __init__(self, message: str) -> None:
        print(f"REFUSED: {message}", file=sys.stderr)
        super().__init__(2)


def add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--database-url", default=None, help="defaults to $DATABASE_URL; the host must be in --allow-host")
    parser.add_argument("--allow-host", action="append", default=[], help="a database host this run may touch (repeatable)")
    parser.add_argument("--apply", action="store_true", help="write (needs --approved-report)")
    parser.add_argument("--approved-report", default=None, help="the dry-run report with the owner's Approved-by line")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="where the dry-run report is written")


def add_as_of(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--as-of", default=None, help="the IST day the report is computed as of (YYYY-MM-DD); --apply needs it")


def as_of(args: argparse.Namespace) -> Any:
    from datetime import UTC, date, datetime, time, timedelta, timezone

    ist = timezone(timedelta(hours=5, minutes=30))
    raw = getattr(args, "as_of", None)
    if not raw:
        if getattr(args, "apply", False):
            raise Refused("--apply needs --as-of (the day of the approved dry run)")
        day = datetime.now(ist).date()
    else:
        try:
            day = date.fromisoformat(raw)
        except ValueError:
            raise Refused(f"--as-of {raw!r} is not a YYYY-MM-DD day") from None
    return datetime.combine(day, time(23, 59, 59), tzinfo=ist).astimezone(UTC)


def database_url(args: argparse.Namespace) -> str:
    url = (args.database_url or os.environ.get("DATABASE_URL") or "").strip()
    if not url:
        raise Refused("no database URL (pass --database-url or set DATABASE_URL)")
    return url


def check_host(url: str, allowed: list[str]) -> str:
    host = urlsplit(url).hostname or ""
    print(f"Target database host: {host or '(none)'}")
    if not host or host not in set(allowed):
        raise Refused(f"host {host!r} is not in --allow-host; name it explicitly to run against it")
    return host


def strip_approval(text: str) -> str:
    return "\n".join(line for line in text.replace("\r\n", "\n").split("\n") if not APPROVAL.match(line)).rstrip("\n") + "\n"


def digest(text: str) -> str:
    return hashlib.sha256(strip_approval(text).encode("utf-8")).hexdigest()


def check_approval(approved_path: str | None, fresh_report: str) -> tuple[str, str]:
    if not approved_path:
        raise Refused("--apply needs --approved-report <the dry-run report carrying the owner's Approved-by line>")
    path = Path(approved_path)
    if not path.exists():
        raise Refused(f"approved report {path} does not exist")
    text = path.read_text(encoding="utf-8")
    match = APPROVAL.search(text)
    if not match:
        raise Refused("the report carries no 'Approved-by: <owner email> <YYYY-MM-DD>' line")
    if digest(text) != digest(fresh_report):
        raise Refused(
            "the data has changed since that report was approved (its sha256 differs from a dry run "
            "made now); run the dry run again and have the new report approved"
        )
    return match.group(1), match.group(2)


async def connect(url: str) -> Any:
    from prisma import Prisma

    client = Prisma(datasource={"url": url})
    await client.connect()
    return client


async def assert_read_only(tx: Any) -> None:
    await tx.execute_raw("SET TRANSACTION READ ONLY")
    rows = await tx.query_raw("SHOW transaction_read_only")
    value = str(next(iter(rows[0].values()))) if rows else ""
    if value.lower() != "on":
        raise Refused("could not make the dry run read-only")


def write_report(out_dir: str, name: str, text: str) -> Path:
    path = Path(out_dir) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"Report: {path}")
    print(f"Report sha256 (without approval lines): {digest(text)}")
    return path
