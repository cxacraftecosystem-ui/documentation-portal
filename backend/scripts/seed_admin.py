import asyncio
import os

from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.security import hash_password


async def upsert_admin(email: str, name: str, password: str, role: str) -> None:
    is_master_admin = role == "MASTER_ADMIN"
    existing = await db.user.find_unique(where={"email": email})
    if existing:
        await db.user.update(
            where={"email": email},
            data={
                "name": name,
                "passwordHash": hash_password(password),
                "role": role,
                "canManageQuestionnaire": True if is_master_admin else existing.canManageQuestionnaire,
            },
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
                "canManageQuestionnaire": is_master_admin,
            }
        )
        print(f"Created {role.lower()} user: {email}")


async def main() -> None:
    settings = get_settings()
    master_admin_email = settings.master_admin_email.lower()
    master_admin_name = settings.master_admin_name
    await connect_db()
    try:
        email = os.getenv("ADMIN_EMAIL", "admin@example.com").lower()
        name = os.getenv("ADMIN_NAME", "Repository Admin")
        password = os.getenv("ADMIN_PASSWORD")
        if not password:
            raise RuntimeError("ADMIN_PASSWORD must be set in .env before seeding local admin accounts")
        await upsert_admin(master_admin_email, master_admin_name, password, "MASTER_ADMIN")
        if email != master_admin_email:
            await upsert_admin(email, name, password, "ADMIN")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
