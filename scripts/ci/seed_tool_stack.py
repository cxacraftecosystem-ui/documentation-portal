from __future__ import annotations

import asyncio
import json
import os
import sys
import uuid
from urllib.parse import urlsplit

HERE = os.getcwd()
sys.path.insert(0, HERE)


def _refuse_remote() -> None:
    host = (urlsplit(os.environ.get("DATABASE_URL", "")).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("seed_tool_stack.py writes; it runs only against a loopback CI database.")


async def main(out_path: str) -> None:
    _refuse_remote()
    import app  # noqa: F401
    from app.core.db import db
    from app.core.security import create_access_token

    t = uuid.uuid4().hex[:10]
    admin = f"uadmin{t}"
    craft = f"c{t}"
    artisans = {"Fahim": f"afahim{t}", "Moin Khan": f"amoin{t}"}
    await db.connect()
    try:
        await db.execute_raw(
            'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
            admin, f"{admin}@example.test", "Stack Admin", "ADMIN",
        )
        await db.execute_raw(
            'INSERT INTO "Craft" ("id", "name", "createdById", "updatedAt", "status") VALUES ($1, $2, $3, now(), \'ACTIVE\'::"CraftStatus")',
            craft, "Agra Marble Inlay", admin,
        )
        for name, aid in artisans.items():
            await db.execute_raw(
                'INSERT INTO "Artisan" ("id", "name", "place", "phone", "status", "createdById", "updatedAt", "submittedAt", "craftId") '
                "VALUES ($1, $2, 'Agra', $3, 'APPROVED', $4, now(), now(), $5)",
                aid, name, f"98{abs(hash(aid)) % 10**8:08d}", admin, craft,
            )
    finally:
        await db.disconnect()
    payload = {
        "token": create_access_token(admin),
        "craftId": craft,
        "artisanIds": [artisans["Fahim"], artisans["Moin Khan"]],
    }
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle)
    print(f"Seeded craft {craft} and {len(artisans)} artisans.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: python ../scripts/ci/seed_tool_stack.py OUT.json")
    asyncio.run(main(sys.argv[1]))
