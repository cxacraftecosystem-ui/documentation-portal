from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
from typing import Any

BACKEND = pathlib.Path(__file__).resolve().parents[1]
if str(BACKEND) not in sys.path:
    sys.path.insert(0, str(BACKEND))


async def pending(client: Any) -> dict[str, int]:
    from app.services import cross_portal_codes

    rows = await client.questionnairequestion.find_many()
    out = {"questions": len(rows), "toChange": 0, "toSet": 0, "toClear": 0}
    for row in rows:
        want = cross_portal_codes.question_code(getattr(row, "sectionCode", None), getattr(row, "prompt", None))
        have = getattr(row, "questionCode", None)
        if have == want:
            continue
        out["toChange"] += 1
        out["toSet" if want else "toClear"] += 1
    return out


async def run(apply: bool) -> int:
    from app.core.db import connect_db, db, disconnect_db
    from app.services import cross_portal_codes

    await connect_db()
    try:
        before = await pending(db)
        print(
            f"Questions: {before['questions']}; codes to change: {before['toChange']} "
            f"({before['toSet']} to set, {before['toClear']} to clear)."
        )
        if not apply:
            print("Dry run: nothing was changed. Run with --apply to write the codes.")
            return 0
        changed = await cross_portal_codes.reconcile_question_codes(db)
        after = await pending(db)
    finally:
        await disconnect_db()
    print(f"Changed {changed}; left to change: {after['toChange']}.")
    return 0 if after["toChange"] == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Give every questionnaire question its shared cross-portal code.")
    parser.add_argument("--apply", action="store_true", help="write the codes; without it, only count")
    args = parser.parse_args()
    return asyncio.run(run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
