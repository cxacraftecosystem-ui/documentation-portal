import asyncio
import os

from app.core.db import connect_db, db, disconnect_db
from app.core.security import hash_password

MASTER_ADMIN_EMAIL = os.getenv("MASTER_ADMIN_EMAIL", "ankits1802@gmail.com").lower()
MASTER_ADMIN_NAME = os.getenv("MASTER_ADMIN_NAME", "Ankit Kumar")


async def upsert_admin(email: str, name: str, password: str, role: str) -> None:
    existing = await db.user.find_unique(where={"email": email})
    if existing:
        await db.user.update(
            where={"email": email},
            data={"name": name, "passwordHash": hash_password(password), "role": role},
        )
        print(f"Updated {role.lower()} user: {email}")
    else:
        await db.user.create(
            data={
                "email": email,
                "name": name,
                "passwordHash": hash_password(password),
                "role": role,
                "authProvider": "LOCAL",
            }
        )
        print(f"Created {role.lower()} user: {email}")


async def main() -> None:
    await connect_db()
    try:
        email = os.getenv("ADMIN_EMAIL", "admin@example.com").lower()
        name = os.getenv("ADMIN_NAME", "Repository Admin")
        password = os.getenv("ADMIN_PASSWORD")
        if not password:
            raise RuntimeError("ADMIN_PASSWORD must be set in .env before seeding local admin accounts")
        await upsert_admin(MASTER_ADMIN_EMAIL, MASTER_ADMIN_NAME, password, "MASTER_ADMIN")
        if email != MASTER_ADMIN_EMAIL:
            await upsert_admin(email, name, password, "ADMIN")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
